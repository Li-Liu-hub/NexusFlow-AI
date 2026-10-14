import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 管理端开发服务器（与用户端分离，端口也不同）。
//
// 【2026-10-06 收敛到网关】原先 /api 直连内核 8126（本机调试方便，但绕开了平台鉴权链）。
// 现在统一经网关（8090）：
//   /api/auth/**   → 匿名放行（登录握手）
//   /api/admin/**、/api/agent/*/introspect → Bearer → 网关（portal:token:）→ 内核
// 注意：**不要 rewrite 掉 /api**（网关路由与鉴权依据，StripPrefix 由网关侧完成）。
//
// 另一条可选通道（保留）：走 backend 的登录态代理 /admin/digitalHuman/**，
// 由 backend 校验 admin 角色后补 AK/SK 出网关 —— 适合"凭证不出服务端"的更严场景。
export default defineConfig(({ command }) => ({
  plugins: [react()],
  // 【2026-10-11 白屏事故】部署路径是 /admin/（nginx bind mount），必须先于 vite 默认
  // base='/' 声明 —— 否则产物 index.html 引用 /assets/**.js（根路径），
  // 而根路径下 nginx 的 SPA fallback 会把 index.html 当 JS 返回（200 但内容是 HTML）
  // → 浏览器解析报错 → 整个页面白屏（连登录门都渲染不出来）。
  // build 用 /admin/，dev server 保持根路径（本机 5174 直接访问不受影响）。
  base: command === 'build' ? '/admin/' : '/',
  build: {
    // Windows 本机构建绕过（2026-10-07）：安全软件会拦截 esbuild 删除临时文件，
    // 报 [vite:esbuild-transpile] remove ... Access is denied。
    //   target: 'esnext' → 跳过降级转译（esbuild-transpile 整包临时文件的删除动作也随之消失）
    //   minify: false   → 跳过 esbuild 压缩步骤
    // 此前靠 CLI 传参，但该 CLI 版本未暴露 --target（会被当成入口路径），故固化到配置。
    target: 'esnext',
    minify: false,
  },
  server: {
    host: '127.0.0.1',
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8090',
        changeOrigin: true,
      },
    },
  },
}));
