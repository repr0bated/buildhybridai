package com.example.callguardian.lookup.sources

import com.example.callguardian.lookup.LookupProvider
import com.example.callguardian.lookup.LookupRateLimited
import com.example.callguardian.lookup.LookupSecrets
import com.example.callguardian.lookup.ReverseLookup
import com.example.callguardian.lookup.ReverseLookupSource
import com.example.callguardian.lookup.generated.twilio.FetchPhoneNumberRequest
import com.example.callguardian.lookup.generated.twilio.LookupResponse
import com.example.callguardian.model.LookupResult
import com.squareup.moshi.Moshi
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Twilio Lookup v2. Request/response types are generated from the official
 * twilio-oai Lookup v2 OpenAPI spec. Requests caller_name and line_type_intelligence.
 * Credentials are never logged.
 */
@Singleton
class TwilioLookupSource @Inject constructor(
    private val secrets: LookupSecrets,
    client: OkHttpClient,
    moshi: Moshi,
) : ReverseLookupSource {

    private val http = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val adapter = moshi.adapter(LookupResponse::class.java)

    override val id: String = LookupProvider.TWILIO.id
    override val displayName: String = LookupProvider.TWILIO.displayName

    override suspend fun lookup(phoneNumber: String): LookupResult? {
        val sid = secrets.twilioSid()
        val token = secrets.twilioToken()
        if (sid.isBlank() || token.isBlank()) return null
        val request = FetchPhoneNumberRequest(
            phoneNumber = ReverseLookup.toE164(phoneNumber) ?: phoneNumber,
            fields = FIELDS,
        )
        val number = request.phoneNumber ?: return null
        val url = BASE.toHttpUrl().newBuilder()
            .addPathSegment("v2")
            .addPathSegment("PhoneNumbers")
            .addPathSegment(number)
            .addQueryParameter("Fields", request.fields)
            .build()
        val call = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(sid, token))
            .get()
            .build()
        val response = runCatching { http.newCall(call).execute() }
            .onFailure { Timber.w("Twilio lookup request failed") }
            .getOrNull() ?: return null
        response.use { resp ->
            if (resp.code == 429) throw LookupRateLimited(ReverseLookup.retryAfterMs(resp.header("Retry-After")))
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val payload = runCatching { adapter.fromJson(body) }
                .onFailure { Timber.w("Failed to parse Twilio lookup response") }
                .getOrNull() ?: return null
            val cnam = payload.callerName?.callerName?.trim()?.takeIf { it.isNotEmpty() }
            val lti = payload.lineTypeIntelligence
            return LookupResult(
                phoneNumber = payload.phoneNumber ?: number,
                displayName = cnam,
                carrier = lti?.carrierName,
                region = payload.countryCode,
                lineType = lti?.type_,
                valid = payload.valid,
                source = displayName,
                sourceId = id,
                rawPayload = mapOf(
                    "valid" to payload.valid,
                    "caller_name" to cnam,
                    "caller_type" to payload.callerName?.callerType,
                    "carrier_name" to lti?.carrierName,
                    "line_type" to lti?.type_,
                    "country_code" to payload.countryCode,
                    "national_format" to payload.nationalFormat,
                ),
            )
        }
    }

    companion object {
        private const val BASE = "https://lookups.twilio.com"
        private const val FIELDS = "caller_name,line_type_intelligence"

    }
}
