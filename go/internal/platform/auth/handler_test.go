package auth

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/aismp/platform/internal/httpkit"
	"github.com/aismp/platform/internal/platform/user"
)

// ---------------------------------------------------------------- 测试替身

// fakeRepo 用内存 map 实现 Repository，行为对齐 user.Repository 的可见语义。
type fakeRepo struct {
	users    map[string]*user.User
	tokens   map[string]user.TokenPrincipal
	expires  map[string]time.Time
	issueErr error
	seq      atomic.Int64 // 令牌序号：Windows 时钟粒度下连续两次登录可能拿到同一纳秒
}

func newFakeRepo() *fakeRepo {
	return &fakeRepo{
		users:   map[string]*user.User{},
		tokens:  map[string]user.TokenPrincipal{},
		expires: map[string]time.Time{},
	}
}

func (f *fakeRepo) FindByCode(_ context.Context, code string) (*user.User, error) {
	if u, ok := f.users[code]; ok {
		return u, nil
	}
	return nil, user.ErrNotFound
}

func (f *fakeRepo) IssueToken(_ context.Context, userCode, role string, ttl time.Duration) (string, error) {
	if f.issueErr != nil {
		return "", f.issueErr
	}
	token := fmt.Sprintf("tok-%s-%d-%s", userCode, f.seq.Add(1), time.Now().Format("150405.000000000"))
	f.tokens[token] = user.TokenPrincipal{UserCode: userCode, Role: role}
	f.expires[token] = time.Now().Add(ttl)
	return token, nil
}

func (f *fakeRepo) FindToken(_ context.Context, token string) (user.TokenPrincipal, bool, error) {
	p, ok := f.tokens[token]
	if !ok {
		return user.TokenPrincipal{}, false, nil
	}
	if time.Now().After(f.expires[token]) {
		return user.TokenPrincipal{}, false, nil
	}
	return p, true, nil
}

func (f *fakeRepo) DeleteToken(_ context.Context, token string) (int64, error) {
	if _, ok := f.tokens[token]; !ok {
		return 0, nil
	}
	delete(f.tokens, token)
	delete(f.expires, token)
	return 1, nil
}

func (f *fakeRepo) PurgeExpired(_ context.Context, userCode string) error {
	for tk, p := range f.tokens {
		if p.UserCode == userCode && time.Now().After(f.expires[tk]) {
			delete(f.tokens, tk)
			delete(f.expires, tk)
		}
	}
	return nil
}

// ---------------------------------------------------------------- 测试脚手架

func newTestEngine(repo Repository) *gin.Engine {
	gin.SetMode(gin.TestMode)
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	e := httpkit.NewEngine(log)
	NewHandler(repo, log).Register(e)
	return e
}

func doJSON(t *testing.T, e *gin.Engine, method, path, body, bearer string) (*httptest.ResponseRecorder, map[string]any) {
	t.Helper()
	var rdr io.Reader
	if body != "" {
		rdr = strings.NewReader(body)
	}
	req := httptest.NewRequest(method, path, rdr)
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	w := httptest.NewRecorder()
	e.ServeHTTP(w, req)

	var out map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
		t.Fatalf("响应不是合法 JSON: %s", w.Body.String())
	}
	return w, out
}

func seedUser(repo *fakeRepo, code, plain, role, status string, wCode *string) {
	repo.users[code] = &user.User{
		UserCode:      code,
		UserName:      code + "的名字",
		PasswordHash:  SHA256Hex(plain),
		Role:          role,
		Status:        status,
		WarehouseCode: wCode,
	}
}

func ptr(s string) *string { return &s }

// ---------------------------------------------------------------- 哈希对齐

// TestSHA256HexMatchesJavaSeed 钉住口令算法：库里 a_user.password_hash 存的是
// SHA2(password, 256) 的小写 hex，演示口令 123456 的期望值是从真实库读出来的。
// 这条测试挂了就意味着所有演示账号都登不进去。
func TestSHA256HexMatchesJavaSeed(t *testing.T) {
	const want = "8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92"
	if got := SHA256Hex("123456"); got != want {
		t.Fatalf("SHA256Hex(123456) = %s，期望 %s", got, want)
	}
}

// ---------------------------------------------------------------- 登录

func TestLoginSuccess(t *testing.T) {
	repo := newFakeRepo()
	seedUser(repo, "xn_zg", "123456", user.RoleAccountMgr, user.StatusActive, ptr("9BZA"))
	e := newTestEngine(repo)

	w, body := doJSON(t, e, http.MethodPost, "/auth/login",
		`{"userCode":"xn_zg","password":"123456"}`, "")

	if w.Code != http.StatusOK {
		t.Fatalf("状态码 = %d，期望 200，body=%s", w.Code, w.Body.String())
	}
	if body["token"] == "" || body["token"] == nil {
		t.Fatal("token 为空")
	}
	if body["roleLabel"] != "账管" {
		t.Errorf("roleLabel = %v，期望 账管", body["roleLabel"])
	}
	if body["warehouseCode"] != "9BZA" {
		t.Errorf("warehouseCode = %v，期望 9BZA", body["warehouseCode"])
	}
	if body["expiresInHours"] != float64(24) {
		t.Errorf("expiresInHours = %v，期望 24", body["expiresInHours"])
	}
	// 密码绝不能回显
	if strings.Contains(w.Body.String(), "8d969eef") || strings.Contains(w.Body.String(), "password") {
		t.Errorf("响应体泄漏了口令或哈希: %s", w.Body.String())
	}
}

// TestLoginPlatformRoleHasEmptyLabel 平台级角色（ADMIN）没有中文标签，且仓库字段应为 null。
func TestLoginPlatformRoleHasEmptyLabel(t *testing.T) {
	repo := newFakeRepo()
	seedUser(repo, "admin", "123456", user.RoleAdmin, user.StatusActive, nil)
	e := newTestEngine(repo)

	_, body := doJSON(t, e, http.MethodPost, "/auth/login",
		`{"userCode":"admin","password":"123456"}`, "")

	if body["roleLabel"] != "" {
		t.Errorf("roleLabel = %v，期望空串", body["roleLabel"])
	}
	if v, ok := body["warehouseCode"]; !ok || v != nil {
		t.Errorf("warehouseCode = %v，期望显式 null", v)
	}
}

// TestLoginMissingFields 400：缺字段与空串都算参数错误。
func TestLoginMissingFields(t *testing.T) {
	repo := newFakeRepo()
	seedUser(repo, "xn_zg", "123456", user.RoleAccountMgr, user.StatusActive, nil)
	e := newTestEngine(repo)

	cases := []string{
		`{}`,
		`{"userCode":"xn_zg"}`,
		`{"userCode":"xn_zg","password":""}`,
		`{"userCode":"   ","password":"123456"}`,
	}
	for _, c := range cases {
		w, body := doJSON(t, e, http.MethodPost, "/auth/login", c, "")
		if w.Code != http.StatusBadRequest {
			t.Errorf("body=%s 状态码 = %d，期望 400", c, w.Code)
		}
		if body["error"] != "userCode 和 password 不能为空" {
			t.Errorf("body=%s error = %v", c, body["error"])
		}
	}
}

// TestLoginRejectsWrongPasswordAndUnknownUser 401，且两者文案必须一致（防账号枚举）。
func TestLoginRejectsWrongPasswordAndUnknownUser(t *testing.T) {
	repo := newFakeRepo()
	seedUser(repo, "xn_zg", "123456", user.RoleAccountMgr, user.StatusActive, nil)
	e := newTestEngine(repo)

	wrongPwd := `{"userCode":"xn_zg","password":"000000"}`
	unknown := `{"userCode":"nobody","password":"123456"}`

	w1, b1 := doJSON(t, e, http.MethodPost, "/auth/login", wrongPwd, "")
	w2, b2 := doJSON(t, e, http.MethodPost, "/auth/login", unknown, "")

	if w1.Code != http.StatusUnauthorized || w2.Code != http.StatusUnauthorized {
		t.Fatalf("状态码 = %d / %d，期望都是 401", w1.Code, w2.Code)
	}
	if b1["error"] != b2["error"] {
		t.Errorf("两种失败的文案不一致，可被用来枚举账号: %v vs %v", b1["error"], b2["error"])
	}
}

// TestLoginDisabledAccount 403。
func TestLoginDisabledAccount(t *testing.T) {
	repo := newFakeRepo()
	seedUser(repo, "dn_zg", "123456", user.RoleAccountMgr, user.StatusDisabled, nil)
	e := newTestEngine(repo)

	w, body := doJSON(t, e, http.MethodPost, "/auth/login",
		`{"userCode":"dn_zg","password":"123456"}`, "")

	if w.Code != http.StatusForbidden {
		t.Fatalf("状态码 = %d，期望 403", w.Code)
	}
	if body["error"] != "账号已停用" {
		t.Errorf("error = %v", body["error"])
	}
}

// TestLoginHashCompareIsCaseInsensitive Java 侧用 equalsIgnoreCase，
// 历史数据里若存了大写 hex 也必须能登。
func TestLoginHashCompareIsCaseInsensitive(t *testing.T) {
	repo := newFakeRepo()
	seedUser(repo, "xn_zg", "123456", user.RoleAccountMgr, user.StatusActive, nil)
	repo.users["xn_zg"].PasswordHash = strings.ToUpper(SHA256Hex("123456"))
	e := newTestEngine(repo)

	w, _ := doJSON(t, e, http.MethodPost, "/auth/login",
		`{"userCode":"xn_zg","password":"123456"}`, "")

	if w.Code != http.StatusOK {
		t.Fatalf("大写哈希未通过校验，状态码 = %d", w.Code)
	}
}

// TestLoginMalformedJSON 400。
func TestLoginMalformedJSON(t *testing.T) {
	e := newTestEngine(newFakeRepo())
	w, _ := doJSON(t, e, http.MethodPost, "/auth/login", `not-json`, "")
	if w.Code != http.StatusBadRequest {
		t.Fatalf("状态码 = %d，期望 400", w.Code)
	}
}

// ---------------------------------------------------------------- me

func TestMeWithoutTokenReturns200Error(t *testing.T) {
	e := newTestEngine(newFakeRepo())

	w, body := doJSON(t, e, http.MethodGet, "/auth/me", "", "")

	// 刻意锁定这个「不优雅但与 Java 一致」的行为：未登录返回 200 + {"error":"未登录"}。
	if w.Code != http.StatusOK {
		t.Fatalf("状态码 = %d，期望 200（与 Java 版保持一致）", w.Code)
	}
	if body["error"] != "未登录" {
		t.Errorf("error = %v，期望 未登录", body["error"])
	}
}

func TestMeWithValidToken(t *testing.T) {
	repo := newFakeRepo()
	seedUser(repo, "xn_kg", "123456", user.RoleWarehouseKp, user.StatusActive, ptr("9BZA"))
	e := newTestEngine(repo)

	_, login := doJSON(t, e, http.MethodPost, "/auth/login",
		`{"userCode":"xn_kg","password":"123456"}`, "")
	token, _ := login["token"].(string)

	w, body := doJSON(t, e, http.MethodGet, "/auth/me", "", token)
	if w.Code != http.StatusOK {
		t.Fatalf("状态码 = %d", w.Code)
	}
	if body["userCode"] != "xn_kg" {
		t.Errorf("userCode = %v", body["userCode"])
	}
	if body["roleLabel"] != "仓管" {
		t.Errorf("roleLabel = %v，期望 仓管", body["roleLabel"])
	}
	if body["warehouseCode"] != "9BZA" {
		t.Errorf("warehouseCode = %v", body["warehouseCode"])
	}
}

// TestMeWithExpiredToken 过期令牌视同未登录。
func TestMeWithExpiredToken(t *testing.T) {
	repo := newFakeRepo()
	seedUser(repo, "xn_zg", "123456", user.RoleAccountMgr, user.StatusActive, nil)
	e := newTestEngine(repo)

	_, login := doJSON(t, e, http.MethodPost, "/auth/login",
		`{"userCode":"xn_zg","password":"123456"}`, "")
	token, _ := login["token"].(string)

	// 人为把令牌改成已过期
	repo.expires[token] = time.Now().Add(-time.Minute)

	_, body := doJSON(t, e, http.MethodGet, "/auth/me", "", token)
	if body["error"] != "未登录" {
		t.Errorf("过期令牌未被拒绝，body = %v", body)
	}
}

// TestMeFallsBackWhenUserDeleted 账号被删但令牌还在有效期时，用令牌里的身份兜底。
func TestMeFallsBackWhenUserDeleted(t *testing.T) {
	repo := newFakeRepo()
	seedUser(repo, "xn_zg", "123456", user.RoleAccountMgr, user.StatusActive, nil)
	e := newTestEngine(repo)

	_, login := doJSON(t, e, http.MethodPost, "/auth/login",
		`{"userCode":"xn_zg","password":"123456"}`, "")
	token, _ := login["token"].(string)
	delete(repo.users, "xn_zg")

	_, body := doJSON(t, e, http.MethodGet, "/auth/me", "", token)
	if body["userCode"] != "xn_zg" {
		t.Errorf("userCode = %v", body["userCode"])
	}
	if body["userName"] != "xn_zg" {
		t.Errorf("userName 应退化为 userCode，实际 = %v", body["userName"])
	}
}

// ---------------------------------------------------------------- logout

func TestLogoutRevokesToken(t *testing.T) {
	repo := newFakeRepo()
	seedUser(repo, "xn_zg", "123456", user.RoleAccountMgr, user.StatusActive, nil)
	e := newTestEngine(repo)

	_, login := doJSON(t, e, http.MethodPost, "/auth/login",
		`{"userCode":"xn_zg","password":"123456"}`, "")
	token, _ := login["token"].(string)

	_, out := doJSON(t, e, http.MethodPost, "/auth/logout", "", token)
	if out["loggedOut"] != true {
		t.Fatalf("首次登出 loggedOut = %v，期望 true", out["loggedOut"])
	}
	// 二次登出应该是 false（令牌已经不在了）
	_, out2 := doJSON(t, e, http.MethodPost, "/auth/logout", "", token)
	if out2["loggedOut"] != false {
		t.Errorf("二次登出 loggedOut = %v，期望 false", out2["loggedOut"])
	}
	// 登出后 me 必须失败
	_, me := doJSON(t, e, http.MethodGet, "/auth/me", "", token)
	if me["error"] != "未登录" {
		t.Errorf("登出后仍能取到身份: %v", me)
	}
}

// TestLogoutWithoutToken 无令牌时 200 + loggedOut:false。
func TestLogoutWithoutToken(t *testing.T) {
	e := newTestEngine(newFakeRepo())
	w, body := doJSON(t, e, http.MethodPost, "/auth/logout", "", "")
	if w.Code != http.StatusOK || body["loggedOut"] != false {
		t.Fatalf("状态码 = %d body = %v", w.Code, body)
	}
}

// ---------------------------------------------------------------- 令牌形态

// TestIssueTokenIsOpaqueUUIDPerLogin 每次登录都换新令牌；旧令牌不自动失效（无单点登录约束）。
func TestIssueTokenIsOpaqueUUIDPerLogin(t *testing.T) {
	repo := newFakeRepo()
	seedUser(repo, "xn_zg", "123456", user.RoleAccountMgr, user.StatusActive, nil)
	e := newTestEngine(repo)

	_, a := doJSON(t, e, http.MethodPost, "/auth/login", `{"userCode":"xn_zg","password":"123456"}`, "")
	_, b := doJSON(t, e, http.MethodPost, "/auth/login", `{"userCode":"xn_zg","password":"123456"}`, "")

	if a["token"] == b["token"] {
		t.Fatal("两次登录拿到了同一个令牌")
	}
}
