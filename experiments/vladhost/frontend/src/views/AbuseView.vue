<script setup lang="ts">
// Открытая форма жалобы на сайт (без входа): фишинг, вирусы, спам, нарушение прав. Жалобы разбирает администратор.
import { NAlert, NButton, NForm, NFormItem, NInput, NSelect } from 'naive-ui'
import { computed, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api, ApiError } from '@/api/client'
import { abuseCategories, type AbuseCategory } from '@/api/schemas'
import BrandLogo from '@/components/BrandLogo.vue'
import LangSwitch from '@/components/LangSwitch.vue'
import { useI18n } from '@/i18n'

const { t } = useI18n()
const route = useRoute()
const form = reactive<{ url: string; category: AbuseCategory | null; message: string; email: string }>({
  url: typeof route.query.url === 'string' ? route.query.url : '',
  category: null,
  message: '',
  email: '',
})
const errors = ref<Record<string, string>>({})
const busy = ref(false)
const sent = ref(0)
const failure = ref('')

const categoryLabel = (c: AbuseCategory) =>
  ({
    phishing: t('abuse.categories.phishing'),
    malware: t('abuse.categories.malware'),
    spam: t('abuse.categories.spam'),
    copyright: t('abuse.categories.copyright'),
    illegal: t('abuse.categories.illegal'),
    other: t('abuse.categories.other'),
  })[c]
const options = computed(() => abuseCategories.map((c) => ({ label: categoryLabel(c), value: c })))

async function submit() {
  errors.value = {}
  failure.value = ''
  if (!form.url.trim()) errors.value.url = t('abuse.urlRequired')
  if (!form.category) errors.value.category = t('abuse.categoryRequired')
  if (form.message.trim().length < 10) errors.value.message = t('abuse.messageRequired')
  if (Object.keys(errors.value).length) return
  busy.value = true
  try {
    const r = await api('/api/abuse', { method: 'POST', body: { ...form }, auth: false })
    sent.value = (r as { id: number } | undefined)?.id ?? 1
  } catch (e) {
    if (e instanceof ApiError && e.field) errors.value = { [e.field]: e.message }
    else failure.value = e instanceof ApiError ? e.message : t('abuse.failed')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="abuse-page">
    <header class="top">
      <router-link to="/" class="plain"><brand-logo /></router-link>
      <lang-switch />
    </header>
    <section class="glass card">
      <h1>{{ t('abuse.title') }}</h1>
      <template v-if="sent">
        <n-alert type="success" :show-icon="false" data-testid="abuse-sent">{{ t('abuse.sent', { id: sent }) }}</n-alert>
      </template>
      <template v-else>
        <p class="note">{{ t('abuse.hint') }}</p>
        <n-alert v-if="failure" type="error" :show-icon="false" class="gap">{{ failure }}</n-alert>
        <n-form @submit.prevent="submit">
          <n-form-item :label="t('abuse.url')" :validation-status="errors.url ? 'error' : undefined" :feedback="errors.url">
            <n-input v-model:value="form.url" placeholder="https://site.user.vladinc.ru/page" :input-props="{ 'aria-label': t('abuse.url') }" />
          </n-form-item>
          <n-form-item :label="t('abuse.category')" :validation-status="errors.category ? 'error' : undefined" :feedback="errors.category">
            <n-select v-model:value="form.category" :options="options" :placeholder="t('abuse.categoryPlaceholder')" data-testid="abuse-category" />
          </n-form-item>
          <n-form-item :label="t('abuse.message')" :validation-status="errors.message ? 'error' : undefined" :feedback="errors.message">
            <n-input v-model:value="form.message" type="textarea" :autosize="{ minRows: 4, maxRows: 10 }" :maxlength="5000" :placeholder="t('abuse.messagePlaceholder')" :input-props="{ 'aria-label': t('abuse.message') }" />
          </n-form-item>
          <n-form-item :label="t('abuse.email')" :validation-status="errors.email ? 'error' : undefined" :feedback="errors.email || t('abuse.emailHint')">
            <n-input v-model:value="form.email" type="text" autocomplete="email" placeholder="you@example.com" :input-props="{ 'aria-label': t('abuse.email') }" />
          </n-form-item>
          <n-button type="primary" attr-type="submit" :loading="busy" data-testid="abuse-submit">{{ t('abuse.submit') }}</n-button>
        </n-form>
      </template>
    </section>
  </div>
</template>

<style scoped>
.abuse-page {
  max-width: 640px;
  margin: 0 auto;
  padding: 28px 16px 48px;
  display: grid;
  gap: 20px;
}

.top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card {
  padding: 26px 28px;
}

h1 {
  font-size: 26px;
  font-weight: 800;
  margin-bottom: 8px;
}

.note {
  margin: 0 0 18px;
  color: var(--text-dim);
}

.gap {
  margin-bottom: 14px;
}
</style>
