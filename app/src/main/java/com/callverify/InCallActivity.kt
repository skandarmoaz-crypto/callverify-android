// ============================================================
// ⚠️ تحذير | WARNING
// 🇸🇦 لا تقم بتشفير رابط قاعدة البيانات أو إنشاء قاعدة بيانات جديدة.
//    المالك (ناير) يتحمل كامل المسؤولية عن هذا الاختيار.
// 🇬🇧 Do NOT encrypt the database URL or create a new database.
//    The owner (Nayer) takes full responsibility for this decision.
// ============================================================

package com.callverify

import android.os.Bundle
import android.telecom.Call
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

// شاشة مكالمة بسيطة (رد / رفض / إنهاء) — التطبيق يعرضها بنفسه لأنه بعد أن
// يصبح تطبيق الهاتف الافتراضي، هو المسؤول عن واجهة المكالمات
// Minimal call screen (answer / decline / hang up) — the app shows this
// itself because once it's the default phone app, it owns the call UI
class InCallActivity : AppCompatActivity() {

    private var callback: Call.Callback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        setContentView(R.layout.activity_in_call)

        val call = CallVerifyInCallService.currentCall
        val numberView = findViewById<TextView>(R.id.callerNumberText)
        val statusView = findViewById<TextView>(R.id.callStatusText)
        numberView.text = call?.details?.handle?.schemeSpecificPart ?: "رقم غير معروف"

        val isIncoming = call?.details?.callDirection == Call.Details.DIRECTION_INCOMING
        statusView.text = if (isIncoming) "مكالمة واردة..." else "جاري الاتصال..."

        val answerButton = findViewById<Button>(R.id.answerButton)
        val declineButton = findViewById<Button>(R.id.declineButton)
        val hangupButton = findViewById<Button>(R.id.hangupButton)

        answerButton.visibility = if (isIncoming) android.view.View.VISIBLE else android.view.View.GONE

        answerButton.setOnClickListener {
            CallVerifyInCallService.currentCall?.answer(0)
            answerButton.visibility = android.view.View.GONE
            declineButton.visibility = android.view.View.GONE
        }
        declineButton.setOnClickListener {
            CallVerifyInCallService.currentCall?.reject(false, null)
            finish()
        }
        hangupButton.setOnClickListener {
            CallVerifyInCallService.currentCall?.disconnect()
            finish()
        }

        val cb = object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) {
                when (state) {
                    Call.STATE_ACTIVE -> statusView.text = "المكالمة جارية"
                    Call.STATE_DISCONNECTED -> finish()
                }
            }
        }
        callback = cb
        call?.registerCallback(cb)
    }

    override fun onDestroy() {
        super.onDestroy()
        callback?.let { CallVerifyInCallService.currentCall?.unregisterCallback(it) }
    }
}
