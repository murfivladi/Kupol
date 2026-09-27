<script setup lang="ts">
// Полоска внизу экрана: «нет подключения» и «доступна новая версия». Видна на всех страницах, в том числе без входа.
import { CloudOfflineOutline, RefreshOutline } from '@vicons/ionicons5'
import { NButton, NIcon } from 'naive-ui'
import { useI18n } from '@/i18n'
import { applyUpdate, pwa } from '@/lib/pwa'

const { t } = useI18n()
</script>

<template>
  <div class="pwa-bar" aria-live="polite">
    <div v-if="!pwa.online" class="note offline" role="status">
      <n-icon :component="CloudOfflineOutline" :size="18" />
      {{ t('pwa.offline') }}
    </div>
    <div v-else-if="pwa.updateReady" class="note" role="status">
      <n-icon :component="RefreshOutline" :size="18" />
      {{ t('pwa.updateReady') }}
      <n-button size="small" type="primary" @click="applyUpdate">{{ t('pwa.reload') }}</n-button>
    </div>
  </div>
</template>

<style scoped>
.pwa-bar {
  position: fixed;
  left: 50%;
  bottom: max(16px, env(safe-area-inset-bottom));
  transform: translateX(-50%);
  z-index: 3000;
  width: max-content;
  max-width: calc(100vw - 32px);
}

.note {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: 14px;
  background: var(--surface-solid);
  border: 1px solid var(--border-strong);
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.45);
  font-size: 14px;
}

.offline {
  color: var(--amber);
}
</style>
