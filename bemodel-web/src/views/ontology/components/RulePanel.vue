<template>
  <el-card>
    <div class="filter-bar">
      <el-select
        v-model="filterConcept"
        placeholder="按概念筛选"
        clearable
        filterable
        style="width: 240px"
        @change="loadRules"
      >
        <el-option
          v-for="c in conceptStore.concepts"
          :key="c.code"
          :label="`${c.name}（${c.code}）`"
          :value="c.code"
        />
      </el-select>
      <el-button type="primary" @click="openDialog('create')">新建规则</el-button>
    </div>
    <el-table :data="rules" v-loading="loading">
      <el-table-column prop="ruleCode" label="规则编码" width="140" />
      <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
      <el-table-column prop="conceptCode" label="关联概念" width="120" />
      <el-table-column label="类型" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="ruleTypeTag(row.ruleType)">{{ row.ruleType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="严重度" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="severityTag(row.severity)">{{ row.severity }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="关联指标" width="150">
        <template #default="{ row }">{{ row.metricCode || '-' }}</template>
      </el-table-column>
      <el-table-column label="驱动方式" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.engine === 'QC'" size="small" type="success">表达式驱动</el-tag>
          <el-tag v-else-if="row.metricCode" size="small" type="info">监控指标</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="表达式" width="80">
        <template #default="{ row }">
          <el-button v-if="row.exprJson" link type="primary" size="small" @click="openExprView(row)">
            查看
          </el-button>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="owner" label="负责人" width="90" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTagType(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="版本" width="70">
        <template #default="{ row }">v{{ row.version }}</template>
      </el-table-column>
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'DRAFT'"
            link type="primary" size="small"
            :loading="actingCode === row.ruleCode"
            @click="doTransition(row, 'REVIEW')"
          >提交评审</el-button>
          <template v-if="row.status === 'REVIEW'">
            <el-button
              link type="success" size="small"
              :loading="actingCode === row.ruleCode"
              @click="doTransition(row, 'PUBLISHED')"
            >发布</el-button>
            <el-button
              link type="warning" size="small"
              :loading="actingCode === row.ruleCode"
              @click="doTransition(row, 'DRAFT')"
            >退回草稿</el-button>
          </template>
          <el-button
            v-if="row.status === 'PUBLISHED'"
            link type="danger" size="small"
            :loading="actingCode === row.ruleCode"
            @click="doTransition(row, 'DEPRECATED')"
          >废弃</el-button>
          <el-button
            v-if="row.status === 'DEPRECATED'"
            link type="primary" size="small"
            :loading="actingCode === row.ruleCode"
            @click="doTransition(row, 'DRAFT')"
          >重建为草稿</el-button>
          <el-button link size="small" @click="openDialog('edit', row)">编辑</el-button>
          <el-button link type="danger" size="small" @click="removeRule(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建/编辑规则 -->
    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新建规则' : '编辑规则'" width="680px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="规则编码" required>
          <el-input v-model="form.ruleCode" :disabled="dialogMode === 'edit'" placeholder="如 RULE-FEE-001" />
        </el-form-item>
        <el-form-item label="规则名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="关联概念" required>
          <el-select v-model="form.conceptCode" filterable style="width: 100%">
            <el-option
              v-for="c in conceptStore.concepts"
              :key="c.code"
              :label="`${c.name}（${c.code}）`"
              :value="c.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="规则类型">
          <el-select v-model="form.ruleType" style="width: 100%">
            <el-option label="约束" value="约束" />
            <el-option label="推导" value="推导" />
            <el-option label="校验" value="校验" />
          </el-select>
        </el-form-item>
        <el-form-item label="严重度">
          <el-select v-model="form.severity" style="width: 100%">
            <el-option label="高" value="高" />
            <el-option label="中" value="中" />
            <el-option label="低" value="低" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联指标">
          <el-select v-model="form.metricCode" clearable filterable style="width: 100%" placeholder="可不关联">
            <el-option
              v-for="m in metrics"
              :key="m.metricCode"
              :label="`${m.name}（${m.metricCode}）`"
              :value="m.metricCode"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="执行引擎">
          <el-select v-model="form.engine" style="width: 100%">
            <el-option label="无" value="" />
            <el-option label="QC 质控引擎" value="QC" />
          </el-select>
        </el-form-item>

        <!-- 表达式构建器：engine = QC 时替代手写 JSON -->
        <template v-if="form.engine === 'QC'">
          <el-form-item label="规则类型">
            <el-select v-model="exprForm.type" style="width: 100%" :disabled="exprRaw">
              <el-option
                v-for="t in EXPR_TYPES"
                :key="t.value"
                :label="`${t.label}（${t.value}）`"
                :value="t.value"
              />
            </el-select>
            <div v-if="!exprRaw" class="expr-tip">{{ currentTypeMeta.desc }}</div>
          </el-form-item>

          <!-- 反解析失败：回退原始 JSON 编辑 -->
          <el-form-item v-if="exprRaw" label="原始 JSON">
            <div class="expr-raw">
              <el-alert
                type="warning"
                :closable="false"
                title="该表达式无法解析为可视化表单，请直接编辑原始 JSON"
                style="margin-bottom: 8px"
              />
              <el-input v-model="form.exprJson" type="textarea" :rows="6" />
              <el-button link type="primary" size="small" @click="retryParse">
                尝试解析为表单
              </el-button>
            </div>
          </el-form-item>

          <template v-else>
            <el-form-item v-if="exprForm.type !== 'DIAG_REQUIRES_ITEM'" label="引用公理">
              <el-input v-model="exprForm.axiom" placeholder="如 AX-001" style="width: 200px" />
            </el-form-item>

            <!-- cases：诊断必须有配套项目 / 并发症须有对应项目 -->
            <el-form-item v-if="hasCases" label="匹配项">
              <div class="case-list">
                <div v-for="(c, i) in exprForm.cases" :key="i" class="case-item">
                  <div class="case-row">
                    <span class="case-label">诊断关键词</span>
                    <TagInput v-model="c.diagKeywords" placeholder="如 糖尿病" />
                  </div>
                  <div class="case-row">
                    <span class="case-label">项目类型</span>
                    <el-select v-model="c.kind" style="width: 160px">
                      <el-option label="检验（LAB）" value="LAB" />
                      <el-option label="药品（DRUG）" value="DRUG" />
                    </el-select>
                  </div>
                  <div class="case-row">
                    <span class="case-label">项目编码</span>
                    <TagInput v-model="c.codes" placeholder="如 L007" />
                  </div>
                  <div class="case-row">
                    <span class="case-label">要求项目</span>
                    <el-input v-model="c.requireName" placeholder="如 空腹血糖检验" style="width: 240px" />
                    <el-button
                      link type="danger" size="small"
                      :disabled="exprForm.cases.length <= 1"
                      @click="exprForm.cases.splice(i, 1)"
                    >删除</el-button>
                  </div>
                </div>
                <el-button size="small" @click="exprForm.cases.push(emptyCase())">添加匹配项</el-button>
              </div>
            </el-form-item>

            <!-- requires：术前必备检查 -->
            <el-form-item v-if="exprForm.type === 'PREOP_REQUIRES'" label="必查项目">
              <div class="case-list">
                <div v-for="(r, i) in exprForm.requires" :key="i" class="case-item">
                  <div class="case-row">
                    <span class="case-label">项目类型</span>
                    <el-select v-model="r.kind" style="width: 160px">
                      <el-option label="检验（LAB）" value="LAB" />
                      <el-option label="药品（DRUG）" value="DRUG" />
                    </el-select>
                  </div>
                  <div class="case-row">
                    <span class="case-label">项目编码</span>
                    <TagInput v-model="r.codes" placeholder="如 L001" />
                  </div>
                  <div class="case-row">
                    <span class="case-label">要求项目</span>
                    <el-input v-model="r.requireName" placeholder="如 血常规" style="width: 240px" />
                    <el-button
                      link type="danger" size="small"
                      :disabled="exprForm.requires.length <= 1"
                      @click="exprForm.requires.splice(i, 1)"
                    >删除</el-button>
                  </div>
                </div>
                <el-button size="small" @click="exprForm.requires.push(emptyRequire())">添加必查项</el-button>
              </div>
            </el-form-item>

            <!-- 检验异常须有处置 -->
            <template v-if="exprForm.type === 'ABNORMAL_REQUIRES_COVER'">
              <el-form-item label="覆盖诊断词">
                <TagInput v-model="exprForm.coverDiagKeywords" placeholder="如 感染" />
              </el-form-item>
              <el-form-item label="处置类型">
                <el-select v-model="exprForm.coverKind" style="width: 160px">
                  <el-option label="药品（DRUG）" value="DRUG" />
                  <el-option label="检验（LAB）" value="LAB" />
                </el-select>
              </el-form-item>
              <el-form-item label="处置编码">
                <TagInput v-model="exprForm.coverCodes" placeholder="如 D006" />
              </el-form-item>
            </template>

            <!-- 性别互斥诊断 -->
            <template v-if="exprForm.type === 'SEX_DISJOINT_DIAG'">
              <el-form-item label="性别">
                <el-radio-group v-model="exprForm.sex">
                  <el-radio value="男">男</el-radio>
                  <el-radio value="女">女</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="互斥诊断词">
                <TagInput v-model="exprForm.diagKeywords" placeholder="如 妊娠" />
              </el-form-item>
            </template>

            <!-- 检查异常须处置 -->
            <template v-if="exprForm.type === 'EXAM_ABNORMAL_REQUIRES_COVER'">
              <el-form-item label="覆盖诊断词">
                <TagInput v-model="exprForm.coverDiagKeywords" placeholder="如 占位" />
              </el-form-item>
              <el-form-item label="手术算处置">
                <el-switch v-model="exprForm.coverProcedure" />
              </el-form-item>
            </template>

            <el-form-item label=" ">
              <div class="expr-json-toggle">
                <el-button link type="primary" size="small" @click="showJson = !showJson">
                  {{ showJson ? '收起 JSON' : '查看生成的 JSON' }}
                </el-button>
                <pre v-if="showJson" class="expr-block">{{ form.exprJson }}</pre>
              </div>
            </el-form-item>
          </template>
        </template>

        <el-form-item label="规则表达">
          <el-input
            v-model="form.expression"
            type="textarea"
            :rows="3"
            placeholder="用业务语言声明，如：医嘱状态=已取消 的明细，费用状态必须=已退费"
          />
        </el-form-item>
        <el-form-item label="负责人">
          <el-input v-model="form.owner" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 表达式查看 -->
    <el-dialog v-model="exprVisible" :title="`规则表达式：${exprRule?.ruleCode || ''}`" width="640px">
      <template v-if="exprView">
        <el-alert
          type="success"
          :closable="false"
          title="该规则由表达式驱动，修改即生效、无需发版"
          style="margin-bottom: 12px"
        />
        <div class="expr-type-line">
          <el-tag size="small" type="primary">{{ exprView.typeLabel }}</el-tag>
          <span class="expr-type-desc">{{ exprView.typeDesc }}</span>
        </div>
        <el-descriptions
          v-if="exprView.fields.length"
          :column="1"
          border
          size="small"
          class="expr-view-block"
        >
          <el-descriptions-item v-for="f in exprView.fields" :key="f.label" :label="f.label">
            <template v-if="Array.isArray(f.value)">
              <el-tag
                v-for="v in f.value"
                :key="v"
                size="small"
                effect="plain"
                style="margin-right: 4px"
              >{{ v }}</el-tag>
              <span v-if="!f.value.length">-</span>
            </template>
            <template v-else>{{ f.value }}</template>
          </el-descriptions-item>
        </el-descriptions>
        <template v-if="exprView.cases.length">
          <div class="expr-subtitle">匹配项（cases）</div>
          <el-table :data="exprView.cases" size="small" border>
            <el-table-column label="诊断关键词" min-width="130">
              <template #default="{ row }">{{ (row.diagKeywords || []).join('、') }}</template>
            </el-table-column>
            <el-table-column label="项目类型" width="90">
              <template #default="{ row }">{{ KIND_LABEL[row.kind] || row.kind }}</template>
            </el-table-column>
            <el-table-column label="项目编码" min-width="110">
              <template #default="{ row }">{{ (row.codes || []).join('、') }}</template>
            </el-table-column>
            <el-table-column prop="requireName" label="要求项目" min-width="140" />
          </el-table>
        </template>
        <template v-if="exprView.requires.length">
          <div class="expr-subtitle">术前必查项目（requires）</div>
          <el-table :data="exprView.requires" size="small" border>
            <el-table-column label="项目类型" width="90">
              <template #default="{ row }">{{ KIND_LABEL[row.kind] || row.kind }}</template>
            </el-table-column>
            <el-table-column label="项目编码" min-width="110">
              <template #default="{ row }">{{ (row.codes || []).join('、') }}</template>
            </el-table-column>
            <el-table-column prop="requireName" label="要求项目" min-width="140" />
          </el-table>
        </template>
        <el-collapse class="expr-view-block">
          <el-collapse-item title="原始 JSON" name="raw">
            <pre class="expr-block">{{ exprView.rawText }}</pre>
          </el-collapse-item>
        </el-collapse>
      </template>
      <template v-else>
        <el-alert
          type="warning"
          :closable="false"
          title="表达式无法解析为已知规则类型，展示原始内容"
          style="margin-bottom: 12px"
        />
        <pre class="expr-block">{{ exprRule?.exprJson }}</pre>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listRules, createRule, updateRule, transitionRule, deleteRule } from '../../../api/ontology'
import { listMetrics } from '../../../api/glossary'
import { statusText, statusTagType } from '../../../utils/dict'
import { useConceptStore } from '../../../store/concept'
import TagInput from './TagInput.vue'

const conceptStore = useConceptStore()

const rules = ref([])
const loading = ref(false)
const filterConcept = ref('')
const metrics = ref([])
const actingCode = ref('')

const ruleTypeTag = (t) => ({ 约束: 'danger', 推导: 'primary', 校验: 'warning' }[t] || 'info')
const severityTag = (s) => ({ 高: 'danger', 中: 'warning', 低: 'info' }[s] || 'info')

const loadRules = async () => {
  loading.value = true
  try {
    rules.value = await listRules(filterConcept.value || undefined)
  } finally {
    loading.value = false
  }
}

const doTransition = async (row, target) => {
  actingCode.value = row.ruleCode
  try {
    await transitionRule(row.ruleCode, target)
    ElMessage.success('状态流转成功')
    loadRules()
  } finally {
    actingCode.value = ''
  }
}

// ---------- 表达式类型定义（与后端 QcRuleEngine 解释器一致） ----------
const EXPR_TYPES = [
  { value: 'DIAG_REQUIRES_ITEM', label: '诊断必须有配套项目', desc: '诊断关键词命中 → 要求存在已执行的检验/药品医嘱' },
  { value: 'PREOP_REQUIRES', label: '术前必备检查', desc: '存在手术 → 必查项目须已执行且早于手术时间' },
  { value: 'ABNORMAL_REQUIRES_COVER', label: '检验异常须有处置', desc: '检验危急值 → 须被覆盖诊断或处置医嘱覆盖' },
  { value: 'COMPLICATION_REQUIRES_ITEM', label: '并发症须有对应项目', desc: '并发症关键词命中 → 要求存在已执行的处置医嘱' },
  { value: 'SEX_DISJOINT_DIAG', label: '性别互斥诊断', desc: '指定性别患者出现互斥诊断关键词 → 违反公理' },
  { value: 'EXAM_ABNORMAL_REQUIRES_COVER', label: '检查异常须处置', desc: '检查报告异常 → 须被对应诊断或手术处置覆盖' }
]

const KIND_LABEL = { LAB: '检验', DRUG: '药品' }

const emptyCase = () => ({ diagKeywords: [], kind: 'LAB', codes: [], requireName: '' })
const emptyRequire = () => ({ kind: 'LAB', codes: [], requireName: '' })

const defaultExprForm = () => ({
  type: 'DIAG_REQUIRES_ITEM',
  axiom: '',
  cases: [emptyCase()],
  requires: [emptyRequire()],
  coverDiagKeywords: [],
  coverKind: 'DRUG',
  coverCodes: [],
  sex: '男',
  diagKeywords: [],
  coverProcedure: true
})

// ---------- 表达式查看 ----------
const exprVisible = ref(false)
const exprRule = ref(null)

const asStrList = (v) => (Array.isArray(v) ? v.map(String) : [])

const exprView = computed(() => {
  const raw = exprRule.value?.exprJson
  if (!raw) return null
  let e
  try {
    e = JSON.parse(raw)
  } catch {
    return null
  }
  const meta = EXPR_TYPES.find((t) => t.value === e.type)
  if (!meta) return null
  const fields = []
  if (e.axiom) fields.push({ label: '引用公理', value: e.axiom })
  if (e.type === 'ABNORMAL_REQUIRES_COVER') {
    fields.push({ label: '覆盖诊断关键词', value: asStrList(e.coverDiagKeywords) })
    fields.push({ label: '处置类型', value: KIND_LABEL[e.coverKind] || e.coverKind || '-' })
    fields.push({ label: '处置项目编码', value: asStrList(e.coverCodes) })
  }
  if (e.type === 'SEX_DISJOINT_DIAG') {
    fields.push({ label: '互斥性别', value: e.sex || '-' })
    fields.push({ label: '互斥诊断关键词', value: asStrList(e.diagKeywords) })
  }
  if (e.type === 'EXAM_ABNORMAL_REQUIRES_COVER') {
    fields.push({ label: '覆盖诊断关键词', value: asStrList(e.coverDiagKeywords) })
    fields.push({ label: '手术记录视为处置', value: e.coverProcedure ? '是' : '否' })
  }
  return {
    typeLabel: `${meta.label}（${e.type}）`,
    typeDesc: meta.desc,
    fields,
    cases: Array.isArray(e.cases) ? e.cases : [],
    requires: Array.isArray(e.requires) ? e.requires : [],
    rawText: JSON.stringify(e, null, 2)
  }
})

const openExprView = (row) => {
  exprRule.value = row
  exprVisible.value = true
}

// ---------- 新建/编辑 ----------
const dialogVisible = ref(false)
const dialogMode = ref('create')
const saving = ref(false)
const form = reactive({
  id: null,
  ruleCode: '',
  name: '',
  conceptCode: '',
  ruleType: '约束',
  severity: '中',
  metricCode: '',
  engine: '',
  exprJson: '',
  expression: '',
  owner: ''
})

// ---------- 表达式构建器 ----------
const exprForm = reactive(defaultExprForm())
const exprRaw = ref(false)
const showJson = ref(false)

const currentTypeMeta = computed(
  () => EXPR_TYPES.find((t) => t.value === exprForm.type) || EXPR_TYPES[0]
)
const hasCases = computed(() =>
  ['DIAG_REQUIRES_ITEM', 'COMPLICATION_REQUIRES_ITEM'].includes(exprForm.type)
)

const buildExpr = () => {
  const f = exprForm
  const cleanCases = (list) =>
    list.map((c) => ({
      diagKeywords: c.diagKeywords,
      kind: c.kind,
      codes: c.codes,
      requireName: c.requireName
    }))
  switch (f.type) {
    case 'PREOP_REQUIRES':
      return {
        type: f.type,
        axiom: f.axiom,
        requires: f.requires.map((r) => ({ kind: r.kind, codes: r.codes, requireName: r.requireName }))
      }
    case 'ABNORMAL_REQUIRES_COVER':
      return {
        type: f.type,
        axiom: f.axiom,
        coverDiagKeywords: f.coverDiagKeywords,
        coverKind: f.coverKind,
        coverCodes: f.coverCodes
      }
    case 'COMPLICATION_REQUIRES_ITEM':
      return { type: f.type, axiom: f.axiom, cases: cleanCases(f.cases) }
    case 'SEX_DISJOINT_DIAG':
      return { type: f.type, axiom: f.axiom, sex: f.sex, diagKeywords: f.diagKeywords }
    case 'EXAM_ABNORMAL_REQUIRES_COVER':
      return {
        type: f.type,
        axiom: f.axiom,
        coverDiagKeywords: f.coverDiagKeywords,
        coverProcedure: f.coverProcedure
      }
    default:
      return { type: 'DIAG_REQUIRES_ITEM', cases: cleanCases(f.cases) }
  }
}

// 表单值实时生成 expr_json
watch(
  exprForm,
  () => {
    if (form.engine === 'QC' && !exprRaw.value) {
      form.exprJson = JSON.stringify(buildExpr())
    }
  },
  { deep: true }
)

watch(
  () => form.engine,
  (v) => {
    if (v === 'QC' && !exprRaw.value) {
      form.exprJson = JSON.stringify(buildExpr())
    }
  }
)

const fillExprForm = (e) => {
  const f = defaultExprForm()
  f.type = e.type
  if (typeof e.axiom === 'string') f.axiom = e.axiom
  if (Array.isArray(e.cases) && e.cases.length) {
    f.cases = e.cases.map((c) => ({
      diagKeywords: asStrList(c?.diagKeywords),
      kind: c?.kind === 'DRUG' ? 'DRUG' : 'LAB',
      codes: asStrList(c?.codes),
      requireName: typeof c?.requireName === 'string' ? c.requireName : ''
    }))
  }
  if (Array.isArray(e.requires) && e.requires.length) {
    f.requires = e.requires.map((r) => ({
      kind: r?.kind === 'DRUG' ? 'DRUG' : 'LAB',
      codes: asStrList(r?.codes),
      requireName: typeof r?.requireName === 'string' ? r.requireName : ''
    }))
  }
  f.coverDiagKeywords = asStrList(e.coverDiagKeywords)
  if (typeof e.coverKind === 'string' && e.coverKind) f.coverKind = e.coverKind
  f.coverCodes = asStrList(e.coverCodes)
  if (e.sex === '女') f.sex = '女'
  f.diagKeywords = asStrList(e.diagKeywords)
  if (typeof e.coverProcedure === 'boolean') f.coverProcedure = e.coverProcedure
  Object.assign(exprForm, f)
}

// 反解析 expr_json 回表单；失败返回 false，由调用方回退原始 JSON
const parseExpr = (jsonStr) => {
  try {
    const e = JSON.parse(jsonStr)
    if (!e || !EXPR_TYPES.some((t) => t.value === e.type)) return false
    fillExprForm(e)
    return true
  } catch {
    return false
  }
}

const retryParse = () => {
  if (parseExpr(form.exprJson)) {
    exprRaw.value = false
  } else {
    ElMessage.warning('仍无法解析，请检查 JSON 格式与规则类型')
  }
}

const openDialog = (mode, row) => {
  dialogMode.value = mode
  exprRaw.value = false
  showJson.value = false
  if (mode === 'create') {
    Object.assign(form, {
      id: null,
      ruleCode: '',
      name: '',
      conceptCode: filterConcept.value || '',
      ruleType: '约束',
      severity: '中',
      metricCode: '',
      engine: '',
      exprJson: '',
      expression: '',
      owner: ''
    })
    Object.assign(exprForm, defaultExprForm())
  } else {
    Object.assign(form, {
      id: row.id,
      ruleCode: row.ruleCode,
      name: row.name,
      conceptCode: row.conceptCode,
      ruleType: row.ruleType,
      severity: row.severity,
      metricCode: row.metricCode || '',
      engine: row.engine || '',
      exprJson: row.exprJson || '',
      expression: row.expression,
      owner: row.owner
    })
    if (form.engine === 'QC' && form.exprJson) {
      exprRaw.value = !parseExpr(form.exprJson)
    } else {
      Object.assign(exprForm, defaultExprForm())
    }
  }
  dialogVisible.value = true
}

const submit = async () => {
  if (!form.ruleCode || !form.name || !form.conceptCode) {
    ElMessage.warning('请填写规则编码、名称和关联概念')
    return
  }
  if (form.engine === 'QC') {
    if (!form.exprJson) {
      ElMessage.warning('请配置规则表达式')
      return
    }
    try {
      JSON.parse(form.exprJson)
    } catch {
      ElMessage.warning('规则表达式 JSON 格式有误')
      return
    }
  }
  saving.value = true
  try {
    const payload = {
      ruleCode: form.ruleCode,
      name: form.name,
      conceptCode: form.conceptCode,
      ruleType: form.ruleType,
      severity: form.severity,
      metricCode: form.metricCode || null,
      engine: form.engine || null,
      exprJson: form.engine === 'QC' ? form.exprJson || null : null,
      expression: form.expression,
      owner: form.owner
    }
    if (dialogMode.value === 'create') {
      await createRule(payload)
      ElMessage.success('规则创建成功（草稿）')
    } else {
      const row = rules.value.find((r) => r.id === form.id)
      await updateRule({ ...row, ...payload })
      ElMessage.success('规则已更新')
    }
    dialogVisible.value = false
    loadRules()
  } finally {
    saving.value = false
  }
}

const removeRule = async (row) => {
  await ElMessageBox.confirm(`确认删除规则「${row.name}」？`, '提示', { type: 'warning' })
  await deleteRule(row.id)
  ElMessage.success('已删除')
  loadRules()
}

onMounted(() => {
  conceptStore.fetchAll()
  listMetrics().then((res) => (metrics.value = res))
  loadRules()
})
</script>

<style scoped>
.filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.expr-block {
  margin: 0;
  padding: 12px;
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  font-family: 'SFMono-Regular', Consolas, Menlo, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  color: #606266;
}

.expr-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.expr-raw {
  width: 100%;
}

.expr-json-toggle {
  width: 100%;
}

.case-list {
  width: 100%;
}

.case-item {
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 8px 12px;
  margin-bottom: 10px;
}

.case-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.case-row:last-child {
  margin-bottom: 0;
}

.case-label {
  width: 70px;
  flex-shrink: 0;
  font-size: 12px;
  color: #909399;
}

.expr-type-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.expr-type-desc {
  font-size: 12px;
  color: #909399;
}

.expr-view-block {
  margin-top: 12px;
}

.expr-subtitle {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  margin: 12px 0 8px;
}
</style>
