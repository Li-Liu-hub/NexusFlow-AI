/**
 * 画布编排页（feature 分支第一期）
 *
 * 能力：查看现网拓扑 → 拖拽布局 → 双击节点编辑 JSON（含条件路由分支表）→
 *       增删普通边 → 保存草稿 → 发布（后端校验数字红线/白名单后固化为 v{N}）。
 * 锁定：verify_numbers / respond 不可删除（数字红线）；条件路由边只读，
 *       分支映射通过双击源节点的 __routes__ 编辑。
 * 后端：/orchestrator/v1/graph/*（draft GET/PUT、publish POST、versions GET）。
 * 设计：docs/Dify编排迁移-工程量评估.md 第十二/十五节。
 */
import { PageContainer } from '@ant-design/pro-components';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Button, Input, InputNumber, message, Modal, Popconfirm, Select, Space, Tabs, Tag, Typography } from 'antd';
import ReactFlow, {
  addEdge,
  Background,
  Controls,
  MarkerType,
  MiniMap,
  useEdgesState,
  useNodesState,
} from 'reactflow';
import 'reactflow/dist/style.css';

const GUARDS = ['verify_numbers', 'respond']; // 数字红线：不可删除
const END_ID = '__end__';
// 拥有可配置提示词的类型（能力键映射见 canvas/registry.py 的 NODE_PROMPT_KEY）
const PROMPT_TYPES = ['intent_router', 'chart_plan', 'report', 'order', 'critic'];

// ---------------------------------------------------------------------------
// DSL ↔ ReactFlow 转换
// ---------------------------------------------------------------------------

/** 分层布局：entry 起最长路径定列，同层纵向排（第一期免 dagre 依赖） */
function layoutNodes(dsl: any) {
  const level: Record<string, number> = { [dsl.entry]: 0 };
  const pairs: [string, string][] = [
    ...dsl.edges.map((e: any) => [e.from, e.to]),
    ...(dsl.conditional || []).flatMap((c: any) =>
      Object.values(c.map || {}).map((t: any) => [c.from, t] as [string, string]),
    ),
  ];
  let changed = true;
  let guard = 0;
  while (changed && guard++ < 60) {
    changed = false;
    for (const [f, t] of pairs) {
      if (level[f] !== undefined && t !== END_ID && (level[t] === undefined || level[t] <= level[f])) {
        level[t] = level[f] + 1;
        changed = true;
      }
    }
  }
  const columns: Record<number, string[]> = {};
  Object.entries(level).forEach(([id, l]) => {
    (columns[l] ||= []).push(id);
  });
  const pos: Record<string, { x: number; y: number }> = {};
  Object.entries(columns).forEach(([l, ids]) => {
    (ids as string[]).forEach((id, i) => {
      pos[id] = { x: Number(l) * 230, y: i * 95 };
    });
  });
  return pos;
}

function toRF(dsl: any) {
  const pos = layoutNodes(dsl);
  const nodes = dsl.nodes.map((n: any) => ({
    id: n.id,
    position: pos[n.id] || { x: 0, y: 0 },
    deletable: !GUARDS.includes(n.id) && n.id !== dsl.entry,
    data: {
      label: `${n.id}`,
      sub: n.type,
      type: n.type,
      params: n.params || {},
      // 条件路由映射挂在源节点上（数据态），双击 JSON 可编辑分支表
      __routes__: (dsl.conditional || []).filter((c: any) => c.from === n.id),
    },
  }));
  nodes.push({ id: END_ID, position: { x: (Object.keys(pos).length + 1) * 230, y: 0 }, deletable: false, data: { label: 'END', sub: '__end__' } });
  const edges: any[] = dsl.edges.map((e: any) => ({
    id: `e-${e.from}-${e.to}`,
    source: e.from,
    target: e.to,
    deletable: true,
  }));
  (dsl.conditional || []).forEach((c: any) =>
    Object.entries(c.map || {}).forEach(([label, target]: any) => {
      edges.push({
        id: `r-${c.from}-${label}-${target}`,
        source: c.from,
        target: target,
        animated: true,
        deletable: false, // 条件路由边第一期只读，分支映射在源节点 JSON 里编辑
        label,
        markerEnd: { type: MarkerType.ArrowClosed },
      });
    }),
  );
  return { nodes, edges };
}

function toDSL(rfNodes: any[], rfEdges: any[]) {
  const dsl: any = {
    entry: 'supervise',
    nodes: rfNodes
      .filter((n) => n.id !== END_ID)
      .map((n) => ({ id: n.id, type: n.data.type, params: n.data.params || {} })),
    edges: rfEdges
      .filter((e) => !String(e.id).startsWith('r-'))
      .map((e) => ({ from: e.source, to: e.target })),
    conditional: [] as any[],
  };
  rfNodes.forEach((n) => {
    (n.data.__routes__ || []).forEach((c: any) => {
      if (rfNodes.some((x) => x.id === n.id) && n.id !== END_ID) {
        dsl.conditional.push({ from: n.id, router: c.router, map: c.map });
      }
    });
  });
  return dsl;
}

// ---------------------------------------------------------------------------
// 页面
// ---------------------------------------------------------------------------
const CanvasPage: React.FC = () => {
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const [loading, setLoading] = useState(false);
  const [editing, setEditing] = useState<any>(null);
  const [editingText, setEditingText] = useState('');
  const [editForm, setEditForm] = useState<any>(null);
  const [editTab, setEditTab] = useState('form');
  const [versions, setVersions] = useState<any[]>([]);
  const [newType, setNewType] = useState('http');
  const rfWrap = useRef<HTMLDivElement>(null);

  const loadDraft = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetch('/orchestrator/v1/graph/draft');
      const body = await res.json();
      const { nodes: rfNodes, edges: rfEdges } = toRF(body.dsl);
      setNodes(rfNodes);
      setEdges(rfEdges);
      message.success(body.isDefault ? '已加载现网默认拓扑（尚无草稿）' : '草稿已加载');
    } catch (e: any) {
      message.error('加载失败：' + e.message);
    } finally {
      setLoading(false);
    }
  }, [setNodes, setEdges]);

  const loadVersions = useCallback(async () => {
    const res = await fetch('/orchestrator/v1/graph/versions');
    const body = await res.json();
    setVersions(body.versions || []);
  }, []);

  useEffect(() => {
    loadDraft();
    loadVersions();
  }, [loadDraft, loadVersions]);

  const saveDraft = async () => {
    const dsl = toDSL(nodes, edges);
    const res = await fetch('/orchestrator/v1/graph/draft', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ dsl }),
    });
    const body = await res.json();
    if (body.errors?.length) {
      message.warning(`草稿已存（本地校验提示 ${body.errors.length} 条，发布时后端会再拦）`);
      Modal.warning({ title: '结构提示', content: <ul>{body.errors.map((e: string) => <li key={e}>{e}</li>)}</ul> });
    } else {
      message.success('草稿已保存，结构校验通过');
    }
  };

  const publish = async () => {
    const dsl = toDSL(nodes, edges);
    const res = await fetch('/orchestrator/v1/graph/publish', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ dsl }),
    });
    const body = await res.json();
    if (res.status === 422) {
      Modal.error({
        title: '发布被校验器拦截',
        content: <ul>{(body.detail?.errors || []).map((e: string) => <li key={e}>{e}</li>)}</ul>,
      });
      return;
    }
    if (!res.ok) {
      message.error('发布失败：' + (body.detail || res.statusText));
      return;
    }
    message.success(`已发布 v${body.version} —— 新对话立即生效（进行中对话粘旧版本）`);
    loadVersions();
  };

  const addNode = () => {
    const id = `${newType}_${Math.random().toString(36).slice(2, 6)}`;
    setNodes((ns) => [
      ...ns,
      { id, position: { x: 120 + Math.random() * 300, y: 120 + Math.random() * 200 }, data: { label: id, sub: newType, type: newType, params: {} } },
    ]);
  };

  const onNodeDoubleClick = (_: any, node: any) => {
    if (node.id === END_ID) return;
    const model = {
      id: node.id,
      type: node.data.type,
      params: node.data.params || {},
      __routes__: node.data.__routes__ || [],
    };
    setEditing(node);
    setEditForm(model);
    setEditingText(JSON.stringify(model, null, 2));
    setEditTab('form');
  };

  // 表单字段变更：同步回 editForm 并镜像到 JSON 文本（两个 Tab 永远一致）
  const patchForm = (patch: any) => {
    setEditForm((m: any) => {
      const next = { ...m, ...patch };
      setEditingText(JSON.stringify(next, null, 2));
      return next;
    });
  };

  const patchParams = (patch: any) => {
    setEditForm((m: any) => {
      const next = { ...m, params: { ...(m.params || {}), ...patch } };
      setEditingText(JSON.stringify(next, null, 2));
      return next;
    });
  };

  const applyEditing = () => {
    try {
      const parsed = editTab === 'json' ? JSON.parse(editingText) : editForm;
      setNodes((ns) =>
        ns.map((n) =>
          n.id === editing.id
            ? { ...n, data: { ...n.data, type: parsed.type, params: parsed.params || {}, __routes__: parsed.__routes__ || [] } }
            : n,
        ),
      );
      setEditing(null);
      message.success('节点已更新（保存草稿后生效到后端）');
    } catch (e: any) {
      message.error('JSON 解析失败：' + e.message);
    }
  };

  const rollback = async (version: number) => {
    const res = await fetch('/orchestrator/v1/graph/rollback', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ version }),
    });
    const body = await res.json();
    if (!res.ok) {
      message.error('回滚失败：' + (body.detail || res.statusText));
      return;
    }
    message.success(`已从 v${version} 回滚 → 新版本 v${body.version}（新对话立即生效）`);
    loadVersions();
  };

  const onConnect = useCallback(
    (conn: any) => setEdges((es) => addEdge({ ...conn, id: `e-${conn.source}-${conn.target}`, deletable: true }, es)),
    [setEdges],
  );

  const nodeTypesLabel = useMemo(() => (
    <Select value={newType} onChange={setNewType} style={{ width: 160 }}
      options={[
        { value: 'http', label: 'http 节点（走网关）' },
        { value: 'intent_router', label: '意图路由（现网实现）' },
        { value: 'digital_human', label: '数字员工透传' },
        { value: 'algorithm', label: '补货算法' },
        { value: 'report', label: '建议卡总结' },
      ]} />
  ), [newType]);

  return (
    <PageContainer title="画布编排" content="调整拓扑/参数 → 保存草稿 → 发布：新对话立即生效，无需重启（GRAPH_SOURCE=dsl 时）">
      <Space style={{ marginBottom: 12 }} wrap>
        <Button type="primary" onClick={saveDraft}>保存草稿</Button>
        <Popconfirm title="发布后新对话立即使用新图，确认？" onConfirm={publish}>
          <Button type="primary" danger>发布</Button>
        </Popconfirm>
        <Button onClick={loadDraft}>重置为草稿</Button>
        {nodeTypesLabel}
        <Button onClick={addNode}>+ 添加节点</Button>
        <Typography.Text type="secondary">
          双击节点编辑 JSON · 拖线连边 · 选中后 Delete 删除（虚线为条件路由，只读）
        </Typography.Text>
        {versions.length > 0 && (
          <span>
            {versions.slice(0, 3).map((v, i) => (
              <span key={v.version} style={{ marginRight: 6 }}>
                <Tag color="green">v{v.version}</Tag>
                {i > 0 && (
                  <Popconfirm title={`把 v${v.version} 设为当前？（复制为新版本，历史保留）`}
                    onConfirm={() => rollback(v.version)}>
                    <Button size="small" type="link">回滚</Button>
                  </Popconfirm>
                )}
              </span>
            ))}
            <Typography.Text type="secondary">最新已发布版本（其余可回滚）</Typography.Text>
          </span>
        )}
      </Space>
      <div ref={rfWrap} style={{ height: 620, border: '1px solid #eee', borderRadius: 8 }}>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          onNodeDoubleClick={onNodeDoubleClick}
          fitView
        >
          <Background />
          <Controls />
          <MiniMap pannable />
        </ReactFlow>
      </div>
      <Modal
        open={!!editing}
        title={`编辑节点 ${editing?.id || ''}`}
        okText="应用"
        cancelText="取消"
        onOk={applyEditing}
        onCancel={() => setEditing(null)}
        width={720}
      >
        <Tabs
          activeKey={editTab}
          onChange={setEditTab}
          items={[
            {
              key: 'form',
              label: '表单',
              children: !editForm ? null : (
                <Space direction="vertical" style={{ width: '100%' }} size="small">
                  <div>
                    <Typography.Text type="secondary">节点类型（决定执行哪个注册表实现）</Typography.Text>
                    <Select
                      style={{ width: '100%' }}
                      value={editForm.type}
                      onChange={(v) => patchForm({ type: v })}
                      options={[
                        { value: 'http', label: 'http 节点（走网关，仅 path+参数）' },
                        { value: 'intent_router', label: '意图路由 supervise' },
                        { value: 'chart_plan', label: '图表选型 chart_plan' },
                        { value: 'digital_human', label: '数字员工透传' },
                        { value: 'fetch', label: '库存取数 fetch' },
                        { value: 'algorithm', label: '补货算法' },
                        { value: 'report', label: '建议卡总结 report' },
                        { value: 'verify_numbers', label: '数字自检（护栏）' },
                        { value: 'order', label: '建单 order' },
                        { value: 'critic', label: '质检 critic' },
                        { value: 'respond', label: '卡片组装（护栏）' },
                        { value: 'kb', label: 'KB 通道' },
                      ]}
                    />
                  </div>
                  {editForm.type === 'http' && (
                    <>
                      <div>
                        <Typography.Text type="secondary">接口路径（服务归属由网关路由表决定，无需填地址）</Typography.Text>
                        <Input value={editForm.params?.path || ''} placeholder="/api/terminal/inventory/snapshot"
                          onChange={(e) => patchParams({ path: e.target.value })} />
                      </div>
                      <div>
                        <Typography.Text type="secondary">请求方法</Typography.Text>
                        <Select style={{ width: 120 }} value={editForm.params?.method || 'GET'}
                          onChange={(v) => patchParams({ method: v })}
                          options={['GET', 'POST', 'PUT', 'DELETE'].map((m) => ({ value: m, label: m }))} />
                      </div>
                      <div>
                        <Typography.Text type="secondary">请求体 JSON（静态参数；动态映射下期）</Typography.Text>
                        <Input.TextArea rows={3} value={editForm.params?.bodyText || JSON.stringify(editForm.params?.body || {}, null, 2)}
                          onChange={(e) => {
                            try { patchParams({ body: JSON.parse(e.target.value || '{}'), bodyText: e.target.value }); }
                            catch { patchParams({ bodyText: e.target.value }); }
                          }} style={{ fontFamily: 'monospace' }} />
                      </div>
                      <div>
                        <Typography.Text type="secondary">超时（秒）</Typography.Text>
                        <InputNumber style={{ width: 120 }} min={1} max={120} value={editForm.params?.timeout || 10}
                          onChange={(v) => patchParams({ timeout: v || 10 })} />
                      </div>
                    </>
                  )}
                  {PROMPT_TYPES.includes(editForm.type) && (
                    <div>
                      <Typography.Text type="secondary">
                        提示词覆盖（留空 = 现网默认；文案级覆盖，数字红线不受影响）
                      </Typography.Text>
                      <Input.TextArea rows={8} value={editForm.params?.prompt || ''}
                        onChange={(e) => patchParams({ prompt: e.target.value })}
                        style={{ fontFamily: 'monospace' }} />
                    </div>
                  )}
                  {!editForm.type.startsWith('http') && !PROMPT_TYPES.includes(editForm.type) && (
                    <Typography.Text type="secondary">该节点使用现网实现，无画布参数。</Typography.Text>
                  )}
                  {(editForm.__routes__ || []).length > 0 && (
                    <div>
                      <Typography.Text type="secondary">条件路由分支表（router + 分支标签 → 目标；数据态，画布连线即改）</Typography.Text>
                      <Input.TextArea rows={6} value={JSON.stringify(editForm.__routes__, null, 2)}
                        onChange={(e) => {
                          try { patchForm({ __routes__: JSON.parse(e.target.value || '[]') }); }
                          catch { /* 中间态允许 */ }
                        }} style={{ fontFamily: 'monospace' }} />
                    </div>
                  )}
                </Space>
              ),
            },
            {
              key: 'json',
              label: 'JSON',
              children: (
                <>
                  <Input.TextArea rows={16} value={editingText} onChange={(e) => setEditingText(e.target.value)} style={{ fontFamily: 'monospace' }} />
                  <Typography.Text type="secondary">
                    id 不可改名（引用稳定）；type 必须在后端注册表内；护栏节点不可删除由后端校验器强制。
                  </Typography.Text>
                </>
              ),
            },
          ]}
        />
      </Modal>
    </PageContainer>
  );
};

export default CanvasPage;
