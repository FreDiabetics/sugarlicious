package app.aapswear.wear

import android.app.Application
import app.aapswear.complications.ComplicationGlucoseColorSource
import app.aapswear.model.CgmRangeClass

/** Supplies complication color hints from the same active profile as the Wear overview. */
class SugarliciousWearApplication :
    Application(),
    ComplicationGlucoseColorSource {
    override fun outOfRangeGlucoseColor(valueMgDl: Double): Int? {
        val preferences = WearDisplayPreferences.read(this)
        return when (preferences.cgmThresholds.classify(valueMgDl)) {
            CgmRangeClass.VERY_LOW -> preferences.uiColors.glucoseVeryLow
            CgmRangeClass.LOW -> preferences.uiColors.glucoseLow
            CgmRangeClass.HIGH -> preferences.uiColors.glucoseHigh
            CgmRangeClass.VERY_HIGH -> preferences.uiColors.glucoseVeryHigh
            else -> null
        }
    }
}
