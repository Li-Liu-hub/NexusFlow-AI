// 数字员工内核「配置面」接口封装。
//
// 管理端只用配置面（/admin/** 与 /agent/*/introspect），不碰对话链路 ——
// 控制面 / 数据面在前端分离：用户端只消费能力，管理端只生产配置。
//
// 类型策略（TS 全量迁移第一段）：**边界强类型、页面渐进**。
// 网络边界有明确签名与错误语义；各接口先用泛型占位（默认 any），
// 页面侧 DTO 随页面逐个收紧 —— 一次性给 20 个接口编 DTO 只会写出「看似有类型、实则全错」。
import { getToken, handleUnauthorized } from './auth';

const BASE = '/api';

async function request<T = any>(path: string, options?: RequestInit): Promise<T> {
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
  if (!text) return null as T;
  const data = JSON.parse(text);
  if (data && data.error) {
    throw new Error(data.error);
  }
  return data as T;
}

/* ---------------- 数字员工 ---------------- */

export const listAgents = () => request<any[]>('/admin/agent/list');
export const getAgentDetail = (code: string) => request(`/admin/agent/${code}/detail`);
export const createAgent = (payload: unknown) =>
  request('/admin/agent', { method: 'POST', body: JSON.stringify(payload) });
export const updateAgent = (code: string, payload: unknown) =>
  request(`/admin/agent/${code}`, { method: 'PUT', body: JSON.stringify(payload) });
export const deleteAgent = (code: string) =>
  request(`/admin/agent/${code}`, { method: 'DELETE' });

export const attachTool = (code: string, toolId: number | string) =>
  request(`/admin/agent/${code}/tool/${toolId}`, { method: 'POST' });
export const detachTool = (code: string, toolId: number | string) =>
  request(`/admin/agent/${code}/tool/${toolId}`, { method: 'DELETE' });
export const attachSkill = (code: string, skillId: number | string) =>
  request(`/admin/agent/${code}/skill/${skillId}`, { method: 'POST' });
export const detachSkill = (code: string, skillId: number | string) =>
  request(`/admin/agent/${code}/skill/${skillId}`, { method: 'DELETE' });

/** 手动逐出缓存（正常不需要，version 会自己触发重建；验证「立刻生效」时用） */
export const reloadAgent = (code: string) =>
  request(`/admin/agent/${code}/reload`, { method: 'POST' });
export const runtimeCache = () => request('/admin/agent/runtime/cache');

/* ---------------- 资源目录 ---------------- */

export const catalogSkills = () => request<any[]>('/admin/agent/catalog/skills');
export const catalogTools = () => request<any[]>('/admin/agent/catalog/tools');
export const catalogRoles = () => request<any[]>('/admin/agent/catalog/roles');
export const catalogKbs = () => request<any[]>('/admin/agent/catalog/kbs');
export const kbDocs = (kbId: number | string) =>
  request(`/admin/agent/catalog/kbs/${kbId}/docs`);

/* ---------------- 技能包（管理面 CRUD） ---------------- */

export const listSkills = () => request<any[]>('/admin/skill/list');
export const createSkill = (payload: unknown) =>
  request('/admin/skill', { method: 'POST', body: JSON.stringify(payload) });
export const updateSkill = (id: number | string, payload: unknown) =>
  request(`/admin/skill/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
export const deleteSkill = (id: number | string) =>
  request(`/admin/skill/${id}`, { method: 'DELETE' });

/* ---------------- 知识库（管理面 CRUD + 文档） ---------------- */

export const listKbsAdmin = () => request<any[]>('/admin/kb/list');
export const getKbSettings = () => request('/admin/kb/settings');
export const createKb = (payload: unknown) =>
  request('/admin/kb', { method: 'POST', body: JSON.stringify(payload) });
export const updateKb = (id: number | string, payload: unknown) =>
  request(`/admin/kb/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
export const deleteKb = (id: number | string) =>
  request(`/admin/kb/${id}`, { method: 'DELETE' });
export const listKbDocuments = (id: number | string) =>
  request<any[]>(`/admin/kb/${id}/documents`);
export const deleteKbDocument = (id: number | string, documentId: number | string) =>
  request(`/admin/kb/${id}/documents/${documentId}`, { method: 'DELETE' });
export const reindexKb = (id: number | string) =>
  request(`/admin/kb/${id}/reindex`, { method: 'POST' });

/**
 * 上传知识库文档（multipart）。
 * 不能复用 request()：那里写死 Content-Type: application/json，
 * 而 multipart 必须让浏览器自带 boundary。
 */
export async function uploadKbDocument(
  kbId: number | string,
  file: File,
  category?: string,
): Promise<any> {
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
      if (err) msg = err; // 演示账号 403 等场景，后端/拦截器给的人话提示
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

export const listMcpServers = () => request<any[]>('/admin/mcp/list');
export const createMcpServer = (payload: unknown) =>
  request('/admin/mcp', { method: 'POST', body: JSON.stringify(payload) });
export const updateMcpServer = (id: number | string, payload: unknown) =>
  request(`/admin/mcp/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
export const deleteMcpServer = (id: number | string) =>
  request(`/admin/mcp/${id}`, { method: 'DELETE' });
/** 探测远端 MCP server 的工具清单（成功/失败都是 200，看返回的 ok 字段） */
export const probeMcpServer = (id: number | string) =>
  request(`/admin/mcp/${id}/probe`, { method: 'POST' });

/* ---------------- 知识库 / 角色 挂载 ---------------- */

export const attachKb = (code: string, kbId: number | string) =>
  request(`/admin/agent/${code}/kb/${kbId}`, { method: 'POST' });
export const detachKb = (code: string) =>
  request(`/admin/agent/${code}/kb`, { method: 'DELETE' });
export const attachRole = (code: string, roleCode: string) =>
  request(`/admin/agent/${code}/role/${roleCode}`, { method: 'POST' });
export const detachRole = (code: string, roleCode: string) =>
  request(`/admin/agent/${code}/role/${roleCode}`, { method: 'DELETE' });

/* ---------------- Token 用量分配 ---------------- */

export const usageOverview = (month?: string) =>
  request(`/admin/token/usage/overview${month ? `?month=${encodeURIComponent(month)}` : ''}`);
export const agentUsage = (month?: string) =>
  request<any[]>(`/admin/token/usage/agents${month ? `?month=${encodeURIComponent(month)}` : ''}`);
export const usageDaily = (userId: string, days = 30) =>
  request(`/admin/token/usage/daily?userId=${encodeURIComponent(userId)}&days=${days}`);
export const listQuotas = () => request<any[]>('/admin/token/quota/list');
/** 配额对象候选（按范围）：USER → 平台账号；AGENT/TENANT 由前端从各自目录取 */
export const tokenTargetCodes = (scope = 'USER') =>
  request<any[]>(`/admin/token/target-codes?scope=${encodeURIComponent(scope)}`);
export const createQuota = (payload: unknown) =>
  request('/admin/token/quota', { method: 'POST', body: JSON.stringify(payload) });
export const updateQuota = (id: number | string, payload: unknown) =>
  request(`/admin/token/quota/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
export const deleteQuota = (id: number | string) =>
  request(`/admin/token/quota/${id}`, { method: 'DELETE' });
/** 某月用量汇总（按用户 + 按智能体）——配额表「本月已使用」列 */
export const usageMonthly = (month: string) =>
  request(`/admin/token/usage/monthly?month=${encodeURIComponent(month)}`);
/** 某对象某月逐日 × 智能体用量（调整弹窗：日柱状图 + 智能体分布饼图） */
export const usageDailyAgents = (month: string, userId?: string, agentCode?: string) => {
  const q = new URLSearchParams({ month });
  if (userId) q.set('userId', userId);
  if (agentCode) q.set('agentCode', agentCode);
  return request(`/admin/token/usage/daily-agents?${q}`);
};
/** 功能点调用次数（用户端·终端管理功能入口，按月份） */
export const featureUsage = (month?: string) =>
  request(`/admin/token/feature-usage${month ? `?month=${encodeURIComponent(month)}` : ''}`);

/* ---------------- 工具 ---------------- */

export const listTools = () => request<any[]>('/admin/tool/list');
export const toolTemplate = () => request('/admin/tool/template');
export const dbSchema = () => request('/admin/tool/db-schema');

/**
 * 校验工具定义。dryRun=true 会真的执行一次 SQL（哑值参数），
 * 能抓到列名写错这类问题 —— 这是「创建时就能发现问题」的关键。
 */
export const validateTool = (payload: unknown, dryRun = true) =>
  request(`/admin/tool/validate?dryRun=${dryRun}`, {
    method: 'POST',
    body: JSON.stringify(payload),
  });

export const createTool = (payload: unknown) =>
  request('/admin/tool?dryRun=true', { method: 'POST', body: JSON.stringify(payload) });
export const updateTool = (id: number | string, payload: unknown) =>
  request(`/admin/tool/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
export const deleteTool = (id: number | string) =>
  request(`/admin/tool/${id}`, { method: 'DELETE' });

/* ---------------- 运行时与审计 ---------------- */

export const introspect = (code: string) => request(`/agent/${code}/introspect`);
export const recentToolCalls = (limit = 30) =>
  request(`/admin/audit/recent?limit=${limit}`);
export const toolCallStats = () => request('/admin/audit/stats');
