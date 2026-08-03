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
import android.widget.Switch
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
        /** schimbare secunde de derulare per swipe/buton (2..30) */
        fun onSeekStepChange(stepSec: Int)
        /** redare în fundal: continuă sunetul la blocare/ieșire */
        fun onBackgroundPlayChange(activat: Boolean)
        /** autoplay continuu la următorul videoclip */
        fun onAutoOrderChange(activat: Boolean)
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
    private var currentSeekStep = 10
    private var currentBackgroundPlay = false
    private var currentAutoOrder = true

    fun setInitial(
        brightness: Float,
        volume: Float,
        resH: Int,
        seekStep: Int = 10,
        backgroundPlay: Boolean = false,
        autoOrder: Boolean = true
    ) {
        currentBrightness = brightness.coerceIn(0.15f, 1f)
        currentVolume = volume.coerceIn(0f, 1f)
        currentResH = resH
        currentSeekStep = seekStep.coerceIn(2, 30)
        currentBackgroundPlay = backgroundPlay
        currentAutoOrder = autoOrder
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

        // ---- Redare (opțiuni on/off pentru comportamentul playerului) ----
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

        // ---- Derulare (seek): secunde per swipe / buton ⏪⏩ (2..30) ----
        root.addView(label("Secunde derulare (swipe & ⏪⏩)"))
        val txtSeekStep = TextView(requireContext()).apply {
            text = "${currentSeekStep} s"
            textSize = 16f
            setTextColor(Color.WHITE)
        }
        root.addView(txtSeekStep)
        val seekStep = SeekBar(requireContext()).apply {
            max = 28 // 2..30 => indiciu: progress+2
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

        // ---- Butoane ----
        val btnClear = Button(requireContext()).apply {
            text = "🧹 Șterge bibliotecă + istoric"
            setOnClickListener { listener?.onClearHistory() }
        }
        root.addView(btnClear)

        val btnReset = Button(requireContext()).apply {
            text = "Reset"
            setOnClickListener {
                currentBrightness = 1f; currentVolume = 1f; currentResH = 0
                currentSeekStep = 10
                currentBackgroundPlay = false
                currentAutoOrder = true
                seekLumina.progress = 1000; seekVolum.progress = 1000
                seekStep.progress = currentSeekStep - 2
                txtSeekStep.text = "${currentSeekStep} s"
                swBackground.isChecked = false
                swAuto.isChecked = true
                listener?.onSeekStepChange(currentSeekStep)
                listener?.onBackgroundPlayChange(false)
                listener?.onAutoOrderChange(true)
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

    /**
     * Rând de setare on/off (switch) cu titlu + descriere.
     * Returnează [SwitchRow] cu referințele la view și switch (pentru Reset).
     */
    private fun switchRow(
        title: String,
        desc: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ): SwitchRow {
        val sw = Switch(requireContext()).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        }
        val txtCol = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        txtCol.addView(TextView(requireContext()).apply {
            text = title
            textSize = 15f
            setTextColor(Color.WHITE)
        })
        if (desc.isNotBlank()) {
            txtCol.addView(TextView(requireContext()).apply {
                text = desc
                textSize = 12f
                setTextColor(Color.LTGRAY)
            })
        }
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(txtCol)
        row.addView(sw)
        return SwitchRow(row, sw)
    }

    private class SwitchRow(val view: View, val switch: Switch)
}