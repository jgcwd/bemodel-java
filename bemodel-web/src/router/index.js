import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '../layout/MainLayout.vue'

const routes = [
  {
    path: '/',
    component: MainLayout,
    redirect: '/architecture',
    children: [
      {
        path: 'architecture',
        name: 'architecture',
        component: () => import('../views/architecture/index.vue'),
        meta: { title: '架构全貌' }
      },
      {
        path: 'ontology',
        name: 'ontology',
        component: () => import('../views/ontology/index.vue'),
        meta: { title: '本体管理' }
      },
      {
        path: 'datasource',
        name: 'datasource',
        component: () => import('../views/datasource/index.vue'),
        meta: { title: '数据源绑定' }
      },
      {
        path: 'glossary',
        name: 'glossary',
        component: () => import('../views/glossary/index.vue'),
        meta: { title: '统一口径' }
      },
      {
        path: 'link',
        name: 'link',
        component: () => import('../views/link/index.vue'),
        meta: { title: '链路追溯' }
      },
      {
        path: 'flow',
        name: 'flow',
        component: () => import('../views/flow/index.vue'),
        meta: { title: '流程演示' }
      },
      {
        path: 'clinical',
        name: 'clinical',
        component: () => import('../views/clinical/index.vue'),
        meta: { title: '临床决策' }
      },
      {
        path: 'gov',
        name: 'gov',
        component: () => import('../views/gov/index.vue'),
        meta: { title: '数据治理' }
      },
      {
        path: 'cs',
        name: 'cs',
        component: () => import('../views/cs/index.vue'),
        meta: { title: 'AI客服' }
      },
      {
        path: 'value',
        name: 'value',
        component: () => import('../views/value/index.vue'),
        meta: { title: '价值实证' }
      },
      {
        path: 'rca',
        redirect: '/cs'
      }
    ]
  }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
