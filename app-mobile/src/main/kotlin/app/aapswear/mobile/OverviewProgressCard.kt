package app.aapswear.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.basalDetailPresentation
import app.aapswear.model.latestDeliveredBolus
import kotlin.math.roundToLong

@Composable
internal fun OverviewProgressCard(state: TherapyDisplayState?, preferences: DashboardUiPreferences, now: Long) {
    val locale = LocalConfiguration.current.locales[0]
    val indicators = therapyIndicatorPresentations(state, preferences.iobProgressMaximumUnits, now, preferences.cobProgressMaximumGrams)
    BoxWithConstraints(Modifier.fillMaxWidth().background(SugarliciousColors.Surface, RoundedCornerShape(28.dp)).padding(18.dp)) {
        val fontScale = LocalDensity.current.fontScale
        val columnWidth = maxOf(104f * fontScale, (maxWidth.value - 24f) / 3f).coerceAtMost(maxWidth.value)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            indicators.forEach { indicator ->
                Column(Modifier.width(columnWidth.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TherapyCircularIndicator(indicator.copy(secondary = null), Modifier, minOf(82f * fontScale, columnWidth))
                    if (preferences.showProgressDetails) {
                        val detail =
                            when (indicator.label) {
                                "IOB" -> latestDeliveredBolus(state, now)?.let { "Letzter Bolus\n${formatCompactBolusAge(now - it.deliveredAtEpochMs)}" } ?: "Letzter Bolus\n—"
                                "COB" -> dailyCarbohydratesGrams(state, now)?.let { "Heute erfasst\n${it.roundToLong()} g KH" } ?: "Heute erfasst\n—"
                                else -> basalDetailPresentation(state?.basal, now, locale).let { "Basis ${it.base}\nTBR ${it.temporary}" }
                            }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(indicator.label, color = SugarliciousColors.TextPrimary, style = oneUiMenuTypography.titleSmall, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
                            Text(detail, color = SugarliciousColors.TextSecondary, style = oneUiMenuTypography.bodySmall, fontSize = 13.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}
