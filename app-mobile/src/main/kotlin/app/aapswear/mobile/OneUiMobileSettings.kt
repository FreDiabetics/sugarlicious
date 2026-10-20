package app.aapswear.mobile

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.widget.Switch

/** Public device widgets retain Samsung's installed theme and accessibility. */
internal fun mobileSettingsSwitch(context: Context, checked: Boolean, onChanged: (Boolean) -> Unit): Switch =
    Switch(mobileSettingsControlContext(context)).apply {
        showText = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        isChecked = checked
        setOnCheckedChangeListener { _, selected -> onChanged(selected) }
    }

internal fun mobileSettingsControlContext(context: Context, dark: Boolean = !app.aapswear.mobile.ui.theme.SugarliciousColors.palette.isLight): Context {
    val configuration =
        Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
    val themeRes =
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.R.style.Theme_DeviceDefault_DayNight
        } else if (dark) {
            android.R.style.Theme_DeviceDefault
        } else {
            android.R.style.Theme_DeviceDefault_Light
        }
    return ContextThemeWrapper(context.createConfigurationContext(configuration), themeRes).apply {
        theme.applyStyle(R.style.MobileSettingsControls, true)
    }
}

/** Touch gestures batch writes; keyboard/accessibility changes have no touch-end callback. */
internal class SettingsSliderInteraction(
    private val onValue: (Int) -> Unit,
    private val onCommit: () -> Unit,
) {
    private var tracking = false

    fun started() {
        tracking = true
    }

    fun changed(value: Int, fromUser: Boolean) {
        if (!fromUser) return
        onValue(value)
        if (!tracking) onCommit()
    }

    fun stopped() {
        tracking = false
        onCommit()
    }
}

internal class MobileSettingsNavigation {
    var category: String? = null
        private set
    var title: String = "Einstellungen"
        private set

    fun open(category: String, title: String) {
        this.category = category
        this.title = title
    }

    fun back(): Boolean {
        if (category == null) return false
        category = null
        title = "Einstellungen"
        return true
    }
}

internal object MobileSettingsStyle {
    fun page(light: Boolean): Int = if (light) Color.rgb(245, 245, 245) else Color.BLACK

    fun card(light: Boolean): Int = if (light) Color.WHITE else Color.rgb(28, 28, 28)

    fun text(light: Boolean): Int = if (light) Color.rgb(20, 20, 20) else Color.rgb(250, 250, 250)

    fun secondary(light: Boolean): Int = if (light) Color.rgb(96, 96, 96) else Color.rgb(183, 183, 190)

    fun divider(light: Boolean): Int = if (light) Color.rgb(224, 224, 224) else Color.rgb(52, 52, 52)
}
