import { defineConfig } from 'vite';

// 门户脚本（原 public/portal.js）的构建配置。
//
// 为什么单独一份：portal.html 用普通 <script src="./portal.js?v=..."> 加载它，
// 不能当 ES module 发；所以走 vite 的 lib 模式打成 IIFE，输出仍旧叫 portal.js，
// 放回 public/ 让 portal.html 的相对路径保持不变（nginx 侧不需要改）。
//
// 产物 public/portal.js 是**构建产物**，已加入 .gitignore —— 仓库里只保留 TS 源码。
export default defineConfig({
  // 产物直接写回 public/，必须关掉 publicDir 拷贝（否则 vite 会警告两者是同一目录）。
  // 注意它是**顶层**选项，不是 build.* 下的字段。
  publicDir: false,
  build: {
    lib: {
      entry: 'src/portal/portal.ts',
      name: 'NexusFlowPortal',
      formats: ['iife'],
      fileName: () => 'portal.js',
    },
    outDir: 'public',
    emptyOutDir: false,
    // 跳过压缩：本机安全软件会对 esbuild 的临时文件报 Access is denied
    minify: false,
    target: 'esnext',
    cssCodeSplit: false,
  },
});
