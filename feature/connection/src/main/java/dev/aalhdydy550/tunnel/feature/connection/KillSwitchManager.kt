package dev.aalhdydy550.tunnel.feature.connection

/**
 * حالة مفتاح القتل داخل التطبيق (البند 2).
 *
 * - [OFF]: المفتاح مغلق، أو النفق متوقف — الـTUN يُغلق كالمعتاد.
 * - [ARMED]: المفتاح مفعّل والنفق يعمل — جاهز للحظر عند أول سقوط.
 * - [BLOCKING]: المفتاح مفعّل والنفق ساقط — الـTUN يبقى مفتوحًا بلا
 *   محرك، فالترافيك الموجَّه إليه لا يجد طريقًا (محظور فعليًا) إلى أن
 *   يعود المحرك أو يوقف المستخدم النفق يدويًا.
 */
enum class KillSwitchState {
    OFF,
    ARMED,
    BLOCKING,
}

/**
 * آلة حالات مفتاح القتل — كوتلن خالصة بلا أي اعتماد أندرويد، قابلة
 * لاختبار JVM مباشرة. لا تقرأ الإعداد المخزّن بنفسها: المتصل (الخدمة)
 * يمرّر اختيار المستخدم عبر [setEnabled] ويُبلغها بأحداث المحرك.
 *
 * جدول الانتقالات:
 *
 * | الحدث | من OFF | من ARMED | من BLOCKING |
 * |---|---|---|---|
 * | `setEnabled(true)` | ARMED | ARMED | BLOCKING (يبقى محظورًا — المحرك ما زال ساقطًا) |
 * | `setEnabled(false)` | OFF | OFF | OFF |
 * | `onEngineStarted()` (مفعّل) | ARMED | ARMED | ARMED |
 * | `onEngineStarted()` (مغلق) | OFF | OFF | OFF |
 * | `onEngineFailed()` (مفعّل) | OFF | BLOCKING | BLOCKING |
 * | `onEngineFailed()` (مغلق) | OFF | OFF | OFF |
 * | `onStopped()` | OFF | OFF | OFF |
 *
 * حالتان موثقتان بلا غموض:
 * - `onEngineFailed()` والمفتاح مفعّل لكن الحالة OFF (أي بعد
 *   [onStopped] مباشرة) يبقى OFF: إيقاف المستخدم قرار نهائي، وحدث
 *   فشل متأخر يجب ألا يعيد الحظر بعد أن أُغلق الـTUN.
 * - `setEnabled(true)` من BLOCKING يبقى BLOCKING: إعادة تأكيد التفعيل
 *   لا تعني أن المحرك عاد؛ وحدها [onEngineStarted] تنقل إلى ARMED.
 *
 * **الحد الصادق:** هذا المفتاح **لا ينجو من موت العملية** — إن قتل
 * النظام العملية كلها مات الـTUN معها، فعاد الترافيك مباشرة. الغطاء
 * الوحيد الباقي بعد موت العملية هو Always-on في النظام (مع «حظر
 * الاتصالات بدون VPN»). المفتاح الداخلي يغطي: سقوط المحرك، وفشل
 * إعادة الاتصال، وفقدان الشبكة أثناء إعادة المحاولة.
 *
 * **لا يعمل في وضع البروكسي إطلاقًا:** بلا TUN لا يوجد ما يُبقيه
 * مفتوحًا — المتصل يعطّل المفتاح في الواجهة عند اختيار وضع البروكسي.
 */
class KillSwitchManager {

    /** الحالة الحالية. */
    var state: KillSwitchState = KillSwitchState.OFF
        private set

    /**
     * هل يُبقي المتصل الـTUN مفتوحًا عند إيقاف المحرك — صحيح فقط في
     * [KillSwitchState.BLOCKING]. يقرؤه `stopEngineSilently(keepTun)`
     * في الخدمة.
     */
    val shouldKeepTun: Boolean get() = state == KillSwitchState.BLOCKING

    /** مرادف مقروء لـ [shouldKeepTun] لعرض حالة «محظور» في الواجهة. */
    val isBlocking: Boolean get() = state == KillSwitchState.BLOCKING

    /** اختيار المستخدم الحالي (يُقرأ من [KillSwitchStore] عند الاتصال). */
    private var enabled: Boolean = false

    /**
     * يطبّق اختيار المستخدم الجديد. التفعيل من OFF يسلّح المفتاح
     * (ARMED)؛ وإلغاء التفعيل يطفئه من أي حالة فورًا — حتى من BLOCKING،
     * فلا يُحبس المستخدم خارج الإنترنت.
     */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        state = when {
            !enabled -> KillSwitchState.OFF
            state == KillSwitchState.OFF -> KillSwitchState.ARMED
            else -> state
        }
    }

    /**
     * المحرك بدأ (أو نجحت إعادة الاتصال). والمفتاح مفعّل ← ARMED من
     * أي حالة (حتى من BLOCKING)؛ والمفتاح مغلق ← OFF.
     */
    fun onEngineStarted() {
        state = if (enabled) KillSwitchState.ARMED else KillSwitchState.OFF
    }

    /**
     * المحرك سقط سقوطًا غير مقصود. والمفتاح مفعّل ← BLOCKING (من ARMED
     * أو من BLOCKING نفسها)؛ والمفتاح مغلق ← OFF. ومن OFF يبقى OFF حتى
     * والمفتاح مفعّل — انظر جدول الانتقالات أعلاه.
     */
    fun onEngineFailed() {
        state = when {
            !enabled -> KillSwitchState.OFF
            state == KillSwitchState.OFF -> KillSwitchState.OFF
            else -> KillSwitchState.BLOCKING
        }
    }

    /**
     * توقّف المحرك توقفًا مقصودًا: إيقاف المستخدم، أو إلغاء إذن VPN،
     * أو تدمير الخدمة، أو طلب النواة. يطفئ المفتاح من أي حالة — الـTUN
     * يُغلق دائمًا هنا، فلا حظر بعد إيقاف مقصود.
     */
    fun onStopped() {
        state = KillSwitchState.OFF
    }
}
