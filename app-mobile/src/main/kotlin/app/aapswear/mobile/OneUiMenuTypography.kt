package app.aapswear.mobile

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.aapswear.mobile.ui.theme.SystemFontFamily
import app.aapswear.mobile.ui.theme.SystemMaterialTypography

/** Measured One UI settings roles; do not inherit Material's 24sp body line height. */
internal val oneUiMenuTypography =
    SystemMaterialTypography.let { base ->
        val row = TextStyle(fontFamily = SystemFontFamily, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal)
        val secondary = row.copy(fontSize = 13.sp, lineHeight = 18.sp)
        val title = row.copy(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
        base.copy(
            headlineSmall = title,
            titleLarge = title,
            titleMedium = row,
            titleSmall = secondary,
            bodyLarge = row,
            bodyMedium = secondary,
            bodySmall = secondary,
            labelLarge = row.copy(fontSize = 17.sp, lineHeight = 22.sp),
            labelMedium = secondary,
            labelSmall = secondary,
        )
    }
