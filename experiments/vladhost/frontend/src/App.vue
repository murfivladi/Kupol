<script setup lang="ts">
import {
  darkTheme,
  dateItIT,
  dateRuRU,
  itIT,
  NConfigProvider,
  NDialogProvider,
  NMessageProvider,
  lightTheme,
  ruRU,
} from 'naive-ui'
import { computed, watch } from 'vue'
import AuroraBackground from '@/components/AuroraBackground.vue'
import PwaBar from '@/components/PwaBar.vue'
import { setTimeZone, useI18n } from '@/i18n'
import { darkOverrides, lightOverrides } from '@/lib/naiveTheme'
import { setThemePref, theme } from '@/lib/theme'
import { useAuthStore } from '@/stores/auth'

const { locale } = useI18n()
const auth = useAuthStore()

// Язык писем (подтверждение, уведомления) следует за языком интерфейса: клиент — источник истины, сервер запоминает.
watch(
  [locale, () => auth.user?.id],
  ([l]) => {
    if (auth.user && auth.user.lang !== l) void auth.updatePreferences({ lang: l }).catch(() => {})
  },
  { immediate: true },
)

// Тема и часовой пояс — из профиля: после входа на любом устройстве панель выглядит так, как выбрал пользователь.
watch(
  () => [auth.user?.theme, auth.user?.timezone] as const,
  ([th, tz]) => {
    if (th) setThemePref(th)
    setTimeZone(tz ?? '')
  },
  { immediate: true },
)

// Локаль компонентов Naive UI (подписи в календарях, «Подтвердить/Отмена» и т. п.) следует за языком интерфейса.
const naiveLocale = computed(() => (locale.value === 'it' ? itIT : ruRU))
const naiveDateLocale = computed(() => (locale.value === 'it' ? dateItIT : dateRuRU))

const naiveTheme = computed(() => (theme.value === 'light' ? lightTheme : darkTheme))
const overrides = computed(() => (theme.value === 'light' ? lightOverrides : darkOverrides))
</script>

<template>
  <n-config-provider
    :theme="naiveTheme"
    :theme-overrides="overrides"
    :locale="naiveLocale"
    :date-locale="naiveDateLocale"
    abstract
  >
    <n-dialog-provider>
      <n-message-provider placement="top">
        <aurora-background />
        <router-view />
        <pwa-bar />
      </n-message-provider>
    </n-dialog-provider>
  </n-config-provider>
</template>
