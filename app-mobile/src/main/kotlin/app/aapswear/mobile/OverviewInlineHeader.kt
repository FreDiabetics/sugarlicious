package app.aapswear.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aapswear.mobile.ui.theme.SugarliciousColors

internal object OverviewHeaderLayout {
    const val START_PADDING_DP = 2
    const val END_PADDING_DP = 8
    const val LOGO_SLOT_WIDTH_DP = 40
    const val LOGO_SIZE_DP = 58
    const val LOGO_X_OFFSET_DP = 0
}

@Composable
internal fun OverviewInlineHeader(
    onSettings: () -> Unit,
    diagnostics: DiagnosticsSnapshot,
    onWatch: () -> Unit,
    modifier: Modifier = Modifier,
    pinActionsToBottom: Boolean = false,
    chromeAlpha: Float = 1f,
    chromeTranslationY: Dp = 0.dp,
    glucoseContent: @Composable () -> Unit,
) {
    Column(modifier.then(if (pinActionsToBottom) Modifier.fillMaxSize() else Modifier.fillMaxWidth()), horizontalAlignment = Alignment.CenterHorizontally) {
        if (pinActionsToBottom) {
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                glucoseContent()
            }
        } else {
            glucoseContent()
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = chromeAlpha.coerceIn(0f, 1f)
                        translationY = chromeTranslationY.toPx()
                    }.padding(start = OverviewHeaderLayout.START_PADDING_DP.dp, end = OverviewHeaderLayout.END_PADDING_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(width = OverviewHeaderLayout.LOGO_SLOT_WIDTH_DP.dp, height = 48.dp).graphicsLayer {
                    translationX = 18.dp.toPx() * (1f - chromeAlpha.coerceIn(0f, 1f))
                },
                contentAlignment = Alignment.CenterStart,
            ) {
                SugarliciousIcon(
                    R.drawable.ic_foreground,
                    null,
                    tint = SugarliciousColors.BrandGreen,
                    modifier = Modifier.requiredSize(OverviewHeaderLayout.LOGO_SIZE_DP.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.graphicsLayer {
                    translationX = -18.dp.toPx() * (1f - chromeAlpha.coerceIn(0f, 1f))
                },
            ) {
                OverviewHeaderActions(onWatch, onSettings, enabled = chromeAlpha > .05f)
            }
        }
    }
}

/** Identical, adequately sized touch targets outside and inside the sticky pill. */
@Composable
internal fun OverviewHeaderActions(onWatch: () -> Unit, onSettings: () -> Unit, enabled: Boolean = true, compact: Boolean = false) {
    val targetSize = 48.dp
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(targetSize).clickable(enabled = enabled, onClick = onWatch), contentAlignment = Alignment.Center) {
            SugarliciousIcon(
                R.drawable.ic_galaxywatch_filled,
                "Smartwatch",
                modifier = Modifier.size(24.dp).offset(x = if (compact) 8.dp else 0.dp),
                tint = SugarliciousColors.BrandGreen,
                colored = false,
            )
        }
        Box(Modifier.size(targetSize).clickable(enabled = enabled, onClick = onSettings), contentAlignment = Alignment.Center) {
            SugarliciousIcon(
                R.drawable.ic_settings,
                "Einstellungen",
                modifier = Modifier.size(24.dp),
                tint = SugarliciousColors.TextSecondary,
                colored = false,
            )
        }
    }
}
