package com.sf12.rr3tool
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ProgressBar
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
    private data class Rr3ViewerCar(
        val id: Int,
        val dataFolder: String,
        val modelFolder: String,
        val displayName: String,
        val brand: String,
        val liveryEntry: GameDataFile
    )
    private data class LiveryCarChoice(
        val entry: GameDataFile,
        val carFolder: String,
        val displayName: String,
        val brand: String
    )
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
    private val requestSaveFolder = 1102
    private val requestRr3TransformInput = 1103
    private val requestRr3TransformOutput = 1104
    private val requestEditableDataInput = 1105
    private val requestCarEditorInput = 1106
    private val requestCareerInspectorInput = 1107
    private val requestTrackInspectorInput = 1108
    private val requestManualLiveryInput = 1109
    private val requestLiverySwatchInput = 1110
    private val requestCamTweaksInput = 1111
    private lateinit var gameDataValue: TextView
    private lateinit var gameFilesStatus: TextView
    private lateinit var setupStatus: TextView
    private lateinit var setupScreen: LinearLayout
    private lateinit var setupIndexButton: TextView
    private lateinit var gameFilesSearch: EditText
    private lateinit var gameFilesContainer: LinearLayout
    private lateinit var indexGameFilesButton: TextView
    private lateinit var locateRootGameDataButton: TextView
    private var indexedGameFiles: List<GameDataFile> = emptyList()
    private var rr3ViewerCatalogStatus: String = "The RR3 car catalogue has not been indexed yet."
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
    private var shizukuAutoReconnectAttempted = false
    private var pendingShizukuAction: (() -> Unit)? = null
    private val shizukuPermissionRequestCode = 4812
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == shizukuPermissionRequestCode) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bindShizukuStorageService()
                } else {
                    runOnUiThread {
                        setGameDataStatus("Shizuku permission was denied. Choose Root access or start Shizuku and retry.")
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
                setGameDataStatus("Shizuku connected, but the RR3 storage service could not be created.")
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
    private lateinit var camTweaksStatus: TextView
    private lateinit var camTweaksFieldsContainer: LinearLayout
    private lateinit var camTweaksSaveButton: TextView
    private var camTweaksDialog: AlertDialog? = null
    private var indexingDialog: AlertDialog? = null
    private var indexingStatusText: TextView? = null
    private var currentCamTweaksOriginal: ByteArray? = null
    private var currentCamTweaksWorking: ByteArray? = null
    private var currentCamTweaksDecoded: CamTweaksV5? = null
    private var camTweaksOverlay: View? = null
    private var selectedCamTweaksRecordOffset: Int? = null
    private var selectedCamTweaksTransformOffset: Int? = null
    private var activeRr3ViewerCarId: Int? = null
    private lateinit var camTweaksTransformStatus: TextView
    private var currentCamTweaksName: String = "camTweaks.dat"
    private val camTweaksInputs = linkedMapOf<Int, EditText>()
    private lateinit var carFieldsContainer: LinearLayout
    private lateinit var carEditorIndexedCarsContainer: LinearLayout
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
    private val expandedLiveryBrands = mutableSetOf<String>()
    private val expandedCarEditorBrands = mutableSetOf<String>()
    private val expandedViewerBrands = mutableSetOf<String>()
    private var rr3ViewerCars: List<Rr3ViewerCar> = emptyList()
    private var rr3ViewerCatalogLoading = false
    private var rr3ViewerLoading = false
    private lateinit var rr3ViewerCarListContainer: LinearLayout
    private lateinit var settingsIndexStatus: TextView
    private lateinit var scanNewFilesButton: TextView
    private lateinit var rr3ViewerStatus: TextView
    private lateinit var rr3ViewerScreen: FrameLayout
    private lateinit var rr3ViewerScreenHost: FrameLayout
    private lateinit var rr3ViewerScreenTitle: TextView
    private lateinit var rr3ViewerScreenStatus: TextView
    private var rr3ViewerSurface: Rr3ModelViewer? = null
    private var viewerPreviousRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var viewerPreviousSystemUiVisibility = 0
    private var selectedLiveryEntry: GameDataFile? = null
    private var selectedLiveryCarFolder: String = ""
    private var selectedLiveryBytes: ByteArray? = null
    private var selectedLiveryArray: org.json.JSONArray? = null
    private var selectedLiveryPayload: JSONObject? = null
    private var selectedLiverySourceName: String = ""
    private var selectedLiverySourceSize: Long = 0L
    private var pendingLiverySwatchEdit: PendingLiverySwatchEdit? = null
    private var liveryAddPreflightRunning = false
    private val preferences by lazy {
        getSharedPreferences("rr3_tool", MODE_PRIVATE)
    }
    private val keysFile: File
        get() = File(filesDir, "rr3_keys.txt")
    private val rr3IndexCacheFile: File
        get() = File(filesDir, "rr3_depot_index.json")
    private val stageDir: File
        get() = File(filesDir, "rr3_save_work")
    private val stagedSave: File
        get() = File(stageDir, "character.2.dat")
    private val stagedTempSave: File
        get() = File(stageDir, "TempSaveGame.dat")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.statusBarColor = UiPalette.background
        window.navigationBarColor = UiPalette.background
        val appRoot = FrameLayout(this)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(UiPalette.background)
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
            setBackgroundColor(UiPalette.background)
            visibility = View.GONE
        }
        val editorToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(18), dp(10))
            setBackgroundColor(UiPalette.surface)
        }
        editorToolbar.addView(actionButton("‹ Back") {
            closeDataEditorAction?.invoke() ?: closeDataEditorScreen()
        })
        editorScreenTitle = text("Editor", 18, UiPalette.text, bold = true)
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
        rr3ViewerScreen = FrameLayout(this).apply {
            setBackgroundColor(UiPalette.background)
            visibility = View.GONE
        }
        rr3ViewerScreenHost = FrameLayout(this).apply {
            setBackgroundColor(UiPalette.surface)
        }
        rr3ViewerScreen.addView(rr3ViewerScreenHost, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        val viewerBackButton = compactOverlayButton("‹ Back") { closeRr3FullScreenViewer() }
        rr3ViewerScreen.addView(viewerBackButton, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply {
            topMargin = dp(14)
            leftMargin = dp(14)
        })
        rr3ViewerScreenTitle = text("3D Viewer", 13, UiPalette.text, bold = true).apply {
            maxLines = 1
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(UiPalette.surfaceOverlay, dp(14).toFloat())
        }
        rr3ViewerScreen.addView(rr3ViewerScreenTitle, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(14) })
        val viewerMenuButton = compactOverlayButton("☰ Menu") { showRr3ViewerMenu() }
        rr3ViewerScreen.addView(viewerMenuButton, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = dp(14)
            rightMargin = dp(14)
        })
        rr3ViewerScreenStatus = text(
            "Drag to rotate · Pinch to zoom",
            10, UiPalette.text
        ).apply {
            maxLines = 1
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(UiPalette.surfaceOverlayLight, dp(12).toFloat())
        }
        rr3ViewerScreen.addView(rr3ViewerScreenStatus, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        ).apply {
            leftMargin = dp(14)
            bottomMargin = dp(14)
        })
        appRoot.addView(rr3ViewerScreen, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        page.addView(
            text("ANDROID EDITION  •  REAL RACING 3", 11, UiPalette.accent).apply {
                letterSpacing = 0.10f
            },
            matchWrap()
        )
        page.addView(
            text("RR3 Tool", 32, UiPalette.text, bold = true),
            matchWrap(top = 7)
        )
        page.addView(
            text(
                "A native Android port of the Monkey Tool RR3 workflows.",
                14,
                UiPalette.textMuted
            ),
            matchWrap(top = 5, bottom = 16)
        )
        val tabNames = listOf(
            "Index", "Save Editor", "Data Editor", "Car Editor",
            "Liveries", "Career", "Track Inspector", "3D Viewer", "About", "Settings"
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
                button.background = interactiveBackground(
                    if (active) UiPalette.primary else UiPalette.surface,
                    dp(18).toFloat()
                )
                button.setTextColor(if (active) UiPalette.text else UiPalette.textMuted)
            }
        }
        for (name in tabNames) {
            val button = text(name, 12, UiPalette.textMuted, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(15), dp(10), dp(15), dp(10))
                background = interactiveBackground(UiPalette.surface, dp(18).toFloat())
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
        selectTab("Index")
        val gameDataPage = tabPages.getValue("Index")
        val savePage = tabPages.getValue("Save Editor")
        val editorPage = tabPages.getValue("Data Editor")
        val carPage = tabPages.getValue("Car Editor")
        val liveryPage = tabPages.getValue("Liveries")
        val careerPage = tabPages.getValue("Career")
        val trackPage = tabPages.getValue("Track Inspector")
        val viewerPage = tabPages.getValue("3D Viewer")
        val aboutPage = tabPages.getValue("About")
        addSection(gameDataPage, "DEPOT INDEX")
        val gameCard = card(gameDataPage)
        gameCard.addView(text("RR3 depot index", 17, UiPalette.text, bold = true), matchWrap())
        gameCard.addView(
            text(
                "Browse the installed .depot listing. Use Settings → Scan for new files to discover additions without rebuilding the existing index.",
                13,
                UiPalette.textMuted
            ),
            matchWrap(top = 6)
        )
        gameFilesStatus = text("The RR3 depot has not been indexed yet.", 12, UiPalette.textMuted)
        gameCard.addView(gameFilesStatus, matchWrap(top = 10))
        gameFilesSearch = editBox("Search indexed RR3 file names and paths", "", numeric = false)
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
        saveCard.addView(text("RR3 save location", 17, UiPalette.text, bold = true), matchWrap())
        saveCard.addView(
            text(
                "Choose the folder containing character.2.dat. The original save is backed up before every write.",
                13,
                UiPalette.textMuted
            ),
            matchWrap(top = 6)
        )
        saveFolderValue = text("No folder selected", 12, UiPalette.text)
        saveCard.addView(saveFolderValue, matchWrap(top = 12))
        saveCard.addView(
            actionButton("Choose save folder") { chooseFolder(requestSaveFolder) },
            matchWrap(top = 12)
        )
        rootSaveValue = text("Root access not configured", 12, UiPalette.text)
        saveCard.addView(rootSaveValue, matchWrap(top = 12))
        saveCard.addView(
            actionButton("Locate private RR3 save folder with root") { locateRootSaveFolder() },
            matchWrap(top = 8)
        )
        saveCard.addView(
            text(
                "Root access is optional. It may be needed to reach the game's private doc folder on Android 11 and later.",
                11,
                UiPalette.textSubtle
            ),
            matchWrap(top = 6)
        )
        loadSaveButton = actionButton("Load RR3 save") { loadRr3Save() }
        saveCard.addView(loadSaveButton, matchWrap(top = 10))
        saveStatus = text(
            "Select a save folder, then load the save.",
            12,
            UiPalette.textMuted
        )
        saveCard.addView(saveStatus, matchWrap(top = 10))
        saveFieldsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        saveCard.addView(saveFieldsContainer, matchWrap(top = 12))
        saveCard.addView(
            text("ALL FIELDS (ADVANCED)", 11, UiPalette.accent, bold = true),
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
        addSection(editorPage, "RR3 FILE CONVERSION")
        val dataCard = card(editorPage)
        dataCard.addView(
            text("Decode or re-encode RR3 data and game text", 17, UiPalette.text, bold = true),
            matchWrap()
        )
        dataCard.addView(
            text(
                "Conversion remains available here when an editor needs a readable working copy. The native conversion engine stays internal; there is no separate Transform screen.",
                13,
                UiPalette.textMuted
            ),
            matchWrap(top = 6)
        )
        dataCard.addView(
            actionButton("Choose RR3 data (.nct / .bin / .gui / .xml / game text .txt)") { chooseRr3DataFile() },
            matchWrap(top = 12)
        )
        dataStatus = text("No RR3 data file selected.", 12, UiPalette.textMuted)
        dataCard.addView(dataStatus, matchWrap(top = 10))
        addSection(editorPage, "RR3 DATA EDITOR")
        val editorCard = card(editorPage)
        editorCard.addView(
            text("Edit supported RR3 data files as text", 17, UiPalette.text, bold = true),
            matchWrap()
        )
        editorCard.addView(
            text(
                "Open supported .sounddef, .evt, table and text files. events.dat gets a compact, disk-backed text view of its series, tiers and events; saving rebuilds the RR3 binary and restores NCT encryption when the source was .dat.nct.",
                13,
                UiPalette.textMuted
            ),
            matchWrap(top = 6)
        )
        editorCard.addView(
            actionButton("Open editable RR3 data file") { chooseEditableDataFile() },
            matchWrap(top = 12)
        )
        dataEditorStatus = text("No data file opened.", 12, UiPalette.textMuted)
        editorCard.addView(dataEditorStatus, matchWrap(top = 10))
        // Camera tweaks are edited from the 3D Viewer menu, alongside the live preview.
        addSection(carPage, "RR3 CAR EDITOR")
        val carCard = card(carPage)
        carCard.addView(
            text("Edit a car's structured depot index", 17, UiPalette.text, bold = true),
            matchWrap()
        )
        carCard.addView(
            text(
                "Open a vehicles/data/<id>_<car>.bin.nct file. The app reads the actual car fields and lets you change supported values without editing encrypted bytes by hand. Keep edited text fields the same UTF-8 byte length; RR3's encrypted record layout cannot safely shift later fields. Output is saved as a separate file.",
                13,
                UiPalette.textMuted
            ),
            matchWrap(top = 6)
        )
        carCard.addView(
            actionButton("Open my own car file (.bin.nct)") { chooseCarEditorFile() },
            matchWrap(top = 12)
        )
        carEditorStatus = text("Choose an indexed car or open your own .bin.nct file.", 12, UiPalette.textMuted)
        carCard.addView(carEditorStatus, matchWrap(top = 10))
        carCard.addView(text("INDEXED CARS", 11, UiPalette.accent, bold = true), matchWrap(top = 14, bottom = 7))
        val carEditorIndexScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            isNestedScrollingEnabled = true
            background = rounded(UiPalette.background, dp(10).toFloat())
        }
        carEditorIndexedCarsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        carEditorIndexScroll.addView(carEditorIndexedCarsContainer)
        carCard.addView(carEditorIndexScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(230)
        ))
        carFieldsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        carCard.addView(carFieldsContainer, matchWrap(top = 12))
        addSection(liveryPage, "RR3 LIVERY MANAGER")
        val liveryCard = card(liveryPage)
        liveryCard.addView(
            text("Manage a car's livery slots", 17, UiPalette.text, bold = true),
            matchWrap()
        )
        liveryCard.addView(
            text(
                "Choose a car from the indexed depot. Its existing livery slots are listed here before you add another one. New slots are appended; existing slots are preserved.",
                13,
                UiPalette.textMuted
            ),
            matchWrap(top = 6)
        )
        liveryStatus = text(
            "Index the RR3 .depot in Index to load the available cars.",
            12,
            UiPalette.textMuted
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
            text("SELECTED CAR · LIVERY SLOTS", 11, UiPalette.textSecondary, bold = true),
            matchWrap(top = 12, bottom = 6)
        )
        liveryCarsPanel.addView(liverySlotsContainer, matchWrap())
        liveryManualStatus = text(
            "Choose a .bin.nct file to inspect its livery entries. The indexed car list remains available in the other tab.",
            12,
            UiPalette.textMuted
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
            "Edit the Motorsports label and Road Cars paint-material/colour names. Index Index first.",
            12,
            UiPalette.textMuted
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
                11, UiPalette.textSecondary
            ),
            matchWrap(top = 12)
        )
        liveryCard.addView(liveryDecodedPanel, matchWrap())
        addSection(careerPage, "RR3 CAREER INSPECTOR")
        val careerCard = card(careerPage)
        careerCard.addView(
            text("Inspect the career database", 17, UiPalette.text, bold = true),
            matchWrap()
        )
        careerCard.addView(
            text(
                "Open data/events.dat.nct here to inspect its RRCE career structure, including tracks, series, tiers and event counts. Use Data Editor to edit the supported career fields and rebuild the file.",
                13,
                UiPalette.textMuted
            ),
            matchWrap(top = 6)
        )
        careerCard.addView(
            actionButton("Choose events.dat.nct") { chooseCareerInspectorFile() },
            matchWrap(top = 12)
        )
        careerInspectorStatus = text("No career file opened.", 12, UiPalette.textMuted)
        careerCard.addView(careerInspectorStatus, matchWrap(top = 10))
        careerDetailsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        careerCard.addView(careerDetailsContainer, matchWrap(top = 12))
        addSection(trackPage, "RR3 TRACK INSPECTOR")
        val trackCard = card(trackPage)
        trackCard.addView(
            text("Inspect a track's data record", 17, UiPalette.text, bold = true),
            matchWrap()
        )
        trackCard.addView(
            text(
                "Open a Tracks/data/*.bin.nct file to read its track ID, display name, track folder, layout, time of day and country. This is a read-only inspection tool.",
                13,
                UiPalette.textMuted
            ),
            matchWrap(top = 6)
        )
        trackCard.addView(
            actionButton("Choose track data (.bin.nct)") { chooseTrackInspectorFile() },
            matchWrap(top = 12)
        )
        trackInspectorStatus = text("No track data file opened.", 12, UiPalette.textMuted)
        trackCard.addView(trackInspectorStatus, matchWrap(top = 10))
        trackDetailsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        trackCard.addView(trackDetailsContainer, matchWrap(top = 12))
        addSection(viewerPage, "RR3 3D VIEWER")
        val viewerCard = card(viewerPage)
        viewerCard.addView(
            text("Load a car from the installed RR3 depot", 17, UiPalette.text, bold = true),
            matchWrap()
        )
        viewerCard.addView(
            text(
                "Choose a cached car model and preview it in the landscape GPU viewer with a panoramic environment backdrop. Game files remain unchanged by the preview.",
                13, UiPalette.textMuted
            ),
            matchWrap(top = 6)
        )
        rr3ViewerCarListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        viewerCard.addView(rr3ViewerCarListContainer, matchWrap(top = 12))
        rr3ViewerStatus = text(
            "Expand a manufacturer to browse its car models.",
            12, UiPalette.textMuted
        )
        viewerCard.addView(rr3ViewerStatus, matchWrap(top = 10, bottom = 8))
        addSection(aboutPage, "NATIVE ENGINE")
        val engineCard = card(aboutPage)
        engineValue = text("Checking native engine…", 13, UiPalette.text)
        engineCard.addView(engineValue, matchWrap())
        engineCard.addView(
            text(
                "The standalone 3D Viewer reads installed RR3 car models and available textures. Model editing and replacement are separate future work.",
                12,
                UiPalette.textSubtle
            ),
            matchWrap(top = 12)
        )
        addSection(aboutPage, "PORT STATUS")
        val statusCard = card(aboutPage)
        statusCard.addView(
            text("Android foundation", 16, UiPalette.text, bold = true),
            matchWrap()
        )
        statusCard.addView(
            text(
                "The app uses the upstream C++ save parser, car-data parser and career-file reader. Large NCT transforms run in the native engine; source game files remain untouched unless you explicitly export replacements.",
                13,
                UiPalette.textMuted
            ),
            matchWrap(top = 6)
        )
        aboutPage.addView(
            text(
                "Unofficial fan project  •  Not affiliated with EA",
                11,
                UiPalette.textSubtle
            ).apply { gravity = Gravity.CENTER },
            matchWrap(top = 24)
        )
        val settingsPage = tabPages.getValue("Settings")
        addSection(settingsPage, "DEPOT INDEX SETTINGS")
        val settingsCard = card(settingsPage)
        settingsCard.addView(text("Find newly added RR3 files", 17, UiPalette.text, bold = true), matchWrap())
        settingsCard.addView(
            text(
                "This walks the depot paths to discover additions, then retains existing index entries and the cached car catalogue. Only newly discovered car records are parsed.",
                13, UiPalette.textMuted
            ),
            matchWrap(top = 6)
        )
        settingsIndexStatus = text("No scan has been run in this session.", 12, UiPalette.textMuted)
        settingsCard.addView(settingsIndexStatus, matchWrap(top = 10))
        scanNewFilesButton = actionButton("Scan for new files") { scanNewGameDataFiles() }
        settingsCard.addView(scanNewFilesButton, matchWrap(top = 10))
        val savedRootGameData = preferences.getString("game_data_root_dir", null)
        if (savedRootGameData.isNullOrBlank()) {
            // Older builds allowed a manually selected document folder. RR3
            // depot access is now deliberately limited to Root or Shizuku.
            preferences.edit()
                .remove("game_data_tree")
                .remove("game_data_backend")
                .remove("setup_complete")
                .apply()
        }
        restoreSelection(saveFolderValue, "save_tree")
        restoreRootSaveSelection()
        updateEngineStatus()

        setupScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiPalette.background)
            setPadding(dp(22), dp(20), dp(22), dp(20))
        }
        val setupScroll = ScrollView(this).apply { isFillViewport = true }
        val setupPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(24))
        }
        setupScroll.addView(setupPage)
        setupScreen.addView(setupScroll, matchWrap())
        setupPage.addView(
            text("ANDROID EDITION  •  REAL RACING 3", 11, UiPalette.accent).apply {
                letterSpacing = 0.10f
            },
            matchWrap()
        )
        setupPage.addView(text("RR3 Tool", 32, UiPalette.text, bold = true), matchWrap(top = 7))
        setupPage.addView(
            text(
                "A quick two-step setup. The app reads the installed depot directly; it does not copy the game files.",
                14, UiPalette.textMuted
            ),
            matchWrap(top = 5, bottom = 20)
        )
        val detectCard = card(setupPage)
        detectCard.addView(text("STEP 1  ·  DETECT RR3 FILES", 12, UiPalette.accent, bold = true), matchWrap())
        detectCard.addView(
            text(
                "Choose one access method. Shizuku works without root when started through Wireless Debugging. Root requires a Superuser grant.",
                13, UiPalette.textMuted
            ),
            matchWrap(top = 7)
        )
        locateRootGameDataButton = actionButton("Detect installed files with Root") {
            locateRootGameDataFolder()
        }
        detectCard.addView(locateRootGameDataButton, matchWrap(top = 12))
        detectCard.addView(
            actionButton("Detect installed files with Shizuku") { locateShizukuGameDataFolder() },
            matchWrap(top = 8)
        )
        gameDataValue = text(
            savedRootGameData ?: "RR3 depot not detected yet.",
            11, UiPalette.text
        ).apply {
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = rounded(UiPalette.background, dp(9).toFloat())
        }
        detectCard.addView(gameDataValue, matchWrap(top = 12))
        setupStatus = text(
            if (savedRootGameData.isNullOrBlank()) "Detect your installed RR3 depot to continue."
            else "RR3 depot already detected. Continue to the full index.",
            12, UiPalette.textMuted
        )
        detectCard.addView(setupStatus, matchWrap(top = 9))

        val indexCard = card(setupPage)
        indexCard.addView(text("STEP 2  ·  INDEX EVERYTHING", 12, UiPalette.accent, bold = true), matchWrap())
        indexCard.addView(
            text(
                "Build one complete file index for the depot, including models, textures, animations, sidecars and RR3 car records. The car catalogue is parsed here and reused by the 3D Viewer.",
                13, UiPalette.textMuted
            ),
            matchWrap(top = 7)
        )
        setupIndexButton = actionButton("Index EVERYTHING") { indexGameDataFiles() }.apply {
            isEnabled = !savedRootGameData.isNullOrBlank()
        }
        indexCard.addView(setupIndexButton, matchWrap(top = 12))
        appRoot.addView(setupScreen, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        val savedBackend = preferences.getString("game_data_backend", "root") ?: "root"
        val setupCompleted = preferences.getBoolean("setup_complete", false) &&
            !savedRootGameData.isNullOrBlank() &&
            restoreRr3IndexCache(savedRootGameData, savedBackend)
        if (setupCompleted) {
            setupScreen.visibility = View.GONE
            rootScrollView.visibility = View.VISIBLE
            gameFilesStatus.text =
                "Restored ${indexedGameFiles.size} indexed files. ${rr3ViewerCatalogStatus} Use Settings → Scan for new files after updating RR3."
            renderGameFiles()
            renderLiveryCarChoices()
            renderRr3ViewerCarChoices()
            renderCarEditorIndexedChoices()
        } else {
            setupScreen.visibility = View.VISIBLE
            rootScrollView.visibility = View.GONE
            setupIndexButton.isEnabled = !savedRootGameData.isNullOrBlank()
        }
        setContentView(appRoot)
        // If a depot has already been detected but its index is absent or
        // incomplete, build the full file + Car Editor catalogue on startup.
        if (!setupCompleted && !savedRootGameData.isNullOrBlank()) {
            window.decorView.post { indexGameDataFiles() }
        }
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
    private fun saveRr3IndexCache(
        rootPath: String,
        backend: String,
        files: List<GameDataFile>,
        cars: List<Rr3ViewerCar>,
        catalogStatus: String
    ) {
        val root = JSONObject()
            .put("schema", 1)
            .put("rootPath", rootPath)
            .put("backend", backend)
            .put("catalogStatus", catalogStatus)
            .put("indexedAt", System.currentTimeMillis())
        val fileArray = org.json.JSONArray()
        for (entry in files) {
            fileArray.put(JSONObject().put("name", entry.name).put("relativePath", entry.relativePath))
        }
        root.put("files", fileArray)
        val carArray = org.json.JSONArray()
        for (car in cars) {
            carArray.put(
                JSONObject()
                    .put("id", car.id)
                    .put("dataFolder", car.dataFolder)
                    .put("modelFolder", car.modelFolder)
                    .put("displayName", car.displayName)
                    .put("brand", car.brand)
            )
        }
        root.put("cars", carArray)
        val temporary = File(filesDir, "rr3_depot_index.json.tmp")
        temporary.writeText(root.toString(), Charsets.UTF_8)
        if (rr3IndexCacheFile.exists() && !rr3IndexCacheFile.delete()) {
            temporary.delete()
            throw IllegalStateException("Could not replace the previous depot index cache.")
        }
        if (!temporary.renameTo(rr3IndexCacheFile)) {
            temporary.delete()
            throw IllegalStateException("Could not save the depot index cache.")
        }
    }

    private fun restoreRr3IndexCache(rootPath: String, backend: String): Boolean {
        return try {
            if (!rr3IndexCacheFile.isFile) return false
            val cached = JSONObject(rr3IndexCacheFile.readText(Charsets.UTF_8))
            if (cached.optInt("schema") != 1 ||
                cached.optString("rootPath") != rootPath ||
                cached.optString("backend", "root") != backend
            ) return false

            val fileArray = cached.optJSONArray("files") ?: return false
            val restoredFiles = ArrayList<GameDataFile>(fileArray.length())
            for (index in 0 until fileArray.length()) {
                val item = fileArray.optJSONObject(index) ?: continue
                val relativePath = item.optString("relativePath").trim('/')
                if (relativePath.isBlank()) continue
                val name = item.optString("name").ifBlank { relativePath.substringAfterLast('/') }
                val absolutePath = rootPath.trimEnd('/') + "/" + relativePath
                restoredFiles.add(
                    GameDataFile(
                        name = name,
                        relativePath = relativePath,
                        uri = null,
                        sizeBytes = -1L,
                        rootPath = absolutePath,
                        depotRootPath = rootPath,
                        isShizuku = backend == "shizuku"
                    )
                )
            }
            if (restoredFiles.isEmpty()) return false
            val cardesc = restoredFiles.firstOrNull {
                it.relativePath.replace('\\', '/').equals(
                    "vehicles/data/cardesc.list.nct", ignoreCase = true
                )
            } ?: return false
            val carArray = cached.optJSONArray("cars") ?: org.json.JSONArray()
            val restoredCars = ArrayList<Rr3ViewerCar>(carArray.length())
            for (index in 0 until carArray.length()) {
                val item = carArray.optJSONObject(index) ?: continue
                val dataFolder = item.optString("dataFolder").trim()
                val modelFolder = item.optString("modelFolder").trim()
                if (dataFolder.isBlank() || modelFolder.isBlank()) continue
                restoredCars.add(
                    Rr3ViewerCar(
                        item.optInt("id"),
                        dataFolder,
                        modelFolder,
                        item.optString("displayName").ifBlank { formatLiveryCarName(modelFolder) },
                        item.optString("brand").trim().ifBlank {
                            inferLegacyCarBrand(item.optString("displayName"), modelFolder)
                        },
                        cardesc
                    )
                )
            }
            indexedGameFiles = restoredFiles
            // An empty cached catalogue is not a completed Car Editor index.
            // Force a fresh depot scan so the first launch cannot silently skip cars.
            if (restoredCars.isEmpty()) return false
            rr3ViewerCars = restoredCars.sortedWith(
                compareBy<Rr3ViewerCar>({ it.displayName.lowercase(Locale.ROOT) }, { it.id })
            )
            rr3ViewerCatalogStatus = cached.optString(
                "catalogStatus", "Restored the cached RR3 car catalogue."
            )
            if (::carEditorStatus.isInitialized) {
                carEditorStatus.text = "Loaded ${rr3ViewerCars.size} cached cars. ${rr3ViewerCatalogStatus}"
            }
            true
        } catch (_: Exception) {
            false
        } catch (_: OutOfMemoryError) {
            false
        }
    }

    private fun setGameDataStatus(message: String) {
        if (::gameFilesStatus.isInitialized) gameFilesStatus.text = message
        if (::setupStatus.isInitialized) setupStatus.text = message
    }

    private fun showIndexingOverlay(message: String) {
        runOnUiThread {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                findViewById<View>(android.R.id.content)?.setRenderEffect(
                    RenderEffect.createBlurEffect(dp(9).toFloat(), dp(9).toFloat(), Shader.TileMode.CLAMP)
                )
            }
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(26), dp(24), dp(26), dp(24))
                background = rounded(UiPalette.surface, dp(18).toFloat())
            }
            panel.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(42), dp(42)).apply { gravity = Gravity.CENTER })
            panel.addView(text("INDEXING RR3", 14, UiPalette.text, bold = true), matchWrap(top = 16, bottom = 6))
            val statusLabel = text(message, 12, UiPalette.textMuted).apply { gravity = Gravity.CENTER }
            indexingStatusText = statusLabel
            panel.addView(statusLabel, matchWrap())
            indexingDialog?.dismiss()
            indexingDialog = AlertDialog.Builder(this).setView(panel).setCancelable(false).create()
            indexingDialog?.show()
            indexingDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
            indexingDialog?.window?.setLayout((resources.displayMetrics.widthPixels * 0.55f).toInt(), -2)
        }
    }

    private fun updateIndexingOverlay(message: String) {
        runOnUiThread { indexingStatusText?.text = message }
    }

    private fun dismissIndexingOverlay() {
        runOnUiThread {
            indexingDialog?.dismiss()
            indexingDialog = null
            indexingStatusText = null
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                findViewById<View>(android.R.id.content)?.setRenderEffect(null)
            }
        }
    }

    private fun setIndexButtonsEnabled(enabled: Boolean) {
        if (::indexGameFilesButton.isInitialized) indexGameFilesButton.isEnabled = enabled
        if (::setupIndexButton.isInitialized) setupIndexButton.isEnabled = enabled
    }

    /** Parses RR3 registries and per-car records during the depot index operation. */
    private fun buildRr3ViewerCarCatalog(
        files: List<GameDataFile>,
        onlyDataPaths: Set<String>? = null,
        previousCars: List<Rr3ViewerCar> = emptyList(),
        onProgress: ((String) -> Unit)? = null
    ): Pair<List<Rr3ViewerCar>, String> {
        fun normalPath(entry: GameDataFile) =
            entry.relativePath.replace('\\', '/').trimStart('/').lowercase(Locale.ROOT)

        // cardesc and carappearances are useful registries, but the native
        // reader can enumerate per-car files without either one. Do not make
        // a missing or unreadable appearance table erase the Car Editor list.
        val cardesc = files.firstOrNull {
            normalPath(it) == "vehicles/data/cardesc.list.nct"
        }
        val appearances = files.firstOrNull {
            normalPath(it) == "vehicles/data/carappearances.appearances.nct"
        }
        val carDataFiles = files.filter { entry ->
            val path = normalPath(entry)
            path.substringBeforeLast('/', "") == "vehicles/data" &&
                Regex("^[0-9]{1,6}_.+\\.bin\\.nct$", RegexOption.IGNORE_CASE)
                    .matches(path.substringAfterLast('/'))
        }.filter { onlyDataPaths == null || normalPath(it) in onlyDataPaths }
            .sortedBy { normalPath(it) }

        if (carDataFiles.isEmpty()) {
            return if (onlyDataPaths != null) {
                previousCars to "No new per-car records were found; the cached car catalogue was retained."
            } else {
                emptyList<Rr3ViewerCar>() to
                    "No per-car .bin.nct files were found directly under vehicles/data."
            }
        }

        val registryEntries = listOfNotNull(cardesc, appearances)
        val registryPaths = registryEntries.map(::normalPath).toTypedArray()
        val registryData = registryEntries.map(::readGameFile)
        val carsByDataFolder = LinkedHashMap<String, Rr3ViewerCar>()
        for (car in previousCars) {
            carsByDataFolder[car.dataFolder.lowercase(Locale.ROOT)] = car
        }

        var missingAppearanceMappings = 0
        var readableRecords = 0
        val batches = carDataFiles.chunked(32)
        for ((batchIndex, batch) in batches.withIndex()) {
            onProgress?.invoke(
                "Car Editor · " + (batch.firstOrNull()?.relativePath ?: "vehicle data") +
                    " · batch " + (batchIndex + 1) + "/" + batches.size
            )
            val paths = ArrayList<String>(registryPaths.size + batch.size)
            val data = ArrayList<ByteArray>(registryData.size + batch.size)
            paths.addAll(registryPaths)
            data.addAll(registryData)
            for (entry in batch) {
                paths.add(normalPath(entry))
                data.add(readGameFile(entry))
            }

            val payload = JSONObject(
                NativeBridge.nativeInspectRr3CarCatalogue(paths.toTypedArray(), data.toTypedArray())
            )
            if (!payload.optBoolean("ok")) {
                throw IllegalStateException(
                    payload.optString(
                        "error",
                        "RR3 could not read car catalogue batch ${batchIndex + 1}."
                    )
                )
            }

            val array = payload.optJSONArray("cars") ?: org.json.JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (!item.optBoolean("readable")) continue
                val dataFolder = item.optString("folder").trim()
                if (dataFolder.isBlank() || dataFolder.contains('/') || dataFolder.contains("..")) continue

                val mappedModelFolder = item.optString("modelFolder").trim()
                if (mappedModelFolder.isNotBlank() &&
                    (mappedModelFolder.contains('/') || mappedModelFolder.contains(".."))
                ) continue

                // Car Editor needs the readable data file, not the appearance
                // registry. Keep the record visible when that optional mapping
                // is unavailable; use the data folder as a display/lookup fallback.
                val modelFolder = mappedModelFolder.ifBlank {
                    missingAppearanceMappings++
                    dataFolder
                }
                readableRecords++

                val label = listOf(
                    item.optString("year").trim(),
                    item.optString("make").trim(),
                    item.optString("model").trim()
                ).filter { it.isNotBlank() }.joinToString(" ")
                    .ifBlank { formatLiveryCarName(dataFolder) }

                carsByDataFolder.putIfAbsent(
                    dataFolder.lowercase(Locale.ROOT),
                    Rr3ViewerCar(
                        item.optInt("id"),
                        dataFolder,
                        modelFolder,
                        label,
                        item.optString("make").trim().ifBlank { "Other" },
                        cardesc ?: registryEntries.firstOrNull() ?: batch.first()
                    )
                )
            }
        }

        val sorted = carsByDataFolder.values.sortedWith(
            compareBy<Rr3ViewerCar>({ it.displayName.lowercase(Locale.ROOT) }, { it.id })
        )
        val baseStatus = if (onlyDataPaths == null) {
            "Indexed ${sorted.size} readable RR3 car records from ${carDataFiles.size} source files (${batches.size} batches)."
        } else {
            "Added ${(sorted.size - previousCars.size).coerceAtLeast(0)} new car record(s); catalogue now contains ${sorted.size} cars."
        }
        val mappingNote = if (missingAppearanceMappings > 0) {
            " ${missingAppearanceMappings} record(s) lacked an appearance-to-model mapping; they remain available in Car Editor using their data-folder name."
        } else {
            ""
        }
        val readNote = if (readableRecords == 0 && sorted.isEmpty()) {
            " The files were found, but none of the per-car records passed the native car-data parser."
        } else {
            ""
        }
        return sorted to (baseStatus + mappingNote + readNote)
    }

    private fun indexGameDataFiles() {
        val rootPath = preferences.getString("game_data_root_dir", null)
            ?.takeIf { it.isNotBlank() }
            ?: run {
                setGameDataStatus("Detect the installed RR3 depot with Root or Shizuku before indexing.")
                setIndexButtonsEnabled(false)
                return
            }
        val backend = preferences.getString("game_data_backend", "root") ?: "root"
        if (backend == "shizuku" && shizukuStorageService == null) {
            ensureShizukuStorageService { indexGameDataFiles() }
            return
        }
        setIndexButtonsEnabled(false)
        showIndexingOverlay("Scanning depot directory tree…")
        setGameDataStatus("Indexing RR3 depot files and Car Editor records…")
        gameFilesContainer.removeAllViews()
        Thread {
            try {
                val listing = if (backend == "shizuku") {
                    val service = shizukuStorageService
                        ?: throw IllegalStateException("Shizuku disconnected. Start it again and retry.")
                    val pipe = service.listFiles(rootPath)
                        ?: throw IllegalStateException("Shizuku could not list the RR3 files.")
                    ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else {
                    runRootCommand("find ${shellQuote(rootPath)} -type f -print")
                }
                val files = GameDataBrowser.fromRootListing(
                    rootPath, listing, useShizuku = backend == "shizuku"
                )
                updateIndexingOverlay("Indexing Car Editor vehicle files and registries…")
                val catalogue = try {
                    buildRr3ViewerCarCatalog(files) { updateIndexingOverlay(it) }
                } catch (error: OutOfMemoryError) {
                    emptyList<Rr3ViewerCar>() to
                        "The depot file index completed, but Android ran out of memory while caching the car catalogue. Re-index after closing other apps."
                } catch (error: Exception) {
                    emptyList<Rr3ViewerCar>() to
                        "The depot file index completed, but the car catalogue could not be cached: " +
                        (error.message ?: "Unknown catalogue error.")
                }
                val cacheError = try {
                    saveRr3IndexCache(rootPath, backend, files, catalogue.first, catalogue.second)
                    null
                } catch (error: Exception) {
                    error.message ?: "Could not save the index cache."
                } catch (error: OutOfMemoryError) {
                    "Android ran out of memory while saving the index cache."
                }
                runOnUiThread {
                    indexedGameFiles = files
                    rr3ViewerCars = catalogue.first
                    rr3ViewerCatalogStatus = catalogue.second
                    carEditorStatus.text = if (rr3ViewerCars.isEmpty()) {
                        "Car catalogue unavailable. ${rr3ViewerCatalogStatus}"
                    } else {
                        "Indexed ${rr3ViewerCars.size} cars. ${rr3ViewerCatalogStatus}"
                    }
                    gameFilesSearch.setText("")
                    setGameDataStatus(
                        "Indexed ${files.size} files across the full RR3 .depot. ${catalogue.second}" +
                            if (cacheError == null) "" else " Cache warning: $cacheError"
                    )
                    renderGameFiles()
                    renderLiveryCarChoices()
                    renderRr3ViewerCarChoices()
                    renderCarEditorIndexedChoices()
                    dismissIndexingOverlay()
                    setIndexButtonsEnabled(true)
                    preferences.edit().putBoolean("setup_complete", cacheError == null).apply()
                    setupScreen.visibility = if (cacheError == null) View.GONE else View.VISIBLE
                    rootScrollView.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dismissIndexingOverlay()
                    setGameDataStatus(e.message ?: "Could not index the RR3 .depot.")
                    setIndexButtonsEnabled(true)
                }
            } catch (e: OutOfMemoryError) {
                runOnUiThread {
                    dismissIndexingOverlay()
                    setGameDataStatus("Android ran out of memory while indexing the RR3 .depot. Close other apps and retry.")
                    setIndexButtonsEnabled(true)
                }
            }
        }.start()
    }
    private fun scanNewGameDataFiles() {
        val rootPath = preferences.getString("game_data_root_dir", null)
            ?.takeIf { it.isNotBlank() }
            ?: run {
                settingsIndexStatus.text = "Detect the RR3 depot with Root or Shizuku first."
                return
            }
        if (indexedGameFiles.isEmpty()) {
            settingsIndexStatus.text = "No existing index was found; performing the initial index."
            indexGameDataFiles()
            return
        }
        val backend = preferences.getString("game_data_backend", "root") ?: "root"
        if (backend == "shizuku" && shizukuStorageService == null) {
            ensureShizukuStorageService { scanNewGameDataFiles() }
            return
        }
        scanNewFilesButton.isEnabled = false
        showIndexingOverlay("Scanning depot directory tree for new files…")
        settingsIndexStatus.text = "Checking depot paths for additions…"
        Thread {
            try {
                val listing = if (backend == "shizuku") {
                    val service = shizukuStorageService
                        ?: throw IllegalStateException("Shizuku disconnected. Start it again and retry.")
                    val pipe = service.listFiles(rootPath)
                        ?: throw IllegalStateException("Shizuku could not list the RR3 files.")
                    ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else {
                    runRootCommand("find ${shellQuote(rootPath)} -type f -print")
                }
                val discovered = GameDataBrowser.fromRootListing(
                    rootPath, listing, useShizuku = backend == "shizuku"
                )
                val knownPaths = indexedGameFiles.map {
                    it.relativePath.replace('\\', '/').trimStart('/').lowercase(Locale.ROOT)
                }.toHashSet()
                val newFiles = discovered.filter {
                    it.relativePath.replace('\\', '/').trimStart('/').lowercase(Locale.ROOT) !in knownPaths
                }
                val merged = (indexedGameFiles + newFiles).sortedBy {
                    it.relativePath.lowercase(Locale.ROOT)
                }
                val newCarPaths = newFiles.asSequence()
                    .filter {
                        val path = it.relativePath.replace('\\', '/').trimStart('/').lowercase(Locale.ROOT)
                        path.substringBeforeLast('/', "") == "vehicles/data" &&
                            Regex("^[0-9]{1,6}_.+\\.bin\\.nct$", RegexOption.IGNORE_CASE)
                                .matches(path.substringAfterLast('/'))
                    }
                    .map { it.relativePath.replace('\\', '/').trimStart('/').lowercase(Locale.ROOT) }
                    .toSet()
                updateIndexingOverlay("Updating Car Editor vehicle index…")
                val catalogue = if (newCarPaths.isNotEmpty()) {
                    buildRr3ViewerCarCatalog(merged, newCarPaths, rr3ViewerCars) { updateIndexingOverlay(it) }
                } else {
                    rr3ViewerCars to rr3ViewerCatalogStatus
                }
                saveRr3IndexCache(rootPath, backend, merged, catalogue.first, catalogue.second)
                runOnUiThread {
                    indexedGameFiles = merged
                    rr3ViewerCars = catalogue.first
                    rr3ViewerCatalogStatus = catalogue.second
                    renderGameFiles()
                    renderLiveryCarChoices()
                    renderRr3ViewerCarChoices()
                    renderCarEditorIndexedChoices()
                    settingsIndexStatus.text = if (newFiles.isEmpty()) {
                        "No new files found. The existing ${indexedGameFiles.size}-file index was kept."
                    } else {
                        "Added ${newFiles.size} new file(s). The index now contains ${indexedGameFiles.size} files. ${catalogue.second}"
                    }
                    gameFilesStatus.text =
                        "Indexed ${indexedGameFiles.size} files across the full RR3 .depot. ${rr3ViewerCatalogStatus}"
                    dismissIndexingOverlay()
                    scanNewFilesButton.isEnabled = true
                }
            } catch (error: Exception) {
                runOnUiThread {
                    dismissIndexingOverlay()
                    settingsIndexStatus.text = error.message ?: "Could not scan for new files."
                    scanNewFilesButton.isEnabled = true
                }
            } catch (error: OutOfMemoryError) {
                runOnUiThread {
                    dismissIndexingOverlay()
                    settingsIndexStatus.text = "Android ran out of memory while scanning the depot."
                    scanNewFilesButton.isEnabled = true
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
            setGameDataStatus("Shizuku is not running. Start it through Wireless Debugging, then retry.")
            return
        }
        if (Shizuku.isPreV11()) {
            pendingShizukuAction = null
            setGameDataStatus("This Shizuku version is too old. Update Shizuku and retry.")
            return
        }
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(shizukuPermissionRequestCode)
                setGameDataStatus("Waiting for Shizuku permission…")
                return
            }
            bindShizukuStorageService()
        } catch (e: Exception) {
            pendingShizukuAction = null
            setGameDataStatus(e.message ?: "Could not connect to Shizuku.")
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
            setGameDataStatus("Connecting to Shizuku's storage service…")
        } catch (e: Exception) {
            shizukuBindPending = false
            pendingShizukuAction = null
            setGameDataStatus(e.message ?: "Could not bind the Shizuku storage service.")
        }
    }
    private fun locateShizukuGameDataFolder() {
        setGameDataStatus("Checking Shizuku and locating the installed RR3 folder…")
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
                            .putBoolean("setup_complete", false)
                            .apply()
                        rr3IndexCacheFile.delete()
                        gameDataValue.text = found
                        if (::setupIndexButton.isInitialized) setupIndexButton.isEnabled = true
                        preferences.edit().putBoolean("setup_complete", false).apply()
                        indexedGameFiles = emptyList()
                        gameFilesContainer.removeAllViews()
                        setGameDataStatus("Found RR3 .depot using Shizuku (UID $uid): $found. Step 2 is ready.")
                    }
                } catch (e: Exception) {
                    runOnUiThread { setGameDataStatus(e.message ?: "Shizuku could not access the RR3 game folder.") }
                }
            }.start()
        }
    }
    private fun locateRootGameDataFolder() {
        locateRootGameDataButton.isEnabled = false
        setGameDataStatus("Requesting root and locating the RR3 .depot folder…")
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
                    .putBoolean("setup_complete", false)
                    .apply()
                rr3IndexCacheFile.delete()
                runOnUiThread {
                    gameDataValue.text = found
                    if (::setupIndexButton.isInitialized) setupIndexButton.isEnabled = true
                    preferences.edit().putBoolean("setup_complete", false).apply()
                    indexedGameFiles = emptyList()
                    gameFilesContainer.removeAllViews()
                    setGameDataStatus("Found populated RR3 .depot: " + found + ". Step 2 is ready.")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setGameDataStatus(e.message ?: "Could not detect RR3 .depot with root.")
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
                text("No indexed files yet.", 12, UiPalette.textSubtle),
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
                text("No matching RR3 files.", 12, UiPalette.textSubtle),
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
                    UiPalette.text,
                    bold = true
                ).apply {
                    setPadding(dp(11 + depth * 16), dp(10), dp(10), dp(10))
                    background = rounded(UiPalette.surface, dp(9).toFloat())
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
                    background = rounded(UiPalette.background, dp(9).toFloat())
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { showGameFileActions(entry) }
                }
                parent.addView(fileRow, matchWrap(bottom = 4))
                fileRow.addView(text("📄  " + entry.name, 12, UiPalette.text, bold = true), matchWrap())
                val size = if (entry.sizeBytes >= 0) "  ·  ${entry.sizeBytes} bytes" else ""
                fileRow.addView(
                    text(entry.relativePath + size, 10, UiPalette.textSecondary),
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
            "Indexed ${indexedGameFiles.size} files across the full RR3 .depot. ${rr3ViewerCatalogStatus}"
        } else {
            "Showing ${matches.size} file(s) matching “${gameFilesSearch.text}”. Matching folders are expanded."
        }
    }
    /** Returns assets from the app-wide depot index; tabs must not rescan the game folder. */
    private fun scanLiveryCarAssets(liveryEntry: GameDataFile, carFolder: String): List<GameDataFile> {
        val prefix = "vehicles/" + carFolder.trim('/', '\\') + "/"
        val assets = indexedGameFiles.filter { entry ->
            entry.relativePath.replace('\\', '/').trimStart('/')
                .startsWith(prefix, ignoreCase = true)
        }.sortedBy { it.relativePath.lowercase(Locale.ROOT) }
        if (assets.isEmpty()) {
            throw IllegalStateException(
                "No indexed assets were found under vehicles/$carFolder. " +
                    "Return to Index and run Index all RR3 game assets."
            )
        }
        return assets
    }
    private fun inferLegacyCarBrand(displayName: String, modelFolder: String): String {
        val cleaned = displayName.trim().replace(Regex("^[0-9]{4}\\s+"), "")
        val folderLabel = formatLiveryCarName(
            modelFolder.trim().replace(Regex("^[0-9]{4}_"), "")
        )
        val candidates = listOf(cleaned, folderLabel).filter { it.isNotBlank() }
        val knownMultiWordMakes = listOf(
            "Aston Martin", "Alfa Romeo", "Mercedes-AMG", "Mercedes Benz",
            "Land Rover", "Rolls-Royce", "Lamborghini Squadra Corse"
        )
        for (candidate in candidates) {
            knownMultiWordMakes.firstOrNull { candidate.startsWith(it, ignoreCase = true) }?.let {
                return it
            }
        }
        val first = candidates.firstOrNull()?.substringBefore(' ')?.trim()
        return first?.takeIf { it.isNotBlank() } ?: "Other"
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
    /** The catalogue is populated only by indexGameDataFiles(); this method never reads depot files. */
    private fun renderRr3ViewerCarChoices() {
        if (!::rr3ViewerCarListContainer.isInitialized) return
        rr3ViewerCarListContainer.removeAllViews()
        if (indexedGameFiles.isEmpty()) {
            rr3ViewerCars = emptyList()
            rr3ViewerStatus.text = "Complete the depot index before loading a car."
            rr3ViewerCarListContainer.addView(
                text("Index the depot to load cars.", 12, UiPalette.textSubtle),
                matchWrap()
            )
            return
        }
        if (rr3ViewerCars.isEmpty()) {
            rr3ViewerStatus.text = rr3ViewerCatalogStatus
            rr3ViewerCarListContainer.addView(
                text("No cached car records.", 12, UiPalette.textSubtle),
                matchWrap()
            )
            return
        }
        val groups = rr3ViewerCars.groupBy { it.brand.ifBlank { "Other" } }
            .toSortedMap(compareBy<String> { it.lowercase(Locale.ROOT) })
        for ((brand, cars) in groups) {
            val expanded = brand in expandedViewerBrands
            val header = text(
                (if (expanded) "▾  " else "▸  ") + brand + "  ·  " + cars.size + " model(s)",
                14, UiPalette.text, bold = true
            ).apply {
                setPadding(dp(12), dp(12), dp(10), dp(12))
                background = rounded(UiPalette.surface, dp(9).toFloat())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (brand in expandedViewerBrands) expandedViewerBrands.remove(brand)
                    else expandedViewerBrands.add(brand)
                    renderRr3ViewerCarChoices()
                }
            }
            rr3ViewerCarListContainer.addView(header, matchWrap(bottom = 5))
            if (!expanded) continue
            for (car in cars.sortedWith(
                compareBy<Rr3ViewerCar>({ it.displayName.lowercase(Locale.ROOT) }, { it.id })
            )) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = rounded(UiPalette.background, dp(9).toFloat())
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { loadRr3ViewerCar(car) }
                }
                rr3ViewerCarListContainer.addView(row, matchWrap(bottom = 5))
                row.addView(text(car.displayName, 13, UiPalette.text, bold = true), matchWrap())
                row.addView(
                    text(car.modelFolder + "  ·  Tap to load", 10, UiPalette.textSecondary),
                    matchWrap(top = 2)
                )
            }
        }
        rr3ViewerStatus.text =
            "Loaded ${rr3ViewerCars.size} car choices from the Index cache. Expand a brand and tap a model to load it."
    }
    private fun loadRr3ViewerCar(car: Rr3ViewerCar) {
        if (indexedGameFiles.isEmpty()) { rr3ViewerStatus.text = "Index the RR3 .depot in Index first."; return }
        if (rr3ViewerLoading) return
        rr3ViewerLoading = true
        rr3ViewerStatus.text = "Finding " + car.displayName + " (" + car.modelFolder + ") exterior model and sidecars…"
        Thread {
            try {
                val assets = scanLiveryCarAssets(car.liveryEntry, car.modelFolder)
                fun plainName(name: String): String = name.removeSuffixIgnoreCaseLocal(".bin").removeSuffixIgnoreCaseLocal(".z")
                val expectedStem = car.modelFolder.lowercase(Locale.ROOT)
                val candidates = assets.filter { entry ->
                    val leaf = plainName(entry.name).substringAfterLast('/').lowercase(Locale.ROOT)
                    val ext = leaf.substringAfterLast('.', "")
                    (ext == "m3g" || ext == "sb3d") && !leaf.contains("_int") && !leaf.contains("_shared") && !leaf.contains("_wheels") && !leaf.contains("driver_lod")
                }
                val modelEntry = candidates.sortedWith(compareBy<GameDataFile> { entry ->
                    val leaf = plainName(entry.name).substringAfterLast('/').lowercase(Locale.ROOT)
                    when { leaf == expectedStem + "_a.m3g" -> 0; leaf == expectedStem + "_a.sb3d" -> 1; leaf.startsWith(expectedStem) && leaf.endsWith("_a.m3g") -> 2; leaf.startsWith(expectedStem) && leaf.endsWith(".m3g") -> 3; leaf.endsWith("_a.m3g") -> 4; else -> 5 }
                }.thenBy { it.relativePath.lowercase(Locale.ROOT) }).firstOrNull() ?: throw IllegalStateException("No exterior model for " + car.displayName + " was found in vehicles/" + car.modelFolder + ".")
                val modelSource = readGameFile(modelEntry)
                val (innerModelName, modelBytes) = unwrappedGameFile(modelEntry, modelSource)
                val modelExtension = innerModelName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (modelExtension != "m3g" && modelExtension != "sb3d") throw IllegalStateException("The selected file did not unwrap to a supported RR3 3D model.")
                val modelPath = plainName(modelEntry.relativePath)
                val supportPaths = ArrayList<String>()
                val supportData = ArrayList<ByteArray>()
                var supportBytes = 0L
                var skippedTextures = 0
                val wantedLivery = "vehicles/data/liveries/" + car.dataFolder + ".liveries.bin.nct"
                indexedGameFiles.firstOrNull { it.relativePath.equals(wantedLivery, ignoreCase = true) }?.let {
                    val bytes = readGameFile(it); supportPaths.add(it.relativePath.replace('\\', '/')); supportData.add(bytes); supportBytes += bytes.size.toLong()
                }
                for (entry in assets) {
                    if (entry.relativePath == modelEntry.relativePath) continue
                    val leaf = plainName(entry.name).substringAfterLast('/').lowercase(Locale.ROOT)
                    val ext = leaf.substringAfterLast('.', "")
                    val isPoint = ext == "points"
                    val isShared = ext == "m3g" && leaf.contains("_shared")
                    val isTexture = ext in setOf("sba", "pvr", "dds", "png", "jpg", "jpeg")
                    if (!isPoint && !isShared && !isTexture) continue
                    try {
                        val raw = readGameFile(entry); val (innerName, bytes) = unwrappedGameFile(entry, raw)
                        val innerExt = innerName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                        if (innerExt !in setOf("points", "m3g", "sba", "pvr", "dds", "png", "jpg", "jpeg")) continue
                        val tex = innerExt in setOf("sba", "pvr", "dds", "png", "jpg", "jpeg")
                        if (tex && bytes.size > 16 * 1024 * 1024) { skippedTextures++; continue }
                        if (supportBytes + bytes.size > 64L * 1024L * 1024L) { if (tex) { skippedTextures++; continue }; throw IllegalStateException("This car's model sidecars exceed the 64 MiB preview limit.") }
                        supportBytes += bytes.size.toLong(); supportPaths.add(plainName(entry.relativePath)); supportData.add(bytes)
                    } catch (error: Exception) { if (isPoint || isShared) throw error; skippedTextures++ }
                }
                val result = JSONObject(NativeBridge.nativeCreateRr3Viewer(modelPath, car.dataFolder, modelBytes, supportPaths.toTypedArray(), supportData.toTypedArray()))
                if (!result.optBoolean("ok")) throw IllegalStateException(result.optString("error", "Monkey Tool could not prepare this RR3 model."))
                val handle = result.optLong("handle", 0L)
                if (handle == 0L) throw IllegalStateException("The native viewer did not create a model session.")
                val meshes = result.optInt("meshes"); val triangles = result.optInt("triangles"); val textures = result.optInt("textures")
                if (meshes <= 0 || triangles <= 0) { NativeBridge.nativeFreeRr3Viewer(handle); throw IllegalStateException("The model parser found no drawable exterior triangles in " + modelEntry.relativePath + ".") }
                runOnUiThread {
                    rr3ViewerSurface?.dispose()
                    val surface = Rr3ModelViewer(this, handle) { frameStatus ->
                        val detail = car.displayName + " · " + meshes + " mesh(es) · " + triangles + " triangles · " + textures + " texture(s) · " + frameStatus + if (skippedTextures > 0) " · " + skippedTextures + " texture file(s) skipped" else ""
                        rr3ViewerStatus.text = detail
                        rr3ViewerScreenStatus.text = detail
                    }
                    rr3ViewerSurface = surface
                    activeRr3ViewerCarId = car.id
                    surface.setCameraTuning(
                        preferences.getFloat("viewer_camera_pan_x", 0f),
                        preferences.getFloat("viewer_camera_pan_y", 0f),
                        preferences.getFloat("viewer_camera_fov", 0.75f)
                    )
                    surface.setCameraAngles(
                        preferences.getFloat("viewer_camera_yaw", 0.6f),
                        preferences.getFloat("viewer_camera_pitch", 0.25f)
                    )
                    surface.setCameraZoom(preferences.getFloat("viewer_camera_zoom", 1f))
                    surface.setEnvironmentEnabled(preferences.getBoolean("viewer_environment_enabled", true))
                    rr3ViewerScreenTitle.text = car.displayName
                    rr3ViewerScreenStatus.text = meshes.toString() + " mesh(es) · " + triangles + " triangles · " + textures + " texture(s)"
                    rr3ViewerStatus.text = "Loaded " + car.displayName + "."
                    rr3ViewerLoading = false
                    openRr3FullScreenViewer()
                }
            } catch (error: Exception) { runOnUiThread {
                rr3ViewerStatus.text = error.message ?: "Could not load this car in the 3D viewer."
                rr3ViewerLoading = false
            } }
            catch (error: OutOfMemoryError) { runOnUiThread {
                rr3ViewerStatus.text = "Android ran out of memory while loading this car. Close other apps and try again."
                rr3ViewerLoading = false
            } }
        }.start()
    }
    private fun openRr3FullScreenViewer() {
        val surface = rr3ViewerSurface
        if (surface == null) {
            rr3ViewerStatus.text = "Load a car before opening the 3D viewer."
            return
        }
        if (rr3ViewerScreen.visibility == View.VISIBLE) return

        viewerPreviousRequestedOrientation = requestedOrientation
        viewerPreviousSystemUiVisibility = window.decorView.systemUiVisibility

        (surface.parent as? ViewGroup)?.removeView(surface)
        rr3ViewerScreenHost.removeAllViews()
        rr3ViewerScreenHost.addView(surface, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        rootScrollView.visibility = View.GONE
        editorScreen.visibility = View.GONE
        rr3ViewerScreen.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        surface.requestLayout()
    }

    private fun closeRr3FullScreenViewer() {
        if (!::rr3ViewerScreen.isInitialized || rr3ViewerScreen.visibility != View.VISIBLE) return
        rr3ViewerSurface?.let { surface ->
            (surface.parent as? ViewGroup)?.removeView(surface)
        }
        rr3ViewerScreenHost.removeAllViews()
        rr3ViewerScreen.visibility = View.GONE
        rootScrollView.visibility = View.VISIBLE
        requestedOrientation = viewerPreviousRequestedOrientation
        window.decorView.systemUiVisibility = viewerPreviousSystemUiVisibility
        rr3ViewerSurface?.refresh()
    }

    private fun showRr3ViewerMenu() {
        val actions = arrayOf(
            "Live camera controls",
            "Toggle panorama backdrop",
            "Reset camera",
            "Edit camTweaks.dat"
        )
        AlertDialog.Builder(this)
            .setTitle("3D Viewer menu")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showRr3ViewerCameraControls()
                    1 -> {
                        val enabled = !preferences.getBoolean("viewer_environment_enabled", true)
                        preferences.edit().putBoolean("viewer_environment_enabled", enabled).apply()
                        rr3ViewerSurface?.setEnvironmentEnabled(enabled)
                        rr3ViewerScreenStatus.text = if (enabled) "Panorama backdrop enabled" else "Panorama backdrop disabled"
                    }
                    2 -> {
                        preferences.edit()
                            .remove("viewer_camera_pan_x")
                            .remove("viewer_camera_pan_y")
                            .remove("viewer_camera_fov")
                            .remove("viewer_camera_yaw")
                            .remove("viewer_camera_pitch")
                            .remove("viewer_camera_zoom")
                            .apply()
                        rr3ViewerSurface?.resetCamera()
                    }
                    3 -> openCamTweaksForViewer()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showRr3ViewerCameraControls() {
        var panX = preferences.getFloat("viewer_camera_pan_x", 0f)
        var panY = preferences.getFloat("viewer_camera_pan_y", 0f)
        var fovDegrees = (preferences.getFloat("viewer_camera_fov", 0.75f) * 180f / Math.PI.toFloat()).coerceIn(25f, 110f)
        var yawDegrees = (preferences.getFloat("viewer_camera_yaw", 0.6f) * 180f / Math.PI.toFloat()).let {
            ((it % 360f) + 360f) % 360f
        }
        var pitchDegrees = (preferences.getFloat("viewer_camera_pitch", 0.25f) * 180f / Math.PI.toFloat()).coerceIn(-63f, 63f)
        var zoom = preferences.getFloat("viewer_camera_zoom", 1f).coerceIn(0.55f, 3f)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(4))
        }
        fun addCameraSlider(
            title: String,
            min: Float,
            max: Float,
            initial: Float,
            onValue: (Float) -> Unit
        ) {
            val label = text("$title · ${"%.1f".format(Locale.ROOT, initial)}", 12, UiPalette.text)
            root.addView(label, matchWrap(top = 7))
            val seek = SeekBar(this).apply {
                this.max = 1000
                progress = (((initial - min) / (max - min)) * 1000f).toInt().coerceIn(0, 1000)
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = min + (max - min) * progress / 1000f
                    label.text = "$title · ${"%.1f".format(Locale.ROOT, value)}"
                    onValue(value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
            root.addView(seek, matchWrap(bottom = 2))
        }
        fun updateTuning() {
            rr3ViewerSurface?.setCameraTuning(
                panX, panY, fovDegrees * Math.PI.toFloat() / 180f
            )
        }
        fun updateOrbit() {
            rr3ViewerSurface?.setCameraAngles(
                yawDegrees * Math.PI.toFloat() / 180f,
                pitchDegrees * Math.PI.toFloat() / 180f
            )
        }
        fun updateZoom() {
            rr3ViewerSurface?.setCameraZoom(zoom)
        }
        addCameraSlider("Camera position X", -2f, 2f, panX) { panX = it; updateTuning() }
        addCameraSlider("Camera position Y", -2f, 2f, panY) { panY = it; updateTuning() }
        addCameraSlider("Camera yaw (degrees)", 0f, 360f, yawDegrees) { yawDegrees = it; updateOrbit() }
        addCameraSlider("Camera pitch (degrees)", -63f, 63f, pitchDegrees) { pitchDegrees = it; updateOrbit() }
        addCameraSlider("Field of view (degrees)", 25f, 110f, fovDegrees) { fovDegrees = it; updateTuning() }
        addCameraSlider("Zoom", 0.55f, 3f, zoom) { zoom = it; updateZoom() }
        AlertDialog.Builder(this)
            .setTitle("Live viewer camera preview")
            .setMessage("Changes update the preview immediately. Save stores a local viewer preset, not game camTweaks.dat.")
            .setView(root)
            .setPositiveButton("Save preview preset") { _, _ ->
                preferences.edit()
                    .putFloat("viewer_camera_pan_x", panX)
                    .putFloat("viewer_camera_pan_y", panY)
                    .putFloat("viewer_camera_fov", fovDegrees * Math.PI.toFloat() / 180f)
                    .putFloat("viewer_camera_yaw", yawDegrees * Math.PI.toFloat() / 180f)
                    .putFloat("viewer_camera_pitch", pitchDegrees * Math.PI.toFloat() / 180f)
                    .putFloat("viewer_camera_zoom", zoom)
                    .apply()
                rr3ViewerScreenStatus.text = "Local viewer camera preset saved"
            }
            .setNeutralButton("Reset Camera") { _, _ ->
                preferences.edit()
                    .remove("viewer_camera_pan_x")
                    .remove("viewer_camera_pan_y")
                    .remove("viewer_camera_fov")
                    .remove("viewer_camera_yaw")
                    .remove("viewer_camera_pitch")
                    .remove("viewer_camera_zoom")
                    .apply()
                panX = 0f; panY = 0f
                fovDegrees = 0.75f * 180f / Math.PI.toFloat()
                yawDegrees = 0.6f * 180f / Math.PI.toFloat()
                pitchDegrees = 0.25f * 180f / Math.PI.toFloat()
                zoom = 1f
                rr3ViewerSurface?.resetCamera()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun renderSelectedLiveryDecodedData() {
        if (!::liveryDecodedContainer.isInitialized) return
        liveryDecodedContainer.removeAllViews()
        val payload = selectedLiveryPayload
        if (payload == null) {
            liveryDecodedContainer.addView(
                text(
                    "Select a car in Cars, or load a .bin.nct file in Manual, then return here to inspect its decoded data.",
                    12, UiPalette.textMuted
                ),
                matchWrap()
            )
            return
        }
        fun addDetail(parent: LinearLayout, label: String, value: String) {
            parent.addView(
                text(label, 10, UiPalette.accent, bold = true),
                matchWrap(top = 9)
            )
            parent.addView(
                text(value.ifBlank { "—" }, 12, UiPalette.text).apply {
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
            text(title, 15, UiPalette.text, bold = true),
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
                text("The parser decoded the container but found no livery records.", 12, UiPalette.accent),
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
                background = rounded(UiPalette.background, dp(10).toFloat())
            }
            liveryDecodedContainer.addView(record, matchWrap(top = 12))
            record.addView(
                text(
                    "RECORD ${index + 1} · " +
                        stringValue(item, "display").ifBlank { stringValue(item, "file").ifBlank { "Unnamed" } },
                    13, UiPalette.text, bold = true
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
                    10, UiPalette.accent, bold = true
                ),
                matchWrap(top = 12, bottom = 4)
            )
            if (overrides == null || overrides.length() == 0) {
                record.addView(text("None parsed", 11, UiPalette.textSecondary), matchWrap())
            } else {
                for (overrideIndex in 0 until overrides.length()) {
                    val override = overrides.optJSONObject(overrideIndex) ?: continue
                    record.addView(
                        text(
                            "Slot ${override.optInt("slot")}  →  " + stringValue(override, "path"),
                            11, UiPalette.text
                        ).apply { setTextIsSelectable(true) },
                        matchWrap(top = 3)
                    )
                }
            }
            val materials = item.optJSONArray("materials")
            record.addView(
                text(
                    "MESH / MATERIAL ASSIGNMENTS · ${materials?.length() ?: 0}",
                    10, UiPalette.accent, bold = true
                ),
                matchWrap(top = 12, bottom = 4)
            )
            if (materials == null || materials.length() == 0) {
                record.addView(text("None parsed", 11, UiPalette.textSecondary), matchWrap())
            } else {
                for (materialIndex in 0 until materials.length()) {
                    val material = materials.optJSONObject(materialIndex) ?: continue
                    record.addView(
                        text(
                            "Mesh ${material.optInt("mesh")}  →  material ${material.optInt("material")}",
                            11, UiPalette.text
                        ).apply { setTextIsSelectable(true) },
                        matchWrap(top = 3)
                    )
                }
            }
        }
        liveryDecodedContainer.addView(
            text("RAW PARSER OUTPUT · JSON", 11, UiPalette.accent, bold = true),
            matchWrap(top = 18, bottom = 6)
        )
        liveryDecodedContainer.addView(
            text(payload.toString(2), 10, UiPalette.text).apply {
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
                "Index the RR3 .depot in Index first."
            } else {
                "No car livery lists were found in the indexed depot (the generic default list is hidden)."
            }
            liveryCarListContainer.addView(
                text("No selectable cars yet.", 12, UiPalette.textSubtle),
                matchWrap()
            )
            return
        }
        liveryStatus.text = "Found ${entries.size} car livery list(s). Expand a brand, then select a car."
        val choices = entries.map { (entry, _, carName) ->
            val carFolder = entry.relativePath.replace('\\', '/')
                .substringAfter(marker)
                .removeSuffixIgnoreCaseLocal(".liveries.bin.nct")
            val catalogueCar = rr3ViewerCars.firstOrNull {
                it.dataFolder.equals(carFolder, ignoreCase = true)
            }
            LiveryCarChoice(
                entry = entry,
                carFolder = carFolder,
                displayName = catalogueCar?.displayName?.ifBlank { formatLiveryCarName(carName) }
                    ?: formatLiveryCarName(carName),
                brand = catalogueCar?.brand?.takeIf { it.isNotBlank() && it != "Other" }
                    ?: inferLegacyCarBrand(catalogueCar?.displayName.orEmpty(), carFolder)
            )
        }
        for ((brand, cars) in choices.groupBy { it.brand }
            .toSortedMap(compareBy<String> { it.lowercase(Locale.ROOT) })) {
            val expanded = brand in expandedLiveryBrands
            val brandRow = text(
                (if (expanded) "▾  " else "▸  ") + brand + "  ·  " + cars.size + " car(s)",
                14, UiPalette.text, bold = true
            ).apply {
                setPadding(dp(12), dp(12), dp(10), dp(12))
                background = rounded(UiPalette.surface, dp(9).toFloat())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (brand in expandedLiveryBrands) expandedLiveryBrands.remove(brand)
                    else expandedLiveryBrands.add(brand)
                    renderLiveryCarChoices()
                }
            }
            liveryCarListContainer.addView(brandRow, matchWrap(bottom = 5))
            if (!expanded) continue
            for (choice in cars.sortedBy { it.displayName.lowercase(Locale.ROOT) }) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = rounded(UiPalette.background, dp(9).toFloat())
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { loadLiverySlots(choice.entry) }
                }
                liveryCarListContainer.addView(row, matchWrap(bottom = 5))
                row.addView(text(choice.displayName, 13, UiPalette.text, bold = true), matchWrap())
                row.addView(text(choice.carFolder, 10, UiPalette.textSecondary), matchWrap(top = 2))
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
            text("$carFolder · ${liveryArray.length()} existing slot(s)", 16, UiPalette.text, bold = true),
            matchWrap(bottom = 9)
        )
        if (liveryArray.length() == 0) {
            target.addView(
                text("This car has no readable livery entries to use as a template.", 12, UiPalette.textMuted),
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
                background = rounded(UiPalette.background, dp(10).toFloat())
            }
            target.addView(slot, matchWrap(bottom = 6))
            slot.addView(
                text("Slot ${i + 1}  ·  $display", 13, UiPalette.text, bold = true),
                matchWrap()
            )
            if (details.isNotEmpty()) {
                slot.addView(
                    text(details.joinToString("  ·  "), 11, UiPalette.textSecondary),
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
            val addStatus = text(
                "Ready to prepare a new livery slot.",
                11,
                UiPalette.textMuted
            )
            target.addView(addStatus, matchWrap(top = 8))
            target.addView(
                actionButton("Add a new livery slot") { prepareAddLiverySlot(addStatus) },
                matchWrap(top = 5)
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
            content.addView(text(label, 12, UiPalette.text, bold = true), matchWrap(bottom = 4))
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
                        "Preview replacement needs the indexed RR3 depot. Re-index Index and try again."
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
            liveryPaintStatus.text = "Index the RR3 .depot in Index first."
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
                "Could not find ${missing.joinToString(", ")} in the indexed depot. Re-index Index and try again."
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
                text("MOTORSPORTS · OFFICIAL / SPECIAL LABEL", 11, UiPalette.textSecondary, bold = true),
                matchWrap(bottom = 6)
            )
            for (item in special) {
                val key = item.optString("key")
                val current = item.optString("value")
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(10), dp(10))
                    background = rounded(UiPalette.background, dp(9).toFloat())
                }
                val label = text(
                    current.ifBlank { fallbackKeyName(key) },
                    13,
                    UiPalette.text,
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
            text("ROAD CARS · PAINT MATERIAL AND COLOUR", 11, UiPalette.textSecondary, bold = true),
            matchWrap(top = 12, bottom = 6)
        )
        if (paintOptions.length() == 0) {
            liveryPaintContainer.addView(
                text("No Road Cars paint options were found in custom.paints.nct.", 12, UiPalette.textMuted),
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
                background = rounded(UiPalette.background, dp(9).toFloat())
            }
            row.addView(
                text(values.joinToString("  ·  ").ifBlank { "Paint option ${i + 1}" }, 13, UiPalette.text, bold = true),
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
                text("PAINT-PACK CATEGORY NAMES", 11, UiPalette.textSecondary, bold = true),
                matchWrap(top = 12, bottom = 6)
            )
            for (item in otherPackLabels) {
                val key = item.optString("key")
                val value = item.optString("value")
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(10), dp(10))
                    background = rounded(UiPalette.background, dp(9).toFloat())
                }
                row.addView(
                    text(value.ifBlank { fallbackKeyName(key) }, 13, UiPalette.text, bold = true),
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
                text(field.label, 12, UiPalette.text, bold = true),
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
                UiPalette.textSecondary
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
    private fun prepareAddLiverySlot(statusView: TextView) {
        fun report(message: String) {
            liveryStatus.text = message
            statusView.text = message
        }
        if (liveryAddPreflightRunning) {
            report("Already preparing the livery assets. Please wait…")
            return
        }
        val entry = selectedLiveryEntry ?: run {
            report("Select a car and load its livery list first.")
            return
        }
        val carFolder = selectedLiveryCarFolder
        val liveryBytes = selectedLiveryBytes ?: run {
            report("Reload the car's livery list before adding a slot.")
            return
        }
        val liveryArray = selectedLiveryArray ?: run {
            report("Reload the car's livery list before adding a slot.")
            return
        }
        if (liveryArray.length() == 0) {
            report("This car has no existing livery to copy as a template.")
            return
        }

        // Capture the selected inputs before leaving the UI thread. The user
        // can change cars while the asset scan is running.
        liveryAddPreflightRunning = true
        report("Preparing texture assets for $carFolder…")
        Thread {
            try {
                val carAssets = scanLiveryCarAssets(entry, carFolder)
                val assetList = indexedGameFiles.firstOrNull {
                    it.relativePath.substringAfterLast('/').equals(
                        "asset_list_base.txtCache.txt",
                        ignoreCase = true
                    )
                } ?: throw IllegalStateException(
                    "asset_list_base.txtCache.txt is missing from the depot index. Re-index RR3 and try again."
                )
                runOnUiThread {
                    liveryAddPreflightRunning = false
                    report("Assets ready. Choose a template and enter the new livery details.")
                    showAddLiverySlotDialog(
                        entry, carFolder, liveryBytes, liveryArray, carAssets, assetList
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    liveryAddPreflightRunning = false
                    report(e.message ?: "Could not prepare the livery editor.")
                }
            } catch (e: OutOfMemoryError) {
                runOnUiThread {
                    liveryAddPreflightRunning = false
                    report("Android ran out of memory while preparing the livery assets.")
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
            12, UiPalette.textMuted
        ), matchWrap(bottom = 10))
        content.addView(text("Template livery", 12, UiPalette.text, bold = true), matchWrap(bottom = 5))
        val templateSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, labels).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(0)
        }
        content.addView(templateSpinner, matchWrap(bottom = 10))
        val nameInput = editBox("Livery display name", "", numeric = false)
        content.addView(text("Display name", 12, UiPalette.text, bold = true), matchWrap(bottom = 5))
        content.addView(nameInput, matchWrap(bottom = 10))
        content.addView(text("Button colour · RGB (0–255)", 12, UiPalette.text, bold = true), matchWrap(bottom = 5))
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
            11, UiPalette.textSecondary
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
        if (lower == "camtweaks.dat") actions.add("Edit camera tweaks")
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
                    "Edit camera tweaks" -> openCamTweaksGameFile(entry)
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
                11, UiPalette.accent, bold = true
            ),
            matchWrap(bottom = 6)
        )
        root.addView(
            text(
                "These are named 3D hardpoints used by the car asset, not performance settings. Tap a point, then edit its X/Y/Z position or nudge it. HINGE_ points also store orientation. Export creates a separate edited file; the original stays untouched.",
                12, UiPalette.textMuted
            ),
            matchWrap(bottom = 10)
        )
        val listScroll = ScrollView(this).apply {
            isFillViewport = false
            isNestedScrollingEnabled = true
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            background = rounded(UiPalette.background, dp(10).toFloat())
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        listScroll.addView(list)
        root.addView(listScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(235)
        ).apply { bottomMargin = dp(10) })
        val selectedTitle = text("", 13, UiPalette.text, bold = true)
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
        root.addView(
            text("Selected point position · enter model coordinates", 11, UiPalette.textMuted),
            matchWrap(bottom = 4)
        )
        root.addView(coordRow, matchWrap(bottom = 8))
        val setRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val setButton = actionButton("Apply coordinates") { }
        val mirrorCheck = android.widget.CheckBox(this).apply {
            text = "Mirror twin"
            textSize = 12f
            setTextColor(UiPalette.text)
            buttonTintList = android.content.res.ColorStateList.valueOf(UiPalette.accent)
            isChecked = true
        }
        setRow.addView(setButton, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginEnd = dp(8)
        })
        setRow.addView(mirrorCheck, LinearLayout.LayoutParams(-2, -2))
        root.addView(setRow, matchWrap(bottom = 8))
        val nudgeHeader = text("Move point · choose a step, then tap an axis direction", 12, UiPalette.accent, bold = true)
        root.addView(nudgeHeader, matchWrap(bottom = 4))
        val stepRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stepRow.addView(text("Step", 12, UiPalette.textMuted), LinearLayout.LayoutParams(dp(42), -2))
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
            actionButton("X −") { },
            actionButton("X +") { },
            actionButton("Y −") { },
            actionButton("Y +") { },
            actionButton("Z −") { },
            actionButton("Z +") { }
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
        val hingeTitle = text("Hinge orientation · HINGE_ points only", 12, UiPalette.accent, bold = true)
        root.addView(hingeTitle, matchWrap(bottom = 3))
        val hingeInfo = text("", 11, UiPalette.textMuted)
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
            actionButton("Rotate X −") { },
            actionButton("Rotate X +") { },
            actionButton("Rotate Y −") { },
            actionButton("Rotate Y +") { },
            actionButton("Rotate Z −") { },
            actionButton("Rotate Z +") { }
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
            rowViews[index].text = point.name + (if (point.hinge) "  · hinge" else "") +
                "     " + formatCoordinate(point.x) + ", " +
                formatCoordinate(point.y) + ", " + formatCoordinate(point.z)
            rowViews[index].setTextColor(if (index == selected) UiPalette.text else UiPalette.textSecondary)
            rowViews[index].background = rounded(
                if (index == selected) UiPalette.danger else Color.TRANSPARENT,
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
        nudgeButtons[0].setOnClickListener { moveBy(0, -1) }
        nudgeButtons[1].setOnClickListener { moveBy(0, 1) }
        nudgeButtons[2].setOnClickListener { moveBy(1, -1) }
        nudgeButtons[3].setOnClickListener { moveBy(1, 1) }
        nudgeButtons[4].setOnClickListener { moveBy(2, -1) }
        nudgeButtons[5].setOnClickListener { moveBy(2, 1) }
        rotateButtons[0].setOnClickListener { rotateHinge(0, -1) }
        rotateButtons[1].setOnClickListener { rotateHinge(0, 1) }
        rotateButtons[2].setOnClickListener { rotateHinge(1, -1) }
        rotateButtons[3].setOnClickListener { rotateHinge(1, 1) }
        rotateButtons[4].setOnClickListener { rotateHinge(2, -1) }
        rotateButtons[5].setOnClickListener { rotateHinge(2, 1) }
        for (index in points.indices) {
            val row = text("", 12, UiPalette.textSecondary).apply {
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
        val dialogScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Car points editor")
            .setView(dialogScroll)
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
    private fun openCamTweaksForViewer() {
        val entry = indexedGameFiles.firstOrNull { it.name.equals("camTweaks.dat", true) || it.relativePath.substringAfterLast('/').equals("camTweaks.dat", true) }
        if (entry != null) openCamTweaksGameFile(entry) else AlertDialog.Builder(this)
            .setTitle("camTweaks.dat not indexed")
            .setMessage("The depot index does not contain camTweaks.dat. Select a copy manually?")
            .setPositiveButton("Choose file") { _, _ -> chooseCamTweaksFile() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun chooseCamTweaksFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, requestCamTweaksInput)
    }

    private fun openCamTweaksGameFile(entry: GameDataFile) {
        rr3ViewerScreenStatus.text = "Loading ${entry.relativePath}…"
        Thread {
            try {
                val bytes = readGameFile(entry)
                runOnUiThread { renderCamTweaksEditor(entry.name, bytes) }
            } catch (error: Exception) {
                runOnUiThread { rr3ViewerScreenStatus.text = error.message ?: "Could not read camTweaks.dat." }
            }
        }.start()
    }

    private fun openCamTweaksFile(uri: Uri) {
        rr3ViewerScreenStatus.text = "Reading camera tweak data…"
        Thread {
            try {
                val name = displayName(uri)
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open the selected camera tweak file.")
                val bytes = input.use { it.readBytes() }
                runOnUiThread { renderCamTweaksEditor(name, bytes) }
            } catch (error: Exception) {
                runOnUiThread { rr3ViewerScreenStatus.text = error.message ?: "Could not open camTweaks.dat." }
            }
        }.start()
    }

    private fun renderCamTweaksEditor(name: String, bytes: ByteArray) {
        try {
            val decoded = CamTweaksV5.parse(bytes)
            currentCamTweaksOriginal = bytes.copyOf()
            currentCamTweaksWorking = bytes.copyOf()
            currentCamTweaksDecoded = decoded
            currentCamTweaksName = name
            selectedCamTweaksTransformOffset = null
            camTweaksInputs.clear()
            camTweaksStatus = text("", 10, UiPalette.textMuted)
            camTweaksFieldsContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            camTweaksSaveButton = compactOverlayButton("Export mode edits") {
                saveCamTweaksFile()
            }

            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = glossyRounded(UiPalette.surface, dp(14).toFloat())
                elevation = dp(12).toFloat()
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(
                text("CAMERA DATA  ·  camTweaks.dat", 13, UiPalette.text, bold = true),
                LinearLayout.LayoutParams(0, dp(28), 1f)
            )
            header.addView(compactOverlayButton("Close") {
                camTweaksOverlay?.let { overlay ->
                    (overlay.parent as? ViewGroup)?.removeView(overlay)
                }
                camTweaksOverlay = null
                camTweaksDialog?.dismiss()
                camTweaksDialog = null
            }, LinearLayout.LayoutParams(dp(62), dp(32)))
            panel.addView(header, matchWrap(bottom = 1))

            val summary = text(
                "v${decoded.version}  ·  ${decoded.modes.size} mode definitions  ·  " +
                    "${decoded.transforms.size} car transforms  ·  " +
                    "${decoded.trailingData.size} trailing bytes retained",
                9, UiPalette.textSecondary
            )
            panel.addView(summary, matchWrap(bottom = 3))

            val modeStrip = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
            }
            val modeButtons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            modeStrip.addView(modeButtons)
            panel.addView(modeStrip, matchWrap(bottom = 3))

            val valuesScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
            }
            valuesScroll.addView(camTweaksFieldsContainer)
            panel.addView(
                valuesScroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(36)
                )
            )

            val transformStrip = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
            }
            val transformButtons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            transformStrip.addView(transformButtons)
            panel.addView(transformStrip, matchWrap(top = 3, bottom = 2))

            camTweaksTransformStatus = text(
                "Select a transform record to inspect its position and quaternion.",
                9, UiPalette.textSecondary
            ).apply {
                maxLines = 2
                isSingleLine = false
            }
            panel.addView(camTweaksTransformStatus, matchWrap(bottom = 1))

            camTweaksStatus.isSingleLine = true
            panel.addView(camTweaksStatus, matchWrap(top = 1, bottom = 2))
            panel.addView(camTweaksSaveButton, matchWrap())

            val activeCarId = activeRr3ViewerCarId
            val visibleTransforms = decoded.transforms.filter { transform ->
                transform.carId == -1 || (activeCarId != null && transform.carId == activeCarId)
            }
            var selectedTransform: CamTweaksV5.CarTransform? = null
            var selectMode: ((CamTweaksV5.ModeDefinition, CamTweaksV5.CarTransform?) -> Unit)? = null

            fun updateModeStatus(mode: CamTweaksV5.ModeDefinition) {
                val working = currentCamTweaksWorking ?: return
                val buffer = java.nio.ByteBuffer.wrap(working).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val values = FloatArray(6) { field ->
                    buffer.getFloat(mode.recordOffset + 4 + field * 4)
                }
                camTweaksStatus.text = String.format(
                    Locale.US,
                    "Mode %d · camera values: %s",
                    mode.cameraModeId,
                    values.joinToString(", ") { "%.3f".format(Locale.US, it) }
                )
            }

            fun rotateByQuaternion(
                x: Float, y: Float, z: Float,
                qx: Float, qy: Float, qz: Float, qw: Float
            ): FloatArray {
                val tx = 2f * (qy * z - qz * y)
                val ty = 2f * (qz * x - qx * z)
                val tz = 2f * (qx * y - qy * x)
                return floatArrayOf(
                    x + qw * tx + qy * tz - qz * ty,
                    y + qw * ty + qz * tx - qx * tz,
                    z + qw * tz + qx * ty - qy * tx
                )
            }

            fun toViewerAxes(x: Float, y: Float, z: Float): FloatArray =
                floatArrayOf(x, z, -y)

            fun applyModePreview(
                mode: CamTweaksV5.ModeDefinition,
                transform: CamTweaksV5.CarTransform?
            ) {
                val working = currentCamTweaksWorking ?: return
                val buffer = java.nio.ByteBuffer.wrap(working).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val values = FloatArray(6) { field ->
                    buffer.getFloat(mode.recordOffset + 4 + field * 4)
                }
                if (values.any { !it.isFinite() }) {
                    camTweaksStatus.text = "This camera mode contains a non-finite value."
                    return
                }

                // Mode points are lateral/forward/up offsets. Larger stored
                // values use the game's 1/10,000 camera-coordinate scale.
                val rawMagnitude = values.maxOf { kotlin.math.abs(it) }
                val modeScale = if (rawMagnitude > 20f) 0.0001f else 1f
                val localEye = floatArrayOf(
                    values[0] * modeScale,
                    values[1] * modeScale,
                    values[2] * modeScale
                )
                val localTarget = floatArrayOf(
                    values[3] * modeScale,
                    values[4] * modeScale,
                    values[5] * modeScale
                )

                fun buildPose(useTransform: Boolean): FloatArray? {
                    var eye = localEye
                    var target = localTarget

                    if (useTransform && transform != null) {
                        val q = transform.rotation
                        val qLength = transform.quaternionLength.toFloat()
                        if (!qLength.isFinite() || qLength < 0.00001f) return null

                        val qx = q[0] / qLength
                        val qy = q[1] / qLength
                        val qz = q[2] / qLength
                        val qw = q[3] / qLength

                        // Rotate in the source RR3 coordinate frame first.
                        // Only then convert lateral/forward/up to the viewer's
                        // X-right/Y-up/Z-forward axes.
                        eye = rotateByQuaternion(
                            localEye[0], localEye[1], localEye[2], qx, qy, qz, qw
                        )
                        target = rotateByQuaternion(
                            localTarget[0], localTarget[1], localTarget[2], qx, qy, qz, qw
                        )

                        // Transform translation uses the RR3 1/2,048 scale.
                        val p = transform.position
                        eye = toViewerAxes(
                            p[0] / 2048f + eye[0],
                            p[1] / 2048f + eye[1],
                            p[2] / 2048f + eye[2]
                        )
                        target = toViewerAxes(
                            p[0] / 2048f + target[0],
                            p[1] / 2048f + target[1],
                            p[2] / 2048f + target[2]
                        )
                    } else {
                        eye = toViewerAxes(eye[0], eye[1], eye[2])
                        target = toViewerAxes(target[0], target[1], target[2])
                    }

                    val dx = target[0] - eye[0]
                    val dy = target[1] - eye[1]
                    val dz = target[2] - eye[2]
                    val spanSquared = dx * dx + dy * dy + dz * dz
                    if (!spanSquared.isFinite() || spanSquared < 0.000001f ||
                        spanSquared > 10000f
                    ) return null

                    return floatArrayOf(
                        eye[0], eye[1], eye[2],
                        target[0], target[1], target[2]
                    )
                }

                val transformedPose = if (transform != null) buildPose(true) else null
                val pose = transformedPose ?: buildPose(false)
                if (pose == null) {
                    camTweaksStatus.text = String.format(
                        Locale.US,
                        "Mode %d has an invalid camera span after RR3 axis/unit conversion.",
                        mode.cameraModeId
                    )
                    return
                }

                val viewer = rr3ViewerSurface
                if (viewer == null) {
                    camTweaksStatus.text = "Camera pose decoded, but the 3D viewer is not active."
                    return
                }

                viewer.setCameraPose(
                    pose[0], pose[1], pose[2],
                    pose[3], pose[4], pose[5]
                )
                val transformLabel = if (transformedPose != null) {
                    "car ${transform?.carId}, transform #${transform?.recordIndex?.plus(1)}"
                } else if (transform != null) {
                    "mode-local fallback (invalid car transform)"
                } else {
                    "mode-local coordinates"
                }
                camTweaksStatus.text = String.format(
                    Locale.US,
                    "Preview applied · mode %d · %s · eye (%.4f, %.4f, %.4f) · target (%.4f, %.4f, %.4f)",
                    mode.cameraModeId,
                    transformLabel,
                    pose[0], pose[1], pose[2],
                    pose[3], pose[4], pose[5]
                )
                camTweaksTransformStatus.text = transform?.let {
                    val q = it.rotation
                    String.format(
                        Locale.US,
                        "Transform #%d · car %d · mode %d · P (%.3f, %.3f, %.3f) · Q (%.4f, %.4f, %.4f, %.4f) · applied in RR3 axes",
                        it.recordIndex + 1, it.carId, it.cameraModeId,
                        it.position[0], it.position[1], it.position[2],
                        q[0], q[1], q[2], q[3]
                    )
                } ?: "No matching car transform. Converted the mode-local camera points."
            }

            selectMode = { mode, transformOverride ->
                selectedCamTweaksRecordOffset = mode.recordOffset
                camTweaksInputs.clear()
                camTweaksFieldsContainer.removeAllViews()
                val working = currentCamTweaksWorking
                    ?: throw IllegalStateException("Camera data is no longer available.")
                val buffer = java.nio.ByteBuffer.wrap(working).order(java.nio.ByteOrder.LITTLE_ENDIAN)

                for (field in 0 until 6) {
                    val valueOffset = mode.recordOffset + 4 + field * 4
                    val input = editBox(
                        "RAW ${field + 1}",
                        buffer.getFloat(valueOffset).toString(),
                        numeric = true
                    ).apply { textSize = 12f }
                    camTweaksFieldsContainer.addView(
                        input,
                        LinearLayout.LayoutParams(dp(104), dp(32)).apply {
                            marginEnd = dp(6)
                        }
                    )
                    camTweaksInputs[valueOffset] = input
                    input.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?, start: Int, count: Int, after: Int
                        ) = Unit

                        override fun onTextChanged(
                            s: CharSequence?, start: Int, before: Int, count: Int
                        ) = Unit

                        override fun afterTextChanged(s: Editable?) {
                            val parsed = s?.toString()?.trim()?.toFloatOrNull()
                            if (parsed != null && parsed.isFinite()) {
                                currentCamTweaksWorking?.let { data ->
                                    java.nio.ByteBuffer.wrap(data)
                                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                        .putFloat(valueOffset, parsed)
                                }
                                if (selectedCamTweaksRecordOffset == mode.recordOffset) {
                                    updateModeStatus(mode)
                                    applyModePreview(mode, selectedTransform)
                                }
                            }
                        }
                    })
                }

                for (i in 0 until modeButtons.childCount) {
                    val child = modeButtons.getChildAt(i) as? TextView ?: continue
                    val selected = child.tag == mode.recordOffset
                    child.background = rounded(
                        if (selected) UiPalette.primary else UiPalette.background,
                        dp(9).toFloat()
                    )
                    child.setTextColor(if (selected) Color.WHITE else UiPalette.text)
                }

                val preferredTransform = transformOverride?.takeIf {
                    it.cameraModeId == mode.cameraModeId.toLong() &&
                        it.recordOffset in visibleTransforms.map { visible -> visible.recordOffset }
                } ?: decoded.transforms.firstOrNull {
                    activeCarId != null &&
                        it.carId == activeCarId &&
                        it.cameraModeId == mode.cameraModeId.toLong()
                } ?: decoded.transforms.firstOrNull {
                    it.carId == -1 && it.cameraModeId == mode.cameraModeId.toLong()
                }

                selectedTransform = preferredTransform
                selectedCamTweaksTransformOffset = preferredTransform?.recordOffset
                for (i in 0 until transformButtons.childCount) {
                    val child = transformButtons.getChildAt(i) as? TextView ?: continue
                    val selected = child.tag == selectedCamTweaksTransformOffset
                    child.background = rounded(
                        if (selected) UiPalette.primary else UiPalette.background,
                        dp(9).toFloat()
                    )
                    child.setTextColor(if (selected) Color.WHITE else UiPalette.text)
                }

                updateModeStatus(mode)
                applyModePreview(mode, preferredTransform)
            }

            for (transform in visibleTransforms) {
                val owner = if (transform.carId == -1) "DEFAULT" else "CAR ${transform.carId}"
                val chip = text(
                    "$owner · M${transform.cameraModeId} · #${transform.recordIndex + 1}",
                    9, UiPalette.text, bold = true
                ).apply {
                    tag = transform.recordOffset
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(5), dp(8), dp(5))
                    background = rounded(UiPalette.background, dp(9).toFloat())
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        val matchingMode = decoded.modes.firstOrNull {
                            it.cameraModeId.toLong() == transform.cameraModeId
                        }
                        if (matchingMode != null) {
                            selectMode?.invoke(matchingMode, transform)
                        } else {
                            camTweaksStatus.text =
                                "Transform #${transform.recordIndex + 1} has no matching mode definition."
                        }
                    }
                }
                transformButtons.addView(
                    chip,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(28)
                    ).apply { marginEnd = dp(5) }
                )
            }
            if (visibleTransforms.isEmpty()) {
                val empty = text(
                    if (activeCarId == null) {
                        "No global/default transforms were found."
                    } else {
                        "No transform records found for car $activeCarId or the global defaults."
                    },
                    9, UiPalette.textMuted
                )
                transformButtons.addView(empty, matchWrap())
            }

            for (mode in decoded.modes) {
                val chip = text(
                    "MODE ${mode.cameraModeId}",
                    10, UiPalette.text, bold = true
                ).apply {
                    tag = mode.recordOffset
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    background = rounded(UiPalette.background, dp(9).toFloat())
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        selectMode?.invoke(mode, null)
                    }
                }
                modeButtons.addView(
                    chip,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(30)
                    ).apply { marginEnd = dp(6) }
                )
            }

            camTweaksOverlay?.let { old ->
                (old.parent as? ViewGroup)?.removeView(old)
            }
            camTweaksOverlay = null
            camTweaksDialog?.dismiss()
            camTweaksDialog = null
            if (rr3ViewerScreen.visibility == View.VISIBLE && rr3ViewerSurface != null) {
                camTweaksOverlay = panel
                val panelWidth = minOf(
                    dp(560),
                    (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(240))
                )
                rr3ViewerScreenHost.addView(
                    panel,
                    FrameLayout.LayoutParams(panelWidth, dp(264), Gravity.BOTTOM or Gravity.START)
                        .apply {
                            leftMargin = dp(8)
                            bottomMargin = dp(8)
                        }
                )
            } else {
                camTweaksDialog = AlertDialog.Builder(this)
                    .setTitle("camTweaks.dat · v5 decoder")
                    .setView(panel)
                    .setNegativeButton("Close", null)
                    .create()
                camTweaksDialog?.show()
                camTweaksDialog?.window?.setLayout(
                    (resources.displayMetrics.widthPixels * 0.96f).toInt(),
                    (resources.displayMetrics.heightPixels * 0.82f).toInt()
                )
            }

            val firstMode = decoded.modes.firstOrNull()
            if (firstMode != null) {
                selectMode?.invoke(firstMode, null)
            }
            camTweaksStatus.contentDescription =
                "Selecting a mode or transform applies its camera pose to the live 3D viewer."
        } catch (error: Exception) {
            currentCamTweaksOriginal = null
            currentCamTweaksWorking = null
            currentCamTweaksDecoded = null
            camTweaksInputs.clear()
            if (::camTweaksFieldsContainer.isInitialized) {
                camTweaksFieldsContainer.removeAllViews()
            }
            rr3ViewerScreenStatus.text =
                error.message ?: "Could not parse camTweaks.dat."
        }
    }

    private fun saveCamTweaksFile() {
        val original = currentCamTweaksOriginal ?: run {
            camTweaksStatus.text = "Open camTweaks.dat before saving."
            return
        }
        val edited = (currentCamTweaksWorking ?: original).copyOf()
        val buffer = java.nio.ByteBuffer.wrap(edited).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for ((offset, input) in camTweaksInputs) {
            val value = input.text.toString().trim().toFloatOrNull()
            if (value == null || !value.isFinite() || kotlin.math.abs(value) > 100000000f) {
                camTweaksStatus.text = "Every parameter must be a finite number within ±100,000,000. No output was created."
                return
            }
            buffer.putFloat(offset, value)
        }
        pendingTransformedData = edited
        pendingTransformedFile?.delete()
        pendingTransformedFile = null
        pendingTransformedName = currentCamTweaksName.substringBeforeLast('.', currentCamTweaksName) + "-edited.dat"
        pendingOutputForDataEditor = false
        pendingOutputForBrowser = true
        pendingOutputForCarEditor = false
        camTweaksStatus.text = "Prepared edited global mode definitions. Car transforms and trailing bytes are unchanged."
        launchRr3OutputPicker()
    }

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
                background = rounded(UiPalette.background, dp(10).toFloat())
            }
            trackDetailsContainer.addView(row, matchWrap(bottom = 6))
            row.addView(text(label, 11, UiPalette.textSecondary, bold = true), matchWrap())
            row.addView(text(value.ifBlank { "—" }, 14, UiPalette.text), matchWrap(top = 3))
        }
    }
    private fun renderCarEditorIndexedChoices() {
        if (!::carEditorIndexedCarsContainer.isInitialized) return
        carEditorIndexedCarsContainer.removeAllViews()
        if (indexedGameFiles.isEmpty() || rr3ViewerCars.isEmpty()) {
            carEditorIndexedCarsContainer.addView(
                text("No indexed car records are available. " + rr3ViewerCatalogStatus, 12, UiPalette.textSubtle),
                matchWrap()
            )
            return
        }
        val groups = rr3ViewerCars.groupBy { it.brand.ifBlank { "Other" } }
            .toSortedMap(compareBy<String> { it.lowercase(Locale.ROOT) })
        for ((brand, cars) in groups) {
            val expanded = brand in expandedCarEditorBrands
            val header = text(
                (if (expanded) "▾  " else "▸  ") + brand + "  ·  " + cars.size + " car(s)",
                13, UiPalette.text, bold = true
            ).apply {
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = rounded(UiPalette.surface, dp(8).toFloat())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (brand in expandedCarEditorBrands) expandedCarEditorBrands.remove(brand)
                    else expandedCarEditorBrands.add(brand)
                    renderCarEditorIndexedChoices()
                }
            }
            carEditorIndexedCarsContainer.addView(header, matchWrap(bottom = 4))
            if (!expanded) continue
            for (car in cars) {
                val expectedName = car.id.toString() + "_" + car.dataFolder.substringAfterLast('/') + ".bin.nct"
                val entry = indexedGameFiles.firstOrNull {
                    it.name.equals(expectedName, ignoreCase = true) ||
                        it.relativePath.substringAfterLast('/').equals(expectedName, ignoreCase = true)
                }
                val row = text(
                    if (entry == null) car.displayName + " · data file not indexed"
                    else car.displayName + " · " + expectedName,
                    12, if (entry == null) UiPalette.textSubtle else UiPalette.text
                ).apply {
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    background = rounded(UiPalette.background, dp(8).toFloat())
                    isEnabled = entry != null
                    if (entry != null) {
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { inspectCarGameFile(entry) }
                    }
                }
                carEditorIndexedCarsContainer.addView(row, matchWrap(bottom = 3))
            }
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
                background = rounded(UiPalette.background, dp(12).toFloat())
            }
            carFieldsContainer.addView(fieldCard, matchWrap(bottom = 8))
            fieldCard.addView(text(label, 12, UiPalette.textMuted, bold = true), matchWrap())
            val isNumericField = !item.optBoolean("text") && key != "gear_ratios"
            val input = editBox("Whole number value", value, numeric = isNumericField)
            fieldCard.addView(input, matchWrap(top = 7))
            val wholeNumber = value.toLongOrNull()
            if (isNumericField && wholeNumber != null &&
                wholeNumber in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
            ) {
                val span = (if (wholeNumber < 0) -wholeNumber else wholeNumber)
                    .div(2L).plus(100L).coerceIn(100L, 1_000_000L)
                val rangeMin = (wholeNumber - span).coerceAtLeast(Int.MIN_VALUE.toLong())
                val rangeMax = (wholeNumber + span).coerceAtMost(Int.MAX_VALUE.toLong())
                if (rangeMax > rangeMin) {
                    fieldCard.addView(text("Adjust with slider", 10, UiPalette.textSubtle), matchWrap(top = 8))
                    val slider = SeekBar(this).apply {
                        max = 1000
                        progress = 500
                    }
                    var syncingSlider = false
                    slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            if (syncingSlider) return
                            val edited = rangeMin + ((rangeMax - rangeMin) * progress.toLong() / 1000L)
                            val next = edited.toString()
                            if (input.text.toString() != next) input.setText(next)
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                    })
                    input.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            val edited = s?.toString()?.toLongOrNull() ?: return
                            if (edited !in rangeMin..rangeMax) return
                            val progress = (((edited - rangeMin) * 1000L) / (rangeMax - rangeMin))
                                .toInt().coerceIn(0, 1000)
                            if (slider.progress != progress) {
                                syncingSlider = true
                                slider.progress = progress
                                syncingSlider = false
                            }
                        }
                        override fun afterTextChanged(s: Editable?) = Unit
                    })
                    fieldCard.addView(slider, matchWrap(top = 2))
                }
            }
            carFieldInputs[key] = input
        }
        currentCarOriginalFields = original
        if (carFieldInputs.isEmpty()) {
            carFieldsContainer.addView(text("No editable fields were returned.", 12, UiPalette.textSubtle), matchWrap())
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
            UiPalette.text
        )
        careerDetailsContainer.addView(summary, matchWrap(bottom = 12))
        careerDetailsContainer.addView(
            text("SERIES (internal text keys)", 11, UiPalette.accent, bold = true),
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
                    text(row, 11, UiPalette.text),
                    matchWrap(bottom = 5)
                )
            }
            if (series.length() > count) {
                careerDetailsContainer.addView(
                    text("Showing the first $count of ${series.length()} series.", 11, UiPalette.textSubtle),
                    matchWrap(top = 4)
                )
            }
        }
        careerDetailsContainer.addView(
            text("TIER STRUCTURE", 11, UiPalette.accent, bold = true),
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
                        UiPalette.text
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
                    throw IllegalArgumentException("Use the RR3 File Conversion card in Data Editor for this .gui file.")
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
            11, UiPalette.textSecondary
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
    override fun onResume() {
        super.onResume()
        rr3ViewerSurface?.resumeRendering()
        val backend = preferences.getString("game_data_backend", "root")
        if (backend == "shizuku" &&
            shizukuStorageService == null &&
            !shizukuBindPending &&
            !shizukuAutoReconnectAttempted &&
            Shizuku.pingBinder()
        ) {
            shizukuAutoReconnectAttempted = true
            ensureShizukuStorageService {
                setGameDataStatus(
                    "Shizuku reconnected. The saved depot index is ready; RR3 files can be opened again."
                )
            }
        } else if (backend == "shizuku" &&
            shizukuStorageService == null &&
            !Shizuku.pingBinder()
        ) {
            setGameDataStatus(
                "The saved index is available. Start Shizuku and return here to reconnect for file access."
            )
        }
    }
    override fun onPause() {
        rr3ViewerSurface?.pauseRendering()
        super.onPause()
    }
    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        if (shizukuBound) runCatching {
            Shizuku.unbindUserService(shizukuServiceArgs, shizukuServiceConnection, true)
        }
        shizukuStorageService = null
        shizukuBound = false
        rr3ViewerSurface?.dispose()
        rr3ViewerSurface = null
        super.onDestroy()
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::rr3ViewerScreen.isInitialized && rr3ViewerScreen.visibility == View.VISIBLE) {
            closeRr3FullScreenViewer()
        } else if (::editorScreen.isInitialized && editorScreen.visibility == View.VISIBLE) {
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
            UiPalette.textMuted
        )
        val pageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiPalette.background)
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
        val loadStatus = text("Scroll to load more of the file…", 11, UiPalette.accent)
        fun addPage(page: CareerTextPage) {
            val rowEditor = EditText(this).apply {
                setText(page.text)
                setTextColor(UiPalette.text)
                setHintTextColor(UiPalette.textHint)
                textSize = 11.5f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setHorizontallyScrolling(false)
                isVerticalScrollBarEnabled = false
                setPadding(dp(10), dp(2), dp(10), dp(2))
                background = rounded(UiPalette.background, 0f)
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
            UiPalette.textMuted
        )
        val pageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiPalette.background)
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
        val loadStatus = text("Scroll to load more of the file…", 11, UiPalette.accent)
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
                setTextColor(UiPalette.text)
                setHintTextColor(UiPalette.textHint)
                textSize = 11.5f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setHorizontallyScrolling(false)
                isVerticalScrollBarEnabled = false
                setPadding(dp(10), dp(2), dp(10), dp(2))
                background = rounded(UiPalette.background, 0f)
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
            UiPalette.textMuted
        )
        val layoutManager = LinearLayoutManager(this)
        val editorList = RecyclerView(this).apply {
            setBackgroundColor(UiPalette.background)
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
                    setTextColor(UiPalette.text)
                    setHintTextColor(UiPalette.textHint)
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    gravity = Gravity.TOP or Gravity.START
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    setHorizontallyScrolling(false)
                    isVerticalScrollBarEnabled = false
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    background = rounded(UiPalette.background, 0f)
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
            UiPalette.textSecondary
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
            setTextColor(UiPalette.text)
            setHintTextColor(UiPalette.textHint)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(false)
            isVerticalScrollBarEnabled = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(UiPalette.background, dp(8).toFloat())
            minLines = 18
            maxLines = 100_000
        }
        val status = text(
            "Edit the decoded representation. The original remains unchanged until you choose a separate output.",
            12,
            UiPalette.textMuted
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
                text("Source: " + name, 11, UiPalette.accent, bold = true),
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
                    // paged editor as files opened from the Index browser.
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
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            putExtra(Intent.EXTRA_TITLE, pendingTransformedName)
        }
        try {
            startActivityForResult(outputIntent, requestRr3TransformOutput)
        } catch (error: Exception) {
            setPendingOutputStatus(
                "Android could not open a document save screen: " +
                    (error.message ?: error.javaClass.simpleName) +
                    ". The rebuilt file is still held in the app; enable a Files/Documents provider and retry."
            )
        }
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
            text(title, 11, UiPalette.accent, bold = true),
            matchWrap(top = 18, bottom = 8)
        )
    }
    private fun card(parent: LinearLayout): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(UiPalette.surface, dp(16).toFloat())
        }
        parent.addView(box, matchWrap())
        return box
    }
    private fun actionButton(label: String, action: () -> Unit): TextView {
        return text(label, 14, UiPalette.text, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = interactiveBackground(UiPalette.primary, dp(12).toFloat())
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
    }

    private fun compactOverlayButton(label: String, action: () -> Unit): TextView {
        return text(label, 11, UiPalette.text, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            minHeight = dp(30)
            background = interactiveBackground(UiPalette.burgundy, dp(10).toFloat())
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
            setTextColor(UiPalette.text)
            setHintTextColor(UiPalette.textHint)
            hint = hintText
            inputType = if (numeric) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(UiPalette.background, dp(10).toFloat())
            setText(value)
        }
    }
    private fun interactiveBackground(color: Int, radius: Float): android.graphics.drawable.Drawable {
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(UiPalette.ripple),
            glossyRounded(color, radius),
            rounded(UiPalette.rippleMask, radius)
        )
    }
    private fun rounded(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }
    private fun glossyRounded(color: Int, radius: Float): GradientDrawable {
        fun mix(from: Int, to: Int, amount: Float): Int {
            fun channel(a: Int, b: Int) =
                (android.graphics.Color.red(a) * (1f - amount) + android.graphics.Color.red(b) * amount).toInt().coerceIn(0, 255)
            fun green(a: Int, b: Int) =
                (android.graphics.Color.green(a) * (1f - amount) + android.graphics.Color.green(b) * amount).toInt().coerceIn(0, 255)
            fun blue(a: Int, b: Int) =
                (android.graphics.Color.blue(a) * (1f - amount) + android.graphics.Color.blue(b) * amount).toInt().coerceIn(0, 255)
            return Color.rgb(channel(from, to), green(from, to), blue(from, to))
        }
        // Layered specular bands create a brushed / polished metal effect,
        // rather than the flat two-tone gloss used by the previous theme.
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                mix(color, Color.WHITE, 0.58f),
                mix(color, Color.WHITE, 0.30f),
                mix(color, Color.WHITE, 0.06f),
                mix(color, Color.BLACK, 0.34f),
                mix(color, Color.WHITE, 0.38f),
                mix(color, Color.BLACK, 0.16f),
                mix(color, UiPalette.navy, 0.52f)
            )
        ).apply {
            cornerRadius = radius
            setStroke(dp(1), 0x66FFFFFF)
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
        if (requestCode == requestRr3TransformOutput) {
            if (resultCode == RESULT_OK && data?.data != null) {
                writeTransformedRr3File(data.data!!)
            } else {
                setPendingOutputStatus(
                    "Save cancelled. Your rebuilt file is still ready in the app; run Export again to choose a destination."
                )
            }
            return
        }
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data ?: return
        if (requestCode == requestRr3TransformInput) {
            transformRr3File(uri)
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
        if (requestCode == requestCamTweaksInput) {
            openCamTweaksFile(uri)
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
                text("Search by field name to inspect or edit individual save values.", 12, UiPalette.textSubtle),
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
                background = rounded(UiPalette.background, dp(12).toFloat())
            }
            advancedFieldsContainer.addView(fieldCard, matchWrap(bottom = 8))
            fieldCard.addView(text(name, 13, UiPalette.text, bold = true), matchWrap())
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
                    text(value, 12, UiPalette.textMuted),
                    matchWrap(top = 5)
                )
            }
            shown++
            if (shown >= 25) break
        }
        if (shown == 0) {
            advancedFieldsContainer.addView(
                text("No matching fields.", 12, UiPalette.textSubtle),
                matchWrap()
            )
        } else if (shown == 25) {
            advancedFieldsContainer.addView(
                text("Showing the first 25 matches. Refine the search to narrow the list.", 11, UiPalette.textSubtle),
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
                background = rounded(UiPalette.background, dp(12).toFloat())
            }
            saveFieldsContainer.addView(fieldCard, matchWrap(bottom = 9))
            fieldCard.addView(
                text(label, 16, UiPalette.text, bold = true),
                matchWrap()
            )
            fieldCard.addView(
                text(
                    if (calibrated) "Calibration saved on this device" else "Not calibrated yet",
                    12,
                    if (calibrated) UiPalette.primary else UiPalette.accent
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
