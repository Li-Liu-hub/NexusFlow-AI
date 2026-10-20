import { defineConfig } from 'vite';

// 门户的本机预览服务器（只服务 public/ 下的静态页，不做打包）。
//
// 为什么需要它：原来的 React 对话页删掉后，`npm run dev` 也跟着没了，
// 但门户 portal.html 仍要连 /api —— 直接用 file:// 或裸静态服务器都会被跨域挡住。
// 这里只做两件事：托管 public/、把 /api 代理到网关（8090），和线上 nginx 的行为一致。
//
// 用法：npm run dev  →  http://127.0.0.1:5173/portal.html
export default defineConfig({
  root: 'public',
  publicDir: false,
  // 门户的入口是 portal.html（不是 index.html），所以访问 / 要重写到它 ——
  // 否则打开 http://127.0.0.1:5173/ 是个 404，看着像服务没起。
  plugins: [
    {
      name: 'portal-index-rewrite',
      configureServer(server) {
        server.middlewares.use((req, _res, next) => {
          if (req.url === '/' || req.url === '') {
            req.url = '/portal.html';
          }
          next();
        });
      },
    },
  ],
  server: {
    host: '127.0.0.1',
    port: 5173,
    open: '/portal.html',
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8090',
        changeOrigin: true,
      },
    },
  },
});
