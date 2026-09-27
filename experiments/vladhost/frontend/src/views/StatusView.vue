<script setup lang="ts">
// Открытая статус-страница сайта: /status/адрес. Видна без входа, если владелец её включил.
import { NSpin } from 'naive-ui'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api, ApiError } from '@/api/client'
import { publicStatusSchema, type PublicStatus } from '@/api/schemas'
import LangSwitch from '@/components/LangSwitch.vue'
import StatusChip from '@/components/StatusChip.vue'
import UptimeReportView from '@/components/UptimeReportView.vue'
import { formatDateTime, useI18n } from '@/i18n'

const route = useRoute()
const { t, locale } = useI18n()
const status = ref<PublicStatus | null>(null)
const error = ref('')
const loading = ref(true)
const host = String(route.params.host ?? '')

let timer: ReturnType<typeof setTimeout> | undefined
async function load() {
  try {
    status.value = (await api(`/api/status/${encodeURIComponent(host)}`, { schema: publicStatusSchema })).status
    error.value = ''
  } catch (e) {
    error.value = e instanceof ApiError && e.status === 404 ? t('uptime.statusNotFound') : t('uptime.loadFailed')
  } finally {
    loading.value = false
    timer = setTimeout(load, 60_000)
  }
}
onMounted(() => {
  document.title = t('uptime.statusTitle', { host })
  void load()
})
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <div class="status-page">
    <header class="top">
      <div>
        <h1>{{ host }}</h1>
        <p class="sub">{{ t('uptime.statusSub') }}</p>
      </div>
      <lang-switch />
    </header>

    <div v-if="loading" class="center"><n-spin /></div>
    <p v-else-if="error" class="glass card empty">{{ error }}</p>
    <template v-else-if="status">
      <section class="glass card now" :class="status.state">
        <status-chip v-if="status.state === 'up'" tone="emerald">{{ t('uptime.state.up') }}</status-chip>
        <status-chip v-else-if="status.state === 'down'" tone="rose" pulse>{{ t('uptime.state.down') }}</status-chip>
        <status-chip v-else tone="slate">{{ t('uptime.state.unknown') }}</status-chip>
        <span v-if="status.state_since" class="sub">{{ t('uptime.since', { date: formatDateTime(status.state_since, locale) }) }}</span>
      </section>
      <section class="glass card">
        <uptime-report-view :report="status" />
      </section>
    </template>
    <footer class="foot">{{ t('uptime.poweredBy') }}</footer>
  </div>
</template>

<style scoped>
.status-page {
  max-width: 880px;
  margin: 0 auto;
  padding: 32px 16px 48px;
  display: grid;
  gap: 18px;
}

.top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

h1 {
  font-size: clamp(24px, 4vw, 34px);
  font-weight: 800;
  overflow-wrap: anywhere;
}

.sub {
  margin: 4px 0 0;
  color: var(--text-dim);
  font-size: 14px;
}

.card {
  padding: 20px 24px;
}

.now {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.now .sub {
  margin: 0;
}

.center {
  display: flex;
  justify-content: center;
  padding: 40px;
}

.empty {
  color: var(--text-dim);
}

.foot {
  text-align: center;
  font-size: 12px;
  color: var(--text-faint);
}
</style>
