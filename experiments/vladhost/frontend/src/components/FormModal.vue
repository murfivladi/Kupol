<script setup lang="ts">
// Модальное окно с формой добавления элемента: форма внутри слота, кнопки отправки — тоже в нём.
import { NModal } from 'naive-ui'

const props = defineProps<{ show: boolean; title: string; width?: number }>()
defineEmits<{ 'update:show': [value: boolean] }>()
const style = { width: `min(${props.width ?? 520}px, calc(100vw - 32px))` }
</script>

<template>
  <n-modal :show="show" preset="card" :title="title" :style="style" @update:show="$emit('update:show', $event)">
    <slot />
  </n-modal>
</template>
