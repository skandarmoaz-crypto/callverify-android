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

  const val DEFAULT_BACKEND_URL = "https://1e2fe7c1-eeda-40ad-a0b7-1d5d02de9dcd-00-11rtmnoymwhei.worf.replit.dev"
  const val DEFAULT_APP_SECRET  = "897829ef954df7f267d2b207368ecedf25062a7880b7136ed41368aa16de9232"
  const val DEFAULT_WEBVIEW_URL = "https://1e2fe7c1-eeda-40ad-a0b7-1d5d02de9dcd-00-11rtmnoymwhei.worf.replit.dev/?auto=Admin%40E2251217"
  const val PREF_AUTO_REJECT    = "auto_reject"

  class MainActivity : AppCompatActivity() {

      private lateinit var webView: WebView
      private lateinit var autoRejectSwitch: SwitchMaterial
      private lateinit var statusText: TextView
      private val mainHandler = Handler(Looper.getMainLooper())

      // ══════════════════════════════════════════════════════════════════════════
      // 🔧 الإصلاح الجذري لمشكلة عدم تحديث Dashboard:
      //
      //    المشكلة القديمة: المستقبل مسجّل في onResume/onPause فقط →
      //    لما التطبيق في الخلفية يُلغى تسجيله → الـ broadcast يضيع.
      //
      //    الحل: نسجّل المستقبل في onCreate وننزعه في onDestroy فقط →
      //    يعمل دائماً حتى لو التطبيق في الخلفية.
      //
      // 🔧 Root fix for Dashboard not updating:
      //    Old: receiver registered in onResume/onPause → when app backgrounded
      //         receiver is unregistered → broadcast is missed.
      //    Fix: register in onCreate, unregister in onDestroy only →
      //         always active regardless of app state.
      // ══════════════════════════════════════════════════════════════════════════

      // هل التطبيق في المقدمة؟ — نستخدمه لتحديد طريقة التحديث
      // Is app in foreground? — used to choose refresh strategy
      @Volatile private var isInForeground = false

      // هل يوجد تحديث معلّق؟ — يُطبَّق فور العودة للمقدمة
      // Is a refresh pending? — applied immediately when returning to foreground
      @Volatile private var pendingRefresh  = false

      private val callDetectedReceiver = object : BroadcastReceiver() {
          override fun onReceive(context: Context?, intent: Intent?) {
              mainHandler.post {
                  if (isInForeground) {
                      // التطبيق مرئي → تحديث فوري بعد 300ms (تقليل من 1500ms)
                      // App visible → refresh after 300ms (reduced from 1500ms)
                      mainHandler.postDelayed({ safeReload() }, 300L)
                  } else {
                      // التطبيق في الخلفية → علّم بأن هناك تحديث معلّق
                      // App in background → mark pending refresh
                      pendingRefresh = true
                  }
              }
          }
      }

      // تحديث دوري خفيف كل 15 ثانية (بدل 60) — ضمان إضافي
      // Light periodic refresh every 15s (instead of 60) — extra guarantee
      private val periodicRefresher = object : Runnable {
          override fun run() {
              if (!isFinishing && isInForeground) {
                  safeReload()
                  mainHandler.postDelayed(this, 15_000L)
              } else if (!isFinishing) {
                  mainHandler.postDelayed(this, 15_000L)
              }
          }
      }

      private fun safeReload() {
          try {
              if (!isFinishing && !isDestroyed) webView.reload()
          } catch (_: Exception) {}
      }

      override fun onCreate(savedInstanceState: Bundle?) {
          super.onCreate(savedInstanceState)
          setContentView(R.layout.activity_main)

          val prefs = getSharedPreferences("callverify", Context.MODE_PRIVATE)
          prefs.edit()
              .putString("backend_url", DEFAULT_BACKEND_URL)
              .putString("api_key",     DEFAULT_APP_SECRET)
              .apply()

          // ─── Toggle الرفض التلقائي ─────────────────────────────────────────────
          autoRejectSwitch = findViewById(R.id.autoRejectSwitch)
          statusText       = findViewById(R.id.autoRejectStatus)

          val savedValue = prefs.getBoolean(PREF_AUTO_REJECT, false)
          autoRejectSwitch.isChecked = savedValue
          CallVerifyInCallService.autoRejectEnabled = savedValue
          updateStatusText(savedValue)

          autoRejectSwitch.setOnCheckedChangeListener { _, isChecked ->
              prefs.edit().putBoolean(PREF_AUTO_REJECT, isChecked).apply()
              CallVerifyInCallService.autoRejectEnabled = isChecked
              updateStatusText(isChecked)
          }

          // ─── الـ WebView ───────────────────────────────────────────────────────
          webView = findViewById(R.id.webView)
          webView.settings.apply {
              javaScriptEnabled    = true
              domStorageEnabled    = true
              loadWithOverviewMode = true
              useWideViewPort      = true
              databaseEnabled      = true
          }
          webView.webViewClient = object : WebViewClient() {
              override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                  val url = request.url.toString()
                  return if (url.contains("34e8dcfa-776c-4884-9e86-1132652e52f0-00-v4m3yrps4sic.riker.replit.dev")) false
                  else { startActivity(Intent(Intent.ACTION_VIEW, request.url)); true }
              }
          }
          webView.loadUrl(DEFAULT_WEBVIEW_URL)

          // ─── تسجيل المستقبل هنا (onCreate) ليبقى نشطاً دائماً ────────────────
          // Register receiver here (onCreate) to stay active always
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
          mainHandler.postDelayed(periodicRefresher, 15_000L)
      }

      private fun updateStatusText(autoRejectOn: Boolean) {
          statusText.text = if (autoRejectOn)
              "🔴 الوضع الحالي: رفض تلقائي فعّال — يتطلب تطبيق الهاتف الافتراضي"
          else
              "⚪ الوضع الحالي: مراقبة فقط"
      }

      override fun onResume() {
          super.onResume()
          isInForeground = true
          // طبّق التحديث المعلّق فوراً إن وجد
          // Apply pending refresh immediately if any
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
          // ← إلغاء تسجيل المستقبل هنا فقط (وليس في onPause)
          // ← Unregister receiver here only (NOT in onPause)
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
  