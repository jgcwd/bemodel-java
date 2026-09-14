<template>
  <div class="page">
    <!-- 顶部统计卡片 -->
    <el-card class="stats-card" v-loading="loading">
      <div class="stats-row">
        <div class="stat">
          <div class="stat-value" :class="stats.pending ? 'stat-hot' : ''">{{ stats.pending }}</div>
          <div class="stat-label">待处理缺口</div>
        </div>
        <div class="stat">
          <div class="stat-value">{{ stats.adopted }}</div>
          <div class="stat-label">已采纳（概念/术语）</div>
        </div>
        <div class="stat">
          <div class="stat-value">{{ stats.dismissed }}</div>
          <div class="stat-label">已忽略</div>
        </div>
        <div class="stat">
          <div class="stat-value">{{ stats.topCount }}</div>
          <div class="stat-label">最高热度（被问次数）</div>
        </div>
      </div>
      <div class="board-tip">
        用户提问、搜索与 AI 映射中本体未覆盖的说法会自动记录到这里；同一说法被问得越多热度越高，
        热度高的缺口优先长成本体概念或术语——这是「本体随使用生长」的增长回路。
        深度处置（采纳为新概念 / 挂为术语 / AI 归类预填）仍在
        <router-link to="/ontology">本体管理</router-link> 的扩展提案抽屉中完成。
      </div>
    </el-card>

    <!-- 工具栏 -->
    <div class="toolbar">
      <el-radio-group v-model="filter">
        <el-radio-button value="all">全部（{{ items.length }}）</el-radio-button>
        <el-radio-button value="pending">待处理（{{ pendingList.length }}）</el-radio-button>
        <el-radio-button value="adopted">已采纳（{{ adoptedList.length }}）</el-radio-button>
        <el-radio-button value="dismissed">已忽略（{{ dismissedList.length }}）</el-radio-button>
      </el-radio-group>
      <el-input
        v-model="keyword"
        placeholder="搜索缺口说法"
        clearable
        :prefix-icon="Search"
        style="width: 220px"
      />
      <span class="toolbar-tip">按热度降序：被问得最多的说法排最前</span>
    </div>

    <!-- 缺口列表 -->
    <div v-loading="loading" class="miss-list">
      <div v-for="m in shownList" :key="m.id" class="miss-card" :class="{ dismissed: m.dismissed === 1 }">
        <div class="miss-main">
          <div class="miss-title-row">
            <span class="miss-term">{{ m.term }}</span>
            <el-tag size="small" :type="m.kind === 'QUESTION' ? 'warning' : 'info'" effect="plain">
              {{ kindText(m.kind) }}
            </el-tag>
            <el-tag size="small" effect="plain">{{ sourceText(m.source) }}</el-tag>
            <el-tag v-if="m.count >= 3" size="small" type="danger" effect="dark">高热</el-tag>
            <span v-if="m.adoptedAs" class="miss-adopted">
              已采纳为{{ m.adoptedAs === 'CONCEPT' ? '概念' : '术语' }}
              <template v-if="m.adoptedConceptCode">（{{ m.adoptedConceptCode }}）</template>
              <el-tag v-if="m.revoked === 1" size="small" type="info" effect="plain">已撤销</el-tag>
            </span>
          </div>
          <div class="miss-meta">
            首次出现 {{ fmt(m.firstSeen) }} ｜ 最近出现 {{ fmt(m.lastSeen) }}
          </div>
          <!-- AI 归类建议 -->
          <div v-if="suggestions[m.id]" class="miss-suggestion">
            <div class="sug-title">
              <el-icon><MagicStick /></el-icon> AI 归类建议{{ suggestions[m.id].degraded ? '（LLM 暂不可用，仅回显原说法）' : '' }}
            </div>
            <div class="sug-body">
              <template v-if="!suggestions[m.id].degraded">
                <el-tag size="small" type="success" effect="plain">{{ suggestions[m.id].kind }}</el-tag>
                <b>{{ suggestions[m.id].name }}</b>
                <span v-if="suggestions[m.id].domainCode" class="sug-dim">域：{{ suggestions[m.id].domainCode }}</span>
              </template>
              <span>{{ suggestions[m.id].definition || '去本体管理中完成采纳' }}</span>
            </div>
          </div>
        </div>
        <div class="miss-side">
          <div class="miss-count" :class="{ hot: m.count >= 3 }">× {{ m.count }}</div>
          <div class="miss-count-label">出现次数</div>
        </div>
        <div v-if="!userStore.isViewer" class="miss-actions">
          <el-button size="small" type="primary" link @click="classify(m)">
            <el-icon><MagicStick /></el-icon>&nbsp;AI 归类
          </el-button>
          <el-button size="small" type="primary" link @click="goAdopt">去本体处置</el-button>
          <template v-if="m.dismissed === 1">
            <el-button size="small" link @click="undismiss(m)">恢复</el-button>
          </template>
          <template v-else>
            <el-button size="small" link type="danger" @click="dismiss(m)">忽略</el-button>
          </template>
        </div>
      </div>
      <el-empty v-if="!loading && !shownList.length" description="没有匹配的缺口记录" />
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, MagicStick } from '@element-plus/icons-vue'
import {
  listOntologyMisses,
  dismissMiss,
  undismissMiss,
  classifyMiss
} from '../../api/ontology'
import { useUserStore } from '../../store/user'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const items = ref([])
const filter = ref('all')
const keyword = ref('')
const suggestions = ref({}) // missId -> suggestion（AI 归类结果）

const KIND_TEXT = { CONCEPT: '新概念说法', ATTRIBUTE: '新属性说法', QUESTION: '未答问题' }
const SOURCE_TEXT = { SEARCH: '概念搜索', MAPPING_AI: 'AI 映射', CS_ASK: 'AI 客服', QA_ASK: '智能问数' }
const kindText = (k) => KIND_TEXT[k] || k
const sourceText = (s) => SOURCE_TEXT[s] || s
const fmt = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '-')

const stats = computed(() => {
  const pending = items.value.filter(isPending)
  return {
    pending: pending.length,
    adopted: items.value.filter((m) => m.adoptedAs && m.revoked === 0).length,
    dismissed: items.value.filter((m) => m.dismissed === 1).length,
    topCount: pending.reduce((max, m) => Math.max(max, m.count || 0), 0)
  }
})

// 待处理口径与后端 isPending 一致：未忽略 且（从未采纳 或 采纳已撤销回池）；dismissed 可能是 null（从未忽略）
const isPending = (m) =>
  m.dismissed !== 1 && (!m.adoptedAs || m.revoked === 1)
const pendingList = computed(() => items.value.filter(isPending))
const adoptedList = computed(() => items.value.filter((m) => m.adoptedAs && m.revoked === 0))
const dismissedList = computed(() => items.value.filter((m) => m.dismissed === 1))

const shownList = computed(() => {
  let list =
    filter.value === 'pending'
      ? pendingList.value
      : filter.value === 'adopted'
        ? adoptedList.value
        : filter.value === 'dismissed'
          ? dismissedList.value
          : items.value
  const kw = keyword.value.trim().toLowerCase()
  if (kw) {
    list = list.filter(
      (m) => m.term?.toLowerCase().includes(kw) || m.adoptedConceptCode?.toLowerCase().includes(kw)
    )
  }
  // 热度降序，同热度按最近出现降序
  return [...list].sort(
    (a, b) => (b.count || 0) - (a.count || 0) || String(b.lastSeen || '').localeCompare(String(a.lastSeen || ''))
  )
})

const load = async () => {
  loading.value = true
  try {
    const data = await listOntologyMisses()
    items.value = data.items || []
  } finally {
    loading.value = false
  }
}

const classify = async (m) => {
  const res = await classifyMiss(m.id)
  suggestions.value = { ...suggestions.value, [m.id]: res.suggestion }
  ElMessage.success('已生成归类建议，请到本体管理确认采纳')
}

const dismiss = async (m) => {
  const { value } = await ElMessageBox.prompt('忽略后该说法不再出现在待处理中，可随时恢复。', '忽略缺口', {
    inputValue: m.term,
    inputPlaceholder: '忽略理由（可选）'
  })
  await dismissMiss(m.id, value || '')
  ElMessage.success('已忽略')
  load()
}

const undismiss = async (m) => {
  await undismissMiss(m.id)
  ElMessage.success('已恢复为待处理')
  load()
}

const goAdopt = () => router.push('/ontology')

onMounted(load)
</script>

<style scoped>
.stats-card {
  margin-bottom: 12px;
}

.stats-row {
  display: flex;
  gap: 40px;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
}

.stat-value.stat-hot {
  color: var(--el-color-warning);
}

.stat-label {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 2px;
}

.board-tip {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--border-color);
  font-size: 12px;
  line-height: 1.8;
  color: var(--text-secondary);
}

.board-tip a {
  color: var(--primary);
  font-weight: 600;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.toolbar-tip {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-muted);
}

.miss-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.miss-card {
  display: flex;
  align-items: center;
  gap: 16px;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: var(--radius, 8px);
  padding: 14px 18px;
  transition: var(--transition);
}

.miss-card:hover {
  border-color: var(--primary);
  box-shadow: var(--shadow-sm);
}

.miss-card.dismissed {
  opacity: 0.55;
}

.miss-main {
  flex: 1;
  min-width: 0;
}

.miss-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.miss-term {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.miss-adopted {
  font-size: 12px;
  color: var(--el-color-success);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.miss-meta {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
}

.miss-suggestion {
  margin-top: 10px;
  padding: 10px 12px;
  background: var(--el-color-success-light-9, #f0f9eb);
  border-radius: 6px;
  font-size: 12px;
}

.sug-title {
  display: flex;
  align-items: center;
  gap: 4px;
  font-weight: 600;
  color: var(--el-color-success);
  margin-bottom: 4px;
}

.sug-body {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  color: var(--text-secondary);
}

.sug-dim {
  color: var(--text-muted);
}

.miss-side {
  text-align: center;
  flex-shrink: 0;
  min-width: 64px;
}

.miss-count {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-secondary);
}

.miss-count.hot {
  color: var(--el-color-danger);
}

.miss-count-label {
  font-size: 11px;
  color: var(--text-muted);
}

.miss-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  flex-shrink: 0;
}

.miss-actions .el-button {
  margin-left: 0;
}
</style>
