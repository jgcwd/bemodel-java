<template>
  <el-row :gutter="16">
    <!-- 左栏：版本发布 -->
    <el-col :span="10">
      <el-card>
        <template #header><span>版本发布</span></template>
        <div class="current-version">
          <div>
            <div class="cv-label">当前版本</div>
            <div class="cv-value" :class="{ none: currentVersion === '未发布' }">
              {{ currentVersion || '-' }}
            </div>
          </div>
          <el-button type="primary" :loading="checking" @click="openPublish">发布新版本</el-button>
        </div>
        <el-table :data="releases" v-loading="loadingReleases" style="margin-top: 16px" size="small">
          <el-table-column label="版本号" width="90">
            <template #default="{ row }">
              <el-tag type="success"><b>{{ row.versionTag }}</b></el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="changeSummary" label="变更说明" min-width="180" show-overflow-tooltip />
          <el-table-column prop="elementCount" label="元素数" width="80" />
          <el-table-column prop="releasedBy" label="发布人" width="90" />
          <el-table-column prop="createdAt" label="发布时间" width="160" />
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openSnapshot(row)">快照</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </el-col>

    <!-- 右栏：LLM 调用审计 -->
    <el-col :span="14">
      <el-card>
        <template #header><span>LLM 调用审计</span></template>
        <div class="llm-stats" v-loading="loadingStats">
          <div class="llm-stat">
            <div class="llm-stat-value">{{ stats.total ?? '-' }}</div>
            <div class="llm-stat-label">总调用次数</div>
          </div>
          <div class="llm-stat">
            <div class="llm-stat-value">{{ stats.successRate ?? '-' }}%</div>
            <div class="llm-stat-label">成功率</div>
          </div>
          <div class="llm-stat">
            <div class="llm-stat-value">{{ stats.avgLatencyMs ?? '-' }}ms</div>
            <div class="llm-stat-label">平均耗时</div>
          </div>
          <div class="llm-stat">
            <div class="llm-stat-value">{{ stats.currentOntologyVersion || '-' }}</div>
            <div class="llm-stat-label">当前本体版本</div>
          </div>
        </div>
        <div class="by-type" v-if="stats.byType && Object.keys(stats.byType).length">
          <el-tag
            v-for="(n, t) in stats.byType"
            :key="t"
            size="small"
            effect="plain"
            :type="callTypeTag(t)"
            style="margin-right: 8px"
          >{{ callTypeText(t) }} × {{ n }}</el-tag>
        </div>
        <el-table
          ref="logTableRef"
          :data="logs"
          v-loading="loadingLogs"
          size="small"
          style="margin-top: 12px"
          @row-click="toggleLogRow"
        >
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="prompt-digest">
                <div class="pd-title">Prompt 摘要</div>
                <pre class="pd-text">{{ row.promptDigest }}</pre>
                <div v-if="row.errMsg" class="pd-err">错误：{{ row.errMsg }}</div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="callTypeTag(row.callType)">{{ callTypeText(row.callType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="model" label="模型" width="150" />
          <el-table-column label="本体版本" width="90">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ row.ontologyVersion }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="90">
            <template #default="{ row }">{{ row.latencyMs }}ms</template>
          </el-table-column>
          <el-table-column label="结果" width="70">
            <template #default="{ row }">
              <el-icon v-if="row.success === 1" color="#67c23a"><CircleCheck /></el-icon>
              <el-icon v-else color="#f56c6c"><CircleClose /></el-icon>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="时间" width="160" />
        </el-table>
        <el-pagination
          v-if="logTotal > 0"
          class="log-pager"
          small
          layout="total, prev, pager, next"
          :current-page="logPage"
          :page-size="pageSize"
          :total="logTotal"
          @current-change="loadLogs"
        />
      </el-card>
    </el-col>
  </el-row>

  <!-- 发布新版本 -->
  <el-dialog v-model="publishVisible" title="发布新版本" width="480px">
    <el-alert
      type="info"
      :closable="false"
      title="将当前全部已发布元素（概念/属性/关系/术语/指标/规则/动作）固化为不可变快照，版本号自动递增"
      style="margin-bottom: 12px"
    />
    <el-form :model="publishForm" label-width="90px">
      <el-form-item label="变更说明" required>
        <el-input v-model="publishForm.changeSummary" type="textarea" :rows="3" />
      </el-form-item>
      <el-form-item label="发布人" required>
        <el-input v-model="publishForm.releasedBy" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="publishVisible = false">取消</el-button>
      <el-button type="primary" :loading="publishing" @click="doPublish">发布</el-button>
    </template>
  </el-dialog>

  <!-- 发布前本体自检缺陷 -->
  <el-dialog v-model="checkVisible" title="发布前自检发现缺陷" width="720px">
    <el-alert
      :type="hasBlocker ? 'error' : 'warning'"
      :closable="false"
      :title="
        hasBlocker
          ? `存在 ${blockerCount} 个阻断级缺陷，禁止发布，请先修复`
          : `存在 ${warnCount} 个警告级缺陷，确认后可强制发布`
      "
      style="margin-bottom: 12px"
    />
    <el-table :data="defects" size="small" max-height="360">
      <el-table-column label="级别" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.severity === 'BLOCKER' ? 'danger' : 'warning'">
            {{ row.severity === 'BLOCKER' ? '阻断' : '警告' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="type" label="类型" width="200" show-overflow-tooltip />
      <el-table-column prop="message" label="缺陷描述" min-width="220" show-overflow-tooltip />
      <el-table-column label="涉及元素" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ (row.refs || []).join('、') }}</template>
      </el-table-column>
    </el-table>
    <template #footer>
      <el-button @click="checkVisible = false">关闭</el-button>
      <el-button v-if="!hasBlocker" type="warning" @click="openPublishForce">仍要发布</el-button>
    </template>
  </el-dialog>

  <!-- 版本快照 -->
  <el-dialog v-model="snapshotVisible" :title="`版本快照：${snapshotRelease?.versionTag || ''}`" width="640px">
    <div v-loading="loadingSnapshot">
      <template v-if="snapshot">
        <el-descriptions :column="3" border>
          <el-descriptions-item v-for="(label, key) in snapshotLabels" :key="key" :label="label">
            {{ snapshot[key]?.length ?? 0 }}
          </el-descriptions-item>
        </el-descriptions>
        <el-divider content-position="left">概念清单</el-divider>
        <div class="code-list">
          <el-tag v-for="c in snapshot.concepts || []" :key="c.code" size="small" effect="plain">
            {{ c.code }}
          </el-tag>
        </div>
        <el-divider content-position="left">规则清单</el-divider>
        <div class="code-list">
          <el-tag
            v-for="r in snapshot.rules || []"
            :key="r.ruleCode"
            size="small"
            effect="plain"
            type="warning"
          >{{ r.ruleCode }}</el-tag>
          <span v-if="!(snapshot.rules || []).length" class="code-empty">无</span>
        </div>
        <el-divider content-position="left">动作清单</el-divider>
        <div class="code-list">
          <el-tag
            v-for="a in snapshot.actions || []"
            :key="a.actionCode"
            size="small"
            effect="plain"
            type="success"
          >{{ a.actionCode }}</el-tag>
          <span v-if="!(snapshot.actions || []).length" class="code-empty">无</span>
        </div>
      </template>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { CircleCheck, CircleClose } from '@element-plus/icons-vue'
import {
  listReleases,
  currentRelease,
  publishRelease,
  releaseDetail,
  listLlmLogs,
  llmStats
} from '../../../api/release'
import { checkOntology } from '../../../api/ontology'

// ---------- 版本发布 ----------
const releases = ref([])
const loadingReleases = ref(false)
const currentVersion = ref('')

const loadReleases = async () => {
  loadingReleases.value = true
  try {
    const [list, cur] = await Promise.all([listReleases(), currentRelease()])
    releases.value = list
    currentVersion.value = cur.version
  } finally {
    loadingReleases.value = false
  }
}

const publishVisible = ref(false)
const publishing = ref(false)
const publishForm = reactive({ changeSummary: '', releasedBy: '' })

// 发布前自检：无缺陷直接进发布框；有缺陷先展示缺陷表，BLOCKER 禁止、WARN 可 force
const checking = ref(false)
const checkVisible = ref(false)
const defects = ref([])
const forcePublish = ref(false)
const blockerCount = computed(() => defects.value.filter((d) => d.severity === 'BLOCKER').length)
const warnCount = computed(() => defects.value.filter((d) => d.severity === 'WARN').length)
const hasBlocker = computed(() => blockerCount.value > 0)

const openPublishDialog = () => {
  Object.assign(publishForm, { changeSummary: '', releasedBy: '' })
  publishVisible.value = true
}

const openPublish = async () => {
  checking.value = true
  try {
    const res = await checkOntology()
    defects.value = res.defects || []
    if (!defects.value.length) {
      forcePublish.value = false
      openPublishDialog()
    } else {
      checkVisible.value = true
    }
  } finally {
    checking.value = false
  }
}

const openPublishForce = () => {
  forcePublish.value = true
  checkVisible.value = false
  openPublishDialog()
}

const doPublish = async () => {
  if (!publishForm.changeSummary.trim() || !publishForm.releasedBy.trim()) {
    ElMessage.warning('请填写变更说明和发布人')
    return
  }
  publishing.value = true
  try {
    const rel = await publishRelease({ ...publishForm, force: String(forcePublish.value) })
    ElMessage.success(`已发布 ${rel.versionTag}（${rel.elementCount} 个元素）`)
    publishVisible.value = false
    loadReleases()
  } finally {
    publishing.value = false
  }
}

// ---------- 快照 ----------
const snapshotVisible = ref(false)
const loadingSnapshot = ref(false)
const snapshotRelease = ref(null)
const snapshot = ref(null)

const snapshotLabels = {
  concepts: '概念',
  attributes: '属性',
  relations: '关系',
  terms: '术语',
  metrics: '指标',
  rules: '规则',
  actions: '动作'
}

const openSnapshot = async (row) => {
  snapshotRelease.value = row
  snapshot.value = null
  snapshotVisible.value = true
  loadingSnapshot.value = true
  try {
    const detail = await releaseDetail(row.id)
    snapshot.value = JSON.parse(detail.snapshotJson || '{}')
  } finally {
    loadingSnapshot.value = false
  }
}

// ---------- LLM 调用审计 ----------
const logs = ref([])
const logPage = ref(1)
const logTotal = ref(0)
const pageSize = 20
const stats = ref({})
const loadingLogs = ref(false)
const loadingStats = ref(false)
const logTableRef = ref(null)

const callTypeText = (t) =>
  ({
    MAPPING_SUGGEST: '映射推荐',
    SEARCH_ANSWER: '语义搜索',
    RCA_REPORT: '根因报告',
    IMPACT_ADVICE: '影响评估'
  }[t] || t)

const callTypeTag = (t) =>
  ({
    MAPPING_SUGGEST: 'warning',
    SEARCH_ANSWER: 'success',
    RCA_REPORT: 'danger',
    IMPACT_ADVICE: 'primary'
  }[t] || 'info')

const toggleLogRow = (row) => logTableRef.value?.toggleRowExpansion(row)

const loadLogs = async (page = logPage.value) => {
  loadingLogs.value = true
  try {
    const res = await listLlmLogs(page, pageSize)
    logs.value = res.list
    logTotal.value = res.total
    logPage.value = res.pageNum
  } finally {
    loadingLogs.value = false
  }
}

const loadLlm = async () => {
  loadingStats.value = true
  try {
    await Promise.all([loadLogs(), llmStats().then((s) => (stats.value = s))])
  } finally {
    loadingStats.value = false
  }
}

onMounted(() => {
  loadReleases()
  loadLlm()
})
</script>

<style scoped>
.current-version {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.cv-label {
  font-size: 12px;
  color: #909399;
}

.cv-value {
  font-size: 28px;
  font-weight: 700;
  color: #67c23a;
}

.cv-value.none {
  color: #909399;
}

.llm-stats {
  display: flex;
  gap: 32px;
}

.llm-stat-value {
  font-size: 22px;
  font-weight: 700;
  color: #303133;
}

.llm-stat-label {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.by-type {
  margin-top: 12px;
}

.log-pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.prompt-digest {
  padding: 8px 12px;
}

.pd-title {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

.pd-text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  line-height: 1.7;
  color: #606266;
  font-family: 'SFMono-Regular', Consolas, Menlo, monospace;
}

.pd-err {
  margin-top: 6px;
  font-size: 12px;
  color: #f56c6c;
}

.code-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.code-empty {
  color: #c0c4cc;
  font-size: 13px;
}
</style>
