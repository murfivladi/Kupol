<script setup lang="ts">
// Карточка пользователя для администратора: лимиты, блокировка, сброс 2FA, сайты (приостановка) и журнал действий.
import { ArrowBack } from '@vicons/ionicons5'
import { NAlert, NButton, NCheckbox, NIcon, NInput, NInputNumber, NModal, NPopconfirm, NSpace, useMessage } from 'naive-ui'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api, ApiError } from '@/api/client'
import { adminUserDetailSchema, adminUserResponseSchema, type AdminUserDetail, type Site } from '@/api/schemas'
import StatusChip from '@/components/StatusChip.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import { formatBytes, formatDateTime, useI18n } from '@/i18n'
import { useAuthStore } from '@/stores/auth'
import ActivityView from '@/views/ActivityView.vue'

const { t, locale } = useI18n()
const route = useRoute()
const auth = useAuthStore()
const message = useMessage()
const uid = Number(route.params.uid)

const d = ref<AdminUserDetail | null>(null)
const loadError = ref('')
const busy = ref('')
const self = computed(() => auth.user?.id === uid)

// Лимиты в форме: «общий» — поле пустое (null на сервере). Место — в мегабайтах.
const lim = reactive({ ownSites: false, sites: 1, ownQuota: false, quotaMB: 500 })

async function load() {
  try {
    d.value = await api(`/api/admin/users/${uid}`, { schema: adminUserDetailSchema })
    const u = d.value.user
    lim.ownSites = u.max_sites !== null
    lim.sites = u.max_sites ?? d.value.defaults.max_sites
    lim.ownQuota = u.disk_quota_bytes !== null
    lim.quotaMB = Math.round((u.disk_quota_bytes ?? d.value.defaults.disk_quota_bytes) / 2 ** 20)
    loadError.value = ''
  } catch (e) {
    loadError.value = e instanceof ApiError ? e.message : t('admin.loadFailed')
  }
}
onMounted(load)

// run выполняет действие и перечитывает карточку; true — успешно (окно действия можно закрыть).
async function run(key: string, req: () => Promise<unknown>, ok: string): Promise<boolean> {
  busy.value = key
  try {
    await req()
    message.success(ok)
    await load()
    return true
  } catch (e) {
    message.error(e instanceof ApiError ? e.message : t('admin.actionFailed'))
    return false
  } finally {
    busy.value = ''
  }
}

function saveLimits() {
  const body = { max_sites: lim.ownSites ? lim.sites : null, disk_quota_bytes: lim.ownQuota ? Math.round(lim.quotaMB * 2 ** 20) : null }
  void run('limits', () => api(`/api/admin/users/${uid}/limits`, { method: 'PUT', body, schema: adminUserResponseSchema }), t('admin.limitsSaved'))
}

const blockDlg = reactive({ open: false, reason: '' })
async function block() {
  if (await run('block', () => api(`/api/admin/users/${uid}/block`, { method: 'POST', body: { reason: blockDlg.reason }, schema: adminUserResponseSchema }), t('admin.userBlocked'))) {
    blockDlg.open = false
    blockDlg.reason = ''
  }
}
const unblock = () => run('block', () => api(`/api/admin/users/${uid}/unblock`, { method: 'POST', schema: adminUserResponseSchema }), t('admin.userUnblocked'))
const reset2fa = () => run('2fa', () => api(`/api/admin/users/${uid}/reset-2fa`, { method: 'POST', schema: adminUserResponseSchema }), t('admin.twoFactorReset'))

const siteDlg = reactive<{ open: boolean; site: Site | null; reason: string }>({ open: false, site: null, reason: '' })
async function suspend() {
  const s = siteDlg.site
  if (s && (await run(`site-${s.id}`, () => api(`/api/admin/sites/${s.id}/suspend`, { method: 'POST', body: { reason: siteDlg.reason } }), t('admin.siteSuspended')))) {
    siteDlg.open = false
  }
}
const unsuspend = (s: Site) => run(`site-${s.id}`, () => api(`/api/admin/sites/${s.id}/unsuspend`, { method: 'POST' }), t('admin.siteUnsuspended'))
</script>

<template>
  <div class="page">
    <router-link :to="{ name: 'admin' }" class="back plain"><n-icon :component="ArrowBack" /> {{ t('admin.allUsers') }}</router-link>
    <n-alert v-if="loadError" type="error" :show-icon="false">{{ loadError }}</n-alert>

    <template v-if="d">
      <section class="glass card profile">
        <user-avatar :name="d.user.username" :src="d.user.avatar_url" :size="64" />
        <div class="who">
          <h1>{{ d.user.username }}</h1>
          <span class="note">{{ d.user.email }}</span>
          <span class="chips">
            <status-chip v-if="d.user.role === 'admin'" tone="violet">{{ t('settings.roleAdmin') }}</status-chip>
            <status-chip v-if="d.user.blocked_at" tone="rose">{{ t('admin.blocked') }}</status-chip>
            <status-chip :tone="d.user.email_verified_at ? 'emerald' : 'amber'">
              {{ d.user.email_verified_at ? t('settings.notifications.verified') : t('settings.notifications.unverified') }}
            </status-chip>
            <status-chip v-if="d.user.two_factor_enabled_at" tone="emerald">2FA</status-chip>
          </span>
          <span class="note">
            {{ t('admin.registered', { date: formatDateTime(d.user.created_at, locale) }) }}
            <template v-if="d.user.last_seen_at"> · {{ t('admin.lastSeen', { date: formatDateTime(d.user.last_seen_at, locale) }) }}</template>
          </span>
        </div>
      </section>

      <n-alert v-if="d.user.blocked_at" type="error" :show-icon="false">
        {{ t('admin.blockedSince', { date: formatDateTime(d.user.blocked_at, locale), reason: d.user.blocked_reason }) }}
      </n-alert>

      <section class="glass card">
        <h3>{{ t('admin.limits') }}</h3>
        <p class="note">{{ t('admin.limitsHint', { sites: d.defaults.max_sites, quota: formatBytes(d.defaults.disk_quota_bytes, locale) }) }}</p>
        <div class="limits">
          <div class="lim">
            <n-checkbox v-model:checked="lim.ownSites">{{ t('admin.ownSites') }}</n-checkbox>
            <n-input-number v-model:value="lim.sites" :min="0" :max="100" :disabled="!lim.ownSites" :input-props="{ 'aria-label': t('admin.ownSites') }" />
          </div>
          <div class="lim">
            <n-checkbox v-model:checked="lim.ownQuota">{{ t('admin.ownQuota') }}</n-checkbox>
            <n-input-number v-model:value="lim.quotaMB" :min="0" :max="102400" :step="100" :disabled="!lim.ownQuota" :input-props="{ 'aria-label': t('admin.ownQuota') }">
              <template #suffix>{{ t('admin.mb') }}</template>
            </n-input-number>
          </div>
        </div>
        <p class="note">{{ t('admin.usage', { n: d.user.sites, size: formatBytes(d.user.disk_bytes, locale), max: d.limits.max_sites, quota: formatBytes(d.limits.disk_quota_bytes, locale) }) }}</p>
        <n-button type="primary" :loading="busy === 'limits'" data-testid="admin-save-limits" @click="saveLimits">{{ t('admin.saveLimits') }}</n-button>
      </section>

      <section v-if="!self && d.user.role !== 'admin'" class="glass card">
        <h3>{{ t('admin.access') }}</h3>
        <p class="note">{{ t('admin.blockHint') }}</p>
        <n-space :size="10">
          <n-button v-if="!d.user.blocked_at" class="tint-rose" data-testid="admin-block" @click="blockDlg.open = true">{{ t('admin.block') }}</n-button>
          <n-button v-else :loading="busy === 'block'" data-testid="admin-unblock" @click="unblock">{{ t('admin.unblock') }}</n-button>
          <n-popconfirm v-if="d.user.two_factor_enabled_at" @positive-click="reset2fa">
            <template #trigger>
              <n-button :loading="busy === '2fa'">{{ t('admin.reset2fa') }}</n-button>
            </template>
            {{ t('admin.reset2faConfirm') }}
          </n-popconfirm>
        </n-space>
      </section>

      <section class="glass card">
        <h3>{{ t('admin.sites') }}</h3>
        <p v-if="!d.sites.length" class="note">{{ t('admin.noSites') }}</p>
        <ul class="sites">
          <li v-for="s in d.sites" :key="s.id">
            <div class="site-info">
              <strong>{{ s.host }}</strong>
              <span class="note">{{ formatBytes(s.disk_bytes, locale) }}<template v-if="s.suspended_at"> · {{ s.suspended_reason }}</template></span>
            </div>
            <status-chip v-if="s.suspended_at" tone="rose">{{ t('admin.suspended') }}</status-chip>
            <n-button v-if="!s.suspended_at" size="small" class="tint-rose" @click="Object.assign(siteDlg, { open: true, site: s, reason: '' })">{{ t('admin.suspendSite') }}</n-button>
            <n-button v-else size="small" :loading="busy === `site-${s.id}`" @click="unsuspend(s)">{{ t('admin.unsuspendSite') }}</n-button>
          </li>
        </ul>
      </section>

      <section class="activity">
        <h3>{{ t('admin.activity') }}</h3>
        <activity-view :user-id="uid" />
      </section>
    </template>

    <n-modal v-model:show="blockDlg.open" preset="card" :title="t('admin.blockTitle', { name: d?.user.username ?? '' })" style="max-width: 520px">
      <p class="note">{{ t('admin.blockConsequences') }}</p>
      <n-input v-model:value="blockDlg.reason" type="textarea" :maxlength="500" :placeholder="t('admin.reasonPlaceholder')" class="dlg-input" />
      <n-space justify="end">
        <n-button @click="blockDlg.open = false">{{ t('common.cancel') }}</n-button>
        <n-button type="error" :loading="busy === 'block'" :disabled="!blockDlg.reason.trim()" data-testid="admin-block-confirm" @click="block">{{ t('admin.block') }}</n-button>
      </n-space>
    </n-modal>

    <n-modal v-model:show="siteDlg.open" preset="card" :title="t('admin.abuse.suspendTitle')" style="max-width: 520px">
      <p class="note">{{ t('admin.abuse.suspendHint') }}</p>
      <n-input v-model:value="siteDlg.reason" type="textarea" :maxlength="500" :placeholder="t('admin.reasonPlaceholder')" class="dlg-input" />
      <n-space justify="end">
        <n-button @click="siteDlg.open = false">{{ t('common.cancel') }}</n-button>
        <n-button type="error" :disabled="!siteDlg.reason.trim()" @click="suspend">{{ t('admin.suspendSite') }}</n-button>
      </n-space>
    </n-modal>
  </div>
</template>

<style scoped>
.page {
  display: grid;
  gap: 18px;
  max-width: 1000px;
}

.back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-dim);
}

.card {
  padding: 20px 24px;
}

h1 {
  font-size: 26px;
  font-weight: 800;
}

h3 {
  font-size: 17px;
  margin-bottom: 6px;
}

.note {
  margin: 0 0 10px;
  color: var(--text-dim);
  font-size: 13.5px;
}

.profile {
  display: flex;
  gap: 18px;
  align-items: center;
}

.who {
  display: grid;
  gap: 4px;
  min-width: 0;
  overflow-wrap: anywhere;
}

.who .note {
  margin: 0;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.limits {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 14px;
  margin-bottom: 12px;
}

.lim {
  display: grid;
  gap: 8px;
}

.sites {
  list-style: none;
  margin: 0;
  padding: 0;
}

.sites li {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-top: 1px solid var(--border);
}

.sites li:first-child {
  border-top: 0;
}

.site-info {
  flex: 1;
  display: grid;
  min-width: 0;
  overflow-wrap: anywhere;
}

.site-info .note {
  margin: 0;
}

.activity h3 {
  margin: 6px 0 12px;
}

.dlg-input {
  margin: 10px 0 14px;
}
</style>
