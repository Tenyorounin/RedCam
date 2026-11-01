package ca.pwalker.ultratone

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalZeroShutterLag
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Observer
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
    private lateinit var exposureRow: LinearLayout
    private lateinit var exposure: SeekBar
    private lateinit var exposureLabel: TextView
    private var exposureRange = 0..0
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var photoProcessingExecutor: ExecutorService
    private val previewBitmapRetireHandler = Handler(Looper.getMainLooper())
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
    private var liveCameraEffect: LiveCameraEffect? = null
    private var livePortraitProcessor: PortraitBlur.StreamProcessor? = null
    private var lastPreviewFrameNanos = 0L
    private var previewCooldownUntilNanos = 0L
    private var livePreviewAvailable = false
    @Volatile private var focusX = 0.5f
    @Volatile private var focusY = 0.5f
    @Volatile private var styleStrength = 0.75f
    private var currentZoomRatio = 1f
    @Volatile private var activeMode = ReferenceRenderer.Mode.DAY
    @Volatile private var activeFinish = ReferenceRenderer.Finish.COLOUR
    @Volatile private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var nightExtensionActive = false
    private var isBusy = false
    // Keep at most three captures outstanding (one processing and up to two waiting).
    private var pendingPhotoCount = 0
    private val maxPendingPhotos = 3
    private var statusMessageGeneration = 0
    private val modeButtons = mutableMapOf<ReferenceRenderer.Mode, TextView>()
    private val finishButtons = mutableMapOf<ReferenceRenderer.Finish, TextView>()
    private lateinit var flashButton: TextView
    private lateinit var shutterButton: ImageButton
    private lateinit var thumbnailButton: ImageButton
    private lateinit var zoomRow: LensZoomControl
    private lateinit var nightCaptureIndicator: LinearLayout
    private lateinit var bottomPanel: LinearLayout
    private lateinit var modeRow: LinearLayout
    private lateinit var finishRow: LinearLayout
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
        photoProcessingExecutor = Executors.newSingleThreadExecutor()
        buildScreen()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun buildScreen() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply {
            // Show the whole camera frame. FILL_CENTER cropped the tall-screen
            // preview, making 1x look close to 2x while saved JPEGs stayed wide.
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            translationY = -dp(42).toFloat()
            contentDescription = "Live camera viewfinder. Tap to focus; pinch to zoom."
        }
        liveCameraEffect = LiveCameraEffect.attach(previewView)
        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            translationY = -dp(42).toFloat()
            visibility = View.GONE
            contentDescription = "Selected photo preview"
        }
        processedPreviewView = ImageView(this).apply {
            // Keep the processed fallback preview framed like the saved full frame.
            scaleType = ImageView.ScaleType.FIT_CENTER
            translationY = -dp(42).toFloat()
            visibility = View.GONE
            contentDescription = "Live viewfinder with selected photo processing"
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        root.addView(processedPreviewView, FrameLayout.LayoutParams(-1, -1))
        root.addView(imageView, FrameLayout.LayoutParams(-1, -1))
        buildNightCaptureIndicator(root)
        previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateLiveLook() }
        updateLiveLook()

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        flashButton = pill("⚡ Off") { cycleFlash() }
        top.addView(flashButton, LinearLayout.LayoutParams(dp(76), dp(36)))
        status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(0xcc111216.toInt(), 24)
            visibility = View.GONE
            maxLines = 2
        }
        top.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12); marginEnd = dp(12)
        })
        top.addView(pill("↻") { switchCamera() }, LinearLayout.LayoutParams(dp(42), dp(36)))
        overlay.addView(top)
        zoomRow = LensZoomControl(this).apply {
            onZoomChanged = ::selectZoom
            contentDescription = "Camera zoom slider. Yellow marks show lens changes."
        }
        overlay.addView(zoomRow, LinearLayout.LayoutParams(-1, dp(38)).apply {
            topMargin = dp(8)
        })
        overlay.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(0xee111216.toInt(), 24)
            setPadding(dp(8), dp(7), dp(8), dp(6))
        }
        val bottom = bottomPanel
        modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        }
        ReferenceRenderer.Mode.values().forEach { mode ->
            val item = TextView(this).apply {
                text = modeName(mode)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setTextColor(Color.WHITE)
                setOnClickListener { selectMode(mode) }
                contentDescription = "${modeName(mode)} photo mode"
            }
            modeButtons[mode] = item
            modeRow.addView(item)
        }
        bottom.addView(modeRow)

        finishRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(2))
        }
        ReferenceRenderer.Finish.values().forEach { finish ->
            val label = if (finish == ReferenceRenderer.Finish.COLOUR) "Colour" else "B&W"
            val item = pill(label) { selectFinish(finish) }
            finishButtons[finish] = item
            finishRow.addView(item, LinearLayout.LayoutParams(dp(88), dp(30)).apply {
                marginStart = dp(4); marginEnd = dp(4)
            })
        }
        bottom.addView(finishRow)

        strengthRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        strengthRow.addView(TextView(this).apply { text = "UltraTone Style"; textSize = 12f; setTextColor(0xffd4d4d8.toInt()) })
        strength = SeekBar(this).apply {
            max = 100
            progress = 100
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

        exposureRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        exposureLabel = TextView(this).apply {
            text = "EV 0.0"
            textSize = 12f
            setTextColor(0xffd4d4d8.toInt())
        }
        exposureRow.addView(exposureLabel, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
        exposure = SeekBar(this).apply {
            max = 20
            progress = 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser || exposureRange.first > exposureRange.last) return
                    val index = (exposureRange.first + value).coerceAtMost(exposureRange.last)
                    val step = boundCamera?.cameraInfo?.exposureState?.exposureCompensationStep?.toFloat() ?: 0f
                    exposureLabel.text = "EV ${"%.1f".format(index * step)}"
                    boundCamera?.cameraControl?.setExposureCompensationIndex(index)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        exposureRow.addView(exposure, LinearLayout.LayoutParams(0, dp(36), 1f))
        bottom.addView(exposureRow)

        captureControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
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
        captureControls.addView(thumbnailButton, LinearLayout.LayoutParams(dp(42), dp(42)))
        captureControls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        shutterButton = ImageButton(this).apply {
            contentDescription = "Take photo with the selected ${if (activeFinish == ReferenceRenderer.Finish.COLOUR) "colour" else "black and white"} style"
            background = rounded(Color.WHITE, 50)
            setImageDrawable(null)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            foreground = roundedStroke(0x00000000, Color.WHITE, 3, 50)
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.88f).scaleY(0.88f).setDuration(75L).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(130L).start()
                }
                false
            }
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                capture()
            }
        }
        captureControls.addView(shutterButton, LinearLayout.LayoutParams(dp(64), dp(64)))
        captureControls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        captureControls.addView(pill("More") {
            val show = strengthRow.visibility != View.VISIBLE
            strengthRow.visibility = if (show) View.VISIBLE else View.GONE
            if (previewView.visibility == View.VISIBLE) updateExposureControl(show)
        },
            LinearLayout.LayoutParams(dp(54), dp(36)))
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
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            overlay.setPadding(bars.left + dp(12), bars.top + dp(8), bars.right + dp(12), bars.bottom + dp(8))
            insets
        }
        updateModeButtons()
        updateFinishButtons()
        attachPreviewGestures()
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun buildNightCaptureIndicator(root: FrameLayout) {
        nightCaptureIndicator = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(0xdd111216.toInt(), 50)
            visibility = View.GONE
            elevation = dp(6).toFloat()
        }
        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(0xffffc857.toInt())
        }
        nightCaptureIndicator.addView(spinner, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(nightCaptureIndicator, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ).apply { topMargin = -dp(34) })
    }

    private fun setNightCaptureActive(active: Boolean) {
        if (!::nightCaptureIndicator.isInitialized) return
        nightCaptureIndicator.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun setNightCaptureProgress(frame: Int, total: Int) {
        // The centered spinner communicates the multi-frame capture without covering the viewfinder.
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
                focusX = (event.x / view.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                focusY = (event.y / view.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                liveCameraEffect?.setFocus(focusX, focusY)
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
                focusX = (event.x / processedPreviewView.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                focusY = (event.y / processedPreviewView.height.coerceAtLeast(1)).coerceIn(0f, 1f)
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

    @OptIn(ExperimentalZeroShutterLag::class)
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        zoomObserver?.let { boundCamera?.cameraInfo?.zoomState?.removeObserver(it) }
        zoomObserver = null
        val baseSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val extension = when (activeMode) {
            ReferenceRenderer.Mode.DAY -> null
            ReferenceRenderer.Mode.NIGHT -> ExtensionMode.NIGHT
            // Applying OEM bokeh before software depth estimation can erase depth cues
            // and leave a second blurred edge around the selected subject.
            ReferenceRenderer.Mode.CLOSE_UP -> null
        }
        var selector = baseSelector
        var usingExtension = false
        if (extension != null && lensFacing == CameraSelector.LENS_FACING_BACK && flashMode == ImageCapture.FLASH_MODE_OFF) {
            try {
                val manager = extensionsManager
                if (manager != null && manager.isExtensionAvailable(baseSelector, extension)) {
                    selector = manager.getExtensionEnabledCameraSelector(baseSelector, extension)
                    usingExtension = true
                }
            } catch (_: Exception) { }
        }
        try {
            // CameraX extensions support Preview and ImageCapture, not ImageAnalysis.
            // Bind the vendor Night pipeline without analysis so it isn't silently
            // rejected and replaced by our software fallback.
            fun newPreview() = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            fun newCapture(mode: Int) = ImageCapture.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setCaptureMode(mode)
                .setFlashMode(flashMode)
                .build()
            fun newAnalysis() = ImageAnalysis.Builder()
                .setTargetResolution(Size(320, 240))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            provider.unbindAll()
            var camera: Camera? = null
            var capture: ImageCapture? = null
            var analysis: ImageAnalysis? = null
            var live = false
            var extensionBound = false

            if (usingExtension) {
                try {
                    val extensionPreview = newPreview()
                    val extensionCapture = newCapture(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    camera = provider.bindToLifecycle(this, selector, extensionPreview, extensionCapture)
                    capture = extensionCapture
                    extensionBound = true
                } catch (_: Exception) {
                    provider.unbindAll()
                }
            }

            if (camera == null) {
                val basePreview = newPreview()
                // Keep normal captures at max quality. The software night burst
                // uses ZSL when flash is off; CameraX falls back if unsupported.
                val baseCaptureMode = when {
                    activeMode != ReferenceRenderer.Mode.NIGHT -> ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                    flashMode == ImageCapture.FLASH_MODE_OFF -> ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
                    else -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                }
                val baseCapture = newCapture(baseCaptureMode)
                // The GPU shader already grades the live preview on Android 13+.
                // Keep analysis only where it supplies the fallback look or depth map.
                val needsAnalysis = liveCameraEffect == null || activeMode == ReferenceRenderer.Mode.CLOSE_UP
                val baseAnalysis = if (needsAnalysis) newAnalysis().also {
                    it.setAnalyzer(analysisExecutor) { proxy -> processPreviewFrame(proxy) }
                } else null
                try {
                    camera = if (baseAnalysis != null) {
                        provider.bindToLifecycle(this, baseSelector, basePreview, baseCapture, baseAnalysis)
                    } else {
                        provider.bindToLifecycle(this, baseSelector, basePreview, baseCapture)
                    }
                    capture = baseCapture
                    analysis = baseAnalysis
                    live = baseAnalysis != null
                } catch (_: Exception) {
                    baseAnalysis?.clearAnalyzer()
                    provider.unbindAll()
                    camera = provider.bindToLifecycle(this, baseSelector, basePreview, baseCapture)
                    capture = baseCapture
                }
            }

            imageCapture = capture ?: error("Camera did not provide image capture")
            imageAnalysis = analysis
            boundCamera = camera
            configureZoomStops(camera?.cameraInfo)
            nightExtensionActive = activeMode == ReferenceRenderer.Mode.NIGHT && extensionBound
            livePreviewAvailable = live
            val rearCameraHasFlash = try { provider.getCameraInfo(baseSelector).hasFlashUnit() } catch (_: Exception) { false }
            flashButton.visibility = if (lensFacing == CameraSelector.LENS_FACING_BACK && rearCameraHasFlash) View.VISIBLE else View.INVISIBLE
            previewView.visibility = View.VISIBLE
            processedPreviewView.visibility = View.GONE
            imageView.visibility = View.GONE
            zoomRow.visibility = View.VISIBLE
            captureControls.visibility = View.VISIBLE
            editControls.visibility = View.GONE
            processedPreviewView.setImageDrawable(null)
            retirePreviewBitmap(processedPreviewBitmap)
            processedPreviewBitmap = null
            observeZoom()
            updateStatus()
        } catch (e: Exception) {
            message("Could not configure camera: ${e.localizedMessage ?: "unsupported configuration"}")
        }
    }

    private fun observeZoom() {
        val camera = boundCamera ?: return
        updateExposureControl(exposureRow.visibility == View.VISIBLE)
        val observer = Observer<ZoomState> { state ->
            if (state == null) return@Observer
            currentZoomRatio = state.zoomRatio
            zoomRow.setZoomRatio(state.zoomRatio)
            updateLiveLook()
        }
        zoomObserver = observer
        camera.cameraInfo.zoomState.observe(this, observer)
    }

    /** Map the actual logical-camera range and physical-lens ratios to the zoom slider. */
    private fun configureZoomStops(cameraInfo: androidx.camera.core.CameraInfo?) {
        if (cameraInfo == null) {
            zoomRow.setZoomRange(1f, 1f, emptyList())
            return
        }
        val physicalStops = try {
            cameraInfo.physicalCameraInfos.mapNotNull { info ->
                info.intrinsicZoomRatio.takeIf { it.isFinite() && it > 0f }
            }
        } catch (_: Exception) { emptyList() }
        val state = cameraInfo.zoomState.value
        val min = state?.minZoomRatio ?: 1f
        val max = state?.maxZoomRatio ?: min
        zoomRow.setZoomRange(min, max, physicalStops)
        currentZoomRatio = state?.zoomRatio ?: min
        zoomRow.setZoomRatio(currentZoomRatio)
    }

    private fun updateExposureControl(show: Boolean) {
        if (!show) { exposureRow.visibility = View.GONE; return }
        val state = boundCamera?.cameraInfo?.exposureState
        val range = state?.exposureCompensationRange
        if (range == null || range.lower >= range.upper) {
            exposureRow.visibility = View.GONE
            return
        }
        exposureRange = range.lower..range.upper
        exposure.max = range.upper - range.lower
        val current = state.exposureCompensationIndex.coerceIn(range.lower, range.upper)
        exposure.progress = current - range.lower
        val step = state.exposureCompensationStep.toFloat()
        exposureLabel.text = "EV ${"%.1f".format(current * step)}"
        exposureRow.visibility = View.VISIBLE
    }

    private fun processPreviewFrame(proxy: ImageProxy) {
        val mode = activeMode
        val now = System.nanoTime()
        val effect = liveCameraEffect
        if (effect != null && mode != ReferenceRenderer.Mode.CLOSE_UP) {
            proxy.close()
            return
        }
        val minimumInterval = if (effect != null) 1_000_000_000L else if (mode == ReferenceRenderer.Mode.CLOSE_UP) 900_000_000L else 150_000_000L
        if (now - lastPreviewFrameNanos < minimumInterval || now < previewCooldownUntilNanos) {
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
            if (effect != null) {
                val processor = livePortraitProcessor ?: PortraitBlur.StreamProcessor(this).also { livePortraitProcessor = it }
                val guide = processor.createDepthGuide(oriented)
                oriented.recycle()
                if (guide != null) {
                    runOnUiThread {
                        if (!isDestroyed && previewView.visibility == View.VISIBLE && activeMode == ReferenceRenderer.Mode.CLOSE_UP) {
                            effect.updateDepthGuide(guide)
                        } else if (!guide.isRecycled) guide.recycle()
                    }
                }
                return
            }
            val colorized = ReferenceRenderer.render(oriented, styleStrength, mode, currentZoomRatio, activeFinish)
            styled = colorized
            oriented.recycle()
            var output: Bitmap = colorized
            if (mode == ReferenceRenderer.Mode.CLOSE_UP) {
                val segmenter = livePortraitProcessor ?: PortraitBlur.StreamProcessor(this).also { livePortraitProcessor = it }
                val focus = mapFocusToFrame(
                    focusX, focusY, previewView.width, previewView.height, colorized.width, colorized.height,
                    lensFacing == CameraSelector.LENS_FACING_FRONT
                )
                val portrait = segmenter.apply(colorized, focus.first, focus.second)
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
                if (!isDestroyed && previewView.visibility == View.VISIBLE && mode == activeMode) {
                    val old = processedPreviewBitmap
                    processedPreviewBitmap = display
                    processedPreviewView.setImageBitmap(display)
                    processedPreviewView.visibility = View.VISIBLE
                    if (old !== display) retirePreviewBitmap(old)
                } else if (!display.isRecycled) display.recycle()
            }
            if (output === colorized) styled = null
        } catch (_: Exception) {
            styled?.takeIf { !it.isRecycled }?.recycle()
        } finally {
            proxy.close()
            val processingNanos = System.nanoTime() - now
            // If CPU rendering is already missing its target interval, give the
            // camera and UI threads time between frames instead of running the
            // analysis executor continuously at full load.
            if (processingNanos > minimumInterval * 3 / 5) {
                val restNanos = (processingNanos / 2).coerceIn(100_000_000L, 450_000_000L)
                previewCooldownUntilNanos = System.nanoTime() + restNanos
            }
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
        updateLiveLook()
        if (mode != ReferenceRenderer.Mode.CLOSE_UP) {
            analysisExecutor.execute { livePortraitProcessor?.close(); livePortraitProcessor = null }
        }
        updateModeButtons()
        sourceBitmap?.let { renderImported(it) }
        if (previewView.visibility == View.VISIBLE && cameraProvider != null) bindCamera()
    }

    private fun selectFinish(finish: ReferenceRenderer.Finish) {
        activeFinish = finish
        updateLiveLook()
        updateFinishButtons()
        sourceBitmap?.let { renderImported(it) }
        updateStatus()
    }

    private fun updateLiveLook() {
        liveCameraEffect?.setLook(activeMode, activeFinish, styleStrength, currentZoomRatio)
    }

    private fun updateFinishButtons() {
        finishButtons.forEach { (finish, button) ->
            val selected = finish == activeFinish
            button.setTextColor(if (selected) 0xffffd28c.toInt() else 0xfff2f2f4.toInt())
            button.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            button.alpha = if (selected) 1f else 0.70f
        }
    }

    private fun applyControlSizing() {
        if (!::bottomPanel.isInitialized || !::modeRow.isInitialized || !::finishRow.isInitialized || !::captureControls.isInitialized) return
        bottomPanel.setPadding(dp(8), dp(7), dp(8), dp(6))
        modeRow.setPadding(0, dp(2), 0, dp(2))
        modeButtons.values.forEach { it.setPadding(dp(10), dp(4), dp(10), dp(4)) }
        finishRow.setPadding(0, 0, 0, dp(2))
        finishButtons.values.forEach { button ->
            val lp = button.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
            lp.width = dp(88)
            lp.height = dp(30)
            button.layoutParams = lp
        }
        captureControls.setPadding(0, dp(2), 0, 0)
        resizeControl(thumbnailButton, 42, 42)
        resizeControl(shutterButton, 64, 64)
        val more = captureControls.getChildAt(captureControls.childCount - 1)
        resizeControl(more, 54, 36)
    }

    private fun resizeControl(view: View?, widthDp: Int, heightDp: Int) {
        val lp = view?.layoutParams as? LinearLayout.LayoutParams ?: return
        lp.width = dp(widthDp)
        lp.height = dp(heightDp)
        view.layoutParams = lp
    }

    private fun updateModeButtons() {
        applyControlSizing()
        modeButtons.forEach { (mode, button) ->
            val selected = mode == activeMode
            button.setTextColor(if (selected) 0xffffd28c.toInt() else 0xfff2f2f4.toInt())
            button.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            button.alpha = if (selected) 1f else 0.72f
        }
    }

    private fun capture() {
        if (isBusy) return
        if (pendingPhotoCount >= maxPendingPhotos) {
            message("Photo queue is full; wait for a save to finish.")
            return
        }
        val capture = imageCapture ?: run { message("Camera is starting. Try again in a moment."); return }
        isBusy = true
        pendingPhotoCount++
        shutterButton.isEnabled = false
        if (activeMode == ReferenceRenderer.Mode.NIGHT) {
            setNightCaptureActive(true)
            if (!nightExtensionActive && flashMode == ImageCapture.FLASH_MODE_OFF) setNightCaptureProgress(1, 2)
            else nightCaptureCaption.text = if (nightExtensionActive) "Combining frames · hold still" else "Flash capture · hold still"
        } else setStatusMessage("Taking photo…")
        val zoom = currentZoomRatio
        val focusXAtCapture = focusX
        val focusYAtCapture = focusY
        val facingAtCapture = lensFacing
        val modeAtCapture = activeMode
        val strengthAtCapture = styleStrength
        val finishAtCapture = activeFinish
        if (modeAtCapture == ReferenceRenderer.Mode.NIGHT && !nightExtensionActive && flashMode == ImageCapture.FLASH_MODE_OFF) {
            captureNightFrames(capture, mutableListOf(), 0) { frames ->
                if (frames == null) {
                    runOnUiThread {
                        isBusy = false
                        setNightCaptureActive(false)
                        pendingPhotoCount = (pendingPhotoCount - 1).coerceAtLeast(0)
                        refreshShutterAvailability()
                        message("Night capture failed; try again with the phone steadier.")
                        updateStatus()
                    }
                } else {
                    runOnUiThread {
                        isBusy = false
                        setNightCaptureActive(false)
                        refreshShutterAvailability()
                        showQueueStatus()
                    }
                    photoProcessingExecutor.execute {
                        try {
                            val merged = NightBurstMerger.merge(frames)
                            processCapturedBitmap(merged, zoom, focusXAtCapture, focusYAtCapture, facingAtCapture, modeAtCapture, strengthAtCapture, finishAtCapture)
                        } catch (e: Exception) {
                            runOnUiThread {
                                pendingPhotoCount = (pendingPhotoCount - 1).coerceAtLeast(0)
                                refreshShutterAvailability()
                                message("Night photo processing failed: ${e.localizedMessage ?: "unknown error"}")
                                updateStatus()
                            }
                        }
                    }
                }
            }
            return
        }

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureStarted() {
                runOnUiThread {
                    if (modeAtCapture != ReferenceRenderer.Mode.NIGHT) setStatusMessage("Taking photo…")
                }
            }

            override fun onCaptureProcessProgressed(progress: Int) {
                runOnUiThread {
                    if (modeAtCapture != ReferenceRenderer.Mode.NIGHT) setStatusMessage("Taking photo · $progress%")
                }
            }

            override fun onCaptureSuccess(image: ImageProxy) {
                val jpeg = try {
                    check(image.format == android.graphics.ImageFormat.JPEG) { "Camera returned an unsupported image format" }
                    val buffer = image.planes.firstOrNull()?.buffer?.duplicate() ?: error("Camera returned no image data")
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                } catch (e: Exception) {
                    image.close()
                    runOnUiThread {
                        isBusy = false
                        setNightCaptureActive(false)
                        pendingPhotoCount = (pendingPhotoCount - 1).coerceAtLeast(0)
                        refreshShutterAvailability()
                        message("Capture failed: ${e.localizedMessage ?: "camera error"}")
                        updateStatus()
                    }
                    return
                }
                val rotation = image.imageInfo.rotationDegrees
                image.close()
                runOnUiThread {
                    isBusy = false
                    setNightCaptureActive(false)
                    refreshShutterAvailability()
                    if (modeAtCapture != ReferenceRenderer.Mode.NIGHT) {
                        setStatusMessage("Processing photo…")
                        showQueueStatus()
                    }
                }
                photoProcessingExecutor.execute {
                    try {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
                        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
                            decodeOptions(bounds.outWidth, bounds.outHeight)) ?: error("Could not decode captured image")
                        processCapturedBitmap(rotate(bitmap, rotation), zoom, focusXAtCapture, focusYAtCapture,
                            facingAtCapture, modeAtCapture, strengthAtCapture, finishAtCapture)
                    } catch (e: Exception) {
                        runOnUiThread {
                            pendingPhotoCount = (pendingPhotoCount - 1).coerceAtLeast(0)
                            refreshShutterAvailability()
                            message("Photo processing failed: ${e.localizedMessage ?: "unknown error"}")
                            updateStatus()
                        }
                    }
                }
            }

            override fun onError(error: ImageCaptureException) {
                runOnUiThread {
                    isBusy = false
                    setNightCaptureActive(false)
                    pendingPhotoCount = (pendingPhotoCount - 1).coerceAtLeast(0)
                    refreshShutterAvailability()
                    message("Capture failed: ${error.message ?: "camera error"}")
                    updateStatus()
                }
            }
        })
    }

    private fun captureNightFrames(
        capture: ImageCapture,
        frames: MutableList<NightBurstMerger.Frame>,
        index: Int,
        onComplete: (List<NightBurstMerger.Frame>?) -> Unit
    ) {
        if (index >= 2) {
            onComplete(frames.toList())
            return
        }
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    var nextFrame: NightBurstMerger.Frame? = null
                    try {
                        check(image.format == android.graphics.ImageFormat.JPEG) { "Night capture returned an unsupported image format" }
                        val buffer = image.planes.firstOrNull()?.buffer?.duplicate()
                            ?: error("Night capture returned no image data")
                        val jpeg = ByteArray(buffer.remaining())
                        buffer.get(jpeg)
                        nextFrame = NightBurstMerger.Frame(jpeg, image.imageInfo.rotationDegrees)
                    } catch (_: Exception) { }
                    image.close()
                    if (nextFrame == null) {
                        onComplete(null)
                        return
                    }
                    frames += nextFrame
                    if (index + 1 < 2) {
                        runOnUiThread { setNightCaptureProgress(index + 2, 2) }
                    }
                    captureNightFrames(capture, frames, index + 1, onComplete)
                }
                override fun onError(error: ImageCaptureException) { onComplete(null) }
            })
    }

    private fun processCapturedBitmap(
        upright: Bitmap,
        zoom: Float,
        focusXAtCapture: Float,
        focusYAtCapture: Float,
        facingAtCapture: Int,
        modeAtCapture: ReferenceRenderer.Mode,
        strengthAtCapture: Float,
        finishAtCapture: ReferenceRenderer.Finish
    ) {
        var style: Bitmap? = null
        var final: Bitmap? = null
        try {
            val styled = ReferenceRenderer.render(upright, strengthAtCapture, modeAtCapture, zoom, finishAtCapture)
            style = styled
            val focus = mapFocusToFrame(
                focusXAtCapture, focusYAtCapture, previewView.width, previewView.height,
                upright.width, upright.height, facingAtCapture == CameraSelector.LENS_FACING_FRONT
            )
            val portrait = if (modeAtCapture == ReferenceRenderer.Mode.CLOSE_UP) (try {
                PortraitBlur.apply(this@MainActivity, styled, focus.first, focus.second)
            } catch (_: Exception) { null }) else null
            val rendered = portrait?.bitmap ?: styled
            final = rendered
            runOnUiThread { setStatusMessage("Saving photo…") }
            val uri = saveBitmap(rendered)
            lastPhotoUri = uri
            val savedPortrait = portrait
            if (rendered !== styled) styled.recycle()
            runOnUiThread {
                pendingPhotoCount = (pendingPhotoCount - 1).coerceAtLeast(0)
                refreshShutterAvailability()
                updateThumbnail(rendered)
                message(if (modeAtCapture == ReferenceRenderer.Mode.CLOSE_UP && savedPortrait?.usedDepth != true && savedPortrait?.subjectDetected != true)
                    "Saved; close-up blur could not detect usable depth or a person" else if (pendingPhotoCount > 0)
                    "Photo saved; processing the next photo" else "Photo saved to Pictures/UltraTone")
                rendered.recycle()
                updateStatus()
            }
        } catch (e: Exception) {
            final?.takeIf { it !== style }?.recycle()
            style?.recycle()
            runOnUiThread {
                pendingPhotoCount = (pendingPhotoCount - 1).coerceAtLeast(0)
                refreshShutterAvailability()
                message("Photo processing failed: ${e.localizedMessage ?: "unknown error"}")
                updateStatus()
            }
        } finally {
            upright.recycle()
        }
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
            if (stream == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 93, stream)) error("Photo encoding failed")
        }
        return uri
    }

    private fun updateThumbnail(bitmap: Bitmap) {
        val thumb = Bitmap.createScaledBitmap(bitmap, 80, 80, true)
        thumbnailButton.setImageBitmap(thumb)
    }

    private fun loadUri(uri: Uri) {
        setStatusMessage("Opening photo…")
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
                    finishRow.visibility = View.VISIBLE
                    captureControls.visibility = View.GONE
                    editControls.visibility = View.VISIBLE
                    strengthRow.visibility = View.VISIBLE
                    exposureRow.visibility = View.GONE
                    renderImported(upright)
                }
            } catch (e: Exception) { runOnUiThread { message("Could not open photo: ${e.localizedMessage ?: "unsupported file"}") } }
        }
    }

    private fun renderImported(source: Bitmap) {
        val mode = activeMode
        cameraExecutor.execute {
            try {
                val styled = ReferenceRenderer.render(source, styleStrength, mode, finish = activeFinish)
                val focus = mapFocusToFrame(
                    focusX, focusY, processedPreviewView.width, processedPreviewView.height,
                    source.width, source.height, false
                )
                val portrait = if (mode == ReferenceRenderer.Mode.CLOSE_UP) (try {
                    PortraitBlur.apply(this@MainActivity, styled, focus.first, focus.second)
                } catch (_: Exception) { null }) else null
                val output = portrait?.bitmap ?: styled
                if (output !== styled) styled.recycle()
                runOnUiThread {
                    renderedBitmap?.recycle()
                    renderedBitmap = output
                    imageView.setImageBitmap(output)
                    status.visibility = View.GONE
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
        processedPreviewView.setImageDrawable(null)
        processedPreviewView.visibility = View.GONE
        retirePreviewBitmap(processedPreviewBitmap)
        processedPreviewBitmap = null
        previewView.visibility = View.VISIBLE
        zoomRow.visibility = View.VISIBLE
        captureControls.visibility = View.VISIBLE
        editControls.visibility = View.GONE
        if (cameraProvider != null) bindCamera()
    }

    /** Let the render thread finish any queued draw before releasing an old preview bitmap. */
    private fun retirePreviewBitmap(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        previewBitmapRetireHandler.postDelayed({
            if (bitmap !== processedPreviewBitmap && !bitmap.isRecycled) bitmap.recycle()
        }, 1_200L)
    }

    private fun decodeOptions(width: Int, height: Int): BitmapFactory.Options {
        var sample = 1
        while (width / sample.toLong() * (height / sample.toLong()) > 16_000_000L) sample *= 2
        return BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    }

    /** Map a tap through PreviewView's FIT_CENTER letterboxing onto the full captured frame. */
    private fun mapFocusToFrame(
        x: Float, y: Float, viewWidth: Int, viewHeight: Int,
        frameWidth: Int, frameHeight: Int, mirrored: Boolean
    ): Pair<Float, Float> {
        val scale = minOf(viewWidth.coerceAtLeast(1) / frameWidth.coerceAtLeast(1).toFloat(),
            viewHeight.coerceAtLeast(1) / frameHeight.coerceAtLeast(1).toFloat())
        val renderedWidth = frameWidth * scale
        val renderedHeight = frameHeight * scale
        val offsetX = (viewWidth - renderedWidth) / 2f
        val offsetY = (viewHeight - renderedHeight) / 2f
        var mappedX = ((x * viewWidth - offsetX) / renderedWidth.coerceAtLeast(1f)).coerceIn(0f, 1f)
        var mappedY = ((y * viewHeight - offsetY) / renderedHeight.coerceAtLeast(1f)).coerceIn(0f, 1f)
        if (mirrored) mappedX = 1f - mappedX
        return mappedX to mappedY
    }

    private fun orient(bitmap: Bitmap, uri: Uri): Bitmap = try {
        contentResolver.openInputStream(uri).use { rotate(bitmap, ExifInterface(it!!).rotationDegrees) }
    } catch (_: Exception) { bitmap }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun setStatusMessage(text: String, hideAfterMs: Long? = null) {
        val generation = ++statusMessageGeneration
        status.text = text
        status.visibility = View.VISIBLE
        if (hideAfterMs != null) status.postDelayed({
            if (generation == statusMessageGeneration && !isBusy && pendingPhotoCount == 0) status.visibility = View.GONE
        }, hideAfterMs)
    }

    // The top pill is reserved for short-lived capture and save feedback.
    // Mode and finish selection are shown by their controls, so there is no idle status text.
    private fun updateStatus() = Unit

    private fun showQueueStatus() {
        if (pendingPhotoCount > 0) setStatusMessage("Processing photos · $pendingPhotoCount queued")
    }

    private fun refreshShutterAvailability() {
        if (!::shutterButton.isInitialized) return
        shutterButton.isEnabled = imageCapture != null && !isBusy && pendingPhotoCount < maxPendingPhotos
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
        ReferenceRenderer.Mode.CLOSE_UP -> "Close-up"
    }
    private fun message(text: String) = setStatusMessage(text, 2_800L)

    override fun onDestroy() {
        super.onDestroy()
        zoomObserver?.let { boundCamera?.cameraInfo?.zoomState?.removeObserver(it) }
        liveCameraEffect?.close()
        liveCameraEffect = null
        cameraExecutor.shutdown()
        photoProcessingExecutor.shutdown()
        analysisExecutor.execute { livePortraitProcessor?.close(); livePortraitProcessor = null }
        analysisExecutor.shutdown()
        sourceBitmap?.recycle()
        renderedBitmap?.recycle()
        processedPreviewView.setImageDrawable(null)
        retirePreviewBitmap(processedPreviewBitmap)
        processedPreviewBitmap = null
    }
}
