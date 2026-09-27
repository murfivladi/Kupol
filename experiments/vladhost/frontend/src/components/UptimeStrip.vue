<script setup lang="ts">
// Полоска доступности по суткам: ячейка на день, цвет — состояние (цвета статусов, всегда с подписью в подсказке и легенде).
// Ячейки — кнопки: подсказку видно и с клавиатуры.
import { NTooltip } from 'naive-ui'
import { computed } from 'vue'
import type { UptimeBucket } from '@/api/schemas'
import { useI18n } from '@/i18n'

const props = defineProps<{ days: UptimeBucket[] }>()
const { t, locale } = useI18n()

type Level = 'good' | 'warn' | 'bad' | 'none'
// Пороги: сутки без сбоев (≥ 99,5%), с короткими сбоями, с серьёзными.
function level(b: UptimeBucket): Level {
  if (!b.checks) return 'none'
  const p = (b.ok / b.checks) * 100
  if (p >= 99.5) return 'good'
  if (p >= 95) return 'warn'
  return 'bad'
}

const fmtDay = computed(() => new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeZone: 'UTC' }))
const fmtPct = computed(() => new Intl.NumberFormat(locale.value, { maximumFractionDigits: 2 }))

const cells = computed(() =>
  props.days.map((b) => {
    const lv = level(b)
    const day = fmtDay.value.format(new Date(b.at))
    const text = lv === 'none' ? t('uptime.noData') : t('uptime.dayValue', { pct: fmtPct.value.format((b.ok / b.checks) * 100), n: b.checks })
    return { key: b.at, lv, day, text, label: levelLabel(lv) }
  }),
)
const levels: Level[] = ['good', 'warn', 'bad', 'none']
const levelLabel = (l: Level) =>
  ({ good: t('uptime.level.good'), warn: t('uptime.level.warn'), bad: t('uptime.level.bad'), none: t('uptime.level.none') })[l]
</script>

<template>
  <div class="strip-wrap">
    <div class="strip" role="list" :aria-label="t('uptime.stripLabel')">
      <n-tooltip v-for="c in cells" :key="c.key" placement="top">
        <template #trigger>
          <button type="button" class="cell" :class="c.lv" role="listitem" :aria-label="`${c.day}: ${c.label}, ${c.text}`" />
        </template>
        <div class="tip-day">{{ c.day }}</div>
        <div><strong>{{ c.label }}</strong> · {{ c.text }}</div>
      </n-tooltip>
    </div>
    <div class="axis" aria-hidden="true">
      <span>{{ t('uptime.daysAgo', { n: days.length }) }}</span>
      <span>{{ t('uptime.today') }}</span>
    </div>
    <ul class="legend">
      <li v-for="l in levels" :key="l"><i :class="l" />{{ levelLabel(l) }}</li>
    </ul>
  </div>
</template>

<style scoped>
.strip {
  display: flex;
  gap: 2px; /* зазор цвета поверхности между ячейками */
  height: 36px;
}

.cell {
  flex: 1;
  min-width: 0;
  padding: 0;
  border: 0;
  border-radius: 4px;
  cursor: default;
}

.cell:focus-visible {
  outline: 2px solid var(--text);
  outline-offset: 1px;
}

.good {
  background: var(--emerald);
}

.warn {
  background: var(--amber);
}

.bad {
  background: var(--rose);
}

.none {
  background: var(--surface-2);
}

.axis {
  display: flex;
  justify-content: space-between;
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-faint);
}

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
  font-size: 13px;
  color: var(--text-dim);
}

.legend li {
  display: flex;
  align-items: center;
  gap: 6px;
}

.legend i {
  width: 10px;
  height: 10px;
  border-radius: 3px;
}

.tip-day {
  font-size: 12px;
  opacity: 0.75;
}
</style>
