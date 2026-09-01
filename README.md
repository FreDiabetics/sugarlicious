# Sugarlicious

Sugarlicious ist eine strikt lesende Android-/Wear-OS-Suite für zuverlässige CGM- und AndroidAPS-Statusdaten auf Smartphone und Uhr. Im Mittelpunkt stehen Datenintegrität, eindeutige Quellenzuordnung, nachvollziehbare Freshness-/Fehlerzustände und ein gemeinsamer kanonischer Datenpfad für UI, Watchfaces, Complications und Alarme.

Sugarlicious ist kein Medizinprodukt und sendet keine Therapiekommandos an AndroidAPS oder eine Pumpe.

## Architektur

Der primäre Datenweg lautet:

`AndroidAPS → Sugarlicious Mobile → Wear Data Layer → kanonischer Resolver → Watch UI / Complications / Watchfaces`

Zusätzlich enthält das Projekt einen direkten Dexcom-G7-Collector ausschließlich für Wear OS:

`Dexcom G7 → G7 Direct to Watch → kanonischer Resolver`

Wichtige Architekturregeln:

- kein eigener G7-BLE-Collector auf Sugarlicious Mobile,
- Mobile- und Watch-Rohdaten bleiben getrennt,
- nur der zentrale Source Resolver bestimmt die aktive Quelle,
- alte, doppelte oder ungültige Daten dürfen keine frischeren gültigen Werte überschreiben,
- Stale-/NO_SOURCE-/Fehlerzustände werden explizit dargestellt,
- Complications, Watchfaces und Alarme verwenden denselben validierten Datenlayer.

## Enthalten

- Mobile Bridge für lokale AndroidAPS-Statusdaten,
- Wear-App mit Data-Layer-Persistenz und Source Resolver,
- Complication-Provider und Watchfaces,
- AndroidAPS-nahe Graph-/Statusdarstellung,
- direkter Dexcom-G7-Watch-Collector als getrennt auslagerbarer Bestandteil,
- Diagnose-, Qualitäts-, Deduplizierungs- und Recovery-Logik,
- CI für Tests, APK-Builds und Watch-Face-Validierung.

## Entwicklungsstatus

Mobile, Wear, Resolver, Complications und Watchfaces werden gemeinsam weiterentwickelt. Der direkte G7-Watch-Collector ist weiterhin **hardware-gated**: Pairing, KEKS-Authentifizierung, Bonding und der Empfang eines echten G7-Werts wurden auf realer Wear-OS-Hardware nachgewiesen; der robuste automatische Reconnect über mehrere 5-Minuten-Zyklen wird vor dem Merge des Collector-Branches weiter validiert.

Der Collector darf erst als stabil gelten, wenn mehrere aufeinanderfolgende echte Sensorwerte ohne manuellen Eingriff empfangen werden und die parallele Dexcom-App auf dem Smartphone unbeeinträchtigt weiterarbeitet.

## Bauen und installieren

Voraussetzungen:

- Android Studio mit Android SDK 36,
- JDK 21,
- ADB für lokale Geräteinstallation.

Gesamtprüfung:

```powershell
.\gradlew.bat test assembleDebug
```

Zusätzliche Watch-Face-Prüfungen:

```powershell
pwsh -File tools\wff-validator\validate.ps1
pwsh -File tools\verify-codefree-watchfaces.ps1
```

Installation und projektspezifische Hinweise: `docs/INSTALLATION.md`.

Repository klonen:

```powershell
git clone https://github.com/FreDiabetics/sugarlicious.git
```

## Repository-Sicherheit und Backup

`main` soll ausschließlich über Pull Requests mit erfolgreichem CI-Check `verify` geändert werden. Force-Push und Branch-Löschung sollen für `main` gesperrt sein. CODEOWNERS und zusätzliche Recovery-Regeln liegen im Repository.

Ein Backup im selben Repository schützt nicht vor Repository-Löschung. Deshalb ist ein externer vollständiger Git-Mirror vorgesehen. Details: `docs/REPOSITORY_SECURITY.md` und `SECURITY.md`.

Secrets, produktive Tokens, Keystores und private Schlüssel gehören nicht in Git.

## Lizenz und Urheberhinweise

Der Quellcode steht unter der **GNU Affero General Public License v3 (AGPL-3.0)**, soweit in `LICENSES/` für einzelne Bestandteile nichts Abweichendes dokumentiert ist. Drittquellen und Asset-Hinweise sind in `NOTICE.md` und `LICENSES/` aufgeführt.

Die AGPL erlaubt Weitergabe und Veröffentlichung ausdrücklich unter ihren Bedingungen. Eine pauschale Untersagung von Kopien wäre damit unvereinbar. **Nicht zulässig ist eine Weiterveröffentlichung, die die tatsächlich anwendbaren Lizenzpflichten, Copyright-/Lizenzhinweise oder erforderliche Bereitstellung des korrespondierenden Quellcodes missachtet.** Für Logos, Marken oder separat gekennzeichnete Assets können zusätzliche Rechtehinweise gelten.

Kontakt: `typ1.diafreddy@gmail.com`  
GitHub: `FreDiabetics/sugarlicious`
