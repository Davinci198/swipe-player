package com.swipe.player

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Panou de setări (bottom sheet) - luminozitate, volum, rezoluție, Reset, Șterge istoric.
 * Elementele se aplică NATIV / în timp real (fără buton "Aplică"):
 *  - luminozitate & volum se aplică imediat când se mișcă sliderul
 *  - rezoluția se aplică la schimbarea selecției
 */
class SettingsBottomSheetDialogFragment : BottomSheetDialogFragment() {

    interface Listener {
        /** slider luminozitate, aplicat în timp real (native de sistem, pe fereastră) */
        fun onBrightnessChange(brightness: Float)
        /** slider volum, aplicat în timp real pe playerul activ */
        fun onVolumeChange(volume: Float)
        /** schimbare rezoluție (Auto=0, 720, 1080) */
        fun onResolutieChange(resolutionH: Int)
        /** șterge doar istoricul de vizionare (nu și fișierele locale) */
        fun onClearHistory()
        /** reset la valori implicite */
        fun onReset()
        /** alege videoclipuri din telefon (din setări) */
        fun onChooseVideos()
        /** alege poze din telefon (din setări) */
        fun onChoosePhotos()
    }

    private var listener: Listener? = null
    private var currentBrightness = 1f
    private var currentVolume = 1f
    private var currentResH = 0

    fun setInitial(brightness: Float, volume: Float, resH: Int) {
        currentBrightness = brightness.coerceIn(0.15f, 1f)
        currentVolume = volume.coerceIn(0f, 1f)
        currentResH = resH
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (listener == null) listener = context as? Listener ?: activity as? Listener
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 48)
        }

        // ---- Titlu ----
        root.addView(TextView(requireContext()).apply {
            text = "⚙️ Setări"
            textSize = 20f
            setTextColor(Color.WHITE)
        })

        // ---- Alegere fișiere (videoclipuri / poze) ----
        root.addView(label("Bibliotecă"))
        root.addView(Button(requireContext()).apply {
            text = "+ ALEGE VIDEOCLIPURI"
            setOnClickListener {
                listener?.onChooseVideos()
                dismiss()
            }
        })
        root.addView(Button(requireContext()).apply {
            text = "+ ALEGE POZE (galerie)"
            setOnClickListener {
                listener?.onChoosePhotos()
                dismiss()
            }
        })

        // ---- Luminozitate (aplicată live, native de sistem) ----
        root.addView(label("Luminozitate (live)"))
        val seekLumina = SeekBar(requireContext()).apply {
            max = 1000
            progress = (currentBrightness * 1000).toInt()
            setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    listener?.onBrightnessChange(progress / 1000f)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        root.addView(seekLumina)

        // ---- Volum (aplicat live pe playerul activ) ----
        root.addView(label("Volum (live)"))
        val seekVolum = SeekBar(requireContext()).apply {
            max = 1000
            progress = (currentVolume * 1000).toInt()
            setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    listener?.onVolumeChange(progress / 1000f)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        root.addView(seekVolum)

        // ---- Rezoluție (aplicată la schimbare) ----
        root.addView(label("Rezoluție"))
        val opts = listOf(
            Triple("Auto", 0, Int.MAX_VALUE to Int.MAX_VALUE),
            Triple("720p", 720, 1280 to 720),
            Triple("1080p", 1080, 1920 to 1080)
        )
        val radio = RadioGroup(requireContext()).apply {
            orientation = RadioGroup.VERTICAL
        }
        val idRes = HashMap<Int, Int>() // id -> inaltime (0, 720, 1080)
        opts.forEach { (nume, h, wh) ->
            val rb = RadioButton(requireContext()).apply {
                text = nume
                setTextColor(Color.WHITE)
                id = View.generateViewId()
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

        // ---- Butoane ----
        val btnClear = Button(requireContext()).apply {
            text = "Șterge istoric vizionare"
            setOnClickListener { listener?.onClearHistory() }
        }
        root.addView(btnClear)

        val btnReset = Button(requireContext()).apply {
            text = "Reset"
            setOnClickListener {
                currentBrightness = 1f; currentVolume = 1f; currentResH = 0
                seekLumina.progress = 1000; seekVolum.progress = 1000
                listener?.onReset()
                Toast.makeText(requireContext(), "Setări resetate", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(btnReset)

        return root
    }

    private fun label(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.WHITE)
    }
}