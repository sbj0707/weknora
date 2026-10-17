<template>
  <main class="evaluation-page">
    <header class="evaluation-header" style="--wails-draggable: drag">
      <h2>
        <t-icon name="chart-radar" size="24px" aria-hidden="true" />
        {{ t('qualityEvaluation.title') }}
      </h2>
      <p>{{ t('qualityEvaluation.description') }}</p>
    </header>

    <nav class="evaluation-nav" :aria-label="t('qualityEvaluation.title')">
      <RouterLink v-for="section in sections" :key="section.key"
        :to="`/platform/quality-evaluation/${section.key}`" class="evaluation-nav-item"
        active-class="evaluation-nav-item--active">
        <t-icon :name="section.icon" size="18px" aria-hidden="true" />
        {{ t(section.title) }}
      </RouterLink>
    </nav>

    <section class="evaluation-content" :aria-labelledby="`evaluation-${selectedSection.key}`">
      <div class="evaluation-section-header">
        <h3 :id="`evaluation-${selectedSection.key}`">{{ t(selectedSection.title) }}</h3>
        <p>{{ t(selectedSection.description) }}</p>
      </div>
      <EvaluationPlanPanel v-if="selectedSection.key === 'plans'" />
      <EmptyState v-else :icon="selectedSection.icon" :title="t('qualityEvaluation.comingSoon')"
        :description="t('qualityEvaluation.comingSoonDescription')" />
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import EmptyState from '@/components/EmptyState.vue'
import EvaluationPlanPanel from './EvaluationPlanPanel.vue'

const route = useRoute()
const { t } = useI18n()
const sections = [
  {
    key: 'plans',
    icon: 'task',
    title: 'qualityEvaluation.plans.title',
    description: 'qualityEvaluation.plans.description',
  },
  {
    key: 'qa-generation',
    icon: 'chat-add',
    title: 'qualityEvaluation.qaGeneration.title',
    description: 'qualityEvaluation.qaGeneration.description',
  },
  {
    key: 'qa-evaluation',
    icon: 'check-circle',
    title: 'qualityEvaluation.qaEvaluation.title',
    description: 'qualityEvaluation.qaEvaluation.description',
  },
  {
    key: 'comparison',
    icon: 'chart-bar',
    title: 'qualityEvaluation.comparison.title',
    description: 'qualityEvaluation.comparison.description',
  },
] as const
const selectedSection = computed(() => sections.find((section) => section.key === route.params.section) ?? sections[0])
</script>

<style scoped lang="less">
.evaluation-page {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  padding: 20px 28px 0;
  color: var(--td-text-color-primary);
}

.evaluation-header {
  flex-shrink: 0;
  margin-bottom: 12px;

  h2 {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 0;
    font-size: var(--app-text-4xl);
    font-weight: 600;
    line-height: 32px;
  }
}

.evaluation-header p,
.evaluation-section-header p {
  margin: 4px 0 0;
  color: var(--td-text-color-secondary);
  font-size: var(--app-text-base);
  line-height: 22px;
}

.evaluation-nav {
  display: flex;
  flex-shrink: 0;
  gap: 32px;
  overflow-x: auto;
  border-bottom: 1px solid var(--td-component-stroke);
}

.evaluation-nav-item {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  gap: 8px;
  min-height: 48px;
  box-sizing: border-box;
  border-bottom: 2px solid transparent;
  color: var(--td-text-color-secondary);
  font-size: var(--app-text-lg);
  font-weight: 400;
  text-decoration: none;

  &:hover,
  &--active {
    color: var(--td-brand-color);
  }

  &--active {
    border-bottom-color: var(--td-brand-color);
  }

  &:focus-visible {
    outline: 2px solid var(--td-brand-color);
    outline-offset: -2px;
    border-radius: var(--app-radius-xs);
  }
}

.evaluation-content {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow-y: auto;
  padding: var(--app-space-5) 0 var(--app-space-8);
}

.evaluation-section-header h3 {
  margin: 0;
  font-size: var(--app-text-xl);
  font-weight: 500;
  line-height: 28px;
}

@media (max-width: 768px) {
  .evaluation-page {
    padding: 16px 16px 0;
  }

  .evaluation-nav {
    gap: 20px;
  }
}
</style>
