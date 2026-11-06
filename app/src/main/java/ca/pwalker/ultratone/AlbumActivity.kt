package ca.pwalker.ultratone

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
    private lateinit var grid: GridView
    private lateinit var status: TextView
    private val tile by lazy { (104 * resources.displayMetrics.density).toInt() }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
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
        }
        layout.addView(grid)
        setContentView(layout)
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
                if (isDestroyed || isFinishing) return@runOnUiThread
                result.onSuccess { photos.addAll(it) }
                (grid.adapter as Tiles).notifyDataSetChanged()
                status.text = if (result.isFailure) "Couldn’t load UltraTone photos." else "No UltraTone photos yet."
                status.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
                if (state != null) grid.setSelectionFromTop(state.getInt("position"), state.getInt("offset"))
            }
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putInt("position", grid.firstVisiblePosition)
        state.putInt("offset", grid.getChildAt(0)?.top ?: 0)
    }

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
                image.tag = workers.submit {
                    val bitmap = runCatching {
                        if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(
                            ContentUris.withAppendedId(media, id), Size(tile, tile), null)
                        else @Suppress("DEPRECATION") MediaStore.Images.Thumbnails.getThumbnail(
                            contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
                    }.getOrNull() ?: return@submit
                    thumbs.put(id, bitmap)
                    runOnUiThread {
                        if (!isDestroyed && image.getTag(R.id.album_photo_id) == id)
                            image.setImageBitmap(bitmap)
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
