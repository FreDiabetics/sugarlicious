package app.aapswear.g7watch

import android.widget.ScrollView

/**
 * Compatibility-only alias. There is deliberately no custom scroll implementation anymore.
 * Direct-to-Watch screens use Android's plain ScrollView with no fade, mask, scaling, clipping,
 * roll-away transform or other viewport-edge effect.
 */
internal typealias G7EdgeFadeScrollView = ScrollView

/** No visual scroll-edge effect. */
internal fun ScrollView.applyG7EdgeFade(): ScrollView = this
