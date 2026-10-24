// Package innerapi 实现服务间内部接口（供网关注入鉴权所需数据）。
//
// 移植自 InnerApiController。调用方是 aismp-gateway 的 CustomGlobalFilter（同机 HTTP）：
//
//	GET  /api/inner/user/byAccessKey?accessKey=          网关鉴权第一步
//	GET  /api/inner/interfaceInfo/byPathMethod?path=&method=  第二步，未登记直接 403
//	POST /api/inner/userInterfaceInfo/invokeCount        MQ 发送失败时的同步降级计数
//
// 安全说明：这些接口只监听同机回环，不经过网关对外暴露；
// 返回体含 secretKey，属于内部凭据，绝不能对外开放。
package innerapi

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"

	"github.com/aismp/platform/internal/httpkit"
)

// InvokeUser 对应 `user` 表一行（yuapi 时代的调用方账号，gateway 用其 AK/SK）。
// JSON 字段名 = Jackson 序列化 User 实体的驼峰输出；列名同名（map-underscore-to-camel-case: false）。
type InvokeUser struct {
	ID           int64      `json:"id"`
	UserName     *string    `json:"userName"`
	UserAccount  *string    `json:"userAccount"`
	UserAvatar   *string    `json:"userAvatar"`
	Gender       *int       `json:"gender"`
	UserRole     *string    `json:"userRole"`
	UserPassword *string    `json:"userPassword"`
	AccessKey    *string    `json:"accessKey"`
	SecretKey    *string    `json:"secretKey"`
	CreateTime   *string    `json:"createTime"`
	UpdateTime   *string    `json:"updateTime"`
	IsDelete     *int       `json:"isDelete"`
}

// InterfaceInfo 对应 `interface_info` 一行（网关按 url+method 决定请求是否放行）。
type InterfaceInfo struct {
	ID             *int64   `json:"id"`
	Name           *string  `json:"name"`
	Description    *string  `json:"description"`
	URL            *string  `json:"url"`
	RequestParams  *string  `json:"requestParams"`
	RequestHeader  *string  `json:"requestHeader"`
	ResponseHeader *string  `json:"responseHeader"`
	Status         *int     `json:"status"`
	Method         *string  `json:"method"`
	UserID         *int64   `json:"userId"`
	CreateTime     *string  `json:"createTime"`
	UpdateTime     *string  `json:"updateTime"`
	IsDelete       *int     `json:"isDelete"`
}

// Repository 是 inner 查询的仓储。
type Repository struct {
	db *sql.DB
}

// NewRepository 构造仓储。
func NewRepository(db *sql.DB) *Repository { return &Repository{db: db} }

// ErrNotFound 表示按条件查不到记录（Java 返回 null 的分支）。
var ErrNotFound = errors.New("innerapi: 记录不存在")

// MyBatis-Plus 全局逻辑删除：所有查询自动追加 isDelete = 0。
const notDeleted = " AND isDelete = 0"

const userColumns = `id, userName, userAccount, userAvatar, gender, userRole,
	userPassword, accessKey, secretKey, createTime, updateTime, isDelete`

// FindUserByAccessKey 按 accessKey 查调用方账号。
func (r *Repository) FindUserByAccessKey(ctx context.Context, accessKey string) (*InvokeUser, error) {
	row := r.db.QueryRowContext(ctx,
		`SELECT `+userColumns+` FROM user WHERE accessKey = ?`+notDeleted, accessKey)
	u, err := scanUser(row)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("innerapi: 查账号失败: %w", err)
	}
	return u, nil
}

func scanUser(row interface{ Scan(...any) error }) (*InvokeUser, error) {
	var u InvokeUser
	var createTime, updateTime sql.NullTime
	err := row.Scan(&u.ID, &u.UserName, &u.UserAccount, &u.UserAvatar, &u.Gender, &u.UserRole,
		&u.UserPassword, &u.AccessKey, &u.SecretKey, &createTime, &updateTime, &u.IsDelete)
	if err != nil {
		return nil, err
	}
	u.CreateTime = isoDatetime(createTime)
	u.UpdateTime = isoDatetime(updateTime)
	return &u, nil
}

const interfaceColumns = `id, name, description, url, requestParams, requestHeader,
	responseHeader, status, method, userId, createTime, updateTime, isDelete`

// FindInterfaceExact 精确匹配（url = ? AND method = ?），走索引等值查询，存量接口全部走这条路径。
func (r *Repository) FindInterfaceExact(ctx context.Context, url, method string) (*InterfaceInfo, error) {
	row := r.db.QueryRowContext(ctx,
		`SELECT `+interfaceColumns+` FROM interface_info WHERE url = ? AND method = ?`+notDeleted,
		url, method)
	info, err := scanInterface(row)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("innerapi: 查接口失败: %w", err)
	}
	return info, nil
}

// FindInterfaceWildcardCandidates 捞出登记里含 * 的同 method 候选行，供逐条 Ant 匹配。
// Java 侧 like("url", "*") 生成 LIKE '%*%'。
func (r *Repository) FindInterfaceWildcardCandidates(ctx context.Context, method string) ([]*InterfaceInfo, error) {
	rows, err := r.db.QueryContext(ctx,
		`SELECT `+interfaceColumns+` FROM interface_info WHERE method = ? AND url LIKE '%*%'`+notDeleted,
		method)
	if err != nil {
		return nil, fmt.Errorf("innerapi: 查通配接口失败: %w", err)
	}
	defer rows.Close()
	var list []*InterfaceInfo
	for rows.Next() {
		info, err := scanInterface(rows)
		if err != nil {
			return nil, fmt.Errorf("innerapi: 读接口行失败: %w", err)
		}
		list = append(list, info)
	}
	return list, rows.Err()
}

func scanInterface(row interface{ Scan(...any) error }) (*InterfaceInfo, error) {
	var i InterfaceInfo
	var createTime, updateTime sql.NullTime
	err := row.Scan(&i.ID, &i.Name, &i.Description, &i.URL, &i.RequestParams, &i.RequestHeader,
		&i.ResponseHeader, &i.Status, &i.Method, &i.UserID, &createTime, &updateTime, &i.IsDelete)
	if err != nil {
		return nil, err
	}
	i.CreateTime = isoDatetime(createTime)
	i.UpdateTime = isoDatetime(updateTime)
	return &i, nil
}

// isoDatetime 输出 Jackson 等价的 ISO 串（LocalDateTime → "2006-01-02T15:04:05"）。
func isoDatetime(t sql.NullTime) *string {
	if !t.Valid {
		return nil
	}
	s := t.Time.Format("2006-01-02T15:04:05")
	return &s
}

// InvokeCount 调用次数 +1（leftNum > 0 才扣，返回是否扣成功）。
// SQL 与 UserInterfaceInfoServiceImpl.invokeCount 的 UpdateWrapper 一致。
func (r *Repository) InvokeCount(ctx context.Context, interfaceInfoID, userID int64) (bool, error) {
	res, err := r.db.ExecContext(ctx, `
		UPDATE user_interface_info
		   SET leftNum = leftNum - 1, totalNum = totalNum + 1
		 WHERE interfaceInfoId = ? AND userId = ? AND leftNum > 0`+notDeleted,
		interfaceInfoID, userID)
	if err != nil {
		return false, fmt.Errorf("innerapi: 计数失败: %w", err)
	}
	n, err := res.RowsAffected()
	if err != nil {
		return false, fmt.Errorf("innerapi: 计数结果读取失败: %w", err)
	}
	return n > 0, nil
}

// Handler 承载 /inner 下的三个端点。
type Handler struct {
	repo *Repository
}

// NewHandler 构造处理器。
func NewHandler(repo *Repository) *Handler { return &Handler{repo: repo} }

// Register 挂载路由（等价于 @RequestMapping("/inner")）。
func (h *Handler) Register(r gin.IRouter) {
	g := r.Group("/inner")
	g.GET("/user/byAccessKey", h.userByAccessKey)
	g.GET("/interfaceInfo/byPathMethod", h.interfaceByPathMethod)
	g.POST("/userInterfaceInfo/invokeCount", h.invokeCount)
}

// userByAccessKey 按 accessKey 查调用方用户（网关鉴权第一步）。
// Java 语义：accessKey 为空 → BusinessException(PARAMS_ERROR)（HTTP 200 + BaseResponse）；
// 查不到 → 200 + null。网关两边都按 null 处理。
func (h *Handler) userByAccessKey(c *gin.Context) {
	accessKey := strings.TrimSpace(c.Query("accessKey"))
	if accessKey == "" {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeParams))
		return
	}
	u, err := h.repo.FindUserByAccessKey(c.Request.Context(), accessKey)
	if errors.Is(err, ErrNotFound) {
		c.JSON(http.StatusOK, nil)
		return
	}
	if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	c.JSON(http.StatusOK, u)
}

// interfaceByPathMethod 按「路径 + 方法」查接口登记：精确优先，Ant 通配兜底。
func (h *Handler) interfaceByPathMethod(c *gin.Context) {
	path := c.Query("path")
	method := c.Query("method")
	if path == "" || method == "" {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeParams))
		return
	}
	ctx := c.Request.Context()
	info, err := h.repo.FindInterfaceExact(ctx, path, method)
	if errors.Is(err, ErrNotFound) {
		candidates, err := h.repo.FindInterfaceWildcardCandidates(ctx, method)
		if err != nil {
			c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
			return
		}
		for _, candidate := range candidates {
			if AntMatch(deref(candidate.URL), path) {
				info = candidate
				break
			}
		}
	}
	if err != nil && !errors.Is(err, ErrNotFound) {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	if info == nil {
		c.JSON(http.StatusOK, nil)
		return
	}
	c.JSON(http.StatusOK, info)
}

// invokeCount 同步计数（网关 MQ 失败时的降级通道）；任何异常都吞掉返回 false，与 Java 一致。
func (h *Handler) invokeCount(c *gin.Context) {
	interfaceInfoID, err1 := strconv.ParseInt(c.Query("interfaceInfoId"), 10, 64)
	userID, err2 := strconv.ParseInt(c.Query("userId"), 10, 64)
	if err1 != nil || err2 != nil || interfaceInfoID <= 0 || userID <= 0 {
		c.JSON(http.StatusOK, false)
		return
	}
	ok, err := h.repo.InvokeCount(c.Request.Context(), interfaceInfoID, userID)
	if err != nil {
		c.JSON(http.StatusOK, false)
		return
	}
	c.JSON(http.StatusOK, ok)
}

func deref[T any](p *T) T {
	if p == nil {
		var zero T
		return zero
	}
	return *p
}

// AntMatch 是 Spring AntPathMatcher 的子集实现（* / ** / ?），供接口登记通配匹配。
// 登记表里只出现 /a/**、/a/*/b 两种形态，这里按「/」分段匹配。
func AntMatch(pattern, path string) bool {
	return matchSegments(strings.Split(pattern, "/"), strings.Split(path, "/"))
}

func matchSegments(pattern, path []string) bool {
	switch {
	case len(pattern) == 0:
		return len(path) == 0
	case pattern[0] == "**":
		// ** 可吞掉任意多段（含零段）
		for i := 0; i <= len(path); i++ {
			if matchSegments(pattern[1:], path[i:]) {
				return true
			}
		}
		return false
	case len(path) == 0:
		return false
	case matchSingleSegment(pattern[0], path[0]):
		return matchSegments(pattern[1:], path[1:])
	default:
		return false
	}
}

// matchSingleSegment 处理段内通配：* 任意串、? 单字符。
func matchSingleSegment(pattern, s string) bool {
	return matchChars(pattern, s)
}

func matchChars(pattern, s string) bool {
	// 递归双指针：* 贪心回溯，? 单字符，其余精确相等
	if pattern == "" {
		return s == ""
	}
	switch pattern[0] {
	case '*':
		for i := 0; i <= len(s); i++ {
			if matchChars(pattern[1:], s[i:]) {
				return true
			}
		}
		return false
	case '?':
		return s != "" && matchChars(pattern[1:], s[1:])
	default:
		return s != "" && pattern[0] == s[0] && matchChars(pattern[1:], s[1:])
	}
}
