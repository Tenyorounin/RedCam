package ca.pwalker.ultratone

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var imageView: ImageView
    private lateinit var status: TextView
    private lateinit var strength: SeekBar
    private lateinit var zoomSeek: SeekBar
    private lateinit var zoomLabel: TextView
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var boundCamera: Camera? = null
    private var zoomObserver: Observer<ZoomState>? = null
    private var sourceBitmap: Bitmap? = null
    private var renderedBitmap: Bitmap? = null
    private var workingFile: File? = null
    private var styleStrength = 0.75f
    private var currentZoomRatio = 1f
    private var sourceZoomRatio = 1f
    private var activeMode = ReferenceRenderer.Mode.DAY
    private var cameraModeNote = "Day capture"
    private var nightExtensionAvailable = false
    private var portraitExtensionAvailable = false
    private val modeButtons = mutableMapOf<ReferenceRenderer.Mode, Button>()

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else message("Camera access is needed for in-app capture. You can still import a photo.")
    }
    private val choosePhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildScreen()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff101114.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val heading = TextView(this).apply {
            text = "UltraTone"
            textSize = 22f
            setTextColor(0xfff3f3f3.toInt())
        }
        val subheading = TextView(this).apply {
            text = "17 Ultra reference rendering · independent profile"
            textSize = 13f
            setTextColor(0xffb8bbc2.toInt())
        }
        root.addView(heading)
        root.addView(subheading)

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        modeRow.addView(modeButton(ReferenceRenderer.Mode.DAY), weightParams())
        modeRow.addView(modeButton(ReferenceRenderer.Mode.NIGHT), weightParams())
        modeRow.addView(modeButton(ReferenceRenderer.Mode.PORTRAIT), weightParams())
        root.addView(modeRow)

        val frame = FrameLayout(this).apply {
            setBackgroundColor(0xff050505.toInt())
        }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            contentDescription = "Photo preview"
        }
        frame.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        frame.addView(imageView, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12); bottomMargin = dp(8) })

        status = TextView(this).apply {
            text = "Camera look: contrast, highlight rolloff and colour"
            textSize = 12f
            setTextColor(0xffc9cbd0.toInt())
        }
        root.addView(status)
        zoomLabel = TextView(this).apply {
            text = "Zoom · camera not ready"
            textSize = 12f
            setTextColor(0xffc9cbd0.toInt())
        }
        root.addView(zoomLabel)
        zoomSeek = SeekBar(this).apply {
            max = 100
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser || previewView.visibility != View.VISIBLE) return
                    applyZoom(value)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        root.addView(zoomSeek)
        strength = SeekBar(this).apply {
            max = 100
            progress = 75
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    styleStrength = value / 100f
                    status.text = if (sourceBitmap == null) "$cameraModeNote · $value% profile strength"
                    else "${modeName(activeMode)} rendering · $value% strength"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) { sourceBitmap?.let { render(it) } }
            })
        }
        root.addView(strength)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row1.addView(button("Import") { choosePhoto.launch("image/*") }, weightParams())
        row1.addView(button("Capture") { capture() }, weightParams())
        row1.addView(button("Camera") { showCamera() }, weightParams())
        root.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row2.addView(button("Save photo") { savePhoto() }, weightParams())
        row2.addView(button("Reset") { resetPhoto() }, weightParams())
        root.addView(row2)
        setContentView(root)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val extensionsFuture = ExtensionsManager.getInstanceAsync(this, provider)
                extensionsFuture.addListener({
                    try { extensionsManager = extensionsFuture.get() } catch (_: Exception) { extensionsManager = null }
                    bindCamera()
                }, ContextCompat.getMainExecutor(this))
            } catch (e: Exception) {
                message("Could not start this camera: ${e.localizedMessage ?: "unsupported camera configuration"}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        zoomObserver?.let { boundCamera?.cameraInfo?.zoomState?.removeObserver(it) }
        zoomObserver = null
        refreshExtensionAvailability()
        val baseSelector = CameraSelector.DEFAULT_BACK_CAMERA
        var selector = baseSelector
        var usingExtension = false
        val extensionMode = when (activeMode) {
            ReferenceRenderer.Mode.DAY -> null
            ReferenceRenderer.Mode.NIGHT -> ExtensionMode.NIGHT
            ReferenceRenderer.Mode.PORTRAIT -> ExtensionMode.BOKEH
        }
        cameraModeNote = when (activeMode) {
            ReferenceRenderer.Mode.DAY -> "Day capture"
            ReferenceRenderer.Mode.NIGHT -> "Night look only; no night capture extension on this camera"
            ReferenceRenderer.Mode.PORTRAIT -> "Portrait look only; no background blur on this camera"
        }
        if (extensionMode != null) {
            try {
                val manager = extensionsManager
                if (manager != null && manager.isExtensionAvailable(baseSelector, extensionMode)) {
                    selector = manager.getExtensionEnabledCameraSelector(baseSelector, extensionMode)
                    usingExtension = true
                    cameraModeNote = when (activeMode) {
                        ReferenceRenderer.Mode.NIGHT -> "Night capture enhancement available"
                        ReferenceRenderer.Mode.PORTRAIT -> "Portrait background blur available"
                        ReferenceRenderer.Mode.DAY -> "Day capture"
                    }
                }
            } catch (_: Exception) {
                selector = baseSelector
            }
        }
        try {
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            provider.unbindAll()
            imageCapture = capture
            boundCamera = provider.bindToLifecycle(this, selector, preview, capture)
            previewView.visibility = View.VISIBLE
            imageView.visibility = View.GONE
            zoomLabel.visibility = View.VISIBLE
            zoomSeek.visibility = View.VISIBLE
            observeZoom()
            status.text = "$cameraModeNote · ${strength.progress}% profile strength"
        } catch (e: Exception) {
            if (usingExtension) {
                try {
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                    provider.unbindAll()
                    imageCapture = capture
                    boundCamera = provider.bindToLifecycle(this, baseSelector, preview, capture)
                    previewView.visibility = View.VISIBLE
                    imageView.visibility = View.GONE
                    zoomLabel.visibility = View.VISIBLE
                    zoomSeek.visibility = View.VISIBLE
                    cameraModeNote = "${modeName(activeMode)} look only; capture extension unavailable"
                    if (activeMode == ReferenceRenderer.Mode.NIGHT) nightExtensionAvailable = false
                    if (activeMode == ReferenceRenderer.Mode.PORTRAIT) portraitExtensionAvailable = false
                    observeZoom()
                    status.text = cameraModeNote
                    return
                } catch (_: Exception) { }
            }
            message("Could not start this camera: ${e.localizedMessage ?: "unsupported camera configuration"}")
        }
    }

    private fun observeZoom() {
        val camera = boundCamera ?: return
        val observer = Observer<ZoomState> { state ->
            if (state == null) return@Observer
            val min = state.minZoomRatio
            val max = state.maxZoomRatio
            currentZoomRatio = state.zoomRatio
            zoomLabel.text = "Zoom · ${"%.1f".format(state.zoomRatio)}× (available ${"%.1f".format(min)}–${"%.1f".format(max)}×)"
            val progress = if (max <= min) 0 else (((state.zoomRatio - min) / (max - min)) * zoomSeek.max).toInt()
            if (zoomSeek.progress != progress) zoomSeek.progress = progress.coerceIn(0, zoomSeek.max)
            zoomSeek.isEnabled = max > min
        }
        zoomObserver = observer
        camera.cameraInfo.zoomState.observe(this, observer)
    }

    private fun applyZoom(progress: Int) {
        val camera = boundCamera ?: return
        val state = camera.cameraInfo.zoomState.value ?: return
        val ratio = state.minZoomRatio + (state.maxZoomRatio - state.minZoomRatio) * (progress / zoomSeek.max.toFloat())
        currentZoomRatio = ratio
        camera.cameraControl.setZoomRatio(ratio)
    }

    private fun selectMode(mode: ReferenceRenderer.Mode) {
        activeMode = mode
        refreshExtensionAvailability()
        updateModeButtons()
        sourceBitmap?.let { render(it) }
        if (previewView.visibility == View.VISIBLE && cameraProvider != null) bindCamera()
    }

    private fun refreshExtensionAvailability() {
        val manager = extensionsManager
        if (manager == null) {
            nightExtensionAvailable = false
            portraitExtensionAvailable = false
            return
        }
        val back = CameraSelector.DEFAULT_BACK_CAMERA
        try { nightExtensionAvailable = manager.isExtensionAvailable(back, ExtensionMode.NIGHT) }
        catch (_: Exception) { nightExtensionAvailable = false }
        try { portraitExtensionAvailable = manager.isExtensionAvailable(back, ExtensionMode.BOKEH) }
        catch (_: Exception) { portraitExtensionAvailable = false }
    }

    private fun modeButton(mode: ReferenceRenderer.Mode): Button = button(modeName(mode)) {
        selectMode(mode)
    }.also { modeButtons[mode] = it; updateModeButtons() }

    private fun updateModeButtons() {
        modeButtons.forEach { (mode, button) ->
            button.text = if (mode == activeMode) "${modeName(mode)} ✓" else modeName(mode)
            button.alpha = if (mode == activeMode) 1f else 0.72f
        }
    }

    private fun capture() {
        val capture = imageCapture ?: run { message("Camera is not ready yet."); return }
        val file = File(cacheDir, "capture-${System.currentTimeMillis()}.jpg")
        workingFile = file
        val zoomAtCapture = currentZoomRatio
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                runOnUiThread { loadFile(file, zoomAtCapture) }
            }
            override fun onError(error: ImageCaptureException) {
                runOnUiThread { message("Capture failed: ${error.message ?: "unknown error"}") }
            }
        })
    }

    private fun loadUri(uri: Uri) {
        status.text = "Loading photo…"
        cameraExecutor.execute {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri).use { boundsStream -> BitmapFactory.decodeStream(boundsStream, null, bounds) }
                val options = decodeOptions(bounds.outWidth, bounds.outHeight)
                val bitmap = contentResolver.openInputStream(uri).use { imageStream ->
                    BitmapFactory.decodeStream(imageStream, null, options)
                } ?: error("Unsupported photo")
                val oriented = orient(bitmap, uri)
                runOnUiThread { setSource(oriented, 1f) }
            } catch (e: Exception) {
                runOnUiThread { message("Could not open photo: ${e.localizedMessage ?: "unsupported file"}") }
            }
        }
    }

    private fun loadFile(file: File, zoomRatio: Float) {
        status.text = "Loading photo…"
        cameraExecutor.execute {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions(bounds.outWidth, bounds.outHeight))
                    ?: error("Could not decode photo")
                val oriented = orient(bitmap, file)
                runOnUiThread { setSource(oriented, zoomRatio) }
            } catch (e: Exception) {
                runOnUiThread { message("Could not process photo: ${e.localizedMessage ?: "unsupported file"}") }
            }
        }
    }

    private fun orient(bitmap: Bitmap, uri: Uri): Bitmap {
        return try {
            contentResolver.openInputStream(uri).use { stream ->
                val exif = ExifInterface(stream!!)
                rotate(bitmap, exif.rotationDegrees)
            }
        } catch (_: Exception) { bitmap }
    }

    private fun orient(bitmap: Bitmap, file: File): Bitmap {
        return try {
            val exif = ExifInterface(file)
            rotate(bitmap, exif.rotationDegrees)
        } catch (_: Exception) { bitmap }
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun decodeOptions(width: Int, height: Int): BitmapFactory.Options {
        var sample = 1
        while (width / sample.toLong() * (height / sample.toLong()) > 16_000_000L) sample *= 2
        return BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    }

    private fun setSource(bitmap: Bitmap, zoomRatio: Float) {
        sourceBitmap = bitmap
        sourceZoomRatio = zoomRatio
        previewView.visibility = View.GONE
        imageView.visibility = View.VISIBLE
        zoomLabel.visibility = View.GONE
        zoomSeek.visibility = View.GONE
        render(bitmap)
    }

    private fun render(source: Bitmap) {
        status.text = "Rendering ${strength.progress}%…"
        val renderStrength = styleStrength
        val renderMode = activeMode
        val renderZoom = sourceZoomRatio
        cameraExecutor.execute {
            try {
                val result = ReferenceRenderer.render(source, renderStrength, renderMode, renderZoom)
                runOnUiThread {
                    renderedBitmap?.takeIf { it !== source }?.recycle()
                    renderedBitmap = result
                    imageView.setImageBitmap(result)
                    status.text = profileStatus(renderMode)
                }
            } catch (e: Exception) {
                runOnUiThread { message("Rendering failed: ${e.localizedMessage ?: "not enough memory"}") }
            }
        }
    }

    private fun savePhoto() {
        val bitmap = renderedBitmap ?: run { message("Capture or import a photo first."); return }
        val saveCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        status.text = "Saving photo…"
        cameraExecutor.execute {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "UltraTone-${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UltraTone")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not create photo in gallery")
                contentResolver.openOutputStream(uri).use { stream ->
                    if (stream == null || !saveCopy.compress(Bitmap.CompressFormat.JPEG, 96, stream)) error("Photo encoding failed")
                }
                runOnUiThread { message("Saved to Pictures/UltraTone") }
            } catch (e: Exception) {
                runOnUiThread { message("Save failed: ${e.localizedMessage ?: "storage error"}") }
            } finally {
                saveCopy.recycle()
            }
        }
    }

    private fun resetPhoto() {
        sourceBitmap?.let { original ->
            styleStrength = 0.75f
            strength.progress = 75
            render(original)
        } ?: showCamera()
    }

    private fun showCamera() {
        sourceBitmap = null
        renderedBitmap?.recycle()
        renderedBitmap = null
        imageView.setImageDrawable(null)
        imageView.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        zoomLabel.visibility = View.VISIBLE
        zoomSeek.visibility = View.VISIBLE
        status.text = "Camera preview"
        if (cameraProvider != null) bindCamera()
        else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun modeName(mode: ReferenceRenderer.Mode) = when (mode) {
        ReferenceRenderer.Mode.DAY -> "Day"
        ReferenceRenderer.Mode.NIGHT -> "Night"
        ReferenceRenderer.Mode.PORTRAIT -> "Portrait"
    }
    private fun profileStatus(mode: ReferenceRenderer.Mode): String = when {
        sourceBitmap != null && mode == ReferenceRenderer.Mode.NIGHT ->
            "Night rendering · ${strength.progress}% strength; no extra multi-frame capture"
        sourceBitmap != null && mode == ReferenceRenderer.Mode.PORTRAIT ->
            "Portrait rendering · ${strength.progress}% strength; no added background blur"
        mode == ReferenceRenderer.Mode.DAY -> "Day look · ${strength.progress}% strength"
        mode == ReferenceRenderer.Mode.NIGHT -> "Night look · ${strength.progress}% strength" +
            if (nightExtensionAvailable) "" else " · tonal fallback, no multi-frame capture"
        else -> "Portrait look · ${strength.progress}% strength" +
            if (portraitExtensionAvailable) "" else " · background blur unavailable"
    }
    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        sourceBitmap?.recycle()
        renderedBitmap?.recycle()
        workingFile?.delete()
    }
}
