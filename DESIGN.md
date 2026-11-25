---
name: Peachy Android
description: Native Material 3 design system for the Peachy private media workspace — graphite surfaces, dusty-pink actions, exact sculpted P lockup, Onest type, dark only.
colors:
  background: "#0B0B0D"
  on-background: "#F4F4F5"
  surface: "#15171B"
  on-surface: "#F4F4F5"
  surface-variant: "#262A31"
  on-surface-variant: "#ADA7B4"
  surface-container-low: "#111318"
  surface-container: "#15171B"
  surface-container-high: "#1C1F24"
  surface-container-highest: "#23262C"
  primary: "#E7A7BE"
  on-primary: "#0B0B0D"
  primary-container: "#3A2430"
  on-primary-container: "#F7DCE7"
  secondary: "#C97A9B"
  secondary-container: "#3A2C33"
  on-secondary-container: "#F3DDE5"
  tertiary: "#D6B6C8"
  outline: "#6F737D"
  outline-variant: "#2A2D33"
  error: "#F2B8B5"
  on-error: "#601410"
  error-container: "#8C1D18"
  on-error-container: "#F9DEDC"
  inverse-surface: "#F4F4F5"
  inverse-on-surface: "#1A1B1F"
  inverse-primary: "#8E4A64"
typography:
  display-large: { fontFamily: "Onest (bundled Regular/Medium/SemiBold)", fontSize: "57sp", fontWeight: 500, lineHeight: "64sp", letterSpacing: "-0.25sp" }
  display-medium: { fontFamily: "Onest (bundled Regular/Medium/SemiBold)", fontSize: "45sp", fontWeight: 500, lineHeight: "52sp", letterSpacing: "0sp" }
  display-small: { fontFamily: "Onest (bundled Regular/Medium/SemiBold)", fontSize: "36sp", fontWeight: 500, lineHeight: "44sp", letterSpacing: "0sp" }
  headline-large: { fontFamily: "Onest (bundled Regular/Medium/SemiBold)", fontSize: "32sp", fontWeight: 500, lineHeight: "40sp", letterSpacing: "0sp" }
  headline-medium: { fontFamily: "Onest (bundled Regular/Medium/SemiBold)", fontSize: "28sp", fontWeight: 500, lineHeight: "36sp", letterSpacing: "0sp" }
  headline-small: { fontFamily: "Onest (bundled Regular/Medium/SemiBold)", fontSize: "24sp", fontWeight: 500, lineHeight: "32sp", letterSpacing: "0sp" }
  title-large: { fontFamily: "Onest (bundled Regular/Medium/SemiBold)", fontSize: "22sp", fontWeight: 500, lineHeight: "28sp", letterSpacing: "0sp" }
  title-medium: { fontFamily: "Onest (bundled Regular/Medium/SemiBold)", fontSize: "16sp", fontWeight: 500, lineHeight: "24sp", letterSpacing: "0.15sp" }
  title-small: { fontFamily: "Onest (bundled Regular/Medium/SemiBold)", fontSize: "14sp", fontWeight: 500, lineHeight: "20sp", letterSpacing: "0.1sp" }
  body-large: { fontFamily: "Onest (bundled Regular/Medium/SemiBold)", fontSize: "16sp", fontWeight: 400, lineHeight: "24sp", letterSpacing: "0.5sp" }
  body-medium: { fontFamily: "Onest (bundled Regular/Medium/SemiBold)", fontSize: "14sp", fontWeight: 400, lineHeight: "20sp", letterSpacing: "0.25sp" }
  body-small: { fontFamily: "Onest (bundled Regular/Medium/SemiBold)", fontSize: "12sp", fontWeight: 400, lineHeight: "16sp", letterSpacing: "0.4sp" }
  label-large: { fontFamily: "Onest (bundled Regular/Medium/SemiBold)", fontSize: "14sp", fontWeight: 500, lineHeight: "20sp", letterSpacing: "0.1sp" }
  label-medium: { fontFamily: "Onest (bundled Regular/Medium/SemiBold)", fontSize: "12sp", fontWeight: 500, lineHeight: "16sp", letterSpacing: "0.5sp" }
  label-small: { fontFamily: "Onest (bundled Regular/Medium/SemiBold)", fontSize: "11sp", fontWeight: 500, lineHeight: "16sp", letterSpacing: "0.5sp" }
rounded:
  extra-small: "6dp"
  small: "8dp"
  medium: "12dp"
  large: "20dp"
  extra-large: "28dp"
  round: "50%"
components:
  action-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.large}"
  action-text:
    textColor: "{colors.on-surface-variant}"
    rounded: "{rounded.large}"
  key-neutral:
    backgroundColor: "{colors.surface-container-high}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.round}"
  key-delete:
    backgroundColor: "{colors.surface-container}"
    textColor: "{colors.on-surface-variant}"
    rounded: "{rounded.round}"
  key-confirm:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.round}"
  surface-auth:
    backgroundColor: "{colors.surface-container}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.extra-large}"
  card-media:
    backgroundColor: "{colors.surface-container-high}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.medium}"
  card-media-selected:
    backgroundColor: "{colors.primary-container}"
    textColor: "{colors.on-primary-container}"
    rounded: "{rounded.medium}"
  card-tab:
    backgroundColor: "{colors.surface-container-high}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.medium}"
  card-tab-active:
    backgroundColor: "{colors.secondary-container}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.medium}"
  topbar-selection:
    backgroundColor: "{colors.primary-container}"
    textColor: "{colors.on-primary-container}"
  brand-lockup:
    backgroundColor: "{colors.background}"
    textColor: "{colors.on-background}"
    height: "32dp"
---

# Design System: Peachy Android

Token values live in the frontmatter above; this prose explains where each one is used. The
frontmatter carries native `dp`/`sp` strings and Material 3 role names rather than CSS units, and
motion, layout gates and asset geometry sit in `.impeccable/design.json`, because the DESIGN.md
schema has no top-level group for them.

## Overview

**Creative North Star: "The Private Graphite Workspace"**

Peachy is an existing native Android media workspace, not a marketing site. The visual system keeps
that topology and restyles it through Material 3: full color roles, the standard type scale, the
shared shape scale, native navigation and system Back. Depth comes from tonal surfaces, not chrome.
Real media stays the loudest thing on every screen; the brand speaks through graphite ground,
off-white text, dusty-pink actions and the exact sculpted P lockup rather than through decorative cards,
gradients or web landing patterns.

The scheme is dark only and deliberate. Dynamic Color is not used: a wallpaper-derived scheme would
replace the brand and can produce light surfaces the app was never verified against. Every role is
set explicitly, including containers, inverse pairs and error, so no default Material purple or the
previous amber palette survives. The same components serve English and Russian copy; layout is
phone-first and must survive a 1.3 font scale, landscape and both gesture and three-button
navigation.

**Key Characteristics:**
- Complete explicit Material 3 dark color scheme; no dynamic color and no light theme.
- Onest bundled at Regular 400 / Medium 500 / SemiBold 600, full Material type scale in `sp`; no runtime font fetch.
- Tonal layering on black/graphite; no drop shadows on app chrome.
- Dusty pink reserved for actions, selection, focus and the wordmark period.
- Native components and system Back; icon-only or mark-only fallbacks before any clipping.
- Black media stages and scrims where honest contrast over video and page art matters.

**Evidence boundary.** This record describes shipped source and the runtime-verified phone pass:
`.impeccable/review/android-runtime-review.md` (ship at three-fix scope) and
`.impeccable/review/native-build-final.txt` (BUILD SUCCESSFUL, deprecation warnings remain). The
manga reader, translation panel and photo viewer were reviewed in code only because the live
catalogue is adult content; the Russian locale was exercised in the final browser-tab pass while
the broader screen captures are en-US. No tablet class, security audit or full regression suite is
claimed. HTML/CSS snippets in the sidecar are visual previews, not app implementation.

## Colors

A restrained graphite ladder under off-white text, with three low-chroma rose/lilac accents. Each
color is a Material role rather than a free hex value; screens read `MaterialTheme.colorScheme` and
never restate a palette constant.

### Primary
- **Dusty Pink** (`primary`, `surface-tint`): filled primary actions, selection checks and
  indicators, the wordmark period, the active tab border, sliders, and the scanning state of the
  device status. Measured 9.99:1 with `on-primary` and 9.12:1 on `surface`.
- **Deep Rose** (`primary-container`) with **Pale Rose** (`on-primary-container`): selected gallery
  and photo cards, the selection-mode top app bar, and the current-item queue border. Measured
  11.08:1 for the container pair.

### Secondary
- **Muted Rose** (`secondary`): connected/idle device state and supporting emphasis; it does not
  replace the primary action color. 5.76:1 on `surface`.
- **Rose Shadow** (`secondary-container`) with **Rose Tint** (`on-secondary-container`): the active
  browser tab card. This is the only place a second container family carries state.

### Tertiary
- **Lilac Haze** (`tertiary`): the active device state, distinct from the pink action language.
  9.73:1 on `surface`.

### Neutral
- **Brand Black** (`background`, `surface-container-lowest`): window, status and navigation bars,
  splash, media stages. The ground of the product.
- **Graphite** (`surface`, `surface-container`): app bars and the authentication surface.
- **Card Graphite** (`surface-container-high`): gallery cards, keypad keys, browser action row.
- **High Graphite** (`surface-container-highest`): tab thumbnails and raised local elements.
- **Raised Graphite** (`surface-variant`): unselected wide cards and settings blocks.
- **Off-white** (`on-background`, `on-surface`): primary text; 17.89:1 on `background`.
- **Muted Lavender Grey** (`on-surface-variant`): metadata, hints, secondary labels, section labels.
- **Steel Outline** (`outline`): interactive boundaries such as keypad keys, 3.78:1 on `surface`.
- **Graphite Hairline** (`outline-variant`): decorative dividers only, 1.30:1 on `surface`.

Functional error is its own family (`error`, `on-error`, `error-container`, `on-error-container`)
and keeps its own semantics: wrong password, failed device connection, destructive actions. It is
never used for emphasis.

### Named Rules

**The One Pink Voice Rule.** `primary` marks actions, selection and state, and nothing else.
Informational section labels use `on-surface-variant`, body copy uses `on-surface` or
`on-background`, because a screen where everything is pink has no primary action left.

**The Hairline Boundary Rule.** `outline-variant` draws dividers and nothing interactive. Any
control edge — keypad key, outlined button, count badge that needs a border — uses `outline` so the
boundary keeps a perceivable contrast on both the auth card and higher-tonal dialogs.

**The Dark-Only Rule.** Do not add a light scheme, `dynamicColorScheme`, or a per-screen background
override. Both platform night mode and the app resolve to the same brand black.

## Typography

**Display Font:** Onest (bundled Regular 400 / Medium 500 / SemiBold 600; SIL OFL 1.1)
**Body Font:** same
**Label Font:** same

**Character:** A rounded, open grotesque with matched Latin and Cyrillic, metrically close to the
previous platform face so the Material scale and line heights still hold. One family and three
static files cover the interface; nothing is fetched at runtime.

### Hierarchy
- **Display** (large/medium/small): declared for completeness; current screens do not use display
  sizes. Reserve them for a future full-screen hero, not for ordinary headings.
- **Headline** (large/medium/small): onboarding welcome uses `headlineMedium`; drawer sections and
  the empty-state title use `headlineSmall`; the keypad digits use `headlineMedium`.
- **Title** (large/medium/small): `titleMedium` for primary and text action labels, `titleSmall` for
  browser sheet section headers, `titleLarge` for revealed password digits.
- **Body** (large/medium/small): `bodyLarge` for onboarding feature rows and longer readable runs,
  `bodyMedium` for status and explanatory copy, `bodySmall` for gallery card titles and inline
  errors.
- **Label** (large/medium/small): chips, compact controls and dense metadata; `labelSmall` carries
  gallery metadata and is the most-used label step in the app.

Display, headline and title roles carry Medium 500; body roles Regular 400; label roles Medium 500.
Sizes, line heights and tracking are unchanged from the Material scale.

The brand lockup is one deliberate exception: the wordmark is live text at `headlineMedium` with
SemiBold 600 and negative tracking, and its period is `primary`. It is not a separate font role
and must not be copied into headings.

### Named Rules

**The Brand Face Rule.** Onest only, from the three bundled static files. Do not add a second family,
request a weight the app does not ship, or use letter-spacing as a substitute for hierarchy. No
client-side font download. Brand voice is color, mark and spacing.

**The sp Rule.** Every size, line height and letter spacing stays in `sp` so the system font-size
setting applies. Fixed `px` text or fixed-height text containers that cannot grow are defects.

## Layout

Native single-activity Compose, phone-first. The verified runtime was 1080×2400 at density 420
(411 dp wide), portrait and landscape, at font scale 1.0 and 1.3. The app is edge-to-edge: windows
draw behind the system bars and screens apply insets rather than assuming a safe rectangle.

- **Onboarding and authentication** use `safeDrawingPadding` plus a real vertical scroll, so compact
  phones, landscape and 1.3 font scale reach every action instead of clipping. Onboarding content is
  capped at 480 dp wide with 24 dp horizontal and 32 dp vertical screen padding. The authentication
  card is capped at 420 dp, paddled 20 dp horizontally and 28 dp vertically, and uses a minimum-height
  centring box because a scroll container cannot centre by itself.
- **Authentication keypad** is 72 dp per key at normal size, 56 dp when height is below 640 dp or
  width below 360 dp, with 14 dp or 10 dp gaps respectively. Digits are 48 dp+ targets on every
  verified configuration.
- **Top app bar** is the standard 64 dp Material bar. The player keeps Menu, Gallery, Add, and
  queue sorting in one row with 48 dp targets. Library joins when NSFW features are enabled.
  Documents and Browser have direct buttons when they fit and share the More menu otherwise.
  The title shows the full brand lockup if it fits, then the mark alone, then neither.
  Gallery moves search to its own row when its hint will not fit beside the actions. Selection mode uses `primary-container`.
- **Settings drawer** is a modal Material drawer with 16 dp content padding, the brand lockup above
  the device section, and bottom padding from `navigationBars ∪ mandatorySystemGestures`. The
  union covers whichever bottom system region is larger; `windowInsetsPadding` subtracts what
  the drawer sheet already consumed, so three-button navigation does not gain a second inset.
- **Browser tab sheet** opens fully expanded, because a partially expanded sheet anchors at half
  height and clips a full-height tab grid at the window edge. Its body carries the activity window's
  bottom inset as a concrete padding and the sheet's own content insets are zeroed so the two cannot
  stack. Measured insets on the verified device: 126 px (48 dp) three-button and 84 px (32 dp)
  gesture. The New tab row ends 83 px above the gesture bar and 62 px above the three-button bar.
- **Back with the settings drawer open:** one Back closes the drawer and `MainActivity` stays
  resumed; a second Back follows the normal destination, as verified in the runtime pass.
  The fullscreen player handles Back to leave fullscreen; that path is described from code.
- **Landscape** is a supported phone orientation, not a separate layout: scrolling screens scroll,
  the player hides the top bar in fullscreen, and the auth keypad shrinks by the height rule above.

### Launcher and splash safe zone

The launcher, monochrome icon and splash share one adaptive 108 dp layer. The exact raster mark is
50 dp tall and about 39 dp wide; its furthest geometry sits 31.7 dp from centre, inside the 33 dp
safe radius of the 66 dp adaptive safe zone, so no launcher mask crops it. Legacy `mipmap` rasters
exist at 48/72/96/144/192 px: the square icon keeps the mark at 56% of tile height with a 22.5%
corner radius, the round icon at 52%. Splash background, window background and system bars are the
same brand black; the splash icon is the adaptive launcher icon itself. These measurements come from
the delivered PNG resources, not guessed.

### Named Rules

**The Inset Rule.** The bottom-most interactive content clears `navigationBars ∪
mandatorySystemGestures`, verified for both navigation modes. Insets are applied once: per surface,
never nested a second time.

**The Back Rule.** A modal surface consumes Back before the activity does. Predictive-back
animation is not claimed as verified; the dismissal contract is.

## Elevation & Depth

Depth is tonal. App chrome is flat: the old 4 dp keypad shadow, the arbitrary 0 dp and 4 dp card
shadows and the shadowed authentication card were removed. Elevation reads through the surface
ladder — `surface-container-low` for recessed lists and drawers, `surface` for bars and the auth
card, `surface-container-high` for cards, keys and the browser action row,
`surface-container-highest` for tab thumbnails — plus 1 dp outlines where a boundary must survive.
Material's own components keep their standard tonal states; the theme adds no custom shadow
vocabulary. The translation panel is the one explicit tonal elevation in screen code
(`tonalElevation = 3.dp`) because it overlays page art.

Black and white scrims are a media technique, not chrome: player controls, queue cards, selection
dims, thumbnail glyphs, manga tap hints and zoom buttons sit on black at partial alpha (or white on
translucent black for translated page text) because the content underneath is unknown. They never
wrap ordinary app content.

### Named Rules

**The Tonal-Only Rule.** No drop shadows on app chrome. If a surface must separate from its
background, move it one tonal step or add a 1 dp `outline`; do not add blur, glow or elevation
animation for decoration.

**The Media Exception Rule.** Scrims exist only where real media makes contrast unknowable. Do not
reuse the black scrim treatment for cards, sheets or dialogs.

## Shapes

The theme exposes one shared scale — `extra-small`, `small`, `medium`, `large` and `extra-large`,
with their values in the frontmatter — plus circular control geometry. Screens call
`MaterialTheme.shapes`; the ad-hoc radii that predated the theme were removed.

- `extra-small`: chips, manga cover clips, the tab-count badge, the browser close glyph.
- `small`: list-row previews, thumbnails, queue cards, player status pills, manga thumbnails.
- `medium`: gallery and photo cards, browser tab cards, settings blocks, password digit rows.
- `large`: onboarding and empty-state buttons, text actions.
- `extra-large`: the authentication card, the manga reader settings sheet, and the translation
  panel's top corners.
- **Circles** (`round` / `CircleShape`): keypad keys, password reveal and delete buttons, the
  selection indicator dot.

One documented derivation exists: the translation panel copies `extra-large` and zeroes its bottom
corners so it reads as a sheet attached to the page, because the available `RoundedCornerShape`
overloads cannot take four theme `CornerSize` values. The launcher raster corner radius (22.5%) is
asset geometry, not a UI token.

### Named Rules

**The Shape-of-the-Theme Rule.** Use the named shape step that matches the component's role. Do not
invent a radius per screen or per state; the only allowed derivative is the translation panel's
documented top-only shape.

## Components

### Buttons and actions

Primary actions are filled Material buttons with `primary` background and `on-primary` content;
onboarding and the empty state give them the `large` shape, and onboarding enforces a 56 dp minimum
height. A screen gets one primary action. Text actions use `on-surface-variant` content with the
`large` shape and a 48 dp minimum height. The keypad confirm is a circle in `primary`. There is no
FAB anywhere; the player's Add action lives in the top bar.

### Numeric keypad

The lock and password dialogs share one keypad. Neutral keys are circular, filled
`surface-container-high` with `on-surface` digits at `headlineMedium`, each with a 1 dp `outline`
border. Delete is recessed to `surface-container` with `on-surface-variant` content. Confirm is the
single `primary` key, disabled until the password is non-empty. Key size and its compact fallback
are layout facts recorded in Layout. The reveal control is a 48 dp icon button; the digit readout uses
`titleLarge` when revealed and pink dots when hidden, turning `error` on a wrong password. Password
state is never saved.

### Top app bar and brand

The player bar shows `PeachyBrand` — the exact sculpted P at the declared lockup height plus the live
`peachy.` wordmark — as its title. The wordmark is measured against the slot, not the screen:
`peachyBrandWidth()` derives the required width from the current font scale and the actual mark; below
it the lockup drops to the mark alone, which keeps it on one line at 1.0 and 1.3.
The gallery bar keeps search; in selection mode its container becomes `primary-container` with
matching content so selection is visible beyond the checkboxes. The drawer repeats the full lockup
above the device section.

### Cards and lists

Gallery and photo cards are `surface-container-high` with `on-surface` titles and
`on-surface-variant` metadata on the `medium` shape; selected cards become `primary-container` with
`on-primary-container` content. Wide and list rows clip their 128×72 dp previews to `small`. Album
rows are full-width clickable rows with hairline dividers, counts in the title, and the same
create/rename/delete callbacks as before. Browser tab cards are `medium` shaped: inactive on
`surface-container-high` with a 1 dp `outline-variant` border, active on `secondary-container` with a
2 dp `primary` border. Queue cards use `small`; the current item takes the `primary` border.

### Sheets, drawer and dialogs

Bottom sheets are Material sheets anchored to the window and inset per the Layout rules. The browser
tab sheet is force-expanded for tab browsing; the empty bookmarks sheet stays content-sized. The
translation panel sits on `surface-container`, takes the top-only `extra-large` shape, shows one line
collapsed and up to half the screen expanded, and animates only its content size. Dialogs remain
Material dialogs; the settings drawer is a modal drawer, not a navigation destination.

### Media surfaces

Player stage, photo viewer, manga reader and fullscreen browser stay black; control overlays stay
black/white scrims. This is deliberate: brand chrome never displaces contrast over unknown footage.
Functional status uses theme roles instead of platform greens and ambers — active `tertiary`,
connected/idle `secondary`, scanning `primary`, failure `error`, disconnected `on-surface-variant`
or white on the media scrim.

### Named Rules

**The One Primary Action Rule.** Each screen or sheet has at most one filled `primary` action. A
second competing action becomes a text or outlined control; a dialog confirmation may be the single
primary for that dialog.

**The Mark-Only Fallback Rule.** When a slot cannot hold the wordmark, show the mark alone. Never
wrap, clip, shrink below the type scale or drop the wordmark's period to make it fit.

## Do's and Don'ts

### Do:
- **Do** consume `MaterialTheme.colorScheme`, `MaterialTheme.typography` and `MaterialTheme.shapes`
  in every screen and keep the explicit palette private to the theme.
- **Do** keep the app dark only, with system bars, splash and window background on the same brand
  black.
- **Do** give every touch target at least 48 dp and keep the keypad at its 56/72 dp measured sizes.
- **Do** apply window insets once per surface, including `mandatorySystemGestures` for bottom
  content on gesture navigation.
- **Do** keep the wordmark on one line via the mark-only fallback and let text grow in `sp`.
- **Do** keep real media, queue cards and page art unthemed; use black/white scrims only there.
- **Do** describe screens from actual captures and reports, and name what was not exercised.

### Don't:
- **Don't** add Dynamic Color, a light scheme or a wallpaper-derived scheme.
- **Don't** use `primary` for informational labels, body emphasis or decoration; selection and
  actions only.
- **Don't** add drop shadows, blur, gradient chrome or custom elevation animation to app surfaces.
- **Don't** hand-pick corner radii or copy raw colors such as `Color.DarkGray`, `Color.Red` or
  platform green/amber into UI chrome.
- **Don't** restyle the player stage, reader, photo viewer or browser fullscreen away from black.
- **Don't** port the website's landing-page composition, marketing cards or hero blocks into the
  app; native Material topology and system Back come first.
- **Don't** claim the reader, translation panel or Russian locale were runtime-reviewed when the
  captures do not cover them.
