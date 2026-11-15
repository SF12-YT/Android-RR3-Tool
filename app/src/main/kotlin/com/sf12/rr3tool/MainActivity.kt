package com.sf12.rr3tool

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
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
        gameFilesStatus = text(
            "Choose the RR3 files/.depot folder, then index its non-texture game files.",
            12,
            0xFF9BAFC5.toInt()
        )
        gameCard.addView(gameFilesStatus, matchWrap(top = 10))
        locateRootGameDataButton = actionButton("Locate private RR3 .depot with root") { locateRootGameDataFolder() }
        gameCard.addView(locateRootGameDataButton, matchWrap(top = 8))
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
                "Open a vehicles/data/<id>_<car>.bin.nct file. The app reads the actual car fields and lets you change supported values without editing encrypted bytes by hand. Output is saved as a separate file.",
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
        if (rootPath.isNullOrBlank() && stored.isNullOrBlank()) {
            gameFilesStatus.text = "Choose the RR3 files/.depot folder or locate it with root."
            return
        }

        indexGameFilesButton.isEnabled = false
        gameFilesStatus.text = "Indexing RR3 .depot files…"
        gameFilesContainer.removeAllViews()
        Thread {
            try {
                val files = if (!rootPath.isNullOrBlank()) {
                    val listing = runRootCommand("find ${shellQuote(rootPath)} -type f -print")
                    GameDataBrowser.fromRootListing(rootPath, listing)
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

    private fun locateRootGameDataFolder() {
        locateRootGameDataButton.isEnabled = false
        gameFilesStatus.text = "Requesting root and locating the RR3 .depot folder…"
        Thread {
            try {
                val uid = runRootCommand("id -u").substringAfterLast('\n').trim()
                if (uid != "0") throw IllegalStateException("Superuser access was not granted.")

                val saveRoot = preferences.getString("save_root_dir", null)
                val inferred = saveRoot?.substringBeforeLast('/')?.let { "$it/.depot" }
                val candidates = (listOfNotNull(inferred) + listOf(
                    "/data/user/0/com.ea.games.r3_row/files/.depot",
                    "/data/data/com.ea.games.r3_row/files/.depot",
                    "/data/user/0/com.ea.games.r3_na/files/.depot",
                    "/data/data/com.ea.games.r3_na/files/.depot",
                    "/data/user/0/com.ea.games.r3_cn/files/.depot",
                    "/data/data/com.ea.games.r3_cn/files/.depot",
                    "/storage/emulated/0/Android/data/com.ea.games.r3_row/files/.depot",
                    "/storage/emulated/0/Android/data/com.ea.games.r3_na/files/.depot"
                )).distinct()
                val found = candidates.firstOrNull { candidate ->
                    runRootCommand("if [ -d ${shellQuote(candidate)} ]; then printf yes; fi") == "yes"
                } ?: throw IllegalStateException(
                    "Root access works, but no standard RR3 .depot folder was found. Check the installed game package."
                )

                preferences.edit()
                    .putString("game_data_root_dir", found)
                    .remove("game_data_tree")
                    .apply()
                runOnUiThread {
                    gameDataValue.text = "Root folder: $found"
                    indexedGameFiles = emptyList()
                    gameFilesContainer.removeAllViews()
                    gameFilesStatus.text = "RR3 .depot located. Tap Index and browse RR3 files."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    gameFilesStatus.text = e.message ?: "Could not locate the RR3 .depot folder with root."
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
        gameFilesStatus.text = "Opening ${entry.relativePath}…"
        Thread {
            try {
                val source = readGameFile(entry)
                val (name, bytes) = unwrappedGameFile(entry, source)
                val editable = NativeBridge.nativeOpenDataAsText(name, bytes)
                    ?: throw IllegalStateException(NativeBridge.nativeGetDataEditError())
                runOnUiThread {
                    gameFilesStatus.text = "Opened ${entry.relativePath}."
                    showEditableDataDialog(name, bytes, editable)
                }
            } catch (e: Exception) {
                runOnUiThread { gameFilesStatus.text = e.message ?: "Could not open this RR3 data file." }
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
        carEditorStatus.text = "Rebuilding car data…"
        Thread {
            try {
                var output = original
                for ((key, input) in carFieldInputs) {
                    val value = input.text.toString()
                    if (value == currentCarOriginalFields[key]) continue
                    output = NativeBridge.nativeSetRr3CarField(output, key, value)
                        ?: throw IllegalStateException(NativeBridge.nativeGetRr3CarError())
                }
                pendingTransformedFile?.delete()
                pendingTransformedFile = null
                pendingTransformedData = output
                pendingTransformedName = currentCarName
                pendingOutputForDataEditor = false
                pendingOutputForBrowser = false
                pendingOutputForCarEditor = true
                runOnUiThread {
                    carEditorStatus.text = "Built ${output.size} bytes. Choose a separate output file."
                    launchRr3OutputPicker()
                }
            } catch (e: Exception) {
                runOnUiThread { carEditorStatus.text = e.message ?: "Could not rebuild this car file." }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { carEditorStatus.text = "Android ran out of memory while rebuilding this car file." }
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
        dataEditorStatus.text = "Reading data file…"
        Thread {
            val careerFile = newCacheFile("rr3-career-source")
            val careerTextFile = newCacheFile("rr3-career-edit", ".txt")
            var careerFilesHandedToEditor = false
            var careerPager: CareerTextPager? = null
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
                        showCareerEditableDialog(name, careerFile, careerTextFile, pager, firstPage, summary)
                    }
                    return@Thread
                }

                val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
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
                    showEditableDataDialog(name, original, editable)
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
            }
        }.start()
    }

    private fun showCareerEditableDialog(
        sourceName: String,
        sourcePlainFile: File,
        workingTextFile: File,
        pager: CareerTextPager,
        firstPage: CareerTextPage,
        summary: JSONObject
    ) {
        val encryptedSource = sourceName.lowercase(Locale.ROOT).endsWith(".nct")
        val pageEditors = mutableListOf<EditText>()
        var isLoadingPage = false
        var reachedEnd = false
        var building = false
        var dismissed = false

        val help = text(
            "Sectioned career editor · " +
                summary.optInt("series") + " series · " +
                summary.optInt("tiers") + " tiers · " +
                summary.optInt("events") + " events. " +
                "Scroll down to load more sections. Edit values after the fixed ID/index columns; untouched sections stay on disk.",
            12,
            0xFF9BAFC5.toInt()
        )

        val pageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val editorScroll = ScrollView(this).apply {
            isFillViewport = false
            isSmoothScrollingEnabled = true
            addView(pageContainer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val loadStatus = text("Scroll to load the next section…", 11, 0xFF75B9F1.toInt())

        fun addPage(page: CareerTextPage) {
            val section = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = rounded(0xFF101F30.toInt(), dp(12).toFloat())
            }
            section.addView(
                text(page.label, 11, 0xFF75B9F1.toInt(), bold = true),
                matchWrap(bottom = 8)
            )
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
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = rounded(0xFF0B1725.toInt(), dp(9).toFloat())
                val lineCount = page.text.count { it == '\n' } + 1
                minLines = minOf(lineCount, 4)
                maxLines = lineCount.coerceAtLeast(1)
            }
            pageEditors.add(rowEditor)
            section.addView(rowEditor, matchWrap())
            pageContainer.addView(section, matchWrap(bottom = 9))
        }

        addPage(firstPage)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(4))
            addView(help, matchWrap(bottom = 10))
            addView(
                editorScroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(470)
                )
            )
            addView(loadStatus, matchWrap(top = 8))
        }

        fun loadNextSection() {
            if (isLoadingPage || reachedEnd || building || dismissed) return
            isLoadingPage = true
            loadStatus.text = "Loading the next section…"
            Thread {
                var nextPage: CareerTextPage? = null
                var failure: String? = null
                try {
                    nextPage = pager.readNextPage()
                } catch (e: Exception) {
                    failure = e.message ?: "Could not read the next section."
                }
                runOnUiThread {
                    isLoadingPage = false
                    if (dismissed) return@runOnUiThread
                    if (failure != null) {
                        reachedEnd = true
                        loadStatus.text = failure
                    } else if (nextPage == null) {
                        reachedEnd = true
                        loadStatus.text = "End of career text · all sections loaded."
                    } else {
                        addPage(nextPage!!)
                        loadStatus.text = "Keep scrolling to load the next section…"
                        editorScroll.post {
                            val child = editorScroll.getChildAt(0)
                            if (child != null &&
                                editorScroll.scrollY + editorScroll.height >= child.height - dp(360)
                            ) {
                                loadNextSection()
                            }
                        }
                    }
                }
            }.start()
        }

        editorScroll.setOnScrollChangeListener { view: View, _, scrollY: Int, _, _ ->
            val child = (view as ScrollView).getChildAt(0)
            if (child != null && scrollY + view.height >= child.height - dp(360)) {
                loadNextSection()
            }
        }
        editorScroll.post {
            val child = editorScroll.getChildAt(0)
            if (child != null && child.height <= editorScroll.height + dp(120)) {
                loadNextSection()
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(sourceName + " — section editor")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Build and export", null)
            .create()

        dialog.setOnDismissListener {
            dismissed = true
            pager.close()
            sourcePlainFile.delete()
            workingTextFile.delete()
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (isLoadingPage || building) {
                    loadStatus.text = "A section is still loading or saving. Try again in a moment."
                    return@setOnClickListener
                }

                building = true
                val editedPages = pageEditors.map { it.text.toString() }
                val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                saveButton.isEnabled = false
                loadStatus.text = "Merging edited sections and rebuilding events.dat…"
                dataEditorStatus.text = "Rebuilding events.dat from the edited sections…"

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
                                build.optString("error", "Could not rebuild events.dat.")
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
                            dialog.dismiss()
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
                            saveButton.isEnabled = true
                            loadStatus.text = e.message ?: "Could not rebuild events.dat."
                        }
                    } catch (e: OutOfMemoryError) {
                        rebuiltPlain.delete()
                        mergedTextFile.delete()
                        outputFile?.delete()
                        runOnUiThread {
                            building = false
                            saveButton.isEnabled = true
                            loadStatus.text =
                                "Android ran out of memory while rebuilding the career file. Your source file is unchanged."
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }
    private fun showEditableDataDialog(
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
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(0xFF0B1725.toInt(), dp(12).toFloat())
            minLines = 14
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(4))
            addView(
                text(
                    "Edit the decoded representation. The original file stays untouched until you choose a separate output.",
                    12,
                    0xFF9BAFC5.toInt()
                ),
                matchWrap(bottom = 10)
            )
            val editorScroll = ScrollView(this@MainActivity).apply {
                addView(
                    editor,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(420)
                    )
                )
            }
            addView(
                editorScroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(440)
                )
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(name)
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Build output", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val edited = editor.text.toString()
                val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                saveButton.isEnabled = false
                dataEditorStatus.text = "Rebuilding RR3 data file…"
                Thread {
                    try {
                        val output = NativeBridge.nativeBuildEditedData(name, edited, original)
                            ?: throw IllegalStateException(NativeBridge.nativeGetDataEditError())
                        pendingTransformedFile?.delete()
                        pendingTransformedFile = null
                        pendingTransformedData = output
                        pendingTransformedName = name
                        pendingOutputForDataEditor = true
                        pendingOutputForBrowser = false
                        pendingOutputForCarEditor = false
                        runOnUiThread {
                            dialog.dismiss()
                            dataEditorStatus.text =
                                "Built " + output.size + " bytes. Choose a new output file."
                            launchRr3OutputPicker()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            saveButton.isEnabled = true
                            dataEditorStatus.text = e.message ?: "Could not rebuild the data file."
                        }
                    }
                }.start()
            }
        }
        dialog.show()
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
                preferences.edit().putString("game_data_tree", uri.toString()).remove("game_data_root_dir").apply()
                gameDataValue.text = displayFolder(uri)
                indexedGameFiles = emptyList()
                gameFilesSearch.setText("")
                gameFilesStatus.text = "Folder selected. Index the RR3 .depot files to browse them."
                gameFilesContainer.removeAllViews()
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
