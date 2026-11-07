package ca.pwalker.ultratone

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.GestureDetector
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
import androidx.camera.core.CameraInfo
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
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
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
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    private companion object {
        const val MAX_LONG_EXPOSURE_MILLIS = 30_000L
        const val LONG_EXPOSURE_TICK_MILLIS = 100L
    }

    private lateinit var previewView: PreviewView
    private lateinit var processedPreviewView: ImageView
    private lateinit var imageView: ImageView
    private lateinit var status: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var longExposureHint: TextView
    private lateinit var strength: SeekBar
    private lateinit var strengthRow: LinearLayout
    private lateinit var exposureRow: LinearLayout
    private lateinit var exposure: SeekBar
    private lateinit var exposureLabel: TextView
    private lateinit var detailMergeButton: TextView
    private lateinit var bokehButton: TextView
    private lateinit var nightEnhancementButton: TextView
    private lateinit var creativeEffectsRow: LinearLayout
    private val creativeEffectButtons = mutableMapOf<String, TextView>()
    private lateinit var focusIndicator: View
    private var exposureRange = 0..0
    @Volatile private var multiFrameDetailEnabled = false
    @Volatile private var bokehLevel = 0
    @Volatile private var nightEnhancementEnabled = false
    @Volatile private var infinityFocusEnabled = false
    @Volatile private var glowEnabled = false
    private lateinit var moreMenuPanel: LinearLayout
    @Volatile private var vignetteEnabled = false
    private var infinityFocusSupported = false
    private var manualLensFocusSupported = false
    private var landscapeFocusDistance: Float? = null
    private var moreMenuExpanded = false
    private val focusIndicatorHandler = Handler(Looper.getMainLooper())
    private val focusMotion by lazy { TapFocusMotion(getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager) }
    private val hideFocusIndicator = Runnable {
        focusMotion.stop()
        if (::focusIndicator.isInitialized) {
            focusIndicator.animate().alpha(0f).setDuration(180L).withEndAction { focusIndicator.visibility = View.GONE }.start()
        }
    }
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var photoProcessingExecutor: ExecutorService
    private val previewBitmapRetireHandler = Handler(Looper.getMainLooper())
    private val longExposureHandler = Handler(Looper.getMainLooper())
    private var imageCapture: ImageCapture? = null
    private var previewUseCase: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    @Volatile private var sensorDepth: SensorDepth? = null
    private val rejectedDepthCameras = mutableSetOf<String>()
    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var boundCamera: Camera? = null
    private var zoomObserver: Observer<ZoomState>? = null
    private var sourceBitmap: Bitmap? = null
    private var importedExif = emptyMap<String, String>()
    private var renderedBitmap: Bitmap? = null
    private var lastPhotoUri: Uri? = null
    private var processedPreviewBitmap: Bitmap? = null
    private var liveCameraEffect: LiveCameraEffect? = null
    private var livePortraitProcessor: PortraitBlur.StreamProcessor? = null
    private var lastPreviewFrameNanos = 0L
    private var lastLiveDepthGuideNanos = 0L
    private var lastLiveDepthFocusX = Float.NaN
    private var lastLiveDepthFocusY = Float.NaN
    private val liveDepthRefreshIntervalNanos = 125_000_000L
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
    private var cameraRebindPending = false
    // Limit queued JPEGs while one full-resolution image is being processed.
    private var pendingPhotoCount = 0
    private val maxPendingPhotos = 5
    private var statusMessageGeneration = 0
    private val modeButtons = mutableMapOf<ReferenceRenderer.Mode, TextView>()
    private val finishButtons = mutableMapOf<ReferenceRenderer.Finish, TextView>()
    private lateinit var flashButton: TextView
    private lateinit var infinityFocusButton: TextView
    private lateinit var shutterButton: ImageButton
    private lateinit var thumbnailButton: ImageButton
    private lateinit var zoomRow: LensZoomControl
    private lateinit var nightCaptureIndicator: LinearLayout
    private lateinit var bottomPanel: LinearLayout
    private lateinit var modeRow: LinearLayout
    private var landscapeModeScroll: android.widget.ScrollView? = null
    private lateinit var finishRow: LinearLayout
    private lateinit var captureControls: LinearLayout
    private lateinit var editControls: LinearLayout
    private lateinit var controlOverlay: FrameLayout
    private lateinit var photoFeedbackLayer: FrameLayout
    private lateinit var landscapeActions: LinearLayout
    private lateinit var moreButton: TextView
    private var landscapeControlsActive: Boolean? = null
    private data class LongExposureCaptureContext(
        val zoom: Float,
        val focusX: Float,
        val focusY: Float,
        val facing: Int,
        val strength: Float,
        val finish: ReferenceRenderer.Finish,
        val infinityFocus: Boolean,
        val effects: ReferenceRenderer.Effects,
        val previewSize: Size
    )
    private var longExposureHoldActive = false
    private var longExposureFrameCaptureInFlight = false
    private var longExposureFrameProcessing = false
    private var longExposureFinalizing = false
    private var longExposureStartedAtMs = 0L
    private var previousLongFrameTimestamp = 0L
    private var waitingLongFrame: LongExposureMerger.Frame? = null
    private var longExposureExif = emptyMap<String, String>()
    private var longExposureFrameCount = 0
    private var longExposureAccumulator: LongExposureMerger? = null
    private var longExposureContext: LongExposureCaptureContext? = null
    private val longExposureTicker = object : Runnable {
        override fun run() {
            if (!longExposureHoldActive) return
            val elapsed = SystemClock.elapsedRealtime() - longExposureStartedAtMs
            if (elapsed >= MAX_LONG_EXPOSURE_MILLIS) {
                finishLongExposureHold(limitReached = true)
                return
            }
            updateLongExposureHint()
            longExposureHandler.postDelayed(this, LONG_EXPOSURE_TICK_MILLIS)
        }
    }

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else message("Camera permission is needed to take photos.")
    }
    private val choosePhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        analysisExecutor = Executors.newSingleThreadExecutor()
        photoProcessingExecutor = Executors.newSingleThreadExecutor()
        multiFrameDetailEnabled = getPreferences(MODE_PRIVATE).getBoolean("multi_frame_detail_enabled", false)
        bokehLevel = getPreferences(MODE_PRIVATE).getInt("bokeh_level", 0).coerceIn(0, 2)
        nightEnhancementEnabled = getPreferences(MODE_PRIVATE).getBoolean("night_enhancement_enabled", false)
        infinityFocusEnabled = getPreferences(MODE_PRIVATE).getBoolean("infinity_focus_enabled", false)
        glowEnabled = getPreferences(MODE_PRIVATE).getBoolean("effect_glow", false)
        vignetteEnabled = getPreferences(MODE_PRIVATE).getBoolean("effect_vignette", false)
        buildScreen()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else requestCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::controlOverlay.isInitialized) {
            applyCameraControlLayout(newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            controlOverlay.requestLayout()
            updateLiveLook()
        }
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
        focusIndicator = View(this).apply {
            background = roundedStroke(0x00000000, 0xffffd052.toInt(), 2, 50)
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            elevation = dp(4).toFloat()
            contentDescription = "Selected focus point"
        }
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
        photoFeedbackLayer = FrameLayout(this).apply { isClickable = false; isFocusable = false }
        root.addView(photoFeedbackLayer, FrameLayout.LayoutParams(-1, -1))
        root.addView(focusIndicator, FrameLayout.LayoutParams(dp(56), dp(56)))
        buildNightCaptureIndicator(root)
        previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateLiveLook() }
        updateLiveLook()

        val overlay = FrameLayout(this)
        controlOverlay = overlay
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        flashButton = pill("⚡ Off") { cycleFlash() }
        topBar.addView(flashButton, LinearLayout.LayoutParams(dp(76), dp(36)))
        infinityFocusButton = pill("AF") { toggleInfinityFocus() }.apply {
            contentDescription = "Switch to infinity focus with blur disabled"
            isEnabled = false
            alpha = 0.5f
        }
        topBar.addView(infinityFocusButton, LinearLayout.LayoutParams(dp(44), dp(36)).apply { marginStart = dp(6) })
        status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(0xcc111216.toInt(), 24)
            visibility = View.GONE
            maxLines = 2
        }
        topBar.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12); marginEnd = dp(12)
        })
        topBar.addView(pill("↻") { switchCamera() }, LinearLayout.LayoutParams(dp(42), dp(36)))
        overlay.addView(topBar, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        zoomRow = LensZoomControl(this).apply {
            onZoomChanged = ::selectZoom
            contentDescription = "Camera zoom slider. Yellow marks show lens changes."
        }
        overlay.addView(zoomRow, FrameLayout.LayoutParams(-1, dp(38), Gravity.TOP))

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

        detailMergeButton = pill("") { toggleMultiFrameDetail() }.apply {
            visibility = View.GONE
            textSize = 12f
            maxLines = 1
            contentDescription = "Photo stack, two-frame deblur. Off"
        }
        updateDetailMergeButton()

        bokehButton = pill("") { cycleBokeh() }.apply {
            textSize = 12f
            maxLines = 1
            contentDescription = "Bokeh strength, Normal"
        }
        updateBokehButton()

        nightEnhancementButton = pill("") { toggleNightEnhancement() }.apply {
            visibility = View.GONE
            textSize = 12f
            maxLines = 1
            contentDescription = "Night-only low-light enhancement. Off"
        }
        updateNightEnhancementButton()

        creativeEffectsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        listOf("glow", "vignette").forEach { effect ->
            val button = pill(effectLabel(effect)) { toggleCreativeEffect(effect) }.apply {
                textSize = 11f
                maxLines = 1
                contentDescription = "${effectLabel(effect)} effect, off"
            }
            creativeEffectButtons[effect] = button
            creativeEffectsRow.addView(button, LinearLayout.LayoutParams(0, dp(30), 1f).apply {
                marginStart = dp(2); marginEnd = dp(2)
            })
        }
        updateCreativeEffectButtons()

        captureControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        thumbnailButton = ImageButton(this).apply {
            contentDescription = "View UltraTone photos"
            background = rounded(0xff33353a.toInt(), 12)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(android.R.drawable.ic_menu_gallery)
            setOnClickListener { startActivity(Intent(this@MainActivity, AlbumActivity::class.java)) }
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
                if (activeMode == ReferenceRenderer.Mode.LONG_EXPOSURE || longExposureHoldActive) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            view.animate().scaleX(0.88f).scaleY(0.88f).setDuration(75L).start()
                            performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                            startLongExposureHold()
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            view.animate().scaleX(1f).scaleY(1f).setDuration(130L).start()
                            finishLongExposureHold()
                        }
                    }
                    true
                } else {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.88f).scaleY(0.88f).setDuration(75L).start()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(130L).start()
                    }
                    false
                }
            }
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                capture()
            }
        }
        captureControls.addView(shutterButton, LinearLayout.LayoutParams(dp(64), dp(64)))
        captureControls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        moreButton = pill("More") {
            moreMenuExpanded = !moreMenuExpanded
            updateMoreMenu()
        }
        captureControls.addView(moreButton, LinearLayout.LayoutParams(dp(54), dp(36)))
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
        moreMenuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(0xee111216.toInt(), 20)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }
        moreMenuPanel.addView(strengthRow)
        moreMenuPanel.addView(exposureRow)
        moreMenuPanel.addView(detailMergeButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(32)
        ).apply { topMargin = dp(2); bottomMargin = dp(2) })
        moreMenuPanel.addView(bokehButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(32)
        ).apply { topMargin = dp(2); bottomMargin = dp(2) })
        moreMenuPanel.addView(nightEnhancementButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(32)
        ).apply { topMargin = dp(2); bottomMargin = dp(2) })
        moreMenuPanel.addView(creativeEffectsRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(32)
        ).apply { topMargin = dp(2); bottomMargin = dp(2) })
        overlay.addView(bottom, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        bottomPanel.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            if (resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                val lp = moreMenuPanel.layoutParams as? FrameLayout.LayoutParams
                val margin = bottom - top + dp(24)
                if (lp != null && lp.bottomMargin != margin) {
                    lp.bottomMargin = margin
                    moreMenuPanel.layoutParams = lp
                }
            }
        }
        overlay.addView(moreMenuPanel)
        longExposureHint = TextView(this).apply {
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(0xcfffffff.toInt())
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            elevation = dp(7).toFloat()
        }
        overlay.addView(longExposureHint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        landscapeActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        overlay.addView(landscapeActions, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.BOTTOM
        ).apply { marginStart = dp(66); bottomMargin = dp(12) })
        applyCameraControlLayout(resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            overlay.setPadding(bars.left, bars.top + dp(8), bars.right, bars.bottom + dp(8))
            insets
        }
        updateModeButtons()
        updateFinishButtons()
        attachPreviewGestures()
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun applyCameraControlLayout(landscape: Boolean) {
        if (!::zoomRow.isInitialized || !::bottomPanel.isInitialized || !::landscapeActions.isInitialized) return
        zoomRow.setVertical(landscape)
        landscapeActions.visibility = if (landscape) View.VISIBLE else View.GONE
        zoomRow.layoutParams = if (landscape) {
            FrameLayout.LayoutParams(dp(48), dp(250), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                marginStart = dp(12)
            }
        } else {
            FrameLayout.LayoutParams(-1, dp(38), Gravity.TOP).apply { topMargin = dp(52) }
        }
        bottomPanel.layoutParams = if (landscape) {
            FrameLayout.LayoutParams(dp(148), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = dp(12)
            }
        } else {
            FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(12)
            }
        }
        if (::moreMenuPanel.isInitialized) {
            moreMenuPanel.layoutParams = if (landscape) {
                FrameLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL).apply {
                    marginStart = dp(72)
                    bottomMargin = dp(20)
                }
            } else {
                FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    leftMargin = dp(12)
                    rightMargin = dp(12)
                    bottomMargin = bottomPanel.height.takeIf { landscapeControlsActive == false && it > 0 }?.plus(dp(24)) ?: dp(164)
                }
            }
        }
        applyStatusPlacement(landscape)
        updateLongExposureHint()
        applyControlSizing(landscape)
    }

    private fun applyStatusPlacement(landscape: Boolean) {
        if (!::status.isInitialized || !::topBar.isInitialized || !::controlOverlay.isInitialized) return
        (status.parent as? ViewGroup)?.removeView(status)
        if (landscape) {
            controlOverlay.addView(status, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(12) })
        } else {
            topBar.addView(status, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(12); marginEnd = dp(12) })
        }
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
        val zoomDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val camera = boundCamera ?: return false
                val state = camera.cameraInfo.zoomState.value ?: return false
                camera.cameraControl.setZoomRatio((currentZoomRatio * detector.scaleFactor).coerceIn(state.minZoomRatio, state.maxZoomRatio))
                return true
            }
        })

        fun attachTapFocus(view: View) {
            val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true
                override fun onSingleTapUp(event: MotionEvent): Boolean {
                    focusOnPreview(view, event.x, event.y)
                    return true
                }
            })
            view.setOnTouchListener { _, event ->
                zoomDetector.onTouchEvent(event)
                tapDetector.onTouchEvent(event)
                true
            }
        }
        attachTapFocus(previewView)
        attachTapFocus(processedPreviewView)
    }

    private fun focusOnPreview(source: View, x: Float, y: Float) {
        if (infinityFocusEnabled) return
        if (activeMode == ReferenceRenderer.Mode.LANDSCAPE && landscapeFocusDistance != null) applyManualLensFocus(null)
        val sourceLocation = IntArray(2)
        val previewLocation = IntArray(2)
        source.getLocationOnScreen(sourceLocation)
        previewView.getLocationOnScreen(previewLocation)
        val previewX = sourceLocation[0] - previewLocation[0] + x
        val previewY = sourceLocation[1] - previewLocation[1] + y
        if (previewX !in 0f..previewView.width.toFloat() || previewY !in 0f..previewView.height.toFloat()) return
        val sourceAspect = if (source === processedPreviewView) {
            processedPreviewBitmap?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: previewContentAspectRatio()
        } else previewContentAspectRatio()
        val contentRect = fitCenterContentRect(previewView.width, previewView.height, sourceAspect) ?: return
        if (!contentRect.contains(previewX, previewY)) return

        focusX = (previewX / previewView.width.coerceAtLeast(1)).coerceIn(0f, 1f)
        focusY = (previewY / previewView.height.coerceAtLeast(1)).coerceIn(0f, 1f)
        showFocusIndicator(previewX, previewY)
        watchFocusMotion(contentRect, previewX, previewY)

        try {
            val point = previewView.meteringPointFactory.createPoint(previewX, previewY, 0.12f)
            val action = FocusMeteringAction.Builder(point)
                .setAutoCancelDuration(5, TimeUnit.SECONDS)
                .build()
            boundCamera?.cameraControl?.startFocusAndMetering(action)
        } catch (_: Exception) { }
        source.performClick()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun watchFocusMotion(rect: RectF, x: Float, y: Float) {
        val camera = boundCamera ?: return
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return
        val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull()?.takeIf { it > 0f } ?: return
        val landscape = rect.width() >= rect.height()
        val longSide = maxOf(sensor.width, sensor.height)
        val shortSide = minOf(sensor.width, sensor.height)
        val halfWidth = minOf(if (landscape) longSide else shortSide,
            (if (landscape) shortSide else longSide) * rect.width() / rect.height()) /
            (2f * focal * currentZoomRatio)
        focusMotion.start((x - rect.left) / rect.width(), (y - rect.top) / rect.height(),
            halfWidth, halfWidth * rect.height() / rect.width(), previewView.display?.rotation ?: 0,
            lensFacing == CameraSelector.LENS_FACING_FRONT, { nx, ny ->
                val px = rect.left + nx * rect.width()
                val py = rect.top + ny * rect.height()
                focusIndicator.translationX += px - focusX * previewView.width
                focusIndicator.translationY += py - focusY * previewView.height
                focusX = px / previewView.width
                focusY = py / previewView.height
            }) {
            camera.cameraControl.cancelFocusAndMetering()
            focusX = 0.5f
            focusY = 0.5f
            focusIndicatorHandler.removeCallbacks(hideFocusIndicator)
            hideFocusIndicator.run()
        }
    }

    private fun previewContentAspectRatio(): Float {
        val info = previewUseCase?.resolutionInfo
        val aspect = if (info != null) {
            val width = info.resolution.width.toFloat().coerceAtLeast(1f)
            val height = info.resolution.height.toFloat().coerceAtLeast(1f)
            if (info.rotationDegrees == 90 || info.rotationDegrees == 270) height / width else width / height
        } else if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            4f / 3f
        } else 3f / 4f
        // CameraX can report the stream in sensor orientation even after the
        // display has rotated. Keep the fitted image shape consistent with the
        // visible preview orientation used by PreviewView.
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return if (landscape && aspect < 1f) 1f / aspect
        else if (!landscape && aspect > 1f) 1f / aspect
        else aspect
    }

    private fun fitCenterContentRect(width: Int, height: Int, aspect: Float): RectF? {
        if (width <= 0 || height <= 0 || aspect <= 0f) return null
        val viewAspect = width.toFloat() / height
        return if (viewAspect > aspect) {
            val contentWidth = height * aspect
            val left = (width - contentWidth) / 2f
            RectF(left, 0f, left + contentWidth, height.toFloat())
        } else {
            val contentHeight = width / aspect
            val top = (height - contentHeight) / 2f
            RectF(0f, top, width.toFloat(), top + contentHeight)
        }
    }

    private fun toggleInfinityFocus() {
        if (!infinityFocusSupported) {
            message("Infinity focus is not supported by this camera.")
            return
        }
        if (isBusy) return
        infinityFocusEnabled = !infinityFocusEnabled
        getPreferences(MODE_PRIVATE).edit().putBoolean("infinity_focus_enabled", infinityFocusEnabled).apply()
        focusIndicator.visibility = View.GONE
        updateInfinityFocusButton()
        updateLiveLook()
        if (activeMode == ReferenceRenderer.Mode.CLOSE_UP) bindCamera()
        else applyInfinityFocus(infinityFocusEnabled)
    }

    private fun updateInfinityFocusSupport(cameraInfo: CameraInfo?) {
        infinityFocusSupported = false
        manualLensFocusSupported = false
        landscapeFocusDistance = null
        try {
            val info = cameraInfo ?: throw IllegalStateException("No active camera")
            val camera2 = Camera2CameraInfo.from(info)
            val minimumDistance = camera2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            val capabilities = camera2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            manualLensFocusSupported = minimumDistance != null && minimumDistance > 0f &&
                capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
            // A fixed-focus lens is already at its fixed focal plane and has no AF to override.
            infinityFocusSupported = minimumDistance == 0f || manualLensFocusSupported

            if (manualLensFocusSupported) {
                val focalLength = camera2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.minOrNull()
                val aperture = camera2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    ?.minOrNull()
                val sensorSize = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                if (focalLength != null && aperture != null && sensorSize != null && minimumDistance != null) {
                    val sensorDiagonal = sqrt(sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height)
                    val circleOfConfusion = sensorDiagonal / 1500f
                    val hyperfocalMm = focalLength * focalLength / (aperture * circleOfConfusion) + focalLength
                    if (hyperfocalMm > 0f) {
                        landscapeFocusDistance = (1000f / hyperfocalMm).coerceIn(0f, minimumDistance)
                    }
                }
            }
        } catch (_: Exception) { }
        if (!infinityFocusSupported && infinityFocusEnabled) {
            infinityFocusEnabled = false
            getPreferences(MODE_PRIVATE).edit().putBoolean("infinity_focus_enabled", false).apply()
        }
        updateInfinityFocusButton()
    }

    private fun updateInfinityFocusButton() {
        if (!::infinityFocusButton.isInitialized) return
        infinityFocusButton.text = if (infinityFocusEnabled) "∞" else "AF"
        infinityFocusButton.isEnabled = infinityFocusSupported
        infinityFocusButton.alpha = if (infinityFocusSupported) 1f else 0.45f
        infinityFocusButton.setTextColor(if (infinityFocusEnabled) 0xffffd28c.toInt() else 0xfff2f2f4.toInt())
        infinityFocusButton.contentDescription = if (infinityFocusEnabled)
            "Infinity focus enabled; tap to restore autofocus. Bokeh blur is disabled."
        else "Autofocus enabled; tap to lock focus at infinity and disable blur."
    }

    private fun applySelectedFocus() {
        when {
            infinityFocusEnabled -> applyManualLensFocus(0f)
            activeMode == ReferenceRenderer.Mode.LANDSCAPE && landscapeFocusDistance != null ->
                applyManualLensFocus(landscapeFocusDistance)
            else -> applyManualLensFocus(null)
        }
    }

    private fun applyInfinityFocus(enabled: Boolean) {
        val distance = if (enabled) 0f else if (activeMode == ReferenceRenderer.Mode.LANDSCAPE) landscapeFocusDistance else null
        applyManualLensFocus(distance)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyManualLensFocus(distanceDiopters: Float?) {
        val camera = boundCamera ?: return
        if (!manualLensFocusSupported) return
        try {
            val control = Camera2CameraControl.from(camera.cameraControl)
            val future = if (distanceDiopters != null) {
                val options = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, distanceDiopters)
                    .build()
                control.setCaptureRequestOptions(options)
            } else control.clearCaptureRequestOptions()
            future.addListener({
                try { future.get() } catch (_: Exception) {
                    if (distanceDiopters == 0f && infinityFocusEnabled) runOnUiThread {
                        infinityFocusEnabled = false
                        getPreferences(MODE_PRIVATE).edit().putBoolean("infinity_focus_enabled", false).apply()
                        updateInfinityFocusButton()
                        updateLiveLook()
                        applySelectedFocus()
                        message("Infinity focus could not be applied by this camera.")
                    }
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (_: Exception) {
            if (distanceDiopters == 0f && infinityFocusEnabled) {
                infinityFocusEnabled = false
                getPreferences(MODE_PRIVATE).edit().putBoolean("infinity_focus_enabled", false).apply()
                updateInfinityFocusButton()
                updateLiveLook()
                applySelectedFocus()
                message("Infinity focus could not be applied by this camera.")
            }
        }
    }

    private fun showFocusIndicator(x: Float, y: Float) {
        focusIndicatorHandler.removeCallbacks(hideFocusIndicator)
        val size = dp(56).toFloat()
        val previewLocation = IntArray(2)
        val rootLocation = IntArray(2)
        previewView.getLocationOnScreen(previewLocation)
        (focusIndicator.parent as? View)?.getLocationOnScreen(rootLocation)
        focusIndicator.translationX = previewLocation[0] - rootLocation[0] + x - size / 2f
        focusIndicator.translationY = previewLocation[1] - rootLocation[1] + y - size / 2f
        focusIndicator.alpha = 0f
        focusIndicator.scaleX = 0.82f
        focusIndicator.scaleY = 0.82f
        focusIndicator.visibility = View.VISIBLE
        focusIndicator.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140L).start()
        focusIndicatorHandler.postDelayed(hideFocusIndicator, 5_000L)
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

    @OptIn(ExperimentalZeroShutterLag::class, ExperimentalCamera2Interop::class)
    private fun bindCamera() {
        focusMotion.stop()
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
            ReferenceRenderer.Mode.SUNSET, ReferenceRenderer.Mode.LONG_EXPOSURE, ReferenceRenderer.Mode.LANDSCAPE -> null
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
            fun newPreview(): Preview {
                val builder = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
                if (activeMode == ReferenceRenderer.Mode.CLOSE_UP && !infinityFocusEnabled) {
                    val id = Camera2CameraInfo.from(provider.getCameraInfo(baseSelector)).cameraId
                    if (id !in rejectedDepthCameras) {
                        runCatching {
                            val info = getSystemService(android.hardware.camera2.CameraManager::class.java).getCameraCharacteristics(id)
                            SensorDepth.open(info)?.let { stream ->
                                sensorDepth = stream
                                stream.attach(this, builder) {
                                    runOnUiThread {
                                        if (!isDestroyed && sensorDepth === stream) {
                                            rejectedDepthCameras.add(id)
                                            requestCameraRebind()
                                        }
                                    }
                                }
                            }
                        }.onFailure { sensorDepth?.close(); sensorDepth = null }
                    }
                }
                return builder.build().also {
                    previewUseCase = it
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
            }
            fun newCapture(mode: Int): ImageCapture {
                val builder = ImageCapture.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setCaptureMode(mode)
                    .setFlashMode(if (activeMode == ReferenceRenderer.Mode.LONG_EXPOSURE) ImageCapture.FLASH_MODE_OFF else flashMode)
                if (activeMode == ReferenceRenderer.Mode.DAY || activeMode == ReferenceRenderer.Mode.CLOSE_UP) builder.setJpegQuality(100)
                return builder.build()
            }
            fun newAnalysis() = ImageAnalysis.Builder()
                .setTargetResolution(Size(320, 240))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            provider.unbindAll()
            sensorDepth?.close()
            sensorDepth = null
            previewUseCase = null
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
                // Prioritize response in Day and Bokeh modes. The software night burst
                // uses ZSL when flash is off; CameraX falls back if unsupported.
                val baseCaptureMode = when {
                    activeMode == ReferenceRenderer.Mode.DAY || activeMode == ReferenceRenderer.Mode.CLOSE_UP || activeMode == ReferenceRenderer.Mode.LONG_EXPOSURE -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    activeMode != ReferenceRenderer.Mode.NIGHT -> ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                    flashMode == ImageCapture.FLASH_MODE_OFF -> ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
                    else -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                }
                val baseCapture = newCapture(baseCaptureMode)
                // The GPU shader already grades the live preview on Android 13+.
                // Keep analysis only where it supplies the fallback look or depth map.
                val needsAnalysis = liveCameraEffect == null || (activeMode == ReferenceRenderer.Mode.CLOSE_UP && !infinityFocusEnabled)
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
                    val fallbackPreview = if (sensorDepth != null) {
                        rejectedDepthCameras.add(Camera2CameraInfo.from(provider.getCameraInfo(baseSelector)).cameraId)
                        sensorDepth?.close()
                        sensorDepth = null
                        newPreview()
                    } else basePreview
                    camera = provider.bindToLifecycle(this, baseSelector, fallbackPreview, baseCapture)
                    capture = baseCapture
                }
            }

            imageCapture = capture ?: error("Camera did not provide image capture")
            imageAnalysis = analysis
            boundCamera = camera
            updateInfinityFocusSupport(camera?.cameraInfo)
            applySelectedFocus()
            configureZoomStops(camera?.cameraInfo)
            nightExtensionActive = activeMode == ReferenceRenderer.Mode.NIGHT && extensionBound
            livePreviewAvailable = live
            val rearCameraHasFlash = try { provider.getCameraInfo(baseSelector).hasFlashUnit() } catch (_: Exception) { false }
            flashButton.visibility = if (lensFacing == CameraSelector.LENS_FACING_BACK && rearCameraHasFlash) View.VISIBLE else View.INVISIBLE
            updateFlashButtonAvailability()
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
        val minimumInterval = if (effect != null) 50_000_000L else if (mode == ReferenceRenderer.Mode.CLOSE_UP) 450_000_000L else 150_000_000L
        if (now - lastPreviewFrameNanos < minimumInterval || now < previewCooldownUntilNanos) {
            proxy.close()
            return
        }
        lastPreviewFrameNanos = now
        var styled: Bitmap? = null
        val focusMoved = lastLiveDepthFocusX.isNaN() || lastLiveDepthFocusY.isNaN() ||
            kotlin.math.abs(focusX - lastLiveDepthFocusX) > 0.01f ||
            kotlin.math.abs(focusY - lastLiveDepthFocusY) > 0.01f
        val refreshLiveDepth = effect != null &&
            (now - lastLiveDepthGuideNanos >= liveDepthRefreshIntervalNanos || focusMoved)
        if (effect != null && !refreshLiveDepth) {
            // The live GPU shader consumes the camera's current frame directly.
            // Only the depth/focus guide needs periodic CPU inference.
            proxy.close()
            return
        }
        if (refreshLiveDepth) {
            // Schedule from inference start, not completion, so model latency does
            // not get added again to the next refresh interval.
            lastLiveDepthGuideNanos = now
            lastLiveDepthFocusX = focusX
            lastLiveDepthFocusY = focusY
        }
        try {
            val plane = proxy.planes.firstOrNull() ?: return
            val buffer: ByteBuffer = plane.buffer.duplicate()
            val width = proxy.width
            val height = proxy.height
            val sampleScale = if (effect != null) minOf(1f, 512f / maxOf(width, height)) else 1f
            val sampleWidth = maxOf(1, (width * sampleScale).toInt())
            val sampleHeight = maxOf(1, (height * sampleScale).toInt())
            val pixels = IntArray(sampleWidth * sampleHeight)
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowBytes = ByteArray((width - 1) * pixelStride + 4)
            val sampleOffsets = IntArray(sampleWidth) { x -> (x * width / sampleWidth) * pixelStride }
            for (y in 0 until sampleHeight) {
                val sourceY = y * height / sampleHeight
                buffer.position(sourceY * rowStride)
                buffer.get(rowBytes)
                for (x in 0 until sampleWidth) {
                    val offset = sampleOffsets[x]
                    val r = rowBytes[offset].toInt() and 0xff
                    val g = rowBytes[offset + 1].toInt() and 0xff
                    val b = rowBytes[offset + 2].toInt() and 0xff
                    val a = rowBytes[offset + 3].toInt() and 0xff
                    pixels[y * sampleWidth + x] = Color.argb(a, r, g, b)
                }
            }
            val raw = Bitmap.createBitmap(pixels, sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888)
            val rotation = proxy.imageInfo.rotationDegrees
            val oriented = if (rotation != 0 || lensFacing == CameraSelector.LENS_FACING_FRONT) {
                val transform = Matrix().apply {
                    postRotate(rotation.toFloat())
                    if (lensFacing == CameraSelector.LENS_FACING_FRONT) postScale(-1f, 1f)
                }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, transform, true).also { if (it !== raw) raw.recycle() }
            } else raw
            if (effect != null) {
                if (infinityFocusEnabled) {
                    oriented.recycle()
                    return
                }
                val processor = livePortraitProcessor ?: PortraitBlur.StreamProcessor(this).also { livePortraitProcessor = it }
                val focus = mapFocusToFrame(
                    focusX, focusY, previewView.width, previewView.height, oriented.width, oriented.height,
                    lensFacing == CameraSelector.LENS_FACING_FRONT
                )
                val guides = processor.createDepthGuide(oriented, focus.first, focus.second, bokehLevel)
                oriented.recycle()
                if (guides != null) {
                    runOnUiThread {
                        if (!isDestroyed && previewView.visibility == View.VISIBLE && activeMode == ReferenceRenderer.Mode.CLOSE_UP && !infinityFocusEnabled) {
                            android.util.Log.d("UltraToneTiming", "guide processing to display ${(System.nanoTime() - now) / 1_000_000} ms")
                            effect.updateDepthGuide(guides.depth, guides.circles, guides.focusDepth)
                        } else {
                            guides.depth.recycle()
                            guides.circles.recycle()
                        }
                    }
                } else {
                    runOnUiThread {
                        if (!isDestroyed && activeMode == ReferenceRenderer.Mode.CLOSE_UP) effect.clearDepthGuide()
                    }
                }
                return
            }
            val colorized = ReferenceRenderer.render(oriented, styleStrength, mode, currentZoomRatio, activeFinish, currentEffects())
            styled = colorized
            oriented.recycle()
            var output: Bitmap = colorized
            if (mode == ReferenceRenderer.Mode.CLOSE_UP && !infinityFocusEnabled) {
                val segmenter = livePortraitProcessor ?: PortraitBlur.StreamProcessor(this).also { livePortraitProcessor = it }
                val focus = mapFocusToFrame(
                    focusX, focusY, previewView.width, previewView.height, colorized.width, colorized.height,
                    lensFacing == CameraSelector.LENS_FACING_FRONT
                )
                val portrait = segmenter.apply(colorized, focus.first, focus.second, bokehLevel)
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
            if (effect == null && processingNanos > minimumInterval * 3 / 5) {
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
            updateMoreMenu()
        }
        requestCameraRebind()
    }

    private fun updateFlashButtonAvailability() {
        if (!::flashButton.isInitialized) return
        val available = activeMode != ReferenceRenderer.Mode.LONG_EXPOSURE
        flashButton.isEnabled = available
        flashButton.alpha = if (available) 1f else 0.45f
        flashButton.contentDescription = if (available) "Flash: ${flashButton.text}" else "Flash is disabled during manual long exposure"
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
        if (cameraProvider != null) requestCameraRebind()
    }

    private fun selectMode(mode: ReferenceRenderer.Mode) {
        if (longExposureHoldActive) return
        activeMode = mode
        updateFlashButtonAvailability()
        updateDetailMergeButton()
        updateLiveLook()
        if (mode != ReferenceRenderer.Mode.CLOSE_UP) {
            analysisExecutor.execute { livePortraitProcessor?.close(); livePortraitProcessor = null }
        }
        updateModeButtons()
        updateMoreMenu()
        sourceBitmap?.let { renderImported(it) }
        if (previewView.visibility == View.VISIBLE && cameraProvider != null) requestCameraRebind()
        updateLongExposureHint()
    }

    /**
     * Switching modes rebuilds CameraX use cases. Defer that unbind while a still
     * capture (including a multi-frame capture) is in flight, or CameraX can
     * cancel its callback before the captured bytes reach the processing queue.
     */
    private fun requestCameraRebind() {
        if (isBusy) {
            cameraRebindPending = true
        } else {
            bindCamera()
        }
    }

    /** Called on the UI thread after the active capture has delivered its data. */
    private fun releaseCaptureAndApplyPendingRebind() {
        isBusy = false
        if (!cameraRebindPending) return
        cameraRebindPending = false
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
        liveCameraEffect?.setLook(activeMode, activeFinish, styleStrength, currentZoomRatio, nightEnhancementEnabled, infinityFocusEnabled, currentEffects())
    }

    private fun updateFinishButtons() {
        finishButtons.forEach { (finish, button) ->
            val selected = finish == activeFinish
            button.setTextColor(if (selected) 0xffffd28c.toInt() else 0xfff2f2f4.toInt())
            button.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            button.alpha = if (selected) 1f else 0.70f
        }
    }

    private fun applyControlSizing(landscape: Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
        if (!::bottomPanel.isInitialized || !::modeRow.isInitialized || !::finishRow.isInitialized || !::captureControls.isInitialized) return
        bottomPanel.setPadding(dp(8), dp(7), dp(8), dp(6))

        // Keep mode and finish choices stacked in the compact right-side panel.
        modeRow.orientation = if (landscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        modeRow.setPadding(0, dp(2), 0, dp(2))
        modeButtons.values.forEach { button ->
            button.setPadding(if (landscape) dp(10) else dp(4), dp(4), if (landscape) dp(10) else dp(4), dp(4))
            button.textSize = if (landscape) 14f else 12f
            button.layoutParams = LinearLayout.LayoutParams(
                if (landscape) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                if (landscape) dp(28) else ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        finishRow.orientation = if (landscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        finishRow.setPadding(0, 0, 0, dp(2))
        finishButtons.values.forEach { button ->
            val lp = button.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
            lp.width = if (landscape) ViewGroup.LayoutParams.MATCH_PARENT else dp(88)
            lp.height = if (landscape) dp(28) else dp(30)
            lp.marginStart = if (landscape) 0 else dp(4)
            lp.marginEnd = if (landscape) 0 else dp(4)
            lp.topMargin = if (landscape) dp(2) else 0
            lp.bottomMargin = if (landscape) dp(2) else 0
            button.layoutParams = lp
        }

        if (landscapeControlsActive != landscape) {
            // Equal upper and lower regions keep the shutter on the screen midline.
            for (row in listOf(finishRow, modeRow, captureControls))
                (row.parent as? ViewGroup)?.removeView(row)
            landscapeModeScroll?.let { bottomPanel.removeView(it) }
            landscapeModeScroll = null
            if (landscape) {
                finishRow.gravity = Gravity.CENTER
                bottomPanel.addView(finishRow, 0, LinearLayout.LayoutParams(-1, 0, 1f))
                bottomPanel.addView(captureControls, 1, LinearLayout.LayoutParams(-1, -2))
                val scroll = android.widget.ScrollView(this).apply { addView(modeRow); isFillViewport = false }
                landscapeModeScroll = scroll
                bottomPanel.addView(scroll, 2, LinearLayout.LayoutParams(-1, 0, 1f))
            } else {
                listOf(modeRow, finishRow, captureControls).forEachIndexed { index, row ->
                    bottomPanel.addView(row, index, LinearLayout.LayoutParams(-1, -2))
                }
            }
            (thumbnailButton.parent as? ViewGroup)?.removeView(thumbnailButton)
            (moreButton.parent as? ViewGroup)?.removeView(moreButton)
            captureControls.removeAllViews()
            landscapeActions.removeAllViews()
            if (landscape) {
                landscapeActions.addView(thumbnailButton, LinearLayout.LayoutParams(dp(42), dp(42)).apply { bottomMargin = dp(8) })
                landscapeActions.addView(moreButton, LinearLayout.LayoutParams(dp(64), dp(36)))
                captureControls.orientation = LinearLayout.VERTICAL
                captureControls.gravity = Gravity.CENTER
                captureControls.addView(shutterButton, LinearLayout.LayoutParams(dp(58), dp(58)))
            } else {
                captureControls.orientation = LinearLayout.HORIZONTAL
                captureControls.gravity = Gravity.CENTER_VERTICAL
                captureControls.addView(thumbnailButton, LinearLayout.LayoutParams(dp(42), dp(42)))
                captureControls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                captureControls.addView(shutterButton, LinearLayout.LayoutParams(dp(64), dp(64)))
                captureControls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                captureControls.addView(moreButton, LinearLayout.LayoutParams(dp(54), dp(36)))
            }
            landscapeControlsActive = landscape
        }
        captureControls.setPadding(0, dp(2), 0, 0)
        resizeControl(thumbnailButton, 42, 42)
        resizeControl(shutterButton, if (landscape) 58 else 64, if (landscape) 58 else 64)
        resizeControl(moreButton, if (landscape) 64 else 54, 36)
    }

    private fun resizeControl(view: View?, widthDp: Int, heightDp: Int) {
        val lp = view?.layoutParams as? LinearLayout.LayoutParams ?: return
        lp.width = dp(widthDp)
        lp.height = dp(heightDp)
        view.layoutParams = lp
    }

    private fun toggleMultiFrameDetail() {
        multiFrameDetailEnabled = !multiFrameDetailEnabled
        getPreferences(MODE_PRIVATE).edit()
            .putBoolean("multi_frame_detail_enabled", multiFrameDetailEnabled)
            .apply()
        updateDetailMergeButton()
    }

    private fun updateDetailMergeButton() {
        if (!::detailMergeButton.isInitialized) return
        val state = if (multiFrameDetailEnabled) "On" else "Off"
        val available = activeMode != ReferenceRenderer.Mode.LONG_EXPOSURE
        detailMergeButton.isEnabled = available
        detailMergeButton.isClickable = available
        if (!available) {
            detailMergeButton.text = "Photo stack: unavailable"
            detailMergeButton.setTextColor(0xff888888.toInt())
            detailMergeButton.setTypeface(null, android.graphics.Typeface.NORMAL)
            detailMergeButton.contentDescription = "Photo stack unavailable in Long Exposure"
            return
        }
        detailMergeButton.text = "Photo stack: $state"
        detailMergeButton.setTextColor(if (multiFrameDetailEnabled) 0xffffd28c.toInt() else 0xfff2f2f4.toInt())
        detailMergeButton.setTypeface(null, if (multiFrameDetailEnabled) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        detailMergeButton.contentDescription = "Photo stack, two-frame deblur, $state"
    }

    private fun cycleBokeh() {
        bokehLevel = (bokehLevel + 1) % 3
        getPreferences(MODE_PRIVATE).edit().putInt("bokeh_level", bokehLevel).apply()
        updateBokehButton()
        updateLiveLook()
        sourceBitmap?.let { renderImported(it) }
    }

    private fun updateBokehButton() {
        if (!::bokehButton.isInitialized) return
        val label = bokehLabel(bokehLevel)
        bokehButton.text = "Bokeh: $label"
        bokehButton.setTypeface(null, if (bokehLevel == 0) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
        bokehButton.setTextColor(if (bokehLevel == 0) 0xfff2f2f4.toInt() else 0xffffd28c.toInt())
        bokehButton.contentDescription = "Bokeh strength, $label. Tap to cycle."
    }

    private fun bokehLabel(level: Int) = when (level.coerceIn(0, 2)) {
        1 -> "Extra"
        2 -> "Max"
        else -> "Normal"
    }

    private fun updateLongExposureHint() {
        if (!::longExposureHint.isInitialized) return
        val visible = previewView.visibility == View.VISIBLE && activeMode == ReferenceRenderer.Mode.LONG_EXPOSURE
        longExposureHint.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            longExposureHint.text = if (longExposureHoldActive) {
                val elapsed = SystemClock.elapsedRealtime() - longExposureStartedAtMs
                "%.1f s".format(elapsed.coerceIn(0L, MAX_LONG_EXPOSURE_MILLIS) / 1000.0)
            } else "press and hold"
        }
    }

    private fun toggleNightEnhancement() {
        nightEnhancementEnabled = !nightEnhancementEnabled
        getPreferences(MODE_PRIVATE).edit().putBoolean("night_enhancement_enabled", nightEnhancementEnabled).apply()
        updateNightEnhancementButton()
        updateLiveLook()
    }

    private fun effectLabel(effect: String) = when (effect) {
        "glow" -> "Glow"
        else -> "Vignette"
    }

    private fun currentEffects() = ReferenceRenderer.Effects(glowEnabled, vignetteEnabled)

    private fun toggleCreativeEffect(effect: String) {
        when (effect) {
            "glow" -> {
                glowEnabled = !glowEnabled
                getPreferences(MODE_PRIVATE).edit().putBoolean("effect_glow", glowEnabled).apply()
            }
            "vignette" -> {
                vignetteEnabled = !vignetteEnabled
                getPreferences(MODE_PRIVATE).edit().putBoolean("effect_vignette", vignetteEnabled).apply()
            }
        }
        updateCreativeEffectButtons()
        updateLiveLook()
        sourceBitmap?.let { renderImported(it) }
    }

    private fun updateCreativeEffectButtons() {
        if (creativeEffectButtons.isEmpty()) return
        creativeEffectButtons.forEach { (effect, button) ->
            val enabled = when (effect) {
                "glow" -> glowEnabled
                else -> vignetteEnabled
            }
            button.text = "${effectLabel(effect)} ${if (enabled) "On" else "Off"}"
            button.setTextColor(if (enabled) 0xffffd28c.toInt() else 0xfff2f2f4.toInt())
            button.setTypeface(null, if (enabled) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            button.contentDescription = "${effectLabel(effect)} effect, ${if (enabled) "on" else "off"}"
        }
    }

    private fun updateNightEnhancementButton() {
        if (!::nightEnhancementButton.isInitialized) return
        val state = if (nightEnhancementEnabled) "On" else "Off"
        nightEnhancementButton.text = "Night enhance: $state"
        nightEnhancementButton.setTextColor(if (nightEnhancementEnabled) 0xffffd28c.toInt() else 0xfff2f2f4.toInt())
        nightEnhancementButton.setTypeface(null, if (nightEnhancementEnabled) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        nightEnhancementButton.contentDescription = "Night-only low-light enhancement, $state"
    }

    private fun updateMoreMenu() {
        if (!::detailMergeButton.isInitialized || !::bokehButton.isInitialized || !::nightEnhancementButton.isInitialized) return
        val show = moreMenuExpanded && previewView.visibility == View.VISIBLE
        if (::moreMenuPanel.isInitialized) moreMenuPanel.visibility = if (show) View.VISIBLE else View.GONE
        strengthRow.visibility = if (show) View.VISIBLE else View.GONE
        detailMergeButton.visibility = if (show) View.VISIBLE else View.GONE
        bokehButton.visibility = if (show) View.VISIBLE else View.GONE
        nightEnhancementButton.visibility = if (show && activeMode == ReferenceRenderer.Mode.NIGHT) View.VISIBLE else View.GONE
        creativeEffectsRow.visibility = if (show) View.VISIBLE else View.GONE
        if (::exposureRow.isInitialized && previewView.visibility == View.VISIBLE) updateExposureControl(show)
    }

    private fun updateModeButtons() {
        applyControlSizing()
        modeButtons.forEach { (mode, button) ->
            val selected = mode == activeMode
            button.setTextColor(if (selected) 0xffffd28c.toInt() else 0xfff2f2f4.toInt())
            button.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            button.alpha = if (selected) 1f else 0.72f
        }
        if (::shutterButton.isInitialized) {
            shutterButton.contentDescription = if (activeMode == ReferenceRenderer.Mode.LONG_EXPOSURE) {
                "Hold shutter to capture a longer exposure; release to save"
            } else {
                "Take photo with the selected ${if (activeFinish == ReferenceRenderer.Finish.COLOUR) "colour" else "black and white"} style"
            }
        }
    }

    private fun startLongExposureHold() {
        if (longExposureHoldActive) return
        if (isBusy) return
        if (pendingPhotoCount >= maxPendingPhotos) {
            message("Photo queue is full; wait for a save to finish.")
            return
        }
        if (imageCapture == null) {
            message("Camera is starting. Try again in a moment.")
            return
        }
        longExposureHoldActive = true
        longExposureFrameCaptureInFlight = false
        longExposureFrameProcessing = false
        longExposureFinalizing = false
        longExposureStartedAtMs = SystemClock.elapsedRealtime()
        longExposureFrameCount = 0
        previousLongFrameTimestamp = 0L
        waitingLongFrame = null
        longExposureExif = emptyMap()
        longExposureAccumulator = LongExposureMerger()
        longExposureContext = LongExposureCaptureContext(
            currentZoomRatio, focusX, focusY, lensFacing, styleStrength, activeFinish,
            infinityFocusEnabled, currentEffects(), Size(previewView.width, previewView.height)
        )
        isBusy = true
        pendingPhotoCount++
        updateLongExposureHint()
        longExposureHandler.removeCallbacks(longExposureTicker)
        longExposureHandler.postDelayed(longExposureTicker, LONG_EXPOSURE_TICK_MILLIS)
        captureLongExposureFrame()
    }

    private fun captureLongExposureFrame() {
        if (!longExposureHoldActive || longExposureFrameCaptureInFlight || waitingLongFrame != null) return
        val capture = imageCapture
        if (capture == null) {
            stopLongExposureAfterCaptureError("Camera stopped during long exposure.")
            return
        }
        longExposureFrameCaptureInFlight = true
        val captureRequestedAt = SystemClock.elapsedRealtime()
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                android.util.Log.d("UltraToneTiming", "capture delivery ${SystemClock.elapsedRealtime() - captureRequestedAt} ms")
                val timestamp = image.imageInfo.timestamp
                if (previousLongFrameTimestamp != 0L) android.util.Log.d("UltraToneTiming",
                    "long frame interval ${(timestamp - previousLongFrameTimestamp) / 1_000_000} ms")
                previousLongFrameTimestamp = timestamp
                val frame = try {
                    check(image.format == android.graphics.ImageFormat.JPEG) { "Camera returned an unsupported image format" }
                    val buffer = image.planes.firstOrNull()?.buffer?.duplicate() ?: error("Camera returned no image data")
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                        .let { LongExposureMerger.Frame(it, image.imageInfo.rotationDegrees) }
                } catch (_: Exception) { null }
                image.close()
                runOnUiThread { acceptLongExposureFrame(frame) }
            }

            override fun onError(error: ImageCaptureException) {
                runOnUiThread { acceptLongExposureFrame(null, error.message) }
            }
        })
    }

    private fun acceptLongExposureFrame(frame: LongExposureMerger.Frame?, errorMessage: String? = null) {
        longExposureFrameCaptureInFlight = false
        if (frame == null) {
            stopLongExposureAfterCaptureError(errorMessage ?: "Could not read a long exposure frame.")
            return
        }
        val accumulator = longExposureAccumulator ?: run {
            stopLongExposureAfterCaptureError("Long exposure was cancelled.")
            return
        }
        if (longExposureFrameProcessing) {
            waitingLongFrame = frame
            return
        }
        mergeLongFrame(accumulator, frame)
    }

    private fun mergeLongFrame(accumulator: LongExposureMerger, frame: LongExposureMerger.Frame) {
        longExposureFrameProcessing = true
        val first = longExposureFrameCount == 0
        captureLongExposureFrame()
        photoProcessingExecutor.execute {
            val error = try {
                if (first) longExposureExif = captureExif(frame.jpegBytes)
                timed("long frame merge") { accumulator.add(frame) }
                null
            } catch (e: Exception) {
                e.localizedMessage ?: "frame processing failed"
            }
            runOnUiThread {
                longExposureFrameProcessing = false
                if (error != null) {
                    waitingLongFrame = null
                    stopLongExposureAfterCaptureError("Long exposure failed: $error")
                    return@runOnUiThread
                }
                longExposureFrameCount = accumulator.frameCount()
                val waiting = waitingLongFrame
                waitingLongFrame = null
                if (waiting != null) mergeLongFrame(accumulator, waiting)
                else if (longExposureHoldActive) captureLongExposureFrame() else finalizeLongExposure()
            }
        }
    }

    private fun finishLongExposureHold(limitReached: Boolean = false) {
        if (!longExposureHoldActive) return
        longExposureHoldActive = false
        updateLongExposureHint()
        longExposureHandler.removeCallbacks(longExposureTicker)
        val elapsed = (SystemClock.elapsedRealtime() - longExposureStartedAtMs).coerceAtLeast(0L)
        setStatusMessage("Processing long exposure · ${"%.1f".format(elapsed / 1000f)} s · $longExposureFrameCount frames")
        if (limitReached) message("Long exposure reached the 30 second limit.")
        if (!longExposureFrameCaptureInFlight && !longExposureFrameProcessing) finalizeLongExposure()
    }

    private fun finalizeLongExposure() {
        if (longExposureHoldActive || longExposureFrameCaptureInFlight || longExposureFrameProcessing || longExposureFinalizing) return
        val accumulator = longExposureAccumulator
        val context = longExposureContext
        if (accumulator == null || context == null || longExposureFrameCount == 0) {
            stopLongExposureAfterCaptureError("No long exposure frames were captured.")
            return
        }
        longExposureFinalizing = true
        longExposureAccumulator = null
        longExposureContext = null
        val exif = longExposureExif
        val frames = longExposureFrameCount
        longExposureFrameCount = 0
        releaseCaptureAndApplyPendingRebind()
        refreshShutterAvailability()
        showPolaroidFeedback()
        setStatusMessage(if (frames == 1) "Saving one usable frame…" else "Merging $frames aligned frames…")
        photoProcessingExecutor.execute {
            try {
                val merged = accumulator.finish()
                processCapturedBitmap(
                    merged, context.zoom, context.focusX, context.focusY, context.facing,
                    ReferenceRenderer.Mode.LONG_EXPOSURE, context.strength, context.finish,
                    infinityFocusAtCapture = context.infinityFocus, effectsAtCapture = context.effects, previewSize = context.previewSize, exif = exif
                )
            } catch (e: Exception) {
                runOnUiThread {
                    pendingPhotoCount = (pendingPhotoCount - 1).coerceAtLeast(0)
                    refreshShutterAvailability()
                    message("Long exposure merge failed: ${e.localizedMessage ?: "unknown error"}")
                    updateStatus()
                }
            }
        }
    }

    private fun stopLongExposureAfterCaptureError(messageText: String) {
        longExposureHoldActive = false
        updateLongExposureHint()
        longExposureHandler.removeCallbacks(longExposureTicker)
        if (longExposureFrameProcessing || longExposureFrameCaptureInFlight) {
            message(messageText)
            return
        }
        if (longExposureFrameCount > 0 && longExposureAccumulator != null && longExposureContext != null) {
            finalizeLongExposure()
            message("$messageText Saving the frames already captured.")
            return
        }
        longExposureAccumulator = null
        longExposureContext = null
        longExposureFrameCount = 0
        longExposureFrameCaptureInFlight = false
        longExposureFrameProcessing = false
        releaseCaptureAndApplyPendingRebind()
        pendingPhotoCount = (pendingPhotoCount - 1).coerceAtLeast(0)
        refreshShutterAvailability()
        message(messageText)
        updateStatus()
    }

    private fun capture() {
        if (isBusy) return
        if (activeMode == ReferenceRenderer.Mode.LONG_EXPOSURE) {
            startLongExposureHold()
            longExposureHandler.postDelayed({ if (longExposureHoldActive) finishLongExposureHold() }, 1_000L)
            return
        }
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
        } else if (activeMode == ReferenceRenderer.Mode.DAY) {
            showPolaroidFeedback()
        } else setStatusMessage("Taking photo…")
        val zoom = currentZoomRatio
        val focusXAtCapture = focusX
        val focusYAtCapture = focusY
        val facingAtCapture = lensFacing
        val modeAtCapture = activeMode
        val detailMergeAtCapture = multiFrameDetailEnabled
        val strengthAtCapture = styleStrength
        val finishAtCapture = activeFinish
        val nightEnhanceAtCapture = nightEnhancementEnabled && modeAtCapture == ReferenceRenderer.Mode.NIGHT
        val infinityFocusAtCapture = infinityFocusEnabled
        val effectsAtCapture = currentEffects()
        val previewSize = Size(previewView.width, previewView.height)
        val bokehLevelAtCapture = bokehLevel
        if (modeAtCapture == ReferenceRenderer.Mode.NIGHT && !nightExtensionActive && flashMode == ImageCapture.FLASH_MODE_OFF) {
            captureNightFrames(capture, mutableListOf(), 0) { frames ->
                if (frames == null) {
                    runOnUiThread {
                        releaseCaptureAndApplyPendingRebind()
                        setNightCaptureActive(false)
                        pendingPhotoCount = (pendingPhotoCount - 1).coerceAtLeast(0)
                        refreshShutterAvailability()
                        message("Night capture failed; try again with the phone steadier.")
                        updateStatus()
                    }
                } else {
                    runOnUiThread {
                        releaseCaptureAndApplyPendingRebind()
                        setNightCaptureActive(false)
                        refreshShutterAvailability()
                        showQueueStatus()
                    }
                    enqueuePhoto {
                        try {
                            val merged = timed("night merge") { if (detailMergeAtCapture) NightBurstMerger.mergeDetail(frames) else NightBurstMerger.merge(frames) }
                            processCapturedBitmap(merged, zoom, focusXAtCapture, focusYAtCapture, facingAtCapture, modeAtCapture, strengthAtCapture, finishAtCapture, nightEnhanceAtCapture, infinityFocusAtCapture, effectsAtCapture, bokehLevelAtCapture, previewSize, captureExif(frames.first().jpegBytes))
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

        if (detailMergeAtCapture) {
            setStatusMessage("Capturing photo stack frames…")
            captureNightFrames(capture, mutableListOf(), 0, showFeedbackOnCompletion = modeAtCapture != ReferenceRenderer.Mode.DAY) { frames ->
                if (frames == null) {
                    runOnUiThread {
                        releaseCaptureAndApplyPendingRebind()
                        setNightCaptureActive(false)
                        pendingPhotoCount = (pendingPhotoCount - 1).coerceAtLeast(0)
                        refreshShutterAvailability()
                        message("Photo stack capture failed; try again with the phone steadier.")
                        updateStatus()
                    }
                } else {
                    runOnUiThread {
                        releaseCaptureAndApplyPendingRebind()
                        setNightCaptureActive(false)
                        refreshShutterAvailability()
                        setStatusMessage("Building photo stack…")
                        showQueueStatus()
                    }
                    enqueuePhoto {
                        try {
                            var guide: PortraitBlur.StackGuide? = null
                            val merged = timed("stack merge") {
                                if (modeAtCapture == ReferenceRenderer.Mode.CLOSE_UP && !infinityFocusAtCapture) {
                                    fun focusAt(w: Int, h: Int) = mapFocusToFrame(focusXAtCapture, focusYAtCapture,
                                        previewSize.width, previewSize.height, w, h, facingAtCapture == CameraSelector.LENS_FACING_FRONT)
                                    NightBurstMerger.mergeDetail(frames, ::focusAt) { base, frame ->
                                        PortraitBlur.prepareStack(this@MainActivity, base, focusAt(base.width, base.height), frame.depth)
                                            .also { guide = it }
                                    }
                                } else NightBurstMerger.mergeDetail(frames)
                            }
                            processCapturedBitmap(merged, zoom, focusXAtCapture, focusYAtCapture,
                                facingAtCapture, modeAtCapture, strengthAtCapture, finishAtCapture, nightEnhanceAtCapture, infinityFocusAtCapture, effectsAtCapture, bokehLevelAtCapture, previewSize, captureExif(frames.first().jpegBytes), guide)
                        } catch (e: Exception) {
                            runOnUiThread {
                                pendingPhotoCount = (pendingPhotoCount - 1).coerceAtLeast(0)
                                refreshShutterAvailability()
                                message("Photo stack failed: ${e.localizedMessage ?: "unknown error"}")
                                updateStatus()
                            }
                        }
                    }
                }
            }
            return
        }

        val captureRequestedAt = SystemClock.elapsedRealtime()
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureStarted() {
                runOnUiThread {
                    if (modeAtCapture != ReferenceRenderer.Mode.DAY && modeAtCapture != ReferenceRenderer.Mode.NIGHT && modeAtCapture != ReferenceRenderer.Mode.LONG_EXPOSURE) setStatusMessage("Taking photo…")
                }
            }

            override fun onCaptureProcessProgressed(progress: Int) {
                runOnUiThread {
                    if (modeAtCapture != ReferenceRenderer.Mode.DAY && modeAtCapture != ReferenceRenderer.Mode.NIGHT && modeAtCapture != ReferenceRenderer.Mode.LONG_EXPOSURE) setStatusMessage("Taking photo · $progress%")
                }
            }

            override fun onCaptureSuccess(image: ImageProxy) {
                android.util.Log.d("UltraToneTiming", "capture delivery ${SystemClock.elapsedRealtime() - captureRequestedAt} ms")
                val jpeg = try {
                    check(image.format == android.graphics.ImageFormat.JPEG) { "Camera returned an unsupported image format" }
                    val buffer = image.planes.firstOrNull()?.buffer?.duplicate() ?: error("Camera returned no image data")
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                } catch (e: Exception) {
                    image.close()
                    runOnUiThread {
                        releaseCaptureAndApplyPendingRebind()
                        setNightCaptureActive(false)
                        pendingPhotoCount = (pendingPhotoCount - 1).coerceAtLeast(0)
                        refreshShutterAvailability()
                        message("Capture failed: ${e.localizedMessage ?: "camera error"}")
                        updateStatus()
                    }
                    return
                }
                val rotation = image.imageInfo.rotationDegrees
                val depthSample = sensorDepth?.takeIf { kotlin.math.abs(zoom - 1f) < 0.001f }?.match(image.imageInfo.timestamp, rotation)
                image.close()
                if (modeAtCapture != ReferenceRenderer.Mode.DAY) runOnUiThread { showPolaroidFeedback() }
                runOnUiThread {
                    releaseCaptureAndApplyPendingRebind()
                    setNightCaptureActive(false)
                    refreshShutterAvailability()
                    if (modeAtCapture != ReferenceRenderer.Mode.NIGHT) {
                        setStatusMessage("Processing photo…")
                        showQueueStatus()
                    }
                }
                enqueuePhoto {
                    try {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
                        val bitmap = timed("decode") { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
                            decodeOptions(bounds.outWidth, bounds.outHeight)) ?: error("Could not decode captured image") }
                        processCapturedBitmap(rotate(bitmap, rotation), zoom, focusXAtCapture, focusYAtCapture,
                            facingAtCapture, modeAtCapture, strengthAtCapture, finishAtCapture, nightEnhanceAtCapture, infinityFocusAtCapture, effectsAtCapture, bokehLevelAtCapture, previewSize, captureExif(jpeg), sensorSample = depthSample)
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
                    releaseCaptureAndApplyPendingRebind()
                    setNightCaptureActive(false)
                    pendingPhotoCount = (pendingPhotoCount - 1).coerceAtLeast(0)
                    refreshShutterAvailability()
                    message("Capture failed: ${error.message ?: "camera error"}")
                    updateStatus()
                }
            }
        })
    }

    private fun showPolaroidFeedback() {
        if (!::photoFeedbackLayer.isInitialized || photoFeedbackLayer.width == 0) return
        val activePreview = when {
            processedPreviewView.visibility == View.VISIBLE -> processedPreviewView
            previewView.visibility == View.VISIBLE -> previewView
            else -> return
        }
        val frame = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val aspect = if (activePreview === processedPreviewView) {
                    processedPreviewBitmap?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }
                        ?: previewContentAspectRatio()
                } else previewContentAspectRatio()
                val rect = fitCenterContentRect(width, height, aspect) ?: return
                val edge = dp(5).toFloat()
                val bottomEdge = dp(23).toFloat()
                canvas.drawRect(rect.left, rect.top, rect.right, rect.top + edge, paint)
                canvas.drawRect(rect.left, rect.top, rect.left + edge, rect.bottom, paint)
                canvas.drawRect(rect.right - edge, rect.top, rect.right, rect.bottom, paint)
                canvas.drawRect(rect.left, rect.bottom - bottomEdge, rect.right, rect.bottom, paint)
            }
        }.apply {
            setWillNotDraw(false)
            isClickable = false
            isFocusable = false
            translationX = activePreview.translationX
            translationY = activePreview.translationY
            pivotX = activePreview.width / 2f
            pivotY = activePreview.height / 2f
        }
        photoFeedbackLayer.removeAllViews()
        photoFeedbackLayer.addView(frame, FrameLayout.LayoutParams(
            activePreview.width, activePreview.height
        ).apply {
            leftMargin = activePreview.left
            topMargin = activePreview.top
        })
        activePreview.animate().cancel()
        frame.animate().cancel()
        activePreview.scaleX = 1f
        activePreview.scaleY = 1f
        frame.scaleX = 1f
        frame.scaleY = 1f
        activePreview.animate().scaleX(0.94f).scaleY(0.94f).setDuration(85L)
            .withEndAction { activePreview.animate().scaleX(1f).scaleY(1f).setDuration(190L).start() }
            .start()
        frame.animate().scaleX(0.94f).scaleY(0.94f).setDuration(85L)
            .withEndAction {
                frame.animate().alpha(0f).scaleX(1f).scaleY(1f).setDuration(190L)
                    .withEndAction { photoFeedbackLayer.removeView(frame) }.start()
            }
            .start()
    }

    private fun captureNightFrames(
        capture: ImageCapture,
        frames: MutableList<NightBurstMerger.Frame>,
        index: Int,
        totalFrames: Int = 2,
        showFeedbackOnCompletion: Boolean = true,
        onComplete: (List<NightBurstMerger.Frame>?) -> Unit
    ) {
        if (index >= totalFrames) {
            if (showFeedbackOnCompletion) runOnUiThread { showPolaroidFeedback() }
            onComplete(frames.toList())
            return
        }
        val captureRequestedAt = SystemClock.elapsedRealtime()
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    android.util.Log.d("UltraToneTiming", "capture delivery ${SystemClock.elapsedRealtime() - captureRequestedAt} ms")
                    var nextFrame: NightBurstMerger.Frame? = null
                    try {
                        check(image.format == android.graphics.ImageFormat.JPEG) { "Night capture returned an unsupported image format" }
                        val buffer = image.planes.firstOrNull()?.buffer?.duplicate()
                            ?: error("Night capture returned no image data")
                        val jpeg = ByteArray(buffer.remaining())
                        buffer.get(jpeg)
                        nextFrame = NightBurstMerger.Frame(jpeg, image.imageInfo.rotationDegrees, sensorDepth?.takeIf { kotlin.math.abs(currentZoomRatio - 1f) < 0.001f }?.match(image.imageInfo.timestamp, image.imageInfo.rotationDegrees))
                    } catch (_: Exception) { }
                    image.close()
                    if (nextFrame == null) {
                        onComplete(null)
                        return
                    }
                    frames += nextFrame
                    if (index + 1 < totalFrames) {
                        runOnUiThread { setNightCaptureProgress(index + 2, totalFrames) }
                    }
                    captureNightFrames(capture, frames, index + 1, totalFrames, showFeedbackOnCompletion, onComplete)
                }
                override fun onError(error: ImageCaptureException) { onComplete(null) }
            })
    }

    private inline fun <T> timed(stage: String, action: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        return try { action() } finally {
            android.util.Log.d("UltraToneTiming", "$stage ${SystemClock.elapsedRealtime() - start} ms")
        }
    }

    private fun enqueuePhoto(action: () -> Unit) {
        val queued = SystemClock.elapsedRealtime()
        photoProcessingExecutor.execute {
            android.util.Log.d("UltraToneTiming", "queue wait ${SystemClock.elapsedRealtime() - queued} ms")
            timed("photo processing") { action() }
        }
    }

    private fun processCapturedBitmap(
        upright: Bitmap,
        zoom: Float,
        focusXAtCapture: Float,
        focusYAtCapture: Float,
        facingAtCapture: Int,
        modeAtCapture: ReferenceRenderer.Mode,
        strengthAtCapture: Float,
        finishAtCapture: ReferenceRenderer.Finish,
        nightEnhanceAtCapture: Boolean = false,
        infinityFocusAtCapture: Boolean = false,
        effectsAtCapture: ReferenceRenderer.Effects = ReferenceRenderer.Effects(),
        bokehLevelAtCapture: Int = 0,
        previewSize: Size,
        exif: Map<String, String>,
        stackGuide: PortraitBlur.StackGuide? = null,
        sensorSample: SensorDepth.Sample? = null
    ) {
        var style: Bitmap? = null
        var enhanced: Bitmap? = null
        var final: Bitmap? = null
        try {
            val imageForStyle = if (nightEnhanceAtCapture && modeAtCapture == ReferenceRenderer.Mode.NIGHT) {
                NightEnhancement.apply(upright).also { enhanced = it }
            } else upright
            val styled = timed("grade") { ReferenceRenderer.render(imageForStyle, strengthAtCapture, modeAtCapture, zoom, finishAtCapture, effectsAtCapture) }
            style = styled
            val focus = mapFocusToFrame(
                focusXAtCapture, focusYAtCapture, previewSize.width, previewSize.height,
                upright.width, upright.height, facingAtCapture == CameraSelector.LENS_FACING_FRONT
            )
            val portrait = if (modeAtCapture == ReferenceRenderer.Mode.CLOSE_UP && !infinityFocusAtCapture) (try {
                timed("bokeh") {
                    val guide = stackGuide ?: sensorSample?.let { PortraitBlur.prepareStack(this@MainActivity, upright, focus, it) }
                    if (guide != null) PortraitBlur.applyStack(styled, guide, focus, bokehLevelAtCapture)
                    else PortraitBlur.apply(this@MainActivity, styled, focus.first, focus.second, bokehLevelAtCapture)
                }
            } catch (_: Exception) { null }) else null
            val rendered = portrait?.bitmap ?: styled
            final = rendered
            runOnUiThread { setStatusMessage("Saving photo…") }
            val uri = timed("gallery save") { saveBitmap(rendered, exif) }
            lastPhotoUri = uri
            val savedPortrait = portrait
            if (rendered !== styled) styled.recycle()
            runOnUiThread {
                pendingPhotoCount = (pendingPhotoCount - 1).coerceAtLeast(0)
                refreshShutterAvailability()
                updateThumbnail(rendered)
                message(if (modeAtCapture == ReferenceRenderer.Mode.CLOSE_UP && !infinityFocusAtCapture && savedPortrait?.usedDepth != true && savedPortrait?.subjectDetected != true)
                    "Saved; Bokeh blur could not detect usable depth or a person" else if (pendingPhotoCount > 0)
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
            enhanced?.takeIf { !it.isRecycled }?.recycle()
            upright.recycle()
        }
    }

    private fun captureExif(jpeg: ByteArray): Map<String, String> =
        try { jpeg.inputStream().use { captureExif(ExifInterface(it)) } } catch (_: Exception) { emptyMap() }

    private fun captureExif(source: ExifInterface): Map<String, String> = listOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY
    ).mapNotNull { tag -> source.getAttribute(tag)?.let { tag to it } }.toMap()

    private fun saveBitmap(bitmap: Bitmap, exif: Map<String, String>): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "UltraTone-${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UltraTone")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create photo in gallery")
        try {
            timed("JPEG encode and write") {
                contentResolver.openOutputStream(uri).use { stream ->
                    if (stream == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 93, stream)) error("Photo encoding failed")
                }
            }
            if (exif.isNotEmpty()) {
                contentResolver.openFileDescriptor(uri, "rw").use { descriptor ->
                    val output = ExifInterface(requireNotNull(descriptor).fileDescriptor)
                    for ((tag, value) in exif) output.setAttribute(tag, value)
                    output.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                    output.saveAttributes()
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val publish = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                check(contentResolver.update(uri, publish, null, null) == 1) { "Could not publish photo" }
            }
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun updateThumbnail(bitmap: Bitmap) {
        val thumb = android.media.ThumbnailUtils.extractThumbnail(bitmap, 80, 80)
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
                val importedTags = try { contentResolver.openInputStream(uri)?.use { captureExif(ExifInterface(it)) }.orEmpty() } catch (_: Exception) { emptyMap() }
                val upright = orient(decoded, uri)
                runOnUiThread {
                    importedExif = importedTags
                    renderedBitmap = null
                    sourceBitmap = upright
                    previewView.visibility = View.GONE
                    imageView.visibility = View.VISIBLE
                    zoomRow.visibility = View.GONE
                    updateLongExposureHint()
                    modeRow.visibility = View.VISIBLE
                    finishRow.visibility = View.VISIBLE
                    captureControls.visibility = View.GONE
                    editControls.visibility = View.VISIBLE
                    strengthRow.visibility = View.VISIBLE
                    exposureRow.visibility = View.GONE
                    detailMergeButton.visibility = View.GONE
                    nightEnhancementButton.visibility = View.GONE
                    creativeEffectsRow.visibility = View.GONE
                    infinityFocusButton.visibility = View.GONE
                    renderImported(upright)
                }
            } catch (e: Exception) { runOnUiThread { message("Could not open photo: ${e.localizedMessage ?: "unsupported file"}") } }
        }
    }

    private fun renderImported(source: Bitmap) {
        val mode = activeMode
        val bokehLevelForRender = bokehLevel
        cameraExecutor.execute {
            try {
                val styled = ReferenceRenderer.render(source, styleStrength, mode, finish = activeFinish, effects = currentEffects())
                val focus = mapFocusToFrame(
                    focusX, focusY, processedPreviewView.width, processedPreviewView.height,
                    source.width, source.height, false
                )
                val portrait = if (mode == ReferenceRenderer.Mode.CLOSE_UP) (try {
                    PortraitBlur.apply(this@MainActivity, styled, focus.first, focus.second, bokehLevelForRender)
                } catch (_: Exception) { null }) else null
                val output = portrait?.bitmap ?: styled
                if (output !== styled) styled.recycle()
                runOnUiThread {
                    if (isDestroyed || sourceBitmap !== source) { output.recycle(); return@runOnUiThread }
                    renderedBitmap = output
                    imageView.setImageBitmap(output)
                    status.visibility = View.GONE
                }
            } catch (e: Exception) { runOnUiThread { message("Processing failed: ${e.localizedMessage ?: "not enough memory"}") } }
        }
    }

    private fun savePhoto() {
        val bitmap = renderedBitmap ?: run { message("Choose a photo first."); return }
        // The save task holds an immutable render until encoding completes.
        val tags = importedExif
        cameraExecutor.execute {
            try {
                val uri = saveBitmap(bitmap, tags)
                lastPhotoUri = uri
                runOnUiThread { updateThumbnail(bitmap); message("Saved to Pictures/UltraTone") }
            } catch (e: Exception) { runOnUiThread { message("Save failed: ${e.localizedMessage ?: "storage error"}") } }
        }
    }

    private fun showCamera() {
        sourceBitmap = null
        renderedBitmap = null
        imageView.setImageDrawable(null)
        imageView.visibility = View.GONE
        processedPreviewView.setImageDrawable(null)
        processedPreviewView.visibility = View.GONE
        retirePreviewBitmap(processedPreviewBitmap)
        processedPreviewBitmap = null
        previewView.visibility = View.VISIBLE
        infinityFocusButton.visibility = View.VISIBLE
        updateInfinityFocusButton()
        zoomRow.visibility = View.VISIBLE
        captureControls.visibility = View.VISIBLE
        editControls.visibility = View.GONE
        if (cameraProvider != null) bindCamera()
        updateLongExposureHint()
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
        updateLongExposureHint()
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
        ReferenceRenderer.Mode.CLOSE_UP -> "Bokeh"
        ReferenceRenderer.Mode.SUNSET -> "Sunset"
        ReferenceRenderer.Mode.LONG_EXPOSURE -> "Long exp."
        ReferenceRenderer.Mode.LANDSCAPE -> "Landscape"
    }
    private fun message(text: String) = setStatusMessage(text, 2_800L)

    override fun onPause() {
        if (longExposureHoldActive) finishLongExposureHold()
        super.onPause()
    }

    override fun onStop() {
        focusMotion.stop()
        super.onStop()
    }

    override fun onDestroy() {
        focusIndicatorHandler.removeCallbacks(hideFocusIndicator)
        longExposureHandler.removeCallbacks(longExposureTicker)
        super.onDestroy()
        sensorDepth?.close()
        sensorDepth = null
        zoomObserver?.let { boundCamera?.cameraInfo?.zoomState?.removeObserver(it) }
        liveCameraEffect?.close()
        liveCameraEffect = null
        cameraExecutor.shutdown()
        photoProcessingExecutor.shutdown()
        analysisExecutor.execute { livePortraitProcessor?.close(); livePortraitProcessor = null }
        analysisExecutor.shutdown()
        sourceBitmap = null
        renderedBitmap = null
        processedPreviewView.setImageDrawable(null)
        retirePreviewBitmap(processedPreviewBitmap)
        processedPreviewBitmap = null
    }
}
