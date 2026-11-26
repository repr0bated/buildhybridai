@file:Suppress("unused", "RemoveRedundantQualifierName")
// GENERATED from the official OpenAPI specification at openapi/twilio_lookups_v2.json (twilio/twilio-oai).
// Do not hand-edit. Re-run scripts/generate-lookup-models.py.

package com.example.callguardian.lookup.generated.twilio

import com.squareup.moshi.Json


data class LookupResponse(
    @Json(name = "calling_country_code") val callingCountryCode: String? = null,
    @Json(name = "country_code") val countryCode: String? = null,
    @Json(name = "phone_number") val phoneNumber: String? = null,
    @Json(name = "national_format") val nationalFormat: String? = null,
    @Json(name = "valid") val valid: Boolean? = null,
    @Json(name = "validation_errors") val validationErrors: List<ValidationError>? = null,
    @Json(name = "caller_name") val callerName: CallerNameInfo? = null,
    @Json(name = "sim_swap") val simSwap: SimSwapInfo? = null,
    @Json(name = "call_forwarding") val callForwarding: CallForwardingInfo? = null,
    @Json(name = "line_type_intelligence") val lineTypeIntelligence: LineTypeIntelligenceInfo? = null,
    @Json(name = "line_status") val lineStatus: LineStatusInfo? = null,
    @Json(name = "identity_match") val identityMatch: IdentityMatchInfo? = null,
    @Json(name = "reassigned_number") val reassignedNumber: ReassignedNumberInfo? = null,
    @Json(name = "sms_pumping_risk") val smsPumpingRisk: SmsPumpingRiskInfo? = null,
    @Json(name = "url") val url: String? = null,
)

data class CallerNameInfo(
    @Json(name = "caller_name") val callerName: String? = null,
    @Json(name = "caller_type") val callerType: String? = null,
    @Json(name = "error_code") val errorCode: Int? = null,
)

data class LineTypeIntelligenceInfo(
    @Json(name = "mobile_country_code") val mobileCountryCode: String? = null,
    @Json(name = "mobile_network_code") val mobileNetworkCode: String? = null,
    @Json(name = "carrier_name") val carrierName: String? = null,
    @Json(name = "type") val type_: String? = null,
    @Json(name = "error_code") val errorCode: Int? = null,
)

enum class ValidationError {
    TOO_SHORT,
    TOO_LONG,
    INVALID_BUT_POSSIBLE,
    INVALID_COUNTRY_CODE,
    INVALID_LENGTH,
    NOT_A_NUMBER,
}

data class SimSwapInfo(
    @Json(name = "last_sim_swap") val lastSimSwap: LastSimSwapInfo? = null,
    @Json(name = "carrier_name") val carrierName: String? = null,
    @Json(name = "mobile_country_code") val mobileCountryCode: String? = null,
    @Json(name = "mobile_network_code") val mobileNetworkCode: String? = null,
    @Json(name = "error_code") val errorCode: Int? = null,
)

data class LastSimSwapInfo(
    @Json(name = "last_sim_swap_date") val lastSimSwapDate: String? = null,
    @Json(name = "swapped_period") val swappedPeriod: String? = null,
    @Json(name = "swapped_in_period") val swappedInPeriod: Boolean? = null,
)

data class CallForwardingInfo(
    @Json(name = "call_forwarding_enabled") val callForwardingEnabled: Boolean? = null,
    @Json(name = "error_code") val errorCode: Int? = null,
)

data class LineStatusInfo(
    @Json(name = "status") val status: String? = null,
    @Json(name = "error_code") val errorCode: Int? = null,
)

data class IdentityMatchInfo(
    @Json(name = "first_name_match") val firstNameMatch: String? = null,
    @Json(name = "last_name_match") val lastNameMatch: String? = null,
    @Json(name = "address_lines_match") val addressLinesMatch: String? = null,
    @Json(name = "city_match") val cityMatch: String? = null,
    @Json(name = "state_match") val stateMatch: String? = null,
    @Json(name = "postal_code_match") val postalCodeMatch: String? = null,
    @Json(name = "address_country_match") val addressCountryMatch: String? = null,
    @Json(name = "national_id_match") val nationalIdMatch: String? = null,
    @Json(name = "date_of_birth_match") val dateOfBirthMatch: String? = null,
    @Json(name = "summary_score") val summaryScore: Int? = null,
    @Json(name = "error_code") val errorCode: Int? = null,
    @Json(name = "error_message") val errorMessage: String? = null,
)

data class ReassignedNumberInfo(
    @Json(name = "last_verified_date") val lastVerifiedDate: String? = null,
    @Json(name = "is_number_reassigned") val isNumberReassigned: String? = null,
    @Json(name = "error_code") val errorCode: String? = null,
)

data class SmsPumpingRiskInfo(
    @Json(name = "carrier_risk_category") val carrierRiskCategory: String? = null,
    @Json(name = "number_blocked") val numberBlocked: Boolean? = null,
    @Json(name = "number_blocked_date") val numberBlockedDate: String? = null,
    @Json(name = "number_blocked_last_3_months") val numberBlockedLast3Months: Boolean? = null,
    @Json(name = "sms_pumping_risk_score") val smsPumpingRiskScore: Int? = null,
    @Json(name = "error_code") val errorCode: Int? = null,
)

/** Request parameters generated from the official OpenAPI operation `FetchPhoneNumber`. */
data class FetchPhoneNumberRequest(
    @Json(name = "PhoneNumber") val phoneNumber: String? = null,
    @Json(name = "Fields") val fields: String? = null,
    @Json(name = "CountryCode") val countryCode: String? = null,
    @Json(name = "FirstName") val firstName: String? = null,
    @Json(name = "LastName") val lastName: String? = null,
    @Json(name = "AddressLine1") val addressLine1: String? = null,
    @Json(name = "AddressLine2") val addressLine2: String? = null,
    @Json(name = "City") val city: String? = null,
    @Json(name = "State") val state: String? = null,
    @Json(name = "PostalCode") val postalCode: String? = null,
    @Json(name = "AddressCountryCode") val addressCountryCode: String? = null,
    @Json(name = "NationalId") val nationalId: String? = null,
    @Json(name = "DateOfBirth") val dateOfBirth: String? = null,
    @Json(name = "LastVerifiedDate") val lastVerifiedDate: String? = null,
    @Json(name = "VerificationSid") val verificationSid: String? = null,
    @Json(name = "PartnerSubId") val partnerSubId: String? = null,
)
