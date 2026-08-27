package app.aapswear.mobile

import androidx.core.content.edit
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.model.Freshness
import app.aapswear.model.FreshnessPolicy
import app.aapswear.model.ComplicationPresentationFormatter
import app.aapswear.model.SugarliciousComplicationIds
import app.aapswear.model.Trend
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseGraphScale
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.protocol.WearProtocol
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

internal data class ComplicationCatalogEntry(
    val name: String,
    val category: ComplicationCategory,
    val variants: List<ComplicationVariant>,
) {
    val id: Int get() = variants.first().id
    val types: String get() = variants.joinToString(" · ") { it.type.shortLabel }

    fun selectedVariant(selectedIds: List<Int>): ComplicationVariant =
        variants.firstOrNull { it.id in selectedIds } ?: variants.first()
}

internal data class ComplicationVariant(
    val id: Int,
    val type: ComplicationVariantType,
)

internal enum class ComplicationVariantType(
    val shortLabel: String,
    val displayLabel: String,
) {
    SHORT_TEXT("SHORT", "Short Text"),
    LONG_TEXT("LONG", "Long Text"),
    RANGED_VALUE("RANGED", "Ranged Value"),
    MONOCHROMATIC_IMAGE("ICON", "Icon"),
    GOAL_PROGRESS("GOAL", "Goal Progress"),
    WEIGHTED_ELEMENTS("WEIGHTED", "Weighted Elements"),
    SMALL_IMAGE("SMALL", "Small Image"),
    PHOTO_IMAGE("LARGE", "Large Image"),
}

private fun variant(id: Int, type: ComplicationVariantType) = ComplicationVariant(id, type)

private fun entry(
    name: String,
    category: ComplicationCategory,
    vararg variants: ComplicationVariant,
) = ComplicationCatalogEntry(name, category, variants.toList())

internal enum class ComplicationCategory(
    val label: String,
    val range: String,
) {
    GLUCOSE("Glukose", "01–12"),
    THERAPY("Therapie", "13–19"),
    GENERAL("Allgemein", "20"),
}

internal val SugarliciousComplicationCatalog = listOf(
    entry("Glukose", ComplicationCategory.GLUCOSE,
        variant(SugarliciousComplicationIds.GLUCOSE, ComplicationVariantType.SHORT_TEXT),
        variant(SugarliciousComplicationIds.GLUCOSE_LONG, ComplicationVariantType.LONG_TEXT),
        variant(SugarliciousComplicationIds.GLUCOSE_RANGED, ComplicationVariantType.RANGED_VALUE)),
    entry("Glukose + Trend", ComplicationCategory.GLUCOSE,
        variant(SugarliciousComplicationIds.GLUCOSE_TREND, ComplicationVariantType.SHORT_TEXT),
        variant(SugarliciousComplicationIds.GLUCOSE_TREND_LONG, ComplicationVariantType.LONG_TEXT),
        variant(SugarliciousComplicationIds.GLUCOSE_TREND_RANGED, ComplicationVariantType.RANGED_VALUE)),
    entry("Glukose + Delta", ComplicationCategory.GLUCOSE,
        variant(SugarliciousComplicationIds.GLUCOSE_PLUS_DELTA, ComplicationVariantType.SHORT_TEXT),
        variant(SugarliciousComplicationIds.GLUCOSE_PLUS_DELTA_LONG, ComplicationVariantType.LONG_TEXT)),
    entry("Glukose + Trend + Zeit", ComplicationCategory.GLUCOSE,
        variant(SugarliciousComplicationIds.GLUCOSE_TREND_AGE, ComplicationVariantType.SHORT_TEXT),
        variant(SugarliciousComplicationIds.GLUCOSE_TREND_AGE_LONG, ComplicationVariantType.LONG_TEXT)),
    entry("Glukose + Trend + Delta", ComplicationCategory.GLUCOSE,
        variant(SugarliciousComplicationIds.GLUCOSE_TREND_DELTA, ComplicationVariantType.SHORT_TEXT)),
    entry("Glukose + Trend + Delta + Zeit", ComplicationCategory.GLUCOSE,
        variant(SugarliciousComplicationIds.GLUCOSE_TREND_DELTA_AGE, ComplicationVariantType.SHORT_TEXT),
        variant(SugarliciousComplicationIds.GLUCOSE_TREND_DELTA_AGE_LONG, ComplicationVariantType.LONG_TEXT)),
    entry("CGM Graph", ComplicationCategory.GLUCOSE,
        variant(SugarliciousComplicationIds.GRAPH, ComplicationVariantType.SMALL_IMAGE),
        variant(SugarliciousComplicationIds.GRAPH_LARGE, ComplicationVariantType.PHOTO_IMAGE)),
    entry("Trend", ComplicationCategory.GLUCOSE,
        variant(SugarliciousComplicationIds.TREND_ONLY, ComplicationVariantType.SHORT_TEXT)),
    entry("Delta", ComplicationCategory.GLUCOSE,
        variant(SugarliciousComplicationIds.DELTA_ONLY, ComplicationVariantType.SHORT_TEXT)),
    entry("Zeit seit letztem Wert", ComplicationCategory.GLUCOSE,
        variant(SugarliciousComplicationIds.GLUCOSE_AGE, ComplicationVariantType.SHORT_TEXT)),
    entry("Zeit + Delta", ComplicationCategory.GLUCOSE,
        variant(SugarliciousComplicationIds.TIME_DELTA, ComplicationVariantType.SHORT_TEXT)),
    entry("Sensoralter", ComplicationCategory.GLUCOSE,
        variant(SugarliciousComplicationIds.SENSOR_AGE, ComplicationVariantType.SHORT_TEXT),
        variant(SugarliciousComplicationIds.SENSOR_AGE_RANGED, ComplicationVariantType.RANGED_VALUE)),
    entry("Basal", ComplicationCategory.THERAPY,
        variant(SugarliciousComplicationIds.BASAL, ComplicationVariantType.SHORT_TEXT)),
    entry("IOB", ComplicationCategory.THERAPY,
        variant(SugarliciousComplicationIds.IOB, ComplicationVariantType.SHORT_TEXT),
        variant(SugarliciousComplicationIds.IOB_RANGED, ComplicationVariantType.RANGED_VALUE)),
    entry("COB", ComplicationCategory.THERAPY,
        variant(SugarliciousComplicationIds.COB, ComplicationVariantType.SHORT_TEXT),
        variant(SugarliciousComplicationIds.COB_RANGED, ComplicationVariantType.RANGED_VALUE)),
    entry("Basal + IOB + COB", ComplicationCategory.THERAPY,
        variant(SugarliciousComplicationIds.IOB_COB_BASAL, ComplicationVariantType.SHORT_TEXT),
        variant(SugarliciousComplicationIds.IOB_COB_BASAL_LONG, ComplicationVariantType.LONG_TEXT)),
    entry("Loop Status", ComplicationCategory.THERAPY,
        variant(SugarliciousComplicationIds.LOOP, ComplicationVariantType.SHORT_TEXT),
        variant(SugarliciousComplicationIds.LOOP_ICON, ComplicationVariantType.MONOCHROMATIC_IMAGE)),
    entry("Pumpe / Reservoir", ComplicationCategory.THERAPY,
        variant(SugarliciousComplicationIds.RESERVOIR, ComplicationVariantType.SHORT_TEXT),
        variant(SugarliciousComplicationIds.RESERVOIR_RANGED, ComplicationVariantType.RANGED_VALUE)),
    entry("TIR", ComplicationCategory.GLUCOSE,
        variant(SugarliciousComplicationIds.TIR, ComplicationVariantType.SHORT_TEXT),
        variant(SugarliciousComplicationIds.TIR_GOAL, ComplicationVariantType.GOAL_PROGRESS),
        variant(SugarliciousComplicationIds.TIR_WEIGHTED, ComplicationVariantType.WEIGHTED_ELEMENTS)),
    entry("Datum", ComplicationCategory.GENERAL,
        variant(SugarliciousComplicationIds.DATE, ComplicationVariantType.SHORT_TEXT)),
)

private fun catalogNumber(entry: ComplicationCatalogEntry): Int =
    SugarliciousComplicationCatalog.indexOf(entry).coerceAtLeast(0) + 1

internal val SugarliciousComplicationVariantIds: Set<Int> =
    SugarliciousComplicationCatalog.flatMap { it.variants }.map { it.id }.toSet()

private fun catalogEntryForVariant(id: Int): ComplicationCatalogEntry? =
    SugarliciousComplicationCatalog.firstOrNull { entry -> entry.variants.any { it.id == id } }

@Composable
internal fun ComplicationStudio(
    state: TherapyDisplayState?,
    onPresetChanged: (List<Int>) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(loadComplicationPreset(context)) }
    var graphHours by remember { mutableStateOf(loadComplicationGraphHours(context)) }
    var variantDialogEntry by remember { mutableStateOf<ComplicationCatalogEntry?>(null) }
    var syncLabel by remember {
        mutableStateOf(
            if (selected.isEmpty()) "Noch kein Smartphone-Preset"
            else "Preset lokal gespeichert",
        )
    }

    fun applySelection(updated: List<Int>) {
        if (updated == selected) return
        selected = updated
        onPresetChanged(updated)
        syncLabel = "Preset geändert · wird synchronisiert"
        scope.launch {
            runCatching { syncComplicationPreset(context, updated, graphHours) }
                .onSuccess { syncLabel = "Preset an Watch synchronisiert" }
                .onFailure { syncLabel = "Lokal gespeichert · Watch-Sync ausstehend" }
        }
    }

    val shape = RoundedCornerShape(24.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SugarliciousColors.Surface, shape)
            .border(1.dp, SugarliciousColors.Border.copy(alpha = 0.75f), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PresetStrip(selected)

        Button(
            onClick = {
                scope.launch {
                    runCatching {
                        syncComplicationPreset(context, selected, graphHours)
                    }.onSuccess {
                        syncLabel = "Preset an Wear Data Layer übergeben"
                    }.onFailure {
                        syncLabel = "Preset-Sync fehlgeschlagen"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(42.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SugarliciousColors.SurfaceHigh,
                contentColor = SugarliciousColors.Primary,
            ),
        ) {
            Text("PRESET AN WATCH SENDEN", fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }

        Text(
            syncLabel,
            color = SugarliciousColors.TextSecondary,
            fontSize = 9.sp,
        )

        Text(
            "CGM-GRAPH ZEITRAUM",
            color = SugarliciousColors.TextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            listOf(1, 2, 6, 12, 24).forEach { hours ->
                Surface(
                    modifier = Modifier.weight(1f).clickable {
                        graphHours = hours
                        saveComplicationGraphHours(context, hours)
                        scope.launch {
                            runCatching { syncComplicationPreset(context, selected, hours) }
                                .onSuccess { syncLabel = "Graph auf ${hours} h synchronisiert" }
                                .onFailure { syncLabel = "Graph lokal auf ${hours} h gesetzt" }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = if (graphHours == hours) SugarliciousColors.SurfaceSelected else SugarliciousColors.SurfaceHigh,
                ) {
                    Text(
                        "${hours}h",
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = if (graphHours == hours) SugarliciousColors.Primary else SugarliciousColors.TextSecondary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }

        SugarliciousComplicationCatalog.chunked(3).forEach { entries ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                entries.forEach { entry ->
                    val selectedVariant = entry.selectedVariant(selected)
                    ComplicationCatalogTile(
                        modifier = Modifier.weight(1f),
                        entry = entry,
                        variant = selectedVariant,
                        state = state,
                        graphHours = graphHours,
                        selected = entry.variants.any { it.id in selected },
                        onToggle = {
                            val updated = togglePresetEntry(context, selected, selectedVariant.id)
                            if (updated == selected && selectedVariant.id !in selected && selected.size >= 4) {
                                Toast.makeText(context, "Das Watch-Preset hat maximal 4 Plätze.", Toast.LENGTH_SHORT).show()
                            } else {
                                applySelection(updated)
                            }
                        },
                        onLongPress = {
                            if (entry.variants.size > 1) variantDialogEntry = entry
                        },
                    )
                }
                repeat(3 - entries.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    variantDialogEntry?.let { entry ->
        val currentVariant = entry.selectedVariant(selected)
        ComplicationVariantDialog(
            entry = entry,
            currentVariant = currentVariant,
            state = state,
            graphHours = graphHours,
            onDismiss = { variantDialogEntry = null },
            onSelect = { variant ->
                val updated = selectPresetVariant(context, selected, entry, variant.id)
                if (updated == selected && entry.variants.none { it.id in selected } && selected.size >= 4) {
                    Toast.makeText(context, "Das Watch-Preset hat maximal 4 Plätze.", Toast.LENGTH_SHORT).show()
                } else {
                    applySelection(updated)
                    variantDialogEntry = null
                }
            },
        )
    }
}

@Composable
private fun PresetStrip(selected: List<Int>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(4) { index ->
            val id = selected.getOrNull(index)
            val entry = id?.let(::catalogEntryForVariant)
            val variant = entry?.variants?.firstOrNull { it.id == id }

            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                color = SugarliciousColors.SurfaceHigh,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        entry?.let { catalogNumber(it).toString().padStart(2, '0') } ?: "—",
                        color = if (entry != null) {
                            SugarliciousColors.Primary
                        } else {
                            SugarliciousColors.TextSecondary
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        entry?.let { "${it.name} ${variant?.type?.shortLabel.orEmpty()}" } ?: "frei",
                        color = SugarliciousColors.TextSecondary,
                        fontSize = 7.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(
    category: ComplicationCategory,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                SugarliciousColors.SurfaceHigh,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            category.label,
            color = SugarliciousColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            category.range,
            color = SugarliciousColors.TextSecondary,
            fontSize = 9.sp,
        )
        Spacer(Modifier.width(7.dp))
        Text(
            if (expanded) "−" else "+",
            color = SugarliciousColors.Primary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ComplicationCatalogTile(
    modifier: Modifier,
    entry: ComplicationCatalogEntry,
    variant: ComplicationVariant,
    state: TherapyDisplayState?,
    graphHours: Int,
    selected: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier.aspectRatio(1f)
            .background(if (selected) SugarliciousColors.SurfaceSelected else SugarliciousColors.SurfaceHigh, shape)
            .border(1.dp, if (selected) SugarliciousColors.Primary.copy(alpha = 0.62f) else SugarliciousColors.Border.copy(alpha = 0.55f), shape)
            .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            entry.name,
            color = SugarliciousColors.TextPrimary,
            fontSize = 8.5.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        CompactComplicationPreview(entry, variant, state, graphHours)
        Spacer(Modifier.weight(1f))
        Text(
            variant.type.shortLabel,
            color = SugarliciousColors.TextSecondary,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CompactComplicationPreview(
    entry: ComplicationCatalogEntry,
    variant: ComplicationVariant,
    state: TherapyDisplayState?,
    graphHours: Int,
) {
    if (SugarliciousComplicationIds.baseId(variant.id) == SugarliciousComplicationIds.GRAPH) {
        MiniGlucosePreview(
            samples = state?.glucoseHistory.orEmpty(),
            current = state?.glucose?.let { GlucoseSample(it.valueMgDl, it.measuredAtEpochMs) },
            windowMinutes = if (variant.type == ComplicationVariantType.PHOTO_IMAGE) 360 else 90,
        )
        return
    }

    val preview = previewFor(variant.id, state)

    when (variant.type) {
        ComplicationVariantType.RANGED_VALUE,
        ComplicationVariantType.GOAL_PROGRESS -> {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .border(4.dp, SugarliciousColors.Primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    preview.primary,
                    color = preview.color,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
            return
        }

        ComplicationVariantType.MONOCHROMATIC_IMAGE -> {
            Text("↻", color = SugarliciousColors.Primary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            return
        }

        ComplicationVariantType.WEIGHTED_ELEMENTS -> {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf(SugarliciousColors.GlucoseLow, SugarliciousColors.GlucoseInRange, SugarliciousColors.GlucoseHigh)
                    .forEach { color ->
                        Box(Modifier.size(width = 12.dp, height = 28.dp).background(color, RoundedCornerShape(5.dp)))
                    }
            }
            return
        }

        else -> Unit
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (preview.trend != null && SugarliciousComplicationIds.baseId(variant.id) == SugarliciousComplicationIds.TREND_ONLY) {
            SugarliciousTrendIndicator(preview.trend, arrowSize = 25.dp, color = preview.color)
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    preview.primary,
                    color = preview.color,
                    fontSize = 14.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                preview.trend?.let {
                    SugarliciousTrendIndicator(it, arrowSize = 17.dp, color = preview.color)
                }
            }
        }
        if (preview.secondary.isNotBlank()) {
            Text(
                preview.secondary,
                modifier = Modifier.offset(y = (-2).dp),
                color = SugarliciousColors.TextSecondary,
                fontSize = 9.sp,
                lineHeight = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ComplicationVariantDialog(
    entry: ComplicationCatalogEntry,
    currentVariant: ComplicationVariant,
    state: TherapyDisplayState?,
    graphHours: Int,
    onDismiss: () -> Unit,
    onSelect: (ComplicationVariant) -> Unit,
) {
    val alternatives = entry.variants.filterNot { it.id == currentVariant.id }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(entry.name, color = SugarliciousColors.TextPrimary, fontWeight = FontWeight.Bold)
                Text(
                    "Aktiv: ${currentVariant.type.displayLabel}",
                    color = SugarliciousColors.TextSecondary,
                    fontSize = 10.sp,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                alternatives.forEach { variant ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(variant) },
                        shape = RoundedCornerShape(16.dp),
                        color = SugarliciousColors.SurfaceHigh,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(width = 78.dp, height = 58.dp), contentAlignment = Alignment.Center) {
                                CompactComplicationPreview(entry, variant, state, graphHours)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                variant.type.displayLabel,
                                color = SugarliciousColors.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Button(onClick = onDismiss) { Text("Schließen") }
        },
        containerColor = SugarliciousColors.Surface,
    )
}

@Composable
private fun ComplicationCatalogRow(
    entry: ComplicationCatalogEntry,
    state: TherapyDisplayState?,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) {
                    SugarliciousColors.SurfaceSelected
                } else {
                    SugarliciousColors.Surface.copy(alpha = 0.01f)
                },
                shape,
            )
            .border(
                1.dp,
                if (selected) {
                    SugarliciousColors.Primary.copy(alpha = 0.5f)
                } else {
                    SugarliciousColors.Border.copy(alpha = 0.55f)
                },
                shape,
            )
            .clickable(onClick = onToggle)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = if (selected) {
                    SugarliciousColors.Primary
                } else {
                    SugarliciousColors.SurfaceHigh
                },
            ) {
                Box(
                    modifier = Modifier.size(30.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        entry.id.toString().padStart(2, '0'),
                        color = if (selected) {
                            SugarliciousColors.OnPrimary
                        } else {
                            SugarliciousColors.TextPrimary
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.width(9.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    color = SugarliciousColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.types,
                    color = SugarliciousColors.TextSecondary,
                    fontSize = 8.sp,
                )
            }

            Text(
                if (selected) "✓ PRESET" else "+ PRESET",
                color = if (selected) {
                    SugarliciousColors.Primary
                } else {
                    SugarliciousColors.TextSecondary
                },
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        ComplicationDataPreview(entry, state)
    }
}

@Composable
private fun ComplicationDataPreview(
    entry: ComplicationCatalogEntry,
    state: TherapyDisplayState?,
) {
    val preview = previewFor(entry.id, state)
    val shape = RoundedCornerShape(14.dp)

    if (entry.id == SugarliciousComplicationIds.GLUCOSE_TREND) {
        val now = System.currentTimeMillis()
        val glucose = state?.glucose
        val freshness = FreshnessPolicy.classify(glucose?.measuredAtEpochMs, now)
        val current = freshness == Freshness.CURRENT || freshness == Freshness.DELAYED
        val g = glucose.takeIf { current }

        CircularGlucoseComplicationPreview(
            glucoseValue = g?.valueMgDl ?: 123.0,
            glucoseText = g?.let { TherapyDisplayFormatter.glucose(it) } ?: "123",
            trendText = g?.let { TherapyDisplayFormatter.trendArrow(it.trend) }
                ?.ifBlank { "↗" }
                ?: "↗",
            modifier = Modifier
                .fillMaxWidth()
                .background(SugarliciousColors.Background, shape)
                .padding(vertical = 10.dp),
        )
        return
    }

    if (entry.id == SugarliciousComplicationIds.GRAPH) {
        val windowMinutes = 180
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SugarliciousColors.Background, shape)
                .padding(horizontal = 9.dp, vertical = 7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "3h Datenvorschau",
                    color = SugarliciousColors.TextSecondary,
                    fontSize = 8.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    preview.primary,
                    color = SugarliciousColors.TextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(4.dp))
            MiniGlucosePreview(
                samples = state?.glucoseHistory.orEmpty(),
                current = state?.glucose?.let {
                    GlucoseSample(it.valueMgDl, it.measuredAtEpochMs)
                },
                windowMinutes = windowMinutes,
            )
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SugarliciousColors.Background, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "VORSCHAU",
            color = SugarliciousColors.TextSecondary,
            fontSize = 7.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            preview.primary,
            modifier = Modifier.weight(1f),
            color = preview.color,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            preview.secondary,
            color = SugarliciousColors.TextSecondary,
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CircularGlucoseComplicationPreview(
    glucoseValue: Double,
    glucoseText: String,
    trendText: String,
    modifier: Modifier = Modifier,
) {
    val foreground = when {
        glucoseValue < 80.0 -> SugarliciousColors.GlucoseLow
        glucoseValue > 160.0 -> SugarliciousColors.GlucoseHigh
        else -> SugarliciousColors.GlucoseInRange
    }
    val progress =
        ((glucoseValue - 40.0) / (260.0 - 40.0))
            .coerceIn(0.0, 1.0)
            .toFloat()

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(146.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 20.dp.toPx()
                val diameter = size.minDimension - stroke
                val topLeft = Offset(
                    (size.width - diameter) / 2f,
                    (size.height - diameter) / 2f,
                )
                val arcSize =
                    androidx.compose.ui.geometry.Size(
                        diameter,
                        diameter,
                    )

                drawArc(
                    color = SugarliciousColors.SurfaceHigh,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(
                        width = stroke,
                        cap = StrokeCap.Round,
                    ),
                )

                drawArc(
                    color = foreground,
                    startAngle = 135f,
                    sweepAngle = 270f * progress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(
                        width = stroke,
                        cap = StrokeCap.Round,
                    ),
                )
            }

            Column(
                modifier = Modifier.offset(y = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = glucoseText,
                    color = foreground,
                    fontSize = 30.sp,
                    lineHeight = 27.sp,
                    fontWeight = FontWeight.Bold,
                )

                Text(
                    text = trendText,
                    modifier = Modifier.offset(y = (-4).dp),
                    color = SugarliciousColors.TextPrimary,
                    fontSize = 37.sp,
                    lineHeight = 33.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun MiniGlucosePreview(
    samples: List<GlucoseSample>,
    current: GlucoseSample?,
    windowMinutes: Int,
) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE) }
    val now = System.currentTimeMillis()
    val windowMs = windowMinutes * 60_000L
    val cutoff = now - windowMs
    val demoHours = maxOf(1, (windowMinutes + 59) / 60)
    val merged = (samples + listOfNotNull(current))
        .filter { it.measuredAtEpochMs in cutoff..(now + 5 * 60_000L) && it.valueMgDl in 20.0..1000.0 }
        .distinctBy { it.measuredAtEpochMs }
        .sortedBy { it.measuredAtEpochMs }
        .ifEmpty { demoHistory(now, demoHours).filter { it.measuredAtEpochMs >= cutoff } }
    val dotRadiusDp =
        (preferences.getFloat("cgm.dotRadiusDp", 2.4f) - 0.5f)
            .coerceIn(1.0f, 5.5f)
    val outlineEnabled = preferences.getBoolean("cgm.dotOutlineEnabled", true)
    val outlineWidthDp = preferences.getFloat("cgm.dotOutlineWidthDp", 0.95f).coerceIn(0.25f, 3f)
    Canvas(
        Modifier.fillMaxWidth().height(52.dp)
            .background(SugarliciousColors.color(SugarliciousColorRole.GRAPH_BACKGROUND)),
    ) {
        val left = 3.dp.toPx()
        val right = size.width - 3.dp.toPx()
        val top = 3.dp.toPx()
        val bottom = size.height - 3.dp.toPx()
        fun x(timestamp: Long) = left + (((timestamp - cutoff).toDouble() / windowMs.toDouble()).coerceIn(0.0, 1.0) * (right - left)).toFloat()
        fun y(value: Double) = bottom - (GlucoseGraphScale.ratio(value) * (bottom - top)).toFloat()
        val low = 80.0
        val high = 160.0
        drawLine(SugarliciousColors.color(SugarliciousColorRole.GRAPH_DIVIDER), Offset(left, y(high)), Offset(right, y(high)), 0.7.dp.toPx())
        drawLine(SugarliciousColors.color(SugarliciousColorRole.GRAPH_DIVIDER), Offset(left, y(low)), Offset(right, y(low)), 0.7.dp.toPx())
        merged.forEachIndexed { index, sample ->
            val radius = dotRadiusDp.dp.toPx() * if (index == merged.lastIndex) 1.25f else 1f
            val center = Offset(x(sample.measuredAtEpochMs), y(sample.valueMgDl))
            if (outlineEnabled) {
                drawCircle(SugarliciousColors.color(SugarliciousColorRole.GRAPH_CURRENT_OUTLINE), radius + outlineWidthDp.dp.toPx(), center)
            }
            val dotColor = when {
                sample.valueMgDl < low -> SugarliciousColors.color(SugarliciousColorRole.CGM_DOT_LOW)
                sample.valueMgDl > high -> SugarliciousColors.color(SugarliciousColorRole.CGM_DOT_HIGH)
                else -> SugarliciousColors.color(SugarliciousColorRole.CGM_DOT_IN_RANGE)
            }
            drawCircle(dotColor, radius, center)
        }
    }
}

private data class PhonePreview(
    val primary: String,
    val secondary: String,
    val color: Color = SugarliciousColors.TextPrimary,
    val trend: Trend? = null,
)

private fun previewFor(
    id: Int,
    state: TherapyDisplayState?,
): PhonePreview {
    val now = System.currentTimeMillis()
    val effectiveState = state ?: previewTherapyState(now)
    val baseId = SugarliciousComplicationIds.baseId(id)
    val presentation = ComplicationPresentationFormatter.format(baseId, effectiveState, now)
    val g = effectiveState.glucose
    val glucoseColor = when {
        g == null -> SugarliciousColors.TextPrimary
        g.valueMgDl < 80.0 -> SugarliciousColors.GlucoseLow
        g.valueMgDl > 160.0 -> SugarliciousColors.GlucoseHigh
        else -> SugarliciousColors.GlucoseInRange
    }
    return PhonePreview(
        primary = presentation.text,
        secondary = presentation.title.orEmpty(),
        color = if (baseId in setOf(
                SugarliciousComplicationIds.GLUCOSE,
                SugarliciousComplicationIds.GLUCOSE_TREND,
                SugarliciousComplicationIds.GLUCOSE_PLUS_DELTA,
                SugarliciousComplicationIds.GLUCOSE_TREND_DELTA,
                SugarliciousComplicationIds.GLUCOSE_TREND_DELTA_AGE,
                SugarliciousComplicationIds.GLUCOSE_TREND_AGE,
            )) glucoseColor else SugarliciousColors.TextPrimary,
        trend = presentation.trend,
    )
}

private fun previewTherapyState(now: Long): TherapyDisplayState =
    TherapyDisplayState(
        receivedAtEpochMs = now,
        sourceVersion = "AndroidAPS",
        glucose = app.aapswear.model.GlucoseState(
            valueMgDl = 123.0,
            displayUnit = GlucoseUnit.MG_DL,
            trend = Trend.FORTY_FIVE_UP,
            measuredAtEpochMs = now - 2 * 60_000L,
            deltaMgDl = 5.0,
        ),
        glucoseHistory = demoHistory(now, 24),
        insulin = app.aapswear.model.InsulinState(totalIob = 1.2, bolusIob = 0.8, basalIob = 0.4),
        carbs = app.aapswear.model.CarbState(cobGrams = 15.0),
        basal = app.aapswear.model.BasalState(currentUnitsPerHour = 0.70),
        loop = app.aapswear.model.LoopState(status = "enacted", lastRunAtEpochMs = now - 2 * 60_000L),
        pump = app.aapswear.model.PumpState(status = "OK", reservoirUnits = 120.0, batteryPercent = 80),
    )

internal fun complicationPreviewLabel(id: Int, state: TherapyDisplayState?): String {
    val entry = catalogEntryForVariant(id)
    val preview = previewFor(id, state)
    return "${entry?.name ?: "Comp"} ${preview.primary}".take(20)
}

private fun demoHistory(now: Long, hours: Int): List<GlucoseSample> {
    val count = hours * 12
    return (0..count).map { index ->
        val minutesAgo = (count - index) * 5L
        val phase = index % 24
        val value = when {
            phase < 8 -> 105.0 + phase * 4.0
            phase < 16 -> 137.0 - (phase - 8) * 3.0
            else -> 113.0 + (phase - 16) * 2.0
        }
        GlucoseSample(value, now - minutesAgo * 60_000L)
    }
}

private fun unitLabel(unit: GlucoseUnit?): String =
    if (unit == GlucoseUnit.MMOL_L) "mmol/L" else "mg/dL"

private const val PRESET_PREFS = "complication_setup"
private const val PRESET_KEY = "selected_ids"
private const val COMPLICATION_GRAPH_HOURS_KEY = "graph_hours"

internal fun loadComplicationPreset(context: Context): List<Int> =
    context.getSharedPreferences(PRESET_PREFS, Context.MODE_PRIVATE)
        .getString(PRESET_KEY, null)
        ?.split(',')
        ?.mapNotNull { it.toIntOrNull() }
        ?.filter { id -> id in SugarliciousComplicationVariantIds }
        ?.distinct()
        ?.take(4)
        .orEmpty()

private fun loadComplicationGraphHours(context: Context): Int =
    context.getSharedPreferences(PRESET_PREFS, Context.MODE_PRIVATE)
        .getInt(COMPLICATION_GRAPH_HOURS_KEY, 3)
        .takeIf { it in listOf(1, 2, 6, 12, 24) } ?: 3

private fun saveComplicationGraphHours(context: Context, hours: Int) {
    context.getSharedPreferences(PRESET_PREFS, Context.MODE_PRIVATE).edit {
        putInt(COMPLICATION_GRAPH_HOURS_KEY, hours.takeIf { it in listOf(1, 2, 6, 12, 24) } ?: 3)
    }
}

private fun togglePresetEntry(
    context: Context,
    current: List<Int>,
    entryId: Int,
): List<Int> {
    val updated = when {
        entryId in current -> current.filterNot { it == entryId }
        current.size >= 4 -> current
        else -> current + entryId
    }

    context.getSharedPreferences(PRESET_PREFS, Context.MODE_PRIVATE).edit {
        putString(PRESET_KEY, updated.joinToString(","))
    }

    return updated
}

private fun selectPresetVariant(
    context: Context,
    current: List<Int>,
    entry: ComplicationCatalogEntry,
    variantId: Int,
): List<Int> {
    if (entry.variants.none { it.id == variantId }) return current

    val familyIds = entry.variants.map { it.id }.toSet()
    val existingIndex = current.indexOfFirst { it in familyIds }
    val updated = when {
        existingIndex >= 0 -> current.toMutableList().also { it[existingIndex] = variantId }
        current.size < 4 -> current + variantId
        else -> current
    }.distinct()

    context.getSharedPreferences(PRESET_PREFS, Context.MODE_PRIVATE).edit {
        putString(PRESET_KEY, updated.joinToString(","))
    }
    return updated
}

internal suspend fun syncComplicationPreset(
    context: Context,
    ids: List<Int>,
    graphHours: Int = loadComplicationGraphHours(context),
) {
    val request = PutDataMapRequest.create(WearProtocol.COMPLICATION_PRESET_PATH).apply {
        dataMap.putIntegerArrayList("ids", ArrayList(ids))
        dataMap.putInt("graphHours", graphHours.takeIf { it in OVERVIEW_GRAPH_HOUR_OPTIONS } ?: 3)
        dataMap.putLong("updatedAt", System.currentTimeMillis())
    }.asPutDataRequest().setUrgent()

    Wearable.getDataClient(context).putDataItem(request).await()
}
