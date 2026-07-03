// ============================================================
// ⚠️ تحذير | WARNING
// ------------------------------------------------------------
// 🇸🇦 لا تقم بتشفير رابط قاعدة البيانات أو إنشاء قاعدة بيانات جديدة.
//    المالك (ناير) يتحمل كامل المسؤولية عن هذا الاختيار.
//    رابط Backend يُدخله المستخدم في إعدادات التطبيق.
//
// 🇬🇧 Do NOT encrypt the database URL or create a new database.
//    The owner (Nayer) takes full responsibility for this decision.
//    Backend URL is entered by the user in app settings.
// ============================================================

package com.callverify

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

data class IncomingCallBody(val callerPhone: String)
data class IncomingCallResponse(val matched: Boolean)

interface CallVerifyApi {
    @POST("api/incoming-call") // بدون / في البداية — Retrofit يضيفها | Retrofit adds it from baseUrl
    suspend fun reportIncomingCall(
        @Header("X-App-Secret") appSecret: String, // نفس الـ header المتوقع في الـ Backend
        @Body body: IncomingCallBody
    ): IncomingCallResponse
}

object ApiService {
    fun build(baseUrl: String): CallVerifyApi {
        // تأكد أن baseUrl تنتهي بـ / وإلا Retrofit يرمي استثناء
        // Ensure baseUrl ends with / otherwise Retrofit throws an exception
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(normalizedUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CallVerifyApi::class.java)
    }
}
