# Changelog

All notable changes to this project are recorded here. This covers the website; the upstream Android application has its own releases.

## Unreleased

### Changed

- Added English, Russian and Simplified Chinese with a saved language choice. The footer links to GitHub and LLMCrew.org with a localized “From Russia with Love” signature. Demo-photo attribution remains in the asset manifest.
- Kept the phone behind the selector and moved the centered camera dot over the capture. Library and Player show Android captures; Browser shows a NASA Science article without stretching.
- Kept the text and phone side by side on tablets. On small screens the language picker stays outside the menu, the selected feature description sits under its tabs, and the storage illustration sits beside its heading.
- Restored the lock illustration’s original 733×824 pixels and metal highlights in a lossless WebP.
- Aligned link, availability and footer treatments with the dark palette. Updated the design and asset documentation to reflect local Onest and the supplied sculpted P.
- Added localized details on ad and tracker blocking, optional device sync through Intiface Central (Buttplug.io), and the nHentai manga reader. Removed long dashes from the landing copy and page titles.

## [1.0.0] (2026-11-17)

### Added

- Svelte 5 + Vite landing page for Peachy, built to the approved dark dusty-pink concept: header with brand mark and navigation, two-column hero with the real app screenshot in a CSS phone frame, keyboard-accessible Library / Player / Browser preview selector, capability strip, private-storage limits, six principles, availability rows, philosophy with the Russian paragraph, footer.
- Shared Peachy brand: canonical 135×158 rounded-P mark with the dusty-pink inset (header, footer, favicon), pink terminal dot wordmark, Manrope variable font self-hosted in latin and cyrillic subsets.
- Design documentation: `DESIGN.md` with token frontmatter and the eight canonical sections, and `.impeccable/design.json` as its schema-v2 sidecar.
- Asset provenance: `public/assets/provenance.json` and `.impeccable/asset-report.md` record the origin, transformation and license of every shipping raster and the webfont.

### Notes

- App screenshots are unchanged upstream captures and carry demonstration content; the site does not restyle or reconstruct app UI.
- The site license is not declared. The AGPL-3.0 notice refers to the upstream Android application.
