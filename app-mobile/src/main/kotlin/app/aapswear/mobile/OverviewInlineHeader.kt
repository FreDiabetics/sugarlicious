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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aapswear.mobile.ui.theme.SugarliciousColors

internal object OverviewHeaderLayout {
    const val START_PADDING_DP = 2
    const val END_PADDING_DP = 8
    const val LOGO_SLOT_WIDTH_DP = 40
    const val LOGO_SIZE_DP = 46
    const val LOGO_X_OFFSET_DP = 0
}

internal fun overviewWatchReplacementHeightDp(statusTextHeightDp: Float, sectionGapDp: Int): Float =
    OverviewWatchHeroLayout.FRAME_HEIGHT_DP +
        2f * OverviewWatchHeroLayout.GAP_DP +
        OverviewWatchHeroLayout.MODEL_HEIGHT_DP +
        maxOf(OverviewWatchHeroLayout.PILL_ICON_SIZE_DP.toFloat(), statusTextHeightDp) +
        2f * OverviewWatchHeroLayout.PILL_VERTICAL_PADDING_DP + sectionGapDp

@Composable
internal fun OverviewInlineHeader(onSettings: () -> Unit, diagnostics: DiagnosticsSnapshot, onWatch: () -> Unit, sectionGapDp: Int = 5) {
    val density = LocalDensity.current
    val statusText = rememberTextMeasurer().measure("Verbunden", style = LocalTextStyle.current.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold))
    val replacementHeight =
        overviewWatchReplacementHeightDp(
            with(density) {
                statusText.size.height
                    .toDp()
                    .value
            },
            sectionGapDp,
        )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(replacementHeight.dp), contentAlignment = Alignment.Center) {
            SugarliciousWordmark()
        }
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
                    modifier =
                        Modifier
                            .requiredSize(OverviewHeaderLayout.LOGO_SIZE_DP.dp)
                            .offset(x = OverviewHeaderLayout.LOGO_X_OFFSET_DP.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            Box(Modifier.clickable(onClick = onWatch)) { WatchConnectionPill(diagnostics, compact = true) }
            SettingsHeaderButton(onSettings)
        }
    }
}

@Composable
private fun SugarliciousWordmark() {
    Text(
        text =
            buildAnnotatedString {
                withStyle(SpanStyle(color = SugarliciousColors.TextPrimary)) {
                    append("Sugar")
                }
                withStyle(SpanStyle(color = SugarliciousColors.Primary)) {
                    append("licious")
                }
            },
        fontSize = 42.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
internal fun WatchMenuHeader(
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            SugarliciousIcon(
                drawableRes = R.drawable.ic_oneui_back,
                contentDescription = "Zurück",
                modifier = Modifier.size(22.dp),
                tint = OneUiWidgetStyle.accent,
                colored = false,
            )
        }

        Spacer(Modifier.width(7.dp))

        Text(
            text = "Watch",
            color = OneUiWidgetStyle.accent,
            fontSize = 20.sp,
            fontWeight = FontWeight.Normal,
        )

        Spacer(Modifier.weight(1f))
        SettingsHeaderButton(onSettings, OneUiWidgetStyle.accent)
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
            modifier = Modifier.size(23.dp).offset(x = 7.dp),
            tint = tint,
            colored = false,
        )
    }
}
