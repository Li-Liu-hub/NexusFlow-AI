// biz 包功能点测试：全内存假适配器驱动，覆盖简历两条要点的关键行为。
//   - 9 态状态机推进、100 条/批、>24h 超时标记、采集重试 ≤30、单条失败不影响整批
//   - 双路抽取合并（非空合并、正文优先）、JSON Schema 校验（21 项/类型/必填）
//   - 工单：requestId 防重复提交、远端成功本地失败只补写、7 类白名单
package biz

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"
)

// ---- 内存假仓储与假适配器 ----

type fakeStore struct {
	files    map[string]*File
	extract  map[string]extractRow
	contents map[string]string
	audits   map[string]string
	seq      int
}

type extractRow struct {
	body, audit, merged map[string]any
	bodyModel           string
}

func newFakeStore() *fakeStore {
	return &fakeStore{files: map[string]*File{}, extract: map[string]extractRow{}}
}

func (f *fakeStore) clone(fr *File) *File {
	cp := *fr
	return &cp
}

func (f *fakeStore) PickByState(ctx context.Context, state string, limit int) ([]File, error) {
	var out []File
	for _, fr := range f.files {
		if fr.State == state && len(out) < limit {
			out = append(out, *f.clone(fr))
		}
	}
	return out, nil
}

func (f *fakeStore) Transition(ctx context.Context, fileNo, from, to string, extra ...func(*updateArgs)) error {
	fr, ok := f.files[fileNo]
	if !ok || fr.State != from {
		return fmt.Errorf("状态不符: %s(期望 %s)", frState(f.files, fileNo), from)
	}
	args := &updateArgs{}
	for _, fn := range extra {
		fn(args)
	}
	fr.State = to
	applyExtra(fr, args)
	return nil
}

func frState(m map[string]*File, no string) string {
	if fr, ok := m[no]; ok {
		return fr.State
	}
	return "?"
}

func applyExtra(fr *File, args *updateArgs) {
	for i, set := range args.sets {
		v := args.vals[i]
		switch set {
		case "s3_key = ?":
			s := v.(string)
			fr.S3Key = &s
		case "content = ?":
			// demo 假仓储不存 content
		case "timeout_flag = ?":
		}
	}
}

func (f *fakeStore) MarkTimeout(ctx context.Context, fileNo string) error {
	f.files[fileNo].TimeoutFlag = true
	return nil
}

func (f *fakeStore) IncRetry(ctx context.Context, fileNo string) (int, error) {
	f.files[fileNo].RetryCount++
	return f.files[fileNo].RetryCount, nil
}

func (f *fakeStore) SetError(ctx context.Context, fileNo, msg string) error {
	f.files[fileNo].ErrorMsg = &msg
	return nil
}

func (f *fakeStore) GetContent(ctx context.Context, fileNo string) (string, error) {
	return f.contents[fileNo], nil
}

func (f *fakeStore) SaveExtract(ctx context.Context, fileNo string, body, audit, merged map[string]any, bodyModel, auditModel string) error {
	f.extract[fileNo] = extractRow{body: body, audit: audit, merged: merged, bodyModel: bodyModel}
	return nil
}

func (f *fakeStore) Notify(ctx context.Context, fileNo, fileName, batchNo, content string) error {
	f.files[fileNo] = &File{FileNo: fileNo, State: StateUploaded, FileName: fileName}
	f.contents[fileNo] = content
	return nil
}

// 补齐 fake 需要的字段
var _ = sql.ErrNoRows

func (f *fakeStore) GetAuditHTML(ctx context.Context, fileNo string) (string, error) {
	return f.audits[fileNo], nil
}
func (f *fakeStore) GetExtract(ctx context.Context, fileNo string) (map[string]any, error) {
	if r, ok := f.extract[fileNo]; ok {
		return r.merged, nil
	}
	return nil, errors.New("not found")
}
func (f *fakeStore) GetByFileNo(ctx context.Context, fileNo string) (*File, error) {
	if fr, ok := f.files[fileNo]; ok {
		return f.clone(fr), nil
	}
	return nil, errors.New("not found")
}
func (f *fakeStore) List(ctx context.Context, state string, limit int) ([]File, error) {
	return f.PickByState(ctx, state, limit)
}

// fake 需要的附加存储
func (f *fakeStore) initExtra() {
	if f.contents == nil {
		f.contents = map[string]string{}
	}
	if f.audits == nil {
		f.audits = map[string]string{}
	}
}

// ---- 假适配器 ----

type fakeArchive struct{ keys []string }

func (a *fakeArchive) Archive(ctx context.Context, fileNo, content string) (string, error) {
	a.keys = append(a.keys, fileNo)
	return "s3://" + fileNo, nil
}

type fakeCollect struct{ err error }

func (c *fakeCollect) Collect(ctx context.Context, f File) (string, error) {
	if c.err != nil {
		return "", c.err
	}
	return "合同正文内容", nil
}

type fakeExtract struct {
	body, audit       map[string]any
	bodyErr, auditErr error
}

func (e *fakeExtract) ExtractBody(ctx context.Context, content string) (map[string]any, error) {
	return e.body, e.bodyErr
}
func (e *fakeExtract) ExtractAudit(ctx context.Context, html string) (map[string]any, error) {
	return e.audit, e.auditErr
}
func (e *fakeExtract) BodyModel() string  { return "group-llm" }
func (e *fakeExtract) AuditModel() string { return "qwen" }

// ---- 测试 ----

func newProcessor(store *fakeStore, collect Collector, extract Extractor) (*Processor, *fakeArchive) {
	arch := &fakeArchive{}
	return &Processor{Store: store, Archive: arch, Collect: collect, Extract: extract,
		Now: func() time.Time { return testNow }}, arch
}

var testNow = time.Date(2026, 10, 25, 12, 0, 0, 0, time.Local)

func TestProcessor_9态全链路推进至DONE(t *testing.T) {
	st := newFakeStore()
	st.initExtra()
	st.Notify(context.Background(), "F001", "合同A.txt", "B1", "正文")
	proc, _ := newProcessor(st, &fakeCollect{}, &fakeExtract{
		body: map[string]any{"contract_no": "HT-001", "contract_name": "终端采购合同", "contract_type": "采购",
			"party_a_name": "甲方公司", "party_b_name": "乙方公司", "amount": 100.5, "sign_date": "2026-01-02"},
	})
	// UPLOADED→COLLECTING→COLLECTED→ARCHIVED→EXTRACTING→(抽取+EXTRACTED)→WRITING→DONE
	for i := 0; i < 8; i++ {
		if _, err := proc.Step(context.Background()); err != nil {
			t.Fatalf("step %d: %v", i, err)
		}
	}
	f, _ := st.GetByFileNo(context.Background(), "F001")
	if f.State != StateDone {
		t.Fatalf("终态 = %s, 错误 = %v", f.State, f.ErrorMsg)
	}
	if !strings.HasPrefix(*f.S3Key, "s3://") {
		t.Fatalf("归档 key = %v", f.S3Key)
	}
	if st.extract["F001"].merged["contract_no"] != "HT-001" {
		t.Fatalf("合并字段缺失: %+v", st.extract["F001"].merged)
	}
}

func TestProcessor_上传等待超24h标记超时(t *testing.T) {
	st := newFakeStore()
	st.initExtra()
	st.Notify(context.Background(), "F001", "合同A.txt", "B1", "正文")
	st.files["F001"].CreatedAt = &[]time.Time{testNow.Add(-25 * time.Hour)}[0]
	proc, _ := newProcessor(st, &fakeCollect{}, &fakeExtract{})
	_, _ = proc.Step(context.Background())
	if !st.files["F001"].TimeoutFlag {
		t.Fatal("超 24h 应标记超时")
	}
}

func TestProcessor_采集失败重试30次后转FAILED(t *testing.T) {
	st := newFakeStore()
	st.initExtra()
	st.Notify(context.Background(), "F001", "合同A.txt", "B1", "正文")
	proc, _ := newProcessor(st, &fakeCollect{err: errors.New("SFTP 连不上")}, &fakeExtract{})
	_, _ = proc.Step(context.Background()) // → COLLECTING
	for i := 0; i < MaxCollectRetry; i++ {
		if _, err := proc.Step(context.Background()); err != nil {
			t.Fatalf("step %d: %v", i, err)
		}
	}
	f, _ := st.GetByFileNo(context.Background(), "F001")
	if f.RetryCount != MaxCollectRetry {
		t.Fatalf("重试计数 = %d, 期望 %d", f.RetryCount, MaxCollectRetry)
	}
	if f.State != StateFailed {
		t.Fatalf("重试耗尽应 FAILED, 实际 %s", f.State)
	}
}

func TestProcessor_单条失败不影响整批(t *testing.T) {
	st := newFakeStore()
	st.initExtra()
	st.Notify(context.Background(), "GOOD", "好.txt", "B1", "正文")
	st.Notify(context.Background(), "BAD", "坏.txt", "B1", "正文")
	// BAD 永远采集失败，GOOD 正常
	proc := &Processor{Store: st,
		Archive: &fakeArchive{},
		Collect: failOneCollector{bad: "BAD"},
		Extract: &fakeExtract{body: map[string]any{"contract_no": "X", "contract_name": "n", "contract_type": "t",
			"party_a_name": "a", "party_b_name": "b", "amount": 1.0, "sign_date": "2026-01-01"}},
		Now:     func() time.Time { return testNow }}
	for i := 0; i < MaxCollectRetry+5; i++ {
		if _, err := proc.Step(context.Background()); err != nil {
			t.Fatalf("step %d: %v", i, err)
		}
	}
	g, _ := st.GetByFileNo(context.Background(), "GOOD")
	b, _ := st.GetByFileNo(context.Background(), "BAD")
	if g.State != StateDone {
		t.Fatalf("GOOD 应 DONE, 实际 %s（单条失败不得拖累整批）", g.State)
	}
	if b.State != StateFailed {
		t.Fatalf("BAD 应 FAILED, 实际 %s", b.State)
	}
}

type failOneCollector struct{ bad string }

func (c failOneCollector) Collect(ctx context.Context, f File) (string, error) {
	if f.FileNo == c.bad {
		return "", errors.New("故障")
	}
	return "内容", nil
}

func TestMergeFields_正文优先审签单补缺(t *testing.T) {
	body := map[string]any{"contract_no": "HT-9", "amount": 88.0}
	audit := map[string]any{"contract_no": "HT-WRONG", "sign_date": "2026-02-03"}
	merged := MergeFields(body, audit)
	if merged["contract_no"] != "HT-9" {
		t.Fatalf("正文应优先: %v", merged)
	}
	if merged["sign_date"] != "2026-02-03" {
		t.Fatalf("审签单应补缺: %v", merged)
	}
}

func TestValidateFields_必填缺失与未知字段与类型(t *testing.T) {
	ok := map[string]any{"contract_no": "1", "contract_name": "n", "contract_type": "t",
		"party_a_name": "a", "party_b_name": "b", "amount": 1.0, "sign_date": "2026-01-01"}
	if err := ValidateFields(mapCopy(ok)); err != nil {
		t.Fatalf("合法字段不应报错: %v", err)
	}
	bad := mapCopy(ok)
	bad["amount"] = "一百块"
	if err := ValidateFields(bad); err == nil || !strings.Contains(err.Error(), "应为数字") {
		t.Fatalf("金额类型校验: %v", err)
	}
	bad2 := mapCopy(ok)
	bad2["hacker_field"] = "x"
	if err := ValidateFields(bad2); err == nil || !strings.Contains(err.Error(), "未知字段") {
		t.Fatalf("未知字段校验: %v", err)
	}
	bad3 := mapCopy(ok)
	delete(bad3, "party_b_name")
	if err := ValidateFields(bad3); err == nil || !strings.Contains(err.Error(), "必填字段缺失") {
		t.Fatalf("必填校验: %v", err)
	}
}

func mapCopy(m map[string]any) map[string]any {
	out := map[string]any{}
	for k, v := range m {
		out[k] = v
	}
	return out
}

// ---- 工单建单 ----

type fakePlatform struct {
	calls   int
	err     error
	created []string
}

func (p *fakePlatform) Create(ctx context.Context, category string, params map[string]any) (string, error) {
	if p.err != nil {
		return "", p.err
	}
	p.calls++
	id := fmt.Sprintf("TF-%03d", p.calls)
	p.created = append(p.created, id)
	return id, nil
}

type fakeTicketStore struct {
	rows     map[string]*TicketRequest
	failSave bool
}

func (s *fakeTicketStore) FindByRequestID(ctx context.Context, id string) (*TicketRequest, error) {
	if r, ok := s.rows[id]; ok {
		return r, nil
	}
	return nil, sql.ErrNoRows
}
func (s *fakeTicketStore) SaveClassified(ctx context.Context, r *TicketRequest) error {
	if s.failSave {
		return errors.New("本地落库失败")
	}
	if _, ok := s.rows[r.RequestID]; !ok {
		r.ID = int64(len(s.rows) + 1)
		s.rows[r.RequestID] = r
	}
	return nil
}
func (s *fakeTicketStore) WriteBack(ctx context.Context, requestID, category string,
	params map[string]any, platformID string, existing *TicketRequest) error {
	if s.failSave {
		return errors.New("本地落库失败")
	}
	if existing != nil {
		existing.State = "saved"
		existing.PlatformID = &platformID
		existing.ErrorMessage = nil
		return nil
	}
	pid := platformID
	s.rows[requestID] = &TicketRequest{RequestID: requestID, State: "saved", PlatformID: &pid}
	return nil
}
func (s *fakeTicketStore) History(ctx context.Context, userID string, limit int) ([]*TicketRequest, error) {
	return nil, nil
}

func TestCreateTicket_重复提交返回首次结果不重建(t *testing.T) {
	store := &fakeTicketStore{rows: map[string]*TicketRequest{}}
	platform := &fakePlatform{}
	ctx := context.Background()
	r1, err := CreateTicket(ctx, store, platform, "req-1", "u1", "pc", "fault_repair",
		map[string]any{"title": "x"})
	if err != nil || r1.Duplicate {
		t.Fatalf("首次: %+v %v", r1, err)
	}
	r2, err := CreateTicket(ctx, store, platform, "req-1", "u1", "app", "fault_repair",
		map[string]any{"title": "x"})
	if err != nil || !r2.Duplicate {
		t.Fatalf("重复提交应 duplicate: %+v %v", r2, err)
	}
	if platform.calls != 1 {
		t.Fatalf("平台应只被调用 1 次, 实际 %d", platform.calls)
	}
}

func TestCreateTicket_远端成功本地失败只标记created(t *testing.T) {
	store := &fakeTicketStore{rows: map[string]*TicketRequest{}, failSave: true}
	platform := &fakePlatform{}
	res, err := CreateTicket(context.Background(), store, platform, "req-2", "u1", "pc",
		"use_apply", map[string]any{"title": "x"})
	if err != nil {
		t.Fatalf("err = %v", err)
	}
	if res.State != "created" || platform.calls != 1 {
		t.Fatalf("res = %+v calls = %d", res, platform.calls)
	}
}

func TestCreateTicket_补写不重创建(t *testing.T) {
	store := &fakeTicketStore{rows: map[string]*TicketRequest{
		"req-3": {RequestID: "req-3", State: "created", Category: "use_apply"}}}
	platform := &fakePlatform{}
	res, err := CreateTicket(context.Background(), store, platform, "req-3", "u1", "pc",
		"use_apply", map[string]any{"title": "x"})
	if err != nil || res.State != "saved" {
		t.Fatalf("补写应 saved: %+v %v", res, err)
	}
	if platform.calls != 0 {
		t.Fatalf("补写不得再调平台, 实际 %d 次", platform.calls)
	}
}

func TestCreateTicket_远端失败不重建不误报成功(t *testing.T) {
	store := &fakeTicketStore{rows: map[string]*TicketRequest{}}
	platform := &fakePlatform{err: errors.New("平台超时")}
	res, err := CreateTicket(context.Background(), store, platform, "req-4", "u1", "pc",
		"use_apply", map[string]any{"title": "x"})
	if err != nil {
		t.Fatalf("远端失败应作为结果而非错误: %v", err)
	}
	if res.State != "failed" || res.TicketID != nil {
		t.Fatalf("res = %+v", res)
	}
	// 重试同一 requestId：远端恢复后正常建单
	platform.err = nil
	res2, _ := CreateTicket(context.Background(), store, platform, "req-4", "u1", "pc",
		"use_apply", map[string]any{"title": "x"})
	if res2.State != "saved" {
		t.Fatalf("恢复后应 saved: %+v", res2)
	}
}

func TestValidCategory_7类白名单(t *testing.T) {
	if !ValidCategory("fault_repair") || ValidCategory("no_such") {
		t.Fatal("白名单判断错误")
	}
	if len(TicketCategories) != 7 {
		t.Fatalf("应为 7 类, 实际 %d", len(TicketCategories))
	}
}
