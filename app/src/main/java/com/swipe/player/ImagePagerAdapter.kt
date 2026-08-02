package com.swipe.player

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar

/**
 * Galerie de POZE (swipe vertical, separat de videoclipuri).
 * Fiecare pagină = o poză full-screen, încărcată eficient (downsampliată la dimensiunea ecranului).
 */
class ImagePagerAdapter(
    private val context: Context,
    private val items: List<Uri>
) : androidx.recyclerview.widget.RecyclerView.Adapter<ImagePagerAdapter.ImgVH>() {

    private val TAG = "ImagePagerAdapter"

    class ImgVH(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.imgPhoto)
        val loading: ProgressBar = view.findViewById(R.id.imgLoading)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImgVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false)
        return ImgVH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ImgVH, position: Int) {
        val uri = items[position]
        holder.image.setImageDrawable(null)
        holder.loading.visibility = View.VISIBLE
        // decodează în background, ca să nu blochez pe main thread
        Thread {
            try {
                val bmp = decodeSampled(uri)
                holder.image.post {
                    if (bmp != null) {
                        holder.image.setImageBitmap(bmp)
                    }
                    holder.loading.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Eroare încărcare poză", e)
                holder.image.post { holder.loading.visibility = View.GONE }
            }
        }.start()
    }

    /** decodează bitmap-ul downsampelat, astfel încât să nu sară memoria pe poze mari */
    private fun decodeSampled(uri: Uri): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
        catch (e: Exception) { return null }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val vizW = context.resources.displayMetrics.widthPixels
        val vizH = context.resources.displayMetrics.heightPixels
        var sample = 1
        while (bounds.outWidth / sample > vizW * 1.5 || bounds.outHeight / sample > vizH * 1.5) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (e: Exception) { null }
    }
}
