import { useEffect, useState } from 'react';
import { Button, Layout, Menu, Tag } from 'antd';
import AgentPage from './pages/AgentPage.jsx';
import SkillPage from './pages/SkillPage.jsx';
import KbPage from './pages/KbPage.jsx';
import McpPage from './pages/McpPage.jsx';
import ToolPage from './pages/ToolPage.jsx';
import RuntimePage from './pages/RuntimePage.jsx';
import AuditPage from './pages/AuditPage.jsx';
import TokenPage from './pages/TokenPage.jsx';
import CanvasPage from './pages/CanvasPage.jsx';
import LoginGate from './LoginGate.jsx';
import { getAuth, logout } from './auth.js';

const { Sider, Content, Header } = Layout;

const MENU = [
  { key: 'agent', label: '数字员工配置' },
  { key: 'skill', label: '技能管理' },
  { key: 'kb', label: '知识库管理' },
  { key: 'tool', label: '工具管理' },
  { key: 'mcp', label: 'MCP 管理' },
  { key: 'token', label: 'Token 用量分配' },
  { key: 'canvas', label: '画布编排' },
  { key: 'runtime', label: '运行时观察' },
  { key: 'audit', label: '调用审计' },
];

export default function App() {
  const [page, setPage] = useState('agent');
  const [auth, setAuth] = useState(getAuth());

  // token 失效（401）时统一跳回登录门
  useEffect(() => {
    const onExpired = () => setAuth(null);
    window.addEventListener('auth:expired', onExpired);
    return () => window.removeEventListener('auth:expired', onExpired);
  }, []);

  if (!auth) {
    return <LoginGate onLogin={setAuth} />;
  }

  return (
    <Layout style={{ height: '100vh' }}>
      <Sider width={200} theme="light" style={{ borderRight: '1px solid #f0f0f0' }}>
        <div style={{ padding: '18px 20px 14px' }}>
          <div style={{ fontSize: 14, fontWeight: 600, color: '#534ab7' }}>
            数字员工运维台
          </div>
          <div style={{ fontSize: 11, color: '#999', marginTop: 4 }}>
            终端 AI 智慧管理平台
          </div>
        </div>
        <Menu
          mode="inline"
          selectedKeys={[page]}
          items={MENU}
          onClick={(e) => setPage(e.key)}
          style={{ borderInlineEnd: 'none' }}
        />
      </Sider>

      <Layout>
        <Header
          style={{
            background: '#fff',
            borderBottom: '1px solid #f0f0f0',
            height: 52,
            lineHeight: '52px',
            padding: '0 24px',
            display: 'flex',
            alignItems: 'center',
            gap: 10,
          }}
        >
          <span style={{ fontSize: 13, color: '#595959' }}>
            配置面 · 只管生产配置，不参与对话链路
          </span>
          <span style={{ marginLeft: 'auto', fontSize: 12, color: '#666' }}>
            {auth.userName}（{auth.userCode}）
          </span>
          <Button
            size="small"
            onClick={async () => {
              await logout();
              setAuth(null);
            }}
          >
            退出
          </Button>
          <Tag color="purple">管理端</Tag>
        </Header>

        <Content style={{ overflowY: 'auto', background: '#f5f6f8' }}>
          {page === 'agent' && <AgentPage />}
          {page === 'skill' && <SkillPage />}
          {page === 'kb' && <KbPage />}
          {page === 'tool' && <ToolPage />}
          {page === 'mcp' && <McpPage />}
          {page === 'token' && <TokenPage />}
          {page === 'canvas' && <CanvasPage />}
          {page === 'runtime' && <RuntimePage />}
          {page === 'audit' && <AuditPage />}
        </Content>
      </Layout>
    </Layout>
  );
}
