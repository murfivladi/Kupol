<script setup lang="ts">
// Очередь жалоб на сайты: разбор (приостановить сайт, закрыть с заметкой или отклонить).
import { NAlert, NButton, NInput, NModal, NRadioButton, NRadioGroup, NSpace, useMessage } from 'naive-ui'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { api, ApiError } from '@/api/client'
import { abuseListSchema, abuseReportResponseSchema, type AbuseReport } from '@/api/schemas'
import StatusChip from '@/components/StatusChip.vue'
import { formatDateTime, useI18n } from '@/i18n'

const { t, locale } = useI18n()
const message = useMessage()
const status = ref<'new' | 'resolved' | 'rejected' | ''>('new')
const reports = ref<AbuseReport[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref('')

async function load(more = false) {
  loading.value = true
  try {
    const p = new URLSearchParams()
    if (status.value) p.set('status', status.value)
    if (more) p.set('offset', String(reports.value.length))
    const r = await api(`/api/admin/abuse?${p}`, { schema: abuseListSchema })
    reports.value = more ? [...reports.value, ...r.reports] : r.reports
    total.value = r.total
    loadError.value = ''
  } catch (e) {
    loadError.value = e instanceof ApiError ? e.message : t('admin.loadFailed')
  } finally {
    loading.value = false
  }
}
onMounted(() => load())
watch(status, () => void load())

const category = (c: AbuseReport['category']) =>
  ({
    phishing: t('abuse.categories.phishing'),
    malware: t('abuse.categories.malware'),
    spam: t('abuse.categories.spam'),
    copyright: t('abuse.categories.copyright'),
    illegal: t('abuse.categories.illegal'),
    other: t('abuse.categories.other'),
  })[c]

// Окно действия: приостановка сайта (причина) или закрытие жалобы (заметка).
const dlg = reactive<{ open: boolean; kind: 'suspend' | 'resolved' | 'rejected'; report: AbuseReport | null; text: string }>({ open: false, kind: 'suspend', report: null, text: '' })
const busy = ref(false)
const dlgTitle = computed(() =>
  dlg.kind === 'suspend' ? t('admin.abuse.suspendTitle') : dlg.kind === 'resolved' ? t('admin.abuse.resolveTitle') : t('admin.abuse.rejectTitle'),
)

function ask(kind: typeof dlg.kind, r: AbuseReport) {
  dlg.kind = kind
  dlg.report = r
  dlg.text = kind === 'suspend' ? t('admin.abuse.suspendReason', { id: r.id, category: category(r.category) }) : ''
  dlg.open = true
}

async function confirm() {
  const r = dlg.report
  if (!r) return
  busy.value = true
  try {
    if (dlg.kind === 'suspend') {
      await api(`/api/admin/sites/${r.site_id}/suspend`, { method: 'POST', body: { reason: dlg.text } })
      message.success(t('admin.siteSuspended'))
    } else {
      await api(`/api/admin/abuse/${r.id}`, { method: 'PATCH', body: { status: dlg.kind, note: dlg.text }, schema: abuseReportResponseSchema })
      message.success(t('admin.abuse.saved'))
    }
    dlg.open = false
    await load()
  } catch (e) {
    message.error(e instanceof ApiError ? e.message : t('admin.actionFailed'))
  } finally {
    busy.value = false
  }
}

async function unsuspend(r: AbuseReport) {
  try {
    await api(`/api/admin/sites/${r.site_id}/unsuspend`, { method: 'POST' })
    message.success(t('admin.siteUnsuspended'))
    await load()
  } catch (e) {
    message.error(e instanceof ApiError ? e.message : t('admin.actionFailed'))
  }
}

async function reopen(r: AbuseReport) {
  try {
    await api(`/api/admin/abuse/${r.id}`, { method: 'PATCH', body: { status: 'new', note: r.note }, schema: abuseReportResponseSchema })
    await load()
  } catch (e) {
    message.error(e instanceof ApiError ? e.message : t('admin.actionFailed'))
  }
}
</script>

<template>
  <div class="queue">
    <div class="toolbar">
      <n-radio-group v-model:value="status" name="abuse-status" size="small">
        <n-radio-button value="new">{{ t('admin.abuse.status.new') }}</n-radio-button>
        <n-radio-button value="resolved">{{ t('admin.abuse.status.resolved') }}</n-radio-button>
        <n-radio-button value="rejected">{{ t('admin.abuse.status.rejected') }}</n-radio-button>
        <n-radio-button value="">{{ t('admin.filter.all') }}</n-radio-button>
      </n-radio-group>
      <span class="note">{{ t('admin.abuse.publicForm') }} <router-link to="/abuse" target="_blank">/abuse</router-link></span>
    </div>
    <n-alert v-if="loadError" type="error" :show-icon="false">{{ loadError }}</n-alert>
    <p v-if="!reports.length && !loading" class="glass card note">{{ t('admin.abuse.empty') }}</p>

    <article v-for="r in reports" :key="r.id" class="glass card report" data-testid="abuse-report">
      <header class="top">
        <strong>№{{ r.id }}</strong>
        <status-chip tone="amber">{{ category(r.category) }}</status-chip>
        <status-chip v-if="r.status === 'new'" tone="cyan">{{ t('admin.abuse.status.new') }}</status-chip>
        <status-chip v-else-if="r.status === 'resolved'" tone="emerald">{{ t('admin.abuse.status.resolved') }}</status-chip>
        <status-chip v-else tone="slate">{{ t('admin.abuse.status.rejected') }}</status-chip>
        <span class="note">{{ formatDateTime(r.created_at, locale) }}</span>
      </header>
      <!-- Адрес прислал посторонний: показываем текстом, без ссылки — открывать его стоит осознанно (копированием). -->
      <code class="url">{{ r.url }}</code>
      <p class="msg">{{ r.message }}</p>
      <p class="note">
        {{ r.email ? t('admin.abuse.from', { email: r.email }) : t('admin.abuse.anonymous') }} · IP {{ r.ip || '—' }}
      </p>
      <p v-if="r.site_id" class="note">
        {{ t('admin.abuse.site') }} <strong>{{ r.site_host }}</strong>
        <template v-if="r.owner_id">
          · {{ t('admin.abuse.owner') }}
          <router-link :to="{ name: 'admin-user', params: { uid: r.owner_id } }">{{ r.owner_name }}</router-link>
        </template>
        <status-chip v-if="r.site_suspended_at" tone="rose" class="inline">{{ t('admin.suspended') }}</status-chip>
      </p>
      <p v-else class="note">{{ t('admin.abuse.notOurs') }}</p>
      <p v-if="r.note" class="note">{{ t('admin.abuse.note', { note: r.note }) }}</p>

      <n-space :size="8">
        <template v-if="r.site_id">
          <n-button v-if="!r.site_suspended_at" size="small" class="tint-rose" @click="ask('suspend', r)">{{ t('admin.suspendSite') }}</n-button>
          <n-button v-else size="small" @click="unsuspend(r)">{{ t('admin.unsuspendSite') }}</n-button>
        </template>
        <template v-if="r.status === 'new'">
          <n-button size="small" class="tint-emerald" @click="ask('resolved', r)">{{ t('admin.abuse.resolve') }}</n-button>
          <n-button size="small" @click="ask('rejected', r)">{{ t('admin.abuse.reject') }}</n-button>
        </template>
        <n-button v-else size="small" quaternary @click="reopen(r)">{{ t('admin.abuse.reopen') }}</n-button>
      </n-space>
    </article>
    <n-button v-if="reports.length < total" :loading="loading" @click="load(true)">{{ t('admin.more', { n: total - reports.length }) }}</n-button>

    <n-modal v-model:show="dlg.open" preset="card" :title="dlgTitle" style="max-width: 520px">
      <p class="note">{{ dlg.kind === 'suspend' ? t('admin.abuse.suspendHint') : t('admin.abuse.noteHint') }}</p>
      <n-input v-model:value="dlg.text" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" :maxlength="dlg.kind === 'suspend' ? 500 : 1000" class="dlg-input" />
      <n-space justify="end">
        <n-button @click="dlg.open = false">{{ t('common.cancel') }}</n-button>
        <n-button type="primary" :loading="busy" :disabled="dlg.kind === 'suspend' && !dlg.text.trim()" @click="confirm">{{ t('admin.confirm') }}</n-button>
      </n-space>
    </n-modal>
  </div>
</template>

<style scoped>
.queue {
  display: grid;
  gap: 14px;
  margin-top: 12px;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.note {
  margin: 0;
  color: var(--text-dim);
  font-size: 13.5px;
}

.card {
  padding: 18px 20px;
}

.report {
  display: grid;
  gap: 8px;
}

.top {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.url {
  justify-self: start;
  overflow-wrap: anywhere;
}

.msg {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.inline {
  margin-left: 6px;
}

.dlg-input {
  margin: 10px 0 14px;
}
</style>
