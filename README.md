# CallVerify Android App

## ⚠️ تحذير | WARNING

**🇸🇦** لا تقم بتشفير رابط قاعدة البيانات أو إنشاء قاعدة بيانات جديدة.  
المالك (ناير) يتحمل كامل المسؤولية عن هذا الاختيار.

**🇬🇧** Do NOT encrypt the database URL or create a new database.  
The owner (Nayer) takes full responsibility for this decision.

---

## كيفية البناء | How to Build

1. افتح المشروع في **Android Studio** | Open the project in Android Studio
2. **Build > Generate Signed APK**
3. ثبّته على الهاتف الذي يستقبل الاتصالات | Install on the phone that receives calls
4. افتح التطبيق وأدخل:
   - **رابط الـ Backend**: رابط Replit الخاص بك
   - **APP_SECRET_KEY**: `callverify-app-secret-2024` (الافتراضي | default)
5. امنح صلاحيات الهاتف (READ_PHONE_STATE, READ_CALL_LOG)

## الإعدادات الافتراضية | Default Configuration

| المتغير | القيمة الافتراضية | كيفية التغيير |
|---------|------------------|---------------|
| APP_SECRET_KEY | `callverify-app-secret-2024` | متغير بيئة ADMIN_PASSWORD في Backend |
| ADMIN_PASSWORD | `admin123` | متغير بيئة ADMIN_PASSWORD |
| RECEIVING_PHONE_NUMBER | `+249000000000` | متغير بيئة RECEIVING_PHONE_NUMBER |

## الهيكل | Structure

```
android-app/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/callverify/
│   │   ├── MainActivity.kt    ← شاشة الإعدادات
│   │   ├── CallReceiver.kt    ← يلتقط الاتصالات
│   │   ├── ApiService.kt      ← يُرسل للـ Backend
│   │   └── BootReceiver.kt    ← يبدأ عند إعادة التشغيل
│   └── res/layout/
│       └── activity_main.xml  ← الواجهة
└── build.gradle
```
