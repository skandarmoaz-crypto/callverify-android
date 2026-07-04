// ============================================================
  // ⚠️ تحذير | WARNING
  // 🇸🇦 لا تقم بتشفير رابط قاعدة البيانات أو إنشاء قاعدة بيانات جديدة.
  //    المالك (ناير) يتحمل كامل المسؤولية عن هذا الاختيار.
  // 🇬🇧 Do NOT encrypt the database URL or create a new database.
  //    The owner (Nayer) takes full responsibility for this decision.
  // ============================================================

  package com.callverify

  import android.app.NotificationChannel
  import android.app.NotificationManager
  import android.app.PendingIntent
  import android.content.BroadcastReceiver
  import android.content.Context
  import android.content.Intent
  import android.os.Build
  import android.os.PowerManager
  import android.telephony.TelephonyManager
  import androidx.core.app.NotificationCompat
  import androidx.core.app.NotificationManagerCompat
  import kotlinx.coroutines.*

  const val ACTION_CALL_DETECTED = "com.callverify.CALL_DETECTED"

  class CallReceiver : BroadcastReceiver() {

      companion object {
          @Volatile private var wasRinging = false

          private const val CH_CALLS  = "callverify_calls"
          private const val NOTIF_ID  = 42

          fun ensureChannel(context: Context) {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                  val ch = NotificationChannel(
                      CH_CALLS, "مكالمات CallVerify", NotificationManager.IMPORTANCE_HIGH
                  ).apply {
                      description = "إشعارات المكالمات الواردة المرصودة"
                      enableLights(true)
                      enableVibration(true)
                  }
                  context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
              }
          }

          fun showRingingNotification(context: Context) {
              ensureChannel(context)
              val openIntent = PendingIntent.getActivity(
                  context, 0,
                  Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
                  PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
              )
              val notif = NotificationCompat.Builder(context, CH_CALLS)
                  .setSmallIcon(android.R.drawable.sym_call_incoming)
                  .setContentTitle("📞 مكالمة واردة")
                  .setContentText("جاري رصد المكالمة وإرسالها للتحقق...")
                  .setPriority(NotificationCompat.PRIORITY_HIGH)
                  .setCategory(NotificationCompat.CATEGORY_CALL)
                  .setContentIntent(openIntent)
                  .setAutoCancel(false)
                  .build()
              try { NotificationManagerCompat.from(context).notify(NOTIF_ID, notif) }
              catch (_: SecurityException) {}
          }

          fun showDetectedNotification(context: Context, number: String?) {
              ensureChannel(context)
              val openIntent = PendingIntent.getActivity(
                  context, 1,
                  Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
                  PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
              )
              val text = if (!number.isNullOrBlank()) "تم رصد: $number" else "تم رصد المكالمة"
              val notif = NotificationCompat.Builder(context, CH_CALLS)
                  .setSmallIcon(android.R.drawable.sym_call_incoming)
                  .setContentTitle("CallVerify")
                  .setContentText(text)
                  .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                  .setContentIntent(openIntent)
                  .setAutoCancel(true)
                  .build()
              try { NotificationManagerCompat.from(context).notify(NOTIF_ID, notif) }
              catch (_: SecurityException) {}
          }
      }

      override fun onReceive(context: Context, intent: Intent) {
          if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

          val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
          val appContext = context.applicationContext

          try {
              val svc = Intent(appContext, CallService::class.java)
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                  appContext.startForegroundService(svc)
              else
                  appContext.startService(svc)
          } catch (e: Exception) { e.printStackTrace() }

          when (state) {
              TelephonyManager.EXTRA_STATE_RINGING -> {
                  wasRinging = true
                  // ✅ إشعار فوري فقط — لا قراءة لسجل المكالمات هنا (لم يُكتب بعد)
                  // ✅ Notification only — no call log read here (not written yet)
                  showRingingNotification(appContext)
              }

              TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                  wasRinging = false
              }

              TelephonyManager.EXTRA_STATE_IDLE -> {
                  if (wasRinging) {
                      wasRinging = false

                      // ══════════════════════════════════════════════════════
                      // 🔧 الإصلاحات:
                      //  ① break فور أول نجاح → يمنع التكرار تماماً
                      //  ② 3 محاولات فقط بدل 5 → أسرع وأخف
                      //  ③ InCallService يرصد مباشرة — هذا backup فقط
                      // 🔧 Fixes:
                      //  ① break on first success → zero duplication
                      //  ② 3 retries instead of 5 → faster and lighter
                      //  ③ InCallService detects directly — this is backup only
                      // ══════════════════════════════════════════════════════
                      val wl = acquireWakeLock(appContext, 15_000L)
                      val pendingResult = goAsync()

                      CoroutineScope(Dispatchers.IO).launch {
                          try {
                              // ✅ delays مخفّضة للحد الأقصى للسرعة
                      val delays = longArrayOf(100L, 400L, 1_000L)
                              var reported = false
                              for (waitMs in delays) {
                                  if (reported) break
                                  delay(waitMs)
                                  val before = CallLogReporter.lastReportedId(appContext)
                                  CallLogReporter.checkAndReportLatest(appContext)
                                  val after = CallLogReporter.lastReportedId(appContext)
                                  if (after != before) {
                                      reported = true
                                      val number = CallLogReporter.lastReportedNumber(appContext)
                                      showDetectedNotification(appContext, number)
                                      appContext.sendBroadcast(
                                          Intent(ACTION_CALL_DETECTED).apply { setPackage(appContext.packageName) }
                                      )
                                      break  // ✅ وقف فوري بعد النجاح / Stop immediately after success
                                  }
                              }
                          } finally {
                              releaseWakeLock(wl)
                              pendingResult.finish()
                          }
                      }
                  }
              }
          }
      }

      private fun acquireWakeLock(context: Context, ms: Long): PowerManager.WakeLock? = try {
          (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
              .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CallVerify:receiverLock")
              .apply { acquire(ms) }
      } catch (e: Exception) { e.printStackTrace(); null }

      private fun releaseWakeLock(wl: PowerManager.WakeLock?) {
          try { wl?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
      }
  }
  