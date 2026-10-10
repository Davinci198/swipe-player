package com.swipe.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Panou de setări (bottom sheet) - luminozitate, volum, rezoluție, Reset, Șterge istoric.
 * Elementele se aplică NATIV / în timp real (fără buton "Aplică"):
 *  - luminozitate & volum se aplică imediat când se mișcă sliderul
 *  - rezoluția se aplică la schimbarea selecției
 */
class SettingsBottomSheetDialogFragment : BottomSheetDialogFragment() {

    interface Listener {
        fun onBrightnessChange(brightness: Float)
        fun onVolumeChange(volume: Float)
        fun onResolutieChange(resolutionH: Int)
        fun onSeekStepChange(stepSec: Int)
        fun onBackgroundPlayChange(activat: Boolean)
        fun onAutoOrderChange(activat: Boolean)
        fun onClearHistory()
        fun onReset()
        fun onChooseVideos()
        fun onChoosePhotos()
        fun onCtrlVideoChange(activat: Boolean)
        fun onCtrlPhotoChange(activat: Boolean)
        fun onPlaylistChange(activat: Boolean)
    }

    private var listener: Listener? = null
    private var currentBrightness = 1f
    private var currentVolume = 1f
    private var currentResH = 0
    private var currentSeekStep = 10
    private var currentBackgroundPlay = false
    private var currentAutoOrder = true
    private var currentCtrlVideo = true
    private var currentCtrlPhoto = true
    private var currentPlaylist = true

    fun setInitial(
        brightness: Float,
        volume: Float,
        resH: Int,
        seekStep: Int = 10,
        backgroundPlay: Boolean = false,
        autoOrder: Boolean = true,
        ctrlVideo: Boolean = true,
        ctrlPhoto: Boolean = true,
        playlist: Boolean = true
    ) {
        currentBrightness = brightness.coerceIn(0.15f, 1f)
        currentVolume = volume.coerceIn(0f, 1f)
        currentResH = resH
        currentSeekStep = seekStep.coerceIn(2, 30)
        currentBackgroundPlay = backgroundPlay
        currentAutoOrder = autoOrder
        currentCtrlVideo = ctrlVideo
        currentCtrlPhoto = ctrlPhoto
        currentPlaylist = playlist
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (listener == null) listener = context as? Listener ?: activity as? Listener
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            navigationBarColor = Color.rgb(12, 12, 17)
            setDimAmount(0.64f)
        }
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.let { bottomSheet ->
                bottomSheet.setBackgroundColor(Color.TRANSPARENT)
                BottomSheetBehavior.from(bottomSheet).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(32))
        }

        root.addView(View(requireContext()).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(4).toFloat()
                setColor(Color.rgb(110, 118, 128))
            }
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(20)
            }
        })

        root.addView(TextView(requireContext()).apply {
            text = "Setări"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })

        root.addView(label("Bibliotecă"))
        root.addView(actionButton("Alege videoclipuri") {
            listener?.onChooseVideos()
            dismiss()
        })
        root.addView(actionButton("Alege poze din galerie") {
            listener?.onChoosePhotos()
            dismiss()
        })

        root.addView(label("Redare"))
        val rowBg = switchRow(
            title = "Redare în fundal",
            desc = "Continuă sunetul când blochezi ecranul sau minimizezi aplicația.",
            initial = currentBackgroundPlay
        ) { activat -> listener?.onBackgroundPlayChange(activat) }
        val swBackground = rowBg.switch
        root.addView(rowBg.view)
        val rowAuto = switchRow(
            title = "Autoplay continuu",
            desc = "Trece automat la videoclipul următor când se termină cel curent.",
            initial = currentAutoOrder
        ) { activat -> listener?.onAutoOrderChange(activat) }
        val swAuto = rowAuto.switch
        root.addView(rowAuto.view)

        root.addView(label("Vizibilitate butoane și liste"))
        val rowCtrlVideo = switchRow(
            title = "Video: butoane de control",
            desc = "Afișează play/pause și derularea la atingere, în modul Video.",
            initial = currentCtrlVideo
        ) { activat -> listener?.onCtrlVideoChange(activat) }
        val swCtrlVideo = rowCtrlVideo.switch
        root.addView(rowCtrlVideo.view)
        val rowCtrlPhoto = switchRow(
            title = "Poze: butoane de control",
            desc = "Afișează luminozitatea, volumul, redenumirea, ștergerea și favoritele.",
            initial = currentCtrlPhoto
        ) { activat -> listener?.onCtrlPhotoChange(activat) }
        val swCtrlPhoto = rowCtrlPhoto.switch
        root.addView(rowCtrlPhoto.view)
        val rowPlaylist = switchRow(
            title = "Liste de redare (miniaturi)",
            desc = "Afișează navigarea rapidă prin miniaturile pozelor.",
            initial = currentPlaylist
        ) { activat -> listener?.onPlaylistChange(activat) }
        val swPlaylist = rowPlaylist.switch
        root.addView(rowPlaylist.view)

        root.addView(label("Luminozitate live"))
        val seekLumina = SeekBar(requireContext()).apply {
            max = 1000
            progress = (currentBrightness * 1000).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 179, 0))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 179, 0))
            setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    listener?.onBrightnessChange(progress / 1000f)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        root.addView(seekLumina)

        root.addView(label("Volum live"))
        val seekVolum = SeekBar(requireContext()).apply {
            max = 1000
            progress = (currentVolume * 1000).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 42, 61))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 42, 61))
            setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    listener?.onVolumeChange(progress / 1000f)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        root.addView(seekVolum)

        root.addView(label("Rezoluție"))
        val opts = listOf(
            Triple("Auto", 0, Int.MAX_VALUE to Int.MAX_VALUE),
            Triple("720p", 720, 1280 to 720),
            Triple("1080p", 1080, 1920 to 1080)
        )
        val radio = RadioGroup(requireContext()).apply {
            orientation = RadioGroup.VERTICAL
        }
        val idRes = HashMap<Int, Int>()
        opts.forEach { (nume, h, _) ->
            val rb = RadioButton(requireContext()).apply {
                text = nume
                setTextColor(Color.WHITE)
                id = View.generateViewId()
                minHeight = dp(48)
            }
            radio.addView(rb)
            idRes[rb.id] = h
            if (h == currentResH) rb.isChecked = true
        }
        radio.setOnCheckedChangeListener { _, checkedId ->
            val h = idRes[checkedId] ?: 0
            listener?.onResolutieChange(h)
            currentResH = h
        }
        root.addView(radio)

        root.addView(label("Secunde derulare (swipe)"))
        val txtSeekStep = TextView(requireContext()).apply {
            text = "${currentSeekStep} s"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        root.addView(txtSeekStep)
        val seekStep = SeekBar(requireContext()).apply {
            max = 28
            progress = currentSeekStep - 2
            setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val sec = progress + 2
                    currentSeekStep = sec
                    txtSeekStep.text = "$sec s"
                    listener?.onSeekStepChange(sec)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        root.addView(seekStep)

        root.addView(label("Statistici"))
        run {
            val st = MemoryManager.getInstance(requireContext()).getStatistici()
            val difTotal = st["timpTotalSecunde"] as? Int ?: 0
            root.addView(TextView(requireContext()).apply {
                text = """
                    Vizionări totale: ${st["totalVizionari"]}
                    Videoclipuri unice: ${st["videouriUnice"]}
                    Favorite: ${st["totalFavorite"]}
                    Timp total vizionat: ${MemoryManager.getInstance(requireContext()).formateazaDurata(difTotal)}
                """.trimIndent()
                textSize = 14f
                setTextColor(Color.LTGRAY)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = requireContext().getDrawable(R.drawable.bg_stats_card)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
        }

        root.addView(label("Acțiuni"))
        root.addView(actionButton("Șterge biblioteca + istoricul", destructive = true) {
            listener?.onClearHistory()
        })

        val btnReset = actionButton("Resetează setările") {
            currentBrightness = 1f; currentVolume = 1f; currentResH = 0
            currentSeekStep = 10
            currentBackgroundPlay = false
            currentAutoOrder = true
            currentCtrlVideo = true
            currentCtrlPhoto = true
            currentPlaylist = true
            seekLumina.progress = 1000
            seekVolum.progress = 1000
            seekStep.progress = currentSeekStep - 2
            txtSeekStep.text = "${currentSeekStep} s"
            swBackground.isChecked = false
            swAuto.isChecked = true
            swCtrlVideo.isChecked = true
            swCtrlPhoto.isChecked = true
            swPlaylist.isChecked = true
            listener?.onSeekStepChange(currentSeekStep)
            listener?.onBackgroundPlayChange(false)
            listener?.onAutoOrderChange(true)
            listener?.onCtrlVideoChange(true)
            listener?.onCtrlPhotoChange(true)
            listener?.onPlaylistChange(true)
            listener?.onReset()
            Toast.makeText(requireContext(), "Setări resetate", Toast.LENGTH_SHORT).show()
        }
        root.addView(btnReset)

        return NestedScrollView(requireContext()).apply {
            isFillViewport = true
            isNestedScrollingEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setBackgroundResource(R.drawable.bg_settings_sheet)
            addView(root)
        }
    }

    private fun label(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(255, 112, 124))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(20)
            bottomMargin = dp(8)
        }
    }

    private fun actionButton(
        text: String,
        destructive: Boolean = false,
        onClick: () -> Unit
    ): Button = Button(requireContext()).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minHeight = dp(52)
        minimumHeight = dp(52)
        setTextColor(Color.WHITE)
        setPadding(dp(18), 0, dp(18), 0)
        val buttonSurface = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(if (destructive) Color.rgb(80, 20, 28) else Color.rgb(30, 30, 39))
            setStroke(dp(1), if (destructive) Color.rgb(255, 76, 92) else Color.rgb(72, 72, 84))
        }
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.argb(48, 255, 255, 255)),
            buttonSurface,
            null
        )
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(8)
        }
        setOnClickListener { onClick() }
    }

    /** Rând de setare on/off cu titlu + descriere. */
    private fun switchRow(
        title: String,
        desc: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ): SwitchRow {
        val sw = Switch(requireContext()).apply {
            isChecked = initial
            minWidth = dp(52)
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        }
        val txtCol = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        txtCol.addView(TextView(requireContext()).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        if (desc.isNotBlank()) {
            txtCol.addView(TextView(requireContext()).apply {
                text = desc
                textSize = 12f
                setTextColor(Color.LTGRAY)
                setPadding(0, dp(3), 0, 0)
            })
        }
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
            background = requireContext().getDrawable(R.drawable.bg_settings_row)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
        row.addView(txtCol)
        row.addView(sw)
        return SwitchRow(row, sw)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private class SwitchRow(val view: View, val switch: Switch)
}
