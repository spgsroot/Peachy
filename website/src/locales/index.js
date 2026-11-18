import en from './en.json';
import ru from './ru.json';
import zhCN from './zh-CN.json';

export const messages = { en, ru, 'zh-CN': zhCN };
export const languages = [
  { id: 'en', label: 'English' },
  { id: 'ru', label: 'Русский' },
  { id: 'zh-CN', label: '简体中文' },
];

const storageKey = 'peachy-locale';

export function initialLocale() {
  if (typeof window === 'undefined') return 'en';

  try {
    const saved = window.localStorage.getItem(storageKey);
    if (Object.hasOwn(messages, saved)) return saved;
  } catch {
    // A blocked storage area must not prevent changing languages.
  }

  for (const language of navigator.languages?.length ? navigator.languages : [navigator.language]) {
    const base = language?.toLowerCase().split('-')[0];
    if (base === 'zh') return 'zh-CN';
    if (base === 'ru' || base === 'en') return base;
  }
  return 'en';
}

export function rememberLocale(locale) {
  try {
    window.localStorage.setItem(storageKey, locale);
  } catch {
    // The current visit still uses the chosen language when storage is unavailable.
  }
}
