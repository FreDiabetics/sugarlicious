package app.aapswear.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColors
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Gives the remaining classic-View Settings hierarchy the same Sugarlicious surface language as
 * the Compose Overview and Watch Configuration screens without duplicating settings behaviour.
 */
class SugarliciousDashboardContent @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        if (child is ComposeView) return
        styleTopLevel(child)
        styleTree(child)
    }

    private fun styleTopLevel(view: View) {
        when (view) {
            is TextView -> if (view.text.toString().isSectionLabel()) {
                view.textSize = 11.5f
                view.setTextColor(SugarliciousColors.argb(SugarliciousColorRole.PRIMARY))
                view.typeface = Typeface.create("sans", Typeface.BOLD)
                view.letterSpacing = 0.08f
                view.setPadding(6.dp, 18.dp, 6.dp, 6.dp)
            }

            is LinearLayout -> {
                val heading = view.firstText()
                if (view.background == null && heading?.text?.toString() == "Einstellungen") {
                    view.setPadding(4.dp, 8.dp, 4.dp, 10.dp)
                    heading.textSize = 28f
                    heading.typeface = Typeface.create("sans", Typeface.BOLD)
                    heading.setTextColor(SugarliciousColors.argb(SugarliciousColorRole.TEXT_PRIMARY))
                } else if (view.background != null) {
                    view.background = cardBackground()
                    view.clipToOutline = true
                    view.setPadding(16.dp, 14.dp, 16.dp, 14.dp)
                    (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                        params.topMargin = maxOf(params.topMargin, 6.dp)
                        params.bottomMargin = maxOf(params.bottomMargin, 2.dp)
                        view.layoutParams = params
                    }
                }
            }
        }
    }

    private fun styleTree(view: View) {
        when (view) {
            is Switch -> styleSwitch(view)
            is TextView -> styleText(view)
        }

        if (view is ViewGroup && view !is ComposeView) {
            if (view.isClickable) {
                view.minimumHeight = maxOf(view.minimumHeight, 54.dp)
                view.foreground = RippleDrawable(
                    ColorStateList.valueOf(withAlpha(SugarliciousColors.argb(SugarliciousColorRole.PRIMARY), 0x2A)),
                    null,
                    null,
                )
            }
            for (index in 0 until view.childCount) styleTree(view.getChildAt(index))
        } else {
            val height = view.layoutParams?.height
            if (view !is TextView && height != null && height in 1..2.dp) {
                view.setBackgroundColor(withAlpha(SugarliciousColors.argb(SugarliciousColorRole.BORDER), 0x72))
            }
        }
    }

    private fun styleText(view: TextView) {
        if (view.isClickable && view.background != null) {
            view.minHeight = maxOf(view.minHeight, 38.dp)
            view.setPadding(14.dp, view.paddingTop, 14.dp, view.paddingBottom)
            val minimumTextSizePx =
                android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP,
                    12f,
                    resources.displayMetrics,
                )
            if (view.textSize < minimumTextSizePx) view.textSize = 12f
            view.typeface = Typeface.create("sans", Typeface.BOLD)
        }
    }

    private fun styleSwitch(view: Switch) {
        val accent = SugarliciousColors.argb(SugarliciousColorRole.PRIMARY)
        view.minimumHeight = 36.dp
        view.minWidth = 52.dp
        view.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.WHITE, SugarliciousColors.argb(SugarliciousColorRole.TEXT_SECONDARY)),
        )
        view.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, SugarliciousColors.argb(SugarliciousColorRole.SURFACE_RAISED)),
        )
    }

    private fun cardBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 24.dp.toFloat()
        setColor(SugarliciousColors.argb(SugarliciousColorRole.SURFACE))
        setStroke(1.dp, SugarliciousColors.argb(SugarliciousColorRole.BORDER))
    }

    private fun LinearLayout.firstText(): TextView? {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child is TextView) return child
            if (child is LinearLayout) child.firstText()?.let { return it }
        }
        return null
    }

    private fun String.isSectionLabel(): Boolean {
        val value = trim()
        return value.length in 2..40 && value == value.uppercase(Locale.GERMAN) && value.any(Char::isLetter)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()
}
