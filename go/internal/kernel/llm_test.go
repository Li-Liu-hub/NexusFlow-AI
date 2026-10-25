// 内核 LLM 流式解析回归测试：MiMo 等兼容端点在每个 tool_call 增量块里
// **全量重发函数名**（而非 OpenAI 标准的只发一次），解析器必须覆盖而非拼接
// ——曾因此拼出 searchHandbooksearchHandbook... 导致模型十轮重试后空答。
package kernel

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestStreamChat_工具名全量重发时不得拼接(t *testing.T) {
	sse := strings.Join([]string{
		`data: {"choices":[{"delta":{"content":"","role":"assistant","tool_calls":null}}]}`,
		`data: {"choices":[{"delta":{"content":null,"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"searchHandbook","arguments":""}}]}}]}`,
		// 第二块：OpenAI 标准是 name 为空；MiMo 是全量重发 —— 两种都必须得到 "searchHandbook"
		`data: {"choices":[{"delta":{"content":null,"tool_calls":[{"index":0,"id":null,"type":"function","function":{"name":"searchHandbook","arguments":"{}"}}]}}]}`,
		`data: {"choices":[{"delta":{"content":null,"tool_calls":null},"finish_reason":"tool_calls"}]}`,
		`data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}`,
		`data: [DONE]`,
		"",
	}, "\n")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = w.Write([]byte(sse))
	}))
	defer srv.Close()

	llm := &LLM{baseURL: srv.URL, apiKey: "test", client: srv.Client()}
	var got ToolCall
	_, _, err := llm.StreamChat(context.Background(), "m", 0.7, nil, nil,
		func(string) error { return nil },
		func(tc ToolCall) error { got = tc; return nil })
	if err != nil {
		t.Fatalf("err = %v", err)
	}
	if got.Function.Name != "searchHandbook" {
		t.Fatalf("工具名被拼接: %q", got.Function.Name)
	}
	if got.Function.Arguments != "{}" {
		t.Fatalf("args = %q", got.Function.Arguments)
	}
	if got.ID != "call_1" {
		t.Fatalf("id = %q", got.ID)
	}
}

func TestStreamChat_纯文本与usage解析(t *testing.T) {
	sse := strings.Join([]string{
		`data: {"choices":[{"delta":{"content":"你"}}]}`,
		`data: {"choices":[{"delta":{"content":"好"}}]}`,
		`data: {"choices":[{"delta":{"content":null},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}`,
		`data: [DONE]`,
		"",
	}, "\n")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = w.Write([]byte(sse))
	}))
	defer srv.Close()

	llm := &LLM{baseURL: srv.URL, apiKey: "test", client: srv.Client()}
	var full strings.Builder
	prompt, completion, err := llm.StreamChat(context.Background(), "m", 0.7, nil, nil,
		func(d string) error { full.WriteString(d); return nil },
		func(ToolCall) error { t.Fatal("不应有工具调用"); return nil })
	if err != nil {
		t.Fatalf("err = %v", err)
	}
	if full.String() != "你好" {
		t.Fatalf("content = %q", full.String())
	}
	if prompt != 3 || completion != 2 {
		t.Fatalf("usage = %d/%d", prompt, completion)
	}
}
