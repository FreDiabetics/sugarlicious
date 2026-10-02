package app.aapswear.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkAccessPolicyTest {
    @Test fun `android 17 requests access only for enabled private network destinations`() {
        assertTrue(LocalNetworkAccessPolicy.needsPermission(37, true, "http://192.168.178.2:1337", false))
        assertTrue(LocalNetworkAccessPolicy.needsPermission(37, true, "http://10.0.0.2", false))
        assertTrue(LocalNetworkAccessPolicy.needsPermission(37, true, "http://nightscout.local", false))
        assertTrue(LocalNetworkAccessPolicy.needsPermission(37, true, "http://[fd00::1]", false))
    }

    @Test fun `public destinations and older android do not request local network access`() {
        assertFalse(LocalNetworkAccessPolicy.needsPermission(37, true, "https://example.com", false))
        assertFalse(LocalNetworkAccessPolicy.needsPermission(36, true, "http://192.168.1.2", false))
    }

    @Test fun `disabled integration and granted permission do not request again`() {
        assertFalse(LocalNetworkAccessPolicy.needsPermission(37, false, "http://192.168.1.2", false))
        assertFalse(LocalNetworkAccessPolicy.needsPermission(37, true, "http://192.168.1.2", true))
    }

    @Test fun `malformed destinations never trigger a permission prompt`() {
        assertFalse(LocalNetworkAccessPolicy.needsPermission(37, true, "not a url", false))
        assertFalse(LocalNetworkAccessPolicy.needsPermission(37, true, "", false))
    }
}
