// Тема оформления: «как в системе», светлая или тёмная. Выбор хранится в профиле (с сервера приходит после входа)
// и в localStorage — чтобы страница входа и первый кадр после перезагрузки были в нужной теме, без мигания.
import { computed, ref } from 'vue'

export type ThemePref = 'system' | 'light' | 'dark'
export type Theme = 'light' | 'dark'

const STORAGE_KEY = 'vh.theme'
const isPref = (v: unknown): v is ThemePref => v === 'system' || v === 'light' || v === 'dark'

function stored(): ThemePref {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    return isPref(v) ? v : 'system'
  } catch {
    return 'system'
  }
}

const media = typeof window !== 'undefined' ? window.matchMedia('(prefers-color-scheme: light)') : null
const systemLight = ref(media?.matches ?? false)
export const themePref = ref<ThemePref>(typeof window !== 'undefined' ? stored() : 'system')
export const theme = computed<Theme>(() => (themePref.value === 'system' ? (systemLight.value ? 'light' : 'dark') : themePref.value))

const THEME_COLOR: Record<Theme, string> = { dark: '#070914', light: '#f4f5fb' }

function apply() {
  if (typeof document === 'undefined') return
  document.documentElement.dataset.theme = theme.value
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLOR[theme.value])
}

export function setThemePref(p: ThemePref) {
  themePref.value = p
  try {
    localStorage.setItem(STORAGE_KEY, p)
  } catch {
    /* без хранилища выбор живёт до перезагрузки */
  }
  apply()
}

/** Подключает тему: применяет её, следит за системной настройкой и за выбором в других вкладках. */
export function initTheme() {
  apply()
  media?.addEventListener('change', (e) => {
    systemLight.value = e.matches
    apply()
  })
  window.addEventListener('storage', (e) => {
    if (e.key === STORAGE_KEY && isPref(e.newValue) && e.newValue !== themePref.value) {
      themePref.value = e.newValue
      apply()
    }
  })
}
