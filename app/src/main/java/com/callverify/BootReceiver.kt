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

// يُشغّل التطبيق تلقائياً عند إعادة تشغيل الهاتف
// Auto-starts the app when the phone reboots
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // التطبيق مسجّل في AndroidManifest كـ receiver — يعمل تلقائياً
            // App is registered in AndroidManifest as receiver — runs automatically
        }
    }
}
