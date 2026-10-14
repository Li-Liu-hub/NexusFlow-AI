import type { RequestConfig } from '@umijs/max';

// 与后端 BaseResponse 约定的响应数据格式
interface ResponseStructure {
  code: number;
  data: any;
  message?: string;
}

/**
 * @name 错误处理
 * pro 自带的错误处理， 可以在这里做自己的改动
 * @doc https://umijs.org/docs/max/request#配置
 */
export const requestConfig: RequestConfig = {
  withCredentials: true,
  // 响应拦截器
  responseInterceptors: [
    (response) => {
      // 拦截响应数据，进行个性化处理
      const { data } = response as unknown as ResponseStructure;
      if (data.code !== 0) {
        throw new Error(data.message);
      }
      return response;
    },
  ],
};
