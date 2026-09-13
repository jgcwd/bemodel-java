<template>
  <div
    class="page"
    v-loading="loading"
    element-loading-text="正在同场执行四组实验（A 组为平台真实调用）…"
  >
    <!-- 顶部说明 -->
    <el-card>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="判断标准不是「谁更快」，而是「能不能、靠什么」：传统方式或纯 AI 解决不了的问题，本体论能解决"
      />
      <div class="legend">
        <span class="legend-item legend-a">A 组｜AI + 本体（平台实测，证据可复核）</span>
        <span class="legend-item legend-b">B 组｜AI + 裸 SQL（只有 execute_sql，靠模型自觉）</span>
        <span class="legend-item legend-c">C 组｜传统固定功能系统（无 AI，可靠但新需求要排期）</span>
      </div>
    </el-card>

    <template v-if="data">
      <!-- 实验卡 -->
      <el-card
        v-for="exp in data.experiments"
        :key="exp.key"
        class="exp-card"
        style="margin-top: 16px"
      >
        <template #header>
          <div class="exp-header">
            <span class="exp-title">{{ exp.title }}</span>
            <span class="exp-question">{{ exp.question }}</span>
          </div>
        </template>

        <el-row :gutter="12">
          <el-col v-for="sideKey in ['a', 'b', 'c']" :key="sideKey" :span="8">
            <div class="side-panel" :class="'side-' + sideKey">
              <div class="side-head">
                <span class="side-label">{{ exp[sideKey].label }}</span>
                <el-tag size="small" :type="exp[sideKey].tag" effect="dark">
                  {{ exp[sideKey].outcome }}
                </el-tag>
              </div>
              <ul class="side-lines">
                <li v-for="(line, i) in exp[sideKey].lines" :key="i">{{ line }}</li>
              </ul>
              <div class="side-basis">靠什么：{{ exp[sideKey].basis }}</div>
            </div>
          </el-col>
        </el-row>

        <div class="verdict-bar">
          <span class="verdict-label">判断</span>
          <span class="verdict-text">{{ exp.verdict }}</span>
        </div>
      </el-card>

      <!-- LLM 价值总结 -->
      <el-card v-if="data.llmSummary" style="margin-top: 16px">
        <template #header>
          <div class="card-header">
            <span>价值总结</span>
            <span class="llm-note">由 DeepSeek 生成</span>
          </div>
        </template>
        <div class="summary-text">{{ data.llmSummary }}</div>
        <div class="gen-time">生成于 {{ data.generatedAt }}</div>
      </el-card>
    </template>
    <el-card v-else-if="!loading" style="margin-top: 16px">
      <el-empty description="实证数据为空" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { valueCompare } from '../../api/value'

const data = ref(null)
const loading = ref(false)

const load = async () => {
  loading.value = true
  try {
    data.value = await valueCompare()
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 10px;
}

.legend-item {
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 4px;
}

.legend-a {
  color: #67c23a;
  background: #f0f9eb;
}

.legend-b {
  color: #e6a23c;
  background: #fdf6ec;
}

.legend-c {
  color: #909399;
  background: #f4f4f5;
}

.exp-header {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.exp-title {
  font-size: 15px;
  font-weight: 600;
}

.exp-question {
  font-size: 12px;
  font-weight: normal;
  color: #909399;
}

.side-panel {
  height: 100%;
  border-radius: 6px;
  padding: 12px 14px;
  border: 1px solid #e4e7ed;
  display: flex;
  flex-direction: column;
}

.side-a {
  background: #f0f9eb;
  border-color: #c2e7b0;
}

.side-b {
  background: #fdf6ec;
  border-color: #f5dab1;
}

.side-c {
  background: #f4f4f5;
  border-color: #e4e7ed;
}

.side-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.side-label {
  font-size: 13px;
  font-weight: 700;
}

.side-a .side-label {
  color: #67c23a;
}

.side-b .side-label {
  color: #e6a23c;
}

.side-c .side-label {
  color: #909399;
}

.side-lines {
  margin: 0;
  padding-left: 16px;
  flex: 1;
}

.side-lines li {
  font-size: 12px;
  line-height: 1.8;
  color: #606266;
  margin-bottom: 4px;
}

.side-basis {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed #dcdfe6;
  font-size: 12px;
  font-weight: 600;
  color: #303133;
}

.verdict-bar {
  margin-top: 12px;
  padding: 10px 14px;
  background: #ecf5ff;
  border: 1px solid #b3d8ff;
  border-radius: 4px;
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.verdict-label {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 700;
  color: #409eff;
}

.verdict-text {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}

.llm-note {
  font-size: 12px;
  color: #909399;
  font-weight: normal;
}

.summary-text {
  white-space: pre-wrap;
  line-height: 1.9;
  font-size: 14px;
}

.gen-time {
  margin-top: 10px;
  font-size: 12px;
  color: #c0c4cc;
}
</style>
