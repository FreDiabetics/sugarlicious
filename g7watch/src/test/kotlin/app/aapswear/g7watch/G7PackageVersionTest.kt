package app.aapswear.g7watch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class G7PackageVersionTest {
    @Test
    fun `schema seven release has a new package version`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

        assertEquals(19L, packageInfo.longVersionCode)
    }
}
