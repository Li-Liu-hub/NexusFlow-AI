import { defineConfig } from 'vitest/config';

// 单元测试只跑纯逻辑（登录态、请求封装），不起浏览器：
// environment 用 node + src/test-setup.ts 里的最小 DOM 桩，
// 避免为了两个文件把 jsdom 拉进依赖（本机装包成本高，且测试跑得更快）。
export default defineConfig({
  test: {
    environment: 'node',
    setupFiles: ['src/test-setup.ts'],
    include: ['src/**/*.test.ts'],
    reporters: 'default',
  },
});
