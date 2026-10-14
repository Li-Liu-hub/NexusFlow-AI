import { useState } from 'react';
import { Button, Form, Input, message } from 'antd';
import { login, isDemoAccount } from './auth.js';

/** 演示账号（口令均为 123456，点击可填充）—— 与用户平台 portal 保持一致 */
const DEMO_ACCOUNTS = [['admin', '管理员']];

/**
 * 管理台登录门：未登录时整页只渲染这一张卡。
 * 后端校验角色 —— 非 ADMIN 账号即使密码正确也进不来。
 * 2026-10-11：视觉对齐用户端 portal 登录页（紫渐变底 + 圆 Logo + 白卡）。
 */
export default function LoginGate({ onLogin }) {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);

  /** 点击演示账号胶囊 → 回填账号密码 */
  const fillDemo = (userCode) => {
    form.setFieldsValue({ userCode, password: '123456' });
  };

  const onFinish = async (values) => {
    setLoading(true);
    try {
      const auth = await login(values.userCode, values.password);
      message.success(`欢迎，${auth.userName}`);
      if (isDemoAccount()) message.info('当前为演示账号，仅支持查看，增删改操作会被拦截', 5);
      onLogin(auth);
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="lg-wrap">
      <div className="lg-card">
        <div className="lg-logo">终</div>
        <h1 className="lg-title">数字员工运维台</h1>
        <div className="lg-sub">终端 AI 智慧管理平台 · 管理员登录</div>
        <Form form={form} layout="vertical" onFinish={onFinish} className="lg-form">
          <Form.Item name="userCode" rules={[{ required: true, message: '请输入账号' }]}>
            <Input placeholder="账号（如 admin）" size="large" autoComplete="username" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password placeholder="密码" size="large" autoComplete="current-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block size="large" loading={loading}>
            登 录
          </Button>
        </Form>
        <div className="login-demo">
          <div className="login-demo-tip">演示账号（口令均为 123456，点击可填充）：</div>
          <div className="login-demo-list">
            {DEMO_ACCOUNTS.map(([u, n]) => (
              <span key={u} className="login-demo-acc" onClick={() => fillDemo(u)}>
                {u}（{n}）
              </span>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
