# Official OpenAPI sources for reverse-lookup types

Fetched 2026-11-26. Types in `app/src/main/java/com/example/callguardian/lookup/generated/` are generated from these files by `scripts/generate-lookup-models.py`. Do not hand-edit the generated models.

## Twilio Lookup v2

- Upstream: https://github.com/twilio/twilio-oai
- File: https://raw.githubusercontent.com/twilio/twilio-oai/main/spec/json/twilio_lookups_v2.json
- Vendored verbatim as `twilio_lookups_v2.json`

## Telnyx Number Lookup

- Upstream: https://github.com/team-telnyx/openapi (`openapi/spec3.json`)
- File: https://raw.githubusercontent.com/team-telnyx/openapi/master/openapi/spec3.json
- `telnyx_number_lookup.json` is a closed subset of that official document: `GET /number_lookup/{phone_number}` and every component it `$ref`s, copied verbatim. Schemas are not hand-written.
