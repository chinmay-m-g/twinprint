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
    
    private lateinit var btnBack: ImageButton

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
    private val bitmapCache = LruCache<String, Bitmap>(60)
    private val renderExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())

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
        
        btnBack = findViewById(R.id.btnBack)

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

        btnBack.setOnClickListener { goHome() }

        loadRecentFiles()
    }

    private fun setupZoomSystem() {
        viewerScaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (System.currentTimeMillis() - lastScaleTime < 500) return
                if (detector.scaleFactor > 1.25f && viewerSpanCount > 1) {
                    updateViewerGrid(viewerSpanCount - 1)
                    lastScaleTime = System.currentTimeMillis()
                } else if (detector.scaleFactor < 0.75f && viewerSpanCount < 3) {
                    updateViewerGrid(viewerSpanCount + 1)
                    lastScaleTime = System.currentTimeMillis()
                }
            }
        })

        editScaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (System.currentTimeMillis() - lastScaleTime < 500) return
                if (detector.scaleFactor > 1.25f && editSpanCount > 1) {
                    updateEditGrid(editSpanCount - 1)
                    lastScaleTime = System.currentTimeMillis()
                } else if (detector.scaleFactor < 0.75f && editSpanCount < 4) {
                    updateEditGrid(editSpanCount + 1)
                    lastScaleTime = System.currentTimeMillis()
                }
            }
        })

        rvPdfPages.setOnTouchListener { _, event ->
            viewerScaleDetector?.onTouchEvent(event)
            false
        }
        rvEditPages.setOnTouchListener { _, event ->
            editScaleDetector?.onTouchEvent(event)
            false
        }
    }

    private fun updateViewerGrid(spans: Int) {
        viewerSpanCount = spans
        (rvPdfPages.layoutManager as GridLayoutManager).spanCount = spans
        rvPdfPages.adapter?.notifyDataSetChanged()
    }

    private fun updateEditGrid(spans: Int) {
        editSpanCount = spans
        (rvEditPages.layoutManager as GridLayoutManager).spanCount = spans
        rvEditPages.adapter?.notifyDataSetChanged()
    }

    private fun loadRecentFiles() {
        llRecentItems.removeAllViews()
        val recents = sharedPrefs.getStringSet("recent_uris", emptySet())?.toList() ?: emptyList()
        tvRecentLabel.visibility = if (recents.isEmpty()) View.GONE else View.VISIBLE
        
        recents.reversed().take(5).forEach { uriString ->
            val uri = Uri.parse(uriString)
            val name = getFileName(uri) ?: "Unknown"
            val view = LayoutInflater.from(this).inflate(R.layout.item_recent_pdf, llRecentItems, false)
            view.findViewById<TextView>(R.id.tvRecentName).text = name
            view.setOnClickListener { 
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {}
                loadPdf(uri) 
            }
            llRecentItems.addView(view)
        }
    }

    private fun saveToRecent(uri: Uri) {
        val recents = sharedPrefs.getStringSet("recent_uris", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        recents.add(uri.toString())
        sharedPrefs.edit().putStringSet("recent_uris", recents).apply()
        loadRecentFiles()
    }

    private fun openCamera() {
        val photoFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "scan_${System.currentTimeMillis()}.jpg")
        cameraImageUri = FileProvider.getUriForFile(this, "$packageName.provider", photoFile)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
        }
        startActivityForResult(intent, CAPTURE_IMAGE_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                PICK_PDF_CODE -> {
                    data?.let { intent ->
                        val uris = mutableListOf<Uri>()
                        if (intent.clipData != null) {
                            for (i in 0 until intent.clipData!!.itemCount) {
                                val u = intent.clipData!!.getItemAt(i).uri
                                try {
                                    contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                } catch (e: SecurityException) {}
                                uris.add(u)
                            }
                        } else intent.data?.let { 
                            try {
                                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            } catch (e: SecurityException) {}
                            uris.add(it) 
                        }

                        if (uris.size > 1) {
                            isBatchSeparate = false
                            showMergeOptionsDialog(uris)
                        } else if (uris.size == 1) {
                            isBatchSeparate = false
                            loadPdf(uris[0])
                        }
                    }
                }
                PICK_IMAGES_CODE -> {
                    data?.let { intent ->
                        val uris = mutableListOf<Uri>()
                        if (intent.clipData != null) {
                            for (i in 0 until intent.clipData!!.itemCount) uris.add(intent.clipData!!.getItemAt(i).uri)
                        } else intent.data?.let { uris.add(it) }
                        if (uris.isNotEmpty()) createPdfFromImages(uris)
                    }
                }
                CAPTURE_IMAGE_CODE -> {
                    cameraImageUri?.let { createPdfFromImages(listOf(it)) }
                }
                CREATE_PDF_CODE -> {
                    data?.data?.let { uri ->
                        performSavePdf(uri)
                    }
                }
            }
        }
    }

    private fun showMergeOptionsDialog(uris: List<Uri>) {
        AlertDialog.Builder(this)
            .setTitle("Multiple Files Selected")
            .setMessage("How would you like to process these files?")
            .setPositiveButton("Merge into One") { _, _ ->
                mergePdfs(uris)
            }
            .setNeutralButton("Print Individually") { _, _ ->
                isBatchSeparate = true
                batchUris.clear()
                batchUris.addAll(uris)
                currentBatchIndex = 0
                processNextInBatch()
            }
            .show()
    }

    private fun processNextInBatch() {
        if (currentBatchIndex < batchUris.size) {
            val uri = batchUris[currentBatchIndex]
            loadPdf(uri, hideUI = true)
            
            if (batchIsDoubleSided) {
                setupDuplexUI()
            } else {
                val pageList = (1..totalPages).toList()
                doPrintMerged(getFileName(uri) ?: "Document", pageList) { success ->
                    if (success) {
                        showAdForPages(pageList.size) {
                            currentBatchIndex++
                            processNextInBatch()
                        }
                    } else {
                        Toast.makeText(this, "Batch printing interrupted", Toast.LENGTH_SHORT).show()
                        goHome()
                    }
                }
            }
        } else {
            Toast.makeText(this, "Batch printing complete", Toast.LENGTH_SHORT).show()
            goHome()
        }
    }

    private fun mergePdfs(uris: List<Uri>) {
        val allPages = mutableListOf<PageItem>()
        uris.forEach { uri ->
            try {
                val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return@forEach
                val renderer = PdfRenderer(pfd)
                for (i in 0 until renderer.pageCount) {
                    allPages.add(PageItem(uri, i))
                }
                renderer.close()
                pfd.close()
            } catch (e: Exception) {
                // Check if encrypted
                if (isPdfEncrypted(uri)) {
                    allPages.add(PageItem(uri, 0, isEncrypted = true))
                }
            }
        }
        mergedPages.clear()
        mergedPages.addAll(allPages)
        exportFileName = "Merged_Document_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}"
        showManageMergeUI()
    }

    private fun createPdfFromImages(uris: List<Uri>) {
        val pages = uris.map { PageItem(it, 0, isFromImage = true) }
        mergedPages.clear()
        mergedPages.addAll(pages)
        exportFileName = "Image_Export_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}"
        showManageMergeUI()
    }

    private fun isPdfEncrypted(uri: Uri): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.use { PDDocument.load(it).close() }
            false
        } catch (e: InvalidPasswordException) { true } catch (e: Exception) { false }
    }

    private fun loadPdf(uri: Uri, hideUI: Boolean = false) {
        currentPdfUri = uri
        saveToRecent(uri)
        
        try {
            closeNativeRenderer()
            nativePfd = contentResolver.openFileDescriptor(uri, "r")
            nativeRenderer = PdfRenderer(nativePfd!!)
            totalPages = nativeRenderer!!.pageCount
            currentRendererUri = uri
            
            if (!hideUI) {
                viewHome.visibility = View.GONE
                viewViewer.visibility = View.VISIBLE
                btnBack.visibility = View.VISIBLE
                
                tvToolbarTitle.text = getFileName(uri)
                tvPageIndicator.text = "1 / $totalPages"
                
                val adapter = PdfPageAdapter(uri, totalPages)
                rvPdfPages.adapter = adapter
                rvPdfPages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        val pos = (recyclerView.layoutManager as GridLayoutManager).findFirstVisibleItemPosition()
                        tvPageIndicator.text = "${pos + 1} / $totalPages"
                    }
                })
            }
        } catch (e: SecurityException) {
            // If encrypted, fallback to full PageItem management
            if (isPdfEncrypted(uri)) {
                promptPassword(uri)
            } else {
                Toast.makeText(this, "Cannot open file: Encrypted or inaccessible", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading PDF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun promptPassword(uri: Uri) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        AlertDialog.Builder(this)
            .setTitle("Encrypted PDF")
            .setMessage("Enter password for ${getFileName(uri)}")
            .setView(input)
            .setPositiveButton("Open") { _, _ ->
                val pass = input.text.toString()
                try {
                    contentResolver.openInputStream(uri)?.use { 
                        val doc = PDDocument.load(it, pass)
                        totalPages = doc.numberOfPages
                        doc.close()
                        
                        // Encrypted files must use the slow path (PageEditAdapter logic)
                        val pages = mutableListOf<PageItem>()
                        for (i in 0 until totalPages) pages.add(PageItem(uri, i, password = pass, isEncrypted = true))
                        mergedPages.clear()
                        mergedPages.addAll(pages)
                        exportFileName = getFileName(uri)?.removeSuffix(".pdf") ?: "Document"
                        showManageMergeUI()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Incorrect password", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManageMergeUI() {
        viewHome.visibility = View.GONE
        viewViewer.visibility = View.GONE
        viewManageMerge.visibility = View.VISIBLE
        btnBack.visibility = View.VISIBLE
        
        tvToolbarTitle.text = exportFileName
        rvEditPages.adapter = PageEditAdapter(mergedPages)
    }

    private fun startSavePdfProcess() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, "$exportFileName.pdf")
        }
        startActivityForResult(intent, CREATE_PDF_CODE)
    }

    private fun performSavePdf(outputUri: Uri) {
        val progressDialog = AlertDialog.Builder(this).setMessage("Saving PDF...").setCancelable(false).show()
        
        renderExecutor.execute {
            try {
                val outDoc = PDDocument()
                val selectedItems = mergedPages.filter { it.isSelected }
                
                selectedItems.forEach { item ->
                    val inputStream = contentResolver.openInputStream(item.uri) ?: return@forEach
                    val bytes = inputStream.readBytes()
                    inputStream.close()
                    
                    if (item.isFromImage) {
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        val page = PDPage(PDRectangle(bitmap.width.toFloat(), bitmap.height.toFloat()))
                        outDoc.addPage(page)
                        val pdImage = LosslessFactory.createFromImage(outDoc, bitmap)
                        PDPageContentStream(outDoc, page).use { it.drawImage(pdImage, 0f, 0f) }
                        bitmap.recycle()
                    } else {
                        val sourceDoc = if (item.isEncrypted) PDDocument.load(bytes, item.password) else PDDocument.load(bytes)
                        val sourcePage = sourceDoc.getPage(item.originalPageIndex)
                        
                        // Create a new page with same dimensions
                        val newPage = PDPage(sourcePage.mediaBox)
                        newPage.rotation = sourcePage.rotation
                        outDoc.addPage(newPage)
                        
                        // Import content (Simplified: In a real app we'd use LayerUtility or page cloning, 
                        // but PDFBox-Android page cloning is heavy. Using image fallback for reliability)
                        val renderer = com.tom_roush.pdfbox.rendering.PDFRenderer(sourceDoc)
                        // Use ARGB and draw on white to fix transparency issues (black patches)
                        val renderedBim = renderer.renderImage(item.originalPageIndex, 2.0f, ImageType.ARGB)
                        val bim = Bitmap.createBitmap(renderedBim.width, renderedBim.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bim)
                        canvas.drawColor(Color.WHITE)
                        canvas.drawBitmap(renderedBim, 0f, 0f, null)
                        renderedBim.recycle()

                        val pdImage = LosslessFactory.createFromImage(outDoc, bim)
                        PDPageContentStream(outDoc, newPage).use { 
                            it.drawImage(pdImage, 0f, 0f, newPage.mediaBox.width, newPage.mediaBox.height) 
                        }
                        bim.recycle()
                        sourceDoc.close()
                    }
                }
                
                contentResolver.openOutputStream(outputUri)?.use { outDoc.save(it) }
                outDoc.close()
                
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this, "PDF Saved successfully", Toast.LENGTH_LONG).show()
                    loadPdf(outputUri)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this, "Error saving: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showEditFileNameDialog() {
        val input = EditText(this)
        input.setText(exportFileName)
        AlertDialog.Builder(this)
            .setTitle("Rename Document")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                exportFileName = input.text.toString()
                tvToolbarTitle.text = exportFileName
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupDuplexUI() {
        viewViewer.visibility = View.GONE
        viewManageMerge.visibility = View.GONE
        viewDuplex.visibility = View.VISIBLE
        btnBack.visibility = View.VISIBLE
        
        btnStep1Even.isEnabled = true
        btnStep2Odd.isEnabled = false
        layoutTimer.visibility = View.GONE
        
        val previewItems = mutableListOf<PageItem>()
        if (mergedPages.isNotEmpty()) {
            previewItems.addAll(mergedPages.filter { it.isSelected })
        } else if (currentPdfUri != null) {
            for (i in 0 until totalPages) previewItems.add(PageItem(currentPdfUri!!, i))
        }
        
        rvDuplexPreview.adapter = DuplexPreviewAdapter(previewItems)
    }

    private fun startFlipTimer() {
        layoutTimer.visibility = View.VISIBLE
        pbTimer.progress = 100
        tvPrintingStatus.text = "Step 1 Complete. Flip papers and put them back in the tray."
        
        object : CountDownTimer(10000, 100) {
            override fun onTick(millis: Long) {
                pbTimer.progress = (millis / 100).toInt()
                tvAdTimer.text = "Proceed in ${millis / 1000 + 1}s"
            }
            override fun onFinish() {
                layoutTimer.visibility = View.GONE
                btnStep2Odd.isEnabled = true
                tvPrintingStatus.text = "Ready for Step 2 (Odd Pages)"
            }
        }.start()
    }

    private fun doPrintMerged(jobName: String, pageIndices: List<Int>, onComplete: (Boolean) -> Unit) {
        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        
        // This is a simplified version. For a real production app, one would implement a custom 
        // PrintDocumentAdapter that renders only the specific pages requested.
        // For this demo, we generate a temporary PDF with just those pages and print it.
        
        renderExecutor.execute {
            try {
                val tempFile = File(cacheDir, "temp_print.pdf")
                val outDoc = PDDocument()
                
                pageIndices.forEach { idx ->
                    if (idx == 0) { // Special case for padding page
                        outDoc.addPage(PDPage(PDRectangle.A4))
                        return@forEach
                    }
                    
                    val actualIdx = idx - 1
                    val item = if (mergedPages.isNotEmpty()) {
                        mergedPages.filter { it.isSelected }.getOrNull(actualIdx)
                    } else {
                        PageItem(currentPdfUri!!, actualIdx)
                    }
                    
                    item?.let {
                        val inputStream = contentResolver.openInputStream(it.uri) ?: return@let
                        val bytes = inputStream.readBytes()
                        inputStream.close()
                        
                        val sourceDoc = if (it.isEncrypted) PDDocument.load(bytes, it.password) else PDDocument.load(bytes)
                        val renderer = com.tom_roush.pdfbox.rendering.PDFRenderer(sourceDoc)
                        // Use ARGB and draw on white to fix transparency issues (black patches)
                        val renderedBim = renderer.renderImage(it.originalPageIndex, 2.0f, ImageType.ARGB)
                        val bim = Bitmap.createBitmap(renderedBim.width, renderedBim.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bim)
                        canvas.drawColor(Color.WHITE)
                        canvas.drawBitmap(renderedBim, 0f, 0f, null)
                        renderedBim.recycle()

                        val page = PDPage(PDRectangle(bim.width.toFloat(), bim.height.toFloat()))
                        outDoc.addPage(page)
                        val pdImage = LosslessFactory.createFromImage(outDoc, bim)
                        PDPageContentStream(outDoc, page).use { cs ->
                            cs.drawImage(pdImage, 0f, 0f)
                        }
                        bim.recycle()
                        sourceDoc.close()
                    }
                }
                
                FileOutputStream(tempFile).use { outDoc.save(it) }
                outDoc.close()
                
                runOnUiThread {
                    val pda = object : PrintDocumentAdapter() {
                        override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: android.os.CancellationSignal?, callback: LayoutResultCallback?, extras: Bundle?) {
                            callback?.onLayoutFinished(android.print.PrintDocumentInfo.Builder(jobName).setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
                        }
                        override fun onWrite(pages: Array<out android.print.PageRange>?, destination: ParcelFileDescriptor?, cancellationSignal: android.os.CancellationSignal?, callback: WriteResultCallback?) {
                            try {
                                val input = tempFile.inputStream()
                                val output = FileOutputStream(destination?.fileDescriptor)
                                input.copyTo(output)
                                callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                                onComplete(true)
                            } catch (e: Exception) { 
                                callback?.onWriteFailed(e.message)
                                onComplete(false)
                            }
                        }
                    }
                    printManager.print(jobName, pda, null)
                }
            } catch (e: Exception) {
                runOnUiThread { 
                    Toast.makeText(this, "Print Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    onComplete(false)
                }
            }
        }
    }

    private fun showAdForPages(count: Int, onClosed: () -> Unit) {
        if (count < 3) {
            onClosed()
            return
        }
        viewAd.visibility = View.VISIBLE
        var seconds = 5
        tvAdTimer.text = "Closing in $seconds..."
        
        val timer = object : CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                seconds = (millisUntilFinished / 1000).toInt() + 1
                tvAdTimer.text = "Closing in $seconds..."
            }
            override fun onFinish() {
                viewAd.visibility = View.GONE
                onClosed()
            }
        }
        timer.start()
    }

    private fun goHome() {
        viewViewer.visibility = View.GONE
        viewManageMerge.visibility = View.GONE
        viewDuplex.visibility = View.GONE
        viewHome.visibility = View.VISIBLE
        btnBack.visibility = View.GONE
        tvToolbarTitle.text = getString(R.string.app_name)
        closeNativeRenderer()
    }

    private fun closeNativeRenderer() {
        nativeRenderer?.close()
        nativePfd?.close()
        nativeRenderer = null
        nativePfd = null
        currentRendererUri = null
    }

    private fun getFileName(uri: Uri?): String? {
        if (uri == null) return null
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result
    }

    private fun setupDragAndDrop(rv: RecyclerView) {
        val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (recyclerView.adapter is PageEditAdapter) {
                    Collections.swap(mergedPages, from, to)
                    recyclerView.adapter?.notifyItemMoved(from, to)
                    return true
                }
                return false
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        helper.attachToRecyclerView(rv)
    }

    // Adapters
    inner class PdfPageAdapter(private val uri: Uri, private val count: Int) : RecyclerView.Adapter<PdfPageAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView = v.findViewById(R.id.ivPage)
            val tv: TextView = v.findViewById(R.id.tvPageNumber)
            var currentPos: Int = -1
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_page, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.currentPos = position
            holder.tv.text = "Page ${position + 1}"
            holder.iv.setImageBitmap(null)
            
            val cacheKey = "view_${uri}_${position}_${viewerSpanCount}"
            bitmapCache.get(cacheKey)?.let {
                holder.iv.setImageBitmap(it)
                return
            }

            renderExecutor.execute {
                val bitmap = try {
                    if (nativeRenderer != null && currentRendererUri == uri) {
                        val page = nativeRenderer!!.openPage(position)
                        val scale = if (viewerSpanCount == 1) 2.0f else 1.5f
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        bmp
                    } else {
                        // Fallback to PDFBox for encrypted or if native fails
                        val inputStream = contentResolver.openInputStream(uri) ?: return@execute
                        val bytes = inputStream.readBytes()
                        inputStream.close()
                        val doc = PDDocument.load(bytes)
                        val renderer = com.tom_roush.pdfbox.rendering.PDFRenderer(doc)
                        val scale = if (viewerSpanCount == 1) 2.0f else 1.5f
                        // Use ARGB and draw on white to fix transparency issues (black patches)
                        val renderedBim = renderer.renderImage(position, scale, ImageType.ARGB)
                        val whiteBitmap = Bitmap.createBitmap(renderedBim.width, renderedBim.height, Bitmap.Config.RGB_565)
                        val canvas = Canvas(whiteBitmap)
                        canvas.drawColor(Color.WHITE)
                        canvas.drawBitmap(renderedBim, 0f, 0f, null)
                        renderedBim.recycle()
                        doc.close()
                        whiteBitmap
                    }
                } catch (e: Exception) { null }

                if (bitmap != null && holder.currentPos == position) {
                    bitmapCache.put(cacheKey, bitmap)
                    runOnUiThread { holder.iv.setImageBitmap(bitmap) }
                }
            }
        }
        override fun getItemCount() = count
    }

    inner class PageEditAdapter(private val items: List<PageItem>) : RecyclerView.Adapter<PageEditAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView = v.findViewById(R.id.ivEditThumb)
            val cb: CheckBox = v.findViewById(R.id.cbPageSelect)
            val tvInfo: TextView = v.findViewById(R.id.tvPageNumber)
            var currentPos: Int = -1
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_edit_page, parent, false))
        
        @SuppressLint("NotifyDataSetChanged")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.currentPos = position
            holder.cb.isChecked = item.isSelected
            holder.tvInfo.text = "Page ${position + 1} of ${itemCount}"
            holder.iv.setImageBitmap(null)
            
            val cacheKey = "edit_${item.uri}_${item.originalPageIndex}_${editSpanCount}"
            bitmapCache.get(cacheKey)?.let {
                holder.iv.setImageBitmap(it)
                return
            }
            
            renderExecutor.execute {
                val bitmap = try {
                    if (item.isFromImage) {
                        val inputStream = contentResolver.openInputStream(item.uri) ?: return@execute
                        val bytes = inputStream.readBytes()
                        inputStream.close()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } else {
                        // Check if we can use native renderer
                        if (nativeRenderer != null && currentRendererUri == item.uri && !item.isEncrypted) {
                            val page = nativeRenderer!!.openPage(item.originalPageIndex)
                            val scale = if (editSpanCount == 1) 1.5f else 1.0f
                            val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            page.close()
                            bmp
                        } else {
                            val inputStream = contentResolver.openInputStream(item.uri) ?: return@execute
                            val bytes = inputStream.readBytes()
                            inputStream.close()
                            val doc = if (item.isEncrypted) PDDocument.load(bytes, item.password) else PDDocument.load(bytes)
                            val renderer = com.tom_roush.pdfbox.rendering.PDFRenderer(doc)
                            val scale = if (editSpanCount == 1) 1.5f else 1.0f
                            // Use ARGB and draw on white to fix transparency issues (black patches)
                            val renderedBim = renderer.renderImage(item.originalPageIndex, scale, ImageType.ARGB)
                            val whiteBitmap = Bitmap.createBitmap(renderedBim.width, renderedBim.height, Bitmap.Config.RGB_565)
                            val canvas = Canvas(whiteBitmap)
                            canvas.drawColor(Color.WHITE)
                            canvas.drawBitmap(renderedBim, 0f, 0f, null)
                            renderedBim.recycle()
                            doc.close()
                            whiteBitmap
                        }
                    }
                } catch (e: Exception) { null }

                if (bitmap != null && holder.currentPos == position) {
                    bitmapCache.put(cacheKey, bitmap)
                    runOnUiThread { holder.iv.setImageBitmap(bitmap) }
                }
            }

            holder.cb.setOnCheckedChangeListener { _, isChecked -> item.isSelected = isChecked }
            holder.itemView.setOnClickListener {
                item.isSelected = !item.isSelected
                holder.cb.isChecked = item.isSelected
            }
        }
        override fun getItemCount() = items.size
    }

    inner class DuplexPreviewAdapter(private val pages: List<PageItem>) : RecyclerView.Adapter<DuplexPreviewAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView = v.findViewById(R.id.ivPage)
            val tv: TextView = v.findViewById(R.id.tvPageNumber)
            var currentPos: Int = -1
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_page, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = pages[position]
            holder.currentPos = position
            holder.tv.text = "Page ${position + 1}"
            holder.iv.setImageBitmap(null)
            
            val cacheKey = "duplex_${item.uri}_${item.originalPageIndex}"
            bitmapCache.get(cacheKey)?.let {
                holder.iv.setImageBitmap(it)
                return
            }
            
            renderExecutor.execute {
                val bitmap = try {
                    if (nativeRenderer != null && currentRendererUri == item.uri && !item.isEncrypted && !item.isFromImage) {
                        val page = nativeRenderer!!.openPage(item.originalPageIndex)
                        val scale = 1.0f
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        bmp
                    } else if (item.isFromImage) {
                        val inputStream = contentResolver.openInputStream(item.uri) ?: return@execute
                        val bytes = inputStream.readBytes()
                        inputStream.close()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } else {
                        // Fallback to PDFBox for encrypted or if native fails
                        val inputStream = contentResolver.openInputStream(item.uri) ?: return@execute
                        val bytes = inputStream.readBytes()
                        inputStream.close()
                        val doc = if (item.isEncrypted) PDDocument.load(bytes, item.password) else PDDocument.load(bytes)
                        val renderer = com.tom_roush.pdfbox.rendering.PDFRenderer(doc)
                        val scale = 1.0f
                        // Use ARGB and draw on white to fix transparency issues (black patches)
                        val renderedBim = renderer.renderImage(item.originalPageIndex, scale, ImageType.ARGB)
                        val whiteBitmap = Bitmap.createBitmap(renderedBim.width, renderedBim.height, Bitmap.Config.RGB_565)
                        val canvas = Canvas(whiteBitmap)
                        canvas.drawColor(Color.WHITE)
                        canvas.drawBitmap(renderedBim, 0f, 0f, null)
                        renderedBim.recycle()
                        doc.close()
                        whiteBitmap
                    }
                } catch (e: Exception) { null }

                if (bitmap != null && holder.currentPos == position) {
                    bitmapCache.put(cacheKey, bitmap)
                    runOnUiThread { holder.iv.setImageBitmap(bitmap) }
                }
            }
        }
        override fun getItemCount() = pages.size
    }
}
