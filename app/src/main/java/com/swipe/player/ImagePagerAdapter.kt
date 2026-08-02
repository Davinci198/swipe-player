package com.swipe.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import java.util.concurrent.Executors

/**
 * Galerie de POZE (swipe vertical, separat de videoclipuri).
 * Încărcare eficientă și SIGURĂ la nivel de memorie, ca să NU se închidă aplicația
 * la selectarea mai multor poze (crash multi-select = OOM la decodare simultană):
 *  - LruCache pe bitmap-uri decodate (cheie = uri),
 *  - downsampling la dimensiunea ecranului + RGB_565 (jumătate de memorie),
 *  - decodare SERIALIZATĂ (un singur thread worker) => fără vârfuri de heap,
 *  - reciclarea bitmap-urilor înlocuite / reciclate de RecyclerView.
 * Include ZOOM (ZoomableImageView) + buton REDENUMIRE (creion), delegat la callback.
 */
class ImagePagerAdapter(
    private val context: Context,
    private val items: List<Uri>
) : androidx.recyclerview.widget.RecyclerView.Adapter<ImagePagerAdapter.ImgVH>() {

    private val loaderQueue = Executors.newSingleThreadExecutor()
    private val cache: LruCache<String, Bitmap>

    init {
        val maxMem = (Runtime.getRuntime().maxMemory() / 8).toInt()
        cache = object : LruCache<String, Bitmap>(maxMem.coerceAtLeast(8 * 1024 * 1024)) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }

    class ImgVH(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.imgPhoto)
        val loading: ProgressBar = view.findViewById(R.id.imgLoading)
        var pendingUri: String? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImgVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false)
        return ImgVH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ImgVH, position: Int) {
        val uri = items[position]
        val key = uri.toString()
        holder.pendingUri = key
        recyclaBitmapActual(holder, key)
        holder.image.setImageDrawable(null)
        holder.loading.visibility = View.VISIBLE

        cache.get(key)?.let { bmp ->
            if (holder.pendingUri == key && !bmp.isRecycled) {
                holder.image.setImageBitmap(bmp)
                holder.loading.visibility = View.GONE
            }
            return
        }

        loaderQueue.execute {
            val bmp = decodeSampled(uri)
            if (bmp != null) cache.put(key, bmp)
            holder.image.post {
                if (holder.pendingUri == key && bmp != null && !bmp.isRecycled) {
                    holder.image.setImageBitmap(bmp)
                }
                holder.loading.visibility = View.GONE
            }
        }
    }

    override fun onViewRecycled(holder: ImgVH) {
        super.onViewRecycled(holder)
        holder.pendingUri = null
        recicleazaDacaNuEInCache(holder)
        holder.image.setImageDrawable(null)
    }

    private fun recyclaBitmapActual(holder: ImgVH, keyNou: String) {
        val d = holder.image.drawable
        if (d is BitmapDrawable) {
            val b = d.bitmap
            if (b != null && !b.isRecycled && cache.get(keyNou) !== b && !isInCache(b)) {
                b.recycle()
            }
        }
    }

    private fun recicleazaDacaNuEInCache(holder: ImgVH) {
        val d = holder.image.drawable
        if (d is BitmapDrawable) {
            val b = d.bitmap
            if (b != null && !b.isRecycled && !isInCache(b)) b.recycle()
        }
    }

    private fun isInCache(b: Bitmap): Boolean {
        for (e in cache.snapshot().values) {
            if (e === b) return true
        }
        return false
    }

    private fun decodeSampled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        } catch (e: Exception) { return null }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val vizW = context.resources.displayMetrics.widthPixels
        val vizH = context.resources.displayMetrics.heightPixels
        var sample = 1
        while (bounds.outWidth / sample > vizW || bounds.outHeight / sample > vizH) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (e: Exception) { null } ?: return null
        // rotește după EXIF (ideal #3 — pozele cu telefonul în lateral apar drept)
        return aplicaRotatie(uri, bmp)
    }

    private fun aplicaRotatie(uri: Uri, bmp: Bitmap): Bitmap {
        val deg = citesteExif(uri)
        if (deg == 0) return bmp
        val m = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
        val rot = try {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } catch (e: Exception) { return bmp }
        if (rot !== bmp) bmp.recycle()
        return rot
    }

    private fun citesteExif(uri: Uri): Int {
        return try {
            val fd = context.contentResolver.openAssetFileDescriptor(uri, "r")
                ?: return 0
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