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

    external fun nativeInspectRr3Liveries(input: ByteArray): String

    external fun nativeInspectRr3LiveryStringTable(input: ByteArray): String

    external fun nativeInspectRr3PaintLabels(
        customPaints: ByteArray,
        customPaintPacks: ByteArray,
        gameText: ByteArray,
        englishText: ByteArray
    ): String

    external fun nativeUpdateRr3PaintLabels(
        gameText: ByteArray,
        englishText: ByteArray,
        keys: Array<String>,
        values: Array<String>
    ): String

    external fun nativeUpdateRr3Livery(
        input: ByteArray,
        liveryIndex: Int,
        display: String,
        swatch: String,
        logo: String,
        arms: String,
        driver: String,
        flag: Int,
        red: Int,
        green: Int,
        blue: Int,
        meshes: IntArray,
        materials: IntArray
    ): ByteArray?

    external fun nativeReplaceRr3Texture(
        assetPath: String,
        original: ByteArray,
        image: ByteArray
    ): ByteArray?

    external fun nativeUpdateRr3AssetList(
        assetList: ByteArray,
        assetPath: String,
        updatedAsset: ByteArray
    ): ByteArray?

    external fun nativeAddRr3Livery(
        carFolder: String,
        assetPaths: Array<String>,
        sourcePaths: Array<String>,
        outputDirectory: String,
        liveryName: String,
        templateIndex: Int,
        red: Int,
        green: Int,
        blue: Int
    ): String

    external fun nativeInspectRr3Career(input: ByteArray): String

    external fun nativeInspectRr3CareerFile(plainPath: String): String

    external fun nativeExportRr3CareerText(plainPath: String, textPath: String): String

    external fun nativeBuildRr3CareerText(plainPath: String, textPath: String, outputPath: String): String

    external fun nativeInspectRr3Track(input: ByteArray): String

    external fun nativeUnwrapRr3Asset(fileName: String, input: ByteArray): ByteArray?

    external fun nativeConvertRr3Audio(fileName: String, input: ByteArray): ByteArray?

    external fun nativeInspectRr3AudioBank(input: ByteArray): String

    external fun nativeInspectRr3Points(input: ByteArray): String

    /** Lists installed cars from RR3's cardesc and appearance registries. */
    external fun nativeInspectRr3CarCatalogue(
        assetPaths: Array<String>,
        assetData: Array<ByteArray>
    ): String

    /** Creates a native Monkey Tool model/texture session for the software viewer. */
    external fun nativeCreateRr3Viewer(
        modelPath: String,
        dataFolder: String,
        model: ByteArray,
        assetPaths: Array<String>,
        assetData: Array<ByteArray>
    ): String

    /** Renders a frame with Monkey Tool's own camera and software rasteriser. */
    external fun nativeRenderRr3ViewerFrame(
        handle: Long,
        width: Int,
        height: Int,
        yaw: Float,
        pitch: Float,
        zoom: Float,
        preview: Boolean
    ): ByteArray?

    /** Creates GPU buffers and uploads model textures on the current OpenGL ES thread. */
    external fun nativeInitRr3ViewerGl(handle: Long): String

    /** Draws the prepared RR3 model directly into the current OpenGL framebuffer. */
    external fun nativeDrawRr3ViewerGl(
        handle: Long,
        width: Int,
        height: Int,
        yaw: Float,
        pitch: Float,
        zoom: Float
    )

    /** Releases GL objects while the viewer's EGL context is current. */
    external fun nativeReleaseRr3ViewerGl(handle: Long)

    external fun nativeFreeRr3Viewer(handle: Long)

    external fun nativeGetRr3ViewerError(): String

    external fun nativeUpdateRr3Points(
        input: ByteArray,
        names: Array<String>,
        positions: FloatArray,
        bases: FloatArray
    ): ByteArray?

    external fun nativeWrapRr3PointsZ(input: ByteArray): ByteArray?

    external fun nativeGetRr3PointsError(): String

    external fun nativeGetRr3AudioError(): String

    external fun nativeTransformRr3Text(input: ByteArray): ByteArray?

    external fun nativeRr3TextIsPlain(input: ByteArray): Boolean

    /** Detects a plain or encrypted RR3 text file from a small prefix only. */
    external fun nativeRr3TextFileIsPlain(path: String): Boolean

    /** Streams the RR3 text cipher from one file to another without loading it into a ByteArray. */
    external fun nativeTransformRr3TextFile(inputPath: String, outputPath: String): Long

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
