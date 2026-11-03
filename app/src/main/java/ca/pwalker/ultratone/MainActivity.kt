Warning: truncated output (original token count: 26978)
Total output lines: 2204

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
        const val LONG_EXPOSURE_TICK_MILLIS = 200L
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
    private val hideFocusIndicator = Runnable {
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
    private var lastLiveDepthGuideNanos = 0L
    private var lastLiveDepthFocusX = Float.NaN
    private var lastLiveDepthFocusY = Float.NaN
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
        val effects: ReferenceRenderer.Effects
    )
    private var longExposureHoldActive = false
    private var longExposureFrameCaptureInFlight = false
    private var longExposureFrameProcessing = false
    private var longExposureFinalizing = false
    private var longExposureStartedAtMs = 0L
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
            setStatusMessage("Long exposure · ${"%.1f".format(elapsed / 1000f)} s · $longExposureFrameCount frames")
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
            contentDescription = "Bokeh strength for Close-up mode, Normal"
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
        overlay.addView(moreMenuPanel)
        longExposureHint = TextView(this).apply {
            text = "Press and hold"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = roundedStroke(0xb3111216.toInt(), 0x99ffffff.toInt(), 1, 18)
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            elevation = dp(7).toFloat()
            contentDescription = "Long exposure mode. Press and hold the shutter to take a longer exposure."
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
        zoomRow.layoutParams = if (l…12978 tokens truncated…frames already captured.")
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
                    photoProcessingExecutor.execute {
                        try {
                            val merged = if (detailMergeAtCapture) NightBurstMerger.mergeDetail(frames) else NightBurstMerger.merge(frames)
                            processCapturedBitmap(merged, zoom, focusXAtCapture, focusYAtCapture, facingAtCapture, modeAtCapture, strengthAtCapture, finishAtCapture, nightEnhanceAtCapture, infinityFocusAtCapture, effectsAtCapture, bokehLevelAtCapture)
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
            captureNightFrames(capture, mutableListOf(), 0) { frames ->
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
                    photoProcessingExecutor.execute {
                        try {
                            val merged = NightBurstMerger.mergeDetail(frames)
                            processCapturedBitmap(merged, zoom, focusXAtCapture, focusYAtCapture,
                                facingAtCapture, modeAtCapture, strengthAtCapture, finishAtCapture, nightEnhanceAtCapture, infinityFocusAtCapture, effectsAtCapture, bokehLevelAtCapture)
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

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureStarted() {
                runOnUiThread {
                    if (modeAtCapture != ReferenceRenderer.Mode.NIGHT && modeAtCapture != ReferenceRenderer.Mode.LONG_EXPOSURE) setStatusMessage("Taking photo…")
                }
            }

            override fun onCaptureProcessProgressed(progress: Int) {
                runOnUiThread {
                    if (modeAtCapture != ReferenceRenderer.Mode.NIGHT && modeAtCapture != ReferenceRenderer.Mode.LONG_EXPOSURE) setStatusMessage("Taking photo · $progress%")
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
                image.close()
                runOnUiThread { showPolaroidFeedback() }
                runOnUiThread {
                    releaseCaptureAndApplyPendingRebind()
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
                            facingAtCapture, modeAtCapture, strengthAtCapture, finishAtCapture, nightEnhanceAtCapture, infinityFocusAtCapture, effectsAtCapture, bokehLevelAtCapture)
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
        onComplete: (List<NightBurstMerger.Frame>?) -> Unit
    ) {
        if (index >= totalFrames) {
            runOnUiThread { showPolaroidFeedback() }
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
                    if (index + 1 < totalFrames) {
                        runOnUiThread { setNightCaptureProgress(index + 2, totalFrames) }
                    }
                    captureNightFrames(capture, frames, index + 1, totalFrames, onComplete)
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
        finishAtCapture: ReferenceRenderer.Finish,
        nightEnhanceAtCapture: Boolean = false,
        infinityFocusAtCapture: Boolean = false,
        effectsAtCapture: ReferenceRenderer.Effects = ReferenceRenderer.Effects(),
        bokehLevelAtCapture: Int = 0
    ) {
        var style: Bitmap? = null
        var enhanced: Bitmap? = null
        var final: Bitmap? = null
        try {
            val imageForStyle = if (nightEnhanceAtCapture && modeAtCapture == ReferenceRenderer.Mode.NIGHT) {
                NightEnhancement.apply(upright).also { enhanced = it }
            } else upright
            val styled = ReferenceRenderer.render(imageForStyle, strengthAtCapture, modeAtCapture, zoom, finishAtCapture, effectsAtCapture)
            style = styled
            val focus = mapFocusToFrame(
                focusXAtCapture, focusYAtCapture, previewView.width, previewView.height,
                upright.width, upright.height, facingAtCapture == CameraSelector.LENS_FACING_FRONT
            )
            val portrait = if (modeAtCapture == ReferenceRenderer.Mode.CLOSE_UP && !infinityFocusAtCapture) (try {
                PortraitBlur.apply(this@MainActivity, styled, focus.first, focus.second, bokehLevelAtCapture)
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
            enhanced?.takeIf { !it.isRecycled }?.recycle()
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
        ReferenceRenderer.Mode.CLOSE_UP -> "Close-up"
        ReferenceRenderer.Mode.SUNSET -> "Sunset"
        ReferenceRenderer.Mode.LONG_EXPOSURE -> "Long exp."
        ReferenceRenderer.Mode.LANDSCAPE -> "Landscape"
    }
    private fun message(text: String) = setStatusMessage(text, 2_800L)

    override fun onPause() {
        if (longExposureHoldActive) finishLongExposureHold()
        super.onPause()
    }

    override fun onDestroy() {
        longExposureHandler.removeCallbacks(longExposureTicker)
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
