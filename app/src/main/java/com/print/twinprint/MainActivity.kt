package com.print.twinprint

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.view.LayoutInflater
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
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
import java.io.FileOutputStream
import java.io.InputStream
import java.util.*

class MainActivity : AppCompatActivity() {

    data class PageItem(val uri: Uri, val originalPageIndex: Int, var isSelected: Boolean = true)

    // Views
    private lateinit var viewHome: View
    private lateinit var viewViewer: View
    private lateinit var viewManageMerge: View
    private lateinit var viewPrintOptions: View
    private lateinit var viewDuplex: View
    
    private lateinit var llRecentItems: LinearLayout
    private lateinit var rvPdfPages: RecyclerView
    private lateinit var rvEditPages: RecyclerView
    
    private lateinit var tvPageIndicator: TextView
    private lateinit var tvToolbarTitle: TextView
    
    private lateinit var layoutTimer: View
    private lateinit var pbTimer: ProgressBar
    private lateinit var btnStep1Even: Button
    private lateinit var btnStep2Odd: Button

    private var currentPdfUri: Uri? = null
    private var mergedPages = mutableListOf<PageItem>()
    private var totalPages: Int = 0
    private lateinit var sharedPrefs: SharedPreferences
    private var pdfRenderer: PdfRenderer? = null
    private var parcelFileDescriptor: ParcelFileDescriptor? = null

    private val STORAGE_PERMISSION_CODE = 101
    private val PICK_PDF_CODE = 102

    // Zoom state
    private var scaleFactor = 1.0f
    private lateinit var scaleDetector: ScaleGestureDetector

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPrefs = getSharedPreferences("TwinPrintPrefs", Context.MODE_PRIVATE)

        // Init UI Components
        viewHome = findViewById(R.id.viewHome)
        viewViewer = findViewById(R.id.viewViewer)
        viewManageMerge = findViewById(R.id.viewManageMerge)
        viewPrintOptions = findViewById(R.id.viewPrintOptions)
        viewDuplex = findViewById(R.id.viewDuplex)
        
        llRecentItems = findViewById(R.id.llRecentItems)
        rvPdfPages = findViewById(R.id.rvPdfPages)
        rvEditPages = findViewById(R.id.rvEditPages)
        
        tvPageIndicator = findViewById(R.id.tvPageIndicator)
        tvToolbarTitle = findViewById(R.id.tvToolbarTitle)
        
        layoutTimer = findViewById(R.id.layoutTimer)
        pbTimer = findViewById(R.id.pbTimer)
        btnStep1Even = findViewById(R.id.btnStep1Even)
        btnStep2Odd = findViewById(R.id.btnStep2Odd)

        rvPdfPages.layoutManager = LinearLayoutManager(this)
        rvEditPages.layoutManager = GridLayoutManager(this, 2)

        // Zoom Implementation
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(1.0f, 5.0f)
                rvPdfPages.scaleX = scaleFactor
                rvPdfPages.scaleY = scaleFactor
                rvPdfPages.pivotX = detector.focusX
                rvPdfPages.pivotY = detector.focusY
                return true
            }
        })

        rvPdfPages.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            v.performClick()
            false
        }

        // Android Back Button Logic
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
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
            doPrintMerged("Single Sided", (1..totalPages).toList())
        }

        findViewById<Button>(R.id.btnOptTwoSided).setOnClickListener {
            viewPrintOptions.visibility = View.GONE
            viewViewer.visibility = View.GONE
            viewDuplex.visibility = View.VISIBLE
            btnStep2Odd.isEnabled = false
        }

        // Duplex Actions
        btnStep1Even.setOnClickListener {
            val evens = (2..totalPages step 2).toList()
            doPrintMerged("Step 1: Even Pages", evens)
            startFlipTimer()
        }

        btnStep2Odd.setOnClickListener {
            val odds = (1..totalPages step 2).toList()
            doPrintMerged("Step 2: Odd Pages", odds)
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
                if (pos != RecyclerView.NO_POSITION) tvPageIndicator.text = "${pos + 1} / $totalPages"
            }
        })

        checkPermissions()
        handleIntent(intent)
    }

    private fun goHome() {
        viewHome.visibility = View.VISIBLE
        viewViewer.visibility = View.GONE
        viewManageMerge.visibility = View.GONE
        viewDuplex.visibility = View.GONE
        tvToolbarTitle.text = "TwinPrint PDF Studio"
        
        // Reset zoom
        scaleFactor = 1.0f
        rvPdfPages.scaleX = 1.0f
        rvPdfPages.scaleY = 1.0f
        
        updateRecentFilesList()
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.action
        if ((Intent.ACTION_SEND == action || Intent.ACTION_VIEW == action) && intent.type == "application/pdf") {
            val uri = if (Intent.ACTION_SEND == action) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) else intent.data
            uri?.let { processSelectedUris(listOf(it)) }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_PDF_CODE && resultCode == RESULT_OK) {
            val uris = mutableListOf<Uri>()
            data?.clipData?.let { cd ->
                for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
            } ?: data?.data?.let { uris.add(it) }
            
            uris.forEach { uri ->
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
                saveRecentFile(uri)
            }
            processSelectedUris(uris)
        }
    }

    private fun processSelectedUris(uris: List<Uri>) {
        if (uris.size > 1) {
            AlertDialog.Builder(this)
                .setTitle("Multiple PDFs Selected")
                .setMessage("Do you want to merge these PDFs into one?")
                .setPositiveButton("MERGE") { _, _ -> startMergeWorkflow(uris) }
                .setNegativeButton("KEEP SEPARATE") { _, _ -> loadPdf(uris[0]) } // Load first one for now
                .show()
        } else if (uris.isNotEmpty()) {
            loadPdf(uris[0])
        }
    }

    private fun startMergeWorkflow(uris: List<Uri>) {
        mergedPages.clear()
        uris.forEach { uri ->
            try {
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    val renderer = PdfRenderer(pfd)
                    for (i in 0 until renderer.pageCount) mergedPages.add(PageItem(uri, i))
                    renderer.close()
                    pfd.close()
                }
            } catch (e: Exception) {}
        }
        
        viewHome.visibility = View.GONE
        viewManageMerge.visibility = View.VISIBLE
        rvEditPages.adapter = EditPagesAdapter(mergedPages)
    }

    private fun finalizeMerge() {
        val filtered = mergedPages.filter { it.isSelected }
        if (filtered.isEmpty()) {
            Toast.makeText(this, "Select at least one page", Toast.LENGTH_SHORT).show()
            return
        }
        // Logic to treat 'filtered' as the current document
        totalPages = filtered.size
        rvPdfPages.adapter = MergedPdfAdapter(filtered)
        
        viewManageMerge.visibility = View.GONE
        viewViewer.visibility = View.VISIBLE
        tvPageIndicator.text = "1 / $totalPages"
    }

    private fun loadPdf(uri: Uri) {
        try {
            currentPdfUri = uri
            val pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                pdfRenderer?.close()
                pdfRenderer = PdfRenderer(pfd)
                totalPages = pdfRenderer!!.pageCount
                
                // Convert single PDF to mergedPages format for unified printing logic
                mergedPages.clear()
                for (i in 0 until totalPages) mergedPages.add(PageItem(uri, i))
                
                rvPdfPages.adapter = PdfPagesAdapter(pdfRenderer!!)
                viewHome.visibility = View.GONE
                viewViewer.visibility = View.VISIBLE
                tvToolbarTitle.text = uri.lastPathSegment ?: "Document"
                tvPageIndicator.text = "1 / $totalPages"
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading PDF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startFlipTimer() {
        btnStep1Even.isEnabled = false
        layoutTimer.visibility = View.VISIBLE
        object : CountDownTimer(10000, 100) {
            override fun onTick(ms: Long) { pbTimer.progress = (ms / 1000).toInt() }
            override fun onFinish() {
                layoutTimer.visibility = View.GONE
                btnStep2Odd.isEnabled = true
            }
        }.start()
    }

    private fun doPrintMerged(jobName: String, pageListIndices: List<Int>) {
        val selectedPageItems = mutableListOf<PageItem>()
        val activePages = mergedPages.filter { it.isSelected }
        pageListIndices.forEach { idx ->
            if (idx > 0 && idx <= activePages.size) selectedPageItems.add(activePages[idx - 1])
        }

        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        printManager.print(jobName, object : PrintDocumentAdapter() {
            override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, signal: android.os.CancellationSignal?, callback: LayoutResultCallback?, extras: Bundle?) {
                callback?.onLayoutFinished(android.print.PrintDocumentInfo.Builder(jobName).setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(selectedPageItems.size).build(), true)
            }
            override fun onWrite(ranges: Array<out android.print.PageRange>?, destination: ParcelFileDescriptor?, signal: android.os.CancellationSignal?, callback: WriteResultCallback?) {
                val pdfDoc = android.graphics.pdf.PdfDocument()
                try {
                    selectedPageItems.forEachIndexed { i, item ->
                        val pfd = contentResolver.openFileDescriptor(item.uri, "r")!!
                        val renderer = PdfRenderer(pfd)
                        val page = renderer.openPage(item.originalPageIndex)
                        val pdfPage = pdfDoc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(page.width, page.height, i).create())
                        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        pdfPage.canvas.drawBitmap(bitmap, null, Rect(0, 0, page.width, page.height), null)
                        pdfDoc.finishPage(pdfPage)
                        bitmap.recycle(); page.close(); renderer.close(); pfd.close()
                    }
                    FileOutputStream(destination?.fileDescriptor).use { pdfDoc.writeTo(it) }
                    callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                } catch (e: Exception) { callback?.onWriteFailed(e.message) }
                finally { pdfDoc.close() }
            }
        }, null)
    }

    private fun checkPermissions() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), STORAGE_PERMISSION_CODE)
        } else { updateRecentFilesList() }
    }

    private fun saveRecentFile(uri: Uri) {
        val recentSet = sharedPrefs.getStringSet("recent_pdfs", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        recentSet.add("${uri.lastPathSegment}|$uri")
        sharedPrefs.edit().putStringSet("recent_pdfs", recentSet).apply()
    }

    private fun updateRecentFilesList() {
        llRecentItems.removeAllViews()
        sharedPrefs.getStringSet("recent_pdfs", emptySet())?.forEach { entry ->
            val parts = entry.split("|")
            if (parts.size > 1) {
                val card = LayoutInflater.from(this).inflate(R.layout.item_recent_pdf, llRecentItems, false) as MaterialCardView
                card.findViewById<TextView>(R.id.tvRecentName).text = parts[0]
                card.findViewById<TextView>(R.id.tvRecentUri).text = parts[1]
                card.setOnClickListener { loadPdf(Uri.parse(parts[1])) }
                llRecentItems.addView(card)
            }
        }
    }

    // ADAPTERS
    private inner class PdfPagesAdapter(private val renderer: PdfRenderer) : RecyclerView.Adapter<PdfPagesAdapter.PageViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PageViewHolder(ImageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            adjustViewBounds = true; setPadding(0, 0, 0, 16)
        })
        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            val page = renderer.openPage(position)
            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            (holder.itemView as ImageView).setImageBitmap(bitmap)
            page.close()
        }
        override fun getItemCount() = renderer.pageCount
        inner class PageViewHolder(view: View) : RecyclerView.ViewHolder(view)
    }

    private inner class MergedPdfAdapter(private val items: List<PageItem>) : RecyclerView.Adapter<MergedPdfAdapter.PageViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PageViewHolder(ImageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            adjustViewBounds = true; setPadding(0, 0, 0, 16)
        })
        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            val item = items[position]
            try {
                val pfd = contentResolver.openFileDescriptor(item.uri, "r")!!
                val renderer = PdfRenderer(pfd)
                val page = renderer.openPage(item.originalPageIndex)
                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                (holder.itemView as ImageView).setImageBitmap(bitmap)
                page.close(); renderer.close(); pfd.close()
            } catch (e: Exception) {}
        }
        override fun getItemCount() = items.size
        inner class PageViewHolder(view: View) : RecyclerView.ViewHolder(view)
    }

    private inner class EditPagesAdapter(private val items: MutableList<PageItem>) : RecyclerView.Adapter<EditPagesAdapter.EditViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EditViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_edit_page, parent, false)
            return EditViewHolder(v)
        }
        override fun onBindViewHolder(holder: EditViewHolder, position: Int) {
            val item = items[position]
            holder.tvInfo.text = "Page ${position + 1}"
            holder.cbSelect.isChecked = item.isSelected
            holder.cbSelect.setOnCheckedChangeListener { _, isChecked -> item.isSelected = isChecked }
            
            holder.btnUp.setOnClickListener { if (position > 0) { Collections.swap(items, position, position - 1); notifyItemRangeChanged(position - 1, 2) } }
            holder.btnDown.setOnClickListener { if (position < items.size - 1) { Collections.swap(items, position, position + 1); notifyItemRangeChanged(position, 2) } }

            // Load thumbnail
            try {
                val pfd = contentResolver.openFileDescriptor(item.uri, "r")!!
                val renderer = PdfRenderer(pfd)
                val page = renderer.openPage(item.originalPageIndex)
                val bitmap = Bitmap.createBitmap(page.width / 4, page.height / 4, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                holder.ivThumb.setImageBitmap(bitmap)
                page.close(); renderer.close(); pfd.close()
            } catch (e: Exception) {}
        }
        override fun getItemCount() = items.size
        inner class EditViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val ivThumb = v.findViewById<ImageView>(R.id.ivEditThumb)
            val cbSelect = v.findViewById<CheckBox>(R.id.cbPageSelect)
            val tvInfo = v.findViewById<TextView>(R.id.tvPageInfo)
            val btnUp = v.findViewById<ImageButton>(R.id.btnMoveUp)
            val btnDown = v.findViewById<ImageButton>(R.id.btnMoveDown)
        }
    }
}