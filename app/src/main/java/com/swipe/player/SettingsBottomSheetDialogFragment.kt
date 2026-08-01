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
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Panou de setări (bottom sheet) - luminozitate, volum, rezoluție, Reset.
 */
class SettingsBottomSheetDialogFragment : BottomSheetDialogFragment() {

    interface Listener {
        fun onSettingsApply(brightness: Float, volume: Float, resolutionH: Int)
        fun onSettingsReset()
    }

    private var listener: Listener? = null
    private var currentBrightness = 1f
    private var currentVolume = 1f
    private var currentResH = 0

    /** apelată de MainActivity înainte de show() pentru a propaga starea actuală */
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

        // ---- Luminozitate ----
        root.addView(label("Luminozitate"))
        val seekLumina = SeekBar(requireContext()).apply {
            max = 1000
            progress = (currentBrightness * 1000).toInt()
        }
        root.addView(seekLumina)

        // ---- Volum ----
        root.addView(label("Volum"))
        val seekVolum = SeekBar(requireContext()).apply {
            max = 1000
            progress = (currentVolume * 1000).toInt()
        }
        root.addView(seekVolum)

        // ---- Rezoluție ----
        root.addView(label("Rezoluție"))
        val opts = listOf(
            Triple("Auto", 0, Int.MAX_VALUE to Int.MAX_VALUE),
            Triple("720p", 720, 1280 to 720),
            Triple("1080p", 1080, 1920 to 1080)
        )
        val radio = RadioGroup(requireContext()).apply { orientation = RadioGroup.VERTICAL }
        val idRes = HashMap<Int, Pair<Int, Pair<Int, Int>>>()
        opts.forEach { (nume, h, wh) ->
            val rb = RadioButton(requireContext()).apply {
                text = nume
                setTextColor(Color.WHITE)
                id = View.generateViewId()
            }
            radio.addView(rb)
            idRes[rb.id] = Pair(h, wh)
            if (h == currentResH) rb.isChecked = true
        }
        root.addView(radio)

        // ---- Butoane ----
        val btnReset = Button(requireContext()).apply {
            text = "Reset"
            setOnClickListener {
                currentBrightness = 1f; currentVolume = 1f
                seekLumina.progress = 1000; seekVolum.progress = 1000
                listener?.onSettingsReset()
            }
        }
        root.addView(btnReset)

        val btnApply = Button(requireContext()).apply {
            text = "Aplică"
            setOnClickListener {
                val lum = seekLumina.progress / 1000f
                val vol = seekVolum.progress / 1000f
                val checked = radio.checkedRadioButtonId
                val (h, _) = idRes[checked] ?: (0 to (Int.MAX_VALUE to Int.MAX_VALUE))
                currentBrightness = lum; currentVolume = vol; currentResH = h
                listener?.onSettingsApply(lum, vol, h)
                dismiss()
            }
        }
        root.addView(btnApply)

        return root
    }

    private fun label(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.WHITE)
    }
}
