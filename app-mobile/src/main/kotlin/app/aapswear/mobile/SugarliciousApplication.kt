package app.aapswear.mobile

import android.app.Application
import android.content.res.Configuration
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Reconcile device-following widgets on system mode events, never through polling. */
class SugarliciousApplication : Application() {
    private val appearanceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var nightMode = Configuration.UI_MODE_NIGHT_UNDEFINED

    override fun onCreate() {
        super.onCreate()
        app.aapswear.uishared.ProductMigrationLifecycle
            .requireReady(this)
        if (app.aapswear.uishared.ProductMigrationLifecycle
                .isQuiesced(this) ||
            app.aapswear.uishared.ProductMigrationLifecycle
                .isRetired(this)
        ) {
            return
        }
        reconcileWidgetAppearance(resources.configuration)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        reconcileWidgetAppearance(newConfig)
    }

    private fun reconcileWidgetAppearance(configuration: Configuration) {
        val current = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val preferences = getSharedPreferences("widget_system_appearance", MODE_PRIVATE)
        val previous = if (nightMode == Configuration.UI_MODE_NIGHT_UNDEFINED) preferences.getInt("night", current) else nightMode
        val scaleChanged = preferences.getFloat("font_scale", configuration.fontScale) != configuration.fontScale || preferences.getInt("density_dpi", configuration.densityDpi) != configuration.densityDpi
        nightMode = current
        preferences.edit {
            putInt("night", current)
            putFloat("font_scale", configuration.fontScale)
            putInt("density_dpi", configuration.densityDpi)
        }
        if (current != previous || scaleChanged) {
            appearanceScope.launch {
                try {
                    SugarliciousWidgets.update(applicationContext)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    recordMobileDiagnostic(
                        module = "WIDGET",
                        code = "WIDGET-THEME-503",
                        message = "Widget appearance refresh retained previous surfaces",
                        severity = app.aapswear.model.DiagnosticSeverity.WARNING,
                        metadata = mapOf("error" to error.javaClass.simpleName),
                    )
                }
            }
        }
    }
}
