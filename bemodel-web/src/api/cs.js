import request from './request'

// ---------- AI客服 ----------
export const listTickets = (pageNum = 1, pageSize = 20) =>
  request.get('/link/list', { params: { nodeType: 'TICKET', pageNum, pageSize } })

export const ticketDiagnosis = (ticketId) => request.get(`/cs/ticket/${ticketId}/diagnosis`)

export const refundTicket = (ticketId, operator = '客服小周') =>
  request.post(`/cs/ticket/${ticketId}/refund`, null, { params: { operator } })

// 问一问：自然语言提问（scene=CS 客服 / ANALYTICS 智能问数），平台真实能力作答
export const askCs = (question, scene = 'CS') => request.post('/cs/ask', { question, scene })

// 回答反馈：correct 1=有帮助 0=归类有误；错例会回流进 LLM 路由提示词
export const submitCsFeedback = (data) => request.post('/cs/feedback', data)
