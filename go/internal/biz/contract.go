// Package biz 是 bizsuite 服务（:8130）的业务域：合同信智能抽取 + AI 辅助工单建单。
//
// 本文件：合同信 9 态状态机与批处理（简历要点：9 态管理 上传通知→SFTP采集→S3归档→
// AI抽取→字段回写；100 条/批；上传等待 >24h 标记超时；采集失败重试 ≤30 次；
// 单条失败不影响整批）。
//
// 外部依赖全部适配器化：SFTP/S3 在 demo 里由本地目录实现（接口一致，生产可换），
// LLM 走 OpenAI 兼容端点（见 extract.go）。
package biz

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"
)

// ---- 9 态状态机 ----

const (
	StateUploaded   = "UPLOADED"   // 1 上传通知已接收
	StateCollecting = "COLLECTING" // 2 SFTP 采集中（等待超 24h 标记超时）
	StateCollected  = "COLLECTED"  // 3 已采集
	StateArchived   = "ARCHIVED"   // 4 S3 已归档
	StateExtracting = "EXTRACTING" // 5 AI 抽取中
	StateExtracted  = "EXTRACTED"  // 6 抽取完成（JSON Schema 校验通过）
	StateWriting    = "WRITING"    // 7 字段回写中
	StateDone       = "DONE"       // 8 完成（21 项合同信息已回写）
	StateFailed     = "FAILED"     // 9 失败（采集重试耗尽 / 抽取不可恢复）

	MaxCollectRetry = 30             // 采集失败重试上限
	UploadWaitLimit = 24 * time.Hour // 上传等待超时阈值
	BatchSize       = 100            // 批处理粒度：100 条/批
)

// ErrSkip 单条跳过（批处理继续下一条——单条失败不影响整批）。
var ErrSkip = errors.New("biz: 单条跳过")

// File 合同文件一行（contract_file）。
type File struct {
	ID          int64      `json:"id"`
	FileNo      string     `json:"fileNo"`
	BatchNo     string     `json:"batchNo"`
	State       string     `json:"state"`
	RetryCount  int        `json:"retryCount"`
	TimeoutFlag bool       `json:"timeoutFlag"`
	SftpPath    *string    `json:"sftpPath"`
	S3Key       *string    `json:"s3Key"`
	FileName    string     `json:"fileName"`
	ErrorMsg    *string    `json:"errorMessage,omitempty"`
	CreatedAt   *time.Time `json:"createdAt"`
	UpdatedAt   *time.Time `json:"updatedAt"`
}

// Store 合同域仓储（MySQL api_db，增量表）。
type Store struct {
	db *sql.DB
}

// NewStore 构造。
func NewStore(db *sql.DB) *Store { return &Store{db: db} }

// EnsureSchema 增量建表（幂等）。
func (s *Store) EnsureSchema(ctx context.Context) error {
	for _, ddl := range []string{
		`CREATE TABLE IF NOT EXISTS contract_file (
		   id BIGINT AUTO_INCREMENT PRIMARY KEY,
		   file_no VARCHAR(64) NOT NULL UNIQUE,
		   batch_no VARCHAR(64) NOT NULL,
		   state VARCHAR(16) NOT NULL,
		   retry_count INT NOT NULL DEFAULT 0,
		   timeout_flag TINYINT NOT NULL DEFAULT 0,
		   sftp_path VARCHAR(255) NULL,
		   s3_key VARCHAR(255) NULL,
		   file_name VARCHAR(255) NOT NULL,
		   content MEDIUMTEXT NULL,
		   audit_html MEDIUMTEXT NULL,
		   error_message VARCHAR(500) NULL,
		   created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
		   updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
		   INDEX idx_cf_state (state), INDEX idx_cf_batch (batch_no)
		 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
		`CREATE TABLE IF NOT EXISTS contract_extract (
		   id BIGINT AUTO_INCREMENT PRIMARY KEY,
		   file_no VARCHAR(64) NOT NULL UNIQUE,
		   body_fields JSON NULL,
		   audit_fields JSON NULL,
		   merged_fields JSON NOT NULL,
		   body_model VARCHAR(64) NULL,
		   audit_model VARCHAR(64) NULL,
		   created_at DATETIME DEFAULT CURRENT_TIMESTAMP
		 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
	} {
		if _, err := s.db.ExecContext(ctx, ddl); err != nil {
			return err
		}
	}
	return nil
}

// Notify 上传通知：登记文件（state=UPLOADED）。
func (s *Store) Notify(ctx context.Context, fileNo, fileName, batchNo, content string) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO contract_file (file_no, batch_no, file_name, content, state)
		VALUES (?, ?, ?, ?, ?)
		ON DUPLICATE KEY UPDATE file_name = VALUES(file_name), batch_no = VALUES(batch_no)`,
		fileNo, batchNo, fileName, content, StateUploaded)
	return err
}

// PickByState 取某状态的一批（≤limit），按创建时间升序（先来先处理）。
func (s *Store) PickByState(ctx context.Context, state string, limit int) ([]File, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, file_no, batch_no, state, retry_count, timeout_flag,
		       sftp_path, s3_key, file_name, error_message, created_at, updated_at
		  FROM contract_file WHERE state = ? ORDER BY id LIMIT ?`, state, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanFiles(rows)
}

func scanFiles(rows *sql.Rows) ([]File, error) {
	var list []File
	for rows.Next() {
		var f File
		var sftp, s3key, errMsg sql.NullString
		var timeout int
		var created, updated sql.NullTime
		if err := rows.Scan(&f.ID, &f.FileNo, &f.BatchNo, &f.State, &f.RetryCount, &timeout,
			&sftp, &s3key, &f.FileName, &errMsg, &created, &updated); err != nil {
			return nil, err
		}
		f.TimeoutFlag = timeout == 1
		f.SftpPath, f.S3Key, f.ErrorMsg = nstr(sftp), nstr(s3key), nstr(errMsg)
		f.CreatedAt, f.UpdatedAt = ntime(created), ntime(updated)
		list = append(list, f)
	}
	return list, rows.Err()
}

func (s *Store) GetByFileNo(ctx context.Context, fileNo string) (*File, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, file_no, batch_no, state, retry_count, timeout_flag,
		       sftp_path, s3_key, file_name, error_message, created_at, updated_at
		  FROM contract_file WHERE file_no = ?`, fileNo)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	list, err := scanFiles(rows)
	if err != nil {
		return nil, err
	}
	if len(list) == 0 {
		return nil, fmt.Errorf("文件不存在: %s", fileNo)
	}
	return &list[0], nil
}

// Transition 单条状态原子跃迁（乐观锁：WHERE state = 期望前态）。
func (s *Store) Transition(ctx context.Context, fileNo, from, to string, extra ...func(*updateArgs)) error {
	args := updateArgs{sets: []string{"state = ?"}, vals: []any{to}}
	for _, fn := range extra {
		fn(&args)
	}
	args.vals = append(args.vals, fileNo, from)
	_, err := s.db.ExecContext(ctx,
		`UPDATE contract_file SET `+strings.Join(args.sets, ", ")+` WHERE file_no = ? AND state = ?`,
		args.vals...)
	return err
}

type updateArgs struct {
	sets []string
	vals []any
}

// MarkTimeout 上传等待超过 24h 的文件标记超时（幂等标记，不改状态）。
func (s *Store) MarkTimeout(ctx context.Context, fileNo string) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE contract_file SET timeout_flag = 1 WHERE file_no = ? AND timeout_flag = 0`, fileNo)
	return err
}

// IncRetry 采集失败重试计数 +1，返回累加后的次数（MySQL 无 RETURNING，两步读取）。
func (s *Store) IncRetry(ctx context.Context, fileNo string) (int, error) {
	if _, err := s.db.ExecContext(ctx,
		`UPDATE contract_file SET retry_count = retry_count + 1, error_message = ? WHERE file_no = ?`,
		"采集失败，等待重试", fileNo); err != nil {
		return 0, err
	}
	var n int
	err := s.db.QueryRowContext(ctx,
		`SELECT retry_count FROM contract_file WHERE file_no = ?`, fileNo).Scan(&n)
	return n, err
}

// SetError 记录失败原因。
func (s *Store) SetError(ctx context.Context, fileNo, msg string) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE contract_file SET error_message = ? WHERE file_no = ?`, clipErr(msg), fileNo)
	return err
}

// GetAuditHTML 审签单 HTML（可能为空——单路失败由另一路兜底）。
func (s *Store) GetAuditHTML(ctx context.Context, fileNo string) (string, error) {
	var html sql.NullString
	err := s.db.QueryRowContext(ctx,
		`SELECT audit_html FROM contract_file WHERE file_no = ?`, fileNo).Scan(&html)
	if err != nil {
		return "", err
	}
	return html.String, nil
}

// GetContent 文件正文。
func (s *Store) GetContent(ctx context.Context, fileNo string) (string, error) {
	var content sql.NullString
	err := s.db.QueryRowContext(ctx,
		`SELECT content FROM contract_file WHERE file_no = ?`, fileNo).Scan(&content)
	if err != nil {
		return "", err
	}
	return content.String, nil
}

// List 按状态/全量列表。
func (s *Store) List(ctx context.Context, state string, limit int) ([]File, error) {
	if state == "" {
		rows, err := s.db.QueryContext(ctx, `
			SELECT id, file_no, batch_no, state, retry_count, timeout_flag,
			       sftp_path, s3_key, file_name, error_message, created_at, updated_at
			  FROM contract_file ORDER BY id DESC LIMIT ?`, limit)
		if err != nil {
			return nil, err
		}
		defer rows.Close()
		return scanFiles(rows)
	}
	return s.PickByState(ctx, state, limit)
}

// SaveExtract 保存抽取结果（双路来源 + 合并结果）。
func (s *Store) SaveExtract(ctx context.Context, fileNo string, bodyFields, auditFields, merged map[string]any,
	bodyModel, auditModel string) error {
	b, _ := jsonMarshal(bodyFields)
	a, _ := jsonMarshal(auditFields)
	m, _ := jsonMarshal(merged)
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO contract_extract (file_no, body_fields, audit_fields, merged_fields, body_model, audit_model)
		VALUES (?, ?, ?, ?, ?, ?)
		ON DUPLICATE KEY UPDATE body_fields = VALUES(body_fields), audit_fields = VALUES(audit_fields),
		  merged_fields = VALUES(merged_fields), body_model = VALUES(body_model), audit_model = VALUES(audit_model)`,
		fileNo, string(b), string(a), string(m), bodyModel, auditModel)
	return err
}

// GetExtract 取抽取结果。
func (s *Store) GetExtract(ctx context.Context, fileNo string) (map[string]any, error) {
	var merged []byte
	err := s.db.QueryRowContext(ctx,
		`SELECT merged_fields FROM contract_extract WHERE file_no = ?`, fileNo).Scan(&merged)
	if err != nil {
		return nil, err
	}
	var m map[string]any
	return m, jsonUnmarshal(merged, &m)
}

// ContractStore 状态机推进所需的仓储能力（测试可注入内存实现）。
type ContractStore interface {
	PickByState(ctx context.Context, state string, limit int) ([]File, error)
	Transition(ctx context.Context, fileNo, from, to string, extra ...func(*updateArgs)) error
	MarkTimeout(ctx context.Context, fileNo string) error
	IncRetry(ctx context.Context, fileNo string) (int, error)
	SetError(ctx context.Context, fileNo, msg string) error
	GetContent(ctx context.Context, fileNo string) (string, error)
	GetAuditHTML(ctx context.Context, fileNo string) (string, error)
	SaveExtract(ctx context.Context, fileNo string, bodyFields, auditFields, merged map[string]any,
		bodyModel, auditModel string) error
}

// Processor 状态机推进器（每文件前进一步，单条失败不影响整批）。
type Processor struct {
	Store   ContractStore
	Archive Archiver  // S3 归档适配器
	Collect Collector // SFTP 采集适配器
	Extract Extractor // AI 抽取（双路）
	Now     func() time.Time
}

// Archiver S3 归档适配器。
type Archiver interface {
	Archive(ctx context.Context, fileNo, content string) (key string, err error)
}

// Collector SFTP 采集适配器：返回正文内容。
type Collector interface {
	Collect(ctx context.Context, f File) (content string, err error)
}

// Extractor AI 抽取适配器（双路：正文→集团智能体；HTML 审签单→Qwen）。
type Extractor interface {
	ExtractBody(ctx context.Context, content string) (map[string]any, error)
	ExtractAudit(ctx context.Context, html string) (map[string]any, error)
	BodyModel() string
	AuditModel() string
}

// Step 推进一批（≤BatchSize）：对每个非终态文件执行其状态的下一个转换。
// 返回各状态的处理计数。单条失败：采集失败 → 重试计数（≤30 否则 FAILED），
// 其余失败 → 该条 FAILED 并记录原因，**不影响同批其他条**。
func (p *Processor) Step(ctx context.Context) (map[string]int, error) {
	counts := map[string]int{}
	for _, state := range []string{StateUploaded, StateCollecting, StateCollected,
		StateArchived, StateExtracting, StateExtracted, StateWriting} {
		files, err := p.Store.PickByState(ctx, state, BatchSize)
		if err != nil {
			return counts, err
		}
		for _, f := range files {
			if err := p.advanceOne(ctx, f, counts); err != nil {
				if errors.Is(err, ErrSkip) {
					continue // 单条失败不影响整批
				}
				return counts, err
			}
		}
	}
	return counts, nil
}

// RunToCompletion 推进直到全部终态（演示/测试用；每文件最多 9 步）。
func (p *Processor) RunToCompletion(ctx context.Context, rounds int) (map[string]int, error) {
	total := map[string]int{}
	for i := 0; i < rounds; i++ {
		c, err := p.Step(ctx)
		if err != nil {
			return total, err
		}
		for k, v := range c {
			total[k] += v
		}
	}
	return total, nil
}

// advanceOne 单条前进一步。
func (p *Processor) advanceOne(ctx context.Context, f File, counts map[string]int) error {
	now := p.Now
	if now == nil {
		now = time.Now
	}
	switch f.State {
	case StateUploaded:
		// 上传等待超过 24h → 标记超时（仍会继续走采集，超时是标记不是终止）
		if f.CreatedAt != nil && now().Sub(*f.CreatedAt) > UploadWaitLimit && !f.TimeoutFlag {
			_ = p.Store.MarkTimeout(ctx, f.FileNo)
			counts["timeout"]++
		}
		if err := p.Store.Transition(ctx, f.FileNo, StateUploaded, StateCollecting); err != nil {
			return p.failOne(ctx, f, counts, err)
		}
		counts["uploaded→collecting"]++
		return nil

	case StateCollecting:
		content, err := p.Collect.Collect(ctx, f)
		if err != nil {
			// 采集失败：重试计数（上限 30，耗尽转 FAILED）；单条不影响整批
			n, _ := p.Store.IncRetry(ctx, f.FileNo)
			if n >= MaxCollectRetry {
				_ = p.Store.Transition(ctx, f.FileNo, StateCollecting, StateFailed)
				_ = p.Store.SetError(ctx, f.FileNo, fmt.Sprintf("采集重试 %d 次耗尽: %v", n, err))
				counts["failed"]++
			}
			counts["collect-retry"]++
			return ErrSkip
		}
		if err := p.Store.Transition(ctx, f.FileNo, StateCollecting, StateCollected,
			func(a *updateArgs) { a.sets = append(a.sets, "content = ?"); a.vals = append(a.vals, content) }); err != nil {
			return p.failOne(ctx, f, counts, err)
		}
		counts["collected"]++
		return nil

	case StateCollected:
		content, err := p.Store.GetContent(ctx, f.FileNo)
		if err != nil {
			return p.failOne(ctx, f, counts, err)
		}
		key, err := p.Archive.Archive(ctx, f.FileNo, content)
		if err != nil {
			return p.failOne(ctx, f, counts, err)
		}
		if err := p.Store.Transition(ctx, f.FileNo, StateCollected, StateArchived,
			func(a *updateArgs) { a.sets = append(a.sets, "s3_key = ?"); a.vals = append(a.vals, key) }); err != nil {
			return p.failOne(ctx, f, counts, err)
		}
		counts["archived"]++
		return nil

	case StateArchived:
		if err := p.Store.Transition(ctx, f.FileNo, StateArchived, StateExtracting); err != nil {
			return p.failOne(ctx, f, counts, err)
		}
		counts["extracting"]++
		return nil

	case StateExtracting:
		// 双路抽取：正文 → 集团智能体；HTML 审签单 → Qwen。JSON Schema 校验 + 非空合并。
		content, err := p.Store.GetContent(ctx, f.FileNo)
		if err != nil {
			return p.failOne(ctx, f, counts, err)
		}
		auditHTML, _ := p.Store.GetAuditHTML(ctx, f.FileNo)
		body, bodyErr := p.Extract.ExtractBody(ctx, content)
		audit, auditErr := p.Extract.ExtractAudit(ctx, auditHTML)
		if bodyErr != nil && auditErr != nil {
			// 双路全失败才置失败；单路失败靠另一路兜底（合并非空字段）
			return p.failOne(ctx, f, counts, fmt.Errorf("双路抽取均失败: %v / %v", bodyErr, auditErr))
		}
		bodyFields, auditFields := map[string]any{}, map[string]any{}
		if bodyErr == nil {
			bodyFields = body
		}
		if auditErr == nil {
			auditFields = audit
		}
		merged := MergeFields(bodyFields, auditFields)
		if err := ValidateFields(merged); err != nil {
			return p.failOne(ctx, f, counts, err)
		}
		if err := p.Store.SaveExtract(ctx, f.FileNo, bodyFields, auditFields, merged,
			p.Extract.BodyModel(), p.Extract.AuditModel()); err != nil {
			return p.failOne(ctx, f, counts, err)
		}
		if err := p.Store.Transition(ctx, f.FileNo, StateExtracting, StateExtracted); err != nil {
			return p.failOne(ctx, f, counts, err)
		}
		counts["extracted"]++
		return nil

	case StateExtracted:
		if err := p.Store.Transition(ctx, f.FileNo, StateExtracted, StateWriting); err != nil {
			return p.failOne(ctx, f, counts, err)
		}
		counts["writing"]++
		return nil

	case StateWriting:
		// 字段回写落库（demo：抽取结果已存 contract_extract，此处为回写确认点；
		// 生产环境这里是向合同系统回写 21 项的 API 调用）
		if err := p.Store.Transition(ctx, f.FileNo, StateWriting, StateDone); err != nil {
			return p.failOne(ctx, f, counts, err)
		}
		counts["done"]++
		return nil
	}
	return nil
}

// failOne 单条置失败（采集重试耗尽/不可恢复错误），批处理继续。
func (p *Processor) failOne(ctx context.Context, f File, counts map[string]int, err error) error {
	_ = p.Store.SetError(ctx, f.FileNo, err.Error())
	_ = p.Store.Transition(ctx, f.FileNo, f.State, StateFailed)
	counts["failed"]++
	return ErrSkip
}

func nstr(s sql.NullString) *string {
	if !s.Valid {
		return nil
	}
	v := s.String
	return &v
}

func ntime(t sql.NullTime) *time.Time {
	if !t.Valid {
		return nil
	}
	v := t.Time
	return &v
}

func clipErr(s string) string {
	if len(s) > 480 {
		return s[:480]
	}
	return s
}
