package com.sf12.rr3tool

internal object NativeBridge {
    init {
        System.loadLibrary("monkeytool_android")
    }

    external fun nativeStatus(): String

    external fun nativeOpenDataAsText(fileName: String, original: ByteArray): String?

    external fun nativeBuildEditedData(fileName: String, editedText: String, original: ByteArray): ByteArray?

    external fun nativeGetDataEditError(): String

    external fun nativeTransformRr3Data(input: ByteArray): ByteArray?

    external fun nativeUnwrapRr3Asset(fileName: String, input: ByteArray): ByteArray?

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
