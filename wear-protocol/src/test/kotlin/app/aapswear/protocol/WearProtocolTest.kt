package app.aapswear.protocol
import app.aapswear.g7.CgmReading
import app.aapswear.model.*
import kotlin.test.*
class WearProtocolTest {
 @Test fun diagnosticBatchRoundTripsStableErrorCodes() {
  val event=DiagnosticEvent("id",123L,"WATCH","PREDICTION","PRED-201",DiagnosticSeverity.WARNING,"Cache retained")
  val decoded=WearProtocol.decodeDiagnostics(WearProtocol.encodeDiagnostics(DiagnosticBatch(listOf(event),124L)))
  assertEquals(listOf(event),decoded.events)
 }
 @Test fun roundTrip() { val s=TherapyDisplayState(receivedAtEpochMs=2,glucose=GlucoseState(100.0,GlucoseUnit.MG_DL,measuredAtEpochMs=1)); assertEquals(s,WearProtocol.decode(WearProtocol.encode(s))) }
 @Test fun migratesProtocolOneContractStoredInVersionField() {
  val legacy="""{"protocolVersion":1,"state":{"schemaVersion":1,"source":"ANDROID_APS","sourceVersion":"AAPS_EXTENDED_STATUS_V1","receivedAtEpochMs":2}}"""
  val migrated=WearProtocol.decode(legacy.encodeToByteArray())
  assertEquals(TherapyDisplayState.CURRENT_SCHEMA,migrated.schemaVersion)
  assertEquals("AAPS_EXTENDED_STATUS_V1",migrated.sourceContract)
  assertNull(migrated.sourceVersion)
 }
 @Test fun rejectsFutureProtocol() {
  val future="""{"protocolVersion":999,"state":{"receivedAtEpochMs":2}}"""
  assertFailsWith<IllegalArgumentException>{WearProtocol.decode(future.encodeToByteArray())}
 }
 @Test fun graphColorsRoundTripWithWatchConfigSchemaTwo() {
  val colors=WatchGraphColors(
   graphBackground=0xFF101010.toInt(),
   rangeLow=0xFFAA0000.toInt(),
   rangeInRange=0xFF00AA00.toInt(),
   rangeHigh=0xFFAAAA00.toInt(),
   cgmLow=0xFFBB0000.toInt(),
   cgmInRange=0xFF00BB00.toInt(),
   cgmHigh=0xFFBBBB00.toInt(),
   divider=0xFF888888.toInt(),
   outline=0xFF121212.toInt(),
  )
  val config=WatchConfig(graphColors=colors,sentAtEpochMs=123)
  assertEquals(config,WearProtocol.decodeConfig(WearProtocol.encodeConfig(config)))
 }
 @Test fun legacyWatchConfigUsesDefaultBasalColor() {
  val legacy="""{"schemaVersion":4,"uiColors":{}}"""
  val decoded=WearProtocol.decodeConfig(legacy.encodeToByteArray())
  assertEquals(WatchUiColors().basal,decoded.uiColors.basal)
 }
 @Test fun g7SetupAndDataSourceRoundTrip() {
  val command=G7SetupCommand("1234","SERIAL","00386270000000")
  assertEquals(command,WearProtocol.decodeG7Setup(WearProtocol.encodeG7Setup(command)))
  val config=WatchConfig(dataSource=WatchDataSource.DEXCOM_G7_WATCH)
  assertEquals(WatchDataSource.DEXCOM_G7_WATCH,WearProtocol.decodeConfig(WearProtocol.encodeConfig(config)).dataSource)
  assertFailsWith<IllegalArgumentException>{G7SetupCommand("12")}
 }
 @Test fun runtimeStatusAllowsSixthSugarliciousFace() {
  val sixth=WatchRuntimeStatus(activeSugarliciousFaceIndex=5,activeComplicationIds=listOf(1,1,2))
  val decoded=WearProtocol.decodeRuntimeStatus(WearProtocol.encodeRuntimeStatus(sixth))
  assertEquals(5,decoded.activeSugarliciousFaceIndex)
  assertEquals(listOf(1,2),decoded.activeComplicationIds)
 }
 @Test fun g7ReadingBatchRoundTripsAndDeduplicatesOnlyIdenticalIds() {
  val first=g7("first","session-a",1,100L)
  val sameId=first.copy(glucoseMgDl=999.0)
  val sameSequenceDifferentSession=g7("second","session-b",1,200L)
  val batch=G7ReadingBatch(batchId="batch-1",readings=listOf(first,sameId,sameSequenceDifferentSession),sentAtEpochMs=300L)
  val decoded=WearProtocol.decodeG7ReadingBatch(WearProtocol.encodeG7ReadingBatch(batch))
  assertEquals(listOf(first,sameSequenceDifferentSession),decoded.readings)
 }
 @Test fun g7ReadingBatchRejectsWrongSourceAndOversizedPayload() {
  val wrong=G7ReadingBatch(batchId="batch",readings=listOf(g7("wrong","session",1,100L).copy(source=DataSourceId.ANDROID_APS)),sentAtEpochMs=200L)
  assertFailsWith<IllegalArgumentException>{WearProtocol.decodeG7ReadingBatch(WearProtocol.encodeG7ReadingBatch(wrong))}
  val oversized=G7ReadingBatch(batchId="batch",readings=(0..G7ReadingBatch.MAX_READINGS).map{g7("id-$it","session",it.toLong(),it.toLong())},sentAtEpochMs=200L)
  assertFailsWith<IllegalArgumentException>{WearProtocol.decodeG7ReadingBatch(WearProtocol.encodeG7ReadingBatch(oversized))}
 }
 @Test fun g7AckAndVersionedWatchColorsRoundTrip() {
  val ack=G7ReadingAck(batchId="batch-1",acknowledgedIds=setOf("one","","two"),acknowledgedAtEpochMs=400L)
  assertEquals(setOf("one","two"),WearProtocol.decodeG7ReadingAck(WearProtocol.encodeG7ReadingAck(ack)).acknowledgedIds)
  val colors=WatchGraphColors(targetValue=0xFF123456.toInt(),signalLoss=0x44112233)
  val sync=WatchColorSync(graphColors=colors,sentAtEpochMs=500L)
  assertEquals(sync,WearProtocol.decodeWatchColorSync(WearProtocol.encodeWatchColorSync(sync)))
 }
 private fun g7(id:String,session:String,sequence:Long,timestamp:Long)=CgmReading(
  id=id,
  source=DataSourceId.DEXCOM_G7_WATCH,
  sensorId="sensor",
  sessionId=session,
  glucoseMgDl=120.0,
  timestampEpochMs=timestamp,
  receivedAtEpochMs=timestamp,
  sequenceNumber=sequence,
 )
}
