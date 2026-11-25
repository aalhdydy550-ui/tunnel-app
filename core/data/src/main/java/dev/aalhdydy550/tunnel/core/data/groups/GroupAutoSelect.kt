package dev.aalhdydy550.tunnel.core.data.groups

import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.net.LatencyTester
import dev.aalhdydy550.tunnel.core.data.net.ProtocolSpeed
import kotlin.random.Random

/**
 * الاختيار التلقائي داخل مجموعة اتصال (البند 6ب).
 *
 * كوتلن خالصة بلا أي اعتماد أندرويد — تُختبر على JVM مباشرة.
 *
 * المدخلات كلها حقائق مقيسة مسبقًا: `latencyMs` من `LatencyTester`
 * (بروفايل بلا إدخال = لم يُجب، أي UDP-only أو ميت)، و`lastSpeedMbps`
 * آخر سرعة مقيسة لكل بروفايل. لا قياس هنا ولا شبكة — القرار فقط.
 *
 * `lastSpeedByProfile` المخزنة في المجموعة نفسها تُستخدم كمصدر
 * ثانٍ للسرعة عندما يغيب البروفايل عن خريطة `lastSpeedMbps`
 * الممرَّرة (مثلًا عند استدعاء المنطق من مسار بلا خريطة سرعة حية).
 */
object GroupAutoSelect {

    /**
     * حد محاولات التجاوز التلقائي عند فشل الاتصال: محاولتان كحد
     * أقصى بعد الفشل الأول، ثم يتوقف الاتصال برسالة واضحة — بلا
     * حلقة لانهائية وبلا استنزاف بطارية.
     */
    const val MAX_FAILOVERS: Int = 2

    /**
     * يختار بروفايلًا من المجموعة حسب `group.mode`:
     *
     * - [GroupMode.FASTEST]: نفس ترتيب [ProtocolSpeed.pickFastest] —
     *   الأقل latency أولًا (ومن بلا latency يُعامَل كالأبطأ لا
     *   كالأول: يُستبعد من المقارنة ويبقى بديلًا أخيرًا فقط إن لم
     *   يجب أحد)؛ وعند التعادل يفوز البروتوكول الأسرع.
     * - [GroupMode.RANDOM]: عشوائي **يفضّل ذوي النجاح الحديث** —
     *   وزن أعلى لآخر بروفايل ناجح (`lastGoodProfileId`) ولمن له
     *   سرعة أخيرة مقيسة؛ لا عشوائية عمياء على خادم ميت.
     * - [GroupMode.ROUND_ROBIN]: التالي دوريًا بعد `lastGoodProfileId`
     *   في ترتيب `profileIds` (وإن كان null أو خارج المجموعة يبدأ
     *   من الأول)؛ ويلتفّ من الأخير إلى الأول.
     *
     * مجموعة فارغة أو بلا بروفايلات مطابقة في `profiles` ← `null`
     * بأمان. الترتيب المعتمد هو ترتيب `group.profileIds` (ترتيب
     * إضافة المستخدم)، لا ترتيب قائمة `profiles` الممرَّرة.
     */
    fun pick(
        group: ConnectionGroup,
        profiles: List<Profile>,
        latencyMs: Map<Long, Long>,
        lastSpeedMbps: Map<Long, Double>,
        random: Random = Random.Default,
    ): Profile? {
        val candidates = orderedMembers(group, profiles)
        if (candidates.isEmpty()) return null
        return when (group.mode) {
            GroupMode.FASTEST -> pickFastest(candidates, latencyMs)
            GroupMode.RANDOM -> pickWeightedRandom(candidates, group, lastSpeedMbps, random)
            GroupMode.ROUND_ROBIN -> pickNextRoundRobin(group, candidates)
        }
    }

    /**
     * قائمة بدائل مرتبة بنفس منطق [pick] بعد استبعاد `failedIds`،
     * مقطوعة عند [MAX_FAILOVERS] (محاولتان كحد أقصى).
     *
     * الترتيب: الأسرع — الأقل latency ثم البروتوكول الأسرع ثم
     * السرعة الأخيرة الأعلى؛ العشوائي — الوزن الأعلى أولًا
     * (النجاح الحديث أولًا)؛ التناوبي — التالي دوريًا بعد
     * `lastGoodProfileId` مع الالتفاف.
     */
    fun failoverCandidates(
        group: ConnectionGroup,
        profiles: List<Profile>,
        failedIds: Set<Long>,
        latencyMs: Map<Long, Long>,
        lastSpeedMbps: Map<Long, Double>,
        random: Random = Random.Default,
    ): List<Profile> {
        val candidates = orderedMembers(group, profiles)
            .filterNot { it.id in failedIds }
        if (candidates.isEmpty()) return emptyList()
        val ordered = when (group.mode) {
            GroupMode.FASTEST -> candidates.sortedWith(fastestComparator(latencyMs, lastSpeedMbps, group))
            GroupMode.RANDOM -> candidates.sortedByDescending { weight(it, group, lastSpeedMbps) }
            GroupMode.ROUND_ROBIN -> rotateAfterLastGood(group, candidates)
        }
        // `random` محفوظ في التوقيع لتوحيده مع [pick]؛ ترتيب البدائل
        // حتمي عمدًا (الأفضل أولًا) فلا عشوائية هنا.
        return ordered.take(MAX_FAILOVERS)
    }

    /** أعضاء المجموعة الموجودون فعلًا، بترتيب `profileIds`، بلا تكرار. */
    private fun orderedMembers(group: ConnectionGroup, profiles: List<Profile>): List<Profile> {
        val byId = profiles.associateBy { it.id }
        return group.profileIds.distinct().mapNotNull { byId[it] }
    }

    private fun pickFastest(
        candidates: List<Profile>,
        latencyMs: Map<Long, Long>,
    ): Profile? {
        val results = candidates.map { LatencyTester.LatencyResult(it, latencyMs[it.id]) }
        // `pickFastest` نفسه: الأقل latency، ثم رتبة البروتوكول،
        // وإن لم يجب أحد يعود إلى الأول بدل `null` — فالقائمة هنا
        // غير فارغة حتمًا، والنتيجة لا تكون `null` أبدًا.
        return ProtocolSpeed.pickFastest(results, fallback = candidates.first())
    }

    private fun fastestComparator(
        latencyMs: Map<Long, Long>,
        lastSpeedMbps: Map<Long, Double>,
        group: ConnectionGroup,
    ): Comparator<Profile> = compareBy(
        { latencyMs[it.id] ?: Long.MAX_VALUE },
        { ProtocolSpeed.speedRank(it.type) },
        { -(lastSpeedMbps[it.id] ?: group.lastSpeedByProfile[it.id] ?: 0.0) },
    )

    /**
     * وزن العشوائي: 1 للجميع + 3 لآخر بروفايل ناجح + (1 + حتى 2)
     * لمن له سرعة أخيرة مقيسة (تزيد مع السرعة، بسقف). الخادم
     * الناجح حديثًا (~6) يُختار غالبًا لا دائمًا — ما زال هناك
     * مجال لاكتشاف غيره؛ والميت بلا سرعة (وزن 1) نادر لا مستحيل.
     */
    private fun weight(
        profile: Profile,
        group: ConnectionGroup,
        lastSpeedMbps: Map<Long, Double>,
    ): Double {
        var w = 1.0
        if (profile.id == group.lastGoodProfileId) w += 3.0
        val speed = lastSpeedMbps[profile.id] ?: group.lastSpeedByProfile[profile.id]
        if (speed != null) w += 1.0 + minOf(speed / 10.0, 2.0)
        return w
    }

    private fun pickWeightedRandom(
        candidates: List<Profile>,
        group: ConnectionGroup,
        lastSpeedMbps: Map<Long, Double>,
        random: Random,
    ): Profile {
        val weights = candidates.map { weight(it, group, lastSpeedMbps) }
        val total = weights.sum()
        var roll = random.nextDouble(total)
        for ((index, profile) in candidates.withIndex()) {
            roll -= weights[index]
            if (roll <= 0.0) return profile
        }
        return candidates.last()
    }

    private fun pickNextRoundRobin(group: ConnectionGroup, candidates: List<Profile>): Profile {
        return rotateAfterLastGood(group, candidates).first()
    }

    /** المجموعة مدوَّرة لتبدأ بعد `lastGoodProfileId` (مع الالتفاف). */
    private fun rotateAfterLastGood(
        group: ConnectionGroup,
        candidates: List<Profile>,
    ): List<Profile> {
        val lastIndex = candidates.indexOfFirst { it.id == group.lastGoodProfileId }
        if (lastIndex < 0) return candidates
        val next = (lastIndex + 1) % candidates.size
        if (next == 0) return candidates
        return candidates.subList(next, candidates.size) + candidates.subList(0, next)
    }
}
