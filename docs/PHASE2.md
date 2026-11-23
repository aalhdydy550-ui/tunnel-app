# Phase 2 plan — ZIVPN, then free-internet mechanisms

> Quality first, no rush (`الجودة أولًا والمدة مفتوحة` — user direction,
> 2026-11-23). Nothing in this document is in v0.1. v0.1 ships the sing-box
> core only; this file records what comes next and how v0.1 is already
> prepared for it.

## 0. Client-only — read this first

**This app is a client only. It ships with no servers and no free
internet.** Exactly like the original app (Hamo Tunnel Plus), free
internet needs a working server / configuration for the user's own
network — a server the user (or their provider) runs. The app provides
the mechanisms (SSH + payload, SNI, DNSTT, ZIVPN); the server side is
the user's responsibility, as it is in the original.

## 1. ZIVPN (Phase 2, first item)

### Facts (static analysis of Hamo 3.3 only — no binary was run, no
### install script was downloaded or executed)

- The ZIVPN client in Hamo is a Go binary named `udp-zivpn`, run as an
  external process:
  `./udp-zivpn client --config /etc/udp-zivpn/client.json`
- It exposes a **local SOCKS5** endpoint that the rest of the tunnel
  connects through.
- Transport is UDP over **QUIC/Hysteria with Brutal congestion control**
  (`ZIVPN_UDP_BRUTAL_DEBUG`, `SetMaxDatagramSize`).
- Authentication modes exist (`auth.Mode` / `auth.type`) plus an
  **obfuscation** layer.
- Profile fields: server + password + UDP port range + obfuscation value.
- Config path: `$HOME/.zivpn`.
- Log variables: `ZIVPN_LOG_FORMAT`, `ZIVPN_LOG_LEVEL`.
- **There is no open-source ZIVPN client.** The `powermx/zivpn`
  repository contains server install scripts only (GPL-3.0); the ZIVPN
  Tunnel app itself is closed-source.

### Why it is worth adding anyway

Hysteria2 inside sing-box already covers the same technical benefit
(QUIC + Brutal congestion control + obfuscation + port hopping). The
value of ZIVPN is **compatibility with existing ZIVPN servers**, not a
new capability.

### Implementation rules (strict)

- **Never run a Hamo binary.** Never download or execute any install
  script from a ZIVPN project.
- The Phase 2 implementation is **clean-room**: written from protocol
  documentation and observed behavior only (command line, config path,
  SOCKS5 interface, log variables).
- The `udp-zivpn` helper must be built from a trusted, licensed source
  or re-implemented; if no such source exists, the feature stays
  unimplemented rather than shipping a closed binary.

### Hooks already in v0.1 (no restructuring needed later)

- `ProfileType.ZIVPN` + `Backend.EXTERNAL_PROCESS` in
  `core/data/.../model/Profile.kt` — the backend is derived from the
  profile type.
- `SingBoxConfigBuilder` builds the standard configuration with a
  `socks` outbound to `127.0.0.1:<port>` for ZIVPN profiles; the port
  is read from the stored `client.json` (`socks_port` / `socks5_port` /
  `local_port`, default 1080) — see `zivpnSocksPort()`.
- `TunnelBackend` in `feature/connection` — the service starts
  backends in order: external process first (if the profile needs one),
  then libbox; stops them in reverse order. The external-process
  launcher is a documented stub in v0.1.
- `ProfileLinkParser` rejects `zivpn://` links with a clear Phase 2
  message (so users are not misled into thinking it connects).

## 2. Free-internet mechanisms (Phase 2, after ZIVPN)

User goal, clarified 2026-11-23: the app should eventually be able to
open the internet without balance, like the original — using the same
mechanisms the original relies on to pass the network's checks through
a free host.

**Where the free hosts come from (recorded 2026-11-23):** the
original's named free-network options (Vodafone Free Internet /
Vodafone Free Flex / Etisalat / WE) show names only — their host
values and payloads were **never extracted** (the renz layer is
encrypted and its strings are encrypted), so there is no host value
to copy from the original, and none is claimed. The free-internet
work relies instead on the config set in `~/workspace/tunnel-app-configs/`
(per-carrier docs + payload formats + test guide): the only
undocumented host there is `scorm.ekb.eg` (WE), and any further host
is added only when documented by experiment. No hidden value from the
original is used, ever.

1. **SSH tunnel with a payload generator** — custom HTTP payload
   templates (CONNECT/GET + Host/SNI headers) wrapped around the SSH
   connection, so the traffic passes as allowed HTTP(S) to a free host.
   In our stack this is a dialer/injector layer in front of sing-box's
   SSH outbound; the exact injection point needs a Phase 2 design
   review (libbox has no public custom-dialer API in v1.14 — options
   are a local injector proxy or a sing-box extension).
2. **SNI support** — already present in v0.1: the link parsers accept
   `sni` and pass it to TLS `server_name` (VLESS/VMess/Trojan/
   Hysteria2/TUIC). Phase 2 adds SNI presets per carrier in the UI.
3. **DNSTT tunnel (Slow DNS)** — DNS-tunneled transport for networks
   where only DNS is free. Same external-process pattern as ZIVPN:
   the `dnstt-client` (public domain, from bamsoftware) runs as a
   helper process and exposes a local endpoint; sing-box routes
   through it. The `Backend.EXTERNAL_PROCESS` abstraction already
   covers this case.

Priority order in Phase 2: ZIVPN → SSH payload generator + SNI
presets → DNSTT. Each ships only when its mechanism is verified
working end-to-end against a real server; no mechanism is faked.

## 3. OpenVPN (Phase 2 — the last original protocol)

sing-box does not cover OpenVPN, so it cannot ride the unified core.
The plan: add **ics-openvpn** (the open-source OpenVPN-for-Android
library, GPL) as a second engine behind the same `TunnelBackend`
interface, with its own `.ovpn` import. It runs as a library inside
the app's process (never as a copied binary, never as an external
process). The `ProfileType` gains `OPENVPN`; the service selects the
backend from the profile type, exactly as it does for ZIVPN today.

## 4. Routing modes + per-app proxy (Phase 2)

- **The four modes** (global / white / black / white_iran) as
  sing-box route rules + downloaded `.srs` rule-sets — the mapping is
  recorded in `docs/CONNECTIONS.md` §4. The download machinery and
  the safe fallback already exist in v0.1 (`RoutingDataManager` +
  `RoutingFallback`); Phase 2 adds the mode setting, the country sets
  (`geoip-{cc}.srs` from sing-geoip), and the category sets
  (`geosite-{category}.srs` from sing-geosite). The fallback rule
  stays: missing data → global mode + notice + retry.
- **Per-app proxy**: the plumbing exists (`OverrideOptions`
  include/exclude + `PlatformInterface` package lists). Phase 2 adds
  the app picker UI (installed-apps list) and the setting. No frozen
  241-package file — the list is the user's choice.
- **DNS options**: fake-IP + a remote/local DNS choice in settings
  (the builder already has the remote/local structure).
- **Manual DNS setting**: v0.1 auto-selects the fastest public
  resolver at connect time; Phase 2 adds a user-chosen DNS in
  settings (with DoH `https://dns.google/dns-query` as an option
  alongside DoT). The resolver *list* itself becomes downloadable
  (a small JSON in our repo, fetched like the rule-sets) instead of
  three hardcoded candidates.
- **ECH toggle** and **prefer-IPv6** (`domain_strategy`) as settings
  (both are sing-box TLS/TUN fields; v0.1 leaves them at defaults).
- **Mux per-profile toggle** in the UI (v0.1 has the `?mux=1` link
  parameter only).
- **In-tunnel latency test** (URL test via libbox CommandClient) for
  measuring through the tunnel, not just TCP to the server.
- **App update check via the GitHub API for OUR repo `tunnel-app`**
  (never v2rayNG's repos): the in-app *routing-data* update exists
  in v0.1; the *app-version* check (releases API, with a notice in
  the UI) is Phase 2.

## 5. Storage hardening + polish (Phase 2)

- **SQLCipher** (optional encryption of the Room database) — v0.1
  relies on app-private storage + `allowBackup=false`.
- **Splash screen**: v0.1 has none — the main screen opens directly
  with "Ayman Tunnel Plus" in the top app bar, which is what the user
  asked for (the name visible the moment the app opens). A branded
  splash is optional polish, not a requirement.
- **armeabi-v7a split**: v0.1 ships arm64-v8a only. If an older
  32-bit device ever needs the app, add the ABI split then.
- **libbox DefaultInterfaceMonitor wiring**: v0.1 deliberately leaves
  `startDefaultInterfaceMonitor` a no-op (the service-level
  NetworkCallback + full reconnect covers interface changes). Wire
  `listener.updateDefaultInterface` once the exact listener contract
  is confirmed from SFA's DefaultNetworkMonitor source.
- **QR import**: the original task listed QR as "later" — add a QR
  scanner for link import when the camera permission flow is wanted.
- **Play-store link**: added only at publish time, pointing to the
  app's own store page — never to any other app's page.

## 6. Explicitly out of scope (never)

- Running, bundling, or copying any Hamo binary or code (closed +
  obfuscated).
- Downloading or executing install scripts from any ZIVPN project.
- Shipping servers, free-server lists, or credentials with the app.
- Any mechanism that impersonates another service or abuses a network
  without the user's own server/config.
