// Command kernel 是 aismp-digital-human（数字员工内核）的 Go 实现。
//
// 对应 Java 侧 server.port: 8126。监听 :8126，无 context-path
// （网关 /digitalHuman/** 与 /api/agent|admin/** 各剥一段后转发到这里）。
package main

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	_ "github.com/jackc/pgx/v5/stdlib"
	"github.com/gin-gonic/gin"

	"github.com/aismp/platform/internal/httpkit"
	"github.com/aismp/platform/internal/kernel"
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
	cfg := kernel.Config{
		TerminalDSN:    getenv("TERMINAL_DSN", getenv("MYSQL_DSN", "root:123456@tcp(127.0.0.1:3306)/api_db?parseTime=true&loc=Local&charset=utf8mb4&timeout=5s")),
		APIDSN:         getenv("API_DSN", getenv("MYSQL_DSN", "root:123456@tcp(127.0.0.1:3306)/api_db?parseTime=true&loc=Local&charset=utf8mb4&timeout=5s")),
		LLMBaseURL:     getenv("LLM_BASE_URL", "https://api.xiaomimimo.com"),
		LLMAPIKey:      getenv("MIMO_API_KEY", ""),
		LLMModel:       getenv("LLM_MODEL", "mimo-v2.6-flash"),
		EmbedBaseURL:   getenv("EMBED_BASE_URL", "https://api.siliconflow.cn"),
		EmbedAPIKey:    getenv("SILICONFLOW_API_KEY", ""),
		EmbedModel:     getenv("EMBED_MODEL", "BAAI/bge-m3"),
		PGDSN:          getenv("PG_DSN", ""),
		BackendBaseURL: getenv("BACKEND_BASE_URL", "http://127.0.0.1:7529/api"),
		AgentBaseURL:   getenv("AGENT_BASE_URL", "http://127.0.0.1:8124"),
		GatewayBaseURL: getenv("GATEWAY_BASE_URL", "http://127.0.0.1:8090"),
		GatewayAK:      getenv("GATEWAY_ACCESS_KEY", "aismp-console"),
		GatewaySK:      getenv("GATEWAY_SECRET_KEY", ""),
		Addr:           getenv("HTTP_ADDR", ":8126"),
	}

	rootCtx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	terminal, err := store.OpenMySQL(rootCtx, store.MySQL{DSN: cfg.TerminalDSN, MaxOpen: 20, MaxIdle: 5})
	if err != nil {
		return err
	}
	defer terminal.Close()
	api, err := store.OpenMySQL(rootCtx, store.MySQL{DSN: cfg.APIDSN, MaxOpen: 20, MaxIdle: 5})
	if err != nil {
		return err
	}
	defer api.Close()
	var pg *sql.DB
	if cfg.PGDSN != "" {
		if pg, err = sql.Open("pgx", cfg.PGDSN); err != nil {
			return err
		}
		pingCtx, cancel := context.WithTimeout(rootCtx, 5*time.Second)
		if err := pg.PingContext(pingCtx); err != nil {
			cancel()
			return err
		}
		cancel()
		defer pg.Close()
	}

	gin.SetMode(gin.ReleaseMode)
	r := gin.New()
	r.Use(gin.Recovery())
	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok", "service": "kernel"})
	})
	r.GET("/health/ready", func(c *gin.Context) {
		ctx, cancel := context.WithTimeout(c.Request.Context(), 2*time.Second)
		defer cancel()
		if err := terminal.PingContext(ctx); err != nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"status": "db_unavailable"})
			return
		}
		c.JSON(http.StatusOK, gin.H{"status": "ready"})
	})
	r.NoRoute(func(c *gin.Context) {
		c.JSON(http.StatusNotFound, httpkit.FailMsg(httpkit.CodeNotFound, "接口不存在: "+c.Request.URL.Path))
	})

	engine := &kernel.Engine{
		Cfg:      cfg,
		Terminal: kernel.NewStore(terminal),
		Auth:     api,
		API:      api,
		Registry: kernel.NewRegistry(kernel.NewStore(terminal)),
		LLM:      kernel.NewLLM(cfg.LLMBaseURL, cfg.LLMAPIKey),
		Log:      log,
		HTTP:     &http.Client{Timeout: 120 * time.Second},
	}
	engine.PG = pg
	engine.Register(r)

	srv := &http.Server{Addr: cfg.Addr, Handler: r, ReadHeaderTimeout: 5 * time.Second}
	errCh := make(chan error, 1)
	go func() {
		log.Info("kernel 已启动", "addr", cfg.Addr)
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

func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

