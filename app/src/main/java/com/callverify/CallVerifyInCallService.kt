// ============================================================
// ⚠️ تحذير | WARNING
// 🇸🇦 لا تقم بتشفير رابط قاعدة البيانات أو إنشاء قاعدة بيانات جديدة.
//    المالك (ناير) يتحمل كامل المسؤولية عن هذا الاختيار.
// 🇬🇧 Do NOT encrypt the database URL or create a new database.
//    The owner (Nayer) takes full responsibility for this decision.
// ============================================================

package com.callverify

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.telecom.Call
import android.telecom.InCallService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// يعمل فقط عندما يكون التطبيق هو تطبيق الهاتف الافتراضي
// Only active when the app is set as the default Phone app
class CallVerifyInCallService : InCallService() {

    companion object {
        @Volatile var currentCall: Call? = null

        // ⚡ قيمة الرفض التلقائي مخزنة في RAM — لا يوجد قراءة ديسك في onCallAdded
        // ⚡ Auto-reject flag cached in RAM — zero disk I/O in onCallAdded
        @Volatile var autoRejectEnabled: Boolean = false
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("callverify", Context.MODE_PRIVATE)
        autoRejectEnabled = prefs.getBoolean(PREF_AUTO_REJECT, false)

        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PREF_AUTO_REJECT) {
                autoRejectEnabled = prefs.getBoolean(PREF_AUTO_REJECT, false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        prefsListener?.let {
            getSharedPreferences("callverify", Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(it)
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        currentCall = call

        val isIncoming = call.details.callDirection == Call.Details.DIRECTION_INCOMING
        if (!isIncoming) return

        if (autoRejectEnabled) {
            // ══════════════════════════════════════════════════════════════════
            // ⚡ الرفض الفائق: call.reject() يُرسل للنظام في نفس اللحظة
            //    بدون أي انتظار — الـ handle قد يكون null لأن النظام لم يجهّزه بعد
            //
            // ⚡ Ultra-fast reject: call.reject() sent to system immediately
            //    No waiting — handle may be null as system hasn't resolved it yet
            // ══════════════════════════════════════════════════════════════════
            val immediateNumber = call.details.handle?.schemeSpecificPart
            call.reject(false, null)   // ← رفض فوري

            scope.launch {
                // 1️⃣ إذا كان الرقم متاحاً → أرسله فوراً
                // 1️⃣ If number was available → report immediately
                if (!immediateNumber.isNullOrBlank()) {
                    CallLogReporter.reportNumberDirectly(applicationContext, immediateNumber)
                }

                // 2️⃣ انتظر ثانيتين ثم تحقق من سجل المكالمات
                //    هذا يضمن رصد المكالمات حتى لو كان الرقم null في onCallAdded
                //    الـ dedup يمنع الإرسال المزدوج إذا أُرسل بالفعل في الخطوة 1
                // 2️⃣ Wait 2s then check call log — ensures call is captured even
                //    if number was null above. Dedup blocks double-report from step 1.
                delay(2_000L)
                CallLogReporter.checkAndReportLatest(applicationContext)

                // 3️⃣ أعلم الـ WebView بأن مكالمة تمت (كانت مفقودة في الإصدارات السابقة)
                // 3️⃣ Notify WebView that a call occurred (was missing in previous versions)
                try {
                    sendBroadcast(
                        Intent(ACTION_CALL_DETECTED).apply { setPackage(packageName) }
                    )
                } catch (_: Exception) {}
            }
            return
        }

        // ─── الوضع الطبيعي: مراقبة + شاشة المكالمة ───────────────────────────
        val number = call.details.handle?.schemeSpecificPart
        if (!number.isNullOrBlank()) {
            scope.launch {
                CallLogReporter.reportNumberDirectly(applicationContext, number)
            }
        }

        try {
            startActivity(
                Intent(this, InCallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
                }
            )
        } catch (e: Exception) { e.printStackTrace() }

        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) {
                when (state) {
                    Call.STATE_DISCONNECTED  -> { if (currentCall == c) currentCall = null }
                    Call.STATE_DISCONNECTING -> {
                        val num = c.details?.handle?.schemeSpecificPart
                        if (!num.isNullOrBlank() &&
                            c.details?.callDirection == Call.Details.DIRECTION_INCOMING) {
                            scope.launch {
                                CallLogReporter.reportNumberDirectly(applicationContext, num)
                            }
                        }
                    }
                }
            }
        })
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (currentCall == call) currentCall = null
    }
}
