// Package config 读取运行期配置。
//
// 对齐 Java 侧的 application.yml：原文件里绝大多数值本来就走 ${ENV:default}
// 形式（MYSQL_APP_USER / REDIS_PW / NACOS_ADDR …），所以这里直接退化成
// 纯环境变量，不再引入 yml 解析器。默认值刻意取「本机开发」那一套，
// 线上由 compose 的 environment: 覆盖。
package config

import (
	"fmt"
	"os"
	"strconv"
	"time"
)

// Config 是 platform-backend 进程的全部可调参数。
type Config struct {
	// Addr 监听地址，对应 server.port: 7529。
	Addr string
	// MySQLDSN 对应 spring.datasource.url + username + password 的合并形式。
	MySQLDSN string
	// ShutdownTimeout 优雅退出等待上限。
	ShutdownTimeout time.Duration
	// DBMaxOpen / DBMaxIdle 对应 HikariCP 的 maximum-pool-size / minimum-idle。
	DBMaxOpen int
	DBMaxIdle int

	// ---- P1 新增：管理代理与接口探测（对应 application.yml 的 aismp.*）----
	// GatewayBaseURL 对应 aismp.gateway.base-url（默认本机网关）。
	GatewayBaseURL string
	// GatewayDigitalHumanPrefix 对应 aismp.gateway.digital-human-prefix。
	GatewayDigitalHumanPrefix string
	// DigitalHumanBaseURL 对应 aismp.digital-human.base-url（仅服务账号登录用）。
	DigitalHumanBaseURL string
	// ClientAccessKey / ClientSecretKey 对应 aismp.client.*，服务端专用。
	ClientAccessKey string
	ClientSecretKey string
}

// Load 从环境变量组装配置，缺省值等价于本机开发环境。
func Load() (Config, error) {
	c := Config{
		Addr:            getenv("HTTP_ADDR", ":7529"),
		MySQLDSN:        getenv("MYSQL_DSN", "root:123456@tcp(127.0.0.1:3306)/api_db?parseTime=true&loc=Local&charset=utf8mb4&timeout=5s"),
		ShutdownTimeout: 10 * time.Second,
		DBMaxOpen:       getenvInt("DB_MAX_OPEN", 20),
		DBMaxIdle:       getenvInt("DB_MAX_IDLE", 5),

		GatewayBaseURL:             getenv("GATEWAY_BASE_URL", "http://127.0.0.1:8090"),
		GatewayDigitalHumanPrefix:  getenv("DIGITAL_HUMAN_GW_PREFIX", "/digitalHuman/admin"),
		DigitalHumanBaseURL:        getenv("DIGITAL_HUMAN_BASE_URL", "http://127.0.0.1:8126"),
		ClientAccessKey:            getenv("AISMP_CLIENT_ACCESS_KEY", "aismp-console"),
		ClientSecretKey:            getenv("AISMP_CLIENT_SECRET_KEY", "7c1f9a3e5d0b2846af31c7e9b0d45268"),
	}
	if c.Addr == "" {
		return c, fmt.Errorf("config: HTTP_ADDR 不能为空")
	}
	return c, nil
}

func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func getenvInt(key string, def int) int {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil || n <= 0 {
		return def
	}
	return n
}
