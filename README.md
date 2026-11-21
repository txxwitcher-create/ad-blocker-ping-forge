# AD BLOCKER | PING FORGE

A native Android (Kotlin + Jetpack Compose) DNS-based protection and DNS resolver benchmarking utility built around a local split-DNS `VpnService`, an $O(\text{labels})$ `ReversedDomainTrie` filtering engine, and **Ping Forge** real-time DNS latency & reliability testing.

---

## Implemented Features

### 1. DNS-Based Ad Blocker & Threat Protection
- **Local Split-DNS `VpnService` (`PingForgeVpnService`)**: Routes only a virtual DNS endpoint (`10.111.222.1/32`) into the Android TUN interface. Normal app TCP/UDP and game packets bypass the VPN directly over Wi-Fi/Mobile Data with zero tunneling overhead.
- **6 Independent Protection Categories**:
  - Advertising Domains
  - Tracking & Analytics Domains
  - Adult Websites
  - Gambling, Betting & Casino Domains
  - Known Malware, Cryptomining & C2 Domains
  - Phishing & Credential Harvesting Domains
- **High-Speed Filtering (`ReversedDomainTrie` + `DomainFilterEngine`)**:
  - Lock-free atomic snapshot hot-swapping (toggle categories or rules without dropping the VPN).
  - Supports hosts files, AdGuard `||domain^` rules, wildcard subdomains, and strict RFC 1035 domain validation.
  - **Allowlist Precedence**: Allowlisted domains always override both custom blocklists and category blocklists.
- **Live Blocklist Updates (`BlocklistRepository`)**: Bundled baseline domain lists across all 6 categories in `assets/blocklists/` plus HTTPS network feed updates with local caching and fallback.
- **DNS Caching (`DnsCache`)**: Bounded LRU cache respecting per-record TTL and instant invalidation on rule or resolver changes.
- **Encrypted & Fallback Upstream Resolution (`UpstreamDnsClient`)**: Supports DNS-over-HTTPS (DoH RFC 8484) and UDP port 53 with `VpnService.protect()` socket protection, timeout handling, and automatic fallback resolvers.

### 2. Ping Forge — Gaming DNS Benchmarking & Optimization
- Sends real DNS queries across curated resolvers (Cloudflare, Cloudflare Security, Google Public DNS, Quad9, AdGuard DNS, Cisco OpenDNS, and optional Custom DNS).
- Measures actual median/average/min/max DNS lookup response time, query failures/timeouts, and reliability percentage.
- Automatically recommends (and optionally selects) the fastest reliable resolver, and re-benchmarks on Wi-Fi / Mobile Data transitions when enabled.
- Explicitly distinguishes **DNS lookup time** from **in-game server ping**.

### 3. Operating Modes
- **Protection Mode**: Enforces all enabled protection categories and custom blocklists.
- **Gaming Mode**: Focuses on Ping Forge low-latency DNS routing and caching while bypassing category blocklists to avoid blocking game telemetry/CDN endpoints.
- **Balanced Mode**: Combines active DNS protection with Ping Forge optimal resolver routing.

### 4. Persistent Settings & Creator Profile
- All preferences, rules, counters, query logs, and benchmark results persist via Jetpack DataStore and Room, with full **Reset Configuration** support.
- Includes a Creator Profile at the bottom of Settings linking to `@unfiltered.java` (`https://www.instagram.com/unfiltered.java/`).
  - **To install your personal Creator Photo**: place your square photo at `app/src/main/res/drawable/creator_photo.jpg` (or `.png`) and swap `R.drawable.creator_avatar_placeholder` to `R.drawable.creator_photo` inside `CreatorProfileCard` in `SettingsScreen.kt`.

---

## Build Instructions

1. Ensure JDK 11+ and Android SDK (API 36) are installed.
2. Clone the repository and build the debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
   *(In this cloud container environment, use `gradle assembleDebug` or `gradle :app:testDebugUnitTest`.)*
3. Run unit & Robolectric CUJ tests:
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```

---

## Known Limitations & Platform Notes
- **Single Active VPN Constraint**: Android allows only one active `VpnService` at a time. Enabling another VPN app will revoke Ping Forge's VPN interface (handled cleanly with status & error reporting).
- **Encrypted/Hardcoded App DNS**: Apps that bypass system DNS by hardcoding IP addresses or internal DoH endpoints cannot be intercepted by a non-root local DNS VPN.
- **DNS Speed vs Game Ping**: Changing DNS resolvers optimizes domain name resolution speed and reliability; it does not alter ISP routing or in-game server ping once connected.
- **IPv4 Split-DNS Focus**: The TUN packet parser handles IPv4 UDP port 53 DNS traffic on `10.111.222.1`.
