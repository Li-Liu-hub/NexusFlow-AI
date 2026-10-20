// 登录态管理：token + 用户信息存 localStorage，供 api.ts 自动附加请求头。
//
// 与内核 /auth/** 对齐：POST /auth/login 换 token（24h），
// 401 统一清登录态并派发 `auth:expired` 事件，由 App.tsx 跳回登录页。
import type { AuthUser } from './types';

const KEY = 'dh_admin_auth';

/** 演示账号（登录卡下方展示的账号）：只可查看，增删改一律拦截 */
const DEMO_USER_CODES: string[] = ['admin'];
export const DEMO_HINT = '该账号为演示账号，仅支持查看，无法进行增删改操作';

export function isDemoAccount(): boolean {
  const a = getAuth();
  return !!a && DEMO_USER_CODES.includes(a.userCode);
}

export function getAuth(): AuthUser | null {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as AuthUser) : null;
  } catch {
    return null;
  }
}

export function setAuth(auth: AuthUser | null): void {
  if (auth) localStorage.setItem(KEY, JSON.stringify(auth));
  else localStorage.removeItem(KEY);
}

export function getToken(): string | null {
  return getAuth()?.token || null;
}

/** 401 统一出口：清登录态 + 通知 App 回登录页。 */
export function handleUnauthorized(): void {
  setAuth(null);
  window.dispatchEvent(new CustomEvent('auth:expired'));
}

export async function login(userCode: string, password: string): Promise<AuthUser> {
  const resp = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userCode, password }),
  });
  const data = await resp.json();
  if (!resp.ok || data.error) {
    throw new Error(data.error || `登录失败（HTTP ${resp.status}）`);
  }
  if (data.role !== 'ADMIN') {
    throw new Error('该账号不是管理员，请用管理台账号登录');
  }
  setAuth(data as AuthUser);
  return data as AuthUser;
}

export async function logout(): Promise<void> {
  try {
    await fetch('/api/auth/logout', {
      method: 'POST',
      headers: { Authorization: `Bearer ${getToken() || ''}` },
    });
  } catch {
    // 登出失败不阻塞
  }
  setAuth(null);
}
