import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as auth from '../auth';
import { authEvents, resetAuthEvents } from '../test-support/dom';

/**
 * 登录态与登录门（对应功能点：管理台登录、演示账号只读、401 统一登出）。
 *
 * 这几条都是「错了就会静默放错人进来」的逻辑，必须锁死：
 * 非 ADMIN 不能进、坏 JSON 不能崩、401 必须清态并广播事件。
 */
const KEY = 'dh_admin_auth';

const ADMIN = {
  token: 'tok-admin',
  userCode: 'admin',
  userName: '管理员',
  role: 'ADMIN' as const,
};

function mockLoginResponse(body: unknown, status = 200) {
  const fetchMock = vi.fn().mockResolvedValue(
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  );
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('auth 登录态', () => {
  beforeEach(() => {
    localStorage.clear();
    resetAuthEvents();
    vi.unstubAllGlobals();
  });

  it('无存储时 getAuth 返回 null', () => {
    expect(auth.getAuth()).toBeNull();
    expect(auth.getToken()).toBeNull();
  });

  it('坏 JSON 不抛异常（历史用户可能存了脏数据）', () => {
    localStorage.setItem(KEY, '{not json');
    expect(auth.getAuth()).toBeNull();
  });

  it('setAuth 写入后 getToken 可取到，置空后清掉', () => {
    auth.setAuth(ADMIN);
    expect(auth.getToken()).toBe('tok-admin');
    auth.setAuth(null);
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it('admin 是演示账号（只读），其他账号不是', () => {
    auth.setAuth(ADMIN);
    expect(auth.isDemoAccount()).toBe(true);

    auth.setAuth({ ...ADMIN, userCode: 'svc_orchestrator' });
    expect(auth.isDemoAccount()).toBe(false);
  });

  it('handleUnauthorized 清态并广播 auth:expired', () => {
    auth.setAuth(ADMIN);
    auth.handleUnauthorized();
    expect(auth.getAuth()).toBeNull();
    expect(authEvents).toContain('auth:expired');
  });

  it('login 成功后落地登录态', async () => {
    mockLoginResponse(ADMIN);
    const user = await auth.login('admin', '123456');
    expect(user.userCode).toBe('admin');
    expect(auth.getToken()).toBe('tok-admin');
  });

  it('非 ADMIN 角色即使密码正确也拒绝进入', async () => {
    mockLoginResponse({ ...ADMIN, role: 'USER' });
    await expect(auth.login('xn_zg', '123456')).rejects.toThrow('不是管理员');
    expect(auth.getAuth()).toBeNull();
  });

  it('后端返回 error 时抛人话', async () => {
    mockLoginResponse({ error: '账号或密码错误' }, 401);
    await expect(auth.login('admin', 'bad')).rejects.toThrow('账号或密码错误');
  });

  it('没有 error 字段时用 HTTP 状态兜底', async () => {
    mockLoginResponse({}, 500);
    await expect(auth.login('admin', 'x')).rejects.toThrow('HTTP 500');
  });

  it('logout 无论后端成败都清登录态', async () => {
    auth.setAuth(ADMIN);
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('网络断了')));
    await auth.logout();
    expect(auth.getAuth()).toBeNull();
  });
});
