package innerapi

import (
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

// AntMatch 对照 Spring AntPathMatcher 的登记通配形态。
func TestAntMatch(t *testing.T) {
	cases := []struct {
		pattern, path string
		want          bool
	}{
		// 登记表里的真实形态（local-init/61）：/digitalHuman/agent/** 覆盖整段前缀
		{"/digitalHuman/agent/**", "/digitalHuman/agent/kb_assistant/chat", true},
		{"/digitalHuman/agent/**", "/digitalHuman/agent", true},
		{"/digitalHuman/agent/**", "/digitalHuman/agent/", true},
		{"/digitalHuman/agent/**", "/digitalHuman/admin/agent/list", false},
		{"/api/terminal/*", "/api/terminal/list", true},
		{"/api/terminal/*", "/api/terminal/list/page", false},
		{"/a/*/c", "/a/b/c", true},
		{"/a/?", "/a/b", true},
		{"/a/?", "/a/bc", false},
		{"/exact/path", "/exact/path", true},
		{"/exact/path", "/exact/other", false},
		{"/x/**/y", "/x/a/b/y", true},
	}
	for _, tc := range cases {
		if got := AntMatch(tc.pattern, tc.path); got != tc.want {
			t.Errorf("AntMatch(%q, %q) = %v, want %v", tc.pattern, tc.path, got, tc.want)
		}
	}
}

// 内部接口的参数守卫：空参 → HTTP 200 + BaseResponse{code:40000}（网关按 null 处理）。
func TestParamGuards(t *testing.T) {
	gin.SetMode(gin.TestMode)
	h := NewHandler(NewRepository(nil))
	r := gin.New()
	h.Register(r)

	req := httptest.NewRequest(http.MethodGet, "/inner/user/byAccessKey", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK || !containsAll(w.Body.String(), `"code":40000`) {
		t.Fatalf("空 accessKey 应返回 PARAMS_ERROR: %d %s", w.Code, w.Body.String())
	}

	req = httptest.NewRequest(http.MethodGet, "/inner/interfaceInfo/byPathMethod", nil)
	w = httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK || !containsAll(w.Body.String(), `"code":40000`) {
		t.Fatalf("空 path/method 应返回 PARAMS_ERROR: %d %s", w.Code, w.Body.String())
	}

	req = httptest.NewRequest(http.MethodPost, "/inner/userInterfaceInfo/invokeCount?interfaceInfoId=abc&userId=1", nil)
	w = httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK || w.Body.String() != "false" {
		t.Fatalf("非数字 id 应返回 false: %d %s", w.Code, w.Body.String())
	}
	_ = slog.Default()
}

func containsAll(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}
