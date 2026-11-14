# Relay sync protocol — Slice 1 (protocol version 1)

The phone is the hub and the LAN server; linked devices (desktop, another
phone, a tablet) are clients. Slice 1 covers discovery, pairing, and the
authenticated encrypted channel — no message content, contacts, or
notifications cross the channel yet. The desktop repo carries a matching
copy of this spec in `ARCHITECTURE.md` — keep the two in sync.

## Discovery (mDNS)

- Service type `_relay._tcp.`, instance name `SMS Relay`, fixed port
  **47831**.
- TXT records: `v=1`, `hub=<hub-id>` (the phone's stable hub id, a UUID
  generated on first run and stored on the phone).
- The phone advertises with `android.net.nsd` (NsdManager). The QR flow
  does not carry an address — discovery finds it.

## Pairing (QR)

- A linking device generates a stable Ed25519 keypair and a stable device
  id (UUID). Its QR encodes:
  `relay://pair?v=1&id=<device-id>&kind=<desktop|phone|tablet>&name=<url-encoded display name>&key=<base64url 32-byte Ed25519 public key>`
- The phone scans it in the Linked devices screen (CameraX preview +
  ZXing core decoding — no Play Services dependency), the user confirms,
  and the phone stores the device: id, kind, display name, public key,
  linked time. Pairing is one-way registration — the linking device needs
  no key from the phone.

## Transport + TLS

- WebSocket over TLS: `wss://<host>:47831/sync`. Server: Ktor (Netty
  engine, WebSockets plugin), running in a foreground service. Netty,
  not CIO: Ktor 3.x's CIO server engine has no HTTPS support — it throws
  on any `sslConnector`, which crashed the app on first server start
  (2026-11-13); `SyncEngineTlsTest` pins the engine+TLS combination.
- The phone generates a self-signed X.509 certificate on first run
  (CN = hub id, 10-year validity). The client pins its SHA-256 fingerprint
  on first connect (TOFU). A fingerprint mismatch after pinning is a hard
  refusal. There is no unencrypted fallback. TLS 1.2 minimum.
- The TLS certificate is transport-only. Device authentication is the
  Ed25519 challenge/response below — two separate key systems by design.

## Wire protocol (JSON over WebSocket, protocol version 1)

Every message is a JSON object with a `type` field. The client speaks
first:

| Direction | Message | Shape |
|---|---|---|
| Client → server | `hello` | `{"type":"hello","protocol":1,"device_id":"...","kind":"desktop","name":"..."}` |
| Server → client | `challenge` | `{"type":"challenge","challenge":"<base64url 32 random bytes>"}` |
| Client → server | `auth` | `{"type":"auth","device_id":"...","signature":"<base64url 64-byte Ed25519 signature over the raw challenge bytes>"}` |
| Server → client | `auth_ok` | `{"type":"auth_ok","hub_id":"...","hub_name":"SMS Relay"}` |
| Server → client | `auth_failed` | `{"type":"auth_failed","reason":"unknown_device"\|"bad_signature"\|"revoked"\|"protocol"}` |
| Either way | `ping` / `pong` | `{"type":"ping","t":<unix ms>}` / `{"type":"pong","t":<same t>}` |
| Server → client | `revoked` | `{"type":"revoked"}` |
| Either way | `error` | `{"type":"error","message":"..."}` |

Auth flow: connect → `hello` → `challenge` → `auth` → `auth_ok` (or
`auth_failed`). A socket that has not completed auth within 10 seconds is
closed by the server. The challenge is issued to any client; the device
check happens at `auth` verification against the stored public key.

Heartbeat: the client sends `ping` every 30 seconds; the server answers
`pong`. The client treats a missed `pong` (10s) as a dead channel and
reconnects with backoff (1s, 2s, 5s, 10s, 30s cap). The server drops
clients silent for more than 90 seconds.

Revocation: removing a device on the phone closes its live socket
(sending `revoked` first) and deletes its stored key. Later auth attempts
from it get `unknown_device`.

## Slice 2 — message sync (protocol version 1)

Slice 2 syncs real conversations + messages from the phone to the
desktop over the Slice 1 channel. All ids on the wire are decimal
strings of the phone's Room ids. Attachments are metadata only in
Slice 2 — no bytes cross the channel yet.

| Direction | Message | Shape |
|---|---|---|
| Client → server | `sync_request` | `{"type":"sync_request","mode":"full"\|"new_only","since_message_id":"12345"}` |
| Client → server | `messages_request` | `{"type":"messages_request","conversation_id":"123","before_id":"789","limit":200}` |
| Server → client | `sync_started` | `{"type":"sync_started","mode":"full","total_conversations":87,"total_messages":12340,"cursor_message_id":"98765"}` |
| Server → client | `conversations` | `{"type":"conversations","conversations":[CONVERSATION, "…"],"has_more":false}` |
| Server → client | `messages_batch` | `{"type":"messages_batch","conversation_id":"123","messages":[MESSAGE, "…"],"has_more":true,"next_before_id":"456"}` |
| Server → client | `sync_complete` | `{"type":"sync_complete","cursor_message_id":"98765"}` |
| Server → client | `message_new` | `{"type":"message_new","message":MESSAGE}` |
| Server → client | `message_updated` | `{"type":"message_updated","message_id":"789","conversation_id":"123","status":"delivered","reaction":"❤️"}` |
| Server → client | `conversation_updated` | `{"type":"conversation_updated","conversation":CONVERSATION}` |
| Server → client | `conversation_deleted` | `{"type":"conversation_deleted","conversation_id":"123"}` |
| Server → client | `sync_error` | `{"type":"sync_error","code":"hub_no_sms_access","message":"…"}` |

Flow: the desktop sends `sync_request` after auth. The phone answers
`sync_started` (totals + the current max message id as the cursor),
streams the conversation snapshot in batches of 100 (most recent
first), then — in `full` mode — every conversation's messages in
batches of 200, newest first, most-recent conversation first, then
`sync_complete`. In `new_only` mode the message stream is skipped:
the list syncs, threads start empty, and the `sync_started` cursor
is the "now" cursor. A `sync_request` with `since_message_id > 0`
(reconnect) sends the full conversation snapshot plus a delta — the
messages above the cursor, per conversation — instead of the full
history; those delta batches carry `has_more: false` (no `before_id`
continuation for a delta range).

`messages_request` is the targeted fetch: gap-fill, repair of one
conversation, or on-demand fetch. The phone answers with one
`messages_batch`; an unknown conversation id gets a plain `error`
(the socket stays up). `limit` is capped at 200 by the server.

Live push runs from auth onwards, concurrently with the initial
stream — upserts by id make duplicates harmless. `message_new` is
one live message; `conversation_updated` carries the full row (and
is also the read-state indicator: `unread: 0` means the phone marked
the conversation read); `conversation_deleted` fires when a
conversation disappears from the phone. `message_updated` is
partial — only changed fields are present (`status`, `reaction`,
`read`, `starred`); a removed reaction rides as
`"reaction_removed": true`, since a null `reaction` would be
omitted on encode and read as "unchanged".

`sync_error` codes: `hub_no_sms_access` (READ_SMS missing on the
phone — the debug build needs the manual grant, since only one app
can hold the default-SMS role), `hub_mirror_empty` (permission
granted but the phone's message import hasn't run yet), `internal`
(anything else; the message stays generic, never a stack trace).

CONVERSATION: `id`, `thread_id` (may be null), `addresses`,
`display_name`, `custom_name`, `title` (the phone's computed title —
authoritative for display), `last_message_at`,
`last_message_preview`, `unread`, `pinned`, `archived`, `muted`,
`blocked`, `message_count`, `pinned_message_id`,
`pinned_message_at`. Not synced: `draft`, bubble color, avatar.
Vault conversations never leave the phone, by design.

MESSAGE: `id`, `conversation_id`, `address` (the sender, not the
group), `body`, `type` (`sms` | `mms`), `direction` (`incoming` |
`outgoing`), `date`, `date_sent`, `read`, `starred`, `status`
(`pending` | `sent` | `delivered` | `failed` | `received` |
`scheduled`), `error_code`, `reply_to_message_id` (a phone Room id),
`reaction`, `attachments` (ATTACHMENT metadata: `id`, `mime_type`,
`file_name`, `size_bytes`, `width`, `height`, `duration_ms`).

Message deletions are not synced in Slice 2 (the phone has no
deletion feed); the repair path is wipe + re-walk via
`messages_request`. Reactions on old messages (outside the ~200 most
recent pushed ids) are not live-pushed either — same repair path.

## Key storage (phone)

Linked-device records in the app's store: device id, kind, display name,
public key (base64url), linked time, last seen. TLS certificate and key in
app-private storage / the AndroidKeyStore.

## Slice 1 scope boundary

Slice 1 carried no message content, contacts, or notifications — Slice
2 (above) adds the sync message types in the same envelope, and the
protocol version stays 1 until a breaking change needs it. Still out
of scope: sending from the desktop, desktop→phone write-back (marking
read on the phone, pin/archive/mute from the desktop), attachment
bytes, and contacts.

## Glue points (Android)

The sync layer is kept separable from the rest of the Android app, so
it survives if the app's base is ever swapped out. Everything in
`com.filestech.sms.sync` — protocol, crypto, QR payload format,
transport, discovery, the linked-device store, the TLS identity —
imports no app code outside that package. The ONLY coupling is this
thin, explicit glue:

1. **The foreground service wrapper** (`sync/SyncService.kt`): an
   Android `Service` that hosts `SyncServer`, owns its notification
   channel and strings, and is started/stopped by `MainApplication`
   (toggle observer) and `BootReceiver` (boot restart).
2. **The Settings navigation entry** (`ui/navigation/Routes.kt`,
   `ui/AppRoot.kt`, `ui/screens/settings/SettingsScreen.kt`): one
   route, one destination, one row.
3. **The Linked devices screen** (`ui/screens/linkeddevices/`): the
   ViewModel talks only to `LinkedDeviceStore` and `SyncServer`; the
   screen reuses the app's theme and shared settings components
   (`RelaySettingsGroup`, `RelaySwitchRow`, …) — the one visual
   dependency.
4. **The message-store seam** (`sync/SyncMessageSource.kt`):
   the interface the sync engine calls, plus the wire DTOs
   (`SyncedConversation` / `SyncedMessage` / `SyncedAttachment`).
   The implementation (`system/sync/RepositorySyncMessageSource.kt`)
   lives outside the sync package — it is the one file that knows
   both the wire DTOs and the app's repositories — and is bound in
   Hilt (`di/RepositoryModule.kt`) and injected into `SyncServer`,
   which hands it to one `SyncEngine` per session. The sync package
   still imports no app code; a future port re-implements this one
   interface. (Built in Slice 2; listed as "future glue" in Slice 1.)

A future port re-wires those four points and lifts the `sync` package
as-is.
