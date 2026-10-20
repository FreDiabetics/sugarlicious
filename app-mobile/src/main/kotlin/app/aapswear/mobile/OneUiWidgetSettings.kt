package app.aapswear.mobile

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.SeekBar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color as UiColor

internal data class OneUiWidgetSettingsColors(
    val page: UiColor = UiColor.Black,
    val card: UiColor = UiColor(0xFF1C1C1C),
    val primary: UiColor = UiColor(0xFFFAFAFA),
    val secondary: UiColor = UiColor(0xFFB7B7BE),
    val accent: UiColor = UiColor(0xFFADC5C9),
    val link: UiColor = UiColor(0xFF4D83FF),
    val action: UiColor = UiColor(0xFF212121),
)

internal val LocalOneUiWidgetSettingsColors = staticCompositionLocalOf { OneUiWidgetSettingsColors() }

internal object OneUiWidgetStyle {
    val primary: UiColor @Composable get() = LocalOneUiWidgetSettingsColors.current.primary
    val secondary: UiColor @Composable get() = LocalOneUiWidgetSettingsColors.current.secondary
    val accent: UiColor @Composable get() = LocalOneUiWidgetSettingsColors.current.accent
    val link: UiColor @Composable get() = LocalOneUiWidgetSettingsColors.current.link
    val card: UiColor @Composable get() = LocalOneUiWidgetSettingsColors.current.card
    val action: UiColor @Composable get() = LocalOneUiWidgetSettingsColors.current.action
}

/** Show the OS-managed wallpaper; never request permission to read or copy its image. */
@Suppress("DEPRECATION") // Transparent system bars are still required before enforced edge-to-edge.
internal fun configureOneUiWidgetSettingsWindow(window: Window) {
    window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
    window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
    WindowCompat.setDecorFitsSystemWindows(window, false)
    if (Build.VERSION.SDK_INT < 35) {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }
    val light = window.context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
    WindowCompat.getInsetsController(window, window.decorView).also {
        it.isAppearanceLightStatusBars = light
        it.isAppearanceLightNavigationBars = light
    }
}

@Composable
internal fun OneUiWidgetSettingsTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val platformScheme =
        if (Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (dark) darkColorScheme() else lightColorScheme()
        }
    val accent =
        if (Build.VERSION.SDK_INT >= 31 && Build.MANUFACTURER.equals("samsung", true)) {
            colorResource(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
        } else {
            platformScheme.primary
        }
    val onAccent =
        if (Build.VERSION.SDK_INT >= 31 && Build.MANUFACTURER.equals("samsung", true)) {
            colorResource(if (dark) android.R.color.system_accent1_800 else android.R.color.system_accent1_0)
        } else {
            platformScheme.onPrimary
        }
    val scheme = platformScheme.copy(primary = accent, onPrimary = onAccent)
    val colors =
        if (dark) {
            OneUiWidgetSettingsColors(primary = scheme.onSurface, secondary = scheme.onSurfaceVariant, accent = scheme.primary, link = scheme.primary)
        } else {
            OneUiWidgetSettingsColors(
                page = UiColor(0xFFF5F5F5),
                card = UiColor.White,
                primary = scheme.onSurface,
                secondary = scheme.onSurfaceVariant,
                accent = scheme.primary,
                link = scheme.primary,
                action = UiColor(0xFFE7E7E7),
            )
        }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val controlContext = remember(context, configuration, dark) { mobileSettingsControlContext(context, dark) }
    CompositionLocalProvider(LocalOneUiWidgetSettingsColors provides colors, LocalContext provides controlContext) {
        MaterialTheme(colorScheme = scheme) {
            ProvideTextStyle(TextStyle(color = colors.primary, fontSize = 18.sp, fontFamily = FontFamily.Default)) { content() }
        }
    }
}

@Composable
internal fun OneUiWidgetSettingsSurface(content: @Composable BoxScope.((Rect) -> Unit) -> Unit) {
    var previewBounds by remember { mutableStateOf<Rect?>(null) }
    val colors = LocalOneUiWidgetSettingsColors.current
    val context = LocalContext.current
    val wallpaperSupported = remember(context) { runCatching { WallpaperManager.getInstance(context).isWallpaperSupported }.getOrDefault(false) }
    Box(
        Modifier.fillMaxSize().drawWithContent {
            val aperture = previewBounds?.takeIf { it.width > 0f && it.height > 0f }
            val path =
                Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, size.width, size.height))
                    aperture?.let { addRoundRect(RoundRect(it, CornerRadius(20.dp.toPx()))) }
                }
            drawPath(path, colors.page)
            if (!wallpaperSupported && aperture != null) {
                drawRoundRect(colors.card, aperture.topLeft, aperture.size, CornerRadius(20.dp.toPx()))
            }
            drawContent()
        },
    ) {
        content { previewBounds = it }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(colors.page)
                .statusBarsPadding()
                .height(60.dp)
                .padding(horizontal = 28.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text("Widget-Einstellungen", color = colors.primary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

internal data class OneUiWidgetPreviewPlacement(
    val widthDp: Float,
    val heightDp: Float,
)

internal fun commitWidgetSettings(commit: () -> Unit, publish: () -> Unit, rollback: () -> Unit) {
    try {
        commit()
        publish()
    } catch (error: Exception) {
        rollback()
        throw error
    }
}

internal fun oneUiWidgetPreviewPlacement(widthDp: Float, heightDp: Float, hostResizeRatio: Float, frameWidthDp: Float): OneUiWidgetPreviewPlacement {
    val ratio = hostResizeRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    val width = widthDp.coerceAtLeast(1f) * ratio
    val height = heightDp.coerceAtLeast(1f) * ratio
    val fit = minOf(1f, (frameWidthDp * .72f).coerceAtLeast(1f) / width, 122f / height)
    return OneUiWidgetPreviewPlacement(width * fit, height * fit)
}

@Composable
internal fun <T> OneUiWidgetChoiceRow(label: String, value: T, choices: List<T>, display: (T) -> String, onSelected: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val colors = LocalOneUiWidgetSettingsColors.current
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(label, color = colors.primary, fontSize = 18.sp)
                Text(display(value), color = colors.link, fontSize = 13.sp, fontWeight = FontWeight.Normal)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = colors.card) {
            choices.forEach { choice ->
                DropdownMenuItem(text = { Text(display(choice), color = if (choice == value) colors.accent else colors.primary) }, onClick = {
                    expanded = false
                    onSelected(choice)
                })
            }
        }
    }
}

@Composable
internal fun WidgetAppearanceModeRow(selected: app.aapswear.model.AppearanceMode, onSelected: (app.aapswear.model.AppearanceMode) -> Unit) {
    OneUiWidgetChoiceRow("Farben", selected, app.aapswear.model.AppearanceMode.entries, { if (it == app.aapswear.model.AppearanceMode.LIGHT) "Helle Farbpalette bearbeiten" else "Dunkle Farbpalette bearbeiten" }, onSelected)
}

@Composable
internal fun OneUiWidgetSeekBar(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val callback by rememberUpdatedState(onChange)
    val currentRange by rememberUpdatedState(range)
    val colors = LocalOneUiWidgetSettingsColors.current
    AndroidView(
        factory = { context ->
            OneUiSeekBar(context).apply {
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            if (fromUser) callback(currentRange.first + progress)
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                    },
                )
            }
        },
        update = { view ->
            view.contentDescription = label
            view.max = (range.last - range.first).coerceAtLeast(1)
            val progress = (value - range.first).coerceIn(0, view.max)
            if (view.progress != progress) view.progress = progress
            view.palette(colors.accent.toArgb(), colors.card.toArgb())
        },
        modifier = Modifier.fillMaxWidth().height(48.dp),
    )
}

@Composable
internal fun MobileSettingsSeekBar(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    val callback by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onFinished)
    val currentRange by rememberUpdatedState(range)
    val colors = LocalOneUiWidgetSettingsColors.current
    AndroidView(
        factory = { context ->
            OneUiSeekBar(context, showTicks = false).apply {
                max = 1000
                val interaction =
                    SettingsSliderInteraction(
                        { progress -> callback(currentRange.start + (currentRange.endInclusive - currentRange.start) * progress / 1000f) },
                        { finished() },
                    )
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            interaction.changed(progress, fromUser)
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar?) = interaction.started()

                        override fun onStopTrackingTouch(seekBar: SeekBar?) = interaction.stopped()
                    },
                )
            }
        },
        update = { view ->
            view.contentDescription = label
            val span = range.endInclusive - range.start
            val progress = if (span > 0f) ((value - range.start) / span * 1000).roundToInt().coerceIn(0, 1000) else 0
            if (view.progress != progress) view.progress = progress
            view.palette(colors.accent.toArgb(), colors.card.toArgb())
        },
        modifier = Modifier.fillMaxWidth().height(48.dp),
    )
}

internal class OneUiSeekBar
    @JvmOverloads
    constructor(
        context: Context,
        private val showTicks: Boolean = true,
    ) : SeekBar(context) {
        private val density = resources.displayMetrics.density
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var currentPalette: Pair<Int, Int>? = null

        fun palette(accent: Int, surface: Int) {
            if (currentPalette == (accent to surface)) return
            currentPalette = accent to surface

            fun track(alpha: Int) =
                GradientDrawable().apply {
                    setColor((accent and 0x00FFFFFF) or (alpha shl 24))
                    cornerRadius = 7f * density
                    setSize(1, (14f * density).roundToInt())
                }
            progressDrawable =
                LayerDrawable(arrayOf(track(60), ClipDrawable(track(130), Gravity.START, ClipDrawable.HORIZONTAL))).apply {
                    setId(0, android.R.id.background)
                    setId(1, android.R.id.progress)
                    for (index in 0..1) {
                        setLayerHeight(index, (14f * density).roundToInt())
                        setLayerGravity(index, Gravity.CENTER_VERTICAL)
                    }
                }
            thumb =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(surface)
                    setStroke((2f * density).roundToInt(), accent)
                    setSize((20f * density).roundToInt(), (20f * density).roundToInt())
                }
            thumbOffset = (10f * density).roundToInt()
            splitTrack = false
            thumbTintList = null
            progressTintList = null
            progressBackgroundTintList = null
            tickMark = null
            setPadding(thumbOffset, 0, thumbOffset, 0)
            tickPaint.color = accent
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!showTicks) return
            val inset = 8f * density
            val trackWidth = width - paddingLeft - paddingRight - 2f * inset
            val currentX = paddingLeft + (width - paddingLeft - paddingRight) * progress / max.coerceAtLeast(1).toFloat()
            listOf(0f, .5f, 1f).forEach { fraction ->
                val x = paddingLeft + inset + trackWidth * fraction
                if (abs(x - currentX) > 12f * density) canvas.drawCircle(x, height / 2f, 4f * density, tickPaint)
            }
        }
    }

@Composable
internal fun WidgetSettingsToggle(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true, label: String? = null) {
    val callback by rememberUpdatedState(onCheckedChange)
    val currentChecked by rememberUpdatedState(checked)
    AndroidView(
        factory = { context ->
            android.widget.Switch(context).apply {
                showText = false
                styleMobileSettingsSwitch(this)
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                setOnCheckedChangeListener { _, value -> if (value != currentChecked) callback?.invoke(value) }
            }
        },
        update = {
            it.contentDescription = label
            it.isChecked = checked
            it.isEnabled = enabled && onCheckedChange != null
        },
        modifier = Modifier.width(64.dp).height(48.dp).padding(end = 10.dp),
    )
}

@Composable
internal fun WidgetToggleSetting(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), color = OneUiWidgetStyle.primary, fontSize = 18.sp)
        WidgetSettingsToggle(checked, onCheckedChange, enabled, label)
    }
}
