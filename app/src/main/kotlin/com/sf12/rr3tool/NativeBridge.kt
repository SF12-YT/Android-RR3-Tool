package com.sf12.rr3tool

internal object NativeBridge {
    init {
        System.loadLibrary("monkeytool_android")
    }

    external fun nativeStatus(): String

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
