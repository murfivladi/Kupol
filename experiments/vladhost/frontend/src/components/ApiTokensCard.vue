<script setup lang="ts">
// API-токены для деплоя из CI: список, выпуск (секрет показывается один раз) и отзыв.
import { CopyOutline, KeyOutline } from '@vicons/ionicons5'
import { NButton, NForm, NFormItem, NIcon, NInput, NModal, NPopconfirm, NSelect, NSpace, useMessage } from 'naive-ui'
import { computed, onMounted, reactive, ref } from 'vue'
import { api, apiVoid, ApiError } from '@/api/client'
import { apiTokensSchema, createdApiTokenSchema, sitesSchema, type ApiToken, type Site } from '@/api/schemas'
import FormModal from '@/components/FormModal.vue'
import PlusButton from '@/components/PlusButton.vue'
import StatusChip from '@/components/StatusChip.vue'
import { formatDateTime, useI18n } from '@/i18n'

const { t, locale } = useI18n()
const message = useMessage()
const tokens = ref<ApiToken[]>([])
const sites = ref<Site[]>([])
const loaded = ref(false)
const busy = ref<number | null>(null)

const showForm = ref(false)
const creating = ref(false)
const form = reactive<{ name: string; siteId: number | null; days: number }>({ name: '', siteId: null, days: 365 })
const errors = ref<Record<string, string>>({})
const created = ref<{ secret: string; token: ApiToken } | null>(null)

const siteOptions = computed(() => [
  { label: t('settings.tokens.anySite'), value: null as unknown as number },
  ...sites.value.map((s) => ({ label: s.host, value: s.id })),
])
const ttlOptions = computed(() => [
  { label: t('settings.tokens.days', { n: 30 }), value: 30 },
  { label: t('settings.tokens.days', { n: 90 }), value: 90 },
  { label: t('settings.tokens.days', { n: 365 }), value: 365 },
  { label: t('settings.tokens.noExpiry'), value: 0 },
])

const errText = (e: unknown, fallback: string) => (e instanceof ApiError ? e.message : fallback)
const expired = (tk: ApiToken) => tk.expires_at !== null && new Date(tk.expires_at) <= new Date()

async function load() {
  try {
    const [tk, st] = await Promise.all([api('/api/me/tokens', { schema: apiTokensSchema }), api('/api/sites', { schema: sitesSchema })])
    tokens.value = tk.tokens
    sites.value = st.sites
  } catch (e) {
    message.error(errText(e, t('settings.tokens.loadFailed')))
  } finally {
    loaded.value = true
  }
}
onMounted(load)

function openForm() {
  form.name = ''
  form.siteId = sites.value.length === 1 ? sites.value[0]!.id : null
  form.days = 365
  errors.value = {}
  showForm.value = true
}

async function create() {
  if (!form.name.trim()) {
    errors.value = { name: t('settings.tokens.nameRequired') }
    return
  }
  creating.value = true
  try {
    const r = await api('/api/me/tokens', {
      method: 'POST',
      body: { name: form.name.trim(), site_id: form.siteId, expires_days: form.days },
      schema: createdApiTokenSchema,
    })
    showForm.value = false
    created.value = r
    await load()
  } catch (e) {
    if (e instanceof ApiError && e.field) errors.value = { [e.field === 'expires_days' ? 'days' : e.field]: e.message }
    else message.error(errText(e, t('settings.tokens.createFailed')))
  } finally {
    creating.value = false
  }
}

async function revoke(tk: ApiToken) {
  busy.value = tk.id
  try {
    await apiVoid(`/api/me/tokens/${tk.id}`, { method: 'DELETE' })
    message.success(t('settings.tokens.revoked'))
    await load()
  } catch (e) {
    message.error(errText(e, t('settings.tokens.revokeFailed')))
  } finally {
    busy.value = null
  }
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    message.success(t('common.copied'))
  } catch {
    message.error(t('common.copyFailed'))
  }
}

// Пример для CI: архив папки dist и один запрос. Для токена одного сайта имя сайта в адресе не нужно.
const example = computed(() => {
  const tk = created.value?.token
  const site = tk?.site_id ? '' : `?site=${sites.value[0]?.slug ?? 'SITE'}`
  return `(cd dist && zip -qr ../site.zip .)\ncurl -fsS -H "Authorization: Bearer $VLADHOST_TOKEN" \\\n  -F file=@site.zip "${location.origin}/api/ci/deploy${site}"`
})
</script>

<template>
  <section class="tokens glass rise" style="--i: 7">
    <div class="head">
      <div>
        <h3>{{ t('settings.tokens.title') }}</h3>
        <p class="hint">
          {{ t('settings.tokens.hint') }}
          <router-link to="/help/14-ci-deploy">{{ t('settings.tokens.howTo') }}</router-link>
        </p>
      </div>
      <plus-button :label="t('settings.tokens.create')" data-testid="token-add" @click="openForm" />
    </div>

    <p v-if="loaded && tokens.length === 0" class="empty">{{ t('settings.tokens.empty') }}</p>
    <ul v-else-if="loaded" class="list">
      <li v-for="tk in tokens" :key="tk.id" class="row" data-testid="token-row">
        <n-icon class="icon" :size="24" :component="KeyOutline" />
        <div class="info">
          <div class="name">
            {{ tk.name }}
            <code>{{ tk.prefix }}…</code>
            <status-chip v-if="expired(tk)" tone="slate">{{ t('settings.tokens.expired') }}</status-chip>
            <status-chip v-else tone="cyan">{{ tk.site_id ? tk.site_host : t('settings.tokens.anySite') }}</status-chip>
          </div>
          <div class="meta">
            {{ t('settings.tokens.createdAt', { date: formatDateTime(tk.created_at, locale) }) }} ·
            <template v-if="tk.expires_at">{{ t('settings.tokens.expiresAt', { date: formatDateTime(tk.expires_at, locale) }) }} · </template>
            <template v-if="tk.last_used_at">{{ t('settings.tokens.lastUsed', { date: formatDateTime(tk.last_used_at, locale), ip: tk.last_used_ip }) }}</template>
            <template v-else>{{ t('settings.tokens.neverUsed') }}</template>
          </div>
        </div>
        <n-popconfirm @positive-click="revoke(tk)">
          <template #trigger>
            <n-button size="small" :loading="busy === tk.id">{{ t('settings.tokens.revoke') }}</n-button>
          </template>
          {{ t('settings.tokens.revokeConfirm') }}
        </n-popconfirm>
      </li>
    </ul>

    <form-modal v-model:show="showForm" :title="t('settings.tokens.create')">
      <n-form @submit.prevent="create">
        <n-form-item :label="t('settings.tokens.name')" :validation-status="errors.name ? 'error' : undefined" :feedback="errors.name">
          <n-input v-model:value="form.name" :placeholder="t('settings.tokens.namePlaceholder')" :maxlength="60" autocomplete="off" @update:value="errors.name = ''" />
        </n-form-item>
        <n-form-item :label="t('settings.tokens.site')">
          <n-select v-model:value="form.siteId" :options="siteOptions" />
        </n-form-item>
        <n-form-item :label="t('settings.tokens.ttl')" :validation-status="errors.days ? 'error' : undefined" :feedback="errors.days">
          <n-select v-model:value="form.days" :options="ttlOptions" />
        </n-form-item>
        <n-button type="primary" attr-type="submit" :loading="creating" block>{{ t('settings.tokens.create') }}</n-button>
      </n-form>
    </form-modal>

    <n-modal :show="created !== null" preset="card" :title="t('settings.tokens.createdTitle')" style="max-width: 640px" :mask-closable="false" @update:show="created = null">
      <template v-if="created">
        <p class="note">{{ t('settings.tokens.createdHint') }}</p>
        <pre class="secret" data-testid="token-secret">{{ created.secret }}</pre>
        <p class="note">{{ t('settings.tokens.exampleHint') }}</p>
        <pre class="secret">{{ example }}</pre>
        <n-space :size="10">
          <n-button type="primary" @click="copy(created.secret)">
            <template #icon><n-icon :component="CopyOutline" /></template>
            {{ t('settings.tokens.copy') }}
          </n-button>
          <n-button @click="created = null">{{ t('settings.tokens.done') }}</n-button>
        </n-space>
      </template>
    </n-modal>
  </section>
</template>

<style scoped>
.tokens {
  padding: 22px 26px;
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 8px;
}

h3 {
  font-size: 17px;
  margin-bottom: 2px;
}

.hint,
.empty,
.note {
  margin: 0;
  font-size: 13.5px;
  color: var(--text-dim);
}

.note {
  margin-bottom: 10px;
}

.list {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
  display: grid;
}

.row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 0;
  border-top: 1px solid var(--border);
}

.row:first-child {
  border-top: 0;
}

.icon {
  color: var(--text-dim);
  flex: none;
}

.info {
  flex: 1;
  min-width: 0;
}

.name {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  font-weight: 650;
}

.meta {
  margin-top: 3px;
  font-size: 13px;
  color: var(--text-dim);
  overflow-wrap: anywhere;
}

.secret {
  margin: 0 0 14px;
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--surface-2, rgba(127, 127, 127, 0.1));
  font-size: 13px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
