package httpkit

import (
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

// NewEngine 构造平台统一的 gin 引擎。
//
// 与 Spring 侧的对应关系：
//   - gin.Recovery()      ≈ @ControllerAdvice 兜住未捕获异常（返回 500 而不是断连接）
//   - accessLog()         ≈ 原 spring.mvc 的访问日志
//   - c.Error()           ≈ 只是收集，不改变响应
func NewEngine(log *slog.Logger) *gin.Engine {
	gin.SetMode(gin.ReleaseMode)
	r := gin.New()
	r.Use(gin.Recovery(), accessLog(log))
	r.NoRoute(func(c *gin.Context) {
		c.JSON(http.StatusNotFound, FailMsg(CodeNotFound, "接口不存在: "+c.Request.URL.Path))
	})
	return r
}

// accessLog 输出结构化访问日志，字段名与运维台日志检索习惯对齐。
func accessLog(log *slog.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()
		log.Info("http",
			"method", c.Request.Method,
			"path", c.Request.URL.Path,
			"status", c.Writer.Status(),
			"latency_ms", time.Since(start).Milliseconds(),
			"ip", c.ClientIP(),
		)
	}
}

// BearerToken 从 Authorization: Bearer xxx 中取出令牌。
// 与 Java 侧 `header.startsWith("Bearer ")` 的判断完全一致（大小写敏感、单空格）。
func BearerToken(c *gin.Context) (string, bool) {
	header := c.GetHeader("Authorization")
	const prefix = "Bearer "
	if !strings.HasPrefix(header, prefix) {
		return "", false
	}
	token := strings.TrimSpace(strings.TrimPrefix(header, prefix))
	if token == "" {
		return "", false
	}
	return token, true
}
