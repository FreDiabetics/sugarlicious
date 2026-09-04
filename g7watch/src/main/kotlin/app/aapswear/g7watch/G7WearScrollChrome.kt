package app.aapswear.g7watch

import android.widget.ScrollView

/** Soft round-screen edge treatment shared by all Direct-to-Watch scroll surfaces. */
internal fun ScrollView.applyG7EdgeFade(): ScrollView = apply {
    isVerticalScrollBarEnabled = false
    isVerticalFadingEdgeEnabled = true
    setFadingEdgeLength((34f * resources.displayMetrics.density).toInt())
    clipToPadding = false
    overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
}
