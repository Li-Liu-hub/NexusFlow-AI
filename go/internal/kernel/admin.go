// 管理面与登录处理器（Register 里引用的其余端点）。
package kernel

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/aismp/platform/internal/httpkit"
)

// kernelLogin 内核登录（svc 账号 + 真人，api_db 的 a_user / a_auth_token）。
// SHA-256 无盐 hex 口令，UUID 令牌 24h —— 与 backend /auth/login 同构。
func (e *Engine) kernelLogin(c *gin.Context) {
	var req struct {
		UserCode *string `json:"userCode"`
		Password *string `json:"password"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.UserCode == nil || req.Password == nil ||
		*req.UserCode == "" || *req.Password == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "userCode 和 password 不能为空"})
		return
	}
	ctx := c.Request.Context()
	var userCode, role, status string
	var passwordHash string
	err := e.Auth.QueryRowContext(ctx,
		`SELECT user_code, role, IFNULL(status,''), password_hash FROM a_user WHERE user_code = ?`,
		*req.UserCode).Scan(&userCode, &role, &status, &passwordHash)
	if err == sql.ErrNoRows {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "账号或密码错误"})
		return
	}
	if err != nil {
		e.Log.Error("内核登录查账号失败", "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "系统内部异常"})
		return
	}
	sum := sha256Hex(*req.Password)
	if sum != passwordHash && !strings.EqualFold(sum, passwordHash) {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "账号或密码错误"})
		return
	}
	if status != "" && status != "active" {
		c.JSON(http.StatusForbidden, gin.H{"error": "账号已停用"})
		return
	}
	token := newUUID()
	if _, err := e.Auth.ExecContext(ctx,
		`INSERT INTO a_auth_token (token, user_code, role, expires_at) VALUES (?, ?, ?, ?)`,
		token, userCode, role, time.Now().Add(24*time.Hour)); err != nil {
		e.Log.Error("内核登录签发令牌失败", "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "系统内部异常"})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"token": token, "userCode": userCode, "role": role, "expiresInHours": 24,
	})
}

// sha256Hex 与 backend auth.SHA256Hex 同一契约（无盐小写 hex）。
func sha256Hex(s string) string {
	sum := sha256.Sum256([]byte(s))
	return hex.EncodeToString(sum[:])
}

func (e *Engine) introspect(c *gin.Context) {
	asm, err := e.Registry.Get(c.Request.Context(), c.Param("agentCode"))
	if err != nil || asm == nil {
		c.JSON(http.StatusOK, gin.H{"error": "数字员工不可用: " + c.Param("agentCode")})
		return
	}
	skillNames := []string{}
	for _, k := range asm.Skills {
		skillNames = append(skillNames, k.SkillName)
	}
	toolNames := []string{}
	for _, t := range asm.Tools {
		toolNames = append(toolNames, t.ToolName)
	}
	c.JSON(http.StatusOK, gin.H{
		"agentCode": asm.Agent.AgentCode, "version": asm.Agent.Version,
		"skills": skillNames, "tools": toolNames,
		"systemPrompt": asm.SystemPrompt, "assembledAt": asm.AssembledAt,
	})
}

// agentsForRole 角色圈定的员工卡片（a_role_def + a_agent_role_rel）。
func (e *Engine) agentsForRole(c *gin.Context) {
	role := c.Query("role")
	where, args := "a.status='active'", []any{}
	if role != "" {
		where = "a.status='active' AND rr.role_code = ?"
		args = append(args, role)
	}
	rows, err := dbMaps(c.Request.Context(), e.Terminal.db, `
		SELECT a.agent_code, a.agent_name, IFNULL(a.description,'') AS description
		FROM a_agent a
		JOIN a_agent_role_rel rr ON rr.agent_id = a.id
		WHERE `+where+` ORDER BY rr.sort_order, a.id`, args...)
	handlersJSON(c, rows, err)
}

func (e *Engine) conversationList(c *gin.Context) {
	agentCode := c.Query("agentCode")
	userID := resolveUserId(c, c.Query("userId"))
	limit := clampInt(queryInt(c, "limit", 20), 1, 100)
	rows, err := e.Terminal.ListConversations(c.Request.Context(), agentCode, userID, limit)
	handlersJSON(c, rows, err)
}

func (e *Engine) conversationDetail(c *gin.Context) {
	detail, err := e.Terminal.ConversationDetail(c.Request.Context(),
		c.Param("conversationId"), clampInt(queryInt(c, "messageLimit", 100), 1, 500))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "系统内部异常"})
		return
	}
	if detail == nil {
		c.JSON(http.StatusOK, gin.H{"error": "会话不存在: " + c.Param("conversationId")})
		return
	}
	c.JSON(http.StatusOK, detail)
}

func (e *Engine) conversationArchive(c *gin.Context) {
	n, err := e.Terminal.ArchiveConversation(c.Request.Context(), c.Param("conversationId"))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "系统内部异常"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"archived": n})
}

func (e *Engine) conversationStats(c *gin.Context) {
	rows, err := dbMaps(c.Request.Context(), e.Terminal.db, `
		SELECT COUNT(*) AS conversations,
		       (SELECT COUNT(*) FROM a_chat_message) AS messages
		FROM a_chat_conversation`)
	handlersJSON(c, firstOrNil(rows), err)
}

func (e *Engine) conversationStatsByAgent(c *gin.Context) {
	rows, err := dbMaps(c.Request.Context(), e.Terminal.db, `
		SELECT agent_code, COUNT(*) AS conversations
		FROM a_chat_conversation GROUP BY agent_code ORDER BY conversations DESC`)
	handlersJSON(c, rows, err)
}

// ---- 管理面 CRUD ----

func (e *Engine) adminAgentList(c *gin.Context) {
	rows, err := dbMaps(c.Request.Context(), e.Terminal.db, `
		SELECT `+agentColumns+` FROM a_agent ORDER BY id`)
	handlersJSON(c, rows, err)
}

func (e *Engine) adminSkillList(c *gin.Context) {
	rows, err := dbMaps(c.Request.Context(), e.Terminal.db,
		`SELECT * FROM a_agent_skill ORDER BY id`)
	handlersJSON(c, rows, err)
}

func (e *Engine) adminToolList(c *gin.Context) {
	rows, err := dbMaps(c.Request.Context(), e.Terminal.db,
		`SELECT * FROM a_llm_tool ORDER BY id`)
	handlersJSON(c, rows, err)
}

func (e *Engine) adminRoleList(c *gin.Context) {
	rows, err := dbMaps(c.Request.Context(), e.Terminal.db,
		`SELECT * FROM a_role_def ORDER BY sort_order, id`)
	handlersJSON(c, rows, err)
}

func (e *Engine) adminKbList(c *gin.Context) {
	rows, err := dbMaps(c.Request.Context(), e.Terminal.db,
		`SELECT id, knowledge_base_id, name, IFNULL(description,'') AS description,
			IFNULL(is_default,0) AS is_default, created_at FROM a_knowledge_base ORDER BY id`)
	handlersJSON(c, rows, err)
}

func (e *Engine) adminKbDocs(c *gin.Context) {
	// 文档清单在 PG 的 vector_store metadata 里聚合（document_id + 文件名 + 分片数）
	rows, err := dbMaps(c.Request.Context(), e.PG, `
		SELECT metadata->>'document_id' AS document_id,
		       metadata->>'source'      AS source,
		       COUNT(*)                 AS chunk_count,
		       MIN(metadata->>'created_at') AS created_at
		FROM vector_store
		WHERE metadata->>'kb_id' = $1
		GROUP BY 1, 2 ORDER BY 1`, c.Param("kbId"))
	handlersJSON(c, rows, err)
}

func (e *Engine) adminAgentReload(c *gin.Context) {
	e.Registry.Evict(c.Param("code"))
	c.JSON(http.StatusOK, gin.H{"reloaded": c.Param("code")})
}

func (e *Engine) adminRuntimeCache(c *gin.Context) {
	e.Registry.mu.Lock()
	defer e.Registry.mu.Unlock()
	list := []any{}
	for code, asm := range e.Registry.cache {
		list = append(list, gin.H{
			"agentCode": code, "version": asm.Agent.Version, "assembledAt": asm.AssembledAt,
		})
	}
	c.JSON(http.StatusOK, gin.H{"cached": len(list), "entries": list})
}

func (e *Engine) auditRecent(c *gin.Context) {
	rows, err := e.Terminal.RecentToolCalls(c.Request.Context(),
		clampInt(queryInt(c, "limit", 30), 1, 200))
	handlersJSON(c, rows, err)
}

func (e *Engine) auditStats(c *gin.Context) {
	rows, err := e.Terminal.ToolCallStats(c.Request.Context())
	handlersJSON(c, rows, err)
}

// adminDBSchema SQL 工具建模板用的库表结构（information_schema 精简版）。
func (e *Engine) adminDBSchema(c *gin.Context) {
	rows, err := dbMaps(c.Request.Context(), e.API, `
		SELECT table_name, column_name, data_type
		FROM information_schema.columns
		WHERE table_schema = DATABASE()
		ORDER BY table_name, ordinal_position`)
	handlersJSON(c, rows, err)
}

// adminToolTemplate 工具定义模板（前端「新建工具」表单预填）。
func (e *Engine) adminToolTemplate(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"toolType": "SQL_TEMPLATE",
		"inputSchema": map[string]any{
			"type": "object",
			"properties": map[string]any{
				"keyword": map[string]any{"type": "string", "description": "查询关键字"},
			},
			"required": []string{},
		},
		"sqlTemplate": "SELECT * FROM some_table WHERE col LIKE CONCAT('%', :keyword, '%')",
		"resultLimit": 50,
	})
}

// ---- 小工具 ----

func queryInt(c *gin.Context, key string, def int) int {
	if v := c.Query(key); v != "" {
		n := 0
		for _, r := range v {
			if r < '0' || r > '9' {
				return def
			}
			n = n*10 + int(r-'0')
		}
		return n
	}
	return def
}

func clampInt(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

func firstOrNil(rows []map[string]any) any {
	if len(rows) == 0 {
		return map[string]any{}
	}
	return rows[0]
}

// 编译期引用清理。
var _ = slog.Default
var _ = httpkit.Fail
var _ = context.Background
