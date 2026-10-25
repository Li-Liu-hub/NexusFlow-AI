// 版本存储（orch_graph，PG terminal_kb）+ 会话粘版本（orch_conversation.graph_version）。
//
// 语义对齐 Python canvas/store.py：
//   - save_draft：单草稿槽（status=draft upsert）
//   - publish：校验通过后固化为 published v{N}（version 单调递增，旧版本永久保留）
//   - load_published：运行时取最新 published
//   - rollback：旧版本 DSL 再发布为新版本（不可变历史，回滚即再发布）
package canvas

import (
	"context"
	"database/sql"
	"fmt"
)

// Store 是 orch_graph / orch_conversation 的仓储。
type Store struct {
	db *sql.DB
}

// NewStore 构造。
func NewStore(db *sql.DB) *Store { return &Store{db: db} }

// EnsureSchema 首次建表（幂等，Python 版同款 DDL + 会话粘版本列）。
func (s *Store) EnsureSchema(ctx context.Context) error {
	for _, ddl := range []string{
		`CREATE TABLE IF NOT EXISTS orch_graph (
		   id bigserial PRIMARY KEY,
		   version int NOT NULL,
		   status varchar(16) NOT NULL,
		   dsl jsonb NOT NULL,
		   note varchar(200),
		   created_at timestamptz NOT NULL DEFAULT now())`,
		`CREATE UNIQUE INDEX IF NOT EXISTS idx_orch_graph_version ON orch_graph(version)`,
		`CREATE TABLE IF NOT EXISTS orch_conversation (
		   id varchar(40) PRIMARY KEY,
		   user_id varchar(64),
		   warehouse_code varchar(32),
		   title varchar(100),
		   graph_version int,
		   created_at timestamptz NOT NULL DEFAULT now())`,
	} {
		if _, err := s.db.ExecContext(ctx, ddl); err != nil {
			return err
		}
	}
	return nil
}

// SaveDraft 保存/覆盖单草稿槽。
func (s *Store) SaveDraft(ctx context.Context, dslJSON []byte, note string) error {
	var version int
	err := s.db.QueryRowContext(ctx,
		`SELECT version FROM orch_graph WHERE status='draft' LIMIT 1`).Scan(&version)
	if err == sql.ErrNoRows {
		var maxV int
		s.db.QueryRowContext(ctx, `SELECT COALESCE(MAX(version),0) FROM orch_graph`).Scan(&maxV)
		_, err = s.db.ExecContext(ctx,
			`INSERT INTO orch_graph (version, status, dsl, note) VALUES ($1,'draft',$2::jsonb,$3)`,
			maxV+1, string(dslJSON), note)
		return err
	}
	if err != nil {
		return err
	}
	_, err = s.db.ExecContext(ctx,
		`UPDATE orch_graph SET dsl=$1::jsonb, note=$2, created_at=now() WHERE version=$3 AND status='draft'`,
		string(dslJSON), note, version)
	return err
}

// LoadDraft 取草稿（无则 nil）。
func (s *Store) LoadDraft(ctx context.Context) ([]byte, error) {
	var raw []byte
	err := s.db.QueryRowContext(ctx,
		`SELECT dsl FROM orch_graph WHERE status='draft' LIMIT 1`).Scan(&raw)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	return raw, err
}

// Publish 把草稿（或指定 DSL）固化为 published v{N}；返回新版本号。
func (s *Store) Publish(ctx context.Context, dslJSON []byte, note string) (int, error) {
	var maxV int
	if err := s.db.QueryRowContext(ctx,
		`SELECT COALESCE(MAX(version),0) FROM orch_graph WHERE status='published'`).Scan(&maxV); err != nil {
		return 0, err
	}
	v := maxV + 1
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	// 草稿槽一并消费（发布后画布无草稿）；与版本插入同事务，失败不丢草稿
	if _, err := tx.ExecContext(ctx, `DELETE FROM orch_graph WHERE status='draft'`); err != nil {
		return 0, err
	}
	if _, err := tx.ExecContext(ctx,
		`INSERT INTO orch_graph (version, status, dsl, note) VALUES ($1,'published',$2::jsonb,$3)`,
		v, string(dslJSON), note); err != nil {
		return 0, err
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	return v, nil
}

// LoadPublished 最新 published 版本（无则 nil, 0）。
func (s *Store) LoadPublished(ctx context.Context) ([]byte, int, error) {
	var raw []byte
	var v int
	err := s.db.QueryRowContext(ctx,
		`SELECT dsl, version FROM orch_graph WHERE status='published' ORDER BY version DESC LIMIT 1`).Scan(&raw, &v)
	if err == sql.ErrNoRows {
		return nil, 0, nil
	}
	return raw, v, err
}

// LoadVersion 取指定版本 DSL（回滚/审计用）。
func (s *Store) LoadVersion(ctx context.Context, version int) ([]byte, error) {
	var raw []byte
	err := s.db.QueryRowContext(ctx,
		`SELECT dsl FROM orch_graph WHERE version=$1 AND status='published'`, version).Scan(&raw)
	if err == sql.ErrNoRows {
		return nil, fmt.Errorf("版本 %d 不存在", version)
	}
	return raw, err
}

// Versions 版本列表。
func (s *Store) Versions(ctx context.Context) ([]map[string]any, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT version, status, note, created_at::text AS created_at FROM orch_graph ORDER BY version DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []map[string]any
	for rows.Next() {
		var v int
		var status, created string
		var note sql.NullString
		if err := rows.Scan(&v, &status, &note, &created); err != nil {
			return nil, err
		}
		m := map[string]any{"version": v, "status": status, "createdAt": created}
		if note.Valid {
			m["note"] = note.String
		}
		list = append(list, m)
	}
	return list, rows.Err()
}

// BindConversation 会话首次创建时绑定图版本（会话粘版本）。
func (s *Store) BindConversation(ctx context.Context, convID, userID, warehouse string, version int) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO orch_conversation (id, user_id, warehouse_code, title, graph_version)
		VALUES ($1, $2, $3, '', $4)
		ON CONFLICT (id) DO NOTHING`, convID, userID, warehouse, version)
	return err
}

// ConversationVersion 取会话绑定的版本（无绑定返回 0）。
func (s *Store) ConversationVersion(ctx context.Context, convID string) (int, error) {
	var v int
	err := s.db.QueryRowContext(ctx,
		`SELECT graph_version FROM orch_conversation WHERE id = $1`, convID).Scan(&v)
	if err == sql.ErrNoRows {
		return 0, nil
	}
	return v, err
}

// NewCanvasStore 是 NewStore 的编排器侧别名。
func NewCanvasStore(db *sql.DB) *Store { return NewStore(db) }
