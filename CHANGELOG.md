# Changelog

All notable changes to Peachy Android are recorded here. This file starts with the native
interface rebrand; earlier entries are not reconstructed.

## 0.9.4

- **Manga.** Expand likely translations in the catalog. Confirm two editions as the same chapter to share a read mark without changing either reading position. Other chapters stay unread; manual links, read marks and rejected suggestions survive library backup and restore.
- **Manga.** Read and Continue have their own row on the details screen. The section menu scrolls so Random gallery stays reachable on short screens.
- Opening an artist or tag from gallery details now keeps the gallery and earlier search results in Back history.

## 0.9.3

- **Settings and lock.** Manga, translation, device control and browser audio capture are opt-in, including after upgrading. Turning them off keeps saved items. Release builds retain screenshot protection.
- **Session restore (#34).** After unlocking, return to the saved screen or browser tab. Browser Back follows page history before switching tabs or leaving the browser.
- **Manga details and reader (#36–37).** Copy the full title, even when it is truncated onscreen. Translation plates use detected light balloons or source text bounds, avoid duplicate OCR groups and rewrap on zoom. The panel keeps the complete saved translation even when a plate cannot fit.
- **OCR translation.** Text requests no longer impose a 4096-token ceiling. Unusable truncated responses report the provider’s output limit; the reader can wait up to four minutes for response data without resending a timed-out page.

## 0.9.2

- **Player.** Library, Documents and Browser are now one tap away in the top bar. Queue sorting and filtering remain below the bar.
- **Gallery.** Search moves to its own row when the hint would be clipped; the empty-library message is centered.
- **Documents.** Sort by date, name or size, or filter by file size.

## 0.9.1

- **Manga translation.** Tap bubble text to translate a single block into the selected language. On-page translations stay within the original text bounds; when no readable line fits, the reader opens the full text in the panel.

## 0.9.0

- **Manga recommendations.** In **For you**, **Already read** adds a gallery to reading history
  and counts it as a completed read for future picks. **Not interested** now ranks similar
  galleries lower even before you have marked anything you like.
- **Version numbering.** This release follows 3.7.1. The Android version code increases from
  14 to 15; install the signed APK over the old app once to switch to the new numbering.

## 3.7.1

- **Browser navigation.** Android Back walks the current page history first. At its root, Back
  returns to the previously selected tab without closing either tab. The browser exits only
  when there is no page or tab left to go back to.
- Recreating the browser activity no longer opens a second copy of the original launch link.

## 3.7.0

### Added

- **Documents.** Import files into a separate workspace. Edit a working copy and export the
  result without changing the source file.
- **PDF.** Search and copy text, add annotations, fill supported form fields, and insert, import,
  reorder, rotate or delete pages. Export text, page images or a PDF copy. Replace one line of
  existing text where it fits and the editor supports it, without reflow or OCR of scans.
  Drawn signatures are annotations; a digital signature requires a PKCS#12 (`.p12` or `.pfx`)
  file with a private key and certificate. Editing a signed PDF prompts for confirmation
  because it can invalidate existing signatures. PDF imports are limited to 100 MiB and 5,000 pages.
- **Image editor.** Crop, rotate at any angle, adjust and draw on imported images, with undo
  and redo. Export as PNG or JPEG; for animated WebP, only the first frame is edited and exported.
- **Office imports.** Supported Word, Excel and PowerPoint files can be imported and listed in
  Documents, but the standard Android release cannot preview or edit their contents.

### Changed

- **Interface.** New Peachy icon, dark theme and Onest typeface across onboarding, lock screen,
  player, gallery, browser, manga and settings.
- **Gallery.** Valid changes to sorting and size filters apply immediately. Photos open in the
  gallery viewer; to edit an image, import it in Documents. Compression and folder export in
  the gallery are still video-only.
- **Browser.** The Brave-powered blocker adds filter subscriptions, custom rules, controls for
  hiding page elements, and per-page and lifetime block counts. Failed filter updates keep the
  previous working rules. Filtering covers only requests and page features exposed by Android
  System WebView; older providers may apply element hiding later or lack dynamic selectors.
- **Browser privacy.** Tab state is encrypted. Clear site data closes tabs and clears WebView
  cookies and cache without deleting bookmarks or the visit list. On supported WebView versions
  it also clears Cache Storage; older providers may leave some site data. Page-audio capture is opt-in.
- **Connections.** Intiface verifies TLS and allows plaintext only on loopback. In-app media
  downloads validate public HTTPS destinations at every hop and drop private headers across origins.

### Fixed

- **Library.** Android Share uses the incoming video's display name when available. Incomplete
  vault imports no longer leave partial library entries. Saved manga pages, video covers and
  compression output are written to temporary files before publication.
- **Manga.** A superseded catalog search no longer replaces current results or clears the newer
  search's loading indicator. Fit-width pages restore their scroll position in paged and
  continuous readers; an explicit jump still starts at the top of the page.
- **Browser navigation.** Site checks use the Public Suffix List, so separate hosted sites such
  as two `github.io` tenants are treated as different sites. Top-level cross-site POST requests
  exposed by WebView are blocked instead of being replayed as GET requests.
- **Encrypted database.** SQLCipher now uses the Android library that supports 16 KiB memory
  pages, retaining the existing database and key. Unsupported migrations no longer recreate
  the database.
