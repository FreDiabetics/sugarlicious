package app.aapswear.wear

import android.view.View
import android.view.ViewGroup
import android.widget.TextView

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
}
