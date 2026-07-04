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
  import android.os.SystemClock
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

          // ═══════════════════════════════════════════════════════════════
          // وقت الرفض الثابت — يضمن دائماً نفس عدد الرنات (ربع جرسة)
          // Fixed rejection target — guarantees consistent ring count (~1/4 ring)
          // ═══════════════════════════════════════════════════════════════
          private const val REJECT_TARGET_MS = 400L   // ms من onCallAdded حتى call.reject()
          private const val POLL_INTERVAL_MS = 50L    // فترة polling لتحليل الـ handle
          private const val POLL_MAX_COUNT   = 6      // أقصى 6 محاولات = 300ms
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
              // 🔧 الإصلاح الشامل — يحل 4 مشاكل دفعة واحدة:
              //
              //  ① رنات غير منضبطة → الرفض دائماً عند REJECT_TARGET_MS ثابت
              //  ② رصد بطيء        → polling سريع للـ handle + إرسال موازٍ
              //  ③ تكرار الرقم     → fallback فقط لو الرقم لم يُعثر عليه
              //  ④ Dashboard لا يتحدث → broadcast بعد تأكيد السيرفر مباشرة
              //
              // 🔧 Comprehensive fix — solves 4 issues at once:
              //  ① Inconsistent rings → always reject at fixed REJECT_TARGET_MS
              //  ② Slow detection     → fast handle polling + parallel reporting
              //  ③ Number duplication → fallback only when number unavailable
              //  ④ Dashboard stale    → broadcast sent right after server confirms
              // ══════════════════════════════════════════════════════════════════
              scope.launch {
                  val tStart = SystemClock.elapsedRealtime()

                  // ── Step 1: polling سريع للـ handle (max POLL_INTERVAL × POLL_MAX_COUNT ms)
                  //    Fast handle polling — runs before the reject deadline
                  var number = call.details.handle?.schemeSpecificPart
                  var pollCount = 0
                  while (number.isNullOrBlank() && pollCount < POLL_MAX_COUNT) {
                      delay(POLL_INTERVAL_MS)
                      pollCount++
                      number = call.details.handle?.schemeSpecificPart
                  }

                  // ── Step 2: إرسال الرقم للسيرفر بشكل موازٍ (لا ينتظر الإرسال لإكمال الخطوات)
                  //    Report to server in parallel — does NOT block rejection timing
                  val reportJob = if (!number.isNullOrBlank()) {
                      launch { CallLogReporter.reportNumberDirectly(applicationContext, number!!) }
                  } else null

                  // ── Step 3: رفض عند الوقت الثابت بالضبط (يضمن دائماً ربع جرسة)
                  //    Reject at exactly the fixed target (guarantees consistent ring count)
                  val elapsed = SystemClock.elapsedRealtime() - tStart
                  val rejectDelay = (REJECT_TARGET_MS - elapsed).coerceAtLeast(0L)
                  delay(rejectDelay)
                  try { call.reject(false, null) } catch (_: Exception) {}

                  // ── Step 4: انتظر تأكيد السيرفر ثم أعلم الـ WebView
                  //    Wait for server confirmation, then notify WebView — ensures data is ready
                  reportJob?.join()
                  try {
                      sendBroadcast(
                          Intent(ACTION_CALL_DETECTED).apply { setPackage(packageName) }
                      )
                  } catch (_: Exception) {}

                  // ── Step 5: fallback عبر سجل المكالمات — فقط لو الرقم لم يُعثر عليه
                  //    Call-log fallback ONLY when number was unavailable (prevents duplication)
                  if (number.isNullOrBlank()) {
                      delay(2_000L)
                      CallLogReporter.checkAndReportLatest(applicationContext)
                      try {
                          sendBroadcast(
                              Intent(ACTION_CALL_DETECTED).apply { setPackage(packageName) }
                          )
                      } catch (_: Exception) {}
                  }
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
  