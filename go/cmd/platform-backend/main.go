// Command platform-backend 是 aismp-backend 的 Go 实现。
//
// 对应 Java 侧 aismp-backend/src/main/resources/application.yml：
//
//	server.port: 7529
//	server.servlet.context-path: /api
//
// 当前只移植了鉴权切片（/api/auth/**）。其余 7 个控制器见 docs/Go重写方案.md P1。
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

	"github.com/aismp/platform/internal/config"
	"github.com/aismp/platform/internal/httpkit"
	"github.com/aismp/platform/internal/platform/auth"
	"github.com/aismp/platform/internal/platform/dhproxy"
	"github.com/aismp/platform/internal/platform/innerapi"
	"github.com/aismp/platform/internal/platform/market"
	"github.com/aismp/platform/internal/platform/tokenadmin"
	"github.com/aismp/platform/internal/platform/user"
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
	cfg, err := config.Load()
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

	engine := httpkit.NewEngine(log)

	// server.servlet.context-path: /api
	api := engine.Group("/api")

	// P0：门户鉴权切片。
	userRepo := user.NewRepository(db)
	auth.NewHandler(userRepo, log).Register(api)

	// P1：token 用量分配面（/admin/token/**）+ 内核计量闸门（/inner/token/**）。
	tokenSvc := tokenadmin.NewService(tokenadmin.NewRepository(db), log)
	tokenadmin.NewHandler(tokenSvc, userRepo, log).Register(api)

	// P1：网关鉴权数据（/inner/user|interfaceInfo|userInterfaceInfo/**）。
	innerapi.NewHandler(innerapi.NewRepository(db)).Register(api)

	// P1：yuapi 管理三件套（/user、/interfaceInfo、/userInterfaceInfo，session 鉴权）。
	marketRepo := market.NewRepository(db)
	marketSession := market.NewSession()
	market.NewHandler(marketRepo, marketSession, log,
		cfg.GatewayBaseURL, cfg.ClientAccessKey, cfg.ClientSecretKey).Register(api)

	// P1：管理端数字员工代理（/admin/digitalHuman/**）。
	dhproxy.NewHandler(dhproxy.Config{
		GatewayBaseURL:  cfg.GatewayBaseURL,
		GatewayPrefix:   cfg.GatewayDigitalHumanPrefix,
		DigitalHumanURL: cfg.DigitalHumanBaseURL,
		AccessKey:       cfg.ClientAccessKey,
		SecretKey:       cfg.ClientSecretKey,
	}, marketRepo, marketSession, log).Register(api)

	// 探活放在 context-path 之外，方便容器编排直接打 /health。
	engine.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok", "service": "platform-backend"})
	})
	engine.GET("/health/ready", func(c *gin.Context) {
		ctx, cancel := context.WithTimeout(c.Request.Context(), 2*time.Second)
		defer cancel()
		if err := db.PingContext(ctx); err != nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"status": "db_unavailable", "error": err.Error()})
			return
		}
		c.JSON(http.StatusOK, gin.H{"status": "ready"})
	})

	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           engine,
		ReadHeaderTimeout: 5 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		log.Info("platform-backend 已启动", "addr", cfg.Addr)
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
