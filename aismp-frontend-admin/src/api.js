// 数字员工内核「配置面」接口封装。
//
// 管理端只用配置面（/admin/** 与 /agent/*/introspect），不碰对话链路 ——
// 这是控制面 / 数据面在前端的分离：用户端只消费能力，管理端只生产配置。
//
// 鉴权：所有请求自动带 Bearer token（见 auth.js），401 统一跳回登录页。
import { getToken, handleUnauthorized } from './auth.js';

const BASE = '/api';

async function request(path, options) {
  const token = getToken();
  const resp = await fetch(BASE + path, {
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    ...options,
  });
  if (resp.status === 401) {
    handleUnauthorized();
    throw new Error('登录已过期，请重新登录');
  }
  if (!resp.ok) {
    let msg = `HTTP ${resp.status} ${resp.statusText}`;
    try {
      const err = (await resp.json())?.error;
      if (err) msg = err;
    } catch {
      // 保留默认消息
    }
    throw new Error(msg);
  }
  const text = await resp.text();
  if (!text) return null;
  const data = JSON.parse(text);
  if (data && data.error) {
    throw new Error(data.error);
  }
  return data;
}

/* ---------------- 数字员工 ---------------- */

export const listAgents = () => request('/admin/agent/list');
export const getAgentDetail = (code) => request(`/admin/agent/${code}/detail`);
export const createAgent = (payload) =>
  request('/admin/agent', { method: 'POST', body: JSON.stringify(payload) });
export const updateAgent = (code, payload) =>
  request(`/admin/agent/${code}`, { method: 'PUT', body: JSON.stringify(payload) });
export const deleteAgent = (code) => request(`/admin/agent/${code}`, { method: 'DELETE' });

export const attachTool = (code, toolId) =>
  request(`/admin/agent/${code}/tool/${toolId}`, { method: 'POST' });
export const detachTool = (code, toolId) =>
  request(`/admin/agent/${code}/tool/${toolId}`, { method: 'DELETE' });
export const attachSkill = (code, skillId) =>
  request(`/admin/agent/${code}/skill/${skillId}`, { method: 'POST' });
export const detachSkill = (code, skillId) =>
  request(`/admin/agent/${code}/skill/${skillId}`, { method: 'DELETE' });

/** 手动逐出缓存（正常不需要，version 会自己触发重建；演示「立刻生效」时用） */
export const reloadAgent = (code) =>
  request(`/admin/agent/${code}/reload`, { method: 'POST' });
export const runtimeCache = () => request('/admin/agent/runtime/cache');

/* ---------------- 资源目录 ---------------- */

export const catalogSkills = () => request('/admin/agent/catalog/skills');
export const catalogTools = () => request('/admin/agent/catalog/tools');
export const catalogRoles = () => request('/admin/agent/catalog/roles');
export const catalogKbs = () => request('/admin/agent/catalog/kbs');
export const kbDocs = (kbId) => request(`/admin/agent/catalog/kbs/${kbId}/docs`);

/* ---------------- 技能包（管理面 CRUD） ---------------- */

export const listSkills = () => request('/admin/skill/list');
export const createSkill = (payload) =>
  request('/admin/skill', { method: 'POST', body: JSON.stringify(payload) });
export const updateSkill = (id, payload) =>
  request(`/admin/skill/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
export const deleteSkill = (id) => request(`/admin/skill/${id}`, { method: 'DELETE' });

/* ---------------- 知识库（管理面 CRUD + 文档） ---------------- */

export const listKbsAdmin = () => request('/admin/kb/list');
export const getKbSettings = () => request('/admin/kb/settings');
export const createKb = (payload) =>
  request('/admin/kb', { method: 'POST', body: JSON.stringify(payload) });
export const updateKb = (id, payload) =>
  request(`/admin/kb/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
export const deleteKb = (id) => request(`/admin/kb/${id}`, { method: 'DELETE' });
export const listKbDocuments = (id) => request(`/admin/kb/${id}/documents`);
export const deleteKbDocument = (id, documentId) =>
  request(`/admin/kb/${id}/documents/${documentId}`, { method: 'DELETE' });
export const reindexKb = (id) => request(`/admin/kb/${id}/reindex`, { method: 'POST' });

/**
 * 上传知识库文档（multipart）。
 * 注意不能复用 request()：那里写死了 Content-Type: application/json，
 * 而 multipart 必须让浏览器自己带 boundary。
 */
export async function uploadKbDocument(kbId, file, category) {
  const token = getToken();
  const form = new FormData();
  form.append('file', file);
  if (category) form.append('category', category);
  const resp = await fetch(`${BASE}/admin/kb/${kbId}/documents`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: form,
  });
  if (resp.status === 401) {
    handleUnauthorized();
    throw new Error('登录已过期，请重新登录');
  }
  if (!resp.ok) {
    let msg = `HTTP ${resp.status} ${resp.statusText}`;
    try {
      const err = (await resp.json())?.error;
      if (err) msg = err;   // 演示账号 403 等场景，后端/拦截器给的人话提示
    } catch {
      // 保留默认消息
    }
    throw new Error(msg);
  }
  const data = await resp.json();
  if (data && data.error) {
    throw new Error(data.error);
  }
  return data;
}

/* ---------------- MCP 服务（管理面 CRUD + 探测） ---------------- */

export const listMcpServers = () => request('/admin/mcp/list');
export const createMcpServer = (payload) =>
  request('/admin/mcp', { method: 'POST', body: JSON.stringify(payload) });
export const updateMcpServer = (id, payload) =>
  request(`/admin/mcp/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
export const deleteMcpServer = (id) => request(`/admin/mcp/${id}`, { method: 'DELETE' });
/** 探测远端 MCP server 的工具清单（成功/失败都是 200，看返回的 ok 字段） */
export const probeMcpServer = (id) => request(`/admin/mcp/${id}/probe`, { method: 'POST' });

/* ---------------- 知识库 / 角色 挂载 ---------------- */

export const attachKb = (code, kbId) =>
  request(`/admin/agent/${code}/kb/${kbId}`, { method: 'POST' });
export const detachKb = (code) =>
  request(`/admin/agent/${code}/kb`, { method: 'DELETE' });
export const attachRole = (code, roleCode) =>
  request(`/admin/agent/${code}/role/${roleCode}`, { method: 'POST' });
export const detachRole = (code, roleCode) =>
  request(`/admin/agent/${code}/role/${roleCode}`, { method: 'DELETE' });

/* ---------------- Token 用量分配 ---------------- */

export const usageOverview = (month) =>
  request(`/admin/token/usage/overview${month ? `?month=${encodeURIComponent(month)}` : ''}`);
export const agentUsage = (month) =>
  request(`/admin/token/usage/agents${month ? `?month=${encodeURIComponent(month)}` : ''}`);
export const usageDaily = (userId, days = 30) =>
  request(`/admin/token/usage/daily?userId=${encodeURIComponent(userId)}&days=${days}`);
export const listQuotas = () => request('/admin/token/quota/list');
/** 配额对象候选（按范围）：USER → 平台账号；AGENT/TENANT 由前端从各自目录取 */
export const tokenTargetCodes = (scope = 'USER') =>
  request(`/admin/token/target-codes?scope=${encodeURIComponent(scope)}`);
export const createQuota = (payload) =>
  request('/admin/token/quota', { method: 'POST', body: JSON.stringify(payload) });
export const updateQuota = (id, payload) =>
  request(`/admin/token/quota/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
export const deleteQuota = (id) =>
  request(`/admin/token/quota/${id}`, { method: 'DELETE' });
/** 某月用量汇总（按用户 + 按智能体）——配额表「本月已使用」列 */
export const usageMonthly = (month) =>
  request(`/admin/token/usage/monthly?month=${encodeURIComponent(month)}`);
/** 某对象某月逐日 × 智能体用量（调整弹窗：日柱状图 + 智能体分布饼图） */
export const usageDailyAgents = (month, userId, agentCode) => {
  const q = new URLSearchParams({ month });
  if (userId) q.set('userId', userId);
  if (agentCode) q.set('agentCode', agentCode);
  return request(`/admin/token/usage/daily-agents?${q}`);
};
/** 功能点调用次数（用户端·终端管理功能入口，按月份） */
export const featureUsage = (month) =>
  request(`/admin/token/feature-usage${month ? `?month=${encodeURIComponent(month)}` : ''}`);

/* ---------------- 工具 ---------------- */

export const listTools = () => request('/admin/tool/list');
export const toolTemplate = () => request('/admin/tool/template');
export const dbSchema = () => request('/admin/tool/db-schema');

/**
 * 校验工具定义。dryRun=true 会真的执行一次 SQL（哑值参数），
 * 能抓到列名写错这类问题 —— 这是「创建时就能发现问题」的关键。
 */
export const validateTool = (payload, dryRun = true) =>
  request(`/admin/tool/validate?dryRun=${dryRun}`, {
    method: 'POST',
    body: JSON.stringify(payload),
  });

export const createTool = (payload) =>
  request('/admin/tool?dryRun=true', { method: 'POST', body: JSON.stringify(payload) });
export const updateTool = (id, payload) =>
  request(`/admin/tool/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
export const deleteTool = (id) => request(`/admin/tool/${id}`, { method: 'DELETE' });

/* ---------------- 运行时与审计 ---------------- */

export const introspect = (code) => request(`/agent/${code}/introspect`);
export const recentToolCalls = (limit = 30) =>
  request(`/admin/audit/recent?limit=${limit}`);
export const toolCallStats = () => request('/admin/audit/stats');
