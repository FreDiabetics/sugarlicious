package app.aapswear.mobile

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import app.aapswear.model.TherapyDisplayState

/** Uses the exact same canonical 152 mg/dL / 2h asset as the Wear watch-face selector. */
@Composable
internal fun DirectToWatchFacePreview(
    @Suppress("UNUSED_PARAMETER") state: TherapyDisplayState?,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(R.drawable.vigil_preview),
        contentDescription = "Vigil Preview · 152 mg/dL · 2h",
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
