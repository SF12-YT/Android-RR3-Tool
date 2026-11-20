package com.sf12.rr3tool

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.FileOutputStream
import java.io.BufferedReader
import java.io.Closeable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {
    private data class CareerTextPage(val label: String, val text: String)

    private class GameDataTreeNode(
        val name: String,
        val relativePath: String,
        val file: GameDataFile? = null
    ) {
        val children = linkedMapOf<String, GameDataTreeNode>()
    }

    private data class PendingLiverySwatchEdit(
        val liveryEntry: GameDataFile,
        val carFolder: String,
        val liveryIndex: Int,
        val swatchPath: String
    )

    private data class PreparedBinaryDataEditorFile(
        val formatName: String,
        val original: ByteArray,
        val editableText: String,
        val reencodeNct: Boolean
    )

    private data class PaintLabelField(
        val key: String,
        val label: String,
        val value: String
    )

    private data class EditableRr3Hardpoint(
        val name: String,
        var x: Float,
        var y: Float,
        var z: Float,
        val hinge: Boolean,
        var basis: FloatArray
    )

    /**
     * Reads the editable career text file in small record groups. The original
     * text remains on disk; only sections requested by the scroll view are
     * turned into EditTexts. Any sections the user never scrolls to are copied
     * straight from the source file when building the output.
     */
    private class CareerTextPager(private val sourceFile: File, private val pageSize: Int = 70) : Closeable {
        private val reader: BufferedReader = sourceFile.bufferedReader(Charsets.UTF_8)
        private val header = StringBuilder()
        private var pendingLine: String? = null
        private var reachedEof = false
        private val counts = mutableMapOf<String, Int>()
        private var loadedRowCount = 0

        init {
            while (true) {
                val line = reader.readLine()
                if (line == null) {
                    reachedEof = true
                    break
                }
                if (line.isBlank() || line.startsWith("#")) {
                    header.append(line).append('\n')
                } else {
                    pendingLine = line
                    break
                }
            }
        }

        @Synchronized
        fun readNextPage(): CareerTextPage? {
            val first = takeNextRecord() ?: return null
            val type = first.substringBefore('\t')
            val rows = ArrayList<String>(pageSize)
            rows.add(first)

            while (rows.size < pageSize) {
                val next = takeNextRecord() ?: break
                if (next.substringBefore('\t') != type) {
                    pendingLine = next
                    break
                }
                rows.add(next)
            }

            loadedRowCount += rows.size
            val priorCount = counts[type] ?: 0
            counts[type] = priorCount + rows.size
            val firstIndex = if (type == "EVENT") priorCount + 1
                else first.split('\t').getOrNull(1)?.toIntOrNull()?.plus(1) ?: priorCount + 1
            val lastIndex = if (type == "EVENT") priorCount + rows.size
                else rows.last().split('\t').getOrNull(1)?.toIntOrNull()?.plus(1)
                    ?: priorCount + rows.size

            val sectionName = when (type) {
                "SERIES" -> "SERIES"
                "TIER" -> "TIERS"
                "EVENT" -> "EVENTS"
                else -> type
            }
            return CareerTextPage(
                "$sectionName  ·  $firstIndex–$lastIndex",
                rows.joinToString("\n")
            )
        }

        private fun takeNextRecord(): String? {
            pendingLine?.let {
                pendingLine = null
                return it
            }
            if (reachedEof) return null
            while (true) {
                val line = reader.readLine()
                if (line == null) {
                    reachedEof = true
                    return null
                }
                if (line.isNotBlank() && !line.startsWith("#")) return line
            }
        }

        @Synchronized
        fun writeMerged(pageTexts: List<String>, output: File) {
            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(header.toString())
                if (header.isNotEmpty() && !header.endsWith("\n")) writer.newLine()

                for (page in pageTexts) {
                    val content = page.trimEnd('\r', '\n')
                    if (content.isNotEmpty()) {
                        writer.write(content)
                        writer.newLine()
                    }
                }

                // Use a fresh reader for the untouched tail. Keeping the
                // scrolling reader intact means a failed validation can be
                // corrected and rebuilt without reopening the source file.
                sourceFile.bufferedReader(Charsets.UTF_8).use { sourceReader ->
                    var skippedRows = 0
                    while (true) {
                        val line = sourceReader.readLine() ?: break
                        if (line.isBlank() || line.startsWith("#")) continue
                        if (skippedRows < loadedRowCount) {
                            skippedRows++
                        } else {
                            writer.write(line)
                            writer.newLine()
                        }
                    }
                }
            }
        }

        override fun close() {
            try {
                reader.close()
            } catch (_: Exception) {
            }
        }
    }
    private val requestGameData = 1101
    private val requestSaveFolder = 1102
    private val requestRr3TransformInput = 1103
    private val requestRr3TransformOutput = 1104
    private val requestEditableDataInput = 1105
    private val requestCarEditorInput = 1106
    private val requestCareerInspectorInput = 1107
    private val requestTrackInspectorInput = 1108
    private val requestManualLiveryInput = 1109
    private val requestLiverySwatchInput = 1110

    private lateinit var gameDataValue: TextView
    private lateinit var gameFilesStatus: TextView
    private lateinit var gameFilesSearch: EditText
    private lateinit var gameFilesContainer: LinearLayout
    private lateinit var indexGameFilesButton: TextView
    private lateinit var locateRootGameDataButton: TextView
    private var indexedGameFiles: List<GameDataFile> = emptyList()
    private val expandedGameDataFolders = mutableSetOf<String>()
    private lateinit var saveFolderValue: TextView
    private lateinit var rootSaveValue: TextView
    private lateinit var engineValue: TextView
    private lateinit var saveStatus: TextView
    private lateinit var dataStatus: TextView
    private lateinit var dataEditorStatus: TextView
    private lateinit var dataEditorWorkspace: LinearLayout
    private lateinit var rootScrollView: ScrollView
    private lateinit var editorScreen: LinearLayout
    private lateinit var editorScreenTitle: TextView
    private var closeDataEditorAction: (() -> Unit)? = null
    private var shizukuStorageService: IRr3StorageService? = null
    private var shizukuBindPending = false
    private var shizukuBound = false
    private var pendingShizukuAction: (() -> Unit)? = null
    private val shizukuPermissionRequestCode = 4812
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == shizukuPermissionRequestCode) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bindShizukuStorageService()
                } else {
                    runOnUiThread {
                        gameFilesStatus.text = "Shizuku permission was denied. Root access or a manually accessible folder is still available."
                    }
                    pendingShizukuAction = null
                }
            }
        }
    private val shizukuServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(this, Rr3StorageUserService::class.java)
        ).daemon(false).processNameSuffix("rr3storage").debuggable(false).version(1)
    }
    private val shizukuServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shizukuBindPending = false
            shizukuStorageService = service?.let { IRr3StorageService.Stub.asInterface(it) }
            shizukuBound = shizukuStorageService != null
            val ready = pendingShizukuAction
            pendingShizukuAction = null
            if (shizukuStorageService != null) ready?.invoke()
            else runOnUiThread {
                gameFilesStatus.text = "Shizuku connected, but the RR3 storage service could not be created."
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            shizukuStorageService = null
            shizukuBound = false
            shizukuBindPending = false
        }
    }
    private lateinit var carEditorStatus: TextView
    private lateinit var careerInspectorStatus: TextView
    private lateinit var trackInspectorStatus: TextView
    private lateinit var carFieldsContainer: LinearLayout
    private lateinit var careerDetailsContainer: LinearLayout
    private lateinit var trackDetailsContainer: LinearLayout
    private lateinit var saveFieldsContainer: LinearLayout
    private lateinit var advancedFieldsContainer: LinearLayout
    private lateinit var advancedSearch: EditText
    private lateinit var loadSaveButton: TextView
    private var currentSavePayload: JSONObject? = null
    private var pendingTransformedData: ByteArray? = null
    private var pendingTransformedFile: File? = null
    private var pendingTransformedSize: Long = 0L
    private var pendingTransformedName: String = "rr3-data.bin"
    private var pendingOutputForDataEditor: Boolean = false
    private var pendingOutputForBrowser: Boolean = false
    private var pendingOutputForCarEditor: Boolean = false
    private var selectTabAction: ((String) -> Unit)? = null
    private var currentCarOriginal: ByteArray? = null
    private var currentCarName: String = "car.bin.nct"
    private var currentCarOriginalFields: Map<String, String> = emptyMap()
    private val carFieldInputs = linkedMapOf<String, EditText>()
    private lateinit var liveryStatus: TextView
    private lateinit var liveryCarListContainer: LinearLayout
    private lateinit var liverySlotsContainer: LinearLayout
    private lateinit var liveryManualStatus: TextView
    private lateinit var liveryManualSlotsContainer: LinearLayout
    private lateinit var liveryPaintStatus: TextView
    private lateinit var liveryPaintContainer: LinearLayout
    private lateinit var liveryDecodedContainer: LinearLayout
    private val expandedLiveryYears = mutableSetOf<String>()
    private var selectedLiveryEntry: GameDataFile? = null
    private var selectedLiveryCarFolder: String = ""
    private var selectedLiveryBytes: ByteArray? = null
    private var selectedLiveryArray: org.json.JSONArray? = null
    private var selectedLiveryPayload: JSONObject? = null
    private var selectedLiverySourceName: String = ""
    private var selectedLiverySourceSize: Long = 0L
    private var pendingLiverySwatchEdit: PendingLiverySwatchEdit? = null

    private val preferences by lazy {
        getSharedPreferences("rr3_tool", MODE_PRIVATE)
    }

    private val keysFile: File
        get() = File(filesDir, "rr3_keys.txt")

    private val stageDir: File
        get() = File(filesDir, "rr3_save_work")

    private val stagedSave: File
        get() = File(stageDir, "character.2.dat")

    private val stagedTempSave: File
        get() = File(stageDir, "TempSaveGame.dat")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(9, 17, 29)
        window.navigationBarColor = Color.rgb(9, 17, 29)

        val appRoot = FrameLayout(this)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(9, 17, 29))
            isFillViewport = true
        }
        rootScrollView = scroll
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(30))
        }
        scroll.addView(page)
        appRoot.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        editorScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(9, 17, 29))
            visibility = View.GONE
        }
        val editorToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(18), dp(10))
            setBackgroundColor(0xFF0D1B2C.toInt())
        }
        editorToolbar.addView(actionButton("‹ Back") {
            closeDataEditorAction?.invoke() ?: closeDataEditorScreen()
        })
        editorScreenTitle = text("Editor", 18, Color.WHITE, bold = true)
        editorToolbar.addView(editorScreenTitle, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginStart = dp(14) })
        editorScreen.addView(editorToolbar, matchWrap())
        dataEditorWorkspace = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(14))
        }
        editorScreen.addView(dataEditorWorkspace, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        appRoot.addView(editorScreen, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(appRoot)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

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
            matchWrap(top = 5, bottom = 16)
        )

        val tabNames = listOf(
            "Game Data", "Save Editor", "Transform", "Data Editor",
            "Car Editor", "Liveries", "Career", "Track Inspector", "About"
        )
        val tabPages = linkedMapOf<String, LinearLayout>()
        for (name in tabNames) {
            tabPages[name] = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
        }
        val tabButtons = linkedMapOf<String, TextView>()
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabScroll.addView(tabBar)
        page.addView(tabScroll, matchWrap(bottom = 14))

        val contentHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        page.addView(contentHost, matchWrap())
        tabPages.values.forEach { contentHost.addView(it, matchWrap()) }

        fun selectTab(selected: String) {
            tabPages.forEach { (name, content) ->
                content.visibility = if (name == selected) View.VISIBLE else View.GONE
            }
            tabButtons.forEach { (name, button) ->
                val active = name == selected
                button.background = rounded(
                    if (active) 0xFF1766A8.toInt() else 0xFF121F30.toInt(),
                    dp(18).toFloat()
                )
                button.setTextColor(if (active) Color.WHITE else 0xFF9BAFC5.toInt())
            }
        }

        for (name in tabNames) {
            val button = text(name, 12, 0xFF9BAFC5.toInt(), bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(15), dp(10), dp(15), dp(10))
                background = rounded(0xFF121F30.toInt(), dp(18).toFloat())
                isClickable = true
                isFocusable = true
                setOnClickListener { selectTab(name) }
            }
            tabButtons[name] = button
            tabBar.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(7) }
            )
        }
        selectTabAction = { name -> selectTab(name) }
        selectTab("Game Data")

        val gameDataPage = tabPages.getValue("Game Data")
        val savePage = tabPages.getValue("Save Editor")
        val transformPage = tabPages.getValue("Transform")
        val editorPage = tabPages.getValue("Data Editor")
        val carPage = tabPages.getValue("Car Editor")
        val liveryPage = tabPages.getValue("Liveries")
        val careerPage = tabPages.getValue("Career")
        val trackPage = tabPages.getValue("Track Inspector")
        val aboutPage = tabPages.getValue("About")

        addSection(gameDataPage, "GAME DATA")
        val gameCard = card(gameDataPage)
        gameCard.addView(text("Real Racing 3 files", 17, Color.WHITE, bold = true), matchWrap())
        gameCard.addView(
            text(
                "Browse the installed RR3 files directly with root or Shizuku. Shizuku works without root using Android's Wireless Debugging (Android 11+), so the game folder does not need to be copied. Manual folder access remains available where Android permits it.",
                13,
                0xFF9BAFC5.toInt()
            ),
            matchWrap(top = 6)
        )
        gameDataValue = text("No folder selected", 12, 0xFFB7C8D9.toInt())
        gameCard.addView(gameDataValue, matchWrap(top = 12))
        locateRootGameDataButton = actionButton("Auto-detect installed RR3 .depot (root)") {
            locateRootGameDataFolder()
        }
        gameCard.addView(locateRootGameDataButton, matchWrap(top = 12))
        gameCard.addView(
            actionButton("Find installed RR3 folder without root (Shizuku)") { locateShizukuGameDataFolder() },
            matchWrap(top = 8)
        )
        gameCard.addView(
            actionButton("Choose accessible RR3 folder manually") { chooseFolder(requestGameData) },
            matchWrap(top = 8)
        )
        gameFilesStatus = text(
            "Choose root or Shizuku to detect the installed game without copying it. Shizuku needs to be running and authorised.",
            12,
            0xFF9BAFC5.toInt()
        )
        gameCard.addView(gameFilesStatus, matchWrap(top = 10))
        indexGameFilesButton = actionButton("Index and browse RR3 files") { indexGameDataFiles() }
        gameCard.addView(indexGameFilesButton, matchWrap(top = 10))
        gameFilesSearch = editBox("Search RR3 file names and paths", "", numeric = false)
        gameCard.addView(gameFilesSearch, matchWrap(top = 10))
        gameFilesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        gameCard.addView(gameFilesContainer, matchWrap(top = 10))
        gameFilesSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderGameFiles()
            override fun afterTextChanged(s: Editable?) = Unit
        })

        addSection(savePage, "SAVE EDITOR")
        val saveCard = card(savePage)
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
        rootSaveValue = text("Root access not configured", 12, 0xFFB7C8D9.toInt())
        saveCard.addView(rootSaveValue, matchWrap(top = 12))
        saveCard.addView(
            actionButton("Locate private RR3 save folder with root") { locateRootSaveFolder() },
            matchWrap(top = 8)
        )
        saveCard.addView(
            text(
                "Root access is optional. It may be needed to reach the game's private doc folder on Android 11 and later.",
                11,
                0xFF7F93A9.toInt()
            ),
            matchWrap(top = 6)
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

        saveCard.addView(
            text("ALL FIELDS (ADVANCED)", 11, 0xFF75B9F1.toInt(), bold = true),
            matchWrap(top = 18, bottom = 8)
        )
        advancedSearch = editBox("Search save field names", "", numeric = false)
        saveCard.addView(advancedSearch, matchWrap(bottom = 10))
        advancedFieldsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        saveCard.addView(advancedFieldsContainer, matchWrap())
        advancedSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderAdvancedFields()
            override fun afterTextChanged(s: Editable?) = Unit
        })

        addSection(transformPage, "RR3 DATA TRANSFORM")
        val dataCard = card(transformPage)
        dataCard.addView(
            text("Decode or re-encode RR3 data and game text", 17, Color.WHITE, bold = true),
            matchWrap()
        )
        dataCard.addView(
            text(
                "The upstream RR3 RC4 transform is built into the native engine. Choose .nct/.gui or .bin/.xml pairs to decode or encode. RR3 gametext.txt and text_<language>.txt have their own cipher; the app asks whether to decode or encode those.",
                13,
                0xFF9BAFC5.toInt()
            ),
            matchWrap(top = 6)
        )
        dataCard.addView(
            actionButton("Choose RR3 data (.nct / .bin / .gui / .xml / game text .txt)") { chooseRr3DataFile() },
            matchWrap(top = 12)
        )
        dataStatus = text("No RR3 data file selected.", 12, 0xFF9BAFC5.toInt())
        dataCard.addView(dataStatus, matchWrap(top = 10))

        addSection(editorPage, "RR3 DATA EDITOR")
        val editorCard = card(editorPage)
        editorCard.addView(
            text("Edit supported RR3 data files as text", 17, Color.WHITE, bold = true),
            matchWrap()
        )
        editorCard.addView(
            text(
                "Open supported .sounddef, .evt, table and text files. events.dat gets a compact, disk-backed text view of its series, tiers and events; saving rebuilds the RR3 binary and restores NCT encryption when the source was .dat.nct.",
                13,
                0xFF9BAFC5.toInt()
            ),
            matchWrap(top = 6)
        )
        editorCard.addView(
            actionButton("Open editable RR3 data file") { chooseEditableDataFile() },
            matchWrap(top = 12)
        )
        dataEditorStatus = text("No data file opened.", 12, 0xFF9BAFC5.toInt())
        editorCard.addView(dataEditorStatus, matchWrap(top = 10))
        addSection(carPage, "RR3 CAR EDITOR")
        val carCard = card(carPage)
        carCard.addView(
            text("Edit a car's structured game data", 17, Color.WHITE, bold = true),
            matchWrap()
        )
        carCard.addView(
            text(
                "Open a vehicles/data/<id>_<car>.bin.nct file. The app reads the actual car fields and lets you change supported values without editing encrypted bytes by hand. Keep edited text fields the same UTF-8 byte length; RR3's encrypted record layout cannot safely shift later fields. Output is saved as a separate file.",
                13,
                0xFF9BAFC5.toInt()
            ),
            matchWrap(top = 6)
        )
        carCard.addView(
            actionButton("Choose car data (.bin.nct)") { chooseCarEditorFile() },
            matchWrap(top = 12)
        )
        carEditorStatus = text("No car data file opened.", 12, 0xFF9BAFC5.toInt())
        carCard.addView(carEditorStatus, matchWrap(top = 10))
        carFieldsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        carCard.addView(carFieldsContainer, matchWrap(top = 12))

        addSection(liveryPage, "RR3 LIVERY MANAGER")
        val liveryCard = card(liveryPage)
        liveryCard.addView(
            text("Manage a car's livery slots", 17, Color.WHITE, bold = true),
            matchWrap()
        )
        liveryCard.addView(
            text(
                "Choose a car from the indexed depot. Its existing livery slots are listed here before you add another one. New slots are appended; existing slots are preserved.",
                13,
                0xFF9BAFC5.toInt()
            ),
            matchWrap(top = 6)
        )
        liveryStatus = text(
            "Index the RR3 .depot in Game Data to load the available cars.",
            12,
            0xFF9BAFC5.toInt()
        )
        liveryCard.addView(liveryStatus, matchWrap(top = 10))

        val liveryCarsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val liveryManualPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val liveryPaintPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val liveryDecodedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val liveryModeTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun selectLiveryMode(mode: String) {
            liveryCarsPanel.visibility = if (mode == "cars") View.VISIBLE else View.GONE
            liveryManualPanel.visibility = if (mode == "manual") View.VISIBLE else View.GONE
            liveryPaintPanel.visibility = if (mode == "paint") View.VISIBLE else View.GONE
            liveryDecodedPanel.visibility = if (mode == "decoded") View.VISIBLE else View.GONE
            if (mode == "paint") loadLiveryPaintLabels()
            if (mode == "decoded") renderSelectedLiveryDecodedData()
        }
        val indexedCarsTab = actionButton("Cars") { selectLiveryMode("cars") }
        val manualFileTab = actionButton("Manual") { selectLiveryMode("manual") }
        val paintLabelsTab = actionButton("Paints") { selectLiveryMode("paint") }
        val decodedDataTab = actionButton("NCT data") { selectLiveryMode("decoded") }
        for (tab in listOf(indexedCarsTab, manualFileTab, paintLabelsTab, decodedDataTab)) {
            liveryModeTabs.addView(
                tab,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(5)
                }
            )
        }
        liveryCard.addView(liveryModeTabs, matchWrap(top = 10))

        liveryCarListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        liveryCarsPanel.addView(liveryCarListContainer, matchWrap(top = 10))
        liverySlotsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        liveryCarsPanel.addView(
            text("SELECTED CAR · LIVERY SLOTS", 11, 0xFF8FA4BA.toInt(), bold = true),
            matchWrap(top = 12, bottom = 6)
        )
        liveryCarsPanel.addView(liverySlotsContainer, matchWrap())

        liveryManualStatus = text(
            "Choose a .bin.nct file to inspect its livery entries. The indexed car list remains available in the other tab.",
            12,
            0xFF9BAFC5.toInt()
        )
        liveryManualPanel.addView(liveryManualStatus, matchWrap(top = 10))
        liveryManualPanel.addView(
            actionButton("Select .bin.nct file") { chooseManualLiveryFile() },
            matchWrap(top = 10)
        )
        liveryManualSlotsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        liveryManualPanel.addView(liveryManualSlotsContainer, matchWrap(top = 10))

        liveryPaintStatus = text(
            "Edit the Motorsports label and Road Cars paint-material/colour names. Index Game Data first.",
            12,
            0xFF9BAFC5.toInt()
        )
        liveryPaintPanel.addView(liveryPaintStatus, matchWrap(top = 10))
        liveryPaintPanel.addView(
            actionButton("Reload paint labels") { loadLiveryPaintLabels() },
            matchWrap(top = 8)
        )
        liveryPaintContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        liveryPaintPanel.addView(liveryPaintContainer, matchWrap(top = 10))

        liveryCard.addView(liveryCarsPanel, matchWrap())
        liveryCard.addView(liveryManualPanel, matchWrap())
        liveryCard.addView(liveryPaintPanel, matchWrap())
        liveryDecodedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        liveryDecodedPanel.addView(liveryDecodedContainer, matchWrap(top = 10))
        liveryDecodedPanel.addView(
            text(
                "This view shows every field currently returned by the native livery parser. It is an experimental inspection view, not a complete dump of every unknown byte in the NCT format.",
                11, 0xFF8FA4BA.toInt()
            ),
            matchWrap(top = 12)
        )
        liveryCard.addView(liveryDecodedPanel, matchWrap())

        addSection(careerPage, "RR3 CAREER INSPECTOR")
        val careerCard = card(careerPage)
        careerCard.addView(
            text("Inspect the career database", 17, Color.WHITE, bold = true),
            matchWrap()
        )
        careerCard.addView(
            text(
                "Open data/events.dat.nct here to inspect its RRCE career structure, including tracks, series, tiers and event counts. Use Data Editor to edit the supported career fields and rebuild the file.",
                13,
                0xFF9BAFC5.toInt()
            ),
            matchWrap(top = 6)
        )
        careerCard.addView(
            actionButton("Choose events.dat.nct") { chooseCareerInspectorFile() },
            matchWrap(top = 12)
        )
        careerInspectorStatus = text("No career file opened.", 12, 0xFF9BAFC5.toInt())
        careerCard.addView(careerInspectorStatus, matchWrap(top = 10))
        careerDetailsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        careerCard.addView(careerDetailsContainer, matchWrap(top = 12))


        addSection(trackPage, "RR3 TRACK INSPECTOR")
        val trackCard = card(trackPage)
        trackCard.addView(
            text("Inspect a track's data record", 17, Color.WHITE, bold = true),
            matchWrap()
        )
        trackCard.addView(
            text(
                "Open a Tracks/data/*.bin.nct file to read its track ID, display name, track folder, layout, time of day and country. This is a read-only inspection tool.",
                13,
                0xFF9BAFC5.toInt()
            ),
            matchWrap(top = 6)
        )
        trackCard.addView(
            actionButton("Choose track data (.bin.nct)") { chooseTrackInspectorFile() },
            matchWrap(top = 12)
        )
        trackInspectorStatus = text("No track data file opened.", 12, 0xFF9BAFC5.toInt())
        trackCard.addView(trackInspectorStatus, matchWrap(top = 10))
        trackDetailsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        trackCard.addView(trackDetailsContainer, matchWrap(top = 12))

        addSection(aboutPage, "NATIVE ENGINE")
        val engineCard = card(aboutPage)
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

        addSection(aboutPage, "PORT STATUS")
        val statusCard = card(aboutPage)
        statusCard.addView(
            text("Android foundation", 16, Color.WHITE, bold = true),
            matchWrap()
        )
        statusCard.addView(
            text(
                "The app uses the upstream C++ save parser, car-data parser and career-file reader. Large NCT transforms run in the native engine; source game files remain untouched unless you explicitly export replacements.",
                13,
                0xFF9BAFC5.toInt()
            ),
            matchWrap(top = 6)
        )

        aboutPage.addView(
            text(
                "Unofficial fan project  •  Not affiliated with EA",
                11,
                0xFF61758B.toInt()
            ).apply { gravity = Gravity.CENTER },
            matchWrap(top = 24)
        )

        restoreSelection(gameDataValue, "game_data_tree")
        val savedRootGameData = preferences.getString("game_data_root_dir", null)
        if (!savedRootGameData.isNullOrBlank()) {
            gameDataValue.text = "Root folder: $savedRootGameData"
        }
        restoreSelection(saveFolderValue, "save_tree")
        restoreRootSaveSelection()
        updateEngineStatus()
    }




    private fun transformNctStream(input: InputStream, output: OutputStream): Long {
        val handle = NativeBridge.nativeCreateRr3TransformStream()
        if (handle == 0L) {
            throw IllegalStateException("Could not initialise the RR3 NCT decoder.")
        }

        val buffer = ByteArray(512 * 1024)
        var total = 0L
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (!NativeBridge.nativeTransformRr3Chunk(handle, buffer, count)) {
                    throw IllegalStateException("The streaming RR3 NCT decoder failed at byte $total.")
                }
                output.write(buffer, 0, count)
                total += count.toLong()
            }
            output.flush()
        } finally {
            NativeBridge.nativeFreeRr3TransformStream(handle)
        }
        if (total == 0L) throw IllegalStateException("The selected RR3 NCT file is empty.")
        return total
    }

    private fun newCacheFile(prefix: String, suffix: String = ".tmp"): File =
        File(cacheDir, prefix + "_" + System.nanoTime() + suffix)

    /**
     * Game text uses its own RC4 stream. Detection reads only the first 256
     * bytes, and decoding streams to a temporary file to avoid multi-copy
     * ByteArray allocations that can kill the app on the large gametext file.
     *
     * @return true when the source was encrypted and has been decoded in place.
     */
    private fun hasGameTextSignature(file: File): Boolean {
        if (!file.isFile || file.length() < 9L) return false
        val prefix = ByteArray(16)
        val count = FileInputStream(file).use { it.read(prefix) }
        if (count < 9) return false
        var offset = 0
        if (count >= 12 &&
            prefix[0] == 0xEF.toByte() &&
            prefix[1] == 0xBB.toByte() &&
            prefix[2] == 0xBF.toByte()
        ) {
            offset = 3
        }
        while (offset < count && (
                prefix[offset] == 0x20.toByte() ||
                prefix[offset] == 0x09.toByte() ||
                prefix[offset] == 0x0A.toByte() ||
                prefix[offset] == 0x0D.toByte()
            )
        ) offset++
        val signature = "GAMETEXT_".toByteArray(Charsets.US_ASCII)
        if (count - offset < signature.size) return false
        return signature.indices.all { prefix[offset + it] == signature[it] }
    }

    /**
     * RR3 game text is a separate RC4 stream. For gametext.txt, require its
     * actual key-table signature before accepting the file as plaintext;
     * the generic control-character heuristic alone is not sufficient.
     */
    private fun prepareRr3TextFileForEditing(
        sourceFile: File,
        isGameTextIndex: Boolean
    ): Boolean {
        if (!sourceFile.isFile || sourceFile.length() <= 0L) {
            throw IllegalStateException("The RR3 game-text file is empty or unreadable.")
        }
        val appearsPlain = NativeBridge.nativeRr3TextFileIsPlain(sourceFile.absolutePath)
        if (appearsPlain && (!isGameTextIndex || hasGameTextSignature(sourceFile))) return false

        val decodedFile = newCacheFile("rr3-game-text-decoded", ".txt")
        try {
            val decodedSize = NativeBridge.nativeTransformRr3TextFile(
                sourceFile.absolutePath,
                decodedFile.absolutePath
            )
            if (decodedSize < 0L || decodedSize != sourceFile.length()) {
                throw IllegalStateException("Could not decode this RR3 game-text file.")
            }
            if (!NativeBridge.nativeRr3TextFileIsPlain(decodedFile.absolutePath) ||
                (isGameTextIndex && !hasGameTextSignature(decodedFile))
            ) {
                throw IllegalStateException(
                    "The decoded file did not contain a valid RR3 game-text key table. The source was left unchanged."
                )
            }

            if (sourceFile.exists() && !sourceFile.delete()) {
                throw IllegalStateException("Could not replace the encrypted temporary text copy.")
            }
            if (!decodedFile.renameTo(sourceFile)) {
                FileInputStream(decodedFile).use { input ->
                    FileOutputStream(sourceFile).use { output ->
                        input.copyTo(output, 256 * 1024)
                    }
                }
                if (sourceFile.length() != decodedSize) {
                    throw IllegalStateException("Could not finish replacing the temporary game-text file.")
                }
            }
            return true
        } finally {
            decodedFile.delete()
        }
    }

    private fun filesAreEqual(first: File, second: File): Boolean {
        if (first.length() != second.length()) return false
        val leftBuffer = ByteArray(64 * 1024)
        val rightBuffer = ByteArray(64 * 1024)
        FileInputStream(first).buffered().use { left ->
            FileInputStream(second).buffered().use { right ->
                while (true) {
                    val leftCount = left.read(leftBuffer)
                    val rightCount = right.read(rightBuffer)
                    if (leftCount != rightCount) return false
                    if (leftCount < 0) return true
                    for (i in 0 until leftCount) {
                        if (leftBuffer[i] != rightBuffer[i]) return false
                    }
                }
            }
        }
    }

    private fun copyGameFileToFile(entry: GameDataFile, target: File) {
        target.parentFile?.mkdirs()
        val rootPath = entry.rootPath
        if (!rootPath.isNullOrBlank()) {
            if (entry.isShizuku) {
                val depotPath = entry.depotRootPath
                    ?: throw IllegalStateException("The Shizuku RR3 folder path was lost. Re-detect the game folder.")
                val service = shizukuStorageService
                    ?: throw IllegalStateException("Shizuku disconnected. Start it again and retry.")
                val pipe = service.openFile(depotPath, rootPath)
                    ?: throw IllegalStateException("Shizuku could not open ${entry.relativePath}.")
                ParcelFileDescriptor.AutoCloseInputStream(pipe).use { source ->
                    FileOutputStream(target).use { destination ->
                        source.copyTo(destination, 512 * 1024)
                    }
                }
                return
            }
            val process = ProcessBuilder("su", "-c", "cat -- ${shellQuote(rootPath)}").start()
            try {
                process.inputStream.use { source ->
                    FileOutputStream(target).use { destination ->
                        source.copyTo(destination, 512 * 1024)
                    }
                }
                val error = process.errorStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    target.delete()
                    throw IllegalStateException(error.trim().ifBlank {
                        "Could not read ${entry.relativePath} with root."
                    })
                }
            } catch (e: Exception) {
                process.destroy()
                target.delete()
                throw e
            }
            return
        }

        val uri = entry.uri
            ?: throw IllegalStateException("No readable location was stored for ${entry.relativePath}.")
        val source = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open ${entry.relativePath}.")
        source.use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, 512 * 1024)
            }
        }
    }

    private fun transformGameFileToCache(entry: GameDataFile, target: File): Long {
        val sourceFile = newCacheFile("rr3-encrypted")
        try {
            copyGameFileToFile(entry, sourceFile)
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(target).use { output ->
                    return transformNctStream(input, output)
                }
            }
        } finally {
            sourceFile.delete()
        }
    }

    private fun transformUriToCache(uri: Uri, target: File): Long {
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open the selected RR3 file.")
        input.use { source ->
            FileOutputStream(target).use { output ->
                return transformNctStream(source, output)
            }
        }
    }

    private fun indexGameDataFiles() {
        val rootPath = preferences.getString("game_data_root_dir", null)
        val stored = preferences.getString("game_data_tree", null)
        val backend = preferences.getString("game_data_backend", "root")
        if (rootPath.isNullOrBlank() && stored.isNullOrBlank()) {
            gameFilesStatus.text = "Choose root, Shizuku, or a folder Android allows you to access."
            return
        }
        if (!rootPath.isNullOrBlank() && backend == "shizuku" && shizukuStorageService == null) {
            ensureShizukuStorageService { indexGameDataFiles() }
            return
        }

        indexGameFilesButton.isEnabled = false
        gameFilesStatus.text = "Indexing RR3 .depot files…"
        gameFilesContainer.removeAllViews()
        Thread {
            try {
                val files = if (!rootPath.isNullOrBlank()) {
                    val listing = if (backend == "shizuku") {
                        val service = shizukuStorageService
                            ?: throw IllegalStateException("Shizuku disconnected. Start it again and retry.")
                        val pipe = service.listFiles(rootPath)
                            ?: throw IllegalStateException("Shizuku could not list the RR3 files.")
                        ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    } else {
                        runRootCommand("find ${shellQuote(rootPath)} -type f -print")
                    }
                    GameDataBrowser.fromRootListing(rootPath, listing, useShizuku = backend == "shizuku")
                } else {
                    GameDataBrowser.scan(contentResolver, Uri.parse(stored!!))
                }
                runOnUiThread {
                    indexedGameFiles = files
                    gameFilesSearch.setText("")
                    gameFilesStatus.text =
                        "Indexed ${files.size} supported files. Open folders to browse the depot tree. Textures and 3D model assets are hidden."
                    renderGameFiles()
                    renderLiveryCarChoices()
                    indexGameFilesButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    gameFilesStatus.text = e.message ?: "Could not index the RR3 game-data folder."
                    indexGameFilesButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun ensureShizukuStorageService(onReady: () -> Unit) {
        if (shizukuStorageService != null) { onReady(); return }
        pendingShizukuAction = onReady
        if (!Shizuku.pingBinder()) {
            pendingShizukuAction = null
            AlertDialog.Builder(this)
                .setTitle("Shizuku is not running")
                .setMessage("Install Shizuku, then start it using Wireless Debugging in Developer Options. This works on Android 11+ without a computer or root. Shizuku must be restarted after a reboot.")
                .setPositiveButton("Shizuku setup") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/")))
                }.setNegativeButton("Cancel", null).show()
            gameFilesStatus.text = "Shizuku is not running. Start it through Wireless Debugging, then retry."
            return
        }
        if (Shizuku.isPreV11()) {
            pendingShizukuAction = null
            gameFilesStatus.text = "This Shizuku version is too old. Update Shizuku and retry."
            return
        }
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(shizukuPermissionRequestCode)
                gameFilesStatus.text = "Waiting for Shizuku permission…"
                return
            }
            bindShizukuStorageService()
        } catch (e: Exception) {
            pendingShizukuAction = null
            gameFilesStatus.text = e.message ?: "Could not connect to Shizuku."
        }
    }

    private fun bindShizukuStorageService() {
        if (shizukuStorageService != null) {
            val ready = pendingShizukuAction
            pendingShizukuAction = null
            ready?.invoke()
            return
        }
        if (shizukuBindPending) return
        try {
            shizukuBindPending = true
            Shizuku.bindUserService(shizukuServiceArgs, shizukuServiceConnection)
            gameFilesStatus.text = "Connecting to Shizuku's storage service…"
        } catch (e: Exception) {
            shizukuBindPending = false
            pendingShizukuAction = null
            gameFilesStatus.text = e.message ?: "Could not bind the Shizuku storage service."
        }
    }

    private fun locateShizukuGameDataFolder() {
        gameFilesStatus.text = "Checking Shizuku and locating the installed RR3 folder…"
        ensureShizukuStorageService {
            val service = shizukuStorageService ?: return@ensureShizukuStorageService
            Thread {
                try {
                    val uid = service.getUid()
                    if (uid != 0 && uid != 2000) {
                        throw IllegalStateException("Shizuku's service is running with an unexpected UID ($uid). Restart Shizuku using its Wireless Debugging method and retry.")
                    }
                    val found = service.findRr3Depot()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw IllegalStateException("Shizuku can run, but no populated RR3 files/.depot folder was found under Android/data.")
                    runOnUiThread {
                        preferences.edit()
                            .putString("game_data_root_dir", found)
                            .putString("game_data_backend", "shizuku")
                            .remove("game_data_tree")
                            .apply()
                        gameDataValue.text = found
                        indexedGameFiles = emptyList()
                        gameFilesContainer.removeAllViews()
                        gameFilesStatus.text = "Found RR3 .depot using Shizuku (UID $uid): $found. Tap Index and browse RR3 files."
                    }
                } catch (e: Exception) {
                    runOnUiThread { gameFilesStatus.text = e.message ?: "Shizuku could not access the RR3 game folder." }
                }
            }.start()
        }
    }

    private fun locateRootGameDataFolder() {
        locateRootGameDataButton.isEnabled = false
        gameFilesStatus.text = "Requesting root and locating the RR3 .depot folder…"
        Thread {
            try {
                val uid = runRootCommand("id -u").substringAfterLast('\n').trim()
                if (uid != "0") {
                    throw IllegalStateException(
                        "Root access was not granted. Grant this app Superuser access, then retry."
                    )
                }

                val userId = android.os.Process.myUid() / 100000
                val packageNames = listOf(
                    "com.ea.games.r3_row",
                    "com.ea.games.r3_na",
                    "com.ea.games.r3_cn"
                )

                // Restore the first detector's strongest clue: the RR3 save
                // folder and depot are siblings under the same files/ folder.
                val saveRoot = preferences.getString("save_root_dir", null)
                val inferredDepot = saveRoot
                    ?.trimEnd('/')
                    ?.substringBeforeLast('/', missingDelimiterValue = "")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "$it/.depot" }

                // RR3 has used both the private app-data location and the
                // external Android/data location. Root can inspect either.
                // Keep the private paths first because the earlier working
                // detector relied on these on devices with private depots.
                val candidates = buildList {
                    inferredDepot?.let { add(it) }
                    for (packageName in packageNames) {
                        add("/data/user/$userId/$packageName/files/.depot")
                        add("/data/user/0/$packageName/files/.depot")
                        add("/data/data/$packageName/files/.depot")
                        add("/data/user_de/$userId/$packageName/files/.depot")
                        add("/storage/emulated/$userId/Android/data/$packageName/files/.depot")
                        add("/data/media/$userId/Android/data/$packageName/files/.depot")
                        add("/storage/emulated/0/Android/data/$packageName/files/.depot")
                        add("/data/media/0/Android/data/$packageName/files/.depot")
                    }
                }.distinct()

                // Match the first working version's behaviour for known
                // RR3 paths: the directory check itself is enough. Some game
                // versions have a delayed/partially populated depot at startup.
                var found = candidates.firstOrNull { candidate ->
                    runRootCommand(
                        "if [ -d ${shellQuote(candidate)} ]; then printf yes; fi"
                    ).substringAfterLast('\n').trim() == "yes"
                }

                // Final fallback for non-standard RR3 package IDs and storage
                // layouts. Search the usual external roots without assuming
                // the application ID.
                if (found == null) {
                    val storageRoots = linkedSetOf<String>().apply {
                        add("/storage/emulated/0")
                        add("/data/media/0")
                        add("/storage/emulated/$userId")
                        add("/data/media/$userId")
                        add("/mnt/user/$userId/primary")
                        val ownExternalSuffix = "/Android/data/" + packageName + "/files"
                        getExternalFilesDirs(null).filterNotNull().forEach { directory ->
                            val absolutePath = directory.absolutePath
                            if (absolutePath.endsWith(ownExternalSuffix)) {
                                add(absolutePath.removeSuffix(ownExternalSuffix))
                            }
                        }
                    }

                    val checks = storageRoots.joinToString("\n") { root ->
                        val dataRoot = shellQuote(root.trimEnd('/') + "/Android/data")
                        "data_root=$dataRoot; " +
                            "if [ -d \"\$data_root\" ]; then " +
                            "for package_dir in \"\$data_root\"/*; do " +
                            "[ -d \"\$package_dir\" ] || continue; " +
                            "depot=\"\$package_dir/files/.depot\"; " +
                            "if [ -d \"\$depot\" ]; then " +
                            "first_file=\$(find \"\$depot\" -type f -print -quit 2>/dev/null); " +
                            "if [ -n \"\$first_file\" ]; then " +
                            "printf 'FOUND|%s\\n' \"\$depot\"; exit 0; fi; fi; done; fi"
                    }
                    val scanOutput = runRootCommand("$checks\nexit 0")
                    found = scanOutput.lineSequence()
                        .firstOrNull { it.startsWith("FOUND|") }
                        ?.substringAfter("FOUND|")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }

                if (found.isNullOrBlank()) {
                    throw IllegalStateException(
                        "Root access works, but no populated RR3 .depot folder was found. " +
                            "Checked the saved RR3 location, private /data/user and /data/data paths, " +
                            "and external Android/data package folders."
                    )
                }

                preferences.edit()
                    .putString("game_data_root_dir", found)
                    .putString("game_data_backend", "root")
                    .remove("game_data_tree")
                    .apply()
                runOnUiThread {
                    gameDataValue.text = found
                    indexedGameFiles = emptyList()
                    gameFilesContainer.removeAllViews()
                    gameFilesStatus.text =
                        "Found populated RR3 .depot: " + found + ". Tap Index and browse RR3 files."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    gameFilesStatus.text = e.message
                        ?: "Could not detect RR3 .depot with root."
                }
            } finally {
                runOnUiThread { locateRootGameDataButton.isEnabled = true }
            }
        }.start()
    }

    private fun renderGameFiles() {
        if (!::gameFilesContainer.isInitialized) return
        gameFilesContainer.removeAllViews()
        if (indexedGameFiles.isEmpty()) {
            gameFilesContainer.addView(
                text("No indexed files yet.", 12, 0xFF7F93A9.toInt()),
                matchWrap()
            )
            return
        }

        val query = gameFilesSearch.text.toString().trim().lowercase(Locale.ROOT)
        val matches = indexedGameFiles.filter {
            query.isEmpty() || it.relativePath.lowercase(Locale.ROOT).contains(query)
        }
        if (matches.isEmpty()) {
            gameFilesContainer.addView(
                text("No matching RR3 files.", 12, 0xFF7F93A9.toInt()),
                matchWrap()
            )
            return
        }

        val root = GameDataTreeNode("", "")
        for (entry in matches) {
            val parts = entry.relativePath.replace('\\', '/').split('/').filter { it.isNotBlank() }
            var node = root
            var pathSoFar = ""
            for ((index, part) in parts.withIndex()) {
                pathSoFar = if (pathSoFar.isEmpty()) part else "$pathSoFar/$part"
                val isFile = index == parts.lastIndex
                val child = node.children.getOrPut(part) {
                    GameDataTreeNode(part, pathSoFar, if (isFile) entry else null)
                }
                node = child
            }
        }

        fun addNode(parent: LinearLayout, node: GameDataTreeNode, depth: Int) {
            val entry = node.file
            if (entry == null) {
                val expanded = query.isNotEmpty() || node.relativePath in expandedGameDataFolders
                val folderRow = text(
                    (if (expanded) "▾  " else "▸  ") + node.name + "  ·  " + node.children.size,
                    13,
                    Color.WHITE,
                    bold = true
                ).apply {
                    setPadding(dp(11 + depth * 16), dp(10), dp(10), dp(10))
                    background = rounded(0xFF13263A.toInt(), dp(9).toFloat())
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (expandedGameDataFolders.contains(node.relativePath)) {
                            expandedGameDataFolders.remove(node.relativePath)
                        } else {
                            expandedGameDataFolders.add(node.relativePath)
                        }
                        renderGameFiles()
                    }
                }
                parent.addView(folderRow, matchWrap(bottom = 5))
                if (expanded) {
                    val children = node.children.values.sortedWith(
                        compareBy<GameDataTreeNode>({ it.file != null }, { it.name.lowercase(Locale.ROOT) })
                    )
                    for (child in children) addNode(parent, child, depth + 1)
                }
            } else {
                val fileRow = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(11 + depth * 16), dp(8), dp(10), dp(8))
                    background = rounded(0xFF0B1725.toInt(), dp(9).toFloat())
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { showGameFileActions(entry) }
                }
                parent.addView(fileRow, matchWrap(bottom = 4))
                fileRow.addView(text("📄  " + entry.name, 12, Color.WHITE, bold = true), matchWrap())
                val size = if (entry.sizeBytes >= 0) "  ·  ${entry.sizeBytes} bytes" else ""
                fileRow.addView(
                    text(entry.relativePath + size, 10, 0xFF8FA4BA.toInt()),
                    matchWrap(top = 2)
                )
            }
        }

        for (child in root.children.values.sortedWith(
            compareBy<GameDataTreeNode>({ it.file != null }, { it.name.lowercase(Locale.ROOT) })
        )) {
            addNode(gameFilesContainer, child, 0)
        }
        gameFilesStatus.text = if (query.isEmpty()) {
            "Indexed ${indexedGameFiles.size} supported files. Open folders to browse the depot tree."
        } else {
            "Showing ${matches.size} file(s) matching “${gameFilesSearch.text}”. Matching folders are expanded."
        }
    }


    private fun scanLiveryCarAssets(liveryEntry: GameDataFile, carFolder: String): List<GameDataFile> {
        val rootPath = liveryEntry.depotRootPath
            ?: preferences.getString("game_data_root_dir", null)
        if (!rootPath.isNullOrBlank()) {
            val backend = preferences.getString("game_data_backend", "root")
            val useShizuku = liveryEntry.isShizuku || backend == "shizuku"
            val carRoot = File(rootPath, "vehicles/$carFolder").absolutePath
            val listing = if (useShizuku) {
                val service = shizukuStorageService
                    ?: throw IllegalStateException("Shizuku disconnected. Start it again and retry.")
                val pipe = service.listFiles(carRoot)
                    ?: throw IllegalStateException("Shizuku could not list this car's texture files.")
                ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                runRootCommand("find ${shellQuote(carRoot)} -type f -print")
            }
            return GameDataBrowser.fromRootListing(
                carRoot, listing, useShizuku = useShizuku, includeTextures = true
            ).map {
                it.copy(
                    relativePath = "vehicles/$carFolder/" + it.relativePath,
                    depotRootPath = rootPath
                )
            }
        }

        val storedTree = preferences.getString("game_data_tree", null)
            ?: throw IllegalStateException("The selected RR3 depot folder is no longer available. Re-index the game files.")
        return GameDataBrowser.scanLiveryCarAssets(
            contentResolver, Uri.parse(storedTree), carFolder
        )
    }

    private fun formatLiveryCarName(carName: String): String =
        carName.split('_').filter { it.isNotBlank() }.joinToString(" ") { part ->
            when (part.lowercase(Locale.ROOT)) {
                "f1" -> "F1"
                "f2" -> "F2"
                "f3" -> "F3"
                "gt3" -> "GT3"
                "gt4" -> "GT4"
                "gt500" -> "GT500"
                "gtr" -> "GTR"
                else -> if (part.matches(Regex("[a-zA-Z]{1,4}[0-9]+"))) {
                    part.uppercase(Locale.ROOT)
                } else {
                    part.replaceFirstChar { it.uppercase(Locale.ROOT) }
                }
            }
        }


    private fun renderSelectedLiveryDecodedData() {
        if (!::liveryDecodedContainer.isInitialized) return
        liveryDecodedContainer.removeAllViews()

        val payload = selectedLiveryPayload
        if (payload == null) {
            liveryDecodedContainer.addView(
                text(
                    "Select a car in Cars, or load a .bin.nct file in Manual, then return here to inspect its decoded data.",
                    12, 0xFF9BAFC5.toInt()
                ),
                matchWrap()
            )
            return
        }

        fun addDetail(parent: LinearLayout, label: String, value: String) {
            parent.addView(
                text(label, 10, 0xFF75B9F1.toInt(), bold = true),
                matchWrap(top = 9)
            )
            parent.addView(
                text(value.ifBlank { "—" }, 12, Color.WHITE).apply {
                    setTextIsSelectable(true)
                },
                matchWrap(top = 2)
            )
        }

        fun stringValue(item: JSONObject, key: String): String {
            return if (item.has(key) && !item.isNull(key)) item.optString(key).trim() else ""
        }

        val title = selectedLiverySourceName.ifBlank { "Livery NCT" }
        liveryDecodedContainer.addView(
            text(title, 15, Color.WHITE, bold = true),
            matchWrap(bottom = 4)
        )
        addDetail(liveryDecodedContainer, "INTERNAL CAR IDENTIFIER", payload.optString("car"))
        addDetail(liveryDecodedContainer, "SOURCE FILE", title)
        addDetail(liveryDecodedContainer, "ENCRYPTED FILE SIZE", "$selectedLiverySourceSize bytes")

        val liveries = payload.optJSONArray("liveries")
        val count = liveries?.length() ?: 0
        addDetail(
            liveryDecodedContainer,
            "PARSED LIVERY RECORDS",
            payload.optInt("count", count).toString()
        )

        if (count == 0) {
            liveryDecodedContainer.addView(
                text("The parser decoded the container but found no livery records.", 12, 0xFFFFC27A.toInt()),
                matchWrap(top = 12)
            )
        }

        for (index in 0 until count) {
            val item = liveries?.optJSONObject(index) ?: continue
            val red = item.optInt("red").coerceIn(0, 255)
            val green = item.optInt("green").coerceIn(0, 255)
            val blue = item.optInt("blue").coerceIn(0, 255)
            val hex = String.format(Locale.ROOT, "#%02X%02X%02X", red, green, blue)
            val record = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(12))
                background = rounded(0xFF0B1725.toInt(), dp(10).toFloat())
            }
            liveryDecodedContainer.addView(record, matchWrap(top = 12))
            record.addView(
                text(
                    "RECORD ${index + 1} · " +
                        stringValue(item, "display").ifBlank { stringValue(item, "file").ifBlank { "Unnamed" } },
                    13, Color.WHITE, bold = true
                ),
                matchWrap(bottom = 3)
            )

            for ((key, label) in listOf(
                "file" to "INTERNAL FILE",
                "display" to "DISPLAY NAME",
                "texture" to "TEXTURE OVERRIDE",
                "swatch" to "SWATCH PATH",
                "logo" to "LOGO PATH",
                "flag" to "FLAG VALUE",
                "arms" to "ARMS TEXTURE",
                "driver" to "DRIVER TEXTURE"
            )) {
                val value = if (key == "flag") item.optInt(key).toString() else stringValue(item, key)
                addDetail(record, label, value)
            }
            addDetail(record, "BUTTON COLOUR · RGB", "$red, $green, $blue")
            addDetail(record, "BUTTON COLOUR · HEX", hex)

            val overrides = item.optJSONArray("overrides")
            record.addView(
                text(
                    "TEXTURE OVERRIDES · ${overrides?.length() ?: 0}",
                    10, 0xFF75B9F1.toInt(), bold = true
                ),
                matchWrap(top = 12, bottom = 4)
            )
            if (overrides == null || overrides.length() == 0) {
                record.addView(text("None parsed", 11, 0xFF8FA4BA.toInt()), matchWrap())
            } else {
                for (overrideIndex in 0 until overrides.length()) {
                    val override = overrides.optJSONObject(overrideIndex) ?: continue
                    record.addView(
                        text(
                            "Slot ${override.optInt("slot")}  →  " + stringValue(override, "path"),
                            11, 0xFFD4E1EF.toInt()
                        ).apply { setTextIsSelectable(true) },
                        matchWrap(top = 3)
                    )
                }
            }

            val materials = item.optJSONArray("materials")
            record.addView(
                text(
                    "MESH / MATERIAL ASSIGNMENTS · ${materials?.length() ?: 0}",
                    10, 0xFF75B9F1.toInt(), bold = true
                ),
                matchWrap(top = 12, bottom = 4)
            )
            if (materials == null || materials.length() == 0) {
                record.addView(text("None parsed", 11, 0xFF8FA4BA.toInt()), matchWrap())
            } else {
                for (materialIndex in 0 until materials.length()) {
                    val material = materials.optJSONObject(materialIndex) ?: continue
                    record.addView(
                        text(
                            "Mesh ${material.optInt("mesh")}  →  material ${material.optInt("material")}",
                            11, 0xFFD4E1EF.toInt()
                        ).apply { setTextIsSelectable(true) },
                        matchWrap(top = 3)
                    )
                }
            }
        }

        liveryDecodedContainer.addView(
            text("RAW PARSER OUTPUT · JSON", 11, 0xFF75B9F1.toInt(), bold = true),
            matchWrap(top = 18, bottom = 6)
        )
        liveryDecodedContainer.addView(
            text(payload.toString(2), 10, 0xFFB7C8D9.toInt()).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            },
            matchWrap()
        )
    }

    private fun renderLiveryCarChoices() {
        if (!::liveryCarListContainer.isInitialized) return
        liveryCarListContainer.removeAllViews()

        val marker = "vehicles/data/liveries/"
        val entries = indexedGameFiles.mapNotNull { entry ->
            val path = entry.relativePath.replace('\\', '/')
            if (!path.startsWith(marker, ignoreCase = true) ||
                !path.endsWith(".liveries.bin.nct", ignoreCase = true)) return@mapNotNull null
            val carFolder = path.substringAfter(marker)
                .removeSuffixIgnoreCaseLocal(".liveries.bin.nct")
            if (carFolder.isBlank() || carFolder.equals("default", ignoreCase = true) ||
                carFolder.contains('/')) return@mapNotNull null
            val yearPart = carFolder.substringBefore('_', "")
            val year = if (yearPart.matches(Regex("[0-9]{4}"))) yearPart else "Other"
            val carName = if (year == "Other") carFolder else carFolder.substringAfter('_')
            Triple(entry, year, carName)
        }.sortedWith(compareBy({ it.second }, { it.third.lowercase(Locale.ROOT) }))

        if (entries.isEmpty()) {
            liveryStatus.text = if (indexedGameFiles.isEmpty()) {
                "Index the RR3 .depot in Game Data first."
            } else {
                "No car livery lists were found in the indexed depot (the generic default list is hidden)."
            }
            liveryCarListContainer.addView(
                text("No selectable cars yet.", 12, 0xFF7F93A9.toInt()),
                matchWrap()
            )
            return
        }

        liveryStatus.text = "Found ${entries.size} car livery list(s). Expand a year, then select a car."
        for ((year, cars) in entries.groupBy { it.second }.toSortedMap()) {
            val expanded = year in expandedLiveryYears
            val yearRow = text(
                (if (expanded) "▾  " else "▸  ") + year + "  ·  " + cars.size + " car(s)",
                14,
                Color.WHITE,
                bold = true
            ).apply {
                setPadding(dp(12), dp(12), dp(10), dp(12))
                background = rounded(0xFF13263A.toInt(), dp(9).toFloat())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (year in expandedLiveryYears) expandedLiveryYears.remove(year)
                    else expandedLiveryYears.add(year)
                    renderLiveryCarChoices()
                }
            }
            liveryCarListContainer.addView(yearRow, matchWrap(bottom = 5))
            if (!expanded) continue

            for ((entry, _, carName) in cars) {
                val carFolder = entry.relativePath.replace('\\', '/')
                    .substringAfter(marker)
                    .removeSuffixIgnoreCaseLocal(".liveries.bin.nct")
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = rounded(0xFF0B1725.toInt(), dp(9).toFloat())
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { loadLiverySlots(entry) }
                }
                liveryCarListContainer.addView(row, matchWrap(bottom = 5))
                row.addView(
                    text(formatLiveryCarName(carName), 13, Color.WHITE, bold = true),
                    matchWrap()
                )
                row.addView(
                    text(carFolder, 10, 0xFF8FA4BA.toInt()),
                    matchWrap(top = 2)
                )
            }
        }
    }

    private fun loadLiverySlots(
        entry: GameDataFile,
        target: LinearLayout = liverySlotsContainer
    ) {
        val marker = "vehicles/data/liveries/"
        val path = entry.relativePath.replace('\\', '/')
        if (!path.startsWith(marker, ignoreCase = true) ||
            !path.endsWith(".liveries.bin.nct", ignoreCase = true)) {
            liveryStatus.text = "Choose an existing car's .liveries.bin.nct file."
            return
        }
        val carFolder = path.substring(marker.length)
            .removeSuffixIgnoreCaseLocal(".liveries.bin.nct")
            .trim()
        if (carFolder.isBlank() || carFolder.contains('/') || carFolder.contains("..")) {
            liveryStatus.text = "Could not identify the car folder from this livery list."
            return
        }

        selectedLiveryEntry = null
        selectedLiveryCarFolder = ""
        selectedLiveryBytes = null
        selectedLiveryArray = null
        selectedLiveryPayload = null
        selectedLiverySourceName = ""
        selectedLiverySourceSize = 0L
        target.removeAllViews()
        liveryStatus.text = "Reading existing livery slots for $carFolder…"

        Thread {
            try {
                val liveryBytes = readGameFile(entry)
                val payload = JSONObject(NativeBridge.nativeInspectRr3Liveries(liveryBytes))
                if (!payload.optBoolean("ok")) {
                    throw IllegalStateException(payload.optString("error", "Could not read this car's livery list."))
                }
                val liveryArray = payload.optJSONArray("liveries")
                    ?: throw IllegalStateException("The livery list contains no readable entries.")

                runOnUiThread {
                    selectedLiveryEntry = entry
                    selectedLiveryCarFolder = carFolder
                    selectedLiveryBytes = liveryBytes
                    selectedLiveryArray = liveryArray
                    selectedLiveryPayload = payload
                    selectedLiverySourceName = entry.name
                    selectedLiverySourceSize = liveryBytes.size.toLong()
                    liveryStatus.text = "Loaded $carFolder. ${liveryArray.length()} existing slot(s)."
                    renderLiverySlots(carFolder, liveryArray, target, allowAdd = true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    liveryStatus.text = e.message ?: "Could not inspect this car's livery slots."
                }
            } catch (e: OutOfMemoryError) {
                runOnUiThread {
                    liveryStatus.text = "Android ran out of memory while reading the livery list."
                }
            }
        }.start()
    }

    private fun renderLiverySlots(
        carFolder: String,
        liveryArray: org.json.JSONArray,
        target: LinearLayout = liverySlotsContainer,
        allowAdd: Boolean = true
    ) {
        target.removeAllViews()
        target.addView(
            text("$carFolder · ${liveryArray.length()} existing slot(s)", 16, Color.WHITE, bold = true),
            matchWrap(bottom = 9)
        )

        if (liveryArray.length() == 0) {
            target.addView(
                text("This car has no readable livery entries to use as a template.", 12, 0xFF9BAFC5.toInt()),
                matchWrap()
            )
            return
        }

        for (i in 0 until liveryArray.length()) {
            val item = liveryArray.optJSONObject(i) ?: continue
            val display = item.optString("display")
                .ifBlank { item.optString("file").ifBlank { "Livery ${i + 1}" } }
            val fileName = item.optString("file")
            val texture = item.optString("texture")
            val swatch = item.optString("swatch")
            val driver = item.optString("driver")
            val details = listOf(
                fileName,
                texture,
                if (driver.isNotBlank()) "Driver suit texture: $driver" else "",
                if (swatch.isNotBlank()) "Swatch: $swatch" else ""
            ).filter { it.isNotBlank() }.distinct()
            val slot = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(0xFF0B1725.toInt(), dp(10).toFloat())
            }
            target.addView(slot, matchWrap(bottom = 6))
            slot.addView(
                text("Slot ${i + 1}  ·  $display", 13, Color.WHITE, bold = true),
                matchWrap()
            )
            if (details.isNotEmpty()) {
                slot.addView(
                    text(details.joinToString("  ·  "), 11, 0xFF8FA4BA.toInt()),
                    matchWrap(top = 3)
                )
            }
            if (allowAdd) {
                val actions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                actions.addView(
                    actionButton("Edit entry") { showEditLiveryEntryDialog(i, target) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(6)
                    }
                )
                val replaceButton = actionButton("Replace preview image") {
                    chooseLiverySwatchImage(i, target)
                }
                replaceButton.isEnabled = swatch.isNotBlank()
                actions.addView(
                    replaceButton,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                target.addView(actions, matchWrap(top = 7))
            }
        }

        if (allowAdd) {
            target.addView(
                actionButton("Add a new livery slot") { prepareAddLiverySlot() },
                matchWrap(top = 9)
            )
        }
    }

    private fun showEditLiveryEntryDialog(liveryIndex: Int, target: LinearLayout) {
        val entry = selectedLiveryEntry ?: run {
            liveryStatus.text = "Select a car livery list first."
            return
        }
        val carFolder = selectedLiveryCarFolder
        val source = selectedLiveryBytes ?: run {
            liveryStatus.text = "Reload the livery list before editing an entry."
            return
        }
        val entries = selectedLiveryArray ?: return
        val item = entries.optJSONObject(liveryIndex) ?: return

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        fun addField(label: String, value: String, hint: String = label): EditText {
            content.addView(text(label, 12, Color.WHITE, bold = true), matchWrap(bottom = 4))
            return editBox(hint, value, numeric = false).also {
                content.addView(it, matchWrap(bottom = 9))
            }
        }

        val driverInput = addField(
            "Driver suit texture path",
            item.optString("driver"),
            "RR3 driver suit texture asset path"
        )
        val swatchInput = addField(
            "Preview image reference",
            item.optString("swatch"),
            "Swatch asset path"
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit livery \${liveryIndex + 1}")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save entry", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val driver = driverInput.text.toString().trim()
                val swatch = swatchInput.text.toString().trim()
                if (driver.length > 1024 || swatch.length > 1024) {
                    liveryStatus.text = "Driver and swatch fields must be at most 1024 characters."
                    return@setOnClickListener
                }

                val meshes = ArrayList<Int>()
                val materials = ArrayList<Int>()
                val materialArray = item.optJSONArray("materials") ?: org.json.JSONArray()
                for (i in 0 until materialArray.length()) {
                    val material = materialArray.optJSONObject(i) ?: continue
                    val mesh = material.optInt("mesh", -1)
                    val materialId = material.optInt("material", -1)
                    if (mesh >= 0 && materialId >= 0) {
                        meshes.add(mesh)
                        materials.add(materialId)
                    }
                }

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                liveryStatus.text = "Updating livery entry \${liveryIndex + 1}…"
                Thread {
                    try {
                        val updated = NativeBridge.nativeUpdateRr3Livery(
                            source,
                            liveryIndex,
                            item.optString("display"),
                            swatch,
                            item.optString("logo"),
                            item.optString("arms"),
                            driver,
                            item.optInt("flag", 1),
                            item.optInt("red", 0),
                            item.optInt("green", 0),
                            item.optInt("blue", 0),
                            meshes.toIntArray(),
                            materials.toIntArray()
                        ) ?: throw IllegalStateException("The RR3 livery writer rejected the edited entry.")
                        publishUpdatedLiveryList(entry, carFolder, updated, target)
                        runOnUiThread { dialog.dismiss() }
                    } catch (e: Exception) {
                        runOnUiThread {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                            liveryStatus.text = e.message ?: "Could not update this livery entry."
                        }
                    } catch (e: OutOfMemoryError) {
                        runOnUiThread {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                            liveryStatus.text = "Android ran out of memory while updating the livery entry."
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun publishUpdatedLiveryList(
        entry: GameDataFile,
        carFolder: String,
        updatedBytes: ByteArray,
        target: LinearLayout
    ) {
        val indexed = indexedGameFiles.any {
            it.relativePath.equals(entry.relativePath, ignoreCase = true)
        }
        val outputFile = if (indexed) {
            val assetListEntry = indexedGameFiles.firstOrNull {
                it.relativePath.substringAfterLast('/').equals(
                    "asset_list_base.txtCache.txt",
                    ignoreCase = true
                )
            } ?: throw IllegalStateException(
                "asset_list_base.txtCache.txt was not indexed. Re-index the depot before editing a livery."
            )
            val assetList = readGameFile(assetListEntry)
            val updatedAssetList = NativeBridge.nativeUpdateRr3AssetList(
                assetList,
                entry.relativePath,
                updatedBytes
            ) ?: throw IllegalStateException("Could not update the depot asset-list cache.")
            val zip = newCacheFile("rr3-livery-entry-update", ".zip")
            try {
                ZipOutputStream(FileOutputStream(zip).buffered()).use { output ->
                    output.putNextEntry(ZipEntry(entry.relativePath))
                    output.write(updatedBytes)
                    output.closeEntry()
                    output.putNextEntry(ZipEntry(assetListEntry.relativePath))
                    output.write(updatedAssetList)
                    output.closeEntry()
                }
                zip
            } catch (e: Exception) {
                zip.delete()
                throw e
            }
        } else {
            val file = newCacheFile("rr3-manual-livery-update", ".bin.nct")
            FileOutputStream(file).use { it.write(updatedBytes) }
            file
        }

        val payload = JSONObject(NativeBridge.nativeInspectRr3Liveries(updatedBytes))
        if (!payload.optBoolean("ok")) {
            outputFile.delete()
            throw IllegalStateException(payload.optString("error", "The updated livery list could not be read back."))
        }
        val array = payload.optJSONArray("liveries")
            ?: throw IllegalStateException("The updated livery list has no entries.")

        runOnUiThread {
            selectedLiveryBytes = updatedBytes
            selectedLiveryArray = array
            selectedLiveryPayload = payload
            selectedLiverySourceSize = updatedBytes.size.toLong()
            renderLiverySlots(carFolder, array, target, allowAdd = array.length() > 0)
            liveryStatus.text = if (indexed) {
                "Updated \${entry.name}. Export the ZIP and apply it to a backup of the depot."
            } else {
                "Updated \${entry.name}. Export the edited .bin.nct file and replace the original manually."
            }
            pendingTransformedData = null
            pendingTransformedFile?.delete()
            pendingTransformedFile = outputFile
            pendingTransformedSize = outputFile.length()
            pendingTransformedName = if (indexed) {
                "RR3-\${carFolder}-livery-list-update.zip"
            } else {
                entry.name
            }
            pendingOutputForDataEditor = false
            pendingOutputForBrowser = true
            pendingOutputForCarEditor = false
            launchRr3OutputPicker()
        }
    }

    private fun chooseLiverySwatchImage(liveryIndex: Int, target: LinearLayout) {
        val entry = selectedLiveryEntry ?: run {
            liveryStatus.text = "Select a car livery list first."
            return
        }
        val carFolder = selectedLiveryCarFolder
        val item = selectedLiveryArray?.optJSONObject(liveryIndex) ?: run {
            liveryStatus.text = "Reload the livery list before replacing a preview image."
            return
        }
        val swatchPath = item.optString("swatch").trim()
        if (swatchPath.isBlank()) {
            liveryStatus.text = "This livery has no preview-image reference. Set one in Edit entry first."
            return
        }

        // Open the picker before touching the depot. A failed asset scan must
        // never make this button appear to do nothing.
        pendingLiverySwatchEdit = PendingLiverySwatchEdit(
            liveryEntry = entry,
            carFolder = carFolder,
            liveryIndex = liveryIndex,
            swatchPath = swatchPath
        )
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(intent, requestLiverySwatchInput)
            liveryStatus.text = "Choose a PNG or JPEG for livery slot ${liveryIndex + 1}."
        } catch (e: Exception) {
            pendingLiverySwatchEdit = null
            liveryStatus.text = e.message ?: "Android could not open the image picker."
        }
    }

    private fun applyLiverySwatchImage(uri: Uri) {
        val pending = pendingLiverySwatchEdit ?: run {
            liveryStatus.text = "No preview image target is waiting."
            return
        }
        pendingLiverySwatchEdit = null
        liveryStatus.text = "Locating the preview texture and rebuilding the image…"
        Thread {
            var zip: File? = null
            try {
                if (indexedGameFiles.none {
                        it.relativePath.equals(pending.liveryEntry.relativePath, ignoreCase = true)
                    }) {
                    throw IllegalStateException(
                        "Preview replacement needs the indexed RR3 depot. Re-index Game Data and try again."
                    )
                }

                val carAssets = scanLiveryCarAssets(pending.liveryEntry, pending.carFolder)
                val normalised = pending.swatchPath
                    .replace('\\', '/')
                    .trimStart('/')
                    .lowercase(Locale.ROOT)
                val exact = carAssets.filter {
                    val candidate = it.relativePath
                        .replace('\\', '/')
                        .trimStart('/')
                        .lowercase(Locale.ROOT)
                    candidate == normalised || candidate.endsWith("/$normalised") ||
                        normalised.endsWith("/$candidate")
                }
                val matches = if (exact.isNotEmpty()) exact else {
                    val leaf = normalised.substringAfterLast('/')
                    carAssets.filter {
                        it.relativePath.substringAfterLast('/').equals(leaf, ignoreCase = true)
                    }
                }
                val textureEntry = matches.singleOrNull()
                    ?: throw IllegalStateException(
                        if (matches.isEmpty()) {
                            "Could not find the preview texture '${pending.swatchPath}' under vehicles/${pending.carFolder}. Check the Preview image reference in Edit entry."
                        } else {
                            "More than one texture matches '${pending.swatchPath}'. Set a unique preview image reference in Edit entry."
                        }
                    )

                val original = readGameFile(textureEntry)
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open the selected image.")
                val picture = input.use { it.readBytes() }
                val updatedTexture = NativeBridge.nativeReplaceRr3Texture(
                    textureEntry.relativePath,
                    original,
                    picture
                ) ?: throw IllegalStateException(
                    "The image could not be converted into this RR3 texture format. Try a PNG or JPEG."
                )
                val assetListEntry = indexedGameFiles.firstOrNull {
                    it.relativePath.substringAfterLast('/').equals(
                        "asset_list_base.txtCache.txt",
                        ignoreCase = true
                    )
                } ?: throw IllegalStateException(
                    "asset_list_base.txtCache.txt was not indexed. Re-index the depot before replacing a swatch."
                )
                val updatedAssetList = NativeBridge.nativeUpdateRr3AssetList(
                    readGameFile(assetListEntry),
                    textureEntry.relativePath,
                    updatedTexture
                ) ?: throw IllegalStateException("Could not update the depot asset-list cache.")
                zip = newCacheFile("rr3-livery-swatch-update", ".zip")
                ZipOutputStream(FileOutputStream(zip!!).buffered()).use { output ->
                    output.putNextEntry(ZipEntry(textureEntry.relativePath))
                    output.write(updatedTexture)
                    output.closeEntry()
                    output.putNextEntry(ZipEntry(assetListEntry.relativePath))
                    output.write(updatedAssetList)
                    output.closeEntry()
                }
                val ready = zip ?: throw IllegalStateException("The preview-image ZIP was not created.")
                runOnUiThread {
                    pendingTransformedData = null
                    pendingTransformedFile?.delete()
                    pendingTransformedFile = ready
                    pendingTransformedSize = ready.length()
                    pendingTransformedName = "RR3-${pending.carFolder}-livery-swatch-update.zip"
                    pendingOutputForDataEditor = false
                    pendingOutputForBrowser = true
                    pendingOutputForCarEditor = false
                    liveryStatus.text =
                        "Built replacement preview image for slot ${pending.liveryIndex + 1} (${textureEntry.relativePath}). Export the ZIP and apply it to a backup of the depot."
                    launchRr3OutputPicker()
                }
            } catch (e: Exception) {
                zip?.delete()
                runOnUiThread {
                    liveryStatus.text = e.message ?: "Could not replace the preview image."
                }
            } catch (e: OutOfMemoryError) {
                zip?.delete()
                runOnUiThread {
                    liveryStatus.text = "Android ran out of memory while replacing the preview image."
                }
            }
        }.start()
    }

    private fun indexedFileByLeaf(leafName: String): GameDataFile? =
        indexedGameFiles.firstOrNull {
            it.relativePath.substringAfterLast('/').equals(leafName, ignoreCase = true)
        }

    private fun loadLiveryPaintLabels() {
        if (indexedGameFiles.isEmpty()) {
            liveryPaintStatus.text = "Index the RR3 .depot in Game Data first."
            return
        }
        val paintEntry = indexedFileByLeaf("custom.paints.nct")
        val packEntry = indexedFileByLeaf("custom.paintpacks.nct")
        val gameTextEntry = indexedFileByLeaf("gametext.txt")
        val englishEntry = indexedFileByLeaf("text_en.txt")
        val missing = listOf(
            "custom.paints.nct" to paintEntry,
            "custom.paintpacks.nct" to packEntry,
            "gametext.txt" to gameTextEntry,
            "text_en.txt" to englishEntry
        ).filter { it.second == null }.map { it.first }
        if (missing.isNotEmpty()) {
            liveryPaintStatus.text =
                "Could not find ${missing.joinToString(", ")} in the indexed depot. Re-index Game Data and try again."
            return
        }

        liveryPaintStatus.text = "Reading paint categories and English label strings…"
        liveryPaintContainer.removeAllViews()
        Thread {
            try {
                val result = JSONObject(
                    NativeBridge.nativeInspectRr3PaintLabels(
                        readGameFile(paintEntry!!),
                        readGameFile(packEntry!!),
                        readGameFile(gameTextEntry!!),
                        readGameFile(englishEntry!!)
                    )
                )
                if (!result.optBoolean("ok")) {
                    throw IllegalStateException(
                        result.optString("error", "Could not inspect the RR3 paint labels.")
                    )
                }
                runOnUiThread {
                    renderLiveryPaintLabels(result)
                    val categoryCount = result.optJSONArray("packLabels")?.length() ?: 0
                    val paintCount = result.optJSONArray("paintOptions")?.length() ?: 0
                    liveryPaintStatus.text =
                        "Loaded $categoryCount paint-pack label(s) and $paintCount Road Cars paint option(s). Edits change English display text, not the underlying paint material or colour."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    liveryPaintStatus.text = e.message ?: "Could not load paint labels."
                }
            } catch (e: OutOfMemoryError) {
                runOnUiThread {
                    liveryPaintStatus.text = "Android ran out of memory while reading the paint label files."
                }
            }
        }.start()
    }

    private fun renderLiveryPaintLabels(payload: JSONObject) {
        liveryPaintContainer.removeAllViews()
        val packLabels = payload.optJSONArray("packLabels") ?: org.json.JSONArray()
        val paintOptions = payload.optJSONArray("paintOptions") ?: org.json.JSONArray()

        fun fallbackKeyName(key: String): String =
            key.substringAfterLast('_')
                .removeSuffix("NAME")
                .replace('_', ' ')
                .lowercase(Locale.ROOT)
                .replaceFirstChar { it.uppercase() }

        val special = (0 until packLabels.length()).mapNotNull { i ->
            packLabels.optJSONObject(i)
        }.filter {
            it.optString("key").endsWith("_SPECIAL", ignoreCase = true)
        }
        if (special.isNotEmpty()) {
            liveryPaintContainer.addView(
                text("MOTORSPORTS · OFFICIAL / SPECIAL LABEL", 11, 0xFF8FA4BA.toInt(), bold = true),
                matchWrap(bottom = 6)
            )
            for (item in special) {
                val key = item.optString("key")
                val current = item.optString("value")
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(10), dp(10))
                    background = rounded(0xFF0B1725.toInt(), dp(9).toFloat())
                }
                val label = text(
                    current.ifBlank { fallbackKeyName(key) },
                    13,
                    Color.WHITE,
                    bold = true
                )
                row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(actionButton("Edit") {
                    if (item.optBoolean("editable")) {
                        showPaintLabelEditDialog(
                            "Edit Motorsports label",
                            listOf(PaintLabelField(key, "Official / Special label", current))
                        )
                    } else {
                        liveryPaintStatus.text = "This paint-pack entry is a category marker without an English localisation line, so it is display-only."
                    }
                })
                liveryPaintContainer.addView(row, matchWrap(bottom = 6))
            }
        }

        liveryPaintContainer.addView(
            text("ROAD CARS · PAINT MATERIAL AND COLOUR", 11, 0xFF8FA4BA.toInt(), bold = true),
            matchWrap(top = 12, bottom = 6)
        )
        if (paintOptions.length() == 0) {
            liveryPaintContainer.addView(
                text("No Road Cars paint options were found in custom.paints.nct.", 12, 0xFF9BAFC5.toInt()),
                matchWrap()
            )
        }
        for (i in 0 until paintOptions.length()) {
            val item = paintOptions.optJSONObject(i) ?: continue
            val materialKey = item.optString("materialKey")
            val colourKey = item.optString("colourKey")
            if (materialKey.isBlank() && colourKey.isBlank()) continue
            val material = item.optString("material").ifBlank {
                if (materialKey.isBlank()) "" else fallbackKeyName(materialKey)
            }
            val colour = item.optString("colour").ifBlank {
                if (colourKey.isBlank()) "" else fallbackKeyName(colourKey)
            }
            val values = listOf(material, colour).filter { it.isNotBlank() }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(10), dp(10))
                background = rounded(0xFF0B1725.toInt(), dp(9).toFloat())
            }
            row.addView(
                text(values.joinToString("  ·  ").ifBlank { "Paint option ${i + 1}" }, 13, Color.WHITE, bold = true),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(actionButton("Edit") {
                val fields = mutableListOf<PaintLabelField>()
                if (materialKey.isNotBlank() && item.optBoolean("materialEditable")) {
                    fields.add(PaintLabelField(materialKey, "Paint material", material))
                }
                if (colourKey.isNotBlank() && item.optBoolean("colourEditable")) {
                    fields.add(PaintLabelField(colourKey, "Colour name", colour))
                }
                if (fields.isEmpty()) {
                    liveryPaintStatus.text = "This paint option is present in the paint table, but it has no matching English localisation entry to edit."
                } else {
                    showPaintLabelEditDialog("Edit Road Cars paint label", fields)
                }
            })
            liveryPaintContainer.addView(row, matchWrap(bottom = 6))
        }

        val otherPackLabels = (0 until packLabels.length()).mapNotNull { i ->
            packLabels.optJSONObject(i)
        }.filterNot { it.optString("key").endsWith("_SPECIAL", ignoreCase = true) }
        if (otherPackLabels.isNotEmpty()) {
            liveryPaintContainer.addView(
                text("PAINT-PACK CATEGORY NAMES", 11, 0xFF8FA4BA.toInt(), bold = true),
                matchWrap(top = 12, bottom = 6)
            )
            for (item in otherPackLabels) {
                val key = item.optString("key")
                val value = item.optString("value")
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(10), dp(10))
                    background = rounded(0xFF0B1725.toInt(), dp(9).toFloat())
                }
                row.addView(
                    text(value.ifBlank { fallbackKeyName(key) }, 13, Color.WHITE, bold = true),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                row.addView(actionButton("Edit") {
                    if (item.optBoolean("editable")) {
                        showPaintLabelEditDialog(
                            "Edit paint-pack category",
                            listOf(PaintLabelField(key, "Category name", value))
                        )
                    } else {
                        liveryPaintStatus.text = "This paint-pack category has no matching English localisation line, so it is display-only."
                    }
                })
                liveryPaintContainer.addView(row, matchWrap(bottom = 6))
            }
        }
    }

    private fun showPaintLabelEditDialog(title: String, fields: List<PaintLabelField>) {
        if (fields.isEmpty()) {
            liveryPaintStatus.text = "This option has no editable label fields in the available files."
            return
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val inputs = fields.map { field ->
            content.addView(
                text(field.label, 12, Color.WHITE, bold = true),
                matchWrap(bottom = 4)
            )
            val input = editBox(field.label, field.value, numeric = false)
            content.addView(input, matchWrap(bottom = 9))
            field to input
        }
        content.addView(
            text(
                "Fields are optional individually. Leave a field as-is to keep its current text; at least one field must contain text to save.",
                11,
                0xFF8499AF.toInt()
            ),
            matchWrap(top = 2)
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save labels", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val populated = inputs.mapNotNull { (field, input) ->
                    val value = input.text.toString().trim()
                    if (value.isBlank()) null else field.key to value
                }
                if (populated.isEmpty()) {
                    liveryPaintStatus.text =
                        "Keep at least one label populated (for example the Official label or the colour name)."
                    return@setOnClickListener
                }
                if (populated.any { it.second.length > 160 }) {
                    liveryPaintStatus.text = "Paint labels must be at most 160 characters."
                    return@setOnClickListener
                }
                saveLiveryPaintLabels(
                    populated.map { it.first }.toTypedArray(),
                    populated.map { it.second }.toTypedArray()
                ) {
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun saveLiveryPaintLabels(
        keys: Array<String>,
        values: Array<String>,
        onBuilt: () -> Unit
    ) {
        val gameTextEntry = indexedFileByLeaf("gametext.txt")
        val englishEntry = indexedFileByLeaf("text_en.txt")
        val assetListEntry = indexedFileByLeaf("asset_list_base.txtCache.txt")
        if (gameTextEntry == null || englishEntry == null || assetListEntry == null) {
            liveryPaintStatus.text =
                "gametext.txt, text_en.txt or asset_list_base.txtCache.txt is not indexed. Re-index the depot."
            return
        }

        liveryPaintStatus.text = "Updating the selected paint label text…"
        Thread {
            var zip: File? = null
            try {
                val result = JSONObject(
                    NativeBridge.nativeUpdateRr3PaintLabels(
                        readGameFile(gameTextEntry),
                        readGameFile(englishEntry),
                        keys,
                        values
                    )
                )
                if (!result.optBoolean("ok")) {
                    throw IllegalStateException(
                        result.optString("error", "The paint label writer rejected the changes.")
                    )
                }
                val changed = result.optJSONArray("files")
                    ?: throw IllegalStateException("The paint label writer returned no updated files.")
                if (changed.length() == 0) {
                    throw IllegalStateException("No paint label changes were needed.")
                }

                var updatedAssetList = readGameFile(assetListEntry)
                val changedFiles = ArrayList<Pair<String, ByteArray>>()
                for (i in 0 until changed.length()) {
                    val file = changed.optJSONObject(i) ?: continue
                    val name = file.optString("name")
                    val entry = indexedFileByLeaf(name)
                        ?: throw IllegalStateException("$name is not present in the indexed depot.")
                    val encoded = file.optString("data")
                    val bytes = try {
                        Base64.decode(encoded, Base64.DEFAULT)
                    } catch (_: IllegalArgumentException) {
                        throw IllegalStateException("The updated $name file could not be decoded.")
                    }
                    updatedAssetList = NativeBridge.nativeUpdateRr3AssetList(
                        updatedAssetList,
                        entry.relativePath,
                        bytes
                    ) ?: throw IllegalStateException("Could not update the asset-list entry for $name.")
                    changedFiles.add(entry.relativePath to bytes)
                }
                if (changedFiles.isEmpty()) {
                    throw IllegalStateException("No paint label files were rebuilt.")
                }

                zip = newCacheFile("rr3-paint-label-update", ".zip")
                ZipOutputStream(FileOutputStream(zip!!).buffered()).use { output ->
                    for ((path, bytes) in changedFiles) {
                        output.putNextEntry(ZipEntry(path))
                        output.write(bytes)
                        output.closeEntry()
                    }
                    output.putNextEntry(ZipEntry(assetListEntry.relativePath))
                    output.write(updatedAssetList)
                    output.closeEntry()
                }
                val ready = zip ?: throw IllegalStateException("The paint label ZIP was not created.")
                runOnUiThread {
                    pendingTransformedData = null
                    pendingTransformedFile?.delete()
                    pendingTransformedFile = ready
                    pendingTransformedSize = ready.length()
                    pendingTransformedName = "RR3-paint-label-update.zip"
                    pendingOutputForDataEditor = false
                    pendingOutputForBrowser = true
                    pendingOutputForCarEditor = false
                    liveryPaintStatus.text =
                        "Built ${changedFiles.size} updated text file(s). Export the ZIP and apply it to a backup of the depot."
                    onBuilt()
                    launchRr3OutputPicker()
                }
            } catch (e: Exception) {
                zip?.delete()
                runOnUiThread {
                    liveryPaintStatus.text = e.message ?: "Could not update the paint labels."
                }
            } catch (e: OutOfMemoryError) {
                zip?.delete()
                runOnUiThread {
                    liveryPaintStatus.text = "Android ran out of memory while updating paint labels."
                }
            }
        }.start()
    }

    private fun chooseManualLiveryFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, requestManualLiveryInput)
    }

    private fun openManualLiveryFile(uri: Uri) {
        liveryManualStatus.text = "Reading selected .bin.nct file…"
        liveryManualSlotsContainer.removeAllViews()
        selectedLiveryPayload = null
        selectedLiverySourceName = ""
        selectedLiverySourceSize = 0L
        Thread {
            try {
                val name = displayName(uri)
                if (!name.endsWith(".bin.nct", ignoreCase = true)) {
                    throw IllegalArgumentException("Choose a file ending in .bin.nct.")
                }
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open the selected file.")
                val bytes = input.use { it.readBytes() }
                val payload = JSONObject(NativeBridge.nativeInspectRr3Liveries(bytes))
                if (!payload.optBoolean("ok")) {
                    throw IllegalStateException(payload.optString("error", "This .bin.nct file is not a recognised livery list."))
                }
                val liveryArray = payload.optJSONArray("liveries")
                    ?: throw IllegalStateException("The selected file contains no readable livery entries.")
                val isCarLiveryList = name.endsWith(".liveries.bin.nct", ignoreCase = true)
                val carFolder = if (isCarLiveryList) {
                    name.removeSuffixIgnoreCaseLocal(".liveries.bin.nct")
                } else {
                    name
                }
                val canAddSlot = isCarLiveryList &&
                    carFolder.isNotBlank() &&
                    !carFolder.equals("default", ignoreCase = true) &&
                    !carFolder.contains('/') &&
                    !carFolder.contains("..")
                runOnUiThread {
                    selectedLiveryPayload = payload
                    selectedLiverySourceName = name
                    selectedLiverySourceSize = bytes.size.toLong()
                    if (canAddSlot) {
                        val entry = GameDataFile(
                            name = name,
                            relativePath = "vehicles/data/liveries/$name",
                            uri = uri,
                            sizeBytes = bytes.size.toLong()
                        )
                        selectedLiveryEntry = entry
                        selectedLiveryCarFolder = carFolder
                        selectedLiveryBytes = bytes
                        selectedLiveryArray = liveryArray
                        liveryManualStatus.text =
                            "Loaded $name · ${liveryArray.length()} existing slot(s). A new slot can be added when the matching car assets are available."
                        renderLiverySlots(
                            carFolder,
                            liveryArray,
                            liveryManualSlotsContainer,
                            allowAdd = liveryArray.length() > 0
                        )
                    } else {
                        selectedLiveryEntry = null
                        selectedLiveryCarFolder = ""
                        selectedLiveryBytes = null
                        selectedLiveryArray = null
                        liveryManualStatus.text =
                            "Inspected $name · ${liveryArray.length()} livery entry/entries. This file can be inspected here, but it is not a named car livery list for slot creation."
                        renderLiverySlots(
                            name,
                            liveryArray,
                            liveryManualSlotsContainer,
                            allowAdd = false
                        )
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    liveryManualStatus.text = e.message ?: "Could not inspect this .bin.nct file."
                }
            } catch (e: OutOfMemoryError) {
                runOnUiThread {
                    liveryManualStatus.text = "Android ran out of memory while reading the selected file."
                }
            }
        }.start()
    }

    private fun prepareAddLiverySlot() {
        val entry = selectedLiveryEntry ?: run {
            liveryStatus.text = "Select a car first."
            return
        }
        val carFolder = selectedLiveryCarFolder
        val liveryBytes = selectedLiveryBytes ?: run {
            liveryStatus.text = "Reload the car's livery list before adding a slot."
            return
        }
        val liveryArray = selectedLiveryArray ?: run {
            liveryStatus.text = "Reload the car's livery list before adding a slot."
            return
        }
        if (liveryArray.length() == 0) {
            liveryStatus.text = "This car has no existing livery to copy as a template."
            return
        }

        liveryStatus.text = "Preparing the texture assets for $carFolder…"
        Thread {
            try {
                val carAssets = scanLiveryCarAssets(entry, carFolder)
                val assetList = indexedGameFiles.firstOrNull {
                    it.relativePath.substringAfterLast('/').equals("asset_list_base.txtCache.txt", ignoreCase = true)
                } ?: throw IllegalStateException(
                    "The depot's asset_list_base.txtCache.txt was not indexed. Re-index the RR3 depot before adding a livery."
                )
                runOnUiThread {
                    liveryStatus.text = "Ready to add a livery slot to $carFolder."
                    showAddLiverySlotDialog(entry, carFolder, liveryBytes, liveryArray, carAssets, assetList)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    liveryStatus.text = e.message ?: "Could not prepare the livery editor."
                }
            } catch (e: OutOfMemoryError) {
                runOnUiThread {
                    liveryStatus.text = "Android ran out of memory while preparing the livery assets."
                }
            }
        }.start()
    }

    private fun showAddLiverySlotDialog(
        liveryEntry: GameDataFile,
        carFolder: String,
        liveryBytes: ByteArray,
        liveryArray: org.json.JSONArray,
        carAssets: List<GameDataFile>,
        assetListEntry: GameDataFile
    ) {
        val labels = ArrayList<String>()
        val templateIndices = ArrayList<Int>()
        for (i in 0 until liveryArray.length()) {
            val item = liveryArray.optJSONObject(i) ?: continue
            val display = item.optString("display").ifBlank { item.optString("file", "Livery ${i + 1}") }
            labels.add("${i + 1}. $display")
            templateIndices.add(i)
        }
        if (labels.isEmpty()) {
            liveryStatus.text = "No usable template livery records were found."
            return
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        content.addView(text(
            "Append a new slot to $carFolder. The selected livery is copied as the template and its body texture is recoloured. Existing slots are preserved.",
            12, 0xFF9BAFC5.toInt()
        ), matchWrap(bottom = 10))
        content.addView(text("Template livery", 12, Color.WHITE, bold = true), matchWrap(bottom = 5))
        val templateSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, labels).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(0)
        }
        content.addView(templateSpinner, matchWrap(bottom = 10))
        val nameInput = editBox("Livery display name", "", numeric = false)
        content.addView(text("Display name", 12, Color.WHITE, bold = true), matchWrap(bottom = 5))
        content.addView(nameInput, matchWrap(bottom = 10))

        content.addView(text("Button colour · RGB (0–255)", 12, Color.WHITE, bold = true), matchWrap(bottom = 5))
        val rgbRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val redInput = editBox("R", "200", numeric = true)
        val greenInput = editBox("G", "200", numeric = true)
        val blueInput = editBox("B", "200", numeric = true)
        for (input in listOf(redInput, greenInput, blueInput)) {
            rgbRow.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(6)
            })
        }
        content.addView(rgbRow, matchWrap())
        content.addView(text(
            "The update is exported as a ZIP containing the new texture, updated livery list and refreshed asset-cache file. Apply it to a backup of the depot and test it in-game.",
            11, 0xFF8499AF.toInt()
        ), matchWrap(top = 10))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add livery slot")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Build ZIP", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val liveryName = nameInput.text.toString().trim()
                if (liveryName.isBlank()) {
                    nameInput.error = "Enter a display name"
                    return@setOnClickListener
                }
                val red = redInput.text.toString().toIntOrNull()
                val green = greenInput.text.toString().toIntOrNull()
                val blue = blueInput.text.toString().toIntOrNull()
                if (red == null || green == null || blue == null ||
                    red !in 0..255 || green !in 0..255 || blue !in 0..255) {
                    liveryStatus.text = "Each RGB value must be a whole number from 0 to 255."
                    return@setOnClickListener
                }
                val templateIndex = templateIndices[templateSpinner.selectedItemPosition]
                val selected = liveryArray.optJSONObject(templateIndex)
                if (selected == null) {
                    liveryStatus.text = "The selected template livery is invalid."
                    return@setOnClickListener
                }
                val textureOverride = selected.optString("texture")
                val requestedStem = textureOverride.substringAfterLast('/').substringBefore('.')
                val donorAsset = carAssets.firstOrNull {
                    requestedStem.isNotBlank() &&
                        it.relativePath.substringAfterLast('/').lowercase(Locale.ROOT)
                            .startsWith(requestedStem.lowercase(Locale.ROOT) + ".")
                } ?: carAssets.firstOrNull {
                    it.relativePath.replace('\\', '/').contains("/livery/", ignoreCase = true)
                }
                if (donorAsset == null) {
                    liveryStatus.text = "Could not find the selected template's body texture. Re-index the car's depot files."
                    return@setOnClickListener
                }

                val sourcePairs = listOf(
                    liveryEntry.relativePath to liveryEntry,
                    donorAsset.relativePath to donorAsset,
                    assetListEntry.relativePath to assetListEntry
                )
                if (sourcePairs.map { it.first }.distinct().size != 3) {
                    liveryStatus.text = "The source asset paths are not unique; cannot safely stage this livery."
                    return@setOnClickListener
                }
                val assetPaths = (carAssets.map { it.relativePath } + sourcePairs.map { it.first })
                    .distinct()
                    .toTypedArray()

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                liveryStatus.text = "Repainting the donor texture and building the new livery slot…"
                Thread {
                    val workDir = File(cacheDir, "rr3-livery-${System.currentTimeMillis()}")
                    val sourceDir = File(workDir, "source")
                    val outputDir = File(workDir, "output")
                    var zipFile: File? = null
                    try {
                        if (!sourceDir.mkdirs() || !outputDir.mkdirs()) {
                            throw IllegalStateException("Could not create the temporary livery workspace.")
                        }
                        val cachedPathByAsset = HashMap<String, String>()
                        for ((index, sourcePair) in sourcePairs.withIndex()) {
                            val cached = File(sourceDir, "source_$index.bin")
                            if (sourcePair.first == liveryEntry.relativePath) {
                                cached.writeBytes(liveryBytes)
                            } else {
                                copyGameFileToFile(sourcePair.second, cached)
                            }
                            cachedPathByAsset[sourcePair.first] = cached.absolutePath
                        }
                        val sourcePaths = assetPaths.map { cachedPathByAsset[it].orEmpty() }.toTypedArray()
                        val result = JSONObject(
                            NativeBridge.nativeAddRr3Livery(
                                carFolder,
                                assetPaths,
                                sourcePaths,
                                outputDir.absolutePath,
                                liveryName,
                                templateIndex,
                                red,
                                green,
                                blue
                            )
                        )
                        if (!result.optBoolean("ok")) {
                            throw IllegalStateException(result.optString("error", "The RR3 livery writer failed."))
                        }
                        val outputFiles = result.optJSONArray("files")
                            ?: throw IllegalStateException("The livery writer returned no output files.")
                        if (outputFiles.length() < 2) {
                            throw IllegalStateException("The livery writer did not return the expected livery and texture files.")
                        }
                        val preparedZip = newCacheFile("rr3-livery-update", ".zip")
                        zipFile = preparedZip
                        ZipOutputStream(FileOutputStream(preparedZip).buffered()).use { zip ->
                            outputDir.walkTopDown().filter { it.isFile }.forEach { file ->
                                val relative = file.relativeTo(outputDir).invariantSeparatorsPath
                                zip.putNextEntry(ZipEntry(relative))
                                FileInputStream(file).use { it.copyTo(zip, 64 * 1024) }
                                zip.closeEntry()
                            }
                        }
                        val readyZip = zipFile ?: throw IllegalStateException("The livery update ZIP was not created.")
                        if (readyZip.length() <= 0L) throw IllegalStateException("The livery update ZIP is empty.")
                        val report = result.optString("report")
                        pendingTransformedData = null
                        pendingTransformedFile?.delete()
                        pendingTransformedFile = readyZip
                        pendingTransformedName = "RR3-${carFolder}-livery-update.zip"
                        pendingOutputForDataEditor = false
                        pendingOutputForBrowser = true
                        pendingOutputForCarEditor = false
                        runOnUiThread {
                            dialog.dismiss()
                            liveryStatus.text =
                                "Built livery update ZIP (${readyZip.length()} bytes). " +
                                report.lines().firstOrNull { it.contains("now.") }.orEmpty() +
                                " Choose where to save it."
                            launchRr3OutputPicker()
                        }
                    } catch (e: Exception) {
                        zipFile?.delete()
                        runOnUiThread {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                            liveryStatus.text = e.message ?: "Could not add the livery slot."
                        }
                    } catch (e: OutOfMemoryError) {
                        zipFile?.delete()
                        runOnUiThread {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                            liveryStatus.text = "Android ran out of memory while building the livery update. Original assets remain unchanged."
                        }
                    } finally {
                        workDir.deleteRecursively()
                    }
                }.start()
            }
        }
        dialog.show()
    }


    private fun showGameFileActions(entry: GameDataFile) {
        val actions = arrayListOf("Export original file")
        val lower = entry.name.lowercase()
        val effectiveName = lower.removeSuffix(".z")
        val isWrapped = lower.endsWith(".z") || lower.endsWith(".bin")
        if (isWrapped) actions.add("Unwrap RR3 container and export")
        if (lower == "events.dat.nct") actions.add("Inspect RR3 career data")
        if (entry.relativePath.replace('\\', '/').lowercase().startsWith("tracks/data/") && lower.endsWith(".bin.nct")) actions.add("Inspect RR3 track data")
        if (lower.matches(Regex("""\d+_.+\.bin\.nct"""))) actions.add("Open in RR3 car editor")
        if (lower.endsWith(".bin") || effectiveName.endsWith(".nct") ||
            effectiveName.endsWith(".gui") || effectiveName.endsWith(".xml")) {
            actions.add("Decode / encode RR3 data")
        }
        val extension = lower.removeSuffix(".z").removeSuffix(".bin")
            .substringAfterLast('.', "").lowercase()
        if (extension in setOf("sounddef", "evt", "tbl", "table", "txt")) {
            actions.add("Open in RR3 text data editor")
        }
        if (extension == "wem" || extension == "gnsu") {
            actions.add("Convert audio to WAV")
        }
        if (extension == "bnk") {
            actions.add("Extract Wwise audio bank")
        }
        if (extension == "points") {
            actions.add("Edit car points")
        }

        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "Export original file" -> exportGameFile(entry, unwrapOnly = false)
                    "Unwrap RR3 container and export" -> exportGameFile(entry, unwrapOnly = true)
                    "Decode / encode RR3 data" -> decodeGameDataFile(entry)
                    "Inspect RR3 career data" -> inspectCareerGameFile(entry)
                    "Inspect RR3 track data" -> inspectTrackGameFile(entry)
                    "Open in RR3 car editor" -> inspectCarGameFile(entry)
                    "Open in RR3 text data editor" -> editGameDataFile(entry)
                    "Convert audio to WAV" -> convertGameAudio(entry)
                    "Extract Wwise audio bank" -> extractGameAudioBank(entry)
                    "Edit car points" -> editGamePoints(entry)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun readGameFile(entry: GameDataFile): ByteArray {
        val rootPath = entry.rootPath
        if (!rootPath.isNullOrBlank()) {
            if (entry.isShizuku) {
                val depotPath = entry.depotRootPath
                    ?: throw IllegalStateException("The Shizuku RR3 folder path was lost. Re-detect the game folder.")
                val service = shizukuStorageService
                    ?: throw IllegalStateException("Shizuku disconnected. Start it again and retry.")
                val pipe = service.openFile(depotPath, rootPath)
                    ?: throw IllegalStateException("Shizuku could not open ${entry.relativePath}.")
                return ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes() }
            }
            val process = ProcessBuilder("su", "-c", "cat -- ${shellQuote(rootPath)}").start()
            val bytes = process.inputStream.use { it.readBytes() }
            val error = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw IllegalStateException(error.trim().ifBlank { "Could not read ${entry.relativePath} with root." })
            }
            return bytes
        }

        val uri = entry.uri ?: throw IllegalStateException("No readable location was stored for ${entry.relativePath}.")
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open ${entry.relativePath}.")
        return input.use { it.readBytes() }
    }

    private fun unwrappedGameFile(
        entry: GameDataFile,
        source: ByteArray,
        requireContainer: Boolean = false
    ): Pair<String, ByteArray> {
        val lower = entry.name.lowercase()
        if (!lower.endsWith(".z") && !lower.endsWith(".bin")) {
            if (requireContainer) throw IllegalStateException("This file is not an RR3 .z/.bin container.")
            return entry.name to source
        }
        val output = NativeBridge.nativeUnwrapRr3Asset(entry.name, source)
        if (output == null) {
            if (lower.endsWith(".z") || requireContainer) {
                throw IllegalStateException("The RR3 .z/.bin container could not be unpacked.")
            }
            // Some RR3 .bin files are data files rather than frame containers.
            return entry.name to source
        }
        val name = entry.name.removeSuffixIgnoreCaseLocal(".bin")
            .removeSuffixIgnoreCaseLocal(".z")
        return name to output
    }

    private fun exportGameFile(entry: GameDataFile, unwrapOnly: Boolean) {
        gameFilesStatus.text = "Reading ${entry.relativePath}…"
        Thread {
            try {
                val source = readGameFile(entry)
                val (name, bytes) = if (unwrapOnly) {
                    unwrappedGameFile(entry, source, requireContainer = true)
                } else {
                    entry.name to source
                }
                pendingTransformedFile?.delete()
                pendingTransformedFile = null
                pendingTransformedData = bytes
                pendingTransformedName = name
                pendingOutputForDataEditor = false
                pendingOutputForBrowser = true
                pendingOutputForCarEditor = false
                runOnUiThread {
                    gameFilesStatus.text = "Ready to export ${bytes.size} bytes."
                    launchRr3OutputPicker()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    gameFilesStatus.text = e.message ?: "Could not read the selected RR3 file."
                }
            }
        }.start()
    }

    private fun decodeGameDataFile(entry: GameDataFile) {
        gameFilesStatus.text = "Decoding ${entry.relativePath}…"
        val fileExtension = entry.name.substringAfterLast('.', "").lowercase()
        if (fileExtension == "nct") {
            Thread {
                val outputFile = newCacheFile("rr3-decoded")
                try {
                    val size = transformGameFileToCache(entry, outputFile)
                    pendingTransformedData = null
                    pendingTransformedFile?.delete()
                    pendingTransformedFile = outputFile
                    pendingTransformedSize = size
                    pendingTransformedName = entry.name.substringBeforeLast('.', entry.name)
                    pendingOutputForDataEditor = false
                    pendingOutputForBrowser = true
                    pendingOutputForCarEditor = false
                    runOnUiThread {
                        gameFilesStatus.text = "Decoded $size bytes using streaming NCT. Choose an output file."
                        launchRr3OutputPicker()
                    }
                } catch (e: Exception) {
                    outputFile.delete()
                    runOnUiThread { gameFilesStatus.text = e.message ?: "RR3 data decoding failed." }
                } catch (e: OutOfMemoryError) {
                    outputFile.delete()
                    runOnUiThread { gameFilesStatus.text = "Android ran out of memory while decoding this file." }
                }
            }.start()
            return
        }
        Thread {
            try {
                val source = readGameFile(entry)
                val (name, unpacked) = unwrappedGameFile(entry, source)
                val extension = name.substringAfterLast('.', "").lowercase()
                val output = when (extension) {
                    "gui" -> {
                        val plain = unpacked.size >= 5 &&
                            unpacked.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "<?xml"
                        if (plain) unpacked else NativeBridge.nativeTransformRr3Data(unpacked)
                            ?: throw IllegalStateException(NativeBridge.nativeGetRr3TransformError())
                    }
                    "xml" -> NativeBridge.nativeTransformRr3Data(unpacked)
                        ?: throw IllegalStateException(NativeBridge.nativeGetRr3TransformError())
                    "nct", "bin" -> NativeBridge.nativeTransformRr3Data(unpacked)
                        ?: throw IllegalStateException(NativeBridge.nativeGetRr3TransformError())
                    else -> unpacked
                }
                val outputName = when (extension) {
                    "gui" -> name.substringBeforeLast('.', name) + ".xml"
                    "xml" -> name.substringBeforeLast('.', name) + ".gui"
                    "nct" -> name.substringBeforeLast('.', name)
                    else -> name
                }
                pendingTransformedFile?.delete()
                pendingTransformedFile = null
                pendingTransformedData = output
                pendingTransformedName = outputName
                pendingOutputForDataEditor = false
                pendingOutputForBrowser = true
                pendingOutputForCarEditor = false
                runOnUiThread {
                    gameFilesStatus.text = "Decoded ${output.size} bytes. Choose an output file."
                    launchRr3OutputPicker()
                }
            } catch (e: Exception) {
                runOnUiThread { gameFilesStatus.text = e.message ?: "RR3 data decoding failed." }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { gameFilesStatus.text = "Android ran out of memory while decoding this file. Close other apps and retry." }
            }
        }.start()
    }

    private fun decodeGameTextFile(entry: GameDataFile) {
        gameFilesStatus.text = "Reading RR3 game text…"
        Thread {
            try {
                val source = readGameFile(entry)
                val (name, unpacked) = unwrappedGameFile(entry, source)
                val alreadyPlain = NativeBridge.nativeRr3TextIsPlain(unpacked)
                val output = if (alreadyPlain) unpacked else
                    NativeBridge.nativeTransformRr3Text(unpacked)
                        ?: throw IllegalStateException("The RR3 game-text transform failed.")
                pendingTransformedFile?.delete()
                pendingTransformedFile = null
                pendingTransformedData = output
                pendingTransformedName = name
                pendingOutputForDataEditor = false
                pendingOutputForBrowser = true
                pendingOutputForCarEditor = false
                runOnUiThread {
                    gameFilesStatus.text = "Decoded ${output.size} bytes. Choose an output file."
                    launchRr3OutputPicker()
                }
            } catch (e: Exception) {
                runOnUiThread { gameFilesStatus.text = e.message ?: "RR3 game-text decoding failed." }
            }
        }.start()
    }

    private fun convertGameAudio(entry: GameDataFile) {
        gameFilesStatus.text = "Converting RR3 audio to WAV…"
        Thread {
            try {
                val source = readGameFile(entry)
                val (name, unpacked) = unwrappedGameFile(entry, source)
                val wav = NativeBridge.nativeConvertRr3Audio(name, unpacked)
                    ?: throw IllegalStateException(NativeBridge.nativeGetRr3AudioError())
                pendingTransformedFile?.delete()
                pendingTransformedFile = null
                pendingTransformedData = wav
                pendingTransformedName = name.substringBeforeLast('.', name) + ".wav"
                pendingOutputForDataEditor = false
                pendingOutputForBrowser = true
                pendingOutputForCarEditor = false
                runOnUiThread {
                    gameFilesStatus.text = "Converted ${wav.size} bytes of WAV audio. Choose an output file."
                    launchRr3OutputPicker()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    gameFilesStatus.text = e.message ?: "RR3 audio conversion failed."
                }
            }
        }.start()
    }

    private fun extractGameAudioBank(entry: GameDataFile) {
        gameFilesStatus.text = "Extracting Wwise audio bank…"
        Thread {
            var archiveFile: File? = null
            try {
                val source = readGameFile(entry)
                val (name, bankBytes) = unwrappedGameFile(entry, source)
                val inspection = JSONObject(NativeBridge.nativeInspectRr3AudioBank(bankBytes))
                if (!inspection.optBoolean("ok")) {
                    throw IllegalStateException(
                        inspection.optString("error").takeIf { it.isNotBlank() }
                            ?: NativeBridge.nativeGetRr3AudioError()
                    )
                }

                val entries = inspection.optJSONArray("entries")
                    ?: throw IllegalStateException("The Wwise bank did not return an embedded-sound list.")
                val externalSourceCount = inspection.optJSONArray("sources")?.length() ?: 0
                if (entries.length() == 0) {
                    val detail = if (externalSourceCount > 0) {
                        " It references $externalSourceCount external media ID(s), which are stored separately in the depot."
                    } else {
                        ""
                    }
                    throw IllegalStateException("This bank contains no embedded audio files.$detail")
                }

                val bankName = name.substringBeforeLast('.', name)
                    .substringAfterLast('/')
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .ifBlank { "rr3_audio_bank" }
                val outputArchive = File(cacheDir, "rr3-bank-${System.currentTimeMillis()}.zip")
                archiveFile = outputArchive
                val usedIds = mutableSetOf<String>()
                var wavCount = 0

                ZipOutputStream(FileOutputStream(outputArchive)).use { archive ->
                    for (index in 0 until entries.length()) {
                        val sound = entries.getJSONObject(index)
                        val id = sound.optLong("id", -1L)
                        val offset = sound.optLong("offset", -1L)
                        val size = sound.optLong("size", -1L)
                        if (id < 0L || offset < 0L || size <= 0L ||
                            size > Int.MAX_VALUE.toLong() ||
                            offset > bankBytes.size.toLong() - size) {
                            throw IllegalStateException("SoundBank entry $index has an invalid data range.")
                        }

                        val kind = sound.optString("kind", "bin").lowercase(Locale.ROOT)
                        val extension = when (kind) {
                            "wem" -> "wem"
                            "gnsu" -> "gnsu"
                            else -> "bin"
                        }
                        val baseId = id.toString()
                        val fileStem = if (usedIds.add(baseId)) {
                            baseId
                        } else {
                            "$baseId-${index + 1}".also { usedIds.add(it) }
                        }
                        val start = offset.toInt()
                        val count = size.toInt()
                        val rawName = "$bankName/$fileStem.$extension"

                        // Preserve the exact original payload for every bank entry.
                        archive.putNextEntry(ZipEntry(rawName))
                        archive.write(bankBytes, start, count)
                        archive.closeEntry()

                        // Add a WAV sidecar when the Android-native decoder supports
                        // the embedded WEM/GNSU codec. Unsupported Wwise Vorbis stays
                        // available as its original .wem file.
                        if (kind == "wem" || kind == "gnsu") {
                            val soundBytes = bankBytes.copyOfRange(start, start + count)
                            val wav = NativeBridge.nativeConvertRr3Audio(
                                "$fileStem.$extension",
                                soundBytes
                            )
                            if (wav != null && wav.isNotEmpty()) {
                                archive.putNextEntry(ZipEntry("$bankName/$fileStem.wav"))
                                archive.write(wav)
                                archive.closeEntry()
                                wavCount++
                            }
                        }
                    }

                    inspection.put("sourceFile", entry.relativePath)
                    inspection.put("wavSidecars", wavCount)
                    inspection.put(
                        "extractionNote",
                        "Original embedded payloads are preserved. WAV sidecars are added only when this Android build can decode the sound codec; unsupported Wwise Vorbis remains as .wem."
                    )
                    archive.putNextEntry(ZipEntry("$bankName/bank-info.json"))
                    archive.write(inspection.toString(2).toByteArray(Charsets.UTF_8))
                    archive.closeEntry()
                }

                val readyFile = outputArchive
                if (!readyFile.isFile || readyFile.length() <= 0L) {
                    throw IllegalStateException("The extracted bank archive is empty.")
                }

                pendingTransformedFile?.delete()
                pendingTransformedData = null
                pendingTransformedFile = readyFile
                pendingTransformedName = "${bankName}_sounds.zip"
                pendingOutputForDataEditor = false
                pendingOutputForBrowser = true
                pendingOutputForCarEditor = false
                runOnUiThread {
                    gameFilesStatus.text =
                        "Extracted ${entries.length()} embedded sound(s); created $wavCount WAV sidecar(s). Save the ZIP to keep the original audio files."
                    launchRr3OutputPicker()
                }
            } catch (e: Exception) {
                archiveFile?.delete()
                runOnUiThread {
                    gameFilesStatus.text = e.message ?: "Wwise audio-bank extraction failed."
                }
            }
        }.start()
    }

    private fun editGamePoints(entry: GameDataFile) {
        gameFilesStatus.text = "Reading car points…"
        Thread {
            try {
                val source = readGameFile(entry)
                val (innerName, pointsBytes) = unwrappedGameFile(entry, source)
                val inspection = JSONObject(NativeBridge.nativeInspectRr3Points(pointsBytes))
                if (!inspection.optBoolean("ok")) {
                    throw IllegalStateException(
                        inspection.optString("error").takeIf { it.isNotBlank() }
                            ?: NativeBridge.nativeGetRr3PointsError()
                    )
                }
                val array = inspection.optJSONArray("points")
                    ?: throw IllegalStateException("The .points file did not return a point list.")
                val points = ArrayList<EditableRr3Hardpoint>(array.length())
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val basisJson = item.optJSONArray("basis")
                    val basis = FloatArray(9) { column ->
                        basisJson?.optDouble(column, if (column % 4 == 0) 1.0 else 0.0)?.toFloat()
                            ?: if (column % 4 == 0) 1f else 0f
                    }
                    points.add(
                        EditableRr3Hardpoint(
                            name = item.optString("name"),
                            x = item.optDouble("x").toFloat(),
                            y = item.optDouble("y").toFloat(),
                            z = item.optDouble("z").toFloat(),
                            hinge = item.optBoolean("hinge"),
                            basis = basis
                        )
                    )
                }
                if (points.isEmpty()) throw IllegalStateException("No editable points were found.")
                runOnUiThread {
                    gameFilesStatus.text = "Loaded ${points.size} car points."
                    showRr3PointsEditor(entry, innerName, pointsBytes, points)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    gameFilesStatus.text = e.message ?: "Could not open the car points file."
                }
            }
        }.start()
    }

    private fun showRr3PointsEditor(
        entry: GameDataFile,
        innerName: String,
        original: ByteArray,
        points: ArrayList<EditableRr3Hardpoint>
    ) {
        val originalPoints = points.map { point ->
            EditableRr3Hardpoint(
                point.name, point.x, point.y, point.z, point.hinge, point.basis.copyOf()
            )
        }
        var selected = 0
        val rowViews = ArrayList<TextView>(points.size)
        val names = points.map { it.name }.toTypedArray()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        root.addView(
            text(
                "${entry.relativePath} · ${points.size} points",
                11, 0xFF75B9F1.toInt(), bold = true
            ),
            matchWrap(bottom = 6)
        )
        root.addView(
            text(
                "Coordinates are in model units. Changes are exported as a separate file; the original remains untouched.",
                12, 0xFF9BAFC5.toInt()
            ),
            matchWrap(bottom = 10)
        )

        val listScroll = ScrollView(this).apply {
            isFillViewport = false
            background = rounded(0xFF091522.toInt(), dp(10).toFloat())
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        listScroll.addView(list)
        root.addView(listScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(235)
        ).apply { bottomMargin = dp(10) })

        val selectedTitle = text("", 13, Color.WHITE, bold = true)
        root.addView(selectedTitle, matchWrap(bottom = 4))
        val coordRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val xField = editBox("X", "", true)
        val yField = editBox("Y", "", true)
        val zField = editBox("Z", "", true)
        for ((index, field) in listOf(xField, yField, zField).withIndex()) {
            coordRow.addView(field, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                if (index > 0) marginStart = dp(6)
            })
        }
        root.addView(coordRow, matchWrap(bottom = 8))

        val setRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val setButton = actionButton("Apply coordinates") { }
        val mirrorCheck = android.widget.CheckBox(this).apply {
            text = "Mirror twin"
            textSize = 12f
            setTextColor(0xFFDCE8F5.toInt())
            buttonTintList = android.content.res.ColorStateList.valueOf(0xFF69B9F5.toInt())
            isChecked = true
        }
        setRow.addView(setButton, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginEnd = dp(8)
        })
        setRow.addView(mirrorCheck, LinearLayout.LayoutParams(-2, -2))
        root.addView(setRow, matchWrap(bottom = 8))

        val nudgeHeader = text("Move point", 12, 0xFF75B9F1.toInt(), bold = true)
        root.addView(nudgeHeader, matchWrap(bottom = 4))
        val stepRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stepRow.addView(text("Step", 12, 0xFF9BAFC5.toInt()), LinearLayout.LayoutParams(dp(42), -2))
        val stepSpinner = Spinner(this)
        val stepLabels = listOf("1 mm", "5 mm", "1 cm", "5 cm", "10 cm")
        stepSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, stepLabels
        )
        stepSpinner.setSelection(2)
        stepRow.addView(stepSpinner, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(stepRow, matchWrap(bottom = 4))

        val nudgeRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val nudgeRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val nudgeButtons = arrayOf(
            actionButton("X −") { moveBy(0, -1) },
            actionButton("X +") { moveBy(0, 1) },
            actionButton("Y −") { moveBy(1, -1) },
            actionButton("Y +") { moveBy(1, 1) },
            actionButton("Z −") { moveBy(2, -1) },
            actionButton("Z +") { moveBy(2, 1) }
        )
        for (index in 0..2) {
            nudgeRow1.addView(nudgeButtons[index * 2], LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginEnd = dp(5)
            })
            nudgeRow2.addView(nudgeButtons[index * 2 + 1], LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginEnd = dp(5)
            })
        }
        // Arrange pairs as X-/X+, Y-/Y+, Z-/Z+ in two compact rows.
        nudgeRow1.removeAllViews()
        nudgeRow2.removeAllViews()
        for (index in listOf(0, 2, 4)) {
            nudgeRow1.addView(nudgeButtons[index], LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginEnd = dp(5)
            })
            nudgeRow2.addView(nudgeButtons[index + 1], LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginEnd = dp(5)
            })
        }
        root.addView(nudgeRow1, matchWrap(bottom = 4))
        root.addView(nudgeRow2, matchWrap(bottom = 10))

        val hingeTitle = text("Hinge orientation", 12, 0xFF75B9F1.toInt(), bold = true)
        root.addView(hingeTitle, matchWrap(bottom = 3))
        val hingeInfo = text("", 11, 0xFF9BAFC5.toInt())
        root.addView(hingeInfo, matchWrap(bottom = 5))
        val angleSpinner = Spinner(this)
        val angleLabels = listOf("1°", "5°", "15°", "45°", "90°")
        angleSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, angleLabels
        )
        angleSpinner.setSelection(1)
        root.addView(angleSpinner, matchWrap(bottom = 5))

        val rotateRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rotateRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rotateButtons = arrayOf(
            actionButton("Rotate X −") { rotateHinge(0, -1) },
            actionButton("Rotate X +") { rotateHinge(0, 1) },
            actionButton("Rotate Y −") { rotateHinge(1, -1) },
            actionButton("Rotate Y +") { rotateHinge(1, 1) },
            actionButton("Rotate Z −") { rotateHinge(2, -1) },
            actionButton("Rotate Z +") { rotateHinge(2, 1) }
        )
        for (index in listOf(0, 2, 4)) {
            rotateRow1.addView(rotateButtons[index], LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginEnd = dp(5)
            })
            rotateRow2.addView(rotateButtons[index + 1], LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginEnd = dp(5)
            })
        }
        root.addView(rotateRow1, matchWrap(bottom = 4))
        root.addView(rotateRow2, matchWrap(bottom = 12))

        fun formatCoordinate(value: Float): String = String.format(Locale.US, "%.4f", value)
        fun updateRow(index: Int) {
            val point = points[index]
            rowViews[index].text = point.name + if (point.hinge) "  · hinge" else "" +
                "     " + formatCoordinate(point.x) + ", " +
                formatCoordinate(point.y) + ", " + formatCoordinate(point.z)
            rowViews[index].setTextColor(if (index == selected) Color.WHITE else 0xFFBDCCDC.toInt())
            rowViews[index].background = rounded(
                if (index == selected) 0xFF174A75.toInt() else 0x00000000,
                dp(7).toFloat()
            )
        }
        fun updateAllRows() { for (i in points.indices) updateRow(i) }
        fun updateHingeInfo() {
            val point = points[selected]
            if (!point.hinge) {
                hingeInfo.text = "Position only. The game reads orientation only from HINGE_ records."
                hingeInfo.alpha = 0.65f
                for (button in rotateButtons) button.isEnabled = false
                angleSpinner.isEnabled = false
            } else {
                val b = point.basis
                val yaw = Math.toDegrees(kotlin.math.atan2((-b[0]).toDouble(), (-b[2]).toDouble()))
                val pitch = Math.toDegrees(kotlin.math.asin(b[1].toDouble().coerceIn(-1.0, 1.0)))
                val roll = Math.toDegrees(kotlin.math.atan2(b[4].toDouble(), b[7].toDouble()))
                hingeInfo.text = String.format(
                    Locale.US, "Turn: yaw %.1f°, pitch %.1f°, roll %.1f°", yaw, pitch, roll
                )
                hingeInfo.alpha = 1f
                for (button in rotateButtons) button.isEnabled = true
                angleSpinner.isEnabled = true
            }
        }
        fun selectPoint(index: Int) {
            selected = index.coerceIn(points.indices)
            val point = points[selected]
            selectedTitle.text = point.name
            xField.setText(formatCoordinate(point.x))
            yField.setText(formatCoordinate(point.y))
            zField.setText(formatCoordinate(point.z))
            updateAllRows()
            updateHingeInfo()
        }
        fun twinIndex(index: Int): Int {
            val name = points[index].name
            val alternatives = listOf(
                "LEFT" to "RIGHT", "RIGHT" to "LEFT",
                "_FL" to "_FR", "_FR" to "_FL",
                "_BL" to "_BR", "_BR" to "_BL",
                "_RL" to "_RR", "_RR" to "_RL"
            )
            for ((from, to) in alternatives) {
                val at = name.indexOf(from)
                if (at >= 0) {
                    val twinName = name.replaceRange(at, at + from.length, to)
                    val found = points.indexOfFirst { it.name == twinName }
                    if (found >= 0) return found
                }
            }
            return -1
        }
        fun updateTwinPosition(index: Int, before: FloatArray, after: FloatArray) {
            if (!mirrorCheck.isChecked) return
            val twin = twinIndex(index)
            if (twin < 0) return
            points[twin].x -= after[0] - before[0]
            points[twin].y += after[1] - before[1]
            points[twin].z += after[2] - before[2]
            updateRow(twin)
        }
        fun applyPosition(values: FloatArray) {
            val point = points[selected]
            val before = floatArrayOf(point.x, point.y, point.z)
            point.x = values[0]
            point.y = values[1]
            point.z = values[2]
            updateTwinPosition(selected, before, values)
            selectPoint(selected)
        }
        fun parseFields(): FloatArray? {
            val fields = listOf(xField, yField, zField)
            val result = FloatArray(3)
            for (i in fields.indices) {
                val value = fields[i].text.toString().trim().toFloatOrNull()
                if (value == null || !value.isFinite()) return null
                result[i] = value
            }
            return result
        }
        fun moveBy(axis: Int, direction: Int) {
            val step = listOf(0.001f, 0.005f, 0.01f, 0.05f, 0.1f)
                .getOrElse(stepSpinner.selectedItemPosition.coerceAtLeast(0)) { 0.01f }
            val point = points[selected]
            val values = floatArrayOf(point.x, point.y, point.z)
            values[axis] += step * direction
            applyPosition(values)
        }
        fun rotateBasis(point: EditableRr3Hardpoint, axis: Int, direction: Int) {
            val angle = Math.toRadians(
                listOf(1.0, 5.0, 15.0, 45.0, 90.0)
                    .getOrElse(angleSpinner.selectedItemPosition.coerceAtLeast(0)) { 5.0 }
            ) * direction
            val c = kotlin.math.cos(angle).toFloat()
            val s = kotlin.math.sin(angle).toFloat()
            val matrix = FloatArray(9) { if (it % 4 == 0) 1f else 0f }
            val i = (axis + 1) % 3
            val j = (axis + 2) % 3
            matrix[i * 3 + i] = c
            matrix[i * 3 + j] = -s
            matrix[j * 3 + i] = s
            matrix[j * 3 + j] = c
            val old = point.basis.copyOf()
            for (r in 0..2) for (k in 0..2) {
                point.basis[r * 3 + k] =
                    matrix[k * 3] * old[r * 3] +
                    matrix[k * 3 + 1] * old[r * 3 + 1] +
                    matrix[k * 3 + 2] * old[r * 3 + 2]
            }
        }
        fun rotateHinge(axis: Int, direction: Int) {
            val point = points[selected]
            if (!point.hinge) return
            rotateBasis(point, axis, direction)
            if (mirrorCheck.isChecked) {
                val twin = twinIndex(selected)
                if (twin >= 0 && points[twin].hinge) {
                    val angle = Math.toRadians(
                        listOf(1.0, 5.0, 15.0, 45.0, 90.0)
                            .getOrElse(angleSpinner.selectedItemPosition.coerceAtLeast(0)) { 5.0 }
                    ) * direction
                    val c = kotlin.math.cos(angle).toFloat()
                    val s = kotlin.math.sin(angle).toFloat()
                    val matrix = FloatArray(9) { if (it % 4 == 0) 1f else 0f }
                    val i = (axis + 1) % 3
                    val j = (axis + 2) % 3
                    matrix[i * 3 + i] = c
                    matrix[i * 3 + j] = -s
                    matrix[j * 3 + i] = s
                    matrix[j * 3 + j] = c
                    for (k in 0..2) {
                        matrix[k] = -matrix[k]
                        matrix[k * 3] = -matrix[k * 3]
                    }
                    val old = points[twin].basis.copyOf()
                    for (r in 0..2) for (k in 0..2) {
                        points[twin].basis[r * 3 + k] =
                            matrix[k * 3] * old[r * 3] +
                            matrix[k * 3 + 1] * old[r * 3 + 1] +
                            matrix[k * 3 + 2] * old[r * 3 + 2]
                    }
                }
            }
            updateHingeInfo()
            updateRow(selected)
            val twin = twinIndex(selected)
            if (twin >= 0) updateRow(twin)
        }

        for (index in points.indices) {
            val row = text("", 12, 0xFFBDCCDC.toInt()).apply {
                setPadding(dp(9), dp(10), dp(9), dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener { selectPoint(index) }
            }
            rowViews.add(row)
            list.addView(row, matchWrap())
        }
        updateAllRows()

        setButton.setOnClickListener {
            val values = parseFields()
            if (values == null) {
                AlertDialog.Builder(this)
                    .setMessage("Enter valid finite X, Y and Z numbers.")
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                applyPosition(values)
            }
        }
        selectPoint(0)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Car points editor")
            .setView(root)
            .setNegativeButton("Close", null)
            .setPositiveButton("Build and export", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pointNames = Array(points.size) { points[it].name }
                val positions = FloatArray(points.size * 3)
                val bases = FloatArray(points.size * 9)
                for (i in points.indices) {
                    val point = points[i]
                    positions[i * 3] = point.x
                    positions[i * 3 + 1] = point.y
                    positions[i * 3 + 2] = point.z
                    for (k in 0..8) bases[i * 9 + k] = point.basis[k]
                }
                val updated = NativeBridge.nativeUpdateRr3Points(
                    original, pointNames, positions, bases
                ) ?: run {
                    AlertDialog.Builder(this)
                        .setMessage(NativeBridge.nativeGetRr3PointsError())
                        .setPositiveButton("OK", null)
                        .show()
                    return@setOnClickListener
                }
                val needsZWrap = entry.name.endsWith(".z", ignoreCase = true)
                val finalBytes = if (needsZWrap) {
                    NativeBridge.nativeWrapRr3PointsZ(updated)
                        ?: run {
                            AlertDialog.Builder(this)
                                .setMessage("The edited points could not be rebuilt into the RR3 .z container.")
                                .setPositiveButton("OK", null)
                                .show()
                            return@setOnClickListener
                        }
                } else updated

                pendingTransformedFile?.delete()
                pendingTransformedFile = null
                pendingTransformedData = finalBytes
                pendingTransformedName = if (needsZWrap) entry.name else innerName
                pendingOutputForDataEditor = false
                pendingOutputForBrowser = true
                pendingOutputForCarEditor = false
                dialog.dismiss()
                gameFilesStatus.text =
                    "Prepared edited car points. The rebuilt file passed its native read-back check."
                launchRr3OutputPicker()
            }
        }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.96f).toInt(),
            (resources.displayMetrics.heightPixels * 0.88f).toInt()
        )
    }

    private fun editGameDataFile(entry: GameDataFile) {
        closeDataEditorAction?.invoke()
        gameFilesStatus.text = "Opening " + entry.relativePath + "…"
        Thread {
            var textFile: File? = null
            var textPager: DiskBackedTextDocument? = null
            var handedToEditor = false
            try {
                // Plain UTF-8 .txt assets can be copied directly to disk. Avoid
                // readGameFile() + another full ByteArray just to create the
                // paging source. Wrapped .txt.z/.txt.bin assets still use the
                // normal RR3 unpacker below.
                val isDirectTextFile = entry.name.endsWith(".txt", ignoreCase = true)
                if (isDirectTextFile) {
                    val name = entry.name
                    val lowerName = name.lowercase(Locale.ROOT)
                    val isGameText = lowerName == "gametext.txt" ||
                        (lowerName.startsWith("text_") && lowerName.endsWith(".txt"))

                    textFile = newCacheFile("rr3-browser-text-source", ".txt")
                    copyGameFileToFile(entry, textFile!!)
                    var needsGameTextEncoding = false
                    if (isGameText) {
                        // The key index must begin with GAMETEXT_; do not let
                        // the generic binary heuristic misclassify this file.
                        needsGameTextEncoding = prepareRr3TextFileForEditing(
                            textFile!!,
                            isGameTextIndex = lowerName == "gametext.txt"
                        )
                    }

                    val pager = DiskBackedTextDocument(textFile!!)
                    textPager = pager
                    val firstPage = pager.readNextPage() ?: ""
                    handedToEditor = true
                    runOnUiThread {
                        gameFilesStatus.text = "Opened " + entry.relativePath + " in the text editor."
                        dataEditorStatus.text = "Opened " + name + " as a section-loaded text file."
                        selectTabFromView("Data Editor")
                        showSectionedTextFileEditor(
                            name, textFile!!, pager, firstPage,
                            encodeGameText = needsGameTextEncoding
                        )
                    }
                    return@Thread
                }

                val source = readGameFile(entry)
                val (name, bytes) = unwrappedGameFile(entry, source)
                val lowerName = name.lowercase(Locale.ROOT)
                val isGameText = lowerName == "gametext.txt" ||
                    (lowerName.startsWith("text_") && lowerName.endsWith(".txt"))
                if (name.substringAfterLast('.', "").equals("txt", ignoreCase = true)) {
                    textFile = newCacheFile("rr3-browser-text-source", ".txt")
                    FileOutputStream(textFile!!).use { it.write(bytes) }
                    val needsGameTextEncoding = if (isGameText) {
                        prepareRr3TextFileForEditing(
                            textFile!!,
                            isGameTextIndex = lowerName == "gametext.txt"
                        )
                    } else {
                        false
                    }
                    val pager = DiskBackedTextDocument(textFile!!)
                    textPager = pager
                    val firstPage = pager.readNextPage() ?: ""
                    handedToEditor = true
                    runOnUiThread {
                        gameFilesStatus.text = "Opened " + entry.relativePath + " in the text editor."
                        dataEditorStatus.text = "Opened " + name + " as a section-loaded text file."
                        selectTabFromView("Data Editor")
                        showSectionedTextFileEditor(
                            name, textFile!!, pager, firstPage,
                            encodeGameText = needsGameTextEncoding
                        )
                    }
                    return@Thread
                }

                val prepared = prepareBinaryDataEditorFile(name, bytes)
                runOnUiThread {
                    gameFilesStatus.text = "Opened " + entry.relativePath + "."
                    dataEditorStatus.text = "Opened " + name +
                        (if (prepared.reencodeNct) " (decoded RR3 NCT)." else ".")
                    selectTabFromView("Data Editor")
                    showEditableDataEditor(
                        name,
                        prepared.original,
                        prepared.editableText,
                        dataFormatName = prepared.formatName,
                        reencodeNct = prepared.reencodeNct
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    gameFilesStatus.text = e.message ?: "Could not open this RR3 data file."
                }
            } catch (e: OutOfMemoryError) {
                runOnUiThread {
                    gameFilesStatus.text = "Android ran out of memory while preparing this file. The original remains unchanged."
                }
            } finally {
                if (!handedToEditor) {
                    textPager?.close()
                    textPager?.deleteEditedPages()
                    textFile?.delete()
                }
            }
        }.start()
    }

    private fun String.removeSuffixIgnoreCaseLocal(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this



    private fun chooseTrackInspectorFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, requestTrackInspectorInput)
    }

    private fun inspectTrackGameFile(entry: GameDataFile) {
        trackInspectorStatus.text = "Reading ${entry.relativePath}…"
        Thread {
            try {
                val bytes = readGameFile(entry)
                val payload = JSONObject(NativeBridge.nativeInspectRr3Track(bytes))
                if (!payload.optBoolean("ok")) {
                    throw IllegalStateException(payload.optString("error", "Could not parse this track file."))
                }
                runOnUiThread {
                    renderTrackDetails(entry.name, bytes.size, payload)
                    trackInspectorStatus.text = "Inspected ${entry.relativePath} (${bytes.size} bytes)."
                    selectTabFromView("Track Inspector")
                }
            } catch (e: Exception) {
                runOnUiThread { trackInspectorStatus.text = e.message ?: "Could not inspect this track file." }
            }
        }.start()
    }

    private fun openTrackInspectorFile(uri: Uri) {
        trackInspectorStatus.text = "Reading track data file…"
        Thread {
            try {
                val name = displayName(uri)
                if (!name.lowercase().endsWith(".bin.nct")) {
                    throw IllegalArgumentException("Choose an RR3 track-data file ending in .bin.nct.")
                }
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open the selected track file.")
                val bytes = input.use { it.readBytes() }
                val payload = JSONObject(NativeBridge.nativeInspectRr3Track(bytes))
                if (!payload.optBoolean("ok")) {
                    throw IllegalStateException(payload.optString("error", "Could not parse this track file."))
                }
                runOnUiThread {
                    renderTrackDetails(name, bytes.size, payload)
                    trackInspectorStatus.text = "Inspected $name (${bytes.size} bytes)."
                }
            } catch (e: Exception) {
                runOnUiThread { trackInspectorStatus.text = e.message ?: "Could not inspect this track file." }
            }
        }.start()
    }

    private fun renderTrackDetails(name: String, size: Int, payload: JSONObject) {
        trackDetailsContainer.removeAllViews()
        val values = listOf(
            "Track ID" to payload.optInt("id").toString(),
            "Display name" to payload.optString("name"),
            "Track folder" to payload.optString("folder"),
            "Layout" to payload.optString("layout"),
            "Time of day" to payload.optString("time"),
            "Country" to payload.optString("country"),
            "Data file" to name,
            "File size" to "$size bytes"
        )
        for ((label, value) in values) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), dp(9), dp(11), dp(9))
                background = rounded(0xFF0B1725.toInt(), dp(10).toFloat())
            }
            trackDetailsContainer.addView(row, matchWrap(bottom = 6))
            row.addView(text(label, 11, 0xFF8FA4BA.toInt(), bold = true), matchWrap())
            row.addView(text(value.ifBlank { "—" }, 14, Color.WHITE), matchWrap(top = 3))
        }
    }

    private fun chooseCarEditorFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, requestCarEditorInput)
    }

    private fun chooseCareerInspectorFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, requestCareerInspectorInput)
    }

    private fun inspectCarGameFile(entry: GameDataFile) {
        carEditorStatus.text = "Reading ${entry.relativePath}…"
        Thread {
            try {
                val bytes = readGameFile(entry)
                val payload = JSONObject(NativeBridge.nativeInspectRr3Car(bytes))
                if (!payload.optBoolean("ok")) {
                    throw IllegalStateException(payload.optString("error", "Could not parse this car file."))
                }
                runOnUiThread {
                    currentCarOriginal = bytes
                    currentCarName = entry.name
                    renderCarFields(payload)
                    carEditorStatus.text = "Loaded ${entry.relativePath} (${bytes.size} bytes)."
                    selectTabFromView("Car Editor")
                }
            } catch (e: Exception) {
                runOnUiThread { carEditorStatus.text = e.message ?: "Could not inspect this car file." }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { carEditorStatus.text = "Android ran out of memory while reading this car file." }
            }
        }.start()
    }

    private fun inspectCareerGameFile(entry: GameDataFile) {
        careerInspectorStatus.text = "Streaming and inspecting ${entry.relativePath}…"
        Thread {
            val plainFile = newCacheFile("rr3-career-plain")
            try {
                val size = transformGameFileToCache(entry, plainFile)
                val payload = JSONObject(NativeBridge.nativeInspectRr3CareerFile(plainFile.absolutePath))
                if (!payload.optBoolean("ok")) {
                    throw IllegalStateException(payload.optString("error", "Could not parse this career file."))
                }
                runOnUiThread {
                    renderCareerDetails(entry.name, size.toInt(), payload)
                    careerInspectorStatus.text = "Inspected ${entry.relativePath} (${size} bytes)."
                    selectTabFromView("Career")
                }
            } catch (e: Exception) {
                runOnUiThread { careerInspectorStatus.text = e.message ?: "Could not inspect this career file." }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { careerInspectorStatus.text = "Android ran out of memory while inspecting this career file." }
            } finally {
                plainFile.delete()
            }
        }.start()
    }

    private fun openCarEditorFile(uri: Uri) {
        carEditorStatus.text = "Reading car data file…"
        Thread {
            try {
                val name = displayName(uri)
                if (!name.lowercase().endsWith(".bin.nct")) {
                    throw IllegalArgumentException("Choose a car data file ending in .bin.nct.")
                }
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open the selected car data file.")
                val bytes = input.use { it.readBytes() }
                val payload = JSONObject(NativeBridge.nativeInspectRr3Car(bytes))
                if (!payload.optBoolean("ok")) {
                    throw IllegalStateException(payload.optString("error", "Could not parse this car file."))
                }
                runOnUiThread {
                    currentCarOriginal = bytes
                    currentCarName = name
                    renderCarFields(payload)
                    carEditorStatus.text = "Loaded $name (${bytes.size} bytes)."
                }
            } catch (e: Exception) {
                runOnUiThread { carEditorStatus.text = e.message ?: "Could not inspect this car file." }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { carEditorStatus.text = "Android ran out of memory while reading this car file." }
            }
        }.start()
    }

    private fun renderCarFields(payload: JSONObject) {
        carFieldsContainer.removeAllViews()
        carFieldInputs.clear()
        val fields = payload.optJSONArray("fields") ?: return
        val original = linkedMapOf<String, String>()
        for (i in 0 until fields.length()) {
            val item = fields.optJSONObject(i) ?: continue
            val key = item.optString("key")
            if (key.isBlank() || key == "id") continue
            val label = item.optString("label", key)
            val value = item.optString("value")
            original[key] = value
            val fieldCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = rounded(0xFF0B1725.toInt(), dp(12).toFloat())
            }
            carFieldsContainer.addView(fieldCard, matchWrap(bottom = 8))
            fieldCard.addView(text(label, 12, 0xFF9BAFC5.toInt(), bold = true), matchWrap())
            val input = editBox("Value", value, numeric = !item.optBoolean("text") && key != "gear_ratios")
            fieldCard.addView(input, matchWrap(top = 7))
            carFieldInputs[key] = input
        }
        currentCarOriginalFields = original
        if (carFieldInputs.isEmpty()) {
            carFieldsContainer.addView(text("No editable fields were returned.", 12, 0xFF7F93A9.toInt()), matchWrap())
            return
        }
        carFieldsContainer.addView(
            actionButton("Build modified car file") { buildCarEditorOutput() },
            matchWrap(top = 8)
        )
    }

    private fun buildCarEditorOutput() {
        val original = currentCarOriginal ?: run {
            carEditorStatus.text = "Open a car data file first."
            return
        }
        // Capture every field on the UI thread before starting the worker.
        val editedValues = carFieldInputs.mapValues { (_, input) -> input.text.toString() }
        val changedValues = editedValues.filter { (key, value) ->
            value != currentCarOriginalFields[key]
        }
        if (changedValues.isEmpty()) {
            carEditorStatus.text = "No changes detected. Edit at least one field before building."
            return
        }

        carEditorStatus.text = "Rebuilding " + changedValues.size + " changed field(s)…"
        Thread {
            try {
                var output = original
                for ((key, value) in changedValues) {
                    output = NativeBridge.nativeSetRr3CarField(output, key, value)
                        ?: throw IllegalStateException(NativeBridge.nativeGetRr3CarError())
                }
                if (output.contentEquals(original)) {
                    throw IllegalStateException("The rebuilt data is identical to the original. Check that the edited values are different.")
                }
                pendingTransformedFile?.delete()
                pendingTransformedFile = null
                pendingTransformedData = output
                pendingTransformedName = currentCarName
                pendingOutputForDataEditor = false
                pendingOutputForBrowser = false
                pendingOutputForCarEditor = true
                runOnUiThread {
                    carEditorStatus.text = "Car data rebuilt successfully (" + output.size + " bytes). Choose a separate output file."
                    launchRr3OutputPicker()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    carEditorStatus.text = e.message ?: "Could not rebuild this car file."
                }
            } catch (e: OutOfMemoryError) {
                runOnUiThread {
                    carEditorStatus.text = "Android ran out of memory while rebuilding this car file. The original remains unchanged."
                }
            }
        }.start()
    }

    private fun openCareerInspectorFile(uri: Uri) {
        careerInspectorStatus.text = "Streaming and inspecting career file…"
        Thread {
            val plainFile = newCacheFile("rr3-career-plain")
            try {
                val name = displayName(uri)
                if (!name.equals("events.dat.nct", ignoreCase = true)) {
                    throw IllegalArgumentException("Choose the RR3 data/events.dat.nct file.")
                }
                val size = transformUriToCache(uri, plainFile)
                val payload = JSONObject(NativeBridge.nativeInspectRr3CareerFile(plainFile.absolutePath))
                if (!payload.optBoolean("ok")) {
                    throw IllegalStateException(payload.optString("error", "Could not parse this career file."))
                }
                runOnUiThread {
                    renderCareerDetails(name, size.toInt(), payload)
                    careerInspectorStatus.text = "Inspected $name (${size} bytes)."
                }
            } catch (e: Exception) {
                runOnUiThread { careerInspectorStatus.text = e.message ?: "Could not inspect this career file." }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { careerInspectorStatus.text = "Android ran out of memory while inspecting this career file." }
            } finally {
                plainFile.delete()
            }
        }.start()
    }

    private fun renderCareerDetails(name: String, size: Int, payload: JSONObject) {
        careerDetailsContainer.removeAllViews()
        val summary = text(
            "$name · ${size} bytes\nFormat version: 0x${payload.optInt("version").toString(16).uppercase()}\n" +
                "Tracks: ${payload.optInt("tracks")} · Series: ${payload.optInt("seriesCount")} · " +
                "Tiers: ${payload.optInt("tiersCount")} · Events: ${payload.optInt("eventsCount")} · " +
                "Career groups: ${payload.optInt("superGroups")}",
            13,
            Color.WHITE
        )
        careerDetailsContainer.addView(summary, matchWrap(bottom = 12))
        careerDetailsContainer.addView(
            text("SERIES (internal text keys)", 11, 0xFF75B9F1.toInt(), bold = true),
            matchWrap(bottom = 7)
        )
        val series = payload.optJSONArray("series")
        if (series != null) {
            val count = minOf(series.length(), 80)
            for (i in 0 until count) {
                val item = series.optJSONObject(i) ?: continue
                val row = "${item.optInt("id")}  ·  ${item.optString("titleKey")}\n" +
                    "${item.optString("descriptionKey")}  ·  type ${item.optInt("type")}"
                careerDetailsContainer.addView(
                    text(row, 11, 0xFFB7C8D9.toInt()),
                    matchWrap(bottom = 5)
                )
            }
            if (series.length() > count) {
                careerDetailsContainer.addView(
                    text("Showing the first $count of ${series.length()} series.", 11, 0xFF7F93A9.toInt()),
                    matchWrap(top = 4)
                )
            }
        }
        careerDetailsContainer.addView(
            text("TIER STRUCTURE", 11, 0xFF75B9F1.toInt(), bold = true),
            matchWrap(top = 12, bottom = 7)
        )
        val tiers = payload.optJSONArray("tiers")
        if (tiers != null) {
            val count = minOf(tiers.length(), 80)
            for (i in 0 until count) {
                val item = tiers.optJSONObject(i) ?: continue
                careerDetailsContainer.addView(
                    text(
                        "Tier ${item.optInt("id")} · series ${item.optInt("seriesId")} · " +
                            "${item.optInt("cars")} allowed cars · ${item.optInt("events")} events",
                        11,
                        0xFFB7C8D9.toInt()
                    ),
                    matchWrap(bottom = 4)
                )
            }
        }
    }

    private fun selectTabFromView(name: String) {
        selectTabAction?.invoke(name)
    }

    private fun chooseEditableDataFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, requestEditableDataInput)
    }

    /**
     * Opens an RR3 binary table as editable text. Plain .dat/.evt/.sounddef
     * files go straight to Monkey Tool's reversible listing. If that parser
     * specifically identifies the bytes as encrypted, try the RR3 NCT stream
     * and only accept the result when the native data parser recognises it.
     * An explicit .nct suffix always means decode first and re-encode on export.
     */
    private fun prepareBinaryDataEditorFile(
        sourceName: String,
        sourceBytes: ByteArray
    ): PreparedBinaryDataEditorFile {
        if (sourceBytes.isEmpty()) {
            throw IllegalArgumentException("The selected RR3 data file is empty.")
        }

        val hasNctSuffix = sourceName.endsWith(".nct", ignoreCase = true)
        val formatName = if (hasNctSuffix) sourceName.dropLast(4) else sourceName
        val decodedBytes = if (hasNctSuffix) {
            NativeBridge.nativeTransformRr3Data(sourceBytes)
                ?: throw IllegalStateException(NativeBridge.nativeGetRr3TransformError())
        } else {
            sourceBytes
        }

        val editable = NativeBridge.nativeOpenDataAsText(formatName, decodedBytes)
        if (editable != null) {
            return PreparedBinaryDataEditorFile(
                formatName = formatName,
                original = decodedBytes,
                editableText = editable,
                reencodeNct = hasNctSuffix
            )
        }

        val parseError = NativeBridge.nativeGetDataEditError()
        if (hasNctSuffix || !parseError.contains("encrypted", ignoreCase = true)) {
            throw IllegalStateException(parseError)
        }

        val nctDecoded = NativeBridge.nativeTransformRr3Data(sourceBytes)
            ?: throw IllegalStateException(
                "The file appears encrypted, but RR3 NCT decoding failed: " +
                    NativeBridge.nativeGetRr3TransformError()
            )
        val decodedEditable = NativeBridge.nativeOpenDataAsText(formatName, nctDecoded)
            ?: throw IllegalStateException(
                "RR3 NCT decoding did not produce a supported $formatName data table. " +
                    NativeBridge.nativeGetDataEditError()
            )

        return PreparedBinaryDataEditorFile(
            formatName = formatName,
            original = nctDecoded,
            editableText = decodedEditable,
            reencodeNct = true
        )
    }

    private fun openEditableDataFile(uri: Uri) {
        closeDataEditorAction?.invoke()
        dataEditorStatus.text = "Reading data file…"
        Thread {
            val careerFile = newCacheFile("rr3-career-source")
            val careerTextFile = newCacheFile("rr3-career-edit", ".txt")
            val plainTextFile = newCacheFile("rr3-plain-text-source", ".txt")
            var careerFilesHandedToEditor = false
            var careerPager: CareerTextPager? = null
            var plainTextHandedToEditor = false
            var plainTextPager: DiskBackedTextDocument? = null
            try {
                val name = displayName(uri)
                val lowerName = name.lowercase(Locale.ROOT)
                // events.dat is binary RRCE data, not raw UTF-8. Decode/copy it to
                // disk, then export its editable fields to a compact .txt working
                // file. The native writer merges edits into the original structure,
                // preserving unknown fields and opaque binary data.
                if (lowerName == "events.dat" || lowerName == "events.dat.nct") {
                    val decodedSize = if (lowerName.endsWith(".nct")) {
                        transformUriToCache(uri, careerFile)
                    } else {
                        copyUriToFile(uri, careerFile)
                        careerFile.length()
                    }
                    val summary = JSONObject(
                        NativeBridge.nativeExportRr3CareerText(
                            careerFile.absolutePath, careerTextFile.absolutePath
                        )
                    )
                    if (!summary.optBoolean("ok")) {
                        throw IllegalStateException(
                            summary.optString("error", "Could not create the editable career text.")
                        )
                    }
                    val pager = CareerTextPager(careerTextFile)
                    careerPager = pager
                    val firstPage = pager.readNextPage()
                        ?: throw IllegalStateException("The editable career text file contains no records.")
                    careerFilesHandedToEditor = true
                    runOnUiThread {
                        dataEditorStatus.text =
                            "Loaded events.dat (" + decodedSize + " bytes) as a sectioned editor. " +
                            summary.optInt("series") + " series, " +
                            summary.optInt("tiers") + " tiers, " +
                            summary.optInt("events") + " events."
                        showCareerEditableEditor(name, careerFile, careerTextFile, pager, firstPage, summary)
                    }
                    return@Thread
                }

                val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (extension == "txt") {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Could not open the selected text file.")
                    input.use { source ->
                        FileOutputStream(plainTextFile).use { destination ->
                            source.copyTo(destination, 256 * 1024)
                        }
                    }

                    val isGameText = lowerName == "gametext.txt" ||
                        (lowerName.startsWith("text_") && lowerName.endsWith(".txt"))
                    val needsGameTextEncoding = if (isGameText) {
                        prepareRr3TextFileForEditing(
                            plainTextFile,
                            isGameTextIndex = lowerName == "gametext.txt"
                        )
                    } else {
                        false
                    }

                    val pager = DiskBackedTextDocument(plainTextFile)
                    plainTextPager = pager
                    val firstPage = pager.readNextPage() ?: ""
                    plainTextHandedToEditor = true
                    runOnUiThread {
                        dataEditorStatus.text = "Opened $name as a section-loaded text file."
                        showSectionedTextFileEditor(
                            name, plainTextFile, pager, firstPage,
                            encodeGameText = needsGameTextEncoding
                        )
                    }
                    return@Thread
                }

                if (extension == "gui") {
                    throw IllegalArgumentException("Use the RR3 Data Transform section for this .gui file.")
                }
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open the selected file.")
                val sourceBytes = input.use { it.readBytes() }
                val prepared = prepareBinaryDataEditorFile(name, sourceBytes)
                runOnUiThread {
                    dataEditorStatus.text = "Opened " + name + " (" +
                        prepared.original.size + " decoded bytes" +
                        (if (prepared.reencodeNct) ", RR3 NCT" else "") + ")."
                    showEditableDataEditor(
                        name,
                        prepared.original,
                        prepared.editableText,
                        dataFormatName = prepared.formatName,
                        reencodeNct = prepared.reencodeNct
                    )
                }
            } catch (e: Exception) {
                runOnUiThread { dataEditorStatus.text = e.message ?: "Could not open this data file." }
            } catch (e: OutOfMemoryError) {
                runOnUiThread {
                    dataEditorStatus.text =
                        "Android ran out of memory while preparing the editable file. The original remains unchanged."
                }
            } finally {
                if (!careerFilesHandedToEditor) {
                    careerPager?.close()
                    careerFile.delete()
                    careerTextFile.delete()
                }
                if (!plainTextHandedToEditor) {
                    plainTextPager?.close()
                    plainTextFile.delete()
                }
            }
        }.start()
    }

    private fun keepOuterPageFromStealingEditorTouch(editor: EditText) {
        editor.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> rootScrollView.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> rootScrollView.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    /**
     * Let a vertical finger drag scroll the paging viewport instead of moving
     * the EditText caret. Screen coordinates stay stable while the ScrollView
     * itself moves; local event.y does not. A real text selection keeps native
     * long-press/selection dragging behaviour.
     */
    private fun attachVerticalSwipeScrolling(editor: EditText, viewport: ViewGroup) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var lastRawY = 0f
        var scrolling = false

        editor.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawY = event.rawY
                    scrolling = false
                    viewport.requestDisallowInterceptTouchEvent(true)
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val totalX = event.rawX - downRawX
                    val totalY = event.rawY - downRawY
                    val hasActiveTextSelection =
                        editor.selectionStart >= 0 &&
                            editor.selectionEnd >= 0 &&
                            editor.selectionStart != editor.selectionEnd

                    if (!scrolling && !hasActiveTextSelection &&
                        kotlin.math.abs(totalY) > touchSlop &&
                        kotlin.math.abs(totalY) > kotlin.math.abs(totalX)
                    ) {
                        scrolling = true
                    }

                    if (scrolling) {
                        viewport.scrollBy(0, (lastRawY - event.rawY).toInt())
                        lastRawY = event.rawY
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val consumed = scrolling
                    scrolling = false
                    viewport.requestDisallowInterceptTouchEvent(false)
                    consumed
                }

                else -> scrolling
            }
        }
    }

    private data class PagedTextMatch(
        val editor: EditText,
        val start: Int,
        val end: Int
    )

    /**
     * Search every section currently materialised in the paged editor.
     * Typing is debounced by one second, and Previous/Next navigate matches
     * without rebuilding or altering the text.
     */
    private fun addPagedTextFindControls(
        parent: LinearLayout,
        pageEditors: MutableList<EditText>,
        viewport: ScrollView
    ): () -> Unit {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val queryInput = editBox("Find in loaded text…", "", numeric = false)
        controls.addView(queryInput, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = dp(6) })

        val matches = mutableListOf<PagedTextMatch>()
        val watchedEditors = HashSet<EditText>()
        var selectedMatch = -1
        var matchAnchor: PagedTextMatch? = null
        var searchPending = false
        var preserveAnchorOnNextSearch = false
        var jumpToFirstOnNextSearch = true
        val status = text(
            "Enter text to find. Search runs 1 second after you stop typing. Only loaded sections are searched.",
            11, 0xFF8FA4BA.toInt()
        )
        val handler = Handler(Looper.getMainLooper())
        lateinit var searchRunnable: Runnable

        fun updateStatus() {
            status.text = when {
                queryInput.text.isNullOrBlank() -> "Enter text to find. Search runs 1 second after you stop typing."
                searchPending -> "Searching loaded sections…"
                matches.isEmpty() -> "No matches in the currently loaded sections."
                else -> "Match ${selectedMatch + 1} of ${matches.size} · loaded sections"
            }
        }

        fun goToMatch(index: Int) {
            if (matches.isEmpty()) return
            selectedMatch = ((index % matches.size) + matches.size) % matches.size
            val match = matches[selectedMatch]
            if (match.start < 0 || match.end > match.editor.text.length || match.start >= match.end) {
                matches.clear()
                selectedMatch = -1
                updateStatus()
                return
            }
            match.editor.requestFocus()
            match.editor.setSelection(match.start, match.end)
            match.editor.post {
                val layout = match.editor.layout
                if (layout != null && match.editor.isAttachedToWindow) {
                    val lineTop = layout.getLineTop(layout.getLineForOffset(match.start))
                    viewport.smoothScrollTo(0, (match.editor.top + lineTop - viewport.height / 3).coerceAtLeast(0))
                }
            }
            updateStatus()
        }

        fun rebuildMatches(preserveCurrent: Boolean, jumpToFirst: Boolean) {
            val previous = if (preserveCurrent) matchAnchor else null
            matchAnchor = null
            val query = queryInput.text.toString()
            matches.clear()
            selectedMatch = -1
            if (query.isNotEmpty()) {
                for (editor in pageEditors) {
                    val content = editor.text.toString()
                    var from = 0
                    while (from <= content.length - query.length) {
                        val found = content.indexOf(query, from, ignoreCase = true)
                        if (found < 0) break
                        matches.add(PagedTextMatch(editor, found, found + query.length))
                        from = found + maxOf(1, query.length)
                    }
                }
            }
            searchPending = false
            if (matches.isEmpty()) {
                updateStatus()
                return
            }
            val previousIndex = previous?.let { old ->
                matches.indexOfFirst { it.editor === old.editor && it.start == old.start && it.end == old.end }
            } ?: -1
            if (previousIndex >= 0) {
                selectedMatch = previousIndex
                updateStatus()
            } else {
                selectedMatch = 0
                if (jumpToFirst) goToMatch(0) else updateStatus()
            }
        }

        fun scheduleSearch(preserveCurrent: Boolean = false, jumpToFirst: Boolean = true) {
            if (preserveCurrent && selectedMatch in matches.indices) matchAnchor = matches[selectedMatch]
            else if (!preserveCurrent) matchAnchor = null
            preserveAnchorOnNextSearch = preserveCurrent
            jumpToFirstOnNextSearch = jumpToFirst
            matches.clear()
            selectedMatch = -1
            searchPending = queryInput.text.isNotBlank()
            handler.removeCallbacks(searchRunnable)
            updateStatus()
            if (queryInput.text.isBlank()) {
                searchPending = false
                matchAnchor = null
                updateStatus()
            } else handler.postDelayed(searchRunnable, 1000L)
        }

        searchRunnable = Runnable {
            val preserve = preserveAnchorOnNextSearch
            val jump = jumpToFirstOnNextSearch
            preserveAnchorOnNextSearch = false
            rebuildMatches(preserve, jump)
        }

        fun watchNewEditors() {
            for (editor in pageEditors) {
                if (!watchedEditors.add(editor)) continue
                editor.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        if (queryInput.text.isNotBlank()) scheduleSearch(preserveCurrent = false, jumpToFirst = false)
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
        }

        queryInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                scheduleSearch(false)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        val previousButton = actionButton("Prev") {
            if (!searchPending && matches.isNotEmpty()) goToMatch(selectedMatch - 1)
        }
        controls.addView(previousButton, LinearLayout.LayoutParams(
            dp(64), LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(5) })
        val nextButton = actionButton("Next") {
            if (!searchPending && matches.isNotEmpty()) goToMatch(selectedMatch + 1)
        }
        controls.addView(nextButton, LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(controls, matchWrap())
        panel.addView(status, matchWrap(top = 5))
        parent.addView(panel, matchWrap(bottom = 5))
        watchNewEditors()

        return {
            watchNewEditors()
            if (queryInput.text.isNotBlank()) scheduleSearch(preserveCurrent = true)
        }
    }

    private fun openDataEditorScreen(title: String) {
        editorScreenTitle.text = title
        dataEditorWorkspace.removeAllViews()
        rootScrollView.visibility = View.GONE
        editorScreen.visibility = View.VISIBLE
    }

    private fun closeDataEditorScreen() {
        if (!::editorScreen.isInitialized) return
        dataEditorWorkspace.removeAllViews()
        editorScreen.visibility = View.GONE
        rootScrollView.visibility = View.VISIBLE
        closeDataEditorAction = null
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        if (shizukuBound) runCatching {
            Shizuku.unbindUserService(shizukuServiceArgs, shizukuServiceConnection, true)
        }
        shizukuStorageService = null
        shizukuBound = false
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::editorScreen.isInitialized && editorScreen.visibility == View.VISIBLE) {
            closeDataEditorAction?.invoke() ?: closeDataEditorScreen()
        } else super.onBackPressed()
    }

    private fun showCareerEditableEditor(
        sourceName: String,
        sourcePlainFile: File,
        workingTextFile: File,
        pager: CareerTextPager,
        firstPage: CareerTextPage,
        summary: JSONObject
    ) {
        val encryptedSource = sourceName.lowercase(Locale.ROOT).endsWith(".nct")
        val pageEditors = mutableListOf<EditText>()
        var refreshFindResults: (() -> Unit)? = null
        var isLoadingPage = false
        var reachedEnd = false
        var building = false
        var closed = false
        var buildButton: TextView? = null
        var closeButton: TextView? = null

        val help = text(
            "Career data · " +
                summary.optInt("series") + " series · " +
                summary.optInt("tiers") + " tiers · " +
                summary.optInt("events") + " events. " +
                "Scroll through the file; more text is loaded before the bottom. Fixed IDs and indexes should be left unchanged.",
            12,
            0xFF9BAFC5.toInt()
        )
        val pageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B1725.toInt())
        }
        val editorScroll = ScrollView(this).apply {
            isFillViewport = false
            isSmoothScrollingEnabled = true
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setScrollBarSize(dp(12))
            }
            addView(
                pageContainer,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val loadStatus = text("Scroll to load more of the file…", 11, 0xFF75B9F1.toInt())

        fun addPage(page: CareerTextPage) {
            val rowEditor = EditText(this).apply {
                setText(page.text)
                setTextColor(Color.WHITE)
                setHintTextColor(0xFF6F849B.toInt())
                textSize = 11.5f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setHorizontallyScrolling(false)
                isVerticalScrollBarEnabled = false
                setPadding(dp(10), dp(2), dp(10), dp(2))
                background = rounded(0xFF0B1725.toInt(), 0f)
                val lineCount = maxOf(1, page.text.count { it == '\n' } + 1)
                minLines = minOf(lineCount, 4)
                maxLines = 100_000
            }
            attachVerticalSwipeScrolling(rowEditor, editorScroll)
            pageEditors.add(rowEditor)
            pageContainer.addView(rowEditor, matchWrap())
            refreshFindResults?.invoke()
        }

        fun closeEditor() {
            if (building) {
                loadStatus.text = "Wait for the rebuild to finish before closing the editor."
                return
            }
            if (closed) return
            closed = true
            pager.close()
            sourcePlainFile.delete()
            workingTextFile.delete()
            closeDataEditorScreen()
        }

        fun loadNextSection() {
            if (isLoadingPage || reachedEnd || building || closed) return
            isLoadingPage = true
            loadStatus.text = "Loading more career data…"
            Thread {
                var nextPage: CareerTextPage? = null
                var failure: String? = null
                try {
                    nextPage = pager.readNextPage()
                } catch (e: Exception) {
                    failure = e.message ?: "Could not read the next part of the file."
                }
                runOnUiThread {
                    isLoadingPage = false
                    if (closed) return@runOnUiThread
                    if (failure != null) {
                        reachedEnd = true
                        loadStatus.text = failure
                    } else if (nextPage == null) {
                        reachedEnd = true
                        loadStatus.text = "End of file."
                    } else {
                        addPage(nextPage!!)
                        loadStatus.text = "Keep scrolling; more text will load automatically."
                        editorScroll.post {
                            val child = editorScroll.getChildAt(0)
                            if (child != null &&
                                editorScroll.scrollY + editorScroll.height >= child.height - dp(600)
                            ) {
                                loadNextSection()
                            }
                        }
                    }
                }
            }.start()
        }

        fun buildOutput() {
            if (isLoadingPage || building || closed) {
                loadStatus.text = "Wait for the current loading operation to finish."
                return
            }
            building = true
            buildButton?.isEnabled = false
            closeButton?.isEnabled = false
            val editedPages = pageEditors.map { it.text.toString() }
            loadStatus.text = "Merging the edited text and rebuilding the career file…"
            dataEditorStatus.text = "Rebuilding " + sourceName + "…"

            Thread {
                val mergedTextFile = newCacheFile("rr3-career-merged", ".txt")
                val rebuiltPlain = newCacheFile("rr3-career-built")
                var outputFile: File? = null
                try {
                    pager.writeMerged(editedPages, mergedTextFile)
                    val build = JSONObject(
                        NativeBridge.nativeBuildRr3CareerText(
                            sourcePlainFile.absolutePath,
                            mergedTextFile.absolutePath,
                            rebuiltPlain.absolutePath
                        )
                    )
                    mergedTextFile.delete()
                    if (!build.optBoolean("ok")) {
                        throw IllegalStateException(
                            build.optString("error", "Could not rebuild the career file.")
                        )
                    }
                    if (encryptedSource) {
                        outputFile = newCacheFile("rr3-career-encoded")
                        FileInputStream(rebuiltPlain).use { input ->
                            FileOutputStream(outputFile!!).use { output ->
                                transformNctStream(input, output)
                            }
                        }
                        rebuiltPlain.delete()
                    } else {
                        outputFile = rebuiltPlain
                    }
                    val readyFile = outputFile
                        ?: throw IllegalStateException("The rebuilt career file was not created.")
                    pendingTransformedData = null
                    pendingTransformedFile?.delete()
                    pendingTransformedFile = readyFile
                    pendingTransformedSize = readyFile.length()
                    pendingTransformedName = sourceName
                    pendingOutputForDataEditor = true
                    pendingOutputForBrowser = false
                    pendingOutputForCarEditor = false
                    runOnUiThread {
                        building = false
                        closeEditor()
                        val outputKind = if (encryptedSource) "re-encoded .dat.nct" else "rebuilt .dat"
                        dataEditorStatus.text =
                            "Built " + outputKind + " (" + readyFile.length() + " bytes). Choose where to save it."
                        launchRr3OutputPicker()
                    }
                } catch (e: Exception) {
                    rebuiltPlain.delete()
                    mergedTextFile.delete()
                    outputFile?.delete()
                    runOnUiThread {
                        building = false
                        buildButton?.isEnabled = true
                        closeButton?.isEnabled = true
                        loadStatus.text = e.message ?: "Could not rebuild the career file."
                    }
                } catch (e: OutOfMemoryError) {
                    rebuiltPlain.delete()
                    mergedTextFile.delete()
                    outputFile?.delete()
                    runOnUiThread {
                        building = false
                        buildButton?.isEnabled = true
                        closeButton?.isEnabled = true
                        loadStatus.text =
                            "Android ran out of memory while rebuilding. The original file remains unchanged."
                    }
                }
            }.start()
        }

        addPage(firstPage)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(help, matchWrap(bottom = 8))
            refreshFindResults = addPagedTextFindControls(this, pageEditors, editorScroll)
            addView(
                editorScroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
            addView(loadStatus, matchWrap(top = 8, bottom = 8))
            val actions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            buildButton = actionButton("Build and export") { buildOutput() }
            closeButton = actionButton("Close editor") { closeEditor() }
            actions.addView(
                buildButton,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
            )
            actions.addView(
                closeButton,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(actions, matchWrap())
        }

        editorScroll.setOnScrollChangeListener { view: View, _, scrollY: Int, _, _ ->
            val child = (view as ScrollView).getChildAt(0)
            if (child != null && scrollY + view.height >= child.height - dp(600)) {
                loadNextSection()
            }
        }
        closeDataEditorAction = { closeEditor() }
        openDataEditorScreen(sourceName)
        dataEditorWorkspace.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
        ))
        editorScroll.post {
            val child = editorScroll.getChildAt(0)
            if (child != null && child.height <= editorScroll.height + dp(120)) {
                loadNextSection()
            }
        }
    }

    /**
     * Plain text and RR3 localisation files use the same section-at-a-time
     * editor interaction as events.dat.nct. The backing store remains
     * disk-based, so unopened sections are never materialised as EditTexts.
     */
    private fun showSectionedTextFileEditor(
        sourceName: String,
        sourceFile: File,
        pager: DiskBackedTextDocument,
        firstPage: String,
        encodeGameText: Boolean
    ) {
        val pageEditors = mutableListOf<EditText>()
        var refreshFindResults: (() -> Unit)? = null
        var loading = false
        var reachedEnd = false
        var building = false
        var closed = false
        var buildAction: () -> Unit = {}

        val help = text(
            "Text file · ${pager.pageCount} section(s) indexed. Scroll to load more; edits are saved section by section.",
            12,
            0xFF9BAFC5.toInt()
        )
        val pageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B1725.toInt())
        }
        val editorScroll = ScrollView(this).apply {
            isFillViewport = false
            isSmoothScrollingEnabled = true
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setScrollBarSize(dp(12))
            }
            addView(
                pageContainer,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val loadStatus = text("Scroll to load more of the file…", 11, 0xFF75B9F1.toInt())

        fun disposeEditor() {
            if (closed) return
            closed = true
            pager.close()
            pager.deleteEditedPages()
            sourceFile.delete()
            closeDataEditorScreen()
        }

        fun closeEditor() {
            if (building) {
                loadStatus.text = "Wait for the rebuild to finish before closing the editor."
                return
            }
            disposeEditor()
        }

        fun addPage(pageText: String) {
            val pageIndex = pageEditors.size
            val lineCount = maxOf(
                1,
                pageText.count { it == '\n' } +
                    if (pageText.endsWith("\n")) 0 else 1
            )
            val editor = EditText(this).apply {
                setText(pageText)
                setTextColor(Color.WHITE)
                setHintTextColor(0xFF6F849B.toInt())
                textSize = 11.5f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setHorizontallyScrolling(false)
                isVerticalScrollBarEnabled = false
                setPadding(dp(10), dp(2), dp(10), dp(2))
                background = rounded(0xFF0B1725.toInt(), 0f)
                minLines = minOf(lineCount, 4)
                // Each section is bounded; avoid the unbounded 100,000-line
                // measurement request used by the old RecyclerView editor.
                maxLines = (lineCount + 64).coerceIn(24, 4096)
                hint = "Section ${pageIndex + 1}"
            }
            attachVerticalSwipeScrolling(editor, editorScroll)
            pageEditors.add(editor)
            pageContainer.addView(editor, matchWrap())
            refreshFindResults?.invoke()
        }

        var checkNearBottom: (() -> Unit)? = null

        fun loadNextSection() {
            if (loading || reachedEnd || building || closed) return
            loading = true
            loadStatus.text = "Loading the next section…"
            Thread {
                var nextPage: String? = null
                var failure: String? = null
                try {
                    nextPage = pager.readNextPage()
                } catch (error: Exception) {
                    failure = error.message ?: "Could not read the next part of the text file."
                } catch (error: OutOfMemoryError) {
                    failure = "Android ran out of memory while loading this section."
                }
                runOnUiThread {
                    loading = false
                    if (closed) return@runOnUiThread
                    when {
                        failure != null -> {
                            reachedEnd = true
                            loadStatus.text = failure
                        }
                        nextPage == null -> {
                            reachedEnd = true
                            loadStatus.text = "End of file."
                        }
                        else -> {
                            addPage(nextPage!!)
                            loadStatus.text = "Keep scrolling; more sections load automatically."
                            editorScroll.post { checkNearBottom?.invoke() }
                        }
                    }
                }
            }.start()
        }

        checkNearBottom = {
            if (!closed && !loading && !reachedEnd && !building) {
                val child = editorScroll.getChildAt(0)
                if (child != null &&
                    editorScroll.scrollY + editorScroll.height >= child.height - dp(600)
                ) {
                    loadNextSection()
                }
            }
        }
        addPage(firstPage)
        editorScroll.setOnScrollChangeListener { view: View, _, scrollY: Int, _, _ ->
            val child = (view as ScrollView).getChildAt(0)
            if (child != null && scrollY + view.height >= child.height - dp(600)) {
                loadNextSection()
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(help, matchWrap(bottom = 8))
            refreshFindResults = addPagedTextFindControls(this, pageEditors, editorScroll)
            addView(
                editorScroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
            addView(loadStatus, matchWrap(top = 8, bottom = 8))
            val actions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val buildButton = actionButton("Build and export") { buildAction() }
            val closeButton = actionButton("Close editor") { closeEditor() }
            actions.addView(buildButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            })
            actions.addView(closeButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(actions, matchWrap(top = 8))
        }

        fun buildOutput() {
            if (loading || building || closed) {
                loadStatus.text = "Wait for the current section to finish loading."
                return
            }
            building = true
            // Snapshot EditText contents on the UI thread; the worker only
            // writes immutable strings to the disk-backed page overlays.
            val editedPages = pageEditors.map { it.text.toString() }
            pageEditors.forEach { it.isEnabled = false }
            loadStatus.text = "Saving edited sections and rebuilding the text file…"
            dataEditorStatus.text = "Building $sourceName…"

            Thread {
                val plainOutput = newCacheFile("rr3-text-edited", ".txt")
                var encodedOutput: File? = null
                var roundTripFile: File? = null
                try {
                    for (index in editedPages.indices) {
                        pager.saveEditedPage(index, editedPages[index])
                    }
                    pager.writeMerged(plainOutput, preserveLineCount = encodeGameText)
                    var readyFile = plainOutput
                    if (encodeGameText) {
                        encodedOutput = newCacheFile("rr3-text-encoded", ".txt")
                        val encodedSize = NativeBridge.nativeTransformRr3TextFile(
                            plainOutput.absolutePath, encodedOutput!!.absolutePath
                        )
                        if (encodedSize < 0L || encodedSize != plainOutput.length()) {
                            throw IllegalStateException("Could not encode the edited RR3 game text.")
                        }
                        roundTripFile = newCacheFile("rr3-text-roundtrip", ".txt")
                        val decodedSize = NativeBridge.nativeTransformRr3TextFile(
                            encodedOutput!!.absolutePath, roundTripFile!!.absolutePath
                        )
                        if (decodedSize != plainOutput.length() ||
                            !filesAreEqual(plainOutput, roundTripFile!!)
                        ) {
                            throw IllegalStateException(
                                "The rebuilt game text failed its decode check. The original has not been changed."
                            )
                        }
                        roundTripFile!!.delete()
                        roundTripFile = null
                        plainOutput.delete()
                        readyFile = encodedOutput!!
                    }

                    pendingTransformedData = null
                    pendingTransformedFile?.delete()
                    pendingTransformedFile = readyFile
                    pendingTransformedSize = readyFile.length()
                    pendingTransformedName = sourceName
                    pendingOutputForDataEditor = true
                    pendingOutputForBrowser = false
                    pendingOutputForCarEditor = false
                    runOnUiThread {
                        building = false
                        disposeEditor()
                        dataEditorStatus.text =
                            "Built ${readyFile.length()} bytes" +
                                (if (encodeGameText) " with RR3 game-text encoding." else ".") +
                                " Choose where to save the edited text."
                        launchRr3OutputPicker()
                    }
                } catch (error: Exception) {
                    plainOutput.delete()
                    encodedOutput?.delete()
                    roundTripFile?.delete()
                    runOnUiThread {
                        building = false
                        pageEditors.forEach { it.isEnabled = true }
                        loadStatus.text = error.message ?: "Could not rebuild the text file."
                    }
                } catch (error: OutOfMemoryError) {
                    plainOutput.delete()
                    encodedOutput?.delete()
                    roundTripFile?.delete()
                    runOnUiThread {
                        building = false
                        pageEditors.forEach { it.isEnabled = true }
                        loadStatus.text = "Android ran out of memory. The original text file remains unchanged."
                    }
                }
            }.start()
        }
        buildAction = { buildOutput() }

        closeDataEditorAction = { closeEditor() }
        openDataEditorScreen(sourceName)
        dataEditorWorkspace.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
        editorScroll.post { checkNearBottom?.invoke() }
    }

    private fun showPagedTextEditorInline(
        sourceName: String,
        sourceFile: File,
        pager: DiskBackedTextDocument,
        firstPage: String,
        encodeGameText: Boolean = false
    ) {
        var closed = false
        var building = false
        var searchGeneration = 0
        var activeHit: TextSearchHit? = null
        var pendingSearch: Runnable? = null
        val searchHandler = Handler(Looper.getMainLooper())

        val help = text(
            "Virtualised text editor · " + sourceName +
                ". 160-line pages are prepared ahead while scrolling; only a small page cache is kept in memory. Edited pages are stored separately, and Find searches the entire document.",
            12,
            0xFF9BAFC5.toInt()
        )

        val layoutManager = LinearLayoutManager(this)
        val editorList = RecyclerView(this).apply {
            setBackgroundColor(0xFF0B1725.toInt())
            this.layoutManager = layoutManager
            setHasFixedSize(false)
            setItemViewCacheSize(2)
            itemAnimator = null
            isNestedScrollingEnabled = false
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setScrollBarSize(dp(12))
            }
        }

        class PageHolder(val editor: EditText) : RecyclerView.ViewHolder(editor) {
            var pageIndex: Int = RecyclerView.NO_POSITION
            var watcher: TextWatcher? = null
            var dirty: Boolean = false
        }

        fun saveHolder(holder: PageHolder) {
            if (!holder.dirty || holder.pageIndex == RecyclerView.NO_POSITION) return
            pager.saveEditedPage(holder.pageIndex, holder.editor.text.toString())
            holder.dirty = false
        }

        val adapter = object : RecyclerView.Adapter<PageHolder>() {
            override fun getItemCount(): Int = pager.pageCount

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
                val editor = EditText(this@MainActivity).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setTextColor(Color.WHITE)
                    setHintTextColor(0xFF6F849B.toInt())
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    gravity = Gravity.TOP or Gravity.START
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    setHorizontallyScrolling(false)
                    isVerticalScrollBarEnabled = false
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    background = rounded(0xFF0B1725.toInt(), 0f)
                    minLines = 4
                    maxLines = 100_000
                }
                attachVerticalSwipeScrolling(editor, editorList)
                return PageHolder(editor)
            }

            override fun onBindViewHolder(holder: PageHolder, position: Int) {
                saveHolder(holder)
                holder.watcher?.let(holder.editor::removeTextChangedListener)
                holder.pageIndex = position
                holder.dirty = false
                holder.editor.setText(pager.readPage(position))
                holder.editor.hint = "Page " + (position + 1) + " of " + pager.pageCount

                val watcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        holder.dirty = true
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                }
                holder.watcher = watcher
                holder.editor.addTextChangedListener(watcher)
            }

            override fun onViewDetachedFromWindow(holder: PageHolder) {
                saveHolder(holder)
                super.onViewDetachedFromWindow(holder)
            }

            override fun onViewRecycled(holder: PageHolder) {
                saveHolder(holder)
                holder.watcher?.let(holder.editor::removeTextChangedListener)
                holder.watcher = null
                holder.pageIndex = RecyclerView.NO_POSITION
                super.onViewRecycled(holder)
            }
        }
        editorList.adapter = adapter
        editorList.setItemViewCacheSize(4)
        editorList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val first = layoutManager.findFirstVisibleItemPosition()
                val last = layoutManager.findLastVisibleItemPosition()
                if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION) {
                    // Keep several pages ahead/behind ready before a fast fling
                    // reaches them; the pager caps the cache to eight small pages.
                    pager.prefetchPages(
                        (first - 2).coerceAtLeast(0),
                        (last + 5).coerceAtMost(pager.pageCount - 1)
                    )
                }
            }
        })

        fun flushAttachedEditors() {
            for (index in 0 until editorList.childCount) {
                val child = editorList.getChildAt(index)
                val holder = editorList.getChildViewHolder(child) as? PageHolder ?: continue
                saveHolder(holder)
            }
        }

        val queryInput = editBox("Find in the entire file…", "", numeric = false)
        val searchStatus = text(
            "Ready · " + pager.pageCount + " page(s) indexed. Search includes text not yet displayed.",
            11,
            0xFF8FA4BA.toInt()
        )

        fun performSearch(forward: Boolean, restart: Boolean) {
            if (closed || building) return
            val query = queryInput.text.toString()
            if (query.isEmpty()) {
                searchGeneration++
                activeHit = null
                searchStatus.text = "Enter text to find in the entire file."
                return
            }

            val generation = ++searchGeneration
            val current = activeHit
            val startPage = if (restart || current == null) {
                if (forward) 0 else pager.pageCount - 1
            } else {
                current.pageIndex
            }
            val startOffset = if (restart || current == null) {
                if (forward) 0 else Int.MAX_VALUE
            } else {
                if (forward) current.end else current.start - 1
            }

            try {
                flushAttachedEditors()
            } catch (error: Exception) {
                searchStatus.text = error.message ?: "Could not save the current edited page before searching."
                return
            }

            searchStatus.text = "Searching the entire file…"
            Thread {
                val outcome = try {
                    pager.findMatch(query, startPage, startOffset, forward) to null
                } catch (error: Exception) {
                    null to (error.message ?: "Could not search this text file.")
                }
                runOnUiThread {
                    if (closed || generation != searchGeneration) return@runOnUiThread
                    val result = outcome.first
                    if (result == null) {
                        activeHit = null
                        searchStatus.text = outcome.second ?: "No match found in the entire file."
                        return@runOnUiThread
                    }

                    activeHit = result
                    searchStatus.text = "Found at line " + result.lineNumber +
                        " · page " + (result.pageIndex + 1) + " of " + pager.pageCount

                    layoutManager.scrollToPositionWithOffset(result.pageIndex, dp(18))
                    fun selectVisibleMatch(attempt: Int) {
                        editorList.post selectPost@{
                            if (closed || generation != searchGeneration) return@selectPost
                            val holder = editorList.findViewHolderForAdapterPosition(result.pageIndex) as? PageHolder
                            if (holder == null) {
                                if (attempt < 12) {
                                    editorList.postDelayed({ selectVisibleMatch(attempt + 1) }, 24L)
                                }
                                return@selectPost
                            }
                            holder.editor.requestFocus()
                            val safeStart = result.start.coerceIn(0, holder.editor.text.length)
                            val safeEnd = result.end.coerceIn(safeStart, holder.editor.text.length)
                            holder.editor.setSelection(safeStart, safeEnd)
                            holder.editor.post centerPost@{
                                val layout = holder.editor.layout ?: return@centerPost
                                val lineTop = layout.getLineTop(layout.getLineForOffset(safeStart))
                                editorList.smoothScrollBy(
                                    0,
                                    holder.editor.top + lineTop - editorList.height / 3
                                )
                            }
                        }
                    }
                    selectVisibleMatch(0)
                }
            }.start()
        }

        fun scheduleSearch() {
            pendingSearch?.let(searchHandler::removeCallbacks)
            // Invalidate a search already running on the worker thread as soon
            // as the query changes, not when the next debounce fires.
            searchGeneration++
            activeHit = null
            val query = queryInput.text.toString()
            if (query.isBlank()) {
                searchGeneration++
                searchStatus.text = "Enter text to find in the entire file."
                return
            }
            searchStatus.text = "Ready to search the entire file…"
            val task = Runnable { performSearch(forward = true, restart = true) }
            pendingSearch = task
            searchHandler.postDelayed(task, 300L)
        }

        queryInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                scheduleSearch()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val findButton = actionButton("Find") {
            pendingSearch?.let(searchHandler::removeCallbacks)
            performSearch(forward = true, restart = true)
        }
        val previousButton = actionButton("Prev") {
            pendingSearch?.let(searchHandler::removeCallbacks)
            performSearch(forward = false, restart = false)
        }
        val nextButton = actionButton("Next") {
            pendingSearch?.let(searchHandler::removeCallbacks)
            performSearch(forward = true, restart = false)
        }

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(queryInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(5)
            })
            addView(findButton, LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(4)
            })
            addView(previousButton, LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(4)
            })
            addView(nextButton, LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun closeEditor() {
            if (building) {
                searchStatus.text = "Wait for the output to finish building before closing the editor."
                return
            }
            if (closed) return
            closed = true
            searchGeneration++
            pendingSearch?.let(searchHandler::removeCallbacks)
            pager.close()
            pager.deleteEditedPages()
            sourceFile.delete()
            closeDataEditorScreen()
        }

        val closeButton = actionButton("Close editor") { closeEditor() }

        var buildButton: TextView? = null
        buildButton = actionButton("Build and export") {
            if (building || closed) return@actionButton
            try {
                flushAttachedEditors()
            } catch (error: Exception) {
                searchStatus.text = error.message ?: "Could not save the edited pages."
                return@actionButton
            }

            building = true
            buildButton?.isEnabled = false
            closeButton.isEnabled = false
            findButton.isEnabled = false
            previousButton.isEnabled = false
            nextButton.isEnabled = false
            queryInput.isEnabled = false
            searchStatus.text = "Merging edited pages into a new text file…"
            dataEditorStatus.text = "Building " + sourceName + "…"

            Thread {
                val outputFile = newCacheFile("rr3-text-edited", ".txt")
                var encodedFile: File? = null
                var roundTripFile: File? = null
                try {
                    pager.writeMerged(outputFile, preserveLineCount = encodeGameText)
                    var readyFile = outputFile
                    if (encodeGameText) {
                        encodedFile = newCacheFile("rr3-text-encoded", ".txt")
                        val encodedSize = NativeBridge.nativeTransformRr3TextFile(
                            outputFile.absolutePath,
                            encodedFile!!.absolutePath
                        )
                        if (encodedSize < 0L || encodedSize != outputFile.length()) {
                            throw IllegalStateException("Could not encode the edited RR3 game text.")
                        }

                        // Verify the stream cipher by decoding the output back
                        // to a second disk file, then compare without heap copies.
                        roundTripFile = newCacheFile("rr3-text-roundtrip", ".txt")
                        val decodedSize = NativeBridge.nativeTransformRr3TextFile(
                            encodedFile!!.absolutePath,
                            roundTripFile!!.absolutePath
                        )
                        if (decodedSize != outputFile.length() ||
                            !filesAreEqual(outputFile, roundTripFile!!)
                        ) {
                            throw IllegalStateException(
                                "The rebuilt game text failed its decode check. The original file has not been changed."
                            )
                        }
                        roundTripFile!!.delete()
                        roundTripFile = null
                        outputFile.delete()
                        readyFile = encodedFile!!
                    }
                    pendingTransformedData = null
                    pendingTransformedFile?.delete()
                    pendingTransformedFile = readyFile
                    pendingTransformedSize = readyFile.length()
                    pendingTransformedName = sourceName
                    pendingOutputForDataEditor = true
                    pendingOutputForBrowser = false
                    pendingOutputForCarEditor = false
                    runOnUiThread {
                        building = false
                        closeEditor()
                        dataEditorStatus.text =
                            "Built " + readyFile.length() + " bytes" +
                                (if (encodeGameText) " with RR3 game-text encoding." else ".") +
                                " Choose where to save the edited text."
                        launchRr3OutputPicker()
                    }
                } catch (error: Exception) {
                    outputFile.delete()
                    encodedFile?.delete()
                    roundTripFile?.delete()
                    runOnUiThread {
                        building = false
                        buildButton?.isEnabled = true
                        closeButton.isEnabled = true
                        findButton.isEnabled = true
                        previousButton.isEnabled = true
                        nextButton.isEnabled = true
                        queryInput.isEnabled = true
                        searchStatus.text = error.message ?: "Could not build the text file."
                    }
                } catch (error: OutOfMemoryError) {
                    outputFile.delete()
                    encodedFile?.delete()
                    roundTripFile?.delete()
                    runOnUiThread {
                        building = false
                        buildButton?.isEnabled = true
                        closeButton.isEnabled = true
                        findButton.isEnabled = true
                        previousButton.isEnabled = true
                        nextButton.isEnabled = true
                        queryInput.isEnabled = true
                        searchStatus.text = "Android ran out of memory. The original text file remains unchanged."
                    }
                }
            }.start()
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(help, matchWrap(bottom = 8))
            addView(searchRow, matchWrap(bottom = 3))
            addView(searchStatus, matchWrap(top = 3, bottom = 7))
            addView(
                editorList,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
            val actions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(buildButton!!, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                })
                addView(closeButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            addView(actions, matchWrap(top = 8))
        }

        closeDataEditorAction = { closeEditor() }
        openDataEditorScreen(sourceName)
        dataEditorWorkspace.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))
        if (pager.pageCount == 0) {
            searchStatus.text = "The text file is empty."
        } else {
            searchStatus.text = "Ready · " + pager.pageCount +
                " page(s) indexed. Search includes text not yet displayed."
        }
        editorList.scrollToPosition(0)
        editorList.post {
            pager.prefetchPages(1, minOf(5, pager.pageCount - 1))
        }
    }

    private fun showEditableDataEditor(
        name: String,
        original: ByteArray,
        editableText: String,
        dataFormatName: String = name,
        reencodeNct: Boolean = false
    ) {
        val editor = EditText(this).apply {
            setText(editableText)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF6F849B.toInt())
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(false)
            isVerticalScrollBarEnabled = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF0B1725.toInt(), dp(8).toFloat())
            minLines = 18
            maxLines = 100_000
        }
        val status = text(
            "Edit the decoded representation. The original remains unchanged until you choose a separate output.",
            12,
            0xFF9BAFC5.toInt()
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val buildButton = actionButton("Build and export") { }
        val closeButton = actionButton("Close editor") {
            closeDataEditorAction?.invoke()
        }
        actions.addView(
            buildButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        )
        actions.addView(
            closeButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                text("Source: " + name, 11, 0xFF75B9F1.toInt(), bold = true),
                matchWrap(bottom = 8)
            )
            addView(editor, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(status, matchWrap(top = 8, bottom = 8))
            addView(actions, matchWrap())
        }

        closeDataEditorAction = { closeDataEditorScreen() }
        buildButton.setOnClickListener {
            buildButton.isEnabled = false
            closeButton.isEnabled = false
            val editedText = editor.text.toString()
            status.text = "Rebuilding the RR3 data file…"
            dataEditorStatus.text = "Rebuilding " + name + "…"
            Thread {
                try {
                    val rebuiltPlain = NativeBridge.nativeBuildEditedData(
                        dataFormatName, editedText, original
                    ) ?: throw IllegalStateException(NativeBridge.nativeGetDataEditError())
                    val output = if (reencodeNct) {
                        val encoded = NativeBridge.nativeTransformRr3Data(rebuiltPlain)
                            ?: throw IllegalStateException(NativeBridge.nativeGetRr3TransformError())
                        val roundTrip = NativeBridge.nativeTransformRr3Data(encoded)
                            ?: throw IllegalStateException(
                                "The rebuilt RR3 NCT file could not be decoded for verification: " +
                                    NativeBridge.nativeGetRr3TransformError()
                            )
                        if (!roundTrip.contentEquals(rebuiltPlain)) {
                            throw IllegalStateException(
                                "The rebuilt RR3 NCT file failed its decode check. The original is unchanged."
                            )
                        }
                        encoded
                    } else {
                        rebuiltPlain
                    }
                    pendingTransformedFile?.delete()
                    pendingTransformedFile = null
                    pendingTransformedData = output
                    pendingTransformedName = name
                    pendingOutputForDataEditor = true
                    pendingOutputForBrowser = false
                    pendingOutputForCarEditor = false
                    runOnUiThread {
                        closeDataEditorAction?.invoke()
                        dataEditorStatus.text =
                            "Built " + output.size + " bytes" +
                                (if (reencodeNct) " with RR3 NCT encoding." else ".") +
                                " Choose a new output file."
                        launchRr3OutputPicker()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        buildButton.isEnabled = true
                        closeButton.isEnabled = true
                        status.text = e.message ?: "Could not rebuild the data file."
                    }
                }
            }.start()
        }

        openDataEditorScreen(name)
        dataEditorWorkspace.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun chooseRr3DataFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, requestRr3TransformInput)
    }

    private fun displayName(uri: Uri): String {
        val cursor = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        ) ?: return uri.lastPathSegment ?: "rr3-data.bin"
        cursor.use {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && it.moveToFirst()) return it.getString(index)
        }
        return uri.lastPathSegment ?: "rr3-data.bin"
    }

    private fun transformRr3File(uri: Uri) {
        dataStatus.text = "Reading RR3 data file…"
        Thread {
            try {
                val name = displayName(uri)
                val lower = name.lowercase()
                val extension = name.substringAfterLast('.', "").lowercase()
                val isGameText = extension == "txt" &&
                    (lower == "gametext.txt" ||
                        (lower.startsWith("text_") && lower.length > 9))
                if (extension !in setOf("nct", "bin", "gui", "xml") && !isGameText) {
                    throw IllegalArgumentException("Choose .nct/.bin/.gui/.xml or RR3 gametext/text_<language>.txt.")
                }

                if (isGameText) {
                    // All RR3 language files now use the same disk-backed,
                    // paged editor as files opened from the Game Data browser.
                    // Do not materialise the full gametext file in a ByteArray.
                    runOnUiThread { openEditableDataFile(uri) }
                    return@Thread
                }


                if (extension == "nct") {
                    val outputFile = newCacheFile("rr3-decoded")
                    try {
                        val size = transformUriToCache(uri, outputFile)
                        pendingTransformedData = null
                        pendingTransformedFile?.delete()
                        pendingTransformedFile = outputFile
                        pendingTransformedSize = size
                        pendingOutputForDataEditor = false
                        pendingOutputForBrowser = false
                        pendingOutputForCarEditor = false
                        pendingTransformedName = name.substringBeforeLast('.', name)
                        runOnUiThread {
                            dataStatus.text = "Decoded $size bytes using streaming NCT. Choose where to save the output."
                            launchRr3OutputPicker()
                        }
                    } catch (e: Exception) {
                        outputFile.delete()
                        throw e
                    }
                    return@Thread
                }

                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open the selected file.")
                val bytes = input.use { it.readBytes() }

                val alreadyPlainGui = extension == "gui" &&
                    bytes.size >= 5 &&
                    bytes.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "<?xml"
                val transformed = if (alreadyPlainGui) {
                    bytes
                } else {
                    NativeBridge.nativeTransformRr3Data(bytes)
                        ?: throw IllegalStateException(NativeBridge.nativeGetRr3TransformError())
                }

                val baseName = name.substringBeforeLast('.', name)
                pendingTransformedFile?.delete()
                pendingTransformedFile = null
                pendingTransformedData = transformed
                pendingOutputForDataEditor = false
                pendingOutputForBrowser = false
                pendingOutputForCarEditor = false
                pendingTransformedName = when (extension) {
                    "nct" -> baseName
                    "bin" -> baseName + ".nct"
                    "gui" -> baseName + ".xml"
                    "xml" -> baseName + ".gui"
                    else -> "rr3-data.bin"
                }

                runOnUiThread {
                    dataStatus.text = "Transformed " + transformed.size + " bytes. Choose where to save the output."
                    launchRr3OutputPicker()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dataStatus.text = e.message ?: "RR3 data transform failed."
                }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { dataStatus.text = "Android ran out of memory while transforming this file. Close other apps and retry." }
            }
        }.start()
    }


    private fun showRr3TextMode(name: String, source: ByteArray) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf("Decode game text", "Encode text for RR3")) { _, which ->
                transformRr3TextFile(name, source, encode = which == 1)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun transformRr3TextFile(name: String, source: ByteArray, encode: Boolean) {
        dataStatus.text = if (encode) "Encoding RR3 game text…" else "Decoding RR3 game text…"
        Thread {
            try {
                val alreadyPlain = NativeBridge.nativeRr3TextIsPlain(source)
                val output = if (!encode && alreadyPlain) {
                    source
                } else {
                    NativeBridge.nativeTransformRr3Text(source)
                        ?: throw IllegalStateException("The RR3 text transform failed.")
                }
                pendingTransformedFile?.delete()
                pendingTransformedFile = null
                pendingTransformedData = output
                pendingOutputForDataEditor = false
                pendingOutputForBrowser = false
                pendingOutputForCarEditor = false
                pendingTransformedName = name
                runOnUiThread {
                    dataStatus.text = (if (encode) "Encoded " else "Decoded ") +
                        output.size + " bytes. Choose where to save the output."
                    launchRr3OutputPicker()
                }
            } catch (e: Exception) {
                runOnUiThread { dataStatus.text = e.message ?: "RR3 text transform failed." }
            }
        }.start()
    }

    private fun launchRr3OutputPicker() {
        val outputIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, pendingTransformedName)
        }
        startActivityForResult(outputIntent, requestRr3TransformOutput)
    }

    private fun writeTransformedRr3File(uri: Uri) {
        val bytes = pendingTransformedData
        val file = pendingTransformedFile
        if (bytes == null && file == null) {
            setPendingOutputStatus("There is no transformed data waiting to be saved.")
            return
        }
        val size = file?.length() ?: (bytes?.size?.toLong() ?: 0L)
        setPendingOutputStatus("Writing output file…")
        Thread {
            try {
                val output = contentResolver.openOutputStream(uri, "wt")
                    ?: throw IllegalStateException("Could not open the output file.")
                output.use { destination ->
                    if (file != null) {
                        FileInputStream(file).use { source -> source.copyTo(destination, 512 * 1024) }
                    } else {
                        destination.write(bytes!!)
                    }
                }
                pendingTransformedData = null
                pendingTransformedFile = null
                file?.delete()
                runOnUiThread {
                    setPendingOutputStatus("Saved $size bytes.")
                    pendingOutputForDataEditor = false
                    pendingOutputForBrowser = false
                    pendingOutputForCarEditor = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setPendingOutputStatus(e.message ?: "Could not save output data.")
                }
            }
        }.start()
    }

    private fun setPendingOutputStatus(message: String) {
        if (pendingOutputForCarEditor && ::carEditorStatus.isInitialized) {
            carEditorStatus.text = message
        } else if (pendingOutputForBrowser && ::gameFilesStatus.isInitialized) {
            gameFilesStatus.text = message
        } else if (pendingOutputForDataEditor && ::dataEditorStatus.isInitialized) {
            dataEditorStatus.text = message
        } else if (::dataStatus.isInitialized) {
            dataStatus.text = message
        }
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

    private fun editBox(hintText: String, value: String, numeric: Boolean = true): EditText {
        return EditText(this).apply {
            setSingleLine(true)
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF6F849B.toInt())
            hint = hintText
            inputType = if (numeric) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
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


    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun runRootCommand(command: String): String {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException(
                output.trim().ifBlank { "Root command failed (exit $exitCode)." }
            )
        }
        return output.trim()
    }

    private fun rootFileExists(path: String): Boolean {
        val result = runRootCommand("if [ -f ${shellQuote(path)} ]; then printf yes; fi")
        return result == "yes"
    }

    private fun locateRootSaveFolder() {
        rootSaveValue.text = "Requesting root and locating RR3 save…"
        Thread {
            try {
                val uid = runRootCommand("id -u").substringAfterLast('\n').trim()
                if (uid != "0") throw IllegalStateException("Superuser access was not granted.")

                val candidates = listOf(
                    "/data/user/0/com.ea.games.r3_row/files/doc",
                    "/data/data/com.ea.games.r3_row/files/doc"
                )
                val found = candidates.firstOrNull { candidate ->
                    rootFileExists("$candidate/character.2.dat")
                } ?: throw IllegalStateException(
                    "Root access works, but character.2.dat was not found in the standard RR3 doc folder. Select the folder manually or check the installed game package."
                )

                preferences.edit()
                    .putString("save_root_dir", found)
                    .remove("save_tree")
                    .apply()
                runOnUiThread {
                    rootSaveValue.text = "Root folder: $found"
                    saveFolderValue.text = "Using private RR3 folder"
                    saveStatus.text = "Root access granted. Tap Load RR3 save."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    rootSaveValue.text = e.message ?: "Could not access the RR3 save folder."
                }
            }
        }.start()
    }

    private fun restoreRootSaveSelection() {
        val root = preferences.getString("save_root_dir", null)
        rootSaveValue.text = if (root.isNullOrBlank()) {
            "Root access not configured"
        } else {
            "Root folder: $root"
        }
    }

    private fun copyFromRootPath(sourcePath: String, target: File) {
        target.parentFile?.mkdirs()
        val process = ProcessBuilder("su", "-c", "cat -- ${shellQuote(sourcePath)}").start()
        process.inputStream.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        val error = process.errorStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            target.delete()
            throw IllegalStateException(error.trim().ifBlank { "Could not read $sourcePath with root." })
        }
    }

    private fun copyToRootPath(source: File, targetPath: String) {
        runRootCommand(
            "cp -- ${shellQuote(source.absolutePath)} ${shellQuote(targetPath)}"
        )
    }

    private fun createPersistentRootBackups(rootDir: String) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val backupDir = "$rootDir/save_editor_backup_$stamp"
        val commands = StringBuilder()
        commands.append("mkdir -p ${shellQuote(backupDir)} && ")
        commands.append("cp -- ${shellQuote("$rootDir/character.2.dat")} ${shellQuote("$backupDir/character.2.dat")}")
        val tempPath = "$rootDir/TempSaveGame.dat"
        if (rootFileExists(tempPath)) {
            commands.append(" && cp -- ${shellQuote(tempPath)} ${shellQuote("$backupDir/TempSaveGame.dat")}")
        }
        runRootCommand(commands.toString())
    }

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
        if (requestCode == requestLiverySwatchInput) {
            if (resultCode == RESULT_OK && data?.data != null) {
                applyLiverySwatchImage(data.data!!)
            } else {
                pendingLiverySwatchEdit = null
                liveryStatus.text = "Preview image selection cancelled."
            }
            return
        }
        if (resultCode != RESULT_OK || data?.data == null) return

        val uri = data.data ?: return
        if (requestCode == requestRr3TransformInput) {
            transformRr3File(uri)
            return
        }
        if (requestCode == requestRr3TransformOutput) {
            writeTransformedRr3File(uri)
            return
        }
        if (requestCode == requestEditableDataInput) {
            openEditableDataFile(uri)
            return
        }
        if (requestCode == requestCarEditorInput) {
            openCarEditorFile(uri)
            return
        }
        if (requestCode == requestCareerInspectorInput) {
            openCareerInspectorFile(uri)
            return
        }
        if (requestCode == requestTrackInspectorInput) {
            openTrackInspectorFile(uri)
            return
        }
        if (requestCode == requestManualLiveryInput) {
            openManualLiveryFile(uri)
            return
        }
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
                preferences.edit()
                    .putString("game_data_tree", uri.toString())
                    .remove("game_data_root_dir")
                    .remove("game_data_backend")
                    .apply()
                gameDataValue.text = displayFolder(uri)
                indexedGameFiles = emptyList()
                gameFilesSearch.setText("")
                gameFilesStatus.text = "Storage access granted. Searching package folders for a populated RR3 .depot…"
                gameFilesContainer.removeAllViews()
                indexGameDataFiles()
            }
            requestSaveFolder -> {
                preferences.edit().putString("save_tree", uri.toString()).remove("save_root_dir").apply()
                saveFolderValue.text = displayFolder(uri)
                rootSaveValue.text = "Using selected document folder"
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
        val rootDir = preferences.getString("save_root_dir", null)
        val stored = preferences.getString("save_tree", null)
        if (rootDir.isNullOrBlank() && stored.isNullOrBlank()) {
            saveStatus.text = "Choose a save folder or locate the private RR3 folder with root."
            return
        }

        saveStatus.text = "Reading RR3 save…"
        loadSaveButton.isEnabled = false
        runBackground {
            try {
                stageDir.mkdirs()
                if (!rootDir.isNullOrBlank()) {
                    copyFromRootPath("$rootDir/character.2.dat", stagedSave)
                    val tempPath = "$rootDir/TempSaveGame.dat"
                    if (rootFileExists(tempPath)) copyFromRootPath(tempPath, stagedTempSave)
                    else stagedTempSave.delete()
                } else {
                    val treeUri = Uri.parse(stored!!)
                    val saveUri = findChildDocument(treeUri, "character.2.dat")
                        ?: throw IllegalStateException("character.2.dat was not found in the selected folder.")
                    copyUriToFile(saveUri, stagedSave)

                    val tempUri = findChildDocument(treeUri, "TempSaveGame.dat")
                    if (tempUri != null) copyUriToFile(tempUri, stagedTempSave) else stagedTempSave.delete()
                }

                val result = NativeBridge.nativeInspectRr3Save(stagedSave.absolutePath, keysFile.absolutePath)
                val json = JSONObject(result)
                if (!json.optBoolean("ok")) {
                    throw IllegalStateException(json.optString("error", "Could not read the RR3 save."))
                }

                runOnUiThread {
                    saveStatus.text = "Save loaded. Calibrate hidden values once using the values shown in-game."
                    renderSavePayload(json)
                }
            } finally {
                runOnUiThread { loadSaveButton.isEnabled = true }
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

    private fun createPersistentBackups(treeUri: Uri) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)

        fun createBackup(source: File, originalName: String) {
            val backupName = originalName + ".backup_" + stamp
            val backupUri = DocumentsContract.createDocument(
                contentResolver,
                parentUri,
                "application/octet-stream",
                backupName
            ) ?: throw IllegalStateException("Could not create backup file: $backupName")
            copyFileToUri(source, backupUri)
        }

        createBackup(stagedSave, "character.2.dat")
        if (stagedTempSave.exists()) createBackup(stagedTempSave, "TempSaveGame.dat")
    }

    private fun renderSavePayload(payload: JSONObject) {
        currentSavePayload = payload
        renderHiddenFields(payload)
        renderAdvancedFields()
    }

    private fun renderAdvancedFields() {
        if (!::advancedFieldsContainer.isInitialized) return
        advancedFieldsContainer.removeAllViews()
        val payload = currentSavePayload ?: return
        val fields = payload.optJSONArray("fields") ?: return
        val query = if (::advancedSearch.isInitialized) advancedSearch.text.toString().trim().lowercase() else ""
        if (query.isEmpty()) {
            advancedFieldsContainer.addView(
                text("Search by field name to inspect or edit individual save values.", 12, 0xFF7F93A9.toInt()),
                matchWrap()
            )
            return
        }

        var shown = 0
        for (i in 0 until fields.length()) {
            val item = fields.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (name.isBlank() || !name.lowercase().contains(query)) continue
            val value = item.optString("value")
            val editable = item.optBoolean("editable")
            val type = item.optInt("type", -1)

            val fieldCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rounded(0xFF0B1725.toInt(), dp(12).toFloat())
            }
            advancedFieldsContainer.addView(fieldCard, matchWrap(bottom = 8))
            fieldCard.addView(text(name, 13, Color.WHITE, bold = true), matchWrap())

            if (editable) {
                val numeric = type == 1 || type == 2 || type == 5
                val input = editBox("Value", value, numeric)
                fieldCard.addView(input, matchWrap(top = 8, bottom = 8))
                fieldCard.addView(
                    actionButton("Save field") { updateHiddenField(name, input.text.toString()) },
                    matchWrap()
                )
            } else {
                fieldCard.addView(
                    text(value, 12, 0xFF9BAFC5.toInt()),
                    matchWrap(top = 5)
                )
            }

            shown++
            if (shown >= 25) break
        }

        if (shown == 0) {
            advancedFieldsContainer.addView(
                text("No matching fields.", 12, 0xFF7F93A9.toInt()),
                matchWrap()
            )
        } else if (shown == 25) {
            advancedFieldsContainer.addView(
                text("Showing the first 25 matches. Refine the search to narrow the list.", 11, 0xFF7F93A9.toInt()),
                matchWrap(top = 4)
            )
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
        val rootDir = preferences.getString("save_root_dir", null)
        val stored = preferences.getString("save_tree", null)
        if (rootDir.isNullOrBlank() && stored.isNullOrBlank()) return
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
                renderSavePayload(inspected)
            }
        }
    }

    private fun updateHiddenField(field: String, value: String) {
        val rootDir = preferences.getString("save_root_dir", null)
        val stored = preferences.getString("save_tree", null)
        if (rootDir.isNullOrBlank() && stored.isNullOrBlank()) return
        saveStatus.text = "Updating save…"
        runBackground {
            val treeUri = if (rootDir.isNullOrBlank()) Uri.parse(stored!!) else null
            if (!rootDir.isNullOrBlank()) createPersistentRootBackups(rootDir)
            else createPersistentBackups(treeUri!!)

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

            if (!rootDir.isNullOrBlank()) {
                copyToRootPath(stagedSave, "$rootDir/character.2.dat")
                if (stagedTempSave.exists()) {
                    copyToRootPath(stagedTempSave, "$rootDir/TempSaveGame.dat")
                    copyFromRootPath("$rootDir/TempSaveGame.dat", stagedTempSave)
                }
                copyFromRootPath("$rootDir/character.2.dat", stagedSave)
            } else {
                val saveUri = findChildDocument(treeUri!!, "character.2.dat")
                    ?: throw IllegalStateException("The save file disappeared from the selected folder.")
                copyFileToUri(stagedSave, saveUri)

                if (stagedTempSave.exists()) {
                    val tempUri = findChildDocument(treeUri, "TempSaveGame.dat")
                        ?: throw IllegalStateException("TempSaveGame.dat is missing; the main save was written, but the secondary copy could not be updated.")
                    copyFileToUri(stagedTempSave, tempUri)
                }
            }

            val inspected = JSONObject(
                NativeBridge.nativeInspectRr3Save(stagedSave.absolutePath, keysFile.absolutePath)
            )
            runOnUiThread {
                saveStatus.text = "Save written and verified. Original save copies were backed up."
                renderSavePayload(inspected)
            }
        }
    }
}
