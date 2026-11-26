// To parse the JSON, install kotlin's serialization plugin and do:
//
// val json             = Json { allowStructuredMapKeys = true }
// val syncContent      = json.parse(SyncContent.serializer(), jsonString)
// val syncPrivateFlag  = json.parse(SyncPrivateFlag.serializer(), jsonString)
// val syncReceipt      = json.parse(SyncReceipt.serializer(), jsonString)
// val syncSenderRule   = json.parse(SyncSenderRule.serializer(), jsonString)
// val syncSettings     = json.parse(SyncSettings.serializer(), jsonString)
// val syncSubscription = json.parse(SyncSubscription.serializer(), jsonString)
// val syncVerdict      = json.parse(SyncVerdict.serializer(), jsonString)

package io.github.repr0bated.callguardian.sync.generated

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*

/**
 * Message text and model-written text for one item. Written ONLY while
 * SyncSettings.syncMessageContent is true (enforced by Firestore rules).
 */
@Serializable
data class SyncContent (
    val advice: String? = null,
    val body: String? = null,

    /**
     * Tombstone: the item was removed. Documents are kept so every device learns about the
     * delete.
     */
    val deleted: Boolean? = null,

    /**
     * Stable random id of the device that wrote this document.
     */
    @SerialName("deviceId")
    val deviceID: String,

    /**
     * Content-bearing method input fields removed from the receipt (reason, warning,
     * onDeviceOpinion).
     */
    val inputText: JsonObject? = null,

    @SerialName("itemId")
    val itemID: String,

    val kind: Kind,
    val reasons: List<String>? = null,
    val redactedText: String? = null,
    val summary: String? = null,

    /**
     * Epoch millis of the last change. Last writer wins.
     */
    val updatedAt: Long
)

@Serializable
enum class Kind(val value: String) {
    @SerialName("call") Call("call"),
    @SerialName("receipt") Receipt("receipt"),
    @SerialName("sms") SMS("sms"),
    @SerialName("verdict") Verdict("verdict");
}

/**
 * Marks a call, text, receipt or verdict as private (blurred until unlocked on each device).
 */
@Serializable
data class SyncPrivateFlag (
    /**
     * Tombstone: the item was removed. Documents are kept so every device learns about the
     * delete.
     */
    val deleted: Boolean? = null,

    /**
     * Stable random id of the device that wrote this document.
     */
    @SerialName("deviceId")
    val deviceID: String,

    val isPrivate: Boolean,

    @SerialName("itemId")
    val itemID: String,

    /**
     * Document id: <kind>_<itemId>.
     */
    val key: String,

    val kind: Kind,

    /**
     * Epoch millis of the last change. Last writer wins.
     */
    val updatedAt: Long
)

/**
 * One typed-method receipt. Contains no message text.
 */
@Serializable
data class SyncReceipt (
    val alias: String,
    val autoApplied: Boolean? = null,
    val category: Category,
    val channel: Channel,
    val confidence: Long,
    val createdAt: Long,
    val decidedBy: DecidedBy,

    /**
     * Tombstone: the item was removed. Documents are kept so every device learns about the
     * delete.
     */
    val deleted: Boolean? = null,

    /**
     * Stable random id of the device that wrote this document.
     */
    @SerialName("deviceId")
    val deviceID: String,

    val dispatch: Dispatch? = null,

    /**
     * <deviceId>-<localId>; also the Firestore document id.
     */
    val id: String,

    /**
     * The typed method input as executed, with content-bearing fields (reason, redactedText,
     * onDeviceOpinion, warning) removed. Full values live in content/{id} when content sync is
     * on.
     */
    val input: JsonObject? = null,

    @SerialName("methodId")
    val methodID: String,

    val model: String? = null,

    /**
     * Which app wrote it.
     */
    val origin: Origin,

    /**
     * The typed method output.
     */
    val output: JsonObject? = null,

    val riskLevel: RiskLevel,

    /**
     * Fixed rule-engine labels (never message text).
     */
    val ruleFlags: List<String>? = null,

    val schemaValid: Boolean? = null,
    val sender: String? = null,
    val source: SyncReceiptSource? = null,
    val status: SyncReceiptStatus,

    /**
     * Epoch millis of the last change. Last writer wins.
     */
    val updatedAt: Long
)

@Serializable
enum class Category(val value: String) {
    @SerialName("BANK_FRAUD") BankFraud("BANK_FRAUD"),
    @SerialName("CRYPTO_INVESTMENT") CryptoInvestment("CRYPTO_INVESTMENT"),
    @SerialName("IMPERSONATION") Impersonation("IMPERSONATION"),
    @SerialName("JOB_OFFER") JobOffer("JOB_OFFER"),
    @SerialName("NONE") None("NONE"),
    @SerialName("OTHER") Other("OTHER"),
    @SerialName("OTP_THEFT") OtpTheft("OTP_THEFT"),
    @SerialName("PACKAGE_DELIVERY") PackageDelivery("PACKAGE_DELIVERY"),
    @SerialName("PHISHING_LINK") PhishingLink("PHISHING_LINK"),
    @SerialName("PRIZE_OR_LOTTERY") PrizeOrLottery("PRIZE_OR_LOTTERY"),
    @SerialName("ROBOCALL") Robocall("ROBOCALL"),
    @SerialName("ROMANCE_OR_WRONG_NUMBER") RomanceOrWrongNumber("ROMANCE_OR_WRONG_NUMBER"),
    @SerialName("SPOOFED_CALLER") SpoofedCaller("SPOOFED_CALLER"),
    @SerialName("TECH_SUPPORT") TechSupport("TECH_SUPPORT"),
    @SerialName("TOLL_OR_FINE") TollOrFine("TOLL_OR_FINE");
}

@Serializable
enum class Channel(val value: String) {
    @SerialName("CALL") Call("CALL"),
    @SerialName("SMS") SMS("SMS");
}

@Serializable
enum class DecidedBy(val value: String) {
    @SerialName("IN_CLOUD") InCloud("IN_CLOUD"),
    @SerialName("ON_DEVICE") OnDevice("ON_DEVICE"),
    @SerialName("RULES") Rules("RULES");
}

@Serializable
enum class Dispatch(val value: String) {
    @SerialName("AUTO") Auto("AUTO"),
    @SerialName("DENY") Deny("DENY"),
    @SerialName("OFFER") Offer("OFFER");
}

/**
 * Which app wrote it.
 */
@Serializable
enum class Origin(val value: String) {
    @SerialName("android") Android("android"),
    @SerialName("web") Web("web");
}

@Serializable
enum class RiskLevel(val value: String) {
    @SerialName("CRITICAL") Critical("CRITICAL"),
    @SerialName("HIGH") High("HIGH"),
    @SerialName("LOW") Low("LOW"),
    @SerialName("MEDIUM") Medium("MEDIUM"),
    @SerialName("SAFE") Safe("SAFE");
}

@Serializable
enum class SyncReceiptSource(val value: String) {
    @SerialName("call_log") CallLog("call_log"),
    @SerialName("call_screening") CallScreening("call_screening"),
    @SerialName("google_voice") GoogleVoice("google_voice"),
    @SerialName("lab_sample") LabSample("lab_sample"),
    @SerialName("sms") SMS("sms"),
    @SerialName("web_paste") WebPaste("web_paste"),
    @SerialName("whatsapp") Whatsapp("whatsapp");
}

@Serializable
enum class SyncReceiptStatus(val value: String) {
    @SerialName("DECLINED") Declined("DECLINED"),
    @SerialName("DENIED") Denied("DENIED"),
    @SerialName("FAILED") Failed("FAILED"),
    @SerialName("OFFERED") Offered("OFFERED"),
    @SerialName("OK") Ok("OK");
}

/**
 * A block, trust or mute entry for one sender.
 */
@Serializable
data class SyncSenderRule (
    val createdAt: Long,

    /**
     * Tombstone: the item was removed. Documents are kept so every device learns about the
     * delete.
     */
    val deleted: Boolean? = null,

    /**
     * Stable random id of the device that wrote this document.
     */
    @SerialName("deviceId")
    val deviceID: String,

    /**
     * Document id: the normalized sender (lower-case digits with optional +, or an email-to-SMS
     * address).
     */
    val key: String,

    val list: ListEnum,
    val number: String,

    /**
     * Which app wrote it.
     */
    val origin: Origin,

    val scope: Scope,

    /**
     * Epoch millis of the last change. Last writer wins.
     */
    val updatedAt: Long
)

@Serializable
enum class ListEnum(val value: String) {
    @SerialName("BLOCK") Block("BLOCK"),
    @SerialName("MUTE") Mute("MUTE"),
    @SerialName("TRUST") Trust("TRUST");
}

@Serializable
enum class Scope(val value: String) {
    @SerialName("ALL") All("ALL"),
    @SerialName("CALLS") Calls("CALLS"),
    @SerialName("MESSAGES") Messages("MESSAGES");
}

/**
 * Synced preferences. syncMessageContent is OFF by default; while it is off no message text
 * leaves the device (Firestore rules enforce this for the content collection).
 */
@Serializable
data class SyncSettings (
    val autoBlockEnabled: Boolean? = null,
    val autoBlockThreshold: Long? = null,

    /**
     * Tombstone: the item was removed. Documents are kept so every device learns about the
     * delete.
     */
    val deleted: Boolean? = null,

    /**
     * Stable random id of the device that wrote this document.
     */
    @SerialName("deviceId")
    val deviceID: String,

    /**
     * Never use the cloud model.
     */
    val onDeviceOnly: Boolean? = null,

    /**
     * Opt-in. When true, message bodies and model-written reasons are synced to the private
     * content collection.
     */
    val syncMessageContent: Boolean,

    /**
     * Epoch millis of the last change. Last writer wins.
     */
    val updatedAt: Long
)

/**
 * One subscription sender (grouped texts) and what was done about it. No message text.
 */
@Serializable
data class SyncSubscription (
    val brand: String? = null,
    val count: Long,

    /**
     * Tombstone: the item was removed. Documents are kept so every device learns about the
     * delete.
     */
    val deleted: Boolean? = null,

    /**
     * Stable random id of the device that wrote this document.
     */
    @SerialName("deviceId")
    val deviceID: String,

    /**
     * Document id: <source>_<normalized sender>.
     */
    val key: String,

    val keyword: Keyword? = null,
    val lastDate: Long? = null,
    val linkDomain: String? = null,
    val linkKind: LinkKind? = null,

    @SerialName("linkUrl")
    val linkURL: String? = null,

    /**
     * Which app wrote it.
     */
    val origin: Origin,

    val sender: String,
    val source: SyncSubscriptionSource,
    val status: SyncSubscriptionStatus,

    /**
     * Epoch millis of the last change. Last writer wins.
     */
    val updatedAt: Long
)

@Serializable
enum class Keyword(val value: String) {
    @SerialName("CANCEL") Cancel("CANCEL"),
    @SerialName("END") End("END"),
    @SerialName("QUIT") Quit("QUIT"),
    @SerialName("STOP") Stop("STOP"),
    @SerialName("STOPALL") Stopall("STOPALL"),
    @SerialName("UNSUBSCRIBE") Unsubscribe("UNSUBSCRIBE");
}

@Serializable
enum class LinkKind(val value: String) {
    @SerialName("MANAGE_SUBSCRIPTION") ManageSubscription("MANAGE_SUBSCRIPTION"),
    @SerialName("OTHER") Other("OTHER"),
    @SerialName("UNSUBSCRIBE") Unsubscribe("UNSUBSCRIBE");
}

@Serializable
enum class SyncSubscriptionSource(val value: String) {
    @SerialName("google_voice") GoogleVoice("google_voice"),
    @SerialName("sms") SMS("sms"),
    @SerialName("web_paste") WebPaste("web_paste"),
    @SerialName("whatsapp") Whatsapp("whatsapp");
}

@Serializable
enum class SyncSubscriptionStatus(val value: String) {
    @SerialName("ACTIVE") Active("ACTIVE"),
    @SerialName("CANCELED") Canceled("CANCELED"),
    @SerialName("DISMISSED") Dismissed("DISMISSED"),
    @SerialName("MANAGE_OPENED") ManageOpened("MANAGE_OPENED"),
    @SerialName("MUTED") Muted("MUTED"),
    @SerialName("STOP_SENT") StopSent("STOP_SENT"),
    @SerialName("UNSUBSCRIBED") Unsubscribed("UNSUBSCRIBED");
}

/**
 * One screened call or text and what each tier concluded. No message text.
 */
@Serializable
data class SyncVerdict (
    val category: Category,
    val channel: Channel,
    val confidence: Long,
    val decidedBy: DecidedBy,

    /**
     * Tombstone: the item was removed. Documents are kept so every device learns about the
     * delete.
     */
    val deleted: Boolean? = null,

    /**
     * Stable random id of the device that wrote this document.
     */
    @SerialName("deviceId")
    val deviceID: String,

    val finalMethod: String? = null,

    /**
     * <deviceId>-<localId>; also the Firestore document id.
     */
    val id: String,

    val model: String? = null,

    /**
     * Which app wrote it.
     */
    val origin: Origin,

    val proposedMethod: String? = null,
    val quarantined: Boolean? = null,
    val receivedAt: Long,
    val riskLevel: RiskLevel,

    /**
     * Fixed rule-engine labels (never message text).
     */
    val ruleFlags: List<String>? = null,

    val rulesScore: Long? = null,
    val schemaValid: Boolean? = null,
    val sender: String? = null,
    val source: SyncReceiptSource? = null,

    /**
     * Epoch millis of the last change. Last writer wins.
     */
    val updatedAt: Long,

    val userAction: String? = null
)
