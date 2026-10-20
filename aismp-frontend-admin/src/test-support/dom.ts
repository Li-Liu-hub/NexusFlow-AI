// 最小浏览器桩：node 环境下跑前端纯逻辑测试用。
//
// 只实现被测代码真正碰到的那几个 API（localStorage / window.dispatchEvent / CustomEvent），
// 不追求完整 —— 完整实现叫 jsdom，那是装包成本，不是测试收益。
export const authEvents: string[] = [];

class MemoryStorage implements Storage {
  private map = new Map<string, string>();

  get length(): number {
    return this.map.size;
  }

  clear(): void {
    this.map.clear();
  }

  getItem(key: string): string | null {
    return this.map.has(key) ? (this.map.get(key) as string) : null;
  }

  key(index: number): string | null {
    return Array.from(this.map.keys())[index] ?? null;
  }

  removeItem(key: string): void {
    this.map.delete(key);
  }

  setItem(key: string, value: string): void {
    this.map.set(key, String(value));
  }
}

export function installBrowserStubs(): void {
  const g = globalThis as any;

  g.localStorage = new MemoryStorage();

  if (typeof g.CustomEvent === 'undefined') {
    g.CustomEvent = class CustomEvent {
      type: string;
      detail: unknown;

      constructor(type: string, init?: { detail?: unknown }) {
        this.type = type;
        this.detail = init?.detail;
      }
    };
  }

  g.window = g.window || {
    dispatchEvent: (event: { type: string }) => {
      authEvents.push(event.type);
      return true;
    },
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  };
}

export function resetAuthEvents(): void {
  authEvents.length = 0;
}
