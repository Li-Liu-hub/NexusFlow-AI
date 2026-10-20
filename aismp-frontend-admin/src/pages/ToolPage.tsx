import { useCallback, useEffect, useState } from 'react';
import {
  Alert, Button, Card, Collapse, Form, Input, InputNumber, Modal, Select,
  Space, Table, Tag, message as antMessage,
} from 'antd';
import * as api from '../api';

/**
 * 工具管理页。
 *
 * 核心价值：**创建时就能发现问题**。表单里的「试运行」会真的拿哑值参数执行一次 SQL，
 * 列名写错、表不存在这类问题当场暴露 —— 而不是等线上被模型调到才发现。
 * (SQL 类型才试运行；知识检索等非 SQL 类型会明确跳过。)
 */
export default function ToolPage() {
  const [tools, setTools] = useState([]);
  const [loading, setLoading] = useState(false);
  const [editing, setEditing] = useState(null); // null | {mode:'create'|'edit', data}
  const [checkResult, setCheckResult] = useState(null);
  const [schema, setSchema] = useState([]);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const list = await api.listTools();
      setTools(Array.isArray(list) ? list : []);
    } catch (e) {
      antMessage.error('加载工具列表失败：' + e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
    api.dbSchema().then((x) => setSchema(x || [])).catch(() => {});
  }, [load]);

  async function openCreate() {
    setCheckResult(null);
    const tpl = await api.toolTemplate().catch(() => ({}));
    setEditing({ mode: 'create', data: tpl });
    form.setFieldsValue(tpl);
  }

  function openEdit(record) {
    setCheckResult(null);
    setEditing({ mode: 'edit', data: record });
    form.setFieldsValue(record);
  }

  /** 只校验不落库 —— 让使用者先确认没问题再保存 */
  async function doValidate() {
    const values = await form.validateFields();
    try {
      const res = await api.validateTool(values, true);
      setCheckResult(res);
      if (res.valid) antMessage.success('校验通过');
    } catch (e) {
      setCheckResult({ valid: false, stage: '请求失败', message: e.message });
    }
  }

  async function doSave() {
    const values = await form.validateFields();
    try {
      if (editing.mode === 'create') {
        const res = await api.createTool(values);
        if (!res.created) {
          setCheckResult(res.check || { valid: false, message: res.message });
          antMessage.error('创建失败，请查看校验结果');
          return;
        }
        antMessage.success(`已创建工具（id=${res.id}）`);
      } else {
        const res = await api.updateTool(editing.data.id, values);
        if (!res.updated) {
          setCheckResult(res.check || { valid: false, message: '更新失败' });
          return;
        }
        antMessage.success(
          `已更新，受影响数字员工：${res.affectedAgents?.join('、') || '无'}（version 已自增）`
        );
      }
      setEditing(null);
      load();
    } catch (e) {
      antMessage.error('保存失败：' + e.message);
    }
  }

  async function doDelete(record) {
    Modal.confirm({
      title: `删除工具 ${record.displayName || record.toolName}？`,
      content: '会先摘掉所有挂载关系，并让受影响数字员工热更新。',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          const res = await api.deleteTool(record.id);
          antMessage.success(
            `已删除，摘除挂载 ${res.detachedRelations} 条，受影响数字员工：${
              res.affectedAgents?.join('、') || '无'
            }`
          );
          load();
        } catch (e) {
          antMessage.error('删除失败：' + e.message);
        }
      },
    });
  }

  const TYPE_OPTIONS = [
    { value: 'SQL_TEMPLATE', label: 'SQL_TEMPLATE · 查库（模板 + 命名参数）' },
    { value: 'KB_RETRIEVAL', label: 'KB_RETRIEVAL · 知识检索（调 RAG）' },
  ];

  return (
    <div className="page-wrap">
      <h2 className="page-title">工具管理</h2>
      <p className="page-desc">
        往 a_llm_tool 插一行 = 得到一个工具，不需要写 Java 代码。工具挂到数字员工上后，模型会自主决定何时调用。
      </p>

      <Card
        size="small"
        title={`工具清单（${tools.length}）`}
        extra={
          <Space>
            <Button size="small" onClick={load}>
              刷新
            </Button>
            <Button size="small" type="primary" onClick={openCreate}>
              新建工具
            </Button>
          </Space>
        }
      >
        <Table
          size="small"
          rowKey="id"
          loading={loading}
          dataSource={tools}
          pagination={{ pageSize: 10, size: 'small' }}
          columns={[
            {
              title: '工具中文名',
              dataIndex: 'displayName',
              width: 150,
              render: (v) => v || <span style={{ color: '#bfbfbf' }}>（未命名）</span>,
            },
            { title: '技术名（LLM 调用）', dataIndex: 'toolName', width: 190, render: (v) => <span className="mono">{v}</span> },
            {
              title: '类型',
              dataIndex: 'toolType',
              width: 130,
              render: (v) => <Tag color={v === 'KB_RETRIEVAL' ? 'purple' : 'blue'}>{v || 'SQL_TEMPLATE'}</Tag>,
            },
            { title: '描述（LLM 靠它选择）', dataIndex: 'toolDesc', ellipsis: true },
            {
              title: '状态',
              dataIndex: 'status',
              width: 80,
              render: (v) => <Tag color={v === 'active' ? 'green' : 'default'}>{v}</Tag>,
            },
            {
              title: '操作',
              width: 120,
              render: (_, r) => (
                <Space size={4}>
                  <Button size="small" type="link" onClick={() => openEdit(r)}>
                    编辑
                  </Button>
                  <Button size="small" type="link" danger onClick={() => doDelete(r)}>
                    删除
                  </Button>
                </Space>
              ),
            },
          ]}
        />
      </Card>

      {/* 可查库表结构 —— 写 SQL 模板时的参考 */}
      <Card size="small" style={{ marginTop: 16 }} title="可查库表结构（写模板时参考）">
        <Collapse
          size="small"
          items={(schema || []).map((t) => ({
            key: t.tableName,
            label: (
              <span>
                <span className="mono">{t.tableName}</span>
                <span style={{ color: '#999', marginLeft: 8, fontSize: 12 }}>
                  {t.tableComment}
                </span>
              </span>
            ),
            children: (
              <Space wrap size={6}>
                {(t.columns || []).map((c) => (
                  <Tag key={c.columnName} style={{ fontSize: 11 }}>
                    <span className="mono">{c.columnName}</span>
                    <span style={{ color: '#bbb', marginLeft: 4 }}>{c.columnType}</span>
                  </Tag>
                ))}
              </Space>
            ),
          }))}
        />
      </Card>

      {/* -------- 新建 / 编辑弹窗 -------- */}
      <Modal
        open={!!editing}
        title={editing?.mode === 'create' ? '新建工具' : '编辑工具'}
        onCancel={() => setEditing(null)}
        width={760}
        footer={[
          <Button key="cancel" onClick={() => setEditing(null)}>
            取消
          </Button>,
          <Button key="check" onClick={doValidate}>
            试运行校验
          </Button>,
          <Button key="ok" type="primary" onClick={doSave}>
            保存
          </Button>,
        ]}
      >
        <Form form={form} layout="vertical" size="small">
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item
              name="displayName"
              label="工具中文名（管理台显示）"
              style={{ flex: 1 }}
              extra="管理台各处显示这个名字"
            >
              <Input placeholder="如：按库位查库存" />
            </Form.Item>
            <Form.Item
              name="toolName"
              label="技术名（LLM 的 function name）"
              rules={[{ required: true }]}
              style={{ flex: 1 }}
              extra="只能字母/数字/下划线"
            >
              <Input placeholder="queryStockByLocation" />
            </Form.Item>
            <Form.Item name="toolType" label="类型" style={{ width: 220 }}>
              <Select options={TYPE_OPTIONS} />
            </Form.Item>
          </Space>

          <Form.Item
            name="toolDesc"
            label="描述"
            rules={[{ required: true }]}
            extra="写清「什么时候用它」—— 模型完全靠这句话决定调不调"
          >
            <Input.TextArea rows={2} />
          </Form.Item>

          <Form.Item
            name="inputSchema"
            label="入参 JSON Schema"
            rules={[{ required: true }]}
          >
            <Input.TextArea rows={3} className="mono" />
          </Form.Item>

          <Form.Item
            name="sqlTemplate"
            label="SQL 模板（仅 SQL_TEMPLATE 类型）"
            extra="用 :param 命名参数；只允许 SELECT，会强制加 LIMIT"
          >
            <Input.TextArea rows={4} className="mono" />
          </Form.Item>

          <Form.Item
            name="configJson"
            label="类型专属配置（仅 KB_RETRIEVAL 等非 SQL 类型）"
            extra='知识检索示例：{"endpoint":"/oa/chat/sync","timeoutSeconds":120}'
          >
            <Input.TextArea rows={2} className="mono" />
          </Form.Item>

          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="resultLimit" label="返回行数上限" style={{ width: 160 }}>
              <InputNumber min={1} max={5000} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="status" label="状态" style={{ width: 140 }}>
              <Select
                options={[
                  { value: 'active', label: '启用' },
                  { value: 'disabled', label: '停用' },
                ]}
              />
            </Form.Item>
            <Form.Item name="remark" label="备注" style={{ flex: 1 }}>
              <Input />
            </Form.Item>
          </Space>
        </Form>

        {checkResult && (
          <Alert
            style={{ marginTop: 8 }}
            type={checkResult.valid ? 'success' : 'error'}
            showIcon
            message={`${checkResult.stage || '校验'}：${checkResult.valid ? '通过' : '未通过'}`}
            description={
              <div style={{ fontSize: 12 }}>
                {checkResult.message && <div>{checkResult.message}</div>}
                {checkResult.note && <div style={{ color: '#8c8c8c' }}>{checkResult.note}</div>}
                {checkResult.sampleRowCount != null && (
                  <div style={{ color: '#8c8c8c' }}>
                    试运行返回 {checkResult.sampleRowCount} 行
                  </div>
                )}
              </div>
            }
          />
        )}
      </Modal>
    </div>
  );
}
