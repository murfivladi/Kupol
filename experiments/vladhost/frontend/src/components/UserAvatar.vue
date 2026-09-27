<script setup lang="ts">
import { computed } from 'vue'

// Круглый аватар: загруженная картинка или инициал. Цвет стабильно выбирается по имени, поэтому у человека он всегда один и тот же.
const props = withDefaults(defineProps<{ name: string; size?: number; src?: string }>(), { size: 36, src: '' })

const gradients = [
  'linear-gradient(135deg,#6366f1,#ec4899)',
  'linear-gradient(135deg,#06b6d4,#3b82f6)',
  'linear-gradient(135deg,#10b981,#22d3ee)',
  'linear-gradient(135deg,#f59e0b,#f43f5e)',
  'linear-gradient(135deg,#8b5cf6,#d946ef)',
]

const bg = computed(() => {
  let h = 0
  for (const ch of props.name) h = (h * 31 + ch.charCodeAt(0)) >>> 0
  return gradients[h % gradients.length]
})
const initial = computed(() => (props.name.trim()[0] ?? '?').toUpperCase())
</script>

<template>
  <img v-if="src" class="avatar" :src="src" alt="" :width="size" :height="size" :style="{ width: `${size}px`, height: `${size}px` }">
  <span v-else class="avatar" :style="{ width: `${size}px`, height: `${size}px`, fontSize: `${size * 0.42}px`, background: bg }">
    {{ initial }}
  </span>
</template>

<style scoped>
.avatar {
  display: inline-grid;
  object-fit: cover;
  place-items: center;
  flex: none;
  border-radius: 50%;
  color: #fff;
  font-weight: 800;
  box-shadow:
    0 6px 18px -6px rgba(139, 92, 246, 0.7),
    0 0 0 2px rgb(var(--ov) / 0.18) inset;
}
</style>
