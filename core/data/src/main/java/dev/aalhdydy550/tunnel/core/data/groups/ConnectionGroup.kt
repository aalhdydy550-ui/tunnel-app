package dev.aalhdydy550.tunnel.core.data.groups

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * كيف تختار المجموعة البروفايل الذي تتصل به (البند 6ب).
 *
 * الافتراضي [FASTEST] — العشوائي لا يُقترح افتراضيًا أبدًا (قد يختار
 * خادمًا ميتًا؛ يُخفَّف بتفضيل النجاح الحديث + التجاوز المحدود،
 * انظر [GroupAutoSelect]).
 */
@Serializable
enum class GroupMode {
    FASTEST,
    RANDOM,
    ROUND_ROBIN;

    /** التسمية العربية المعروضة في الواجهة. */
    val arabicLabel: String
        get() = when (this) {
            FASTEST -> "الأسرع"
            RANDOM -> "عشوائي"
            ROUND_ROBIN -> "تناوبي"
        }
}

/**
 * مجموعة اتصال: بروفايلات يختارها المستخدم يدويًا + وضع اختيار
 * تلقائي واحد + ذاكرة آخر نجاح وآخر سرعة لكل بروفايل.
 *
 * تُخزَّن كمصفوفة JSON واحدة في DataStore (انظر [GroupStore]) — بلا
 * ترحيل Room. كل الحقول لها قيم افتراضية، فملف قديم بلا الحقول
 * الجديدة يُفكّ بأمان (انظر `decodeGroups`).
 *
 * حقل `activeGroupId` **ليس** هنا — إنه مفتاح مستقل في المخزن
 * ([GroupStore.activeGroupId])، لأن المجموعة النشطة اختيار عام
 * لا صفة من صفات المجموعة نفسها.
 *
 * كوتلن خالصة (لا اعتماد أندرويد) — قابلة لاختبار JVM مباشرة.
 */
@Serializable
data class ConnectionGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** بروفايلات المجموعة، بالترتيب الذي أضافها به المستخدم. */
    val profileIds: List<Long> = emptyList(),
    val mode: GroupMode = GroupMode.FASTEST,
    /** آخر بروفايل نجح الاتصال به من هذه المجموعة، أو null إن لم ينجح أحد بعد. */
    val lastGoodProfileId: Long? = null,
    /** آخر سرعة مقيسة (ميجابت/ث) لكل بروفايل، بالمفتاح `profileId`. */
    val lastSpeedByProfile: Map<Long, Double> = emptyMap(),
)
