# Changelog

All notable changes to Peachy Android are recorded here. This file starts with the native
interface rebrand; earlier entries are not reconstructed.

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
