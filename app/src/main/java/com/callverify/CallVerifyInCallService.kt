// ============================================================
// ⚠️ تحذير | WARNING
// 🇸🇦 لا تقم بتشفير رابط قاعدة البيانات أو إنشاء قاعدة بيانات جديدة.
//    المالك (ناير) يتحمل كامل المسؤولية عن هذا الاختيار.
// 🇬🇧 Do NOT encrypt the database URL or create a new database.
//    The owner (Nayer) takes full responsibility for this decision.
// ============================================================

package com.callverify

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ============================================================
// 🇸🇦 هذه الخدمة تعمل فقط عندما يكون التطبيق هو "تطبيق الهاتف الافتراضي".
//    عندها يسلّمنا نظام أندرويد رقم المتصل مباشرة ولحظياً عبر
//    call.details.handle — بدون أي تأخير أو اعتماد على سجل المكالمات،
//    وهي الطريقة الوحيدة المضمونة 100% على كل الأجهزة وكل الشركات المصنّعة.
//
// 🇬🇧 This service only runs once the app is set as the "default Phone
//    app". Android then hands us the caller number directly and
//    instantly via call.details.handle — no delay, no CallLog
//    dependency. This is the only 100%-guaranteed method across all
//    devices and OEMs.
// ============================================================
class CallVerifyInCallService : InCallService() {

    companion object {
        @Volatile
        var currentCall: Call? = null
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        currentCall = call

        val isIncoming = call.details.callDirection == Call.Details.DIRECTION_INCOMING
        val number = call.details.handle?.schemeSpecificPart

        if (isIncoming && !number.isNullOrBlank()) {
            scope.launch {
                CallLogReporter.reportNumberDirectly(applicationContext, number)
            }
        }

        // نعرض شاشة اتصال بسيطة كي يقدر المستخدم يرد/يرفض/يقفل — لازمة لأن
        // التطبيق أصبح مسؤولاً عن واجهة المكالمات بعد أن صار التطبيق الافتراضي
        // We show a minimal call screen so the user can answer/decline/hang up —
        // required because the app is now responsible for the call UI once it
        // becomes the default phone app
        try {
            val intent = Intent(this, InCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) {
                if (state == Call.STATE_DISCONNECTED && currentCall == c) {
                    currentCall = null
                }
            }
        })
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (currentCall == call) currentCall = null
    }
}
