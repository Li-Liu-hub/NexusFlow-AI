import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Button, Form, Input, InputNumber, Modal, Segmented, Select,
  Tag, Tooltip, message as antMessage,
} from 'antd';
import * as api from '../api';

/** 就地编辑只动这些字段，保存时整体 PUT（与后端 AgentPO 对齐，避免误清 knowledgeBaseId） */
const EDITABLE = [
  'agentName', 'description', 'modelName', 'temperature', 'maxTurns', 'status', 'systemPrompt',
];

const MODEL_OPTIONS = [
  { value: 'mimo-v2.6-flash', label: 'mimo-v2.6-flash' },
  { value: 'mimo-v2.6', label: 'mimo-v2.6' },
];

/** 从详情接口构造编辑草稿 */
function draftOf(detail) {
  const a = detail?.agent;
  if (!a) return null;
  return {
    agentName: a.agentName,
    description: a.description || '',
    modelName: a.modelName,
    temperature: a.temperature,
    maxTurns: a.maxTurns,
    status: a.status || 'active',
    systemPrompt: a.systemPrompt || '',
  };
}

/**
 * 数字员工配置页 —— 参考应用编排台（Coze 风格）的三栏布局：
 * 左：数字员工列表；中：分区配置（基本信息 / 模型 / 人设 / 挂载能力，全部就地编辑）；
 * 右：运行预览（装配结果自省）。保存即 version 自增、运行时热更新，无需重启。
 */
export default function AgentPage() {
  const [agents, setAgents] = useState([]);
  const [current, setCurrent] = useState(null);
  const [detail, setDetail] = useState(null);
  const [allSkills, setAllSkills] = useState([]);
  const [allTools, setAllTools] = useState([]);
  const [allRoles, setAllRoles] = useState([]);
  const [allKbs, setAllKbs] = useState([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [form] = Form.useForm();

  // 就地编辑草稿：detail 变化时重建；脏标记驱动「保存 / 重置」
  const [draft, setDraft] = useState(null);
  const [dirty, setDirty] = useState(false);
  const dirtyRef = useRef(false);

  const loadAgents = useCallback(async () => {
    setLoading(true);
    try {
      const list = await api.listAgents();
      setAgents(Array.isArray(list) ? list : []);
      if (list?.length && !current) {
        selectAgent(list[0].agentCode);
      }
    } catch (e) {
      antMessage.error('加载数字员工列表失败：' + e.message);
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function selectAgent(code) {
    try {
      dirtyRef.current = false; // 换人后草稿作废，避免把 A 的修改带到 B
      setDirty(false);
      const d = await api.getAgentDetail(code);
      setCurrent(code);
      setDetail(d);
    } catch (e) {
      antMessage.error('加载配置失败：' + e.message);
    }
  }

  useEffect(() => {
    loadAgents();
    api.catalogSkills().then((x) => setAllSkills(x || [])).catch(() => {});
    api.catalogTools().then((x) => setAllTools(x || [])).catch(() => {});
    api.catalogRoles().then((x) => setAllRoles(x || [])).catch(() => {});
    api.catalogKbs().then((x) => setAllKbs(x || [])).catch(() => {});
  }, [loadAgents]);

  // detail 刷新后重建草稿；若因挂载操作触发刷新且本地还有未保存修改，则保留草稿
  useEffect(() => {
    setDraft((prev) => (dirtyRef.current && prev ? prev : draftOf(detail)));
    if (!dirtyRef.current) setDirty(false);
  }, [detail]);

  function patch(key, value) {
    dirtyRef.current = true;
    setDirty(true);
    setDraft((prev) => ({ ...prev, [key]: value }));
  }

  function resetDraft() {
    dirtyRef.current = false;
    setDirty(false);
    setDraft(draftOf(detail));
  }

  async function saveDraft() {
    try {
      const payload = {};
      EDITABLE.forEach((k) => { payload[k] = draft[k]; });
      const res = await api.updateAgent(current, payload);
      antMessage.success(`已保存（version → ${res.version}，运行时已热更新）`);
      dirtyRef.current = false;
      setDirty(false);
      await selectAgent(current);
      loadAgents();
    } catch (e) {
      antMessage.error('保存失败：' + e.message);
    }
  }

  /** 挂载/卸载后刷新：注意 version 会变，这正是热更新的证据 */
  async function mutate(fn, okText) {
    try {
      const res = await fn();
      antMessage.success(`${okText}（version → ${res.version}）`);
      selectAgent(current);
    } catch (e) {
      antMessage.error('操作失败：' + e.message);
    }
  }

  /** 新建：默认值对齐 a_agent 表默认（模型/温度/轮次/状态），agentCode 需手填且创建后不可改 */
  function openCreate() {
    form.resetFields();
    form.setFieldsValue({
      modelName: 'mimo-v2.6-flash',
      temperature: 0.7,
      maxTurns: 10,
      status: 'active',
    });
    setCreateOpen(true);
  }

  async function submitCreate() {
    const values = await form.validateFields();
    try {
      const res = await api.createAgent(values);
      antMessage.success(`已创建「${values.agentName}」（v${res.version}）—— 继续配工具/技能/角色后即可在门户使用`);
      setCreateOpen(false);
      await loadAgents();
      selectAgent(values.agentCode);
    } catch (e) {
      antMessage.error('创建失败：' + e.message);
    }
  }

  const attachedToolIds = new Set((detail?.tools || []).map((t) => t.id));
  const attachedSkillIds = new Set((detail?.skills || []).map((s) => s.id));
  const attachedRoleCodes = new Set((detail?.roles || []).map((r) => r.roleCode));
  const mountedKbId = detail?.agent?.knowledgeBaseId || null;
  const mountedKbName =
    detail?.knowledgeBase?.name || allKbs.find((k) => k.id === mountedKbId)?.name || mountedKbId;
  const mountedTools = detail?.tools || [];
  const mountedSkills = detail?.skills || [];
  const mountedRoles = detail?.roles || [];

  return (
    <div className="ag-page">
      {/* -------- 左：数字员工列表 -------- */}
      <aside className="ag-col ag-side">
        <div className="ag-col-head">
          <span>数字员工</span>
          <Button size="small" type="link" onClick={openCreate}>+ 新建</Button>
        </div>
        <div className="ag-side-list">
          {agents.map((a) => (
            <div
              key={a.agentCode}
              className={`ag-agent-item ${a.agentCode === current ? 'on' : ''}`}
              onClick={() => selectAgent(a.agentCode)}
            >
              <span className="ag-avatar">{(a.agentName || '员')[0]}</span>
              <span className="tx">
                <span className="nm" style={{ display: 'block' }}>{a.agentName}</span>
                <span className="cd mono" style={{ display: 'block' }}>
                  {a.agentCode} · v{a.version}
                </span>
              </span>
            </div>
          ))}
          {!loading && agents.length === 0 && (
            <div className="ag-empty-add" style={{ textAlign: 'center', padding: '18px 0' }}>
              暂无数字员工
            </div>
          )}
        </div>
      </aside>

      {/* -------- 中：分区配置（就地编辑） -------- */}
      <section className="ag-col ag-main">
        {!detail || !draft ? (
          <div className="ag-main-empty">
            <div style={{ fontSize: 14, color: '#5a5a68' }}>
              {loading ? '加载中…' : '还没有数字员工'}
            </div>
            {!loading && (
              <>
                <div style={{ fontSize: 12 }}>新建一个，配好模型与人设后即可在门户使用</div>
                <Button type="primary" size="small" style={{ marginTop: 8 }} onClick={openCreate}>
                  新建数字员工
                </Button>
              </>
            )}
          </div>
        ) : (
          <>
            <div className="ag-main-head">
              <span className="ag-avatar lg">{(draft.agentName || '员')[0]}</span>
              <div className="ag-head-title">
                <div className="t">
                  <span className="nm-ell">{draft.agentName || '未命名'}</span>
                  <Tag style={{ marginInlineEnd: 0 }}>v{detail.agent.version}</Tag>
                  <Tag color={draft.status === 'active' ? 'green' : 'default'} style={{ marginInlineEnd: 0 }}>
                    {draft.status === 'active' ? '启用' : '停用'}
                  </Tag>
                </div>
                <div className="s mono">{detail.agent.agentCode}</div>
              </div>
              <div className="ag-head-ops">
                {dirty && <span className="ag-dirty">未保存</span>}
                <Tooltip title="放弃未保存的修改">
                  <Button size="small" disabled={!dirty} onClick={resetDraft}>重置</Button>
                </Tooltip>
                <Button size="small" type="primary" disabled={!dirty} onClick={saveDraft}>
                  保存
                </Button>
                <Tooltip title="手动逐出运行时缓存，下次对话立即按最新配置重建">
                  <Button
                    size="small"
                    icon={<Ico size={13}>{ICONS.refresh}</Ico>}
                    onClick={() => mutate(() => api.reloadAgent(current), '缓存已逐出，下次对话将重建')}
                  />
                </Tooltip>
              </div>
            </div>

            <div className="ag-main-scroll">
              <Sec icon={<Ico>{ICONS.info}</Ico>} title="基本信息" sub="名称与介绍会展示在门户">
                <div className="ag-row">
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div className="ag-field-label">显示名</div>
                    <Input
                      value={draft.agentName}
                      onChange={(e) => patch('agentName', e.target.value)}
                      placeholder="如 财务助手"
                    />
                  </div>
                  <div style={{ width: 190, flexShrink: 0 }}>
                    <div className="ag-field-label">状态</div>
                    <Segmented
                      block
                      value={draft.status}
                      onChange={(v) => patch('status', v)}
                      options={[
                        { label: '启用', value: 'active' },
                        { label: '停用', value: 'disabled' },
                      ]}
                    />
                  </div>
                </div>
                <div style={{ marginTop: 10 }}>
                  <div className="ag-field-label">一句话介绍</div>
                  <Input
                    value={draft.description}
                    maxLength={100}
                    onChange={(e) => patch('description', e.target.value)}
                    placeholder="展示给门户用户的一句话简介"
                  />
                </div>
              </Sec>

              <Sec icon={<Ico>{ICONS.cpu}</Ico>} title="模型设置" extra={draft.modelName}>
                <div className="ag-row">
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div className="ag-field-label">模型</div>
                    <Select
                      style={{ width: '100%' }}
                      options={MODEL_OPTIONS}
                      value={draft.modelName}
                      onChange={(v) => patch('modelName', v)}
                    />
                  </div>
                  <div style={{ width: 110, flexShrink: 0 }}>
                    <div className="ag-field-label">温度</div>
                    <InputNumber
                      style={{ width: '100%' }}
                      min={0} max={2} step={0.05}
                      value={draft.temperature}
                      onChange={(v) => patch('temperature', v)}
                    />
                  </div>
                  <div style={{ width: 110, flexShrink: 0 }}>
                    <div className="ag-field-label">最大轮次</div>
                    <InputNumber
                      style={{ width: '100%' }}
                      min={1} max={50}
                      value={draft.maxTurns}
                      onChange={(v) => patch('maxTurns', v)}
                    />
                  </div>
                </div>
              </Sec>

              <Sec icon={<Ico>{ICONS.pen}</Ico>} title="人设提示词" sub="只写人设基底，能力细节放技能包">
                <Input.TextArea
                  value={draft.systemPrompt}
                  onChange={(e) => patch('systemPrompt', e.target.value)}
                  rows={9}
                  maxLength={4000}
                  showCount
                  placeholder={'你是……（角色定位、语气风格）\n## 你能做什么\n## 输出要求\n（工具调用细节交给技能包，这里不必重复）'}
                />
              </Sec>

              <Sec
                icon={<Ico>{ICONS.layers}</Ico>}
                title="技能包"
                extra={mountedSkills.length ? `${mountedSkills.length} 个已挂载` : undefined}
              >
                <div className="ag-chips">
                  {mountedSkills.map((s) => (
                    <Tooltip key={s.id} title={s.description || s.skillName}>
                      <span className="ag-chip">
                        {s.skillName}
                        <span
                          className="ag-chip-x"
                          onClick={() => mutate(() => api.detachSkill(current, s.id), '已卸载技能')}
                        >
                          ✕
                        </span>
                      </span>
                    </Tooltip>
                  ))}
                  {mountedSkills.length === 0 && (
                    <span className="ag-empty-add">尚未挂载技能 —— 技能是数字员工的能力来源</span>
                  )}
                </div>
                <Select
                  className="ag-chip-add"
                  value={null}
                  showSearch
                  optionFilterProp="label"
                  popupMatchSelectWidth={false}
                  placeholder="+ 挂载技能"
                  options={allSkills
                    .filter((s) => !attachedSkillIds.has(s.id))
                    .map((s) => ({ value: s.id, label: s.skillName }))}
                  onChange={(id) => mutate(() => api.attachSkill(current, id), '已挂载技能')}
                  notFoundContent="没有可挂载的技能"
                />
              </Sec>

              <Sec
                icon={<Ico>{ICONS.wrench}</Ico>}
                title="工具"
                extra={mountedTools.length ? `${mountedTools.length} 个已挂载` : undefined}
              >
                <div className="ag-chips">
                  {mountedTools.map((t) => {
                    const off = t.status !== 'active';
                    return (
                      <Tooltip
                        key={t.id}
                        title={off ? '已停用：需在「工具管理」启用后才会被装配' : (t.toolDesc || t.toolName)}
                      >
                        <span className={`ag-chip ${off ? 'off' : ''}`}>
                          {t.displayName || t.toolName}
                          <span
                            className="ag-chip-x"
                            onClick={() => mutate(() => api.detachTool(current, t.id), '已卸载工具')}
                          >
                            ✕
                          </span>
                        </span>
                      </Tooltip>
                    );
                  })}
                  {mountedTools.length === 0 && (
                    <span className="ag-empty-add">尚未挂载工具 —— 挂载后模型才能查库、调接口</span>
                  )}
                </div>
                <Select
                  className="ag-chip-add"
                  value={null}
                  showSearch
                  optionFilterProp="label"
                  popupMatchSelectWidth={false}
                  placeholder="+ 挂载工具"
                  options={allTools
                    .filter((t) => !attachedToolIds.has(t.id))
                    .map((t) => ({
                      value: t.id,
                      label: (t.displayName || t.toolName) + (t.status !== 'active' ? '（已停用）' : ''),
                      title: t.toolName + (t.toolDesc ? ' — ' + t.toolDesc : ''),
                      disabled: t.status !== 'active',
                    }))}
                  onChange={(id) => mutate(() => api.attachTool(current, id), '已挂载工具')}
                  notFoundContent="没有可挂载的工具"
                />
              </Sec>

              <Sec icon={<Ico>{ICONS.book}</Ico>} title="知识库" sub="单挂载，挂载后回答可引用库内文档">
                <div className="ag-chips">
                  {mountedKbId ? (
                    <span className="ag-chip">
                      {mountedKbName}
                      <span
                        className="ag-chip-x"
                        onClick={() => mutate(() => api.detachKb(current), '已解绑知识库')}
                      >
                        ✕
                      </span>
                    </span>
                  ) : (
                    <span className="ag-empty-add">未挂载知识库</span>
                  )}
                </div>
                <Select
                  className="ag-chip-add"
                  value={null}
                  showSearch
                  optionFilterProp="label"
                  popupMatchSelectWidth={false}
                  placeholder="+ 挂载知识库"
                  options={allKbs
                    .filter((k) => k.id !== mountedKbId)
                    .map((k) => ({
                      value: k.id,
                      label: `${k.name}（${k.doc_count} 文档 / ${k.chunk_count} 分片）`,
                    }))}
                  onChange={(id) => mutate(() => api.attachKb(current, id), '已挂载知识库')}
                  notFoundContent="没有可挂载的知识库"
                />
              </Sec>

              <Sec
                icon={<Ico>{ICONS.users}</Ico>}
                title="可用角色"
                sub="分配后该角色在门户可使用此数字员工"
                extra={mountedRoles.length ? `${mountedRoles.length} 个已分配` : undefined}
              >
                <div className="ag-chips">
                  {mountedRoles.map((r) => (
                    <span key={r.roleCode} className="ag-chip" title={r.description || r.roleName}>
                      {r.roleName}
                      <span
                        className="ag-chip-x"
                        onClick={async () => {
                          try {
                            await api.detachRole(current, r.roleCode);
                            antMessage.success('已收回该角色的使用权限');
                            selectAgent(current);
                          } catch (e) {
                            antMessage.error('操作失败：' + e.message);
                          }
                        }}
                      >
                        ✕
                      </span>
                    </span>
                  ))}
                  {mountedRoles.length === 0 && (
                    <span className="ag-empty-add">尚未分配角色 —— 不分配则无人可用</span>
                  )}
                </div>
                <Select
                  className="ag-chip-add"
                  value={null}
                  showSearch
                  optionFilterProp="label"
                  popupMatchSelectWidth={false}
                  placeholder="+ 分配角色"
                  options={allRoles
                    .filter((r) => !attachedRoleCodes.has(r.roleCode))
                    .map((r) => ({ value: r.roleCode, label: r.roleName }))}
                  onChange={async (rc) => {
                    try {
                      await api.attachRole(current, rc);
                      antMessage.success('已分配给该角色使用');
                      selectAgent(current);
                    } catch (e) {
                      antMessage.error('操作失败：' + e.message);
                    }
                  }}
                  notFoundContent="没有可分配的角色"
                />
              </Sec>
            </div>
          </>
        )}
      </section>

      {/* -------- 右：运行预览（装配自省） -------- */}
      {detail && draft && (
        <aside className="ag-col ag-prev">
          <AgentPreview code={current} detail={detail} draft={draft} />
        </aside>
      )}

      {/* -------- 新建弹窗 -------- */}
      <Modal
        open={createOpen}
        title="新建数字员工"
        onCancel={() => setCreateOpen(false)}
        onOk={submitCreate}
        width={560}
        okText="创建"
      >
        <Form form={form} layout="vertical" size="small">
          <Form.Item
            name="agentCode"
            label="编码"
            rules={[
              { required: true, message: '请输入编码' },
              {
                pattern: /^[a-z][a-z0-9_]{1,31}$/,
                message: '小写字母开头，仅含小写字母/数字/下划线（2-32 位）',
              },
            ]}
            extra="门户与 API 均以此编码寻址，创建后不可修改"
          >
            <Input placeholder="如 finance_assistant" />
          </Form.Item>
          <Form.Item name="agentName" label="显示名" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label="一句话介绍">
            <Input />
          </Form.Item>
          <div className="ag-row">
            <Form.Item name="modelName" label="模型" style={{ flex: 1 }}>
              <Select options={MODEL_OPTIONS} />
            </Form.Item>
            <Form.Item name="temperature" label="温度" style={{ width: 110 }}>
              <InputNumber min={0} max={2} step={0.05} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="maxTurns" label="最大轮次" style={{ width: 110 }}>
              <InputNumber min={1} max={50} style={{ width: '100%' }} />
            </Form.Item>
          </div>
          <Form.Item name="status" label="状态">
            <Segmented
              block
              options={[
                { label: '启用', value: 'active' },
                { label: '停用', value: 'disabled' },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="systemPrompt"
            label="人设提示词"
            rules={[{ required: true }]}
            extra="注意：这里只写人设基底，能力细节放技能包里 —— 便于跨数字员工复用"
          >
            <Input.TextArea rows={8} maxLength={4000} showCount />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

/* ================= 局部小组件（置于文件底部） ================= */

/** 内联线性图标：不引图标库，保持依赖干净 */
function Ico({ children, size = 15 }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {children}
    </svg>
  );
}

const ICONS = {
  info: (
    <>
      <circle cx="12" cy="12" r="9" />
      <line x1="12" y1="11" x2="12" y2="16.2" />
      <line x1="12" y1="7.6" x2="12" y2="7.7" />
    </>
  ),
  cpu: (
    <>
      <rect x="6" y="6" width="12" height="12" rx="2" />
      <rect x="10" y="10" width="4" height="4" />
      <line x1="9" y1="2.8" x2="9" y2="6" />
      <line x1="15" y1="2.8" x2="15" y2="6" />
      <line x1="9" y1="18" x2="9" y2="21.2" />
      <line x1="15" y1="18" x2="15" y2="21.2" />
      <line x1="2.8" y1="9" x2="6" y2="9" />
      <line x1="2.8" y1="15" x2="6" y2="15" />
      <line x1="18" y1="9" x2="21.2" y2="9" />
      <line x1="18" y1="15" x2="21.2" y2="15" />
    </>
  ),
  pen: <path d="M4 20l1.3-4.4L16.9 4a2.15 2.15 0 013 3L8.4 18.7 4 20z" />,
  layers: (
    <>
      <path d="M12 3.2l8.8 4.6-8.8 4.6-8.8-4.6 8.8-4.6z" />
      <path d="M3.2 13.4l8.8 4.6 8.8-4.6" />
    </>
  ),
  wrench: (
    <path d="M14.7 6.3a1 1 0 000 1.4l1.6 1.6a1 1 0 001.4 0l3.77-3.77a6 6 0 01-7.94 7.94l-6.91 6.91a2.12 2.12 0 01-3-3l6.91-6.91a6 6 0 017.94-7.94l-3.76 3.76z" />
  ),
  book: (
    <>
      <path d="M4 19.5A2.5 2.5 0 016.5 17H20" />
      <path d="M6.5 2H20v20H6.5A2.5 2.5 0 014 19.5v-15A2.5 2.5 0 016.5 2z" />
    </>
  ),
  users: (
    <>
      <path d="M17 21v-2a4 4 0 00-4-4H7a4 4 0 00-4 4v2" />
      <circle cx="10" cy="7" r="4" />
      <path d="M23 21v-2a4 4 0 00-3-3.87" />
      <path d="M16 3.13a4 4 0 010 7.75" />
    </>
  ),
  refresh: (
    <>
      <path d="M22 4v6h-6" />
      <path d="M20.5 15a9 9 0 11-2.1-9.4L22 10" />
    </>
  ),
  spark: <path d="M12 3l1.9 5.6 5.6 1.9-5.6 1.9L12 18l-1.9-5.6-5.6-1.9 5.6-1.9L12 3z" />,
};

/** 配置分区：图标 + 标题 + 副标题/右侧计数 */
interface SecProps {
  icon?: any;
  title?: any;
  sub?: any;
  extra?: any;
  children?: any;
}

function Sec({ icon, title, sub, extra, children }: SecProps) {
  return (
    <div className="ag-sec">
      <div className="ag-sec-head">
        <span className="ag-sec-ico">{icon}</span>
        <span className="ag-sec-title">{title}</span>
        {sub && <span className="ag-sec-sub">{sub}</span>}
        {extra && <span className="ag-sec-extra">{extra}</span>}
      </div>
      {children}
    </div>
  );
}

/** 右栏：运行预览 —— 信息卡 + 能力清单 + 装配后提示词（运行时自省） */
function AgentPreview({ code, detail, draft }) {
  const [info, setInfo] = useState(null);
  const [expanded, setExpanded] = useState(false);
  const version = detail?.agent?.version;

  const loadInfo = useCallback(() => {
    if (!code) return;
    api.introspect(code).then(setInfo).catch(() => setInfo(null));
  }, [code]);

  // version 变化（保存/挂载）后自动重取装配结果
  useEffect(() => {
    loadInfo();
  }, [loadInfo, version]);

  const skills = detail?.skills || [];
  const tools = detail?.tools || [];
  const roles = detail?.roles || [];
  const kbName = detail?.knowledgeBase?.name;
  const promptText = info?.systemPrompt || '';

  return (
    <>
      <div className="ag-col-head">
        <span className="ag-prev-tag">
          <Ico size={14}>{ICONS.spark}</Ico>运行预览
        </span>
        <Button size="small" type="link" onClick={loadInfo}>刷新</Button>
      </div>
      <div className="ag-prev-scroll">
        <div className="ag-prev-hero">
          <div className="ag-avatar">{(draft?.agentName || '员')[0]}</div>
          <div className="nm">{draft?.agentName || '未命名'}</div>
          <div className="ds">{draft?.description || '还没有一句话介绍'}</div>
        </div>

        <div className="ag-info">
          <div className="ag-info-row">
            <span className="k">编码</span>
            <span className="v mono">{code}</span>
          </div>
          <div className="ag-info-row">
            <span className="k">模型</span>
            <span className="v">{draft?.modelName}</span>
          </div>
          <div className="ag-info-row">
            <span className="k">温度 / 轮次</span>
            <span className="v">{draft?.temperature} / {draft?.maxTurns}</span>
          </div>
          <div className="ag-info-row">
            <span className="k">状态</span>
            <span className="v">
              <Tag color={draft?.status === 'active' ? 'green' : 'default'} style={{ margin: 0 }}>
                {draft?.status === 'active' ? '启用' : '停用'}
              </Tag>
            </span>
          </div>
          <div className="ag-info-row">
            <span className="k">版本</span>
            <span className="v">v{version}{info ? ` · 装配 v${info.version}` : ''}</span>
          </div>
        </div>

        <div className="ag-info">
          <div className="ag-info-row">
            <span className="k">技能包</span>
            <span className="v">{skills.length ? skills.map((s) => s.skillName).join('、') : '—'}</span>
          </div>
          <div className="ag-info-row">
            <span className="k">工具</span>
            <span className="v">{tools.length ? tools.map((t) => t.displayName || t.toolName).join('、') : '—'}</span>
          </div>
          <div className="ag-info-row">
            <span className="k">知识库</span>
            <span className="v">{kbName || '—'}</span>
          </div>
          <div className="ag-info-row">
            <span className="k">可用角色</span>
            <span className="v">{roles.length ? roles.map((r) => r.roleName).join('、') : '—'}</span>
          </div>
        </div>

        <div className="ag-sec">
          <div className="ag-sec-head">
            <span className="ag-sec-title">装配后提示词</span>
            {promptText && <span className="ag-sec-extra">{promptText.length} 字</span>}
          </div>
          <div className="sql-box" style={{ maxHeight: expanded ? 'none' : 150 }}>
            {promptText || '加载中…'}
          </div>
          {promptText.length > 120 && (
            <Button type="link" size="small" style={{ padding: 0, marginTop: 6 }} onClick={() => setExpanded(!expanded)}>
              {expanded ? '收起' : '展开全部'}
            </Button>
          )}
        </div>

        <div className="ag-foot-note">保存后 version 自增 · 运行时自动热更新</div>
      </div>
    </>
  );
}
