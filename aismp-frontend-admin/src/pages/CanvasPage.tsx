/**
 * 画布编排（管理端 · Dify 式操作逻辑，2026-10-11 重构）
 *
 * 理念（产品定稿）：**画布只管编排，节点是已配好的能力，几乎不用调属性**。
 *   - 左侧「节点库」按分类列出可用节点：点一下即加到画布；
 *   - Agent 节点**动态来自管理端「Agent」页配置好的数字员工**（那边配人设/技能/工具，
 *     这边零参数直接用）——配置面与编排面分离；
 *   - 接口节点（13 个已登记接口）已预填 path/method，拖进来即用；
 *   - 只有「HTTP 调用」这个通用节点需要填 path（自定义接口时才用）；
 *   - 提示词覆盖等属「高级」，默认折叠。
 * 设计：docs/Dify编排迁移-工程量评估.md 第十二/十五/十六节。
 * 2026-10-11 下午：样式统一 —— 对齐全站 ag-* 设计语言（满高三栏白卡 + 紫点缀），
 *   reactflow 节点/连线改主题色，版本管理收进右列。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Button, Collapse, Empty, Input, InputNumber, message, Modal, Popconfirm, Select,
  Tabs, Tag, Tooltip, Typography,
} from 'antd';
import ReactFlow, {
  addEdge, Background, Controls, Handle, MarkerType, MiniMap, Position,
  useEdgesState, useNodesState,
} from 'reactflow';
import 'reactflow/dist/style.css';
import { getToken } from '../auth';
import { listAgents } from '../api';

const GUARDS = ['verify_numbers', 'respond'];
const END_ID = '__end__';

async function api(path, options = {}) {
  const token = getToken();
  const resp = await fetch(`/api/orchestrator/v1/graph${path}`, {
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    ...options,
  });
  const body = await resp.json().catch(() => ({}));
  if (!resp.ok) throw Object.assign(new Error(body.detail || `HTTP ${resp.status}`), { status: resp.status, body });
  return body;
}

// 分类 → 圆点色（画布节点与节点库条目共用，保持同一映射直觉）
const CAT_COLOR = {
  agent: '#8577ec',      // AI 智能体：浅紫（主题近亲）
  integration: '#4a90d9',
  data: '#3aa6a6',
  analysis: '#d9974a',
  output: '#7bbf5e',
  guard: '#a0a0a8',
  internal: '#b08fd9',
  custom: '#999999',
  entry: '#534ab7',
};
const catColorOf = (type: string, kind?: string) => {
  if (kind === 'end') return '#c3c3cc';
  if (type === 'agent') return CAT_COLOR.agent;
  return CAT_COLOR[type] || CAT_COLOR.custom;
};

// ---------------------------------------------------------------------------
// 自定义节点（白底紫边卡片；Agent 渐变描边；护栏虚线；结束灰）
// nodeTypes 必须是稳定引用 —— 定义在组件外，否则 reactflow 每次渲染重建节点。
// ---------------------------------------------------------------------------
function CvNode({ data }) {
  const kind = data.kind || 'normal';
  return (
    <div className={`cv-node ${kind}`}>
      <div className="nm">
        <i className="cv-dot" style={{ background: catColorOf(data.type, kind) }} />
        {data.label}
      </div>
      <div className="tp">{data.sub}</div>
      <Handle type="target" position={Position.Left} />
      <Handle type="source" position={Position.Right} />
    </div>
  );
}
const NODE_TYPES = { cv: CvNode };

// ---------------------------------------------------------------------------
// DSL ↔ ReactFlow（布局：BFS 首层定位，环安全）
// ---------------------------------------------------------------------------
function layoutNodes(dsl) {
  const level = { [dsl.entry]: 0 };
  const adj = {};
  const link = (f, t) => { if (t !== END_ID) (adj[f] = adj[f] || []).push(t); };
  (dsl.edges || []).forEach((e) => link(e.from, e.to));
  (dsl.conditional || []).forEach((c) => Object.values(c.map || {}).forEach((t) => link(c.from, t)));
  const queue = [dsl.entry];
  while (queue.length) {
    const cur = queue.shift();
    (adj[cur] || []).forEach((next) => {
      if (level[next] === undefined) { level[next] = level[cur] + 1; queue.push(next); }
    });
  }
  let maxLevel = Math.max(0, ...Object.values(level));
  (dsl.nodes || []).forEach((n) => { if (level[n.id] === undefined) level[n.id] = ++maxLevel; });
  const columns: Record<string, string[]> = {};
  Object.entries(level).forEach(([id, l]) => { (columns[l] = columns[l] || []).push(id); });
  // 布局参数（2026-10-11 视觉调优）：列距 340 / 行距 150，各列按最高列垂直居中，
  // 避免节点挤成一团、连线大面积交叉。
  const COL_W = 340, ROW_H = 150;
  const maxRows = Math.max(...Object.values(columns).map((a) => a.length));
  const pos = {};
  Object.entries(columns).forEach(([l, ids]) => {
    const vOffset = ((maxRows - ids.length) * ROW_H) / 2;
    ids.forEach((id, i) => { pos[id] = { x: Number(l) * COL_W, y: vOffset + i * ROW_H }; });
  });
  return { pos, maxLevel };
}

function toRF(dsl, labelOf) {
  const { pos, maxLevel } = layoutNodes(dsl);
  const nodes = dsl.nodes.map((n) => {
    const kind = GUARDS.includes(n.id) ? 'guard' : (n.type === 'agent' ? 'agent' : 'normal');
    return {
      id: n.id,
      type: 'cv',
      position: pos[n.id] || { x: 0, y: 0 },
      deletable: !GUARDS.includes(n.id) && n.id !== dsl.entry,
      data: {
        label: labelOf ? (labelOf(n) || n.id) : n.id,
        sub: n.type, type: n.type, params: n.params || {}, nodeId: n.id, kind,
        __routes__: (dsl.conditional || []).filter((c) => c.from === n.id),
      },
    };
  });
  nodes.push({
    id: END_ID, type: 'cv',
    position: { x: (maxLevel + 1) * 340, y: 0 },
    deletable: false, draggable: false,
    data: { label: '结束', sub: '__end__', type: '__end__', kind: 'end' },
  });
  // smoothstep 折线 + 圆角：比默认贝塞尔曲线在多分支图上交叉更少、走向更清晰
  const EDGE_COMMON = { type: 'smoothstep', pathOptions: { borderRadius: 12 } };
  const edges = dsl.edges.map((e) => ({
    id: `e-${e.from}-${e.to}`, source: e.from, target: e.to, deletable: true,
    ...EDGE_COMMON,
    markerEnd: { type: MarkerType.ArrowClosed, color: '#a89ee6' },
  }));
  (dsl.conditional || []).forEach((c) =>
    Object.entries(c.map || {}).forEach(([label, target]) => {
      edges.push({
        id: `r-${c.from}-${label}-${target}`, source: c.from, target,
        ...EDGE_COMMON,
        animated: true, deletable: false, label,
        labelStyle: { fontSize: 10, fill: '#6b6b7a' },
        labelBgStyle: { fill: '#f4f2fc' },
        markerEnd: { type: MarkerType.ArrowClosed, color: '#8577ec' },
      });
    }));
  return { nodes, edges };
}

function toDSL(rfNodes, rfEdges) {
  const dsl = {
    entry: 'supervise',
    nodes: rfNodes.filter((n) => n.id !== END_ID)
      .map((n) => ({ id: n.data.nodeId || n.id, type: n.data.type, params: n.data.params || {} })),
    edges: rfEdges.filter((e) => !String(e.id).startsWith('r-'))
      .map((e) => ({ from: e.source, to: e.target })),
    conditional: [],
  };
  rfNodes.forEach((n) => {
    (n.data.__routes__ || []).forEach((c) => {
      if (n.id !== END_ID) dsl.conditional.push({ from: n.data.nodeId || n.id, router: c.router, map: c.map });
    });
  });
  return dsl;
}

// ---------------------------------------------------------------------------
// 页面
// ---------------------------------------------------------------------------
export default function CanvasPage() {
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const [categories, setCategories] = useState([]);   // 节点库（后端）
  const [agents, setAgents] = useState([]);           // Agent 节点（管理端 Agent 页配置）
  const [versions, setVersions] = useState([]);
  const [kw, setKw] = useState('');
  const [editing, setEditing] = useState(null);
  const [editForm, setEditForm] = useState(null);
  const [editTab, setEditTab] = useState('basic');
  const [editingText, setEditingText] = useState('');
  const [metaErr, setMetaErr] = useState(false);   // 元数据加载失败（可重试，不静默装空）

  /** 节点库全部条目（含动态合成的 Agent 节点）：{implType,label,description,paramsDefault,paramsSchema,isAgent}
   *  Agent 节点来自管理端「Agent」页（接口 /admin/agent/list 的真实字段：agentCode/agentName/status，
   *  status 是小写 'active'）—— 那边配好发布，这里刷新即出现。 */
  const libItems = useMemo(() => {
    const out = [];
    (categories || []).forEach((c) => (c.items || []).forEach((it) => out.push({ ...it, catKey: c.key, catLabel: c.label })));
    (agents || []).forEach((a) => {
      const code = a.agentCode || a.code || a.agent_code;
      if (!code) return;
      const name = a.agentName || a.name || code;
      out.push({
        id: 'agent_' + code, implType: 'agent', label: name,
        description: (a.description || '数字员工') + '（人设/技能/工具在「Agent」页配置）',
        paramsDefault: { agent_code: code }, paramsSchema: [], builtin: true,
        catKey: 'agent', catLabel: 'AI 智能体', isAgent: true,
        _hasKb: !!a.knowledgeBaseId,   // 挂了知识库的（数字员工）排序优先
      });
    });
    return out;
  }, [categories, agents]);

  const libGroups = useMemo(() => {
    const order = ['entry', 'agent', 'integration', 'data', 'analysis', 'output', 'guard', 'internal', 'custom'];
    const groups = {};
    libItems.forEach((it) => {
      if (kw && !(it.label + it.description + it.implType).toLowerCase().includes(kw.toLowerCase())) return;
      (groups[it.catKey] = groups[it.catKey] || []).push(it);
    });
    return order.filter((k) => groups[k]).map((k) => ({ key: k, label: groups[k][0].catLabel || k, items: groups[k] }));
  }, [libItems, kw]);

  const schemaFor = useCallback((type) => {
    const hit = libItems.find((i) => i.implType === type);
    return (hit && hit.paramsSchema) || [];
  }, [libItems]);
  const labelForNodeId = useCallback((n) => {
    const hit = libItems.find((i) => i.implType === n.type
      && (!i.paramsDefault || !i.paramsDefault.path || i.paramsDefault.path === (n.params || {}).path));
    return hit ? hit.label : n.id;
  }, [libItems]);
  // 【2026-10-11 429 事故】标签映射放 ref，**不进入 loadDraft 的依赖数组**：
  // 否则 loadMeta → setCategories → libItems → labelForNodeId → loadDraft 变
  // → useEffect 依赖变化重跑 loadMeta → 无限循环请求风暴 → 撞网关限流 429。
  const labelFnRef = useRef(labelForNodeId);
  labelFnRef.current = labelForNodeId;

  const loadDraft = useCallback(async () => {
    try {
      const body = await api('/draft');
      const { nodes: rfNodes, edges: rfEdges } = toRF(body.dsl, labelFnRef.current);
      setNodes(rfNodes);
      setEdges(rfEdges);
    } catch (e) {
      console.error('[canvas] 草稿加载失败', e);
      message.error('画布加载失败：' + (e && e.message ? e.message : e), 6);
    }
  }, [setNodes, setEdges]);

  const loadMeta = useCallback(async () => {
    const [lib, ver, ag] = await Promise.all([
      api('/node-types').catch((e) => { console.warn('[canvas] node-types 失败', e); return null; }),
      api('/versions').catch((e) => { console.warn('[canvas] versions 失败', e); return null; }),
      listAgents().catch((e) => { console.warn('[canvas] Agent 列表拉取失败', e); return null; }),
    ]);
    // 任一失败不再静默装空 —— 标记 metaErr，左列/右列给「重试」入口
    if (!lib || !ver || !ag) { setMetaErr(true); } else { setMetaErr(false); }
    setCategories(lib?.categories || []);
    setVersions((ver?.versions || []).map((r, idx) => ({ ...r, isCurrent: idx === 0 })));
    // 2026-10-11 修正：/admin/agent/list 的 status 是小写 'active'（此前误按大写 'ACTIVE'/数字 1
    // 过滤，导致 9 个 Agent 全被滤掉、节点库「AI 智能体」分类为空 —— 用户截图反馈的正是这个）。
    setAgents((ag || []).filter((a) => {
      const st = String((a && a.status) != null ? a.status : '').toLowerCase();
      return !st || st === 'active' || st === '1' || st === 'enabled';
    }).sort((a, b) => (b.knowledgeBaseId ? 1 : 0) - (a.knowledgeBaseId ? 1 : 0)));
  }, []);
  const didInit = useRef(false);
  useEffect(() => {
    if (didInit.current) return;   // 初始化只跑一次（防依赖变化引发请求循环 → 429）
    didInit.current = true;
    loadMeta().then(loadDraft);
  }, [loadMeta, loadDraft]);

  /** 元数据加载失败后的手动重试（左列/右列的「重试」按钮） */
  const retryMeta = () => { setMetaErr(false); loadMeta().then(loadDraft).catch(() => setMetaErr(true)); };

  const addNode = (item) => {
    const base = String(item.id || item.implType).replace(/[^a-zA-Z0-9_]/g, '_').slice(0, 40);
    setNodes((ns) => {
      let id = base;
      let i = 2;
      while (ns.some((n) => n.id === id)) { id = `${base}_${i++}`; }
      return [...ns, {
        id, type: 'cv', deletable: true,
        position: { x: 180 + Math.random() * 320, y: 140 + Math.random() * 240 },
        data: {
          label: item.label, sub: item.implType, type: item.implType, nodeId: id,
          kind: item.implType === 'agent' ? 'agent' : 'normal',
          params: JSON.parse(JSON.stringify(item.paramsDefault || {})),
        },
      }];
    });
    message.success(`已添加「${item.label}」—— 拖线接入流程，保存/发布即生效`);
  };

  const saveDraft = async () => {
    const dsl = toDSL(nodes, edges);
    const body = await api('/draft', { method: 'PUT', body: JSON.stringify({ dsl }) });
    if (body.errors?.length) {
      Modal.warning({ title: `草稿已存（校验提示 ${body.errors.length} 条）`,
        content: <ul>{body.errors.map((e) => <li key={e}>{e}</li>)}</ul> });
    } else message.success('草稿已保存');
  };

  const publish = async () => {
    const dsl = toDSL(nodes, edges);
    try {
      const body = await api('/publish', { method: 'POST', body: JSON.stringify({ dsl }) });
      message.success(`已发布 v${body.version} —— 新对话立即生效`);
      loadMeta();
    } catch (e) {
      if (e.status === 422) {
        Modal.error({ title: '发布被校验器拦截',
          content: <ul>{(e.body?.detail?.errors || []).map((er) => <li key={er}>{er}</li>)}</ul> });
      } else message.error('发布失败：' + e.message);
    }
  };

  const rollback = async (version) => {
    const body = await api('/rollback', { method: 'POST', body: JSON.stringify({ version }) });
    message.success(`已从 v${version} 回滚 → 新版本 v${body.version}`);
    loadMeta();
  };

  const onNodeDoubleClick = (_, node) => {
    if (node.id === END_ID) return;
    setEditing(node);
    setEditForm({
      nodeId: node.data.nodeId || node.id, type: node.data.type,
      params: node.data.params || {}, __routes__: node.data.__routes__ || [],
    });
    setEditingText(JSON.stringify({
      id: node.data.nodeId || node.id, type: node.data.type,
      params: node.data.params || {}, __routes__: node.data.__routes__ || [],
    }, null, 2));
    setEditTab('basic');
  };

  const patchParams = (patch) => setEditForm((m) => ({ ...m, params: { ...(m.params || {}), ...patch } }));

  const applyEditing = () => {
    try {
      const parsed = editTab === 'json' ? JSON.parse(editingText) : editForm;
      setNodes((ns) => ns.map((n) => (n.id === editing.id
        ? { ...n, data: { ...n.data, type: parsed.type, params: parsed.params || {}, __routes__: parsed.__routes__ || [] } }
        : n)));
      setEditing(null);
      message.success('节点已更新（保存草稿后生效）');
    } catch (e) {
      message.error('JSON 解析失败：' + e.message);
    }
  };

  const onConnect = useCallback(
    (conn) => setEdges((es) => addEdge({
      ...conn, id: `e-${conn.source}-${conn.target}`, deletable: true,
      type: 'smoothstep', pathOptions: { borderRadius: 12 },
      markerEnd: { type: MarkerType.ArrowClosed, color: '#a89ee6' },
    }, es)),
    [setEdges],
  );

  const renderField = (f) => {
    const val = editForm?.params?.[f.key];
    const common = { key: f.key, style: { width: '100%' } };
    let control = null;
    if (f.type === 'select') {
      control = <Select {...common} value={val ?? f.default}
        onChange={(v) => patchParams({ [f.key]: v })}
        options={(f.options || []).map((o) => ({ value: o, label: (f.optionLabels || {})[o] || o }))} />;
    } else if (f.type === 'number') {
      control = <InputNumber {...common} value={val ?? f.default} onChange={(v) => patchParams({ [f.key]: v })} />;
    } else if (f.type === 'textarea' || f.type === 'prompt' || f.type === 'json') {
      const text = f.type === 'json' ? JSON.stringify(val ?? f.default ?? {}, null, 2) : (val ?? '');
      control = <Input.TextArea rows={f.type === 'prompt' ? 6 : 4} style={{ fontFamily: 'monospace' }} value={text}
        onChange={(e) => {
          if (f.type === 'json') {
            try { patchParams({ [f.key]: JSON.parse(e.target.value || '{}') }); } catch { /* 中间态 */ }
          } else patchParams({ [f.key]: e.target.value });
        }} />;
    } else {
      control = <Input {...common} value={val ?? f.default ?? ''} placeholder={f.placeholder}
        onChange={(e) => patchParams({ [f.key]: e.target.value })} />;
    }
    return (
      <div key={f.key} style={{ marginBottom: 8 }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>{f.label}</Typography.Text>
        {control}
      </div>
    );
  };

  const basicFields = schemaFor(editForm?.type).filter((f) => !f.advanced);
  const advancedFields = schemaFor(editForm?.type).filter((f) => f.advanced);

  return (
    <div className="cv-page">
      {/* ── 左列：节点库（Dify 式）── */}
      <div className="cv-col cv-side">
        <div className="cv-col-head">
          <span>节点库</span>
          <span className="cv-col-sub">点击即添加</span>
        </div>
        <div className="cv-lib-search">
          <Input.Search placeholder="搜索节点（名称/类型）" allowClear value={kw}
            onChange={(e) => setKw(e.target.value)} size="small" />
        </div>
        <div className="cv-lib-list">
          {metaErr && (
            <div className="cv-meta-err">
              节点库/版本加载失败（可能撞了网关限流）
              <Button size="small" type="primary" onClick={retryMeta} style={{ marginTop: 8 }}>重试</Button>
            </div>
          )}
          {libGroups.length === 0 && !metaErr && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无匹配节点" />}
          <Collapse ghost size="small" defaultActiveKey={['entry', 'agent', 'integration']}
            items={libGroups.map((g) => ({
              key: g.key,
              label: (
                <span style={{ fontSize: 12, fontWeight: 600, color: '#37384d' }}>
                  <i className="cv-dot" style={{ background: catColorOf(g.key === 'agent' ? 'agent' : g.key) }} />
                  {g.label}
                  <span style={{ fontSize: 10.5, color: '#a0a0a8', fontWeight: 400, marginLeft: 6 }}>{g.items.length}</span>
                </span>
              ),
              children: (
                <div>
                  {g.items.map((it) => (
                    <Tooltip key={it.id || it.implType} title={it.description} placement="right">
                      <div className="cv-lib-item" onClick={() => addNode(it)}>
                        <div className="nm">
                          <i className="cv-dot" style={{ background: catColorOf(it.implType) }} />
                          {it.label}
                        </div>
                        <div className="cd">
                          {it.isAgent ? `agent:${it.paramsDefault?.agent_code}` : it.implType}
                          {it.paramsDefault?.path ? ` · ${it.paramsDefault.method || ''} ${it.paramsDefault.path}` : ''}
                        </div>
                      </div>
                    </Tooltip>
                  ))}
                </div>
              ),
            }))} />
        </div>
      </div>

      {/* ── 中列：画布 ── */}
      <div className="cv-col cv-main">
        <div className="cv-main-head">
          <span className="t">画布编排</span>
          {versions.length > 0 && <Tag color="purple" style={{ marginRight: 0 }}>v{versions[0].version}</Tag>}
          <span className="hint">点左侧加节点 · 拖线连边 · 双击节点看配置（多数节点无需改）</span>
          <span className="cv-head-ops">
            <Button size="small" onClick={loadDraft}>重置</Button>
            <Button size="small" type="primary" onClick={saveDraft}>保存草稿</Button>
            <Popconfirm title="发布后新对话立即使用新图，确认？" onConfirm={publish}>
              <Button size="small" type="primary">发布</Button>
            </Popconfirm>
          </span>
        </div>
        <div className="cv-flow">
          <ReactFlow
            nodes={nodes} edges={edges}
            nodeTypes={NODE_TYPES}
            onNodesChange={onNodesChange} onEdgesChange={onEdgesChange}
            onConnect={onConnect} onNodeDoubleClick={onNodeDoubleClick}
            defaultEdgeOptions={{ type: 'smoothstep', markerEnd: { type: MarkerType.ArrowClosed, color: '#a89ee6' } }}
            fitViewOptions={{ padding: 0.12, maxZoom: 0.95 }}
            fitView>
            <Background color="#eceef3" gap={18} />
            <Controls showInteractive={false} />
            <MiniMap pannable
              nodeColor={(n) => catColorOf(n.data?.type, n.data?.kind)}
              nodeStrokeWidth={0} maskColor="rgba(246, 245, 252, 0.72)" />
          </ReactFlow>

          {/* 节点属性面板：画布内右侧悬浮卡（无遮罩、不占页面布局，仅覆盖画布右缘） */}
          {editing && (
            <div className="cv-panel">
              <div className="cv-panel-head">
                <span className="t">节点：{editing?.data?.label || editing?.id || ''}</span>
                <Button size="small" type="text" onClick={() => setEditing(null)}
                  style={{ marginLeft: 'auto', width: 24, height: 24, minWidth: 0 }}>×</Button>
              </div>
              <div className="cv-panel-body">
                <Tabs activeKey={editTab} onChange={setEditTab}
                  items={[
                    {
                      key: 'basic', label: '配置',
                      children: !editForm ? null : (
                        <div>
                          <div style={{ marginBottom: 10 }}>
                            <Typography.Text type="secondary" style={{ fontSize: 12 }}>节点类型</Typography.Text>
                            <div><Tag>{editForm.type}</Tag>
                              {editForm.type === 'agent' && <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                属性（人设/技能/工具）在「Agent」页配置，此处无需修改</Typography.Text>}
                            </div>
                          </div>
                          {basicFields.length === 0 && (
                            <Typography.Text type="secondary">
                              {editForm.type === 'agent'
                                ? '该节点零配置，直接使用即可。'
                                : '该节点使用现网默认实现，无需配置。'}
                            </Typography.Text>
                          )}
                          {basicFields.map(renderField)}
                          {advancedFields.length > 0 && (
                            <Collapse ghost size="small" items={[{
                              key: 'adv', label: <Typography.Text type="secondary" style={{ fontSize: 12 }}>高级参数（一般不用改）</Typography.Text>,
                              children: advancedFields.map(renderField),
                            }]} />
                          )}
                          {(editForm.__routes__ || []).length > 0 && (
                            <Collapse ghost size="small" items={[{
                              key: 'routes', label: <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                条件路由分支表（{editForm.__routes__.length} 组）</Typography.Text>,
                              children: <Input.TextArea rows={5} style={{ fontFamily: 'monospace' }}
                                value={JSON.stringify(editForm.__routes__, null, 2)}
                                onChange={(e) => { try { setEditForm((m) => ({ ...m, __routes__: JSON.parse(e.target.value || '[]') })); } catch { /* 中间态 */ } }} />,
                            }]} />
                          )}
                        </div>
                      ),
                    },
                    {
                      key: 'json', label: 'JSON（高级）',
                      children: (
                        <>
                          <Input.TextArea rows={14} style={{ fontFamily: 'monospace' }} value={editingText}
                            onChange={(e) => setEditingText(e.target.value)} />
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            节点 id 不可改名；type 必须在后端注册表内；护栏节点不可删除由校验器强制。
                          </Typography.Text>
                        </>
                      ),
                    },
                  ]} />
              </div>
              <div className="cv-panel-foot">
                <Button size="small" onClick={() => setEditing(null)}>取消</Button>
                <Button size="small" type="primary" onClick={applyEditing}>应用</Button>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* ── 右列：版本管理 ── */}
      <div className="cv-col cv-ver">
        <div className="cv-col-head">
          <span>版本管理</span>
          <span className="cv-col-sub" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            DSL 存 orch_graph
            <a style={{ fontSize: 11 }} onClick={retryMeta}>刷新</a>
          </span>
        </div>
        <div className="cv-ver-list">
          {versions.length === 0 && !metaErr && (
            <div className="cv-ver-empty">
              尚无已发布版本<br />点中列上方「发布」创建 v1
            </div>
          )}
          {versions.length === 0 && metaErr && (
            <div className="cv-ver-empty">版本列表加载失败，请重试</div>
          )}
          {versions.map((r) => (
            <div key={r.version} className={`cv-ver-item${r.isCurrent ? ' on' : ''}`}>
              <div className="row1">
                <span className="v">v{r.version}</span>
                {r.isCurrent && <span className="cur">当前生效</span>}
                {!r.isCurrent && (
                  <Popconfirm title={`把 v${r.version} 设为当前？`}
                    description="复制为新版本（历史保留）。新对话立即生效，进行中对话不受影响。"
                    onConfirm={() => rollback(r.version)}>
                    <Button size="small" type="primary" style={{ marginLeft: 'auto' }}>设为当前</Button>
                  </Popconfirm>
                )}
              </div>
              <div className="tm">{(r.created_at || '').slice(0, 19).replace('T', ' ')}</div>
              {r.note && <div className="nt">{r.note}</div>}
            </div>
          ))}
        </div>
        <div style={{ padding: '0 14px 12px', fontSize: 10.5, color: '#b5b5bf', lineHeight: 1.6 }}>
          「设为当前」= 复制为新版本，历史链不破坏；进行中对话粘住旧版本继续跑。
        </div>
      </div>

    </div>
  );
}
