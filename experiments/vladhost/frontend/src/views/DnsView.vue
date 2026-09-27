<script setup lang="ts">
import { CheckmarkCircleOutline, CopyOutline, RefreshOutline, WarningOutline } from '@vicons/ionicons5'
import { NAlert, NButton, NForm, NFormItem, NIcon, NInput, NInputNumber, NPopconfirm, NSelect, NSpace, useMessage } from 'naive-ui'
import { computed, onMounted, reactive, ref, type InputHTMLAttributes } from 'vue'
import { api, ApiError } from '@/api/client'
import {
  dnsDelegationSchema,
  dnsDomainForm,
  dnsOverviewSchema,
  dnsRecordForm,
  dnsRecordResponseSchema,
  dnsZoneResponseSchema,
  fieldErrors,
  type DnsDelegation,
  type DnsRecord,
  type DnsZone,
} from '@/api/schemas'
import FormModal from '@/components/FormModal.vue'
import PlusButton from '@/components/PlusButton.vue'
import { resolveMessage, useI18n } from '@/i18n'

const { t } = useI18n()
const message = useMessage()
const zoneInputProps = computed(() => ({ 'aria-label': t('dns.domain'), spellcheck: false, 'data-testid': 'dns-domain-input' }) as InputHTMLAttributes)

type Overview = ReturnType<typeof dnsOverviewSchema.parse>
const overview = ref<Overview | null>(null)
const loadError = ref('')
const info = computed(() => overview.value?.info)
const zones = computed<DnsZone[]>(() => overview.value?.zones ?? [])
const eligible = computed(() => info.value?.eligible_domains ?? [])
const typeOptions = computed(() => (info.value?.types ?? []).map((v) => ({ label: v, value: v })))
const ttlOptions = computed(() => (info.value?.ttls ?? []).map((n) => ({ label: t('dns.ttlSeconds', { n }), value: n })))

const errText = (e: unknown, fallback: string) => (e instanceof ApiError ? e.message : fallback)

async function load() {
  try {
    overview.value = await api('/api/dns', { schema: dnsOverviewSchema })
    loadError.value = ''
  } catch (e) {
    loadError.value = errText(e, t('dns.loadFailed'))
  }
}
onMounted(load)

// --- зоны ---
const showZone = ref(false)
const zoneForm = reactive({ domain: '' })
const zoneErrors = ref<Record<string, string>>({})
const creating = ref(false)

function openZone() {
  zoneForm.domain = ''
  zoneErrors.value = {}
  showZone.value = true
}

async function addZone() {
  const parsed = dnsDomainForm.safeParse({ domain: zoneForm.domain })
  zoneErrors.value = parsed.success ? {} : fieldErrors(parsed.error)
  if (!parsed.success) return
  creating.value = true
  try {
    const r = await api('/api/dns/zones', { method: 'POST', body: parsed.data, schema: dnsZoneResponseSchema })
    showZone.value = false
    if (r.zone.verified) message.success(t('dns.zoneCreated'))
    else message.info(t('dns.notServed'))
    await load()
  } catch (e) {
    if (e instanceof ApiError && e.field) zoneErrors.value = { [e.field]: e.message }
    else message.error(errText(e, t('dns.zoneFailed')))
  } finally {
    creating.value = false
  }
}

async function removeZone(z: DnsZone) {
  try {
    await api(`/api/dns/zones/${z.id}`, { method: 'DELETE' })
    message.success(t('dns.zoneRemoved'))
    await load()
  } catch (e) {
    message.error(errText(e, t('dns.removeFailed')))
  }
}

// --- подтверждение владения доменом ---
const verifying = reactive<Record<number, boolean>>({})

async function verifyZone(z: DnsZone) {
  verifying[z.id] = true
  try {
    await api(`/api/dns/zones/${z.id}/verify`, { method: 'POST', schema: dnsZoneResponseSchema })
    message.success(t('dns.verified'))
    await load()
  } catch (e) {
    message.error(errText(e, t('dns.verifyFailed')))
  } finally {
    verifying[z.id] = false
  }
}

// --- делегирование ---
const delegations = reactive<Record<number, DnsDelegation>>({})
const checking = reactive<Record<number, boolean>>({})

async function checkDelegation(z: DnsZone) {
  checking[z.id] = true
  try {
    delegations[z.id] = (await api(`/api/dns/zones/${z.id}/delegation`, { schema: dnsDelegationSchema })).delegation
  } catch (e) {
    message.error(errText(e, t('dns.delegationFailed')))
  } finally {
    checking[z.id] = false
  }
}

// Ключи перечислены явно: сторож i18n ищет их в коде как строки.
const stateLabel = (s: DnsDelegation['state']) =>
  s === 'ok' ? t('dns.state.ok') : s === 'partial' ? t('dns.state.partial') : s === 'mixed' ? t('dns.state.mixed') : s === 'none' ? t('dns.state.none') : t('dns.state.unknown')
const hintFor = (type: string) =>
  type === 'A'
    ? t('dns.hint_a')
    : type === 'AAAA'
      ? t('dns.hint_aaaa')
      : type === 'CNAME'
        ? t('dns.hint_cname')
        : type === 'MX'
          ? t('dns.hint_mx')
          : type === 'SRV'
            ? t('dns.hint_srv')
            : type === 'CAA'
              ? t('dns.hint_caa')
              : t('dns.hint_txt')

// --- записи ---
interface Form {
  id: number
  name: string
  type: string
  value: string
  priority: number | null
  ttl: number | null
}
const forms = reactive<Record<number, Form>>({})
const recordDlg = ref<DnsZone | null>(null) // зона, для которой открыто окно записи
const dlgOpen = computed({ get: () => recordDlg.value !== null, set: (v: boolean) => { if (!v) recordDlg.value = null } })
const recordErrors = reactive<Record<number, Record<string, string>>>({})
const saving = reactive<Record<number, boolean>>({})

const formOf = (z: DnsZone) => (forms[z.id] ??= { id: 0, name: '', type: 'A', value: '', priority: 10, ttl: 300 })
const usesPriority = (type: string) => type === 'MX' || type === 'SRV'

async function saveRecord(z: DnsZone) {
  const f = formOf(z)
  const parsed = dnsRecordForm.safeParse({ name: f.name, type: f.type, value: f.value, priority: usesPriority(f.type) ? (f.priority ?? Number.NaN) : 0, ttl: f.ttl ?? Number.NaN })
  recordErrors[z.id] = parsed.success ? {} : fieldErrors(parsed.error)
  if (!parsed.success) return
  saving[z.id] = true
  try {
    const url = f.id ? `/api/dns/records/${f.id}` : `/api/dns/zones/${z.id}/records`
    await api(url, { method: f.id ? 'PUT' : 'POST', body: parsed.data, schema: dnsRecordResponseSchema })
    message.success(t('dns.recordSaved'))
    Object.assign(f, { id: 0, name: '', value: '', priority: 10 })
    recordDlg.value = null
    await load()
  } catch (e) {
    if (e instanceof ApiError && e.field) recordErrors[z.id] = { [e.field]: e.message }
    else message.error(errText(e, t('dns.recordFailed')))
  } finally {
    saving[z.id] = false
  }
}

function editRecord(z: DnsZone, r: DnsRecord) {
  Object.assign(formOf(z), { id: r.id, name: r.name === '@' ? '' : r.name, type: r.type, value: r.value, priority: r.priority, ttl: r.ttl })
  recordErrors[z.id] = {}
  recordDlg.value = z
}

function newRecord(z: DnsZone) {
  Object.assign(formOf(z), { id: 0, name: '', type: 'A', value: '', priority: 10, ttl: 300 })
  recordErrors[z.id] = {}
  recordDlg.value = z
}

async function removeRecord(r: DnsRecord) {
  try {
    await api(`/api/dns/records/${r.id}`, { method: 'DELETE' })
    message.success(t('dns.recordRemoved'))
    await load()
  } catch (e) {
    message.error(errText(e, t('dns.removeFailed')))
  }
}

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    message.success(t('common.copied'))
  } catch {
    message.error(t('common.copyFailed'))
  }
}
</script>

<template>
  <div class="page">
    <header class="head rise">
      <h1>{{ t('dns.title') }}</h1>
      <p class="note">{{ t('dns.hint') }}</p>
    </header>

    <n-alert v-if="loadError" type="error" :show-icon="false">{{ loadError }}</n-alert>

    <template v-if="info">
      <section class="glass card rise" style="--i: 1">
        <h2>{{ t('dns.nsTitle') }}</h2>
        <ul class="ns" data-testid="dns-ns">
          <li v-for="n in info.ns" :key="n">
            <code>{{ n }}</code>
            <n-button size="tiny" quaternary :aria-label="t('common.copy')" @click="copyText(n)"><n-icon :component="CopyOutline" /></n-button>
          </li>
        </ul>
        <p class="note">{{ t('dns.nsHint') }}</p>
      </section>

      <section class="glass card rise" style="--i: 2">
        <div class="row">
          <div>
            <h2>{{ t('dns.zones') }}</h2>
            <p class="note">{{ t('dns.zonesLimit', { used: zones.length, max: info.max_zones }) }}</p>
          </div>
          <span class="grow" />
          <plus-button :label="t('dns.addZone')" :disabled="zones.length >= info.max_zones" data-testid="dns-zone-add" @click="openZone" />
        </div>
        <p v-if="!zones.length" class="note">{{ t('dns.noZones') }}</p>
      </section>

      <section v-for="(z, i) in zones" :key="z.id" class="glass card rise" :style="`--i: ${i + 3}`" :data-testid="`dns-zone-${z.domain}`">
        <div class="row">
          <h2>{{ z.domain }}</h2>
          <span v-if="!z.verified" class="badge warn" :data-testid="`dns-pending-${z.domain}`">{{ t('dns.pending') }}</span>
          <span v-if="delegations[z.id]" class="note">{{ t('dns.delegation') }}:</span>
          <span v-if="delegations[z.id]" :class="['badge', delegations[z.id]!.state === 'ok' ? 'ok' : delegations[z.id]!.state === 'partial' ? 'warn' : 'mismatch']" :data-testid="`dns-state-${z.domain}`">
            <n-icon :component="delegations[z.id]!.state === 'ok' ? CheckmarkCircleOutline : WarningOutline" />
            {{ stateLabel(delegations[z.id]!.state) }}
          </span>
          <span class="grow" />
          <n-button v-if="z.verified" size="small" class="tint-cyan" :loading="checking[z.id]" :data-testid="`dns-check-${z.domain}`" @click="checkDelegation(z)">
            <template #icon><n-icon :component="RefreshOutline" /></template>
            {{ t('dns.checkDelegation') }}
          </n-button>
          <n-popconfirm @positive-click="removeZone(z)">
            <template #trigger>
              <n-button size="small" class="tint-rose">{{ t('dns.removeZone') }}</n-button>
            </template>
            {{ t('dns.removeZoneConfirm', { name: z.domain }) }}
          </n-popconfirm>
        </div>
        <div v-if="!z.verified" class="block verify" :data-testid="`dns-verify-${z.domain}`">
          <h3>{{ t('dns.verifyTitle') }}</h3>
          <p class="note">{{ t('dns.verifyHint') }}</p>
          <ul class="recs">
            <li class="rec">
              <span class="cell note">{{ t('dns.verifyName') }}</span>
              <code class="cell val">{{ z.verify_name }}</code>
              <n-button size="tiny" quaternary :aria-label="t('common.copy')" @click="copyText(z.verify_name)"><n-icon :component="CopyOutline" /></n-button>
            </li>
            <li class="rec">
              <span class="cell note">{{ t('dns.verifyValue') }}</span>
              <code class="cell val">{{ z.verify_value }}</code>
              <n-button size="tiny" quaternary :aria-label="t('common.copy')" @click="copyText(z.verify_value)"><n-icon :component="CopyOutline" /></n-button>
            </li>
          </ul>
          <n-space>
            <n-button type="primary" :loading="verifying[z.id]" :data-testid="`dns-verify-btn-${z.domain}`" @click="verifyZone(z)">
              <template #icon><n-icon :component="RefreshOutline" /></template>
              {{ t('dns.verify') }}
            </n-button>
          </n-space>
          <p class="note">{{ t('dns.notServed') }}</p>
        </div>
        <template v-if="delegations[z.id]">
          <p class="note">{{ delegations[z.id]!.found.length ? t('dns.found', { value: delegations[z.id]!.found.join(', ') }) : t('dns.foundNone') }}</p>
          <p v-if="delegations[z.id]!.state !== 'ok'" class="note">{{ t('dns.expected', { value: delegations[z.id]!.expected.join(', ') }) }}</p>
        </template>

        <div class="block">
          <div class="row">
            <div>
              <h3>{{ t('dns.records') }}</h3>
              <p class="note">{{ t('dns.recordsLimit', { used: z.records.length, max: info.max_records }) }}</p>
            </div>
            <span class="grow" />
            <plus-button size="small" :label="t('dns.addRecord')" :disabled="z.records.length >= info.max_records" :data-testid="`dns-record-add-${z.domain}`" @click="newRecord(z)" />
          </div>
          <p v-if="!z.records.length" class="note">{{ t('dns.noRecords') }}</p>
          <ul v-else class="recs">
            <li v-for="r in z.records" :key="r.id" class="rec" :data-testid="`dns-record-${r.name}-${r.type}`">
              <span class="cell name">{{ r.name === '@' ? t('dns.apex') : r.name }}</span>
              <code class="cell type">{{ r.type }}</code>
              <code class="cell val">{{ usesPriority(r.type) ? `${r.priority} ` : '' }}{{ r.value }}</code>
              <span class="cell note">{{ t('dns.ttlSeconds', { n: r.ttl }) }}</span>
              <span v-if="r.managed === 'mail'" class="badge ok">{{ t('dns.managedMail') }}</span>
              <span class="grow" />
              <n-button size="tiny" class="tint-violet" @click="editRecord(z, r)">{{ t('dns.edit') }}</n-button>
              <n-popconfirm @positive-click="removeRecord(r)">
                <template #trigger>
                  <n-button size="tiny" class="tint-rose">{{ t('dns.remove') }}</n-button>
                </template>
                {{ t('dns.removeRecordConfirm', { type: r.type, name: r.name === '@' ? t('dns.apex') : r.name }) }}
              </n-popconfirm>
            </li>
          </ul>
        </div>
      </section>
    </template>

    <form-modal v-model:show="dlgOpen" :title="recordDlg && formOf(recordDlg).id ? t('dns.editRecordTitle') : t('dns.addRecordTitle')" :width="640">
      <template v-if="recordDlg">
        <n-form class="form" @submit.prevent="saveRecord(recordDlg)">
          <n-space :size="12" align="start">
            <n-form-item :label="t('dns.name')" :validation-status="recordErrors[recordDlg.id]?.name ? 'error' : undefined" :feedback="recordErrors[recordDlg.id]?.name ? resolveMessage(recordErrors[recordDlg.id]!.name!) : undefined">
              <n-input v-model:value="formOf(recordDlg).name" :placeholder="t('dns.namePlaceholder')" autocomplete="off" :input-props="{ 'aria-label': t('dns.name') }" />
            </n-form-item>
            <n-form-item :label="t('dns.type')" :validation-status="recordErrors[recordDlg.id]?.type ? 'error' : undefined" :feedback="recordErrors[recordDlg.id]?.type ? resolveMessage(recordErrors[recordDlg.id]!.type!) : undefined">
              <n-select v-model:value="formOf(recordDlg).type" :options="typeOptions" style="width: 110px" :aria-label="t('dns.type')" :data-testid="`dns-type-${recordDlg.domain}`" />
            </n-form-item>
            <n-form-item v-if="usesPriority(formOf(recordDlg).type)" :label="t('dns.priority')" :validation-status="recordErrors[recordDlg.id]?.priority ? 'error' : undefined" :feedback="recordErrors[recordDlg.id]?.priority ? resolveMessage(recordErrors[recordDlg.id]!.priority!) : undefined">
              <n-input-number v-model:value="formOf(recordDlg).priority" :min="0" :max="65535" style="width: 110px" :input-props="{ 'aria-label': t('dns.priority') }" />
            </n-form-item>
            <n-form-item :label="t('dns.ttl')" :validation-status="recordErrors[recordDlg.id]?.ttl ? 'error' : undefined" :feedback="recordErrors[recordDlg.id]?.ttl ? resolveMessage(recordErrors[recordDlg.id]!.ttl!) : undefined">
              <n-select v-model:value="formOf(recordDlg).ttl" :options="ttlOptions" style="width: 110px" :aria-label="t('dns.ttl')" />
            </n-form-item>
          </n-space>
          <n-form-item :label="t('dns.value')" :validation-status="recordErrors[recordDlg.id]?.value ? 'error' : undefined" :feedback="recordErrors[recordDlg.id]?.value ? resolveMessage(recordErrors[recordDlg.id]!.value!) : hintFor(formOf(recordDlg).type)">
            <n-input v-model:value="formOf(recordDlg).value" autocomplete="off" :input-props="{ 'aria-label': t('dns.value'), spellcheck: false }" @update:value="recordErrors[recordDlg.id] = {}" />
          </n-form-item>
          <n-space :size="10">
            <n-button type="primary" attr-type="submit" :loading="saving[recordDlg.id]" :data-testid="`dns-save-${recordDlg.domain}`">
              {{ formOf(recordDlg).id ? t('dns.saveRecord') : t('dns.addRecord') }}
            </n-button>
            <n-button @click="recordDlg = null">{{ t('common.cancel') }}</n-button>
          </n-space>
        </n-form>
      </template>
    </form-modal>

    <form-modal v-model:show="showZone" :title="t('dns.addZoneTitle')">
      <n-form class="form" @submit.prevent="addZone">
        <n-form-item :label="t('dns.domain')" :validation-status="zoneErrors.domain ? 'error' : undefined" :feedback="zoneErrors.domain ? resolveMessage(zoneErrors.domain) : t('dns.domainHint')">
          <n-input v-model:value="zoneForm.domain" :placeholder="t('dns.domainPlaceholder')" autocomplete="off" :input-props="zoneInputProps" @update:value="zoneErrors.domain = ''" />
        </n-form-item>
        <div v-if="eligible.length" class="chips">
          <span class="note">{{ t('dns.fromSites') }}</span>
          <n-button v-for="d in eligible" :key="d" size="tiny" class="tint-cyan" @click="((zoneForm.domain = d), (zoneErrors.domain = ''))">{{ d }}</n-button>
        </div>
        <n-space :size="10" class="actions">
          <n-button type="primary" attr-type="submit" :loading="creating" data-testid="dns-zone-submit">{{ t('dns.addZone') }}</n-button>
          <n-button @click="showZone = false">{{ t('common.cancel') }}</n-button>
        </n-space>
      </n-form>
    </form-modal>
  </div>
</template>

<style scoped>
.page {
  display: grid;
  gap: 18px;
  max-width: 980px;
}

.head h1 {
  font-size: clamp(26px, 3vw, 34px);
  font-weight: 800;
}

h2 {
  font-size: 19px;
  font-weight: 700;
  word-break: break-all;
}

h3 {
  font-size: 15px;
  font-weight: 700;
}

.note {
  color: var(--text-dim);
  font-size: 13.5px;
  word-break: break-word;
}

.card {
  padding: 18px 22px 20px;
  display: grid;
  gap: 12px;
}

.block {
  display: grid;
  gap: 10px;
  padding-top: 14px;
  border-top: 1px solid var(--border);
}

.row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.grow {
  flex: 1;
}

.ns {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.ns li {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border-radius: 10px;
  border: 1px solid var(--border);
}

.badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  background: rgba(148, 163, 184, 0.18);
}

.badge.ok {
  background: rgba(52, 211, 153, 0.18);
  color: var(--emerald-text);
}

.badge.warn {
  background: rgba(251, 191, 36, 0.18);
  color: var(--amber-text);
}

.badge.mismatch {
  background: rgba(251, 113, 133, 0.18);
  color: var(--rose-text);
}

.recs {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 6px;
}

.rec {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
  padding: 6px 0;
  border-bottom: 1px solid var(--border);
  font-size: 13px;
}

.cell.name {
  min-width: 90px;
  font-weight: 600;
  word-break: break-all;
}

.cell.type {
  min-width: 48px;
}

.cell.val {
  word-break: break-all;
  font-size: 12px;
  flex: 1 1 260px;
}

.form {
  max-width: 760px;
}

.chips {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.actions {
  margin-top: 6px;
}
</style>
