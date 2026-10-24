// Package market 实现 yuapi 血统的三个管理控制器（/user、/interfaceInfo、/userInterfaceInfo）。
//
// 移植自 UserController / InterfaceinfoController / UserInterfaceinfoController。
// 这组接口服务 API 开放平台管理面（aismp-frontend-master，Ant Design Pro），
// 鉴权走 HttpSession（cookie JSESSIONID）——与门户的 Bearer 令牌（/auth/**，见 auth 包）是两套体系。
//
// 与 Java 版的有意偏差（都会在 README 记录）：
//   - RabbitMQ 缓存失效通知暂不发送（打告警日志）；网关侧 Redis 缓存 30 分钟 TTL 自愈，
//     与 Java 版「MQ 发送失败仅记日志」的降级形态一致。P5 切换时接入。
//   - 分页排序字段做标识符白名单校验（Java 版 MP orderBy 直拼列名，存在注入面）。
package market

import (
	"crypto/rand"
	"encoding/hex"
	"sync"
	"time"

	"github.com/google/uuid"
)

// SessionUser 是放进 session 的用户脱敏形态。
//
// Java 侧把整个 User 实体塞进 session（USER_LOGIN_STATE），isAdmin 只读 session 里的
// userRole、getLoginUser 用 session 里的 id 回查数据库——这里按同样口径存这两个字段。
type SessionUser struct {
	ID       int64
	UserRole string
}

// Session 是内存会话存储。
//
// Java 侧 session.store-type: none（Tomcat 内存会话），重启即失效、不跨实例——
// 这里保持同构：单实例内存 map，24h 不活跃过期（server.servlet.session.timeout: 86400 秒）。
type Session struct {
	mu       sync.Mutex
	entries  map[string]*sessionEntry
	idleTTL  time.Duration
	now      func() time.Time
}

type sessionEntry struct {
	user       SessionUser
	lastActive time.Time
}

// NewSession 构造会话存储。
func NewSession() *Session {
	s := &Session{entries: map[string]*sessionEntry{}, idleTTL: 24 * time.Hour, now: time.Now}
	return s
}

// Create 为登录成功的用户创建新会话，返回会话 ID（即 JSESSIONID 的值）。
func (s *Session) Create(user SessionUser) string {
	id := uuid.NewString()
	s.mu.Lock()
	defer s.mu.Unlock()
	s.entries[id] = &sessionEntry{user: user, lastActive: s.now()}
	return id
}

// Get 取会话并刷新活跃时间；不存在或已过期返回 ok=false。
func (s *Session) Get(id string) (SessionUser, bool) {
	if id == "" {
		return SessionUser{}, false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	e, ok := s.entries[id]
	if !ok {
		return SessionUser{}, false
	}
	now := s.now()
	if now.Sub(e.lastActive) > s.idleTTL {
		delete(s.entries, id)
		return SessionUser{}, false
	}
	e.lastActive = now
	return e.user, true
}

// Remove 销毁会话（注销）。
func (s *Session) Remove(id string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.entries, id)
}

// randomDigits 生成 n 位数字串（对齐 hutool RandomUtil.randomNumbers）。
func randomDigits(n int) string {
	const digits = "0123456789"
	buf := make([]byte, n)
	raw := make([]byte, n)
	_, _ = rand.Read(raw)
	for i := range buf {
		buf[i] = digits[int(raw[i])%len(digits)]
	}
	return string(buf)
}

// RandomHex32 生成 32 位 hex 随机串（网关签名头的高熵 nonce）。
func RandomHex32() string {
	buf := make([]byte, 16)
	_, _ = rand.Read(buf)
	return hex.EncodeToString(buf)
}
