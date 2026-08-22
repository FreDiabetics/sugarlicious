package app.aapswear.mobile

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.model.TherapyDisplayState
import kotlinx.coroutines.launch

internal const val SUGARLICIOUS_G6_STYLE_FACE_INDEX = 5

internal enum class WatchFaceSelectionEvent {
    USER_SELECTION,
    CGM_REFRESH,
    COLLECTOR_REFRESH,
    DATA_LAYER_REFRESH,
}

internal fun reduceWatchFaceSelection(
    currentFaceIndex: Int,
    requestedFaceIndex: Int?,
    event: WatchFaceSelectionEvent,
): Int =
    if (event == WatchFaceSelectionEvent.USER_SELECTION && requestedFaceIndex != null) {
        requestedFaceIndex.coerceIn(sugarliciousWatchFaceCards.indices)
    } else {
        currentFaceIndex.coerceIn(sugarliciousWatchFaceCards.indices)
    }

internal data class SugarliciousWatchFaceCard(
    val name: String,
    val style: String,
    val slots: Int,
    val features: List<String>,
)

internal val sugarliciousWatchFaceCards =
    listOf(
        SugarliciousWatchFaceCard(
            name = "Sugarlicious Analog",
            style = "Analog",
            slots = 8,
            features = listOf("Graph", "AOD"),
        ),
        SugarliciousWatchFaceCard(
            name = "Sugarlicious Orbit",
            style = "Analog",
            slots = 4,
            features = listOf("Glukosering", "Graph", "AOD"),
        ),
        SugarliciousWatchFaceCard(
            name = "Sugarlicious Rings",
            style = "Analog",
            slots = 4,
            features = listOf("Glukosering", "Graph", "AOD"),
        ),
        SugarliciousWatchFaceCard(
            name = "Sugarlicious Graph",
            style = "Analog",
            slots = 4,
            features = listOf("Großer Graph", "AOD"),
        ),
        SugarliciousWatchFaceCard(
            name = "Sugarlicious Digital",
            style = "Digital",
            slots = 8,
            features = listOf("Glukose", "Graph", "AOD"),
        ),
        SugarliciousWatchFaceCard(
            name = "Sugarlicious G6 Style",
            style = "G7 Collector",
            slots = 3,
            features = listOf("G7 Collector", "3h Graph", "AOD", "Fixed Layout"),
        ),
    )

internal data class LegacyWatchFaceCard(val name: String, val previewRes: Int)

internal val legacyWatchFaceCards = listOf(
    LegacyWatchFaceCard("AAPS BigChart", R.drawable.legacy_aaps_big_chart),
    LegacyWatchFaceCard("AAPS Circle", R.drawable.legacy_aaps_circle),
    LegacyWatchFaceCard("AAPS Cockpit", R.drawable.legacy_aaps_cockpit),
    LegacyWatchFaceCard("AAPS Community", R.drawable.legacy_aaps_community),
    LegacyWatchFaceCard("AAPS Digital Style", R.drawable.legacy_aaps_digital_style),
    LegacyWatchFaceCard("AAPS Large", R.drawable.legacy_aaps_large),
    LegacyWatchFaceCard("AAPS NoChart", R.drawable.legacy_aaps_no_chart),
    LegacyWatchFaceCard("AAPS Standard", R.drawable.legacy_aaps_standard),
    LegacyWatchFaceCard("AAPS V2", R.drawable.legacy_aaps_v2),
    LegacyWatchFaceCard("AAPS V2 TT DarkOnly", R.drawable.legacy_aaps_v2_tt_dark),
    LegacyWatchFaceCard("AAPS V4", R.drawable.legacy_aaps_v4),
    LegacyWatchFaceCard("AIMICO", R.drawable.legacy_aimico),
    LegacyWatchFaceCard("Analog G-Watch", R.drawable.legacy_analog_g_watch),
    LegacyWatchFaceCard("Blue Ring", R.drawable.legacy_blue_ring),
    LegacyWatchFaceCard("Digital Big Graph", R.drawable.legacy_digital_big_graph),
    LegacyWatchFaceCard("Digital G-Watch", R.drawable.legacy_digital_g_watch),
    LegacyWatchFaceCard("Gears", R.drawable.legacy_gears),
    LegacyWatchFaceCard("Gota", R.drawable.legacy_gota),
    LegacyWatchFaceCard("LuckyLoopKoeln", R.drawable.legacy_lucky_loop_koeln),
    LegacyWatchFaceCard("P-Zero", R.drawable.legacy_p_zero),
    LegacyWatchFaceCard("Robby", R.drawable.legacy_robby),
    LegacyWatchFaceCard("Simple Digital", R.drawable.legacy_simple_digital),
    LegacyWatchFaceCard("AAPS SteamPunk", R.drawable.legacy_steam_punk),
)

@Composable
internal fun SugarliciousWatchScreen(
    state: TherapyDisplayState?,
    preferences: DashboardUiPreferences,
    onSelectedFace: (Int) -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val savedFaceIndex = SugarliciousWatchFaceSelectionStore.read(appContext, preferences.watchFaceIndex)
    val g6StyleRelevant =
        SugarliciousWatchFaceSelectionStore.isG6StyleRelevant(appContext, state, preferences)
    var activeFaceIndex by remember(savedFaceIndex) { mutableStateOf(savedFaceIndex) }
    var editingFaceIndex by remember(savedFaceIndex) { mutableStateOf(savedFaceIndex) }
    var facePresets by remember { mutableStateOf(WatchFacePresetStore.readAll(appContext)) }

    LaunchedEffect(appContext) {
        runCatching { requestWatchRuntimeStatus(appContext) }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Ziffernblätter",
            modifier = Modifier.fillMaxWidth(),
            color = SugarliciousColors.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start,
        )
        sugarliciousWatchFaceCards.indices.chunked(2).forEach { indices ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                indices.forEach { index ->
                    val enabled = index != SUGARLICIOUS_G6_STYLE_FACE_INDEX || g6StyleRelevant
                    WatchFaceTile(
                        modifier = Modifier.weight(1f),
                        face = sugarliciousWatchFaceCards[index],
                        index = index,
                        state = state,
                        activeComplicationIds = facePresets.getOrElse(index) { emptyList() },
                        selected = activeFaceIndex == index,
                        enabled = enabled,
                        onSelected = {
                            editingFaceIndex = index
                            activeFaceIndex =
                                reduceWatchFaceSelection(
                                    currentFaceIndex = activeFaceIndex,
                                    requestedFaceIndex = index,
                                    event = WatchFaceSelectionEvent.USER_SELECTION,
                                )
                            SugarliciousWatchFaceSelectionStore.write(appContext, index)
                            val activated = WatchFacePresetStore.activate(appContext, index)
                            facePresets =
                                WatchFacePresetStore.readAll(appContext).toMutableList().also {
                                    it[index] = activated
                                }
                            onSelectedFace(index)
                        },
                    )
                }
                if (indices.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        Text(
            text = "Complications",
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            color = SugarliciousColors.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start,
        )

        key(editingFaceIndex) {
            if (editingFaceIndex == SUGARLICIOUS_G6_STYLE_FACE_INDEX) {
                Surface(
                    color = SugarliciousColors.SurfaceHigh,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text =
                            if (g6StyleRelevant) {
                                "G6 Style nutzt drei feste, nicht austauschbare Slots für Glukose/Trend, den 3-Stunden-Graphen und Quelle/Freshness. Alle Werte kommen aus dem zentralen Sugarlicious-Resolver."
                            } else {
                                "G6 Style wird verfügbar, sobald der G7 Watch Collector eingerichtet oder als Datenquelle aktiv ist."
                            },
                        color = SugarliciousColors.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            } else {
                CompositionLocalProvider(LocalSugarliciousTrendArrowMaxSize provides 8.dp) {
                    ComplicationStudio(
                        state = state,
                        onPresetChanged = { updated ->
                            WatchFacePresetStore.save(appContext, editingFaceIndex, updated)
                            facePresets = facePresets.toMutableList().also { presets ->
                                presets[editingFaceIndex] = updated
                            }
                        },
                    )
                }
            }
        }
    }

}

@Composable
private fun WatchFaceTile(
    modifier: Modifier,
    face: SugarliciousWatchFaceCard,
    index: Int,
    state: TherapyDisplayState?,
    activeComplicationIds: List<Int>,
    selected: Boolean,
    enabled: Boolean,
    onSelected: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(24.dp)

    Surface(
        modifier =
            modifier
                .aspectRatio(1f)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color =
                        if (selected) {
                            SugarliciousColors.Primary
                        } else {
                            SugarliciousColors.Border.copy(alpha = if (enabled) 0.58f else 0.32f)
                        },
                    shape = shape,
                )
                .clickable(enabled = enabled) {
                    onSelected()
                    scope.launch {
                        val appContext = context.applicationContext
                        val preset = WatchFacePresetStore.activate(appContext, index)
                        runCatching { syncComplicationPreset(appContext, preset) }
                        val nodes = runCatching { requestWatchFaceApply(appContext, index) }.getOrDefault(0)
                        if (nodes == 0) {
                            Toast.makeText(context, "Watch nicht erreichbar", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
        shape = shape,
        color =
            when {
                selected -> SugarliciousColors.SurfaceSelected
                enabled -> SugarliciousColors.Surface
                else -> SugarliciousColors.Surface.copy(alpha = 0.55f)
            },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            FaceDial(
                index = index,
                state = state,
                activeComplicationIds = activeComplicationIds,
                modifier = Modifier.size(116.dp),
            )

            Text(
                text = face.name,
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                color = if (enabled) SugarliciousColors.TextPrimary else SugarliciousColors.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            if (index == SUGARLICIOUS_G6_STYLE_FACE_INDEX) {
                Text(
                    text = if (enabled) "G7 Collector Style" else "G7 Collector erforderlich",
                    color = if (enabled) SugarliciousColors.Primary else SugarliciousColors.TextSecondary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
