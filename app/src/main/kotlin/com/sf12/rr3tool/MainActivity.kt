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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

class MainActivity : Activity() {
    private data class CareerTextPage(val label: String, val text: String)

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
    /**
     * Reads ordinary UTF-8 text in fixed-size line windows. Each window is an
     * editable block in one continuous document; untouched tail lines remain
     * on disk and are copied directly when the user builds the output.
     */
    private class PlainTextPager(private val sourceFile: File, private val pageSize: Int = 240) : Closeable {
        private val reader: BufferedReader = sourceFile.bufferedReader(Charsets.UTF_8)
        private var loadedLineCount = 0
        private val pageLineCounts = mutableListOf<Int>()

        @Synchronized
        fun readNextPage(): String? {
            val page = StringBuilder()
            var count = 0
            while (count < pageSize) {
                val line = reader.readLine() ?: break
                page.append(line).append('\n')
                count++
            }
            if (count == 0) return null
            loadedLineCount += count
            pageLineCounts.add(count)
            return page.toString()
        }

        @Synchronized
        fun writeMerged(
            pageTexts: List<String>,
            output: File,
            preserveLineCount: Boolean = false
        ) {
            if (preserveLineCount) {
                if (pageTexts.size != pageLineCounts.size) {
                    throw IllegalStateException("The RR3 localisation file has an unexpected number of edited sections.")
                }
                for (index in pageTexts.indices) {
                    val page = pageTexts[index]
                    val editedLineCount = if (page.isEmpty()) 0
                        else page.count { it == '\n' } + if (page.endsWith('\n')) 0 else 1
                    if (editedLineCount != pageLineCounts[index]) {
                        throw IllegalStateException(
                            "RR3 game text must keep its original line count. Section " +
                                (index + 1) + " must contain exactly " + pageLineCounts[index] + " line(s)."
                        )
                    }
                }
            }
            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                for (page in pageTexts) {
                    if (page.isEmpty()) continue
                    writer.write(page)
                    if (!page.endsWith('\n')) writer.newLine()
                }
                sourceFile.bufferedReader(Charsets.UTF_8).use { sourceReader ->
                    var lineNumber = 0
                    while (true) {
                        val line = sourceReader.readLine() ?: break
                        if (lineNumber++ < loadedLineCount) continue
                        writer.write(line)
                        writer.newLine()
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

    private lateinit var gameDataValue: TextView
    private lateinit var gameFilesStatus: TextView
    private lateinit var gameFilesSearch: EditText
    private lateinit var gameFilesContainer: LinearLayout
    private lateinit var indexGameFilesButton: TextView
    private lateinit var locateRootGameDataButton: TextView
    private var indexedGameFiles: List<GameDataFile> = emptyList()
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
            "Car Editor", "Career", "Track Inspector", "About"
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

    private fun copyGameFileToFile(entry: GameDataFile, target: File) {
        target.parentFile?.mkdirs()
        val rootPath = entry.rootPath
        if (!rootPath.isNullOrBlank()) {
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
                        "Indexed ${files.size} supported files. Textures and 3D model assets are hidden."
                    renderGameFiles()
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
        gameFilesStatus.text = "Requesting root and searching Android/data/*/files/.depot…"
        Thread {
            try {
                val uid = runRootCommand("id -u").substringAfterLast('\n').trim()
                if (uid != "0") {
                    throw IllegalStateException(
                        "Root access was not granted. Android 12 blocks normal apps from reading another app's Android/data folder. Grant this app Superuser access, then retry."
                    )
                }

                val userId = android.os.Process.myUid() / 100000
                val ownExternalSuffix = "/Android/data/" + packageName + "/files"
                val ownExternalRoots = getExternalFilesDirs(null)
                    .filterNotNull()
                    .mapNotNull { directory ->
                        val absolutePath = directory.absolutePath
                        if (absolutePath.endsWith(ownExternalSuffix)) {
                            absolutePath.removeSuffix(ownExternalSuffix)
                        } else {
                            null
                        }
                    }

                // Search the exact path layout used by RR3, with wildcard
                // package directories so this does not depend on a guessed ID.
                val storageRoots = linkedSetOf<String>().apply {
                    add("/storage/emulated/0")
                    add("/data/media/0")
                    add("/storage/emulated/" + userId)
                    add("/data/media/" + userId)
                    add("/mnt/user/" + userId + "/primary")
                    addAll(ownExternalRoots)
                }.toList()

                val checks = storageRoots.joinToString("\n") { root ->
                    val androidData = shellQuote(root + "/Android/data")
                    "data_root=$androidData; " +
                        "if [ -d \"\$data_root\" ]; then " +
                        "for package_dir in \"\$data_root\"/*; do " +
                        "[ -d \"\$package_dir\" ] || continue; " +
                        "depot=\"\$package_dir/files/.depot\"; " +
                        "if [ -d \"\$depot\" ]; then " +
                        "first_file=\$(find \"\$depot\" -type f -print -quit 2>/dev/null); " +
                        "if [ -n \"\$first_file\" ]; then " +
                        "printf 'FOUND|%s\\n' \"\$depot\"; exit 0; " +
                        "else printf 'EMPTY|%s\\n' \"\$depot\"; fi; fi; done; fi"
                }

                val scanOutput = runRootCommand("$checks\nexit 0")
                val found = scanOutput.lineSequence()
                    .firstOrNull { it.startsWith("FOUND|") }
                    ?.substringAfter("FOUND|")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

                if (found.isNullOrBlank()) {
                    val emptyPaths = scanOutput.lineSequence()
                        .filter { it.startsWith("EMPTY|") }
                        .map { it.substringAfter('|') }
                        .toList()
                    val detail = if (emptyPaths.isNotEmpty()) {
                        " .depot exists but contains no files: " + emptyPaths.joinToString(", ") + "."
                    } else {
                        " No package directory contained files/.depot."
                    }
                    throw IllegalStateException(
                        "Root access works, but no populated files/.depot directory was found. " +
                            "Searched " + storageRoots.joinToString(", ") +
                            " for Android/data/[package]/files/.depot." + detail
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
                        ?: "Could not detect RR3 Android/data files/.depot."
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

        val query = gameFilesSearch.text.toString().trim().lowercase()
        val matches = indexedGameFiles.asSequence()
            .filter { query.isEmpty() || it.relativePath.lowercase().contains(query) }
            .take(60)
            .toList()

        if (matches.isEmpty()) {
            gameFilesContainer.addView(
                text("No matching RR3 files.", 12, 0xFF7F93A9.toInt()),
                matchWrap()
            )
            return
        }

        for (entry in matches) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), dp(10), dp(11), dp(10))
                background = rounded(0xFF0B1725.toInt(), dp(10).toFloat())
                isClickable = true
                isFocusable = true
                setOnClickListener { showGameFileActions(entry) }
            }
            gameFilesContainer.addView(row, matchWrap(bottom = 6))
            row.addView(text(entry.name, 13, Color.WHITE, bold = true), matchWrap())
            val size = if (entry.sizeBytes >= 0) " · ${entry.sizeBytes} bytes" else ""
            row.addView(
                text(entry.relativePath + size, 11, 0xFF8FA4BA.toInt()),
                matchWrap(top = 3)
            )
        }

        val totalMatches = indexedGameFiles.count {
            query.isEmpty() || it.relativePath.lowercase().contains(query)
        }
        if (totalMatches > matches.size) {
            gameFilesContainer.addView(
                text("Showing ${matches.size} of $totalMatches matches. Refine the search to narrow the list.", 11, 0xFF7F93A9.toInt()),
                matchWrap(top = 4)
            )
        }
    }


    private fun scanLiveryCarAssets(liveryEntry: GameDataFile, carFolder: String): List<GameDataFile> {
        val rootPath = liveryEntry.depotRootPath
        if (!rootPath.isNullOrBlank()) {
            val carRoot = File(rootPath, "vehicles/$carFolder").absolutePath
            val listing = if (liveryEntry.isShizuku) {
                val service = shizukuStorageService
                    ?: throw IllegalStateException("Shizuku disconnected. Start it again and retry.")
                val pipe = service.listFiles(carRoot)
                    ?: throw IllegalStateException("Shizuku could not list this car's texture files.")
                ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                runRootCommand("find \${shellQuote(carRoot)} -type f -print")
            }
            return GameDataBrowser.fromRootListing(
                carRoot, listing, useShizuku = liveryEntry.isShizuku, includeTextures = true
            ).map { it.copy(relativePath = "vehicles/$carFolder/" + it.relativePath) }
        }

        val storedTree = preferences.getString("game_data_tree", null)
            ?: throw IllegalStateException("The selected RR3 depot folder is no longer available. Re-index the game files.")
        return GameDataBrowser.scanLiveryCarAssets(
            contentResolver, Uri.parse(storedTree), carFolder
        )
    }

    private fun prepareAddLiverySlot(entry: GameDataFile) {
        val marker = "vehicles/data/liveries/"
        val path = entry.relativePath.replace('\\', '/')
        if (!path.startsWith(marker, ignoreCase = true) ||
            !path.endsWith(".liveries.bin.nct", ignoreCase = true)) {
            gameFilesStatus.text = "Choose an existing car's .liveries.bin.nct file."
            return
        }
        val carFolder = path.substring(marker.length)
            .removeSuffixIgnoreCaseLocal(".liveries.bin.nct")
            .trim()
        if (carFolder.isBlank() || carFolder.contains('/') || carFolder.contains("..")) {
            gameFilesStatus.text = "Could not identify the car folder from this livery list."
            return
        }

        gameFilesStatus.text = "Inspecting the livery slots for $carFolder…"
        Thread {
            try {
                val liveryBytes = readGameFile(entry)
                val payload = JSONObject(NativeBridge.nativeInspectRr3Liveries(liveryBytes))
                if (!payload.optBoolean("ok")) {
                    throw IllegalStateException(payload.optString("error", "Could not read this car's livery list."))
                }
                val liveryArray = payload.optJSONArray("liveries")
                    ?: throw IllegalStateException("The livery list contains no readable entries.")
                if (liveryArray.length() == 0) throw IllegalStateException("This car has no existing livery to copy.")
                val carAssets = scanLiveryCarAssets(entry, carFolder)
                val assetList = indexedGameFiles.firstOrNull {
                    it.relativePath.substringAfterLast('/').equals("asset_list_base.txtCache.txt", ignoreCase = true)
                } ?: throw IllegalStateException(
                    "The depot's asset_list_base.txtCache.txt was not indexed. Re-index the RR3 depot before adding a livery."
                )
                runOnUiThread {
                    gameFilesStatus.text = "Ready to add a livery slot to $carFolder."
                    showAddLiverySlotDialog(entry, carFolder, liveryBytes, liveryArray, carAssets, assetList)
                }
            } catch (e: Exception) {
                runOnUiThread { gameFilesStatus.text = e.message ?: "Could not prepare the livery editor." }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { gameFilesStatus.text = "Android ran out of memory while preparing the livery files." }
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
            val display = item.optString("display").ifBlank { item.optString("file", "Livery \${i + 1}") }
            labels.add("\${i + 1}. $display")
            templateIndices.add(i)
        }
        if (labels.isEmpty()) {
            gameFilesStatus.text = "No usable template livery records were found."
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
                    gameFilesStatus.text = "Each RGB value must be a whole number from 0 to 255."
                    return@setOnClickListener
                }
                val templateIndex = templateIndices[templateSpinner.selectedItemPosition]
                val selected = liveryArray.optJSONObject(templateIndex)
                if (selected == null) {
                    gameFilesStatus.text = "The selected template livery is invalid."
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
                    gameFilesStatus.text = "Could not find the selected template's body texture. Re-index the car's depot files."
                    return@setOnClickListener
                }

                val sourcePairs = listOf(
                    liveryEntry.relativePath to liveryEntry,
                    donorAsset.relativePath to donorAsset,
                    assetListEntry.relativePath to assetListEntry
                )
                if (sourcePairs.map { it.first }.distinct().size != 3) {
                    gameFilesStatus.text = "The source asset paths are not unique; cannot safely stage this livery."
                    return@setOnClickListener
                }
                val sourceEntryByPath = sourcePairs.associate { it.first to it.second }
                val assetPaths = (carAssets.map { it.relativePath } + sourcePairs.map { it.first })
                    .distinct()
                    .toTypedArray()

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                gameFilesStatus.text = "Repainting the donor texture and building the new livery slot…"
                Thread {
                    val workDir = File(cacheDir, "rr3-livery-\${System.currentTimeMillis()}")
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
                                cached.writeBytes(readGameFile(sourcePair.second))
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
                        zipFile = newCacheFile("rr3-livery-update", ".zip")
                        ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zip ->
                            outputDir.walkTopDown().filter { it.isFile }.forEach { file ->
                                val relative = file.relativeTo(outputDir).invariantSeparatorsPath
                                zip.putNextEntry(ZipEntry(relative))
                                FileInputStream(file).use { it.copyTo(zip, 64 * 1024) }
                                zip.closeEntry()
                            }
                        }
                        if (zipFile.length() <= 0L) throw IllegalStateException("The livery update ZIP is empty.")
                        val report = result.optString("report")
                        pendingTransformedData = null
                        pendingTransformedFile?.delete()
                        pendingTransformedFile = zipFile
                        pendingTransformedName = "RR3-\${carFolder}-livery-update.zip"
                        pendingOutputForDataEditor = false
                        pendingOutputForBrowser = true
                        pendingOutputForCarEditor = false
                        runOnUiThread {
                            dialog.dismiss()
                            gameFilesStatus.text =
                                "Built livery update ZIP (\${zipFile.length()} bytes). " +
                                report.lines().firstOrNull { it.contains("now.") }.orEmpty() +
                                " Choose where to save it."
                            launchRr3OutputPicker()
                        }
                    } catch (e: Exception) {
                        zipFile?.delete()
                        runOnUiThread {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                            gameFilesStatus.text = e.message ?: "Could not add the livery slot."
                        }
                    } catch (e: OutOfMemoryError) {
                        zipFile?.delete()
                        runOnUiThread {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                            gameFilesStatus.text = "Android ran out of memory while building the livery update. Original assets remain unchanged."
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
        val normalisedPath = entry.relativePath.replace('\\', '/').lowercase(Locale.ROOT)
        val isLiveryList = normalisedPath.startsWith("vehicles/data/liveries/") &&
            lower.endsWith(".liveries.bin.nct")
        if (isLiveryList) actions.add("Add livery slot to this car")
        if (entry.relativePath.replace('\\', '/').lowercase().startsWith("tracks/data/") && lower.endsWith(".bin.nct")) actions.add("Inspect RR3 track data")
        if (lower.matches(Regex("""\d+_.+\.bin\.nct"""))) actions.add("Open in RR3 car editor")
        if (lower.endsWith(".bin") || effectiveName.endsWith(".nct") ||
            effectiveName.endsWith(".gui") || effectiveName.endsWith(".xml")) {
            actions.add("Decode / encode RR3 data")
        }
        if (effectiveName == "gametext.txt" ||
            (effectiveName.startsWith("text_") && effectiveName.endsWith(".txt"))) {
            actions.add("Decode / encode RR3 game text")
        }
        val extension = effectiveName.substringAfterLast('.', "").lowercase()
        if (extension in setOf("sounddef", "evt", "tbl", "table", "txt")) {
            actions.add("Open in RR3 text data editor")
        }
        if (extension == "wem" || extension == "gnsu") {
            actions.add("Convert audio to WAV")
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
                    "Add livery slot to this car" -> prepareAddLiverySlot(entry)
                    "Decode / encode RR3 game text" -> decodeGameTextFile(entry)
                    "Open in RR3 text data editor" -> editGameDataFile(entry)
                    "Convert audio to WAV" -> convertGameAudio(entry)
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

    private fun editGameDataFile(entry: GameDataFile) {
        closeDataEditorAction?.invoke()
        gameFilesStatus.text = "Opening " + entry.relativePath + "…"
        Thread {
            var textFile: File? = null
            var textPager: PlainTextPager? = null
            var handedToEditor = false
            try {
                val source = readGameFile(entry)
                val (name, bytes) = unwrappedGameFile(entry, source)
                val lowerName = name.lowercase(Locale.ROOT)
                val isGameText = lowerName == "gametext.txt" ||
                    (lowerName.startsWith("text_") && lowerName.endsWith(".txt"))
                if (name.substringAfterLast('.', "").equals("txt", ignoreCase = true)) {
                    val editableBytes = if (isGameText && !NativeBridge.nativeRr3TextIsPlain(bytes)) {
                        NativeBridge.nativeTransformRr3Text(bytes)
                            ?: throw IllegalStateException("Could not decode this RR3 game-text file.")
                    } else {
                        bytes
                    }
                    textFile = newCacheFile("rr3-browser-text-source", ".txt")
                    textFile!!.writeBytes(editableBytes)
                    val pager = PlainTextPager(textFile!!)
                    textPager = pager
                    val firstPage = pager.readNextPage() ?: ""
                    handedToEditor = true
                    runOnUiThread {
                        gameFilesStatus.text = "Opened " + entry.relativePath + " in the text editor."
                        dataEditorStatus.text = "Opened " + name + " as a continuous text file."
                        selectTabFromView("Data Editor")
                        showPagedTextEditorInline(name, textFile!!, pager, firstPage, encodeGameText = isGameText)
                    }
                    return@Thread
                }

                val editable = NativeBridge.nativeOpenDataAsText(name, bytes)
                    ?: throw IllegalStateException(NativeBridge.nativeGetDataEditError())
                runOnUiThread {
                    gameFilesStatus.text = "Opened " + entry.relativePath + "."
                    dataEditorStatus.text = "Opened " + name + "."
                    selectTabFromView("Data Editor")
                    showEditableDataEditor(name, bytes, editable)
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
            var plainTextPager: PlainTextPager? = null
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
                    if (isGameText) {
                        val existingText = plainTextFile.readBytes()
                        if (!NativeBridge.nativeRr3TextIsPlain(existingText)) {
                            val decodedText = NativeBridge.nativeTransformRr3Text(existingText)
                                ?: throw IllegalStateException("Could not decode this RR3 game-text file.")
                            plainTextFile.writeBytes(decodedText)
                        }
                    }

                    val pager = PlainTextPager(plainTextFile)
                    plainTextPager = pager
                    val firstPage = pager.readNextPage() ?: ""
                    plainTextHandedToEditor = true
                    runOnUiThread {
                        dataEditorStatus.text = "Opened $name as a continuous, section-loaded text file."
                        showPagedTextEditorInline(name, plainTextFile, pager, firstPage, encodeGameText = isGameText)
                    }
                    return@Thread
                }

                if (extension == "nct" || extension == "gui") {
                    throw IllegalArgumentException("Use the RR3 Data Transform section for this .nct or .gui file.")
                }
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open the selected file.")
                val original = input.use { it.readBytes() }
                val editable = NativeBridge.nativeOpenDataAsText(name, original)
                    ?: throw IllegalStateException(NativeBridge.nativeGetDataEditError())
                runOnUiThread {
                    dataEditorStatus.text = "Opened " + name + " (" + original.size + " bytes)."
                    showEditableDataEditor(name, original, editable)
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
    private fun attachVerticalSwipeScrolling(editor: EditText, viewport: ScrollView) {
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

    private fun showPagedTextEditorInline(
        sourceName: String,
        sourceFile: File,
        pager: PlainTextPager,
        firstPage: String,
        encodeGameText: Boolean = false
    ) {
        val pageEditors = mutableListOf<EditText>()
        var refreshFindResults: (() -> Unit)? = null
        var isLoadingPage = false
        var reachedEnd = false
        var building = false
        var closed = false
        var buildButton: TextView? = null
        var closeButton: TextView? = null

        val help = text(
            "Plain-text editor · " + sourceName +
                ". The file is shown as one continuous document. More lines load automatically as you scroll.",
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
        val loadStatus = text("Scroll to load more text…", 11, 0xFF75B9F1.toInt())

        fun addPage(value: String) {
            val rowEditor = EditText(this).apply {
                setText(value)
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
                setPadding(dp(10), dp(2), dp(10), dp(2))
                background = rounded(0xFF0B1725.toInt(), 0f)
                val lineCount = maxOf(1, value.count { it == '\n' } + 1)
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
                loadStatus.text = "Wait for the output to finish building before closing the editor."
                return
            }
            if (closed) return
            closed = true
            pager.close()
            sourceFile.delete()
            closeDataEditorScreen()
        }

        fun loadNextPage() {
            if (isLoadingPage || reachedEnd || building || closed) return
            isLoadingPage = true
            loadStatus.text = "Loading more lines…"
            Thread {
                var nextPage: String? = null
                var failure: String? = null
                try {
                    nextPage = pager.readNextPage()
                } catch (e: Exception) {
                    failure = e.message ?: "Could not read the next part of the text file."
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
                        loadStatus.text = "Keep scrolling; the next part loads automatically."
                        editorScroll.post {
                            val child = editorScroll.getChildAt(0)
                            if (child != null &&
                                editorScroll.scrollY + editorScroll.height >= child.height - dp(600)
                            ) {
                                loadNextPage()
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
            loadStatus.text = "Merging edited lines into a new text file…"
            dataEditorStatus.text = "Building " + sourceName + "…"
            Thread {
                val outputFile = newCacheFile("rr3-text-edited", ".txt")
                var encodedFile: File? = null
                try {
                    pager.writeMerged(
                        editedPages,
                        outputFile,
                        preserveLineCount = encodeGameText
                    )
                    var readyFile = outputFile
                    if (encodeGameText) {
                        val editedBytes = outputFile.readBytes()
                        val encodedBytes = NativeBridge.nativeTransformRr3Text(editedBytes)
                            ?: throw IllegalStateException("Could not encode the edited RR3 game text.")
                        encodedFile = newCacheFile("rr3-text-encoded", ".txt")
                        FileOutputStream(encodedFile!!).use { it.write(encodedBytes) }
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
                } catch (e: Exception) {
                    outputFile.delete()
                    encodedFile?.delete()
                    runOnUiThread {
                        building = false
                        buildButton?.isEnabled = true
                        closeButton?.isEnabled = true
                        loadStatus.text = e.message ?: "Could not build the text file."
                    }
                } catch (e: OutOfMemoryError) {
                    outputFile.delete()
                    encodedFile?.delete()
                    runOnUiThread {
                        building = false
                        buildButton?.isEnabled = true
                        closeButton?.isEnabled = true
                        loadStatus.text = "Android ran out of memory. The original text file remains unchanged."
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
                loadNextPage()
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
                loadNextPage()
            }
        }
    }

    private fun showEditableDataEditor(
        name: String,
        original: ByteArray,
        editableText: String
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
                    val output = NativeBridge.nativeBuildEditedData(name, editedText, original)
                        ?: throw IllegalStateException(NativeBridge.nativeGetDataEditError())
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
                            "Built " + output.size + " bytes. Choose a new output file."
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
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Could not open the selected file.")
                    val bytes = input.use { it.readBytes() }
                    runOnUiThread { showRr3TextMode(name, bytes) }
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
