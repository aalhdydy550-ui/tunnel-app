package dev.aalhdydy550.tunnel.feature.connection.injector

import java.util.Locale

/**
 * Egyptian mobile carrier.
 *
 * [UNKNOWN] is only for SIM detection ([CarrierDetector]): it means no
 * SIM could be read, so the user must pick a carrier by hand. Reference
 * networks that match no known carrier classify as [OTHER], never
 * [UNKNOWN] — see [CarrierClassifier].
 */
enum class Carrier(val arabicName: String) {
    WE("وي"),
    VODAFONE("فودافون"),
    ETISALAT("اتصالات"),
    ORANGE("اورنج"),
    OTHER("أخرى"),
    UNKNOWN("غير معروفة"),
}

/**
 * Detects the SIM carrier from the MCC/MNC code.
 *
 * Pure function, no Android dependency, unit-tested. Egypt is MCC 602;
 * MNC 01 = Orange, 02 = Vodafone, 03 = Etisalat, 04 = WE (Telecom
 * Egypt). Anything else — non-Egyptian code, empty, null, too short —
 * is [Carrier.UNKNOWN].
 */
object CarrierDetector {

    fun fromMccMnc(mccMnc: String?): Carrier {
        val code = mccMnc?.trim().orEmpty()
        if (code.length < 5) return Carrier.UNKNOWN
        if (!code.startsWith("602")) return Carrier.UNKNOWN
        return when (code.substring(3)) {
            "01" -> Carrier.ORANGE
            "02" -> Carrier.VODAFONE
            "03" -> Carrier.ETISALAT
            "04" -> Carrier.WE
            else -> Carrier.UNKNOWN
        }
    }
}

/**
 * Classifies a reference network row into a carrier.
 *
 * The `carrierInfo` field always starts with the carrier prefix followed
 * by " — " (for example "WE — استهلاك لحد10جيجا كل شهر"), so the prefix
 * before the first "—" (falling back to "-") is read first. Only when
 * the prefix is missing or unrecognised do the words in the network
 * name decide. A row that matches nothing is [Carrier.OTHER].
 */
object CarrierClassifier {

    fun fromNetwork(name: String, carrierInfo: String): Carrier {
        carrierFromPrefix(carrierInfo)?.let { return it }
        return carrierFromName(name)
    }

    private fun carrierFromPrefix(carrierInfo: String): Carrier? {
        val prefix = carrierInfo.split("—", "-").firstOrNull()?.trim().orEmpty()
        if (prefix.isEmpty()) return null
        return when (prefix.lowercase(Locale.US)) {
            "we" -> Carrier.WE
            "vodafone", "فودافون" -> Carrier.VODAFONE
            "etisalat", "اتصالات" -> Carrier.ETISALAT
            "orange", "اورنج", "أورنج" -> Carrier.ORANGE
            "أخرى", "اخرى", "other" -> Carrier.OTHER
            else -> null
        }
    }

    private fun carrierFromName(name: String): Carrier {
        val lower = name.lowercase(Locale.US)
        return when {
            "vodafone" in lower || "فودافون" in lower -> Carrier.VODAFONE
            "etisalat" in lower || "اتصالات" in lower -> Carrier.ETISALAT
            "orange" in lower || "اورنج" in lower || "أورنج" in lower -> Carrier.ORANGE
            // "we" only as a standalone token (or the Arabic "وي" as a
            // standalone word, or "wifi"): a bare substring would match
            // inside unrelated words.
            "wifi" in lower || hasStandaloneWe(lower) -> Carrier.WE
            else -> Carrier.OTHER
        }
    }

    private fun hasStandaloneWe(lower: String): Boolean {
        if (lower.split(Regex("[^a-z0-9]+")).any { it == "we" }) return true
        // Arabic "وي" as its own word: preceded/followed by a
        // non-letter (or a string edge).
        val index = lower.indexOf("وي")
        if (index < 0) return false
        val beforeOk = index == 0 || !lower[index - 1].isLetter()
        val afterIndex = index + 2
        val afterOk = afterIndex >= lower.length || !lower[afterIndex].isLetter()
        return beforeOk && afterOk
    }
}
