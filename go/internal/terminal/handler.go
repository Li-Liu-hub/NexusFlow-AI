// 终端服务的 HTTP 层：/terminal/** 与 /transferOrder/**（无 context-path，
// 网关已剥掉 /api 段）。门户可见范围与写入授权以网关注入的 X-User-* 头为准
// —— 无注入头 = 服务方通道（编排层/AK-SK），保持全量语义。
package terminal

import (
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"

	"github.com/aismp/platform/internal/httpkit"
)

// Identity 是网关注入的门户身份（X-User-Id 有值才算门户通道）。
type Identity struct {
	UserCode      string
	WarehouseCode string
	Role          string
}

// identityOf 从请求头解析注入身份（对齐 InjectedIdentity.of）。
func identityOf(c *gin.Context) *Identity {
	uid := c.GetHeader("X-User-Id")
	if uid == "" {
		return nil
	}
	return &Identity{
		UserCode:      uid,
		WarehouseCode: c.GetHeader("X-User-Warehouse"),
		Role:          c.GetHeader("X-User-Role"),
	}
}

// Handler 承载终端业务全部端点。
type Handler struct {
	repo *Repository
	log  *slog.Logger
}

// NewHandler 构造处理器。
func NewHandler(repo *Repository, log *slog.Logger) *Handler {
	return &Handler{repo: repo, log: log}
}

// Register 挂载路由。
func (h *Handler) Register(r gin.IRouter) {
	t := r.Group("/terminal")
	t.GET("/inventory/aggregate", h.aggregate)
	t.GET("/inventory/byModel", h.byModel)
	t.GET("/inventory/aging", h.aging)
	t.GET("/inventory/snapshot", h.snapshot)
	t.GET("/inventory/serialNos", h.serialNos)
	t.GET("/inventory/inboundTrend", h.inboundTrend)
	t.GET("/inventory/monthCompare", h.monthCompare)
	t.GET("/inventory/inboundSummary", h.inboundSummary)
	t.GET("/stat/cards", h.statCards)
	t.GET("/warehouse/list", h.warehouseList)
	t.GET("/health/suggest", h.healthSuggest)
	t.POST("/stockAlert/scan", h.stockAlertScan)

	o := r.Group("/transferOrder")
	o.POST("/create", h.orderCreate)
	o.POST("/outbound", h.orderOutbound)
	o.POST("/inbound", h.orderInbound)
	o.POST("/reject", h.orderReject)
	o.POST("/notice", h.orderNoticeUpsert)
	o.GET("/notices", h.orderNotices)
	o.GET("/get", h.orderGet)
	o.GET("/list/page", h.orderListPage)
	o.GET("/snFile", h.orderSnFile)
}

// fail 统一业务错误出口：*ErrBiz → 200 + code；其他 → 200 + 50000。
func (h *Handler) fail(c *gin.Context, err error) {
	var eb *ErrBiz
	if errors.As(err, &eb) {
		c.JSON(http.StatusOK, httpkit.BaseResponse{Code: eb.Code, Message: eb.Msg})
		return
	}
	h.log.Error("terminal 服务错误", "path", c.Request.URL.Path, "err", err)
	c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeSystem))
}

func ok(c *gin.Context, data any) {
	c.JSON(http.StatusOK, httpkit.OK(data))
}

// scopeOf 门户可见范围；无注入头 → nil（不限制）。
func (h *Handler) scopeOf(c *gin.Context) map[string]bool {
	return h.repo.VisibleFrom(c.Request.Context(), c.GetHeader("X-User-Warehouse"))
}

// ---- 台账 ----

func (h *Handler) aggregate(c *gin.Context) {
	scope := h.scopeOf(c)
	warehouseCode := c.Query("warehouseCode")
	if eb := RequireInScope(warehouseCode, scope); eb != nil {
		h.fail(c, eb)
		return
	}
	rows, err := h.repo.AggregateInventory(c.Request.Context(),
		warehouseCode, c.Query("model"), c.Query("bigType"))
	if err != nil {
		h.fail(c, err)
		return
	}
	if scope != nil && warehouseCode == "" {
		kept := rows[:0]
		for _, row := range rows {
			if row.WarehouseCode != nil && scope[*row.WarehouseCode] {
				kept = append(kept, row)
			}
		}
		rows = kept
	}
	if rows == nil {
		rows = []*InventoryRow{}
	}
	ok(c, rows)
}

func (h *Handler) byModel(c *gin.Context) {
	rows, err := h.repo.AggregateByModel(c.Request.Context())
	h.respondRows(c, rows, err)
}

func (h *Handler) aging(c *gin.Context) {
	scope := h.scopeOf(c)
	warehouseCode := h.scopedOrDefault(c.Query("warehouseCode"), scope, c)
	if eb := RequireInScope(warehouseCode, scope); eb != nil {
		h.fail(c, eb)
		return
	}
	rows, err := h.repo.AgingDistribution(c.Request.Context(), warehouseCode)
	h.respondRows(c, rows, err)
}

func (h *Handler) snapshot(c *gin.Context) {
	scope := h.scopeOf(c)
	warehouseCode := h.scopedOrDefault(c.Query("warehouseCode"), scope, c)
	if eb := RequireInScope(warehouseCode, scope); eb != nil {
		h.fail(c, eb)
		return
	}
	snap, err := h.repo.BuildSnapshot(c.Request.Context(), warehouseCode, c.Query("model"))
	if err != nil {
		h.fail(c, err)
		return
	}
	ok(c, snap)
}

func (h *Handler) serialNos(c *gin.Context) {
	warehouseCode := c.Query("warehouseCode")
	if eb := RequireInScope(warehouseCode, h.scopeOf(c)); eb != nil {
		h.fail(c, eb)
		return
	}
	limit := 10
	if v, err := strconv.Atoi(c.DefaultQuery("limit", "10")); err == nil {
		limit = v
	}
	if limit < 1 {
		limit = 1
	}
	if limit > 500 {
		limit = 500
	}
	rows, err := h.repo.ListAvailableSerialNos(c.Request.Context(),
		warehouseCode, c.Query("materialCode"), limit)
	h.respondRows(c, rows, err)
}

func (h *Handler) statCards(c *gin.Context) {
	cards, err := h.repo.StatCards(c.Request.Context())
	h.respondRows(c, cards, err)
}

func (h *Handler) warehouseList(c *gin.Context) {
	rows, err := h.repo.WarehouseListAllOrdered(c.Request.Context())
	h.respondRows(c, rows, err)
}

func (h *Handler) healthSuggest(c *gin.Context) {
	warehouseCode := c.Query("warehouseCode")
	if eb := RequireInScope(warehouseCode, h.scopeOf(c)); eb != nil {
		h.fail(c, eb)
		return
	}
	vo, err := h.repo.InventoryHealth(c.Request.Context(), warehouseCode)
	if err != nil {
		h.fail(c, err)
		return
	}
	ok(c, vo)
}

func (h *Handler) stockAlertScan(c *gin.Context) {
	scope := h.scopeOf(c)
	warehouseCode := h.scopedOrDefault(c.Query("warehouseCode"), scope, c)
	if eb := RequireInScope(warehouseCode, scope); eb != nil {
		h.fail(c, eb)
		return
	}
	result, err := h.repo.Scan(c.Request.Context(), warehouseCode)
	if err != nil {
		h.fail(c, err)
		return
	}
	ok(c, result)
}

// ---- 入库量时间维度 ----

func (h *Handler) inboundTrend(c *gin.Context) {
	scope := h.scopeOf(c)
	warehouseCode := h.scopedOrDefault(c.Query("warehouseCode"), scope, c)
	if eb := RequireInScope(warehouseCode, scope); eb != nil {
		h.fail(c, eb)
		return
	}
	granularity := c.DefaultQuery("granularity", "month")
	from, to := c.Query("from"), c.Query("to")
	if granularity == "day" && (from == "" || to == "") {
		h.fail(c, biz(40000, "按天查询必须同时给出 from 与 to（yyyy-MM-dd）；查多月/多年请改用月或年粒度"))
		return
	}
	granularity, rows, err := h.repo.InboundTrend(c.Request.Context(), granularity, from, to,
		warehouseCode, c.Query("model"), c.Query("bigType"), c.DefaultQuery("groupBy", "none"))
	if err != nil {
		h.fail(c, err)
		return
	}
	total := int64(0)
	for _, r := range rows {
		total += r.Cnt
	}
	if rows == nil {
		rows = []*InboundBucket{}
	}
	ok(c, gin.H{
		"granularity": granularity, "from": from, "to": to,
		"rows": rows, "bucketCount": len(rows), "totalCnt": total,
	})
}

func (h *Handler) monthCompare(c *gin.Context) {
	monthsRaw := c.Query("months")
	if monthsRaw == "" {
		h.fail(c, biz(40000, "months 不能为空（逗号分隔的 YYYY-MM，如 2025-06,2025-08）"))
		return
	}
	months, err := normalizeMonths(monthsRaw)
	if err != nil {
		h.fail(c, err)
		return
	}
	scope := h.scopeOf(c)
	warehouseCode := h.scopedOrDefault(c.Query("warehouseCode"), scope, c)
	if eb := RequireInScope(warehouseCode, scope); eb != nil {
		h.fail(c, eb)
		return
	}
	got, err := h.repo.InboundByMonthRange(c.Request.Context(),
		months[0]+"-01", nextMonth(months[len(months)-1])+"-01",
		warehouseCode, c.Query("model"), c.Query("bigType"))
	if err != nil {
		h.fail(c, err)
		return
	}
	byMonth := map[string]int64{}
	for _, r := range got {
		byMonth[r.Period] = r.Cnt
	}
	aligned := make([]*InboundBucket, 0, len(months))
	total := int64(0)
	for _, m := range months {
		cnt := byMonth[m]
		total += cnt
		aligned = append(aligned, &InboundBucket{Period: m})
	}
	ok(c, gin.H{"months": months, "totalCnt": total, "rows": aligned})
}

func (h *Handler) inboundSummary(c *gin.Context) {
	scope := h.scopeOf(c)
	warehouseCode := h.scopedOrDefault(c.Query("warehouseCode"), scope, c)
	if eb := RequireInScope(warehouseCode, scope); eb != nil {
		h.fail(c, eb)
		return
	}
	for _, name := range []string{"from", "to"} {
		if v := c.Query(name); v != "" && !isDate(v) {
			h.fail(c, biz(40000, "%s 需为 yyyy-MM-dd 格式，收到：%s", name, v))
			return
		}
	}
	groupBy := c.DefaultQuery("groupBy", "none")
	rows, err := h.repo.InboundSummary(c.Request.Context(), c.Query("from"), c.Query("to"),
		warehouseCode, c.Query("model"), c.Query("bigType"), groupBy)
	if err != nil {
		h.fail(c, err)
		return
	}
	total := int64(0)
	for _, r := range rows {
		total += r.Cnt
	}
	if rows == nil {
		rows = []*InboundBucket{}
	}
	ok(c, gin.H{
		"from": c.Query("from"), "to": c.Query("to"), "groupBy": groupBy,
		"groups": rows, "totalCnt": total,
	})
}

// ---- 调拨单 ----

// requireOrderSide 校验注入身份属于工单指定侧（from/to）与角色。
func (h *Handler) requireOrderSide(c *gin.Context, identity *Identity, orderNo, side string, allowedRoles map[string]bool) (*OrderVO, error) {
	order, err := h.repo.OrderDetail(c.Request.Context(), orderNo)
	if err != nil {
		return nil, err
	}
	expected := deref(order.ToWarehouse)
	if side == "from" {
		expected = deref(order.FromWarehouse)
	}
	if expected != identity.WarehouseCode || !allowedRoles[identity.Role] {
		return nil, biz(40101, "当前登录身份（%s / %s）无权操作该工单", identity.WarehouseCode, identity.Role)
	}
	return order, nil
}

var keeperOnly = map[string]bool{roleWarehouseKeeper: true}

func (h *Handler) orderCreate(c *gin.Context) {
	var req CreateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.fail(c, biz(40000, "请求体格式错误"))
		return
	}
	if identity := identityOf(c); identity != nil {
		if identity.WarehouseCode != deref(req.ToWarehouse) {
			h.fail(c, biz(40101, "只能由调入仓（本仓）发起调拨申请"))
			return
		}
		if identity.Role != roleAccountManager {
			h.fail(c, biz(40101, "只有账管可以发起调拨申请（当前角色：%s）", identity.Role))
			return
		}
		req.Creator = &identity.UserCode
	}
	vo, err := h.repo.CreateOrder(c.Request.Context(), &req)
	if err != nil {
		h.fail(c, err)
		return
	}
	ok(c, vo)
}

func (h *Handler) orderOutbound(c *gin.Context) {
	var req OutboundRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.fail(c, biz(40000, "请求体格式错误"))
		return
	}
	if identity := identityOf(c); identity != nil {
		if _, err := h.requireOrderSide(c, identity, deref(req.OrderNo), "from", keeperOnly); err != nil {
			h.fail(c, err)
			return
		}
	}
	vo, err := h.repo.Outbound(c.Request.Context(), &req)
	if err != nil {
		h.fail(c, err)
		return
	}
	ok(c, vo)
}

func (h *Handler) orderInbound(c *gin.Context) {
	var req ActionRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.fail(c, biz(40000, "请求体格式错误"))
		return
	}
	if identity := identityOf(c); identity != nil {
		if _, err := h.requireOrderSide(c, identity, deref(req.OrderNo), "to", keeperOnly); err != nil {
			h.fail(c, err)
			return
		}
		req.Operator = &identity.UserCode
	}
	vo, err := h.repo.Inbound(c.Request.Context(), &req)
	if err != nil {
		h.fail(c, err)
		return
	}
	ok(c, vo)
}

func (h *Handler) orderReject(c *gin.Context) {
	var req ActionRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.fail(c, biz(40000, "请求体格式错误"))
		return
	}
	if identity := identityOf(c); identity != nil {
		order, err := h.repo.OrderDetail(c.Request.Context(), deref(req.OrderNo))
		if err != nil {
			h.fail(c, err)
			return
		}
		switch deref(order.Status) {
		case StApplied:
			if _, err := h.requireOrderSide(c, identity, deref(req.OrderNo), "from", keeperOnly); err != nil {
				h.fail(c, err)
				return
			}
		case StOutbound:
			if _, err := h.requireOrderSide(c, identity, deref(req.OrderNo), "to", keeperOnly); err != nil {
				h.fail(c, err)
				return
			}
		default:
			h.fail(c, biz(50001, "当前状态（%s）不支持驳回", deref(order.Status)))
			return
		}
		req.Operator = &identity.UserCode
	}
	vo, err := h.repo.Reject(c.Request.Context(), &req)
	if err != nil {
		h.fail(c, err)
		return
	}
	ok(c, vo)
}

func (h *Handler) orderNoticeUpsert(c *gin.Context) {
	var req NoticeUpsertRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.fail(c, biz(40000, "请求体格式错误"))
		return
	}
	result, err := h.repo.UpsertNotice(c.Request.Context(), &req)
	if err != nil {
		h.fail(c, err)
		return
	}
	ok(c, result)
}

func (h *Handler) orderNotices(c *gin.Context) {
	warehouseCode, role, userCode := c.Query("warehouseCode"), c.Query("role"), c.Query("userCode")
	if identity := identityOf(c); identity != nil {
		warehouseCode, role, userCode = identity.WarehouseCode, identity.Role, identity.UserCode
	}
	limit := 20
	if v, err := strconv.Atoi(c.DefaultQuery("limit", "20")); err == nil {
		limit = v
	}
	rows, err := h.repo.ListNotices(c.Request.Context(), warehouseCode, role, userCode, limit)
	h.respondRows(c, rows, err)
}

func (h *Handler) orderGet(c *gin.Context) {
	orderNo := c.Query("orderNo")
	order, err := h.repo.OrderDetail(c.Request.Context(), orderNo)
	if err != nil {
		h.fail(c, err)
		return
	}
	if identity := identityOf(c); identity != nil {
		involved := identity.UserCode == deref(order.Creator) ||
			identity.WarehouseCode == deref(order.FromWarehouse) ||
			identity.WarehouseCode == deref(order.ToWarehouse)
		if !involved {
			h.fail(c, biz(40101, "当前登录身份无权查看该工单"))
			return
		}
	}
	ok(c, order)
}

func (h *Handler) orderListPage(c *gin.Context) {
	q := map[string]string{}
	for _, k := range []string{"orderNo", "status", "operationType", "fromWarehouse", "toWarehouse", "startTime", "endTime", "current", "pageSize"} {
		q[k] = c.Query(k)
	}
	var scope map[string]bool
	if identity := identityOf(c); identity != nil {
		scope = h.repo.VisibleFrom(c.Request.Context(), identity.WarehouseCode)
	}
	page, err := h.repo.PageOrders(c.Request.Context(), q, scope)
	if err != nil {
		h.fail(c, err)
		return
	}
	ok(c, page)
}

func (h *Handler) orderSnFile(c *gin.Context) {
	orderNo := c.Query("orderNo")
	if identity := identityOf(c); identity != nil {
		if _, err := h.requireOrderSide(c, identity, orderNo, "from", keeperOnly); err != nil {
			h.fail(c, err)
			return
		}
	}
	rows, err := h.repo.BuildSnFile(c.Request.Context(), orderNo)
	if err != nil {
		h.fail(c, err)
		return
	}
	ok(c, rows)
}

// ---- 辅助 ----

func (h *Handler) respondRows(c *gin.Context, data any, err error) {
	if err != nil {
		h.fail(c, err)
		return
	}
	ok(c, data)
}

// scopedOrDefault 门户通道缺省仓收敛到本仓（注入头）。
func (h *Handler) scopedOrDefault(requested string, scope map[string]bool, c *gin.Context) string {
	if scope != nil && requested == "" {
		return c.GetHeader("X-User-Warehouse")
	}
	return requested
}

// normalizeMonths 月份归一化 + 去重 + 升序（对齐 inboundMonthsCompare 的解析）。
func normalizeMonths(raw string) ([]string, error) {
	seen := map[string]bool{}
	var out []string
	for _, part := range strings.FieldsFunc(raw, func(r rune) bool { return r == ',' || r == '，' }) {
		m := normalizeMonth(part)
		if m == "" {
			return nil, biz(40000, "月份格式应为 YYYY-MM，收到：%s", part)
		}
		if !seen[m] {
			seen[m] = true
			out = append(out, m)
		}
	}
	if len(out) == 0 {
		return nil, biz(40000, "months 里没有可识别的月份（YYYY-MM）")
	}
	if len(out) > 24 {
		return nil, biz(40000, "一次最多对比 24 个月")
	}
	return out, nil
}

// normalizeMonth 「2025年6月 / 2025/6 / 2025-6」→「2025-06」。
func normalizeMonth(raw string) string {
	m := strings.TrimSpace(raw)
	m = strings.NewReplacer("年", "-", "月", "", "/", "-", ".", "-").Replace(m)
	parts := strings.Split(m, "-")
	if len(parts) != 2 || len(parts[0]) != 4 {
		return ""
	}
	var y, mm int
	if _, err := fmtSscan(parts[0], &y); err != nil {
		return ""
	}
	if _, err := fmtSscan(parts[1], &mm); err != nil || mm < 1 || mm > 12 {
		return ""
	}
	return parts[0] + "-" + string([]byte{'0' + byte(mm/10)}) + string([]byte{'0' + byte(mm%10)})
}

func isDate(v string) bool {
	if len(v) != 10 {
		return false
	}
	for i, r := range v {
		switch i {
		case 4, 7:
			if r != '-' {
				return false
			}
		default:
			if r < '0' || r > '9' {
				return false
			}
		}
	}
	return true
}

func nextMonth(ym string) string {
	y, _ := fmtSscan2(ym[:4])
	m, _ := fmtSscan2(ym[5:])
	m++
	if m > 12 {
		m = 1
		y++
	}
	return pad4(y) + "-" + pad2(m)
}

func fmtSscan(s string, out *int) (int, error) {
	n := 0
	for _, r := range s {
		if r < '0' || r > '9' {
			return 0, errors.New("not a number")
		}
		n = n*10 + int(r-'0')
	}
	*out = n
	return 1, nil
}

func fmtSscan2(s string) (int, error) {
	var v int
	_, err := fmtSscan(s, &v)
	return v, err
}

func pad4(n int) string {
	return string([]byte{
		byte('0' + n/1000%10), byte('0' + n/100%10), byte('0' + n/10%10), byte('0' + n%10),
	})
}

func pad2(n int) string {
	return string([]byte{byte('0' + n/10%10), byte('0' + n%10)})
}
