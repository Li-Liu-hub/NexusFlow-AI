import { useCallback, useEffect, useState } from 'react';
import {
  Alert, Button, Card, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, Tooltip,
  message as antMessage,
} from 'antd';
import * as api from '../api';

/**
 * MCP 服务管理页。
 *
 * 平台的 MCP 方向有两个：① agent 侧对外暴露 MCP server（aismp-agent /sse）；
 * ② 本页 —— 把**外部 MCP server 接进来**：注册地址 → 探测工具清单 → 一键生成工具定义
 * （a_llm_tool，tool_type=MCP）→ 到「数字员工配置」挂载，模型即可调用。
 *
 * 自包含验收：把平台自己的 MCP server（http://127.0.0.1:8124，SSE）注册进来，
 * 探测应能列出 queryOrderStatus / searchKnowledgeBase 等工具。
 */
export default function McpPage() {
  const [servers, setServers] = useState([]);
  const [loading, setLoading] = useState(false);
  const [modal, setModal] = useState(null); // null | { mode: 'create' | 'edit', record? }
  const [probe, setProbe] = useState(null); // null | { server, loading, result }
  const [generated, setGenerated] = useState({}); // 远端工具名 → 平台工具名（本次会话内已生成）
  /** 平台里已存在的 MCP 工具名（页面加载时刷新）——避免对已生成的工具重复点「生成」 */
  const [existingMcpTools, setExistingMcpTools] = useState([]);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const list = await api.listMcpServers();
      setServers(Array.isArray(list) ? list : []);
      const tools = await api.listTools().catch(() => []);
      setExistingMcpTools((tools || [])
        .filter((t) => t.toolType === 'MCP')
        .map((t) => t.toolName));
    } catch (e) {
      antMessage.error('加载 MCP 服务失败：' + e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  function openCreate() {
    form.resetFields();
    form.setFieldsValue({ transport: 'SSE', status: 'active', authType: 'NONE' });
    setModal({ mode: 'create' });
  }

  function openEdit(record) {
    form.setFieldsValue(record);
    setModal({ mode: 'edit', record });
  }

  async function submit() {
    const values = await form.validateFields();
    const isCreate = modal?.mode === 'create';
    try {
      if (isCreate) {
        await api.createMcpServer(values);
        antMessage.success('MCP 服务已注册，接下来点「探测工具」验证连通性');
      } else {
        await api.updateMcpServer(modal.record.id, values);
        antMessage.success('已保存');
      }
      setModal(null);
      load();
    } catch (e) {
      antMessage.error((isCreate ? '注册失败：' : '保存失败：') + e.message);
    }
  }

  async function remove(record) {
    try {
      await api.deleteMcpServer(record.id);
      antMessage.success(`已删除「${record.serverName}」`);
      load();
    } catch (e) {
      antMessage.error('删除失败：' + e.message);
    }
  }

  async function doProbe(record) {
    setProbe({ server: record, loading: true, result: null });
    try {
      const result = await api.probeMcpServer(record.id);
      setProbe({ server: record, loading: false, result });
      load();
    } catch (e) {
      setProbe({ server: record, loading: false, result: { ok: false, error: e.message } });
      load();
    }
  }

  /** 一键生成工具定义：把探测到的远端工具落成 a_llm_tool（toolType=MCP）。 */
  async function generateTool(tool) {
    const payload = {
      toolName: tool.name,
      toolDesc: tool.description || `MCP 工具 ${tool.name}`,
      toolType: 'MCP',
      inputSchema: tool.inputSchema || '{"type":"object","properties":{}}',
      configJson: JSON.stringify({ serverCode: probe.server.serverCode, toolName: tool.name }),
      resultLimit: 10,
      status: 'active',
      remark: `由 MCP 服务 ${probe.server.serverCode} 探测生成`,
    };
    try {
      const res = await api.createTool(payload);
      if (res && res.created) {
        antMessage.success(`已生成工具定义「${tool.name}」—— 到「数字员工配置」挂载即可调用`);
        setGenerated((g) => ({ ...g, [tool.name]: true }));
      } else {
        antMessage.error(res?.message || res?.check?.message || '生成失败');
      }
    } catch (e) {
      antMessage.error('生成失败：' + e.message);
    }
  }

  const probeTools = probe?.result?.tools || [];

  return (
    <div className="page-wrap">
      <h2 className="page-title">MCP 管理</h2>
      <p className="page-desc">
        把外部 MCP server 接入平台：注册地址 → 探测远端工具清单 → 一键生成工具定义 →
        到「数字员工配置」挂载。平台自身也对外暴露 MCP server（aismp-agent 的 /sse），可用来做自包含验证。
      </p>

      <Card
        size="small"
        title="已注册的 MCP 服务"
        extra={
          <Button size="small" type="primary" onClick={openCreate}>
            注册 MCP 服务
          </Button>
        }
      >
        <Table
          size="small"
          rowKey="id"
          loading={loading}
          dataSource={servers}
          pagination={false}
          columns={[
            {
              title: '编码',
              dataIndex: 'serverCode',
              width: 180,
              render: (v) => <span className="mono">{v}</span>,
            },
            { title: '名称', dataIndex: 'serverName', width: 160 },
            {
              title: '传输',
              dataIndex: 'transport',
              width: 80,
              render: (v) => <Tag color="blue">{v}</Tag>,
            },
            {
              title: '地址',
              dataIndex: 'baseUrl',
              ellipsis: true,
              render: (v) => <span className="mono">{v}</span>,
            },
            {
              title: '状态',
              dataIndex: 'status',
              width: 90,
              render: (v) => <Tag color={v === 'active' ? 'green' : 'default'}>{v}</Tag>,
            },
            {
              title: '最近探测',
              dataIndex: 'lastSyncTime',
              width: 170,
              render: (v, r) => {
                if (r.lastError) {
                  return (
                    <Tooltip title={r.lastError}>
                      <Tag color="red">失败</Tag>
                    </Tooltip>
                  );
                }
                return v ? String(v).replace('T', ' ').slice(0, 19) : '—';
              },
            },
            {
              title: '操作',
              width: 200,
              render: (_, r) => (
                <Space size={4}>
                  <Button size="small" type="link" onClick={() => doProbe(r)}>
                    探测工具
                  </Button>
                  <Button size="small" type="link" onClick={() => openEdit(r)}>
                    编辑
                  </Button>
                  <Popconfirm
                    title={`删除「${r.serverName}」？`}
                    description="被工具定义引用时会被拒绝（先删工具）"
                    onConfirm={() => remove(r)}
                  >
                    <Button size="small" type="link" danger>
                      删除
                    </Button>
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />
      </Card>

      {/* -------- 探测结果 -------- */}
      <Modal
        open={!!probe}
        title={`探测工具：${probe?.server?.serverName || ''}`}
        onCancel={() => setProbe(null)}
        footer={null}
        width={880}
      >
        {probe?.loading && <div style={{ padding: 24, textAlign: 'center' }}>正在连接并握手…</div>}
        {probe && !probe.loading && probe.result && !probe.result.ok && (
          <Alert type="error" showIcon message="探测失败" description={probe.result.error} />
        )}
        {probe && !probe.loading && probe.result?.ok && (
          <>
            <div style={{ marginBottom: 8, fontSize: 12, color: '#8c8c8c' }}>
              远端 server：{probe.result.serverName} {probe.result.serverVersion} · 发现{' '}
              {probeTools.length} 个工具 · 耗时 {probe.result.elapsedMs}ms
            </div>
            <Table
              size="small"
              rowKey="name"
              dataSource={probeTools}
              pagination={false}
              columns={[
                {
                  title: '工具名',
                  dataIndex: 'name',
                  width: 200,
                  render: (v) => <span className="mono">{v}</span>,
                },
                { title: '说明', dataIndex: 'description', ellipsis: true },
                {
                  title: '入参 Schema',
                  dataIndex: 'inputSchema',
                  width: 120,
                  render: (v) => (
                    <Tooltip title={<pre style={{ maxHeight: 300, overflow: 'auto' }}>{v}</pre>}>
                      <span className="mono" style={{ fontSize: 11 }}>
                        {(v || '').slice(0, 40)}…
                      </span>
                    </Tooltip>
                  ),
                },
                {
                  title: '操作',
                  width: 130,
                  render: (_, t) =>
                    generated[t.name] || existingMcpTools.includes(t.name) ? (
                      <Tag color="green">已生成</Tag>
                    ) : (
                      <Button size="small" type="link" onClick={() => generateTool(t)}>
                        生成工具定义
                      </Button>
                    ),
                },
              ]}
            />
            <div style={{ marginTop: 8, fontSize: 12, color: '#8c8c8c' }}>
              生成后在「数字员工配置 → 工具」里挂载；工具定义的 config_json 会引用本服务的编码{' '}
              <span className="mono">{probe.server.serverCode}</span>。
            </div>
          </>
        )}
      </Modal>

      {/* -------- 注册/编辑 -------- */}
      <Modal
        open={!!modal}
        title={modal?.mode === 'create' ? '注册 MCP 服务' : '编辑 MCP 服务'}
        onCancel={() => setModal(null)}
        onOk={submit}
        width={620}
        okText={modal?.mode === 'create' ? '注册' : '保存'}
      >
        <Form form={form} layout="vertical" size="small">
          {modal?.mode === 'create' && (
            <Form.Item
              name="serverCode"
              label="服务编码"
              rules={[
                { required: true, message: '请输入编码' },
                {
                  pattern: /^[a-z][a-z0-9_-]{1,63}$/,
                  message: '小写字母开头，仅含小写字母/数字/下划线/中划线（2-64 位）',
                },
              ]}
              extra="创建后不可修改（工具定义的 config_json 按它引用）"
            >
              <Input placeholder="如 aismp-agent-mcp" />
            </Form.Item>
          )}
          <Form.Item name="serverName" label="名称" rules={[{ required: true }]}>
            <Input placeholder="如 平台自身 MCP server" />
          </Form.Item>
          <Form.Item name="transport" label="传输类型" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'SSE', label: 'SSE（http://host:port，端点默认 /sse）' },
                { value: 'HTTP', label: 'HTTP（streamable http，填完整端点地址）' },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="baseUrl"
            label="地址"
            rules={[
              { required: true, message: '请输入地址' },
              { pattern: /^https?:\/\//, message: '须以 http:// 或 https:// 开头' },
            ]}
          >
            <Input placeholder="http://127.0.0.1:8124" />
          </Form.Item>
          <Form.Item
            name="headersJson"
            label="自定义请求头（可选，JSON 对象）"
            extra='需要鉴权的远端服务在这里带凭证，如 {"Authorization":"Bearer xxx"}'
          >
            <Input.TextArea rows={2} placeholder='{"Authorization":"Bearer xxx"}' />
          </Form.Item>
          {modal?.mode === 'edit' && (
            <Form.Item name="status" label="状态">
              <Select
                options={[
                  { value: 'active', label: '启用' },
                  { value: 'disabled', label: '停用' },
                ]}
              />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </div>
  );
}