// Package store 管理数据库连接池。
//
// 对应 Spring 侧由 HikariCP 托管的数据源。这里的参数名刻意贴合
// Hikari 的语义（maximum-pool-size / minimum-idle / connection-timeout），
// 便于从 application.yml 直接翻译。
package store

import (
	"context"
	"database/sql"
	"fmt"
	"time"

	_ "github.com/go-sql-driver/mysql" // MySQL 驱动注册
)

// MySQL 汇总一个 MySQL 连接池的配置。
type MySQL struct {
	DSN     string
	MaxOpen int
	MaxIdle int
}

// OpenMySQL 建立连接池并做一次探活，失败立即返回（等价于 Spring 启动时 DataSource 初始化失败）。
func OpenMySQL(ctx context.Context, cfg MySQL) (*sql.DB, error) {
	db, err := sql.Open("mysql", cfg.DSN)
	if err != nil {
		return nil, fmt.Errorf("store: 打开 MySQL 失败: %w", err)
	}
	db.SetMaxOpenConns(cfg.MaxOpen)
	db.SetMaxIdleConns(cfg.MaxIdle)
	db.SetConnMaxLifetime(30 * time.Minute)

	pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := db.PingContext(pingCtx); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("store: MySQL 探活失败: %w", err)
	}
	return db, nil
}
