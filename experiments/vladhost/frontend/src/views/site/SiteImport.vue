<script setup lang="ts">
// Импорт сайта: архив по ссылке или перенос по FTP со старого хостинга. Задача идёт на сервере, страница опрашивает её состояние.
import { CloudDownloadOutline } from '@vicons/ionicons5'
import { NAlert, NButton, NCheckbox, NForm, NFormItem, NIcon, NInput, NInputNumber, NRadioButton, NRadioGroup, useMessage } from 'naive-ui'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { api, ApiError } from '@/api/client'
import { siteImportResponseSchema, type Site, type SiteImport } from '@/api/schemas'
import StatusChip from '@/components/StatusChip.vue'
import { formatBytes, formatDateTime, useI18n } from '@/i18n'
import { useSitesStore } from '@/stores/sites'

const props = defineProps<{ site: Site }>()
const { t, locale } = useI18n()
const message = useMessage()
const store = useSitesStore()

const kind = ref<'url' | 'ftp'>('url')
const form = reactive({ url: '', host: '', port: 21, username: '', password: '', path: '/public_html', tls: true })
const errors = ref<Record<string, string>>({})
const job = ref<SiteImport | null>(null)
const starting = ref(false)
const running = computed(() => job.value?.status === 'running')

const errText = (e: unknown, fallback: string) => (e instanceof ApiError ? e.message : fallback)

let timer: ReturnType<typeof setTimeout> | undefined
async function poll() {
  clearTimeout(timer)
  try {
    const wasRunning = running.value
    job.value = (await api(`/api/sites/${props.site.id}/import`, { schema: siteImportResponseSchema })).job
    if (running.value) {
      timer = setTimeout(poll, 2000)
    } else if (wasRunning && job.value) {
      if (job.value.status === 'done') message.success(t('siteImport.done'))
      await store.load(t('sites.loadFailed'))
    }
  } catch (e) {
    message.error(errText(e, t('siteImport.loadFailed')))
  }
}
onMounted(poll)
onBeforeUnmount(() => clearTimeout(timer))

async function start() {
  errors.value = {}
  if (kind.value === 'url' && !form.url.trim()) {
    errors.value = { url: t('siteImport.urlRequired') }
    return
  }
  if (kind.value === 'ftp' && !form.host.trim()) {
    errors.value = { host: t('siteImport.hostRequired') }
    return
  }
  starting.value = true
  try {
    const body = kind.value === 'url' ? { kind: 'url', url: form.url.trim() } : { kind: 'ftp', ...form }
    job.value = (await api(`/api/sites/${props.site.id}/import`, { method: 'POST', body, schema: siteImportResponseSchema })).job
    form.password = '' // пароль нужен только на время переноса
    timer = setTimeout(poll, 1500)
  } catch (e) {
    if (e instanceof ApiError && e.field) errors.value = { [e.field]: e.message }
    else message.error(errText(e, t('siteImport.startFailed')))
  } finally {
    starting.value = false
  }
}
</script>

<template>
  <div class="page">
    <header class="head rise">
      <h1>{{ t('siteArea.import') }}</h1>
      <p>{{ t('siteImport.hint') }}</p>
    </header>

    <section v-if="job" class="glass card rise" style="--i: 1" data-testid="import-job">
      <div class="row">
        <span class="k">{{ t('siteImport.last') }}</span>
        <span class="chips">
          <status-chip v-if="job.status === 'running'" tone="amber" pulse>{{ t('siteImport.status.running') }}</status-chip>
          <status-chip v-else-if="job.status === 'done'" tone="emerald">{{ t('siteImport.status.done') }}</status-chip>
          <status-chip v-else tone="rose">{{ t('siteImport.status.failed') }}</status-chip>
        </span>
      </div>
      <div class="row">
        <span class="k">{{ t('siteImport.source') }}</span>
        <code class="src">{{ job.source }}</code>
      </div>
      <div class="row">
        <span class="k">{{ t('siteImport.started') }}</span>
        <span>{{ formatDateTime(job.created_at, locale) }}</span>
      </div>
      <div v-if="job.status !== 'running'" class="row">
        <span class="k">{{ t('siteImport.received') }}</span>
        <span>{{ t('siteImport.receivedValue', { size: formatBytes(job.bytes, locale), n: job.files }) }}</span>
      </div>
      <n-alert v-if="job.error" type="error" :show-icon="false" class="err">{{ job.error }}</n-alert>
    </section>

    <section class="glass card rise" style="--i: 2">
      <n-alert type="warning" :show-icon="false" class="warn">{{ t('siteImport.replaceWarning') }}</n-alert>
      <n-radio-group v-model:value="kind" name="kind" class="kind">
        <n-radio-button value="url">{{ t('siteImport.byUrl') }}</n-radio-button>
        <n-radio-button value="ftp">{{ t('siteImport.byFtp') }}</n-radio-button>
      </n-radio-group>

      <n-form @submit.prevent="start">
        <template v-if="kind === 'url'">
          <p class="note">{{ t('siteImport.urlHint') }}</p>
          <n-form-item :label="t('siteImport.url')" :validation-status="errors.url ? 'error' : undefined" :feedback="errors.url">
            <n-input v-model:value="form.url" placeholder="https://example.com/site.tar.gz" autocomplete="off" @update:value="errors.url = ''" />
          </n-form-item>
        </template>
        <template v-else>
          <p class="note">{{ t('siteImport.ftpHint') }}</p>
          <div class="grid">
            <n-form-item :label="t('siteImport.host')" :validation-status="errors.host ? 'error' : undefined" :feedback="errors.host">
              <n-input v-model:value="form.host" placeholder="ftp.old-hosting.ru" autocomplete="off" @update:value="errors.host = ''" />
            </n-form-item>
            <n-form-item :label="t('siteImport.port')">
              <n-input-number v-model:value="form.port" :min="1" :max="65535" :show-button="false" />
            </n-form-item>
          </div>
          <div class="grid two">
            <n-form-item :label="t('siteImport.username')">
              <n-input v-model:value="form.username" autocomplete="off" />
            </n-form-item>
            <n-form-item :label="t('siteImport.password')" :validation-status="errors.password ? 'error' : undefined" :feedback="errors.password">
              <n-input v-model:value="form.password" type="password" show-password-on="click" autocomplete="new-password" />
            </n-form-item>
          </div>
          <n-form-item :label="t('siteImport.path')" :validation-status="errors.path ? 'error' : undefined" :feedback="errors.path">
            <n-input v-model:value="form.path" placeholder="/public_html" autocomplete="off" />
          </n-form-item>
          <n-checkbox v-model:checked="form.tls" class="tls">{{ t('siteImport.tls') }}</n-checkbox>
        </template>
        <n-button type="primary" attr-type="submit" :loading="starting || running" :disabled="running" data-testid="import-start">
          <template #icon><n-icon :component="CloudDownloadOutline" /></template>
          {{ running ? t('siteImport.status.running') : t('siteImport.start') }}
        </n-button>
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

.head p,
.note {
  margin: 4px 0 12px;
  color: var(--text-dim);
}

.card {
  padding: 20px 24px;
}

.row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 10px 0;
  border-bottom: 1px solid var(--border);
}

.row:last-of-type {
  border-bottom: 0;
}

.k {
  width: 160px;
  flex: none;
  color: var(--text-dim);
}

.src {
  overflow-wrap: anywhere;
}

.chips {
  display: flex;
  gap: 8px;
}

.err,
.warn {
  margin: 8px 0 14px;
}

.kind {
  margin-bottom: 8px;
}

.grid {
  display: grid;
  grid-template-columns: 1fr 120px;
  column-gap: 14px;
}

.grid.two {
  grid-template-columns: 1fr 1fr;
}

.tls {
  display: flex;
  margin: 0 0 16px;
}

@media (max-width: 640px) {
  .grid,
  .grid.two {
    grid-template-columns: 1fr;
  }

  .k {
    width: 110px;
  }
}
</style>
