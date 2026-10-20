import { useCallback, useEffect, useState } from 'react';
import {
  Button, Card, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag,
  message as antMessage,
} from 'antd';
import * as api from '../api';

/**
 * 技能包管理页。
 *
 * 技能 = 可复用的能力描述（常驻 description + 展开的 promptFragment + examples 示例），
 * 挂载关系在「数字员工配置」页。删改技能会自动 bump 引用它的数字员工 version ——
 * 运行时缓存随即重建，不需要重启（与配置页的「保存即热更新」同一套机制）。
 */
export default function SkillPage() {
  const [skills, setSkills] = useState([]);
  const [loading, setLoading] = useState(false);
  const [modal, setModal] = useState(null); // null | { mode: 'create' | 'edit', record? }
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const list = await api.listSkills();
      setSkills(Array.isArray(list) ? list : []);
    } catch (e) {
      antMessage.error('加载技能列表失败：' + e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  function openCreate() {
    form.resetFields();
    form.setFieldsValue({ status: 'active' });
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
      const res = isCreate
        ? await api.createSkill(values)
        : await api.updateSkill(modal.record.id, values);
      if (res && res.error) {
        antMessage.error(res.error);
        return;
      }
      const affected = res?.affectedAgents?.length || 0;
      antMessage.success(
        isCreate ? '技能已创建' : `已保存${affected ? `（${affected} 个数字员工已热更新）` : ''}`,
      );
      setModal(null);
      load();
    } catch (e) {
      antMessage.error((isCreate ? '创建失败：' : '保存失败：') + e.message);
    }
  }

  async function remove(record) {
    try {
      const res = await api.deleteSkill(record.id);
      if (res && res.error) {
        antMessage.error(res.error);
        return;
      }
      antMessage.success(`已删除，并摘除 ${res?.detachedRelations ?? 0} 条挂载`);
      load();
    } catch (e) {
      antMessage.error('删除失败：' + e.message);
    }
  }

  return (
    <div className="page-wrap">
      <h2 className="page-title">技能管理</h2>
      <p className="page-desc">
        技能 = 一段可复用的能力描述（常驻说明 + 展开的提示词片段 + 示例），可挂给任意数字员工；删改自动热更新。
      </p>

      <Card
        size="small"
        extra={
          <Button size="small" type="primary" onClick={openCreate}>
            新建技能
          </Button>
        }
      >
        <Table
          size="small"
          rowKey="id"
          loading={loading}
          dataSource={skills}
          columns={[
            { title: '技能名', dataIndex: 'skillName', width: 180 },
            {
              title: '编码',
              dataIndex: 'skillCode',
              width: 170,
              render: (v) => <span className="mono">{v}</span>,
            },
            { title: '常驻说明', dataIndex: 'description', ellipsis: true },
            {
              title: '提示词片段',
              dataIndex: 'promptFragment',
              ellipsis: true,
              render: (v) => (v ? <span className="mono">{String(v).slice(0, 60)}</span> : '—'),
            },
            {
              title: '示例',
              dataIndex: 'examples',
              width: 80,
              render: (v) => (v ? <Tag color="purple">有</Tag> : '—'),
            },
            {
              title: '状态',
              dataIndex: 'status',
              width: 90,
              render: (v) => <Tag color={v === 'active' ? 'green' : 'default'}>{v}</Tag>,
            },
            {
              title: '操作',
              width: 130,
              render: (_, r) => (
                <Space size={4}>
                  <Button size="small" type="link" onClick={() => openEdit(r)}>
                    编辑
                  </Button>
                  <Popconfirm
                    title={`删除技能「${r.skillName}」？`}
                    description="会同时摘除它在本平台的全部挂载"
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

      <Modal
        open={!!modal}
        title={modal?.mode === 'create' ? '新建技能' : '编辑技能'}
        onCancel={() => setModal(null)}
        onOk={submit}
        width={680}
        okText={modal?.mode === 'create' ? '创建' : '保存'}
      >
        <Form form={form} layout="vertical" size="small">
          {modal?.mode === 'create' && (
            <Form.Item
              name="skillCode"
              label="编码"
              rules={[
                { required: true, message: '请输入编码' },
                {
                  pattern: /^[a-z][a-z0-9_]{1,31}$/,
                  message: '小写字母开头，仅含小写字母/数字/下划线（2-32 位）',
                },
              ]}
              extra="创建后不可修改"
            >
              <Input placeholder="如 aging_analysis" />
            </Form.Item>
          )}
          <Form.Item name="skillName" label="技能名" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item
            name="description"
            label="常驻说明（进提示词，一句话）"
            rules={[{ required: true }]}
          >
            <Input placeholder="如：分析长库龄终端并给出盘活建议" />
          </Form.Item>
          <Form.Item
            name="promptFragment"
            label="提示词片段（按需展开）"
            extra="技能挂载后会拼接到人设之后，这里写具体做法、口径与注意事项"
          >
            <Input.TextArea rows={6} />
          </Form.Item>
          <Form.Item name="examples" label="示例（Few-shot，可选）">
            <Input.TextArea rows={4} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select
              options={[
                { value: 'active', label: '启用' },
                { value: 'disabled', label: '停用' },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}