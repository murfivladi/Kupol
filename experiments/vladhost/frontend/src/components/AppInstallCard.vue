<script setup lang="ts">
// Карточка «Приложение»: установить панель на телефон или компьютер как отдельное приложение (PWA).
import { DownloadOutline, PhonePortraitOutline } from '@vicons/ionicons5'
import { NButton, NIcon, useMessage } from 'naive-ui'
import { computed } from 'vue'
import StatusChip from '@/components/StatusChip.vue'
import { useI18n } from '@/i18n'
import { install, isIOS, pwa } from '@/lib/pwa'

const { t } = useI18n()
const message = useMessage()
const ios = computed(() => !pwa.installed && !pwa.canInstall && isIOS())

async function onInstall() {
  if (await install()) message.success(t('pwa.installed'))
}
</script>

<template>
  <section class="app-card glass rise" style="--i: 8">
    <n-icon class="icon" :size="30" :component="PhonePortraitOutline" />
    <div class="info">
      <h3>
        {{ t('pwa.title') }}
        <status-chip v-if="pwa.installed" tone="emerald">{{ t('pwa.installedChip') }}</status-chip>
      </h3>
      <p class="hint">{{ t('pwa.hint') }}</p>
      <p v-if="ios" class="hint">{{ t('pwa.iosHint') }}</p>
      <p v-else-if="!pwa.installed && !pwa.canInstall" class="hint">{{ t('pwa.browserHint') }}</p>
    </div>
    <n-button v-if="pwa.canInstall" type="primary" data-testid="pwa-install" @click="onInstall">
      <template #icon><n-icon :component="DownloadOutline" /></template>
      {{ t('pwa.install') }}
    </n-button>
  </section>
</template>

<style scoped>
.app-card {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  padding: 22px 26px;
}

.icon {
  color: var(--text-dim);
  flex: none;
}

.info {
  flex: 1;
  min-width: 220px;
}

h3 {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 17px;
  margin-bottom: 2px;
}

.hint {
  margin: 0;
  font-size: 13.5px;
  color: var(--text-dim);
}
</style>
