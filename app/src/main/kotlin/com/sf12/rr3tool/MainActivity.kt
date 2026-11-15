package com.sf12.rr3tool

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val requestGameData = 1101
    private val requestSaveFolder = 1102

    private lateinit var gameDataValue: TextView
    private lateinit var saveFolderValue: TextView
    private lateinit var engineValue: TextView

    private val preferences by lazy {
        getSharedPreferences("rr3_tool", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(9, 17, 29)
        window.navigationBarColor = Color.rgb(9, 17, 29)
        window.decorView.systemUiVisibility = 0

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(9, 17, 29))
            isFillViewport = true
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(30))
        }
        scroll.addView(page)
        setContentView(scroll)

        val eyebrow = text("ANDROID EDITION  •  REAL RACING 3", 11, 0xFF75B9F1.toInt()).apply {
            letterSpacing = 0.10f
        }
        page.addView(eyebrow, matchWrap())

        page.addView(
            text("RR3 Tool", 32, Color.WHITE, bold = true),
            matchWrap(top = 7)
        )
        page.addView(
            text(
                "A native Android port of the Monkey Tool RR3 workflows.",
                14,
                0xFF9BAFC5.toInt()
            ),
            matchWrap(top = 5, bottom = 22)
        )

        addSection(page, "GAME DATA")
        val gameCard = card(page)
        gameCard.addView(text("Real Racing 3 files", 17, Color.WHITE, bold = true), matchWrap())
        gameCard.addView(
            text(
                "Choose the game-data folder that contains the RR3 .depot files.",
                13,
                0xFF9BAFC5.toInt()
            ),
            matchWrap(top = 6)
        )
        gameDataValue = text("No folder selected", 12, 0xFFB7C8D9.toInt())
        gameCard.addView(gameDataValue, matchWrap(top = 12))
        gameCard.addView(
            actionButton("Choose game-data folder") {
                chooseFolder(requestGameData)
            },
            matchWrap(top = 12)
        )

        addSection(page, "SAVE EDITOR")
        val saveCard = card(page)
        saveCard.addView(text("RR3 save location", 17, Color.WHITE, bold = true), matchWrap())
        saveCard.addView(
            text(
                "Select the folder containing the game's doc save files. The Android adapter will use this persisted folder permission.",
                13,
                0xFF9BAFC5.toInt()
            ),
            matchWrap(top = 6)
        )
        saveFolderValue = text("No folder selected", 12, 0xFFB7C8D9.toInt())
        saveCard.addView(saveFolderValue, matchWrap(top = 12))
        saveCard.addView(
            actionButton("Choose save folder") {
                chooseFolder(requestSaveFolder)
            },
            matchWrap(top = 12)
        )

        addSection(page, "NATIVE ENGINE")
        val engineCard = card(page)
        engineValue = text("Checking native engine…", 13, 0xFFB7C8D9.toInt())
        engineCard.addView(engineValue, matchWrap())
        engineCard.addView(
            text(
                "3D model viewing and texture extraction/conversion are intentionally excluded from this port.",
                12,
                0xFF7F93A9.toInt()
            ),
            matchWrap(top = 12)
        )

        addSection(page, "PORT STATUS")
        val statusCard = card(page)
        statusCard.addView(
            text("Foundation build", 16, Color.WHITE, bold = true),
            matchWrap()
        )
        statusCard.addView(
            text(
                "The Android shell, native C++ build and folder-permission selection are in place. RR3 file indexing and save operations are the next integration layer.",
                13,
                0xFF9BAFC5.toInt()
            ),
            matchWrap(top = 6)
        )

        val footer = text("Unofficial fan project  •  Not affiliated with EA", 11, 0xFF61758B.toInt()).apply {
            gravity = Gravity.CENTER
        }
        page.addView(footer, matchWrap(top = 24))

        restoreSelection(gameDataValue, "game_data_tree", "Game-data folder")
        restoreSelection(saveFolderValue, "save_tree", "Save folder")
        updateEngineStatus()
    }

    private fun addSection(parent: LinearLayout, title: String) {
        parent.addView(
            text(title, 11, 0xFF75B9F1.toInt(), bold = true),
            matchWrap(top = 18, bottom = 8)
        )
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(0xFF121F30.toInt(), dp(16).toFloat())
        }
        parent.addView(box, matchWrap())
        return box
    }

    private fun actionButton(label: String, action: () -> Unit): TextView {
        return text(label, 14, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = rounded(0xFF1766A8.toInt(), dp(12).toFloat())
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
    }

    private fun text(
        value: String,
        size: Int,
        color: Int,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun matchWrap(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            if (top != 0 || bottom != 0) {
                topMargin = dp(top)
                bottomMargin = dp(bottom)
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun chooseFolder(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        startActivityForResult(intent, requestCode)
    }

    @Deprecated("Using the document-tree activity result to persist a folder grant")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return

        val uri = data.data ?: return
        val accessFlags = data.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        try {
            contentResolver.takePersistableUriPermission(uri, accessFlags)
        } catch (_: SecurityException) {
            // Some document providers return a temporary grant only.
        }

        when (requestCode) {
            requestGameData -> {
                preferences.edit().putString("game_data_tree", uri.toString()).apply()
                gameDataValue.text = displayFolder(uri)
            }
            requestSaveFolder -> {
                preferences.edit().putString("save_tree", uri.toString()).apply()
                saveFolderValue.text = displayFolder(uri)
            }
        }
    }

    private fun restoreSelection(target: TextView, key: String, fallback: String) {
        val saved = preferences.getString(key, null)
        if (saved.isNullOrBlank()) {
            target.text = "No folder selected"
            return
        }

        val uri = runCatching { Uri.parse(saved) }.getOrNull()
        target.text = if (uri == null) "Saved folder permission unavailable" else displayFolder(uri)
    }

    private fun displayFolder(uri: Uri): String {
        val documentId = runCatching {
            DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull()
        val relative = documentId?.substringAfter(':', documentId)?.trim('/')
        return if (relative.isNullOrBlank()) {
            "Selected folder"
        } else {
            relative.substringAfterLast('/')
        }
    }

    private fun updateEngineStatus() {
        val status = runCatching { NativeBridge.nativeStatus() }
            .getOrElse { "Native engine failed to load: " + (it.message ?: "unknown error") }
        engineValue.text = status
    }
}
