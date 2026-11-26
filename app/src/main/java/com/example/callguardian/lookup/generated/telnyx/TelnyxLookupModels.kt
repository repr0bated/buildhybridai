@file:Suppress("unused", "RemoveRedundantQualifierName")
// GENERATED from the official OpenAPI specification at openapi/telnyx_number_lookup.json (subset of team-telnyx/openapi spec3.json).
// Do not hand-edit. Re-run scripts/generate-lookup-models.py.

package com.example.callguardian.lookup.generated.telnyx

import com.squareup.moshi.Json


data class NumberLookupResponse(
    @Json(name = "data") val data_: NumberLookupRecord? = null,
)

data class Errors(
    @Json(name = "errors") val errors: List<Error>? = null,
)

data class Error(
    @Json(name = "code") val code: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "detail") val detail: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "source") val source: ErrorSource? = null,
    @Json(name = "meta") val meta: ErrorMeta? = null,
)

data class ErrorSource(
    @Json(name = "pointer") val pointer: String? = null,
    @Json(name = "parameter") val parameter: String? = null,
)

class ErrorMeta

data class NumberLookupRecord(
    @Json(name = "record_type") val recordType: String? = null,
    @Json(name = "country_code") val countryCode: String? = null,
    @Json(name = "national_format") val nationalFormat: String? = null,
    @Json(name = "phone_number") val phoneNumber: String? = null,
    @Json(name = "fraud") val fraud: String? = null,
    @Json(name = "carrier") val carrier: Carrier? = null,
    @Json(name = "caller_name") val callerName: CallerName? = null,
    @Json(name = "portability") val portability: Portability? = null,
    @Json(name = "nnid_override") val nnidOverride: NumberLookupRecordNnidOverride? = null,
    @Json(name = "valid_number") val validNumber: Boolean? = null,
    @Json(name = "enhanced_caller_name") val enhancedCallerName: EnhancedCallerName? = null,
    @Json(name = "caller_identity") val callerIdentity: CallerIdentity? = null,
)

data class NumberLookupRecordNnidOverride(
    @Json(name = "nnid") val nnid: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "type") val type_: String? = null,
)

data class CallerIdentity(
    @Json(name = "name") val name: String? = null,
    @Json(name = "address") val address: NumberLookupAddress? = null,
    @Json(name = "confidence_score") val confidenceScore: Double? = null,
    @Json(name = "error_code") val errorCode: String? = null,
    @Json(name = "phone_number") val phoneNumber: String? = null,
    @Json(name = "entity_type") val entityType: String? = null,
    @Json(name = "first_name") val firstName: String? = null,
    @Json(name = "middle_name") val middleName: String? = null,
    @Json(name = "last_name") val lastName: String? = null,
    @Json(name = "alternate_names") val alternateNames: List<String>? = null,
    @Json(name = "industry") val industry: List<String>? = null,
    @Json(name = "entity_linked_since") val entityLinkedSince: String? = null,
    @Json(name = "is_valid") val isValid: Boolean? = null,
    @Json(name = "country_calling_code") val countryCallingCode: String? = null,
    @Json(name = "line_type") val lineType: String? = null,
    @Json(name = "carrier") val carrier: String? = null,
    @Json(name = "is_prepaid") val isPrepaid: Boolean? = null,
    @Json(name = "is_commercial") val isCommercial: Boolean? = null,
    @Json(name = "warnings") val warnings: List<String>? = null,
    @Json(name = "demographics") val demographics: CallerIdentityDemographics? = null,
)

data class CallerIdentityDemographics(
    @Json(name = "age_range") val ageRange: String? = null,
    @Json(name = "gender") val gender: String? = null,
)

data class NumberLookupAddress(
    @Json(name = "street_line_1") val streetLine1: String? = null,
    @Json(name = "street_line_2") val streetLine2: String? = null,
    @Json(name = "city") val city: String? = null,
    @Json(name = "state_code") val stateCode: String? = null,
    @Json(name = "postal_code") val postalCode: String? = null,
    @Json(name = "zip4") val zip4: String? = null,
    @Json(name = "country_code") val countryCode: String? = null,
    @Json(name = "location_type") val locationType: String? = null,
    @Json(name = "delivery_point") val deliveryPoint: String? = null,
    @Json(name = "is_active") val isActive: Boolean? = null,
    @Json(name = "entity_linked_since") val entityLinkedSince: String? = null,
)

data class Portability(
    @Json(name = "lrn") val lrn: String? = null,
    @Json(name = "ported_status") val portedStatus: String? = null,
    @Json(name = "ported_date") val portedDate: String? = null,
    @Json(name = "ocn") val ocn: String? = null,
    @Json(name = "line_type") val lineType: String? = null,
    @Json(name = "spid") val spid: String? = null,
    @Json(name = "spid_carrier_name") val spidCarrierName: String? = null,
    @Json(name = "spid_carrier_type") val spidCarrierType: String? = null,
    @Json(name = "altspid") val altspid: String? = null,
    @Json(name = "altspid_carrier_name") val altspidCarrierName: String? = null,
    @Json(name = "altspid_carrier_type") val altspidCarrierType: String? = null,
    @Json(name = "city") val city: String? = null,
    @Json(name = "state") val state: String? = null,
)

data class EnhancedCallerName(
    @Json(name = "caller_name") val callerName: String? = null,
    @Json(name = "entity_type") val entityType: String? = null,
    @Json(name = "error_code") val errorCode: String? = null,
)

data class CallerName(
    @Json(name = "caller_name") val callerName: String? = null,
    @Json(name = "error_code") val errorCode: String? = null,
)

data class Carrier(
    @Json(name = "mobile_country_code") val mobileCountryCode: String? = null,
    @Json(name = "mobile_network_code") val mobileNetworkCode: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "type") val type_: String? = null,
    @Json(name = "error_code") val errorCode: String? = null,
    @Json(name = "normalized_carrier") val normalizedCarrier: String? = null,
)

/** Request parameters generated from the official OpenAPI operation `LookupNumber`. */
data class LookupNumberRequest(
    @Json(name = "phone_number") val phoneNumber: String? = null,
    @Json(name = "type") val type_: List<String>? = null,
)
