<script>
  import { initialLocale, languages, messages, rememberLocale } from './locales/index.js';

  const screenAssets = [
    { id: 'library', art: '/assets/library.webp', src: '/assets/gallery.webp' },
    { id: 'player', art: '/assets/player.webp', src: '/assets/player-screen.webp' },
    { id: 'browser', art: '/assets/browser.webp', src: '/assets/browser-nasa.webp' },
  ];

  let locale = $state(initialLocale());
  const copy = $derived(messages[locale] ?? messages.en);
  const screens = $derived(
    screenAssets.map((screen) => ({ ...screen, ...copy.screens[screen.id] }))
  );
  const principles = $derived(copy.principles.items);

  let menuOpen = $state(false);
  let currentScreen = $state('library');

  const activeScreen = $derived(
    screens.find((screen) => screen.id === currentScreen) ?? screens[0]
  );

  $effect(() => {
    document.documentElement.lang = locale;
    document.title = copy.meta.title;
    document.querySelector('meta[name="description"]')?.setAttribute('content', copy.meta.description);
    document.querySelector('meta[property="og:title"]')?.setAttribute('content', copy.meta.title);
    document.querySelector('meta[property="og:description"]')?.setAttribute('content', copy.meta.socialDescription);
    document.querySelector('meta[property="og:locale"]')?.setAttribute('content', copy.meta.socialLocale);
  });

  function changeLocale(event) {
    const selected = event.currentTarget.value;
    if (!Object.hasOwn(messages, selected)) return;
    locale = selected;
    rememberLocale(selected);
  }

  function closeMenu() {
    menuOpen = false;
  }

  function onWindowKeydown(event) {
    if (event.key !== 'Escape' || !menuOpen) return;
    menuOpen = false;
    document.getElementById('menu-toggle')?.focus();
  }

  function onWindowPointerDown(event) {
    if (!menuOpen) return;
    if (event.target instanceof Element && event.target.closest('.site-header')) return;
    menuOpen = false;
  }

  function onScreenKeydown(event, index) {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();

    let nextIndex = index;
    if (event.key === 'ArrowLeft') nextIndex = (index - 1 + screens.length) % screens.length;
    if (event.key === 'ArrowRight') nextIndex = (index + 1) % screens.length;
    if (event.key === 'Home') nextIndex = 0;
    if (event.key === 'End') nextIndex = screens.length - 1;

    currentScreen = screens[nextIndex].id;
    document.getElementById(`screen-tab-${screens[nextIndex].id}`)?.focus();
  }
</script>

<svelte:window onkeydown={onWindowKeydown} onpointerdown={onWindowPointerDown} />

<a class="skip-link" href="#main">{copy.nav.skip}</a>

<header class="site-header">
  <div class="shell header">
    <a class="brand" href="#main" aria-label={copy.nav.home}>
      <img
        class="brand__mark"
        src="/assets/peachy-mark.webp"
        alt=""
        height="34"
        decoding="async"
      />
      <span class="brand__name">peachy<span class="brand__dot">.</span></span>
    </a>

    <label class="locale-picker">
      <span class="visually-hidden">{copy.nav.language}</span>
      <select class="locale-select" value={locale} onchange={changeLocale}>
        {#each languages as language (language.id)}
          <option value={language.id} lang={language.id}>{language.label}</option>
        {/each}
      </select>
    </label>

    <button
      class="menu-toggle"
      id="menu-toggle"
      type="button"
      aria-expanded={menuOpen}
      aria-label={menuOpen ? copy.nav.close : copy.nav.menu}
      aria-controls="site-nav"
      onclick={() => (menuOpen = !menuOpen)}
    >
      <span>{menuOpen ? copy.nav.close : copy.nav.menu}</span>
      <svg viewBox="0 0 20 20" aria-hidden="true">
        {#if menuOpen}
          <path d="M5 5l10 10M15 5L5 15" />
        {:else}
          <path d="M4 7h12M4 13h12" />
        {/if}
      </svg>
    </button>

    <nav class="nav" id="site-nav" class:is-open={menuOpen} aria-label={copy.nav.primary}>
      <a class="nav__link" href="#features" onclick={closeMenu}>{copy.nav.features}</a>
      <a class="nav__link" href="#principles" onclick={closeMenu}>{copy.nav.principles}</a>
      <a
        class="nav__link nav__link--external"
        href="https://github.com/spgsroot/Peachy"
        target="_blank"
        rel="noopener noreferrer"
        onclick={closeMenu}
      >
        GitHub
        <svg viewBox="0 0 20 20" aria-hidden="true">
          <path d="M11.5 4.5h4v4M15.5 4.5l-7 7M15 12v3.5H4.5v-11H8" />
        </svg>
      </a>
      <a
        class="button button--compact"
        href="https://github.com/spgsroot/Peachy/releases/latest"
        target="_blank"
        rel="noopener noreferrer"
        onclick={closeMenu}
      >
        {copy.nav.download}
      </a>
    </nav>
  </div>
</header>

<main id="main" tabindex="-1">
  <section class="hero shell" aria-labelledby="hero-title">
    <div class="hero__copy">
      <h1 id="hero-title" class="hero__title">
        {copy.hero.titleFirst}<br />
        {copy.hero.titleSecond}<span class="accent">{copy.period}</span>
      </h1>

      <p class="hero__meta">{copy.hero.openSource} <span aria-hidden="true">·</span> {copy.hero.localFirst}</p>

      <p class="hero__lead">
        {copy.hero.lead}
      </p>

      <div class="hero__actions">
        <a
          class="button"
          href="https://github.com/spgsroot/Peachy/releases/latest"
          target="_blank"
          rel="noopener noreferrer"
        >
          <svg viewBox="0 0 20 20" aria-hidden="true">
            <path d="M10 3.5v8.5m0 0 3.2-3.2M10 12 6.8 8.8M4.5 16.5h11" />
          </svg>
          {copy.hero.download}
        </a>
        <a
          class="text-link"
          href="https://github.com/spgsroot/Peachy"
          target="_blank"
          rel="noopener noreferrer"
        >
          {copy.hero.source}
          <svg viewBox="0 0 20 20" aria-hidden="true">
            <path d="M11.5 4.5h4v4M15.5 4.5l-7 7M15 12v3.5H4.5v-11H8" />
          </svg>
        </a>
      </div>
    </div>

    <div class="capabilities-bar" id="features" tabindex="-1">
      <h2 class="visually-hidden" id="features-title">{copy.hero.featuresTitle}</h2>
      <div class="capabilities" role="tablist" aria-label={copy.hero.chooseScreen}>
        {#each screens as screen, index (screen.id)}
          <button
            class="cap"
            id={`screen-tab-${screen.id}`}
            type="button"
            role="tab"
            aria-labelledby={`screen-label-${screen.id}`}
            aria-describedby={`screen-description-${screen.id}`}
            aria-selected={currentScreen === screen.id}
            aria-controls="phone-screen"
            tabindex={currentScreen === screen.id ? 0 : -1}
            onclick={() => (currentScreen = screen.id)}
            onkeydown={(event) => onScreenKeydown(event, index)}
          >
            <img class="cap__art" src={screen.art} alt="" decoding="async" />
            <span class="cap__body">
              <span class="cap__title" id={`screen-label-${screen.id}`}>{screen.label}</span>
              <span class="cap__text" id={`screen-description-${screen.id}`}>{screen.caption}</span>
            </span>
          </button>
        {/each}
      </div>
      <p class="capabilities-summary">{activeScreen.caption}</p>
    </div>

    <figure class="hero__visual">
      <div class="phone">
        <div class="phone__hardware" aria-hidden="true">
          <span class="phone__key phone__key--mute"></span>
          <span class="phone__key phone__key--volume"></span>
          <span class="phone__key phone__key--power"></span>
        </div>
        <div
          class="phone__screen"
          data-screen={activeScreen.id}
          id="phone-screen"
          role="tabpanel"
          aria-labelledby={`screen-tab-${activeScreen.id}`}
          tabindex="0"
        >
          {#key activeScreen.id}
            <img
              class="phone__image"
              src={activeScreen.src}
              alt={activeScreen.alt}
              decoding="async"
            />
          {/key}
        </div>
      </div>
      <figcaption class="phone__note">
        <strong>{activeScreen.label}</strong>
        <span>{activeScreen.id === 'browser' ? copy.screens.browser.sourceLabel : copy.hero.appLabel}</span>
      </figcaption>
    </figure>
  </section>

  <section class="section feature-details" aria-labelledby="feature-details-title">
    <div class="shell">
      <div class="section-head">
        <h2 id="feature-details-title">{copy.featureDetails.title}</h2>
      </div>
      <div class="detail-grid">
        {#each copy.featureDetails.items as item (item.title)}
          <article class="detail">
            <h3>{item.title}</h3>
            <p>{item.text}</p>
          </article>
        {/each}
      </div>
    </div>
  </section>

  <section class="storage-section" aria-labelledby="storage-title">
    <div class="shell storage">
      <div class="storage__media">
        <img
          class="storage__image"
          src="/assets/protected-storage.webp"
          alt={copy.storage.alt}
          width="733"
          height="824"
          loading="lazy"
          decoding="async"
        />
      </div>

      <div class="storage__copy">
        <h2 id="storage-title">{copy.storage.title}</h2>
        <p class="storage__lead">
          {copy.storage.lead}
        </p>

        <ul class="storage__facts">
          <li class="storage__fact">
            <h3>{copy.storage.localTitle}</h3>
            <p>{copy.storage.localText}</p>
          </li>
          <li class="storage__fact">
            <h3>{copy.storage.networkTitle}</h3>
            <p>
              {copy.storage.networkText}
            </p>
          </li>
          <li class="storage__fact">
            <h3>{copy.storage.protectionTitle}</h3>
            <p>
              {copy.storage.protectionText}
            </p>
          </li>
        </ul>
      </div>
    </div>
  </section>

  <section class="section" id="principles" aria-labelledby="principles-title" tabindex="-1">
    <div class="shell">
      <div class="section-head">
        <h2 id="principles-title">{copy.principles.title}</h2>
        <p>
          {copy.principles.lead}
        </p>
      </div>

      <div class="detail-grid">
        {#each principles as principle (principle.title)}
          <article class="detail">
            <h3>{principle.title}</h3>
            <p>{principle.text}</p>
          </article>
        {/each}
      </div>
    </div>
  </section>

  <section class="section" id="platforms" aria-labelledby="platforms-title">
    <div class="shell">
      <div class="section-head">
        <h2 id="platforms-title">{copy.platforms.title}</h2>
        <p>{copy.platforms.lead}</p>
      </div>

      <ul class="platform-list">
        <li class="platform">
          <h3 class="platform__name">Android</h3>
          <p class="platform__scope">{copy.platforms.mainApplication}</p>
          <span class="status status--available">
            <span class="status__dot" aria-hidden="true"></span>
            {copy.platforms.available}
          </span>
          <a
            class="platform__action"
            href="https://github.com/spgsroot/Peachy/releases/latest"
            target="_blank"
            rel="noopener noreferrer"
          >
            {copy.nav.download}
            <svg viewBox="0 0 20 20" aria-hidden="true">
              <path d="M10 3.5v8.5m0 0 3.2-3.2M10 12 6.8 8.8M4.5 16.5h11" />
            </svg>
          </a>
        </li>

        <li class="platform">
          <h3 class="platform__name">{copy.platforms.desktop}</h3>
          <p class="platform__scope">{copy.platforms.desktopApplication}</p>
          <span class="status">
            <span class="status__dot status__dot--pending" aria-hidden="true"></span>
            {copy.platforms.planned}
          </span>
          <span class="platform__pending">{copy.platforms.noBuild}</span>
        </li>

        <li class="platform">
          <h3 class="platform__name">{copy.platforms.alternativeNetworks}</h3>
          <p class="platform__scope">Yggdrasil / Tor / I2P</p>
          <span class="status">
            <span class="status__dot status__dot--pending" aria-hidden="true"></span>
            {copy.platforms.research}
          </span>
          <span class="platform__pending">{copy.platforms.noIntegration}</span>
        </li>
      </ul>

      <p class="platform-note">
        {copy.platforms.note}
      </p>
    </div>
  </section>

  <section class="philosophy" aria-labelledby="philosophy-title">
    <div class="shell philosophy__inner">
      <h2 id="philosophy-title">
        {copy.philosophy.title}<span class="accent">{copy.period}</span>
      </h2>
      <p class="philosophy__statement">
        {copy.philosophy.statement}
      </p>
    </div>
  </section>
</main>

<footer class="site-footer">
  <div class="shell footer">
    <a class="brand brand--footer" href="#main" aria-label={copy.nav.backToTop}>
      <img
        class="brand__mark"
        src="/assets/peachy-mark.webp"
        alt=""
        height="24"
        decoding="async"
      />
      <span class="brand__name">peachy<span class="brand__dot">.</span></span>
    </a>

    <div class="footer__meta">
      <a
        class="footer__link"
        href="https://github.com/spgsroot/Peachy"
        target="_blank"
        rel="noopener noreferrer"
      >
        GitHub
        <svg viewBox="0 0 20 20" aria-hidden="true">
          <path d="M11.5 4.5h4v4M15.5 4.5l-7 7M15 12v3.5H4.5v-11H8" />
        </svg>
      </a>
      <a class="footer__link" href="https://llmcrew.org/" target="_blank" rel="noopener noreferrer">
        {copy.footer.signature}
      </a>
    </div>
  </div>
</footer>

<style>
  .shell {
    width: 100%;
    max-width: calc(var(--shell) + 2 * var(--pad));
    margin-inline: auto;
    padding-inline: var(--pad);
  }

  .skip-link {
    position: absolute;
    top: 12px;
    left: 12px;
    z-index: 100;
    transform: translateY(-160%);
    padding: 10px 16px;
    border-radius: 8px;
    background: var(--text);
    color: var(--bg);
    font-size: 0.875rem;
    font-weight: 500;
    transition: transform 160ms cubic-bezier(0.16, 1, 0.3, 1);
  }

  .skip-link:focus-visible {
    transform: translateY(0);
    outline-color: var(--pink);
  }

  .accent {
    color: var(--pink);
  }

  /* Header */
  .site-header {
    position: relative;
    z-index: 20;
    border-bottom: 1px solid var(--rule);
    background: var(--bg);
  }

  .header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 24px;
    min-height: 72px;
  }

  .brand {
    display: inline-flex;
    align-items: center;
    gap: 10px;
    flex: none;
    border-radius: 8px;
  }

  .brand__mark {
    width: auto;
    height: 34px;
    display: block;
  }

  .brand__name {
    font-size: 1.25rem;
    font-weight: 600;
    letter-spacing: -0.02em;
    line-height: 1;
    color: var(--text);
  }

  .brand__dot {
    color: var(--pink);
  }

  .nav {
    display: flex;
    align-items: center;
    gap: clamp(18px, 2.2vw, 32px);
  }

  .nav__link {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 1rem;
    font-weight: 500;
    color: var(--text);
    transition: color 160ms ease;
  }

  .nav__link svg {
    width: 14px;
    height: 14px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.5;
    stroke-linecap: round;
    stroke-linejoin: round;
  }

  .nav__link:hover {
    color: var(--pink);
  }

  .locale-picker {
    display: inline-flex;
    align-items: center;
    margin-inline-start: auto;
  }

  .locale-select {
    min-height: 44px;
    padding: 8px 10px;
    border: 1px solid transparent;
    border-radius: 9px;
    background: var(--bg);
    color: var(--text);
    font: inherit;
    font-size: 1rem;
    cursor: pointer;
    transition: border-color 160ms ease;
  }

  .locale-select:hover {
    border-color: var(--muted);
  }

  .locale-select:focus-visible {
    outline: 2px solid var(--pink);
    outline-offset: 3px;
  }

  .menu-toggle {
    display: none;
    align-items: center;
    gap: 8px;
    min-height: 44px;
    padding: 8px 4px;
    border: 0;
    background: none;
    color: var(--text);
    font-size: 1rem;
    font-weight: 500;
    cursor: pointer;
  }

  .menu-toggle svg {
    width: 20px;
    height: 20px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.5;
    stroke-linecap: round;
  }

  /* Buttons */
  .button {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 10px;
    padding: 15px 24px;
    border-radius: 12px;
    background: var(--pink);
    color: var(--ink-on-pink);
    font-size: 1rem;
    font-weight: 500;
    line-height: 1.2;
    letter-spacing: -0.01em;
    transition:
      background-color 160ms ease,
      transform 160ms ease;
  }

  .button:hover {
    background: var(--rose);
  }

  .button:active {
    transform: translateY(1px);
  }

  .button svg {
    width: 18px;
    height: 18px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.6;
    stroke-linecap: round;
    stroke-linejoin: round;
  }

  .button--compact {
    padding: 10px 18px;
    font-size: 0.95rem;
    border: 1px solid var(--rule);
    border-radius: 9px;
    background: var(--surface);
    color: var(--text);
  }

  .button--compact:hover {
    background: var(--graphite);
    color: var(--pink);
  }

  .text-link {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    min-height: 44px;
    padding: 6px 0;
    color: var(--text);
    font-size: 1rem;
    font-weight: 500;
    text-decoration: underline;
    text-decoration-color: var(--quiet);
    text-decoration-thickness: 1px;
    text-underline-offset: 5px;
    transition:
      color 160ms ease,
      text-decoration-color 160ms ease;
  }

  .text-link svg {
    width: 16px;
    height: 16px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.5;
    stroke-linecap: round;
    stroke-linejoin: round;
  }

  .text-link:hover {
    color: var(--pink);
    text-decoration-color: var(--pink);
  }

  /* Hero */
  .hero {
    display: grid;
    grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
    grid-template-areas:
      'copy visual'
      'caps caps';
    align-items: center;
    column-gap: clamp(48px, 7vw, 104px);
    padding-top: clamp(28px, 4.5vw, 56px);
  }

  .hero__copy {
    grid-area: copy;
    min-width: 0;
  }

  .hero__title {
    font-size: clamp(2.75rem, 6.2vw, 6rem);
    line-height: 0.97;
    letter-spacing: -0.035em;
  }

  :global(html[lang='zh-CN']) .hero__title {
    line-height: 1.14;
    letter-spacing: normal;
  }

  @media (min-width: 921px) {
    :global(html[lang='ru']) .hero__title {
      font-size: clamp(2.7rem, 4.65vw, 4.25rem);
    }
  }

  .hero__meta {
    margin-top: 18px;
    color: var(--muted);
    font-size: 0.75rem;
    font-weight: 500;
    letter-spacing: 0.13em;
    text-transform: uppercase;
  }

  .hero__meta span {
    margin-inline: 4px;
    color: var(--pink);
  }

  .hero__lead {
    max-width: 44ch;
    margin-top: 16px;
    color: var(--muted);
    font-size: clamp(1.05rem, 1.3vw, 1.15rem);
    line-height: 1.6;
    text-wrap: pretty;
  }

  .hero__actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 14px 26px;
    margin-top: 28px;
  }

  /* Capability tabs */
  .capabilities-bar {
    grid-area: caps;
    position: relative;
    z-index: 1;
    margin-top: -12px;
    scroll-margin-block-start: min(68svh, 38rem);
    border-block: 1px solid var(--rule);
    background: var(--bg);
  }

  .capabilities {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .cap {
    -webkit-appearance: none;
    appearance: none;
    display: flex;
    align-items: center;
    gap: clamp(12px, 1.4vw, 20px);
    width: 100%;
    padding: 18px clamp(16px, 2vw, 28px);
    border: 0;
    border-left: 1px solid var(--rule);
    border-bottom: 2px solid transparent;
    border-radius: 0;
    background: transparent;
    color: var(--text);
    text-align: left;
    cursor: pointer;
    transition:
      background-color 160ms ease,
      border-color 160ms ease;
  }

  .cap:first-child {
    border-left: 0;
    padding-left: 0;
  }

  .cap:last-child {
    padding-right: 0;
  }

  .cap:hover {
    background: var(--surface);
  }

  .cap[aria-selected='true'] {
    background: var(--surface);
    border-bottom-color: var(--pink);
  }

  .cap__art {
    flex: none;
    width: clamp(60px, 5.6vw, 80px);
    height: clamp(60px, 5.6vw, 80px);
    object-fit: contain;
  }

  .cap__body {
    display: flex;
    flex-direction: column;
    gap: 4px;
    min-width: 0;
  }

  .cap__title {
    font-size: 1.05rem;
    font-weight: 500;
    letter-spacing: -0.015em;
    line-height: 1.3;
  }

  .cap__text {
    color: var(--muted);
    font-size: 1rem;
    line-height: 1.5;
    text-wrap: pretty;
  }

  /* Phone */
  .hero__visual {
    grid-area: visual;
    display: grid;
    align-self: end;
    justify-self: center;
    gap: 12px;
    width: min(100%, clamp(282px, 26vw, 338px));
    min-width: 0;
  }

  .phone {
    position: relative;
    isolation: isolate;
    width: 100%;
    padding: 5px;
    border: 1px solid #41434a;
    border-radius: 36px 36px 0 0;
    background: linear-gradient(155deg, #383a40, var(--graphite) 28%, #101114 74%, #2c2d33);
    box-shadow:
      0 28px 56px -30px #000,
      inset 0 1px 0 var(--rule-soft);
  }

  .phone__screen {
    position: relative;
    overflow: hidden;
    border-radius: 30px 30px 0 0;
    background: #08090b;
  }

  .phone__screen::after {
    content: '';
    position: absolute;
    top: 8px;
    left: 50%;
    transform: translateX(-50%);
    width: 8px;
    height: 8px;
    border: 1px solid #41434a;
    border-radius: 50%;
    background: radial-gradient(circle at 38% 35%, #383a40, var(--graphite) 42%, var(--bg) 72%);
    pointer-events: none;
  }

  .phone__image {
    display: block;
    width: 100%;
    height: auto;
    aspect-ratio: 430 / 727;
    object-fit: cover;
    object-position: center 10%;
    animation: screen-in 220ms cubic-bezier(0.16, 1, 0.3, 1);
  }

  .phone__screen[data-screen='player'] .phone__image {
    object-position: center bottom;
  }

  .phone__screen[data-screen='browser'] .phone__image {
    object-fit: contain;
  }

  @keyframes screen-in {
    from {
      opacity: 0.35;
    }
    to {
      opacity: 1;
    }
  }

  .phone__hardware {
    position: absolute;
    inset: 0;
    pointer-events: none;
  }

  .phone__key {
    position: absolute;
    width: 3px;
    border-radius: 2px;
    background: #2e3036;
  }

  .phone__key--mute {
    top: 21%;
    left: -3px;
    height: 30px;
  }

  .phone__key--volume {
    top: 29%;
    left: -3px;
    height: 54px;
  }

  .phone__key--power {
    top: 26%;
    right: -3px;
    height: 76px;
  }

  .phone__note {
    order: -1;
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    gap: 2px 12px;
    padding-inline: 6px;
    color: var(--muted);
    font-size: 0.875rem;
    line-height: 1.45;
  }

  .phone__note strong {
    color: var(--text);
    font-size: 1rem;
    font-weight: 500;
  }

  .capabilities-summary {
    display: none;
  }


  /* Storage */
  .storage-section {
    padding-block: clamp(64px, 8vw, 112px);
  }

  .storage {
    display: grid;
    grid-template-columns: minmax(0, 0.85fr) minmax(0, 1.15fr);
    align-items: center;
    gap: clamp(48px, 7vw, 104px);
  }

  .storage__media {
    display: flex;
    justify-content: center;
  }

  .storage__image {
    display: block;
    width: min(100%, 400px);
    height: auto;
    border-radius: 22px;
    box-shadow: 0 50px 90px -50px rgba(0, 0, 0, 0.85);
  }

  .storage h2 {
    max-width: 18ch;
    font-size: clamp(1.8rem, 3vw, 2.6rem);
    font-weight: 500;
    line-height: 1.08;
    letter-spacing: -0.032em;
    text-wrap: balance;
  }

  .storage__lead {
    max-width: 52ch;
    margin-top: 20px;
    color: var(--muted);
    font-size: 1rem;
    line-height: 1.65;
  }

  .storage__facts {
    margin-top: 30px;
  }

  .storage__fact {
    display: grid;
    grid-template-columns: 172px minmax(0, 1fr);
    gap: 24px;
    padding: 18px 0;
    border-top: 1px solid var(--rule);
  }

  .storage__fact:last-child {
    padding-bottom: 0;
  }

  .storage__fact h3 {
    font-size: 1rem;
    font-weight: 500;
    line-height: 1.5;
  }

  .storage__fact p {
    color: var(--muted);
    font-size: 1rem;
    line-height: 1.6;
    text-wrap: pretty;
  }

  /* Shared sections */
  .section {
    padding-block: clamp(64px, 8vw, 112px);
    border-top: 1px solid var(--rule);
  }

  .section-head {
    display: grid;
    grid-template-columns: minmax(0, 1.1fr) minmax(0, 0.72fr);
    align-items: end;
    gap: 28px 64px;
    margin-bottom: clamp(36px, 4.5vw, 56px);
  }

  .section-head h2 {
    font-size: clamp(1.8rem, 3vw, 2.6rem);
    font-weight: 500;
    line-height: 1.05;
    letter-spacing: -0.032em;
    text-wrap: balance;
  }

  .section-head p {
    max-width: 44ch;
    color: var(--muted);
    font-size: 1rem;
    line-height: 1.6;
    justify-self: end;
    text-wrap: pretty;
  }

  .detail-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    column-gap: clamp(40px, 6vw, 88px);
  }

  .feature-details {
    border-top: 0;
    padding-bottom: 0;
  }

  .feature-details .detail-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .detail {
    padding: 22px 0 30px;
    border-top: 1px solid var(--rule);
  }

  .detail h3 {
    margin-bottom: 6px;
    font-size: 1.05rem;
    font-weight: 500;
    letter-spacing: -0.012em;
  }

  .detail p {
    max-width: 44ch;
    color: var(--muted);
    font-size: 1rem;
    line-height: 1.6;
    text-wrap: pretty;
  }

  /* Platforms */
  .platform-list {
    border-top: 1px solid var(--rule);
  }

  .platform {
    display: grid;
    grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr) auto 150px;
    align-items: center;
    gap: 24px;
    padding: 24px 0;
    border-bottom: 1px solid var(--rule);
  }

  .platform__name {
    font-size: 1.05rem;
    font-weight: 500;
    letter-spacing: -0.015em;
  }

  .platform__scope {
    color: var(--muted);
    font-size: 1rem;
  }

  .status {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    justify-self: start;
    padding: 5px 12px;
    border: 1px solid var(--rule);
    border-radius: 999px;
    color: var(--muted);
    font-size: 0.8rem;
    font-weight: 500;
    letter-spacing: 0.02em;
    line-height: 1.5;
    white-space: nowrap;
  }

  .status--available {
    color: var(--pink);
    background: var(--surface);
  }

  .status__dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--pink);
  }

  .status__dot--pending {
    background: transparent;
    border: 1px solid var(--quiet);
  }

  .platform__action {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    justify-self: end;
    color: var(--pink);
    font-size: 0.95rem;
    font-weight: 500;
    text-decoration: underline;
    text-decoration-thickness: 1px;
    text-underline-offset: 5px;
    transition:
      color 160ms ease,
      text-decoration-color 160ms ease;
  }

  .platform__action:hover {
    color: var(--rose);
    text-decoration-color: currentColor;
  }

  .platform__action svg {
    width: 16px;
    height: 16px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.6;
    stroke-linecap: round;
    stroke-linejoin: round;
  }

  .platform__pending {
    justify-self: end;
    color: var(--quiet);
    font-size: 0.9rem;
    text-align: right;
  }

  .platform-note {
    max-width: 60ch;
    margin-top: 20px;
    color: var(--quiet);
    font-size: 0.875rem;
    line-height: 1.6;
  }

  /* Philosophy */
  .philosophy {
    padding-block: clamp(72px, 9vw, 128px);
    border-top: 1px solid var(--rule);
  }

  .philosophy h2 {
    max-width: 20ch;
    font-size: clamp(2rem, 4vw, 3.25rem);
    font-weight: 500;
    line-height: 1.06;
    letter-spacing: -0.035em;
    text-wrap: balance;
  }

  .philosophy__statement {
    max-width: 30ch;
    margin-top: 26px;
    color: var(--text);
    font-size: clamp(1.1rem, 1.7vw, 1.35rem);
    line-height: 1.5;
    letter-spacing: -0.012em;
    text-wrap: pretty;
  }


  /* Footer */
  .site-footer {
    border-top: 1px solid var(--rule);
  }

  .footer {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 20px 32px;
    padding-block: 26px;
  }

  .brand--footer .brand__mark {
    height: 24px;
  }

  .brand--footer .brand__name {
    font-size: 1.05rem;
  }

  .footer__meta {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 14px 28px;
    color: var(--quiet);
    font-size: 0.85rem;
  }


  .footer__link {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    color: var(--muted);
    text-decoration: underline;
    text-decoration-color: var(--quiet);
    text-underline-offset: 4px;
    transition:
      color 160ms ease,
      text-decoration-color 160ms ease;
  }

  .footer__link:hover {
    color: var(--text);
    text-decoration-color: currentColor;
  }

  .footer__link svg {
    width: 14px;
    height: 14px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.5;
    stroke-linecap: round;
    stroke-linejoin: round;
  }

  /* Responsive */
  @media (max-width: 1120px) {
    .hero {
      padding-top: 28px;
    }

    .platform {
      grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr) auto 130px;
    }
  }

  @media (max-width: 920px) {
    .hero {
      column-gap: 24px;
    }

    .hero__copy {
      padding-block: 20px 68px;
    }

    .hero__visual {
      width: min(100%, 290px);
    }

    .cap {
      justify-content: center;
      gap: 10px;
      min-height: 60px;
      padding: 8px;
    }

    .cap:first-child,
    .cap:last-child {
      padding-inline: 4px;
    }

    .cap__art {
      width: 34px;
      height: 34px;
    }

    .cap__text {
      display: none;
    }
    .phone__note strong {
      display: none;
    }

    .capabilities-summary {
      display: grid;
      place-content: center;
      min-height: 3.5rem;
      margin: 0;
      padding: 8px 12px;
      border-top: 1px solid var(--rule);
      color: var(--muted);
      font-size: 0.95rem;
      line-height: 1.4;
      text-align: center;
      text-wrap: balance;
    }

    .storage {
      grid-template-columns: minmax(0, 0.7fr) minmax(0, 1.3fr);
      gap: 28px;
    }

    .storage__fact {
      grid-template-columns: minmax(0, 1fr);
      gap: 6px;
    }

    .section-head {
      grid-template-columns: minmax(0, 1fr);
      gap: 14px;
    }

    .section-head p {
      justify-self: start;
    }

    .feature-details .detail-grid {
      grid-template-columns: minmax(0, 1fr);
    }

    .platform {
      grid-template-columns: minmax(0, 1fr) auto;
      gap: 10px 20px;
    }

    .platform__name {
      grid-column: 1;
      grid-row: 1;
    }

    .platform__scope {
      grid-column: 1;
      grid-row: 2;
    }

    .status {
      grid-column: 2;
      grid-row: 1;
      justify-self: end;
    }

    .platform__action,
    .platform__pending {
      grid-column: 1 / -1;
      grid-row: 3;
      justify-self: start;
      margin-top: 4px;
      text-align: left;
    }
  }

  @media (min-width: 641px) and (max-width: 920px) {
    :global(html[lang='ru']) .hero__title {
      font-size: clamp(1.875rem, 4.65vw, 2.7rem);
    }
  }

  @media (max-width: 640px) {
    .hero {
      grid-template-columns: minmax(0, 1fr);
      grid-template-areas: 'copy' 'visual' 'caps';
      padding-top: 32px;
    }

    .hero__copy {
      padding: 0;
    }

    .hero__lead {
      font-size: 1rem;
      margin-top: 12px;
    }

    .hero__actions {
      margin-top: 20px;
    }

    .hero__visual {
      width: clamp(224px, 66vw, 300px);
      margin-top: 24px;
      gap: 8px;
    }

    .phone__note {
      justify-content: center;
      text-align: center;
      padding: 0;
    }

    .storage {
      grid-template-columns: minmax(0, 1fr) clamp(76px, 21vw, 140px);
      gap: 18px;
    }

    .storage__copy {
      display: contents;
    }

    .storage__media {
      grid-area: 1 / 2;
    }

    .storage h2 {
      grid-area: 1 / 1;
    }

    .storage__lead, .storage__facts {
      grid-column: 1 / -1;
      margin-top: 0;
    }
  }

  @media (max-width: 880px) {
    .menu-toggle {
      display: inline-flex;
    }

    .nav {
      position: absolute;
      top: 100%;
      left: 0;
      right: 0;
      display: none;
      flex-direction: column;
      align-items: stretch;
      gap: 0;
      padding: 8px var(--pad) 24px;
      max-height: calc(100dvh - 72px);
      overflow-y: auto;
      border-bottom: 1px solid var(--rule);
      background: var(--surface);
      box-shadow: 0 30px 50px -30px rgba(0, 0, 0, 0.9);
    }

    .nav.is-open {
      display: flex;
    }

    .nav__link {
      padding: 14px 0;
      border-bottom: 1px solid var(--rule-soft);
    }

    .nav .button {
      margin-top: 16px;
      padding-block: 13px;
    }
  }

  @media (max-width: 640px) {
    .header {
      min-height: 64px;
      gap: 8px;
    }

    .menu-toggle {
      min-width: 44px;
      justify-content: center;
    }

    .hero__title {
      font-size: clamp(2.6rem, 11.5vw, 3.4rem);
    }

    :global(html[lang='ru']) .hero__title {
      font-size: clamp(1.875rem, 9vw, 3.4rem);
    }

    .hero__actions {
      gap: 4px 12px;
    }

    .hero__actions .button {
      gap: 6px;
      padding: 14px;
      font-size: 0.95rem;
    }

    .text-link {
      font-size: 0.95rem;
    }

    .detail-grid {
      grid-template-columns: minmax(0, 1fr);
    }

    .cap {
      flex-direction: column;
      gap: 4px;
      padding: 8px 4px;
      text-align: center;
    }

    .footer {
      flex-direction: column;
      align-items: flex-start;
    }
  }

  @media (max-width: 400px) {
    .menu-toggle span {
      display: none;
    }
  }

  @media (pointer: coarse) {
    .header .brand, .nav__link, .nav .button, .platform__action, .footer__link {
      min-height: 44px;
    }
  }
</style>
