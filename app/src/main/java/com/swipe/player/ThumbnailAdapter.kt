package com.swipe.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import java.util.concurrent.Executors

/**
 * Miniaturi (thumbnail-uri) pentru galeria de poze — navigare rapidă (sus/jos sau prin listă).
 * Apăsarea unei miniature sare la poza respectivă din galerie.
 */
class ThumbnailAdapter(
    private val context: Context,
    private val items: List<Uri>,
    private val currentIndex: Int,
    private val onClick: (position: Int) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<ThumbnailAdapter.ThVH>() {

    private val loader = Executors.newSingleThreadExecutor()

    class ThVH(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.thumbImg)
        var pending: String? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_thumb, parent, false)
        return ThVH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ThVH, position: Int) {
        val uri = items[position]
        val key = uri.toString()
        holder.pending = key
        holder.image.setImageDrawable(null)
        holder.image.alpha = if (position == currentIndex) 1f else 0.55f
        holder.itemView.setOnClickListener { onClick(position) }

        loader.execute {
            val bmp = decodeThumb(uri)
            holder.image.post {
                if (holder.pending == key && bmp != null && !bmp.isRecycled) {
                    holder.image.setImageBitmap(bmp)
                }
            }
        }
    }

    override fun onViewRecycled(holder: ThVH) {
        super.onViewRecycled(holder)
        holder.pending = null
        holder.image.setImageDrawable(null)
    }

    private fun decodeThumb(uri: Uri): Bitmap? {
        val target = context.resources.getDimensionPixelSize(
            android.R.dimen.notification_large_icon_height)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (e: Exception) { return null }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / sample > target || opts.outHeight / sample > target) {
            sample *= 2
        }
        val decode = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decode)
            }
        } catch (e: Exception) { null } ?: return null
        val deg = exifRot(uri)
        if (deg == 0) return bmp
        val m = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
        val rot = try { Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true) }
            catch (e: Exception) { return bmp }
        if (rot !== bmp) bmp.recycle()
        return rot
    }

    private fun exifRot(uri: Uri): Int {
        return try {
            val fd = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: return 0
            fd.use {
                val exif = android.media.ExifInterface(fd.fileDescriptor)
                when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL)) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (e: Exception) { 0 }
    }
}