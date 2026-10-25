// Command terminal-agent 是 aismp-agent 终端业务的 Go 实现（P2 + 知识检索栈）。
//
// 对应 Java 侧 server.port: 8124。监听 :8124，路径无 context-path
// （网关已剥 /api 段）：/terminal/**、/transferOrder/**、/oa/**、/inner/kb/**。
// 业务数据在 api_db（MySQL）；PG（terminal_kb）承载 vector_store。
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
	"github.com/aismp/platform/internal/kb"
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

	// PG（terminal_kb）：vector_store。缺 DSN 时知识检索栈不装配（终端业务不受影响）。
	var pg *sql.DB
	if pgDSN := getenv("PG_DSN", ""); pgDSN != "" {
		if pg, err = sql.Open("pgx", pgDSN); err != nil {
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
		c.JSON(http.StatusOK, gin.H{"status": "ok", "service": "terminal-agent"})
	})
	r.NoRoute(func(c *gin.Context) {
		c.JSON(http.StatusNotFound, httpkit.FailMsg(httpkit.CodeNotFound, "接口不存在: "+c.Request.URL.Path))
	})

	terminal.NewHandler(terminal.NewRepository(db), log).Register(r)

	// 知识检索栈（PG + SiliconFlow + MiMo 齐备才装配）
	if pg != nil && getenv("SILICONFLOW_API_KEY", "") != "" && getenv("MIMO_API_KEY", "") != "" {
		svc := &kb.Service{
			Cfg: kb.Config{
				Enabled:       getenv("KB_HYBRID_ENABLED", "true") == "true",
				MultiQuery:    atoiDefault(getenv("KB_MULTI_QUERY", "3"), 3),
				RRFK:          60,
				Candidates:    20,
				TopK:          5,
				VectorWeight:  1.0,
				KeywordWeight: 1.0,
				RerankEnabled: getenv("KB_RERANK_ENABLED", "true") == "true",
				RerankModel:   getenv("KB_RERANK_MODEL", "BAAI/bge-reranker-v2-m3"),
				SimThreshold:  0.5,
				EmbedModel:    getenv("EMBED_MODEL", "BAAI/bge-m3"),
				ChatModel:     getenv("LLM_MODEL", "mimo-v2.6-flash"),
			},
			PG:  kb.NewPgStore(pg),
			SF:  &kb.SiliconFlow{BaseURL: getenv("EMBED_BASE_URL", "https://api.siliconflow.cn"),
				APIKey: getenv("SILICONFLOW_API_KEY", ""), Client: &http.Client{Timeout: 30 * time.Second}},
			LLM: &kb.ChatClient{BaseURL: getenv("LLM_BASE_URL", "https://api.xiaomimimo.com"),
				APIKey: getenv("MIMO_API_KEY", ""), Model: getenv("LLM_MODEL", "mimo-v2.6-flash")},
			Log: log,
		}
		terminal.NewKBHandler(svc).RegisterKB(r)
		log.Info("知识检索栈已装配", "hybrid", svc.Cfg.Enabled, "rerank", svc.Cfg.RerankEnabled)
	} else {
		log.Warn("知识检索栈未装配（缺 PG_DSN / SILICONFLOW_API_KEY / MIMO_API_KEY）")
	}

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

func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func atoiDefault(s string, def int) int {
	n := 0
	for _, r := range s {
		if r < '0' || r > '9' {
			return def
		}
		n = n*10 + int(r-'0')
	}
	if n <= 0 {
		return def
	}
	return n
}
