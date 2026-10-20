package app.aapswear.mobile.ui.theme

import android.content.Context
import android.os.Build
import androidx.annotation.ColorRes
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource

@ColorRes
@RequiresApi(31)
private fun menuAccentResource(dark: Boolean): Int =
    if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600

@ColorRes
@RequiresApi(31)
private fun menuOnAccentResource(dark: Boolean): Int =
    if (dark) android.R.color.system_accent1_800 else android.R.color.system_accent1_0

/** Read the public device palette, avoiding stale OEM system_primary aliases. */
internal fun mobileMenuAccentArgb(context: Context, dark: Boolean): Int =
    if (Build.VERSION.SDK_INT >= 31) {
        context.getColor(menuAccentResource(dark))
    } else {
        SugarliciousColors.argb(SugarliciousColorRole.PRIMARY)
    }

internal fun mobileMenuOnAccentArgb(context: Context, dark: Boolean): Int =
    if (Build.VERSION.SDK_INT >= 31) {
        context.getColor(menuOnAccentResource(dark))
    } else {
        SugarliciousColors.argb(SugarliciousColorRole.ON_PRIMARY)
    }

@Composable
internal fun mobileMenuAccent(dark: Boolean): Color =
    if (Build.VERSION.SDK_INT >= 31) {
        // colorResource observes LocalConfiguration, including device palette changes.
        colorResource(menuAccentResource(dark))
    } else {
        SugarliciousColors.Primary
    }

@Composable
internal fun mobileMenuOnAccent(dark: Boolean): Color =
    if (Build.VERSION.SDK_INT >= 31) {
        colorResource(menuOnAccentResource(dark))
    } else {
        SugarliciousColors.OnPrimary
    }
