package com.print.twinprint

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.text.InputType
import android.util.LruCache
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.rendering.ImageType
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    data class PageItem(val uri: Uri, val originalPageIndex: Int, var isSelected: Boolean = true, var password: String? = null, var isEncrypted: Boolean = false, var isFromImage: Boolean = false)

    // Views
    private lateinit var viewHome: View
    private lateinit var viewViewer: View
    private lateinit var viewManageMerge: View
    private lateinit var viewPrintOptions: View
    private lateinit var viewCreateOptions: View
    private lateinit var viewDuplex: View
    private lateinit var viewAd: View
    
    private lateinit var llRecentItems: LinearLayout
    private lateinit var rvPdfPages: RecyclerView
    private lateinit var rvEditPages: RecyclerView
    private lateinit var rvDuplexPreview: RecyclerView
    
    private lateinit var tvPageIndicator: TextView
    private lateinit var tvToolbarTitle: TextView
    private lateinit var tvWelcome: TextView
    private lateinit var tvAdTimer: TextView
    private lateinit var tvRecentLabel: TextView
    private lateinit var tvPrintingStatus: TextView
    
    private lateinit var layoutTimer: View
    private lateinit var pbTimer: ProgressBar
    private lateinit var btnStep1Even: Button
    private lateinit var btnStep2Odd: Button
    private lateinit var btnMainSave: Button
    private lateinit var btnEditFileName: ImageButton

    private var currentPdfUri: Uri? = null
    private var mergedPages = mutableListOf<PageItem>()
    private var totalPages: Int = 0
    private lateinit var sharedPrefs: SharedPreferences
    
    private var exportFileName: String = ""
    
    private val STORAGE_PERMISSION_CODE = 101
    private val PICK_PDF_CODE = 102
    private val PICK_IMAGES_CODE = 103
    private val CAPTURE_IMAGE_CODE = 104
    private val CAMERA_PERMISSION_CODE = 105
    private val CREATE_PDF_CODE = 106

    private var cameraImageUri: Uri? = null

    // Batch Print State
    private var isBatchSeparate = false
    private var batchUris = mutableListOf<Uri>()
    private var batchIsDoubleSided = false
    private var currentBatchIndex = 0

    // Zoom and Grid state
    private var viewerSpanCount = 1
    private var editSpanCount = 1
    private var viewerScaleDetector: ScaleGestureDetector? = null
    private var editScaleDetector: ScaleGestureDetector? = null

    // Native PDF Renderer cache
    private var nativeRenderer: PdfRenderer? = null
    private var nativePfd: ParcelFileDescriptor? = null
    private var currentRendererUri: Uri? = null

    // Rendering optimization
    private val bitmapCache = LruCache<String, Bitmap>(40)
    private val renderExecutor = Executors.newFixedThreadPool(4)

    private var lastScaleTime = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Initialize PDFBox
        PDFBoxResourceLoader.init(applicationContext)

        sharedPrefs = getSharedPreferences("TwinPrintPrefs", Context.MODE_PRIVATE)

        // Init UI Components
        viewHome = findViewById(R.id.viewHome)
        viewViewer = findViewById(R.id.viewViewer)
        viewManageMerge = findViewById(R.id.viewManageMerge)
        viewPrintOptions = findViewById(R.id.viewPrintOptions)
        viewCreateOptions = findViewById(R.id.viewCreateOptions)
        viewDuplex = findViewById(R.id.viewDuplex)
        viewAd = findViewById(R.id.viewAd)
        
        llRecentItems = findViewById(R.id.llRecentItems)
        rvPdfPages = findViewById(R.id.rvPdfPages)
        rvEditPages = findViewById(R.id.rvEditPages)
        rvDuplexPreview = findViewById(R.id.rvDuplexPreview)
        
        tvPageIndicator = findViewById(R.id.tvPageIndicator)
        tvToolbarTitle = findViewById(R.id.tvToolbarTitle)
        tvWelcome = findViewById(R.id.tvWelcome)
        tvAdTimer = findViewById(R.id.tvAdTimer)
        tvRecentLabel = findViewById(R.id.tvRecentLabel)
        tvPrintingStatus = findViewById(R.id.tvPrintingStatus)
        
        layoutTimer = findViewById(R.id.layoutTimer)
        pbTimer = findViewById(R.id.pbTimer)
        btnStep1Even = findViewById(R.id.btnStep1Even)
        btnStep2Odd = findViewById(R.id.btnStep2Odd)
        btnMainSave = findViewById(R.id.btnMainSave)
        btnEditFileName = findViewById(R.id.btnEditFileName)

        rvPdfPages.layoutManager = GridLayoutManager(this, 1)
        rvEditPages.layoutManager = GridLayoutManager(this, 1)
        rvDuplexPreview.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        // Setup Drag and Drop
        setupDragAndDrop(rvPdfPages)
        setupDragAndDrop(rvEditPages)

        setupZoomSystem()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewAd.visibility == View.VISIBLE) return
                if (viewPrintOptions.visibility == View.VISIBLE) {
                    viewPrintOptions.visibility = View.GONE
                } else if (viewCreateOptions.visibility == View.VISIBLE) {
                    viewCreateOptions.visibility = View.GONE
                } else if (viewViewer.visibility == View.VISIBLE || viewManageMerge.visibility == View.VISIBLE || viewDuplex.visibility == View.VISIBLE) {
                    goHome()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        findViewById<Button>(R.id.btnOpenPdf).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/pdf"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(intent, PICK_PDF_CODE)
        }

        findViewById<Button>(R.id.btnCreatePdf).setOnClickListener {
            viewCreateOptions.visibility = View.VISIBLE
        }

        findViewById<Button>(R.id.btnOptGallery).setOnClickListener {
            viewCreateOptions.visibility = View.GONE
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(intent, PICK_IMAGES_CODE)
        }

        findViewById<Button>(R.id.btnOptCamera).setOnClickListener {
            viewCreateOptions.visibility = View.GONE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
            } else {
                openCamera()
            }
        }

        findViewById<TextView>(R.id.tvCancelCreate).setOnClickListener {
            viewCreateOptions.visibility = View.GONE
        }

        btnMainSave.setOnClickListener {
            startSavePdfProcess()
        }
        
        btnEditFileName.setOnClickListener {
            showEditFileNameDialog()
        }

        findViewById<Button>(R.id.btnMainPrint).setOnClickListener { viewPrintOptions.visibility = View.VISIBLE }
        findViewById<TextView>(R.id.tvCancelOptions).setOnClickListener { viewPrintOptions.visibility = View.GONE }

        findViewById<Button>(R.id.btnOptSingle).setOnClickListener {
            viewPrintOptions.visibility = View.GONE
            if (isBatchSeparate) {
                batchIsDoubleSided = false
                processNextInBatch()
            } else if (totalPages > 0) {
                val pageList = (1..totalPages).toList()
                doPrintMerged(getFileName(currentPdfUri) ?: "Document", pageList) { success ->
                    if (success) {
                        showAdForPages(pageList.size) {
                            goHome()
                        }
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnOptTwoSided).setOnClickListener {
            viewPrintOptions.visibility = View.GONE
            if (isBatchSeparate) {
                batchIsDoubleSided = true
                processNextInBatch()
            } else {
                setupDuplexUI()
            }
        }

        btnStep1Even.setOnClickListener {
            if (totalPages > 0) {
                btnStep1Even.isEnabled = false
                val evens = mutableListOf<Int>()
                for (i in 2..totalPages step 2) evens.add(i)
                if (totalPages % 2 != 0) evens.add(0)
                doPrintMerged("Even Pages - ${getFileName(currentPdfUri) ?: "Document"}", evens) { success ->
                    if (success) showAdForPages(evens.size) { startFlipTimer() }
                    else btnStep1Even.isEnabled = true
                }
            }
        }

        btnStep2Odd.setOnClickListener {
            if (totalPages > 0) {
                btnStep2Odd.isEnabled = false
                val odds = (1..totalPages step 2).toList()
                doPrintMerged("Odd Pages - ${getFileName(currentPdfUri) ?: "Document"}", odds) { success ->
                    if (success) {
                        showAdForPages(odds.size) {
                            if (isBatchSeparate) {
                                currentBatchIndex++
                                processNextInBatch()
                            } else {
                                Toast.makeText(this, "Printing complete", Toast.LENGTH_SHORT).show()
                                goHome()
                            }
                        }
                    } else btnStep2Odd.isEnabled = true
                }
            }
        }

        findViewById<Button>(R.id.btnBackFromDuplex).setOnClickListener {
            viewDuplex.visibility = View.GONE
            viewViewer.visibility = View.VISIBLE
        }

        findViewById<Button>(R.id.btnDiscardMerge).setOnClickListener { goHome() }
        findViewById<Button>(R.id.btnConfirmMerge).setOnClickListener {
            finalizeMergeAndPromptSave()
        }

        rvPdfPages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val pos = (recyclerView.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                if (pos != RecyclerView.NO_POSITION) {
                    tvPageIndicator.text = "${pos + 1} / $totalPages"
                }
            }
        })

        tvPageIndicator.visibility = View.GONE
        handleIntent(intent)
        updateRecentFilesList()
        checkPermissions()
    }

    private fun setupZoomSystem() {
        viewerScaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scale = detector.scaleFactor
                val currentTime = System.currentTimeMillis()
                if (scale > 1.05f) {
                    if (viewerSpanCount > 1 && currentTime - lastScaleTime > 150) {
                        viewerSpanCount--
                        updateSpanCount(rvPdfPages, viewerSpanCount)
                        lastScaleTime = currentTime
                        return true
                    } else if (viewerSpanCount == 1) {
                        rvPdfPages.scaleX = (rvPdfPages.scaleX * scale).coerceIn(1.0f, 4.0f)
                        rvPdfPages.scaleY = (rvPdfPages.scaleY * scale).coerceIn(1.0f, 4.0f)
                        return true
                    }
                } else if (scale < 0.95f) {
                    if (rvPdfPages.scaleX > 1.0f) {
                        rvPdfPages.scaleX = (rvPdfPages.scaleX * scale).coerceIn(1.0f, 4.0f)
                        rvPdfPages.scaleY = (rvPdfPages.scaleY * scale).coerceIn(1.0f, 4.0f)
                        return true
                    } else if (viewerSpanCount < 5 && currentTime - lastScaleTime > 150) {
                        viewerSpanCount++
                        updateSpanCount(rvPdfPages, viewerSpanCount)
                        lastScaleTime = currentTime
                        return true
                    }
                }
                return false
            }
        })

        editScaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scale = detector.scaleFactor
                val currentTime = System.currentTimeMillis()
                if (scale > 1.05f) {
                    if (editSpanCount > 1 && currentTime - lastScaleTime > 150) {
                        editSpanCount--
                        updateSpanCount(rvEditPages, editSpanCount)
                        lastScaleTime = currentTime
                        return true
                    } else if (editSpanCount == 1) {
                        rvEditPages.scaleX = (rvEditPages.scaleX * scale).coerceIn(1.0f, 4.0f)
                        rvEditPages.scaleY = (rvEditPages.scaleY * scale).coerceIn(1.0f, 4.0f)
                        return true
                    }
                } else if (scale < 0.95f) {
                    if (rvEditPages.scaleX > 1.0f) {
                        rvEditPages.scaleX = (rvEditPages.scaleX * scale).coerceIn(1.0f, 4.0f)
                        rvEditPages.scaleY = (rvEditPages.scaleY * scale).coerceIn(1.0f, 4.0f)
                        return true
                    } else if (editSpanCount < 5 && currentTime - lastScaleTime > 150) {
                        editSpanCount++
                        updateSpanCount(rvEditPages, editSpanCount)
                        lastScaleTime = currentTime
                        return true
                    }
                }
                return false
            }
        })

        rvPdfPages.setOnTouchListener { v, event ->
            viewerScaleDetector?.onTouchEvent(event)
            if (event.pointerCount > 1) true else v.onTouchEvent(event)
        }

        rvEditPages.setOnTouchListener { v, event ->
            editScaleDetector?.onTouchEvent(event)
            if (event.pointerCount > 1) true else v.onTouchEvent(event)
        }
    }

    private fun generateDefaultFileName(): String {
        val timeStamp = SimpleDateFormat("dd MM yy HH:mm:ss:SSS", Locale.getDefault()).format(Date())
        return "TwinPrint $timeStamp.pdf"
    }

    private fun showEditFileNameDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setText(exportFileName.removeSuffix(".pdf"))
        input.setSelection(input.text.length)
        
        AlertDialog.Builder(this)
            .setTitle("Edit PDF Name")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    exportFileName = if (newName.endsWith(".pdf")) newName else "$newName.pdf"
                    tvToolbarTitle.text = exportFileName
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startSavePdfProcess() {
        if (exportFileName.isEmpty()) {
            exportFileName = generateDefaultFileName()
        }
        
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, exportFileName)
        }
        startActivityForResult(intent, CREATE_PDF_CODE)
    }

    private fun setupDragAndDrop(rv: RecyclerView) {
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val fromPos = viewHolder.adapterPosition
                val toPos = target.adapterPosition
                Collections.swap(mergedPages, fromPos, toPos)
                recyclerView.adapter?.notifyItemMoved(fromPos, toPos)
                return true
            }
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.apply { alpha = 0.7f; scaleX = 1.05f; scaleY = 1.05f }
                }
            }
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.apply { alpha = 1.0f; scaleX = 1.0f; scaleY = 1.0f }
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                mergedPages.removeAt(position)
                rv.adapter?.notifyItemRemoved(position)
                totalPages = mergedPages.size
                tvPageIndicator.text = "Pages: $totalPages"
            }
        })
        itemTouchHelper.attachToRecyclerView(rv)
    }

    private fun updateSpanCount(recyclerView: RecyclerView, count: Int) {
        val layoutManager = recyclerView.layoutManager as? GridLayoutManager
        if (layoutManager != null && layoutManager.spanCount != count) {
            layoutManager.spanCount = count
            recyclerView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
            recyclerView.requestLayout()
        }
    }

    private fun openCamera() {
        try {
            val photoFile = File.createTempFile("IMG_", ".jpg", getExternalFilesDir(Environment.DIRECTORY_PICTURES))
            cameraImageUri = FileProvider.getUriForFile(this, "${packageName}.provider", photoFile)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply { putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri) }
            startActivityForResult(intent, CAPTURE_IMAGE_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type ?: ""
        if (Intent.ACTION_VIEW == action) {
            intent.data?.let { processIncomingUri(it) }
        } else if (Intent.ACTION_SEND == action && type.startsWith("application/pdf")) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            uri?.let { processIncomingUri(it) }
        }
    }

    private fun processIncomingUri(uri: Uri) {
        try {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
            val cachedUri = copyToCache(uri)
            if (cachedUri != null) openPdf(cachedUri) else openPdf(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to process incoming PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToCache(uri: Uri): Uri? {
        return try {
            val originalName = getFileName(uri) ?: "document.pdf"
            val uniqueName = "${uri.toString().hashCode()}_$originalName"
            val cacheFile = File(cacheDir, uniqueName)
            if (cacheFile.exists()) return Uri.fromFile(cacheFile)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
            }
            Uri.fromFile(cacheFile)
        } catch (e: Exception) { null }
    }

    private fun goHome() {
        viewHome.visibility = View.VISIBLE
        viewViewer.visibility = View.GONE
        viewManageMerge.visibility = View.GONE
        viewPrintOptions.visibility = View.GONE
        viewCreateOptions.visibility = View.GONE
        viewDuplex.visibility = View.GONE
        viewAd.visibility = View.GONE
        tvToolbarTitle.text = "TwinPrint PDF Studio"
        btnEditFileName.visibility = View.GONE
        btnMainSave.visibility = View.VISIBLE
        updateRecentFilesList()
        viewerSpanCount = 1
        editSpanCount = 1
        updateSpanCount(rvPdfPages, 1)
        updateSpanCount(rvEditPages, 1)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        closeNativeRenderer()
    }

    private fun setupDuplexUI() {
        viewViewer.visibility = View.GONE
        viewDuplex.visibility = View.VISIBLE
        val duplexInfo = findViewById<TextView>(R.id.tvDuplexInfo)
        rvDuplexPreview.adapter = DuplexPreviewAdapter(mergedPages)
        duplexInfo.text = "Double Sided Printing"
        btnStep1Even.isEnabled = true
        btnStep2Odd.isEnabled = false
    }

    private fun showAdForPages(numPages: Int, onFinish: () -> Unit) {
        val durationSeconds = 30L + numPages * 10L
        showAd(durationSeconds * 1000L, isPrinting = true, onFinish)
    }

    private fun showAd(ms: Long, isPrinting: Boolean = false, onFinish: () -> Unit) {
        viewAd.visibility = View.VISIBLE
        tvPrintingStatus.visibility = if (isPrinting) View.VISIBLE else View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        object : CountDownTimer(ms, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                tvAdTimer.text = "Closing in ${millisUntilFinished / 1000}s..."
            }
            override fun onFinish() {
                viewAd.visibility = View.GONE
                if (layoutTimer.visibility != View.VISIBLE) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onFinish()
            }
        }.start()
    }

    private fun startFlipTimer() {
        layoutTimer.visibility = View.VISIBLE
        pbTimer.progress = 10
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        object : CountDownTimer(10000, 1000) {
            override fun onTick(millisUntilFinished: Long) { pbTimer.progress = (millisUntilFinished / 1000).toInt() }
            override fun onFinish() {
                layoutTimer.visibility = View.GONE
                btnStep2Odd.isEnabled = true
                if (viewAd.visibility != View.VISIBLE) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                PICK_PDF_CODE -> {
                    if (data != null) {
                        val clipData = data.clipData
                        if (clipData != null && clipData.itemCount > 1) {
                            batchUris.clear()
                            for (i in 0 until clipData.itemCount) {
                                val uri = clipData.getItemAt(i).uri
                                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                batchUris.add(uri)
                            }
                            showBatchOptionsDialog()
                        } else {
                            val uri = data.data ?: return
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            openPdf(uri)
                        }
                    }
                }
                PICK_IMAGES_CODE -> {
                    if (data != null) {
                        val uris = mutableListOf<Uri>()
                        val clipData = data.clipData
                        if (clipData != null) {
                            for (i in 0 until clipData.itemCount) {
                                val uri = clipData.getItemAt(i).uri
                                uris.add(uri)
                            }
                        } else {
                            data.data?.let { uri ->
                                uris.add(uri)
                            }
                        }
                        if (uris.isNotEmpty()) startImageManageProcess(uris)
                    }
                }
                CAPTURE_IMAGE_CODE -> { cameraImageUri?.let { startImageManageProcess(listOf(it)) } }
                CREATE_PDF_CODE -> { data?.data?.let { savePdf(it) } }
            }
        }
    }

    private fun startImageManageProcess(uris: List<Uri>) {
        mergedPages.clear()
        uris.forEach { mergedPages.add(PageItem(it, 0, true, isFromImage = true)) }
        
        exportFileName = generateDefaultFileName()
        tvToolbarTitle.text = exportFileName
        btnEditFileName.visibility = View.VISIBLE
        
        viewHome.visibility = View.GONE
        viewManageMerge.visibility = View.VISIBLE
        editSpanCount = 1
        updateSpanCount(rvEditPages, 1)
        rvEditPages.adapter = PageEditAdapter(mergedPages)
    }

    private fun finalizeMergeAndPromptSave() {
        val selected = mergedPages.filter { it.isSelected }
        if (selected.isEmpty()) {
            Toast.makeText(this, "No pages selected", Toast.LENGTH_SHORT).show()
            return
        }
        mergedPages.clear()
        mergedPages.addAll(selected)
        totalPages = mergedPages.size
        // Prompt for save immediately as requested
        startSavePdfProcess()
    }

    private fun showBatchOptionsDialog() {
        val options = arrayOf("Merge into one document", "Print separately (Batch)")
        AlertDialog.Builder(this)
            .setTitle("Multiple Files Selected")
            .setItems(options) { _, which ->
                if (which == 0) {
                    isBatchSeparate = false
                    startMergeProcess(batchUris)
                } else {
                    isBatchSeparate = true
                    currentBatchIndex = 0
                    showBatchPrintModeDialog()
                }
            }.show()
    }

    private fun showBatchPrintModeDialog() {
        val options = arrayOf("Single Sided Print", "Two Sided Print")
        AlertDialog.Builder(this)
            .setTitle("Batch Print Mode")
            .setItems(options) { _, which ->
                batchIsDoubleSided = (which == 1)
                processNextInBatch()
            }
            .setCancelable(false)
            .show()
    }

    private fun processNextInBatch() {
        if (currentBatchIndex < batchUris.size) {
            val uri = batchUris[currentBatchIndex]
            openPdf(uri) {
                if (batchIsDoubleSided) setupDuplexUI()
                else {
                    val pageList = (1..totalPages).toList()
                    doPrintMerged(getFileName(uri) ?: "Document", pageList) { success ->
                        if (success) showAdForPages(pageList.size) { currentBatchIndex++; processNextInBatch() }
                    }
                }
            }
        } else {
            Toast.makeText(this, "Batch printing completed", Toast.LENGTH_SHORT).show()
            goHome()
        }
    }

    private fun openPdf(uri: Uri, onOpened: (() -> Unit)? = null) {
        currentPdfUri = uri
        addToRecentFiles(uri)
        bitmapCache.evictAll()
        
        closeNativeRenderer()
        
        try {
            nativePfd = contentResolver.openFileDescriptor(uri, "r")
            if (nativePfd != null) {
                nativeRenderer = PdfRenderer(nativePfd!!)
                totalPages = nativeRenderer!!.pageCount
                currentRendererUri = uri
                
                mergedPages.clear()
                for (i in 0 until totalPages) mergedPages.add(PageItem(uri, i, true))
                displayPdf()
                onOpened?.invoke()
            }
        } catch (e: Exception) {
            // If native renderer fails (e.g., password protected), fallback to PDFBox
            openPdfWithPdfBox(uri, onOpened)
        }
    }

    private fun openPdfWithPdfBox(uri: Uri, onOpened: (() -> Unit)? = null) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val bytes = inputStream.readBytes()
            inputStream.close()
            PDDocument.load(bytes).use { doc ->
                totalPages = doc.numberOfPages
                mergedPages.clear()
                for (i in 0 until totalPages) mergedPages.add(PageItem(uri, i, true))
                displayPdf()
                onOpened?.invoke()
            }
        } catch (e: InvalidPasswordException) {
            showPasswordDialog(uri, onOpened)
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun closeNativeRenderer() {
        try {
            nativeRenderer?.close()
            nativePfd?.close()
        } catch (e: Exception) {}
        nativeRenderer = null
        nativePfd = null
        currentRendererUri = null
    }

    private fun showPasswordDialog(uri: Uri, onOpened: (() -> Unit)? = null) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        AlertDialog.Builder(this)
            .setTitle("Password Protected")
            .setMessage("Enter password for this PDF:")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val password = input.text.toString()
                try {
                    val inputStream = contentResolver.openInputStream(uri) ?: return@setPositiveButton
                    val bytes = inputStream.readBytes()
                    inputStream.close()
                    PDDocument.load(bytes, password).use { doc ->
                        totalPages = doc.numberOfPages
                        mergedPages.clear()
                        for (i in 0 until totalPages) mergedPages.add(PageItem(uri, i, true, password, true))
                        displayPdf()
                        onOpened?.invoke()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Incorrect password", Toast.LENGTH_SHORT).show()
                    showPasswordDialog(uri, onOpened)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startMergeProcess(uris: List<Uri>) {
        mergedPages.clear()
        uris.forEach { uri ->
            try {
                val inputStream = contentResolver.openInputStream(uri) ?: return@forEach
                val bytes = inputStream.readBytes()
                inputStream.close()
                PDDocument.load(bytes).use { doc ->
                    for (i in 0 until doc.numberOfPages) mergedPages.add(PageItem(uri, i))
                }
            } catch (e: Exception) {}
        }
        
        exportFileName = generateDefaultFileName()
        tvToolbarTitle.text = exportFileName
        btnEditFileName.visibility = View.VISIBLE

        viewHome.visibility = View.GONE
        viewManageMerge.visibility = View.VISIBLE
        editSpanCount = 1
        updateSpanCount(rvEditPages, 1)
        rvEditPages.adapter = PageEditAdapter(mergedPages)
    }

    private fun displayPdf() {
        viewHome.visibility = View.GONE
        viewViewer.visibility = View.VISIBLE
        tvToolbarTitle.text = getFileName(currentPdfUri) ?: "PDF Viewer"
        tvPageIndicator.text = "1 / $totalPages"
        btnEditFileName.visibility = View.GONE
        btnMainSave.visibility = View.GONE
        viewerSpanCount = 1
        updateSpanCount(rvPdfPages, 1)
        rvPdfPages.adapter = PdfPageAdapter(mergedPages)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun savePdf(destUri: Uri) {
        renderExecutor.execute {
            val openedDocs = mutableMapOf<Uri, PDDocument>()
            val outDoc = PDDocument()
            try {
                mergedPages.forEach { item ->
                    if (item.isFromImage) {
                        val page = PDPage(PDRectangle.A4)
                        outDoc.addPage(page)
                        contentResolver.openInputStream(item.uri)?.use { input ->
                            val bitmap = BitmapFactory.decodeStream(input)
                            val pdImage = LosslessFactory.createFromImage(outDoc, bitmap)
                            PDPageContentStream(outDoc, page).use { content ->
                                val pageWidth = page.mediaBox.width
                                val pageHeight = page.mediaBox.height
                                val imgWidth = pdImage.width.toFloat()
                                val imgHeight = pdImage.height.toFloat()
                                val scale = (pageWidth / imgWidth).coerceAtMost(pageHeight / imgHeight)
                                val x = (pageWidth - imgWidth * scale) / 2
                                val y = (pageHeight - imgHeight * scale) / 2
                                content.drawImage(pdImage, x, y, imgWidth * scale, imgHeight * scale)
                            }
                        }
                    } else {
                        val sourceDoc = openedDocs.getOrPut(item.uri) {
                            val inputStream = contentResolver.openInputStream(item.uri) ?: throw Exception("Fail to open")
                            val bytes = inputStream.readBytes()
                            inputStream.close()
                            if (item.isEncrypted) PDDocument.load(bytes, item.password) else PDDocument.load(bytes)
                        }
                        outDoc.importPage(sourceDoc.getPage(item.originalPageIndex))
                    }
                }
                contentResolver.openOutputStream(destUri)?.use { output -> outDoc.save(output) }
                runOnUiThread {
                    Toast.makeText(this, "PDF Saved successfully", Toast.LENGTH_LONG).show()
                    addToRecentFiles(destUri)
                    // After saving, show it in the viewer
                    viewManageMerge.visibility = View.GONE
                    btnMainSave.visibility = View.GONE
                    openPdf(destUri)
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Save Failed: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally {
                outDoc.close()
                openedDocs.values.forEach { try { it.close() } catch (e: Exception) {} }
            }
        }
    }

    private fun doPrintMerged(jobName: String, pageNumbers: List<Int>, onComplete: ((Boolean) -> Unit)? = null) {
        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val pagesToPrint = ArrayList(mergedPages)
        var printJob: android.print.PrintJob? = null
        val adapter = object : PrintDocumentAdapter() {
            var wroteContent = false
            override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: android.os.CancellationSignal?, callback: LayoutResultCallback?, extras: Bundle?) {
                if (cancellationSignal?.isCanceled == true) { callback?.onLayoutCancelled(); return }
                val info = android.print.PrintDocumentInfo.Builder(jobName).setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(pageNumbers.size).build()
                callback?.onLayoutFinished(info, true)
            }
            override fun onWrite(pages: Array<out android.print.PageRange>?, destination: ParcelFileDescriptor?, cancellationSignal: android.os.CancellationSignal?, callback: WriteResultCallback?) {
                val openedDocs = mutableMapOf<Uri, PDDocument>()
                val outDoc = PDDocument()
                try {
                    for (pNum in pageNumbers) {
                        if (cancellationSignal?.isCanceled == true) { callback?.onWriteCancelled(); return }
                        if (pNum == 0) { outDoc.addPage(PDPage()); continue }
                        val item = pagesToPrint[pNum - 1]
                        if (item.isFromImage) {
                            val page = PDPage(PDRectangle.A4)
                            outDoc.addPage(page)
                            contentResolver.openInputStream(item.uri)?.use { input ->
                                val bitmap = BitmapFactory.decodeStream(input)
                                val pdImage = LosslessFactory.createFromImage(outDoc, bitmap)
                                PDPageContentStream(outDoc, page).use { content ->
                                    val pageWidth = page.mediaBox.width
                                    val pageHeight = page.mediaBox.height
                                    val imgWidth = pdImage.width.toFloat()
                                    val imgHeight = pdImage.height.toFloat()
                                    val scale = (pageWidth / imgWidth).coerceAtMost(pageHeight / imgHeight)
                                    val x = (pageWidth - imgWidth * scale) / 2
                                    val y = (pageHeight - imgHeight * scale) / 2
                                    content.drawImage(pdImage, x, y, imgWidth * scale, imgHeight * scale)
                                }
                            }
                        } else {
                            val sourceDoc = openedDocs.getOrPut(item.uri) {
                                val inputStream = contentResolver.openInputStream(item.uri) ?: throw Exception("Fail")
                                val bytes = inputStream.readBytes()
                                inputStream.close()
                                if (item.isEncrypted) PDDocument.load(bytes, item.password) else PDDocument.load(bytes)
                            }
                            outDoc.importPage(sourceDoc.getPage(item.originalPageIndex))
                        }
                    }
                    FileOutputStream(destination?.fileDescriptor).use { outDoc.save(it) }
                    wroteContent = true
                    callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                } catch (e: Exception) { callback?.onWriteFailed(e.message) }
                finally { outDoc.close(); openedDocs.values.forEach { try { it.close() } catch (e: Exception) {} } }
            }
            override fun onFinish() {
                super.onFinish()
                val job = printJob
                window.decorView.postDelayed({
                    val state = job?.info?.state ?: 0
                    val success = wroteContent && (state == android.print.PrintJobInfo.STATE_QUEUED || state == android.print.PrintJobInfo.STATE_STARTED || state == android.print.PrintJobInfo.STATE_COMPLETED || state == android.print.PrintJobInfo.STATE_BLOCKED)
                    runOnUiThread { onComplete?.invoke(success) }
                }, 1000)
            }
        }
        printJob = printManager.print(jobName, adapter, null)
    }

    private fun addToRecentFiles(uri: Uri) {
        val recents = sharedPrefs.getStringSet("recent_files", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        recents.add(uri.toString())
        sharedPrefs.edit().putStringSet("recent_files", recents).apply()
    }

    private fun removeRecentFile(uriStr: String) {
        val recents = sharedPrefs.getStringSet("recent_files", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        recents.remove(uriStr)
        sharedPrefs.edit().putStringSet("recent_files", recents).apply()
        updateRecentFilesList()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun updateRecentFilesList() {
        llRecentItems.removeAllViews()
        val recents = sharedPrefs.getStringSet("recent_files", emptySet()) ?: emptySet()
        if (recents.isEmpty()) { tvWelcome.visibility = View.VISIBLE; tvRecentLabel.visibility = View.GONE }
        else { tvWelcome.visibility = View.GONE; tvRecentLabel.visibility = View.VISIBLE }
        recents.toList().reversed().take(5).forEach { uriStr ->
            val uri = Uri.parse(uriStr)
            val view = LayoutInflater.from(this).inflate(R.layout.item_recent_pdf, llRecentItems, false)
            val foreground = view.findViewById<View>(R.id.layoutRecentForeground)
            val background = view.findViewById<View>(R.id.layoutDeleteBackground)
            view.findViewById<TextView>(R.id.tvRecentName).text = getFileName(uri)
            var startX = 0f
            var currentDx = 0f
            foreground.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { startX = event.rawX; currentDx = 0f; background.visibility = View.VISIBLE; true }
                    MotionEvent.ACTION_MOVE -> { val dx = event.rawX - startX; if (dx < 0) { currentDx = dx; v.translationX = dx }; true }
                    MotionEvent.ACTION_UP -> {
                        if (currentDx < -v.width / 3) { v.animate().translationX(-v.width.toFloat()).alpha(0f).setDuration(200).withEndAction { removeRecentFile(uriStr) }.start() }
                        else { v.animate().translationX(0f).setDuration(200).withEndAction { background.visibility = View.INVISIBLE }.start()
                            if (abs(currentDx) < 10) foreground.performClick() }
                        true
                    }
                    else -> false
                }
            }
            foreground.setOnClickListener { openPdf(uri) }
            llRecentItems.addView(view)
        }
    }

    @SuppressLint("Range")
    private fun getFileName(uri: Uri?): String? {
        if (uri == null) return null
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) result = cursor.getString(index)
                    }
                }
            } catch (e: Exception) {}
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result ?: "document.pdf"
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), STORAGE_PERMISSION_CODE)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) openCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeNativeRenderer()
    }

    inner class PdfPageAdapter(private val pages: List<PageItem>) : RecyclerView.Adapter<PdfPageAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView = v.findViewById(R.id.ivPage)
            val tv: TextView = v.findViewById(R.id.tvPageNumber)
            val card: MaterialCardView = v as MaterialCardView
            var currentPos: Int = -1
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_page, parent, false))
        
        @SuppressLint("NotifyDataSetChanged")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = pages[position]
            holder.tv.text = "Page ${position + 1} of ${itemCount}"
            holder.currentPos = position
            val cacheKey = "${item.uri}_${item.originalPageIndex}_${viewerSpanCount}"
            val cachedBitmap = bitmapCache.get(cacheKey)
            if (cachedBitmap != null) holder.iv.setImageBitmap(cachedBitmap)
            else {
                holder.iv.setImageBitmap(null)
                renderExecutor.execute {
                    val bitmap = renderPage(item)
                    if (bitmap != null) {
                        bitmapCache.put(cacheKey, bitmap)
                        if (holder.currentPos == position) runOnUiThread { holder.iv.setImageBitmap(bitmap) }
                    }
                }
            }

            holder.card.setOnClickListener {
                if (viewerSpanCount > 1) {
                    viewerSpanCount = 1
                    updateSpanCount(rvPdfPages, 1)
                    rvPdfPages.post { 
                        notifyDataSetChanged()
                        rvPdfPages.scrollToPosition(position)
                    }
                }
            }
        }
        private fun renderPage(item: PageItem): Bitmap? {
            return try {
                if (item.isFromImage) {
                    val inputStream = contentResolver.openInputStream(item.uri) ?: return null
                    val bytes = inputStream.readBytes()
                    inputStream.close()
                    val options = BitmapFactory.Options().apply { inSampleSize = if (viewerSpanCount > 2) 2 else 1 }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                } else if (nativeRenderer != null && item.uri == currentRendererUri && !item.isEncrypted) {
                    // Use native renderer for accurate display
                    synchronized(nativeRenderer!!) {
                        val page = nativeRenderer!!.openPage(item.originalPageIndex)
                        val scale = if (viewerSpanCount == 1) 2.0f else 1.2f
                        val width = (page.width * scale).toInt()
                        val height = (page.height * scale).toInt()
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        bitmap
                    }
                } else {
                    // Fallback to PDFBox for encrypted or if native fails
                    val inputStream = contentResolver.openInputStream(item.uri) ?: return null
                    val bytes = inputStream.readBytes()
                    inputStream.close()
                    val doc = if (item.isEncrypted) PDDocument.load(bytes, item.password) else PDDocument.load(bytes)
                    val renderer = com.tom_roush.pdfbox.rendering.PDFRenderer(doc)
                    val scale = if (viewerSpanCount == 1) 2.0f else 1.5f
                    // Composite on white background manually to fix black patches in PDFBox
                    val bim = renderer.renderImage(item.originalPageIndex, scale, ImageType.ARGB)
                    val whiteBitmap = Bitmap.createBitmap(bim.width, bim.height, Bitmap.Config.RGB_565)
                    val canvas = Canvas(whiteBitmap)
                    canvas.drawColor(Color.WHITE)
                    canvas.drawBitmap(bim, 0f, 0f, null)
                    bim.recycle()
                    doc.close()
                    whiteBitmap
                }
            } catch (e: Exception) { null }
        }
        override fun getItemCount() = pages.size
    }

    inner class PageEditAdapter(private val items: List<PageItem>) : RecyclerView.Adapter<PageEditAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView = v.findViewById(R.id.ivEditThumb)
            val cb: CheckBox = v.findViewById(R.id.cbPageSelect)
            val card: MaterialCardView = v as MaterialCardView
            val tvInfo: TextView = v.findViewById(R.id.tvPageNumber) ?: v.findViewById(R.id.tvPageInfo) // Handle possible different resource IDs
            var currentPos: Int = -1
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_edit_page, parent, false))
        
        @SuppressLint("NotifyDataSetChanged")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.currentPos = position
            holder.cb.isChecked = item.isSelected
            holder.tvInfo.text = "Page ${position + 1} of ${itemCount}"
            
            renderExecutor.execute {
                val bitmap = try {
                    val inputStream = contentResolver.openInputStream(item.uri) ?: return@execute
                    val bytes = inputStream.readBytes()
                    inputStream.close()
                    if (item.isEncrypted || item.isFromImage.not()) {
                        val doc = if (item.isEncrypted) PDDocument.load(bytes, item.password) else PDDocument.load(bytes)
                        val renderer = com.tom_roush.pdfbox.rendering.PDFRenderer(doc)
                        val scale = if (editSpanCount == 1) 1.5f else 1.0f
                        val bim = renderer.renderImage(item.originalPageIndex, scale, ImageType.ARGB)
                        val whiteBitmap = Bitmap.createBitmap(bim.width, bim.height, Bitmap.Config.RGB_565)
                        val canvas = Canvas(whiteBitmap)
                        canvas.drawColor(Color.WHITE)
                        canvas.drawBitmap(bim, 0f, 0f, null)
                        bim.recycle()
                        doc.close()
                        whiteBitmap
                    } else {
                        val sampleSize = if (editSpanCount == 1) 1 else 2
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
                    }
                } catch (e: Exception) { null }
                if (bitmap != null && holder.currentPos == position) {
                    runOnUiThread { holder.iv.setImageBitmap(bitmap) }
                }
            }
            
            holder.cb.setOnClickListener {
                item.isSelected = holder.cb.isChecked
            }

            holder.card.setOnClickListener {
                if (editSpanCount > 1) {
                    // When multiple columns, clicking the image switches back to 1 column
                    editSpanCount = 1
                    updateSpanCount(rvEditPages, 1)
                    rvEditPages.post { 
                        notifyDataSetChanged()
                        rvEditPages.scrollToPosition(position)
                    }
                } else {
                    // When 1 column, clicking the image toggles the checkbox
                    item.isSelected = !item.isSelected
                    holder.cb.isChecked = item.isSelected
                }
            }
        }
        override fun getItemCount() = items.size
    }

    inner class DuplexPreviewAdapter(private val items: List<PageItem>) : RecyclerView.Adapter<DuplexPreviewAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView = v.findViewById(R.id.ivDuplexThumb)
            val tvInfo: TextView = v.findViewById(R.id.tvDuplexPageInfo)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_duplex_preview, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvInfo.text = "Page ${position + 1}"
            renderExecutor.execute {
                val bitmap = try {
                    val inputStream = contentResolver.openInputStream(item.uri) ?: return@execute
                    val bytes = inputStream.readBytes()
                    inputStream.close()
                    if (item.isEncrypted || item.isFromImage.not()) {
                        val doc = if (item.isEncrypted) PDDocument.load(bytes, item.password) else PDDocument.load(bytes)
                        val renderer = com.tom_roush.pdfbox.rendering.PDFRenderer(doc)
                        val bim = renderer.renderImage(item.originalPageIndex, 1.0f, ImageType.ARGB)
                        val whiteBitmap = Bitmap.createBitmap(bim.width, bim.height, Bitmap.Config.RGB_565)
                        val canvas = Canvas(whiteBitmap)
                        canvas.drawColor(Color.WHITE)
                        canvas.drawBitmap(bim, 0f, 0f, null)
                        bim.recycle()
                        doc.close()
                        whiteBitmap
                    } else {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 1 })
                    }
                } catch (e: Exception) { null }
                if (bitmap != null) runOnUiThread { holder.iv.setImageBitmap(bitmap) }
            }
        }
        override fun getItemCount() = items.size
    }
}
