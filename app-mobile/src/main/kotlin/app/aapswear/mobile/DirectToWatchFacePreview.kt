package app.aapswear.mobile

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import app.aapswear.model.TherapyDisplayState

/** Regenerated from the current Vigil WFF and its actual provider previews, never live glucose. */
@Composable
internal fun DirectToWatchFacePreview(
    @Suppress("UNUSED_PARAMETER") state: TherapyDisplayState?,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(R.drawable.vigil_preview),
        contentDescription = "Vigil-Vorschau · aktuelles Layout mit Beispieldaten, keine Live-Messwerte",
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
