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

// ============================================================
// نقطة واحدة مشتركة لقراءة آخر مكالمة فائتة/مرفوضة وإرسالها للـ Backend.
// ------------------------------------------------------------
// لماذا هذا الملف موجود:
// الاعتماد فقط على "انتظار عدد ثوانٍ ثابت بعد بث انتهاء الرنين" (الطريقة
// القديمة) يفشل على أجهزة كثيرة لأن تطبيق الهاتف الافتراضي (خصوصاً في
// واجهات الشركات المصنّعة مثل Xiaomi/Vivo/Oppo/Huawei/Tecno/Infinix) قد
// يستغرق وقتاً أطول من المنتظر لكتابة المكالمة في سجل المكالمات، أو قد
// يسجلها كـ "مرفوضة" بدل "فائتة". لذلك ننتقل هنا لمراقبة سجل المكالمات
// مباشرة عبر ContentObserver في الخدمة (CallService)، ونستخدم هذه الدالة
// كنقطة تحقق وإرسال موحّدة مع منع التكرار (لا نرسل نفس المكالمة مرتين
// حتى لو استُدعيت من أكثر من مكان).
//
// Why this file exists:
// Relying only on "wait a fixed number of seconds after the ring-end
// broadcast" (the old approach) fails on many devices because the default
// dialer app (especially OEM skins like Xiaomi/Vivo/Oppo/Huawei/Tecno/
// Infinix) can take longer than expected to write the call into CallLog,
// or may log it as "rejected" instead of "missed". We now watch CallLog
// directly via a ContentObserver in CallService, and use this function as
// a single, de-duplicated check-and-report entry point (safe to call from
// multiple triggers without double-reporting the same call).
// ============================================================
object CallLogReporter {

    private const val PREFS = "callverify"
    private const val KEY_LAST_ID = "last_reported_call_log_id"

    // إرسال فوري ومباشر لرقم قادم من نظام الاتصالات مباشرة (InCallService) —
    // هذا هو الرقم الحقيقي بدون أي حاجة لقراءة سجل المكالمات إطلاقاً، ويُستخدم
    // فقط عندما يكون التطبيق هو تطبيق الهاتف الافتراضي
    // Immediate, direct report of a number coming straight from the telecom
    // system (InCallService) — the real number, no CallLog read needed at
    // all. Used only when the app is the default Phone app.
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

    suspend fun checkAndReportLatest(context: Context) {
        val appContext = context.applicationContext

        val hasPermission = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return

        val latest = withContext(Dispatchers.IO) { readLatestCall(appContext) } ?: return
        val (id, number, type) = latest

        // فقط المكالمات الفائتة أو المرفوضة تهمنا هنا — بعض الأجهزة تسجل
        // الرفض السريع (قبل أن يصل للحالة "فائتة") كـ REJECTED
        // Only missed or rejected calls matter here — some devices log a
        // fast decline (before it reaches "missed") as REJECTED
        if (type != CallLog.Calls.MISSED_TYPE && type != CallLog.Calls.REJECTED_TYPE) return
        if (number.isNullOrBlank()) return

        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastId = prefs.getLong(KEY_LAST_ID, -1L)
        if (id != -1L && id == lastId) return // نفس المكالمة أُرسلت مسبقاً | already reported

        val backendUrl = prefs.getString("backend_url", "") ?: return
        val apiKey = prefs.getString("api_key", "") ?: return
        if (backendUrl.isEmpty() || apiKey.isEmpty()) return

        try {
            val retrofit = ApiService.build(backendUrl)
            retrofit.reportIncomingCall(
                appSecret = apiKey,
                body = IncomingCallBody(callerPhone = number)
            )
            // نُسجّل الإرسال الناجح فقط لمنع إعادة المحاولة على فشل الشبكة
            // Only mark as reported on success, so a network failure can retry
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
                val idIdx = cursor.getColumnIndex(CallLog.Calls._ID)
                val numIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                Triple(
                    if (idIdx >= 0) cursor.getLong(idIdx) else -1L,
                    if (numIdx >= 0) cursor.getString(numIdx) else null,
                    if (typeIdx >= 0) cursor.getInt(typeIdx) else -1
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
