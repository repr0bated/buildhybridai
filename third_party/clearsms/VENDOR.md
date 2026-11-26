# Vendored Clear SMS

This directory is a vendored snapshot of [itsluminous/ClearSMS](https://github.com/itsluminous/ClearSMS)
(Apache License 2.0). See `LICENSE`, `NOTICE`, and `UPSTREAM_REVISION` for the
pinned commit.

Call Guardian uses Clear SMS as the SMS/MMS engine and compose base. The
Call Guardian application remains Apache-2.0, copyright Jeremy Hobson.
Upstream copyright is retained in `LICENSE` and `NOTICE`. Do not drop
those files.

The `site/` and `fastlane/` trees were omitted from this snapshot as they
are not part of the messaging engine.

The upstream `app/src/main/assets/sender_ids.db` (~43 MB) is omitted from this
snapshot. It is a Clear SMS sender-id lookup database, not required for the
Call Guardian verdict pipeline. Restore it from the pinned upstream commit if
you need Clear SMS's own sender-id pack.
