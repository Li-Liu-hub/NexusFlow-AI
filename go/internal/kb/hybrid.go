// 混合检索服务（HybridRetrievalService 的 Go 等价物）与 /oa/chat/sync 的 RAG 问答链。
package kb

import (
	"context"
	"fmt"
	"log/slog"
	"sort"
	"strings"
)

// Config 检索栈参数（对齐 KbHybridProperties + application.yml 缺省）。
type Config struct {
	Enabled        bool     // 总开关（false → 单路向量）
	MultiQuery     int      // 多查询扩展条数（<=1 → 只用原问）
	RRFK           int      // RRF 平滑常数（60）
	Candidates     int      // 每路候选数（20）
	TopK           int      // 最终返回条数（5）
	VectorWeight   float64  // 向量路权重（1.0）
	KeywordWeight  float64  // 关键词路权重（1.0）
	RerankEnabled  bool
	RerankModel    string
	SimThreshold   float64  // 向量相似度阈值（0.5）
	EmbedModel     string   // BAAI/bge-m3
	ChatModel      string   // 重写/扩展/回答用（mimo-v2.6-flash）
}

// Service 混合检索 + RAG 问答。
type Service struct {
	Cfg   Config
	PG    *PgStore
	SF    *SiliconFlow
	LLM   *ChatClient
	Log   *slog.Logger
}

// ChatClient 是 OpenAI 兼容的非流式补全客户端（重写/扩展/回答用小模型）。
type ChatClient struct {
	BaseURL string
	APIKey  string
	Model   string
}

// Chat 单轮补全，返回文本。
func (c *ChatClient) Chat(ctx context.Context, system, user string) (string, error) {
	return c.ChatWithModel(ctx, c.Model, system, user)
}

// ChatWithModel 指定模型的单轮补全。
func (c *ChatClient) ChatWithModel(ctx context.Context, model, system, user string) (string, error) {
	body := fmt.Sprintf(`{"model":%q,"temperature":0.3,"messages":[{"role":"system","content":%q},{"role":"user","content":%q}]}`,
		model, system, user)
	return c.rawChat(ctx, body)
}

func (c *ChatClient) rawChat(ctx context.Context, body string) (string, error) {
	respBody, err := postJSON(ctx, c.BaseURL+"/v1/chat/completions", c.APIKey, []byte(body))
	if err != nil {
		return "", err
	}
	var out struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := unmarshal(respBody, &out); err != nil {
		return "", err
	}
	if len(out.Choices) == 0 {
		return "", fmt.Errorf("LLM 空响应")
	}
	return out.Choices[0].Message.Content, nil
}

// Search 混合检索入口：多查询扩展 → 向量+关键词双路 → RRF → Rerank。
// 返回命中片段文本（已按相关度排序）；无命中返回空列表。
// 每一层可降级：扩展失败退原问、关键词异常退纯向量、rerank 失败沿用 RRF 顺序。
func (s *Service) Search(ctx context.Context, query, filterExpression string) []string {
	if strings.TrimSpace(query) == "" {
		return []string{}
	}
	cfg := s.Cfg
	if !cfg.Enabled {
		return s.singleVectorSearch(ctx, query, cfg.TopK, filterExpression)
	}
	variants := s.expand(ctx, query)
	textByKey := map[string]string{}
	var rankedLists [][]string
	var weights []float64

	// ① 向量路：每个子查询各召回一批
	perVariant := cfg.VectorWeight / float64(max(1, len(variants)))
	qvecCache := map[string][]Hit{}
	for _, variant := range variants {
		hits := s.vectorSearch(ctx, variant, cfg.Candidates, filterExpression)
		qvecCache[variant] = hits
		keys := make([]string, 0, len(hits))
		for _, h := range hits {
			key := keyOf(h)
			if _, ok := textByKey[key]; !ok {
				textByKey[key] = h.Content
			}
			keys = append(keys, key)
		}
		if len(keys) > 0 {
			rankedLists = append(rankedLists, keys)
			weights = append(weights, perVariant)
		}
	}

	// ② 关键词路：字面命中（型号编码、专有名词靠它兜）
	kwHits := s.keywordSearch(ctx, query, cfg.Candidates, CategoriesOf(filterExpression))
	if len(kwHits) > 0 {
		keys := make([]string, 0, len(kwHits))
		for _, h := range kwHits {
			if _, ok := textByKey[h.Key]; !ok {
				textByKey[h.Key] = h.Content
			}
			keys = append(keys, h.Key)
		}
		rankedLists = append(rankedLists, keys)
		weights = append(weights, cfg.KeywordWeight)
	}
	if len(rankedLists) == 0 {
		return []string{}
	}

	// ③ RRF 融合
	fused := RRFFuse(rankedLists, weights, cfg.RRFK)
	candidates := make([]string, 0, len(fused))
	for _, r := range fused {
		if text := textByKey[r.Key]; text != "" {
			candidates = append(candidates, text)
		}
	}
	if len(candidates) == 0 {
		return []string{}
	}

	// ④ Rerank（失败沿用 RRF 顺序）
	finalList := candidates
	if cfg.RerankEnabled {
		if results := s.SF.Rerank(ctx, cfg.RerankModel, query, candidates, cfg.TopK); results != nil {
			ordered := make([]string, 0, len(results))
			for _, r := range results {
				if r.Index >= 0 && r.Index < len(candidates) {
					ordered = append(ordered, candidates[r.Index])
				}
			}
			if len(ordered) > 0 {
				finalList = ordered
			}
		}
	}
	if len(finalList) > cfg.TopK {
		finalList = finalList[:cfg.TopK]
	}
	s.Log.Info("混合检索", "路数", len(rankedLists), "子查询", len(variants),
		"候选", len(candidates), "返回", len(finalList))
	return finalList
}

// Ingest 文本入库：删旧分片（幂等）→ 切分 → embedding → 写 vector_store。
// 返回实际分片数与嵌入模型名（内核回写账本用）。
func (s *Service) Ingest(ctx context.Context, documentID, knowledgeBaseID, sourceName, category, content string, chunkSize int) (int, error) {
	if chunkSize < 100 {
		chunkSize = 800
	}
	if _, err := s.PG.DeleteByDocument(ctx, documentID); err != nil {
		return 0, fmt.Errorf("清旧分片失败: %w", err)
	}
	chunks := SplitChunks(content, chunkSize)
	if len(chunks) == 0 {
		return 0, fmt.Errorf("切分后没有有效分片")
	}
	vecs, err := s.SF.Embed(ctx, s.Cfg.EmbedModel, chunks)
	if err != nil {
		return 0, fmt.Errorf("embedding 失败: %w", err)
	}
	kbChunks := make([]Chunk, len(chunks))
	for i, c := range chunks {
		meta := map[string]any{
			"document_id":       documentID,
			"knowledge_base_id": knowledgeBaseID,
			"source_name":       orDefault(sourceName, "untitled"),
			"chunk_index":       i,
		}
		if category != "" {
			meta["category"] = category
		}
		kbChunks[i] = Chunk{Content: c, Metadata: meta}
	}
	written, err := s.PG.InsertChunks(ctx, kbChunks, vecs, 16)
	if err != nil {
		return 0, err
	}
	s.Log.Info("知识库入库完成", "document", documentID, "source", sourceName, "分片", written)
	return written, nil
}

// DeleteByDocument 按文档清全部向量。
func (s *Service) DeleteByDocument(ctx context.Context, documentID string) (int64, error) {
	return s.PG.DeleteByDocument(ctx, documentID)
}

// OaChatSync /oa/chat/sync 的核心逻辑：
// 查询重写+分类（一次 LLM）→ 混合检索 → 空上下文拒答 → 上下文增强回答。
func (s *Service) OaChatSync(ctx context.Context, message string) (string, error) {
	rewritten, category := s.transformQuery(ctx, message)
	filter := ""
	if category != "" && category != "none" {
		// Spring AI 风格过滤表达式，供向量路与关键词路同口径收口
		filter = "category in [" + category + "]"
	}
	passages := s.Search(ctx, rewritten, filter)
	if len(passages) == 0 {
		// allowEmptyContext=false：超纲题礼貌拒绝，防止瞎编
		return "抱歉，这个问题超出了我掌握的《终端业务规范库》范围，我无法提供准确的回答。" +
			"建议联系终端管理员或查阅相关管理文档。", nil
	}
	var ctxBuf strings.Builder
	for i, p := range passages {
		fmt.Fprintf(&ctxBuf, "【参考材料 %d】\n%s\n\n", i+1, p)
	}
	system := "你是「运营商固网终端管理」系统的智能客服。仅依据提供的参考材料回答用户问题；" +
		"材料之间相互矛盾时以更具体的为准；参考材料里没有的内容如实说明不知道，不要编造。"
	user := "参考材料：\n" + ctxBuf.String() + "\n用户问题：" + message
	answer, err := s.LLM.Chat(ctx, system, user)
	if err != nil {
		return "", err
	}
	return answer, nil
}

// transformQuery 一次 LLM 调用同时完成查询重写 + 分类（支持多轮指代消解）。
// 失败降级为原问 + 不过滤。
func (s *Service) transformQuery(ctx context.Context, question string) (string, string) {
	system := "你是「运营商固网终端管理」系统的查询分析助手。对用户问题做两件事：\n" +
		"1. 查询重写：把口语化问题改写为规范、完整的表达，保留原始意图，使用终端管理术语（光猫、机顶盒、FTTR、串码、仓位、调拨、领用、回收、利旧、盘点、库龄等）\n" +
		"2. 分类判断：判断问题属于哪些分类（可多选）：intro(引言与背景), process(系统流程), role(角色职责), operation(业务操作：库存管理/领用管理/装机出库/回收管理/利旧/终端查询/盘点/报表), trouble(常见问题与故障排查)\n" +
		"如果与五个分类都无关，返回 none。\n" +
		"输出格式（严格遵守，不要加其他内容）：\nQUERY: 改写后的问题\nCATEGORY: 分类1,分类2\n" +
		"示例：\n输入：光猫怎么调拨给别人\nQUERY: 光猫如何通过库存调拨流程转移到其他仓位\nCATEGORY: operation\n" +
		"输入：调拨要谁审批\nQUERY: 终端调拨需要哪一级主管审批\nCATEGORY: operation,role"
	out, err := s.LLM.Chat(ctx, system, question)
	if err != nil {
		s.Log.Warn("查询重写失败，退回原问", "err", err)
		return question, ""
	}
	var q, cat string
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		if v, ok := stripPrefix(line, "QUERY:"); ok && q == "" {
			q = v
		}
		if v, ok := stripPrefix(line, "CATEGORY:"); ok && cat == "" {
			cat = strings.ToLower(v)
		}
	}
	if q == "" {
		q = question
	}
	return q, cat
}

// expand 多查询扩展：一次 LLM 生成 N 个子查询，始终包含原始问题。
func (s *Service) expand(ctx context.Context, query string) []string {
	if s.Cfg.MultiQuery <= 1 {
		return []string{query}
	}
	system := "你是查询扩展助手。将用户的问题扩展为 " + itoa(s.Cfg.MultiQuery) + " 个语义不同但相关的子查询。\n" +
		"要求：\n1. 每个子查询从不同角度表达原始意图\n" +
		"2. 使用终端管理术语（库存、调拨、领用、回收、盘点、库龄、串码、仓位等）\n" +
		"3. 每行一个子查询，不要编号，不要解释"
	out, err := s.LLM.Chat(ctx, system, query)
	if err != nil {
		s.Log.Warn("多查询扩展失败，退回原问", "err", err)
		return []string{query}
	}
	seen := map[string]bool{query: true}
	limited := []string{query}
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		line = trimNumbering(line)
		if line == "" || seen[line] {
			continue
		}
		seen[line] = true
		limited = append(limited, line)
		if len(limited) >= s.Cfg.MultiQuery {
			break
		}
	}
	return limited
}

// vectorSearch 单路向量检索；失败降级为空（由调用方跳过该路）。
func (s *Service) vectorSearch(ctx context.Context, query string, topK int, filter string) []Hit {
	vec, err := s.SF.Embed(ctx, s.Cfg.EmbedModel, []string{query})
	if err != nil {
		s.Log.Warn("向量检索失败（embedding）", "err", err)
		return nil
	}
	if len(vec) == 0 || len(vec[0]) == 0 {
		return nil
	}
	hits, err := s.PG.SearchVectors(ctx, vec[0], topK, s.Cfg.SimThreshold, CategoriesOf(filter))
	if err != nil {
		s.Log.Warn("向量检索失败", "err", err)
		return nil
	}
	return hits
}

// singleVectorSearch 关闭混合检索时的原路径：单路向量。
func (s *Service) singleVectorSearch(ctx context.Context, query string, topK int, filter string) []string {
	hits := s.vectorSearch(ctx, query, topK, filter)
	out := make([]string, 0, len(hits))
	for _, h := range hits {
		out = append(out, h.Content)
	}
	return out
}

// keywordHit 关键词路命中。
type keywordHit struct {
	Key     string
	Content string
}

// keywordSearch 2-gram 覆盖率打分（对齐 KeywordSearcher：捞 500 行在内存打分）。
func (s *Service) keywordSearch(ctx context.Context, query string, topN int, categories []string) []keywordHit {
	rows, err := s.PG.AllContents(ctx, categories)
	if err != nil {
		s.Log.Warn("关键词通道异常，降级为纯向量", "err", err)
		return nil
	}
	type scored struct {
		hit   keywordHit
		score float64
	}
	var list []scored
	for _, h := range rows {
		if score := KeywordScore(query, h.Content); score > 0 {
			list = append(list, scored{keywordHit{Key: h.ID, Content: h.Content}, score})
		}
	}
	sort.Slice(list, func(i, j int) bool { return list[i].score > list[j].score })
	if len(list) > topN {
		list = list[:topN]
	}
	out := make([]keywordHit, 0, len(list))
	for _, it := range list {
		out = append(out, it.hit)
	}
	return out
}

// keyOf 跨路去重 key：文档 + 切片序号优先，退回内容哈希。
func keyOf(h Hit) string {
	if h.Metadata != nil {
		doc, dok := h.Metadata["document_id"]
		chunk, cok := h.Metadata["chunk_index"]
		if dok && cok {
			return fmt.Sprintf("%v:%v", doc, chunk)
		}
	}
	return h.ID
}

func orDefault(v, def string) string {
	if strings.TrimSpace(v) == "" {
		return def
	}
	return v
}

func stripPrefix(line, prefix string) (string, bool) {
	if strings.HasPrefix(strings.ToUpper(line), prefix) {
		return strings.TrimSpace(line[len(prefix):]), true
	}
	return "", false
}

func trimNumbering(s string) string {
	for _, p := range []string{"1.", "2.", "3.", "4.", "5.", "1、", "2、", "3、", "4、", "5、"} {
		if strings.HasPrefix(s, p) {
			return strings.TrimSpace(s[len(p):])
		}
	}
	return s
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var buf [8]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	return string(buf[i:])
}

func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}
