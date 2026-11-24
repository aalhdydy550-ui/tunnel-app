package dev.aalhdydy550.tunnel.feature.connection.injector

import org.junit.Assert.assertEquals
import org.junit.Test

class CarrierDetectorTest {

    @Test
    fun `60201 is Orange`() {
        assertEquals(Carrier.ORANGE, CarrierDetector.fromMccMnc("60201"))
    }

    @Test
    fun `60202 is Vodafone`() {
        assertEquals(Carrier.VODAFONE, CarrierDetector.fromMccMnc("60202"))
    }

    @Test
    fun `60203 is Etisalat`() {
        assertEquals(Carrier.ETISALAT, CarrierDetector.fromMccMnc("60203"))
    }

    @Test
    fun `60204 is WE`() {
        assertEquals(Carrier.WE, CarrierDetector.fromMccMnc("60204"))
    }

    @Test
    fun `unknown Egyptian MNC is UNKNOWN`() {
        assertEquals(Carrier.UNKNOWN, CarrierDetector.fromMccMnc("60205"))
        assertEquals(Carrier.UNKNOWN, CarrierDetector.fromMccMnc("60200"))
    }

    @Test
    fun `non-Egyptian MCC is UNKNOWN`() {
        assertEquals(Carrier.UNKNOWN, CarrierDetector.fromMccMnc("310260"))
        assertEquals(Carrier.UNKNOWN, CarrierDetector.fromMccMnc("26201"))
    }

    @Test
    fun `blank null and too-short codes are UNKNOWN`() {
        assertEquals(Carrier.UNKNOWN, CarrierDetector.fromMccMnc(null))
        assertEquals(Carrier.UNKNOWN, CarrierDetector.fromMccMnc(""))
        assertEquals(Carrier.UNKNOWN, CarrierDetector.fromMccMnc("   "))
        assertEquals(Carrier.UNKNOWN, CarrierDetector.fromMccMnc("602"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(Carrier.VODAFONE, CarrierDetector.fromMccMnc(" 60202 "))
    }
}
