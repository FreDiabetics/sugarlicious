package app.aapswear.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class AapsSourceTrustPolicyTest {
    private val configured =
        AapsSourceBinding(
            packageName = "info.nightscout.androidaps",
            signingCertificateSha256 = "AA:BB",
        )

    @Test
    fun `only the official AndroidAPS package can be selected`() {
        assertEquals(true, AapsSourceTrustPolicy.isAllowedPackageName("info.nightscout.androidaps"))
        assertEquals(false, AapsSourceTrustPolicy.isAllowedPackageName("info.nightscout.aapspumpcontrol"))
        assertEquals(false, AapsSourceTrustPolicy.isAllowedPackageName("info.nightscout.androidaps.dev"))
        assertEquals(false, AapsSourceTrustPolicy.isAllowedPackageName("attacker.example"))
    }

    @Test
    fun `rejects input until an installed package has been configured`() {
        assertEquals(
            AapsSourceTrust.REJECTED_NOT_CONFIGURED,
            AapsSourceTrustPolicy.evaluate(null, null, emptySet(), null),
        )
    }

    @Test
    fun `rejects a broadcast whose verified Android sender differs from configured package`() {
        assertEquals(
            AapsSourceTrust.REJECTED_WRONG_SENDER,
            AapsSourceTrustPolicy.evaluate(configured, "other.app", setOf("other.app"), "AA:BB"),
        )
    }

    @Test
    fun `accepts a matching verified Android sender and certificate`() {
        assertEquals(
            AapsSourceTrust.VERIFIED,
            AapsSourceTrustPolicy.evaluate(configured, configured.packageName, setOf(configured.packageName), "AA:BB"),
        )
    }

    @Test
    fun `rejects a changed signing certificate`() {
        assertEquals(
            AapsSourceTrust.REJECTED_CERTIFICATE_CHANGED,
            AapsSourceTrustPolicy.evaluate(configured, configured.packageName, setOf(configured.packageName), "CC:DD"),
        )
    }

    @Test
    fun `labels old broadcasts without shared sender identity as legacy compatibility`() {
        assertEquals(
            AapsSourceTrust.LEGACY_COMPATIBILITY,
            AapsSourceTrustPolicy.evaluate(configured, null, emptySet(), "AA:BB"),
        )
    }
}
