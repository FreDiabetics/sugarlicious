package app.aapswear.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.aapswear.model.DataCapability
import app.aapswear.model.GlucosePrediction
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.PredictionKind
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TherapyStateStoreTest {
    @Test
    fun `last state survives repository recreation`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val expected =
                TherapyDisplayState(
                    receivedAtEpochMs = 1_234_567L,
                    sourceVersion = "3.4.0-dev",
                    sourceContract = "AAPS_EXTENDED_STATUS_V1",
                    glucose =
                        GlucoseState(
                            valueMgDl = 142.0,
                            displayUnit = GlucoseUnit.MG_DL,
                            trend = Trend.FORTY_FIVE_UP,
                            measuredAtEpochMs = 1_200_000L,
                            deltaMgDl = 4.0,
                            averageDeltaMgDl = 3.0,
                        ),
                    glucosePredictions =
                        listOf(
                            GlucosePrediction(
                                PredictionKind.IOB,
                                listOf(GlucoseSample(150.0, 1_500_000L)),
                            ),
                        ),
                    capabilities =
                        setOf(
                            DataCapability.GLUCOSE,
                            DataCapability.TREND,
                            DataCapability.PREDICTIONS,
                        ),
                )

            TherapyStateStore(context).save(expected)
            val restored = TherapyStateStore(context).state.first()

            assertNotNull(restored)
            assertEquals(expected, restored)
        }
}
