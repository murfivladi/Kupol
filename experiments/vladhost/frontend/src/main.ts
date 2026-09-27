import '@fontsource-variable/inter'
import { createPinia } from 'pinia'
import { createApp } from 'vue'
import { onUnauthorized } from '@/api/client'
import { applyLocale, watchStorage } from '@/i18n'
import { initPwa } from '@/lib/pwa'
import { initTheme } from '@/lib/theme'
import { router } from '@/router'
import { useAuthStore } from '@/stores/auth'
import '@/styles/theme.css'
import App from './App.vue'

// Язык документа и заголовок вкладки — до первого показа, чтобы не мигало.
applyLocale()
watchStorage()
initTheme()

const app = createApp(App)
app.use(createPinia())
app.use(router)

// Сессия истекла посреди работы — возвращаем на вход.
onUnauthorized(() => {
  useAuthStore().clear()
  if (router.currentRoute.value.meta.auth) {
    void router.push({ name: 'login', query: { next: router.currentRoute.value.fullPath } })
  }
})

app.mount('#app')

// Service worker — только в сборке: в режиме разработки он мешал бы горячей перезагрузке.
initPwa(import.meta.env.PROD)
