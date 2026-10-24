package dhproxy

import (
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"

	"github.com/aismp/platform/internal/platform/market"
)

// 非_ASCII 转 \uXXXX：只动字符、不动 JSON 结构。
func TestToASCIIJSON(t *testing.T) {
	got := toASCIIJSON(`{"name":"数字员工","n":1}`)
	want := `{"name":"\u6570\u5b57\u5458\u5de5","n":1}`
	if got != want {
		t.Fatalf("got  %s\nwant %s", got, want)
	}
	if got := toASCIIJSON("plain ascii"); got != "plain ascii" {
		t.Fatalf("ASCII 应原样: %s", got)
	}
}

func TestExtractJSONString(t *testing.T) {
	if got := extractJSONString(`{"token":"abc-123","role":"ADMIN"}`, "token"); got != "abc-123" {
		t.Fatalf("got %q", got)
	}
	if got := extractJSONString(`{"error":"x"}`, "token"); got != "" {
		t.Fatalf("缺字段应返回空: %q", got)
	}
}

// 未登录走代理 → HTTP 200 + code 40100（对齐验收脚本 C1）。
func TestProxyNotLogin(t *testing.T) {
	gin.SetMode(gin.TestMode)
	h := NewHandler(Config{}, market.NewRepository(nil), market.NewSession(), slog.Default())
	r := gin.New()
	h.Register(r.Group("/api")) // 与 main.go 一致：路由挂在 /api 分组下

	req := httptest.NewRequest(http.MethodGet, "/api/admin/digitalHuman/agent/list", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK || !strings.Contains(w.Body.String(), `"code":40100`) {
		t.Fatalf("未登录应 200+40100: %d %s", w.Code, w.Body.String())
	}
}
