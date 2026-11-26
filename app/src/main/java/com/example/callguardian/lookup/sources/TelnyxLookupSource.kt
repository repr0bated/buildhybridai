package com.example.callguardian.lookup.sources

import com.example.callguardian.lookup.LookupProvider
import com.example.callguardian.lookup.LookupRateLimited
import com.example.callguardian.lookup.LookupSecrets
import com.example.callguardian.lookup.ReverseLookup
import com.example.callguardian.lookup.ReverseLookupSource
import com.example.callguardian.lookup.generated.telnyx.LookupNumberRequest
import com.example.callguardian.lookup.generated.telnyx.NumberLookupResponse
import com.example.callguardian.model.LookupResult
import com.squareup.moshi.Moshi
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Telnyx Number Lookup. Request/response types are generated from the official
 * Telnyx OpenAPI spec (GET /number_lookup/{phone_number}). Requests carrier
 * and caller-name. The API key is never logged.
 */
@Singleton
class TelnyxLookupSource @Inject constructor(
    private val secrets: LookupSecrets,
    client: OkHttpClient,
    moshi: Moshi,
) : ReverseLookupSource {

    private val http = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val adapter = moshi.adapter(NumberLookupResponse::class.java)

    override val id: String = LookupProvider.TELNYX.id
    override val displayName: String = LookupProvider.TELNYX.displayName

    override suspend fun lookup(phoneNumber: String): LookupResult? {
        val key = secrets.telnyxKey()
        if (key.isBlank()) return null
        val request = LookupNumberRequest(
            phoneNumber = ReverseLookup.toE164(phoneNumber) ?: phoneNumber,
            type_ = TYPES,
        )
        val number = request.phoneNumber ?: return null
        val url = BASE.toHttpUrl().newBuilder()
            .addPathSegment("number_lookup")
            .addPathSegment(number)
        request.type_?.forEach { url.addQueryParameter("type", it) }
        val call = Request.Builder()
            .url(url.build())
            .header("Authorization", "Bearer $key")
            .get()
            .build()
        val response = runCatching { http.newCall(call).execute() }
            .onFailure { Timber.w("Telnyx lookup request failed") }
            .getOrNull() ?: return null
        response.use { resp ->
            if (resp.code == 429) throw LookupRateLimited(ReverseLookup.retryAfterMs(resp.header("Retry-After")))
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val payload = runCatching { adapter.fromJson(body) }
                .onFailure { Timber.w("Failed to parse Telnyx lookup response") }
                .getOrNull() ?: return null
            val data = payload.data_ ?: return null
            val cnam = data.callerName?.callerName?.trim()?.takeIf { it.isNotEmpty() }
            val carrier = data.carrier?.normalizedCarrier?.takeIf { it.isNotBlank() } ?: data.carrier?.name
            return LookupResult(
                phoneNumber = data.phoneNumber ?: number,
                displayName = cnam,
                carrier = carrier,
                region = data.countryCode,
                lineType = data.carrier?.type_,
                valid = data.validNumber,
                source = displayName,
                sourceId = id,
                rawPayload = mapOf(
                    "valid" to data.validNumber,
                    "caller_name" to cnam,
                    "carrier" to carrier,
                    "line_type" to data.carrier?.type_,
                    "country_code" to data.countryCode,
                    "national_format" to data.nationalFormat,
                ),
            )
        }
    }

    companion object {
        private const val BASE = "https://api.telnyx.com/v2"
        private val TYPES = listOf("carrier", "caller-name")
    }
}
