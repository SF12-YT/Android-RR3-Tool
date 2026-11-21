package com.sf12.rr3tool

import android.graphics.Color

/**
 * Shared RR3 Tool palette. The six core colours are also mirrored in values/colors.xml
 * for Android-managed surfaces such as system bars, dialogs and text selection.
 */
internal object UiPalette {
    val background: Int = Color.rgb(24, 26, 47)       // #181A2F
    val surface: Int = Color.rgb(36, 46, 73)          // #242E49
    val primary: Int = Color.rgb(52, 113, 92)         // #34715C
    val accent: Int = Color.rgb(253, 164, 129)        // #FDA481
    val danger: Int = Color.rgb(132, 24, 45)          // #84182D
    val burgundy: Int = Color.rgb(84, 22, 43)          // #54162B

    val text: Int = Color.rgb(247, 243, 240)
    val textMuted: Int = Color.rgb(200, 198, 211)
    val textSecondary: Int = Color.rgb(176, 178, 197)
    val textSubtle: Int = Color.rgb(151, 153, 174)
    val textHint: Int = Color.rgb(124, 128, 152)

    val surfaceOverlay: Int = 0xE6242E49.toInt()
    val surfaceOverlayLight: Int = 0xD9242E49.toInt()
    val ripple: Int = 0x44FDA481.toInt()
    val rippleMask: Int = Color.WHITE
}
