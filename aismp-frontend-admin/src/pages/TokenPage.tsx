import { useCallback, useEffect, useState } from 'react';
import {
  Button, Card, Col, DatePicker, Empty, Form, Input, InputNumber, Modal, Row, Select,
  Space, Table, Tag, Tooltip, message as antMessage,
} from 'antd';
import dayjs from 'dayjs';
import * as api from '../api';

/**
 * Token 用量分配页。
 *
 * 布局对齐运维总览风格（2026-10-08 三稿）：第一行两块排行 ——「用量 Top 5」（带刻度轴
 * 与配额刻度虚线）｜「调用次数 Top 5」；第二行两块 ——「功能调用次数」（2026-10-09 由
 * 「全部用户」改来，用户端·终端管理功能入口）｜「智能体 token 用量」（按 agent_code 聚合），
 * 均固定高度内部滚动；再下是配额规则 CRUD（带「本月已使用」列，
 * 「调整」弹窗为 2 合 1 布局：左用量视图（日点线图 + 智能体分布饼图）/ 右编辑配额）。
 * 计量数据不落报表式表格。
 *
 * 月份筛选（2026-10-10）：页面顶部一个页面级「统计月份」选择器（显示如「2026年10月」），
 * 联动上方四张卡（用量/调用次数/智能体/功能调用）与配额表「已使用」列、调整弹窗图表。
 *
 * 口径：
 *   - 条形按「全体用户本指标最大值」向上取整到好刻度（1/2/2.5/5×10^n）后归一，
 *     Top 5 与全部用户同尺，可直接比长短；
 *   - 用量条颜色 = 配额状态（正常/接近上限/已超限/不限），虚线 = 该用户月配额刻度；
 *   - 调用次数与 token 量纲不同，两条形之间不互相比较；
 *   - 智能体卡以内核目录为基准全量列出（0 用量也占位），条形按智能体间最大值单独归一，
 *     与左侧用户卡不同尺；编排层 LLM 节点不经内核计量，恒为 0。
 * 用量由内核的 ChatModel 装饰器逐跳累计落账 —— 这里展示的是真实计量结果。
 */

/** 状态 → 条形颜色（与 Tag 语义一致） */
const BAR_COLOR = {
  已超限: '#ff4d4f',
  接近上限: '#faad14',
  正常: '#534ab7',
  不限: '#5b8ff9',
};

/** 状态 → Tag 颜色 */
const TAG_COLOR = { 已超限: 'red', 接近上限: 'orange', 正常: 'blue', 不限: 'default' };

const STATUS_LEGEND = ['正常', '接近上限', '已超限', '不限'];

/** 月配额达到该值时按「不限量」展示（与配额规则表的判定一致） */
const QUOTA_UNLIMITED = 900000000;

/** 比例尺上界取好刻度（1/2/2.5/5 × 10^n），让轴刻度落在整数上 */
function niceMax(v) {
  const n = Number(v || 0);
  if (n <= 0) return 1;
  const pow = 10 ** Math.floor(Math.log10(n));
  const step = [1, 2, 2.5, 5, 10].find((s) => s * pow >= n);
  return step * pow;
}

/** 轴向紧凑数字：1.2万 / 200万 / 1.9亿（刻度放不下全量位数字） */
function fmtShort(v) {
  const n = Number(v || 0);
  if (n >= 1e8) return `${Number((n / 1e8).toFixed(1))}亿`;
  if (n >= 1e4) return `${Number((n / 1e4).toFixed(1))}万`;
  return String(n);
}

/** ISO 日期/时间串 → 「10月9日」（空值返回 fallback） */
function fmtMD(v, fallback = '—') {
  if (!v) return fallback;
  const s = String(v);
  const m = Number(s.slice(5, 7));
  const d = Number(s.slice(8, 10));
  if (!m || !d) return fallback;
  return `${m}月${d}日`;
}

/** ISO 时间串 → 「10月9日 11:41」（空值返回 fallback） */
function fmtMDHM(v, fallback = '—') {
  if (!v) return fallback;
  const s = String(v).replace('T', ' ');
  const hm = s.slice(11, 16);
  return fmtMD(s, fallback) + (hm ? ` ${hm}` : '');
}

/** 状态图例（四个状态固定展示，兼作颜色口径说明） */
function Legend() {
  return (
    <div className="rk-legend">
      {STATUS_LEGEND.map((s) => (
        <span key={s}>
          <i style={{ background: BAR_COLOR[s] }} />
          {s}
        </span>
      ))}
    </div>
  );
}

/**
 * 排行图：左侧名称轴 +（可选）刻度网格 + 横向条形 + 右侧数值。
 * items: [{ key, name, code, value, color, valueText, subText, markPct, markTitle }]
 */
function RankChart({ items, scaleMax, axis = true, markTicks = true }) {
  const ticks = [0, 0.25, 0.5, 0.75, 1];
  // 首尾刻度贴边，避免文字溢出轨道
  const tickStyle = (t) => (t === 0
    ? { left: 0 }
    : t === 1
      ? { left: '100%', transform: 'translateX(-100%)' }
      : { left: `${t * 100}%`, transform: 'translateX(-50%)' });

  return (
    <div className="rk">
      {items.map((it) => {
        const pct = it.value <= 0 ? 0 : Math.max((it.value / scaleMax) * 100, 1.2);
        return (
          <div className="rk-row" key={it.key}>
            <div className="rk-name">
              <span className="nm" title={`${it.name}（${it.code}）`}>{it.name}</span>
              {it.name !== it.code && <span className="cd mono">{it.code}</span>}
            </div>
            <div className="rk-area">
              {markTicks && (
                <div className="rk-grid">
                  {ticks.map((t) => <span key={t} className="rk-gl" style={{ left: `${t * 100}%` }} />)}
                </div>
              )}
              <div className="rk-bar" style={{ width: `${pct}%`, background: it.color }} />
              {it.markPct != null && (
                <Tooltip title={it.markTitle}>
                  <span className="rk-mark" style={{ left: `${it.markPct}%` }} />
                </Tooltip>
              )}
            </div>
            <div className="rk-val">
              <span className="v">{it.valueText}</span>
              {it.subText && <span className="s">{it.subText}</span>}
            </div>
          </div>
        );
      })}
      {axis && (
        <div className="rk-axis">
          <div className="rk-axis-pad" />
          <div className="rk-axis-track">
            {ticks.map((t, i) => (
              <span className={`rk-tick t${i}`} style={tickStyle(t)} key={t}>{fmtShort(scaleMax * t)}</span>
            ))}
          </div>
          <div className="rk-axis-val" />
        </div>
      )}
    </div>
  );
}

/** 饼图配色（与调用审计页同风格） */
const PIE_COLORS = ['#534ab7', '#5b8ff9', '#faad14', '#52c41a', '#eb2f96', '#13c2c2', '#fa541c', '#9254de', '#a0d911', '#2f54eb'];

/**
 * 日用量点线图（调整弹窗·用量视图，2026-10-09 v2 改为线性图）：
 * x 轴 = 当月每一天，y 轴 = token 用量；点数据点选中该天（右侧饼图切到当天分布），
 * 再点一次取消。带面积填充与悬浮提示，选中日画竖向虚线。
 */
function DailyLineChart({ values, selected, onSelect }) {
  const W = 680;
  const H = 240;
  const PADL = 56;
  const PADR = 14;
  const PADT = 14;
  const PADB = 30;
  const max = niceMax(Math.max(0, ...values));
  const n = values.length || 1;
  const iw = W - PADL - PADR;
  const ih = H - PADT - PADB;
  const x = (i) => PADL + (n <= 1 ? iw / 2 : (i / (n - 1)) * iw);
  const y = (v) => PADT + ih - (Math.max(0, Number(v || 0)) / max) * ih;
  const line = values.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)} ${y(v).toFixed(1)}`).join(' ');
  const area = `${line} L${x(n - 1).toFixed(1)} ${PADT + ih} L${x(0).toFixed(1)} ${PADT + ih} Z`;
  const yTicks = [0, 0.25, 0.5, 0.75, 1];
  const labelEvery = n > 20 ? 5 : 2;
  return (
    <svg width="100%" viewBox={`0 0 ${W} ${H}`} style={{ display: 'block' }}>
      {/* y 网格与刻度 */}
      {yTicks.map((t) => (
        <g key={t}>
          <line
            x1={PADL} x2={W - PADR}
            y1={PADT + ih * (1 - t)} y2={PADT + ih * (1 - t)}
            stroke="#eceaf7" strokeDasharray={t === 0 ? '' : '3 3'}
          />
          <text x={PADL - 8} y={PADT + ih * (1 - t) + 3} textAnchor="end" fontSize="9.5" fill="#b5b5bf">
            {fmtShort(max * t)}
          </text>
        </g>
      ))}
      {/* 面积 + 折线 */}
      <path d={area} fill="#7a71d6" opacity="0.12" />
      <path d={line} fill="none" stroke="#534ab7" strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
      {/* 选中日竖向虚线 */}
      {selected != null && (
        <line
          x1={x(selected - 1)} x2={x(selected - 1)} y1={PADT} y2={PADT + ih}
          stroke="#fa8c16" strokeWidth="1.2" strokeDasharray="4 3" pointerEvents="none"
        />
      )}
      {/* 数据点 + 整列点击热区 */}
      {values.map((v, i) => {
        const day = i + 1;
        const on = selected === day;
        return (
          <g key={day}>
            <rect
              x={x(i) - iw / (n * 2)} y={PADT} width={iw / n} height={ih}
              fill="transparent" style={{ cursor: 'pointer' }}
              onClick={() => onSelect(on ? null : day)}
            >
              <title>{`${day} 日 · ${Number(v || 0).toLocaleString()} tokens`}</title>
            </rect>
            <circle
              cx={x(i)} cy={y(v)} r={on ? 5 : 3}
              fill={on ? '#fa8c16' : '#534ab7'} stroke="#fff" strokeWidth="1.5"
              pointerEvents="none"
            />
            {(day === 1 || day % labelEvery === 0 || day === n) && (
              <text x={x(i)} y={H - 10} textAnchor="middle" fontSize="9.5" fill={on ? '#fa8c16' : '#999'}>
                {day}
              </text>
            )}
          </g>
        );
      })}
    </svg>
  );
}

/** 智能体用量分布饼图（默认月度分布，点点线图某天后为当天分布）。rows: [{ code, value }] */
function AgentPie({ rows, nameOf }) {
  const total = rows.reduce((s, r) => s + r.value, 0);
  if (!total) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该时段暂无带智能体归属的用量" />;
  }
  const R = 72;
  const CX = 84;
  const CY = 84;
  let angle = -Math.PI / 2;
  const shapes = [];
  rows.forEach((r) => {
    if (r.value <= 0) return;
    const frac = r.value / total;
    const a2 = angle + frac * Math.PI * 2;
    const color = PIE_COLORS[rows.indexOf(r) % PIE_COLORS.length];
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
      <path key={r.code} d={d} fill={color} stroke="#fff" strokeWidth={1.5}>
        <title>{`${nameOf(r.code)}：${r.value.toLocaleString()} tokens（${(frac * 100).toFixed(1)}%）`}</title>
      </path>,
    );
    angle = a2;
  });
  return (
    <div className="tk-pie-row">
      <svg width="168" height="168" viewBox="0 0 168 168">{shapes}</svg>
      <div className="tk-pie-leg">
        {rows.map((r, idx) => (
          <div className="tk-pie-leg-row" key={r.code}>
            <span className="dot" style={{ background: PIE_COLORS[idx % PIE_COLORS.length] }} />
            <span className="nm" title={nameOf(r.code)}>{nameOf(r.code)}</span>
            <span className="pv">{r.value.toLocaleString()} · {((r.value / total) * 100).toFixed(1)}%</span>
          </div>
        ))}
      </div>
    </div>
  );
}

export default function TokenPage() {
  const [rows, setRows] = useState([]);
  const [agentRows, setAgentRows] = useState([]); // 智能体维度用量（按 agent_code 聚合）
  const [quotas, setQuotas] = useState([]);
  const [featureRows, setFeatureRows] = useState([]); // 功能点调用次数（用户端·终端管理）
  const [month, setMonth] = useState(dayjs());        // 页面级月份筛选（粒度：月），驱动全部卡片
  const [monthly, setMonthly] = useState(null);       // { month, byUser, byAgent } 配额表「本月已使用」列
  const [usageRows, setUsageRows] = useState([]);     // 调整弹窗·逐日×智能体用量
  const [selDay, setSelDay] = useState(null);         // 选中日（饼图切日维度分布）
  const [loading, setLoading] = useState(false);
  const [editing, setEditing] = useState(null); // null | {} (新建) | quota 对象
  const [scope, setScope] = useState('USER'); // 当前弹窗里选的范围，决定「对象编码」用哪种候选
  const [userOptions, setUserOptions] = useState([]); // USER：平台账号（backend）
  const [userMeta, setUserMeta] = useState({}); // user_code → user_name（排行图上显示中文名）
  const [agentOptions, setAgentOptions] = useState([]); // AGENT：数字员工目录（内核）
  const [agentMeta, setAgentMeta] = useState({}); // agent_code → agent_name（排行图上显示中文名）
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const monthStr = month.format('YYYY-MM');
      const [ov, ag, q, ft, my] = await Promise.all([
        api.usageOverview(monthStr),
        api.agentUsage(monthStr),
        api.listQuotas(),
        api.featureUsage(monthStr).catch(() => []),
        api.usageMonthly(monthStr).catch(() => null),
      ]);
      setRows(Array.isArray(ov) ? ov : []);
      setAgentRows(Array.isArray(ag) ? ag : []);
      setQuotas(Array.isArray(q) ? q : []);
      setFeatureRows(Array.isArray(ft) ? ft : []);
      setMonthly(my || null);
    } catch (e) {
      antMessage.error('加载失败：' + e.message);
    } finally {
      setLoading(false);
    }
  }, [month]);

  useEffect(() => { load(); }, [load]);

  // 对象编码候选：USER 走 backend 账号表，AGENT 走内核数字员工目录（同一 Bearer 令牌即可）
  useEffect(() => {
    api.tokenTargetCodes('USER')
      .then((list) => {
        const opts = [];
        const meta = {};
        (list || []).forEach((u) => {
          opts.push({ value: u.user_code, label: `${u.user_name || ''}（${u.user_code}）` });
          if (u.user_name) meta[u.user_code] = u.user_name;
        });
        setUserOptions(opts);
        setUserMeta(meta);
      })
      .catch(() => {});
    api.listAgents()
      .then((list) => {
        setAgentOptions((list || []).map((a) => ({
          value: a.agentCode,
          label: `${a.agentName || ''}（${a.agentCode}）`,
        })));
        const meta = {};
        (list || []).forEach((a) => {
          if (a.agentCode && a.agentName) meta[a.agentCode] = a.agentName;
        });
        setAgentMeta(meta);
      })
      .catch(() => {});
  }, []);

  function openEdit(quota) {
    setEditing(quota || {});
    setScope(quota?.scope || 'USER');
    setSelDay(null);
    setUsageRows([]);
    form.setFieldsValue(
      quota
        ? { scope: quota.scope, targetCode: quota.targetCode, monthlyTokens: quota.monthlyTokens, remark: quota.remark }
        : { scope: 'USER', targetCode: undefined, monthlyTokens: 1000000, remark: '' },
    );
    // 用量视图数据：按对象取当月逐日 × 智能体（柱状图 + 饼图一次取足）
    if (quota?.id) {
      api.usageDailyAgents(
        month.format('YYYY-MM'),
        quota.scope === 'USER' ? quota.targetCode : undefined,
        quota.scope === 'AGENT' ? quota.targetCode : undefined,
      )
        .then((list) => setUsageRows(Array.isArray(list) ? list : []))
        .catch(() => setUsageRows([]));
    }
  }

  async function submit() {
    const v = await form.validateFields();
    try {
      const payload = editing?.id
        ? { ...v, id: editing.id }
        : v;
      const res = editing?.id ? await api.updateQuota(editing.id, payload) : await api.createQuota(payload);
      if (res && res.error) throw new Error(res.error);
      antMessage.success('配额已保存，下一轮对话即生效');
      setEditing(null);
      load();
    } catch (e) {
      antMessage.error('保存失败：' + e.message);
    }
  }

  async function remove(id) {
    try {
      await api.deleteQuota(id);
      antMessage.success('已删除（该用户回到不限量状态）');
      load();
    } catch (e) {
      antMessage.error('删除失败：' + e.message);
    }
  }

  // ---- 视图取数 ----
  const top5 = rows.slice(0, 5); // 接口已按 token 用量倒序
  const scaleTok = niceMax(Math.max(0, ...rows.map((r) => Number(r.total_tokens || 0))));
  const scaleCall = niceMax(Math.max(0, ...rows.map((r) => Number(r.requests || 0))));

  const nameOf = (code) => userMeta[code] || code;

  /** 用量排行项：条形 = token 用量，虚线 = 月配额刻度 */
  function usageItems(list) {
    return list.map((r) => {
      const code = r.user_id;
      const status = r.quota_status || '不限';
      const q = Number(r.quota_tokens || 0);
      const markPct = r.quota_tokens != null && q > 0 && q < QUOTA_UNLIMITED && q <= scaleTok
        ? (q / scaleTok) * 100
        : null;
      const last = fmtMD(r.last_active); // 中文「10月5日」（2026-10-10 中文化）
      return {
        key: code,
        name: nameOf(code),
        code,
        value: Number(r.total_tokens || 0),
        color: BAR_COLOR[status] || '#534ab7',
        valueText: `${Number(r.total_tokens || 0).toLocaleString()} tokens`,
        subText: `调用 ${Number(r.requests || 0).toLocaleString()} 次 · 最近 ${last}`,
        markPct,
        markTitle: `月配额刻度 ${q.toLocaleString()}`,
      };
    });
  }

  /** 调用次数排行项：条形 = 请求次数 */
  function callItems(list) {
    return list.map((r) => {
      const code = r.user_id;
      const status = r.quota_status || '不限';
      return {
        key: code,
        name: nameOf(code),
        code,
        value: Number(r.requests || 0),
        color: BAR_COLOR[status] || '#534ab7',
        valueText: `${Number(r.requests || 0).toLocaleString()} 次`,
        subText: `${fmtShort(r.total_tokens)} tokens`,
      };
    });
  }

  const agentNameOf = (code) => agentMeta[code] || code;
  const scaleAgent = niceMax(Math.max(0, ...agentRows.map((r) => Number(r.total_tokens || 0))));
  const sumAgentTokens = agentRows.reduce((s, r) => s + Number(r.total_tokens || 0), 0);

  /**
   * 智能体排行项：以内核智能体目录为基准做全量列表（0 用量也列出，占满卡片不留白），
   * 账本里有而目录没有的 code 兜底追加。编排层 5 个 LLM 节点不经内核计量，恒为 0 —— 刻意暴露该盲区。
   */
  function agentItems() {
    const merged = agentRows.map((r) => ({
      code: r.agent_code,
      value: Number(r.total_tokens || 0),
      requests: Number(r.requests || 0),
      users: Number(r.users || 0),
      last: fmtMD(r.last_active, null),
    }));
    agentOptions.forEach((o) => {
      if (!merged.some((r) => r.code === o.value)) {
        merged.push({ code: o.value, value: 0, requests: 0, users: 0, last: null });
      }
    });
    merged.sort((a, b) => b.value - a.value);
    return merged.map((r) => ({
      key: r.code,
      name: agentNameOf(r.code),
      code: r.code,
      value: r.value,
      color: '#5b8ff9',
      valueText: `${r.value.toLocaleString()} tokens`,
      subText: r.requests > 0
        ? `调用 ${r.requests.toLocaleString()} 次 · ${r.users} 人使用 · 最近 ${r.last || '—'}`
        : '本月暂无对话计量',
    }));
  }

  // ---- 功能调用次数（用户端·终端管理功能入口，2026-10-09） ----
  const featureItems = featureRows.map((r) => ({
    key: r.feature_code,
    name: r.feature_name,
    code: r.feature_code,
    value: Number(r.call_count || 0),
    color: '#5b8ff9',
    valueText: `${Number(r.call_count || 0).toLocaleString()} 次`,
    subText: `最近 ${fmtMDHM(r.last_called_at)}`,
  }));
  const scaleFeature = niceMax(Math.max(0, ...featureRows.map((r) => Number(r.call_count || 0))));
  const sumFeatureCalls = featureRows.reduce((s, r) => s + Number(r.call_count || 0), 0);

  // ---- 配额表「本月已使用」列（随月份筛选联动） ----
  const monthStr = month.format('YYYY-MM');       // 接口口径（YYYY-MM）
  const monthCN = month.format('YYYY年MM月');     // 展示口径（中文，2026-10-10）
  const isCurrentMonth = monthStr === dayjs().format('YYYY-MM');
  const usedOf = (r) => {
    if (!monthly) return null;
    if (r.scope === 'USER') {
      const row = (monthly.byUser || []).find((x) => x.user_id === r.targetCode);
      return row ? Number(row.total_tokens || 0) : 0;
    }
    if (r.scope === 'AGENT') {
      const row = (monthly.byAgent || []).find((x) => x.agent_code === r.targetCode);
      return row ? Number(row.total_tokens || 0) : 0;
    }
    return null;   // TENANT 无个人账本
  };

  // ---- 调整弹窗·用量视图：日柱状图 + 智能体分布饼图 ----
  const daysInMonth = month.daysInMonth();
  const dayTotals = (() => {
    const arr = new Array(daysInMonth).fill(0);
    usageRows.forEach((r) => {
      const d = Number(String(r.usage_date).slice(8, 10));
      if (d >= 1 && d <= daysInMonth) arr[d - 1] += Number(r.total_tokens || 0);
    });
    return arr;
  })();
  const pieSource = selDay == null
    ? usageRows
    : usageRows.filter((r) => Number(String(r.usage_date).slice(8, 10)) === selDay);
  const agentAgg = {};
  pieSource.forEach((r) => {
    const c = r.agent_code || 'unassigned';
    agentAgg[c] = (agentAgg[c] || 0) + Number(r.total_tokens || 0);
  });
  const pieRows = Object.entries(agentAgg)
    .map(([code, value]) => ({ code, value }))
    .sort((a, b) => b.value - a.value);
  const pieNameOf = (c) => (c === 'unassigned' ? '未归属智能体' : agentNameOf(c));

  const usageView = (
    <div className="tk-usage">
      <div className="tk-chart-head">
        <span className="tk-pie-title">{monthCN} 每日 token 用量</span>
        <span className="hint">点线图上任一天的数据点，下方饼图即切到当天分布；再点一次回到月度</span>
        {selDay != null && (
          <Button size="small" onClick={() => setSelDay(null)}>回到月度分布</Button>
        )}
      </div>
      {/* 用量视图 = 点线图（日粒度趋势）+ 饼图（点线图的明细分布）两个独立图，上下排布 */}
      <div className="tk-line-box">
        <DailyLineChart values={dayTotals} selected={selDay} onSelect={setSelDay} />
      </div>
      <div className="tk-pie-box">
        <div className="tk-pie-title" style={{ marginBottom: 10 }}>
          智能体用量分布 · {selDay == null
            ? `${monthCN}（月度）`
            : `${monthCN}${selDay}日（当日）`}
        </div>
        <AgentPie rows={pieRows} nameOf={pieNameOf} />
      </div>
    </div>
  );

  const quotaColumns = [
    {
      title: '范围', dataIndex: 'scope', width: 80,
      render: (v) => <Tag color={v === 'USER' ? 'blue' : v === 'AGENT' ? 'purple' : 'orange'}>{v}</Tag>,
    },
    { title: '对象', dataIndex: 'targetCode', width: 150, render: (v) => <span className="mono">{v}</span> },
    {
      title: '月配额', dataIndex: 'monthlyTokens', width: 130,
      render: (v) => (v >= QUOTA_UNLIMITED ? '不限' : Number(v).toLocaleString()),
    },
    {
      // 本月已使用 token 量（2026-10-09）：随页面级月份筛选联动；非本月时标题带上所选月份（中文）
      title: isCurrentMonth ? '本月已使用 token 量' : `${monthCN} 已使用`,
      width: 150,
      render: (_, r) => {
        const used = usedOf(r);
        if (used == null) return <span style={{ color: '#bfbfbf' }}>—</span>;
        const q = Number(r.monthlyTokens || 0);
        const pct = q > 0 && q < QUOTA_UNLIMITED ? Math.round((used / q) * 100) : null;
        return (
          <span>
            {used.toLocaleString()}
            {pct != null && (
              <span
                style={{
                  marginLeft: 6,
                  fontSize: 11,
                  color: pct >= 100 ? '#ff4d4f' : pct >= 80 ? '#faad14' : '#999',
                }}
              >
                {pct}%
              </span>
            )}
          </span>
        );
      },
    },
    { title: '备注', dataIndex: 'remark', ellipsis: true },
    {
      title: '操作', width: 120,
      render: (_, r) => (
        <Space>
          <Button size="small" type="link" onClick={() => openEdit(r)}>调整</Button>
          <Button size="small" type="link" danger onClick={() => remove(r.id)}>删除</Button>
        </Space>
      ),
    },
  ];

  /** 编辑配额表单（新增/调整共用；调整弹窗里是「编辑配额」页签的内容） */
  const quotaForm = (
    <Form form={form} layout="vertical" size="small">
      <Space size={12} style={{ display: 'flex' }}>
        <Form.Item name="scope" label="范围" style={{ width: 120 }}>
          <Select
            onChange={(v) => {
              // 切换范围时清空对象编码：候选集变了，旧值多半不再适用
              setScope(v);
              form.setFieldsValue({ targetCode: undefined });
            }}
            options={[
              { value: 'USER', label: '按用户' },
              { value: 'AGENT', label: '按数字员工' },
              { value: 'TENANT', label: '按租户' },
            ]}
          />
        </Form.Item>
        <Form.Item
          name="targetCode" label="对象编码" style={{ flex: 1 }}
          rules={[{ required: true, message: '请选择/填写对象编码' }]}
        >
          {scope === 'TENANT' ? (
            <Input placeholder="如 ENT001（租户编码，需手工填写）" />
          ) : (
            <Select
              showSearch
              allowClear
              optionFilterProp="label"
              placeholder={scope === 'USER' ? '选择平台账号' : '选择数字员工'}
              options={scope === 'USER' ? userOptions : agentOptions}
            />
          )}
        </Form.Item>
      </Space>
      <Form.Item
        name="monthlyTokens" label="月度 token 上限"
        rules={[{ required: true, message: '填月度上限' }]}
        extra="填 0 表示封禁该对象的对话；数字员工不设上限可填 999000000"
      >
        <InputNumber min={0} step={100000} style={{ width: '100%' }} />
      </Form.Item>
      <Form.Item name="remark" label="备注">
        <Input placeholder="如：门户演示账号月配额 200 万" />
      </Form.Item>
    </Form>
  );

  return (
    <div className="page-wrap">
      {/* 页面级月份筛选（2026-10-10）：上移到页头，联动四张卡 + 配额表「已使用」列 + 调整弹窗，
          全页只有一个月份口径（此前只在配额卡上，上方卡片恒为本月） */}
      <div className="tk-head">
        <h2 className="page-title" style={{ margin: 0 }}>Token 用量分配</h2>
        <Space size={8}>
          <span className="tk-head-label">统计月份</span>
          <DatePicker
            picker="month"
            size="small"
            value={month}
            allowClear={false}
            format="YYYY年MM月"
            onChange={(v) => { if (v) { setMonth(v); setSelDay(null); } }}
          />
        </Space>
      </div>
      <p className="page-desc">
        内核在每一跳模型调用上计量真实用量（prompt + completion）并按日落账；配额按用户月度控制，
        超限的下一轮对话直接拒绝。没有配额规则的用户不限量。
      </p>

      {/* 第一行：用量 | 调用次数 两块排行 */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col xs={24} lg={12}>
          <Card
            size="small"
            title="用量 Top 5"
            loading={loading}
            extra={<Button size="small" onClick={load}>刷新</Button>}
          >
            <Legend />
            {rows.length === 0 ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="所选月份还没有对话用量 —— 去用户端聊一轮就有了"
              />
            ) : (
              <RankChart items={usageItems(top5)} scaleMax={scaleTok} />
            )}
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card size="small" title="调用次数 Top 5" loading={loading}>
            <Legend />
            {rows.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="所选月份暂无用量的调用记录" />
            ) : (
              <RankChart items={callItems(top5)} scaleMax={scaleCall} axis={false} markTicks={false} />
            )}
          </Card>
        </Col>
      </Row>

      {/* 第二行：功能调用次数 | 智能体 token 用量 两块排行（均固定高度内部滚动） */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col xs={24} lg={12}>
          {/* 功能调用次数（2026-10-09）：原「全部用户」卡改为用户端·终端管理功能入口的调用次数排行 */}
          <Card
            size="small"
            title={`${monthCN} 功能调用次数（${featureRows.length}）`}
            loading={loading}
            extra={(
              <span style={{ fontSize: 11, color: '#999' }}>
                合计 {sumFeatureCalls.toLocaleString()} 次 · 用户端·终端管理
              </span>
            )}
          >
            {featureRows.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="所选月份暂无功能调用数据" />
            ) : (
              <div className="rk-scroll">
                <RankChart items={featureItems} scaleMax={scaleFeature} axis={false} markTicks={false} />
              </div>
            )}
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card
            size="small"
            title={`${monthCN} 智能体 token 用量（${agentItems().length}）`}
            loading={loading}
            extra={(
              <span style={{ fontSize: 11, color: '#999' }}>
                合计 {sumAgentTokens.toLocaleString()} tokens
              </span>
            )}
          >
            {agentItems().length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="所选月份还没有带智能体归属的调用记录" />
            ) : (
              <div className="rk-scroll">
                <RankChart items={agentItems()} scaleMax={scaleAgent} axis={false} markTicks={false} />
              </div>
            )}
          </Card>
        </Col>
      </Row>

      <Card
        size="small"
        title="配额规则"
        extra={<Button size="small" type="primary" onClick={() => openEdit(null)}>新增配额</Button>}
      >
        <Table
          size="small"
          rowKey="id"
          pagination={false}
          dataSource={quotas}
          scroll={{ x: 720 }}
          columns={quotaColumns}
        />
      </Card>

      <Modal
        open={!!editing}
        title={editing?.id ? '调整配额' : '新增配额'}
        onCancel={() => setEditing(null)}
        onOk={submit}
        okText="保存（即时生效）"
        width={editing?.id ? 1120 : 560}
      >
        {editing?.id ? (
          // 2 合 1 布局（2026-10-09 v3）：左 = 用量视图（点线图 + 饼图），右 = 编辑配额表单
          <div className="tk-split">
            <div className="tk-split-left">{usageView}</div>
            <div className="tk-split-right">{quotaForm}</div>
          </div>
        ) : (
          quotaForm
        )}
      </Modal>
    </div>
  );
}