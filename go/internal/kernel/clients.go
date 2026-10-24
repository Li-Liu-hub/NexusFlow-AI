// 内核的外部依赖客户端（LLM / PG）与辅助构造。
package kernel

import (
	"context"
	"database/sql"
	"fmt"
	"net/http"
	"time"
)

// NewLLM 构造 LLM 客户端。
func NewLLM(baseURL, apiKey string) *LLM {
	return &LLM{baseURL: baseURL, apiKey: apiKey,
		client: &http.Client{Timeout: 180 * time.Second}}
}

// openPG 打开 PostgreSQL（terminal_kb）连接。
func NewPG(ctx context.Context, dsn string) (*sql.DB, error) {
	db, err := sql.Open("pgx", dsn)
	if err != nil {
		return nil, err
	}
	pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := db.PingContext(pingCtx); err != nil {
		return nil, fmt.Errorf("kernel: PG 探活失败: %w", err)
	}
	return db, nil
}
