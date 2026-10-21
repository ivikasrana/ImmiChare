# ImmiShare

[![license](https://img.shields.io/badge/license-MIT%20%2B%20Commercial-blue.svg)](#license)

Android companion for a self-hosted Immich server (built against Immich v3.1.0). Everything works through Immich shared links: no account login and no API key.

## Features

- **Download from a link.** Share an Immich link (`https://host/share/KEY`) from any app to ImmiShare, or paste it on the main screen. You get a thumbnail grid with video badges and durations. Long-press an item to see its file name and size. You can multi-select, use Select all and Clear, and see a running total. Originals download in the background into `Pictures/ImmiShare` or `Movies/ImmiShare`, keeping the original file names and the date taken.
- **Upload to a link.** Share photos or videos to ImmiShare, then pick a saved link or paste a new one. Each link shows whether it allows uploads. Files upload in the background with per-file progress and a final summary (uploaded / duplicates / failed). Uploads to an album link go into that album.
- **Saved links.** Every link you open is saved. You can rename it, change its server address, or delete it.
- **Password-protected links.** ImmiShare asks for the password and keeps the session for later requests.
- **Servers.** Works with HTTPS servers on custom domains (system CAs and user-installed CAs), plain-HTTP LAN servers, ports, and reverse-proxy subpaths such as `https://example.com/immich/share/KEY`.

## Requirements

- JDK 17 or newer (the one bundled with Android Studio works)
- Android SDK with platform 37 (set `sdk.dir` in `local.properties`, or `ANDROID_HOME`)
- A device running Android 11 (API 30) or newer

## Build

```
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew testDebugUnitTest
```

APKs are written to `app/build/outputs/apk/<variant>/`. On Windows you can also run `./build.ps1`, which copies the APKs into `dist/` (`-Install` installs via adb).

### Signing

If there is no `keystore.properties`, `assembleRelease` signs with the Android debug key. That APK installs fine but cannot be updated later by a build signed with a different key. To sign with your own key, create `keystore.properties` in the project root:

```
storeFile=immishare.jks
storePassword=...
keyAlias=...
keyPassword=...
```

To create a key: `keytool -genkeypair -v -keystore immishare.jks -alias immishare -keyalg RSA -keysize 2048 -validity 10000`

## Immich API usage (v3.1.0)

| Purpose | Endpoint |
| --- | --- |
| Link info | `GET /api/shared-links/me?key=` |
| Password | `POST /api/shared-links/login?key=` with `{"password"}`; the `immich_shared_link_token` cookie is sent on later requests |
| Album contents | `GET /api/timeline/buckets` and `/api/timeline/bucket` with `albumId` and `key` (v3 album links return no assets in `/shared-links/me`) |
| Asset details | `GET /api/assets/{id}?key=` |
| Thumbnail | `GET /api/assets/{id}/thumbnail?key=&size=thumbnail` |
| Original | `GET /api/assets/{id}/original?key=` |
| Upload | `POST /api/assets?key=` multipart with `assetData`, `fileCreatedAt`, `fileModifiedAt`, `filename`, `duration` (ms) |

In v3 the upload endpoint no longer has `deviceAssetId`/`deviceId`. The server adds uploads made with a link key to that link's album (or to the individual link) by itself, so the app makes no separate album call. A `200` response means the file is a duplicate and `201` means it was created.

## Permissions

`INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS` (requested when the first transfer starts, Android 13+), `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC`. No `READ_MEDIA_*` permission is needed: shared files come with a temporary read grant, and saving uses MediaStore, which needs no permission on Android 10+.

## License

ImmiShare is dual licensed:

- **[MIT License](LICENSE)**: free for personal, open source and commercial use, with
  attribution and no warranty.
- **[Commercial License](COMMERCIAL-LICENSE.md)**: for use without attribution, with
  warranty, support or custom terms.

Contributions are accepted under the MIT License.
