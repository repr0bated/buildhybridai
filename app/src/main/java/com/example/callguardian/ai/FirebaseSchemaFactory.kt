package com.example.callguardian.ai

import com.google.firebase.ai.type.Schema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Intermediate view of a JSON Schema document, used to build a Firebase AI Logic
 * [Schema] (`Schema.obj` / `enumeration` / …) and to unit-test the mapping.
 */
data class JsonSchemaView(
    val type: String?,
    val description: String? = null,
    val properties: Map<String, JsonSchemaView> = emptyMap(),
    val required: List<String> = emptyList(),
    val enumValues: List<String> = emptyList(),
    val items: JsonSchemaView? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val maxItems: Int? = null,
    val additionalProperties: Boolean? = null,
)

/** Turns the registry verdict JSON Schema into a Firebase `Schema.obj` for cloud structured output. */
object FirebaseSchemaFactory {

    fun viewOf(root: JsonObject): JsonSchemaView = view(root)

    fun fromJson(root: JsonObject): Schema = toSchema(view(root))

    fun fromJson(text: String): Schema =
        fromJson(kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject)

    private fun view(n: JsonObject): JsonSchemaView {
        val type = n["type"]?.jsonPrimitive?.contentOrNull
        val props = n["properties"]?.jsonObject?.mapValues { view(it.value.jsonObject) }.orEmpty()
        val required = n["required"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val enums = n["enum"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val items = n["items"]?.jsonObject?.let { view(it) }
        return JsonSchemaView(
            type = type,
            description = n["description"]?.jsonPrimitive?.contentOrNull,
            properties = props,
            required = required,
            enumValues = enums,
            items = items,
            minimum = n["minimum"]?.jsonPrimitive?.doubleOrNull,
            maximum = n["maximum"]?.jsonPrimitive?.doubleOrNull,
            maxItems = n["maxItems"]?.jsonPrimitive?.intOrNull,
            additionalProperties = n["additionalProperties"]?.jsonPrimitive?.booleanOrNull,
        )
    }

    private fun toSchema(v: JsonSchemaView): Schema {
        if (v.enumValues.isNotEmpty()) {
            return Schema.enumeration(v.enumValues, v.description, nullable = false)
        }
        return when (v.type) {
            "object" -> Schema.obj(
                properties = v.properties.mapValues { toSchema(it.value) },
                optionalProperties = v.properties.keys.filterNot { it in v.required },
                description = v.description,
                nullable = false,
            )
            "array" -> Schema.array(
                items = toSchema(v.items ?: JsonSchemaView(type = "string")),
                description = v.description,
                nullable = false,
                maxItems = v.maxItems,
            )
            "integer" -> Schema.integer(description = v.description, nullable = false, minimum = v.minimum, maximum = v.maximum)
            "number" -> Schema.double(description = v.description, nullable = false, minimum = v.minimum, maximum = v.maximum)
            "boolean" -> Schema.boolean(v.description, nullable = false)
            else -> Schema.string(description = v.description, nullable = false)
        }
    }
}
