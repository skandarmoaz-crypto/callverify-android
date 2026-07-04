// ============================================================
  // ⚠️ تحذير | WARNING
  // 🇸🇦 لا تقم بتشفير رابط قاعدة البيانات أو إنشاء قاعدة بيانات جديدة.
  //    المالك (ناير) يتحمل كامل المسؤولية عن هذا الاختيار.
  // 🇬🇧 Do NOT encrypt the database URL or create a new database.
  //    The owner (Nayer) takes full responsibility for this decision.
  // ============================================================

  package com.callverify

  import android.content.Intent
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

          // ✅ الرفض التلقائي دائماً مفعّل ومثبّت — لا يمكن تغييره
          // ✅ Auto-reject always ON and locked — immutable
          @Volatile var autoRejectEnabled: Boolean = true

          // وقت الرفض الثابت — ربع جرسة تقريباً
          // Fixed rejection target — approx 1/4 ring
          private const val REJECT_TARGET_MS = 400L
          private const val POLL_INTERVAL_MS = 30L    // polling سريع جداً / ultra-fast polling
          private const val POLL_MAX_COUNT   = 8      // 8 × 30ms = 240ms قبل الرفض
      }

      private val scope = CoroutineScope(Dispatchers.IO + Job())

      override fun onCallAdded(call: Call) {
          super.onCallAdded(call)
          currentCall = call

          val isIncoming = call.details.callDirection == Call.Details.DIRECTION_INCOMING
          if (!isIncoming) return

          // ✅ دائماً يرفض — لا يوجد شرط toggle
          // ✅ Always rejects — no toggle condition
          scope.launch {
              val tStart = SystemClock.elapsedRealtime()

              // Step 1: polling سريع للرقم قبل الرفض
              var number = call.details.handle?.schemeSpecificPart
              var pollCount = 0
              while (number.isNullOrBlank() && pollCount < POLL_MAX_COUNT) {
                  delay(POLL_INTERVAL_MS)
                  pollCount++
                  number = call.details.handle?.schemeSpecificPart
              }

              // Step 2: إرسال الرقم للسيرفر بشكل موازٍ مع الرفض
              val reportJob = if (!number.isNullOrBlank()) {
                  launch { CallLogReporter.reportNumberDirectly(applicationContext, number!!) }
              } else null

              // Step 3: رفض عند الوقت الثابت بالضبط
              val elapsed = SystemClock.elapsedRealtime() - tStart
              val rejectDelay = (REJECT_TARGET_MS - elapsed).coerceAtLeast(0L)
              delay(rejectDelay)
              try { call.reject(false, null) } catch (_: Exception) {}

              // Step 4: انتظر تأكيد السيرفر ثم أبلّغ الـ WebView بالتحديث
              reportJob?.join()
              try {
                  sendBroadcast(
                      Intent(ACTION_CALL_DETECTED).apply { setPackage(packageName) }
                  )
              } catch (_: Exception) {}

              // Step 5: fallback عبر سجل المكالمات إذا لم يتوفر الرقم
              if (number.isNullOrBlank()) {
                  delay(1_000L)
                  CallLogReporter.checkAndReportLatest(applicationContext)
                  try {
                      sendBroadcast(
                          Intent(ACTION_CALL_DETECTED).apply { setPackage(packageName) }
                      )
                  } catch (_: Exception) {}
              }
          }
      }

      override fun onCallRemoved(call: Call) {
          super.onCallRemoved(call)
          if (currentCall == call) currentCall = null
      }
  }
