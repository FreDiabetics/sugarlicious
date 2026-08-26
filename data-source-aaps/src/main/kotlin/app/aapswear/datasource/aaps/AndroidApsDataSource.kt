package app.aapswear.datasource.aaps

import android.content.Context
import android.os.Build
import app.aapswear.datasource.HealthDataSource
import app.aapswear.model.FreshnessPolicy
import app.aapswear.model.TherapyDisplayState
import java.util.concurrent.atomic.AtomicReference

enum class AapsContract(val id: String?) {
 DEV_EXTENDED_STATUS_V1("AAPS_EXTENDED_STATUS_V1"),
 STABLE_LEGACY_STATUS("AAPS_LEGACY_STATUS"),
 UNSUPPORTED(null),
}

data class AapsInstallation(val packageName:String,val versionName:String?,val versionCode:Long)

class AapsCapabilityDetector {
 fun detect(values:Map<String,Any?>):AapsContract = when {
  values.keys.any { it in EXTENDED_KEYS } -> AapsContract.DEV_EXTENDED_STATUS_V1
  "glucoseMgdl" in values && "glucoseTimeStamp" in values -> AapsContract.STABLE_LEGACY_STATUS
  else -> AapsContract.UNSUPPORTED
 }
 companion object {
  private val EXTENDED_KEYS=setOf("deltaMgdl","avgDeltaMgdl","bolusIob","basalIob","baseBasal","pumpStatus","pumpReservoir","therapyEvents")
  private val KNOWN_PACKAGES=listOf("info.nightscout.androidaps","info.nightscout.aapspumpcontrol","info.nightscout.aapsclient","info.nightscout.aapsclient2","info.nightscout.aapsclient3")
  fun detectContract(values:Map<String,Any?>)=AapsCapabilityDetector().detect(values)
  @Suppress("DEPRECATION")
  fun detectInstallation(context:Context):AapsInstallation?=KNOWN_PACKAGES.firstNotNullOfOrNull { packageName ->
   runCatching { context.packageManager.getPackageInfo(packageName,0) }.getOrNull()?.let { info ->
    val code=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.P)info.longVersionCode else info.versionCode.toLong()
    AapsInstallation(packageName,info.versionName,code)
   }
  }
 }
}

class AapsPayloadValidator {
 fun isValid(values:Map<String,Any?>,receivedAtEpochMs:Long):Boolean {
  val glucose=values.number("glucoseMgdl")?:return false
  val timestamp=values.number("glucoseTimeStamp")?.toLong()?:return false
  return glucose in 20.0..1000.0&&timestamp>0&&timestamp<=receivedAtEpochMs+FreshnessPolicy.FUTURE_TOLERANCE_MS
 }
 private fun Map<String,Any?>.number(key:String):Double?=get(key)?.let{when(it){is Number->it.toDouble();is String->it.toDoubleOrNull();else->null}}?.takeIf{it.isFinite()}
}

interface AapsVersionAdapter { fun parse(values:Map<String,Any?>,receivedAtEpochMs:Long):TherapyDisplayState? }
class AapsDevAdapter: AapsVersionAdapter { override fun parse(values:Map<String,Any?>,receivedAtEpochMs:Long)=AapsPayloadAdapter.parse(values,receivedAtEpochMs) }
class AapsStableAdapter: AapsVersionAdapter { override fun parse(values:Map<String,Any?>,receivedAtEpochMs:Long)=AapsPayloadAdapter.parse(values,receivedAtEpochMs) }

class AndroidApsDataSource(
 private val detector:AapsCapabilityDetector=AapsCapabilityDetector(),
 private val validator:AapsPayloadValidator=AapsPayloadValidator(),
 private val dev:AapsVersionAdapter=AapsDevAdapter(),
 private val stable:AapsVersionAdapter=AapsStableAdapter()
):HealthDataSource {
 private val current=AtomicReference<TherapyDisplayState?>()
 fun accept(values:Map<String,Any?>,receivedAtEpochMs:Long):TherapyDisplayState? { if(!validator.isValid(values,receivedAtEpochMs))return null; val parsed=when(detector.detect(values)){AapsContract.DEV_EXTENDED_STATUS_V1->dev.parse(values,receivedAtEpochMs);AapsContract.STABLE_LEGACY_STATUS->stable.parse(values,receivedAtEpochMs);AapsContract.UNSUPPORTED->null}; if(parsed!=null)current.set(parsed);return parsed }
 override fun latest():TherapyDisplayState?=current.get()
}
