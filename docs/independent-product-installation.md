# Independent product installation

Run one command for the product you want from the repository root in the Android Studio terminal.

Sugarlicious Mobile and Wear companion (one connected phone and one connected watch):

```powershell
.\gradlew.bat installSugarliciousDebug
```

SugarWear collector and its Vigil watchface (one connected watch; no phone needed):

```powershell
.\gradlew.bat installSugarWearDebug
```

The installers update only their own product APKs. They do not uninstall or clear another product. Sugarlicious does not install SugarWear, Vigil, or separate watchface packages. Its optional Push catalog contains Digital, ApeX and Orbit; Vigil belongs only to SugarWear. All four watchfaces remain in the build and validation matrix.

Public Android package identities are `app.sugarlicious` (Mobile/Wear), `app.sugarwear` and `app.sugarwear.watchfacepush.vigil`. Optional Sugarlicious faces use `app.sugarlicious.watchfacepush.apex`, `.orbit` and `.digital`.

Existing installations under the former IDs need the one-time signed migration in `tools/product-migration/migrate_products.py` before using the normal installers. It transfers settings, history and decrypted credentials into the new app's protected storage, then disables the old package without deleting its data. The tool requires explicitly identified devices and cached legacy bridge APKs built with `-PlegacyProductMigration=true`. Ordinary installers reject that build flag. Native widget and complication bindings belong to Android package identities and must be added or assigned again after migration; sensor pairing itself is preserved.
