// market 处理器（二）：/interfaceInfo/** 与 /userInterfaceInfo/**。
//
// 移植自 InterfaceinfoController 与 UserInterfaceinfoController。
// 前者是接口元数据管理（发布/下线/测试调用走网关 AK/SK 签名），
// 后者是用户接口额度管理（全部 @AuthCheck(admin)）。
package market

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/aismp/platform/internal/httpkit"
)

// 接口状态（InrerfaceInfoStatusEnum）。
const (
	statusOnline  = 1
	statusOffline = 0
)

// ---------------------------------------------------------------- /interfaceInfo

type interfaceAddRequest struct {
	Name           *string `json:"name"`
	Description    *string `json:"description"`
	URL            *string `json:"url"`
	RequestParams  *string `json:"requestParams"`
	RequestHeader  *string `json:"requestHeader"`
	ResponseHeader *string `json:"responseHeader"`
	Method         *string `json:"method"`
}

// validInterfaceInfo 对齐 InterfaceInfoServiceImpl.validInterfaceInfo。
func validInterfaceInfo(name *string, add bool) (string, bool) {
	n := deref(name)
	if add && strings.TrimSpace(n) == "" {
		return httpkit.CodeParams.Message(), false
	}
	if n != "" && len([]rune(n)) > 50 {
		return "名称过长", false
	}
	return "", true
}

// interfaceAdd 创建接口登记；归属人为当前登录用户。
func (h *Handler) interfaceAdd(c *gin.Context) {
	var req interfaceAddRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeParams))
		return
	}
	if msg, ok := validInterfaceInfo(req.Name, true); !ok {
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeParams, msg))
		return
	}
	owner, _ := h.currentUser(c)
	ownerID := int64(0)
	if owner != nil && owner.ID != nil {
		ownerID = *owner.ID
	}
	info := &InterfaceInfo{
		Name: req.Name, Description: req.Description, URL: req.URL,
		RequestParams: req.RequestParams, RequestHeader: req.RequestHeader,
		ResponseHeader: req.ResponseHeader, Method: req.Method, UserID: &ownerID,
	}
	id, err := h.repo.InterfaceInsert(c.Request.Context(), info)
	if err != nil {
		h.log.Error("新增接口失败", "err", err)
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeOperation))
		return
	}
	c.JSON(http.StatusOK, httpkit.OK(id))
}

// interfaceDelete 删除：仅本人或管理员。
func (h *Handler) interfaceDelete(c *gin.Context) {
	var req deleteRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.ID == nil || *req.ID <= 0 {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeParams))
		return
	}
	ctx := c.Request.Context()
	user, _ := h.currentUser(c)
	old, err := h.repo.InterfaceGetByID(ctx, *req.ID)
	if err == ErrNotFound {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeNotFound))
		return
	} else if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	if !ownsOrAdmin(user, old.UserID, h.isAdminSession(c)) {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeNoAuth))
		return
	}
	ok, err := h.repo.InterfaceRemoveByID(ctx, *req.ID)
	if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	c.JSON(http.StatusOK, httpkit.OK(ok))
}

type interfaceUpdateRequest struct {
	ID             *int64  `json:"id"`
	Name           *string `json:"name"`
	Description    *string `json:"description"`
	URL            *string `json:"url"`
	RequestHeader  *string `json:"requestHeader"`
	ResponseHeader *string `json:"responseHeader"`
	Status         *int    `json:"status"`
	Method         *string `json:"method"`
}

// interfaceUpdate 更新：仅本人或管理员，只更新提交的非空字段。
func (h *Handler) interfaceUpdate(c *gin.Context) {
	var req interfaceUpdateRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.ID == nil || *req.ID <= 0 {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeParams))
		return
	}
	if msg, ok := validInterfaceInfo(req.Name, false); !ok {
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeParams, msg))
		return
	}
	ctx := c.Request.Context()
	user, _ := h.currentUser(c)
	old, err := h.repo.InterfaceGetByID(ctx, *req.ID)
	if err == ErrNotFound {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeNotFound))
		return
	} else if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	if !ownsOrAdmin(user, old.UserID, h.isAdminSession(c)) {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeNoAuth))
		return
	}
	info := &InterfaceInfo{
		ID: req.ID, Name: req.Name, Description: req.Description, URL: req.URL,
		RequestHeader: req.RequestHeader, ResponseHeader: req.ResponseHeader,
		Status: req.Status, Method: req.Method,
	}
	ok, err := h.repo.InterfaceUpdateByID(ctx, info)
	if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	c.JSON(http.StatusOK, httpkit.OK(ok))
}

// ownsOrAdmin 仅本人或管理员可动。
func ownsOrAdmin(user *User, ownerID *int64, isAdmin bool) bool {
	if isAdmin {
		return true
	}
	if user == nil || user.ID == nil || ownerID == nil {
		return false
	}
	return *user.ID == *ownerID
}

// interfaceGet 按 id 获取（Java 版查不到返回 data=null，不报错）。
func (h *Handler) interfaceGet(c *gin.Context) {
	id, err := strconv.ParseInt(c.Query("id"), 10, 64)
	if err != nil || id <= 0 {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeParams))
		return
	}
	info, err := h.repo.InterfaceGetByID(c.Request.Context(), id)
	if err == ErrNotFound {
		c.JSON(http.StatusOK, httpkit.OK(nil))
		return
	} else if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	c.JSON(http.StatusOK, httpkit.OK(info))
}

// interfaceQueryFromRequest 收集 GET 等值条件。
func interfaceQueryFromRequest(c *gin.Context) *InterfaceQuery {
	q := &InterfaceQuery{}
	bindInt64(c, "id", func(v int64) { q.ID = &v })
	bindStr(c, "name", func(v string) { q.Name = &v })
	bindStr(c, "description", func(v string) { q.Description = &v })
	bindStr(c, "url", func(v string) { q.URL = &v })
	bindStr(c, "method", func(v string) { q.Method = &v })
	bindInt(c, "status", func(v int) { q.Status = &v })
	bindInt64(c, "userId", func(v int64) { q.UserID = &v })
	return q
}

func bindStr(c *gin.Context, key string, set func(string)) {
	if v := c.Query(key); v != "" {
		set(v)
	}
}

func bindInt(c *gin.Context, key string, set func(int)) {
	if v := c.Query(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			set(n)
		}
	}
}

func bindInt64(c *gin.Context, key string, set func(int64)) {
	if v := c.Query(key); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil {
			set(n)
		}
	}
}

// interfaceList 接口列表（仅管理员）。
func (h *Handler) interfaceList(c *gin.Context) {
	list, err := h.repo.InterfaceList(c.Request.Context(), interfaceQueryFromRequest(c))
	if err != nil {
		if strings.Contains(err.Error(), "Unknown column") {
			c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeSystem, err.Error()))
			return
		}
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	c.JSON(http.StatusOK, httpkit.OK(list))
}

// orderExprFromRequest 解析 sortField/sortOrder（ascend 升序、其余降序，CommonConstant）。
func orderExprFromRequest(c *gin.Context) (string, bool) {
	sortField := c.Query("sortField")
	if sortField == "" {
		return "", true
	}
	if !validSortField(sortField) {
		return "", false
	}
	dir := "DESC"
	if c.Query("sortOrder") == "ascend" {
		dir = "ASC"
	}
	return sortField + " " + dir, true
}

// interfaceListPage 分页接口列表；pageSize>50 拒绝（防爬虫，与 Java 一致）。
func (h *Handler) interfaceListPage(c *gin.Context) {
	current, size := pageParams(c)
	if size > 50 {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeParams))
		return
	}
	orderExpr, ok := orderExprFromRequest(c)
	if !ok {
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeParams, "非法排序字段"))
		return
	}
	q := interfaceQueryFromRequest(c)
	likeDescription := deref(q.Description)
	q.Description = nil
	offset := (current - 1) * size
	rows, total, err := h.repo.InterfacePage(c.Request.Context(), q, int(offset), int(size), orderExpr, likeDescription)
	if err != nil {
		// 与 Java 同款行为：描述模糊搜索引用了不存在的 content 列，SQL 报错 → SYSTEM_ERROR。
		h.log.Warn("接口分页查询失败", "err", err)
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeSystem, err.Error()))
		return
	}
	c.JSON(http.StatusOK, httpkit.OK(newPage(rows, total, current, size)))
}

type idRequest struct {
	ID *int64 `json:"id"`
}

// pathOf 提取 URL 的 path 部分（对齐 new java.net.URI(url).getPath()）。
func pathOf(raw string) (string, error) {
	u, err := url.Parse(raw)
	if err != nil {
		return "", err
	}
	return u.Path, nil
}

// invokeViaGateway 对齐 OpenApiClient.invokeApi：
// GET → body JSON 转查询参数；POST → body 原样；签名头见 signHeaders。
// 只在传输层出错时返回错误；HTTP 4xx/5xx 不算失败（hutool 同款语义）。
func (h *Handler) invokeViaGateway(ak, sk, path, method, body string) (string, error) {
	target := h.GatewayBaseURL + path
	method = strings.ToUpper(method)
	var req *http.Request
	var err error
	if method == "GET" {
		targetURL, err := url.Parse(target)
		if err != nil {
			return "", err
		}
		if strings.TrimSpace(body) != "" {
			var params map[string]any
			if err := json.Unmarshal([]byte(body), &params); err != nil {
				return "", fmt.Errorf("body 需为 JSON 对象: %w", err)
			}
			qs := targetURL.Query()
			for k, v := range params {
				qs.Set(k, fmt.Sprintf("%v", v))
			}
			targetURL.RawQuery = qs.Encode()
		}
		req, err = http.NewRequest(http.MethodGet, targetURL.String(), nil)
	} else {
		var payload io.Reader
		if body != "" {
			payload = strings.NewReader(body)
		}
		req, err = http.NewRequest(http.MethodPost, target, payload)
	}
	if err != nil {
		return "", err
	}
	for k, v := range signHeaders(body, ak, sk) {
		req.Header.Set(k, v)
	}
	resp, err := h.httpClient.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", err
	}
	return string(data), nil
}

// signHeaders 构造网关要求的签名头。body 为空串表示「无 body」：
// Java 侧 null 参与拼接得到 "null."+sk，网关对无 body 请求同样按 null 拼接。
func signHeaders(body, ak, sk string) map[string]string {
	payload := body
	if payload == "" {
		payload = "null"
	}
	return map[string]string{
		"accessKey": ak,
		"nonce":     RandomHex32(),
		"timestamp": strconv.FormatInt(time.Now().Unix(), 10),
		"body":      payload,
		"sign":      Sign(payload, sk),
	}
}

// Sign 对齐 SignUtils.getSign：SHA256(body + "." + secretKey) 的小写 hex。
func Sign(body, secretKey string) string {
	sum := sha256.Sum256([]byte(body + "." + secretKey))
	return hex.EncodeToString(sum[:])
}

// interfaceOnline 发布：经网关对接口自身做一次连通性探测，通过才置上线。
func (h *Handler) interfaceOnline(c *gin.Context) {
	var req idRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.ID == nil || *req.ID <= 0 {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeParams))
		return
	}
	ctx := c.Request.Context()
	old, err := h.repo.InterfaceGetByID(ctx, *req.ID)
	if err == ErrNotFound {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeNotFound))
		return
	} else if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	probePath, err := pathOf(deref(old.URL))
	if err != nil {
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeParams, "接口地址格式错误"))
		return
	}
	if _, err := h.invokeViaGateway(h.ClientAccessKey, h.ClientSecretKey,
		probePath, deref(old.Method), ""); err != nil {
		h.log.Warn("接口连通性校验失败", "path", probePath, "err", err)
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeSystem, "接口验证失败"))
		return
	}
	info := &InterfaceInfo{ID: req.ID, Status: ptrInt(statusOnline)}
	ok, err := h.repo.InterfaceUpdateByID(ctx, info)
	if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	c.JSON(http.StatusOK, httpkit.OK(ok))
}

// interfaceOffline 下线：置状态并通知网关失效接口缓存（Go 版暂只告警，见包注释）。
func (h *Handler) interfaceOffline(c *gin.Context) {
	var req idRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.ID == nil || *req.ID <= 0 {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeParams))
		return
	}
	ctx := c.Request.Context()
	old, err := h.repo.InterfaceGetByID(ctx, *req.ID)
	if err == ErrNotFound {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeNotFound))
		return
	} else if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	info := &InterfaceInfo{ID: req.ID, Status: ptrInt(statusOffline)}
	ok, err := h.repo.InterfaceUpdateByID(ctx, info)
	if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	if ok && old.URL != nil && old.Method != nil {
		path := deref(old.URL)
		if p, err := pathOf(deref(old.URL)); err == nil {
			path = p
		}
		h.log.Warn("缓存失效通知未发送（P5 切换时接入 MQ）",
			"cache_key", "interface:"+path+":"+deref(old.Method))
	}
	c.JSON(http.StatusOK, httpkit.OK(ok))
}

type interfaceInvokeRequest struct {
	ID                *int64  `json:"id"`
	UserRequestParams *string `json:"userRequestParams"`
}

// interfaceInvoke 测试调用：用当前登录用户自己的 AK/SK 经网关发起真实调用。
func (h *Handler) interfaceInvoke(c *gin.Context) {
	var req interfaceInvokeRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.ID == nil || *req.ID <= 0 {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeParams))
		return
	}
	ctx := c.Request.Context()
	old, err := h.repo.InterfaceGetByID(ctx, *req.ID)
	if err == ErrNotFound {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeNotFound))
		return
	} else if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	if old.Status != nil && *old.Status == statusOffline {
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeOperation, "接口已关闭"))
		return
	}
	user, _ := h.currentUser(c)
	path, err := pathOf(deref(old.URL))
	if err != nil {
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeParams, "接口地址格式错误"))
		return
	}
	body := deref(req.UserRequestParams)
	if body == "null" {
		body = ""
	}
	result, err := h.invokeViaGateway(deref(user.AccessKey), deref(user.SecretKey),
		path, deref(old.Method), body)
	if err != nil {
		h.log.Warn("测试调用失败", "path", path, "err", err)
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeSystem, err.Error()))
		return
	}
	c.JSON(http.StatusOK, httpkit.OK(result))
}

// ---------------------------------------------------------------- /userInterfaceInfo

type quotaAddRequest struct {
	UserID          *int64 `json:"userId"`
	InterfaceInfoID *int64 `json:"interfaceInfoId"`
	TotalNum        *int   `json:"totalNum"`
	LeftNum         *int   `json:"leftNum"`
}

// validQuota 对齐 validUserInterfaceInfo：创建时接口与用户必填，剩余次数非负。
func validQuota(interfaceInfoID, userID *int64, leftNum *int, add bool) (string, httpkit.Code, bool) {
	if add {
		if interfaceInfoID == nil || *interfaceInfoID <= 0 || userID == nil || *userID <= 0 {
			return "接口或用户不存在", httpkit.CodeParams, false
		}
	}
	if leftNum != nil && *leftNum < 0 {
		return "剩余次数不能小于 0", httpkit.CodeParams, false
	}
	if leftNum == nil {
		// Java 侧 null 拆箱 NPE → 500 SYSTEM_ERROR，这里给出等价错误码。
		return "剩余次数不能为空", httpkit.CodeSystem, false
	}
	return "", httpkit.CodeSuccess, true
}

// quotaAdd 创建额度行（管理员），归属人为当前登录用户。
func (h *Handler) quotaAdd(c *gin.Context) {
	var req quotaAddRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeParams))
		return
	}
	if msg, code, ok := validQuota(req.InterfaceInfoID, req.UserID, req.LeftNum, true); !ok {
		c.JSON(http.StatusOK, httpkit.FailMsg(code, msg))
		return
	}
	owner, _ := h.currentUser(c)
	ownerID := int64(0)
	if owner != nil && owner.ID != nil {
		ownerID = *owner.ID
	}
	q := &UserInterfaceInfo{
		UserID: &ownerID, InterfaceInfoID: req.InterfaceInfoID,
		TotalNum: req.TotalNum, LeftNum: req.LeftNum,
	}
	id, err := h.repo.QuotaInsert(c.Request.Context(), q)
	if err != nil {
		h.log.Error("新增额度失败", "err", err)
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeOperation))
		return
	}
	c.JSON(http.StatusOK, httpkit.OK(id))
}

// quotaDelete 删除额度行（管理员；本人或管理员双保险，与 Java 同款判断）。
func (h *Handler) quotaDelete(c *gin.Context) {
	var req deleteRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.ID == nil || *req.ID <= 0 {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeParams))
		return
	}
	ctx := c.Request.Context()
	user, _ := h.currentUser(c)
	old, err := h.repo.QuotaGetByID(ctx, *req.ID)
	if err == ErrNotFound {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeNotFound))
		return
	} else if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	if !ownsOrAdmin(user, old.UserID, h.isAdminSession(c)) {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeNoAuth))
		return
	}
	ok, err := h.repo.QuotaRemoveByID(ctx, *req.ID)
	if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	c.JSON(http.StatusOK, httpkit.OK(ok))
}

type quotaUpdateRequest struct {
	ID      *int64 `json:"id"`
	TotalNum *int  `json:"totalNum"`
	LeftNum  *int  `json:"leftNum"`
	Status   *int  `json:"status"`
	IsDelete *int  `json:"isDelete"`
}

// quotaUpdate 更新额度行（管理员）。
func (h *Handler) quotaUpdate(c *gin.Context) {
	var req quotaUpdateRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.ID == nil || *req.ID <= 0 {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeParams))
		return
	}
	if msg, code, ok := validQuota(nil, nil, req.LeftNum, false); !ok {
		c.JSON(http.StatusOK, httpkit.FailMsg(code, msg))
		return
	}
	ctx := c.Request.Context()
	user, _ := h.currentUser(c)
	old, err := h.repo.QuotaGetByID(ctx, *req.ID)
	if err == ErrNotFound {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeNotFound))
		return
	} else if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	if !ownsOrAdmin(user, old.UserID, h.isAdminSession(c)) {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeNoAuth))
		return
	}
	q := &UserInterfaceInfo{
		ID: req.ID, TotalNum: req.TotalNum, LeftNum: req.LeftNum,
		Status: req.Status, IsDelete: req.IsDelete,
	}
	ok, err := h.repo.QuotaUpdateByID(ctx, q)
	if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	c.JSON(http.StatusOK, httpkit.OK(ok))
}

// quotaGet 按 id 获取额度行。
func (h *Handler) quotaGet(c *gin.Context) {
	id, err := strconv.ParseInt(c.Query("id"), 10, 64)
	if err != nil || id <= 0 {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeParams))
		return
	}
	q, err := h.repo.QuotaGetByID(c.Request.Context(), id)
	if err == ErrNotFound {
		c.JSON(http.StatusOK, httpkit.OK(nil))
		return
	} else if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	c.JSON(http.StatusOK, httpkit.OK(q))
}

// quotaList 额度列表（管理员）。
func (h *Handler) quotaList(c *gin.Context) {
	q := &QuotaQuery{}
	bindInt64(c, "userId", func(v int64) { q.UserID = &v })
	bindInt64(c, "interfaceInfoId", func(v int64) { q.InterfaceInfoID = &v })
	bindInt(c, "totalNum", func(v int) { q.TotalNum = &v })
	bindInt(c, "leftNum", func(v int) { q.LeftNum = &v })
	bindInt(c, "status", func(v int) { q.Status = &v })
	list, err := h.repo.QuotaList(c.Request.Context(), q)
	if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	c.JSON(http.StatusOK, httpkit.OK(list))
}

// quotaListPage 分页额度列表（管理员；pageSize>50 拒绝）。
func (h *Handler) quotaListPage(c *gin.Context) {
	current, size := pageParams(c)
	if size > 50 {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeParams))
		return
	}
	orderExpr, ok := orderExprFromRequest(c)
	if !ok {
		c.JSON(http.StatusOK, httpkit.FailMsg(httpkit.CodeParams, "非法排序字段"))
		return
	}
	q := &QuotaQuery{}
	bindInt64(c, "userId", func(v int64) { q.UserID = &v })
	bindInt64(c, "interfaceInfoId", func(v int64) { q.InterfaceInfoID = &v })
	bindInt(c, "totalNum", func(v int) { q.TotalNum = &v })
	bindInt(c, "leftNum", func(v int) { q.LeftNum = &v })
	bindInt(c, "status", func(v int) { q.Status = &v })
	offset := (current - 1) * size
	rows, total, err := h.repo.QuotaPage(c.Request.Context(), q, int(offset), int(size), orderExpr)
	if err != nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
		return
	}
	c.JSON(http.StatusOK, httpkit.OK(newPage(rows, total, current, size)))
}

func ptrInt(v int) *int { return &v }
