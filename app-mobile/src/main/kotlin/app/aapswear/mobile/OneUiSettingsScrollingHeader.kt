package app.aapswear.mobile

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun settingsTitleHeightDp(): Float =
    with(LocalDensity.current) {
        minOf(
            250f,
            LocalWindowInfo.current.containerSize.height
                .toDp()
                .value * .28f,
        )
    }

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
internal fun OneUiScrollingSettingsTitle(title: String, scroll: ScrollState) {
    val height = settingsTitleHeightDp()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(height.dp).settingsSnapHeading("page-title", height), contentAlignment = Alignment.Center) {
        val compact = measuredSettingsCompactTitle(title, maxWidth.value)
        val state by remember(scroll, density, height, compact) { derivedStateOf { settingsScrollHeaderState(with(density) { scroll.value.toDp().value }, height, height + 20f, compact.heightDp) } }
        Text(
            title,
            color = OneUiWidgetStyle.accent,
            fontSize = (34f * state.titleScale).sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.clearAndSetSemantics {}.graphicsLayer {
                    translationY = with(density) { state.titleOffsetDp.dp.toPx() }
                    alpha = state.titleAlpha * (1f - state.collapseFraction)
                },
        )
        Text(
            title,
            color = OneUiWidgetStyle.accent,
            fontSize = 20.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = compact.startDp.dp)
                    .width(compact.widthDp.dp)
                    .semantics { heading() }
                    .graphicsLayer {
                        translationY = with(density) { state.titleOffsetDp.dp.toPx() }
                        alpha = state.titleAlpha * state.collapseFraction
                    },
        )
    }
}

@Composable
internal fun OneUiScrollingSettingsBack(scroll: ScrollState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val height = settingsTitleHeightDp()
    val compact = measuredSettingsCompactTitle("Widget-Einstellungen", settingsWindowWidthDp() - 20f)
    val density = LocalDensity.current
    val state by remember(scroll, density, height, compact) { derivedStateOf { settingsScrollHeaderState(with(density) { scroll.value.toDp().value }, height, height + 20f, compact.heightDp) } }
    IconButton(onBack, modifier.padding(top = ((compact.heightDp - 48f) / 2f - 4f).coerceAtLeast(0f).dp).size(48.dp).background(OneUiWidgetStyle.card.copy(alpha = state.backCircleAlpha), CircleShape)) {
        Icon(painterResource(R.drawable.ic_oneui_back), "Zurück", tint = OneUiWidgetStyle.accent, modifier = Modifier.size(22.dp))
    }
}
