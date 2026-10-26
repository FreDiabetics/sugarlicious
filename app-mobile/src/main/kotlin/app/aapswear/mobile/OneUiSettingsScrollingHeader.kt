package app.aapswear.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun settingsWindowWidthDp(): Float =
    with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width
            .toDp()
            .value
    }

@Composable
internal fun measuredSettingsCompactTitle(title: String, availableWidthDp: Float): SettingsCompactTitleLayout {
    val density = LocalDensity.current
    val width = settingsCompactTitleLayout(availableWidthDp, 0f, 58f).widthDp
    val measured =
        rememberTextMeasurer().measure(
            title,
            style = oneUiMenuTypography.headlineSmall.copy(fontSize = 20.sp),
            maxLines = 2,
            constraints = Constraints(maxWidth = with(density) { width.dp.roundToPx() }),
        )
    return settingsCompactTitleLayout(
        availableWidthDp,
        with(density) {
            measured.size.height
                .toDp()
                .value
        },
        58f,
    )
}

@Composable
internal fun OneUiCompactSettingsHeader(onBack: () -> Unit, modifier: Modifier = Modifier, state: SettingsSubmenuHeaderState = settingsSubmenuHeaderState(0f, 0f, 160f, 88f), title: String = "Widget-Einstellungen") {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, Modifier.size(48.dp).background(OneUiWidgetStyle.card.copy(alpha = state.backCircleAlpha), androidx.compose.foundation.shape.CircleShape)) {
            Icon(painterResource(R.drawable.ic_oneui_back), "Zurück", tint = OneUiWidgetStyle.accent, modifier = Modifier.size(22.dp))
        }
        Text(
            title,
            color = OneUiWidgetStyle.accent,
            fontSize = 20.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .weight(1f)
                    .then(
                        Modifier.graphicsLayer {
                            alpha = state.compactTitleAlpha
                            translationY = state.compactTitleOffsetDp.dp.toPx()
                            scaleX = .96f + .04f * state.compactTitleAlpha
                            scaleY = scaleX
                        },
                    ).semantics { heading() },
        )
    }
}
