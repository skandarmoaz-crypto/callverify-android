// ============================================================
  // ⚠️ تحذير | WARNING
  // 🇸🇦 لا تقم بتشفير رابط قاعدة البيانات أو إنشاء قاعدة بيانات جديدة.
  //    المالك (ناير) يتحمل كامل المسؤولية عن هذا الاختيار.
  // 🇬🇧 Do NOT encrypt the database URL or create a new database.
  //    The owner (Nayer) takes full responsibility for this decision.
  // ============================================================

  package com.callverify

  import android.Manifest
  import android.content.Context
  import android.content.pm.PackageManager
  import android.database.Cursor
  import android.provider.CallLog
  import androidx.core.content.ContextCompat
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.sync.Mutex
  import kotlinx.coroutines.sync.withLock
  import kotlinx.coroutines.withContext

  object CallLogReporter {

      private const val PREFS           = "callverify"
      private const val KEY_LAST_ID     = "last_reported_call_log_id"
      private const val KEY_LAST_NUMBER = "last_reported_number"
      private const val KEY_LAST_DATE   = "last_reported_call_date"   // ← جديد: وقت المكالمة

      // ═══════════════════════════════════════════════════════════════════════════
      // 🔒 Mutex + dedup مزدوج: بـ ID سجل المكالمات (دقيق) + بالوقت (احتياطي)
      //    يمنع كل مصادر التبليغ: InCallService + CallReceiver + ContentObserver
      //
      // 🔒 Dual dedup: by call log ID (precise) + by timestamp (fallback)
      //    Blocks all reporting sources: InCallService + CallReceiver + ContentObserver
      // ═══════════════════════════════════════════════════════════════════════════
      private val mutex = Mutex()

      // نافذة التكرار: 30 ثانية كافية للمكالمة الواحدة — أقل من 60s السابقة
      // Dedup window: 30s is sufficient per call — reduced from previous 60s
      // 🔧 fix: 30_000→5_000 — 30s كان يحجب مكالمات شرعية (alternating bug)
        //    5s كافية لمنع duplicate نفس الـ event، وتسمح بمكالمة جديدة بعد 5 ثواني
        // 🔧 fix: 30s window was blocking legitimate new calls (alternating-call bug)
        //    5s is enough to dedup same-call duplicates (arrive in ms), allows new call after 5s
        private const val DEDUP_WINDOW_MS = 5_000L

      // RAM dedup (تُصفَّر عند إعادة التشغيل — SharedPrefs تسد الفجوة)
      @Volatile private var lastDedupNumber: String? = null
      @Volatile private var lastDedupTimeMs: Long    = 0L

      fun lastReportedId(context: Context): Long =
          context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_ID, -1L)

      fun lastReportedNumber(context: Context): String? =
          context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_NUMBER, null)

      // ─── إرسال مباشر — يُستدعى من InCallService فقط ──────────────────────────
      // Direct report — called only from InCallService (default phone app path)
      suspend fun reportNumberDirectly(context: Context, number: String) {
          val appContext = context.applicationContext
          val prefs      = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
          val backendUrl = prefs.getString("backend_url", "") ?: return
          val apiKey     = prefs.getString("api_key",     "") ?: return
          if (backendUrl.isEmpty() || apiKey.isEmpty()) return

          mutex.withLock {
              val now = System.currentTimeMillis()
              // ✅ dedup: نفس الرقم خلال نافذة 30 ثانية → تجاهل
              // ✅ dedup: same number within 30s window → ignore
              if (number == lastDedupNumber && (now - lastDedupTimeMs) < DEDUP_WINDOW_MS) return@withLock

              try {
                  ApiService.build(backendUrl).reportIncomingCall(
                      appSecret = apiKey,
                      body      = IncomingCallBody(callerPhone = number)
                  )
                  lastDedupNumber = number
                  lastDedupTimeMs = System.currentTimeMillis()
                  prefs.edit().putString(KEY_LAST_NUMBER, number).apply()
              } catch (e: Exception) {
                  e.printStackTrace()
              }
          }
      }

      // ─── فحص سجل المكالمات — يُستدعى من CallReceiver + ContentObserver ─────────
      // Check CallLog — called from CallReceiver + ContentObserver (backup path)
      suspend fun checkAndReportLatest(context: Context) {
          val appContext = context.applicationContext

          val hasPermission = ContextCompat.checkSelfPermission(
              appContext, Manifest.permission.READ_CALL_LOG
          ) == PackageManager.PERMISSION_GRANTED
          if (!hasPermission) return

          val latest = withContext(Dispatchers.IO) { readLatestCall(appContext) } ?: return
          val (id, number, type, callDate) = latest

          if (type == CallLog.Calls.OUTGOING_TYPE) return
          if (number.isNullOrBlank()) return

          mutex.withLock {
              val prefs  = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
              val lastId = prefs.getLong(KEY_LAST_ID, -1L)

              // ✅ dedup أولي: نفس ID في سجل المكالمات → تجاهل فوري
              // ✅ Primary dedup: same call log ID → immediate skip
              if (id != -1L && id == lastId) return@withLock

              val now = System.currentTimeMillis()

              // ✅ dedup ثانوي: نفس الرقم + نفس وقت المكالمة (يمنع تكرار نفس المكالمة)
              // ✅ Secondary dedup: same number + same call timestamp (blocks same-call duplicates)
              val lastDate = prefs.getLong(KEY_LAST_DATE, -1L)
              if (number == lastDedupNumber && callDate != -1L && callDate == lastDate) {
                  if (id != -1L) prefs.edit().putLong(KEY_LAST_ID, id).apply()
                  return@withLock
              }

              // ✅ dedup ثلاثي: نفس الرقم خلال نافذة زمنية
              // ✅ Tertiary dedup: same number within time window
              if (number == lastDedupNumber && (now - lastDedupTimeMs) < DEDUP_WINDOW_MS) {
                  if (id != -1L) prefs.edit().putLong(KEY_LAST_ID, id).apply()
                  return@withLock
              }

              val backendUrl = prefs.getString("backend_url", "") ?: return@withLock
              val apiKey     = prefs.getString("api_key",     "") ?: return@withLock
              if (backendUrl.isEmpty() || apiKey.isEmpty()) return@withLock

              try {
                  ApiService.build(backendUrl).reportIncomingCall(
                      appSecret = apiKey,
                      body      = IncomingCallBody(callerPhone = number)
                  )
                  lastDedupNumber = number
                  lastDedupTimeMs = System.currentTimeMillis()
                  prefs.edit()
                      .putLong(KEY_LAST_ID,   id)
                      .putLong(KEY_LAST_DATE, callDate)
                      .putString(KEY_LAST_NUMBER, number)
                      .apply()
              } catch (e: Exception) {
                  e.printStackTrace()
              }
          }
      }

      private data class CallEntry(val id: Long, val number: String?, val type: Int, val date: Long)

      private fun readLatestCall(context: Context): CallEntry? {
          var cursor: Cursor? = null
          return try {
              cursor = context.contentResolver.query(
                  CallLog.Calls.CONTENT_URI,
                  arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER,
                          CallLog.Calls.TYPE, CallLog.Calls.DATE),
                  null, null,
                  "${CallLog.Calls.DATE} DESC LIMIT 1"
              )
              if (cursor != null && cursor.moveToFirst()) {
                  val idIdx   = cursor.getColumnIndex(CallLog.Calls._ID)
                  val numIdx  = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                  val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                  val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                  CallEntry(
                      id     = if (idIdx   >= 0) cursor.getLong(idIdx)   else -1L,
                      number = if (numIdx  >= 0) cursor.getString(numIdx) else null,
                      type   = if (typeIdx >= 0) cursor.getInt(typeIdx)   else -1,
                      date   = if (dateIdx >= 0) cursor.getLong(dateIdx)  else -1L
                  )
              } else null
          } catch (e: Exception) {
              e.printStackTrace(); null
          } finally {
              cursor?.close()
          }
      }
  }
  