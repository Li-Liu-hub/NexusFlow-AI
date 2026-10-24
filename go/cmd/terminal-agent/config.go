// terminal-agent 的运行配置。
package main

import "github.com/aismp/platform/internal/config"

// agentConfig 复用 platform-backend 的配置面，仅端口缺省不同。
type agentConfig = config.Config

// loadAgentConfig 读取配置：HTTP_ADDR 缺省 :8124（aismp-agent 的 server.port）。
func loadAgentConfig() (agentConfig, error) {
	cfg, err := config.LoadWithDefaultAddr(":8124")
	if err != nil {
		return cfg, err
	}
	return cfg, nil
}
