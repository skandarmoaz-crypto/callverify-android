// ============================================================
// ⚠️ تحذير | WARNING
// ------------------------------------------------------------
// 🇸🇦 لا تقم بتشفير رابط قاعدة البيانات أو إنشاء قاعدة بيانات جديدة.
//    المالك (ناير) يتحمل كامل المسؤولية عن هذا الاختيار.
//
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
import kotlinx.coroutines.withContext

object CallLogReporter {

    private const val PREFS = "callverify"
    private const val KEY_LAST_ID = "last_reported_call_log_id"

    // ─── إرسال مباشر من InCallService (عندما يكون التطبيق هو تطبيق الهاتف الافتراضي) ───
    // Direct report from InCallService (when the app IS the default Phone app)
    // No CallLog read needed — the number is delivered instantly by the telecom system
    suspend fun reportNumberDirectly(context: Context, number: String) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val backendUrl = prefs.getString("backend_url", "") ?: return
        val apiKey = prefs.getString("api_key", "") ?: return
        if (backendUrl.isEmpty() || apiKey.isEmpty()) return

        try {
            val retrofit = ApiService.build(backendUrl)
            retrofit.reportIncomingCall(
                appSecret = apiKey,
                body = IncomingCallBody(callerPhone = number)
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ─── فحص وإرسال آخر مكالمة واردة من سجل المكالمات ─────────────────────────────
    // Check and report the latest incoming call from CallLog
    // Used as fallback when the app is NOT the default Phone app
    //
    // 🔑 Fix: We now report ALL non-outgoing call types (MISSED, REJECTED, and INCOMING
    //    i.e. answered). Previously we only reported MISSED and REJECTED, which meant
    //    any answered call — including a quick ring-and-answer used for verification —
    //    was silently dropped and never sent to the backend.
    suspend fun checkAndReportLatest(context: Context) {
        val appContext = context.applicationContext

        val hasPermission = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return

        val latest = withContext(Dispatchers.IO) { readLatestCall(appContext) } ?: return
        val (id, number, type) = latest

        // ✅ الإصلاح الرئيسي: نبلّغ عن كل مكالمة واردة بغض النظر عن نوعها
        //    (مفقودة / مرفوضة / مُجابة) — نستثني فقط المكالمات الصادرة
        // ✅ Main fix: report every incoming call regardless of type
        //    (missed / rejected / answered) — only skip outgoing calls
        if (type == CallLog.Calls.OUTGOING_TYPE) return
        if (number.isNullOrBlank()) return

        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastId = prefs.getLong(KEY_LAST_ID, -1L)
        if (id != -1L && id == lastId) return  // أُرسلت مسبقاً | already reported

        val backendUrl = prefs.getString("backend_url", "") ?: return
        val apiKey = prefs.getString("api_key", "") ?: return
        if (backendUrl.isEmpty() || apiKey.isEmpty()) return

        try {
            val retrofit = ApiService.build(backendUrl)
            retrofit.reportIncomingCall(
                appSecret = apiKey,
                body = IncomingCallBody(callerPhone = number)
            )
            // نُسجّل الإرسال الناجح فقط لمنع التكرار عند إعادة المحاولة
            // Only mark as reported on success so network failures can retry
            if (id != -1L) prefs.edit().putLong(KEY_LAST_ID, id).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun readLatestCall(context: Context): Triple<Long, String?, Int>? {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE),
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT 1"
            )
            if (cursor != null && cursor.moveToFirst()) {
                val idIdx   = cursor.getColumnIndex(CallLog.Calls._ID)
                val numIdx  = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                Triple(
                    if (idIdx   >= 0) cursor.getLong(idIdx)   else -1L,
                    if (numIdx  >= 0) cursor.getString(numIdx) else null,
                    if (typeIdx >= 0) cursor.getInt(typeIdx)   else -1
                )
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            cursor?.close()
        }
    }
}
