package app.aapswear.datasource
import app.aapswear.model.TherapyDisplayState

fun interface HealthDataSource {
    fun latest(): TherapyDisplayState?
}
