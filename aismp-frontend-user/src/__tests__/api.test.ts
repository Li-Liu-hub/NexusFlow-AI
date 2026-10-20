import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from '../api';

/**
 * 用户端请求封装（对应功能点：会话列表 / 会话回放 / 归档 / 对话 / 自省）。
 *
 * 这里锁的是 URL 与请求体形状 —— 后端改一个字就会 404/400，
 * 而这类错误在页面上只表现为「列表一直是空的」，很难看出是路径错了。
 */
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

describe('user api', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  it('会话列表按用户 + 条数拼参数', async () => {
    const f = stubFetch([]);
    await api.listConversations(10);
    expect(lastCall(f).url).toBe(`/api/agent/conversation/list?userId=${api.DEFAULT_USER}&limit=10`);
  });

  it('会话详情走路径参数', async () => {
    const f = stubFetch({ messages: [] });
    await api.getConversation('orch-abcd1234');
    expect(lastCall(f).url).toBe('/api/agent/conversation/orch-abcd1234');
  });

  it('归档是 POST', async () => {
    const f = stubFetch({});
    await api.archiveConversation('orch-1');
    const { url, init } = lastCall(f);
    expect(url).toBe('/api/agent/conversation/orch-1/archive');
    expect(init.method).toBe('POST');
  });

  it('对话请求体带 message / conversationId / userId', async () => {
    const f = stubFetch({ answer: '好的' });
    const res = await api.chat('哪个仓该补货', 'orch-1');

    const { url, init } = lastCall(f);
    expect(url).toBe(`/api/agent/${api.DEFAULT_AGENT}/chat`);
    expect(init.method).toBe('POST');
    expect(JSON.parse(String(init.body))).toEqual({
      message: '哪个仓该补货',
      conversationId: 'orch-1',
      userId: api.DEFAULT_USER,
    });
    expect(res.answer).toBe('好的');
  });

  it('自省走 GET', async () => {
    const f = stubFetch({});
    await api.introspect();
    expect(lastCall(f).url).toBe(`/api/agent/${api.DEFAULT_AGENT}/introspect`);
  });

  it('非 2xx 抛 HTTP 错误', async () => {
    stubFetch({}, 502);
    await expect(api.listConversations()).rejects.toThrow('HTTP 502');
  });

  it('body.error 优先抛业务错误', async () => {
    stubFetch({ error: '会话不存在' }, 404);
    await expect(api.getConversation('x')).rejects.toThrow('会话不存在');
  });
});
