// Command gateway 是 aismp-gateway 的 Go 实现（Spring Cloud Gateway 的替代）。
//
// 对应 Java 侧 server.port: 8090。监听 :8090，nginx 的 /api/ 与 /digitalHuman/
// 都打到它。路由表见 internal/gateway.routes()（对齐 application.yml 的 routes 段）。
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

	"github.com/redis/go-redis/v9"

	"github.com/aismp/platform/internal/config"
	"github.com/aismp/platform/internal/gateway"
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
	cfg, err := config.LoadGateway()
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

	rdb := redis.NewClient(&redis.Options{
		Addr:     cfg.Gateway.Redis.Addr,
		Password: cfg.Gateway.Redis.Password,
	})
	if err := rdb.Ping(rootCtx).Err(); err != nil {
		return errors.New("gateway: Redis 连接失败: " + err.Error())
	}

	gw := gateway.New(cfg.Gateway, db, rdb, log)
	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           gw,
		ReadHeaderTimeout: 5 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		log.Info("gateway 已启动", "addr", cfg.Addr)
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
	shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		return err
	}
	log.Info("已关闭")
	return nil
}
