package com.sf12.rr3tool

internal object NativeBridge {
    init {
        System.loadLibrary("monkeytool_android")
    }

    external fun nativeStatus(): String

    external fun nativeOpenDataAsText(fileName: String, original: ByteArray): String?

    external fun nativeBuildEditedData(fileName: String, editedText: String, original: ByteArray): ByteArray?

    external fun nativeGetDataEditError(): String

    external fun nativeCreateRr3TransformStream(): Long

    external fun nativeTransformRr3Chunk(handle: Long, chunk: ByteArray, length: Int): Boolean

    external fun nativeFreeRr3TransformStream(handle: Long)

    external fun nativeTransformRr3Data(input: ByteArray): ByteArray?

    external fun nativeGetRr3TransformError(): String

    external fun nativeInspectRr3Car(input: ByteArray): String

    external fun nativeSetRr3CarField(input: ByteArray, key: String, value: String): ByteArray?

    external fun nativeGetRr3CarError(): String

    external fun nativeInspectRr3Career(input: ByteArray): String

    external fun nativeInspectRr3CareerFile(plainPath: String): String

    external fun nativeInspectRr3Track(input: ByteArray): String

    external fun nativeUnwrapRr3Asset(fileName: String, input: ByteArray): ByteArray?

    external fun nativeConvertRr3Audio(fileName: String, input: ByteArray): ByteArray?

    external fun nativeGetRr3AudioError(): String

    external fun nativeTransformRr3Text(input: ByteArray): ByteArray?

    external fun nativeRr3TextIsPlain(input: ByteArray): Boolean

    external fun nativeInspectRr3Save(savePath: String, keysPath: String): String

    external fun nativeCalibrateRr3Save(
        savePath: String,
        keysPath: String,
        hiddenIndex: Int,
        displayedValue: String
    ): String

    external fun nativeSetRr3SaveField(
        savePath: String,
        keysPath: String,
        fieldName: String,
        value: String
    ): String
}
