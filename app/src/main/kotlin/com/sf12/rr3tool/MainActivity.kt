package com.sf12.rr3tool

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : Activity() {
    private val requestGameData = 1101
    private val requestSaveFolder = 1102

    private lateinit var gameDataValue: TextView
    private lateinit var saveFolderValue: TextView
    private lateinit var engineValue: TextView
    private lateinit var saveStatus: TextView
    private lateinit var saveFieldsContainer: LinearLayout
    private lateinit var loadSaveButton: TextView

    private val preferences by lazy {
        getSharedPreferences("rr3_tool", MODE_PRIVATE)
    }

    private val keysFile: File
        get() = File(filesDir, "rr3_keys.txt")

    private val stageDir: File
        get() = File(cacheDir, "rr3_save_work")

    private val stagedSave: File
        get() = File(stageDir, "character.2.dat")

    private val stagedTempSave: File
        get() = File(stageDir, "TempSaveGame.dat")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(9, 17, 29)
        window.navigationBarColor = Color.rgb(9, 17, 29)

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

        page.addView(
            text("ANDROID EDITION  •  REAL RACING 3", 11, 0xFF75B9F1.toInt()).apply {
                letterSpacing = 0.10f
            },
            matchWrap()
        )
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
                "Choose the game-data folder containing the RR3 .depot files.",
                13,
                0xFF9BAFC5.toInt()
            ),
            matchWrap(top = 6)
        )
        gameDataValue = text("No folder selected", 12, 0xFFB7C8D9.toInt())
        gameCard.addView(gameDataValue, matchWrap(top = 12))
        gameCard.addView(
            actionButton("Choose game-data folder") { chooseFolder(requestGameData) },
            matchWrap(top = 12)
        )

        addSection(page, "SAVE EDITOR")
        val saveCard = card(page)
        saveCard.addView(text("RR3 save location", 17, Color.WHITE, bold = true), matchWrap())
        saveCard.addView(
            text(
                "Choose the folder containing character.2.dat. The original save is backed up before every write.",
                13,
                0xFF9BAFC5.toInt()
            ),
            matchWrap(top = 6)
        )
        saveFolderValue = text("No folder selected", 12, 0xFFB7C8D9.toInt())
        saveCard.addView(saveFolderValue, matchWrap(top = 12))
        saveCard.addView(
            actionButton("Choose save folder") { chooseFolder(requestSaveFolder) },
            matchWrap(top = 12)
        )
        loadSaveButton = actionButton("Load RR3 save") { loadRr3Save() }
        saveCard.addView(loadSaveButton, matchWrap(top = 10))
        saveStatus = text(
            "Select a save folder, then load the save.",
            12,
            0xFF9BAFC5.toInt()
        )
        saveCard.addView(saveStatus, matchWrap(top = 10))
        saveFieldsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        saveCard.addView(saveFieldsContainer, matchWrap(top = 12))

        addSection(page, "NATIVE ENGINE")
        val engineCard = card(page)
        engineValue = text("Checking native engine…", 13, 0xFFB7C8D9.toInt())
        engineCard.addView(engineValue, matchWrap())
        engineCard.addView(
            text(
                "3D model viewing and texture extraction/conversion are intentionally excluded.",
                12,
                0xFF7F93A9.toInt()
            ),
            matchWrap(top = 12)
        )

        addSection(page, "PORT STATUS")
        val statusCard = card(page)
        statusCard.addView(
            text("Android foundation", 16, Color.WHITE, bold = true),
            matchWrap()
        )
        statusCard.addView(
            text(
                "The app uses the upstream C++ save parser and RR3 calibration routines. Archive browsing and the other RR3 data editors are still being integrated.",
                13,
                0xFF9BAFC5.toInt()
            ),
            matchWrap(top = 6)
        )

        page.addView(
            text(
                "Unofficial fan project  •  Not affiliated with EA",
                11,
                0xFF61758B.toInt()
            ).apply { gravity = Gravity.CENTER },
            matchWrap(top = 24)
        )

        restoreSelection(gameDataValue, "game_data_tree")
        restoreSelection(saveFolderValue, "save_tree")
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

    private fun editBox(hintText: String, value: String): EditText {
        return EditText(this).apply {
            setSingleLine(true)
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF6F849B.toInt())
            hint = hintText
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(0xFF0B1725.toInt(), dp(10).toFloat())
            setText(value)
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
                saveStatus.text = "Folder selected. Tap Load RR3 save."
                saveFieldsContainer.removeAllViews()
            }
        }
    }

    private fun restoreSelection(target: TextView, key: String) {
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
        return if (relative.isNullOrBlank()) "Selected folder" else relative.substringAfterLast('/')
    }

    private fun updateEngineStatus() {
        engineValue.text = runCatching { NativeBridge.nativeStatus() }
            .getOrElse { "Native engine failed to load: " + (it.message ?: "unknown error") }
    }

    private fun runBackground(task: () -> Unit) {
        Thread {
            try {
                task()
            } catch (e: Exception) {
                runOnUiThread {
                    saveStatus.text = e.message ?: "Operation failed."
                }
            }
        }.start()
    }

    private fun loadRr3Save() {
        val stored = preferences.getString("save_tree", null)
        if (stored.isNullOrBlank()) {
            saveStatus.text = "Choose the save folder first."
            return
        }

        saveStatus.text = "Reading RR3 save…"
        loadSaveButton.isEnabled = false
        runBackground {
            val treeUri = Uri.parse(stored)
            stageDir.mkdirs()
            val saveUri = findChildDocument(treeUri, "character.2.dat")
                ?: throw IllegalStateException("character.2.dat was not found in the selected folder.")
            copyUriToFile(saveUri, stagedSave)

            val tempUri = findChildDocument(treeUri, "TempSaveGame.dat")
            if (tempUri != null) copyUriToFile(tempUri, stagedTempSave) else stagedTempSave.delete()

            val result = NativeBridge.nativeInspectRr3Save(stagedSave.absolutePath, keysFile.absolutePath)
            val json = JSONObject(result)
            if (!json.optBoolean("ok")) {
                throw IllegalStateException(json.optString("error", "Could not read the RR3 save."))
            }

            runOnUiThread {
                loadSaveButton.isEnabled = true
                saveStatus.text = "Save loaded. Calibrate hidden values once using the values shown in-game."
                renderHiddenFields(json)
            }
        }
    }

    private fun findChildDocument(treeUri: Uri, displayName: String): Uri? {
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )

        val cursor: Cursor = contentResolver.query(childrenUri, projection, null, null, null)
            ?: return null
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (it.moveToNext()) {
                if (it.getString(nameColumn).equals(displayName, ignoreCase = true)) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, it.getString(idColumn))
                }
            }
        }
        return null
    }

    private fun copyUriToFile(uri: Uri, target: File) {
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not read $uri")
        input.use { source ->
            FileOutputStream(target).use { destination -> source.copyTo(destination) }
        }
    }

    private fun copyFileToUri(source: File, target: Uri) {
        val output = contentResolver.openOutputStream(target, "wt")
            ?: throw IllegalStateException("Could not write to $target")
        output.use { destination ->
            FileInputStream(source).use { input -> input.copyTo(destination) }
        }
    }

    private fun renderHiddenFields(payload: JSONObject) {
        saveFieldsContainer.removeAllViews()
        val hidden = payload.optJSONArray("hidden") ?: return

        for (index in 0 until hidden.length()) {
            val item = hidden.optJSONObject(index) ?: continue
            val label = item.optString("label")
            val field = item.optString("field")
            val calibrated = item.optBoolean("calibrated")
            val currentValue = if (calibrated) item.optString("value") else ""
            val fieldCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rounded(0xFF0B1725.toInt(), dp(12).toFloat())
            }
            saveFieldsContainer.addView(fieldCard, matchWrap(bottom = 9))
            fieldCard.addView(
                text(label, 16, Color.WHITE, bold = true),
                matchWrap()
            )
            fieldCard.addView(
                text(
                    if (calibrated) "Calibration saved on this device" else "Not calibrated yet",
                    12,
                    if (calibrated) 0xFF83C6A0.toInt() else 0xFFE6B56C.toInt()
                ),
                matchWrap(top = 4, bottom = 8)
            )
            val valueInput = editBox(
                if (calibrated) "New value" else "Enter the value currently shown in-game",
                currentValue
            )
            fieldCard.addView(valueInput, matchWrap(bottom = 9))

            fieldCard.addView(
                actionButton(if (calibrated) "Save $label" else "Calibrate $label") {
                    val entered = valueInput.text.toString().trim()
                    if (entered.isEmpty()) {
                        saveStatus.text = "Enter a value first."
                    } else if (calibrated) {
                        updateHiddenField(field, entered)
                    } else {
                        calibrateHiddenField(index, entered)
                    }
                },
                matchWrap()
            )
        }
    }

    private fun calibrateHiddenField(index: Int, shownValue: String) {
        val stored = preferences.getString("save_tree", null) ?: return
        saveStatus.text = "Calibrating…"
        runBackground {
            val result = JSONObject(
                NativeBridge.nativeCalibrateRr3Save(
                    stagedSave.absolutePath,
                    keysFile.absolutePath,
                    index,
                    shownValue
                )
            )
            if (!result.optBoolean("ok")) {
                throw IllegalStateException(result.optString("error", "Calibration failed."))
            }
            val inspected = JSONObject(
                NativeBridge.nativeInspectRr3Save(stagedSave.absolutePath, keysFile.absolutePath)
            )
            runOnUiThread {
                saveStatus.text = "Calibration saved privately on this device."
                renderHiddenFields(inspected)
            }
        }
    }

    private fun updateHiddenField(field: String, value: String) {
        val stored = preferences.getString("save_tree", null) ?: return
        saveStatus.text = "Updating save…"
        runBackground {
            val result = JSONObject(
                NativeBridge.nativeSetRr3SaveField(
                    stagedSave.absolutePath,
                    keysFile.absolutePath,
                    field,
                    value
                )
            )
            if (!result.optBoolean("ok")) {
                throw IllegalStateException(result.optString("error", "Could not update save."))
            }

            val treeUri = Uri.parse(stored)
            val saveUri = findChildDocument(treeUri, "character.2.dat")
                ?: throw IllegalStateException("The save file disappeared from the selected folder.")
            copyFileToUri(stagedSave, saveUri)

            if (stagedTempSave.exists()) {
                val tempUri = findChildDocument(treeUri, "TempSaveGame.dat")
                    ?: throw IllegalStateException("TempSaveGame.dat is missing; the main save was written, but the secondary copy could not be updated.")
                copyFileToUri(stagedTempSave, tempUri)
            }

            val inspected = JSONObject(
                NativeBridge.nativeInspectRr3Save(stagedSave.absolutePath, keysFile.absolutePath)
            )
            runOnUiThread {
                saveStatus.text = "Save written and verified. A backup was created in the app's temporary working folder."
                renderHiddenFields(inspected)
            }
        }
    }
}
