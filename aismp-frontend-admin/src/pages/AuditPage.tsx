import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Button, Card, Col, Empty, Row, Segmented, Space, Table, Tag, Tooltip, message as antMessage,
} from 'antd';
import * as api from '../api';

/**
 * 调用审计页。
 *
 * 这页回答「数字员工到底查了什么」—— 出问题时能凭 traceId 还原
 * 「谁在什么时候、用哪句话、调了哪个工具、拿到几条、花了多久、成没成」。
 * 注意：**排障要以这些数据为准，不要采信模型对自身执行过程的描述**（它可能会编）。
 *
 * 展示口径（2026-10-08）：按工具聚合用条形/饼图视图呈现（不再落报表式表格）；
 * 工具同时给两个口径 —— 英文内部标识（审计口径）+ 工具定义里的中文说明（人读口径），
 * 中文说明来自 /admin/tool/list，未登记的工具退化为只显示标识。
 */

const PIE_COLORS = ['#6D5BD0', '#8a7be0', '#a99df0', '#5b8ff9', '#61ddaa', '#f6bd16',
  '#ff9d4d', '#f2637b', '#9270ca', '#69c0ff', '#ffd666', '#95de64'];

/** 图表轴/卡片里放不下整段工具说明，截断展示（完整内容走 Tooltip） */
function clip(s, n = 26) {
  return s && s.length > n ? `${s.slice(0, n)}…` : s;
}

export default function AuditPage() {
  const [rows, setRows] = useState([]);
  const [stats, setStats] = useState([]);
  const [catalog, setCatalog] = useState({}); // tool_name → { displayName, desc }（工具中文名 + 中文说明）
  const [view, setView] = useState('bar'); // bar | pie
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [recent, st, tools] = await Promise.all([
        api.recentToolCalls(50),
        api.toolCallStats(),
        // 工具目录拉不到不挡审计：中文说明列退化为「—」，标识照常展示
        api.listTools().catch(() => []),
      ]);
      setRows(Array.isArray(recent) ? recent : []);
      setStats(Array.isArray(st) ? st : []);
      const map = {};
      (Array.isArray(tools) ? tools : []).forEach((t) => {
        map[t.toolName] = { displayName: t.displayName || '', desc: t.toolDesc || '' };
      });
      setCatalog(map);
    } catch (e) {
      antMessage.error('加载审计数据失败：' + e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const totalCalls = stats.reduce((s, r) => s + Number(r.call_count || 0), 0);
  const totalErrors = stats.reduce((s, r) => s + Number(r.error_count || 0), 0);
  const avgMs = stats.length
    ? Math.round(stats.reduce((s, r) => s + Number(r.avg_duration_ms || 0), 0) / stats.length)
    : 0;

  /** 聚合项：次数/失败/耗时 + 中文说明（统计接口已按调用次数倒序） */
  const items = useMemo(() => stats.map((r) => ({
    name: r.tool_name,
    cn: catalog[r.tool_name]?.displayName || '',
    desc: catalog[r.tool_name]?.desc || '',
    calls: Number(r.call_count || 0),
    errors: Number(r.error_count || 0),
    avgMs: r.avg_duration_ms == null ? null : Number(r.avg_duration_ms),
    rows: Number(r.total_rows || 0),
  })), [stats, catalog]);

  const maxCalls = Math.max(1, ...items.map((i) => i.calls));

  /** 条形视图：条长 = 调用次数；条内右端红色段 = 失败占比 */
  function barView() {
    return items.map((i) => {
      const calls = Math.max(i.calls, 1);
      const totalPct = (i.calls / maxCalls) * 100;
      const okPct = totalPct * ((calls - i.errors) / calls);
      const errPct = totalPct * (i.errors / calls);
      return (
        <div className="vbar-block" key={i.name}>
          <div className="vbar-title">
            <span className="nm" title={i.cn || i.desc || i.name}>
              {i.cn || (i.desc ? clip(i.desc, 32) : '（未登记中文名）')}
            </span>
            <span className="id mono">{i.name}</span>
            {i.errors > 0 && <Tag color="red" style={{ marginLeft: 'auto', marginInlineEnd: 0 }}>失败 {i.errors}</Tag>}
          </div>
          <div className="vbar-row">
            <div className="vbar-track">
              {okPct > 0 && <div className="vbar-fill" style={{ width: `${okPct}%`, background: '#534ab7' }} />}
              {errPct > 0 && <div className="vbar-fill" style={{ width: `${errPct}%`, background: '#ff4d4f' }} />}
            </div>
            <span className="vbar-val">
              <b>{i.calls.toLocaleString()} 次</b>
              <span style={{ color: '#999' }}> · 均 {i.avgMs ?? '-'}ms · 返回 {i.rows.toLocaleString()} 行</span>
            </span>
          </div>
        </div>
      );
    });
  }

  /** 饼图视图：按调用次数占比 + 右侧图例（含失败数） */
  function pieView() {
    const total = items.reduce((s, i) => s + i.calls, 0);
    if (!total) return <div className="chart-empty">暂无可展示的数据</div>;
    const R = 84;
    const CX = 96;
    const CY = 96;
    let angle = -Math.PI / 2;
    const shapes = [];
    const legend = [];
    items.forEach((i, idx) => {
      if (i.calls <= 0) return;
      const frac = i.calls / total;
      const a2 = angle + frac * Math.PI * 2;
      const color = PIE_COLORS[idx % PIE_COLORS.length];
      const x1 = (CX + R * Math.cos(angle)).toFixed(2);
      const y1 = (CY + R * Math.sin(angle)).toFixed(2);
      const x2 = (CX + R * Math.cos(a2)).toFixed(2);
      const y2 = (CY + R * Math.sin(a2)).toFixed(2);
      const large = frac > 0.5 ? 1 : 0;
      // 单项占满 100% 时 arc 画不出整圆，用小弧差回退
      const d = frac >= 0.9999
        ? `M ${CX} ${CY - R} A ${R} ${R} 0 1 1 ${CX - 0.01} ${CY - R} Z`
        : `M ${CX} ${CY} L ${x1} ${y1} A ${R} ${R} 0 ${large} 1 ${x2} ${y2} Z`;
      shapes.push(
        <path key={i.name} d={d} fill={color} stroke="#fff" strokeWidth={1.5}>
          <title>{`${i.name}：${i.calls} 次（${(frac * 100).toFixed(1)}%）`}</title>
        </path>,
      );
      legend.push(
        <div className="pie-leg-row" key={i.name}>
          <span className="pie-dot" style={{ background: color }} />
          <span className="pl" title={`${i.cn || i.desc ? `${i.cn || i.desc}（${i.name}）` : i.name}`}>
            {i.cn || (i.desc ? clip(i.desc, 18) : i.name)}
          </span>
          <span className="pv">
            {i.calls.toLocaleString()} 次{i.errors > 0 ? `（失败 ${i.errors}）` : ''} · {(frac * 100).toFixed(1)}%
          </span>
        </div>,
      );
      angle = a2;
    });
    return (
      <div className="pie-wrap">
        <svg width="192" height="192" viewBox="0 0 192 192">{shapes}</svg>
        <div className="pie-legend">{legend}</div>
      </div>
    );
  }

  return (
    <div className="page-wrap">
      <h2 className="page-title">调用审计</h2>
      <p className="page-desc">
        每次工具调用都落 a_tool_call_log：traceId / 会话 / 入参 / 结果数 / 耗时 / 状态。
        排障时以此为准 —— 模型自述的执行过程可能不准确。
      </p>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        {[
          { label: '累计工具调用', value: totalCalls },
          { label: '失败次数', value: totalErrors },
          { label: '平均耗时', value: `${avgMs} ms` },
          { label: '涉及工具数', value: stats.length },
        ].map((c) => (
          <Col span={6} key={c.label}>
            <div className="stat-card">
              <div className="stat-num">{c.value}</div>
              <div className="stat-label">{c.label}</div>
            </div>
          </Col>
        ))}
      </Row>

      <Card
        size="small"
        title="按工具聚合视图"
        style={{ marginBottom: 16 }}
        extra={
          <Space size={8}>
            <Segmented
              size="small"
              value={view}
              onChange={setView}
              options={[
                { label: '条形', value: 'bar' },
                { label: '饼图', value: 'pie' },
              ]}
            />
            <Button size="small" onClick={load}>刷新</Button>
          </Space>
        }
      >
        {items.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有工具调用记录 —— 去用户端聊一轮就有了" />
        ) : view === 'bar' ? barView() : pieView()}
      </Card>

      <Card size="small" title="最近调用明细">
        <Table
          size="small"
          rowKey={(r) => `${r.trace_id}-${r.tool_name}-${r.created_at}`}
          loading={loading}
          pagination={{ pageSize: 12, size: 'small' }}
          dataSource={rows}
          scroll={{ x: 1120 }}
          columns={[
            {
              title: '时间',
              dataIndex: 'created_at',
              width: 160,
              render: (v) => <span className="mono">{String(v).replace('T', ' ').slice(0, 19)}</span>,
            },
            {
              title: '工具标识',
              dataIndex: 'tool_name',
              width: 190,
              render: (v) => <span className="mono">{v}</span>,
            },
            {
              title: '工具名称',
              dataIndex: 'tool_name',
              width: 260,
              render: (v) => {
                const info = catalog[v] || {};
                const label = info.displayName || (info.desc ? clip(info.desc, 24) : '');
                return label ? (
                  <Tooltip title={info.displayName || info.desc}>
                    <span style={{ color: '#595959' }}>{label}</span>
                  </Tooltip>
                ) : (
                  <span style={{ color: '#bfbfbf' }}>—</span>
                );
              },
            },
            {
              title: '入参',
              dataIndex: 'arguments_json',
              ellipsis: true,
              render: (v) => (
                <Tooltip title={v}>
                  <span className="mono" style={{ color: '#595959' }}>
                    {v || '—'}
                  </span>
                </Tooltip>
              ),
            },
            { title: '结果数', dataIndex: 'result_count', width: 80 },
            { title: '耗时', dataIndex: 'duration_ms', width: 90, render: (v) => `${v ?? '-'} ms` },
            {
              title: '状态',
              dataIndex: 'status',
              width: 90,
              render: (v, r) =>
                v === 'success' ? (
                  <Tag color="green">成功</Tag>
                ) : (
                  <Tooltip title={r.error_message}>
                    <Tag color="red">失败</Tag>
                  </Tooltip>
                ),
            },
            {
              title: 'traceId',
              dataIndex: 'trace_id',
              width: 130,
              render: (v) => (
                <Tooltip title={v}>
                  <span className="mono" style={{ color: '#999' }}>
                    {String(v).slice(0, 8)}…
                  </span>
                </Tooltip>
              ),
            },
          ]}
        />
      </Card>
    </div>
  );
}