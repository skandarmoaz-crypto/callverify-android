// ============================================================
// ⚠️ تحذير | WARNING
// ------------------------------------------------------------
// 🇸🇦 لا تقم بتشفير رابط قاعدة البيانات أو إنشاء قاعدة بيانات جديدة.
//    المالك (ناير) يتحمل كامل المسؤولية عن هذا الاختيار.
//    رابط Backend يُدخله المستخدم في إعدادات التطبيق.
//
// 🇬🇧 Do NOT encrypt the database URL or create a new database.
//    The owner (Nayer) takes full responsibility for this decision.
//    Backend URL is entered by the user in app settings.
// ============================================================

package com.callverify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.*

// ============================================================
// ملاحظة مهمة | Important note
// ------------------------------------------------------------
// 🇸🇦 منذ أندرويد 10 (API 29)، نظام أندرويد يمنع تطبيقات الطرف
//    الثالث (غير تطبيق الهاتف الافتراضي) من استقبال EXTRA_INCOMING_NUMBER
//    ضمن بث ACTION_PHONE_STATE_CHANGED، حتى لو مُنحت كل الأذونات.
//    الاعتماد الأساسي الآن على ContentObserver في CallService الذي يراقب
//    سجل المكالمات مباشرة لحظة كتابته (بغض النظر عن التوقيت). هذا المستقبل
//    (Receiver) يبقى فقط كطبقة احتياطية: يتأكد أن الخدمة تعمل، ويطلب فحصاً
//    إضافياً لسجل المكالمات بعد انتهاء الرنين تحسباً لأي تأخير في تسليم
//    تغييرات ContentObserver.
//
// 🇬🇧 Since Android 10 (API 29), the OS blocks third-party
//    (non-default-dialer) apps from receiving EXTRA_INCOMING_NUMBER
//    in the ACTION_PHONE_STATE_CHANGED broadcast, even with all
//    permissions granted. The primary detection path is now the
//    ContentObserver in CallService, which reacts the instant CallLog is
//    written (no fixed-timing guesswork). This receiver is now just a
//    backup layer: it makes sure the service is running and triggers an
//    extra CallLog check after the ring ends, in case a ContentObserver
//    notification is ever delayed or missed.
// ============================================================
class CallReceiver : BroadcastReceiver() {

    companion object {
        @Volatile
        private var wasRinging = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        // نستقبل فقط أحداث تغيير حالة الهاتف | Only handle phone state changes
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val appContext = context.applicationContext

        // نضمن تشغيل خدمة المراقبة عند أي تغيّر في حالة الهاتف — إذا كان
        // النظام قد أوقفها، هذا يعيد تشغيلها فوراً (والخدمة نفسها تسجّل
        // ContentObserver الذي يلتقط المكالمة الفائتة لحظة كتابتها)
        // Make sure the monitoring service is running on any phone-state
        // change — if the OS had stopped it, this restarts it immediately
        // (the service itself registers the ContentObserver that captures
        // the missed call the moment it's written)
        try {
            val serviceIntent = Intent(appContext, CallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(serviceIntent)
            } else {
                appContext.startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                wasRinging = true
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (wasRinging) {
                    wasRinging = false

                    // نُبقي الجهاز مستيقظاً ونمدد عمر استقبال البث حتى لا يقتل
                    // النظام العملية قبل أن ينتهي فحص سجل المكالمات الاحتياطي
                    // Keep the CPU awake and extend the receiver's lifetime so
                    // the OS doesn't kill the process before the backup
                    // CallLog check finishes
                    val wakeLock = try {
                        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                        pm.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK,
                            "CallVerify:missedCallWakeLock"
                        ).apply { acquire(15_000L) }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                    val pendingResult = goAsync()

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            // محاولات احتياطية قصيرة — الالتقاط الأساسي يتم عبر
                            // ContentObserver، وهذه فقط شبكة أمان إضافية
                            // Short backup retries — primary capture happens via
                            // ContentObserver, this is just an extra safety net
                            val delaysMs = longArrayOf(0, 1500, 3000, 5000)
                            for (waitMs in delaysMs) {
                                if (waitMs > 0) delay(waitMs)
                                CallLogReporter.checkAndReportLatest(appContext)
                            }
                        } finally {
                            try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { e.printStackTrace() }
                            pendingResult.finish()
                        }
                    }
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // تم الرد على المكالمة — لا تُعتبر فائتة | Call was answered — not missed
                wasRinging = false
            }
        }
    }
}
