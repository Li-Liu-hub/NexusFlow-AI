import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import App from './App.jsx';
import { isDemoAccount, DEMO_HINT } from './auth.js';
import './styles.css';

// DatePicker 面板（月份/星期）文案走 dayjs locale：只配 antd 的 zhCN 时
// 面板仍是 Jan…Dec 英文缩写，必须显式设置 dayjs 全局 locale（2026-10-10）。
dayjs.locale('zh-cn');

// ---------------------------------------------------------------------------
// 演示账号只读拦截（2026-10-11）：在 fetch 出口统一拦截非 GET 请求，
// 返回 403 + DEMO_HINT —— 各页面现有的错误处理会把这句话 toast 出来。
// 放在 fetch 层而不是逐个接口包一层，是为了覆盖 api.js / CanvasPage /
// 上传 multipart 等所有调用通道，以后新增接口也不会漏。
// ---------------------------------------------------------------------------
const _origFetch = window.fetch.bind(window);
window.fetch = (input, init = {}) => {
  const method = String(init.method || 'GET').toUpperCase();
  const url = typeof input === 'string' ? input : input?.url || '';
  const isApiCall = url.startsWith('/api/') || url.includes('/api/');
  const isAuthFlow = url.includes('/auth/login') || url.includes('/auth/logout');
  if (isDemoAccount() && isApiCall && !isAuthFlow && method !== 'GET' && method !== 'HEAD') {
    return Promise.resolve(new Response(JSON.stringify({ error: DEMO_HINT }), {
      status: 403,
      headers: { 'Content-Type': 'application/json' },
    }));
  }
  return _origFetch(input, init);
};

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{ token: { colorPrimary: '#534AB7', borderRadius: 8 } }}
    >
      <App />
    </ConfigProvider>
  </React.StrictMode>
);
