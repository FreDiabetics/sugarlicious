package app.aapswear.wear

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** On small watches prioritize complete BR/TBR values over optional row icons. */
internal fun configureTherapyOverviewLayout(root: View, widthDp: Int = root.resources.configuration.screenWidthDp) {
    val compact = widthDp < 210
    listOf(R.id.wear_basal_base_icon, R.id.wear_basal_temp_icon).forEach { root.findViewById<View>(it).visibility = if (compact) View.GONE else View.VISIBLE }
    root.findViewById<TextView>(R.id.wear_basal_base).maxLines = if (compact) 2 else 1
    if (compact) {
        listOf(R.id.wear_basal_base, R.id.wear_basal_secondary).forEach { id ->
            root.findViewById<View>(id).apply { layoutParams = (layoutParams as ViewGroup.MarginLayoutParams).apply { marginStart = 0 } }
        }
    }
    applySystemScaleLayout(root)
}

private fun applySystemScaleLayout(root: View) {
    val fontScale = root.resources.configuration.fontScale
    if (fontScale <= 1f) return
    val density = root.resources.displayMetrics.density
    val stacked = fontScale > 1.2f
    if (stacked) {
        root.findViewById<LinearLayout>(R.id.wear_glucose_primary_row).orientation = LinearLayout.VERTICAL
        root.findViewById<LinearLayout>(R.id.wear_therapy_row).orientation = LinearLayout.VERTICAL
        listOf(R.id.wear_iob_card, R.id.wear_cob_card).forEach { id ->
            root.findViewById<View>(id).layoutParams =
                android.widget.LinearLayout
                    .LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = (7 * density).roundToInt() }
        }
        root.findViewById<LinearLayout>(R.id.wear_basal_card).apply {
            orientation = LinearLayout.VERTICAL
            getChildAt(1).layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        listOf(R.id.wear_basal_base, R.id.wear_basal_secondary).forEach { id ->
            root.findViewById<TextView>(id).apply {
                setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
                textSize = 12f
                maxLines = 3
                layoutParams = layoutParams.apply { height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT }
            }
        }
    }
    listOf(R.id.wear_iob_progress, R.id.wear_cob_progress, R.id.wear_basal_progress).forEach { id ->
        val frame = root.findViewById<View>(id).parent as View
        frame.layoutParams =
            frame.layoutParams.apply {
                width = (66 * density * fontScale).roundToInt()
                height = width
            }
    }
    listOf(R.id.wear_iob_icon, R.id.wear_cob_icon, R.id.wear_basal_icon).forEach { id ->
        root.findViewById<View>(id).layoutParams =
            root.findViewById<View>(id).layoutParams.apply {
                width = (19 * density * fontScale).roundToInt()
                height = width
            }
    }
}
