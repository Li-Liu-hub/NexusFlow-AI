import { useCallback, useEffect, useState } from 'react';
import { Button, Card, Empty, Space, Table, Tag, message as antMessage } from 'antd';
import * as api from '../api';

/**
 * 运行时观察页。
 *
 * 回答运维最关心的一个问题：**此刻跑着的到底是哪一版配置？**
 * 缓存里的 version 与数据库的 version 一比，就能确认热更新有没有生效。
 */
export default function RuntimePage() {
  const [cache, setCache] = useState({});
  const [agents, setAgents] = useState([]);
  const [selected, setSelected] = useState(null);
  const [info, setInfo] = useState(null);

  const load = useCallback(async () => {
    try {
      const [c, list] = await Promise.all([api.runtimeCache(), api.listAgents()]);
      setCache(c || {});
      setAgents(Array.isArray(list) ? list : []);
    } catch (e) {
      antMessage.error('加载运行时状态失败：' + e.message);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function inspect(code) {
    setSelected(code);
    try {
      setInfo(await api.introspect(code));
    } catch (e) {
      antMessage.error('自省失败：' + e.message);
    }
  }

  async function doReload(code) {
    try {
      const res = await api.reloadAgent(code);
      antMessage.success(`已逐出缓存（evicted=${res.evicted}）`);
      load();
      if (selected === code) inspect(code);
    } catch (e) {
      antMessage.error('刷新失败：' + e.message);
    }
  }

  const rows = agents.map((a) => ({
    ...a,
    cachedVersion: cache[a.agentCode],
    /** 缓存版本落后于配置版本 → 下次对话会自动重建 */
    pending: cache[a.agentCode] != null && cache[a.agentCode] !== a.version,
    notCached: cache[a.agentCode] == null,
  }));

  return (
    <div className="page-wrap">
      <h2 className="page-title">运行时观察</h2>
      <p className="page-desc">
        数字员工在内存里按 agent_code 缓存。每次请求先比一次 version，不一致才重建 ——
        所以改配置不必重启。「配置版本」与「缓存版本」不一致时，下一次对话就会自动生效。
      </p>

      <Card
        size="small"
        title="配置版本 vs 缓存版本"
        extra={
          <Button size="small" onClick={load}>
            刷新
          </Button>
        }
      >
        <Table
          size="small"
          rowKey="agentCode"
          pagination={false}
          dataSource={rows}
          columns={[
            {
              title: '数字员工',
              dataIndex: 'agentName',
              render: (v, r) => (
                <a onClick={() => inspect(r.agentCode)}>
                  {v} <span className="mono" style={{ color: '#999' }}>({r.agentCode})</span>
                </a>
              ),
            },
            {
              title: '配置版本',
              dataIndex: 'version',
              width: 100,
              render: (v) => <Tag color="blue">v{v}</Tag>,
            },
            {
              title: '缓存版本',
              dataIndex: 'cachedVersion',
              width: 100,
              render: (v) =>
                v == null ? <Tag color="default">未装配</Tag> : <Tag color="purple">v{v}</Tag>,
            },
            {
              title: '状态',
              width: 150,
              render: (_, r) =>
                r.notCached ? (
                  <span style={{ color: '#999', fontSize: 12 }}>尚未被调用过</span>
                ) : r.pending ? (
                  <Tag color="orange">下次对话将重建</Tag>
                ) : (
                  <Tag color="green">已同步</Tag>
                ),
            },
            {
              title: '操作',
              width: 120,
              render: (_, r) => (
                <Space size={4}>
                  <Button size="small" type="link" onClick={() => inspect(r.agentCode)}>
                    自省
                  </Button>
                  <Button size="small" type="link" onClick={() => doReload(r.agentCode)}>
                    逐出缓存
                  </Button>
                </Space>
              ),
            },
          ]}
        />
      </Card>

      <Card size="small" style={{ marginTop: 16 }} title={selected ? `装配结果 · ${selected}` : '装配结果'}>
        {!info ? (
          <Empty description="点上方「自省」查看某个数字员工此刻装了什么" />
        ) : (
          <>
            <Space style={{ marginBottom: 12 }}>
              <Tag color="blue">v{info.version}</Tag>
              {(info.skills || []).map((s) => (
                <Tag key={s} color="purple">
                  {s}
                </Tag>
              ))}
              {(info.tools || []).map((t) => (
                <Tag key={t}>{t}</Tag>
              ))}
            </Space>
            <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 6 }}>
              拼装后的完整系统提示词（人设 + 技能片段）
            </div>
            <div className="sql-box">{info.systemPrompt}</div>
          </>
        )}
      </Card>
    </div>
  );
}
