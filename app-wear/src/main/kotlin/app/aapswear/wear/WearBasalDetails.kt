package app.aapswear.wear

import app.aapswear.model.BasalDetailPresentation
import app.aapswear.model.BasalState
import app.aapswear.model.basalDetailPresentation
import java.util.Locale

internal typealias WearBasalDetails = BasalDetailPresentation

internal fun wearBasalDetails(basal: BasalState?, now: Long, locale: Locale = Locale.getDefault()): WearBasalDetails =
    basalDetailPresentation(basal, now, locale)
