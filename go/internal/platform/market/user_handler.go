// market 处理器（一）：/user/** —— 注册 / 登录 / 注销 / 用户 CRUD。
//
// 移植自 UserController + UserServiceImpl。鉴权走 JSESSIONID 会话（见 session.go）。
// 响应语义：除登录接口的请求体错误外，错误也返回 HTTP 200 + BaseResponse{code,message}
// （GlobalExceptionHandler 的行为），前端按 code 判断。
package market

import (
	"crypto/md5"
	"encoding/hex"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/aismp/platform/internal/httpkit"
)

// CookieName 与 Tomcat 默认会话 cookie 一致；Path 取 context-path /api。
const (
	CookieName = "JSESSIONID"
	cookiePath = "/api"
	// Salt 与 UserServiceImpl.SALT 一致（口令摘要与 AK/SK 生成的混淆盐）。
	Salt = "aismp-platform"
	// AdminRole 与 UserConstant.ADMIN_ROLE 一致。
	AdminRole = "admin"
)

// Handler 承载 market 三个控制器的全部端点。
type Handler struct {
	repo      *Repository
	session   *Session
	log       *slog.Logger
	// GatewayBaseURL 对应 OpenApiClient.DEFAULT_GATEWAY_HOST，由环境变量覆盖。
	GatewayBaseURL string
	// ClientAccessKey/SecretKey 对应 aismp.client.*（服务端探测接口用，绝不下发浏览器）。
	ClientAccessKey string
	ClientSecretKey string
	httpClient      *http.Client
}

// NewHandler 构造处理器。
func NewHandler(repo *Repository, session *Session, log *slog.Logger, gatewayBaseURL, clientAK, clientSK string) *Handler {
	return &Handler{
		repo: repo, session: session, log: log,
		GatewayBaseURL: gatewayBaseURL, ClientAccessKey: clientAK, ClientSecretKey: clientSK,
		httpClient: &http.Client{Timeout: 15 * time.Second},
	}
}

// Register 挂载三个控制器的路由。
func (h *Handler) Register(r gin.IRouter) {
	u := r.Group("/user")
	u.POST("/register", h.userRegister)
	u.POST("/login", h.userLogin)
	u.POST("/logout", h.userLogout)
	u.GET("/get/login", h.userGetLogin)
	u.POST("/add", h.userAdd)
	u.POST("/delete", h.userDelete)
	u.POST("/update", h.userUpdate)
	u.GET("/get", h.userGet)
	u.GET("/list", h.userList)
	u.GET("/list/page", h.userListPage)

	i := r.Group("/interfaceInfo")
	i.POST("/add", h.requireLogin, h.interfaceAdd)
	i.POST("/delete", h.requireLogin, h.interfaceDelete)
	i.POST("/update", h.requireLogin, h.interfaceUpdate)
	i.GET("/get", h.interfaceGet)
	i.GET("/list", h.requireAdmin, h.interfaceList)
	i.GET("/list/page", h.interfaceListPage)
	i.POST("/online", h.requireAdmin, h.interfaceOnline)
	i.POST("/offline", h.requireAdmin, h.interfaceOffline)
	i.POST("/invoke", h.requireLogin, h.interfaceInvoke)

	q := r.Group("/userInterfaceInfo")
	q.POST("/add", h.requireAdmin, h.quotaAdd)
	q.POST("/delete", h.requireAdmin, h.quotaDelete)
	q.POST("/update", h.requireAdmin, h.quotaUpdate)
	q.GET("/get", h.requireAdmin, h.quotaGet)
	q.GET("/list", h.requireAdmin, h.quotaList)
	q.GET("/list/page", h.requireAdmin, h.quotaListPage)
}

// ---------------------------------------------------------------- 会话助手

// currentUser 返回当前登录用户（session 里的 id 回查数据库，与 getLoginUser 一致）。
// 未登录返回 ok=false。
func (h *Handler) currentUser(c *gin.Context) (*User, bool) {
	sid, err := c.Cookie(CookieName)
	if err != nil || sid == "" {
		return nil, false
	}
	su, ok := h.session.Get(sid)
	if !ok {
		return nil, false
	}
	u, err := h.repo.UserGetByID(c.Request.Context(), su.ID)
	if err != nil {
		return nil, false
	}
	return u, true
}

// requireLogin 对齐 UserService.getLoginUser：未登录抛 NOT_LOGIN_ERROR（HTTP 200 + code）。
func (h *Handler) requireLogin(c *gin.Context) {
	if _, ok := h.currentUser(c); !ok {
		c.AbortWithStatusJSON(http.StatusOK, httpkit.Fail(httpkit.CodeNotLogin))
		return
	}
}

// requireAdmin 对齐 @AuthCheck(mustRole="admin") + AuthInterceptor：
// 先 getLoginUser（未登录 → NOT_LOGIN），再角色比对（非 admin → NO_AUTH）。
func (h *Handler) requireAdmin(c *gin.Context) {
	u, ok := h.currentUser(c)
	if !ok {
		c.AbortWithStatusJSON(http.StatusOK, httpkit.Fail(httpkit.CodeNotLogin))
		return
	}
	if u.UserRole == nil || *u.UserRole != AdminRole {
		c.AbortWithStatusJSON(http.StatusOK, httpkit.Fail(httpkit.CodeNoAuth))
		return
	}
}

// isAdminSession 只读 session 里的角色（不回查数据库，与 UserService.isAdmin 一致）。
func (h *Handler) isAdminSession(c *gin.Context) bool {
	sid, err := c.Cookie(CookieName)
	if err != nil || sid == "" {
		return false
	}
	su, ok := h.session.Get(sid)
	return ok && su.UserRole == AdminRole
}

// MD5Hex 对齐 Spring DigestUtils.md5DigestAsHex / hutool DigestUtil.md5Hex（小写 hex）。
func MD5Hex(input string) string {
	sum := md5.Sum([]byte(input))
	return hex.EncodeToString(sum[:])
}

// ---------------------------------------------------------------- /user：注册与登录

type registerRequest struct {
	UserAccount  *string `json:"userAccount"`
	UserPassword *string `json:"userPassword"`
	CheckPassword *string `json:"checkPassword"`
}

// userRegister 注册。校验文案逐条对齐 UserServiceImpl.userRegister。
func (h *Handler) userRegister(c *gin.Context) {
	var req registerRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeParams))
		return
	}
	account := deref(req.UserAccount)
	password := deref(req.UserPassword)
	check := deref(req.CheckPassword)
	// Java 版此处 `return null` → 200 空 body；空 body 对前端等价于失败，这里同样空体返回。
	if account == "" || password == "" || check == "" {
		c.Status(http.StatusOK)
		return
	}
	if len(account) < 4 {
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeParams, "用户账号过短"))
		return
	}
	if len(password) < 8 || len(check) < 8 {
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeParams, "用户密码过短"))
		return
	}
	if password != check {
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeParams, "两次输入的密码不一致"))
		return
	}
	ctx := c.Request.Context()
	if existing, err := h.repo.UserGetByAccount(ctx, account); err == nil && existing != nil {
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeParams, "账号重复"))
		return
	} else if err != nil && err != ErrNotFound {
		h.log.Error("注册查重失败", "err", err)
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeSystem, "注册失败，数据库错误"))
		return
	}
	encrypt := MD5Hex(Salt + password)
	role := "user"
	avatar := ""
	ak := MD5Hex(Salt + account + randomDigits(5))
	sk := MD5Hex(Salt + account + randomDigits(8))
	u := &User{
		UserName: &account, UserAccount: &account, UserAvatar: &avatar,
		UserRole: &role, UserPassword: &encrypt, AccessKey: &ak, SecretKey: &sk,
	}
	id, err := h.repo.UserInsert(ctx, u)
	if err != nil {
		h.log.Error("注册落库失败", "err", err)
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeSystem, "注册失败，数据库错误"))
		return
	}
	c.JSON(http.StatusOK, httpkit.OK(id))
}

type loginRequest struct {
	UserAccount  *string `json:"userAccount"`
	UserPassword *string `json:"userPassword"`
}

// userLogin 登录：口令摘要比对成功后写会话 cookie，返回完整用户实体（与 Java 一致，含 AK/SK）。
func (h *Handler) userLogin(c *gin.Context) {
	var req loginRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeParams))
		return
	}
	account := deref(req.UserAccount)
	password := deref(req.UserPassword)
	if account == "" || password == "" {
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeParams, "参数为空"))
		return
	}
	if len(account) < 4 {
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeParams, "账号错误"))
		return
	}
	if len(password) < 8 {
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeParams, "密码错误"))
		return
	}
	ctx := c.Request.Context()
	u, err := h.repo.UserLoginMatch(ctx, account, MD5Hex(Salt+password))
	if err == ErrNotFound {
		h.log.Info("user login failed, userAccount cannot match userPassword")
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeParams, "用户不存在或密码错误"))
		return
	} else if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	role := deref(u.UserRole)
	sid := h.session.Create(SessionUser{ID: derefInt64(u.ID), UserRole: role})
	c.SetCookie(CookieName, sid, 0, cookiePath, "", false, true)
	c.JSON(http.StatusOK, httpkit.OK(u))
}

// userLogout 注销：销毁会话。未登录时返回 OPERATION_ERROR「未登录」（对齐 Java）。
func (h *Handler) userLogout(c *gin.Context) {
	sid, err := c.Cookie(CookieName)
	if err != nil || sid == "" {
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeOperation, "未登录"))
		return
	}
	if _, ok := h.session.Get(sid); !ok {
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeOperation, "未登录"))
		return
	}
	h.session.Remove(sid)
	c.SetCookie(CookieName, "", -1, cookiePath, "", false, true)
	c.JSON(http.StatusOK, httpkit.OK(true))
}

// userGetLogin 当前登录用户（脱敏 VO）。
func (h *Handler) userGetLogin(c *gin.Context) {
	u, ok := h.currentUser(c)
	if !ok {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeNotLogin))
		return
	}
	c.JSON(http.StatusOK, httpkit.OK(u.ToVO()))
}

// ---------------------------------------------------------------- /user：CRUD

type userAddRequest struct {
	UserName     *string `json:"userName"`
	UserAccount  *string `json:"userAccount"`
	UserAvatar   *string `json:"userAvatar"`
	Gender       *int    `json:"gender"`
	UserRole     *string `json:"userRole"`
	UserPassword *string `json:"userPassword"`
}

// userAdd 创建用户。注意：Java 版此处既不校验登录态也不做口令摘要，照抄。
func (h *Handler) userAdd(c *gin.Context) {
	var req userAddRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeParams))
		return
	}
	u := &User{
		UserName: req.UserName, UserAccount: req.UserAccount, UserAvatar: req.UserAvatar,
		Gender: req.Gender, UserRole: req.UserRole, UserPassword: req.UserPassword,
	}
	id, err := h.repo.UserInsert(c.Request.Context(), u)
	if err != nil {
		h.log.Error("新增用户失败", "err", err)
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeOperation))
		return
	}
	c.JSON(http.StatusOK, httpkit.OK(id))
}

type deleteRequest struct {
	ID *int64 `json:"id"`
}

// userDelete 删除用户（逻辑删除）。Java 版删除后会经 MQ 通知网关失效缓存；
// Go 版暂只打告警（网关 Redis 缓存 30 分钟 TTL 自愈），P5 切换时接入 MQ。
func (h *Handler) userDelete(c *gin.Context) {
	var req deleteRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.ID == nil || *req.ID <= 0 {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeParams))
		return
	}
	ctx := c.Request.Context()
	old, _ := h.repo.UserGetByID(ctx, *req.ID)
	ok, err := h.repo.UserRemoveByID(ctx, *req.ID)
	if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	if ok && old != nil && old.AccessKey != nil {
		h.log.Warn("缓存失效通知未发送（P5 切换时接入 MQ）", "cache_key", "user:ak:"+*old.AccessKey)
	}
	c.JSON(http.StatusOK, httpkit.OK(ok))
}

type userUpdateRequest struct {
	ID           *int64  `json:"id"`
	UserName     *string `json:"userName"`
	UserAccount  *string `json:"userAccount"`
	UserAvatar   *string `json:"userAvatar"`
	Gender       *int    `json:"gender"`
	UserRole     *string `json:"userRole"`
	UserPassword *string `json:"userPassword"`
}

// userUpdate 更新用户（只更新提交的非空字段）。
func (h *Handler) userUpdate(c *gin.Context) {
	var req userUpdateRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.ID == nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeParams))
		return
	}
	u := &User{
		ID: req.ID, UserName: req.UserName, UserAccount: req.UserAccount, UserAvatar: req.UserAvatar,
		Gender: req.Gender, UserRole: req.UserRole, UserPassword: req.UserPassword,
	}
	ok, err := h.repo.UserUpdateByID(c.Request.Context(), u)
	if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	if ok {
		h.log.Warn("缓存失效通知未发送（P5 切换时接入 MQ）", "user_id", *req.ID)
	}
	c.JSON(http.StatusOK, httpkit.OK(ok))
}

// userGet 按 id 获取用户（VO）。Java 版查不到时 NPE → 500 SYSTEM_ERROR，这里同样给系统错误。
func (h *Handler) userGet(c *gin.Context) {
	id, err := strconv.ParseInt(c.Query("id"), 10, 64)
	if err != nil || id <= 0 {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeParams))
		return
	}
	u, err := h.repo.UserGetByID(c.Request.Context(), id)
	if err == ErrNotFound {
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeSystem, "用户不存在"))
		return
	} else if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	c.JSON(http.StatusOK, httpkit.OK(u.ToVO()))
}

// userQueryFromRequest 收集 GET 查询参数里的等值条件（Spring 按 setter 绑定，缺省 null）。
func userQueryFromRequest(c *gin.Context) *UserQuery {
	q := &UserQuery{}
	if v := c.Query("id"); v != "" {
		if id, err := strconv.ParseInt(v, 10, 64); err == nil {
			q.ID = &id
		}
	}
	if v := c.Query("userName"); v != "" {
		q.UserName = &v
	}
	if v := c.Query("userAccount"); v != "" {
		q.UserAccount = &v
	}
	if v := c.Query("userRole"); v != "" {
		q.UserRole = &v
	}
	if v := c.Query("gender"); v != "" {
		if g, err := strconv.Atoi(v); err == nil {
			q.Gender = &g
		}
	}
	return q
}

// userList 用户列表（VO）。
func (h *Handler) userList(c *gin.Context) {
	users, err := h.repo.UserList(c.Request.Context(), userQueryFromRequest(c))
	if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	vos := make([]*UserVO, 0, len(users))
	for _, u := range users {
		vos = append(vos, u.ToVO())
	}
	c.JSON(http.StatusOK, httpkit.OK(vos))
}

// pageResponse 是 MyBatis-Plus Page 序列化形态的子集（records/total/size/current/pages），
// 管理台只消费这些字段。
type pageResponse struct {
	Records any   `json:"records"`
	Total   int64 `json:"total"`
	Size    int64 `json:"size"`
	Current int64 `json:"current"`
	Pages   int64 `json:"pages"`
	Orders  []any `json:"orders"`
}

func newPage(records any, total, current, size int64) *pageResponse {
	pages := int64(0)
	if size > 0 {
		pages = (total + size - 1) / size
	}
	return &pageResponse{Records: records, Total: total, Size: size, Current: current, Pages: pages, Orders: []any{}}
}

// pageParams 解析分页参数（PageRequest 默认 current=1 / pageSize=10）。
func pageParams(c *gin.Context) (current, size int64) {
	current, size = 1, 10
	if v := c.Query("current"); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil && n > 0 {
			current = n
		}
	}
	if v := c.Query("pageSize"); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil && n > 0 {
			size = n
		}
	}
	return current, size
}

// userListPage 分页用户列表（VO）。
func (h *Handler) userListPage(c *gin.Context) {
	current, size := pageParams(c)
	q := userQueryFromRequest(c)
	offset := (current - 1) * size
	rows, err := h.repo.UserPageRows(c.Request.Context(), q, int(offset), int(size))
	if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	total, err := h.repo.UserPageCount(c.Request.Context(), q)
	if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	vos := make([]*UserVO, 0, len(rows))
	for _, u := range rows {
		vos = append(vos, u.ToVO())
	}
	c.JSON(http.StatusOK, httpkit.OK(newPage(vos, total, current, size)))
}

// validSortField 排序字段白名单（Java 版 MP orderBy 直拼列名有注入面，这里收紧为标识符）。
func validSortField(f string) bool {
	if f == "" || len(f) > 64 {
		return false
	}
	for _, r := range f {
		if !(r == '_' || (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9')) {
			return false
		}
	}
	return true
}
