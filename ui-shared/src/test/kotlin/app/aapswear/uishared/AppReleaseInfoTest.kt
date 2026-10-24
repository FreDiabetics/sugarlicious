package app.aapswear.uishared

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppReleaseInfoTest {
    @Test fun `about uses installed package version and does not invent collector changes`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unbekannt"
        assertTrue(AppReleaseInfo.summary(context, AppReleaseInfo.Product.WEAR).startsWith("Version $installed"))
        assertTrue(AppReleaseInfo.summary(context, AppReleaseInfo.Product.WEAR).contains("BR-/TBR"))
        assertEquals("Version $installed", AppReleaseInfo.summary(context, AppReleaseInfo.Product.COLLECTOR))
    }
}
