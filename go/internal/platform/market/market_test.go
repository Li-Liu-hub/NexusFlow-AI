package market

import (
	"encoding/hex"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// 会话存储：创建/读取/过期/销毁。
func TestSessionStore(t *testing.T) {
	s := NewSession()
	sid := s.Create(SessionUser{ID: 7, UserRole: "admin"})

	if u, ok := s.Get(sid); !ok || u.ID != 7 || u.UserRole != "admin" {
		t.Fatalf("Get 应返回写入的用户: %+v %v", u, ok)
	}
	if _, ok := s.Get(uuid.NewString()); ok {
		t.Fatal("随机 id 不应命中")
	}
	// 手动把活跃时间拨到 25 小时前 → 过期
	s.mu.Lock()
	s.entries[sid].lastActive = time.Now().Add(-25 * time.Hour)
	s.mu.Unlock()
	if _, ok := s.Get(sid); ok {
		t.Fatal("超过 24h 不活跃应过期")
	}

	sid2 := s.Create(SessionUser{ID: 8, UserRole: "user"})
	s.Remove(sid2)
	if _, ok := s.Get(sid2); ok {
		t.Fatal("Remove 后不应命中")
	}
}

// MD5Hex 跨语言基准向量：md5("abc")。
func TestMD5Hex(t *testing.T) {
	if got := MD5Hex("abc"); got != "900150983cd24fb0d6963f7d28e17f72" {
		t.Fatalf("MD5Hex(abc) = %s", got)
	}
}

// Sign 契约：SHA256(body + "." + secretKey) 的小写 hex（与 SignUtils/网关验签一致）。
func TestSignFormat(t *testing.T) {
	got := Sign("{\"a\":1}", "sk-测试")
	if len(got) != 64 {
		t.Fatalf("应为 64 位 hex: %s", got)
	}
	if _, err := hex.DecodeString(got); err != nil {
		t.Fatalf("应为合法 hex: %v", err)
	}
	if Sign("a", "sk1") == Sign("a", "sk2") {
		t.Fatal("不同 secretKey 应得到不同签名")
	}
}

func newTestHandler() *Handler {
	gin.SetMode(gin.TestMode)
	return NewHandler(NewRepository(nil), NewSession(), slog.Default(), "http://127.0.0.1:8090", "ak", "sk")
}

// 未登录守卫语义：HTTP 200 + code 40100/40101（对齐验收脚本 C1 的判断）。
func TestGuardsNotLogin(t *testing.T) {
	h := newTestHandler()
	r := gin.New()
	h.Register(r)

	cases := []struct {
		path string
		want string
	}{
		{"/userInterfaceInfo/list", `"code":40100`},
		{"/interfaceInfo/list", `"code":40100`},
		{"/user/get/login", `"code":40100`},
		{"/admin-does-not-exist", ""}, // 未挂路由，走 NoRoute 由引擎处理（此处仅对照前三个）
	}
	for _, tc := range cases {
		if tc.want == "" {
			continue
		}
		req := httptest.NewRequest(http.MethodGet, tc.path, nil)
		w := httptest.NewRecorder()
		r.ServeHTTP(w, req)
		if w.Code != http.StatusOK || !strings.Contains(w.Body.String(), tc.want) {
			t.Errorf("%s → %d %s, want 200 containing %s", tc.path, w.Code, w.Body.String(), tc.want)
		}
	}
}

// 注册校验文案逐条对照 UserServiceImpl（不触库的分支）。
func TestRegisterValidation(t *testing.T) {
	h := newTestHandler()
	r := gin.New()
	h.Register(r)

	do := func(body string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPost, "/user/register", strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		r.ServeHTTP(w, req)
		return w
	}

	// 缺字段 → Java `return null` → 200 空 body
	if w := do(`{"userAccount":"a","userPassword":"12345678","checkPassword":""}`); w.Body.Len() != 0 {
		t.Fatalf("缺 checkPassword 应空 body: %s", w.Body.String())
	}
	if w := do(`{"userAccount":"ab","userPassword":"12345678","checkPassword":"12345678"}`); !strings.Contains(w.Body.String(), "用户账号过短") {
		t.Fatalf("短账号: %s", w.Body.String())
	}
	if w := do(`{"userAccount":"abcd","userPassword":"1234567","checkPassword":"1234567"}`); !strings.Contains(w.Body.String(), "用户密码过短") {
		t.Fatalf("短密码: %s", w.Body.String())
	}
	if w := do(`{"userAccount":"abcd","userPassword":"12345678","checkPassword":"123456789"}`); !strings.Contains(w.Body.String(), "两次输入的密码不一致") {
		t.Fatalf("不一致: %s", w.Body.String())
	}
}

// 接口元数据校验：add 必填 name、长度 ≤ 50（按字符数，不是字节数）。
func TestValidInterfaceInfo(t *testing.T) {
	if _, ok := validInterfaceInfo(nil, true); ok {
		t.Fatal("add 时空 name 应拒绝")
	}
	long := strings.Repeat("长", 51)
	if msg, ok := validInterfaceInfo(&long, false); ok || msg != "名称过长" {
		t.Fatalf("51 个汉字应拒绝: %q %v", msg, ok)
	}
	fifty := strings.Repeat("长", 50)
	if _, ok := validInterfaceInfo(&fifty, true); !ok {
		t.Fatal("50 个汉字应放行")
	}
}

// 额度校验：add 时接口/用户必填、剩余次数非负。
func TestValidQuota(t *testing.T) {
	one, two, neg := int64(1), int64(2), -1
	if msg, _, ok := validQuota(nil, nil, nil, true); ok || msg != "接口或用户不存在" {
		t.Fatalf("add 缺 id: %q", msg)
	}
	if msg, _, ok := validQuota(&one, &two, &neg, false); ok || msg != "剩余次数不能小于 0" {
		t.Fatalf("负剩余: %q", msg)
	}
}

// 分页参数：缺省 current=1 / pageSize=10（PageRequest 的字段默认值）。
func TestPageParams(t *testing.T) {
	gin.SetMode(gin.TestMode)
	mk := func(q string) *gin.Context {
		c, _ := gin.CreateTestContext(httptest.NewRecorder())
		c.Request = httptest.NewRequest(http.MethodGet, "/x?"+q, nil)
		return c
	}
	if cur, size := pageParams(mk("")); cur != 1 || size != 10 {
		t.Fatalf("缺省分页 = %d/%d, want 1/10", cur, size)
	}
	if cur, size := pageParams(mk("current=3&pageSize=20")); cur != 3 || size != 20 {
		t.Fatalf("显式分页 = %d/%d, want 3/20", cur, size)
	}
}

// 分页响应的 pages 取整。
func TestNewPage(t *testing.T) {
	p := newPage([]any{}, 21, 1, 10)
	if p.Pages != 3 {
		t.Fatalf("pages = %d, want 3", p.Pages)
	}
}

// 排序解析：ascend → ASC，其余 → DESC；非标识符字段拒绝。
func TestOrderExpr(t *testing.T) {
	gin.SetMode(gin.TestMode)

	mk := func(field string) *gin.Context {
		c, _ := gin.CreateTestContext(httptest.NewRecorder())
		target := "/x"
		if field != "" {
			target += "?sortField=" + url.QueryEscape(field)
		}
		c.Request = httptest.NewRequest(http.MethodGet, target, nil)
		return c
	}
	if expr, ok := orderExprFromRequest(mk("id")); !ok || expr != "id DESC" {
		t.Fatalf("缺省序: %q %v", expr, ok)
	}
	if _, ok := orderExprFromRequest(mk("id;DROP TABLE user")); ok {
		t.Fatal("注入形态的排序字段应被拒绝")
	}
	if _, ok := orderExprFromRequest(mk("id--")); ok {
		t.Fatal("注释形态的排序字段应被拒绝")
	}
	if expr, ok := orderExprFromRequest(mk("")); !ok || expr != "" {
		t.Fatal("空字段应放行且不产生排序子句")
	}

	// ascend 需要显式 sortOrder
	c, _ := gin.CreateTestContext(httptest.NewRecorder())
	c.Request = httptest.NewRequest(http.MethodGet, "/x?sortField=id&sortOrder=ascend", nil)
	if expr, ok := orderExprFromRequest(c); !ok || expr != "id ASC" {
		t.Fatalf("ascend: %q %v", expr, ok)
	}
}
