package dev.aalhdydy550.tunnel.feature.connection.injector

import org.junit.Assert.assertEquals
import org.junit.Test

class CarrierClassifierTest {

    @Test
    fun `WE prefix wins`() {
        assertEquals(
            Carrier.WE,
            CarrierClassifier.fromNetwork("anything", "WE — استهلاك لحد10جيجا كل شهر"),
        )
    }

    @Test
    fun `Etisalat prefix wins`() {
        assertEquals(
            Carrier.ETISALAT,
            CarrierClassifier.fromNetwork("anything", "Etisalat — نت مجاني كل يوم هدية فري"),
        )
    }

    @Test
    fun `Vodafone prefix wins`() {
        assertEquals(
            Carrier.VODAFONE,
            CarrierClassifier.fromNetwork("anything", "Vodafone — فودافون700ميجا مرحله اولى"),
        )
    }

    @Test
    fun `Orange prefix wins`() {
        assertEquals(
            Carrier.ORANGE,
            CarrierClassifier.fromNetwork("anything", "Orange — عرض اورنج"),
        )
    }

    @Test
    fun `other prefix maps to OTHER`() {
        assertEquals(
            Carrier.OTHER,
            CarrierClassifier.fromNetwork("anything", "أخرى — عرض عام"),
        )
    }

    @Test
    fun `unknown prefix falls back to name words`() {
        assertEquals(
            Carrier.VODAFONE,
            CarrierClassifier.fromNetwork(
                "فودافون 700 ميجا",
                "غير معروف — عرض",
            ),
        )
        assertEquals(
            Carrier.ETISALAT,
            CarrierClassifier.fromNetwork(
                "رفع سرعه رواتر اتصالات{Etisalat}",
                "",
            ),
        )
    }

    @Test
    fun `name fallback finds standalone WE`() {
        assertEquals(
            Carrier.WE,
            CarrierClassifier.fromNetwork(
                "Wifi |رفع سرعه رواتر خلصان وي1️⃣",
                "",
            ),
        )
    }

    @Test
    fun `fully unknown row is OTHER`() {
        assertEquals(
            Carrier.OTHER,
            CarrierClassifier.fromNetwork("PREMIUM SERVER {01}", ""),
        )
        assertEquals(
            Carrier.OTHER,
            CarrierClassifier.fromNetwork("ServerGold {1}🟢", "—"),
        )
    }
}
