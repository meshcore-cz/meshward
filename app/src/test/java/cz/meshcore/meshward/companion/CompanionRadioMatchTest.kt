package cz.meshcore.meshward.companion

import cz.meshcore.meshward.data.MeshNetwork
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for AUTO-mode radio verification (network ↔ device param matching). */
class CompanionRadioMatchTest {

    private fun self(freqHz: Long, bwHz: Long, sf: Int, cr: Int) = CompanionMessage.SelfInfo(
        txPower = 22, maxTxPower = 22, publicKey = "", latitude = 0.0, longitude = 0.0,
        radioFreqHz = freqHz, radioBwHz = bwHz, radioSf = sf, radioCr = cr, name = "dev",
    )

    private fun net(freqMhz: Double, bwKhz: Double, sf: Int, cr: Int) =
        MeshNetwork(code = "CZ", name = "Czechia", freqMhz = freqMhz, bandwidthKhz = bwKhz, spreadingFactor = sf, codingRate = cr)

    @Test fun matchingDevicePasses() {
        val n = net(869.525, 250.0, 11, 5)
        assertTrue(n.hasRadioParams())
        assertTrue(n.matchesDevice(self(869_525_000, 250_000, 11, 5)))
    }

    @Test fun mismatchedSpreadingFactorFails() {
        assertFalse(net(869.525, 250.0, 11, 5).matchesDevice(self(869_525_000, 250_000, 7, 5)))
    }

    @Test fun mismatchedFrequencyFails() {
        // 869.432 vs 869.525 MHz — far beyond the 2 kHz tolerance.
        assertFalse(net(869.525, 250.0, 11, 5).matchesDevice(self(869_432_000, 250_000, 11, 5)))
    }

    @Test fun frequencyWithinToleranceMatches() {
        // 1 kHz off is within tolerance.
        assertTrue(net(869.525, 250.0, 11, 5).matchesDevice(self(869_526_000, 250_000, 11, 5)))
    }

    @Test fun networkWithoutParamsHasNoRadioParams() {
        assertFalse(MeshNetwork(code = "X", name = "x").hasRadioParams())
    }
}
