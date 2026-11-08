# OCB — OTA Control Bridge

A minimal Android app that installs A/B OTA packages on OPPO/Realme devices using an exploited `uid=0` context delivered through a kernel exploit, without requiring an unlocked bootloader.

---

## How It Works

### Overview

OCB operates in three phases: **Exploit → Root session → OTA install**.

```
User taps Exploit
       │
       ▼
Shizuku UserService (uid=2000, shell)
       │
       ├── 1. Install timer binary to /data/local/tmp/timer_GKI
       │
       ├── 2. Probe for existing su socket
       │       └─ if found → use existing root session
       │
       └── 3. Launch timer fake exploit
               │
               ▼
        timer_GKI fake    (kernel exploit: fake cred write)
               │  uid=0, SELinux Permissive
               ├── Write su wrapper to /data/local/tmp/su
               ├── Start su_server (Unix socket daemon)
               ├── Mount tmpfs over /my_engineering/etc/security
               └── Write stock otacerts.zip to new mount
                          │
                          ▼
                 User taps Install
                          │
                          ▼
               Shizuku → IUpdateEngineStable.applyPayloadFd()
                          │
                    update_engine installs OTA
                          │
                          ▼
                   Reboot → new slot active
```

### Exploit (timer fake)

The exploit (`timer_GKI`) gains `uid=0` by writing fake credentials into a child task through a kernel memory race. Once the privileged child runs:

1. `setenforce 0` — sets SELinux to Permissive
2. Starts `su_server` — a Unix socket daemon at `/data/local/tmp/su.sock` that runs commands as root
3. Mounts a `tmpfs` over `/my_engineering/etc/security` and writes `otacerts.zip` so `update_engine` accepts the OTA's signature

The exploit parks itself after success (zombie held) until the device reboots to clean up kernel state.

### OTA Install

After the root session is ready, the install path:

1. Opens the OTA zip and reads `payload_properties.txt` for `FILE_HASH`, `FILE_SIZE`, `METADATA_HASH`, `METADATA_SIZE`
2. Calls `IUpdateEngineStable.applyPayloadFd()` via the Shizuku UserService, passing a `ParcelFileDescriptor` pointing directly at the zip — no copy needed
3. Subscribes to status callbacks (`onStatusUpdate` / `onPayloadApplicationComplete`) for live progress
4. On success, prompts reboot — `update_engine` switches the active slot

### Cert handling

By default the app uses **Stock mode** — no cert is planted, trusting the device's existing `otacerts.zip`. The Settings menu exposes:

| Mode | Behaviour |
|---|---|
| Stock (default) | No cert planted; use device's existing OTA cert |
| Custom | Pick a `.zip` from storage; planted at install time |

### Transport: Shizuku

All privileged operations go through a Shizuku `UserService` running as `uid=2000` (shell). The service exposes:

- `install(apkPath)` — extract and install the timer binary from APK assets
- `execShell(cmd)` — run a shell command, return stdout (capped at 20 KB)
- `startUpdate(path, offset, size, headers, cb)` — submit OTA payload to `update_engine_stable`
- `cancelUpdate()` — cancel + `resetStatus()` to return engine to IDLE
- `rebindCallback(cb)` — re-attach to an in-progress OTA after app restart
- `rebootDevice()` — `svc power reboot`

---

## Requirements

| Item | Version |
|---|---|
| Android SDK | API 36 (build-tools 37.0.0) |
| Java | 11+ |
| Shizuku | 13+ running on device |
| Device | OPPO/Realme, Android 11–15 (A/B partition) |

---

## Project Layout

```
OCB/
├── app/
│   └── src/main/
│       ├── java/com/bhlnk/ocb/
│       │   ├── MainActivity.java      # UI + OTA flow
│       │   ├── UserService.java       # Shizuku UserService (privileged ops)
│       │   └── AdbBridge.java         # ADB shell transport (fallback)
│       ├── aidl/com/bhlnk/ocb/
│       │   ├── IUserService.aidl      # Service interface
│       │   └── IUpdateCallback.aidl   # Progress callbacks
│       ├── assets/
│       │   └── timer_GKI              # Kernel exploit binary (arm64)
│       ├── res/                       # Drawables, layouts (code-generated UI)
│       └── AndroidManifest.xml
├── Shizuku-API/                       # Shizuku client library (git submodule)
└── scripts/
    └── build.sh                       # Standalone build (no Gradle)
```

---

## Building

### Prerequisites

```bash
# Install Android SDK command-line tools, then:
sdkmanager "platforms;android-36" "build-tools;37.0.0"

# Java 11+
java -version
```

### Clone

```bash
git clone --recurse-submodules <repo-url> OCB
cd OCB
```

If you cloned without `--recurse-submodules`:
```bash
git submodule update --init --recursive
```

### Build (standalone — no Gradle needed)

```bash
bash scripts/build.sh
# Output: build/VSOCB-3.2.apk
```

The script uses `aapt2 → javac → d8 → zipalign → apksigner`. It signs with the Android debug key (`~/.android/debug.keystore`) and auto-generates one if it does not exist.

### Custom signing

```bash
KEYSTORE=/path/to/release.jks \
STORE_PASS=changeme \
KEY_ALIAS=mykey \
KEY_PASS=changeme \
bash scripts/build.sh
```

### Version override

```bash
VERSION_CODE=100 VERSION_NAME=1.0 bash scripts/build.sh
```

### Install

```bash
adb install -r build/VSOCB-3.2.apk
```

---

## Integrated Build (Shizuku Next)

For a combined APK that embeds the Shizuku server (no separate Shizuku app required):

```bash
# Clone Shizuku Next alongside OCB
git clone https://github.com/RikkaApps/Shizuku .integrated-cache/shizuku-next

# Overlay OCB onto it
python3 scripts/prepare-integrated.py .integrated-cache/shizuku-next

# Build with Gradle
cd .integrated-cache/shizuku-next
./gradlew :manager:assembleDebug
```

---

## Permissions

| Permission | Purpose |
|---|---|
| `moe.shizuku.manager.permission.API_V23` | Bind to Shizuku UserService |
| `INTERNET` | (unused in this build) |
| `REBOOT` | Declared for `svc power reboot` |

---

## Notes

- The exploit leaves a parked zombie process until reboot — this is expected (`[!] fake cred page held; reboot to clean`).
- Cancelling an OTA calls `cancel()` then `resetStatus()` so the engine returns to IDLE immediately and the next install is accepted without a reboot.
- Re-opening the app while an OTA is in progress automatically re-attaches via `IUpdateEngineStable.bind()` and resumes the progress view.
