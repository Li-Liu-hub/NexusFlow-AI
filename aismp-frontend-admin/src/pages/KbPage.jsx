import { useCallback, useEffect, useState } from 'react';
import {
  Button, Card, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Tag, Tooltip, Upload,
  message as antMessage,
} from 'antd';
import * as api from '../api';

/**
 * 知识库管理页。
 *
 * 数据面在上半部分（知识库 CRUD），文档面点「文档管理」在弹窗里打开（上传/删除/重建索引）。
 * 上传时要选「分类」：agent 侧 RAG 检索带着分类过滤（intro/process/role/operation/trouble），
 * 不指定分类的文档只在「分类=none」的提问里可被检索到 —— 页面里专门写了提示。
 */
const CATEGORY_OPTIONS = [
  { value: '', label: '不指定分类（仅无分类提问可命中）' },
  { value: 'intro', label: 'intro · 引言与背景' },
  { value: 'process', label: 'process · 系统流程' },
  { value: 'role', label: 'role · 角色职责' },
  { value: 'operation', label: 'operation · 业务操作' },
  { value: 'trouble', label: 'trouble · 常见问题' },
];

const STATUS_TAG = {
  ready: { color: 'green', text: '已入库' },
  processing: { color: 'blue', text: '处理中' },
  failed: { color: 'red', text: '失败' },
  superseded: { color: 'default', text: '已被新版本替代' },
};

function formatSize(bytes) {
  if (bytes == null) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

export default function KbPage() {
  const [kbs, setKbs] = useState([]);
  const [loading, setLoading] = useState(false);
  const [selected, setSelected] = useState(null); // 当前展开文档的知识库
  const [docs, setDocs] = useState([]);
  const [docsLoading, setDocsLoading] = useState(false);
  const [modal, setModal] = useState(null); // null | { mode: 'create' | 'edit', record? }
  const [category, setCategory] = useState('');
  const [reindexing, setReindexing] = useState(false);
  // 入库参数（嵌入模型只读 + 切片大小默认值/区间），由内核 /admin/kb/settings 提供
  const [settings, setSettings] = useState(null);
  const [form] = Form.useForm();

  const loadKbs = useCallback(async () => {
    setLoading(true);
    try {
      const list = await api.listKbsAdmin();
      setKbs(Array.isArray(list) ? list : []);
    } catch (e) {
      antMessage.error('加载知识库失败：' + e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  const loadDocs = useCallback(async (kb) => {
    if (!kb) return;
    setDocsLoading(true);
    try {
      const list = await api.listKbDocuments(kb.id);
      setDocs(Array.isArray(list) ? list : []);
    } catch (e) {
      antMessage.error('加载文档失败：' + e.message);
    } finally {
      setDocsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadKbs();
    api.getKbSettings().then((s) => setSettings(s || null)).catch(() => {});
  }, [loadKbs]);

  function openCreate() {
    form.resetFields();
    form.setFieldsValue({ chunkSize: settings?.defaultChunkSize ?? 800 });
    setModal({ mode: 'create' });
  }

  function openEdit(record) {
    form.setFieldsValue({
      name: record.name,
      description: record.description,
      status: record.status,
      chunkSize: record.chunk_size ?? settings?.defaultChunkSize ?? 800,
    });
    setModal({ mode: 'edit', record });
  }

  async function submit() {
    const values = await form.validateFields();
    const isCreate = modal?.mode === 'create';
    try {
      if (isCreate) {
        await api.createKb(values);
        antMessage.success('知识库已创建');
      } else {
        await api.updateKb(modal.record.id, values);
        antMessage.success('已保存');
      }
      setModal(null);
      loadKbs();
    } catch (e) {
      antMessage.error((isCreate ? '创建失败：' : '保存失败：') + e.message);
    }
  }

  async function remove(record) {
    try {
      await api.deleteKb(record.id);
      antMessage.success(`知识库「${record.name}」已删除`);
      if (selected?.id === record.id) {
        setSelected(null);
        setDocs([]);
      }
      loadKbs();
    } catch (e) {
      antMessage.error('删除失败：' + e.message);
    }
  }

  async function removeDoc(record) {
    try {
      await api.deleteKbDocument(selected.id, record.documentId);
      antMessage.success(`文档「${record.sourceName}」已删除（向量已同步清理）`);
      loadDocs(selected);
      loadKbs();
    } catch (e) {
      antMessage.error('删除失败：' + e.message);
    }
  }

  async function reindex() {
    setReindexing(true);
    try {
      const res = await api.reindexKb(selected.id);
      const fails = (res.results || []).filter((r) => r.error);
      if (fails.length === 0) {
        antMessage.success(`重建完成：${res.ok}/${res.total} 篇文档已重新向量化`);
      } else {
        Modal.info({
          title: `重建完成：成功 ${res.ok} / 共 ${res.total}`,
          content: (
            <div style={{ fontSize: 12 }}>
              {fails.map((f) => (
                <div key={f.documentId} style={{ marginTop: 6 }}>
                  <span className="mono">{f.sourceName}</span>：{f.error}
                </div>
              ))}
            </div>
          ),
        });
      }
      loadDocs(selected);
      loadKbs();
    } catch (e) {
      antMessage.error('重建失败：' + e.message);
    } finally {
      setReindexing(false);
    }
  }

  return (
    <div className="page-wrap">
      <h2 className="page-title">知识库管理</h2>
      <p className="page-desc">
        知识库 = 文档集合（向量化后供数字员工检索）。上传文本类文档（txt / md / markdown / csv /
        json），选好分类后自动切分并向量化入库；「挂载」在数字员工配置页完成。
      </p>

      <Card
        size="small"
        title="知识库"
        extra={
          <Button size="small" type="primary" onClick={openCreate}>
            新建知识库
          </Button>
        }
      >
        <Table
          size="small"
          rowKey="id"
          loading={loading}
          dataSource={kbs}
          pagination={false}
          columns={[
            {
              title: '名称',
              dataIndex: 'name',
              width: 200,
              render: (v, r) => (
                <Space size={6}>
                  <span>{v}</span>
                  {r.is_default === 1 && <Tag color="gold">默认</Tag>}
                </Space>
              ),
            },
            { title: '说明', dataIndex: 'description', ellipsis: true },
            {
              title: '文档',
              dataIndex: 'doc_count',
              width: 70,
              align: 'center',
            },
            {
              title: '分片',
              dataIndex: 'chunk_count',
              width: 70,
              align: 'center',
            },
            {
              title: '切片大小',
              dataIndex: 'chunk_size',
              width: 90,
              align: 'center',
              render: (v) => (v == null ? '—' : v),
            },
            {
              title: '状态',
              dataIndex: 'status',
              width: 80,
              render: (v) => <Tag color={v === 'active' ? 'green' : 'default'}>{v}</Tag>,
            },
            {
              title: '操作',
              width: 200,
              render: (_, r) => (
                <Space size={4}>
                  <Button
                    size="small"
                    type="link"
                    onClick={() => {
                      setSelected(r);
                      setCategory('');
                      loadDocs(r);
                    }}
                  >
                    文档管理
                  </Button>
                  <Button size="small" type="link" onClick={() => openEdit(r)}>
                    编辑
                  </Button>
                  <Popconfirm
                    title={`删除知识库「${r.name}」？`}
                    description="库内文档一并删除（软删），挂载中的数字员工需先解除挂载"
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

      {/* 文档管理弹窗（2026-10-09）：原来嵌在页面下方的第二个表格改为弹窗 */}
      <Modal
        open={!!selected}
        title={
          <Space>
            <span>文档：{selected?.name}</span>
            <span className="mono" style={{ fontSize: 11, color: '#999' }}>
              {selected?.knowledge_base_id}
            </span>
          </Space>
        }
        onCancel={() => setSelected(null)}
        footer={null}
        width={920}
        destroyOnClose={false}
      >
        <Space style={{ marginBottom: 12 }} wrap>
          <Select
            size="small"
            style={{ width: 260 }}
            value={category}
            onChange={setCategory}
            options={CATEGORY_OPTIONS}
          />
          <Upload
            showUploadList={false}
            customRequest={async ({ file, onSuccess, onError }) => {
              try {
                const res = await api.uploadKbDocument(selected.id, file, category || undefined);
                onSuccess(res);
                antMessage.success(
                  `已入库「${file.name}」：${res.chunkCount} 个分片（${res.embeddingModel}）`,
                );
                loadDocs(selected);
                loadKbs();
              } catch (e) {
                onError(e);
                antMessage.error(`上传失败：${e.message}`);
                loadDocs(selected);
              }
            }}
          >
            <Button size="small" type="primary">
              上传文档
            </Button>
          </Upload>
          <Popconfirm
            title="按落盘源文件重建全部文档的向量索引？"
            onConfirm={reindex}
          >
            <Button size="small" loading={reindexing}>
              重建索引
            </Button>
          </Popconfirm>
        </Space>
        <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 8 }}>
          提示：分类决定文档能被哪类提问检索到（与 RAG 的分类过滤对齐）；「不指定分类」的文档只在
          LLM 判定「与五个分类都无关」的提问里可命中。
        </div>
        <Table
          size="small"
          rowKey="id"
          loading={docsLoading}
          dataSource={docs}
          pagination={false}
          columns={[
            { title: '文件名', dataIndex: 'sourceName', ellipsis: true },
            {
              title: '状态',
              dataIndex: 'status',
              width: 110,
              render: (v, r) => {
                const t = STATUS_TAG[v] || { color: 'default', text: v };
                const tag = <Tag color={t.color}>{t.text}</Tag>;
                return v === 'failed' && r.errorMessage ? (
                  <Tooltip title={r.errorMessage}>{tag}</Tooltip>
                ) : (
                  tag
                );
              },
            },
            { title: '分片', dataIndex: 'chunkCount', width: 60, align: 'center' },
            {
              title: '大小',
              dataIndex: 'sizeBytes',
              width: 90,
              render: formatSize,
            },
            {
              title: '嵌入模型',
              dataIndex: 'embeddingModel',
              width: 130,
              render: (v) => (v ? <span className="mono" style={{ fontSize: 11 }}>{v}</span> : '—'),
            },
            {
              title: '上传时间',
              dataIndex: 'createdAt',
              width: 160,
              render: (v) => (v ? String(v).replace('T', ' ').slice(0, 19) : '—'),
            },
            {
              title: '操作',
              width: 80,
              render: (_, r) => (
                <Popconfirm
                  title={`删除文档「${r.sourceName}」？`}
                  description="同时删除其全部向量分片"
                  onConfirm={() => removeDoc(r)}
                >
                  <Button size="small" type="link" danger>
                    删除
                  </Button>
                </Popconfirm>
              ),
            },
          ]}
        />
      </Modal>

      <Modal
        open={!!modal}
        title={modal?.mode === 'create' ? '新建知识库' : '编辑知识库'}
        onCancel={() => setModal(null)}
        onOk={submit}
        width={560}
        okText={modal?.mode === 'create' ? '创建' : '保存'}
      >
        <Form form={form} layout="vertical" size="small">
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如：终端业务规范库" />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item
            label="嵌入模型"
            extra="平台统一口径（检索与入库必须同模型）；如需更换需改 RAG 服务配置并重建全库索引"
          >
            <Input value={settings?.embeddingModel || 'BAAI/bge-m3'} disabled readOnly />
          </Form.Item>
          <Form.Item
            name="chunkSize"
            label="切片大小（字符）"
            extra="上传 / 重建索引时按此长度切分：段落优先，超长段落按句末标点回看硬切"
          >
            <InputNumber
              min={settings?.minChunkSize ?? 100}
              max={settings?.maxChunkSize ?? 4000}
              step={100}
              style={{ width: 200 }}
            />
          </Form.Item>
          {modal?.mode === 'edit' && (
            <Form.Item name="status" label="状态">
              <Select
                options={[
                  { value: 'active', label: '启用（可上传、可挂载）' },
                  { value: 'inactive', label: '停用' },
                ]}
              />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </div>
  );
}