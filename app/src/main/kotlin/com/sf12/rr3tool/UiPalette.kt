package com.sf12.rr3tool

import android.graphics.Color

/**
 * RR3 Tool's black / brilliant-red palette with restrained navy-blue accents.
 */
internal object UiPalette {
    val background: Int = Color.rgb(7, 9, 13)          // #07090D
    val surface: Int = Color.rgb(18, 24, 36)            // #121824
    val primary: Int = Color.rgb(245, 27, 63)           // #F51B3F brilliant red
    val accent: Int = Color.rgb(62, 91, 137)            // #3E5B89 navy blue
    val danger: Int = Color.rgb(181, 18, 45)             // #B5122D
    val burgundy: Int = Color.rgb(16, 27, 44)            // #101B2C

    val navy: Int = Color.rgb(20, 35, 58)                // #14233A
    val text: Int = Color.rgb(246, 247, 250)
    val textMuted: Int = Color.rgb(203, 208, 218)
    val textSecondary: Int = Color.rgb(157, 168, 185)
    val textSubtle: Int = Color.rgb(119, 132, 153)
    val textHint: Int = Color.rgb(88, 101, 122)

    val surfaceOverlay: Int = 0xE6121824.toInt()
    val surfaceOverlayLight: Int = 0xD9121824.toInt()
    val ripple: Int = 0x55F51B3F
    val rippleMask: Int = Color.WHITE
}
