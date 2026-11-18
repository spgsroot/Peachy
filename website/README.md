# Peachy landing page

Static landing page for [Peachy](https://github.com/spgsroot/Peachy), a local-first media workspace for Android. Canonical website: [peachyproject.org](https://peachyproject.org/), as declared in the upstream Android README. Svelte 5 + Vite; no runtime dependencies and no third-party requests.

Requires Node.js 20.19+ or 22.12+ (Vite 8).

## Commands

```sh
npm install
npm run dev      # http://127.0.0.1:5173
npm run build    # writes dist/
npm run preview  # serves dist/ on 127.0.0.1
npm run check    # svelte-check
```

The header switches between English, Russian and Simplified Chinese. The first visit uses a supported browser language or falls back to English. A manual choice is saved locally when browser storage is available.

## Design system

[`DESIGN.md`](DESIGN.md) describes the current web design: local Onest, the sculpted graphite P, a near-black and dusty-pink palette, the phone preview and the responsive selector. The older `.impeccable/design.json` panel sidecar does not describe this refinement.

## Deployment

`npm run build` produces a plain static site in `dist/`. Serve that directory from any static host at the domain root: asset URLs are root-relative (`/assets/...`). No server-side rendering, redirects or rewrite rules are required. Use immutable caching only for Vite's content-hashed JS/CSS filenames; HTML and the stable filenames copied from `public/assets/` need revalidation or short-lived caching so later deployments can replace them.

## Privacy limits

The website itself is static. It loads no analytics, remote fonts, tracking pixels or third-party scripts; the typeface and images are served from this site.

The Android app is local-first, not offline-only. Its browser contacts sites and filter-list publishers; the manga catalog contacts nhentai. Optional translation sends page images or locally recognised text to the selected provider after host consent. Update checks contact GitHub; automatic daily checks are off by default. A numeric password locks the interface, while SQLCipher encrypts the database with a separate Keystore-protected key. Media files are not individually encrypted by Peachy and depend on Android's sandbox/device encryption. The site makes no anonymity or compromised-device protection claim. Download/source links open GitHub under its own policies.

## Android feature claims

The three locale files describe the same Android capabilities. The browser uses a source-built native Brave filter engine, not the Brave browser; filtering is subject to WebView interception limits. Local photos and videos share the library, but compression and gallery folder export are video-only. PDF and supported-image editing are separate from Office support: Word, Excel (including legacy XLS) and PowerPoint files can be imported/listed, but `OfficeIntegration.ENABLED` is false even when a debug APK packages the optional LibreOffice engine. Do not promote engine packaging to a shipped editor claim.

The [Android source](https://github.com/spgsroot/Peachy) is the reference for feature claims, in particular its README files, `native/adblock/README.md`, `DocumentFormats.kt` and `OfficeEntryPolicy.kt`. Desktop and alternative networks remain planned/research, not available builds or integrations.

## Assets and provenance

The site uses self-hosted Latin and Cyrillic Onest subsets ([SIL Open Font License 1.1](public/assets/onest-license.txt)) and system fonts for Chinese. The sculpted P and capability illustrations derive from user-supplied originals. Library and Player use Android app captures. Browser shows a [NASA Science article on the Webb telescope's image of a forming star](https://science.nasa.gov/missions/webb/nasas-webb-captures-celestial-fireworks-around-forming-star/), captured in Chromium rather than the Peachy browser. [`public/assets/provenance.json`](public/assets/provenance.json) records image sources and demo-photo credits.

The landing page's own license is not declared here. See the [upstream Android repository](https://github.com/spgsroot/Peachy) for the app's AGPL-3.0 license.
