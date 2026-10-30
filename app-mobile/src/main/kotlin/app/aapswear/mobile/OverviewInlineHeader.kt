package app.aapswear.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
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
internal fun OverviewInlineHeader(onSettings: () -> Unit, diagnostics: DiagnosticsSnapshot, onWatch: () -> Unit, modifier: Modifier = Modifier, glucoseContent: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        glucoseContent()
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .offset(y = (-2).dp)
                    .padding(
                        start = OverviewHeaderLayout.START_PADDING_DP.dp,
                        end = OverviewHeaderLayout.END_PADDING_DP.dp,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.size(
                        width = OverviewHeaderLayout.LOGO_SLOT_WIDTH_DP.dp,
                        height = 38.dp,
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                SugarliciousIcon(
                    drawableRes = R.drawable.ic_foreground,
                    contentDescription = null,
                    tint = SugarliciousColors.BrandGreen,
                    modifier =
                        Modifier
                            .requiredSize(OverviewHeaderLayout.LOGO_SIZE_DP.dp)
                            .offset(x = OverviewHeaderLayout.LOGO_X_OFFSET_DP.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            Row(Modifier.weight(4f).clickable(onClick = onWatch), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                Text(
                    if (isWatchConnected(diagnostics.reachableWatches)) diagnostics.connectedWatchModels.ifBlank { "Watch" } else "Keine Watch verbunden",
                    modifier = Modifier.weight(1f, fill = false),
                    color = Color.White,
                    style = oneUiMenuTypography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                SugarliciousIcon(R.drawable.ic_galaxywatch_outline, null, modifier = Modifier.size(24.dp), tint = SugarliciousColors.BrandGreen, colored = false)
            }
            SettingsHeaderButton(onSettings)
        }
    }
}

@Composable
private fun SettingsHeaderButton(onSettings: () -> Unit, tint: androidx.compose.ui.graphics.Color = SugarliciousColors.TextSecondary) {
    Box(
        modifier = Modifier.size(38.dp).clickable(onClick = onSettings),
        contentAlignment = Alignment.Center,
    ) {
        SugarliciousIcon(
            drawableRes = R.drawable.ic_settings,
            contentDescription = "Einstellungen",
            modifier = Modifier.size(24.dp),
            tint = tint,
            colored = false,
        )
    }
}
