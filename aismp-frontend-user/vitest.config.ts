import { defineConfig } from 'vitest/config';

// 用户端单元测试：只覆盖请求封装（纯逻辑），环境用 node，不引 jsdom。
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
    reporters: 'default',
  },
});
