package com.print.twinprint

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.text.InputType
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.FileOutputStream
import java.util.*
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    data class PageItem(val uri: Uri, val originalPageIndex: Int, var isSelected: Boolean = true, var password: String? = null, var isEncrypted: Boolean = false)

    // Views
    private lateinit var viewHome: View
    private lateinit var viewViewer: View
    private lateinit var viewManageMerge: View
    private lateinit var viewPrintOptions: View
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

    private var currentPdfUri: Uri? = null
    private var mergedPages = mutableListOf<PageItem>()
    private var totalPages: Int = 0
    private lateinit var sharedPrefs: SharedPreferences
    
    private val STORAGE_PERMISSION_CODE = 101
    private val PICK_PDF_CODE = 102

    // Batch Print state
    private var batchUris = mutableListOf<Uri>()
    private var currentBatchIndex = 0
    private var isBatchSeparate = false
    private var batchIsDoubleSided = false

    // Zoom and Pan state
    private var scaleFactor = 1.0f
    private lateinit var scaleDetector: ScaleGestureDetector
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var activePointerId = -1

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

        rvPdfPages.layoutManager = LinearLayoutManager(this)
        rvEditPages.layoutManager = GridLayoutManager(this, 2)
        rvDuplexPreview.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        // Zoom Implementation
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prevScale = scaleFactor
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(1.0f, 5.0f)

                if (prevScale != scaleFactor) {
                    rvPdfPages.scaleX = scaleFactor
                    rvPdfPages.scaleY = scaleFactor

                    val focusX = detector.focusX
                    val focusY = detector.focusY
                    
                    val dx = (focusX - rvPdfPages.translationX) * (scaleFactor / prevScale - 1)
                    val dy = (focusY - rvPdfPages.translationY) * (scaleFactor / prevScale - 1)
                    
                    rvPdfPages.translationX -= dx
                    rvPdfPages.translationY -= dy
                    
                    clampTranslation()
                }
                return true
            }
        })

        rvPdfPages.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            
            val action = event.actionMasked
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = event.getPointerId(0)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex != -1) {
                        val currentRawX = event.rawX
                        val currentRawY = event.rawY
                        
                        if (scaleFactor > 1.0f && !scaleDetector.isInProgress) {
                            val dx = currentRawX - lastRawX
                            val dy = currentRawY - lastRawY
                            
                            rvPdfPages.translationX += dx
                            rvPdfPages.translationY += dy
                            
                            clampTranslation()
                            
                            lastRawX = currentRawX
                            lastRawY = currentRawY
                            return@setOnTouchListener true
                        }
                        lastRawX = currentRawX
                        lastRawY = currentRawY
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val pointerIndex = event.actionIndex
                    val pointerId = event.getPointerId(pointerIndex)
                    if (pointerId == activePointerId) {
                        val newPointerIndex = if (pointerIndex == 0) 1 else 0
                        lastRawX = event.getX(newPointerIndex)
                        lastRawY = event.getY(newPointerIndex)
                        activePointerId = event.getPointerId(newPointerIndex)
                    }
                }
            }
            
            if (action == MotionEvent.ACTION_UP) v.performClick()
            false
        }

        // Android Back Button Logic
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewAd.visibility == View.VISIBLE) return // Block back during ad
                if (viewPrintOptions.visibility == View.VISIBLE) {
                    viewPrintOptions.visibility = View.GONE
                } else if (viewViewer.visibility == View.VISIBLE || viewManageMerge.visibility == View.VISIBLE || viewDuplex.visibility == View.VISIBLE) {
                    goHome()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Home Page Actions
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

        // Viewer Actions
        findViewById<Button>(R.id.btnMainPrint).setOnClickListener { viewPrintOptions.visibility = View.VISIBLE }
        findViewById<TextView>(R.id.tvCancelOptions).setOnClickListener { viewPrintOptions.visibility = View.GONE }

        findViewById<Button>(R.id.btnOptSingle).setOnClickListener {
            viewPrintOptions.visibility = View.GONE
            if (isBatchSeparate) {
                batchIsDoubleSided = false
                processNextInBatch()
            } else if (totalPages > 0) {
                val pageList = (1..totalPages).toList()
                doPrintMerged(getFileName(currentPdfUri) ?: "Document", pageList) {
                    showAdForPages(pageList.size) {
                        goHome()
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

        // Duplex Actions
        btnStep1Even.setOnClickListener {
            if (totalPages >= 2) {
                btnStep1Even.isEnabled = false
                val evens = (2..totalPages step 2).toList()
                doPrintMerged("Even Pages - ${getFileName(currentPdfUri) ?: "Document"}", evens) {
                    showAdForPages(evens.size) {
                        startFlipTimer()
                    }
                }
            } else {
                Toast.makeText(this, "Document has only one page", Toast.LENGTH_SHORT).show()
            }
        }

        btnStep2Odd.setOnClickListener {
            if (totalPages > 0) {
                btnStep2Odd.isEnabled = false
                val odds = (1..totalPages step 2).toList()
                doPrintMerged("Odd Pages - ${getFileName(currentPdfUri) ?: "Document"}", odds) {
                    showAdForPages(odds.size) {
                        if (isBatchSeparate) {
                            currentBatchIndex++
                            processNextInBatch()
                        } else {
                            Toast.makeText(this, "Printing complete", Toast.LENGTH_SHORT).show()
                            goHome()
                        }
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnBackFromDuplex).setOnClickListener {
            viewDuplex.visibility = View.GONE
            viewViewer.visibility = View.VISIBLE
        }

        // Merge Manager Actions
        findViewById<Button>(R.id.btnDiscardMerge).setOnClickListener { goHome() }
        findViewById<Button>(R.id.btnConfirmMerge).setOnClickListener {
            finalizeMerge()
        }

        // Setup Page Scroll Listener
        rvPdfPages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val pos = (recyclerView.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                if (pos != RecyclerView.NO_POSITION) {
                    tvPageIndicator.text = "${pos + 1} / $totalPages"
                }
            }
        })

        updateRecentFilesList()
        checkPermissions()
    }

    private fun clampTranslation() {
        val maxDx = (rvPdfPages.width * (scaleFactor - 1)) / 2
        val maxDy = (rvPdfPages.height * (scaleFactor - 1)) / 2
        rvPdfPages.translationX = rvPdfPages.translationX.coerceIn(-maxDx, maxDx)
        rvPdfPages.translationY = rvPdfPages.translationY.coerceIn(-maxDy, maxDy)
    }

    private fun goHome() {
        viewHome.visibility = View.VISIBLE
        viewViewer.visibility = View.GONE
        viewManageMerge.visibility = View.GONE
        viewPrintOptions.visibility = View.GONE
        viewDuplex.visibility = View.GONE
        viewAd.visibility = View.GONE
        tvToolbarTitle.text = "TwinPrint PDF Studio"
        updateRecentFilesList()
        // Ensure keep screen on flag is cleared
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setupDuplexUI() {
        viewViewer.visibility = View.GONE
        viewDuplex.visibility = View.VISIBLE
        val duplexInfo = findViewById<TextView>(R.id.tvDuplexInfo)
        
        // Setup Preview
        rvDuplexPreview.adapter = DuplexPreviewAdapter(mergedPages)
        
        if (totalPages == 1) {
            duplexInfo.text = "Double Sided Printing (Single Page)"
            btnStep1Even.isEnabled = false
            btnStep2Odd.isEnabled = true
            Toast.makeText(this, "This document has only one page. Print as Odd page.", Toast.LENGTH_LONG).show()
        } else {
            duplexInfo.text = "Double Sided Printing"
            btnStep1Even.isEnabled = true
            btnStep2Odd.isEnabled = false
        }
    }

    private fun showAdForPages(numPages: Int, onFinish: () -> Unit) {
        val durationSeconds = 30L + numPages * 10L
        showAd(durationSeconds * 1000L, isPrinting = true, onFinish)
    }

    private fun showAd(ms: Long, isPrinting: Boolean = false, onFinish: () -> Unit) {
        viewAd.visibility = View.VISIBLE
        tvPrintingStatus.visibility = if (isPrinting) View.VISIBLE else View.GONE
        
        // Keep screen on during advertisement
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        object : CountDownTimer(ms, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                tvAdTimer.text = "Closing in ${millisUntilFinished / 1000}s..."
            }
            override fun onFinish() {
                viewAd.visibility = View.GONE
                // Only clear flag if we are not currently in the flip timer phase
                if (layoutTimer.visibility != View.VISIBLE) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onFinish()
            }
        }.start()
    }

    private fun startFlipTimer() {
        layoutTimer.visibility = View.VISIBLE
        pbTimer.progress = 10
        
        // Keep screen on during reversal (flip timer)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        object : CountDownTimer(10000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                pbTimer.progress = (millisUntilFinished / 1000).toInt()
            }
            override fun onFinish() {
                layoutTimer.visibility = View.GONE
                btnStep2Odd.isEnabled = true
                // Only clear flag if we are not currently showing an ad
                if (viewAd.visibility != View.VISIBLE) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_PDF_CODE && resultCode == RESULT_OK && data != null) {
            val clipData = data.clipData
            if (clipData != null && clipData.itemCount > 1) {
                // Batch/Merge mode
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
                if (batchIsDoubleSided) {
                    setupDuplexUI()
                } else {
                    val pageList = (1..totalPages).toList()
                    doPrintMerged(getFileName(uri) ?: "Document", pageList) {
                        showAdForPages(pageList.size) {
                            currentBatchIndex++
                            processNextInBatch()
                        }
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
        
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val bytes = inputStream.readBytes()
            inputStream.close()
            PDDocument.load(bytes).use { doc ->
                totalPages = doc.numberOfPages
                mergedPages.clear()
                for (i in 0 until totalPages) {
                    mergedPages.add(PageItem(uri, i, true))
                }
                displayPdf()
                onOpened?.invoke()
            }
        } catch (e: InvalidPasswordException) {
            showPasswordDialog(uri, onOpened)
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
                        for (i in 0 until totalPages) {
                            mergedPages.add(PageItem(uri, i, true, password, true))
                        }
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
                    for (i in 0 until doc.numberOfPages) {
                        mergedPages.add(PageItem(uri, i))
                    }
                }
            } catch (e: Exception) {}
        }
        viewHome.visibility = View.GONE
        viewManageMerge.visibility = View.VISIBLE
        rvEditPages.adapter = PageEditAdapter(mergedPages)
    }

    private fun finalizeMerge() {
        val selected = mergedPages.filter { it.isSelected }
        if (selected.isEmpty()) {
            Toast.makeText(this, "No pages selected", Toast.LENGTH_SHORT).show()
            return
        }
        mergedPages.clear()
        mergedPages.addAll(selected)
        totalPages = mergedPages.size
        viewManageMerge.visibility = View.GONE
        displayPdf()
    }

    private fun displayPdf() {
        viewHome.visibility = View.GONE
        viewViewer.visibility = View.VISIBLE
        tvToolbarTitle.text = getFileName(currentPdfUri) ?: "PDF Viewer"
        tvPageIndicator.text = "1 / $totalPages"
        rvPdfPages.adapter = PdfPageAdapter(mergedPages)
        
        // Reset Zoom
        scaleFactor = 1.0f
        rvPdfPages.scaleX = 1.0f
        rvPdfPages.scaleY = 1.0f
        rvPdfPages.translationX = 0f
        rvPdfPages.translationY = 0f
    }

    private fun doPrintMerged(jobName: String, pageNumbers: List<Int>, onComplete: (() -> Unit)? = null) {
        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val pagesToPrint = ArrayList(mergedPages)
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: android.os.CancellationSignal?, callback: LayoutResultCallback?, extras: Bundle?) {
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onLayoutCancelled()
                    return
                }
                val info = android.print.PrintDocumentInfo.Builder(jobName)
                    .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(pageNumbers.size)
                    .build()
                callback?.onLayoutFinished(info, true)
            }

            override fun onWrite(pages: Array<out android.print.PageRange>?, destination: ParcelFileDescriptor?, cancellationSignal: android.os.CancellationSignal?, callback: WriteResultCallback?) {
                val openedDocs = mutableMapOf<Uri, PDDocument>()
                val outDoc = PDDocument()
                try {
                    for (pNum in pageNumbers) {
                        if (cancellationSignal?.isCanceled == true) {
                            callback?.onWriteCancelled()
                            return
                        }
                        val item = pagesToPrint[pNum - 1]
                        val sourceDoc = openedDocs.getOrPut(item.uri) {
                            val inputStream = contentResolver.openInputStream(item.uri)
                                ?: throw Exception("Cannot open stream for ${item.uri}")
                            val bytes = inputStream.readBytes()
                            inputStream.close()
                            if (item.isEncrypted) PDDocument.load(bytes, item.password)
                            else PDDocument.load(bytes)
                        }
                        outDoc.importPage(sourceDoc.getPage(item.originalPageIndex))
                    }
                    
                    FileOutputStream(destination?.fileDescriptor).use { outputStream ->
                        outDoc.save(outputStream)
                    }
                    callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback?.onWriteFailed(e.message)
                } finally {
                    outDoc.close()
                    openedDocs.values.forEach { 
                        try { it.close() } catch (ex: Exception) {}
                    }
                }
            }

            override fun onFinish() {
                super.onFinish()
                runOnUiThread { onComplete?.invoke() }
            }
        }
        printManager.print(jobName, adapter, null)
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
        
        if (recents.isEmpty()) {
            tvWelcome.visibility = View.VISIBLE
            tvRecentLabel.visibility = View.GONE
        } else {
            tvWelcome.visibility = View.GONE
            tvRecentLabel.visibility = View.VISIBLE
        }

        val recentList = recents.toList()
        val lastFive = if (recentList.size > 5) {
            recentList.subList(recentList.size - 5, recentList.size)
        } else {
            recentList
        }
        lastFive.reversed().forEach { uriStr ->
            val uri = Uri.parse(uriStr)
            val view = LayoutInflater.from(this).inflate(R.layout.item_recent_pdf, llRecentItems, false)
            val foreground = view.findViewById<View>(R.id.layoutRecentForeground)
            val background = view.findViewById<View>(R.id.layoutDeleteBackground)
            view.findViewById<TextView>(R.id.tvRecentName).text = getFileName(uri)
            
            var startX = 0f
            var currentDx = 0f
            foreground.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        currentDx = 0f
                        background.visibility = View.VISIBLE
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - startX
                        if (dx < 0) { // Swipe Left
                            currentDx = dx
                            v.translationX = dx
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (currentDx < -v.width / 3) {
                            v.animate().translationX(-v.width.toFloat()).alpha(0f).setDuration(200).withEndAction {
                                removeRecentFile(uriStr)
                            }.start()
                        } else {
                            v.animate().translationX(0f).setDuration(200).withEndAction {
                                background.visibility = View.INVISIBLE
                            }.start()
                            if (abs(currentDx) < 10) foreground.performClick()
                        }
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
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), STORAGE_PERMISSION_CODE)
            }
        }
    }

    // Adapters
    inner class PdfPageAdapter(private val pages: List<PageItem>) : RecyclerView.Adapter<PdfPageAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView = v.findViewById(R.id.ivPage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_page, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = pages[position]
            try {
                val inputStream = contentResolver.openInputStream(item.uri) ?: return
                val bytes = inputStream.readBytes()
                inputStream.close()
                if (item.isEncrypted) {
                    val doc = if (item.password != null) PDDocument.load(bytes, item.password)
                              else PDDocument.load(bytes)
                    val renderer = com.tom_roush.pdfbox.rendering.PDFRenderer(doc)
                    val bitmap = renderer.renderImage(item.originalPageIndex, 1.5f)
                    holder.iv.setImageBitmap(bitmap)
                    doc.close()
                } else {
                    val fd = contentResolver.openFileDescriptor(item.uri, "r") ?: return
                    val renderer = PdfRenderer(fd)
                    val page = renderer.openPage(item.originalPageIndex)
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    holder.iv.setImageBitmap(bitmap)
                    page.close()
                    renderer.close()
                    fd.close()
                }
            } catch (e: Exception) {}
        }

        override fun getItemCount() = pages.size
    }

    inner class PageEditAdapter(private val items: List<PageItem>) : RecyclerView.Adapter<PageEditAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView = v.findViewById(R.id.ivEditThumb)
            val cb: CheckBox = v.findViewById(R.id.cbPageSelect)
            val card: MaterialCardView = v as MaterialCardView
            val tvInfo: TextView = v.findViewById(R.id.tvPageInfo)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_edit_page, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.cb.isChecked = item.isSelected
            holder.tvInfo.text = "Page ${position + 1}"
            
            try {
                val inputStream = contentResolver.openInputStream(item.uri) ?: return
                val bytes = inputStream.readBytes()
                inputStream.close()
                if (item.isEncrypted) {
                    val doc = if (item.password != null) PDDocument.load(bytes, item.password)
                              else PDDocument.load(bytes)
                    val renderer = com.tom_roush.pdfbox.rendering.PDFRenderer(doc)
                    val bitmap = renderer.renderImage(item.originalPageIndex, 0.25f)
                    holder.iv.setImageBitmap(bitmap)
                    doc.close()
                } else {
                    val fd = contentResolver.openFileDescriptor(item.uri, "r") ?: return
                    val renderer = PdfRenderer(fd)
                    val page = renderer.openPage(item.originalPageIndex)
                    val bitmap = Bitmap.createBitmap(page.width / 4, page.height / 4, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    holder.iv.setImageBitmap(bitmap)
                    page.close()
                    renderer.close()
                    fd.close()
                }
            } catch (e: Exception) {}

            holder.card.setOnClickListener {
                item.isSelected = !item.isSelected
                holder.cb.isChecked = item.isSelected
            }
        }

        override fun getItemCount() = items.size
    }

    inner class DuplexPreviewAdapter(private val items: List<PageItem>) : RecyclerView.Adapter<DuplexPreviewAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView = v.findViewById(R.id.ivDuplexThumb)
            val tvInfo: TextView = v.findViewById(R.id.tvDuplexPageInfo)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_duplex_preview, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvInfo.text = "Page ${position + 1}"
            try {
                val inputStream = contentResolver.openInputStream(item.uri) ?: return
                val bytes = inputStream.readBytes()
                inputStream.close()
                if (item.isEncrypted) {
                    val doc = if (item.password != null) PDDocument.load(bytes, item.password)
                              else PDDocument.load(bytes)
                    val renderer = com.tom_roush.pdfbox.rendering.PDFRenderer(doc)
                    val bitmap = renderer.renderImage(item.originalPageIndex, 1.0f) // Higher quality for preview
                    holder.iv.setImageBitmap(bitmap)
                    doc.close()
                } else {
                    val fd = contentResolver.openFileDescriptor(item.uri, "r") ?: return
                    val renderer = PdfRenderer(fd)
                    val page = renderer.openPage(item.originalPageIndex)
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    holder.iv.setImageBitmap(bitmap)
                    page.close()
                    renderer.close()
                    fd.close()
                }
            } catch (e: Exception) {}
        }

        override fun getItemCount() = items.size
    }
}
