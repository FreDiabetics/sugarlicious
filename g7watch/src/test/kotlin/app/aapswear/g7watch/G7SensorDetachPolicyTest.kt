package app.aapswear.g7watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class G7SensorDetachPolicyTest {
    @Test fun `detach stops only local collector and preserves history without ending sensor`() {
        val semantics = g7DetachSemantics()

        assertTrue(semantics.stopsCollector)
        assertTrue(semantics.clearsLocalSession)
        assertTrue(semantics.preservesHistory)
        assertFalse(semantics.sendsSensorEndCommand)
    }
}
