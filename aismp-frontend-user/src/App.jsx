import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Input, Spin, Tag, message as antMessage } from 'antd';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import * as api from './api';

/** 空状态下的引导问题 —— 覆盖三条能力（查库 / 统计 / 流程） */
const SUGGESTIONS = [
  '西湖营业厅有多少台终端？',
  '现在各状态的终端分别有多少台？',
  '终端领用要走什么流程？',
  'CPE202600001 这台设备现在在哪？',
];

export default function App() {
  const [conversations, setConversations] = useState([]);
  const [currentConvId, setCurrentConvId] = useState(null);
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [agentInfo, setAgentInfo] = useState(null);
  const bodyRef = useRef(null);

  // 消息追加后自动滚到底部
  useEffect(() => {
    if (bodyRef.current) {
      bodyRef.current.scrollTop = bodyRef.current.scrollHeight;
    }
  }, [messages, loading]);

  const refreshConversations = useCallback(async () => {
    try {
      const list = await api.listConversations();
      setConversations(Array.isArray(list) ? list : []);
    } catch (e) {
      // 会话列表加载失败不该打断对话，静默即可
      console.warn('会话列表加载失败:', e.message);
    }
  }, []);

  useEffect(() => {
    refreshConversations();
    api.introspect().then(setAgentInfo).catch(() => {});
  }, [refreshConversations]);

  /** 新建会话：只清空本地状态，会话本身在首次提问时由后端创建 */
  function newConversation() {
    setCurrentConvId(null);
    setMessages([]);
    setInput('');
  }

  /** 打开历史会话 */
  async function openConversation(convId) {
    if (convId === currentConvId) return;
    try {
      const detail = await api.getConversation(convId);
      setCurrentConvId(convId);
      setMessages(
        (detail.messages || []).map((m) => ({
          role: m.role,
          content: m.content,
          time: m.created_at,
        }))
      );
    } catch (e) {
      antMessage.error('打开会话失败：' + e.message);
    }
  }

  /** 发送并接收回答 */
  async function send(text) {
    const question = (text ?? input).trim();
    if (!question || loading) return;

    setInput('');
    setMessages((prev) => [...prev, { role: 'user', content: question }]);
    setLoading(true);

    try {
      const res = await api.chat(question, currentConvId);
      setMessages((prev) => [
        ...prev,
        {
          role: 'assistant',
          content: res.answer || res.error || '(空回答)',
          meta: {
            version: res.version,
            elapsedMs: res.elapsedMs,
            historyTurns: res.historyTurns,
          },
        },
      ]);
      // 首次提问后后端才创建会话，这里把 conversationId 接住
      if (res.conversationId && res.conversationId !== currentConvId) {
        setCurrentConvId(res.conversationId);
      }
      refreshConversations();
    } catch (e) {
      setMessages((prev) => [
        ...prev,
        { role: 'assistant', content: `请求失败：${e.message}` },
      ]);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="app-shell">
      {/* ---------- 会话列表 ---------- */}
      <aside className="sidebar">
        <div className="sidebar-head">
          <div className="sidebar-brand">终端 AI 智慧管理平台</div>
          <Button type="primary" block onClick={newConversation}>
            新建会话
          </Button>
        </div>
        <div className="conv-list">
          {conversations.length === 0 && (
            <div style={{ color: '#bbb', fontSize: 12, padding: 12, textAlign: 'center' }}>
              暂无历史会话
            </div>
          )}
          {conversations.map((c) => (
            <div
              key={c.conversation_id}
              className={`conv-item ${c.conversation_id === currentConvId ? 'active' : ''}`}
              onClick={() => openConversation(c.conversation_id)}
            >
              <div className="conv-item-title">{c.title || '(无标题)'}</div>
              <div className="conv-item-meta">
                {c.message_count} 条消息
                {c.updated_at ? ` · ${String(c.updated_at).slice(5, 16).replace('T', ' ')}` : ''}
              </div>
            </div>
          ))}
        </div>
      </aside>

      {/* ---------- 对话区 ---------- */}
      <main className="chat-main">
        <header className="chat-head">
          <div className="chat-head-title">终端管理助手</div>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            {agentInfo && (
              <>
                <Tag color="blue">v{agentInfo.version}</Tag>
                <Tag>{agentInfo.tools?.length ?? 0} 个工具</Tag>
                {agentInfo.skills?.map((s) => (
                  <Tag key={s} color="purple">
                    {s}
                  </Tag>
                ))}
              </>
            )}
          </div>
        </header>

        <div className="chat-body" ref={bodyRef}>
          {messages.length === 0 && !loading && (
            <div className="empty-tip">
              <h2>你好，我是终端管理助手</h2>
              <div>可以帮你查库存、看统计、查流程、溯源设备</div>
              <div className="suggest">
                {SUGGESTIONS.map((s) => (
                  <div key={s} className="suggest-item" onClick={() => send(s)}>
                    {s}
                  </div>
                ))}
              </div>
            </div>
          )}

          {messages.map((m, i) => (
            <div key={i} className={`msg-row ${m.role}`}>
              <div className="msg-bubble">
                {m.role === 'assistant' ? (
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>{m.content}</ReactMarkdown>
                ) : (
                  m.content
                )}
                {m.meta && (
                  <div className="msg-meta">
                    配置版本 v{m.meta.version} · 历史 {m.meta.historyTurns} 条 ·{' '}
                    {(m.meta.elapsedMs / 1000).toFixed(1)}s
                  </div>
                )}
              </div>
            </div>
          ))}

          {loading && (
            <div className="msg-row assistant">
              <div className="msg-bubble">
                <Spin size="small" /> <span style={{ marginLeft: 8 }}>正在思考…</span>
              </div>
            </div>
          )}
        </div>

        <footer className="chat-input">
          <Input.TextArea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="问点什么…（Enter 发送，Shift+Enter 换行）"
            autoSize={{ minRows: 1, maxRows: 4 }}
            disabled={loading}
            onPressEnter={(e) => {
              if (!e.shiftKey) {
                e.preventDefault();
                send();
              }
            }}
          />
          <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 10 }}>
            <span style={{ fontSize: 12, color: '#bbb' }}>
              回答中的数字均来自数据库与算法，模型不生成数字
            </span>
            <Button type="primary" onClick={() => send()} loading={loading}>
              发送
            </Button>
          </div>
        </footer>
      </main>
    </div>
  );
}
