# Adaptive Mobile Widget Families

## Goal

Sugarlicious Mobile shall offer size-aware glucose, graph, and therapy widgets that use one canonical renderer per family. Resizing changes the information architecture without losing the previous rendered state or showing a loading placeholder. Existing compact widgets and existing per-instance settings remain isolated and intact.

## Scope

This change affects only Mobile home-screen widgets, their configuration screens, picker metadata, rendering, persistence, click handling, previews, migrations, and tests. Wear OS, complications, SugarWear, the G7 collector, notifications, and Mobile in-app graphs remain unchanged.

## Widget picker and provider model

Android exposes one picker entry per `AppWidgetProvider`; it does not expose several preset sizes for one provider entry. Sugarlicious therefore registers lightweight provider presets that all delegate to shared family renderers and shared configuration models.

The picker offers these entries:

- `Glukose` — 2x1 preset
- `Glukose` — 2x2 preset
- `Glukose` — 4x2 preset
- `Glukose + Graph kompakt` — the existing compact combined widget
- `Glukosegraph` — 2x1 preset
- `Glukosegraph` — 2x2 preset
- `Glukosegraph` — 4x2 preset
- the existing therapy widget family

The three glucose presets are one logical family. The three graph presets are one logical family. Provider classes contain no rendering logic.

Android launchers may still permit intermediate physical sizes. The renderer maps the measured host bounds deterministically to the nearest supported layout class instead of stretching a layout. It never crops content to pretend that an unsupported size is supported.

## Glucose family

### 2x1 compact layout

- The outer shape is always a pill, independent of the prior per-instance shape setting.
- Glucose value and canonical trend arrow occupy the left portion.
- The right portion contains age above delta, for example `5m` and `-5`.
- No glucose unit is shown.
- Age and delta remain individually configurable using the existing per-instance size, position, and color controls.

### 2x2 detail layout

- Glucose value and trend arrow are centered in the upper section.
- A centered delta row appears underneath with the existing canonical delta icon on the left, for example `+10 mg/dL`.
- The following centered row displays `5m her`.
- The last centered row displays the current-day in-range percentage as `Im Ziel: 89%`.
- TIR uses canonical history samples from the local calendar day, validated against the configured glucose target range. If there are no valid samples, the row displays `Im Ziel: --` rather than inventing a value.
- The 2x2 layout uses the Android/Samsung system widget corner radius, not the compact pill override.

### 4x2 glucose plus graph layout

- A subtle vertical separator divides the widget at its visual center.
- The left side uses the 2x2 glucose/detail information style: glucose, trend, icon plus delta/unit, age, and TIR.
- The right side renders the existing canonical widget graph without stretching.
- The graph reuses the same thresholds, sample validation, prediction separation, axis handling, dot styles, and per-instance appearance settings as the graph family.
- The 4x2 layout uses the system widget corner radius.

### Resize behavior

- The measured host bounds determine the layout on every render and after `APPWIDGET_UPDATE_OPTIONS`.
- Resizing never starts a second widget instance and never resets settings.
- App widgets cannot provide a reliable frame-by-frame resize animation across launchers. Sugarlicious instead performs an atomic content replacement: the old render remains visible until the complete new bitmap is ready. No loading screen or blank state is inserted.

## Existing compact combined widget

The existing `GlucoseGraphWidgetReceiver` remains a separate family and is labeled `Glukose + Graph kompakt`. Its rendering, settings isolation, and stored instances are preserved unless a shared bug fix is required by the new family infrastructure.

## Graph family

### Supported layouts

The picker exposes 2x1, 2x2, and 4x2 graph presets. Intermediate launcher dimensions map to the nearest of these three aspect/layout classes. The graph always uses the maximum safe plot area for the selected class.

### Scale and age label

- Every graph layout displays a combined scale/age label at the top-left of the graph area, aligned consistently with the current compact 2x2 graph treatment.
- The label uses the existing short age format such as `5m`.
- When the confirmed high-region background is active and yellow, the label switches to a dedicated per-instance high-background text color. The default is near-black; settings expose the same system color picker used elsewhere.
- Outside the yellow high region, the normal configured graph-label color is used.

### Click behavior

- The scale/age label has its own click target and cycles through the existing supported graph scale modes in canonical order. After the last mode it returns to the first.
- The selected scale mode is persisted per widget instance and the affected instance refreshes immediately.
- Every point outside that click target opens the per-instance configured launch application.
- Click targets remain separate and do not overlap.

## Therapy family

- A 2x1 host displays at most two enabled indicators. They are equal in size, centered, symmetric, and use equal outer and inner spacing.
- A 2x2 host displays at most three enabled indicators. One is centered in the top row; two are centered in the bottom row. All three use equal sizing and balanced spacing.
- The existing per-indicator enable switches determine which indicators are used in the existing canonical order.
- If only one indicator is enabled it is centered. If two are enabled they are centered symmetrically in either size. Disabled indicators do not leave empty slots.
- Existing colors, icons, progress geometry, values, and settings remain unchanged.

## State, persistence, and migration

- Every physical widget instance retains independent settings.
- New provider presets resolve to the correct logical family without duplicating preference namespaces.
- Existing widget IDs and provider classes remain valid after update.
- New settings receive explicit defaults through a versioned migration. Missing keys are handled defensively and cannot crash the settings activity.
- Resizing does not alter unrelated settings.
- The graph scale-cycle action updates only its target widget ID.

## Rendering and freshness

- All displayed values come from the existing canonical `TherapyDisplayState` and validated history. No renderer computes an alternative glucose source.
- Stale and signal-loss presentation continues to preserve the last valid value and trend according to the existing system contract.
- New rendering is completed off the visible state and then published atomically. The old bitmap remains visible while work is in progress.
- Widget updates remain event-driven; resizing or scale cycling triggers only the affected instance.

## Settings and previews

- Settings remain per widget instance.
- The glucose family settings preview follows the currently measured/configured preset size and renders the appropriate 2x1, 2x2, or 4x2 form.
- Graph previews include the scale/age label and high-background contrast color.
- Existing Mobile control styles, color picker, sliders, reset behavior, and naming are reused.
- The 2x1 glucose pill rule is presented as fixed rather than as an ineffective shape option.
- The new glucose, graph, and therapy families use automatic geometry: 2x1 is always a pill; every larger size uses the Samsung-provided rounded widget radius, matching the compact 2x2 reference. Their settings do not expose a pill/standard selector.
- Pre-existing provider entries remain available for compatibility and are clearly prefixed `Developer Widgets` in the picker; the new families use their product labels.
- Every widget settings screen ends with one floating dark pill containing `Abbrechen | Speichern`, separated by a vertical divider.
- Settings edits remain local while the screen is open. `Abbrechen` discards the complete edit session; `Speichern` persists the complete configuration for exactly that widget instance, refreshes it immediately, and closes the screen.
- Back navigation behaves like cancellation and never silently persists a partial edit.

## Testing

Automated tests shall cover:

- provider-to-family resolution for every new preset;
- picker metadata, labels, target dimensions, and existing-provider compatibility;
- deterministic measured-size classification including boundary values;
- forced 2x1 glucose pill and system-radius 2x2/4x2 layouts;
- exact 2x1 age/delta text contract without unit;
- exact 2x2 delta icon/unit, `5m her`, and TIR rows;
- TIR calculation for current-day valid samples and the unavailable state;
- 4x2 divider and left/right region geometry;
- graph scale/age label geometry and yellow-background contrast selection;
- per-instance scale cycling, wraparound, isolated persistence, and immediate refresh;
- non-label widget click retaining the configured launch target;
- therapy layouts for one, two, and three enabled indicators;
- no loading placeholder during resize or update;
- configuration migration and missing-color resilience;
- explicit save versus cancel behavior for the shared settings action pill;
- render smoke tests at Samsung-reported 2x1, 2x2, and 4x2 dimensions.

## Validation

Before installation, run Mobile unit tests, affected integration/render tests, `assembleDebug`, lint, detekt, and ktlint. Install on the connected Samsung phone and verify picker entries, all three glucose sizes, all three graph sizes, scale-label cycling, launch-target clicking, settings isolation, and therapy layouts. Hardware observations remain separate from code/build validation.

## Acceptance criteria

The work is complete when the three glucose presets and three graph presets are directly selectable, every family renders the required information at each size without clipping or loading placeholders, scale cycling is isolated and persistent, therapy layouts obey their capacity rules, existing instances and compact widgets remain intact, settings cannot crash during migration, all relevant gates pass, and the built APK has been installed for hardware validation.
