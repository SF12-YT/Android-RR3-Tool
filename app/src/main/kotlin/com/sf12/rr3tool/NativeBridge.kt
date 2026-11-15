package com.sf12.rr3tool

internal object NativeBridge {
    init {
        System.loadLibrary("monkeytool_android")
    }

    external fun nativeStatus(): String
}
