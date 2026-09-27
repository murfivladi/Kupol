<script setup lang="ts">
// Загрузка и удаление аватара. Сервер сам обрезает картинку до квадрата и уменьшает; исходный файл не хранится.
import { CloudUploadOutline, TrashOutline } from '@vicons/ionicons5'
import { NButton, NIcon, useMessage } from 'naive-ui'
import { ref } from 'vue'
import { api, ApiError } from '@/api/client'
import { meSchema } from '@/api/schemas'
import { useI18n } from '@/i18n'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
const message = useMessage()
const busy = ref(false)

async function run(req: Promise<{ user: import('@/api/schemas').User }>, ok: string) {
  busy.value = true
  try {
    auth.setUser((await req).user)
    message.success(ok)
  } catch (e) {
    message.error(e instanceof ApiError ? e.message : t('avatar.failed'))
  } finally {
    busy.value = false
  }
}

function onPick(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > 5 * 1024 * 1024) {
    message.error(t('avatar.tooBig'))
    return
  }
  const body = new FormData()
  body.append('file', file)
  void run(api('/api/me/avatar', { method: 'PUT', body, schema: meSchema.pick({ user: true }) }), t('avatar.saved'))
}

function remove() {
  void run(api('/api/me/avatar', { method: 'DELETE', schema: meSchema.pick({ user: true }) }), t('avatar.removed'))
}
</script>

<template>
  <div class="avatar-controls">
    <n-button size="small" :loading="busy" tag="label">
      <template #icon><n-icon :component="CloudUploadOutline" /></template>
      {{ auth.user?.avatar_url ? t('avatar.change') : t('avatar.upload') }}
      <input type="file" accept="image/png,image/jpeg,image/gif" class="file" data-testid="avatar-input" @change="onPick">
    </n-button>
    <n-button v-if="auth.user?.avatar_url" size="small" quaternary :disabled="busy" @click="remove">
      <template #icon><n-icon :component="TrashOutline" /></template>
      {{ t('avatar.remove') }}
    </n-button>
  </div>
</template>

<style scoped>
.avatar-controls {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.file {
  display: none;
}
</style>
