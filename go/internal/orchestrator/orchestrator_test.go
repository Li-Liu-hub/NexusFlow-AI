// 编排器回归测试：画布节点复用路线函数时无 SSE 上下文不得 panic。
package orchestrator

import (
	"testing"

	"github.com/gin-gonic/gin"
)

func TestSSEWriter_无上下文时静默跳过(t *testing.T) {
	// 回归：chart_build 节点经 canvas 复用 runChart，内部向 nil gin.Context 写帧曾 panic
	s := &sseWriter{} // c == nil
	s.send("progress", "x")
	s.sendJSON("progress", gin.H{"node": "x"}) // 不得 panic
}
