# Wear settings follow-up — deferred

The user explicitly deferred implementation until after the Mobile header correction and the collector reliability/efficiency investigation. Only a read-only inventory was performed; no further Wear presentation changes belong to the header correction.

## Remaining coverage

- Sugarlicious Wear: all categories in WearSettingsActivity (display, glucose, trend, delta, therapy, graph, tiles, watchfaces, connection, diagnostics, about), choice rows, sliders, number entry and picker presentation.
- SugarWear: G7SettingsActivity, G7AppearanceActivity, G7AlarmSettingsActivity, G7DirectToWatchSettingsActivity, G7SystemStatusActivity, inline pairing/unlink surfaces and threshold editors.
- Shared: coherent semantic typography, neutral meaningful groups without nested same-color cards, header/navigation/action/selection vocabulary, round-safe spacing and scalable text. Existing scroll arbitration and rotary handling must be preserved.
- SharedColorEditor **already routes Wear devices to full-screen WearColorPicker**; its Mobile AlertDialog branch is not the Wear path. SharedNumberEditor still has a generic dialog, as do local threshold editors.
- Systemstatus still has a giant diagnostic card and small independent typography. Grouping changes in the previous release did not constitute a complete visual modernization of every submenu.

Use Samsung Health settings on the connected Galaxy Watch as a visual reference. Its currently selected font scale was read as 0.9, display density 340. No system font setting was changed. Preserve all stored values, validations, callbacks and collector behavior; verify normal and enlarged system font layouts before declaring full coverage.
