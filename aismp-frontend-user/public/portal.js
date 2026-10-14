/* ================================================================
 * 终端 AI 智慧管理平台 · 门户前端逻辑（原生 JS，无框架）
 *
 *  - 登录鉴权：/api/auth/login → token 存 localStorage，请求带 Bearer
 *  - 智能对话（唯一入口，2026-10-07 起）：/api/orchestrator/**
 *      —— 不再手选数字员工/路线，全部由编排层意图识别分流：
 *         ① 生成图表 ② 补货建议并形成调拨单 ③ 知识问答（数字员工内核）
 *      AI助手页仅展示数字员工清单（人设/技能由管理台配置，被调度时自动装配）
 *  - 调拨工单闭环：/api/terminal/**、/api/transferOrder/**（建单→发送→账管→仓管→完成）
 *  - 库存告警：编排层 scan→判定→notify（通知面板「扫描本仓」= /api/orchestrator/v1/alert/scan）
 * ================================================================ */
(function () {
  'use strict';

  const AUTH_KEY = 'portal_auth';
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

  let AUTH = null;                 // 登录身份对象（含 token/userCode/warehouseCode/role…）
  let NOTICES = [];                // 通知缓存

  /* ================= 自诊断：错误不白屏，弹 toast ================= */
  function toast(msg, type) {
    try {
      const t = document.createElement('div');
      t.className = 'toast' + (type === 'err' ? ' err' : '');
      t.textContent = msg;
      document.body.appendChild(t);
      setTimeout(() => t.remove(), 4200);
    } catch (ignored) { /* 自诊断不能再抛错 */ }
  }
  window.addEventListener('error', (e) => toast('[脚本错误] ' + e.message + ' @' + e.lineno, 'err'));
  window.addEventListener('unhandledrejection', (e) => toast('[请求异常] ' + (e.reason && e.reason.message ? e.reason.message : e.reason), 'err'));

  /* ================= 鉴权 ================= */
  function loadAuth() {
    try { return JSON.parse(localStorage.getItem(AUTH_KEY)) || null; } catch (ignored) { return null; }
  }
  function showLogin(msg) {
    AUTH = null;
    resetScope();
    $('loginErr').textContent = msg || '';
    $('loginMask').hidden = false;
    $('loginUser').focus();
  }
  function afterLogin(a) {
    AUTH = a;
    resetScope();   // 可见范围按登录用户重算（切换账号不能沿用上一个账号的缓存）
    localStorage.setItem(AUTH_KEY, JSON.stringify(a));
    fillUserCard();
    $('greetName').textContent = a.userName || a.userCode || '';
    $('loginMask').hidden = true;
    loadStats();
    loadNotices();
    // 2026-10-10：未读通知主动弹窗 —— 定时轮询（60s）。只挂一次，避免重复 interval。
    if (!window.__notifPoll) { window.__notifPoll = setInterval(loadNotices, 60000); }
    loadConversations();
    showView('chat');
    setNavActive('chat');
    resetChat();
  }
  async function authFetch(url, options) {
    const auth = AUTH || loadAuth();
    const resp = await fetch(url, {
      ...(options || {}),
      headers: {
        ...(options && options.headers || {}),
        ...(auth && auth.token ? { Authorization: 'Bearer ' + auth.token } : {}),
      },
    });
    if (resp.status === 401) {
      localStorage.removeItem(AUTH_KEY);
      showLogin('登录已过期，请重新登录');
      throw new Error('未登录或登录已过期');
    }
    return resp;
  }
  // 统一响应体：{code:0,data:...,message:"ok"}
  async function readData(resp) {
    const d = await resp.json().catch(() => ({}));
    if (!resp.ok) throw new Error((d && d.message) || ('HTTP ' + resp.status));
    if (d && d.code !== undefined && d.code !== 0) throw new Error(d.message || ('业务错误 ' + d.code));
    return d && d.data !== undefined ? d.data : d;
  }
  async function apiGet(url) { return readData(await authFetch(url)); }
  async function apiPost(url, body) {
    return readData(await authFetch(url, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}),
    }));
  }
  async function apiPut(url, body) {
    return readData(await authFetch(url, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}),
    }));
  }
  async function apiDelete(url) { return readData(await authFetch(url, { method: 'DELETE' })); }

  /* ================= 登录表单 ================= */
  const DEMO_ACCOUNTS = [
    ['portal_user', '演示账号'],
    ['xn_zg', '新区账管'],
    ['xn_kg', '新区仓管'],
    ['dn_zg', '东宁账管'],
    ['dn_kg', '东宁仓管'],
  ];
  $('loginHint').innerHTML = DEMO_ACCOUNTS
    .map(([u, n]) => '<span class="hint-acc" data-u="' + u + '">' + u + '（' + n + '）</span>').join('');
  $('loginHint').addEventListener('click', (e) => {
    const el = e.target.closest('.hint-acc');
    if (!el) return;
    $('loginUser').value = el.dataset.u;
    $('loginPass').value = '123456';
  });
  $('loginForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const u = $('loginUser').value.trim();
    const p = $('loginPass').value;
    if (!u || !p) { $('loginErr').textContent = '请输入账号和密码'; return; }
    $('loginBtn').disabled = true;
    $('loginErr').textContent = '';
    try {
      const resp = await fetch('/api/auth/login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userCode: u, password: p }),
      });
      const d = await resp.json().catch(() => ({}));
      if (!resp.ok || d.error) {
        $('loginErr').textContent = d.error || ('登录失败（HTTP ' + resp.status + '）');
        return;
      }
      afterLogin(d);
    } catch (err) {
      $('loginErr').textContent = '登录请求失败：' + err.message;
    } finally {
      $('loginBtn').disabled = false;
    }
  });
  $('logoutBtn').addEventListener('click', async () => {
    const auth = loadAuth();
    try {
      await fetch('/api/auth/logout', { method: 'POST', headers: { Authorization: 'Bearer ' + ((auth && auth.token) || '') } });
    } catch (ignored) { /* 忽略 */ }
    localStorage.removeItem(AUTH_KEY);
    $('notifPanel').hidden = true;
    showLogin('已退出登录');
  });

  function fillUserCard() {
    if (!AUTH) return;
    const name = AUTH.userName || AUTH.userCode || '—';
    $('userName').textContent = name;
    $('userRole').textContent = (AUTH.warehouseName || '') + (AUTH.roleLabel || AUTH.role || '');
    $('userAvatar').textContent = name.slice(0, 1);
  }

  /* ================= 视图切换 ================= */
  function showView(name) {
    $('viewChat').hidden = name !== 'chat';
    $('viewOrders').hidden = name !== 'orders';
    $('viewPage').hidden = name !== 'page';
  }
  function setNavActive(view) {
    document.querySelectorAll('.nav-item').forEach((n) => n.classList.toggle('active', n.dataset.view === view));
  }
  function handleNav(view) {
    if (view === 'chat') { setNavActive('chat'); showView('chat'); }
    else if (view === 'orders') { setNavActive('orders'); showView('orders'); loadOrders(ordersTab); }
    else if (view === 'terminal') { setNavActive('terminal'); showView('page'); renderBizHub(); }
  }
  $('nav').addEventListener('click', (e) => {
    const el = e.target.closest('.nav-item');
    if (el) handleNav(el.dataset.view);
  });

  /* ================= 统计卡（本仓库存快照） ================= */
  function fmtNum(v) {
    if (v == null || v === '') return null;
    const n = Number(v);
    return isNaN(n) ? String(v) : n.toLocaleString('en-US');
  }
  function setStat(id, v) {
    const f = fmtNum(v);
    $(id).innerHTML = f == null ? '—' : (f + '<small>台</small>');
  }
  /** 可见范围内的库存汇总（本仓 + 下级仓）：逐仓取快照求和 —— 聊天统计卡与业务首页共用 */
  async function scopedStats() {
    const scope = await visibleWarehouses();
    const codes = scope ? Array.from(scope) : [null];   // null = 无仓库归属账号，取全局
    const snaps = await Promise.all(codes.map((c) =>
      apiGet('/api/terminal/inventory/snapshot' + (c ? '?warehouseCode=' + encodeURIComponent(c) : ''))));
    const sum = (k) => snaps.reduce((acc, d) => acc + (Number(d && d[k]) || 0), 0);
    return { scope, codes, total: sum('totalTerminals'), avail: sum('totalAvailable'),
             aging: sum('longAgingCount'), transit: sum('totalInTransit'),
             note: scope && codes.length > 1 ? ('统计范围：本仓及 ' + (codes.length - 1) + ' 个下级仓') : '' };
  }

  /** 统计卡：按可见范围（本仓 + 下级仓）汇总 —— 基层只看自己，上级看整棵子树 */
  async function loadStats() {
    if (!AUTH) return;
    try {
      const s = await scopedStats();
      setStat('sTotal', s.total);
      setStat('sAvail', s.avail);
      setStat('sAging', s.aging);
      setStat('sTransit', s.transit);
      $('statNote').textContent = s.note;
    } catch (e) {
      ['sTotal', 'sAvail', 'sAging', 'sTransit'].forEach((id) => setStat(id, null));
      $('statNote').textContent = '统计加载失败：' + e.message;
    }
  }

  /* ================= 快捷 chips ================= */
  const QUICK = [
    { t: '智能问答', i: '✨', act: 'ask' },
    // 2026-10-07 收敛：补货建议统一走编排层（产出「调拨+采购」双清单，可增删改/导出/申请调拨）；
    //   原直连 agent 的确定性快查改名「库存健康」，收进「更多」（见 renderMoreChips）
    { t: '补货建议', i: '🛒', act: 'replenish' },
    { t: '库存管理', i: '📦', act: 'inv' },
    { t: '数据分析', i: '📈', act: 'ana' },
    { t: '风险稽核', i: '🛡', act: 'risk' },
    { t: '更多', i: '···', act: 'more' },
  ];
  function renderQuick() {
    $('quickChips').innerHTML = QUICK.map((c) =>
      '<span class="qchip" data-act="' + c.act + '"><span class="ico">' + c.i + '</span>' + c.t + '</span>').join('');
  }
  $('quickChips').addEventListener('click', (e) => {
    const c = e.target.closest('.qchip');
    if (c) quickAction(c.dataset.act);
  });
  function quickAction(a) {
    showView('chat'); setNavActive('chat');
    if (a === 'replenish') return aAsk('给我一份补货建议');   // 统一入口：走编排层意图识别 → replenish 路线
    if (a === 'health') return runHealthCheck();
    if (a === 'ask') { $('chatInput').focus(); return; }
    if (a === 'inv') return aAsk('当前库存情况怎么样？');
    if (a === 'ana') return aAsk('给我一份库存数据分析');
    if (a === 'risk') return aAsk('当前有哪些库存风险需要关注？');
    if (a === 'more') return renderMoreChips();
  }
  function renderMoreChips() {
    if ($('moreChips')) return;
    const d = document.createElement('div');
    d.className = 'chips-row';
    d.id = 'moreChips';
    const items = [
      ['长库龄明细', 'ask', '长库龄终端有哪些？'],
      ['在途调拨', 'ask', '当前有哪些在途调拨？'],
      ['库存健康', 'health', ''],   // 确定性快查（直连 agent，不走编排；原「补货建议」入口）
      ['调拨工单', 'orders', ''],
      ['通知', 'notif', ''],
    ];
    d.innerHTML = items.map((x) =>
      '<span class="qchip" data-act="' + x[1] + '" data-q="' + esc(x[2]) + '"><span class="ico">·</span>' + x[0] + '</span>').join('');
    $('quickChips').after(d);
    d.addEventListener('click', (e) => {
      const c = e.target.closest('.qchip');
      if (!c) return;
      if (c.dataset.act === 'orders') handleNav('orders');
      else if (c.dataset.act === 'notif') $('notifPanel').hidden = false;
      else if (c.dataset.act === 'health') runHealthCheck();
      else aAsk(c.dataset.q);
    });
  }

  /* ================= 智能对话（唯一入口：编排层意图识别分流） =================
   * 2026-10-07：移除数字员工选择器与角色筛选 —— 不再手动挑员工/路线。
   * 所有提问统一发编排层，由其意图识别决定走哪条路线：
   * ① 生成报表（图表）② 补货建议并形成调拨单 ③ 知识问答（数字员工内核）。
   * AI助手/知识库/工具箱属管理端能力，用户端不再提供（管理端 5174 维护）。
   */
  let aBusy = false;
  // 【2026-10-11 卡死事故】进行中的对话流：切换/新建会话必须先中断 + 复位忙碌态。
  // 原实现三处漏洞（"流未结束就切会话"时全部触发）：
  //   ① aBusy 只在与服务端正常结束时复位 —— 中断场景永不复位，新会话里点发送毫无反应（即"卡死"）；
  //   ② 旧流继续往已清空的界面追加事件（串台），并把 CONV_ID 改写回旧会话；
  //   ③ SSE 连接不释放 —— 浏览器同域连接池（HTTP/1.1 上限 6 条）被占满，后续所有请求排队。
  let CHAT_ABORT = null;
  // 【2026-10-12 首轮必失败修复】会话代次：用户**主动**切换/新建会话时递增。
  // 原实现在流循环里用「CONV_ID 是否等于发起时的值」判断用户有没有切走，但新会话第一轮的
  // start 帧会把 CONV_ID 由 null 回填成本轮 id —— 下一块数据到达时被判成"用户已切会话"→ break，
  // 结果被整块丢弃（result 恒为 null）→ 前端显示「连接中断（服务端未返回结果）」。
  // 现象：每个新会话的第一次提问必失败，原样重发一次就成功（此时 CONV_ID 已就位）。
  let CONV_EPOCH = 0;
  function abortChatStream() {
    CONV_EPOCH++;   // 中断当前流 = 该轮的渲染基准作废（服务端回填 id 不算，故用代次而非 CONV_ID）
    if (CHAT_ABORT) {
      try { CHAT_ABORT.abort(); } catch (ignored) { /* 忽略 */ }
      CHAT_ABORT = null;
    }
    aBusy = false;
    const btn = document.getElementById('sendBtn');
    if (btn) btn.disabled = false;
  }

  // 快捷示例：三条路线各一个
  const SUG_CHIPS = ['各仓库存分布', '给我一份补货建议', '终端领用要走什么流程？'];
  const WELCOME = '你好，我是终端 AI 智慧管理平台助手。直接说需求，'
    + '我会先识别意图，再调度对应能力：生成报表 / 补货建议并形成调拨单 / 流程问答。';

  /* ---- 数据可见范围（2026-10-07）：本仓 + 所有下级仓（四级组织通用） ---- *
   * 规则：基层用户只能看本仓；上一级可看本仓与全部下级，以此类推。
   * 无仓库归属的账号（如演示账号）不限制。                                        */
  let SCOPE = null;          // Set<warehouseCode> 或 null（未加载/不限制）
  let SCOPE_LOADED = false;
  const WH_NAMES = {};   // 仓库编码 → 名称（工单派生描述名用，2026-10-09）
  function resetScope() { SCOPE = null; SCOPE_LOADED = false; }   // 切换账号必须重置
  async function visibleWarehouses() {
    const me = myWh();
    if (!me) { SCOPE = null; SCOPE_LOADED = true; return null; }
    if (SCOPE_LOADED) return SCOPE;
    try {
      const list = await apiGet('/api/terminal/warehouse/list');
      const children = {};   // parentCode → [childCode]
      for (const w of list || []) {
        if (w.warehouseCode) WH_NAMES[w.warehouseCode] = w.name || '';
        const p = w.parentCode || '';
        (children[p] = children[p] || []).push(w.warehouseCode);
      }
      const set = new Set();
      const stack = [me];
      while (stack.length) {
        const c = stack.pop();
        if (set.has(c)) continue;
        set.add(c);
        (children[c] || []).forEach((x) => stack.push(x));
      }
      SCOPE = set;
    } catch (e) {
      SCOPE = new Set([me]);   // 加载失败按最严口径：只看本仓
    }
    SCOPE_LOADED = true;
    return SCOPE;
  }

  /* ================= 仓位下拉选项（2026-10-10） =================
   * 建议调拨清单的调出/调入仓用可搜索下拉（原生 datalist，输入即过滤）。
   * 取全量仓 —— 调出仓可能在用户可见范围外（比如省仓有富余库存）。 */
  let WH_OPTIONS = null;
  async function warehouseOptions() {
    if (WH_OPTIONS) return WH_OPTIONS;
    try {
      const list = await apiGet('/api/terminal/warehouse/list');
      WH_OPTIONS = (list || []).filter((w) => w.warehouseCode)
        .map((w) => ({ code: w.warehouseCode, name: w.name || '', level: w.level }));
    } catch (e) { WH_OPTIONS = []; }
    return WH_OPTIONS;
  }
  /** 仓编码 → 下拉显示值（"编码 名称"） */
  function whLabel(code) {
    const c = (code || '').trim();
    if (!c) return '';
    return c + (WH_NAMES[c] ? ' ' + WH_NAMES[c] : '');
  }
  /** 下拉显示值 → 仓编码（取第一个空格前的部分；编码与名称都不含空格） */
  function parseWh(v) {
    const s = (v || '').trim();
    return s ? s.split(/\s+/)[0] : '';
  }
  const inScope = (code) => !SCOPE || SCOPE.has(code);

  /** 重置对话（登录后 / 新建对话）：清空并给欢迎语 */
  function resetChat() {
    $('msgList').innerHTML = '';
    // 版本号随每次前端修复递增（配合 portal.html 的 ?v= 与 nginx 的 no-cache）：
    // 页面上一眼可见，避免"改了但浏览器还在用旧文件"这类问题再浪费排查时间。
    aAdd('a', WELCOME, '所有提问统一由编排层意图识别分流；数据范围＝本仓及其下级仓 · 前端 v20261012a');
  }

  /* ---- 历史会话（2026-10-07）：编排层把每轮问答落 PG 的会话表+消息表，这里读回来 ---- */
  let CONV_ID = null;   // 当前会话 id（续聊时随请求回传，服务端据此归档）

  async function loadConversations() {
    if (!AUTH) return;
    try {
      const rows = await apiGet('/api/orchestrator/v1/conversation/list?user_id='
        + encodeURIComponent(AUTH.userCode || '') + '&limit=20');
      renderConversations(Array.isArray(rows) ? rows : []);
    } catch (e) {
      renderConversations([]);
    }
  }
  function renderConversations(rows) {
    // 2026-10-08 CRUD 改版：DOM 构建（不用 innerHTML 拼接），每条挂「重命名/删除」悬停钮
    const host = $('recentList');
    if (!host) return;
    host.innerHTML = '';
    if (!rows.length) {
      const empty = document.createElement('div');
      empty.className = 'recent-empty';
      empty.textContent = '暂无历史对话';
      host.appendChild(empty);
      return;
    }
    for (const c of rows) {
      const item = document.createElement('div');
      item.className = 'recent-item' + (c.conversation_id === CONV_ID ? ' active' : '');
      item.dataset.cid = c.conversation_id || '';
      item.title = c.title || '';
      const t = document.createElement('div');
      t.className = 't';
      t.textContent = c.title || '(无标题)';
      const d = document.createElement('div');
      d.className = 'd';
      d.textContent = (c.message_count || 0) + ' 条 · ' + String(c.updated_at || '').slice(5, 16);
      const acts = document.createElement('div');
      acts.className = 'recent-acts';
      const bRename = document.createElement('button');
      bRename.className = 'ra-btn';
      bRename.title = '重命名';
      bRename.textContent = '✎';
      const bDel = document.createElement('button');
      bDel.className = 'ra-btn ra-del';
      bDel.title = '删除';
      bDel.textContent = '✕';
      acts.appendChild(bRename);
      acts.appendChild(bDel);
      item.appendChild(t);
      item.appendChild(d);
      item.appendChild(acts);
      item.addEventListener('click', () => openConversation(c.conversation_id));
      bRename.addEventListener('click', (e) => { e.stopPropagation(); startRename(item, c); });
      bDel.addEventListener('click', (e) => { e.stopPropagation(); deleteConversation(c); });
      host.appendChild(item);
    }
  }
  /** 内联重命名：标题换输入框，Enter/失焦保存，Esc 取消（空标题=取消） */
  function startRename(item, c) {
    if (item.querySelector('.recent-rename')) return;
    const t = item.querySelector('.t');
    if (!t) return;
    const input = document.createElement('input');
    input.className = 'recent-rename';
    input.maxLength = 200;
    input.value = c.title || '';
    t.replaceWith(input);
    input.focus();
    input.select();
    let done = false;
    const finish = async (save) => {
      if (done) return;
      done = true;
      const title = input.value.trim();
      if (save && title && title !== (c.title || '')) {
        try {
          await apiPut('/api/orchestrator/v1/conversation/' + encodeURIComponent(c.conversation_id), { title: title });
          toast('已重命名');
        } catch (e) {
          toast('重命名失败：' + e.message, 'err');
        }
      }
      loadConversations();
    };
    input.addEventListener('click', (e) => e.stopPropagation());
    input.addEventListener('keydown', (e) => {
      e.stopPropagation();   // 别把按键漏给全局快捷键（如 ⌘K 新建对话）
      if (e.key === 'Enter') finish(true);
      else if (e.key === 'Escape') finish(false);
    });
    input.addEventListener('blur', () => finish(true));
  }
  /** 删除会话：确认后调删除接口；删的是当前打开的会话则回到新对话 */
  async function deleteConversation(c) {
    if (!window.confirm('删除对话「' + (c.title || '(无标题)') + '」？该对话的全部消息将一并删除。')) return;
    try {
      await apiDelete('/api/orchestrator/v1/conversation/' + encodeURIComponent(c.conversation_id));
      toast('已删除');
      if (c.conversation_id === CONV_ID) newChat();
      else loadConversations();
    } catch (e) {
      toast('删除失败：' + e.message, 'err');
    }
  }
  /** 打开历史会话：回放消息，并把后续提问接到同一会话下 */
  async function openConversation(cid) {
    if (!cid) return;
    showView('chat'); setNavActive('chat');
    try {
      abortChatStream();   // 切换会话：中断旧流 + 复位忙碌态
      const d = await apiGet('/api/orchestrator/v1/conversation/' + encodeURIComponent(cid));
      const list = (d && d.messages) || [];
      CONV_ID = cid;
      $('msgList').innerHTML = '';
      if (!list.length) { aAdd('a', '这个会话还没有可回放的消息。'); }
      else {
        aSysHint('已载入历史会话「' + ((d && d.title) || cid) + '」，继续提问会接在同一会话下');
        for (const m of list) {
          if (m.role === 'user') {
            aAdd('u', esc(m.content || ''));
          } else {
            aAdd('a', md2html(m.content || ''), m.route ? ('意图识别 → ' + (ROUTE_LABEL[m.route] || m.route)) : '');
          }
        }
      }
      $('chatScroll').scrollTop = 0;
      loadConversations();   // 刷新高亮
    } catch (e) {
      toast('加载会话失败：' + e.message, 'err');
    }
  }

  /* ---- 消息渲染（2026-10-08 对话格式改版：用户蓝气泡 + AI 白卡片[思考过程折叠 + 正文 + 操作栏]） ---- */
  function scrollBottom() { const s = $('chatScroll'); s.scrollTop = s.scrollHeight; }

  // 进行中的「思考过程」上下文与卡片挂载点（仅在 aAsk 一轮对话内非空）
  let THINK_CTX = null;    // { headerEl, bodyEl, thinkEl, start, last }
  let CUR_CARDS = null;    // 本轮 AI 卡片的容器（图表/清单/工单卡内嵌到 AI 卡片里）
  function cardHost() { return CUR_CARDS || $('msgList'); }
  /** 卡片统一出口（2026-10-08）：AI 回复进行中挂进当前气泡；由按钮触发的补挂卡片
   *  （草稿/工单卡等）自动包「AI 头像 + 同款 ai-card 气泡」，气泡宽度/内边距与对话完全一致。 */
  function appendCardEl(el) {
    if (CUR_CARDS) {
      CUR_CARDS.appendChild(el);
      scrollBottom();
      return;
    }
    const row = document.createElement('div');
    row.className = 'msg a';
    row.appendChild(aiAvatar());
    const card = document.createElement('div');
    card.className = 'ai-card';
    const box = document.createElement('div');
    box.className = 'ai-cards';
    box.style.marginTop = '0';
    box.appendChild(el);
    card.appendChild(box);
    row.appendChild(card);
    $('msgList').appendChild(row);
    scrollBottom();
  }

  /** 轻量 Markdown 渲染（先 esc 再转换，安全）：标题/加粗/列表/表格/分隔线/段落。 */
  function md2html(text) {
    const inline = (s) => s.replace(/\*\*(.+?)\*\*/g, '<b>$1</b>');
    let html = '', inTable = false, list = null;
    const closeList = () => { if (list) { html += '</' + list + '>'; list = null; } };
    for (const raw of esc(text).split('\n')) {
      const line = raw.trimEnd();
      if (/^\s*\|.*\|\s*$/.test(line)) {
        closeList();
        const cells = line.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim());
        if (/^[-: ]+$/.test(cells.join(''))) continue;
        if (!inTable) { html += '<table>'; inTable = true; }
        html += '<tr>' + cells.map((c) => '<td>' + inline(c) + '</td>').join('') + '</tr>';
        continue;
      }
      if (inTable) { html += '</table>'; inTable = false; }
      if (/^###\s+/.test(line)) { closeList(); html += '<h5>' + inline(line.replace(/^###\s+/, '')) + '</h5>'; }
      else if (/^##\s+/.test(line)) { closeList(); html += '<h4>' + inline(line.replace(/^##\s+/, '')) + '</h4>'; }
      else if (/^#\s+/.test(line)) { closeList(); html += '<h3>' + inline(line.replace(/^#\s+/, '')) + '</h3>'; }
      else if (/^\s*---+\s*$/.test(line)) { closeList(); html += '<hr>'; }
      else if (/^[-*]\s+/.test(line)) {
        if (list !== 'ul') { closeList(); html += '<ul>'; list = 'ul'; }
        html += '<li>' + inline(line.replace(/^[-*]\s+/, '')) + '</li>';
      } else if (/^\d+\.\s+/.test(line)) {
        if (list !== 'ol') { closeList(); html += '<ol>'; list = 'ol'; }
        html += '<li>' + inline(line.replace(/^\d+\.\s+/, '')) + '</li>';
      } else if (line.trim() === '') { closeList(); }
      else { closeList(); html += '<p>' + inline(line) + '</p>'; }
    }
    closeList();
    if (inTable) html += '</table>';
    return html;
  }

  /** AI 头像（渐变圆 + ✦）。 */
  function aiAvatar() {
    const av = document.createElement('div');
    av.className = 'ava';
    av.textContent = '✦';
    return av;
  }

  /** AI 回答卡片底部的操作栏：复制 / 点赞 / 点踩（+ 左侧 meta 小字）。 */
  function aiFoot(getText, metaText) {
    const foot = document.createElement('div');
    foot.className = 'ai-foot';
    if (metaText) {
      const m = document.createElement('span');
      m.className = 'meta';
      m.textContent = metaText;
      foot.appendChild(m);
    }
    const acts = document.createElement('div');
    acts.className = 'ai-acts';
    const mk = (icon, title, fn) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'ai-act';
      b.title = title;
      b.textContent = icon;
      b.addEventListener('click', (ev) => fn(ev.currentTarget, b));
      return b;
    };
    acts.appendChild(mk('⧉', '复制回答', (btn, el) => {
      const t = String(getText() || '');
      const done = () => { el.classList.add('done'); setTimeout(() => el.classList.remove('done'), 1200); };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(t).then(done, () => toast('复制失败', 'err'));
      } else {
        const ta = document.createElement('textarea');
        ta.value = t; document.body.appendChild(ta); ta.select();
        try { document.execCommand('copy'); done(); } catch (ignored) { toast('复制失败', 'err'); }
        ta.remove();
      }
    }));
    acts.appendChild(mk('👍', '有帮助', (_b, el) => {
      el.classList.toggle('picked');
      const sib = el.nextElementSibling;
      if (el.classList.contains('picked') && sib) sib.classList.remove('picked');
      if (el.classList.contains('picked')) toast('感谢反馈');
    }));
    acts.appendChild(mk('👎', '没帮助', (_b, el) => {
      el.classList.toggle('picked');
      const sib = el.previousElementSibling;
      if (el.classList.contains('picked') && sib) sib.classList.remove('picked');
      if (el.classList.contains('picked')) toast('已记录，我们会改进');
    }));
    foot.appendChild(acts);
    return foot;
  }

  function aAdd(role, html, meta) {
    const d = document.createElement('div');
    d.className = 'msg ' + role;
    if (role === 'a') {
      d.appendChild(aiAvatar());
      const card = document.createElement('div');
      card.className = 'ai-card';
      const bd = document.createElement('div');
      bd.className = 'ai-body';
      bd.innerHTML = html;
      card.appendChild(bd);
      if (meta) card.appendChild(aiFoot(() => bd.textContent, meta));
      d.appendChild(card);
    } else {
      // 用户消息：右侧蓝气泡 + 头像
      const row = document.createElement('div');
      row.className = 'u-row';
      const b = document.createElement('div');
      b.className = 'u-bubble';
      b.innerHTML = html;
      if (meta) {
        const m = document.createElement('div');
        m.className = 'meta';
        m.textContent = meta;
        b.appendChild(m);
      }
      const av = document.createElement('div');
      av.className = 'ava u';
      av.textContent = (AUTH && (AUTH.userName || AUTH.userCode) || '我').slice(0, 1);
      row.appendChild(b);
      row.appendChild(av);
      d.appendChild(row);
    }
    $('msgList').appendChild(d);
    scrollBottom();
    return d;
  }

  /** 过程步骤：有进行中的 AI 卡片 → 进「思考过程」折叠块（带步进耗时）；否则独立提示。 */
  function aToolHint(text) {
    if (THINK_CTX) {
      const now = performance.now();
      const dur = Math.max(0, (now - THINK_CTX.last) / 1000);
      THINK_CTX.last = now;
      const row = document.createElement('div');
      row.className = 'think-step';
      const t = document.createElement('span');
      t.className = 'ts';
      t.textContent = '（用时 ' + dur.toFixed(1) + ' 秒）';
      row.appendChild(document.createTextNode(text));
      row.appendChild(t);
      THINK_CTX.bodyEl.appendChild(row);
      THINK_CTX.headerEl.textContent = '思考中…';
      scrollBottom();
      return;
    }
    const h = document.createElement('div');
    h.className = 'tool-hint';
    h.textContent = text;
    appendCardEl(h);
    scrollBottom();
  }

  function aSysHint(text) {
    const h = document.createElement('div');
    h.className = 'sys-hint';
    h.textContent = text;
    appendCardEl(h);
    scrollBottom();
    return h;
  }

  function aRenderChips() {
    $('sugChips').innerHTML = SUG_CHIPS.map((c) => '<span class="sug-chip">' + esc(c) + '</span>').join('');
  }
  $('sugChips').addEventListener('click', (e) => {
    if (e.target.classList.contains('sug-chip')) aAsk(e.target.textContent);
  });

  /* ---- 新建对话 ---- */
  function newChat() {
    abortChatStream();   // 中断进行中的流并复位忙碌态（否则新会话里发不出消息 = 卡死）
    showView('chat'); setNavActive('chat');
    CONV_ID = null;   // 新会话：下一轮由服务端分配新 id
    resetChat();
    loadConversations();
    $('chatScroll').scrollTop = 0;
    $('chatInput').focus();
  }
  $('newChat').addEventListener('click', newChat);
  document.addEventListener('keydown', (e) => {
    if ((e.metaKey || e.ctrlKey) && String(e.key).toLowerCase() === 'k') { e.preventDefault(); newChat(); }
  });

  /* ---- 发送 ---- */
  $('sendBtn').addEventListener('click', () => {
    const v = $('chatInput').value;
    $('chatInput').value = '';
    aAsk(v);
  });
  $('chatInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      const v = e.target.value;
      e.target.value = '';
      aAsk(v);
    }
  });

  /* ---- 发送：统一走编排层（意图识别，2026-10-07 起唯一路径；2026-10-08 对话卡片化） ---- */
  async function aAsk(text) {
    if (aBusy || !String(text).trim()) return;
    if (!AUTH) { toast('请先登录', 'err'); return; }
    aBusy = true;
    $('sendBtn').disabled = true;
    showView('chat'); setNavActive('chat');
    aAdd('u', esc(text));

    // AI 回答卡片：思考过程（折叠）→ 正文 → 内嵌卡片 → 操作栏
    const bubble = document.createElement('div');
    bubble.className = 'msg a';
    bubble.appendChild(aiAvatar());
    const card = document.createElement('div');
    card.className = 'ai-card';
    const think = document.createElement('div');
    think.className = 'ai-think open';
    const th = document.createElement('div');
    th.className = 'ai-think-h';
    th.textContent = '思考中…';
    const tb = document.createElement('div');
    tb.className = 'ai-think-body';
    think.appendChild(th);
    think.appendChild(tb);
    th.addEventListener('click', () => think.classList.toggle('open'));
    const bd = document.createElement('div');
    bd.className = 'ai-body';
    bd.innerHTML = '<span class="ai-pending">正在识别意图并调度能力，通常需要 10~30 秒…</span>';
    const cardsBox = document.createElement('div');
    cardsBox.className = 'ai-cards';
    card.appendChild(think);
    card.appendChild(bd);
    card.appendChild(cardsBox);
    bubble.appendChild(card);
    bubble.dataset.answer = '';
    $('msgList').appendChild(bubble);
    scrollBottom();

    THINK_CTX = { headerEl: th, bodyEl: tb, thinkEl: think, start: performance.now(), last: performance.now() };
    CUR_CARDS = cardsBox;
    const render = () => {
      bd.innerHTML = md2html(bubble.dataset.answer);
      scrollBottom();
    };

    try {
      // 唯一路径：编排层意图识别 → 三条路线之一（图表 / 补货调拨 / 知识问答），结果含富卡片
      const result = await askOrchestrator(text, { bubble });
      // 收尾思考块：改标题为「思考完成（用时 N.N 秒）」并收起
      const dur = Math.max(0, (performance.now() - THINK_CTX.start) / 1000);
      th.textContent = '思考完成（用时 ' + dur.toFixed(1) + ' 秒）';
      think.classList.remove('open');
      THINK_CTX = null;
      const route = (result && result.route) || '';
      // error 帧已把错误写进 dataset.answer；只有拿到 result 才覆盖
      if (result) bubble.dataset.answer = result.agent_response || '（本轮没有文本结果）';
      else if (!bubble.dataset.answer) bubble.dataset.answer = '⚠ 连接中断（服务端未返回结果），请重发一次';
      if (!bubble.dataset.answer.trim() && !((result && result.cards) || []).length) {
        bubble.dataset.answer = '（本轮没有文本结果）';
      }
      render();
      renderOrchCards(result);
      CUR_CARDS = null;
      card.appendChild(aiFoot(() => bubble.dataset.answer,
        '意图识别 → ' + (ROUTE_LABEL[route] || '编排层')
        + ' · ' + (((result && result.cards) || []).length) + ' 张卡片'));
      loadConversations();   // 刷新「最近对话」（本轮已落库）
    } catch (e) {
      THINK_CTX = null;
      CUR_CARDS = null;
      th.textContent = '思考中断';
      think.classList.remove('open');
      bd.innerHTML = '<p>连接失败：' + esc(e.message) + '</p>';
    } finally {
      aBusy = false;
      $('sendBtn').disabled = false;
      scrollBottom();
    }
  }

  /* ================================================================
   * 编排层（对话唯一入口）：意图识别 → 三条路线 chart / replenish（含建单）/ kb
   *   入口 = 上面的 aAsk；本段负责 SSE 解析与结果卡片渲染。
   *   说明：编排层是无状态服务（建议凭据 30 分钟 TTL），不提供会话历史。
   * ================================================================ */
  const ROUTE_LABEL = { chart: '生成报表', replenish: '补货建议', order: '调拨申请', kb: '流程问答' };

  /** 调编排层 SSE：agent_step 逐条显示为过程提示，返回 result 载荷（含富卡片）。 */
  async function askOrchestrator(text, opts) {
    const o = opts || {};
    abortChatStream();                 // 同一界面重复发送：先中断上一轮未结束的流
    const ctl = new AbortController();
    CHAT_ABORT = ctl;
    const epochAtSend = CONV_EPOCH;    // 发起时会话代次：流中途用户切/新建会话则停止渲染（防串台）
    const url = '/api/orchestrator/v1/chat/stream?query=' + encodeURIComponent(text)
      + (o.suggestionId ? '&suggestion_id=' + encodeURIComponent(o.suggestionId) : '')
      + '&user_id=' + encodeURIComponent((AUTH && AUTH.userCode) || '')
      + '&warehouse_code=' + encodeURIComponent((AUTH && AUTH.warehouseCode) || '')
      + (CONV_ID ? '&conversation_id=' + encodeURIComponent(CONV_ID) : '');
    // 必须是 POST：编排层对话端点是 @app.post（参数走查询串、无请求体）
    let resp;
    try {
      resp = await authFetch(url, { method: 'POST', signal: ctl.signal });
    } catch (e) {
      if (e && e.name === 'AbortError') return null;   // 用户主动中断：静默
      throw e;
    }
    if (!resp.ok || !resp.body) throw new Error('HTTP ' + resp.status);
    const reader = resp.body.getReader();
    const dec = new TextDecoder();
    let buf = '', result = null;
    try {
    for (;;) {
      const rd = await reader.read();
      if (rd.done) break;
      if (CONV_EPOCH !== epochAtSend) break;   // 用户已切/新建会话 → 停止渲染，连接在 finally 释放
      buf += dec.decode(rd.value, { stream: true });
      const parts = buf.split('\n\n');
      buf = parts.pop();
      for (const part of parts) {
        let ev = 'message', dat = '';
        for (const line of part.split('\n')) {
          if (line.startsWith('event:')) ev = line.slice(6).trim();
          else if (line.startsWith('data:')) dat += line.slice(5).trim();
        }
        if (ev === 'start') {
          // 会话 id：服务端为本轮分配/沿用（落库 orch_conversation），续聊时回传
          try { const s = JSON.parse(dat); if (s && s.conversation_id && CONV_EPOCH === epochAtSend) CONV_ID = s.conversation_id; } catch (ignored) { /* 忽略 */ }
        } else if (ev === 'agent_step') {
          try { const s = JSON.parse(dat); if (s && s.text) aToolHint('▸ ' + s.text); } catch (ignored) { /* 忽略 */ }
        } else if (ev === 'result') {
          try { result = JSON.parse(dat); if (result && result.conversation_id && CONV_EPOCH === epochAtSend) CONV_ID = result.conversation_id; } catch (ignored) { /* 忽略 */ }
        } else if (ev === 'error') {
          if (o.bubble) o.bubble.dataset.answer = '⚠ ' + dat;
          else toast('⚠ ' + dat, 'err');
        }
      }
    }
    } catch (e) {
      if (e && e.name === 'AbortError') return null;   // 中断：静默返回
      throw e;
    } finally {
      try { reader.cancel(); } catch (ignored) { /* 兜底释放 SSE 连接 */ }
      if (CHAT_ABORT === ctl) CHAT_ABORT = null;
    }
    return result;
  }

  /** 渲染编排层结果卡片（未知类型静默跳过，向前兼容）。 */
  function renderOrchCards(result) {
    for (const c of ((result && result.cards) || [])) renderOrchCard(c, result);
  }
  function renderOrchCard(c, result) {
    const t = c && c.type;
    if (t === 'chart') renderChartCard(c);
    else if (t === 'transfer_list') renderTransferListCard(c, result);
    else if (t === 'procurement_list') renderProcurementCard(c);
    else if (t === 'transfer_order') renderOrderCardFromResult(c);
    else if (t === 'replenish_reminder') renderReplenishReminder(c);
    else if (t === 'replenish_unavailable') aSysHint('⚠ ' + (c.detail || '补货建议暂不可用'));
  }

  /** 图表卡（2026-10-07 多图型）：metric=数字磁贴；分布类（bar/pie/line/table）
   *  卡片头提供「条形|饼图|折线|表格」按钮 —— 同一份数据纯前端切换，不发请求、不重跑 LLM。 */
  const CHART_STYLES = [['bar', '条形'], ['pie', '饼图'], ['line', '折线'], ['table', '表格']];
  const PIE_COLORS = ['#6D5BD0', '#8a7be0', '#a99df0', '#5b8ff9', '#61ddaa', '#f6bd16',
    '#ff9d4d', '#f2637b', '#9270ca', '#69c0ff', '#ffd666', '#95de64'];

  function renderChartCard(c) {
    const el = document.createElement('div');
    el.className = 'chart-card';
    const labels = c.labels || [];
    const series = (c.series || [])[0] || { name: '', data: [] };
    const data = series.data || [];
    const metric = c.chartType === 'metric';
    let cur = metric ? 'metric'
      : (CHART_STYLES.some((s) => s[0] === c.chartType) ? c.chartType : 'bar');
    el.innerHTML = '<div class="chart-h">📊 ' + esc(c.title || '图表')
      + (series.name ? '<span class="tag tag-purple">' + esc(series.name) + '</span>' : '')
      + (metric ? '' : '<span class="chart-switch">' + CHART_STYLES.map(([k, n]) =>
          '<button type="button" class="cs-btn' + (k === cur ? ' active' : '')
          + '" data-style="' + k + '">' + n + '</button>').join('') + '</span>')
      + '</div><div class="chart-body"></div>'
      + (c.note ? '<div class="chart-note">' + esc(c.note) + '</div>' : '');
    const body = el.querySelector('.chart-body');
    const draw = (style) => { body.innerHTML = chartBody(style, labels, data); };
    draw(cur);
    if (!metric) {
      el.querySelector('.chart-switch').addEventListener('click', (e) => {
        const btn = e.target.closest('.cs-btn');
        if (!btn) return;
        cur = btn.dataset.style;
        el.querySelectorAll('.cs-btn').forEach((b) => b.classList.toggle('active', b === btn));
        draw(cur);
      });
    }
    appendCardEl(el);
    scrollBottom();
  }

  /** 按样式渲染图表主体（各图型共用同一份 labels/data，纯前端换图）。 */
  function chartBody(style, labels, data) {
    if (style === 'metric') {
      return '<div class="metric-grid">' + labels.map((l, i) =>
        '<div class="metric-item"><div class="v">' + esc(fmtNum(data[i])) + '</div>'
        + '<div class="k">' + esc(l) + '</div></div>').join('') + '</div>';
    }
    if (!labels.length) return '<div class="chart-empty">暂无可展示的数据</div>';
    if (style === 'pie') return pieChart(labels, data);
    if (style === 'line') return lineChart(labels, data);
    if (style === 'table') return tableChart(labels, data);
    return barChart(labels, data);
  }

  /** 条形：比例按最大值归一（纯 CSS）。 */
  function barChart(labels, data) {
    const max = Math.max.apply(null, data.concat([1]));
    return labels.map((l, i) => {
      const v = Number(data[i]) || 0;
      const w = Math.max(2, Math.round(v / max * 100));
      return '<div class="bar-row"><span class="bar-label" title="' + esc(l) + '">' + esc(l) + '</span>'
        + '<div class="bar-track"><div class="bar-fill" style="width:' + w + '%"></div></div>'
        + '<span class="bar-val">' + esc(fmtNum(v)) + '</span></div>';
    }).join('');
  }

  /** 饼图：SVG 扇形 + 右侧图例（含台数与占比；各项之和 == 接口原值合计）。 */
  function pieChart(labels, data) {
    const vals = labels.map((l, i) => Number(data[i]) || 0);
    const total = vals.reduce((s, v) => s + v, 0);
    if (!total) return '<div class="chart-empty">暂无可展示的数据</div>';
    const R = 82, CX = 92, CY = 92;
    let angle = -Math.PI / 2, shapes = '', legend = '';
    vals.forEach((v, i) => {
      if (v <= 0) return;
      const frac = v / total;
      const a2 = angle + frac * Math.PI * 2;
      const color = PIE_COLORS[i % PIE_COLORS.length];
      const x1 = (CX + R * Math.cos(angle)).toFixed(2), y1 = (CY + R * Math.sin(angle)).toFixed(2);
      const x2 = (CX + R * Math.cos(a2)).toFixed(2), y2 = (CY + R * Math.sin(a2)).toFixed(2);
      const large = frac > 0.5 ? 1 : 0;
      // 单项占满 100% 时 arc 画不出整圆，用小弧差回退
      const d = frac >= 0.9999
        ? 'M ' + CX + ' ' + (CY - R) + ' A ' + R + ' ' + R + ' 0 1 1 ' + (CX - 0.01) + ' ' + (CY - R) + ' Z'
        : 'M ' + CX + ' ' + CY + ' L ' + x1 + ' ' + y1 + ' A ' + R + ' ' + R + ' 0 ' + large + ' 1 ' + x2 + ' ' + y2 + ' Z';
      shapes += '<path d="' + d + '" fill="' + color + '" stroke="#fff" stroke-width="1">'
        + '<title>' + esc(labels[i]) + '：' + esc(fmtNum(v)) + ' 台（' + (frac * 100).toFixed(1) + '%）</title></path>';
      legend += '<div class="pie-leg-row"><span class="pie-dot" style="background:' + color + '"></span>'
        + '<span class="pl" title="' + esc(labels[i]) + '">' + esc(labels[i]) + '</span>'
        + '<span class="pv">' + esc(fmtNum(v)) + '（' + (frac * 100).toFixed(1) + '%）</span></div>';
      angle = a2;
    });
    return '<div class="pie-wrap"><svg width="184" height="184" viewBox="0 0 184 184">' + shapes + '</svg>'
      + '<div class="pie-legend">' + legend + '</div></div>';
  }

  /** 折线：横轴为项目序号（按值降序的分布，非时间序列），点上方标数值。 */
  function lineChart(labels, data) {
    const vals = labels.map((l, i) => Number(data[i]) || 0);
    const n = vals.length;
    const W = 560, H = 210, PL = 64, PR = 18, PT = 24, PB = 58;
    const max = Math.max.apply(null, vals.concat([1]));
    const iw = W - PL - PR, ih = H - PT - PB;
    const x = (i) => PL + (n === 1 ? iw / 2 : iw * i / (n - 1));
    const y = (v) => PT + ih * (1 - v / max);
    let dots = '';
    vals.forEach((v, i) => {
      dots += '<circle cx="' + x(i).toFixed(1) + '" cy="' + y(v).toFixed(1) + '" r="3.5" fill="#6D5BD0">'
        + '<title>' + esc(labels[i]) + '：' + esc(fmtNum(v)) + '</title></circle>'
        + '<text x="' + x(i).toFixed(1) + '" y="' + (y(v) - 9).toFixed(1)
        + '" text-anchor="middle" class="lc-v">' + esc(fmtNum(v)) + '</text>';
    });
    const tx = H - PB + 16;
    const ticks = labels.map((l, i) =>
      '<text x="' + x(i).toFixed(1) + '" y="' + tx + '" text-anchor="end" class="lc-x"'
      + ' transform="rotate(-30 ' + x(i).toFixed(1) + ' ' + tx + ')">'
      + esc(String(l).length > 6 ? String(l).slice(0, 6) + '…' : l) + '</text>').join('');
    const pts = vals.map((v, i) => x(i).toFixed(1) + ',' + y(v).toFixed(1)).join(' ');
    return '<div class="line-wrap"><svg width="' + W + '" height="' + H + '" viewBox="0 0 ' + W + ' ' + H + '">'
      + '<line x1="' + PL + '" y1="' + (PT + ih) + '" x2="' + (W - PR) + '" y2="' + (PT + ih) + '" stroke="#eceaf7"/>'
      + (n > 1 ? '<polyline points="' + pts + '" fill="none" stroke="#6D5BD0" stroke-width="2.5" stroke-linejoin="round"/>' : '')
      + dots + ticks + '</svg></div>';
  }

  /** 表格：复用 .ptable 样式，名称 + 台数 + 占比。 */
  function tableChart(labels, data) {
    const vals = labels.map((l, i) => Number(data[i]) || 0);
    const total = vals.reduce((s, v) => s + v, 0) || 1;
    return '<div class="ptable-wrap"><table class="ptable"><thead><tr><th>项目</th><th>台数</th><th>占比</th></tr></thead><tbody>'
      + labels.map((l, i) => '<tr><td>' + esc(l) + '</td><td>' + esc(fmtNum(vals[i]))
        + '</td><td>' + (vals[i] / total * 100).toFixed(1) + '%</td></tr>').join('')
      + '</tbody></table></div>';
  }

  /** 调拨行按 (调出仓, 调入仓, 操作类型) 分组 —— 草稿卡展示与建单同口径。 */
  function groupTransfers(rows) {
    const groups = [];
    const gidx = {};
    (rows || []).forEach((r) => {
      const op = r.operationType || 'TRANSFER';
      const key = (r.fromWarehouse || '') + '|' + (r.toWarehouse || '') + '|' + op;
      if (!gidx[key]) {
        gidx[key] = {
          fromWarehouse: r.fromWarehouse || '',
          fromName: r.fromWarehouseName || r.fromWarehouse || '—',
          toWarehouse: r.toWarehouse || '',
          toName: r.toWarehouseName || r.toWarehouse || '—',
          op,
          rows: [],
        };
        groups.push(gidx[key]);
      }
      gidx[key].rows.push(r);
    });
    return groups;
  }
  /** 分组明细表：方向行 + 报表式表格（型号 | 物料名称 | 数量），与工单卡同款。 */
  function transferGroupTable(g) {
    return '<div class="olist-sect"><div class="olist-dir">'
      + '<span class="wh">' + esc(g.fromName) + '</span> → <span class="wh">' + esc(g.toName) + '</span>'
      + '<span class="tag tag-gray">' + (g.op === 'RECALL' ? '收回' : '调拨') + '</span></div>'
      + '<table class="ptable"><thead><tr><th>型号</th><th>物料名称</th><th class="num">数量(台)</th></tr></thead><tbody>'
      + g.rows.map((r) => '<tr><td>' + esc(r.model || '—') + '</td>'
        + '<td>' + esc(r.materialName || '—') + '</td>'
        + '<td class="num">' + esc(r.quantity == null ? '—' : r.quantity) + '</td></tr>').join('')
      + '</tbody></table></div>';
  }

  /** 行内输入框（可编辑清单用）：平时隐形、悬停/聚焦显形，外观对齐报表式表格。 */
  function cellInput(val, cls, type) {
    return '<input class="rli ' + cls + '" type="' + (type || 'text') + '" value="'
      + esc(val == null ? '' : String(val)) + '">';
  }
  /** 清点合计。 */
  function sumQty(items) {
    return items.reduce((s, it) => s + (Number(it.quantity) || 0), 0);
  }
  /** 改数后按原物料占比分摊（最大余数法，保证 sum == 改后数量）。 */
  function scaleMaterials(materials, target) {
    const total = (materials || []).reduce((s, m) => s + (Number(m.quantity) || 0), 0);
    if (total <= 0) return [];
    const out = materials.map((m) => ({
      materialCode: m.materialCode, materialName: m.materialName || '',
      quantity: Math.floor(target * (Number(m.quantity) || 0) / total),
    }));
    let rest = target - out.reduce((s, m) => s + m.quantity, 0);
    for (const m of out.slice().sort((a, b) => b.quantity - a.quantity)) {
      if (rest <= 0) break;
      m.quantity += 1;
      rest -= 1;
    }
    return out.filter((m) => m.quantity > 0);
  }
  /** 手工添加的行没有物料明细：① 优先复用清单内同行（同调出仓+同型号）的物料明细
   *  —— 不越数据权限；② 兜底按 调出仓+型号 查库存聚合（需调出仓在可见范围内）。 */
  async function resolveMaterials(rows) {
    const cache = {};
    for (const it of rows) {
      if ((it.materials || []).length) continue;
      const model = (it.model || '').trim();
      const sibling = rows.find((r) => r !== it && r.fromWarehouse === it.fromWarehouse
        && (r.model || '').trim() === model && (r.materials || []).length);
      if (sibling) {
        it.materials = sibling.materials.map((m) => ({
          materialCode: m.materialCode, materialName: m.materialName || '', quantity: m.quantity,
        }));
        continue;
      }
      const key = it.fromWarehouse + '|' + model;
      if (!(key in cache)) {
        const found = await apiGet('/api/terminal/inventory/aggregate?warehouseCode='
          + encodeURIComponent(it.fromWarehouse) + '&model=' + encodeURIComponent(model));
        const r0 = (found || []).find((r) => (Number(r.availableQty) || 0) > 0) || (found || [])[0];
        cache[key] = r0 ? [{
          materialCode: r0.materialCode,
          materialName: r0.materialName || '',
          quantity: Math.max(Number(r0.availableQty) || 1, 1),
        }] : [];
      }
      it.materials = cache[key];
      if (!it.materials.length) {
        throw new Error('型号 ' + model + ' 解析不到物料明细：请改用清单中已有的型号'
          + '（调出仓 ' + (it.fromWarehouseName || it.fromWarehouse) + ' 不在你的数据可见范围内）');
      }
    }
  }

  /** 补货提醒卡（2026-10-10）：chip + 分析文字（需求：推荐流程的对话卡片里要有分析）。
   *  编排层只给 stats 数据字段（2026-10-10 实测：在 report 节点改 LLM 提示词会卡死整条流程，
   *  故文案由前端拼，后端不碰提示词）。数字全部来自算法输出，前端只渲染不加工。 */
  function renderReplenishReminder(c) {
    const el = document.createElement('div');
    el.className = 'chart-card replenish-reminder';
    const st = c.stats || {};
    const analysis = (st.totalAvailable != null)
      ? '根据当前库存分布与库龄分析：可见范围内可用 ' + (st.totalAvailable || 0) + ' 台、'
        + '在途 ' + (st.totalInTransit || 0) + ' 台，' + (c.detail || '')
        + '；另有 ' + (st.longAgingCount || 0) + ' 台库龄已超 180 天，建议及时盘活处理！'
      : '';
    el.innerHTML = '<div class="rr-head"><span class="rr-chip">🛒 '
      + esc(c.detail || '补货提醒') + '</span></div>'
      + (analysis ? '<div class="rr-analysis">📌 ' + esc(analysis) + '</div>' : '');
    appendCardEl(el);
    scrollBottom();
  }

  /** 建议调拨清单卡（2026-10-08 重构）：可编辑（改数/删行/加行），外观对齐报表式表格；
   *  「申请调拨」先出草稿卡，人工「确认发送」后按**修改后的清单**直接建单
   *  （不再回编排层缓存，改多少建多少；收回单已下线）。 */
  function renderTransferListCard(c, result) {
    const el = document.createElement('div');
    el.className = 'chart-card';
    // 可编辑数据副本：materials 为建单物料明细（改数后按比例分摊到各物料）
    const items = (c.rows || []).map((r) => ({
      model: r.model || '',
      materialName: r.materialName || '',
      fromWarehouse: r.fromWarehouse || '',
      fromWarehouseName: r.fromWarehouseName || r.fromWarehouse || '',
      toWarehouse: r.toWarehouse || '',
      toWarehouseName: r.toWarehouseName || r.toWarehouse || '',
      operationType: r.operationType || 'TRANSFER',
      reason: r.reason || '',
      quantity: Number(r.quantity) || 0,
      originQuantity: Number(r.quantity) || 0,
      materials: r.materials || [],
    }));
    const dirText = (it) => (it.fromWarehouseName || '—') + ' → ' + (it.toWarehouseName || '—');
    el.innerHTML = '<div class="chart-h">🧾 ' + esc(c.title || '建议调拨补货清单')
      + (c.badge ? '<span class="tag tag-purple">' + esc(c.badge) + '</span>' : '')
      + '<span class="tag tag-gray" data-total>合计 — 台</span></div>'
      + '<div class="rlist-wrap"><table class="rlist-table"><thead><tr>'
      // 2026-10-11 修复错位：数据行把「调出仓/调入仓」合并进一个单元格（源→目标），
      // 表头必须同步合并（原来写 5 个 th、数据行只有 4 个 td，整行右移一列：
      // 数量挤进「调入仓」列、按钮挤进「数量」列）。
      + '<th class="col-m">型号</th><th>调出仓 → 调入仓</th><th class="col-q">数量(台)</th><th class="col-act">操作</th>'
      + '</tr></thead><tbody></tbody></table></div>'
      + '<div class="rlist-acts"><button class="btn btn-ghost" data-act="add">＋ 添加一行</button>'
      + '<span class="torder-none">每个型号单独申请一张调拨单；调出/调入仓可搜索修改（输入编码或名称过滤）</span></div>'
      + '<div class="rlist-err" data-err hidden></div>';
    const tbody = el.querySelector('tbody');
    const totalEl = el.querySelector('[data-total]');
    function refreshTotal() {
      totalEl.textContent = '合计 ' + sumQty(items) + ' 台';
    }
    /** 仓位可搜索下拉（2026-10-11 替换原生 datalist）：
     *  datalist 的下拉外观完全由浏览器/系统主题控制（深色主题下是黑框，且会遮挡相邻行），
     *  这里自绘面板：白底圆角、编码+名称+层级三栏、hover 高亮，样式与全站一致。 */
    function bindWhPicker(inp, idx, dir) {
      let pop = null;
      const close = () => { if (pop) { pop.remove(); pop = null; } };
      const pick = (code) => {
        items[idx][dir === 'from' ? 'fromWarehouse' : 'toWarehouse'] = code;
        items[idx][dir === 'from' ? 'fromWarehouseName' : 'toWarehouseName'] = WH_NAMES[code] || code;
        inp.value = whLabel(code);
        close();
      };
      const open = () => {
        close();
        if (!WH_OPTIONS) { warehouseOptions().then(open); return; }
        const kw = (inp.value || '').trim();
        const list = WH_OPTIONS.filter((w) => !kw || w.code.includes(kw) || (w.name || '').includes(kw));
        if (!list.length) return;
        pop = document.createElement('div');
        pop.className = 'wh-pop';
        pop.innerHTML = list.slice(0, 60).map((w) =>
          '<div class="wh-pop-i" data-code="' + esc(w.code) + '">'
          + '<span class="wh-pop-c">' + esc(w.code) + '</span>'
          + '<span class="wh-pop-n">' + esc(w.name || '') + '</span>'
          + (w.level ? '<span class="wh-pop-l">L' + esc(w.level) + '</span>' : '')
          + '</div>').join('');
        document.body.appendChild(pop);
        const r = inp.getBoundingClientRect();
        pop.style.left = Math.round(r.left) + 'px';
        pop.style.top = Math.round(r.bottom + 4) + 'px';
        pop.style.width = Math.max(Math.round(r.width), 280) + 'px';
        pop.querySelectorAll('.wh-pop-i').forEach((row) => {
          // mousedown + preventDefault：抢在 input blur 之前完成选择
          row.addEventListener('mousedown', (ev) => { ev.preventDefault(); pick(row.dataset.code); });
        });
      };
      inp.addEventListener('focus', open);
      inp.addEventListener('input', open);
      inp.addEventListener('blur', () => setTimeout(close, 150));
      inp.addEventListener('keydown', (ev) => { if (ev.key === 'Escape') { close(); inp.blur(); } });
    }
    function render() {
      if (!items.length) {
        tbody.innerHTML = '<tr><td colspan="4" class="rlist-empty">暂无建议行，可点「添加一行」补录</td></tr>';
        refreshTotal();
        return;
      }
      tbody.innerHTML = items.map((it, i) =>
        '<tr>'
        + '<td>' + cellInput(it.model, 'rm') + '</td>'
        + '<td class="rd">'
          + '<input class="rwh" autocomplete="off" data-i="' + i + '" data-dir="from" '
             + 'value="' + esc(whLabel(it.fromWarehouse)) + '" placeholder="搜索调出仓">'
          + '<span class="wh-arrow">→</span>'
          + '<input class="rwh" autocomplete="off" data-i="' + i + '" data-dir="to" '
             + 'value="' + esc(whLabel(it.toWarehouse)) + '" placeholder="搜索调入仓">'
          + '<br><span class="s">'
          + esc((it.operationType === 'RECALL' ? '收回' : '调拨') + (it.reason ? ' · ' + it.reason : ''))
          + '</span></td>'
        + '<td>' + cellInput(it.quantity, 'rq', 'number') + '</td>'
        + '<td class="ract">'
          + (it.appliedOrderNo
              ? '<span class="tag tag-green">✓ ' + esc(it.appliedOrderNo) + '</span>'
              : '<button class="btn btn-primary rli-apply" data-i="' + i + '">申请调拨</button>')
          + '<button class="rli-del" title="删除该行">×</button></td>'
        + '</tr>').join('');
      tbody.querySelectorAll('tr').forEach((tr, i) => {
        const m = tr.querySelector('.rm');
        const q = tr.querySelector('.rq');
        const del = tr.querySelector('.rli-del');
        const apply = tr.querySelector('.rli-apply');
        if (m) m.addEventListener('input', () => { items[i].model = m.value; });
        if (q) q.addEventListener('input', () => { items[i].quantity = Number(q.value) || 0; refreshTotal(); });
        if (del) del.addEventListener('click', () => { items.splice(i, 1); render(); });
        if (apply) apply.addEventListener('click', () => applyRow(i, apply));
        tr.querySelectorAll('.rwh').forEach((inp) => {
          bindWhPicker(inp, i, inp.dataset.dir);   // 自绘可搜索下拉（替换原生 datalist）
          inp.addEventListener('change', () => {
            const code = parseWh(inp.value);
            if (!code) return;
            if (inp.dataset.dir === 'from') {
              items[i].fromWarehouse = code;
              items[i].fromWarehouseName = WH_NAMES[code] || code;
            } else {
              items[i].toWarehouse = code;
              items[i].toWarehouseName = WH_NAMES[code] || code;
            }
          });
        });
      });
      refreshTotal();
    }
    render();
    // 添加一行：继承第一行的调拨方向（同方向追加型号最常见），物料明细发送前自动解析
    el.querySelector('[data-act="add"]').addEventListener('click', () => {
      const base = items[0];
      items.push(base ? {
        model: '', materialName: '',
        fromWarehouse: base.fromWarehouse, fromWarehouseName: base.fromWarehouseName,
        toWarehouse: base.toWarehouse, toWarehouseName: base.toWarehouseName,
        operationType: base.operationType, reason: '手工添加',
        quantity: 0, originQuantity: 0, materials: [],
      } : {
        model: '', materialName: '', fromWarehouse: '', fromWarehouseName: '待填写',
        toWarehouse: '', toWarehouseName: '待填写', operationType: 'TRANSFER',
        reason: '手工添加', quantity: 0, originQuantity: 0, materials: [],
      });
      render();
    });
    // 2026-10-10：改为「一个型号一张调拨单」—— 行内直接申请，不再整单出草稿卡。
    // 建单入参与原草稿卡确认发送一致（operationType/from/to/items…）。
    async function applyRow(i, btn) {
      if (!AUTH) { toast('请先登录', 'err'); return; }
      if (!isAccMgr()) { toast('只有账管可以发起调拨申请', 'err'); return; }
      const it = items[i];
      const model = (it.model || '').trim();
      const qty = Number(it.quantity) || 0;
      if (!model || qty <= 0) { toast('该行缺少有效型号或数量', 'err'); return; }
      if (!it.fromWarehouse || !it.toWarehouse) { toast('该行缺少调出/调入仓，请用下拉选择', 'err'); return; }
      if (it.fromWarehouse === it.toWarehouse) { toast('调出仓与调入仓不能相同', 'err'); return; }
      const errEl = el.querySelector('[data-err]');
      if (errEl) errEl.hidden = true;
      btn.disabled = true;
      btn.textContent = '提交中…';
      try {
        await resolveMaterials([it]);   // 物料明细发送前自动解析（沿用原逻辑）
        const vo = await apiPost('/api/transferOrder/create', {
          operationType: it.operationType || 'TRANSFER',
          fromWarehouse: it.fromWarehouse,
          toWarehouse: it.toWarehouse,
          creator: AUTH.userCode,
          source: 'MANUAL',
          suggestionId: (result && result.suggestion_id) || undefined,
          items: (it.materials && it.materials.length ? it.materials : [{
            materialCode: it.materialCode || '', model: it.model,
            materialName: it.materialName || '', quantity: qty }]),
        });
        it.appliedOrderNo = vo.orderNo || '';
        toast('调拨单 ' + (it.appliedOrderNo || '') + ' 已提交：' + model + ' ' + qty + ' 台');
        loadNotices();
        render();
      } catch (e) {
        toast('提交失败：' + e.message, 'err');
        if (errEl) { errEl.textContent = '⚠ ' + e.message; errEl.hidden = false; }
        btn.disabled = false;
        btn.textContent = '申请调拨';
      }
    }
    // 仓位下拉选项：全量仓（调出仓可能在用户可见范围外，比如省仓有富余库存）
    warehouseOptions().then((opts) => {
      const dl = document.getElementById('wh-options-dl');
      if (dl) dl.innerHTML = opts.map((w) =>
        '<option value="' + esc(w.code + ' ' + w.name) + '">').join('');
    });
    appendCardEl(el);
    scrollBottom();
  }

  /** 采购清单卡（2026-10-08 统一样式）：只读报表式明细（与工单卡同款）；导出按原始建议行生成 CSV。 */
  function renderProcurementCard(c) {
    const el = document.createElement('div');
    el.className = 'chart-card';
    const rows = c.rows || [];
    el.innerHTML = '<div class="chart-h">🛍 ' + esc(c.title || '建议采购补货清单')
      + (c.badge ? '<span class="tag tag-orange">' + esc(c.badge) + '</span>' : '')
      + '<span class="tag tag-gray">合计 ' + esc(c.totalQuantity == null ? '—' : c.totalQuantity) + ' 台</span></div>'
      + (rows.length
        ? '<div class="olist-sect"><table class="ptable"><thead><tr>'
          + '<th>型号</th><th>厂商</th><th>物料型号</th><th>物料名称</th><th class="num">建议数量(台)</th></tr></thead><tbody>'
          + rows.map((r) => '<tr><td>' + esc(r.model || '—') + '</td>'
            + '<td>' + esc(r.vendor || r.suggestedBrand || '—') + '</td>'
            + '<td class="mono">' + esc(r.materialCode || '—') + '</td>'
            + '<td>' + esc(r.materialName || '—') + '</td>'
            + '<td class="num">' + esc(r.suggestedQty == null ? '—' : r.suggestedQty) + '</td></tr>').join('')
          + '</tbody></table></div>'
        : '<div class="olist-sect"><div class="torder-none">暂无采购建议行</div></div>')
      + '<div class="rlist-acts"><button class="btn btn-ghost" data-act="export">导出采购清单</button></div>';
    el.querySelector('[data-act="export"]').addEventListener('click', () => exportProcurementCsv(rows));
    appendCardEl(el);
    scrollBottom();
  }
  function exportProcurementCsv(rows) {
    const head = ['型号', '厂商', '物料型号', '物料名称', '建议数量(台)', '优先级', '原因'];
    const lines = (rows || []).map((r) => [r.model, r.vendor || r.suggestedBrand, r.materialCode, r.materialName, r.suggestedQty, r.priority, r.reason]);
    // \ufeff = BOM：Excel 打开中文 CSV 不乱码
    const csv = '\ufeff' + [head].concat(lines).map((row) =>
      row.map((v) => '"' + String(v == null ? '' : v).replace(/"/g, '""') + '"').join(',')).join('\r\n');
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    a.download = '采购补货清单.csv';
    a.click();
    URL.revokeObjectURL(a.href);
    toast('采购清单已导出');
  }

  /** 调拨申请草稿卡（2026-10-08）：按**修改后的清单**生成，人工「确认发送」后
   *  直接调后端建单（不再回编排层缓存），成功一组标记一组，防重复发送。 */
  function renderTransferDraft(items, sid, srcBtn) {
    const total = items.reduce((s, r) => s + (Number(r.quantity) || 0), 0);
    const pending = groupTransfers(items);   // 待发送组：成功/失败各移除一组，重试不重发
    const el = document.createElement('div');
    el.className = 'torder';   // 2026-10-08：草稿卡与工单卡同款白卡样式（状态靠「待人工确认」标签区分），明细只读
    let html = '<div class="torder-h"><span class="torder-no">调拨申请草稿</span>'
      + '<span class="tag tag-orange" data-dtag>待人工确认</span>'
      + '<span class="tag tag-gray">合计 ' + total + ' 台</span></div>'
      + '<div class="torder-none" style="margin-top:8px">已按修改后的清单生成草稿：点「确认发送」才会建单'
      + '，建单后由调出仓仓管导入 SN 并确认出库</div>';
    html += pending.map(transferGroupTable).join('');
    html += '<div class="torder-btns"><button class="btn btn-primary" data-act="send">确认发送</button>'
      + '<button class="btn btn-ghost" data-act="discard">放弃</button></div>';
    el.innerHTML = html;
    el.querySelector('[data-act="discard"]').addEventListener('click', () => {
      el.remove();
      if (srcBtn) { srcBtn.disabled = false; srcBtn.textContent = '申请调拨'; }
    });
    el.querySelector('[data-act="send"]').addEventListener('click', async (ev) => {
      const btn = ev.currentTarget;
      btn.disabled = true;
      btn.textContent = '提交中…';
      const errs = [];
      while (pending.length) {
        const g = pending[0];
        const idx = Number(el.dataset.next || 0);
        // 未改数 → 按算法分好的物料明细原样建单；改过数 → 按占比分摊到各物料
        const orderItems = [];
        (g.rows || []).forEach((r) => {
          const mats = (Number(r.quantity) === Number(r.originQuantity))
            ? (r.materials || [])
            : scaleMaterials(r.materials || [], Number(r.quantity) || 0);
          mats.forEach((m) => {
            if ((Number(m.quantity) || 0) > 0) {
              orderItems.push({
                materialCode: m.materialCode,
                model: r.model,
                materialName: m.materialName || r.materialName || '',
                quantity: Number(m.quantity),
              });
            }
          });
        });
        const dirEl = el.querySelectorAll('.olist-sect')[idx];
        try {
          if (!orderItems.length) throw new Error('无有效明细');
          const vo = await apiPost('/api/transferOrder/create', {
            operationType: g.op,
            fromWarehouse: g.fromWarehouse,
            toWarehouse: g.toWarehouse,
            creator: AUTH.userCode,
            source: 'MANUAL',
            suggestionId: sid || undefined,
            items: orderItems,
          });
          if (dirEl) dirEl.querySelector('.olist-dir')
            .insertAdjacentHTML('beforeend', '<span class="tag tag-green">✓ 已发送</span>');
          aAddOrderCard(vo);
        } catch (e) {
          errs.push(g.fromName + ' → ' + g.toName + '：' + e.message);
          if (dirEl) dirEl.querySelector('.olist-dir')
            .insertAdjacentHTML('beforeend', '<span class="tag tag-red">✕ 发送失败</span>');
        }
        pending.shift();
        el.dataset.next = idx + 1;
      }
      loadNotices();
      const dtag = el.querySelector('[data-dtag]');
      if (errs.length) {
        if (dtag) { dtag.textContent = '部分未发送'; dtag.className = 'tag tag-red'; }
        toast('部分组发送失败：' + errs.join('；'), 'err');
        if (srcBtn) { srcBtn.disabled = false; srcBtn.textContent = '申请调拨'; }
        btn.textContent = '已处理';
      } else {
        if (dtag) { dtag.textContent = '已全部发送'; dtag.className = 'tag tag-green'; }
        btn.textContent = '已发送';
        el.querySelector('[data-act="discard"]').disabled = true;
      }
      btn.disabled = true;   // 全部组已处理过，防重复发送
    });
    appendCardEl(el);
    scrollBottom();
  }

  /** 编排层返回的 transfer_order 卡 → 拉全量 VO 渲染成可交互工单卡（按角色给按钮）。 */
  async function renderOrderCardFromResult(card) {
    const rows = (card && card.rows) || [];
    if (!rows.length) { aSysHint('未创建工单：' + ((card && (card.message || card.title)) || '')); return; }
    for (const r of rows) {
      if (!r.orderNo) continue;
      try {
        aAddOrderCard(await apiGet('/api/transferOrder/get?orderNo=' + encodeURIComponent(r.orderNo)));
      } catch (ignored) {
        aSysHint('工单已创建：' + r.orderNo + '（' + (r.fromWarehouseName || r.fromWarehouse) + ' → '
          + (r.toWarehouseName || r.toWarehouse) + '，' + (r.totalQuantity == null ? '—' : r.totalQuantity) + ' 台）');
      }
    }
  }

  /* ================================================================
   * 调拨工单闭环
   * ================================================================ */
  const ORDER = {};   // orderNo → VO
  const STATUS_LABEL = {
    // 环节状态（2026-10-09）：徽标直接写「当前卡在哪个环节」
    APPLIED: '待确认出库', OUTBOUND: '待确认入库', DONE: '已完成', REJECTED: '已驳回',
    // 历史状态（2026-10-08 前旧账管/仓管闭环，仅老单据展示）
    PENDING: '待发送(历史)', SENT: '待账管确认(历史)', ACCOUNT_CONFIRMED: '待仓管确认(历史)',
  };
  const STATUS_CLS = {
    APPLIED: 'tag-orange', OUTBOUND: 'tag-orange', DONE: 'tag-green', REJECTED: 'tag-red',
    PENDING: 'tag-gray', SENT: 'tag-gray', ACCOUNT_CONFIRMED: 'tag-gray',
  };
  /** 工单派生描述名（2026-10-09）：列表/卡片标题用「调出仓名 → 调入仓名 · N 台」，
   *  不再拿 TR 单号当标题；单号降为详情卡副行小字。 */
  const whName = (code) => (code && WH_NAMES[code]) || code || '';
  const orderName = (r) => (r.fromWarehouseName || whName(r.fromWarehouse))
    + ' → ' + (r.toWarehouseName || whName(r.toWarehouse))
    + ' · ' + (r.totalQuantity == null ? '—' : r.totalQuantity) + ' 台';
  const myWh = () => (AUTH && AUTH.warehouseCode) || '';
  const isAccMgr = () => !!AUTH && (AUTH.role === 'ACCOUNT_MANAGER' || /ACCOUNT/i.test(AUTH.role || ''));
  const isKeeper = () => !!AUTH && (AUTH.role === 'WAREHOUSE_KEEPER' || /KEEPER/i.test(AUTH.role || ''));
  /** 时间显示统一入口（2026-10-08 修：接口存在两种时间形态）。
   *  ① 带时区的 ISO（Jackson 3 对 java.util.Date 的默认输出，如 2026-10-08T01:04:20.000Z）
   *     → 交给 Date 解析后转浏览器本地时间。修复前直接字符串截断，等于把 UTC 当北京时间显示，
   *     通知 / 工单列表的时间偏 8 小时；
   *  ② 已是「yyyy-MM-dd HH:mm:ss」的北京时间字符串（后端 VO 的 SimpleDateFormat 输出）→ 原样截断。
   */
  const fmtTime = (t) => {
    if (!t) return '—';
    const s = String(t);
    if (/^\d{4}-\d{2}-\d{2}T/.test(s)) {
      const d = new Date(s);
      if (!Number.isNaN(d.getTime())) {
        const p = (n) => String(n).padStart(2, '0');
        return esc(d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
          + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()));
      }
    }
    return esc(s.replace('T', ' ').slice(0, 16));
  };

  /* ---- 库存健康检查（2026-10-07 口径统一） ----
   * 健康度/统计来自 agent 快查接口；补货建议改调编排层 /orchestrator/v1/replenish/preview
   * （与聊天「补货建议」同一个算法：分布口径），结束"同一仓两个入口两套建议"。
   */
  async function runHealthCheck() {
    showView('chat'); setNavActive('chat');
    if (!AUTH) { toast('请先登录', 'err'); return; }
    const wh = myWh();
    if (!wh) { aAdd('a', '未获取到你的仓库编码，无法执行本仓健康检查。'); return; }
    const load = aAdd('a', '正在分析本仓库存健康度…');
    try {
      const [d, pv] = await Promise.all([
        apiGet('/api/terminal/health/suggest?warehouseCode=' + encodeURIComponent(wh)),
        apiPost('/api/orchestrator/v1/replenish/preview?warehouse_code=' + encodeURIComponent(wh), {}),
      ]);
      load.remove();
      renderHealthCard(d, pv || {});
    } catch (e) {
      load.remove();
      aAdd('a', '库存健康检查失败：' + esc(e.message));
    }
  }
  function renderHealthCard(d, pv) {
    const stMap = { HEALTHY: ['健康', 'tag-green'], WATCH: ['关注', 'tag-orange'], RISK: ['风险', 'tag-red'] };
    const st = stMap[d.healthStatus] || [d.healthStatus || '—', 'tag-gray'];
    const wh = myWh();
    const transfers = pv.transfers || [];
    const procurements = pv.procurements || [];
    const inflow = transfers.filter((t) => t.toWarehouse === wh);    // 调入本仓：可一键建单
    const others = transfers.filter((t) => t.toWarehouse !== wh);    // 调出/收回：只读提示
    const el = document.createElement('div');
    el.className = 'hcard';
    let html = '<div class="hcard-h">🏥 库存健康检查 · ' + esc(d.warehouseName || '') + '<span class="tag ' + st[1] + '">' + esc(st[0]) + '</span></div>';
    html += '<div class="hcard-sum">' + esc(d.summary || '暂无总结')
      + '\n本仓库存 ' + esc(d.totalStock == null ? '—' : d.totalStock) + ' 台 · 可用 '
      + esc(d.availableQty == null ? '—' : d.availableQty) + ' 台 · 长库龄 '
      + esc(d.longAgingQty == null ? '—' : d.longAgingQty) + ' 台</div>';
    html += '<div class="hcard-sum" style="margin-top:6px">🛒 补货建议（与智能对话同口径）：调拨 '
      + transfers.length + ' 条 · 采购 ' + procurements.length + ' 条'
      + '<button class="btn btn-ghost" id="hcOpenChat" style="margin-left:10px;padding:2px 10px">在对话中处理（可编辑清单）</button></div>';
    if (!transfers.length && !procurements.length) {
      html += '<div class="torder-none">暂无补货建议</div>';
    }
    inflow.forEach((t, i) => {
      html += '<div class="sug">'
        + '<div class="sug-main">'
        + '<div class="sug-model">' + esc(t.model || '') + ' ' + esc(t.materialName || '') + '</div>'
        + '<div class="sug-sub">从 ' + esc(t.fromWarehouseName || t.fromWarehouse) + '（' + esc(t.fromWarehouse)
        + '）调入本仓 · 建议调入 <span class="sug-qty">' + (Number(t.quantity) || 0) + ' 台</span></div>'
        + (t.reason ? '<div class="sug-reason">' + esc(t.reason) + '</div>' : '')
        + '</div>'
        + '<div class="sug-acts">'
        + '<button class="btn btn-primary sug-go" data-i="' + i + '">提交调拨工单</button>'
        + '<button class="btn btn-ghost sug-skip" data-i="' + i + '">暂不处理</button>'
        + '</div></div>';
    });
    others.forEach((t) => {
      html += '<div class="sug"><div class="sug-main">'
        + '<div class="sug-model">' + esc(t.model || '') + ' ' + esc(t.materialName || '') + '</div>'
        + '<div class="sug-sub">' + (t.operationType === 'RECALL' ? '收回' : '调出') + '：从 '
        + esc(t.fromWarehouseName || t.fromWarehouse) + ' → ' + esc(t.toWarehouseName || t.toWarehouse)
        + ' · ' + (Number(t.quantity) || 0) + ' 台（本仓为调入方，提交申请后由调出仓仓管确认出库）</div>'
        + (t.reason ? '<div class="sug-reason">' + esc(t.reason) + '</div>' : '')
        + '</div></div>';
    });
    procurements.forEach((p) => {
      html += '<div class="sug"><div class="sug-main">'
        + '<div class="sug-model">' + esc(p.model || '') + ' ' + esc(p.materialName || '') + '</div>'
        + '<div class="sug-sub">采购建议：' + esc(p.toWarehouseName || p.toWarehouse || '本仓')
        + ' 缺口 <span class="sug-qty">' + (Number(p.suggestedQty) || 0) + ' 台</span>（省内可调拨库存不足）</div>'
        + (p.reason ? '<div class="sug-reason">' + esc(p.reason) + '</div>' : '')
        + '</div></div>';
    });
    el.innerHTML = html;
    const openBtn = el.querySelector('#hcOpenChat');
    if (openBtn) openBtn.onclick = () => aAsk('给我一份补货建议');
    el.querySelectorAll('.sug-go').forEach((b) =>
      b.addEventListener('click', () => {
        const t = inflow[Number(b.dataset.i)];
        openReplenish({
          fromWarehouse: t.fromWarehouse, fromWarehouseName: t.fromWarehouseName,
          materialCode: t.materialCode, model: t.model, materialName: t.materialName,
          suggestQty: Number(t.quantity) || 1,
        });
      }));
    // 暂不处理：只把这张卡片标记为已决策，不发任何请求 —— 建议本来就不是必须执行的
    el.querySelectorAll('.sug-skip').forEach((b) =>
      b.addEventListener('click', () => {
        const row = b.closest('.sug');
        if (row) {
          row.querySelector('.sug-acts').innerHTML = '<span class="sug-skip-note">已跳过，本次不建单</span>';
        }
      }));
    appendCardEl(el);
    scrollBottom();
  }

  /* ---- 去补货：提交调拨申请 ---- */
  function openReplenish(s) {
    if (!s) return;
    if (!isAccMgr()) { toast('只有账管可以发起调拨申请', 'err'); return; }
    openModal(
      '<h3>提交调拨申请</h3>'
      + '<div class="field"><label>调拨方向</label><div class="ro">' + esc(s.fromWarehouseName || s.fromWarehouse)
      + '（' + esc(s.fromWarehouse) + '） → ' + esc(AUTH.warehouseName || myWh()) + '（本仓）</div></div>'
      + '<div class="field"><label>型号 / 物料</label><div class="ro">' + esc(s.model || '') + ' ' + esc(s.materialName || '') + '</div></div>'
      + '<div class="field"><label>调拨数量（台，默认 ' + esc(s.suggestQty) + '）</label>'
      + '<input id="mQty" type="number" min="1" value="' + esc(s.suggestQty) + '" /></div>'
      + '<div class="modal-btns"><button class="btn btn-ghost" data-x="cancel">暂不处理</button>'
      + '<button class="btn btn-primary" data-x="ok">提交调拨申请</button></div>',
      (box) => {
        box.querySelector('[data-x="cancel"]').onclick = closeModal;
        box.querySelector('[data-x="ok"]').onclick = async () => {
          const qty = Number(box.querySelector('#mQty').value) || Number(s.suggestQty) || 1;
          const bs = box.querySelectorAll('button');
          bs.forEach((b) => { b.disabled = true; });
          try {
            // 提交申请（2026-10-08 链路重构：申请直达调出仓仓管，不再有账管确认/发送环节）
            const vo = await apiPost('/api/transferOrder/create', {
              operationType: 'TRANSFER',
              fromWarehouse: s.fromWarehouse,
              toWarehouse: myWh(),
              creator: AUTH.userCode,
              source: 'MANUAL',
              items: [{ materialCode: s.materialCode, model: s.model, materialName: s.materialName, quantity: qty }],
            });
            closeModal();
            aAddOrderCard(vo);
            toast('调拨申请已提交，等待 ' + (s.fromWarehouseName || s.fromWarehouse) + ' 仓管确认出库：' + (vo.orderNo || ''));
            loadNotices();
          } catch (e) {
            toast('提交申请失败：' + e.message, 'err');
            bs.forEach((b) => { b.disabled = false; });
          }
        };
      });
  }

  /* ---- 工单卡片 ---- */
  function aAddOrderCard(vo) {
    const el = orderCardEl(vo);
    appendCardEl(el);
    scrollBottom();
  }
  function pendingText(vo) {
    const st = vo.status;
    const fromName = esc(vo.fromWarehouseName || vo.fromWarehouse);
    const mine = myWh();
    switch (st) {
      case 'APPLIED':
        return '⏳ 等待 ' + fromName + ' 仓管导入 SN 并确认出库'
          + (mine && mine === vo.fromWarehouse && isKeeper() ? '（待你操作）' : '');
      case 'OUTBOUND':
        return '🚚 已出库，在途运输中'
          + (mine && mine === vo.toWarehouse && isKeeper() ? '，待你确认入库' : '');
      case 'DONE': return '✅ 已完成，库存已到账';
      case 'REJECTED': return '✕ 已驳回' + (vo.rejectReason ? ('：' + esc(vo.rejectReason)) : '');
      default: return vo.pendingAction ? esc(vo.pendingAction) : '';
    }
  }
  function buttonsFor(vo) {
    const st = vo.status, mine = myWh();
    let b = '';
    // 链路（2026-10-08 重构）：A 账管申请 → B 仓管导入 SN 出库 → A 仓管入库确认/驳回
    if (st === 'APPLIED' && mine && mine === vo.fromWarehouse && isKeeper()) {
      b += '<button class="btn btn-primary" data-act="outbound">导入 SN 并确认出库</button>';
      b += '<button class="btn btn-danger" data-act="reject">拒绝申请</button>';
    }
    if (st === 'OUTBOUND' && mine && mine === vo.toWarehouse && isKeeper()) {
      b += '<button class="btn btn-primary" data-act="inbound">同意入库</button>';
      b += '<button class="btn btn-danger" data-act="reject">驳回入库</button>';
    }
    return b || '<span class="torder-none">当前状态无需你操作</span>';
  }
  function orderCardEl(vo) {
    ORDER[vo.orderNo] = vo;
    const st = vo.status || 'APPLIED';
    const el = document.createElement('div');
    el.className = 'torder';
    el.dataset.order = vo.orderNo;
    const tl = [
      ['申请', vo.createdAt],
      ['出库', vo.outboundAt],
      ['入库', vo.inboundAt],
    ];
    let html = '<div class="torder-h"><span class="torder-no">' + esc(orderName(vo)) + '</span>'
      + '<span class="tag ' + (STATUS_CLS[st] || 'tag-gray') + '">' + esc(vo.statusLabel || STATUS_LABEL[st] || st) + '</span></div>'
      + '<div class="torder-no-sub">单号 ' + esc(vo.orderNo) + '</div>';
    // 工单信息改四列表格（2026-10-08）：调出仓｜调入仓｜数量｜创建人，替代原先分散的文本行
    html += '<table class="torder-info"><tbody>'
      + '<tr><th>调出仓</th><td>' + esc([vo.fromWarehouse, vo.fromWarehouseName].filter(Boolean).join(' ')) + '</td>'
      + '<th>调入仓</th><td>' + esc([vo.toWarehouse, vo.toWarehouseName].filter(Boolean).join(' ')) + '</td></tr>'
      + '<tr><th>数量</th><td>' + esc(vo.totalQuantity == null ? '—' : vo.totalQuantity) + ' 台</td>'
      + '<th>创建人</th><td>' + esc(vo.creator || '—') + '</td></tr>'
      + '</tbody></table>';
    // 明细改为报表式表格（2026-10-08）：型号 | 物料名称 | 数量(台)，与图表「表格」同一套 .ptable 样式
    const items = vo.items || [];
    if (items.length) {
      html += '<div class="torder-items"><table class="ptable"><thead><tr>'
        + '<th>型号</th><th>物料名称</th><th class="num">数量(台)</th></tr></thead><tbody>'
        + items.map((it) => '<tr><td>' + esc(it.model || '—') + '</td>'
          + '<td>' + esc(it.materialName || '—') + '</td>'
          + '<td class="num">' + esc(it.quantity == null ? '—' : it.quantity) + '</td></tr>').join('')
        + '</tbody></table></div>';
    }
    html += '<div class="tl">' + tl.map(([n, t]) =>
      '<div class="tl-item' + (t ? '' : ' off') + '"><span class="tl-dot"></span>' + n
      + '<span class="tl-time">' + fmtTime(t) + '</span></div>').join('') + '</div>';
    const pend = pendingText(vo);
    if (pend) html += '<div class="torder-hint">' + pend + '</div>';
    html += '<div class="torder-btns">' + buttonsFor(vo) + '</div>';
    el.innerHTML = html;
    el.querySelectorAll('[data-act]').forEach((b) =>
      b.addEventListener('click', () => onOrderAction(vo.orderNo, b.dataset.act)));
    return el;
  }
  function refreshOrders(vo) {
    if (!vo || !vo.orderNo) return;
    document.querySelectorAll('.torder[data-order="' + vo.orderNo + '"]').forEach((old) => {
      old.replaceWith(orderCardEl(vo));
    });
  }
  async function onOrderAction(orderNo, act) {
    if (!AUTH) { toast('请先登录', 'err'); return; }
    if (act === 'reject') { openReject(orderNo); return; }
    if (act === 'outbound') { openOutbound(orderNo); return; }
    const urlMap = { inbound: '/api/transferOrder/inbound' };
    const url = urlMap[act];
    if (!url) return;
    document.querySelectorAll('.torder[data-order="' + orderNo + '"]').forEach((c) =>
      c.querySelectorAll('button').forEach((b) => { b.disabled = true; }));
    try {
      const vo = await apiPost(url, { orderNo, operator: AUTH.userCode });
      refreshOrders(vo);
      loadNotices();
      if (vo.status === 'DONE') {
        const q = vo.movedQuantity == null ? vo.totalQuantity : vo.movedQuantity;
        aSysHint('入库完成：' + (vo.fromWarehouseName || vo.fromWarehouse) + ' -' + q
          + ' 台 → ' + (vo.toWarehouseName || vo.toWarehouse) + ' +' + q + ' 台');
      } else {
        toast('已更新：' + (vo.statusLabel || STATUS_LABEL[vo.status] || vo.status));
      }
    } catch (e) {
      toast('操作失败：' + e.message, 'err');
      refreshOrders(ORDER[orderNo] || null);
    }
  }

  /**
   * 出库确认（B 仓管）：下载 SN 模板 → 填好上传（CSV/TXT）→ 校验数量 → 确认出库。
   * 后端做「型号/数量/状态/在仓」四重比对，模板里的 SN 必须全部属于本仓可用库存。
   */
  function openOutbound(orderNo) {
    const vo = ORDER[orderNo] || {};
    const need = vo.totalQuantity == null ? 0 : vo.totalQuantity;
    openModal(
      '<h3>导入 SN 并确认出库</h3>'
      + '<div class="field"><label>工单</label><div class="ro">' + esc(orderNo) + ' · '
      + esc(vo.fromWarehouseName || vo.fromWarehouse || '') + ' → '
      + esc(vo.toWarehouseName || vo.toWarehouse || '') + ' · 需出库 ' + need + ' 台</div></div>'
      + '<div class="field"><label>SN 清单（模板为 CSV，可用 Excel 打开编辑后另存为 CSV 上传）</label>'
      + '<div class="sn-acts">'
      + '<button class="btn btn-ghost" data-x="tpl">下载 SN 模板</button>'
      + '<button class="btn btn-ghost" data-x="gen">⚡ 生成所需SN文件</button>'
      + '<label class="btn btn-ghost sn-file">选择文件<input id="mSnFile" type="file" accept=".csv,.txt" class="sn-file-input" /></label>'
      + '</div><div class="sn-preview" id="mSnPreview">未选择文件</div></div>'
      + '<div class="modal-btns"><button class="btn btn-ghost" data-x="cancel">取消</button>'
      + '<button class="btn btn-primary" data-x="ok" disabled>确认出库</button></div>',
      (box) => {
        let sns = [];
        const preview = box.querySelector('#mSnPreview');
        const okBtn = box.querySelector('[data-x="ok"]');
        box.querySelector('[data-x="cancel"]').onclick = closeModal;
        box.querySelector('[data-x="tpl"]').onclick = () => downloadSnTemplate(orderNo, need);
        // 实验功能（演示用）：按工单明细从调出仓可用库存抓 SN，生成可直接导入的 CSV ——
        // 与出库接口同一套取数口径，导出的文件必过四重校验；只读，不动库存
        box.querySelector('[data-x="gen"]').onclick = async () => {
          preview.textContent = '正在生成 SN 清单…';
          try {
            const rows = await apiGet('/api/transferOrder/snFile?orderNo=' + encodeURIComponent(orderNo));
            const lines = ['serialNo'];
            const lack = [];
            let total = 0;
            for (const r of (rows || [])) {
              const got = r.serialNos || [];
              if (got.length < (r.quantity || 0)) {
                lack.push((r.materialCode || '?') + '（' + got.length + '/' + r.quantity + '）');
              }
              for (const s of got) { lines.push(s); total += 1; }
            }
            if (!total) { preview.textContent = '调出仓可用库存为空，无法生成'; return; }
            const blob = new Blob(['\uFEFF' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
            const a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = 'SN清单_' + orderNo + '.csv';
            document.body.appendChild(a);
            a.click();
            setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 0);
            preview.textContent = '已生成并下载 SN清单_' + orderNo + '.csv（' + total + ' 个 SN，需 ' + need + '）'
              + ' —— 点「选择文件」导入后确认出库即可'
              + (lack.length ? '；⚠ 以下物料可用不足，导入会校验失败：' + lack.join('、') : '');
          } catch (e) {
            preview.textContent = '生成失败：' + e.message;
          }
        };
        box.querySelector('#mSnFile').addEventListener('change', (ev) => {
          const f = ev.target.files && ev.target.files[0];
          if (!f) return;
          const reader = new FileReader();
          reader.onload = () => {
            sns = parseSnText(String(reader.result || ''));
            preview.textContent = '已解析 ' + sns.length + ' 个 SN（需 ' + need + ' 个）'
              + (sns.length ? '：' + sns.slice(0, 3).join('、') + (sns.length > 3 ? ' …' : '') : '');
            okBtn.disabled = sns.length === 0;
          };
          reader.onerror = () => toast('文件读取失败', 'err');
          reader.readAsText(f, 'utf-8');
        });
        okBtn.onclick = async () => {
          if (!sns.length) return;
          const bs = box.querySelectorAll('button');
          bs.forEach((b) => { b.disabled = true; });
          try {
            const vo2 = await apiPost('/api/transferOrder/outbound',
              { orderNo, operator: AUTH.userCode, serialNos: sns });
            closeModal();
            refreshOrders(vo2);
            loadNotices();
            toast('已确认出库：' + sns.length + ' 台在途，等待调入方仓管入库确认');
          } catch (e) {
            toast('出库失败：' + e.message, 'err');
            bs.forEach((b) => { b.disabled = false; });
            okBtn.disabled = !sns.length;
          }
        };
      });
  }

  /** SN 文本解析：按行/逗号/分号/空白拆分，去掉表头与空项，去重（Excel 另存的 CSV 通用）。 */
  function parseSnText(text) {
    const out = [];
    const seen = {};
    String(text).split(/[\s,，;；]+/).forEach((t) => {
      const s = t.trim().replace(/^"|"$/g, '');
      if (!s || /^serial.?no$/i.test(s) || s === '串码') return;
      if (seen[s]) return;
      seen[s] = 1;
      out.push(s);
    });
    return out;
  }

  /** 下载 SN 模板：CSV 表头 serialNo + 按工单数量预置空行（带 BOM，Excel 直接双击可编辑）。 */
  function downloadSnTemplate(orderNo, need) {
    const rows = ['serialNo'];
    const n = Math.max(need || 0, 1);
    for (let i = 0; i < n; i += 1) rows.push('');
    const blob = new Blob(['\uFEFF' + rows.join('\r\n')], { type: 'text/csv;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'SN模板_' + orderNo + '.csv';
    document.body.appendChild(a);
    a.click();
    setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 0);
  }

  function openReject(orderNo) {
    const vo = ORDER[orderNo] || {};
    const inbound = vo.status === 'OUTBOUND';
    const hint = inbound
      ? '驳回后：在途货品将退回 ' + esc(vo.fromWarehouseName || vo.fromWarehouse || '调出仓') + ' 可用库存'
      : '拒绝后：本次申请作废，不会产生任何库存变动';
    openModal(
      '<h3>' + (inbound ? '驳回入库' : '拒绝调拨申请') + '</h3>'
      + '<div class="field"><label>驳回原因（必填）</label>'
      + '<textarea id="mReason" rows="3" placeholder="请填写驳回原因"></textarea></div>'
      + '<div class="torder-none">' + hint + '</div>'
      + '<div class="modal-btns"><button class="btn btn-ghost" data-x="cancel">取消</button>'
      + '<button class="btn btn-primary" data-x="ok">确认驳回</button></div>',
      (box) => {
        box.querySelector('[data-x="cancel"]').onclick = closeModal;
        box.querySelector('[data-x="ok"]').onclick = async () => {
          const reason = box.querySelector('#mReason').value.trim();
          if (!reason) { toast('请填写驳回原因', 'err'); return; }
          const bs = box.querySelectorAll('button');
          bs.forEach((b) => { b.disabled = true; });
          try {
            const vo2 = await apiPost('/api/transferOrder/reject', { orderNo, operator: AUTH.userCode, reason });
            closeModal();
            refreshOrders(vo2);
            toast(inbound ? '已驳回入库，货品退回调出仓' : '已拒绝该调拨申请');
            loadNotices();
          } catch (e) {
            toast('驳回失败：' + e.message, 'err');
            bs.forEach((b) => { b.disabled = false; });
          }
        };
      });
  }

  /* ---- 工单列表视图 ---- */
  let ordersTab = 'todo';
  function orderItemHTML(r) {
    const st = r.status || 'APPLIED';
    return '<div class="oitem" data-no="' + esc(r.orderNo) + '">'
      // 2026-10-11 用户定稿：**调拨工单号作主标题（第一行放大）** —— 单号是业务核对主键；
      // 派生名（仓名→仓名 · N 台）与仓编码降为副行。
      + '<div><div class="no' + (r.orderNo ? ' tr-no' : '') + '">'
      + esc(r.orderNo || orderName(r)) + '</div>'
      + '<div class="dir">' + esc(orderName(r))
      + ((r.fromWarehouse || r.toWarehouse)
          ? ' · ' + esc((r.fromWarehouse || '') + ' → ' + (r.toWarehouse || '')) : '')
      + '</div></div>'
      + '<div class="right"><span class="tag ' + (STATUS_CLS[st] || 'tag-gray') + '">' + esc(STATUS_LABEL[st] || st) + '</span>'
      + '<div style="margin-top:6px">' + fmtTime(r.createdAt) + '</div></div></div>';
  }
  async function loadOrders(tab) {
    ordersTab = tab || ordersTab;
    document.querySelectorAll('#ordersTabs .tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === ordersTab));
    $('ordersDetail').innerHTML = '';
    const host = $('ordersList');
    if (!AUTH) { host.innerHTML = '<div class="olist-empty">请先登录</div>'; return; }
    let q = '';
    let pageSize = 20;
    if (ordersTab === 'todo') {
      // 新链路待办（2026-10-08）：仓管 = 本仓为调出仓的「待确认出库」+ 本仓为调入仓的「待确认入库」
      if (!isKeeper()) {
        host.innerHTML = '<div class="olist-empty">账管在调拨链路中负责「发起申请」，工单流转由双方仓管处理；'
          + '可切换到「我发起的」或「全部」查看。</div>';
        return;
      }
      pageSize = 50;   // 待办要在客户端合并两类状态，多取一页
    } else if (ordersTab === 'mine') {
      q = '&toWarehouse=' + encodeURIComponent(myWh());
    }
    host.innerHTML = '<div class="olist-empty">加载中…</div>';
    try {
      await visibleWarehouses();   // 数据可见范围：本仓 + 下级仓
      // 筛选条件（2026-10-09）：状态（环节）+ 创建时间范围
      const fSt = $('ofStatus') ? $('ofStatus').value : '';
      const fFrom = $('ofFrom') ? $('ofFrom').value : '';
      const fTo = $('ofTo') ? $('ofTo').value : '';
      let q2 = '';
      if (fSt) q2 += '&status=' + encodeURIComponent(fSt);
      if (fFrom) q2 += '&startTime=' + encodeURIComponent(fFrom);
      if (fTo) q2 += '&endTime=' + encodeURIComponent(fTo);
      const d = await apiGet('/api/transferOrder/list/page?current=1&pageSize=' + pageSize + q + q2);
      let recs = (d && d.records) || [];
      if (ordersTab === 'mine') recs = recs.filter((r) => r.toWarehouse === myWh() || r.creator === AUTH.userCode);
      if (ordersTab === 'todo') {
        recs = recs.filter((r) =>
          (r.status === 'APPLIED' && r.fromWarehouse === myWh())
          || (r.status === 'OUTBOUND' && r.toWarehouse === myWh()));
      }
      // 全部/我发起的：只保留与本仓（或下级仓）相关的单据
      recs = recs.filter((r) => inScope(r.fromWarehouse) || inScope(r.toWarehouse));
      if (!recs.length) { host.innerHTML = '<div class="olist-empty">暂无工单</div>'; return; }
      host.innerHTML = '<div class="olist">' + recs.map(orderItemHTML).join('') + '</div>';
      host.querySelectorAll('.oitem').forEach((it) =>
        it.addEventListener('click', () => openInlineOrderDetail(it, it.dataset.no)));
    } catch (e) {
      host.innerHTML = '<div class="olist-empty">加载失败：' + esc(e.message) + '</div>';
    }
  }

  /**
   * 列表内联展开工单详情（2026-10-07 修）：在点击的那一行下方就地展开，
   * 不再把整个列表滚回顶部（旧实现 scrollIntoView + scrollTop=0 会丢失浏览位置）。
   * 再次点击同一行 = 收起。
   */
  async function openInlineOrderDetail(anchorEl, orderNo) {
    const existing = anchorEl.nextElementSibling;
    if (existing && existing.classList.contains('oitem-detail')) { existing.remove(); return; }
    document.querySelectorAll('.oitem-detail').forEach((d) => d.remove());   // 同时只展开一份
    const box = document.createElement('div');
    box.className = 'oitem-detail';
    box.innerHTML = '<div class="olist-empty">加载详情…</div>';
    anchorEl.after(box);
    try {
      const vo = await apiGet('/api/transferOrder/get?orderNo=' + encodeURIComponent(orderNo));
      box.innerHTML = '';
      const back = document.createElement('button');
      back.className = 'btn btn-ghost';
      back.textContent = '← 收起详情';
      back.onclick = () => box.remove();
      box.appendChild(back);
      box.appendChild(orderCardEl(vo));
    } catch (e) {
      box.innerHTML = '<div class="olist-empty">详情加载失败：' + esc(e.message) + '</div>';
    }
  }

  /** 详情区（从通知点入时用）：滚到详情头露出完整工单卡。 */
  async function openOrderDetail(orderNo) {
    const host = $('ordersDetail');
    host.innerHTML = '<div class="olist-empty">加载详情…</div>';
    try {
      const vo = await apiGet('/api/transferOrder/get?orderNo=' + encodeURIComponent(orderNo));
      host.innerHTML = '';
      const back = document.createElement('button');
      back.className = 'btn btn-ghost';
      back.textContent = '← 返回列表';
      back.onclick = () => { host.innerHTML = ''; };
      host.appendChild(back);
      host.appendChild(orderCardEl(vo));
      host.scrollIntoView({ block: 'nearest' });
    } catch (e) {
      host.innerHTML = '<div class="olist-empty">详情加载失败：' + esc(e.message) + '</div>';
    }
  }
  $('ordersTabs').addEventListener('click', (e) => {
    const t = e.target.closest('.tab');
    if (t) loadOrders(t.dataset.tab);
  });
  // 筛选条件（状态/时间）：任一变化即重查；重置清空三者
  if ($('ofStatus')) $('ofStatus').addEventListener('change', () => loadOrders());
  if ($('ofFrom')) $('ofFrom').addEventListener('change', () => loadOrders());
  if ($('ofTo')) $('ofTo').addEventListener('change', () => loadOrders());
  if ($('ofReset')) $('ofReset').addEventListener('click', () => {
    $('ofStatus').value = '';
    $('ofFrom').value = '';
    $('ofTo').value = '';
    loadOrders();
  });

  /* ================= 通知 ================= */
  async function loadNotices() {
    if (!AUTH) return;
    const q = '?warehouseCode=' + encodeURIComponent(AUTH.warehouseCode || '')
      + '&role=' + encodeURIComponent(AUTH.role || '')
      + '&userCode=' + encodeURIComponent(AUTH.userCode || '')
      + '&limit=20';
    try {
      const list = await apiGet('/api/transferOrder/notices' + q);
      NOTICES = Array.isArray(list) ? list : [];
      renderNotices();
      $('notifDot').style.display = NOTICES.some((n) => !n.isRead) ? 'block' : 'none';
      checkUnreadPopup();   // 2026-10-10：有新的未读通知时主动居中弹窗
    } catch (e) {
      NOTICES = [];
      renderNotices();
    }
  }
  function renderNotices() {
    const box = $('notifList');
    if (!NOTICES.length) { box.innerHTML = '<div class="notif-node"><div class="co">暂无通知</div></div>'; return; }
    box.innerHTML = NOTICES.map((n, i) => {
      const isAlert = n.noticeType === 'STOCK_ALERT';
      return '<div class="notif-node' + (isAlert ? ' alert' : '') + '" data-i="' + i + '">'
        + '<div class="ti">' + (isAlert ? '<span class="tag-alert">库存告警</span>' : '') + esc(n.title || '通知') + '</div>'
        + '<div class="co">' + esc(n.content || '') + '</div>'
        + '<div class="tm">' + fmtTime(n.createdAt) + (n.isRead ? '' : ' · 未读')
        + (n.orderNo ? ' · 调拨单号 ' + esc(n.orderNo) : '')
        + (isAlert ? ' · 点击查看补货建议' : '') + '</div></div>';
    }).join('');
    box.querySelectorAll('.notif-node').forEach((el) => el.addEventListener('click', () => {
      const n = NOTICES[Number(el.dataset.i)];
      $('notifPanel').hidden = true;
      if (!n) return;
      // 库存告警：不回工单页，直接调起数字员工问补仓建议（告警的下一步动作就是"问建议"）
      if (n.noticeType === 'STOCK_ALERT') { openStockAlert(); return; }
      if (n.orderNo) {
        setNavActive('orders'); showView('orders');
        loadOrders('all').then(() => openOrderDetail(n.orderNo));
      }
    }));
  }

  /* ================= 未读通知主动弹窗（2026-10-10） =================
   * 需求：有「最新未读通知」时不再只亮红点等人点铃铛，而是主动弹到页面正中要求去看。
   *
   * 触发策略（用户选定：有新通知才弹一次）：
   *   - 已提示过的通知 id 存 localStorage（跨刷新/重登录仍不重复弹）
   *   - 只有出现「新的未读通知」才弹；点「去看通知」/「稍后」都记为已提示
   *   - 轮询 60s 一次（挂在 afterLogin，只挂一次）
   */
  const NOTIF_SEEN_KEY = 'portal_notified_notice_ids';
  let notifModalEl = null;

  function notifSeenIds() {
    try { return JSON.parse(localStorage.getItem(NOTIF_SEEN_KEY) || '[]'); }
    catch (e) { return []; }
  }
  function markNotifSeen(ids) {
    try {
      const seen = new Set(notifSeenIds());
      ids.forEach((id) => seen.add(String(id)));
      // 只留最近 200 个，防止 localStorage 无限增长
      localStorage.setItem(NOTIF_SEEN_KEY, JSON.stringify([...seen].slice(-200)));
    } catch (e) { /* 隐私模式等场景静默降级为"每次都弹" */ }
  }
  function notifKey(n) { return String(n.id != null ? n.id : (n.orderNo || '') + '|' + (n.createdAt || '')); }

  function showNotifModal(list) {
    if (notifModalEl) notifModalEl.remove();
    const latest = list[0] || {};
    const isAlert = latest.noticeType === 'STOCK_ALERT';
    notifModalEl = document.createElement('div');
    notifModalEl.className = 'notif-modal-mask';
    notifModalEl.innerHTML = '<div class="notif-modal">'
      + '<div class="nm-head">🔔 你有 ' + list.length + ' 条新的未读通知</div>'
      + '<div class="nm-body">'
        + '<div class="nm-title">' + (isAlert ? '<span class="tag-alert">库存告警</span>' : '')
          + esc(latest.title || '通知') + '</div>'
        + '<div class="nm-co">' + esc(latest.content || '') + '</div>'
        + '<div class="nm-time">' + fmtTime(latest.createdAt)
          + (latest.orderNo ? ' · 调拨单号 ' + esc(latest.orderNo) : '') + '</div>'
      + '</div>'
      + '<div class="nm-acts">'
        + '<button class="btn btn-ghost" data-act="later">稍后处理</button>'
        + '<button class="btn btn-primary" data-act="go">去看通知</button>'
      + '</div></div>';
    document.body.appendChild(notifModalEl);
    notifModalEl.querySelectorAll('[data-act]').forEach((b) => b.addEventListener('click', () => {
      const act = b.dataset.act;
      const mask = notifModalEl;
      notifModalEl = null;
      mask.remove();
      if (act === 'go') { $('notifPanel').hidden = false; loadNotices(); }
    }));
  }

  function checkUnreadPopup() {
    if (!AUTH) return;
    const unread = NOTICES.filter((n) => !n.isRead);
    if (!unread.length) return;
    const seen = new Set(notifSeenIds());
    const fresh = unread.filter((n) => !seen.has(notifKey(n)));
    if (!fresh.length) return;
    // 未读按时间倒序，弹最新的前 3 条
    const sorted = unread.slice().sort((a, b) =>
      String(b.createdAt || '').localeCompare(String(a.createdAt || '')));
    showNotifModal(sorted.slice(0, 3));
    markNotifSeen(sorted.map((n) => n.id));
  }

  /**
   * 库存告警 → 生成补仓建议（2026-10-07 收敛）。
   *
   * 之前是前端串两步：先问数字员工（SSE 问答），再单独调健康检查接口追加建议卡。
   * 现在一次提问即可 —— 走编排层「智能路由」入口，意图识别落在「补货建议」路线：
   * 取数 → 算法 → 建议卡（含「申请调拨」按钮）由编排图一次产出，
   * 前端只负责渲染，不再承担"串联业务步骤"的职责（编排在服务端）。
   */
  async function openStockAlert() {
    if (!AUTH) { toast('请先登录', 'err'); return; }
    const wh = myWh();
    if (!wh) { aAdd('a', '未获取到你的仓库编码，无法生成补货建议。'); return; }
    showView('chat'); setNavActive('chat');
    await aAsk('本仓（' + wh + '）收到库存告警，请分析库存水位并给出补仓建议，说明可以从哪个仓调、调多少。');
  }

  /* 通知面板（2026-10-07）：移除「扫描本仓」按钮 —— 巡检是后台自动任务（编排层定时 10 分钟），
     用户端不需要这个入口；需要手动触发时走编排层端点 /orchestrator/v1/alert/scan。 */
  $('notifBtn').addEventListener('click', () => {
    const p = $('notifPanel');
    p.hidden = !p.hidden;
    if (!p.hidden) loadNotices();
  });

  /* ================= 终端管理（业务明细页） =================
   * 2026-10-07：侧栏不再平铺 13 个子菜单 —— 收敛为单个「终端管理」入口，
   * 点击进入二级枢纽页（renderBizHub：13 个入口卡片），再点卡片进具体业务页。
   * 首页 / 库存管理 / 终端查询 三页接**真实数据**并遵守数据可见范围；
   * 其余为流程占位页（真实业务在智能对话里由意图识别办理）。
   */
  const BIZ_MENUS = [
    ['首页', '🏠'], ['库存管理', '📦'], ['领用管理', '📤'], ['装机出库', '🔧'],
    ['回收管理', '♻️'], ['利旧', '🧰'], ['终端信息维护', '📇'], ['终端报废管理', '🗑️'],
    ['故障返厂维修', '🛠️'], ['终端查询', '🔍'], ['终端盘点', '📋'], ['系统管理', '⚙️'],
    ['报表管理', '📊'],
  ];

  /** 终端管理枢纽页：13 个业务入口卡片（二级页入口） */
  function renderBizHub() {
    setNavActive('terminal');
    $('bizBack').hidden = true;
    const cards = BIZ_MENUS.map(([name, icon]) =>
      '<div class="biz-card" data-biz="' + esc(name) + '"><span class="ico">' + icon + '</span>'
      + '<span class="nm">' + esc(name) + '</span></div>').join('');
    $('pageHost').innerHTML =
      '<div class="orders-title">终端管理</div>'
      + '<p class="page-sub">终端全生命周期业务入口：首页 / 库存管理 / 终端查询为真实数据，其余为流程占位（实际业务也可在「智能对话」中直接办理）</p>'
      + '<div class="biz-grid">' + cards + '</div>';
    $('pageHost').querySelectorAll('.biz-card').forEach((el) =>
      el.addEventListener('click', () => openBizPage(el.dataset.biz)));
  }

  function openBizPage(name) {
    showView('page');
    setNavActive('terminal');          // 二级页仍归属「终端管理」，侧栏高亮不变
    $('bizBack').hidden = false;
    $('pageHost').innerHTML = '<div class="orders-title">' + esc(name) + '</div><div class="page-sub">加载中…</div>';
    if (name === '首页') renderBizHome();
    else if (name === '库存管理') renderInventoryPage();
    else if (name === '终端查询') renderQueryPage();
    else renderBizPlaceholder(name);
  }

  function renderBizPlaceholder(name) {
    $('pageHost').innerHTML = '<div class="orders-title">' + esc(name) + '</div>'
      + '<p class="page-sub">该页面为业务流程占位；实际业务可在「智能对话」中直接办理 —— AI 会按意图分流到报表 / 补货建单 / 流程问答。</p>'
      + '<div class="pcard" style="max-width:660px">'
      + '<div class="panel-h">在智能对话里怎么说</div>'
      + '<div class="note">例如：「各仓库存分布」「给我一份补货建议」「' + esc(name) + '怎么操作？」</div>'
      + '<button class="btn btn-primary" id="bizAsk" style="margin-top:12px">去智能对话</button></div>';
    const b = $('bizAsk');
    if (b) b.onclick = () => { showView('chat'); setNavActive('chat'); $('chatInput').focus(); };
  }

  /** 可见范围内的库存明细行（仓位 × 物料 × 型号） */
  async function scopedInventoryRows() {
    const scope = await visibleWarehouses();
    const rows = (await apiGet('/api/terminal/inventory/aggregate')) || [];
    return scope ? rows.filter((r) => scope.has(r.warehouseCode)) : rows;
  }
  function bizNote(count, shown) {
    return '共 ' + count + ' 条物料行' + (shown && count > shown ? ('，仅显示前 ' + shown + ' 条') : '')
      + '；数据范围＝本仓及其下级仓';
  }
  function inventoryTable(rows, limit) {
    const list = rows.slice(0, limit || 50);
    return '<div class="ptable-wrap"><table class="ptable"><thead><tr>'
      + '<th>仓位</th><th>物料编码</th><th>型号</th><th>物料名称</th><th>可用(台)</th><th>总量(台)</th><th>平均库龄(天)</th></tr></thead><tbody>'
      + list.map((r) => '<tr><td>' + esc(r.warehouseName || r.warehouseCode) + '</td>'
        + '<td class="mono">' + esc(r.materialCode || '') + '</td><td>' + esc(r.model || '') + '</td>'
        + '<td>' + esc(r.materialName || '') + '</td><td>' + esc(r.availableQty) + '</td>'
        + '<td>' + esc(r.totalQty) + '</td>'
        + '<td>' + esc(r.avgAgingDays == null ? '—' : Math.round(r.avgAgingDays)) + '</td></tr>').join('')
      + '</tbody></table></div>'
      + '<div class="note">' + bizNote(rows.length, list.length) + '</div>';
  }

  /** 首页：真实统计（可见范围）+ 库存明细预览 */
  async function renderBizHome() {
    try {
      const s = await scopedStats();
      const rows = await scopedInventoryRows();
      const tile = (k, v) => '<div class="metric-item"><div class="v">' + esc(v) + '</div><div class="k">' + k + '</div></div>';
      $('pageHost').innerHTML = '<div class="orders-title">首页</div>'
        + '<p class="page-sub">库存总览' + (s.note ? ('（' + s.note + '）') : '') + '</p>'
        + '<div class="metric-grid">' + tile('库存总量', s.total) + tile('可用库存', s.avail)
        + tile('长库龄', s.aging) + tile('在途', s.transit) + '</div>'
        + '<div class="pcard" style="margin-top:16px"><div class="panel-h">库存明细（前 20 条）</div>'
        + inventoryTable(rows, 20) + '</div>';
    } catch (e) {
      $('pageHost').innerHTML = '<div class="orders-title">首页</div><div class="page-sub">加载失败：' + esc(e.message) + '</div>';
    }
  }

  /** 库存管理：库存明细表（真实数据，按可见范围） */
  async function renderInventoryPage() {
    try {
      const rows = await scopedInventoryRows();
      const totalAvail = rows.reduce((a, r) => a + (Number(r.availableQty) || 0), 0);
      $('pageHost').innerHTML = '<div class="orders-title">库存管理</div>'
        + '<p class="page-sub">按「仓位 × 物料 × 型号」的实时库存（可用合计 ' + totalAvail + ' 台）</p>'
        + '<div class="pcard"><div class="panel-h">库存明细</div>' + inventoryTable(rows, 100) + '</div>';
    } catch (e) {
      $('pageHost').innerHTML = '<div class="orders-title">库存管理</div><div class="page-sub">加载失败：' + esc(e.message) + '</div>';
    }
  }

  /** 终端查询：按型号 / 物料 / 仓位关键字检索（真实数据） */
  function renderQueryPage() {
    $('pageHost').innerHTML = '<div class="orders-title">终端查询</div>'
      + '<p class="page-sub">按型号 / 物料编码 / 物料名称 / 仓位 关键字查询（真实数据，范围为可见仓位）</p>'
      + '<div class="pcard"><input id="qInput" class="q-input" placeholder="如 F657 / 光猫 / 9BZA" />'
      + '<button class="btn btn-primary" id="qBtn" style="margin-left:10px">查询</button></div>'
      + '<div id="qResult" style="margin-top:14px"></div>';
    const run = async () => {
      const kw = String($('qInput').value || '').trim().toLowerCase();
      try {
        const rows = await scopedInventoryRows();
        const hit = kw ? rows.filter((r) => [r.model, r.materialCode, r.materialName, r.warehouseCode, r.warehouseName]
          .some((x) => String(x || '').toLowerCase().includes(kw))) : rows;
        $('qResult').innerHTML = '<div class="pcard"><div class="panel-h">查询结果（'
          + hit.length + ' 条）</div>' + inventoryTable(hit, 50) + '</div>';
      } catch (e) {
        $('qResult').innerHTML = '<div class="pcard"><div class="note">查询失败：' + esc(e.message) + '</div></div>';
      }
    };
    $('qBtn').onclick = run;
    $('qInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') run(); });
  }

  /* ================= 弹窗 ================= */
  function openModal(html, onMount) {
    const box = $('modalBox');
    box.innerHTML = html;
    $('modalMask').hidden = false;
    if (onMount) onMount(box);
    const first = box.querySelector('input, textarea');
    if (first) first.focus();
  }
  function closeModal() { $('modalMask').hidden = true; $('modalBox').innerHTML = ''; }
  $('modalMask').addEventListener('click', (e) => { if (e.target === $('modalMask')) closeModal(); });

  /* ================= 启动 ================= */
  renderQuick();
  aRenderChips();
  $('bizBack').onclick = renderBizHub;   // 二级页返回条 → 回「终端管理」枢纽页
  (function boot() {
    const auth = loadAuth();
    if (auth && auth.token) afterLogin(auth);
    else showLogin();
  })();
})();