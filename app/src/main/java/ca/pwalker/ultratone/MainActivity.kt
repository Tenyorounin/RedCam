package ca.pwalker.ultratone

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Observer
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var processedPreviewView: ImageView
    private lateinit var imageView: ImageView
    private lateinit var status: TextView
    private lateinit var strength: SeekBar
    private lateinit var strengthRow: LinearLayout
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var analysisExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var boundCamera: Camera? = null
    private var zoomObserver: Observer<ZoomState>? = null
    private var sourceBitmap: Bitmap? = null
    private var renderedBitmap: Bitmap? = null
    private var lastPhotoUri: Uri? = null
    private var processedPreviewBitmap: Bitmap? = null
    private var livePortraitProcessor: PortraitBlur.StreamProcessor? = null
    private var lastPreviewFrameNanos = 0L
    private var livePreviewAvailable = false
    @Volatile private var styleStrength = 0.75f
    private var currentZoomRatio = 1f
    @Volatile private var activeMode = ReferenceRenderer.Mode.DAY
    @Volatile private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var isBusy = false
    private var cameraModeNote = ""
    private val modeButtons = mutableMapOf<ReferenceRenderer.Mode, TextView>()
    private val zoomButtons = mutableMapOf<Float, TextView>()
    private lateinit var flashButton: TextView
    private lateinit var shutterButton: ImageButton
    private lateinit var thumbnailButton: ImageButton
    private lateinit var zoomRow: LinearLayout
    private lateinit var modeRow: LinearLayout
    private lateinit var captureControls: LinearLayout
    private lateinit var editControls: LinearLayout

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else message("Camera permission is needed to take photos.")
    }
    private val choosePhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        analysisExecutor = Executors.newSingleThreadExecutor()
        buildScreen()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun buildScreen() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            contentDescription = "Live camera viewfinder. Tap to focus; pinch to zoom."
        }
        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            contentDescription = "Selected photo preview"
        }
        processedPreviewView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            contentDescription = "Live viewfinder with selected photo processing"
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        root.addView(processedPreviewView, FrameLayout.LayoutParams(-1, -1))
        root.addView(imageView, FrameLayout.LayoutParams(-1, -1))

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        flashButton = pill("⚡ Off") { cycleFlash() }
        top.addView(flashButton, LinearLayout.LayoutParams(dp(86), dp(42)))
        status = TextView(this).apply {
            text = "UltraTone"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(0x99000000.toInt(), 24)
        }
        top.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12); marginEnd = dp(12)
        })
        top.addView(pill("↻") { switchCamera() }, LinearLayout.LayoutParams(dp(50), dp(42)))
        overlay.addView(top)
        overlay.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(0xee111216.toInt(), 24)
            setPadding(dp(12), dp(12), dp(12), dp(10))
        }
        zoomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf(0.5f, 1f, 2f, 5f).forEach { ratio ->
            val label = when (ratio) { 0.5f -> "0.5×"; else -> "${ratio.toInt()}×" }
            val item = pill(label) { selectZoom(ratio) }
            zoomButtons[ratio] = item
            zoomRow.addView(item, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4) })
        }
        bottom.addView(zoomRow)

        modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(6))
        }
        ReferenceRenderer.Mode.values().forEach { mode ->
            val item = TextView(this).apply {
                text = modeName(mode)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(9), dp(16), dp(9))
                setTextColor(Color.WHITE)
                setOnClickListener { selectMode(mode) }
                contentDescription = "${modeName(mode)} photo mode"
            }
            modeButtons[mode] = item
            modeRow.addView(item)
        }
        bottom.addView(modeRow)

        strengthRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        strengthRow.addView(TextView(this).apply { text = "Look"; textSize = 12f; setTextColor(0xffd4d4d8.toInt()) })
        strength = SeekBar(this).apply {
            max = 100
            progress = 75
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    styleStrength = value / 100f
                    if (fromUser) updateStatus()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    sourceBitmap?.let { renderImported(it) }
                }
            })
        }
        strengthRow.addView(strength, LinearLayout.LayoutParams(0, dp(36), 1f))
        bottom.addView(strengthRow)

        captureControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        thumbnailButton = ImageButton(this).apply {
            contentDescription = "Open the last photo; long press to choose a photo"
            background = rounded(0xff33353a.toInt(), 12)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(android.R.drawable.ic_menu_gallery)
            setOnClickListener {
                val uri = lastPhotoUri
                if (uri == null) choosePhoto.launch("image/*")
                else try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) { choosePhoto.launch("image/*") }
            }
            setOnLongClickListener { choosePhoto.launch("image/*"); true }
        }
        captureControls.addView(thumbnailButton, LinearLayout.LayoutParams(dp(52), dp(52)))
        captureControls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        shutterButton = ImageButton(this).apply {
            contentDescription = "Take photo and apply UltraTone processing"
            background = rounded(Color.WHITE, 50)
            setImageDrawable(null)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            foreground = roundedStroke(0x00000000, Color.WHITE, 3, 50)
            setOnClickListener { capture() }
        }
        captureControls.addView(shutterButton, LinearLayout.LayoutParams(dp(70), dp(70)))
        captureControls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        captureControls.addView(pill("More") { strengthRow.visibility = if (strengthRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE },
            LinearLayout.LayoutParams(dp(64), dp(44)))
        bottom.addView(captureControls)

        editControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        editControls.addView(pill("Camera") { showCamera() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        editControls.addView(pill("Save") { savePhoto() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        editControls.addView(pill("Choose another") { choosePhoto.launch("image/*") }, LinearLayout.LayoutParams(0, dp(44), 1f))
        bottom.addView(editControls)
        overlay.addView(bottom)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        updateModeButtons()
        attachPreviewGestures()
        setContentView(root)
    }

    private fun attachPreviewGestures() {
        val detector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val camera = boundCamera ?: return false
                val state = camera.cameraInfo.zoomState.value ?: return false
                camera.cameraControl.setZoomRatio((currentZoomRatio * detector.scaleFactor).coerceIn(state.minZoomRatio, state.maxZoomRatio))
                return true
            }
        })
        previewView.setOnTouchListener { view, event ->
            detector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !detector.isInProgress) {
                try {
                    val factory: MeteringPointFactory = previewView.meteringPointFactory
                    val point = factory.createPoint(event.x, event.y)
                    boundCamera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                } catch (_: Exception) { }
            }
            true
        }
        processedPreviewView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !detector.isInProgress) {
                try {
                    val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                    boundCamera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                } catch (_: Exception) { }
            }
            true
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val ext = ExtensionsManager.getInstanceAsync(this, provider)
                ext.addListener({
                    try { extensionsManager = ext.get() } catch (_: Exception) { extensionsManager = null }
                    bindCamera()
                }, ContextCompat.getMainExecutor(this))
            } catch (e: Exception) { message("Could not start camera: ${e.localizedMessage ?: "unsupported camera"}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        zoomObserver?.let { boundCamera?.cameraInfo?.zoomState?.removeObserver(it) }
        zoomObserver = null
        val baseSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val extension = when (activeMode) {
            ReferenceRenderer.Mode.DAY -> null
            ReferenceRenderer.Mode.NIGHT -> ExtensionMode.NIGHT
            ReferenceRenderer.Mode.PORTRAIT -> ExtensionMode.BOKEH
        }
        var selector = baseSelector
        var usingExtension = false
        if (extension != null && lensFacing == CameraSelector.LENS_FACING_BACK) {
            try {
                val manager = extensionsManager
                if (manager != null && manager.isExtensionAvailable(baseSelector, extension)) {
                    selector = manager.getExtensionEnabledCameraSelector(baseSelector, extension)
                    usingExtension = true
                }
            } catch (_: Exception) { }
        }
        try {
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(flashMode).build()
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { processPreviewFrame(it) }
            provider.unbindAll()
            var live = false
            var camera: Camera? = null
            var extensionBound = usingExtension
            try {
                camera = provider.bindToLifecycle(this, selector, preview, capture, analysis)
                live = true
            } catch (_: Exception) {
                analysis.clearAnalyzer()
                provider.unbindAll()
                if (usingExtension) {
                    try {
                        analysis.setAnalyzer(analysisExecutor) { processPreviewFrame(it) }
                        camera = provider.bindToLifecycle(this, baseSelector, preview, capture, analysis)
                        extensionBound = false
                        live = true
                    } catch (_: Exception) {
                        analysis.clearAnalyzer()
                        provider.unbindAll()
                        camera = provider.bindToLifecycle(this, baseSelector, preview, capture)
                        extensionBound = false
                    }
                } else {
                    camera = provider.bindToLifecycle(this, baseSelector, preview, capture)
                }
            }
            imageCapture = capture
            imageAnalysis = if (live) analysis else null
            boundCamera = camera
            livePreviewAvailable = live
            cameraModeNote = when (activeMode) {
                ReferenceRenderer.Mode.DAY -> "Day"
                ReferenceRenderer.Mode.NIGHT -> if (extensionBound) "Night capture enhancement" else "Night · single-frame processing"
                ReferenceRenderer.Mode.PORTRAIT -> if (extensionBound) "Portrait · camera bokeh plus subject blur" else "Portrait · AI background blur"
            }
            if (live) cameraModeNote += " · live look"
            if (!live) cameraModeNote += " · live effect unavailable"
            flashButton.visibility = if (boundCamera?.cameraInfo?.hasFlashUnit() == true) View.VISIBLE else View.INVISIBLE
            previewView.visibility = View.VISIBLE
            processedPreviewView.visibility = View.GONE
            imageView.visibility = View.GONE
            zoomRow.visibility = View.VISIBLE
            captureControls.visibility = View.VISIBLE
            editControls.visibility = View.GONE
            processedPreviewBitmap?.recycle()
            processedPreviewBitmap = null
            observeZoom()
            updateStatus()
        } catch (e: Exception) {
            message("Could not configure camera: ${e.localizedMessage ?: "unsupported configuration"}")
        }
    }

    private fun observeZoom() {
        val camera = boundCamera ?: return
        val observer = Observer<ZoomState> { state ->
            if (state == null) return@Observer
            currentZoomRatio = state.zoomRatio
            zoomButtons.forEach { (ratio, view) ->
                val available = ratio >= state.minZoomRatio - 0.03f && ratio <= state.maxZoomRatio + 0.03f
                view.visibility = if (available) View.VISIBLE else View.GONE
                view.alpha = if (kotlin.math.abs(currentZoomRatio - ratio) < 0.12f) 1f else 0.68f
            }
        }
        zoomObserver = observer
        camera.cameraInfo.zoomState.observe(this, observer)
    }

    private fun processPreviewFrame(proxy: ImageProxy) {
        val mode = activeMode
        val now = System.nanoTime()
        val minimumInterval = if (mode == ReferenceRenderer.Mode.PORTRAIT) 450_000_000L else 120_000_000L
        if (now - lastPreviewFrameNanos < minimumInterval) {
            proxy.close()
            return
        }
        lastPreviewFrameNanos = now
        var styled: Bitmap? = null
        try {
            val plane = proxy.planes.firstOrNull() ?: return
            val buffer: ByteBuffer = plane.buffer.duplicate()
            val width = proxy.width
            val height = proxy.height
            val pixels = IntArray(width * height)
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            for (y in 0 until height) {
                val rowOffset = y * rowStride
                for (x in 0 until width) {
                    val offset = rowOffset + x * pixelStride
                    val r = buffer.get(offset).toInt() and 0xff
                    val g = buffer.get(offset + 1).toInt() and 0xff
                    val b = buffer.get(offset + 2).toInt() and 0xff
                    val a = buffer.get(offset + 3).toInt() and 0xff
                    pixels[y * width + x] = Color.argb(a, r, g, b)
                }
            }
            val raw = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            val rotation = proxy.imageInfo.rotationDegrees
            val oriented = if (rotation != 0 || lensFacing == CameraSelector.LENS_FACING_FRONT) {
                val transform = Matrix().apply {
                    postRotate(rotation.toFloat())
                    if (lensFacing == CameraSelector.LENS_FACING_FRONT) postScale(-1f, 1f)
                }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, transform, true).also { if (it !== raw) raw.recycle() }
            } else raw
            val colorized = ReferenceRenderer.render(oriented, styleStrength, mode, currentZoomRatio)
            styled = colorized
            oriented.recycle()
            var output: Bitmap = colorized
            if (mode == ReferenceRenderer.Mode.PORTRAIT) {
                val segmenter = livePortraitProcessor ?: PortraitBlur.StreamProcessor().also { livePortraitProcessor = it }
                val portrait = segmenter.apply(colorized)
                output = portrait.bitmap
                if (output !== colorized) colorized.recycle()
            } else {
                livePortraitProcessor?.close()
                livePortraitProcessor = null
            }
            if (mode != activeMode) {
                output.recycle()
                return
            }
            val display = output
            runOnUiThread {
                if (previewView.visibility == View.VISIBLE) {
                    val old = processedPreviewBitmap
                    processedPreviewBitmap = display
                    processedPreviewView.setImageBitmap(display)
                    processedPreviewView.visibility = View.VISIBLE
                    old?.takeIf { it !== display && !it.isRecycled }?.recycle()
                } else if (!display.isRecycled) display.recycle()
            }
            if (output === colorized) styled = null
        } catch (_: Exception) {
            styled?.takeIf { !it.isRecycled }?.recycle()
        } finally {
            proxy.close()
        }
    }

    private fun selectZoom(requested: Float) {
        val state = boundCamera?.cameraInfo?.zoomState?.value ?: return
        boundCamera?.cameraControl?.setZoomRatio(requested.coerceIn(state.minZoomRatio, state.maxZoomRatio))
    }

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        if (lensFacing == CameraSelector.LENS_FACING_FRONT && activeMode == ReferenceRenderer.Mode.NIGHT) {
            activeMode = ReferenceRenderer.Mode.DAY
            updateModeButtons()
        }
        bindCamera()
    }

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        flashButton.text = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> "⚡ Auto"
            ImageCapture.FLASH_MODE_ON -> "⚡ On"
            else -> "⚡ Off"
        }
        if (cameraProvider != null) bindCamera()
    }

    private fun selectMode(mode: ReferenceRenderer.Mode) {
        activeMode = mode
        updateModeButtons()
        sourceBitmap?.let { renderImported(it) }
        if (previewView.visibility == View.VISIBLE && cameraProvider != null) bindCamera()
    }

    private fun updateModeButtons() {
        modeButtons.forEach { (mode, button) ->
            val selected = mode == activeMode
            button.setTextColor(if (selected) 0xffffd28c.toInt() else 0xfff2f2f4.toInt())
            button.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            button.alpha = if (selected) 1f else 0.72f
        }
    }

    private fun capture() {
        if (isBusy) return
        val capture = imageCapture ?: run { message("Camera is starting. Try again in a moment."); return }
        isBusy = true
        shutterButton.isEnabled = false
        status.text = "Taking photo…"
        val file = File(cacheDir, "capture-${System.currentTimeMillis()}.jpg")
        val zoom = currentZoomRatio
        val modeAtCapture = activeMode
        val strengthAtCapture = styleStrength
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    try {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, bounds)
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions(bounds.outWidth, bounds.outHeight))
                            ?: error("Could not decode captured image")
                        val upright = orient(bitmap, file)
                        val style = ReferenceRenderer.render(upright, strengthAtCapture, modeAtCapture, zoom)
                        val portrait = if (modeAtCapture == ReferenceRenderer.Mode.PORTRAIT) (try { PortraitBlur.apply(style) } catch (_: Exception) { null }) else null
                        val final = portrait?.bitmap ?: style
                        upright.recycle()
                        if (final !== style) style.recycle()
                        val uri = saveBitmap(final)
                        runOnUiThread {
                            isBusy = false
                            shutterButton.isEnabled = true
                            status.text = if (portrait?.subjectDetected == true) "Saved · portrait blur applied" else "Saved to Pictures/UltraTone"
                            updateThumbnail(final)
                            message(if (modeAtCapture == ReferenceRenderer.Mode.PORTRAIT && portrait?.subjectDetected != true)
                                "Saved; portrait blur was skipped because no person was isolated" else "Photo processed and saved")
                            final.recycle()
                        }
                        lastPhotoUri = uri
                    } catch (e: Exception) {
                        runOnUiThread { isBusy = false; shutterButton.isEnabled = true; message("Photo processing failed: ${e.localizedMessage ?: "unknown error"}"); updateStatus() }
                    } finally { file.delete() }
                }
                override fun onError(error: ImageCaptureException) {
                    runOnUiThread { isBusy = false; shutterButton.isEnabled = true; message("Capture failed: ${error.message ?: "camera error"}"); updateStatus() }
                    file.delete()
                }
            })
    }

    private fun saveBitmap(bitmap: Bitmap): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "UltraTone-${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UltraTone")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create photo in gallery")
        contentResolver.openOutputStream(uri).use { stream ->
            if (stream == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 96, stream)) error("Photo encoding failed")
        }
        return uri
    }

    private fun updateThumbnail(bitmap: Bitmap) {
        val thumb = Bitmap.createScaledBitmap(bitmap, 80, 80, true)
        thumbnailButton.setImageBitmap(thumb)
    }

    private fun loadUri(uri: Uri) {
        status.text = "Opening photo…"
        cameraExecutor.execute {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
                val decoded = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, decodeOptions(bounds.outWidth, bounds.outHeight)) }
                    ?: error("Unsupported photo")
                val upright = orient(decoded, uri)
                runOnUiThread {
                    sourceBitmap?.recycle()
                    sourceBitmap = upright
                    previewView.visibility = View.GONE
                    imageView.visibility = View.VISIBLE
                    zoomRow.visibility = View.GONE
                    modeRow.visibility = View.VISIBLE
                    captureControls.visibility = View.GONE
                    editControls.visibility = View.VISIBLE
                    strengthRow.visibility = View.VISIBLE
                    renderImported(upright)
                }
            } catch (e: Exception) { runOnUiThread { message("Could not open photo: ${e.localizedMessage ?: "unsupported file"}") } }
        }
    }

    private fun renderImported(source: Bitmap) {
        val mode = activeMode
        cameraExecutor.execute {
            try {
                val styled = ReferenceRenderer.render(source, styleStrength, mode)
                val portrait = if (mode == ReferenceRenderer.Mode.PORTRAIT) (try { PortraitBlur.apply(styled) } catch (_: Exception) { null }) else null
                val output = portrait?.bitmap ?: styled
                if (output !== styled) styled.recycle()
                runOnUiThread {
                    renderedBitmap?.recycle()
                    renderedBitmap = output
                    imageView.setImageBitmap(output)
                    status.text = if (portrait?.subjectDetected == true) "Portrait · person detected, background blurred" else "${modeName(mode)} look preview"
                }
            } catch (e: Exception) { runOnUiThread { message("Processing failed: ${e.localizedMessage ?: "not enough memory"}") } }
        }
    }

    private fun savePhoto() {
        val bitmap = renderedBitmap ?: run { message("Choose a photo first."); return }
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        cameraExecutor.execute {
            try {
                val uri = saveBitmap(copy)
                lastPhotoUri = uri
                runOnUiThread { updateThumbnail(copy); message("Saved to Pictures/UltraTone"); copy.recycle() }
            } catch (e: Exception) { copy.recycle(); runOnUiThread { message("Save failed: ${e.localizedMessage ?: "storage error"}") } }
        }
    }

    private fun showCamera() {
        sourceBitmap?.recycle(); sourceBitmap = null
        renderedBitmap?.recycle(); renderedBitmap = null
        imageView.setImageDrawable(null)
        imageView.visibility = View.GONE
        processedPreviewView.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        zoomRow.visibility = View.VISIBLE
        captureControls.visibility = View.VISIBLE
        editControls.visibility = View.GONE
        if (cameraProvider != null) bindCamera()
    }

    private fun decodeOptions(width: Int, height: Int): BitmapFactory.Options {
        var sample = 1
        while (width / sample.toLong() * (height / sample.toLong()) > 16_000_000L) sample *= 2
        return BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    }

    private fun orient(bitmap: Bitmap, file: File): Bitmap = try {
        val exif = ExifInterface(file)
        rotate(bitmap, exif.rotationDegrees)
    } catch (_: Exception) { bitmap }

    private fun orient(bitmap: Bitmap, uri: Uri): Bitmap = try {
        contentResolver.openInputStream(uri).use { rotate(bitmap, ExifInterface(it!!).rotationDegrees) }
    } catch (_: Exception) { bitmap }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun updateStatus() {
        if (!isBusy) status.text = "$cameraModeNote · ${strength.progress}% look"
    }

    private fun pill(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = rounded(0xff292b30.toInt(), 22)
        setOnClickListener { action() }
        isClickable = true
        contentDescription = label
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    private fun roundedStroke(color: Int, strokeColor: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); setStroke(dp(stroke), strokeColor); cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun modeName(mode: ReferenceRenderer.Mode) = when (mode) {
        ReferenceRenderer.Mode.DAY -> "Day"
        ReferenceRenderer.Mode.NIGHT -> "Night"
        ReferenceRenderer.Mode.PORTRAIT -> "Portrait"
    }
    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        zoomObserver?.let { boundCamera?.cameraInfo?.zoomState?.removeObserver(it) }
        cameraExecutor.shutdown()
        analysisExecutor.execute { livePortraitProcessor?.close(); livePortraitProcessor = null }
        analysisExecutor.shutdown()
        sourceBitmap?.recycle()
        renderedBitmap?.recycle()
        processedPreviewBitmap?.recycle()
    }
}
