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

## Call Guardian integration notes

The sms/mms engine is compiled as the `:clearsms-engine` Android library
(namespace `app.clearsms`). Four FileProvider authority constants were changed
from `BuildConfig.APPLICATION_ID + ".fileprovider"` to
`HostPackage.fileProviderAuthority` so the host app package is used. No other
upstream behavior was intentionally changed.


The library compiles a filtered copy of `app/src/main/kotlin` (Application,
MainActivity, and most Compose screens are excluded). Type-only UI helpers
needed by the engine (`AvatarStyle`, `BrandCatalog`, `SwipeActions`,
`DirectCall`, and similar) plus small shims (`HostPackage`,
`MessageSelectionAction`, `AvatarHelpers`) live in
`clearsms-engine/src/main/kotlin`. Vendor XML themes, launcher mipmaps, and
shortcuts are excluded so Call Guardian keeps its own icon, app name, and
theme. `Theme.ClearSms` is stubbed to `android:Theme.Material.NoActionBar`.

The host `Application` implements WorkManager `Configuration.Provider` with
`HiltWorkerFactory` and calls `HostPackage.init`. ROLE_SMS exported receivers
(`SMS_DELIVER`, `WAP_PUSH_DELIVER`, `RESPONSE_SMS`, `SENDTO`) stay in Call
Guardian; the library only registers non-exported result/boot/notification
receivers. Do not also register Clear SMS's `SMS_DELIVER` / `WAP_PUSH_DELIVER`.

Additional host patches in this snapshot:

- `MmsSendReqEncoder` writes one To header per comma-separated address so
  group MMS can be sent.
- After an MMS download completes, `HostIncoming.mmsListener` is invoked so
  Call Guardian can screen the message and post a per-conversation
  notification. SMS delivery reports are supported via `UiPrefs` and
  `SmsSentReceiver`. MMS delivery reports are not available from the
  platform MMS service this encoder hands off to (send result only).

