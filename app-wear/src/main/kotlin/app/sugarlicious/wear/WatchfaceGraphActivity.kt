package app.sugarlicious.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sugarlicious.complications.ActiveProviderUpdates
import app.sugarlicious.complications.NativeApexGraphComplication
import app.sugarlicious.complications.NativeOrbitGraphComplication
import app.sugarlicious.complications.WatchfaceGraphProfile
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow

/** A read-only destination for the editor-colored WFF graph tap. Back returns to the watchface. */
@AndroidEntryPoint
class WatchfaceGraphActivity : ComponentActivity() {
    private val model by viewModels<WearOverviewViewModel>()
    private val requestedTarget = MutableStateFlow<GraphPopupTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!select(intent)) return
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND or android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes =
            window.attributes.apply {
                dimAmount = 0.35f
                blurBehindRadius = (32 * resources.displayMetrics.density).toInt()
            }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        setContent {
            val target by requestedTarget.collectAsStateWithLifecycle()
            val state by model.uiState.collectAsStateWithLifecycle()
            val configuration = LocalConfiguration.current
            val geometry = graphPopupGeometry(configuration.screenWidthDp, configuration.screenHeightDp)
            Box(Modifier.fillMaxSize().background(Color.Transparent), contentAlignment = Alignment.Center) {
                target?.let { selected ->
                    key(selected) {
                        AndroidView(
                            factory = { context ->
                                WatchfaceGraphPopupView(context, selected).apply {
                                    setOnClickListener { finish() }
                                }
                            },
                            update = { it.update(state) },
                            modifier = Modifier.size(geometry.widthDp.dp, geometry.heightDp.dp).clip(RoundedCornerShape(geometry.cornerRadiusDp.dp)),
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        select(intent)
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) return
        // Samsung's watchface renderer can lose the graph bitmap when the overlay closes.
        // Re-publish just the face's graph when the overlay is no longer visible.
        val provider =
            when (requestedTarget.value?.profile) {
                WatchfaceGraphProfile.APEX -> NativeApexGraphComplication::class.java
                WatchfaceGraphProfile.ORBIT -> NativeOrbitGraphComplication::class.java
                else -> return
            }
        ActiveProviderUpdates.request(applicationContext, provider)
    }

    private fun select(intent: Intent): Boolean {
        val target = GraphPopupTarget.parse(intent.data)
        if (target == null) {
            finish()
            return false
        }
        requestedTarget.value = target
        return true
    }
}
