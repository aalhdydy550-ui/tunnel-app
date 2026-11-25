package dev.aalhdydy550.tunnel.core.data.model

import kotlinx.serialization.Serializable

/**
 * نتيجة اختبار سرعة تنزيل واحد (البند 6أ).
 *
 * بلا `profileId` هنا عمدًا: المخزن ([SpeedTestStore][dev.aalhdydy550.tunnel.core.data.repository.SpeedTestStore])
 * هو الذي يربط النتيجة بالبروفايل الذي اختُبر عليه، فتبقى النتيجة
 * نفسها قابلة لإعادة الاستخدام والمقارنة.
 *
 * **الرفع مؤجَّل للإصدار 2** (الخطة §6أ): لا حقل رفع هنا — عند
 * تنفيذه يُضاف حقل `uploadMbps` اختياريًا مع `ignoreUnknownKeys`
 * في المخزن فيقرأ النتائج القديمة بلا ترحيل.
 *
 * @param downloadMbps سرعة التنزيل المقاسة بالميجابت/ثانية.
 * @param bytesDownloaded البايتات المقروءة فعلًا أثناء الاختبار
 *   (قد تختلف عن الحجم المطلوب إن توقف التنزيل مبكرًا).
 * @param durationMs زمن القياس بالمللي ثانية (من أول بايت إلى آخر بايت).
 * @param testedAt وقت الاختبار بـ`System.currentTimeMillis()`.
 */
@Serializable
data class SpeedTestResult(
    val downloadMbps: Double,
    val bytesDownloaded: Long,
    val durationMs: Long,
    val testedAt: Long,
)
