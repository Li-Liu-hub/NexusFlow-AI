#!/usr/bin/env node
/**
 * agent-platform 本机端到端验证
 *
 * 覆盖链路：
 *   1. 网关 AK/SK 签名鉴权 + 路由 → aismp-interface(8123)
 *   2. 网关 → aismp-agent(8124) 知识型问答（RAG，pgvector + bge-m3）
 *   3. 网关 → aismp-agent(8124) 查询型问答（工具调用，PG 业务表）
 *   4. 网关 → agent-orchestrator(8125) LangGraph 编排 SSE
 *   5. 负向用例：错误签名应被网关 403
 *
 * 用法： node e2e-test.mjs
 */
import crypto from 'node:crypto';

const GW = process.env.GATEWAY_BASE_URL || 'http://127.0.0.1:8090';
const AK = process.env.GATEWAY_ACCESS_KEY || 'aismp-console';
const SK = process.env.GATEWAY_SECRET_KEY || '7c1f9a3e5d0b2846af31c7e9b0d45268';

let pass = 0, fail = 0;
const results = [];

function sha256Hex(s) {
  return crypto.createHash('sha256').update(s, 'utf8').digest('hex');
}

/** 与 SignUtils.java 一致：sign = sha256hex(body + "." + secretKey)；无 body 时用字面量 "null" */
function authHeaders(body, extra = {}) {
  const h = {
    accessKey: AK,
    nonce: crypto.randomBytes(16).toString('hex'),
    timestamp: String(Math.floor(Date.now() / 1000)),
    sign: sha256Hex(`${body === null ? 'null' : body}.${SK}`),
    ...extra,
  };
  if (body !== null) h.body = body;
  return h;
}

/**
 * HTTP header 只能装 ASCII。body 里带中文时不能直接塞进 header，
 * 否则 fetch 抛 "Cannot convert argument to a ByteString"。
 * Java SDK / Python 编排层用的是 \uXXXX 转义（JSON 合法，下游解析后内容不变），这里对齐。
 */
function toAsciiJson(obj) {
  return JSON.stringify(obj).replace(/[^\x00-\x7F]/g, (c) =>
    '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0'));
}

function show(d) {
  return typeof d === 'string' ? d : JSON.stringify(d);
}

async function call(name, expect, fn) {
  process.stdout.write(`\n▶ ${name}\n`);
  try {
    const detail = await fn();
    const ok = expect(detail);
    if (ok) { pass++; console.log(`  ✅ ${show(detail)}`); }
    else { fail++; console.log(`  ❌ 断言未通过：${show(detail)}`); }
    results.push({ name, ok, detail });
    return { ok, detail };
  } catch (e) {
    fail++;
    console.log(`  ❌ 异常：${e.message}`);
    results.push({ name, ok: false, detail: e.message });
    return { ok: false, detail: e.message };
  }
}

// ---------------------------------------------------------------- 1. Mock 接口
await call('1) 网关转发 → aismp-interface：GET /api/name/get', (d) => d.status === 200 && d.text === 'E2E-OK', async () => {
  const url = `${GW}/api/name/get?name=E2E-OK`;
  const r = await fetch(url, { headers: authHeaders(null) });
  const text = (await r.text()).trim();
  if (text !== 'E2E-OK') console.log(`     ↳ 原始响应：${text.slice(0, 300)}`);
  return { status: r.status, text };
});

// ---------------------------------------------------------------- 2. RAG 知识型
await call('2) 网关 → aismp-agent：知识型问答（RAG 检索 pgvector）', (d) => d.status === 200 && d.text.length > 20, async () => {
  const body = toAsciiJson({ message: '我忘记OA系统登录密码了，怎么重置？', conversationId: 'e2e-rag-1' });
  const r = await fetch(`${GW}/oa/chat/sync`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders(body, { 'X-User-Id': 'U1001', 'X-Area-Code': 'JL_CC' }) },
    body,
  });
  const text = await r.text();
  console.log(`     ↳ 回答：${text.slice(0, 300).replace(/\n/g, ' ')}`);
  return { status: r.status, text };
});

// ---------------------------------------------------------------- 3. 工具调用型
await call('3) 网关 → aismp-agent：查询型问答（Tool 查 PG 业务表）', (d) => d.status === 200 && d.text.length > 10, async () => {
  const body = toAsciiJson({ message: '我有什么权限？我是哪个地市的？', conversationId: 'e2e-tool-1' });
  const r = await fetch(`${GW}/oa/chat/sync`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders(body, { 'X-User-Id': 'U1001', 'X-Area-Code': 'JL_CC' }) },
    body,
  });
  const text = await r.text();
  console.log(`     ↳ 回答：${text.slice(0, 300).replace(/\n/g, ' ')}`);
  return { status: r.status, text };
});

// ---------------------------------------------------------------- 4. 编排层 SSE
await call('4) 网关 → agent-orchestrator：LangGraph 编排 SSE', (d) => d.status === 200 && d.events.length > 0, async () => {
  const q = encodeURIComponent('工单提交后多久能审批完？');
  const r = await fetch(`${GW}/orchestrator/v1/chat/stream?query=${q}`, {
    method: 'POST',
    headers: authHeaders(null),
  });
  const text = await r.text();
  const events = text.split('\n').filter((l) => l.startsWith('event:')).map((l) => l.slice(6).trim());
  const nodeLines = text.split('\n').filter((l) => l.startsWith('data:'));
  if (!events.length) console.log(`     ↳ 原始响应：${text.slice(0, 400)}`);
  console.log(`     ↳ 事件流：${events.join(' → ') || '(空)'}`);
  nodeLines.forEach((l) => console.log(`       ${l.slice(0, 200)}`));
  const resultLine = text.split('\n').filter((l) => l.startsWith('data:') && !l.includes('"node"') && !l.includes('seq'));
  if (resultLine.length) console.log(`     ↳ 最终回答：${resultLine[resultLine.length - 1].slice(0, 300)}`);
  return { status: r.status, events, text };
});

// ---------------------------------------------------------------- 5. 负向用例
await call('5) 负向：错误签名应被网关拒绝(403)', (d) => d.status === 403, async () => {
  const u = `${GW}/api/name/get?name=x`;
  const h = authHeaders(null);
  h.sign = 'deadbeef'.repeat(8);
  const r = await fetch(u, { headers: h });
  return { status: r.status };
});

console.log(`\n${'='.repeat(60)}\n通过 ${pass} / 失败 ${fail}\n${'='.repeat(60)}`);
process.exit(fail === 0 ? 0 : 1);
