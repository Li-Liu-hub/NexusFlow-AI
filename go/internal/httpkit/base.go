// Package httpkit 收敛跨服务的 HTTP 契约：
// 统一响应体（对齐 Java 的 BaseResponse）、错误码（对齐 ErrorCode 枚举）、
// 以及 gin 引擎的公共装配。
package httpkit

import "net/http"

// Code 是业务错误码，取值与 Java 侧 com.aismp.project.common.ErrorCode 一一对应。
// 改名会破坏前端判断逻辑，不要动数值。
type Code int

const (
	CodeSuccess     Code = 0
	CodeParams      Code = 40000 // PARAMS_ERROR  请求参数错误
	CodeNotLogin    Code = 40100 // NOT_LOGIN_ERROR 未登录
	CodeNoAuth      Code = 40101 // NO_AUTH_ERROR  无权限
	CodeForbidden   Code = 40300 // FORBIDDEN_ERROR 禁止访问
	CodeNotFound    Code = 40400 // NOT_FOUND_ERROR 请求数据不存在
	CodeSystem      Code = 50000 // SYSTEM_ERROR  系统内部异常
	CodeOperation   Code = 50001 // OPERATION_ERROR 操作失败
)

// Message 返回错误码对应的中文文案，与 Java 枚举的 message 字段一致。
func (c Code) Message() string {
	switch c {
	case CodeSuccess:
		return "ok"
	case CodeParams:
		return "请求参数错误"
	case CodeNotLogin:
		return "未登录"
	case CodeNoAuth:
		return "无权限"
	case CodeForbidden:
		return "禁止访问"
	case CodeNotFound:
		return "请求数据不存在"
	case CodeSystem:
		return "系统内部异常"
	case CodeOperation:
		return "操作失败"
	default:
		return "未知错误"
	}
}

// HTTPStatus 把业务码映射到 HTTP 状态码。
//
// 注意：Java 版绝大多数接口是「HTTP 200 + code 非 0」，前端按 code 判断。
// 这里保留那套语义，只在少数已明确修正过的接口（如 /auth/login，见 7655013）
// 上使用真实状态码。
func (c Code) HTTPStatus() int {
	switch c {
	case CodeSuccess:
		return http.StatusOK
	case CodeParams:
		return http.StatusBadRequest
	case CodeNotLogin:
		return http.StatusUnauthorized
	case CodeNoAuth, CodeForbidden:
		return http.StatusForbidden
	case CodeNotFound:
		return http.StatusNotFound
	default:
		return http.StatusInternalServerError
	}
}

// BaseResponse 是 Java 侧 BaseResponse<T> 的同构体：{code, data, message}。
type BaseResponse struct {
	Code    int    `json:"code"`
	Data    any    `json:"data"`
	Message string `json:"message"`
}

// OK 构造成功响应。
func OK(data any) BaseResponse {
	return BaseResponse{Code: int(CodeSuccess), Data: data, Message: "ok"}
}

// Fail 按错误码构造失败响应，data 恒为 null。
func Fail(code Code) BaseResponse {
	return BaseResponse{Code: int(code), Data: nil, Message: code.Message()}
}

// FailMsg 在错误码基础上覆盖文案（Java 侧大量 new BaseResponse(code, null, 自定义文案)）。
func FailMsg(code Code, message string) BaseResponse {
	return BaseResponse{Code: int(code), Data: nil, Message: message}
}
