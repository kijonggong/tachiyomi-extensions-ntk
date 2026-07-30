# NTK — extension source

Source for the NTK Mihon/Tachiyomi extension published from this repository's
`main` branch. `main` holds the built artifacts (`apk/`, `index.min.json`,
`repo.json`, `icon/`) that Mihon reads; this branch holds the code they are
built from.

## Layout

    src/ko/ntk/build.gradle.kts    extension metadata (name, versionCode, sources)
    src/ko/ntk/res/                launcher icons
    src/ko/ntk/src/                Ntk.kt — the source implementation

## Building

Drop `src/ko/ntk/` into a checkout of
[keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source)
and run:

    ./gradlew :src:ko:ntk:assembleRelease

The build emits the APK, a signed JAR, and `keiyoushi-source-info.json`
(metadata used to regenerate `index.min.json` on `main`).

Signing needs `signingkey.jks` plus `KEY_STORE_PASSWORD` / `KEY_PASSWORD` /
`ALIAS`. **The published key must not change** — installs already in the wild
refuse an update signed by a different key.

## Notes for future maintenance

- **Domain.** The shipped default lives in `DOMAIN_DEFAULT`. Users can override
  it in the source's settings; both sources share one value via the
  `source_ntk_shared` preference file, so changing it once covers both.
- **Source ids are pinned** in `build.gradle.kts` and mirrored in `Ntk.kt`.
  They are the ids v19 shipped. Letting them regenerate hands users new source
  ids and detaches their libraries — do not "clean these up".
- **Chapter images are browser-only.** The site injects them client-side, so
  `pageListParse` deliberately throws with a message pointing at WebView.
- **Networking.** DNS prefers IPv4 because Korean ISPs SNI-block the Cloudflare
  IPv6 route, and the rate limit is scoped to the main host so image requests
  are not throttled.
