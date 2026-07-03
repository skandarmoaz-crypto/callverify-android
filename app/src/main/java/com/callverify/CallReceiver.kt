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

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.os.PowerManager
import android.provider.CallLog
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

// ============================================================
// ملاحظة مهمة | Important note
// ------------------------------------------------------------
// 🇸🇦 منذ أندرويد 10 (API 29)، نظام أندرويد يمنع تطبيقات الطرف
//    الثالث (غير تطبيق الهاتف الافتراضي) من استقبال EXTRA_INCOMING_NUMBER
//    ضمن بث ACTION_PHONE_STATE_CHANGED، حتى لو مُنحت كل الأذونات.
//    لذلك نعتمد بدلاً من ذلك على قراءة سجل المكالمات (CallLog)
//    بعد انتهاء الرنين مباشرة، وهي الطريقة الموثوقة الوحيدة
//    المتاحة لتطبيق خارجي على أندرويد الحديث.
//
// 🇬🇧 Since Android 10 (API 29), the OS blocks third-party
//    (non-default-dialer) apps from receiving EXTRA_INCOMING_NUMBER
//    in the ACTION_PHONE_STATE_CHANGED broadcast, even with all
//    permissions granted. Instead, we read the CallLog content
//    provider right after the call ends/rings out — this is the
//    only reliable method available to a third-party app on
//    modern Android.
// ============================================================
class CallReceiver : BroadcastReceiver() {

    companion object {
        @Volatile
        private var wasRinging = false
        @Volatile
        private var ringStartTime = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        // نستقبل فقط أحداث تغيير حالة الهاتف | Only handle phone state changes
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // الهاتف بدأ يرن — نسجل الوقت وننتظر انتهاء الرنين
                // Phone started ringing — remember the time and wait for it to stop
                wasRinging = true
                ringStartTime = System.currentTimeMillis()
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                // إذا كان يرن ثم عاد لحالة الخمول = مكالمة فائتة (لم تُجب)
                // If it was ringing and returned to idle = missed call (not answered)
                if (wasRinging) {
                    wasRinging = false
                    val appContext = context.applicationContext
                    val sinceRing = ringStartTime

                    // نُبقي الجهاز مستيقظاً ونمدد عمر استقبال البث حتى لا يقتل النظام
                    // العملية قبل أن تنتهي (خصوصاً عند إغلاق الشاشة مباشرة بعد المكالمة)
                    // Keep the CPU awake and extend the receiver's lifetime so the OS
                    // doesn't kill the process mid-work (especially right after screen-off)
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

                    // نضمن تشغيل خدمة المراقبة أيضاً هنا — إذا كان النظام قد أوقفها
                    // فهذا يعيد رفع أولوية العملية فوراً قبل محاولة قراءة السجل
                    // Also (re)start the monitoring service here — if the OS had stopped
                    // it, this immediately restores process priority before we proceed
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

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            // نتحقق من الإذن أولاً — بدونه لن نقدر نقرأ سجل المكالمات إطلاقاً
                            // Check permission first — without it we can't read CallLog at all
                            val hasPermission = ContextCompat.checkSelfPermission(
                                appContext, Manifest.permission.READ_CALL_LOG
                            ) == PackageManager.PERMISSION_GRANTED

                            if (!hasPermission) {
                                // لا يوجد إذن — لا فائدة من المحاولة
                                // No permission — no point retrying
                                return@launch
                            }

                            // محاولة فورية أولاً، ثم إعادة محاولات قصيرة — نبقى ضمن
                            // النافذة الزمنية التي يضمنها goAsync (~10 ثوانٍ) لتفادي القتل
                            // Try immediately first, then short retries — staying within
                            // the window goAsync guarantees (~10s) to avoid being killed
                            val delaysMs = longArrayOf(0, 800, 1500, 2500, 4000)
                            for (waitMs in delaysMs) {
                                if (waitMs > 0) delay(waitMs)
                                val callerNumber = readLastMissedCallNumber(appContext, sinceRing)
                                if (callerNumber != null) {
                                    sendCallerToBackend(appContext, callerNumber)
                                    break
                                }
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

    // نقرأ آخر مكالمة فائتة سُجلت بعد لحظة بدء الرنين (لتفادي التقاط رقم قديم)
    // Read the latest missed call recorded after the ring started (to avoid stale numbers)
    private fun readLastMissedCallNumber(context: Context, sinceMillis: Long): String? {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?",
                arrayOf(CallLog.Calls.MISSED_TYPE.toString(), (sinceMillis - 5000).toString()),
                "${CallLog.Calls.DATE} DESC LIMIT 1"
            )
            if (cursor != null && cursor.moveToFirst()) {
                val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val number = if (numberIndex >= 0) cursor.getString(numberIndex) else null
                if (!number.isNullOrBlank()) number else null
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

    private suspend fun sendCallerToBackend(context: Context, callerNumber: String) {
        val prefs = context.getSharedPreferences("callverify", Context.MODE_PRIVATE)
        val backendUrl = prefs.getString("backend_url", "") ?: return
        val apiKey = prefs.getString("api_key", "") ?: return

        if (backendUrl.isEmpty() || apiKey.isEmpty()) return

        try {
            val retrofit = ApiService.build(backendUrl)
            retrofit.reportIncomingCall(
                appSecret = apiKey,
                body = IncomingCallBody(callerPhone = callerNumber)
            )
            // نجح الإرسال | Success — can log to LogCat for debugging
        } catch (e: Exception) {
            // فشل الإرسال — نُسجل للتتبع فقط | Failed — log for debugging only
            e.printStackTrace()
        }
    }
}
