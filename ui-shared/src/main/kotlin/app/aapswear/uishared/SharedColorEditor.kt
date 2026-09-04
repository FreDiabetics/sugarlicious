package app.aapswear.uishared

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import app.aapswear.model.ArgbColor
import kotlin.math.roundToInt

/** One functional color editor for classic Mobile/Wear/Collector surfaces. Layout stays responsive. */
object SharedColorEditor {
    private val recentColors = ArrayDeque<Int>()
    private val presets = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF6DE892.toInt(), 0xFFFF5C69.toInt(), 0xFFFFD040.toInt(), 0xFF64BFFF.toInt())
    fun show(
        activity: Activity,
        title: String,
        initialArgb: Int,
        surfaceArgb: Int,
        textArgb: Int,
        borderArgb: Int,
        defaultArgb: Int,
        onChange: (Int) -> Unit,
        onReset: () -> Unit,
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        fun background(fill: Int, radius: Float = 14f) = GradientDrawable().apply {
            setColor(fill); setStroke(dp(1), borderArgb); cornerRadius = radius * density
        }
        val hsv = FloatArray(3).also { Color.colorToHSV(initialArgb, it) }
        var hue = hsv[0]; var saturation = hsv[1]; var brightness = hsv[2]; var alpha = Color.alpha(initialArgb) / 255f
        var updating = false
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); setBackgroundColor(surfaceArgb) }
        val preview = android.view.View(activity).apply { background = background(initialArgb) }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        val hex = EditText(activity).apply {
            setText(ArgbColor.format(initialArgb)); inputType = InputType.TYPE_CLASS_TEXT; filters = arrayOf(InputFilter.LengthFilter(9)); setTextColor(textArgb)
        }
        root.addView(hex)
        val rgb = TextView(activity).apply { setTextColor(textArgb); textSize = 10f }
        root.addView(rgb)
        fun color() = Color.HSVToColor((alpha * 255).roundToInt(), floatArrayOf(hue, saturation, brightness))
        fun refresh(persist: Boolean = true) {
            val value = color(); preview.background = background(value); rgb.text = "R ${Color.red(value)} · G ${Color.green(value)} · B ${Color.blue(value)} · A ${Color.alpha(value)}"
            if (!updating) { updating = true; hex.setText(ArgbColor.format(value)); hex.setSelection(hex.length()); updating = false }
            if (persist) { remember(value); onChange(value) }
        }
        fun slider(label: String, max: Int, progress: Int, update: (Int) -> Unit) {
            root.addView(TextView(activity).apply { text = label; textSize = 10f; setTextColor(textArgb) })
            root.addView(SeekBar(activity).apply {
                this.max = max; this.progress = progress
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(view: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) { update(value); refresh() } }
                    override fun onStartTrackingTouch(view: SeekBar?) = Unit
                    override fun onStopTrackingTouch(view: SeekBar?) = Unit
                })
            })
        }
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                ArgbColor.parse(s?.toString())?.let { value ->
                    Color.colorToHSV(value, hsv); hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]; alpha = Color.alpha(value) / 255f
                    preview.background = background(value); rgb.text = "R ${Color.red(value)} · G ${Color.green(value)} · B ${Color.blue(value)} · A ${Color.alpha(value)}"; onChange(value)
                }
            }
        })
        slider("Farbton", 360, hue.roundToInt()) { hue = it.toFloat() }
        slider("Sättigung", 100, (saturation * 100).roundToInt()) { saturation = it / 100f }
        slider("Helligkeit", 100, (brightness * 100).roundToInt()) { brightness = it / 100f }
        slider("Deckkraft / Alpha", 100, (alpha * 100).roundToInt()) { alpha = it / 100f }
        root.addView(TextView(activity).apply { text = "Presets / zuletzt verwendet"; textSize = 10f; setTextColor(textArgb) })
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            (recentColors.toList() + presets.toList()).distinct().take(8).forEach { preset ->
                addView(android.view.View(activity).apply {
                    background = background(preset, 999f)
                    setOnClickListener { Color.colorToHSV(preset, hsv); hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]; alpha = Color.alpha(preset) / 255f; refresh() }
                }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(3) })
            }
        })
        refresh(false)
        val scroll = ScrollView(activity).apply { isFillViewport = true; addView(root) }
        AlertDialog.Builder(activity).setTitle(title).setView(scroll)
            .setNeutralButton("Standard") { _, _ -> onReset() }
            .setNegativeButton("Fertig", null).create().apply {
                setOnShowListener { window?.setBackgroundDrawable(background(surfaceArgb, 24f)) }
                show()
            }
    }

    private fun remember(color: Int) {
        recentColors.remove(color)
        recentColors.addFirst(color)
        while (recentColors.size > 8) recentColors.removeLast()
    }
}
