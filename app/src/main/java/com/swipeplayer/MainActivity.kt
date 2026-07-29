package com.swipeplayer

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private lateinit var tvStatus: TextView
    private lateinit var viewPager: ViewPager2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        viewPager = findViewById(R.id.viewPager)

        // Example video list (replace with your sources or feed)
        val videos = listOf(
            "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4",
            "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_480x270.mp4"
        )

        viewPager.orientation = ViewPager2.ORIENTATION_VERTICAL
        viewPager.adapter = VideoPagerAdapter(this, videos)

        // Init native DragonBones bridge
        val ok = DragonBonesBridge.init()
        val version = DragonBonesBridge.getVersion()
        val msg = "DragonBones Native v$version\nStatus: ${if (ok) "✓ LOADED" else "✗ FAILED"}"
        tvStatus.text = msg
        Log.i(TAG, "DragonBones init: $ok, version=$version")
        Toast.makeText(this, "DragonBones $version", Toast.LENGTH_LONG).show()
    }
}
