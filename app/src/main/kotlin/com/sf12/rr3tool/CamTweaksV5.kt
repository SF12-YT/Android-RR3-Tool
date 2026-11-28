package com.sf12.rr3tool

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lossless structural decoder for RR3 camTweaks.dat version 5.
 *
 * This class deliberately keeps the original record order and all bytes after
 * the known transform array. Duplicate (carId, cameraModeId) pairs are valid
 * input data and must not be collapsed into a map.
 */
internal data class CamTweaksV5(
    val version: Int,
    val modes: List<ModeDefinition>,
    val reservedHeaderValue: Int,
    val transforms: List<CarTransform>,
    val trailingData: ByteArray,
    val transformCountOffset: Int,
    val transformDataOffset: Int,
    val knownDataEndOffset: Int,
    val fileSize: Int
) {
    data class ModeDefinition(
        val cameraModeId: Int,
        val rawParameters: List<Float>,
        val recordOffset: Int
    )

    data class CarTransform(
        val carId: Int,
        val position: List<Float>,
        /** Quaternion components in the file's stored X, Y, Z, W order. */
        val rotation: List<Float>,
        /** Stored as an unsigned 32-bit value in the file. */
        val cameraModeId: Long,
        val recordOffset: Int,
        /** Zero-based position in the original transform array. */
        val recordIndex: Int
    ) {
        val quaternionLength: Double
            get() = kotlin.math.sqrt(rotation.sumOf { it.toDouble() * it.toDouble() })
    }

    companion object {
        private const val EXPECTED_VERSION = 5
        private const val MODE_RECORD_SIZE = 28
        private const val TRANSFORM_RECORD_SIZE = 36
        private const val MAX_MODE_COUNT = 64
        private const val MAX_TRANSFORM_COUNT = 1_000_000

        fun parse(bytes: ByteArray): CamTweaksV5 {
            require(bytes.size >= 12) {
                "camTweaks.dat is too short to contain its RRCT header."
            }
            require(
                bytes[0] == 'R'.code.toByte() &&
                    bytes[1] == 'R'.code.toByte() &&
                    bytes[2] == 'C'.code.toByte() &&
                    bytes[3] == 'T'.code.toByte()
            ) {
                "This file does not have the expected RRCT camTweaks header."
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val version = buffer.getInt(4)
            require(version == EXPECTED_VERSION) {
                "Unsupported camTweaks version $version. Expected version $EXPECTED_VERSION."
            }

            val modeCount = buffer.getInt(8)
            require(modeCount in 1..MAX_MODE_COUNT) {
                "The camera mode count ($modeCount) is outside the supported range."
            }

            val modeTableEndLong = 12L + modeCount.toLong() * MODE_RECORD_SIZE
            require(modeTableEndLong <= bytes.size.toLong()) {
                "The camera mode table extends beyond the file."
            }
            val modeTableEnd = modeTableEndLong.toInt()
            require(modeTableEndLong + 8L <= bytes.size.toLong()) {
                "The car-transform array header is missing or truncated."
            }

            val modes = ArrayList<ModeDefinition>(modeCount)
            for (index in 0 until modeCount) {
                val offset = 12 + index * MODE_RECORD_SIZE
                val cameraModeId = buffer.getInt(offset)
                val parameters = List(6) { field ->
                    buffer.getFloat(offset + 4 + field * 4).also { value ->
                        require(value.isFinite()) {
                            "Camera mode $cameraModeId has a non-finite parameter in slot ${field + 1}."
                        }
                    }
                }
                modes.add(ModeDefinition(cameraModeId, parameters, offset))
            }

            val transformCountOffset = modeTableEnd
            val transformCount = buffer.getInt(transformCountOffset)
            require(transformCount in 0..MAX_TRANSFORM_COUNT) {
                "The car-transform count ($transformCount) is outside the supported range."
            }
            val reservedHeaderValue = buffer.getInt(transformCountOffset + 4)
            val transformDataOffset = transformCountOffset + 8
            val knownDataEndLong =
                transformDataOffset.toLong() + transformCount.toLong() * TRANSFORM_RECORD_SIZE
            require(knownDataEndLong <= bytes.size.toLong()) {
                "The car-transform array is truncated: expected $transformCount records of $TRANSFORM_RECORD_SIZE bytes."
            }
            val knownDataEndOffset = knownDataEndLong.toInt()

            val transforms = ArrayList<CarTransform>(transformCount)
            for (index in 0 until transformCount) {
                val offset = transformDataOffset + index * TRANSFORM_RECORD_SIZE
                val carId = buffer.getInt(offset)
                val position = List(3) { axis ->
                    buffer.getFloat(offset + 4 + axis * 4).also { value ->
                        require(value.isFinite()) {
                            "Transform record $index (car $carId) has a non-finite position component."
                        }
                    }
                }
                val rotation = List(4) { component ->
                    buffer.getFloat(offset + 16 + component * 4).also { value ->
                        require(value.isFinite()) {
                            "Transform record $index (car $carId) has a non-finite quaternion component."
                        }
                    }
                }
                val cameraModeId = buffer.getInt(offset + 32).toLong() and 0xffff_ffffL
                transforms.add(
                    CarTransform(
                        carId = carId,
                        position = position,
                        rotation = rotation,
                        cameraModeId = cameraModeId,
                        recordOffset = offset,
                        recordIndex = index
                    )
                )
            }

            return CamTweaksV5(
                version = version,
                modes = modes,
                reservedHeaderValue = reservedHeaderValue,
                transforms = transforms,
                trailingData = bytes.copyOfRange(knownDataEndOffset, bytes.size),
                transformCountOffset = transformCountOffset,
                transformDataOffset = transformDataOffset,
                knownDataEndOffset = knownDataEndOffset,
                fileSize = bytes.size
            )
        }
    }
}
