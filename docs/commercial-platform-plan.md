# Telefyna Cloud: implementation plan

**Status:** proposal for review · **Owner:** Avvento Media · **Date:** 30 October 2026
**Designs:** [Telefyna Platform Design canvas](https://claude.ai/artifact/3gnD7V6DKPYBvxswiSgjjp) (private until shared): three visual directions for the Overview, and a clickable prototype of the whole product in the recommended direction: sign-in with two-step codes, onboarding and box pairing, schedule builder, library and playlist editor (every type), graphics with live preview, exports (EPG/DVR, print, config history), devices with every output state, device detail, reports, settings (station, team, alerts, radio stream, advanced), billing with days or hours left at each stage, the Avvento admin payments queue, and a board of edge states.

This plan turns the *Commercial Platform (Future)* roadmap items into one product. The configuration tool, live device status, reports, billing and licences live in a single web app, **Telefyna Cloud**, and Telefyna boxes connect to it.

## 1. Decisions already made

| Topic | Decision |
|---|---|
| Product shape | **One product.** The configuration tool is rebuilt as the schedule builder inside Telefyna Cloud. A **free tier** keeps today's "build a schedule and export `config.json`" use. |
| Stack | **Laravel (PHP 8.2+) and MySQL on the existing Bluehost hosting.** No framework lock-in to Bluehost: the same app moves to a VPS when needed. |
| Payments | **Mobile money (MTN, Airtel), cards, PayPal, and manual invoice / bank transfer.** |
| Look | **Direction A, Studio Light** (IBM Plex type, light neutral ground, tally-red accent). |
| Price | **Free:** UGX 0 (1 box). **Station:** UGX 1,000,000 per month (up to 5 boxes). **Network:** quoted. |
| Exemptions | **Complimentary accounts**, starting with Avvento Media's own station: every feature, never invoiced, licences renewed automatically. Only Avvento admins can grant one, optionally with an end date (e.g. a partner pilot), and each change is logged. |
| Domain | The app runs on **avventomedia.org** (exact subdomain or path to choose at deploy, e.g. `telefyna.avventomedia.org`). |

## 2. Principles

1. **A box never depends on the cloud to stay on air.** The platform plans, watches and bills. It never drives playback live. Boxes keep their last good config, local media and an offline-verifiable licence.
2. **Boxes only call out.** Every box-to-cloud exchange is an HTTPS request from the box, so boxes work behind any router, NAT or mobile data connection. Shared hosting needs nothing more (no websockets).
3. **Billing never causes black.** An unpaid licence degrades step by step (reminders, then an on-screen mark, then fillers and logo only). It never stops output.
4. **Migrate, don't strand.** Any existing `config.json` imports into the new builder, and the free tier exports one back.

## 3. Architecture

```
Browser (owner, scheduler, engineer)
        │ HTTPS, session + 2FA
        ▼
Telefyna Cloud (Laravel on Bluehost)
  ├── Web app: onboarding, schedule builder, library, devices, reports, alerts, billing, team
  ├── Device API  /api/device/v1   (per-box bearer token from pairing)
  ├── Payments: aggregator checkout + webhooks
  ├── Licences: Ed25519-signed tokens
  └── Jobs: cron-driven queue (reports, alerts, renewals)
        ▲
        │ HTTPS, box-initiated only
Telefyna boxes (Android TV): pair, heartbeat, pull config + licence, upload logs/reports
```

**Bluehost constraints and how the design fits them:**
- *No long-running workers:* the Laravel queue runs from cron (`schedule:run` every minute, `queue:work --stop-when-empty`). Jobs are short: alerts, report rendering, renewal reminders.
- *No websockets:* the dashboard polls device status every 15–30 s. Boxes send a heartbeat every 60 s. That's enough for "is it on air"; live preview stays on the box.
- *Storage:* audit logs and PDFs go in a private storage directory with quotas per plan. Media files are **not** uploaded to the cloud in phase 1 (they stay on boxes and USB/SD drives). Bluehost bandwidth and storage don't suit video.
- *Upgrade path:* if device count or polling load outgrows shared hosting, move the same app to a VPS and switch the queue to a real worker. No code rewrite.

## 4. Data model (core tables)

| Table | Key fields |
|---|---|
| `organisations` | name, country, billing email, plan, status, `complimentary` (bool), `complimentary_until` (nullable date), `complimentary_reason` |
| `users` / `memberships` | email, password hash, 2FA secret; role per organisation: owner, scheduler, engineer, viewer |
| `stations` | organisation, name, time zone, logo, current published config version |
| `devices` | station, name, model, app version, paired at, token hash, last heartbeat, last status JSON |
| `pairing_codes` | code (6 chars, e.g. `K7Q-4MD`), device fingerprint, expires at (10 min), claimed by |
| `configs` | station, version, JSON (the exact `config.json` the app reads), published by, published at |
| `playlists`, `schedules` | structured editor state that compiles to `configs.json`; same fields as today's `config.json` |
| `heartbeats` | device, at, now playing, health (memory, storage, network, stream state); 7–90 days by plan |
| `audit_uploads`, `reports` | device or station, period, file path, size |
| `subscriptions`, `invoices`, `payments` | plan, period, amount, currency (UGX/USD), provider, provider reference, status |
| `licences` | organisation, plan, features, device limit, valid until, grace until, signed token |

## 5. Device API (v1)

All endpoints are box-initiated, JSON, versioned, and rate-limited per device.

| Endpoint | Purpose |
|---|---|
| `POST /pair/start` | Box asks for a pairing code (shown on the TV). Returns code and poll token. |
| `GET /pair/status` | Box polls until a user claims the code in the web app. Returns the device token once. |
| `POST /heartbeat` | Every 60 s: now playing, playlist, position, app/config versions, health, radio stream state. Response says whether a newer config, licence or command is waiting. |
| `GET /config?since=<version>` | Signed config (`config.json` plus a detached signature). The box validates it, keeps it as last good, and applies it through the existing reload path. |
| `GET /licence` | Current signed licence token. |
| `POST /logs` | Daily audit log and PDF report upload (nightly, after midnight maintenance; resumable, gzip). |
| `POST /commands/{id}/ack` | Remote actions queued in the web app: restart app, show/hide slate, re-run maintenance, upload logs now. |

**Security:**
- The device token is stored on the box in Android Keystore-backed encrypted storage; only its hash is kept server-side. Revoking a box deletes its token.
- Configs and licences are signed with a server **Ed25519** key. The app ships the public key, so a tampered or replayed config or licence is rejected even offline.
- Pairing codes are short-lived, single-use and rate-limited, and a claim needs an authenticated user with the engineer or owner role.

## 6. Licensing and plan gating

- **Token:** `{org, plan, features[], maxDevices, validUntil, graceUntil, issuedAt}` signed with Ed25519. The box verifies it locally, so no internet is needed day to day.
- **Grace and degradation** (shown in the Billing design): days 0–7 reminders only; days 8–14 a small "licence expired" mark on screen; after day 14, fillers and logo only, with schedules paused. Never black.
- **Complimentary accounts** get a token with all features and a rolling validity that the cloud renews automatically, so an exempt station never sees reminders or marks.
- **Free tier:** no token needed. The app runs today's feature set from a manually installed config. Cloud features (pairing, heartbeat, remote config, reports upload, alerts) need a paid plan.
- **Gating** happens in two places: the web app (what you can configure) and the box (features in the token, e.g. radio stream, number of online streams, white label).

## 7. Payments

| Method | Approach |
|---|---|
| Mobile money (MTN, Airtel) | Through an aggregator that covers Uganda and neighbouring markets (Flutterwave or Pesapal; choose after comparing fees, payout time and recurring-billing support). The customer approves each renewal with their PIN, so renewal is "request to pay" with reminders, not silent auto-debit. |
| Cards | Same aggregator's hosted card fields, so card data never touches our servers (keeps PCI scope minimal). |
| Invoice / bank transfer | PDF invoice emailed 14 days before renewal, with a unique reference (e.g. `TFY-2026-0142`) to put in the transfer narration. See *Confirming bank transfers* below. |
| PayPal | PayPal Subscriptions for automatic monthly renewal. Where an account can't pay in UGX, PayPal charges the USD equivalent; the invoice stays in UGX. |

**Confirming bank transfers.** Bank transfers don't notify us, so each one is matched by hand at first and automatically later:
1. The customer clicks **I've paid** on the invoice and can attach the bank slip. The invoice shows *Awaiting confirmation* and their grace period is paused.
2. Avvento staff see it in the admin console's **Payments to confirm** queue (reference, customer, amount, claim date, slip). They check the bank statement and click **Mark paid**, which extends the licence on all the customer's boxes and emails a receipt. **Can't find it** asks the customer for the slip or the right reference; the grace period resumes after 3 days.
3. Later: upload the bank's statement CSV and the system matches transactions by reference and amount, leaving staff only to confirm.

All provider callbacks go through **verified webhooks** (signature check, idempotent on provider reference). A payment only extends a subscription after server-side verification with the provider's API, never on the browser redirect alone.

## 8. Changes needed in the Android app

| Change | Notes |
|---|---|
| Pairing screen | Shows the code on first run or after "Pair with Telefyna Cloud" in settings; works with the remote alone. |
| Cloud client | Heartbeat, config pull, licence pull, log upload, command polling. Runs on a background thread owned by `Playout`/the foreground service, like the audio streamer. Bounded memory and back-off. |
| Signed config | Verify signature; on failure keep the last good config (the same path as today's bad-config fallback). |
| Licence enforcement | Offline verification and the degradation steps; on-screen mark drawn by the existing graphics layer. |
| Settings | Unpair, cloud on/off, show device ID. |
| Output health | Detect what the box really puts out, not just what is scheduled: picture frozen (no new rendered frames while playback advances), black picture, silence (audio level), audio only (video decoder failed), buffering. Reported in each heartbeat; the box also reacts on its own (restart the player, then fall back to fillers or the slate). |

These build on what the app already has: `Playout`, last-known-good config, audit log, PDF reports, and the slate.

## 9. Phases

| Phase | Scope | Exit criteria |
|---|---|---|
| **0. Foundations** (2 weeks) | Laravel skeleton on Bluehost, CI deploy, auth with 2FA, organisations/roles, design system from the chosen direction | Sign up, sign in, invite a teammate on staging |
| **1. Schedule builder (free tier)** (4–5 weeks) | Rebuild today's config tool: playlists (all current types incl. `LOCAL_RANDOMIZED_ONE`), schedules, graphics, alerts, `audioStream`, `driveImport`, preview grid, PDF print, import/export `config.json` with version history, and the EPG/DVR exporter (TSV for mySDAtv, CSV, plus XMLTV; 7, 14 or 30 days; a stable guide link that always serves the published schedule) | Every field the app reads round-trips; existing configs import cleanly; old tool can be retired |
| **2. Devices** (4 weeks) | Pairing, heartbeat, live status dashboard, config publish to boxes, remote commands, log and report upload; app changes in section 8 | A box pairs in under 2 minutes; publishing reaches all boxes within one heartbeat; box survives cloud outage |
| **3. Billing and licences** (4 weeks) | Plans, aggregator integration (mobile money + card), invoices, signed licences, degradation on box | Test payments in sandbox; renewal, failure and grace paths tested end to end on a box |
| **4. Reports and alerts** (2–3 weeks) | Cloud-side as-run and uptime reports across boxes, email alerts (SMS later), audit search | Weekly/monthly reports per station match box PDFs |
| **5. Fleet** (later) | OTA app updates (staged rollout), white label, multi-station networks | — |

Estimates assume one full-time developer; they are planning ranges, not commitments.

## 10. Testing

- **Web:** PHPUnit/Pest feature tests for every Device API endpoint and webhook; contract tests that compiled `config.json` matches what the Android app parses (shared JSON fixtures in both repos).
- **App:** the existing emulator regression suite, plus scenarios for pairing, cloud outage (box keeps airing), signed-config rejection, licence expiry stages.
- **Payments:** provider sandboxes only until launch; idempotency and replay tests on webhooks.

## 11. Risks

| Risk | Mitigation |
|---|---|
| Shared hosting limits (CPU, cron frequency, request time) | Short jobs, polling not sockets, small payloads; documented move to a VPS |
| Mobile money recurring billing is "approve each time" | Reminders, invoice fallback, generous grace period |
| Cloud outage or breach affecting stations | Boxes are autonomous; signed configs; per-device revocable tokens; 2FA; backups |
| Scope creep in the builder | Phase 1 ships parity with today's tool first, new features after |

## 12. Open questions

1. Is UGX 1,000,000 per month for the Station plan (assumed), per year, or per box?
2. Network plan pricing and the exact plan limits (boxes, team size, log retention).
3. Exact address on avventomedia.org (subdomain or path).
