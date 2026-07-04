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
// 🇸🇦 منذ أندرويد 10 (API 29)، نظام أندرويد يمنع تطبيقات الطرف الثالث
//    من الحصول على رقم المتصل عبر EXTRA_INCOMING_NUMBER.
//    الالتقاط الأساسي يتم عبر ContentObserver في CallService.
//    هذا المستقبل يعمل كطبقة احتياطية مضاعفة:
//    1. يُشغّل CallService إذا كان النظام قد أوقفها
//    2. يُطلق فحصاً فورياً لسجل المكالمات وقت الرنين (بعض OEMs تكتب السجل مبكراً)
//    3. يُطلق فحوصات احتياطية متعددة بعد انتهاء الرنين
//
// 🇬🇧 Since Android 10+ blocks third-party apps from getting the caller
//    number via EXTRA_INCOMING_NUMBER, the primary detection path is the
//    ContentObserver in CallService. This receiver is a double-backup:
//    1. Restarts CallService if the OS killed it
//    2. Fires an immediate CallLog check on RINGING (some OEMs write early)
//    3. Fires multiple backup checks after the ring ends
// ============================================================
class CallReceiver : BroadcastReceiver() {

    companion object {
        @Volatile private var wasRinging = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val appContext = context.applicationContext

        // نضمن تشغيل خدمة المراقبة دائماً | Always make sure monitoring service is running
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

                // ✅ إصلاح جديد: فحص فوري وقت الرنين
                // بعض الشركات (Samsung / Xiaomi / Oppo) تكتب سجل المكالمات
                // أثناء الرنين وليس فقط بعد الانتهاء — هذا يلتقط تلك الحالة
                // ✅ New fix: immediate CallLog check on RINGING
                // Some OEMs (Samsung / Xiaomi / Oppo) write CallLog during
                // ringing, not only after it ends — this catches that case
                val pendingResult = goAsync()
                val wakeLock = acquireWakeLock(appContext, 8_000L)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        delay(1_000L) // انتظر ثانية كي يكتب OEM السجل | give OEM 1 s to write the log
                        CallLogReporter.checkAndReportLatest(appContext)
                    } finally {
                        releaseWakeLock(wakeLock)
                        pendingResult.finish()
                    }
                }
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // تم الرد — لا إجراء إضافي هنا لأن ContentObserver سيلتقط التغيير
                // Call answered — ContentObserver in CallService will catch the log update
                wasRinging = false
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (wasRinging) {
                    wasRinging = false

                    // فحوصات احتياطية بعد انتهاء الاتصال (مفقودة / مرفوضة / مُجابة)
                    // Backup checks after call ends (missed / rejected / answered)
                    val wakeLock = acquireWakeLock(appContext, 20_000L)
                    val pendingResult = goAsync()

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val delaysMs = longArrayOf(0L, 1_500L, 3_500L, 6_000L, 10_000L)
                            for (waitMs in delaysMs) {
                                if (waitMs > 0L) delay(waitMs)
                                CallLogReporter.checkAndReportLatest(appContext)
                            }
                        } finally {
                            releaseWakeLock(wakeLock)
                            pendingResult.finish()
                        }
                    }
                }
            }
        }
    }

    private fun acquireWakeLock(context: Context, timeoutMs: Long): PowerManager.WakeLock? {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CallVerify:receiverWakeLock")
                .apply { acquire(timeoutMs) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { e.printStackTrace() }
    }
}
