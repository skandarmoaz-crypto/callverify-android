// ============================================================
// ⚠️ تحذير | WARNING
// 🇸🇦 لا تقم بتشفير رابط قاعدة البيانات أو إنشاء قاعدة بيانات جديدة.
//    المالك (ناير) يتحمل كامل المسؤولية عن هذا الاختيار.
// 🇬🇧 Do NOT encrypt the database URL or create a new database.
//    The owner (Nayer) takes full responsibility for this decision.
// ============================================================

package com.callverify

import android.content.Context
import android.content.SharedPreferences
import android.telecom.Call
import android.telecom.InCallService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// يعمل فقط عندما يكون التطبيق هو تطبيق الهاتف الافتراضي
// Only active when the app is set as the default Phone app
class CallVerifyInCallService : InCallService() {

    companion object {
        @Volatile var currentCall: Call? = null

        // ═══════════════════════════════════════════════════════════════════════
        // ⚡ قيمة مخزنة في RAM — يُقرأ منها في onCallAdded بدون أي I/O
        //    يُحدَّث من MainActivity عند تغيير الـ Toggle فوراً
        // ⚡ In-RAM cache — read in onCallAdded with zero disk I/O
        //    Updated immediately from MainActivity on toggle change
        // ═══════════════════════════════════════════════════════════════════════
        @Volatile var autoRejectEnabled: Boolean = false
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate() {
        super.onCreate()

        // تحميل القيمة من الـ prefs مرة واحدة عند بدء الخدمة
        // Load initial value from prefs once when service starts
        val prefs = getSharedPreferences("callverify", Context.MODE_PRIVATE)
        autoRejectEnabled = prefs.getBoolean(PREF_AUTO_REJECT, false)

        // استمع لأي تغيير يحدث من Toggle في MainActivity
        // Listen for toggle changes from MainActivity
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

        val number = call.details.handle?.schemeSpecificPart

        // ═══════════════════════════════════════════════════════════════════════
        // ⚡ رفض فائق السرعة: القراءة من @Volatile Boolean في RAM = نانوثوان
        //    call.reject() يُرسَل للنظام فوراً بدون أي انتظار I/O
        // ⚡ Ultra-fast reject: read @Volatile Boolean from RAM = nanoseconds
        //    call.reject() sent to system immediately with zero I/O wait
        // ═══════════════════════════════════════════════════════════════════════
        if (autoRejectEnabled) {
            call.reject(false, null)      // ← الرفض يحدث هنا مباشرة — أسرع ممكن

            // تبليغ الـ backend بالرقم في الخلفية (لا يؤخر الرفض)
            // Report to backend in background (does NOT delay the reject)
            if (!number.isNullOrBlank()) {
                scope.launch {
                    CallLogReporter.reportNumberDirectly(applicationContext, number)
                }
            }
            return   // لا نفتح InCallActivity — المكالمة انرفضت تلقائياً
        }

        // ─── الوضع الطبيعي: عرض شاشة المكالمة ──────────────────────────────
        if (!number.isNullOrBlank()) {
            scope.launch {
                CallLogReporter.reportNumberDirectly(applicationContext, number)
            }
        }

        try {
            startActivity(
                android.content.Intent(this, InCallActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
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
