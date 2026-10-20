import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 用户端开发服务器。
//
// 【2026-10-06 收敛到网关】此前 /api 直连数字员工内核 8126，绕开了平台的鉴权/限流/计量。
// 现在统一经网关（8090）：
//   /api/auth/**   → 网关匿名放行 → 内核 /auth/**（登录成功后网关登记令牌映射）
//   /api/agent/**  → 网关按 Bearer 校验（portal:token:）→ lb://aismp-digital-human → /agent/**
// 注意：**不要 rewrite 掉 /api** —— 前缀是网关的路由与鉴权依据（StripPrefix 由网关侧完成）。
// 生产部署时由 nginx 承担同样的转发（见 docs 部署说明）。
export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8090',
        changeOrigin: true,
      },
    },
  },
});
