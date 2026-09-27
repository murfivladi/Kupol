<script setup lang="ts">
// Мониторинг доступности сайта: включение, адрес проверки, письма, открытая статус-страница и сводка.
import { CopyOutline, OpenOutline } from '@vicons/ionicons5'
import { NButton, NForm, NFormItem, NIcon, NInput, NSwitch, useMessage } from 'naive-ui'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { api, ApiError } from '@/api/client'
import { siteMonitorResponseSchema, type Site, type SiteMonitor, type UptimeReport } from '@/api/schemas'
import StatusChip from '@/components/StatusChip.vue'
import UptimeReportView from '@/components/UptimeReportView.vue'
import { formatDateTime, useI18n } from '@/i18n'

const props = defineProps<{ site: Site }>()
const { t, locale } = useI18n()
const message = useMessage()

const monitor = ref<SiteMonitor | null>(null)
const report = ref<UptimeReport | null>(null)
const intervalMin = ref(2)
const loaded = ref(false)
const saving = ref(false)
const form = reactive({ enabled: true, path: '/', notify: true, public: false })
const pathError = ref('')

const errText = (e: unknown, fallback: string) => (e instanceof ApiError ? e.message : fallback)
const statusUrl = computed(() => `${location.origin}/status/${props.site.host}`)

function fill(m: SiteMonitor | null) {
  monitor.value = m
  if (m) Object.assign(form, { enabled: m.enabled, path: m.path, notify: m.notify, public: m.public })
}

let timer: ReturnType<typeof setTimeout> | undefined
async function load() {
  clearTimeout(timer)
  try {
    const r = await api(`/api/sites/${props.site.id}/monitor`, { schema: siteMonitorResponseSchema })
    if (!saving.value) fill(r.monitor)
    report.value = r.report ?? null
    if (r.interval_sec) intervalMin.value = Math.round(r.interval_sec / 60)
  } catch (e) {
    message.error(errText(e, t('uptime.loadFailed')))
  } finally {
    loaded.value = true
    // Пока идёт мониторинг, страница обновляется сама: состояние меняется раз в несколько минут.
    if (monitor.value?.enabled) timer = setTimeout(load, 60_000)
  }
}
onMounted(load)
onBeforeUnmount(() => clearTimeout(timer))

async function save() {
  saving.value = true
  pathError.value = ''
  try {
    const r = await api(`/api/sites/${props.site.id}/monitor`, { method: 'PUT', body: { ...form }, schema: siteMonitorResponseSchema })
    fill(r.monitor)
    message.success(t('uptime.saved'))
    await load()
  } catch (e) {
    if (e instanceof ApiError && e.field === 'path') pathError.value = e.message
    else message.error(errText(e, t('uptime.saveFailed')))
  } finally {
    saving.value = false
  }
}

async function copy() {
  try {
    await navigator.clipboard.writeText(statusUrl.value)
    message.success(t('common.copied'))
  } catch {
    message.error(t('common.copyFailed'))
  }
}

const lastError = computed(() => {
  const m = monitor.value
  if (!m?.last_error) return ''
  if (m.last_error === 'http') return t('uptime.lastHttp', { code: m.last_code })
  return (
    {
      timeout: t('uptime.reason.timeout'),
      tls: t('uptime.reason.tls'),
      dns: t('uptime.reason.dns'),
      connect: t('uptime.reason.connect'),
    } as Record<string, string>
  )[m.last_error] ?? t('uptime.reason.connect')
})
</script>

<template>
  <div class="page">
    <header class="head rise">
      <h1>{{ t('siteArea.monitor') }}</h1>
      <p>{{ t('uptime.hint', { n: intervalMin }) }}</p>
    </header>

    <section v-if="monitor?.enabled" class="glass card rise state" style="--i: 1" data-testid="monitor-state">
      <status-chip v-if="monitor.state === 'up'" tone="emerald">{{ t('uptime.state.up') }}</status-chip>
      <status-chip v-else-if="monitor.state === 'down'" tone="rose" pulse>{{ t('uptime.state.down') }}</status-chip>
      <status-chip v-else tone="slate">{{ t('uptime.state.unknown') }}</status-chip>
      <span v-if="monitor.state_since" class="dim">{{ t('uptime.since', { date: formatDateTime(monitor.state_since, locale) }) }}</span>
      <span v-if="monitor.last_checked_at" class="dim">
        · {{ t('uptime.lastCheck', { date: formatDateTime(monitor.last_checked_at, locale) }) }}
        <template v-if="lastError">— {{ lastError }}</template>
        <template v-else-if="monitor.last_code">— {{ monitor.last_code }}, {{ t('uptime.ms', { n: monitor.last_ms }) }}</template>
      </span>
    </section>

    <section v-if="monitor?.enabled && report" class="glass card rise" style="--i: 2">
      <uptime-report-view :report="report" />
    </section>

    <section v-if="loaded" class="glass card rise" style="--i: 3">
      <h3>{{ t('uptime.settings') }}</h3>
      <n-form @submit.prevent="save">
        <div class="switch-row">
          <n-switch v-model:value="form.enabled" data-testid="monitor-enabled" />
          <span>{{ t('uptime.enabled') }}</span>
        </div>
        <n-form-item :label="t('uptime.path')" :validation-status="pathError ? 'error' : undefined" :feedback="pathError || t('uptime.pathHint', { host: site.host })">
          <n-input v-model:value="form.path" placeholder="/" autocomplete="off" @update:value="pathError = ''" />
        </n-form-item>
        <div class="switch-row">
          <n-switch v-model:value="form.notify" />
          <span>{{ t('uptime.notify') }}</span>
        </div>
        <div class="switch-row">
          <n-switch v-model:value="form.public" />
          <span>{{ t('uptime.public') }}</span>
        </div>
        <div v-if="monitor?.public && monitor.enabled" class="public">
          <code>{{ statusUrl }}</code>
          <n-button size="small" quaternary :aria-label="t('common.copy')" @click="copy"><template #icon><n-icon :component="CopyOutline" /></template></n-button>
          <n-button size="small" quaternary tag="a" :href="statusUrl" target="_blank" rel="noopener" :aria-label="t('uptime.openStatus')">
            <template #icon><n-icon :component="OpenOutline" /></template>
          </n-button>
        </div>
        <n-button type="primary" attr-type="submit" :loading="saving" data-testid="monitor-save">{{ t('uptime.save') }}</n-button>
      </n-form>
    </section>
  </div>
</template>

<style scoped>
.page {
  display: grid;
  gap: 18px;
  max-width: 900px;
}

.head h1 {
  font-size: clamp(26px, 3vw, 34px);
  font-weight: 800;
}

.head p {
  margin: 4px 0 12px;
  color: var(--text-dim);
}

.card {
  padding: 20px 24px;
}

.card h3 {
  font-size: 17px;
  margin-bottom: 12px;
}

.state {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.dim {
  color: var(--text-dim);
  font-size: 14px;
}

.switch-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.public {
  display: flex;
  align-items: center;
  gap: 4px;
  margin: -6px 0 16px;
  overflow-wrap: anywhere;
}
</style>
