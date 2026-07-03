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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

// يُشغّل خدمة المراقبة تلقائياً عند إعادة تشغيل الهاتف — بدون هذا، الخدمة
// تبقى متوقفة حتى يفتح المستخدم التطبيق يدوياً بعد كل إعادة تشغيل
// Actually starts the monitoring service on boot — without this, the service
// stays off until the user manually opens the app after every reboot
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                val serviceIntent = Intent(context, CallService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
