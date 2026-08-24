package app.aapswear.datasource.aaps

import android.os.Bundle
import app.aapswear.model.*

object AapsPayloadAdapter {
 const val ACTION = "info.nightscout.androidaps.status"

 @Suppress("DEPRECATION")
 fun parse(bundle: Bundle, receivedAtEpochMs: Long): TherapyDisplayState? =
  parse(bundle.keySet().associateWith { key -> bundle.get(key) }, receivedAtEpochMs)

 fun parse(values: Map<String, Any?>, receivedAtEpochMs: Long): TherapyDisplayState? {
  if (!AapsPayloadValidator().isValid(values, receivedAtEpochMs)) return null
  val value = values.number("glucoseMgdl") ?: return null
  val measured = values.number("glucoseTimeStamp")?.toLong() ?: return null
  val trend = when((values["slopeArrow"] as? String)?.trim()) { "⇊","DoubleDown"->Trend.DOUBLE_DOWN; "↓","SingleDown"->Trend.SINGLE_DOWN; "↘","FortyFiveDown"->Trend.FORTY_FIVE_DOWN; "→","Flat"->Trend.FLAT; "↗","FortyFiveUp"->Trend.FORTY_FIVE_UP; "↑","SingleUp"->Trend.SINGLE_UP; "⇈","DoubleUp"->Trend.DOUBLE_UP; else->Trend.UNKNOWN }
  val unit=if((values["units"] as? String)?.startsWith("mmol",true)==true) GlucoseUnit.MMOL_L else GlucoseUnit.MG_DL
  val delta=values.number("deltaMgdl")
  val averageDelta=values.number("avgDeltaMgdl")
  val low=values.number("low")
  val high=values.number("high")
  val iob=values.number("iob")
  val bolusIob=values.number("bolusIob")
  val basalIob=values.number("basalIob")
  val cob=values.number("cob")?.takeIf { it>=0 }
  val futureCarbs=values.number("futureCarbs")
  val profile=values["profile"] as? String
  val baseBasal=values.number("baseBasal")
  val tempStart=values.number("tempBasalStart")?.toLong()
  val tempDuration=values.number("tempBasalDurationInMinutes")?.toLong()?.takeIf { it>=0 }
  val tempAbsolute=values.number("tempBasalAbsolute")
  val tempPercent=values.number("tempBasalPercent")?.toInt()
  val suggestedAt=values.number("suggestedTimeStamp")?.toLong()?.takeIf { it>0 }
  val enactedAt=values.number("enactedTimeStamp")?.toLong()?.takeIf { it>0 }
  val suggestedPayload=values["suggested"] as? String
  val enactedPayload=values["enacted"] as? String
  val parsedTarget=AapsTargetParser.parseTarget(suggestedPayload)?:AapsTargetParser.parseTarget(enactedPayload)
  val targetValue=parsedTarget?.valueMgDl
  val targetStart=values.number("tempTargetStart")?.toLong()?.takeIf { it>0 }
  val targetDuration=values.number("tempTargetDurationInMinutes")?.toLong()?.takeIf { it>0 }
  val targetEnd=values.number("tempTargetEnd")?.toLong()?.takeIf { it>0 }
   ?:targetStart?.let { start -> targetDuration?.let { duration -> start+duration*60_000L } }
  val smb=AapsSmbParser.parse(enactedPayload,enactedAt)
  val predictions=AapsPredictionParser.parse(suggestedPayload?:enactedPayload,suggestedAt?:enactedAt?:measured)
  val pumpStatus=values["pumpStatus"] as? String
  val reservoir=values.number("pumpReservoir")
  val pumpBattery=values.number("pumpBattery")?.toInt()?.takeIf { it in 0..100 }
  val phoneBattery=values.number("phoneBattery")?.toInt()?.takeIf { it in 0..100 }
  val rigBattery=values.number("rigBattery")?.toInt()?.takeIf { it in 0..100 }
  val caps=buildSet {
   add(DataCapability.GLUCOSE); if(trend!=Trend.UNKNOWN)add(DataCapability.TREND); if(delta!=null)add(DataCapability.DELTA); if(averageDelta!=null)add(DataCapability.AVERAGE_DELTA)
   if(low!=null||high!=null||targetValue!=null)add(DataCapability.TARGET); if(parsedTarget?.temporary==true||targetStart!=null)add(DataCapability.TEMP_TARGET); if(iob!=null)add(DataCapability.IOB); if(bolusIob!=null)add(DataCapability.BOLUS_IOB); if(basalIob!=null)add(DataCapability.BASAL_IOB)
   if(smb!=null)add(DataCapability.SMB); if(cob!=null)add(DataCapability.COB); if(futureCarbs!=null)add(DataCapability.FUTURE_CARBS); if(baseBasal!=null)add(DataCapability.BASAL); if(tempStart!=null||tempAbsolute!=null||tempPercent!=null)add(DataCapability.TEMP_BASAL); if(predictions.isNotEmpty())add(DataCapability.PREDICTIONS)
   if(profile!=null)add(DataCapability.PROFILE); if(suggestedAt!=null||enactedAt!=null)add(DataCapability.LOOP); if(pumpStatus!=null)add(DataCapability.PUMP); if(reservoir!=null)add(DataCapability.RESERVOIR); if(pumpBattery!=null)add(DataCapability.PUMP_BATTERY); if(phoneBattery!=null)add(DataCapability.PHONE_BATTERY)
  }
  val detectedContract=AapsCapabilityDetector.detectContract(values).id
  val loopState = if (suggestedAt != null || enactedAt != null) {
   LoopState(
    status = if (enactedAt != null) "enacted" else "suggested",
    lastRunAtEpochMs = enactedAt ?: suggestedAt,
    suggestedAtEpochMs = suggestedAt,
    enactedAtEpochMs = enactedAt,
    suggestedPayload = suggestedPayload,
    enactedPayload = enactedPayload,
    smbUnits = smb?.units,
    smbAtEpochMs = smb?.deliveredAtEpochMs,
   )
  } else null
  return TherapyDisplayState(
   receivedAtEpochMs=receivedAtEpochMs, sourceContract=detectedContract,
   glucose=GlucoseState(value,unit,trend,measured,delta,averageDelta,source=DataSourceId.ANDROID_APS,receivedAtEpochMs=receivedAtEpochMs),
   targetHistory=targetValue?.let { target ->
    val observedAt=targetStart?:suggestedAt?:enactedAt?:measured
    listOf(TargetSample(target,observedAt,targetEnd?:observedAt,parsedTarget?.temporary==true||targetStart!=null))
   }.orEmpty(),
   glucosePredictions=predictions,
   insulin=if(iob!=null||bolusIob!=null||basalIob!=null) InsulinState(iob,bolusIob,basalIob) else null,
   carbs=if(cob!=null||futureCarbs!=null) CarbState(cob,futureCarbs) else null,
   basal=if(baseBasal!=null||tempStart!=null||tempAbsolute!=null||tempPercent!=null) BasalState(baseBasal,tempAbsolute,tempPercent,tempStart,tempDuration,tempStart?.let{s->tempDuration?.let{d->s+d*60_000}},values["tempBasalString"] as? String) else null,
   target=if(low!=null||high!=null||targetValue!=null) TargetState(low,high,parsedTarget?.temporary==true||targetStart!=null,targetValue,targetStart,targetEnd) else null,
   loop=loopState,
   pump=if(pumpStatus!=null||reservoir!=null||pumpBattery!=null) PumpState(pumpStatus,reservoir,pumpBattery) else null,
   device=if(phoneBattery!=null||rigBattery!=null) DeviceState(phoneBattery,rigBattery) else null,
   profile=profile?.let{ProfileState(it)}, capabilities=caps
  )
 }
 private fun Map<String,Any?>.number(key:String):Double? = get(key)?.let { when(it){ is Number->it.toDouble(); is String->it.toDoubleOrNull(); else->null } }?.takeIf{it.isFinite()}
}
