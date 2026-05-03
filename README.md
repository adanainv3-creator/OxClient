# OxClient

**Minecraft Bedrock Android MITM Client — Target: 2b2tpe.org**

Hybrid Java + Kotlin · Netty NIO UDP Proxy · Jetpack Compose Material3

---

## Modules

| Module    | Category | Description |
|-----------|----------|-------------|
| AutoTotem | Combat   | Auto-equips Totem of Undying to off-hand |
| KillAura  | Combat   | Packet-level auto-attack (CPS, Range, FOV, Rotation) |
| Criticals | Combat   | Forces 1.5× critical hits (TPJump/MovePacket/Jump) |
| TPAura    | Movement | Teleports around target (Random/Strafe/Behind/Speed) |

---

## Project Structure

```
OxClient/
├── .github/workflows/build.yml          ← GitHub Actions APK build
├── settings.gradle.kts
├── build.gradle.kts
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/oxclient/
        │   ├── core/
        │   │   ├── vpn/OxVpnService.java       ← TUN intercept, no root
        │   │   ├── proxy/MitmProxy.java         ← Netty NIO UDP MITM
        │   │   ├── proxy/PacketProcessor.java   ← Event bus dispatcher
        │   │   ├── proxy/PacketFactory.java     ← Packet builder/injector
        │   │   ├── proxy/PacketIds.java         ← Bedrock 1.21 packet IDs
        │   │   ├── raknet/RakNetSession.java    ← RakNet framing decoder
        │   │   └── entity/EntityTracker.java    ← Live entity mirror
        │   ├── events/PacketEvent.java
        │   └── utils/BinaryUtils.java
        └── kotlin/com/oxclient/
            ├── OxClientApp.kt
            ├── events/PacketEventBus.kt         ← Priority event bus
            ├── session/SessionManager.kt
            ├── module/
            │   ├── BaseModule.kt                ← Lifecycle + settings DSL
            │   ├── ModuleManager.kt             ← Registry + DataStore
            │   └── combat/
            │       ├── KillAura.kt
            │       ├── Criticals.kt
            │       └── AutoTotem.kt
            │   └── movement/
            │       └── TPAura.kt
            └── ui/
                ├── theme/OxTheme.kt
                ├── dashboard/DashboardActivity.kt
                ├── dashboard/MicrosoftAuthManager.kt
                └── overlay/
                    ├── OverlayService.kt
                    ├── OverlayState.kt
                    └── OverlayNotifier.kt
```

---

## GitHub Actions — Auto APK Build

Push to `main` → GitHub Actions builds APK automatically.

**Workflow file:** `.github/workflows/build.yml`

Every push produces a **debug APK** (no secrets needed).
A **release APK** is built on `main` if you add signing secrets.

### Repository Secrets (for signed release APK)

| Secret         | Description |
|----------------|-------------|
| `KEYSTORE_PATH`| Path to keystore file in repo |
| `KEYSTORE_PASS`| Keystore password |
| `KEY_ALIAS`    | Key alias |
| `KEY_PASS`     | Key password |

---

## Setup

### 1. Clone
```bash
git clone https://github.com/YOU/OxClient
cd OxClient
chmod +x gradlew
```

### 2. MSAL (Microsoft Auth)
1. Create Azure App Registration → https://portal.azure.com
2. Add Android platform with package `com.oxclient`
3. Get base64 key hash:
   ```bash
   keytool -exportcert -keystore ~/.android/debug.keystore -alias androiddebugkey | openssl sha1 -binary | openssl base64
   ```
4. Edit `app/src/main/res/raw/msal_config.json`:
   ```json
   { "client_id": "YOUR_CLIENT_ID", "redirect_uri": "msauth://com.oxclient/YOUR_BASE64" }
   ```
5. Update `AndroidManifest.xml` MSAL `<data android:path="...">` with same base64.

### 3. CloudburstMC Protocol (snapshot)

Already configured in `settings.gradle.kts` via `repo.opencollab.dev`.

For offline builds, publish locally:
```bash
git clone https://github.com/CloudburstMC/Protocol
cd Protocol && ./gradlew publishToMavenLocal
```

### 4. Local build
```bash
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

---

## Adding a new module

```kotlin
class MyModule : BaseModule(
    name        = "MyModule",
    description = "Does something cool",
    category    = ModuleCategory.MISC
) {
    val speed = floatSetting("Speed", 0f, 5f, 1f)

    override fun onEnable() { /* start */ }
    override fun onDisable() { /* stop  */ }
    override fun onPacketReceive(event: PacketEvent) { /* S→C packets */ }
    override fun onPacketSend(event: PacketEvent)    { /* C→S packets */ }
    override suspend fun onTick() { /* 20 TPS */ }
}
```

Register in `ModuleManager.registerAll()`:
```kotlin
register(MyModule())
```

---

## Disclaimer
Educational purposes only.
