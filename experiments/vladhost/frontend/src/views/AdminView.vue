<script setup lang="ts">
// Раздел администратора: пользователи (поиск, статус) и очередь жалоб на сайты.
import { SearchOutline } from '@vicons/ionicons5'
import { NAlert, NButton, NIcon, NInput, NRadioButton, NRadioGroup, NTabPane, NTabs } from 'naive-ui'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiError } from '@/api/client'
import { adminUsersSchema, type AdminUserRow } from '@/api/schemas'
import AbuseQueue from '@/components/AbuseQueue.vue'
import StatusChip from '@/components/StatusChip.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import { formatBytes, formatDateTime, useI18n } from '@/i18n'

const { t, locale } = useI18n()
const route = useRoute()
const router = useRouter()

const tab = computed({
  get: () => (route.query.tab === 'abuse' ? 'abuse' : 'users'),
  set: (v: string) => void router.replace({ query: { ...route.query, tab: v === 'users' ? undefined : v } }),
})

// --- пользователи ---
const users = ref<AdminUserRow[]>([])
const total = ref(0)
const query = ref('')
const status = ref<'' | 'blocked' | 'admin'>('')
const loading = ref(false)
const loadError = ref('')

async function load(more = false) {
  loading.value = true
  try {
    const p = new URLSearchParams()
    if (query.value.trim()) p.set('q', query.value.trim())
    if (status.value) p.set('status', status.value)
    if (more) p.set('offset', String(users.value.length))
    const r = await api(`/api/admin/users?${p}`, { schema: adminUsersSchema })
    users.value = more ? [...users.value, ...r.users] : r.users
    total.value = r.total
    loadError.value = ''
  } catch (e) {
    loadError.value = e instanceof ApiError ? e.message : t('admin.loadFailed')
  } finally {
    loading.value = false
  }
}
onMounted(() => load())
let timer: ReturnType<typeof setTimeout> | undefined
watch(query, () => {
  clearTimeout(timer)
  timer = setTimeout(() => void load(), 300)
})
watch(status, () => void load())

function open(u: AdminUserRow) {
  void router.push({ name: 'admin-user', params: { uid: u.id } })
}
</script>

<template>
  <div class="page">
    <header class="head rise">
      <h1>{{ t('admin.title') }}</h1>
      <p class="note">{{ t('admin.hint') }}</p>
    </header>

    <n-tabs v-model:value="tab" type="line" animated>
      <n-tab-pane name="users" :tab="t('admin.users')">
        <div class="toolbar">
          <n-input v-model:value="query" clearable :placeholder="t('admin.search')" :input-props="{ 'aria-label': t('admin.search') }" class="search">
            <template #prefix><n-icon :component="SearchOutline" /></template>
          </n-input>
          <n-radio-group v-model:value="status" name="status" size="small">
            <n-radio-button value="">{{ t('admin.filter.all') }}</n-radio-button>
            <n-radio-button value="blocked">{{ t('admin.filter.blocked') }}</n-radio-button>
            <n-radio-button value="admin">{{ t('admin.filter.admins') }}</n-radio-button>
          </n-radio-group>
        </div>
        <n-alert v-if="loadError" type="error" :show-icon="false">{{ loadError }}</n-alert>

        <section class="glass card">
          <p v-if="!users.length && !loading" class="note">{{ t('admin.noUsers') }}</p>
          <ul v-else class="users" data-testid="admin-users">
            <li v-for="u in users" :key="u.id">
              <button type="button" class="row" @click="open(u)">
                <user-avatar :name="u.username" :src="u.avatar_url" :size="38" />
                <span class="who">
                  <strong>{{ u.username }}</strong>
                  <span class="note">{{ u.email }}</span>
                </span>
                <span class="stats note">
                  {{ t('admin.sitesN', { n: u.sites }) }} · {{ formatBytes(u.disk_bytes, locale) }}
                  <template v-if="u.last_seen_at"> · {{ t('admin.lastSeen', { date: formatDateTime(u.last_seen_at, locale) }) }}</template>
                </span>
                <span class="chips">
                  <status-chip v-if="u.role === 'admin'" tone="violet">{{ t('settings.roleAdmin') }}</status-chip>
                  <status-chip v-if="u.blocked_at" tone="rose">{{ t('admin.blocked') }}</status-chip>
                  <status-chip v-if="u.two_factor_enabled_at" tone="emerald">2FA</status-chip>
                </span>
              </button>
            </li>
          </ul>
          <n-button v-if="users.length < total" :loading="loading" class="more" @click="load(true)">{{ t('admin.more', { n: total - users.length }) }}</n-button>
        </section>
      </n-tab-pane>
      <n-tab-pane name="abuse" :tab="t('admin.abuse.title')">
        <abuse-queue />
      </n-tab-pane>
    </n-tabs>
  </div>
</template>

<style scoped>
.page {
  display: grid;
  gap: 18px;
  max-width: 1100px;
}

.head h1 {
  font-size: clamp(26px, 3vw, 34px);
  font-weight: 800;
}

.note {
  margin: 0;
  color: var(--text-dim);
  font-size: 13.5px;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  margin: 12px 0;
}

.search {
  max-width: 360px;
}

.card {
  padding: 10px 14px;
}

.users {
  list-style: none;
  margin: 0;
  padding: 0;
}

.users li + li {
  border-top: 1px solid var(--border);
}

.row {
  width: 100%;
  display: grid;
  grid-template-columns: auto minmax(0, 1.2fr) minmax(0, 1.5fr) 190px;
  gap: 14px;
  align-items: center;
  padding: 12px 8px;
  border: 0;
  border-radius: 12px;
  background: transparent;
  color: var(--text);
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.row:hover {
  background: rgb(var(--ov) / 0.05);
}

.who {
  display: grid;
  min-width: 0;
  overflow-wrap: anywhere;
}

.chips {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.more {
  margin: 10px 8px;
}

@media (max-width: 760px) {
  .row {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .stats,
  .chips {
    grid-column: 2;
    justify-content: flex-start;
  }
}
</style>
