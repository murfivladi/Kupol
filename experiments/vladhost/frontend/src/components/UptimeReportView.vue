<script setup lang="ts">
// Сводка мониторинга: аптайм за сутки/неделю/месяц, полоска по дням, среднее время ответа и список сбоев.
// Общая для вкладки «Мониторинг» и открытой статус-страницы.
import { computed } from 'vue'
import type { UptimeReport } from '@/api/schemas'
import DayBars, { type Bar } from '@/components/DayBars.vue'
import UptimeStrip from '@/components/UptimeStrip.vue'
import { formatDateTime, useI18n } from '@/i18n'

const props = defineProps<{ report: UptimeReport }>()
const { t, locale } = useI18n()

const fmtPct = computed(() => new Intl.NumberFormat(locale.value, { maximumFractionDigits: 2 }))
const pct = (v: number | null) => (v === null ? '—' : `${fmtPct.value.format(v)}%`)
const ms = (v: number) => t('uptime.ms', { n: Math.round(v) })

const tiles = computed(() => [
  { label: t('uptime.day'), value: pct(props.report.uptime.day) },
  { label: t('uptime.week'), value: pct(props.report.uptime.week) },
  { label: t('uptime.month'), value: pct(props.report.uptime.month) },
  { label: t('uptime.avgMs'), value: props.report.avg_ms ? ms(props.report.avg_ms) : '—' },
])

// Время ответа по дням (только удачные проверки); в подсказке ещё доля удачных.
const bars = computed<Bar[]>(() =>
  props.report.days.map((d) => ({
    day: d.at.slice(0, 10),
    value: d.avg_ms,
    rows: [
      { label: t('uptime.avgMs'), value: d.checks ? ms(d.avg_ms) : '—' },
      { label: t('uptime.checksOk'), value: d.checks ? `${d.ok} / ${d.checks}` : '—' },
    ],
  })),
)
const hasChecks = computed(() => props.report.days.some((d) => d.checks > 0))

function lasted(i: { started_at: string; ended_at: string | null }) {
  const m = Math.max(1, Math.round(((i.ended_at ? new Date(i.ended_at) : new Date()).getTime() - new Date(i.started_at).getTime()) / 60000))
  return m >= 60 ? t('uptime.durationH', { h: Math.floor(m / 60), m: m % 60 }) : t('uptime.durationM', { m })
}
const reason = (code: string) =>
  ({
    http: t('uptime.reason.http'),
    timeout: t('uptime.reason.timeout'),
    tls: t('uptime.reason.tls'),
    dns: t('uptime.reason.dns'),
    connect: t('uptime.reason.connect'),
  })[code] ?? t('uptime.reason.connect')
</script>

<template>
  <div class="report">
    <div class="tiles">
      <div v-for="x in tiles" :key="x.label" class="tile">
        <span class="v">{{ x.value }}</span>
        <span class="l">{{ x.label }}</span>
      </div>
    </div>

    <section>
      <h4>{{ t('uptime.byDay') }}</h4>
      <uptime-strip :days="report.days" />
    </section>

    <section v-if="hasChecks">
      <h4>{{ t('uptime.responseTitle') }}</h4>
      <day-bars :bars="bars" :format="ms" :label="t('uptime.responseTitle')" />
    </section>

    <section>
      <h4>{{ t('uptime.incidents') }}</h4>
      <p v-if="!report.incidents.length" class="empty">{{ t('uptime.noIncidents') }}</p>
      <ul v-else class="incidents">
        <li v-for="i in report.incidents" :key="i.id">
          <span class="dot" :class="i.ended_at ? 'closed' : 'open'" aria-hidden="true" />
          <div>
            <div class="when">
              {{ formatDateTime(i.started_at, locale) }} ·
              <strong>{{ i.ended_at ? lasted(i) : t('uptime.ongoing', { d: lasted(i) }) }}</strong>
            </div>
            <div class="why">{{ reason(i.error) }}</div>
          </div>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.report {
  display: grid;
  gap: 22px;
}

.tiles {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
}

.tile {
  display: grid;
  gap: 2px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: 12px;
}

.tile .v {
  font-size: 24px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
}

.tile .l,
.empty,
.why {
  font-size: 13px;
  color: var(--text-dim);
}

h4 {
  margin: 0 0 10px;
  font-size: 15px;
}

.incidents {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
}

.incidents li {
  display: flex;
  gap: 12px;
  align-items: baseline;
  padding: 10px 0;
  border-top: 1px solid var(--border);
}

.incidents li:first-child {
  border-top: 0;
}

.dot {
  width: 9px;
  height: 9px;
  flex: none;
  border-radius: 50%;
}

.dot.open {
  background: var(--rose);
}

.dot.closed {
  background: var(--text-faint);
}

.when {
  font-size: 14px;
}
</style>
