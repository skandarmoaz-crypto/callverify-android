// ============================================================
// ⚠️ تحذير | WARNING
// 🇸🇦 لا تقم بتشفير رابط قاعدة البيانات أو إنشاء قاعدة بيانات جديدة.
//    المالك (ناير) يتحمل كامل المسؤولية عن هذا الاختيار.
// 🇬🇧 Do NOT encrypt the database URL or create a new database.
//    The owner (Nayer) takes full responsibility for this decision.
// ============================================================

package com.callverify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CallLog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// تحويل رقم الهاتف السوداني للصيغة الدولية قبل الإرسال
// Normalize Sudanese phone to international format before sending
// e.g. 0902288269 → +249902288269 | +249902288269 → +249902288269
fun normalizePhone(number: String): String {
    val cleaned = number.trim().replace(Regex("[\\s\\-]"), "")
    return when {
        cleaned.startsWith("+")  -> cleaned
        cleaned.startsWith("00") -> "+${cleaned.substring(2)}"
        cleaned.startsWith("0")  -> "+249${cleaned.substring(1)}"
        else                     -> "+249$cleaned"
    }
}

object CallLogReporter {

    private const val PREFS                = "callverify"
    private const val KEY_LAST_ID          = "last_reported_call_log_id"
    private const val KEY_LAST_NUMBER      = "last_reported_number"
    private const val KEY_LAST_DATE        = "last_reported_call_date"
    // ✅ مفتاح جديد: وقت آخر إرسال ناجح — مشترك بين كل مصادر التبليغ
    // ✅ New key: last successful report timestamp — shared across ALL reporting sources
    private const val KEY_LAST_REPORT_TIME = "last_report_time_ms"

    private val mutex = Mutex()

    // ═══════════════════════════════════════════════════════════════════════════
    // 🔧 الإصلاح الجذري لمشكلة التكرار:
    //
    // المشكلة كانت: reportNumberDirectly يخزن الرقم الخام مثل "0902288269"
    // بينما checkAndReportLatest يقرأه من سجل المكالمات كـ "+249902288269"
    // → المقارنة تفشل دائماً → كل مصدر يُرسل تقرير منفصل → تكرار!
    //
    // الحل:
    // ① نُطبّع (normalize) الرقم قبل تخزينه في lastDedupNumber
    // ② نحفظ وقت آخر إرسال في SharedPrefs (يبقى بعد restart العملية)
    // ③ كلا الدالتين تقرآن نفس المفاتيح وتكتبان فيها
    // ④ نافذة 30 ثانية — تكفي لكل retries المكالمة الواحدة
    //
    // 🔧 Root fix for duplicate reporting:
    //
    // Bug was: reportNumberDirectly stored raw number e.g. "0902288269"
    // while checkAndReportLatest read from call log as "+249902288269"
    // → comparison always failed → each source sent its own report → duplicates!
    //
    // Fix:
    // ① Normalize the number BEFORE storing in lastDedupNumber
    // ② Save last report time in SharedPrefs (survives process restart)
    // ③ Both functions read/write the same keys
    // ④ 30-second window — covers all retries for a single call
    // ═══════════════════════════════════════════════════════════════════════════

    // نافذة 30 ثانية: تمنع كل تكرارات المكالمة الواحدة دون حجب مكالمات جديدة
    // 30s window: blocks all duplicates of one call without blocking new calls
    private const val DEDUP_WINDOW_MS = 3_000L  // ✅ مُخفَّض من 30s إلى 3s — كل مكالمة منفصلة تُحسب

    // RAM cache — يُسرّع الفحص بدون قراءة SharedPrefs في كل مرة
    // RAM cache — speeds up checks without reading SharedPrefs every time
    // ⚠️ دائماً مُطبَّع (normalized) — هذا هو جوهر الإصلاح
    // ⚠️ Always normalized — this is the core of the fix
    @Volatile private var lastDedupNumber: String? = null
    @Volatile private var lastDedupTimeMs: Long    = 0L

    fun lastReportedId(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_ID, -1L)

    fun lastReportedNumber(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_NUMBER, null)

    // ─── فحص dedup موحّد — يُستدعى من كلا الدالتين ──────────────────────────
    // Unified dedup check — called by both functions
    // يجب استدعاؤها داخل mutex دائماً | Must always be called inside mutex
    private fun isDuplicate(context: Context, normalizedNumber: String): Boolean {
        val now = System.currentTimeMillis()

        // ① فحص RAM أولاً — أسرع (بلا disk I/O)
        // ① Check RAM first — fastest (no disk I/O)
        if (normalizedNumber == lastDedupNumber && (now - lastDedupTimeMs) < DEDUP_WINDOW_MS) {
            return true
        }

        // ② فحص SharedPrefs — يغطي حالة restart العملية
        // ② Check SharedPrefs — covers process-restart edge case
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedNumber = prefs.getString(KEY_LAST_NUMBER, null)
        val savedTime   = prefs.getLong(KEY_LAST_REPORT_TIME, 0L)
        if (normalizedNumber == savedNumber && (now - savedTime) < DEDUP_WINDOW_MS) {
            // نحدّث RAM cache لتسريع الفحوصات القادمة
            // Update RAM cache to speed up future checks
            lastDedupNumber = normalizedNumber
            lastDedupTimeMs = savedTime
            return true
        }

        return false
    }

    // ─── تسجيل إرسال ناجح — يُستدعى من كلا الدالتين ────────────────────────
    // Record successful report — called by both functions
    // يجب استدعاؤها داخل mutex دائماً | Must always be called inside mutex
    private fun markReported(
        context: Context,
        normalizedNumber: String,
        callLogId: Long  = -1L,
        callDate:  Long  = -1L
    ) {
        val now = System.currentTimeMillis()
        // تحديث RAM cache
        lastDedupNumber = normalizedNumber
        lastDedupTimeMs = now

        // تحديث SharedPrefs — يصمد بعد restart العملية
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_NUMBER, normalizedNumber)
            .putLong(KEY_LAST_REPORT_TIME, now)
        if (callLogId != -1L) edit.putLong(KEY_LAST_ID, callLogId)
        if (callDate  != -1L) edit.putLong(KEY_LAST_DATE, callDate)
        edit.apply()
    }

    // ─── إرسال مباشر — يُستدعى من InCallService فقط ──────────────────────────
    // Direct report — called only from InCallService (default phone app path)
    suspend fun reportNumberDirectly(context: Context, number: String) {
        val appContext     = context.applicationContext
        val prefs          = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val backendUrl     = prefs.getString("backend_url", DEFAULT_BACKEND_URL) ?: return
        val apiKey         = prefs.getString("api_key", DEFAULT_APP_SECRET) ?: return
        val normalizedNum  = normalizePhone(number)

        mutex.withLock {
            // ✅ dedup موحّد — يقارن أرقاماً مُطبَّعة دائماً
            // ✅ Unified dedup — always compares normalized numbers
            if (isDuplicate(appContext, normalizedNum)) return@withLock

            try {
                ApiService.build(backendUrl).reportIncomingCall(
                    appSecret = apiKey,
                    body      = IncomingCallBody(callerPhone = normalizedNum)
                )
                // ✅ نحفظ في SharedPrefs أيضاً حتى تراه checkAndReportLatest
                // ✅ Save to SharedPrefs so checkAndReportLatest can see it
                markReported(appContext, normalizedNum)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ─── فحص سجل المكالمات — يُستدعى من CallReceiver + ContentObserver ─────────
    // Check CallLog — called from CallReceiver + ContentObserver (backup path)
    suspend fun checkAndReportLatest(context: Context) {
        val appContext = context.applicationContext

        val hasPermission = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return

        val latest = withContext(Dispatchers.IO) { readLatestCall(appContext) } ?: return
        val (id, number, type, callDate) = latest

        if (type == CallLog.Calls.OUTGOING_TYPE) return
        if (number.isNullOrBlank()) return

        val normalizedNum = normalizePhone(number)

        mutex.withLock {
            val prefs  = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastId = prefs.getLong(KEY_LAST_ID, -1L)

            // ✅ dedup بـ ID سجل المكالمات — الأدق والأسرع
            // ✅ Dedup by call log ID — most precise and fastest
            if (id != -1L && id == lastId) return@withLock

            // ✅ dedup موحّد — يقارن أرقاماً مُطبَّعة ويشمل SharedPrefs
            // ✅ Unified dedup — compares normalized numbers, includes SharedPrefs
            if (isDuplicate(appContext, normalizedNum)) {
                // نحدّث KEY_LAST_ID رغم ذلك لمنع فحوصات لاحقة بنفس الـ ID
                // Still update KEY_LAST_ID to block future checks with same ID
                if (id != -1L) prefs.edit().putLong(KEY_LAST_ID, id).apply()
                return@withLock
            }

            val backendUrl = prefs.getString("backend_url", DEFAULT_BACKEND_URL) ?: return@withLock
            val apiKey     = prefs.getString("api_key", DEFAULT_APP_SECRET) ?: return@withLock
            if (backendUrl.isEmpty() || apiKey.isEmpty()) return@withLock

            try {
                ApiService.build(backendUrl).reportIncomingCall(
                    appSecret = apiKey,
                    body      = IncomingCallBody(callerPhone = normalizedNum)
                )
                // ✅ يحفظ ID + Date + وقت الإرسال — يصمد لكل المصادر
                // ✅ Saves ID + Date + report time — visible to all sources
                markReported(appContext, normalizedNum, id, callDate)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private data class CallEntry(val id: Long, val number: String?, val type: Int, val date: Long)

    private fun readLatestCall(context: Context): CallEntry? {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER,
                        CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null, null,
                "${CallLog.Calls.DATE} DESC LIMIT 1"
            )
            if (cursor != null && cursor.moveToFirst()) {
                val idIdx   = cursor.getColumnIndex(CallLog.Calls._ID)
                val numIdx  = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                CallEntry(
                    id     = if (idIdx   >= 0) cursor.getLong(idIdx)   else -1L,
                    number = if (numIdx  >= 0) cursor.getString(numIdx) else null,
                    type   = if (typeIdx >= 0) cursor.getInt(typeIdx)   else -1,
                    date   = if (dateIdx >= 0) cursor.getLong(dateIdx)  else -1L
                )
            } else null
        } catch (e: Exception) {
            e.printStackTrace(); null
        } finally {
            cursor?.close()
        }
    }
}
