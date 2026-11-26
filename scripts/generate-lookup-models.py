#!/usr/bin/env python3
"""Generate Moshi Kotlin data classes from official OpenAPI specs.

Types are derived from the vendored Twilio and Telnyx OpenAPI documents in
openapi/. Do not hand-edit the generated Kotlin; re-run this script.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OAS = ROOT / "openapi"
OUT = ROOT / "app/src/main/java/com/example/callguardian/lookup/generated"

RESERVED = {
    "class", "object", "fun", "val", "var", "type", "in", "for", "if", "else",
    "when", "interface", "package", "return", "this", "super", "null", "true",
    "false", "is", "as", "typealias", "enum", "data", "sealed", "open",
}


def camel(name: str) -> str:
    parts = re.split(r"[^A-Za-z0-9]+", name)
    if not parts:
        return name
    head, *rest = parts
    ident = head[:1].lower() + head[1:] + "".join(p[:1].upper() + p[1:] for p in rest if p)
    if ident in RESERVED or (ident and ident[:1].isdigit()):
        ident = ident + "_"
    return ident or "value"


def pascal(name: str) -> str:
    parts = re.split(r"[^A-Za-z0-9]+", name)
    ident = "".join((p[:1].upper() + p[1:]) for p in parts if p) or "Model"
    if ident[0].isdigit():
        ident = "N" + ident
    return ident


def resolve_type(schema: dict, components: dict, inline_prefix: str, acc_inline: list) -> str:
    if schema is None:
        return "Any?"
    if "$ref" in schema:
        ref = schema["$ref"].rsplit("/", 1)[-1]
        return pascal(ref)
    types = schema.get("type")
    if isinstance(types, list):
        non_null = [t for t in types if t != "null"]
        types = non_null[0] if non_null else "string"
    if "allOf" in schema and len(schema["allOf"]) == 1:
        return resolve_type(schema["allOf"][0], components, inline_prefix, acc_inline)
    if "oneOf" in schema or "anyOf" in schema:
        alts = schema.get("oneOf") or schema.get("anyOf")
        refs = [a for a in alts if isinstance(a, dict) and "$ref" in a]
        if len(refs) == 1:
            return resolve_type(refs[0], components, inline_prefix, acc_inline)
        return "Any"
    t = types
    fmt = schema.get("format")
    if t == "string":
        return "String"
    if t == "boolean":
        return "Boolean"
    if t == "integer":
        return "Long" if fmt in {"int64"} else "Int"
    if t == "number":
        return "Double"
    if t == "array":
        inner = resolve_type(schema.get("items") or {}, components, inline_prefix, acc_inline)
        return f"List<{inner}>"
    if t == "object" or "properties" in schema:
        name = pascal(schema.get("title") or inline_prefix)
        acc_inline.append((name, schema))
        return name
    return "Any"


def emit_enum(name: str, schema: dict) -> str:
    values = schema.get("enum") or []
    body = "\n".join(f"    {v}," for v in values)
    return f"enum class {name} {{\n{body}\n}}\n"


def emit_class(name: str, schema: dict, components: dict) -> str:
    if schema.get("type") == "string" and schema.get("enum"):
        return emit_enum(name, schema)
    acc_inline: list = []
    cls = emit_class_plain(name, schema, components, acc_inline)
    extras = []
    seen = {name}
    while acc_inline:
        iname, ischema = acc_inline.pop(0)
        if iname in seen:
            continue
        seen.add(iname)
        more: list = []
        extras.append(emit_class_plain(iname, ischema, components, more))
        for item in more:
            if item[0] not in seen:
                acc_inline.append(item)
    return "\n".join([cls] + extras)


def emit_class_plain(name: str, schema: dict, components: dict, acc_inline: list) -> str:
    props = schema.get("properties") or {}
    fields = []
    for raw, spec in props.items():
        # Official schemas sometimes list a property with only a description.
        # Skip typeless fields rather than emit Moshi-unfriendly Any.
        if "$ref" not in spec and "type" not in spec and "properties" not in spec and "allOf" not in spec and "oneOf" not in spec and "anyOf" not in spec and "items" not in spec:
            continue
        typ = resolve_type(spec, components, name + pascal(raw), acc_inline)
        if typ.startswith("Any"):
            continue
        if not typ.endswith("?"):
            typ = typ + "?"
        ident = camel(raw)
        fields.append(f'    @Json(name = "{raw}") val {ident}: {typ} = null,')
    body = "\n".join(fields) if fields else ""
    return f"data class {name}(\n{body}\n)\n"


def emit_request(op_id: str, parameters: list, components: dict) -> str:
    fields = []
    for p in parameters:
        if "$ref" in p:
            ref = p["$ref"].rsplit("/", 1)[-1]
            p = components.get("parameters", {}).get(ref, p)
        name = p.get("name")
        if not name:
            continue
        schema = p.get("schema") or {}
        typ = resolve_type(schema, components, pascal(op_id) + pascal(name), [])
        ident = camel(name)
        desc = (p.get("description") or "")
        if typ == "String" and "more than once" in desc.lower():
            typ = "List<String>"
        if typ == "List<String>":
            typ = "List<String>?"
        elif not typ.endswith("?"):
            typ = typ + "?"
        fields.append(f'    @Json(name = "{name}") val {ident}: {typ} = null,')
    body = "\n".join(fields)
    return (
        f"/** Request parameters generated from the official OpenAPI operation `{op_id}`. */\n"
        f"data class {pascal(op_id)}Request(\n{body}\n)\n"
    )


HEADER = """@file:Suppress("unused", "RemoveRedundantQualifierName")
// GENERATED from the official OpenAPI specification at {spec}.
// Do not hand-edit. Re-run scripts/generate-lookup-models.py.

package {pkg}

import com.squareup.moshi.Json

"""


def generate_twilio():
    spec = json.loads((OAS / "twilio_lookups_v2.json").read_text())
    comps = spec.get("components", {})
    schemas = comps.get("schemas", {})
    keep = [
        "LookupResponse", "CallerNameInfo", "LineTypeIntelligenceInfo",
        "ValidationError", "SimSwapInfo", "LastSimSwapInfo",
        "CallForwardingInfo", "LineStatusInfo", "IdentityMatchInfo",
        "ReassignedNumberInfo", "SmsPumpingRiskInfo",
    ]
    parts = [HEADER.format(spec="openapi/twilio_lookups_v2.json (twilio/twilio-oai)", pkg="com.example.callguardian.lookup.generated.twilio")]
    for name in keep:
        if name not in schemas:
            raise SystemExit(f"missing Twilio schema {name}")
        parts.append(emit_class(pascal(name), schemas[name], comps))
    op = spec["paths"]["/v2/PhoneNumbers/{PhoneNumber}"]["get"]
    parts.append(emit_request(op.get("operationId") or "FetchLookupsV2PhoneNumber", op.get("parameters") or [], comps))
    dest = OUT / "twilio" / "TwilioLookupModels.kt"
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text("\n".join(parts).rstrip() + "\n")
    print("wrote", dest, "chars", dest.stat().st_size)


def generate_telnyx():
    spec = json.loads((OAS / "telnyx_number_lookup.json").read_text())
    comps = spec.get("components", {})
    schemas = comps.get("schemas", {})
    parts = [HEADER.format(spec="openapi/telnyx_number_lookup.json (subset of team-telnyx/openapi spec3.json)", pkg="com.example.callguardian.lookup.generated.telnyx")]
    resp_schema = (
        spec["paths"]["/number_lookup/{phone_number}"]["get"]["responses"]["200"]
        ["content"]["application/json"]["schema"]
    )
    parts.append(emit_class("NumberLookupResponse", resp_schema, comps))
    for name, schema in schemas.items():
        parts.append(emit_class(pascal(name), schema, comps))
    op = spec["paths"]["/number_lookup/{phone_number}"]["get"]
    params = []
    for p in op.get("parameters") or []:
        if "$ref" in p:
            ref = p["$ref"].rsplit("/", 1)[-1]
            params.append(comps.get("parameters", {}).get(ref, p))
        else:
            params.append(p)
    parts.append(emit_request(op.get("operationId") or "LookupNumber", params, comps))
    dest = OUT / "telnyx" / "TelnyxLookupModels.kt"
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text("\n".join(parts).rstrip() + "\n")
    print("wrote", dest, "chars", dest.stat().st_size)


if __name__ == "__main__":
    generate_twilio()
    generate_telnyx()
