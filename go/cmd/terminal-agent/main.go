// Command terminal-agent 是 aismp-agent 终端业务的 Go 实现（P2 起步：台账 + 调拨状态机）。
//
// 对应 Java 侧 server.port: 8124。监听 :8124，路径无 context-path
// （网关已剥 /api 段）：/terminal/**、/transferOrder/**。
// 业务数据在 api_db（MySQL）；PG（terminal_kb）只服务 KB/RAG，由对话链路使用。
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/aismp/platform/internal/httpkit"
	"github.com/aismp/platform/internal/terminal"
	"github.com/aismp/platform/internal/store"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(log)
	if err := run(log); err != nil {
		log.Error("服务退出", "err", err)
		os.Exit(1)
	}
}

func run(log *slog.Logger) error {
	cfg, err := loadAgentConfig()
	if err != nil {
		return err
	}
	rootCtx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	db, err := store.OpenMySQL(rootCtx, store.MySQL{
		DSN:     cfg.MySQLDSN,
		MaxOpen: cfg.DBMaxOpen,
		MaxIdle: cfg.DBMaxIdle,
	})
	if err != nil {
		return err
	}
	defer func() { _ = db.Close() }()

	gin.SetMode(gin.ReleaseMode)
	r := gin.New()
	r.Use(gin.Recovery())
	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok", "service": "terminal-agent"})
	})
	r.NoRoute(func(c *gin.Context) {
		c.JSON(http.StatusNotFound, httpkit.FailMsg(httpkit.CodeNotFound, "接口不存在: "+c.Request.URL.Path))
	})

	terminal.NewHandler(terminal.NewRepository(db), log).Register(r)

	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           r,
		ReadHeaderTimeout: 5 * time.Second,
	}
	errCh := make(chan error, 1)
	go func() {
		log.Info("terminal-agent 已启动", "addr", cfg.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
	}()
	select {
	case err := <-errCh:
		return err
	case <-rootCtx.Done():
		log.Info("收到退出信号，开始优雅关闭")
	}
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		return err
	}
	log.Info("已关闭")
	return nil
}
