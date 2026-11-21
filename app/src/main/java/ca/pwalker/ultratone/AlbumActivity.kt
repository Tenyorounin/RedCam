package ca.pwalker.ultratone

import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.Executors
import java.util.concurrent.Future

class AlbumActivity : Activity() {
    private val workers = Executors.newFixedThreadPool(2)
    private val media = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val photos = mutableListOf<Pair<Long, String>>()
    private val thumbs = object : LruCache<Long, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private var loadGeneration = 0
    private var pendingId: Long? = null
    private var pendingPath: String? = null
    private var operationBusy = false
    private var restoredPosition: Pair<Int, Int>? = null
    private lateinit var grid: GridView
    private lateinit var status: TextView
    private val tile by lazy { (104 * resources.displayMetrics.density).toInt() }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        pendingId = state?.getLong("pendingId", -1L)?.takeIf { it >= 0 }
        pendingPath = state?.getString("pendingPath")
        restoredPosition = state?.let { it.getInt("position") to it.getInt("offset") }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Button(this).apply { text = "Back"; setOnClickListener { finish() } })
        header.addView(TextView(this).apply { text = "UltraTone"; textSize = 22f })
        layout.addView(header)
        status = TextView(this).apply { text = "Loading photos…"; gravity = Gravity.CENTER }
        layout.addView(status)
        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = tile
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = Tiles()
            setOnItemClickListener { _, _, position, _ -> viewPhoto(photos[position].first) }
            setOnItemLongClickListener { _, _, position, _ ->
                if (!operationBusy) photoOptions(photos[position])
                true
            }
        }
        layout.addView(grid)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        loadPhotos()
    }

    private fun loadPhotos() {
        val generation = ++loadGeneration
        val position = restoredPosition ?: (grid.firstVisiblePosition to (grid.getChildAt(0)?.top ?: 0))
        restoredPosition = null
        workers.execute {
            val result = runCatching {
                val filter = if (Build.VERSION.SDK_INT >= 29)
                    "relative_path IN (?, ?) AND is_pending = 0" else "_display_name LIKE ?"
                val args = if (Build.VERSION.SDK_INT >= 29)
                    arrayOf("Pictures/UltraTone/", "Pictures/UltraTone") else arrayOf("UltraTone-%.jpg")
                requireNotNull(contentResolver.query(media, arrayOf("_id", "_display_name"),
                    filter, args, "date_added DESC, _id DESC")).use { rows ->
                    buildList { while (rows.moveToNext()) add(rows.getLong(0) to rows.getString(1)) }
                }
            }
            runOnUiThread {
                if (isDestroyed || isFinishing || generation != loadGeneration) return@runOnUiThread
                result.onSuccess { photos.clear(); photos.addAll(it); thumbs.evictAll() }
                (grid.adapter as Tiles).notifyDataSetChanged()
                status.text = if (result.isFailure) "Couldn’t load UltraTone photos." else "No UltraTone photos yet."
                status.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
                grid.setSelectionFromTop(position.first.coerceAtMost((photos.size - 1).coerceAtLeast(0)), position.second)
            }
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        pendingId?.let { state.putLong("pendingId", it) }
        state.putString("pendingPath", pendingPath)
        state.putInt("position", grid.firstVisiblePosition)
        state.putInt("offset", grid.getChildAt(0)?.top ?: 0)
    }

    private fun photoOptions(photo: Pair<Long, String>) {
        AlertDialog.Builder(this).setTitle(photo.second)
            .setItems(arrayOf("Edit", "Delete", "Move")) { _, choice ->
                when (choice) {
                    0 -> {
                        val uri = ContentUris.withAppendedId(media, photo.first)
                        val edit = Intent(Intent.ACTION_EDIT).apply {
                            setDataAndType(uri, "image/jpeg")
                            clipData = android.content.ClipData.newRawUri("Photo", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        }
                        try { startActivity(Intent.createChooser(edit, "Edit photo")) }
                        catch (_: ActivityNotFoundException) { notice("No photo editor is installed.") }
                        catch (_: SecurityException) { notice("This photo cannot be opened for editing.") }
                    }
                    1 -> AlertDialog.Builder(this).setTitle("Delete photo?")
                        .setMessage("${photo.second} will be permanently deleted.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ -> changePhoto(photo.first, null) }.show()
                    2 -> chooseAlbum(photo.first)
                }
            }.show()
    }

    private fun chooseAlbum(id: Long) {
        if (Build.VERSION.SDK_INT < 29) {
            notice("Moving between albums requires Android 10 or later.")
            return
        }
        workers.execute {
            val paths = sortedSetOf("Pictures/")
            runCatching {
                contentResolver.query(media, arrayOf(MediaStore.Images.Media.RELATIVE_PATH),
                    "relative_path LIKE ?", arrayOf("Pictures/%"), null)?.use { rows ->
                    while (rows.moveToNext()) rows.getString(0)?.let { paths.add(it) }
                }
            }
            paths.removeAll { it.trimEnd('/') == "Pictures/UltraTone" }
            val albums = paths.toList()
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                AlertDialog.Builder(this).setTitle("Move to album")
                    .setItems((albums + "New album…").toTypedArray()) { _, index ->
                        if (index < albums.size) changePhoto(id, albums[index]) else newAlbum(id)
                    }.setNegativeButton("Cancel", null).show()
            }
        }
    }

    private fun newAlbum(id: Long) {
        val input = EditText(this).apply { hint = "Album name"; setSingleLine(true) }
        val dialog = AlertDialog.Builder(this).setTitle("New Pictures album").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Move", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty() || name == "." || name == ".." ||
                    name.any { it == '/' || it == '\\' || it.code < 32 } ||
                    name.equals("UltraTone", ignoreCase = true)) {
                    input.error = "Enter a different album name without slashes."
                } else {
                    dialog.dismiss()
                    changePhoto(id, "Pictures/$name/")
                }
            }
        }
        dialog.show()
    }

    private fun changePhoto(id: Long, path: String?, allowPermissionRequest: Boolean = true) {
        if (operationBusy) return
        operationBusy = true
        workers.execute {
            val uri = ContentUris.withAppendedId(media, id)
            val result = runCatching {
                val changed = if (path == null) contentResolver.delete(uri, null, null)
                else contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.RELATIVE_PATH, path)
                }, null, null)
                check(changed > 0) { "Photo no longer exists or could not be changed." }
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                operationBusy = false
                val error = result.exceptionOrNull()
                if (error is SecurityException && allowPermissionRequest) {
                    try {
                        val permission = when {
                            Build.VERSION.SDK_INT >= 30 -> MediaStore.createWriteRequest(contentResolver, listOf(uri))
                            Build.VERSION.SDK_INT >= 29 && error is RecoverableSecurityException -> error.userAction.actionIntent
                            else -> null
                        }
                        if (permission != null) {
                            pendingId = id
                            pendingPath = path
                            startIntentSenderForResult(permission.intentSender, 41, null, 0, 0, 0)
                            return@runOnUiThread
                        }
                    } catch (_: Exception) { pendingId = null; pendingPath = null }
                }
                if (result.isSuccess) {
                    notice(if (path == null) "Photo deleted." else "Moved to $path")
                    loadPhotos()
                } else notice("Could not ${if (path == null) "delete" else "move"} photo: ${error?.localizedMessage ?: "unknown error"}")
            }
        }
    }

    @Deprecated("Platform activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 41) {
            val id = pendingId
            val path = pendingPath
            pendingId = null
            pendingPath = null
            if (resultCode == RESULT_OK && id != null) changePhoto(id, path, false)
        }
    }

    private fun notice(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun viewPhoto(id: Long) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setPackage("com.google.android.apps.photos")
            setDataAndType(ContentUris.withAppendedId(media, id), "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Google Photos is unavailable.", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class Tiles : BaseAdapter() {
        override fun getCount() = photos.size
        override fun getItem(position: Int) = photos[position]
        override fun getItemId(position: Int) = getItem(position).first
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val image = recycled as? ImageView ?: ImageView(this@AlbumActivity).apply {
                layoutParams = AbsListView.LayoutParams(-1, tile)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            (image.tag as? Future<*>)?.cancel(false)
            val (id, name) = getItem(position)
            image.contentDescription = name
            val cached = thumbs.get(id)
            image.setImageBitmap(cached)
            if (cached == null) {
                val generation = loadGeneration
                image.tag = workers.submit {
                    val bitmap = runCatching {
                        if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(
                            ContentUris.withAppendedId(media, id), Size(tile, tile), null)
                        else @Suppress("DEPRECATION") MediaStore.Images.Thumbnails.getThumbnail(
                            contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
                    }.getOrNull() ?: return@submit
                    runOnUiThread {
                        if (!isDestroyed && generation == loadGeneration) {
                            thumbs.put(id, bitmap)
                            if (image.getTag(R.id.album_photo_id) == id) image.setImageBitmap(bitmap)
                        }
                    }
                }
            }
            image.setTag(R.id.album_photo_id, id)
            return image
        }
    }

    override fun onDestroy() {
        workers.shutdownNow()
        super.onDestroy()
    }
}
