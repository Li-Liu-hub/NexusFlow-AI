// 调拨单状态机（TransferOrderServiceImpl 逐行为移植）：
//
//	APPLIED --B仓管导入SN出库--> OUTBOUND --A仓管确认入库--> DONE
//	   │                            │
//	   └─B仓管拒绝(货未动)→ REJECTED └─A仓管驳回(在途退回B仓可用)→ REJECTED
//
// 四条硬规则在 create/outbound 全量校验；事务在 Go 侧用 sql.Tx 包裹，
// 计数不符时回滚（与 Java 的 @Transactional + 显式抛错等价）。
package terminal

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"math/rand"
	"sort"
	"strings"
	"time"
)

// TransferOrder 对齐 transfer_order 实体（列名同名）。
type TransferOrder struct {
	OrderNo            string     `json:"orderNo"`
	OperationType      *string    `json:"operationType"`
	FromWarehouse      *string    `json:"fromWarehouse"`
	ToWarehouse        *string    `json:"toWarehouse"`
	Status             *string    `json:"status"`
	ItemCount          *int       `json:"itemCount"`
	TotalQuantity      *int       `json:"totalQuantity"`
	NeedApproval       *int       `json:"needApproval"`
	Creator            *string    `json:"creator"`
	RejectReason       *string    `json:"rejectReason"`
	Source             *string    `json:"source"`
	SuggestionID       *string    `json:"suggestionId"`
	CreatedAt          *time.Time `json:"createdAt"`
	OutboundAt         *time.Time `json:"outboundAt"`
	InboundAt          *time.Time `json:"inboundAt"`
	SentAt             *time.Time `json:"sentAt"`
	AccountConfirmedAt *time.Time `json:"accountConfirmedAt"`
	KeeperConfirmedAt  *time.Time `json:"keeperConfirmedAt"`
	SenderUser         *string    `json:"senderUser"`
	AccountConfirmer   *string    `json:"accountConfirmer"`
	KeeperConfirmer    *string    `json:"keeperConfirmer"`
}

const orderCols = `orderNo, operationType, fromWarehouse, toWarehouse, status, itemCount,
	totalQuantity, needApproval, creator, rejectReason, source, suggestionId,
	createdAt, outboundAt, inboundAt, sentAt, accountConfirmedAt, keeperConfirmedAt,
	senderUser, accountConfirmer, keeperConfirmer`

func scanOrder(scan func(...any) error) (*TransferOrder, error) {
	var o TransferOrder
	var op, from, to, status, creator, rejectReason, source, suggestionID sql.NullString
	var itemCount, totalQuantity, needApproval sql.NullInt64
	var createdAt, outboundAt, inboundAt, sentAt, accountConfirmedAt, keeperConfirmedAt sql.NullTime
	var senderUser, accountConfirmer, keeperConfirmer sql.NullString
	err := scan(&o.OrderNo, &op, &from, &to, &status, &itemCount, &totalQuantity, &needApproval,
		&creator, &rejectReason, &source, &suggestionID,
		&createdAt, &outboundAt, &inboundAt, &sentAt, &accountConfirmedAt, &keeperConfirmedAt,
		&senderUser, &accountConfirmer, &keeperConfirmer)
	if err != nil {
		return nil, err
	}
	o.OperationType, o.FromWarehouse, o.ToWarehouse, o.Status = nstr(op), nstr(from), nstr(to), nstr(status)
	o.ItemCount, o.TotalQuantity, o.NeedApproval = nint(itemCount), nint(totalQuantity), nint(needApproval)
	o.Creator, o.RejectReason, o.Source, o.SuggestionID = nstr(creator), nstr(rejectReason), nstr(source), nstr(suggestionID)
	o.CreatedAt, o.OutboundAt, o.InboundAt = ntime(createdAt), ntime(outboundAt), ntime(inboundAt)
	o.SentAt, o.AccountConfirmedAt, o.KeeperConfirmedAt = ntime(sentAt), ntime(accountConfirmedAt), ntime(keeperConfirmedAt)
	o.SenderUser, o.AccountConfirmer, o.KeeperConfirmer = nstr(senderUser), nstr(accountConfirmer), nstr(keeperConfirmer)
	return &o, nil
}

// OrderGet 按 orderNo 查单。
func (r *Repository) OrderGet(ctx context.Context, tx *sql.Tx, orderNo string) (*TransferOrder, error) {
	q := `SELECT ` + orderCols + ` FROM transfer_order WHERE orderNo = ?`
	var args = []any{orderNo}
	row := interface{ Scan(...any) error }(nil)
	if tx != nil {
		row = tx.QueryRowContext(ctx, q, args...)
	} else {
		row = r.db.QueryRowContext(ctx, q, args...)
	}
	o, err := scanOrder(row.Scan)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return o, err
}

// OrderItem 对齐 transfer_order_item。
type OrderItem struct {
	ID           int64      `json:"id"`
	OrderNo      string     `json:"orderNo"`
	MaterialCode *string    `json:"materialCode"`
	Model        *string    `json:"model"`
	MaterialName *string    `json:"materialName"`
	Quantity     *int       `json:"quantity"`
	SerialNos    *string    `json:"serialNos"`
	CreatedAt    *time.Time `json:"createdAt"`
}

func (r *Repository) OrderItems(ctx context.Context, tx *sql.Tx, orderNo string) ([]*OrderItem, error) {
	q := `SELECT id, orderNo, materialCode, model, materialName, quantity, serialNos, createdAt
		  FROM transfer_order_item WHERE orderNo = ?`
	var rows *sql.Rows
	var err error
	if tx != nil {
		rows, err = tx.QueryContext(ctx, q, orderNo)
	} else {
		rows, err = r.db.QueryContext(ctx, q, orderNo)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []*OrderItem
	for rows.Next() {
		var it OrderItem
		var materialCode, model, materialName, serialNos sql.NullString
		var quantity sql.NullInt64
		var created sql.NullTime
		if err := rows.Scan(&it.ID, &it.OrderNo, &materialCode, &model, &materialName,
			&quantity, &serialNos, &created); err != nil {
			return nil, err
		}
		it.MaterialCode, it.Model, it.MaterialName = nstr(materialCode), nstr(model), nstr(materialName)
		it.Quantity, it.SerialNos, it.CreatedAt = nint(quantity), nstr(serialNos), ntime(created)
		list = append(list, &it)
	}
	return list, rows.Err()
}

// Notice 对齐 notice 实体。
type Notice struct {
	ID         int64      `json:"id"`
	Receiver   *string    `json:"receiver"`
	NoticeType *string    `json:"noticeType"`
	Title      *string    `json:"title"`
	Content    *string    `json:"content"`
	OrderNo    *string    `json:"orderNo"`
	IsRead     *int       `json:"isRead"`
	CreatedAt  *time.Time `json:"createdAt"`
}

// OrderLine / OrderVO 对齐 TransferOrderVO。
type OrderLine struct {
	MaterialCode *string   `json:"materialCode"`
	Model        *string   `json:"model"`
	MaterialName *string   `json:"materialName"`
	Quantity     *int      `json:"quantity"`
	SerialNos    []string  `json:"serialNos"`
}

type OrderVO struct {
	OrderNo            *string      `json:"orderNo"`
	OperationType      *string      `json:"operationType"`
	FromWarehouse      *string      `json:"fromWarehouse"`
	FromWarehouseName  *string      `json:"fromWarehouseName"`
	ToWarehouse        *string      `json:"toWarehouse"`
	ToWarehouseName    *string      `json:"toWarehouseName"`
	Status             *string      `json:"status"`
	StatusLabel        string       `json:"statusLabel"`
	PendingAction      string       `json:"pendingAction"`
	ItemCount          *int         `json:"itemCount"`
	TotalQuantity      *int         `json:"totalQuantity"`
	MovedQuantity      *int         `json:"movedQuantity"`
	NeedApproval       *int         `json:"needApproval"`
	Creator            *string      `json:"creator"`
	RejectReason       *string      `json:"rejectReason"`
	Source             *string      `json:"source"`
	SuggestionID       *string      `json:"suggestionId"`
	CreatedAt          *string      `json:"createdAt"`
	OutboundAt         *string      `json:"outboundAt"`
	InboundAt          *string      `json:"inboundAt"`
	SentAt             *string      `json:"sentAt"`
	AccountConfirmedAt *string      `json:"accountConfirmedAt"`
	KeeperConfirmedAt  *string      `json:"keeperConfirmedAt"`
	SenderUser         *string      `json:"senderUser"`
	AccountConfirmer   *string      `json:"accountConfirmer"`
	KeeperConfirmer    *string      `json:"keeperConfirmer"`
	Items              []*OrderLine `json:"items"`
}

func fmtTime(t *time.Time) *string {
	if t == nil {
		return nil
	}
	s := t.Format("2006-01-02 15:04:05")
	return &s
}

func statusLabel(status string) string {
	switch status {
	case StApplied:
		return "待确认出库"
	case StOutbound:
		return "待确认入库"
	case StDone:
		return "已完成"
	case StRejected:
		return "已驳回"
	case "PENDING":
		return "待发送（历史）"
	case "SENT":
		return "待账管确认（历史）"
	case "ACCOUNT_CONFIRMED":
		return "待仓管确认（历史）"
	default:
		return status
	}
}

func pendingAction(status string) string {
	switch status {
	case StApplied:
		return "待调出方仓管导入 SN 并确认出库"
	case StOutbound:
		return "待调入方仓管确认入库或驳回"
	default:
		return ""
	}
}

// toVO 组装详情（含仓位名）。
func (r *Repository) toVO(ctx context.Context, o *TransferOrder) (*OrderVO, error) {
	vo := &OrderVO{
		OrderNo: strPtr(o.OrderNo), OperationType: o.OperationType,
		FromWarehouse: o.FromWarehouse, ToWarehouse: o.ToWarehouse,
		Status: o.Status, ItemCount: o.ItemCount, TotalQuantity: o.TotalQuantity,
		NeedApproval: o.NeedApproval, Creator: o.Creator, RejectReason: o.RejectReason,
		Source: o.Source, SuggestionID: o.SuggestionID,
		CreatedAt: fmtTime(o.CreatedAt), OutboundAt: fmtTime(o.OutboundAt),
		InboundAt: fmtTime(o.InboundAt), SentAt: fmtTime(o.SentAt),
		AccountConfirmedAt: fmtTime(o.AccountConfirmedAt), KeeperConfirmedAt: fmtTime(o.KeeperConfirmedAt),
		SenderUser: o.SenderUser, AccountConfirmer: o.AccountConfirmer, KeeperConfirmer: o.KeeperConfirmer,
		Items: []*OrderLine{},
	}
	if o.Status != nil {
		vo.StatusLabel = statusLabel(*o.Status)
		vo.PendingAction = pendingAction(*o.Status)
		if *o.Status == StDone && o.TotalQuantity != nil {
			vo.MovedQuantity = o.TotalQuantity
		}
	}
	items, err := r.OrderItems(ctx, nil, o.OrderNo)
	if err != nil {
		return nil, err
	}
	moved := 0
	for _, it := range items {
		sns := ParseSerialNos(it.SerialNos)
		vo.Items = append(vo.Items, &OrderLine{
			MaterialCode: it.MaterialCode, Model: it.Model, MaterialName: it.MaterialName,
			Quantity: it.Quantity, SerialNos: sns,
		})
		moved += len(sns)
	}
	if o.Status != nil && *o.Status != StDone && moved > 0 {
		vo.MovedQuantity = &moved
	}
	if o.FromWarehouse != nil {
		if w, err := r.WarehouseGet(ctx, *o.FromWarehouse); err == nil {
			vo.FromWarehouseName = &w.Name
		}
	}
	if o.ToWarehouse != nil {
		if w, err := r.WarehouseGet(ctx, *o.ToWarehouse); err == nil {
			vo.ToWarehouseName = &w.Name
		}
	}
	return vo, nil
}

// OrderDetail 查详情。
func (r *Repository) OrderDetail(ctx context.Context, orderNo string) (*OrderVO, error) {
	o, err := r.OrderGet(ctx, nil, orderNo)
	if err != nil {
		return nil, err
	}
	return r.toVO(ctx, o)
}

// ---- 建单 ----

// CreateItem 对齐 TransferOrderCreateRequest.Item。
type CreateItem struct {
	MaterialCode *string `json:"materialCode"`
	Model        *string `json:"model"`
	MaterialName *string `json:"materialName"`
	Quantity     *int    `json:"quantity"`
}

// CreateRequest 对齐 TransferOrderCreateRequest。
type CreateRequest struct {
	FromWarehouse *string      `json:"fromWarehouse"`
	ToWarehouse   *string      `json:"toWarehouse"`
	OperationType *string      `json:"operationType"`
	Creator       *string      `json:"creator"`
	Source        *string      `json:"source"`
	SuggestionID  *string      `json:"suggestionId"`
	Items         []*CreateItem `json:"items"`
}

func (r *Repository) validateOperation(op string, from, to *Warehouse) error {
	fl, tl := 0, 0
	if from.Level != nil {
		fl = *from.Level
	}
	if to.Level != nil {
		tl = *to.Level
	}
	switch op {
	case OpIssue:
		if tl != fl+1 {
			return biz(50001, "下发只能到直接下级仓位（%s 是 %d 级，%s 是 %d 级）",
				from.Name, fl, to.Name, tl)
		}
	case OpTransfer:
		if fl >= tl {
			return biz(50001, "调拨由上级发起，调出仓位层级必须高于调入仓位")
		}
	case OpRecall, OpReturn:
		if tl >= fl {
			word := "收回"
			if op == OpReturn {
				word = "回退"
			}
			return biz(50001, "%s的方向必须是下级退上级", word)
		}
	}
	return nil
}

// CreateOrder 建单（事务）。
func (r *Repository) CreateOrder(ctx context.Context, req *CreateRequest) (*OrderVO, error) {
	if req == nil || deref(req.FromWarehouse) == "" || deref(req.ToWarehouse) == "" {
		return nil, biz(40000, "调出/调入仓位不能为空")
	}
	if len(req.Items) == 0 {
		return nil, biz(40000, "调拨明细不能为空")
	}
	if deref(req.FromWarehouse) == deref(req.ToWarehouse) {
		return nil, biz(40000, "调出与调入仓不能相同")
	}
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()

	from, err := r.WarehouseGet(ctx, deref(req.FromWarehouse))
	if errors.Is(err, ErrNotFound) {
		return nil, biz(40400, "仓位不存在")
	} else if err != nil {
		return nil, err
	}
	to, err := r.WarehouseGet(ctx, deref(req.ToWarehouse))
	if errors.Is(err, ErrNotFound) {
		return nil, biz(40400, "仓位不存在")
	} else if err != nil {
		return nil, err
	}
	op := strings.ToUpper(deref(req.OperationType))
	if op == "" {
		op = OpTransfer
	}
	if op != OpTransfer && op != OpIssue && op != OpRecall && op != OpReturn {
		return nil, biz(40000, "不支持的操作类型：%s（仅 TRANSFER / ISSUE / RECALL / RETURN）", op)
	}
	if err := r.validateOperation(op, from, to); err != nil {
		return nil, err
	}
	needApproval := 0
	if from.City == nil || to.City == nil || deref(from.City) != deref(to.City) {
		needApproval = 1
		if len(req.Items) > 1 {
			return nil, biz(40000, "跨本地网调拨仅支持单一物料，当前提交了 %d 种", len(req.Items))
		}
	}
	orderNo := "TR" + time.Now().Format("20060102") + fmt.Sprintf("%04d", rand.Intn(10000))
	total := 0
	for _, it := range req.Items {
		if it.Quantity != nil {
			total += *it.Quantity
		}
	}
	creator := deref(req.Creator)
	if creator == "" {
		creator = "system"
	}
	source := deref(req.Source)
	if source == "" {
		source = "MANUAL"
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO transfer_order (orderNo, operationType, fromWarehouse, toWarehouse, status,
			itemCount, totalQuantity, needApproval, creator, source, suggestionId, createdAt)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())`,
		orderNo, op, from.WarehouseCode, to.WarehouseCode, StApplied,
		len(req.Items), total, needApproval, creator, source, deref(req.SuggestionID)); err != nil {
		return nil, err
	}
	for _, it := range req.Items {
		if deref(it.MaterialCode) == "" || it.Quantity == nil || *it.Quantity <= 0 {
			return nil, biz(40000, "明细的物料编码与数量必须有效")
		}
		available, err := r.CountAvailable(ctx, from.WarehouseCode, deref(it.MaterialCode))
		if err != nil {
			return nil, err
		}
		if available < *it.Quantity {
			return nil, biz(50001, "仓位 %s 的物料 %s 可用库存不足：需要 %d，实际可用 %d",
				from.WarehouseCode, deref(it.MaterialCode), *it.Quantity, available)
		}
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO transfer_order_item (orderNo, materialCode, model, materialName, quantity, createdAt)
			VALUES (?, ?, ?, ?, ?, NOW())`,
			orderNo, deref(it.MaterialCode), deref(it.Model), deref(it.MaterialName), *it.Quantity); err != nil {
			return nil, err
		}
	}
	// 通知调出方仓管：有一条申请待确认出库
	if err := pushNotice(ctx, tx, from.WarehouseCode+":"+roleWarehouseKeeper, "ORDER_APPLIED",
		"有一条调拨申请待您确认出库",
		fmt.Sprintf("调拨单 %s：%s 申请从本仓调入 %d 台，请导入 SN 码并确认出库",
			orderNo, to.Name, total), orderNo); err != nil {
		return nil, err
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return r.OrderDetail(ctx, orderNo)
}

// ---- 出库 ----

// OutboundRequest 对齐 TransferOrderOutboundRequest。
type OutboundRequest struct {
	OrderNo   *string  `json:"orderNo"`
	SerialNos []string `json:"serialNos"`
	Operator  *string  `json:"operator"`
}

func allowedStatusFor(op string) map[string]bool {
	if op == OpRecall || op == OpReturn {
		return map[string]bool{TSAvailable: true, "待检": true, "待报废": true}
	}
	return map[string]bool{TSAvailable: true}
}

// Outbound 出库：四重校验 + 批量换状态。
func (r *Repository) Outbound(ctx context.Context, req *OutboundRequest) (*OrderVO, error) {
	if req == nil || deref(req.OrderNo) == "" {
		return nil, biz(40000, "调拨单号不能为空")
	}
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	order, err := r.OrderGet(ctx, tx, deref(req.OrderNo))
	if errors.Is(err, ErrNotFound) {
		return nil, biz(40400, "调拨单不存在")
	} else if err != nil {
		return nil, err
	}
	if deref(order.Status) != StApplied {
		return nil, biz(50001, "只有「待确认出库」的调拨申请可以确认出库，当前状态：%s", deref(order.Status))
	}
	if len(req.SerialNos) == 0 {
		return nil, biz(40000, "出库串码清单不能为空")
	}
	distinct := dedupe(req.SerialNos)
	items, err := r.OrderItems(ctx, tx, order.OrderNo)
	if err != nil {
		return nil, err
	}
	allowed := allowedStatusFor(deref(order.OperationType))

	itemByMaterial := map[string]*OrderItem{}
	for _, it := range items {
		code := deref(it.MaterialCode)
		if _, ok := itemByMaterial[code]; !ok {
			itemByMaterial[code] = it
		}
	}
	// 一次性查出这批串码
	placeholders := strings.TrimSuffix(strings.Repeat("?,", len(distinct)), ",")
	qargs := toAnySlice(distinct)
	rows, err := tx.QueryContext(ctx,
		`SELECT serialNo, warehouseCode, terminalStatus, materialCode FROM terminal
		 WHERE serialNo IN (`+placeholders+`)`, qargs...)
	if err != nil {
		return nil, err
	}
	type termRow struct {
		serial, warehouse, status, material string
	}
	bySerial := map[string]termRow{}
	for rows.Next() {
		var t termRow
		if err := rows.Scan(&t.serial, &t.warehouse, &t.status, &t.material); err != nil {
			rows.Close()
			return nil, err
		}
		bySerial[t.serial] = t
	}
	rows.Close()

	counted := map[string]int{}
	for _, serialNo := range distinct {
		t, ok := bySerial[serialNo]
		if !ok {
			return nil, biz(50001, "串码不存在：%s", serialNo)
		}
		if t.warehouse != deref(order.FromWarehouse) {
			return nil, biz(50001, "串码 %s 不在调出仓位 %s（当前在 %s）",
				serialNo, deref(order.FromWarehouse), t.warehouse)
		}
		if !allowed[t.status] {
			return nil, biz(50001, "串码 %s 的状态为「%s」，%s 仅允许 %v",
				serialNo, t.status, deref(order.OperationType), allowedKeys(allowed))
		}
		if _, ok := itemByMaterial[t.material]; !ok {
			return nil, biz(50001, "串码 %s 的物料 %s 不在本调拨单明细中", serialNo, t.material)
		}
		counted[t.material]++
	}
	for _, it := range items {
		if counted[deref(it.MaterialCode)] != derefInt(it.Quantity) {
			return nil, biz(50001, "物料 %s 出库数量不符：单据要求 %d，实际导入 %d",
				deref(it.MaterialCode), derefInt(it.Quantity), counted[deref(it.MaterialCode)])
		}
	}
	// 按源状态分组批量更新
	byStatus := map[string][]string{}
	for _, serialNo := range distinct {
		t := bySerial[serialNo]
		byStatus[t.status] = append(byStatus[t.status], serialNo)
	}
	updated := 0
	for fromStatus, sns := range byStatus {
		n, err := r.TransferBatch(ctx, tx, sns, fromStatus, TSInTransit, deref(order.FromWarehouse))
		if err != nil {
			return nil, err
		}
		updated += n
	}
	if updated != len(distinct) {
		return nil, biz(50001, "出库未全部生效（期望 %d 条，实际 %d 条），已回滚", len(distinct), updated)
	}
	// 串码回写明细
	for _, it := range items {
		var own []string
		for _, s := range distinct {
			if t, ok := bySerial[s]; ok && t.material == deref(it.MaterialCode) {
				own = append(own, s)
			}
		}
		snJSON := `["` + strings.Join(own, `","`) + `"]`
		if _, err := tx.ExecContext(ctx,
			`UPDATE transfer_order_item SET serialNos = ? WHERE id = ?`, snJSON, it.ID); err != nil {
			return nil, err
		}
	}
	if _, err := tx.ExecContext(ctx,
		`UPDATE transfer_order SET status = ?, outboundAt = NOW() WHERE orderNo = ?`,
		StOutbound, order.OrderNo); err != nil {
		return nil, err
	}
	if err := pushNotice(ctx, tx, deref(order.ToWarehouse)+":"+roleWarehouseKeeper, "OUTBOUND",
		"有货已出库，请确认入库",
		fmt.Sprintf("调拨单 %s 已由 %s 出库，共 %d 台（%d 个 SN），请确认入库或驳回入库",
			order.OrderNo, deref(order.FromWarehouse), len(distinct), len(distinct)),
		order.OrderNo); err != nil {
		return nil, err
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return r.OrderDetail(ctx, order.OrderNo)
}

// ---- 入库 / 驳回 ----

// ActionRequest 对齐 TransferOrderActionRequest。
type ActionRequest struct {
	OrderNo  *string `json:"orderNo"`
	Reason   *string `json:"reason"`
	Operator *string `json:"operator"`
}

// Inbound 入库确认：串码换到调入仓可用。
func (r *Repository) Inbound(ctx context.Context, req *ActionRequest) (*OrderVO, error) {
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	order, err := r.requireOrder(ctx, tx, req)
	if err != nil {
		return nil, err
	}
	if deref(order.Status) != StOutbound {
		return nil, biz(50001, "只有「已出库」的调拨单可以入库确认，当前状态：%s", deref(order.Status))
	}
	items, err := r.OrderItems(ctx, tx, order.OrderNo)
	if err != nil {
		return nil, err
	}
	var all []string
	for _, it := range items {
		all = append(all, ParseSerialNos(it.SerialNos)...)
	}
	if len(all) == 0 {
		return nil, biz(50001, "该调拨单没有串码记录（出库未完成？），无法入库")
	}
	moved, err := r.TransferBatch(ctx, tx, dedupe(all), TSInTransit, TSAvailable, deref(order.ToWarehouse))
	if err != nil {
		return nil, err
	}
	if moved != len(dedupe(all)) {
		return nil, biz(50001, "入库未全部生效（期望 %d 条，实际 %d 条），已回滚", len(dedupe(all)), moved)
	}
	if _, err := tx.ExecContext(ctx,
		`UPDATE transfer_order SET status = ?, inboundAt = NOW() WHERE orderNo = ?`,
		StDone, order.OrderNo); err != nil {
		return nil, err
	}
	if err := pushNotice(ctx, tx, deref(order.Creator), "DONE",
		"入库已确认，调拨完成",
		fmt.Sprintf("调拨单 %s 已由 %s 确认入库，共 %d 台", order.OrderNo, deref(order.ToWarehouse), moved),
		order.OrderNo); err != nil {
		return nil, err
	}
	if err := pushNotice(ctx, tx, deref(order.FromWarehouse)+":"+roleWarehouseKeeper, "DONE",
		"调拨完成：对方已确认入库",
		fmt.Sprintf("调拨单 %s：%d 台已由 %s 确认入库完成", order.OrderNo, moved, deref(order.ToWarehouse)),
		order.OrderNo); err != nil {
		return nil, err
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return r.OrderDetail(ctx, order.OrderNo)
}

// Reject 驳回：APPLIED 直接拒；OUTBOUND 在途退回 B 仓可用。
func (r *Repository) Reject(ctx context.Context, req *ActionRequest) (*OrderVO, error) {
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	order, err := r.requireOrder(ctx, tx, req)
	if err != nil {
		return nil, err
	}
	reason := deref(req.Reason)
	if reason == "" {
		reason = "未填写原因"
	}
	switch deref(order.Status) {
	case StApplied:
		if _, err := tx.ExecContext(ctx,
			`UPDATE transfer_order SET status = ?, rejectReason = ? WHERE orderNo = ?`,
			StRejected, reason, order.OrderNo); err != nil {
			return nil, err
		}
		if err := pushNotice(ctx, tx, deref(order.Creator), "REJECTED",
			"调拨申请被拒绝",
			fmt.Sprintf("调拨单 %s 被 %s 拒绝：%s", order.OrderNo, deref(order.FromWarehouse), reason),
			order.OrderNo); err != nil {
			return nil, err
		}
		if err := tx.Commit(); err != nil {
			return nil, err
		}
		return r.OrderDetail(ctx, order.OrderNo)
	case StOutbound:
		items, err := r.OrderItems(ctx, tx, order.OrderNo)
		if err != nil {
			return nil, err
		}
		var all []string
		for _, it := range items {
			all = append(all, ParseSerialNos(it.SerialNos)...)
		}
		returned := 0
		if len(all) > 0 {
			returned, err = r.TransferBatch(ctx, tx, dedupe(all), TSInTransit, TSAvailable, deref(order.FromWarehouse))
			if err != nil {
				return nil, err
			}
			if returned != len(dedupe(all)) {
				return nil, biz(50001, "驳回回滚未全部生效（期望 %d 条，实际 %d 条），已回滚", len(dedupe(all)), returned)
			}
		}
		if _, err := tx.ExecContext(ctx,
			`UPDATE transfer_order SET status = ?, rejectReason = ? WHERE orderNo = ?`,
			StRejected, reason, order.OrderNo); err != nil {
			return nil, err
		}
		if err := pushNotice(ctx, tx, deref(order.Creator), "REJECTED",
			"入库被驳回，货已退回",
			fmt.Sprintf("调拨单 %s 被 %s 驳回入库：%s；%d 台已退回 %s",
				order.OrderNo, deref(order.ToWarehouse), reason, returned, deref(order.FromWarehouse)),
			order.OrderNo); err != nil {
			return nil, err
		}
		if err := pushNotice(ctx, tx, deref(order.FromWarehouse)+":"+roleWarehouseKeeper, "REJECTED",
			"出库货品被退回本仓",
			fmt.Sprintf("调拨单 %s 被调入方驳回入库：%s；%d 台已退回本仓可用库存",
				order.OrderNo, reason, returned),
			order.OrderNo); err != nil {
			return nil, err
		}
		if err := tx.Commit(); err != nil {
			return nil, err
		}
		return r.OrderDetail(ctx, order.OrderNo)
	default:
		return nil, biz(50001, "当前状态（%s）不支持驳回：仅「待确认出库」的申请与「待确认入库」的在途单可驳回",
			deref(order.Status))
	}
}

// ---- 通知 ----

func pushNotice(ctx context.Context, tx *sql.Tx, receiver, noticeType, title, content, orderNo string) error {
	_, err := tx.ExecContext(ctx, `
		INSERT INTO notice (receiver, noticeType, title, content, orderNo, isRead, createdAt)
		VALUES (?, ?, ?, ?, ?, 0, NOW())`,
		receiver, noticeType, title, content, orderNo)
	return err
}

// NoticeUpsertRequest 对齐 NoticeUpsertRequest。
type NoticeUpsertRequest struct {
	Receiver   *string `json:"receiver"`
	NoticeType *string `json:"noticeType"`
	Title      *string `json:"title"`
	Content    *string `json:"content"`
	OrderNo    *string `json:"orderNo"`
}

// UpsertNotice 幂等写/刷新通知（同 receiver+type 未读只刷新）。
func (r *Repository) UpsertNotice(ctx context.Context, req *NoticeUpsertRequest) (map[string]any, error) {
	if deref(req.Receiver) == "" || deref(req.NoticeType) == "" {
		return nil, biz(40000, "receiver 与 noticeType 必填")
	}
	var id int64
	err := r.db.QueryRowContext(ctx, `
		SELECT id FROM notice
		WHERE receiver = ? AND noticeType = ? AND isRead = 0
		ORDER BY createdAt DESC LIMIT 1`,
		deref(req.Receiver), deref(req.NoticeType)).Scan(&id)
	switch {
	case err == nil:
		_, err := r.db.ExecContext(ctx, `
			UPDATE notice SET title = ?, content = ?, createdAt = NOW() WHERE id = ?`,
			deref(req.Title), clipContent(deref(req.Content)), id)
		if err != nil {
			return nil, err
		}
		return map[string]any{"action": "updated", "id": id}, nil
	case errors.Is(err, sql.ErrNoRows):
		res, err := r.db.ExecContext(ctx, `
			INSERT INTO notice (receiver, noticeType, title, content, orderNo, isRead, createdAt)
			VALUES (?, ?, ?, ?, ?, 0, NOW())`,
			deref(req.Receiver), deref(req.NoticeType), deref(req.Title),
			clipContent(deref(req.Content)), deref(req.OrderNo))
		if err != nil {
			return nil, err
		}
		newID, _ := res.LastInsertId()
		return map[string]any{"action": "inserted", "id": newID}, nil
	default:
		return nil, err
	}
}

// ListNotices 按仓+角色+本人取件。
func (r *Repository) ListNotices(ctx context.Context, warehouseCode, role, userCode string, limit int) ([]*Notice, error) {
	var keys []any
	if warehouseCode != "" && role != "" {
		keys = append(keys, warehouseCode+":"+role)
	}
	if warehouseCode != "" {
		keys = append(keys, warehouseCode)
	}
	if userCode != "" {
		keys = append(keys, userCode)
	}
	if len(keys) == 0 {
		return []*Notice{}, nil
	}
	if limit < 1 {
		limit = 1
	}
	if limit > 50 {
		limit = 50
	}
	placeholders := strings.TrimSuffix(strings.Repeat("?,", len(keys)), ",")
	args := append([]any{}, keys...)
	args = append(args, limit)
	rows, err := r.db.QueryContext(ctx, `
		SELECT id, receiver, noticeType, title, content, orderNo, isRead, createdAt
		FROM notice WHERE receiver IN (`+placeholders+`)
		ORDER BY createdAt DESC LIMIT ?`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []*Notice
	for rows.Next() {
		var n Notice
		var receiver, noticeType, title, content, orderNo sql.NullString
		var isRead sql.NullInt64
		var created sql.NullTime
		if err := rows.Scan(&n.ID, &receiver, &noticeType, &title, &content, &orderNo, &isRead, &created); err != nil {
			return nil, err
		}
		n.Receiver, n.NoticeType, n.Title, n.Content, n.OrderNo =
			nstr(receiver), nstr(noticeType), nstr(title), nstr(content), nstr(orderNo)
		n.IsRead, n.CreatedAt = nint(isRead), ntime(created)
		list = append(list, &n)
	}
	return list, rows.Err()
}

// ---- 分页 / SN 清单 ----

// PageOrders 分页（visibleScope 非空时只能收紧不能放大）。
func (r *Repository) PageOrders(ctx context.Context, q map[string]string, visibleScope map[string]bool) (map[string]any, error) {
	where, args := "1=1", []any{}
	for col, key := range map[string]string{
		"orderNo": "orderNo", "status": "status", "operationType": "operationType",
		"fromWarehouse": "fromWarehouse", "toWarehouse": "toWarehouse",
	} {
		if v := q[key]; v != "" {
			where += " AND " + col + " = ?"
			args = append(args, v)
		}
	}
	// 时间筛选（按创建时间；止日期只给日期时含整天）
	if v := q["startTime"]; v != "" {
		if t, ok := parseTimeParam(v, false); ok {
			where += " AND createdAt >= ?"
			args = append(args, t)
		}
	}
	if v := q["endTime"]; v != "" {
		if t, ok := parseTimeParam(v, true); ok {
			where += " AND createdAt <= ?"
			args = append(args, t)
		}
	}
	if visibleScope != nil {
		codes := make([]string, 0, len(visibleScope))
		for c := range visibleScope {
			codes = append(codes, c)
		}
		ph := strings.TrimSuffix(strings.Repeat("?,", len(codes)), ",")
		where += " AND (fromWarehouse IN (" + ph + ") OR toWarehouse IN (" + ph + "))"
		for _, c := range codes {
			args = append(args, c)
		}
		for _, c := range codes {
			args = append(args, c)
		}
	}
	current, size := 1, 10
	if n, err := atoi64(q["current"]); err == nil && n > 0 {
		current = int(n)
	}
	if n, err := atoi64(q["pageSize"]); err == nil && n > 0 {
		size = int(n)
	}
	if size > 50 {
		size = 50
	}
	var total int64
	if err := r.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM transfer_order WHERE `+where, args...).Scan(&total); err != nil {
		return nil, err
	}
	offset := (current - 1) * size
	rows, err := r.db.QueryContext(ctx,
		`SELECT `+orderCols+` FROM transfer_order WHERE `+where+` ORDER BY createdAt DESC LIMIT ? OFFSET ?`,
		append(args, size, offset)...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	records := []*TransferOrder{}
	for rows.Next() {
		o, err := scanOrder(rows.Scan)
		if err != nil {
			return nil, err
		}
		records = append(records, o)
	}
	pages := int64(0)
	if size > 0 {
		pages = (total + int64(size) - 1) / int64(size)
	}
	return map[string]any{
		"records": records, "total": total, "size": size,
		"current": current, "pages": pages, "orders": []any{},
	}, nil
}

// BuildSnFile 一键生成出库 SN 清单（只读，库龄倒序抓取）。
func (r *Repository) BuildSnFile(ctx context.Context, orderNo string) ([]map[string]any, error) {
	if orderNo == "" {
		return nil, biz(40000, "调拨单号不能为空")
	}
	order, err := r.OrderGet(ctx, nil, orderNo)
	if errors.Is(err, ErrNotFound) {
		return nil, biz(40400, "调拨单不存在")
	} else if err != nil {
		return nil, err
	}
	items, err := r.OrderItems(ctx, nil, orderNo)
	if err != nil {
		return nil, err
	}
	var result []map[string]any
	for _, it := range items {
		qty := derefInt(it.Quantity)
		if qty <= 0 {
			qty = 0
		}
		sns, err := r.ListAvailableSerialNos(ctx, deref(order.FromWarehouse), deref(it.MaterialCode), qty)
		if err != nil {
			return nil, err
		}
		if sns == nil {
			sns = []string{}
		}
		result = append(result, map[string]any{
			"materialCode": it.MaterialCode, "materialName": it.MaterialName,
			"model": it.Model, "quantity": it.Quantity, "serialNos": sns,
		})
	}
	return result, nil
}

// ---- 告警扫描（StockAlertScanner）----

// ScanFinding / ScanResult 对齐 StockAlertScanner 的 record。
type ScanFinding struct {
	WarehouseCode   string         `json:"warehouseCode"`
	WarehouseName   string         `json:"warehouseName"`
	SuggestionCount int            `json:"suggestionCount"`
	Notice          map[string]any `json:"notice"`
}

type ScanResult struct {
	Scanned  int            `json:"scanned"`
	Findings []*ScanFinding `json:"findings"`
}

// Scan 告警巡检（只查+判定，不写通知）。
func (r *Repository) Scan(ctx context.Context, warehouseCode string) (*ScanResult, error) {
	var warehouses []*Warehouse
	if warehouseCode != "" {
		w, err := r.WarehouseGet(ctx, warehouseCode)
		if errors.Is(err, ErrNotFound) {
			warehouses = []*Warehouse{}
		} else if err != nil {
			return nil, err
		} else {
			warehouses = []*Warehouse{w}
		}
	} else {
		var err error
		warehouses, err = r.WarehouseListAllOrdered(ctx)
		if err != nil {
			return nil, err
		}
	}
	result := &ScanResult{Scanned: len(warehouses), Findings: []*ScanFinding{}}
	for _, wh := range warehouses {
		health, err := r.InventoryHealth(ctx, wh.WarehouseCode)
		if err != nil || len(health.Suggestions) == 0 {
			continue
		}
		top := health.Suggestions[0]
		var b strings.Builder
		b.WriteString(health.Summary)
		b.WriteByte('\n')
		avail := "0"
		if top.AvailableAtFrom > 0 {
			avail = fmt.Sprintf("%d", top.AvailableAtFrom)
		}
		fmt.Fprintf(&b, "首要建议：%s %s，从 %s 调入 %d 台（对方可用 %s 台）",
			deref(top.Model), deref(top.MaterialName), top.FromWarehouseName, top.SuggestQty, avail)
		if len(health.Suggestions) > 1 {
			fmt.Fprintf(&b, "；另有 %d 条型号建议", len(health.Suggestions)-1)
		}
		b.WriteString("\n点此通知可直接查看补货建议并一键建单。")
		result.Findings = append(result.Findings, &ScanFinding{
			WarehouseCode: wh.WarehouseCode, WarehouseName: wh.Name,
			SuggestionCount: len(health.Suggestions),
			Notice: map[string]any{
				"receiver":   wh.WarehouseCode + ":" + roleAccountManager,
				"noticeType": typeStockAlert,
				"title": fmt.Sprintf("库存告警：%s 有 %d 条调拨建议待处理",
					wh.Name, len(health.Suggestions)),
				"content": clipContent(b.String()),
			},
		})
	}
	return result, nil
}

// ---- 工具 ----

// ParseSerialNos 解析明细串码 JSON（["a","b"]）。
func ParseSerialNos(p *string) []string {
	if p == nil || *p == "" {
		return []string{}
	}
	var arr []string
	if err := json.Unmarshal([]byte(*p), &arr); err == nil {
		if arr == nil {
			return []string{}
		}
		return arr
	}
	// Java 版按逗号切 + 去引号的宽松解析，兜底保留
	body := strings.TrimSpace(*p)
	body = strings.TrimPrefix(body, "[")
	body = strings.TrimSuffix(body, "]")
	var out []string
	for _, s := range strings.Split(body, ",") {
		s = strings.TrimSpace(strings.ReplaceAll(s, `"`, ""))
		if s != "" {
			out = append(out, s)
		}
	}
	if out == nil {
		return []string{}
	}
	return out
}

func (r *Repository) requireOrder(ctx context.Context, tx *sql.Tx, req *ActionRequest) (*TransferOrder, error) {
	if req == nil || deref(req.OrderNo) == "" {
		return nil, biz(40000, "调拨单号不能为空")
	}
	o, err := r.OrderGet(ctx, tx, deref(req.OrderNo))
	if errors.Is(err, ErrNotFound) {
		return nil, biz(40400, "调拨单不存在")
	}
	return o, err
}

func dedupe(in []string) []string {
	seen := map[string]bool{}
	var out []string
	for _, s := range in {
		if !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	return out
}

func allowedKeys(m map[string]bool) []string {
	var out []string
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

func clipContent(s string) string {
	if len(s) <= 1000 {
		return s
	}
	return s[:1000]
}

func parseTimeParam(raw string, endOfDay bool) (string, bool) {
	s := strings.TrimSpace(raw)
	dateOnly := len(s) <= 10
	var layout = "2006-01-02 15:04:05"
	if dateOnly {
		layout = "2006-01-02"
	}
	t, err := time.ParseInLocation(layout, s, time.Local)
	if err != nil {
		return "", false
	}
	if endOfDay && dateOnly {
		return t.Add(24*time.Hour - time.Second).Format("2006-01-02 15:04:05"), true
	}
	return t.Format("2006-01-02 15:04:05"), true
}

func atoi64(s string) (int64, error) {
	var n int64
	_, err := fmt.Sscanf(s, "%d", &n)
	return n, err
}

func derefInt(p *int) int {
	if p == nil {
		return 0
	}
	return *p
}
