package app.aapswear.datasource.aaps
import app.aapswear.model.*
import kotlin.test.*
class AapsPayloadAdapterTest {
 @Test fun parsesDocumentedBroadcast() { val b=mapOf("glucoseMgdl" to 123.0,"glucoseTimeStamp" to 900_000L,"units" to "mmol","slopeArrow" to "↗","iob" to 1.25,"cob" to 18.0,"profile" to "Default"); val s=assertNotNull(AapsPayloadAdapter.parse(b,1_000_000)); assertEquals(Trend.FORTY_FIVE_UP,s.glucose?.trend); assertEquals(GlucoseUnit.MMOL_L,s.glucose?.displayUnit); assertTrue(DataCapability.IOB in s.capabilities) }
 @Test fun rejectsMissingAndWrongTypes() { assertNull(AapsPayloadAdapter.parse(emptyMap(),1)); assertNull(AapsPayloadAdapter.parse(mapOf("glucoseMgdl" to "oops","glucoseTimeStamp" to 1L),1)) }
 @Test fun unknownFieldsAreIgnored() { assertNotNull(AapsPayloadAdapter.parse(mapOf("glucoseMgdl" to 100.0,"glucoseTimeStamp" to 1L,"futureThing" to Any()),2)) }
 @Test fun parsesCurrentDevExtendedPayload() {
  val values=mapOf<String,Any?>("glucoseMgdl" to 145.0,"glucoseTimeStamp" to 1_000L,"units" to "mg/dl","slopeArrow" to "→","deltaMgdl" to 2.0,"avgDeltaMgdl" to 1.5,"high" to 180.0,"low" to 70.0,"bolusIob" to 0.8,"basalIob" to 0.4,"iob" to 1.2,"cob" to 12.0,"futureCarbs" to 5.0,"phoneBattery" to 75,"rigBattery" to 80,"suggestedTimeStamp" to 900L,"suggested" to "{}","enactedTimeStamp" to 950L,"enacted" to "{}","baseBasal" to 0.9,"profile" to "Default","tempBasalStart" to 1_000L,"tempBasalDurationInMinutes" to 30L,"tempBasalAbsolute" to 1.1,"tempBasalString" to "1.10U/h","pumpTimeStamp" to 990L,"pumpBattery" to 60,"pumpReservoir" to 120.0,"pumpStatus" to "OK")
  val state=assertNotNull(AapsPayloadAdapter.parse(values,1_100L))
  assertEquals("AAPS_EXTENDED_STATUS_V1",state.sourceContract); assertNull(state.sourceVersion); assertEquals(2.0,state.glucose?.deltaMgDl); assertEquals(0.8,state.insulin?.bolusIob); assertEquals(5.0,state.carbs?.futureCarbsGrams); assertEquals(1_801_000L,state.basal?.tempEndsAtEpochMs); assertEquals(950L,state.loop?.lastRunAtEpochMs); assertEquals(120.0,state.pump?.reservoirUnits); assertEquals(75,state.device?.phoneBatteryPercent)
  assertTrue(DataCapability.PUMP in state.capabilities); assertTrue(DataCapability.TEMP_BASAL in state.capabilities); assertTrue(DataCapability.AVERAGE_DELTA in state.capabilities)
 }
 @Test fun fallsBackToEnactedTargetWhenSuggestedHasNoTarget() {
  val state=assertNotNull(AapsPayloadAdapter.parse(mapOf<String,Any?>("glucoseMgdl" to 110.0,"glucoseTimeStamp" to 1_000L,"suggested" to "{}","enacted" to "{\"targetBG\":140}"),1_100L))
  assertEquals(140.0,state.target?.valueMgDl)
  assertTrue(DataCapability.TARGET in state.capabilities)
 }
 @Test fun missingOptionalAndInvalidBatteryDoNotCrash() { val state=assertNotNull(AapsPayloadAdapter.parse(mapOf("glucoseMgdl" to 90.0,"glucoseTimeStamp" to 1L,"pumpBattery" to 150),2)); assertNull(state.pump); assertTrue(DataCapability.PUMP_BATTERY !in state.capabilities) }
 @Test fun rejectsImplausibleFutureTimestamp() { assertNull(AapsPayloadAdapter.parse(mapOf("glucoseMgdl" to 100.0,"glucoseTimeStamp" to 400_001L),100_000L)) }
 @Test fun preservesRealTempTargetStartAndDurationWithoutExtendingIt() {
  val start=1_000_000L
  val state=assertNotNull(AapsPayloadAdapter.parse(mapOf<String,Any?>(
   "glucoseMgdl" to 110.0,
   "glucoseTimeStamp" to start,
   "suggestedTimeStamp" to start,
   "suggested" to "{\"targetBG\":140,\"reason\":\"active temp target\"}",
   "tempTargetStart" to start,
   "tempTargetDurationInMinutes" to 30L,
  ),start+1_000L))
  assertTrue(state.target?.temporary==true)
  assertEquals(start,state.target?.startedAtEpochMs)
  assertEquals(start+30*60_000L,state.target?.endsAtEpochMs)
  assertEquals(listOf(TargetSample(140.0,start,start+30*60_000L,true)),state.targetHistory)
 }
 @Test fun tempTargetWithoutPublicTimingDoesNotInventAnExpiry() {
  val measured=1_000_000L
  val state=assertNotNull(AapsPayloadAdapter.parse(mapOf<String,Any?>(
   "glucoseMgdl" to 110.0,
   "glucoseTimeStamp" to measured,
   "suggested" to "{\"targetBG\":140,\"isTempTarget\":true}",
  ),measured+1_000L))
  assertNull(state.target?.startedAtEpochMs)
  assertNull(state.target?.endsAtEpochMs)
  assertEquals(measured,state.targetHistory.single().startedAtEpochMs)
  assertEquals(measured,state.targetHistory.single().endsAtEpochMs)
 }

}
