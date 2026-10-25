// Command orchestrator 是编排器的 Go 实现（对齐 server.port: 8125）。
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

	"github.com/gin-gonic/gin"

	"github.com/aismp/platform/internal/canvas"
	"github.com/aismp/platform/internal/orchestrator"
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
	cfg := orchestrator.Config{
		AgentBaseURL:     getenv("AGENT_BASE_URL", "http://127.0.0.1:8124"),
		KernelBaseURL:    getenv("KERNEL_BASE_URL", "http://127.0.0.1:8126"),
		KernelUser:       getenv("KERNEL_USER", "svc_orchestrator"),
		KernelPass:       getenv("KERNEL_PASS", "svc-orch-2026"),
		KBAgentCode:      getenv("KB_AGENT_CODE", "kb_assistant"),
		GatewayAccessKey: getenv("GATEWAY_ACCESS_KEY", "aismp-console"),
		GatewaySecretKey: getenv("GATEWAY_SECRET_KEY", ""),
		Addr:             getenv("HTTP_ADDR", ":8125"),
	}
	rootCtx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// PG（terminal_kb）：orch_graph 版本库。缺 DSN 时画布编排不装配（内置路线兜底）。
	var canvasEng *orchestrator.CanvasEngine
	if pgDSN := getenv("PG_DSN", ""); pgDSN != "" {
		if pg, err := sql.Open("pgx", pgDSN); err == nil {
			if err := pg.PingContext(rootCtx); err == nil {
				store := canvas.NewCanvasStore(pg)
				_ = store.EnsureSchema(rootCtx)
				eng := orchestrator.New(cfg, log)
				eng.Canvas = &orchestrator.CanvasEngine{Store: store, Agent: eng}
				canvasEng = eng.Canvas
				log.Info("画布编排已装配")
			}
		}
	}

	gin.SetMode(gin.ReleaseMode)
	r := gin.New()
	r.Use(gin.Recovery())
	eng := orchestrator.New(cfg, log)
	eng.Canvas = canvasEng
	eng.Register(r)

	srv := &http.Server{Addr: cfg.Addr, Handler: r, ReadHeaderTimeout: 5 * time.Second}
	errCh := make(chan error, 1)
	go func() {
		log.Info("orchestrator 已启动", "addr", cfg.Addr)
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
	return nil
}

func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
