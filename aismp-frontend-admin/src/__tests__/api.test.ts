import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from '../api';
import * as auth from '../auth';
import { authEvents, resetAuthEvents } from '../test-support/dom';

/**
 * 请求封装（对应功能点：统一鉴权头、401 登出、错误信息透传、multipart 上传）。
 *
 * 这里锁的是「人人都会踩、踩了又很难发现」的几条：
 *  - 401 必须清登录态，否则页面会一直显示已登录但所有请求都失败；
 *  - 后端把人话放在 body.error 里，不能被 "HTTP 403" 盖掉（演示账号只读就是靠这句）；
 *  - 上传不能带 Content-Type: application/json（否则 boundary 丢失，后端解析必失败）。
 */
const ADMIN = { token: 'tok-1', userCode: 'admin', userName: '管理员', role: 'ADMIN' as const };

function stubFetch(body: unknown, status = 200) {
  const fetchMock = vi.fn().mockResolvedValue(
    new Response(typeof body === 'string' ? body : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  );
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function lastCall(fetchMock: ReturnType<typeof vi.fn>) {
  const [url, init] = fetchMock.mock.calls[fetchMock.mock.calls.length - 1];
  return { url: String(url), init: (init ?? {}) as RequestInit };
}

describe('api 请求封装', () => {
  beforeEach(() => {
    localStorage.clear();
    resetAuthEvents();
    vi.unstubAllGlobals();
    auth.setAuth(ADMIN);
  });

  it('自动带 Bearer 头', async () => {
    const f = stubFetch([]);
    await api.listAgents();
    const { url, init } = lastCall(f);
    expect(url).toBe('/api/admin/agent/list');
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer tok-1');
  });

  it('未登录时不带 Authorization', async () => {
    auth.setAuth(null);
    const f = stubFetch([]);
    await api.listAgents();
    expect((lastCall(f).init.headers as Record<string, string>).Authorization).toBeUndefined();
  });

  it('401 清登录态、广播事件并抛出可读错误', async () => {
    stubFetch({}, 401);
    await expect(api.listAgents()).rejects.toThrow('登录已过期');
    expect(auth.getAuth()).toBeNull();
    expect(authEvents).toContain('auth:expired');
  });

  it('后端 error 字段优先于 HTTP 状态码', async () => {
    stubFetch({ error: '该账号为演示账号，仅支持查看' }, 403);
    await expect(api.createAgent({})).rejects.toThrow('演示账号');
  });

  it('HTTP 200 但 body 里带 error 也要抛', async () => {
    stubFetch({ error: '接口未登记' });
    await expect(api.listTools()).rejects.toThrow('接口未登记');
  });

  it('没有 error 时用 HTTP 状态兜底', async () => {
    stubFetch({}, 502);
    await expect(api.listTools()).rejects.toThrow('HTTP 502');
  });

  it('空 body 返回 null（删除类接口的常见返回）', async () => {
    stubFetch('');
    await expect(api.deleteAgent('a1')).resolves.toBeNull();
  });

  it('写操作带 JSON body', async () => {
    const f = stubFetch({ ok: true });
    await api.createAgent({ agentCode: 'x', name: 'X' });
    const { init } = lastCall(f);
    expect(init.method).toBe('POST');
    expect(init.body).toBe(JSON.stringify({ agentCode: 'x', name: 'X' }));
  });

  it('月份参数做了 URL 编码', async () => {
    const f = stubFetch({});
    await api.usageDaily('用户 1', 7);
    expect(lastCall(f).url).toBe('/api/admin/token/usage/daily?userId=%E7%94%A8%E6%88%B7%201&days=7');
  });

  it('上传走 multipart，不带 Content-Type（boundary 必须由浏览器生成）', async () => {
    const f = stubFetch({ chunkCount: 3, embeddingModel: 'bge-m3' });
    const file = new File(['hello'], '手册.txt', { type: 'text/plain' });

    const res = await api.uploadKbDocument(9, file, 'operation');

    const { url, init } = lastCall(f);
    expect(url).toBe('/api/admin/kb/9/documents');
    expect(init.method).toBe('POST');
    expect(init.body).toBeInstanceOf(FormData);
    const headers = (init.headers ?? {}) as Record<string, string>;
    expect(headers['Content-Type']).toBeUndefined();
    expect(headers.Authorization).toBe('Bearer tok-1');
    expect((init.body as FormData).get('category')).toBe('operation');
    expect(res.chunkCount).toBe(3);
  });

  it('上传遇到 403 时把后端人话抛出去', async () => {
    stubFetch({ error: '该账号为演示账号，仅支持查看' }, 403);
    await expect(
      api.uploadKbDocument(9, new File(['x'], 'a.txt'), undefined),
    ).rejects.toThrow('演示账号');
  });
});
