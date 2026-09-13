<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">BeModel 本体平台</div>
      <el-menu
        :default-active="activeMenu"
        router
        background-color="#001529"
        text-color="#a6adb4"
        active-text-color="#ffffff"
      >
        <el-menu-item index="/architecture">
          <el-icon><Platform /></el-icon>
          <span>架构全貌</span>
        </el-menu-item>
        <el-menu-item index="/ontology">
          <el-icon><Collection /></el-icon>
          <span>本体管理</span>
        </el-menu-item>
        <el-menu-item index="/datasource">
          <el-icon><Connection /></el-icon>
          <span>数据源绑定</span>
        </el-menu-item>
        <el-menu-item index="/glossary">
          <el-icon><Notebook /></el-icon>
          <span>统一口径</span>
        </el-menu-item>
        <el-menu-item index="/link">
          <el-icon><Share /></el-icon>
          <span>链路追溯</span>
        </el-menu-item>
        <el-menu-item index="/flow">
          <el-icon><Tickets /></el-icon>
          <span>流程演示</span>
        </el-menu-item>
        <el-menu-item index="/clinical">
          <el-icon><FirstAidKit /></el-icon>
          <span>临床决策</span>
        </el-menu-item>
        <el-menu-item index="/gov">
          <el-icon><Stamp /></el-icon>
          <span>数据治理</span>
        </el-menu-item>
        <el-menu-item index="/cs">
          <el-icon><Service /></el-icon>
          <span>AI客服</span>
        </el-menu-item>
        <el-menu-item index="/value">
          <el-icon><DataAnalysis /></el-icon>
          <span>价值实证</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="page-title">{{ route.meta.title || '' }}</span>
        <el-tag type="success" effect="plain">DeepSeek: deepseek-v4-flash</el-tag>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Platform, Collection, Connection, Notebook, Share, Tickets, FirstAidKit, Stamp, Service, DataAnalysis } from '@element-plus/icons-vue'

const route = useRoute()
const activeMenu = computed(() => route.path)
</script>

<style scoped>
.layout {
  height: 100vh;
}

.aside {
  background-color: #001529;
}

.logo {
  height: 60px;
  line-height: 60px;
  text-align: center;
  color: #ffffff;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 1px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.aside :deep(.el-menu) {
  border-right: none;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #ffffff;
  border-bottom: 1px solid #e4e7ed;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
}

.main {
  padding: 16px;
  overflow-y: auto;
}
</style>
