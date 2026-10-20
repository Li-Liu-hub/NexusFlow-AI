import { installBrowserStubs } from './test-support/dom';

// vitest setupFiles：每个测试文件运行前装一次浏览器桩
installBrowserStubs();
