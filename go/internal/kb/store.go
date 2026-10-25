// kb 的外部依赖与 PG 向量库访问：SiliconFlow embedding/rerank、pgvector 查询与写入。
package kb

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"
)

// ---- SiliconFlow 客户端 ----

// SiliconFlow 封装 embedding 与 rerank 两个端点。
type SiliconFlow struct {
	BaseURL string
	APIKey  string
	Client  *http.Client
}

// Embed 对一批文本生成向量（bge-m3，1024 维）。
func (s *SiliconFlow) Embed(ctx context.Context, model string, texts []string) ([][]float32, error) {
	if len(texts) == 0 {
		return nil, nil
	}
	body, _ := json.Marshal(map[string]any{"model": model, "input": texts})
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost,
		strings.TrimSuffix(s.BaseURL, "/")+"/v1/embeddings", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+s.APIKey)
	resp, err := s.Client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return nil, fmt.Errorf("embed %d: %s", resp.StatusCode, string(b))
	}
	var out struct {
		Data []struct {
			Index     int       `json:"index"`
			Embedding []float32 `json:"embedding"`
		} `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, err
	}
	vecs := make([][]float32, len(texts))
	for _, d := range out.Data {
		if d.Index >= 0 && d.Index < len(vecs) {
			vecs[d.Index] = d.Embedding
		}
	}
	return vecs, nil
}

// RerankResult 重排后的条目。
type RerankResult struct {
	Index         int
	RelevanceScore float64
}

// Rerank 调 SiliconFlow /v1/rerank（BAAI/bge-reranker-v2-m3）。
// 失败必须降级而不是报错：返回 nil，调用方沿用 RRF 顺序。超时 3 秒。
func (s *SiliconFlow) Rerank(ctx context.Context, model, query string, documents []string, topN int) []RerankResult {
	if s.APIKey == "" || len(documents) == 0 {
		return nil
	}
	if topN > len(documents) {
		topN = len(documents)
	}
	body, _ := json.Marshal(map[string]any{
		"model": model, "query": query, "documents": documents,
		"top_n": topN, "return_documents": false,
	})
	ctx2, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx2, http.MethodPost,
		strings.TrimSuffix(s.BaseURL, "/")+"/v1/rerank", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+s.APIKey)
	resp, err := s.Client.Do(req)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil
	}
	var out struct {
		Results []struct {
			Index          int     `json:"index"`
			RelevanceScore float64 `json:"relevance_score"`
		} `json:"results"`
	}
	if json.NewDecoder(resp.Body).Decode(&out) != nil || len(out.Results) == 0 {
		return nil
	}
	results := make([]RerankResult, 0, len(out.Results))
	for _, r := range out.Results {
		results = append(results, RerankResult{Index: r.Index, RelevanceScore: r.RelevanceScore})
	}
	return results
}

func postJSON(ctx context.Context, url, apiKey string, body []byte) ([]byte, error) {
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+apiKey)
	resp, err := siliconHTTPClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<22))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("LLM %d: %s", resp.StatusCode, string(data[:min(512, len(data))]))
	}
	return data, nil
}

func unmarshal(data []byte, v any) error { return json.Unmarshal(data, v) }

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

// siliconHTTPClient 供 ChatClient 与 SiliconFlow 共用（超时分层在调用方控制）。
var siliconHTTPClient = &http.Client{Timeout: 120 * time.Second}

// ---- pgvector 访问（vector_store，Spring AI PgVectorStore 同构）----

// PgStore 是 vector_store 的仓储。
type PgStore struct {
	db *sql.DB
}

// NewPgStore 构造。
func NewPgStore(db *sql.DB) *PgStore { return &PgStore{db: db} }

// Hit 一条向量命中。
type Hit struct {
	ID       string
	Content  string
	Metadata map[string]any
}

// SearchVectors 余弦相似度检索：1 - (embedding <=> qvec) >= threshold。
// categories 非空时按 metadata->>'category' 收口（与 Java 的过滤表达式同口径）。
func (s *PgStore) SearchVectors(ctx context.Context, qvec []float32, topK int, threshold float64, categories []string) ([]Hit, error) {
	vecJSON, err := json.Marshal(qvec)
	if err != nil {
		return nil, err
	}
	query := `SELECT id::text, COALESCE(content,''), metadata,
	        1 - (embedding <=> $1::vector) AS similarity
	  FROM vector_store WHERE content IS NOT NULL
	    AND 1 - (embedding <=> $1::vector) >= $2`
	args := []any{string(vecJSON), threshold}
	if len(categories) > 0 {
		ph := strings.TrimSuffix(strings.Repeat(",$", len(categories)+2), ",")
		_ = ph
		conds := make([]string, len(categories))
		for i, c := range categories {
			conds[i] = fmt.Sprintf("metadata->>'category' = $%d", i+3)
			args = append(args, c)
		}
		query += " AND (" + strings.Join(conds, " OR ") + ")"
	}
	query += fmt.Sprintf(" ORDER BY embedding <=> $1::vector LIMIT $%d", len(args)+1)
	args = append(args, topK)
	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var hits []Hit
	for rows.Next() {
		var h Hit
		var meta []byte
		if err := rows.Scan(&h.ID, &h.Content, &meta, new(float64)); err != nil {
			return nil, err
		}
		if len(meta) > 0 {
			_ = json.Unmarshal(meta, &h.Metadata)
		}
		hits = append(hits, h)
	}
	return hits, rows.Err()
}

// DeleteByDocument 按文档元数据清分片（幂等写入 / 删除 / 重建共用）。
func (s *PgStore) DeleteByDocument(ctx context.Context, documentID string) (int64, error) {
	res, err := s.db.ExecContext(ctx, `DELETE FROM vector_store WHERE metadata->>'document_id' = $1`, documentID)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

// CountByDocument 该文档现有分片数。
func (s *PgStore) CountByDocument(ctx context.Context, documentID string) (int, error) {
	var n int
	err := s.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM vector_store WHERE metadata->>'document_id' = $1`, documentID).Scan(&n)
	return n, err
}

// Chunk 一条待入库分片。
type Chunk struct {
	Content  string
	Metadata map[string]any
}

// InsertChunks 写入分片：embedding 由调用方先算好；id 用 UUID（uuid 列只收合法 UUID，
// 自定义拼接串会抛错——Java 版实测踩过）。分批提交（默认每批 16 条，摊薄单请求体）。
func (s *PgStore) InsertChunks(ctx context.Context, chunks []Chunk, vectors [][]float32, batch int) (int, error) {
	if batch <= 0 {
		batch = 16
	}
	written := 0
	for start := 0; start < len(chunks); start += batch {
		end := start + batch
		if end > len(chunks) {
			end = len(chunks)
		}
		tx, err := s.db.BeginTx(ctx, nil)
		if err != nil {
			return written, err
		}
		for i := start; i < end; i++ {
			vecJSON, _ := json.Marshal(vectors[i])
			metaJSON, _ := json.Marshal(chunks[i].Metadata)
			if _, err := tx.ExecContext(ctx,
				`INSERT INTO vector_store (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?::vector)`,
				uuid.NewString(), chunks[i].Content, string(metaJSON), string(vecJSON)); err != nil {
				tx.Rollback()
				return written, err
			}
		}
		if err := tx.Commit(); err != nil {
			return written, err
		}
		written = end
	}
	return written, nil
}

// AllContents 关键词通道的语料捞取（上限 500，Java 同款）：id + content + category。
func (s *PgStore) AllContents(ctx context.Context, categories []string) ([]Hit, error) {
	query := `SELECT id::text, COALESCE(content,''), metadata FROM vector_store WHERE content IS NOT NULL`
	var args []any
	if len(categories) > 0 {
		conds := make([]string, len(categories))
		for i, c := range categories {
			conds[i] = fmt.Sprintf("metadata->>'category' = $%d", i+1)
			args = append(args, c)
		}
		query += " AND (" + strings.Join(conds, " OR ") + ")"
	}
	query += " LIMIT 500"
	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var hits []Hit
	for rows.Next() {
		var h Hit
		var meta []byte
		if err := rows.Scan(&h.ID, &h.Content, &meta); err != nil {
			return nil, err
		}
		hits = append(hits, h)
	}
	return hits, rows.Err()
}
