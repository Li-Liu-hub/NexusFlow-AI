// Command bizsuite 是业务套件服务（:8130）：合同信智能抽取 + AI 辅助工单建单。
//
// 状态：UPLOADED→COLLECTING→COLLECTED→ARCHIVED→EXTRACTING→EXTRACTED→WRITING→DONE（9 态含 FAILED）。
// 鉴权：经网关门户通道（Bearer）或 AK/SK；本服务读 X-User-Id 注入身份。
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

	_ "github.com/jackc/pgx/v5/stdlib"
	"github.com/gin-gonic/gin"

	"github.com/aismp/platform/internal/biz"
	"github.com/aismp/platform/internal/httpkit"
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
	cfg := loadConfig()
	rootCtx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	db, err := store.OpenMySQL(rootCtx, store.MySQL{DSN: cfg.MySQLDSN, MaxOpen: 20, MaxIdle: 5})
	if err != nil {
		return err
	}
	defer db.Close()

	store := biz.NewStore(db)
	tickets := biz.NewTicketStore(db)
	if err := store.EnsureSchema(rootCtx); err != nil {
		log.Error("合同表初始化失败", "err", err)
	}
	if err := tickets.EnsureTicketSchema(rootCtx); err != nil {
		log.Error("工单表初始化失败", "err", err)
	}

	// 适配器装配：归档=本地目录；采集=通知时随内容落库（生产可换 SFTP 客户端）；
	// 抽取=双路 LLM（正文→集团端点，审签单→Qwen 端点）；建单平台=本地实现（生产换联通工单平台）。
	archive := &localArchiver{Dir: getenv("ARCHIVE_DIR", "/tmp/biz-archive")}
	collect := &notifyCollector{Store: store}
	extract := &biz.LLMExtractor{
		GroupBaseURL: getenv("GROUP_LLM_BASE_URL", cfg.LLMBaseURL),
		GroupAPIKey:  getenv("GROUP_LLM_API_KEY", cfg.LLMAPIKey),
		GroupModel:   getenv("GROUP_LLM_MODEL", cfg.LLMModel),
		AuditBaseURL: getenv("QWEN_BASE_URL", cfg.LLMBaseURL),
		AuditAPIKey:  getenv("QWEN_API_KEY", cfg.LLMAPIKey),
		AuditQwen:    getenv("QWEN_MODEL", "qwen3-6.5b-a3b"),
	}
	proc := &biz.Processor{Store: store, Archive: archive, Collect: collect, Extract: extract}
	classifier := &biz.Classifier{
		LLMBaseURL: getenv("GROUP_LLM_BASE_URL", cfg.LLMBaseURL),
		LLMAPIKey:  getenv("GROUP_LLM_API_KEY", cfg.LLMAPIKey),
		LLMModel:   getenv("GROUP_LLM_MODEL", cfg.LLMModel),
	}
	platform := &localTicketPlatform{Tickets: tickets}

	gin.SetMode(gin.ReleaseMode)
	r := gin.New()
	r.Use(gin.Recovery())
	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok", "service": "bizsuite"})
	})
	r.NoRoute(func(c *gin.Context) {
		c.JSON(http.StatusNotFound, httpkit.FailMsg(httpkit.CodeNotFound, "接口不存在: "+c.Request.URL.Path))
	})
	biz.NewHandler(store, tickets, proc, extract, classifier, platform, archive, collect).Register(r)

	srv := &http.Server{Addr: cfg.Addr, Handler: r, ReadHeaderTimeout: 5 * time.Second}
	errCh := make(chan error, 1)
	go func() {
		log.Info("bizsuite 已启动", "addr", cfg.Addr)
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

// ---- 适配器实现（demo 本地版；生产按接口替换）----

// localArchiver S3 归档适配器的本地实现：写归档目录，返回伪 S3 key。
type localArchiver struct{ Dir string }

func (a *localArchiver) Archive(ctx context.Context, fileNo, content string) (string, error) {
	dir := a.Dir + "/" + fileNo[:2]
	if err := mkDirAll(dir); err != nil {
		return "", err
	}
	key := "contracts/" + fileNo + ".txt"
	if err := writeFile(dir+"/"+fileNo+".txt", []byte(content)); err != nil {
		return "", err
	}
	return key, nil
}

// notifyCollector SFTP 采集适配器的 demo 实现：通知时正文已随请求落库（content 列），
// 采集动作即「确认内容就绪」。生产替换为真实 SFTP 拉取（sftp_path 指向远端路径）。
type notifyCollector struct{ Store *biz.Store }

func (c *notifyCollector) Collect(ctx context.Context, f biz.File) (string, error) {
	return c.Store.GetContent(ctx, f.FileNo)
}

// localTicketPlatform 工单平台适配器的本地实现：平台工单号 = 演示号段。
// 生产替换为联通工单平台 HTTP 调用（接口签名同 gateway 约定）。
type localTicketPlatform struct{ Tickets *biz.TicketStore }

func (p *localTicketPlatform) Create(ctx context.Context, category string, params map[string]any) (string, error) {
	return "TF" + time.Now().Format("20060102150405"), nil
}

// ---- 配置与文件辅助 ----

// suiteConfig bizsuite 运行配置。
type suiteConfig struct {
	Addr      string
	MySQLDSN  string
	LLMBaseURL string
	LLMAPIKey  string
	LLMModel   string
}

func loadConfig() suiteConfig {
	return suiteConfig{
		Addr:       getenv("HTTP_ADDR", ":8130"),
		MySQLDSN:   getenv("MYSQL_DSN", "root:123456@tcp(127.0.0.1:3306)/api_db?parseTime=true&loc=Local&charset=utf8mb4&timeout=5s"),
		LLMBaseURL: getenv("LLM_BASE_URL", "https://api.xiaomimimo.com"),
		LLMAPIKey:  getenv("MIMO_API_KEY", ""),
		LLMModel:   getenv("LLM_MODEL", "mimo-v2.6-flash"),
	}
}

func mkDirAll(dir string) error { return os.MkdirAll(dir, 0o755) }

func writeFile(path string, data []byte) error { return os.WriteFile(path, data, 0o644) }
