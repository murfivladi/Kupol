// PWA: регистрация service worker, предложение обновиться, установка приложения и признак «нет сети».
// Состояние реактивное — его читают полоска в App.vue и карточка «Приложение» в настройках.
import { reactive } from 'vue'

interface InstallPromptEvent extends Event {
  prompt(): Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

export const pwa = reactive({
  online: typeof navigator === 'undefined' ? true : navigator.onLine,
  updateReady: false, // новая версия панели скачана и ждёт перезагрузки
  canInstall: false, // браузер готов показать системный диалог установки
  installed: false, // панель открыта как установленное приложение
})

let waiting: ServiceWorker | null = null
let installEvent: InstallPromptEvent | null = null

/** iOS Safari не умеет beforeinstallprompt: там установка — «Поделиться → На экран „Домой“». */
export function isIOS(): boolean {
  return /iphone|ipad|ipod/i.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
}

export function initPwa(register: boolean) {
  pwa.installed = window.matchMedia('(display-mode: standalone)').matches || (navigator as { standalone?: boolean }).standalone === true
  window.addEventListener('online', () => (pwa.online = true))
  window.addEventListener('offline', () => (pwa.online = false))
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault() // свой пункт «Установить» вместо баннера браузера в неудобный момент
    installEvent = e as InstallPromptEvent
    pwa.canInstall = true
  })
  window.addEventListener('appinstalled', () => {
    pwa.canInstall = false
    pwa.installed = true
    installEvent = null
  })
  if (register && 'serviceWorker' in navigator) void registerWorker()
}

async function registerWorker() {
  try {
    const reg = await navigator.serviceWorker.register('/sw.js', { scope: '/' })
    const track = (w: ServiceWorker | null) => {
      if (!w) return
      w.addEventListener('statechange', () => {
        // installed при уже работающем worker'е — это обновление, а не первая установка
        if (w.state === 'installed' && navigator.serviceWorker.controller) {
          waiting = w
          pwa.updateReady = true
        }
      })
    }
    if (reg.waiting && navigator.serviceWorker.controller) {
      waiting = reg.waiting
      pwa.updateReady = true
    }
    reg.addEventListener('updatefound', () => track(reg.installing))
    // Вкладка может жить днями: проверяем новую версию раз в час.
    setInterval(() => void reg.update().catch(() => {}), 60 * 60 * 1000)
    let reloading = false
    navigator.serviceWorker.addEventListener('controllerchange', () => {
      if (reloading) return
      reloading = true
      location.reload()
    })
  } catch {
    // без service worker панель работает как обычный сайт
  }
}

/** Перезагрузка на новую версию (по кнопке пользователя). */
export function applyUpdate() {
  if (waiting) waiting.postMessage('SKIP_WAITING')
  else location.reload()
}

/** Системный диалог установки; true — пользователь согласился. */
export async function install(): Promise<boolean> {
  if (!installEvent) return false
  await installEvent.prompt()
  const { outcome } = await installEvent.userChoice
  installEvent = null
  pwa.canInstall = false
  return outcome === 'accepted'
}
