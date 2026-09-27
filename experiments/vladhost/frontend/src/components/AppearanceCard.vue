<script setup lang="ts">
// Оформление и время: тема (как в системе / светлая / тёмная) и часовой пояс для всех дат в панели.
// Выбор сохраняется в профиле и применяется сразу, на всех устройствах после входа.
import { CheckmarkCircleOutline, ContrastOutline, MoonOutline, SunnyOutline } from '@vicons/ionicons5'
import { NIcon, NSelect, useMessage } from 'naive-ui'
import { computed, ref } from 'vue'
import { ApiError } from '@/api/client'
import { formatDateTime, setTimeZone, useI18n } from '@/i18n'
import { setThemePref, themePref, type ThemePref } from '@/lib/theme'
import { useAuthStore } from '@/stores/auth'

const { t, locale } = useI18n()
const auth = useAuthStore()
const message = useMessage()
const busy = ref(false)

const themes = computed(() => [
  { value: 'system' as const, label: t('appearance.system'), icon: ContrastOutline },
  { value: 'light' as const, label: t('appearance.light'), icon: SunnyOutline },
  { value: 'dark' as const, label: t('appearance.dark'), icon: MoonOutline },
])

const browserZone = Intl.DateTimeFormat().resolvedOptions().timeZone
const zones: string[] = typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : [browserZone, 'UTC']
const zoneOptions = computed(() => [
  { label: t('appearance.browserZone', { zone: browserZone }), value: '' },
  ...zones.map((z) => ({ label: z.replaceAll('_', ' '), value: z })),
])
// Пример — текущее время в выбранном поясе: сразу видно, что выбрано правильно.
const now = new Date().toISOString()
const example = computed(() => formatDateTime(now, locale.value))

async function save(p: { theme?: ThemePref; timezone?: string }) {
  busy.value = true
  try {
    await auth.updatePreferences(p)
    message.success(t('appearance.saved'))
  } catch (e) {
    message.error(e instanceof ApiError ? e.message : t('appearance.saveFailed'))
  } finally {
    busy.value = false
  }
}

function pickTheme(v: ThemePref) {
  setThemePref(v) // сразу, не дожидаясь сервера
  void save({ theme: v })
}

function pickZone(v: string) {
  setTimeZone(v)
  void save({ timezone: v })
}
</script>

<template>
  <section class="appearance glass rise" style="--i: 2">
    <h3>{{ t('appearance.theme') }}</h3>
    <div class="tiles" role="radiogroup" :aria-label="t('appearance.theme')">
      <button
        v-for="o in themes"
        :key="o.value"
        type="button"
        role="radio"
        class="tile"
        :class="{ on: themePref === o.value }"
        :aria-checked="themePref === o.value"
        :data-testid="`theme-${o.value}`"
        @click="pickTheme(o.value)"
      >
        <n-icon :size="24" :component="o.icon" />
        <span class="name">{{ o.label }}</span>
        <n-icon v-if="themePref === o.value" class="ok" :size="22" :component="CheckmarkCircleOutline" />
      </button>
    </div>

    <h3 class="tz-title">{{ t('appearance.timezone') }}</h3>
    <p class="hint">{{ t('appearance.timezoneHint') }}</p>
    <n-select
      class="tz"
      filterable
      :value="auth.user?.timezone ?? ''"
      :options="zoneOptions"
      :disabled="busy"
      :input-props="{ 'aria-label': t('appearance.timezone') }"
      data-testid="timezone"
      @update:value="pickZone"
    />
    <p class="hint">{{ t('appearance.now', { date: example }) }}</p>
  </section>
</template>

<style scoped>
.appearance {
  padding: 22px 26px;
}

h3 {
  font-size: 17px;
  margin-bottom: 14px;
}

.tz-title {
  margin: 22px 0 4px;
}

.hint {
  margin: 0 0 10px;
  font-size: 13.5px;
  color: var(--text-dim);
}

.tz {
  max-width: 420px;
  margin-bottom: 8px;
}

.tiles {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 14px;
}

.tile {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: 16px;
  background: rgb(var(--ov) / 0.04);
  color: var(--text);
  font: inherit;
  font-weight: 650;
  cursor: pointer;
  transition:
    background 0.2s,
    border-color 0.2s;
}

.tile:hover {
  background: rgba(167, 139, 250, 0.12);
  border-color: rgba(167, 139, 250, 0.5);
}

.tile.on {
  background: linear-gradient(135deg, rgba(99, 102, 241, 0.22), rgba(236, 72, 153, 0.16));
  border-color: rgba(167, 139, 250, 0.8);
}

.name {
  flex: 1;
  text-align: left;
}

.ok {
  color: var(--emerald);
}
</style>
