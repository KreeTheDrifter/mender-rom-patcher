# Mender: ROM patcher for Android

Applies IPS, BPS and UPS patches to files on your phone. Free with ads; a one-time
"Remove ads" purchase turns them off.

- Pick a ROM and a patch with Android's file picker, tap **Patch and save**, choose where to save.
- BPS and UPS patches are checked before patching, so the app can tell you up front
  whether your ROM is the right version (CRC32 match). ROMs with a 512-byte copier header
  are handled, and a UPS patch applied to an already-patched ROM restores the original.
- Works offline. Files never leave the device.

## Building

There's nothing to install: every push to `main` builds the app on GitHub
(`.github/workflows/build.yml`) and publishes the results under **Releases**:

| File | Use |
| --- | --- |
| `Mender-test-N.apk` | Install on a phone to try it. Always shows test ads. |
| `Mender-play-store-N.aab` | Upload to Google Play Console. |
| `Mender-release-N.apk` | The store build as an APK, for checking real ads. |

Repository settings (Settings → Secrets and variables → Actions):

| Name | Kind | Value |
| --- | --- | --- |
| `MENDER_KEYSTORE_PASSWORD` | Secret | Password of `keystore/upload.jks` (the Play upload key). Without it, only the test APK is built. |
| `ADMOB_APP_ID` | Variable | AdMob app ID, `ca-app-pub-…~…`. Without it, test ads are shown. |
| `ADMOB_BANNER_ID` | Variable | AdMob banner ad unit ID. |
| `ADMOB_INTERSTITIAL_ID` | Variable | AdMob interstitial ad unit ID. |

The in-app product for removing ads must be created in Play Console with the product ID
`remove_ads`.

## Code

| Path | What |
| --- | --- |
| `app/src/main/java/.../patch/Patcher.java` | IPS, BPS and UPS patching (plain Java, no Android code) |
| `app/src/test/` | Patch tests against fixtures made with RomPatcher.js |
| `app/src/main/java/.../MainActivity.java` | The screen |
| `app/src/main/java/.../Ads.java` | AdMob banner and interstitial, with Google's consent form |
| `app/src/main/java/.../Billing.java` | The "Remove ads" purchase (Play Billing) |
| `docs/` | Website and privacy policy (GitHub Pages) |

Copyright © 2026 KreeTheDrifter. All rights reserved.
