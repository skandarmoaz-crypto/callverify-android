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

object CallLogReporter {

    private const val PREFS           = "callverify"
    private const val KEY_LAST_ID     = "last_reported_call_log_id"
    private const val KEY_LAST_NUMBER = "last_reported_number"

    // ═══════════════════════════════════════════════════════════════════════════
    // 🔒 Mutex + dedup زمني عالمي يغطي كل مصادر التبليغ:
    //    InCallService (reportNumberDirectly) + CallReceiver (checkAndReportLatest)
    //    + ContentObserver. إذا أُرسل نفس الرقم خلال DEDUP_WINDOW_MS → يُتجاهل.
    //
    // 🔒 Global Mutex + time-based dedup covering ALL reporting sources:
    //    InCallService (reportNumberDirectly) + CallReceiver (checkAndReportLatest)
    //    + ContentObserver. Same number within DEDUP_WINDOW_MS → ignored.
    // ═══════════════════════════════════════════════════════════════════════════
    private val mutex = Mutex()

    private const val DEDUP_WINDOW_MS = 60_000L   // 60 ثانية بين تقرير وآخر لنفس الرقم

    // ذاكرة في الـ RAM (تُصفَّر عند إعادة التشغيل — الـ SharedPrefs تسد هذه الحالة)
    // In-memory dedup (cleared on restart — SharedPrefs handles that edge case)
    @Volatile private var lastDedupNumber: String? = null
    @Volatile private var lastDedupTimeMs: Long    = 0L

    fun lastReportedId(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_ID, -1L)

    fun lastReportedNumber(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_NUMBER, null)

    // ─── إرسال مباشر — يُستدعى من InCallService فقط ──────────────────────────
    // Direct report — called only from InCallService (default phone app path)
    suspend fun reportNumberDirectly(context: Context, number: String) {
        val appContext = context.applicationContext
        val prefs      = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val backendUrl = prefs.getString("backend_url", "") ?: return
        val apiKey     = prefs.getString("api_key",     "") ?: return
        if (backendUrl.isEmpty() || apiKey.isEmpty()) return

        mutex.withLock {
            // ✅ فحص dedup عالمي — يمنع InCallService + CallReceiver من الإرسال معاً
            // ✅ Global dedup check — blocks InCallService + CallReceiver dual-fire
            val now = System.currentTimeMillis()
            if (number == lastDedupNumber && (now - lastDedupTimeMs) < DEDUP_WINDOW_MS) return@withLock

            try {
                ApiService.build(backendUrl).reportIncomingCall(
                    appSecret = apiKey,
                    body      = IncomingCallBody(callerPhone = number)
                )
                lastDedupNumber = number
                lastDedupTimeMs = System.currentTimeMillis()
                prefs.edit().putString(KEY_LAST_NUMBER, number).apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ─── فحص سجل المكالمات — يُستدعى من CallReceiver + ContentObserver ─────────
    // Check CallLog — called from CallReceiver + ContentObserver
    suspend fun checkAndReportLatest(context: Context) {
        val appContext = context.applicationContext

        val hasPermission = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return

        val latest = withContext(Dispatchers.IO) { readLatestCall(appContext) } ?: return
        val (id, number, type) = latest

        if (type == CallLog.Calls.OUTGOING_TYPE) return
        if (number.isNullOrBlank()) return

        mutex.withLock {
            val prefs  = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastId = prefs.getLong(KEY_LAST_ID, -1L)

            // تجاهل إذا نفس ID المكالمة في سجل المكالمات سبق وأُرسل
            if (id != -1L && id == lastId) return@withLock

            // ✅ فحص dedup عالمي — يمنع CallReceiver من الإرسال بعد إرسال InCallService
            // ✅ Global dedup check — blocks CallReceiver from firing after InCallService
            val now = System.currentTimeMillis()
            if (number == lastDedupNumber && (now - lastDedupTimeMs) < DEDUP_WINDOW_MS) {
                // حدّث الـ ID في الـ prefs حتى لا نفحص هذه المكالمة مرة أخرى
                if (id != -1L) prefs.edit().putLong(KEY_LAST_ID, id).apply()
                return@withLock
            }

            val backendUrl = prefs.getString("backend_url", "") ?: return@withLock
            val apiKey     = prefs.getString("api_key",     "") ?: return@withLock
            if (backendUrl.isEmpty() || apiKey.isEmpty()) return@withLock

            try {
                ApiService.build(backendUrl).reportIncomingCall(
                    appSecret = apiKey,
                    body      = IncomingCallBody(callerPhone = number)
                )
                lastDedupNumber = number
                lastDedupTimeMs = System.currentTimeMillis()
                prefs.edit()
                    .putLong(KEY_LAST_ID,       id)
                    .putString(KEY_LAST_NUMBER, number)
                    .apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun readLatestCall(context: Context): Triple<Long, String?, Int>? {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE),
                null, null,
                "${CallLog.Calls.DATE} DESC LIMIT 1"
            )
            if (cursor != null && cursor.moveToFirst()) {
                val idIdx   = cursor.getColumnIndex(CallLog.Calls._ID)
                val numIdx  = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                Triple(
                    if (idIdx   >= 0) cursor.getLong(idIdx)    else -1L,
                    if (numIdx  >= 0) cursor.getString(numIdx)  else null,
                    if (typeIdx >= 0) cursor.getInt(typeIdx)    else -1
                )
            } else null
        } catch (e: Exception) {
            e.printStackTrace(); null
        } finally {
            cursor?.close()
        }
    }
}
