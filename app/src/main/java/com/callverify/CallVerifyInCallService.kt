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
              // 🔧 الإصلاح: نرسل الرقم للسيرفر أولاً، ثم نرفض المكالمة
              //
              //    المشكلة القديمة: call.reject() يحصل فورياً قبل إرسال الرقم،
              //    وعندما يكون handle=null (النظام لم يجهّزه بعد) لا يُرسَل الرقم خالص.
              //
              //    الحل: ننتظر 300ms لتحليل الـ handle، نرسل الرقم، ثم نرفض.
              //    إجمالي الوقت من بداية الرنين ≈ 300-400ms ≈ ربع جرس.
              //
              // 🔧 FIX: Report number to server FIRST, then reject the call.
              //
              //    Old problem: call.reject() fired immediately before reporting;
              //    when handle=null (system not ready yet) the number was never sent.
              //
              //    Solution: wait 300ms for handle resolution, report, then reject.
              //    Total time from ring start ≈ 300-400ms ≈ 1/4 ring.
              // ══════════════════════════════════════════════════════════════════
              scope.launch {
                  // 1️⃣ انتظر 300ms لتحليل الـ handle (ربع جرس تقريباً)
                  //    Wait 300ms for handle resolution (~1/4 ring)
                  delay(300L)

                  // 2️⃣ احصل على الرقم بعد انتظار تحليل الـ handle
                  //    Get number after handle has had time to resolve
                  val number = call.details.handle?.schemeSpecificPart

                  // 3️⃣ أرسل الرقم للسيرفر أولاً — قبل أي رفض
                  //    Report to server FIRST — before any rejection
                  if (!number.isNullOrBlank()) {
                      CallLogReporter.reportNumberDirectly(applicationContext, number)
                  }

                  // 4️⃣ ارفض المكالمة بعد الإرسال مباشرة
                  //    Reject the call immediately after reporting
                  try {
                      call.reject(false, null)
                  } catch (_: Exception) {}

                  // 5️⃣ أعلم الـ WebView فوراً بوجود مكالمة
                  //    Notify WebView immediately
                  try {
                      sendBroadcast(
                          Intent(ACTION_CALL_DETECTED).apply { setPackage(packageName) }
                      )
                  } catch (_: Exception) {}

                  // 6️⃣ انتظر ثانيتين ثم تحقق من سجل المكالمات كـ fallback
                  //    الـ dedup يمنع الإرسال المزدوج إذا أُرسل بالفعل في الخطوة 3
                  //    Wait 2s then check call log as fallback
                  //    Dedup blocks double-report if already sent in step 3
                  delay(2_000L)
                  CallLogReporter.checkAndReportLatest(applicationContext)
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
  