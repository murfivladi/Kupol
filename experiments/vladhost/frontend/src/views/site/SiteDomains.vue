<script setup lang="ts">
import { NAlert, NButton, NForm, NFormItem, NInput, NModal, NSpace, useMessage } from 'naive-ui'
import { computed, ref } from 'vue'
import { api, ApiError } from '@/api/client'
import { domainForm, domainResponseSchema, fieldErrors, subdomainForm, type Domain, type Site } from '@/api/schemas'
import DomainRow from '@/components/DomainRow.vue'
import FormModal from '@/components/FormModal.vue'
import PlusButton from '@/components/PlusButton.vue'
import { resolveMessage, useI18n } from '@/i18n'
import { useSitesStore } from '@/stores/sites'

const props = defineProps<{ site: Site }>()
const { t } = useI18n()
const store = useSitesStore()
const message = useMessage()

const errText = (e: unknown, fallback: string) => (e instanceof ApiError ? e.message : fallback)
const reload = () => store.load(t('sites.loadFailed'))
const path = (suffix = '') => `/api/sites/${props.site.id}${suffix}`

const subs = computed(() => props.site.domains.filter((d) => d.kind === 'sub'))
const customs = computed(() => props.site.domains.filter((d) => d.kind !== 'sub'))
const serverIp = computed(() => store.domainConfig.server_ips.join(', '))
const subLimit = computed(() => store.domainConfig.per_site_sub)
const subAtLimit = computed(() => subs.value.length >= subLimit.value)

const busyId = ref<number | null>(null)

// --- поддомены сайта ---
const sub = ref({ label: '', dir: '' })
const subErrors = ref<Record<string, string>>({})
const addingSub = ref(false)
const showSub = ref(false)

function openSub() {
  sub.value = { label: '', dir: '' }
  subErrors.value = {}
  showSub.value = true
}
const subPreview = computed(() => `${sub.value.label.trim().toLowerCase() || t('subdomains.placeholder')}.${props.site.host}`)

async function addSub() {
  const parsed = subdomainForm.safeParse({ label: sub.value.label })
  subErrors.value = parsed.success ? {} : fieldErrors(parsed.error)
  if (!parsed.success) return
  addingSub.value = true
  try {
    await api(path('/subdomains'), { method: 'POST', body: { label: parsed.data.label, dir: sub.value.dir.trim() }, schema: domainResponseSchema })
    sub.value = { label: '', dir: '' }
    showSub.value = false
    message.success(t('subdomains.added'))
    await reload()
  } catch (e) {
    if (e instanceof ApiError && e.field) subErrors.value = { [e.field]: e.message }
    else message.error(errText(e, t('subdomains.addFailed')))
  } finally {
    addingSub.value = false
  }
}

// --- свои домены ---
const custom = ref({ host: '', dir: '' })
const customErrors = ref<Record<string, string>>({})
const addingCustom = ref(false)
const showCustom = ref(false)

function openCustom() {
  custom.value = { host: '', dir: '' }
  customErrors.value = {}
  showCustom.value = true
}

async function addCustom() {
  const parsed = domainForm.safeParse({ host: custom.value.host })
  customErrors.value = parsed.success ? {} : fieldErrors(parsed.error)
  if (!parsed.success) return
  addingCustom.value = true
  try {
    await api(path('/domains'), { method: 'POST', body: { host: parsed.data.host, dir: custom.value.dir.trim() }, schema: domainResponseSchema })
    custom.value = { host: '', dir: '' }
    showCustom.value = false
    message.success(t('sites.domains.added'))
    await reload()
  } catch (e) {
    if (e instanceof ApiError && e.field) customErrors.value = { [e.field]: e.message }
    else message.error(errText(e, t('sites.domains.addFailed')))
  } finally {
    addingCustom.value = false
  }
}

// --- действия над строкой ---
async function check(d: Domain) {
  busyId.value = d.id
  try {
    await api(path(`/domains/${d.id}/check`), { method: 'POST', schema: domainResponseSchema })
    await reload()
  } catch (e) {
    message.error(errText(e, t('sites.domains.checkFailed')))
  } finally {
    busyId.value = null
  }
}

async function remove(d: Domain) {
  busyId.value = d.id
  try {
    await api(path(`/domains/${d.id}`), { method: 'DELETE' })
    await reload()
  } catch (e) {
    message.error(errText(e, t('sites.domains.removeFailed')))
  } finally {
    busyId.value = null
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

// Папка имени меняется в отдельном окне.
const editing = ref<Domain | null>(null)
const editDir = ref('')
const editError = ref('')
const saving = ref(false)

function openEdit(d: Domain) {
  editing.value = d
  editDir.value = d.dir
  editError.value = ''
}

async function saveEdit() {
  const d = editing.value
  if (!d) return
  saving.value = true
  editError.value = ''
  try {
    await api(path(`/domains/${d.id}`), { method: 'PATCH', body: { dir: editDir.value.trim() }, schema: domainResponseSchema })
    editing.value = null
    message.success(t('subdomains.dirUpdated'))
    await reload()
  } catch (e) {
    editError.value = errText(e, t('subdomains.dirFailed'))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="page">
    <header class="head rise">
      <h1>{{ t('siteArea.domains') }}</h1>
    </header>

    <n-alert v-if="!store.domainConfig.available" type="info" :show-icon="false">{{ t('siteArea.domainsUnavailable') }}</n-alert>

    <template v-else>
      <section class="glass card rise" style="--i: 1">
        <div class="sec-head">
          <div>
            <h3>{{ t('subdomains.title') }}</h3>
            <p class="note">{{ t('subdomains.hint', { example: `docs.${site.host}` }) }}</p>
          </div>
          <plus-button :label="t('subdomains.add')" :disabled="subAtLimit" data-testid="subdomain-add" @click="openSub" />
        </div>

        <ul v-if="subs.length" class="dom-list" data-testid="subdomains">
          <domain-row
            v-for="d in subs"
            :key="d.id"
            :domain="d"
            :server-ips="store.domainConfig.server_ips"
            :busy="busyId === d.id"
            @check="check(d)"
            @remove="remove(d)"
            @edit-dir="openEdit(d)"
            @copy-ip="copyText"
          />
        </ul>
        <p v-else class="note">{{ t('subdomains.empty') }}</p>

        <n-alert v-if="subAtLimit" type="info" :show-icon="false">{{ t('subdomains.limit', { max: subLimit }) }}</n-alert>
      </section>

      <section class="glass card rise" style="--i: 2">
        <div class="sec-head">
          <div>
            <h3>{{ t('sites.domains.title') }}</h3>
            <p class="note">{{ t('sites.domains.hint', { ip: serverIp }) }}</p>
          </div>
          <plus-button :label="t('sites.domains.add')" data-testid="domain-add" @click="openCustom" />
        </div>

        <ul v-if="customs.length" class="dom-list" data-testid="custom-domains">
          <domain-row
            v-for="d in customs"
            :key="d.id"
            :domain="d"
            :server-ips="store.domainConfig.server_ips"
            :busy="busyId === d.id"
            @check="check(d)"
            @remove="remove(d)"
            @edit-dir="openEdit(d)"
            @copy-ip="copyText"
          />
        </ul>
        <p v-else class="note">{{ t('sites.domains.empty') }}</p>
      </section>
    </template>

    <form-modal v-model:show="showSub" :title="t('subdomains.add')">
      <n-form class="dom-form" @submit.prevent="addSub">
        <n-form-item
          :label="t('subdomains.label')"
          :validation-status="subErrors.label ? 'error' : undefined"
          :feedback="subErrors.label ? resolveMessage(subErrors.label) : t('subdomains.preview', { host: subPreview })"
        >
          <n-input
            v-model:value="sub.label"
            :placeholder="t('subdomains.placeholder')"
            autocomplete="off"
            :input-props="{ 'aria-label': t('subdomains.label') }"
            @update:value="subErrors.label = ''"
          />
        </n-form-item>
        <n-form-item
          :label="t('subdomains.dir')"
          :validation-status="subErrors.dir ? 'error' : undefined"
          :feedback="subErrors.dir ? resolveMessage(subErrors.dir) : t('subdomains.dirHint')"
        >
          <n-input
            v-model:value="sub.dir"
            :placeholder="t('subdomains.dirPlaceholder')"
            autocomplete="off"
            :input-props="{ 'aria-label': t('subdomains.dir') }"
            @update:value="subErrors.dir = ''"
          />
        </n-form-item>
        <n-space :size="10">
          <n-button type="primary" attr-type="submit" :loading="addingSub">
            {{ t('subdomains.add') }}
          </n-button>
          <n-button @click="showSub = false">{{ t('common.cancel') }}</n-button>
        </n-space>
      </n-form>
    </form-modal>

    <form-modal v-model:show="showCustom" :title="t('sites.domains.add')">
      <n-form class="dom-form" @submit.prevent="addCustom">
        <n-form-item
          :label="t('sites.domains.label')"
          :validation-status="customErrors.host ? 'error' : undefined"
          :feedback="customErrors.host ? resolveMessage(customErrors.host) : t('sites.domains.wwwHint')"
        >
          <n-input
            v-model:value="custom.host"
            :placeholder="t('sites.domains.placeholder')"
            autocomplete="off"
            :input-props="{ 'aria-label': t('sites.domains.label') }"
            @update:value="customErrors.host = ''"
          />
        </n-form-item>
        <n-form-item
          :label="t('subdomains.dir')"
          :validation-status="customErrors.dir ? 'error' : undefined"
          :feedback="customErrors.dir ? resolveMessage(customErrors.dir) : t('subdomains.dirHint')"
        >
          <n-input
            v-model:value="custom.dir"
            :placeholder="t('subdomains.dirPlaceholder')"
            autocomplete="off"
            :input-props="{ 'aria-label': `${t('subdomains.dir')} (${t('sites.domains.title')})` }"
            @update:value="customErrors.dir = ''"
          />
        </n-form-item>
        <n-space :size="10">
          <n-button type="primary" attr-type="submit" :loading="addingCustom">
            {{ t('sites.domains.add') }}
          </n-button>
          <n-button @click="showCustom = false">{{ t('common.cancel') }}</n-button>
        </n-space>
      </n-form>
    </form-modal>

    <n-modal :show="editing !== null" preset="card" :title="t('subdomains.editTitle', { host: editing?.host ?? '' })" style="max-width: 460px" @update:show="editing = null">
      <n-form @submit.prevent="saveEdit">
        <n-form-item
          :label="t('subdomains.dir')"
          :validation-status="editError ? 'error' : undefined"
          :feedback="editError ? resolveMessage(editError) : t('subdomains.dirHint')"
        >
          <n-input
            v-model:value="editDir"
            :placeholder="t('subdomains.dirPlaceholder')"
            autocomplete="off"
            :input-props="{ 'aria-label': t('subdomains.dir') }"
            @update:value="editError = ''"
          />
        </n-form-item>
        <n-button type="primary" attr-type="submit" :loading="saving">{{ t('subdomains.save') }}</n-button>
      </n-form>
    </n-modal>
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

.card {
  padding: 20px 24px;
}

.card h3 {
  font-size: 16px;
}

.note {
  font-size: 13.5px;
  color: var(--text-dim);
}

.dom-list {
  list-style: none;
  margin: 10px 0;
  padding: 0;
  display: grid;
  gap: 10px;
}

.dom-form {
  margin-top: 8px;
  max-width: 420px;
}
.sec-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
</style>
