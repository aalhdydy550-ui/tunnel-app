package dev.aalhdydy550.tunnel.feature.connection

import dev.aalhdydy550.tunnel.core.data.model.SpeedTestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * اختبار سرعة تنزيل حقيقي (throughput) عبر النفق (البند 6أ).
 *
 * يقيس سرعة التنزيل بتنزيل بايتات فعلية من نقطة اختبار علنية
 * مستقرة بلا مفتاح — `https://speed.cloudflare.com/__down?bytes=N`
 * (خدمة اختبار سرعة علنية من Cloudflare، بلا مصادقة وبلا بيانات
 * دخول) — لا زمن استجابة ولا عداد ترافيك جارٍ.
 *
 * القواعد:
 * - يعمل **فقط بضغطة المستخدم** (أبدًا تلقائيًا — يستهلك بيانات)،
 *   و**فقط أثناء اتصال نفق قائم** ([runDownload] يرفض البدء بلا
 *   اتصال)؛ والمتصل هو من يعرض حجم البيانات ويطلب التأكيد قبل البدء.
 * - حجم تكيفي: يبدأ 5MB ويكبر إن كان سريعًا (حد أقصى 25MB للجولة
 *   الواحدة)، وسقف 10 ثوانٍ للقياس كله.
 * - فشل الشبكة يُرمى [IOException] — **لا رقم وهمي أبدًا**.
 * - يدعم الإلغاء عبر الكوروتين: عند الإلغاء تُلغى مكالمة OkHttp
 *   فيُغلق الـresponse ولا يبقى التنزيل معلقًا.
 *
 * **الرفع مؤجَّل للإصدار 2** (الخطة §6أ): لا رفع هنا إطلاقًا —
 * عند تنفيذه يُقاس بـ`__up` ويُضاف حقل `uploadMbps` إلى
 * [SpeedTestResult] (اختياريًا ليقرأ المخزن النتائج القديمة).
 *
 * ملاحظة للمتصل: [onProgress] يُستدعى من خيط IO لا الخيط الرئيسي —
 * انقل القيم إلى حالة الواجهة قبل عرضها.
 */
class SpeedTest(
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = "https://speed.cloudflare.com",
) {

    private val endpointBase = baseUrl.trimEnd('/')

    /**
     * يقيس سرعة التنزيل عبر النفق.
     *
     * @param isTunnelConnected يجب أن يكون `true` — نفق قائم فعلًا.
     *   `false` يرمي [IllegalStateException] قبل أي طلب شبكة.
     * @param onProgress يُستدعى بعد كل مقطع مقروء بـ(البايتات الكلية،
     *   الزمن المنقضي بالمللي ثانية، السرعة الحالية بالميجابت/ثانية).
     * @return نتيجة القياس (بايتات فعلية مقروءة / زمن فعلي).
     * @throws IllegalStateException إن لم يكن هناك اتصال نفق قائم.
     * @throws IOException عند أي فشل شبكة (بلا اتصال، HTTP غير 200،
     *   جسم فارغ) — لا رقم وهمي.
     * @throws CancellationException عند إلغاء الكوروتين أو تجاوز
     *   سقف الـ10 ثوانٍ.
     */
    suspend fun runDownload(
        isTunnelConnected: Boolean,
        onProgress: (downloadedBytes: Long, elapsedMs: Long, mbps: Double) -> Unit,
    ): SpeedTestResult {
        if (!isTunnelConnected) {
            throw IllegalStateException(
                "اختبار السرعة يقيس السرعة عبر النفق فقط — لا يبدأ بلا اتصال قائم",
            )
        }
        return withTimeout(TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                var requestedBytes = INITIAL_BYTES
                var totalBytes = 0L
                val startNs = System.nanoTime()
                while (true) {
                    val roundStartNs = System.nanoTime()
                    val roundBytes = downloadOnce(
                        bytes = requestedBytes,
                        alreadyDownloaded = totalBytes,
                        startNs = startNs,
                        onProgress = onProgress,
                    )
                    totalBytes += roundBytes
                    val roundMs = max(1L, (System.nanoTime() - roundStartNs) / 1_000_000L)
                    val elapsedMs = max(1L, (System.nanoTime() - startNs) / 1_000_000L)
                    // التكيّف: نكبّر الجولة التالية فقط إن كان تقدير زمنها
                    // — من سرعة الجولة الأخيرة — يتسع في المتبقي من
                    // السقف مع هامش أمان؛ وإلا نتوقف بهذه الجولات.
                    val nextBytes = min(requestedBytes * 2, MAX_BYTES)
                    if (nextBytes <= requestedBytes) break
                    val roundMbps = roundBytes * 8.0 / (roundMs / 1_000.0) / 1_000_000.0
                    if (roundMbps <= 0.0) break
                    val estimatedNextMs = nextBytes * 8.0 / roundMbps / 1_000.0
                    val remainingMs = TIMEOUT_MS - elapsedMs
                    if (estimatedNextMs > remainingMs * GROWTH_HEADROOM) break
                    requestedBytes = nextBytes
                }
                val durationMs = max(1L, (System.nanoTime() - startNs) / 1_000_000L)
                SpeedTestResult(
                    downloadMbps = totalBytes * 8.0 / (durationMs / 1_000.0) / 1_000_000.0,
                    bytesDownloaded = totalBytes,
                    durationMs = durationMs,
                    testedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    /**
     * جولة تنزيل واحدة بـ`__down?bytes=N`، تعيد البايتات المقروءة
     * فعلًا (قد تقل عن المطلوب إن ردّ الخادم أقل).
     */
    private suspend fun downloadOnce(
        bytes: Long,
        alreadyDownloaded: Long,
        startNs: Long,
        onProgress: (downloadedBytes: Long, elapsedMs: Long, mbps: Double) -> Unit,
    ): Long {
        val request = Request.Builder()
            .url("$endpointBase/__down?bytes=$bytes")
            .build()
        val call = client.newCall(request)
        // إلغاء المكالمة عند إلغاء الكوروتين حتى لا يبقى التنزيل
        // معلقًا على سوكيت مقروء — فيُغلق الـresponse نظيفًا.
        currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(
                        "اختبار السرعة ردّ HTTP ${response.code} بدل 200",
                    )
                }
                val body = response.body
                    ?: throw IOException("اختبار السرعة ردّ بلا جسم")
                val source = body.source()
                val buffer = ByteArray(BUFFER_SIZE)
                var read: Long = 0
                while (true) {
                    // فحص تعاوني للإلغاء عند كل مقطع.
                    currentCoroutineContext().ensureActive()
                    val n = source.read(buffer)
                    if (n == -1) break
                    read += n
                    val total = alreadyDownloaded + read
                    val elapsedMs = max(1L, (System.nanoTime() - startNs) / 1_000_000L)
                    onProgress(
                        total,
                        elapsedMs,
                        total * 8.0 / (elapsedMs / 1_000.0) / 1_000_000.0,
                    )
                }
                if (read == 0L) {
                    throw IOException("اختبار السرعة ردّ جسمًا فارغًا")
                }
                return read
            }
        } catch (e: IOException) {
            // إن كان الإلغاء هو السبب أعد رميه إلغاءً لا خطأ شبكة.
            currentCoroutineContext().ensureActive()
            throw e
        }
    }

    companion object {
        /** سقف القياس كله: 10 ثوانٍ (مع مهلات OkHttp أدناه). */
        const val TIMEOUT_MS = 10_000L

        /** حجم الجولة الأولى: 5MB. */
        const val INITIAL_BYTES = 5L * 1024L * 1024L

        /** حد الجولة الواحدة: 25MB. */
        const val MAX_BYTES = 25L * 1024L * 1024L

        /** مقطع القراءة الواحد: 8KB. */
        const val BUFFER_SIZE = 8 * 1024

        /**
         * هامش أمان لنمو الجولة: الجولة التالية تُطلب فقط إن كان
         * تقدير زمنها أقل من 80% من المتبقي من السقف.
         */
        const val GROWTH_HEADROOM = 0.8

        /**
         * عميل الاختبار الافتراضي: مهلة اتصال وقراءة 10 ثوانٍ
         * (سقف القياس نفسه)؛ بلا callTimeout — [withTimeout] هو
         * الذي يحكم السقف الكلي بما فيه الجولات المتتالية.
         */
        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
    }
}
