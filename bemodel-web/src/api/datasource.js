import request from './request'

// ---------- 数据源 ----------
export const listDatasources = () => request.get('/datasource/list')

export const scanDatasource = (dsCode) => request.post(`/datasource/scan/${dsCode}`)

export const listTables = (dsCode) => request.get(`/datasource/tables/${dsCode}`)

export const listColumns = (dsCode, tableName) =>
  request.get(`/datasource/columns/${dsCode}`, { params: { tableName } })

// ---------- 映射 ----------
export const listMappings = (dsCode, tableName) =>
  request.get('/mapping/list', { params: { dsCode, tableName } })

export const saveMappings = (mappings) => request.post('/mapping/batch', mappings)

export const aiSuggest = (dsCode, tableName) =>
  request.get('/mapping/ai-suggest', { params: { dsCode, tableName } })

export const deleteMapping = (id) => request.delete(`/mapping/${id}`)
