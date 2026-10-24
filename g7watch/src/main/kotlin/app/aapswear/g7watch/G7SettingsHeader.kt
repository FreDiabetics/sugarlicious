package app.aapswear.g7watch

import android.app.Activity
import android.widget.LinearLayout

internal fun Activity.g7SettingsHeader(
    title: String,
    palette: G7AppearancePalette,
): LinearLayout =
    app.aapswear.uishared.WearSettingsUi
        .header(this, title, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY)) { finish() }
