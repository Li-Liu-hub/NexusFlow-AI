// Package terminal 是 aismp-agent 终端业务的 Go 实现（P2）：
// 库存台账聚合（TerminalController/TerminalServiceImpl/TerminalMapper）
// + 调拨状态机（TransferOrderController/TransferOrderServiceImpl）
// + 库存告警扫描（StockAlertScanner）。
//
// 业务表全部在 api_db（MySQL）：terminal / warehouse / transfer_order /
// transfer_order_item / notice。列名与 Java 实体字段同名
// （map-underscore-to-camel-case=false），SQL 逐句对照移植。
//
// 数字红线：健康度/告警判定全是确定性 SQL 聚合，不经过 LLM（开发文档 1.4）。
package terminal

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"math"
	"sort"
	"strings"
	"time"
)

// ErrNotFound 记录不存在（Java 返回 null / 抛 NOT_FOUND 的分支）。
var ErrNotFound = errors.New("terminal: 记录不存在")

// ErrBiz 是带用户文案的业务错误（对应 BusinessException，HTTP 200 + code）。
type ErrBiz struct {
	Code int    // httpkit 错误码数值
	Msg  string
}

func (e *ErrBiz) Error() string { return e.Msg }

// biz 造业务错误。
func biz(code int, format string, args ...any) *ErrBiz {
	return &ErrBiz{Code: code, Msg: fmt.Sprintf(format, args...)}
}

// 常量对齐 TransferOrderServiceImpl。
const (
	OpTransfer = "TRANSFER"
	OpIssue    = "ISSUE"
	OpRecall   = "RECALL"
	OpReturn   = "RETURN"

	StApplied  = "APPLIED"
	StOutbound = "OUTBOUND"
	StDone     = "DONE"
	StRejected = "REJECTED"

	TSAvailable  = "可用"
	TSInTransit  = "在途"

	typeStockAlert       = "STOCK_ALERT"
	roleAccountManager   = "ACCOUNT_MANAGER"
	roleWarehouseKeeper  = "WAREHOUSE_KEEPER"
)

// Repository 承载全部 SQL。
type Repository struct {
	db *sql.DB
}

// NewRepository 构造仓储。
func NewRepository(db *sql.DB) *Repository { return &Repository{db: db} }

// ---- 行结构（JSON 字段名与 Java VO 的 Jackson 输出一致）----

// InventoryRow 对齐 TerminalInventoryVO。
type InventoryRow struct {
	WarehouseCode  *string  `json:"warehouseCode"`
	WarehouseName  *string  `json:"warehouseName"`
	WarehouseLevel *int     `json:"warehouseLevel"`
	BigType        *string  `json:"bigType"`
	MaterialCode   *string  `json:"materialCode"`
	Model          *string  `json:"model"`
	MaterialName   *string  `json:"materialName"`
	Vendor         *string  `json:"vendor"`
	AvailableQty   int64    `json:"availableQty"`
	TotalQty       int64    `json:"totalQty"`
	InTransitQty   int64    `json:"inTransitQty"`
	AvgAgingDays   *float64 `json:"avgAgingDays"`
	MaxAgingDays   *int64   `json:"maxAgingDays"`
}

// AgingRow 对齐 AgingDistributionVO。
type AgingRow struct {
	Band         string `json:"band"`
	SortNo       int    `json:"sortNo"`
	Cnt          int64  `json:"cnt"`
	AvailableQty int64  `json:"availableQty"`
}

// InboundBucket 对齐 InboundBucketVO。
type InboundBucket struct {
	Period    string `json:"period"`
	GroupKey  string `json:"groupKey"`
	GroupName string `json:"groupName"`
	Cnt       int64  `json:"cnt"`
}

// Warehouse 对齐 warehouse 实体。
type Warehouse struct {
	WarehouseCode string     `json:"warehouseCode"`
	Name          string     `json:"name"`
	Level         *int       `json:"level"`
	ParentCode    *string    `json:"parentCode"`
	Province      *string    `json:"province"`
	City          *string    `json:"city"`
	District      *string    `json:"district"`
	CreatedAt     *time.Time `json:"createdAt"`
}

// nstr / nint / nfloat 把可空列规整为 JSON 兼容值。
func nstr(s sql.NullString) *string {
	if !s.Valid {
		return nil
	}
	v := s.String
	return &v
}

func nint(s sql.NullInt64) *int {
	if !s.Valid {
		return nil
	}
	v := int(s.Int64)
	return &v
}

func nint64(s sql.NullInt64) *int64 {
	if !s.Valid {
		return nil
	}
	v := s.Int64
	return &v
}

func nfloat(s sql.NullFloat64) *float64 {
	if !s.Valid {
		return nil
	}
	v := s.Float64
	return &v
}

func ntime(s sql.NullTime) *time.Time {
	if !s.Valid {
		return nil
	}
	t := s.Time
	return &t
}

// ---- 台账聚合（TerminalMapper SQL 逐句移植）----

func (r *Repository) AggregateInventory(ctx context.Context, warehouseCode, model, bigType string) ([]*InventoryRow, error) {
	where, args := "1=1", []any{}
	if warehouseCode != "" {
		where += " AND t.warehouseCode = ?"
		args = append(args, warehouseCode)
	}
	if model != "" {
		where += " AND t.model = ?"
		args = append(args, model)
	}
	if bigType != "" {
		where += " AND t.bigType = ?"
		args = append(args, bigType)
	}
	rows, err := r.db.QueryContext(ctx, `
		SELECT t.warehouseCode, w.name, w.level, t.bigType, t.materialCode, t.model,
		       t.materialName, t.vendor,
		       SUM(CASE WHEN t.terminalStatus = '可用' THEN 1 ELSE 0 END) AS availableQty,
		       COUNT(*) AS totalQty,
		       SUM(CASE WHEN t.terminalStatus = '在途' THEN 1 ELSE 0 END) AS inTransitQty,
		       ROUND(AVG(t.agingDays), 1) AS avgAgingDays,
		       MAX(t.agingDays) AS maxAgingDays
		FROM terminal t
		LEFT JOIN warehouse w ON t.warehouseCode = w.warehouseCode
		WHERE `+where+`
		GROUP BY t.warehouseCode, w.name, w.level, t.bigType, t.materialCode, t.model, t.materialName, t.vendor
		ORDER BY t.warehouseCode, availableQty DESC`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []*InventoryRow
	for rows.Next() {
		var v InventoryRow
		var whName, bigType, materialCode, model, materialName, vendor sql.NullString
		var level sql.NullInt64
		var avg sql.NullFloat64
		var maxAge sql.NullInt64
		if err := rows.Scan(&v.WarehouseCode, &whName, &level, &bigType, &materialCode, &model,
			&materialName, &vendor, &v.AvailableQty, &v.TotalQty, &v.InTransitQty, &avg, &maxAge); err != nil {
			return nil, err
		}
		v.WarehouseName, v.WarehouseLevel = nstr(whName), nint(level)
		v.BigType, v.MaterialCode, v.Model, v.MaterialName, v.Vendor =
			nstr(bigType), nstr(materialCode), nstr(model), nstr(materialName), nstr(vendor)
		v.AvgAgingDays, v.MaxAgingDays = nfloat(avg), nint64(maxAge)
		list = append(list, &v)
	}
	return list, rows.Err()
}

func (r *Repository) AggregateByModel(ctx context.Context) ([]*InventoryRow, error) {
	rows, err := r.db.QueryContext(ctx, `
		SELECT t.model, t.materialCode, t.materialName, t.vendor, t.bigType,
		       SUM(CASE WHEN t.terminalStatus = '可用' THEN 1 ELSE 0 END) AS availableQty,
		       SUM(CASE WHEN t.terminalStatus = '在途' THEN 1 ELSE 0 END) AS inTransitQty,
		       COUNT(*) AS totalQty,
		       ROUND(AVG(t.agingDays), 1) AS avgAgingDays,
		       MAX(t.agingDays) AS maxAgingDays
		FROM terminal t
		GROUP BY t.model, t.materialCode, t.materialName, t.vendor, t.bigType
		ORDER BY availableQty DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []*InventoryRow
	for rows.Next() {
		var v InventoryRow
		var model, materialCode, materialName, vendor, bigType sql.NullString
		var avg sql.NullFloat64
		var maxAge sql.NullInt64
		if err := rows.Scan(&model, &materialCode, &materialName, &vendor, &bigType,
			&v.AvailableQty, &v.InTransitQty, &v.TotalQty, &avg, &maxAge); err != nil {
			return nil, err
		}
		v.Model, v.MaterialCode, v.MaterialName, v.Vendor, v.BigType =
			nstr(model), nstr(materialCode), nstr(materialName), nstr(vendor), nstr(bigType)
		v.AvgAgingDays, v.MaxAgingDays = nfloat(avg), nint64(maxAge)
		list = append(list, &v)
	}
	return list, rows.Err()
}

func (r *Repository) AgingDistribution(ctx context.Context, warehouseCode string) ([]*AgingRow, error) {
	where, args := "1=1", []any{}
	if warehouseCode != "" {
		where = "t.warehouseCode = ?"
		args = append(args, warehouseCode)
	}
	rows, err := r.db.QueryContext(ctx, `
		SELECT CASE WHEN t.agingDays IS NULL THEN 'NULL'
		            WHEN t.agingDays < 30  THEN '0-30'
		            WHEN t.agingDays < 90  THEN '30-90'
		            WHEN t.agingDays < 180 THEN '90-180'
		            WHEN t.agingDays < 365 THEN '180-365'
		            ELSE '365+' END AS band,
		       CASE WHEN t.agingDays IS NULL THEN 5
		            WHEN t.agingDays < 30  THEN 0
		            WHEN t.agingDays < 90  THEN 1
		            WHEN t.agingDays < 180 THEN 2
		            WHEN t.agingDays < 365 THEN 3
		            ELSE 4 END AS sortNo,
		       COUNT(*) AS cnt,
		       SUM(CASE WHEN t.terminalStatus = '可用' THEN 1 ELSE 0 END) AS availableQty
		FROM terminal t
		WHERE `+where+`
		GROUP BY band, sortNo
		ORDER BY sortNo`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []*AgingRow
	for rows.Next() {
		var v AgingRow
		if err := rows.Scan(&v.Band, &v.SortNo, &v.Cnt, &v.AvailableQty); err != nil {
			return nil, err
		}
		list = append(list, &v)
	}
	return list, rows.Err()
}

// ListAvailableSerialNos 某仓某物料可用串码（库龄倒序）。
func (r *Repository) ListAvailableSerialNos(ctx context.Context, warehouseCode, materialCode string, limit int) ([]string, error) {
	rows, err := r.db.QueryContext(ctx, `
		SELECT serialNo FROM terminal
		WHERE warehouseCode = ? AND materialCode = ? AND terminalStatus = '可用'
		ORDER BY agingDays DESC
		LIMIT ?`, warehouseCode, materialCode, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []string
	for rows.Next() {
		var s string
		if err := rows.Scan(&s); err != nil {
			return nil, err
		}
		list = append(list, s)
	}
	return list, rows.Err()
}

// CountAvailable 某仓某物料可用量（COUNT，不搬明细）。
func (r *Repository) CountAvailable(ctx context.Context, warehouseCode, materialCode string) (int, error) {
	var n int
	err := r.db.QueryRowContext(ctx, `
		SELECT COUNT(*) FROM terminal
		WHERE warehouseCode = ? AND materialCode = ? AND terminalStatus = '可用'`,
		warehouseCode, materialCode).Scan(&n)
	return n, err
}

// TransferBatch 批量换状态 + 换仓，返回受影响行数。
func (r *Repository) TransferBatch(ctx context.Context, tx *sql.Tx, serialNos []string, fromStatus, toStatus, toWarehouseCode string) (int, error) {
	if len(serialNos) == 0 {
		return 0, nil
	}
	placeholders := strings.TrimSuffix(strings.Repeat("?,", len(serialNos)), ",")
	args := []any{toStatus, toWarehouseCode, toWarehouseCode, fromStatus}
	args = append(args, toAnySlice(serialNos)...)
	exec := func(q string) (sql.Result, error) {
		if tx != nil {
			return tx.ExecContext(ctx, q, args...)
		}
		return r.db.ExecContext(ctx, q, args...)
	}
	res, err := exec(`UPDATE terminal SET terminalStatus = ?, warehouseCode = ?,
		ownerWarehouse = (SELECT name FROM warehouse WHERE warehouseCode = ?),
		statusChangedAt = NOW(), updatedAt = NOW()
		WHERE terminalStatus = ? AND serialNo IN (` + placeholders + `)`)
	if err != nil {
		return 0, err
	}
	n, err := res.RowsAffected()
	return int(n), err
}

// ---- 仓位 ----

func warehouseScan(scan interface{ Scan(...any) error }) (*Warehouse, error) {
	var w Warehouse
	var level sql.NullInt64
	var parent, province, city, district sql.NullString
	var created sql.NullTime
	if err := scan.Scan(&w.WarehouseCode, &w.Name, &level, &parent, &province, &city, &district, &created); err != nil {
		return nil, err
	}
	w.Level, w.ParentCode, w.Province, w.City, w.District, w.CreatedAt =
		nint(level), nstr(parent), nstr(province), nstr(city), nstr(district), ntime(created)
	return &w, nil
}

const warehouseCols = `warehouseCode, name, level, parentCode, province, city, district, createdAt`

// WarehouseGet 按 code 查仓位。
func (r *Repository) WarehouseGet(ctx context.Context, code string) (*Warehouse, error) {
	w, err := warehouseScan(r.db.QueryRowContext(ctx,
		`SELECT `+warehouseCols+` FROM warehouse WHERE warehouseCode = ?`, code))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return w, err
}

// WarehouseListAllOrdered 全部仓位（按层级排序，WarehouseMapper.listAllOrdered）。
func (r *Repository) WarehouseListAllOrdered(ctx context.Context) ([]*Warehouse, error) {
	rows, err := r.db.QueryContext(ctx,
		`SELECT ` + warehouseCols + ` FROM warehouse ORDER BY level, warehouseCode`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []*Warehouse
	for rows.Next() {
		w, err := warehouseScan(rows)
		if err != nil {
			return nil, err
		}
		list = append(list, w)
	}
	return list, rows.Err()
}

// ---- 可见范围（WarehouseScopeResolver）----

// VisibleFrom 以 rootCode 为根算可见仓位集合（本仓 + 所有下级，parentCode 递归）。
// rootCode 为空 → nil（不限制）。
func (r *Repository) VisibleFrom(ctx context.Context, rootCode string) map[string]bool {
	if rootCode == "" {
		return nil
	}
	rows, err := r.WarehouseListAllOrdered(ctx)
	if err != nil {
		return map[string]bool{rootCode: true} // 层级取不到按最严口径
	}
	children := map[string][]string{}
	for _, w := range rows {
		parent := ""
		if w.ParentCode != nil {
			parent = *w.ParentCode
		}
		children[parent] = append(children[parent], w.WarehouseCode)
	}
	scope := map[string]bool{}
	stack := []string{rootCode}
	for len(stack) > 0 {
		code := stack[len(stack)-1]
		stack = stack[:len(stack)-1]
		if code == "" || scope[code] {
			continue
		}
		scope[code] = true
		stack = append(stack, children[code]...)
	}
	return scope
}

// RequireInScope 越权返回 40101 业务错误（对齐 requireInScope），nil = 放行。
func RequireInScope(requested string, scope map[string]bool) *ErrBiz {
	if scope == nil || requested == "" {
		return nil
	}
	if !scope[requested] {
		return biz(40101, "请求仓位 %s 超出当前账号可见范围", requested)
	}
	return nil
}

// ---- 快照 / 统计卡 / 健康检查（TerminalServiceImpl）----

// SnapshotVO 对齐 InventorySnapshotVO。
type SnapshotVO struct {
	WarehouseCode  *string         `json:"warehouseCode"`
	Model          *string         `json:"model"`
	GeneratedAt    string          `json:"generatedAt"`
	Inventory      []*InventoryRow `json:"inventory"`
	Aging          []*AgingRow     `json:"aging"`
	ByModel        []*InventoryRow `json:"byModel"`
	TotalTerminals int64           `json:"totalTerminals"`
	TotalAvailable int64           `json:"totalAvailable"`
	TotalInTransit int64           `json:"totalInTransit"`
	LongAgingCount int64           `json:"longAgingCount"`
}

// BuildSnapshot 一次取全聚合。
func (r *Repository) BuildSnapshot(ctx context.Context, warehouseCode, model string) (*SnapshotVO, error) {
	inventory, err := r.AggregateInventory(ctx, warehouseCode, model, "")
	if err != nil {
		return nil, err
	}
	aging, err := r.AgingDistribution(ctx, warehouseCode)
	if err != nil {
		return nil, err
	}
	byModel, err := r.AggregateByModel(ctx)
	if err != nil {
		return nil, err
	}
	snap := &SnapshotVO{
		WarehouseCode: strPtr(warehouseCode), Model: strPtrIf(model),
		GeneratedAt: time.Now().Format("2006-01-02 15:04:05"),
		Inventory:   inventory, Aging: aging, ByModel: byModel,
	}
	for _, v := range inventory {
		snap.TotalTerminals += v.TotalQty
		snap.TotalAvailable += v.AvailableQty
		snap.TotalInTransit += v.InTransitQty
	}
	for _, b := range aging {
		if b.Band == "180-365" || b.Band == "365+" {
			snap.LongAgingCount += b.Cnt
		}
	}
	return snap, nil
}

// StatCards 首页统计卡（无流水数据 → 昨日两项恒 null，与 Java 一致）。
func (r *Repository) StatCards(ctx context.Context) (map[string]any, error) {
	var total int
	if err := r.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM terminal`).Scan(&total); err != nil {
		return nil, err
	}
	aging, err := r.AgingDistribution(ctx, "")
	if err != nil {
		return nil, err
	}
	longAging := int64(0)
	for _, b := range aging {
		if b.Band == "180-365" || b.Band == "365+" {
			longAging += b.Cnt
		}
	}
	return map[string]any{
		"totalStock":       total,
		"longAging":        longAging,
		"yesterdayInstall": nil,
		"yesterdayRecycle": nil,
	}, nil
}

// HealthSuggestion 对齐 InventoryHealthVO.Suggestion。
type HealthSuggestion struct {
	MaterialCode      *string  `json:"materialCode"`
	Model             *string  `json:"model"`
	MaterialName      *string  `json:"materialName"`
	BigType           *string  `json:"bigType"`
	FromWarehouse     string   `json:"fromWarehouse"`
	FromWarehouseName string   `json:"fromWarehouseName"`
	AvailableAtFrom   int64    `json:"availableAtFrom"`
	FromAvgAgingDays  *float64 `json:"fromAvgAgingDays"`
	ToWarehouse       string   `json:"toWarehouse"`
	ToWarehouseName   string   `json:"toWarehouseName"`
	AvailableAtTo     int64    `json:"availableAtTo"`
	SuggestQty        int      `json:"suggestQty"`
	Reason            string   `json:"reason"`
}

// HealthVO 对齐 InventoryHealthVO。
type HealthVO struct {
	WarehouseCode  string              `json:"warehouseCode"`
	WarehouseName  string              `json:"warehouseName"`
	TotalStock     int64               `json:"totalStock"`
	AvailableQty   int64               `json:"availableQty"`
	LongAgingQty   int64               `json:"longAgingQty"`
	LongAgingRatio float64             `json:"longAgingRatio"`
	ModelCount     int                 `json:"modelCount"`
	HealthStatus   string              `json:"healthStatus"`
	Summary        string              `json:"summary"`
	Suggestions    []*HealthSuggestion `json:"suggestions"`
}

const (
	surplusThreshold  = 200
	shortageThreshold = 50
)

// InventoryHealth 健康检查 + 跨仓调拨建议（确定性计算，数字红线）。
func (r *Repository) InventoryHealth(ctx context.Context, warehouseCode string) (*HealthVO, error) {
	if warehouseCode == "" {
		return nil, biz(40000, "warehouseCode 必填")
	}
	me, err := r.WarehouseGet(ctx, warehouseCode)
	if errors.Is(err, ErrNotFound) {
		return nil, biz(40400, "仓位不存在：%s", warehouseCode)
	} else if err != nil {
		return nil, err
	}
	mine, err := r.AggregateInventory(ctx, warehouseCode, "", "")
	if err != nil {
		return nil, err
	}
	aging, err := r.AgingDistribution(ctx, warehouseCode)
	if err != nil {
		return nil, err
	}
	vo := &HealthVO{WarehouseCode: me.WarehouseCode, WarehouseName: me.Name}
	models := map[string]bool{}
	myByMaterial := map[string]int64{}
	for _, row := range mine {
		vo.TotalStock += row.TotalQty
		vo.AvailableQty += row.AvailableQty
		if row.Model != nil {
			models[*row.Model] = true
		}
		myByMaterial[deref(row.MaterialCode)] += row.AvailableQty
	}
	for _, b := range aging {
		if b.Band == "365+" {
			vo.LongAgingQty += b.Cnt
		}
	}
	partner, err := r.resolvePartner(ctx, me)
	if err != nil {
		return nil, err
	}
	var theirs []*InventoryRow
	if partner != nil {
		theirs, err = r.AggregateInventory(ctx, partner.WarehouseCode, "", "")
		if err != nil {
			return nil, err
		}
	}
	for _, row := range theirs {
		atFrom := row.AvailableQty
		if atFrom < surplusThreshold {
			continue
		}
		atTo := myByMaterial[deref(row.MaterialCode)]
		if atTo >= shortageThreshold {
			continue
		}
		qty := int(atFrom) / 10
		if qty < 10 {
			qty = 10
		}
		if qty > 100 {
			qty = 100
		}
		avgAging := 0.0
		if row.AvgAgingDays != nil {
			avgAging = *row.AvgAgingDays
		}
		vo.Suggestions = append(vo.Suggestions, &HealthSuggestion{
			MaterialCode: row.MaterialCode, Model: row.Model, MaterialName: row.MaterialName,
			BigType: row.BigType,
			FromWarehouse: partner.WarehouseCode, FromWarehouseName: partner.Name,
			AvailableAtFrom: atFrom, FromAvgAgingDays: row.AvgAgingDays,
			ToWarehouse: me.WarehouseCode, ToWarehouseName: me.Name,
			AvailableAtTo: atTo, SuggestQty: qty,
			Reason: fmt.Sprintf("本仓可用 %d 台（低于阈值 %d），%s 可用 %d 台、平均库龄 %.0f 天，建议调入 %d 台",
				atTo, shortageThreshold, partner.Name, atFrom, avgAging, qty),
		})
	}
	// 先缺得最狠、再挑对方库龄最长的
	sort.SliceStable(vo.Suggestions, func(i, j int) bool {
		a, b := vo.Suggestions[i], vo.Suggestions[j]
		if a.AvailableAtTo != b.AvailableAtTo {
			return a.AvailableAtTo < b.AvailableAtTo
		}
		da, db := 0.0, 0.0
		if a.FromAvgAgingDays != nil {
			da = *a.FromAvgAgingDays
		}
		if b.FromAvgAgingDays != nil {
			db = *b.FromAvgAgingDays
		}
		return da > db
	})
	if len(vo.Suggestions) > 5 {
		vo.Suggestions = vo.Suggestions[:5]
	}
	ratio := 0.0
	if vo.TotalStock > 0 {
		ratio = float64(vo.LongAgingQty) / float64(vo.TotalStock)
	}
	vo.LongAgingRatio = math.Round(ratio*1000) / 1000
	switch {
	case ratio >= 0.6:
		vo.HealthStatus = "RISK"
	case ratio >= 0.3:
		vo.HealthStatus = "WATCH"
	default:
		vo.HealthStatus = "HEALTHY"
	}
	tail := "；暂无跨仓调拨建议"
	if len(vo.Suggestions) > 0 {
		tail = fmt.Sprintf("；有 %d 条调拨建议待处理", len(vo.Suggestions))
	}
	vo.Summary = fmt.Sprintf("%s：在库 %d 台（可用 %d 台）、型号 %d 个，长库龄（≥365 天）%d 台占比 %.1f%%%s",
		me.Name, vo.TotalStock, vo.AvailableQty, len(models), vo.LongAgingQty, ratio*100, tail)
	return vo, nil
}

// resolvePartner 调出方：优先上级仓；没有就取库存最大的其他仓。
func (r *Repository) resolvePartner(ctx context.Context, me *Warehouse) (*Warehouse, error) {
	if me.ParentCode != nil && *me.ParentCode != "" {
		if parent, err := r.WarehouseGet(ctx, *me.ParentCode); err == nil {
			return parent, nil
		}
	}
	all, err := r.AggregateInventory(ctx, "", "", "")
	if err != nil {
		return nil, err
	}
	perWarehouse := map[string]int64{}
	for _, row := range all {
		if row.WarehouseCode == nil {
			continue
		}
		perWarehouse[*row.WarehouseCode] += row.AvailableQty
	}
	bestCode, bestQty := "", int64(-1)
	for code, qty := range perWarehouse {
		if code == me.WarehouseCode || qty <= bestQty {
			continue
		}
		bestQty, bestCode = qty, code
	}
	if bestCode == "" {
		return nil, nil
	}
	return r.WarehouseGet(ctx, bestCode)
}

// ---- 入库量时间维度（按 firstInboundAt 归桶）----

func (r *Repository) inboundRows(ctx context.Context, periodExpr, groupBy, where string, args []any) ([]*InboundBucket, error) {
	var groupSelect, groupTail string
	switch groupBy {
	case "warehouse":
		groupSelect = "t.warehouseCode AS groupKey, COALESCE(w.name, t.warehouseCode) AS groupName, "
		groupTail = " GROUP BY period, groupKey, groupName"
	case "model":
		groupSelect = "t.model AS groupKey, t.model AS groupName, "
		groupTail = " GROUP BY period, groupKey, groupName"
	default:
		groupSelect = "'' AS groupKey, '' AS groupName, "
		if periodExpr == "''" {
			groupTail = " GROUP BY groupKey, groupName"
		} else {
			groupTail = " GROUP BY period, groupKey, groupName"
		}
	}
	order := " ORDER BY cnt DESC"
	if periodExpr != "''" {
		order = " ORDER BY period, cnt DESC"
	}
	rows, err := r.db.QueryContext(ctx,
		`SELECT `+periodExpr+` AS period, `+groupSelect+`COUNT(*) AS cnt
		 FROM terminal t
		 LEFT JOIN warehouse w ON t.warehouseCode = w.warehouseCode
		 WHERE t.firstInboundAt IS NOT NULL AND `+where+groupTail+order, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []*InboundBucket
	for rows.Next() {
		var b InboundBucket
		if err := rows.Scan(&b.Period, &b.GroupKey, &b.GroupName, &b.Cnt); err != nil {
			return nil, err
		}
		list = append(list, &b)
	}
	return list, rows.Err()
}

func (r *Repository) inboundFilters(warehouseCode, model, bigType string) (string, []any) {
	where, args := "1=1", []any{}
	if warehouseCode != "" {
		where += " AND t.warehouseCode = ?"
		args = append(args, warehouseCode)
	}
	if model != "" {
		where += " AND t.model = ?"
		args = append(args, model)
	}
	if bigType != "" {
		where += " AND t.bigType = ?"
		args = append(args, bigType)
	}
	return where, args
}

// InboundTrend 月/年/日 趋势。
func (r *Repository) InboundTrend(ctx context.Context, granularity, from, to, warehouseCode, model, bigType, groupBy string) (string, []*InboundBucket, error) {
	fmtExpr := "%Y-%m"
	switch granularity {
	case "year":
		fmtExpr = "%Y"
	case "day":
		fmtExpr = "%Y-%m-%d"
	default:
		granularity = "month"
	}
	where, args := r.inboundFilters(warehouseCode, model, bigType)
	if from != "" {
		where += " AND t.firstInboundAt >= ?"
		args = append(args, from)
	}
	if to != "" {
		where += " AND t.firstInboundAt < DATE_ADD(?, INTERVAL 1 DAY)"
		args = append(args, to)
	}
	rows, err := r.inboundRows(ctx, "DATE_FORMAT(t.firstInboundAt, '"+fmtExpr+"')", groupBy, where, args)
	return granularity, rows, err
}

// InboundByMonthRange 区间按月计数（monthCompare 取数）。
func (r *Repository) InboundByMonthRange(ctx context.Context, from, toExclusive, warehouseCode, model, bigType string) ([]*InboundBucket, error) {
	where, args := r.inboundFilters(warehouseCode, model, bigType)
	where += " AND t.firstInboundAt >= ? AND t.firstInboundAt < ?"
	args = append(args, from, toExclusive)
	return r.inboundRows(ctx, "DATE_FORMAT(t.firstInboundAt, '%Y-%m')", "", where, args)
}

// InboundSummary 汇总（不按时间分桶）。
func (r *Repository) InboundSummary(ctx context.Context, from, to, warehouseCode, model, bigType, groupBy string) ([]*InboundBucket, error) {
	where, args := r.inboundFilters(warehouseCode, model, bigType)
	if from != "" {
		where += " AND t.firstInboundAt >= ?"
		args = append(args, from)
	}
	if to != "" {
		where += " AND t.firstInboundAt < DATE_ADD(?, INTERVAL 1 DAY)"
		args = append(args, to)
	}
	gb := groupBy
	if gb == "none" {
		gb = ""
	}
	return r.inboundRows(ctx, "''", gb, where, args)
}

// ---- 小工具 ----

func strPtr(s string) *string                { return &s }
func strPtrIf(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func deref(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}

func toAnySlice(ss []string) []any {
	out := make([]any, len(ss))
	for i, s := range ss {
		out[i] = s
	}
	return out
}
