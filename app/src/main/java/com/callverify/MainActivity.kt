// ============================================================
  // ⚠️ تحذير | WARNING
  // 🇸🇦 لا تقم بتشفير رابط قاعدة البيانات أو إنشاء قاعدة بيانات جديدة.
  //    المالك (ناير) يتحمل كامل المسؤولية عن هذا الاختيار.
  // 🇬🇧 Do NOT encrypt the database URL or create a new database.
  //    The owner (Nayer) takes full responsibility for this decision.
  // ============================================================

  package com.callverify

  import android.Manifest
  import android.app.role.RoleManager
  import android.content.BroadcastReceiver
  import android.content.Context
  import android.content.Intent
  import android.content.IntentFilter
  import android.net.Uri
  import android.os.Build
  import android.os.Bundle
  import android.os.Handler
  import android.os.Looper
  import android.os.PowerManager
  import android.provider.Settings
  import android.telecom.TelecomManager
  import android.webkit.WebResourceRequest
  import android.webkit.WebView
  import android.webkit.WebViewClient
  import android.widget.TextView
  import androidx.appcompat.app.AppCompatActivity
  import androidx.core.app.ActivityCompat
  import com.google.android.material.switchmaterial.SwitchMaterial

  const val DEFAULT_BACKEND_URL = "https://skandar5288-callverify-backend.hf.space"
  const val DEFAULT_APP_SECRET  = "callverify-app-secret-2024"
  const val DEFAULT_WEBVIEW_URL = "https://skandar5288-callverify-backend.hf.space/?auto=Admin%40E2251217"
  const val PREF_AUTO_REJECT    = "auto_reject"

  class MainActivity : AppCompatActivity() {

      private lateinit var webView: WebView
      private lateinit var autoRejectSwitch: SwitchMaterial
      private lateinit var statusText: TextView
      private val mainHandler = Handler(Looper.getMainLooper())

      @Volatile private var isInForeground = false
      @Volatile private var pendingRefresh  = false

      // ── عند رصد مكالمة: انتظر 300ms فقط (السيرفر أكّد الاستلام قبل البث)
      // On call detected: wait 300ms only (server already confirmed before broadcast)
      private val callDetectedReceiver = object : BroadcastReceiver() {
          override fun onReceive(context: Context?, intent: Intent?) {
              mainHandler.post {
                  if (isInForeground) {
                      mainHandler.postDelayed({ safeReload() }, 300L)
                  } else {
                      pendingRefresh = true
                  }
              }
          }
      }

      // تحديث دوري كل 30 ثانية — ضمان إضافي فقط
      // Periodic refresh every 30s — extra safety net only
      private val periodicRefresher = object : Runnable {
          override fun run() {
              if (!isFinishing) {
                  if (isInForeground) safeReload()
                  mainHandler.postDelayed(this, 30_000L)
              }
          }
      }

      // ── تحديث بـ timestamp في الرابط لضمان جلب أحدث بيانات من السيرفر ──────
      // Reload with timestamp param to bust server-side cache and get fresh data
      private fun safeReload() {
          try {
              if (!isFinishing && !isDestroyed) {
                  val freshUrl = "$DEFAULT_WEBVIEW_URL&_t=${System.currentTimeMillis()}"
                  webView.loadUrl(freshUrl)
              }
          } catch (_: Exception) {}
      }

      override fun onCreate(savedInstanceState: Bundle?) {
          super.onCreate(savedInstanceState)
          setContentView(R.layout.activity_main)

          val prefs = getSharedPreferences("callverify", Context.MODE_PRIVATE)

          // ✅ الرفض التلقائي دائماً مفعّل ومثبّت — لا يمكن إيقافه
          // ✅ Auto-reject always ON and locked — cannot be disabled
          prefs.edit()
              .putString("backend_url", DEFAULT_BACKEND_URL)
              .putString("api_key",     DEFAULT_APP_SECRET)
              .putBoolean(PREF_AUTO_REJECT, true)
              .apply()
          CallVerifyInCallService.autoRejectEnabled = true

          // ─── Toggle الرفض التلقائي — مثبّت دائماً (للعرض فقط) ───────────────
          autoRejectSwitch = findViewById(R.id.autoRejectSwitch)
          statusText       = findViewById(R.id.autoRejectStatus)

          autoRejectSwitch.isChecked = true
          autoRejectSwitch.isEnabled = false   // مقفول — لا يمكن للمستخدم تغييره
          statusText.text = "🔴 الوضع الحالي: رفض تلقائي فعّال دائماً"

          // ─── الـ WebView ───────────────────────────────────────────────────────
          webView = findViewById(R.id.webView)
          webView.settings.apply {
              javaScriptEnabled    = true
              domStorageEnabled    = true
              loadWithOverviewMode = true
              useWideViewPort      = true
              databaseEnabled      = true
              // ✅ وضع افتراضي — يتحكم السيرفر في الـ cache عبر headers
              // ✅ Default mode — server controls cache via its own HTTP headers
              cacheMode            = android.webkit.WebSettings.LOAD_DEFAULT
          }
          // مسح أي cache قديم من السيرفر السابق عند أول تشغيل
          webView.clearCache(true)
          webView.webViewClient = object : WebViewClient() {
              override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                  val url = request.url.toString()
                  return if (url.contains("skandar5288-callverify-backend.hf.space")) false
                  else { startActivity(Intent(Intent.ACTION_VIEW, request.url)); true }
              }
          }
          // تحميل الصفحة مع timestamp لجلب أحدث بيانات فور الفتح
          webView.loadUrl("$DEFAULT_WEBVIEW_URL&_t=${System.currentTimeMillis()}")

          // ─── تسجيل المستقبل في onCreate ليبقى نشطاً دائماً ──────────────────
          val filter = IntentFilter(ACTION_CALL_DETECTED)
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
              registerReceiver(callDetectedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
          else
              registerReceiver(callDetectedReceiver, filter)

          // ─── خدمة المراقبة الخلفية ────────────────────────────────────────────
          try {
              val svc = Intent(this, CallService::class.java)
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
              else startService(svc)
          } catch (e: Exception) { e.printStackTrace() }

          ActivityCompat.requestPermissions(
              this,
              arrayOf(
                  Manifest.permission.READ_PHONE_STATE,
                  Manifest.permission.READ_CALL_LOG,
                  Manifest.permission.POST_NOTIFICATIONS
              ),
              1001
          )

          requestIgnoreBatteryOptimizations()
          requestDefaultDialerRole()

          // ─── تشغيل المحدّث الدوري ─────────────────────────────────────────────
          mainHandler.postDelayed(periodicRefresher, 30_000L)
      }

      override fun onResume() {
          super.onResume()
          isInForeground = true
          if (pendingRefresh) {
              pendingRefresh = false
              mainHandler.postDelayed({ safeReload() }, 300L)
          }
      }

      override fun onPause() {
          super.onPause()
          isInForeground = false
      }

      override fun onDestroy() {
          super.onDestroy()
          try { unregisterReceiver(callDetectedReceiver) } catch (_: Exception) {}
          mainHandler.removeCallbacks(periodicRefresher)
      }

      override fun onBackPressed() {
          if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
      }

      private fun requestDefaultDialerRole() {
          try {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                  val rm = getSystemService(Context.ROLE_SERVICE) as RoleManager
                  if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER))
                      startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQUEST_DEFAULT_DIALER)
              } else {
                  val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                  if (tm.defaultDialerPackage != packageName)
                      startActivityForResult(
                          Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                              putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                          }, REQUEST_DEFAULT_DIALER
                      )
              }
          } catch (e: Exception) { e.printStackTrace() }
      }

      override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
          super.onActivityResult(requestCode, resultCode, data)
          if (requestCode == REQUEST_DEFAULT_DIALER) safeReload()
      }

      private fun requestIgnoreBatteryOptimizations() {
          try {
              val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
              if (!pm.isIgnoringBatteryOptimizations(packageName))
                  startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                      data = Uri.parse("package:$packageName")
                  })
          } catch (e: Exception) { e.printStackTrace() }
      }

      companion object {
          private const val REQUEST_DEFAULT_DIALER = 2002
      }
  }
