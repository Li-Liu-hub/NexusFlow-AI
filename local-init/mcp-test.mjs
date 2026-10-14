#!/usr/bin/env node
/**
 * MCP 通道端到端验证（经网关，Bearer token 鉴权）
 *
 * 流程：SSE 建流拿 sessionId → POST initialize → POST tools/list → POST tools/call
 * 依据：gateway 路由 /sse,/mcp/** → aismp-agent:8124（spring-ai-mcp-server-webmvc）
 */
const GW = process.env.GATEWAY_BASE_URL || 'http://127.0.0.1:8090';
const TOKEN = process.env.MCP_TOKEN || 'local-mcp-token-2026';

const messages = [];   // 收到的 SSE data 帧

// 1) 建立 SSE 流
const sseResp = await fetch(`${GW}/sse`, { headers: { Authorization: `Bearer ${TOKEN}` } });
if (!sseResp.ok) { console.error(`SSE 建流失败 status=${sseResp.status}`); process.exit(1); }

const reader = sseResp.body.getReader();
const decoder = new TextDecoder();
let sessionId = null;
let buf = '';
const done = new Promise((resolve) => {
  (async () => {
    for (;;) {
      const { value, done: fin } = await reader.read();
      if (fin) return resolve();
      buf += decoder.decode(value, { stream: true });
      let i;
      while ((i = buf.indexOf('\n\n')) !== -1) {
        const frame = buf.slice(0, i); buf = buf.slice(i + 2);
        const ev = (frame.match(/^event:(.*)$/m) || [])[1]?.trim();
        const da = (frame.match(/^data:(.*)$/m) || [])[1]?.trim();
        if (ev === 'endpoint' && da) sessionId = da;
        if (ev === 'message' && da) {
          messages.push(da);
          if (messages.length >= 3) return resolve();
        }
      }
    }
  })();
});

for (let i = 0; i < 40 && !sessionId; i++) await new Promise((r) => setTimeout(r, 100));
if (!sessionId) { console.error('未拿到 sessionId'); process.exit(1); }
console.log(`✅ SSE 建流成功，sessionId=${sessionId.split('=')[1]}`);

async function rpc(method, params, id) {
  const r = await fetch(`${GW}${sessionId}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${TOKEN}` },
    body: JSON.stringify({ jsonrpc: '2.0', id, method, params }),
  });
  console.log(`   POST ${method} → HTTP ${r.status}`);
  await new Promise((res) => setTimeout(res, 1200));
}

// 2) MCP 握手
await rpc('initialize', {
  protocolVersion: '2024-11-05',
  capabilities: {},
  clientInfo: { name: 'e2e-client', version: '1.0.0' },
}, 1);
await rpc('notifications/initialized', {}, undefined);
await rpc('tools/list', {}, 2);
await rpc('tools/call', { name: 'queryMyPermission', arguments: {} }, 3);

await Promise.race([done, new Promise((r) => setTimeout(r, 5000))]);
try { await reader.cancel(); } catch { /* ignore */ }

console.log('\n=== 收到的 MCP 响应 ===');
let toolNames = [];
for (const m of messages) {
  try {
    const j = JSON.parse(m);
    if (j.result?.tools) {
      toolNames = j.result.tools.map((t) => t.name);
      console.log(`  tools/list → 暴露 ${toolNames.length} 个工具：${toolNames.join(', ')}`);
    } else if (j.result?.content) {
      console.log(`  tools/call → ${JSON.stringify(j.result.content).slice(0, 300)}`);
    } else if (j.result?.serverInfo) {
      console.log(`  initialize → ${j.result.serverInfo.name} v${j.result.serverInfo.version}`);
    } else {
      console.log(`  ${m.slice(0, 200)}`);
    }
  } catch { console.log(`  ${m.slice(0, 200)}`); }
}

const ok = toolNames.length > 0;
console.log(`\n${ok ? '✅ MCP 通道验证通过' : '❌ MCP 通道未返回工具列表'}`);
process.exit(ok ? 0 : 1);
