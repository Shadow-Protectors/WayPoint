package com.shadowprotectors.alarmapp

import com.shadowprotectors.alarmapp.engine.*
import org.junit.Assert.*
import org.junit.Test

class MotionEtaTest {
    @Test fun etaUsesMeasuredSpeedWithoutModeFloor() {
        assertEquals(75, DistanceEngine.estimateEtaMinutes(50.0, 40.0, TripMode.BUS_CAR))
        assertEquals(50, DistanceEngine.estimateEtaMinutes(50.0, 60.0, TripMode.BUS_CAR))
        assertEquals(150, DistanceEngine.estimateEtaMinutes(50.0, 20.0, TripMode.BUS_CAR))
        assertEquals(150, DistanceEngine.estimateEtaMinutes(50.0, 20.0, TripMode.TRAIN))
        assertNull(DistanceEngine.estimateEtaMinutes(50.0, 0.0, TripMode.BUS_CAR))
        assertNull(DistanceEngine.estimateEtaMinutes(Double.NaN, 40.0, TripMode.BUS_CAR))
        assertNull(DistanceEngine.estimateEtaMinutes(50.0, Double.NaN, TripMode.TRAIN))
    }

    @Test fun smoothingAdaptsToMotionAndStopsWithoutInventingSpeed() {
        val estimator = MotionSpeedEstimator()
        assertEquals(40.0, estimator.update(0, 40.0)!!, 0.001)
        val accelerated = estimator.update(20_000, 60.0)!!
        assertTrue(accelerated > 50 && accelerated < 60)
        assertEquals(accelerated, estimator.update(10_000, 0.0)!!, 0.001)
        for (time in 40_000L..100_000L step 20_000) estimator.update(time, 0.0)
        assertNull(DistanceEngine.estimateEtaMinutes(50.0, estimator.update(120_000, 0.0)!!, TripMode.BUS_CAR))
        assertNull(estimator.update(125_000, null))
        assertEquals(80.0, estimator.update(130_000, 80.0)!!, 0.001)
        estimator.reset()
        assertEquals(20.0, estimator.update(140_000, 20.0)!!, 0.001)
    }
}
