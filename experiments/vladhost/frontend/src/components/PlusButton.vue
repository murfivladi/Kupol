<script setup lang="ts">
// Круглая кнопка «+» для добавления элемента (открывает модальное окно с формой). Подпись — во всплывающей подсказке и для скринридеров.
import { AddOutline } from '@vicons/ionicons5'
import { NButton, NIcon, NTooltip } from 'naive-ui'

defineOptions({ inheritAttrs: false })
defineProps<{ label: string; disabled?: boolean; loading?: boolean; size?: 'tiny' | 'small' | 'medium' | 'large' }>()
</script>

<template>
  <n-tooltip>
    <template #trigger>
      <n-button circle type="primary" :size="size ?? 'medium'" :disabled="disabled" :loading="loading" :aria-label="label" v-bind="$attrs">
        <template #icon><n-icon :component="AddOutline" /></template>
      </n-button>
    </template>
    {{ label }}
  </n-tooltip>
</template>
