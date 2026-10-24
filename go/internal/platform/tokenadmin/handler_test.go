package tokenadmin

import (
	"context"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/aismp/platform/internal/platform/user"
)

type fakeTokens struct {
	principal user.TokenPrincipal
	valid     bool
	err       error
}

func (f *fakeTokens) FindToken(_ context.Context, _ string) (user.TokenPrincipal, bool, error) {
	return f.principal, f.valid, f.err
}

func newTestHandler(tokens TokenChecker) *Handler {
	gin.SetMode(gin.TestMode)
	return NewHandler(NewService(nil, slog.Default()), tokens, slog.Default())
}

func doReq(h *Handler, path, authHeader string) *httptest.ResponseRecorder {
	r := gin.New()
	r.GET(path, h.guard, func(c *gin.Context) { c.String(http.StatusOK, "ok") })
	req := httptest.NewRequest(http.MethodGet, path, nil)
	if authHeader != "" {
		req.Header.Set("Authorization", authHeader)
	}
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

// 守卫语义对照 AuthRepository.checkAdmin：三种拒绝原因 + 两种放行角色。
func TestGuard(t *testing.T) {
	cases := []struct {
		name    string
		tokens  TokenChecker
		header  string
		want    int
		wantMsg string
	}{
		{"无 Authorization", &fakeTokens{}, "", 403, "未登录（缺少 Bearer token）"},
		{"令牌无效", &fakeTokens{valid: false}, "Bearer whatever", 403, "登录已过期或 token 无效，请重新登录"},
		{"普通用户", &fakeTokens{valid: true, principal: user.TokenPrincipal{UserCode: "xn_zg", Role: "USER"}},
			"Bearer ok", 403, "需要管理员权限"},
		{"ADMIN 放行", &fakeTokens{valid: true, principal: user.TokenPrincipal{UserCode: "admin", Role: "ADMIN"}},
			"Bearer ok", 200, ""},
		{"SERVICE 放行", &fakeTokens{valid: true, principal: user.TokenPrincipal{UserCode: "svc", Role: "SERVICE"}},
			"Bearer ok", 200, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			w := doReq(newTestHandler(tc.tokens), "/admin/token/quota/list", tc.header)
			if w.Code != tc.want {
				t.Fatalf("status = %d, want %d, body=%s", w.Code, tc.want, w.Body.String())
			}
			if tc.wantMsg != "" && !strings.Contains(w.Body.String(), tc.wantMsg) {
				t.Fatalf("body 缺少 %q: %s", tc.wantMsg, w.Body.String())
			}
		})
	}
}

// 月份解析：合法 yyyy-MM 取月初；缺省/非法回退本月。
func TestSaveQuotaValidation(t *testing.T) {
	svc := NewService(nil, slog.Default())
	ctx := context.Background()

	res, err := svc.SaveQuota(ctx, &Quota{})
	if err != nil {
		t.Fatal(err)
	}
	if res["error"] != "scope 与 target_code 必填" {
		t.Fatalf("got %v", res)
	}

	badScope := "TENANTX"
	res, _ = svc.SaveQuota(ctx, &Quota{Scope: &badScope, TargetCode: strPtr("u1")})
	if res["error"] != "scope 只支持 USER/AGENT/TENANT" {
		t.Fatalf("got %v", res)
	}
}

// 月份解析：合法 yyyy-MM 取月初；缺省/非法回退本月。
func TestMonthStart(t *testing.T) {
	now := mustTime("2026-10-24")
	if got := monthStartOf(now, "2026-03"); got.Format("2006-01-02") != "2026-03-01" {
		t.Fatalf("got %v", got)
	}
	if got := monthStartOf(now, ""); got.Format("2006-01-02") != "2026-10-01" {
		t.Fatalf("缺省应回退本月，got %v", got)
	}
	if got := monthStartOf(now, "not-a-month"); got.Format("2006-01-02") != "2026-10-01" {
		t.Fatalf("非法应回退本月，got %v", got)
	}
}

func strPtr(s string) *string { return &s }

func mustTime(date string) time.Time {
	t, err := time.ParseInLocation("2006-01-02", date, time.Local)
	if err != nil {
		panic(err)
	}
	return t
}
