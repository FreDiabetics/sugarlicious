<p align="center">
  <img src="design-assets/app-icons/logo_big.svg" width="220" alt="Sugarlicious" />
</p>

<h1 align="center">Sugarlicious</h1>

<p align="center">
  The signal is becoming a system.<br />
  Something larger is taking shape.
</p>

Sugarlicious is a local-first Android and Wear OS monitoring suite. It accepts
read-only glucose and therapy state from supported sources, normalizes that
state once, and renders it consistently across Mobile, Wear, Tiles,
Complications, Notifications and code-free Watch Face Format packages. The
project does not issue therapy, pump or loop commands.

## Start here

- [Installation and local deployment](docs/INSTALLATION.md)
- [Architecture and ownership](docs/ARCHITECTURE_INDEX.md)
- [Data contract](docs/AAPS_DATA_CONTRACT.md)
- [Privacy and security](docs/PRIVACY.md)
- [Quality gates and developer workflow](docs/QUALITY_GATES.md)
- [Release checklist](docs/RELEASE_CHECKLIST.md)

## Repository shape

| Area | Responsibility |
|---|---|
| `core-model` | Canonical CGM identity, freshness and presentation policy |
| `data-source-*`, `dexcom-g7` | Source-specific adapters and collector protocol |
| `wear-protocol`, `wear-storage` | Cross-device contract and durable local state |
| `app-mobile`, `app-wear`, `g7watch` | Android and Wear OS application surfaces |
| `tiles`, `complications`, `watchfaces` | Platform renderers and WFF packages |
| `tools` | Validation, release and visual-regression tooling |

## Build and verify

Use JDK 21 and Android SDK 36. On Windows, the complete local software gate is:

```powershell
.\gradlew.bat test assembleDebug lint --continue --no-daemon --no-problems-report
.\tools\wff-validator\validate.ps1
.\tools\verify-codefree-watchfaces.ps1
```

Real-device behavior is a separate gate. A successful build does not establish
Bluetooth reliability, AOD appearance, round-display clipping, battery impact
or multi-device handoff behavior; those results must be recorded with the exact
device, build and observation interval.
