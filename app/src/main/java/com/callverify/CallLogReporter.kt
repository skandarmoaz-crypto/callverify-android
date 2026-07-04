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

    // ✅ Mutex يضمن إن كوروتين واحد بس يدخل منطقة التحقق والإرسال في أي وقت —
    //    هذا يحل مشكلة التكرار الناتجة عن race condition بين
    //    ContentObserver + RINGING check + IDLE checks
    // ✅ Mutex ensures only one coroutine at a time enters the check-and-report
    //    section, fixing the duplicate-report race condition between
    //    ContentObserver, RINGING check, and IDLE backup checks.
    private val mutex = Mutex()

    fun lastReportedId(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_ID, -1L)

    fun lastReportedNumber(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_NUMBER, null)

    // ─── إرسال مباشر من InCallService (تطبيق الهاتف الافتراضي فقط) ──────────────
    suspend fun reportNumberDirectly(context: Context, number: String) {
        val appContext = context.applicationContext
        val prefs      = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val backendUrl = prefs.getString("backend_url", "") ?: return
        val apiKey     = prefs.getString("api_key",     "") ?: return
        if (backendUrl.isEmpty() || apiKey.isEmpty()) return

        mutex.withLock {
            try {
                ApiService.build(backendUrl).reportIncomingCall(
                    appSecret = apiKey,
                    body      = IncomingCallBody(callerPhone = number)
                )
                prefs.edit().putString(KEY_LAST_NUMBER, number).apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ─── فحص سجل المكالمات وإرسال آخر مكالمة واردة (مع حماية من التكرار) ───────
    // Check CallLog and report the latest incoming call (duplicate-safe)
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

        // ✅ كل العمليات داخل الـ Mutex — لا يمكن لكوروتينين أن يمررا الفحص معاً
        // ✅ All state checks inside the Mutex — no two coroutines can pass simultaneously
        mutex.withLock {
            val prefs  = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastId = prefs.getLong(KEY_LAST_ID, -1L)

            // تجاهل إذا نفس المكالمة أُرسلت مسبقاً
            if (id != -1L && id == lastId) return@withLock

            val backendUrl = prefs.getString("backend_url", "") ?: return@withLock
            val apiKey     = prefs.getString("api_key",     "") ?: return@withLock
            if (backendUrl.isEmpty() || apiKey.isEmpty()) return@withLock

            try {
                ApiService.build(backendUrl).reportIncomingCall(
                    appSecret = apiKey,
                    body      = IncomingCallBody(callerPhone = number)
                )
                prefs.edit()
                    .putLong(KEY_LAST_ID,     id)
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
