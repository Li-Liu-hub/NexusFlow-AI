// 管理端共享类型。
//
// 只放**跨页面复用**的契约：登录态、接口错误、以及后端统一响应体外壳。
// 各页面自己的 DTO（Agent / Skill / Kb / Quota …）留在各自页面里，
// 避免造一个「什么都往里塞」的全局 d.ts —— 那种文件最后没人敢改。

/** 登录态（localStorage 里存的就是这个对象，字段与 /api/auth/login 返回一致） */
export interface AuthUser {
  token: string;
  userCode: string;
  userName: string;
  role: 'ADMIN' | 'USER' | 'SERVICE';
}

/** 登录表单值 */
export interface LoginValues {
  userCode: string;
  password: string;
}

/** 后端统一响应外壳（内核与网关都用这一套） */
export interface ApiEnvelope<T> {
  code?: number;
  message?: string;
  data?: T;
  error?: string;
}

/** 菜单项（antd Menu items 的结构子集） */
export interface MenuEntry {
  key: string;
  label: string;
}
