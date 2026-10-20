// 数字员工内核接口封装。
//
// 开发期经 Vite 代理把 /api 转发到网关 8090（见 vite.config.ts），
// 生产期由 nginx 做同样的转发 —— 前端代码两种环境下都不用改。
//
// 类型策略：网络边界强类型，返回值先泛型占位（页面 DTO 随页面收紧）。

const BASE = '/api';

/** 默认数字员工（后续接入多数字员工后，这里改成选择器） */
export const DEFAULT_AGENT = 'terminal_manager';

/** 当前用户（登录体系接入后由登录态提供） */
export const DEFAULT_USER = 'web_user';

async function request<T = any>(path: string, options?: RequestInit): Promise<T> {
  const resp = await fetch(BASE + path, options);
  if (!resp.ok) {
    throw new Error(`HTTP ${resp.status} ${resp.statusText}`);
  }
  const data = await resp.json();
  if (data && data.error) {
    throw new Error(data.error);
  }
  return data as T;
}

/** 会话列表（按最后活跃时间倒序） */
export function listConversations(limit = 50): Promise<any[]> {
  return request<any[]>(`/agent/conversation/list?userId=${DEFAULT_USER}&limit=${limit}`);
}

/** 某会话的完整消息（打开历史会话时回显） */
export function getConversation(conversationId: string): Promise<any> {
  return request(`/agent/conversation/${conversationId}`);
}

/** 归档会话 */
export function archiveConversation(conversationId: string): Promise<any> {
  return request(`/agent/conversation/${conversationId}/archive`, { method: 'POST' });
}

/**
 * 发一句话给数字员工。
 *
 * 带上 conversationId 时后端会回放该会话历史，
 * 所以「那它呢」这类依赖上文的追问能接住。
 */
export function chat(message: string, conversationId?: string): Promise<any> {
  return request(`/agent/${DEFAULT_AGENT}/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      message,
      conversationId,
      userId: DEFAULT_USER,
    }),
  });
}

/** 数字员工运行时自省（看它此刻装了什么技能与工具） */
export function introspect(): Promise<any> {
  return request(`/agent/${DEFAULT_AGENT}/introspect`);
}
