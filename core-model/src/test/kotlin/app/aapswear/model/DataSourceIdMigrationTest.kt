package app.aapswear.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DataSourceIdMigrationTest {
    @Test
    fun `legacy xdrip source decodes as other without remaining selectable`() {
        assertEquals(DataSourceId.OTHER, Json.decodeFromString<DataSourceId>("\"XDRIP_PLUS\""))
        assertFalse(DataSourceId.entries.any { it.name == "XDRIP_PLUS" })
    }

    @Test
    fun `legacy xdrip history sample keeps its measurement`() {
        val raw = """{"valueMgDl":121.0,"measuredAtEpochMs":1234,"source":"XDRIP_PLUS"}"""
        val sample = Json.decodeFromString<GlucoseSample>(raw)

        assertEquals(121.0, sample.valueMgDl, 0.0)
        assertEquals(1234L, sample.measuredAtEpochMs)
        assertEquals(DataSourceId.OTHER, sample.source)
    }
}
