// kb 包功能点测试：RRF 融合、2-gram 关键词打分、切分器、过滤表达式解析。
package kb

import (
	"strings"
	"testing"
)

func TestRRFFuse_多路名次融合去重(t *testing.T) {
	fused := RRFFuse([][]string{{"x", "y"}, {"y", "z"}}, []float64{1, 1}, 60)
	// y 在两路都出现，融合分应最高
	if len(fused) == 0 || fused[0].Key != "y" {
		t.Fatalf("fused = %+v", fused)
	}
}

func TestRRFFuse_同路重复key只按最好名次计分(t *testing.T) {
	fused := RRFFuse([][]string{{"x", "x", "x"}}, nil, 60)
	if len(fused) != 1 {
		t.Fatalf("去重失败: %+v", fused)
	}
}

func TestKeywordScore_中文二字组命中(t *testing.T) {
	if KeywordScore("库龄分布", "库存的库龄分布统计") <= 0 {
		t.Fatal("命中应 > 0")
	}
	if KeywordScore("完全无关词", "库存的库龄分布统计") != 0 {
		t.Fatal("无命中应为 0")
	}
}

func TestSplitChunks_段落合并不超限(t *testing.T) {
	content := "第一段。\n\n第二段。\n\n第三段。"
	chunks := SplitChunks(content, 100)
	if len(chunks) != 1 || !strings.Contains(chunks[0], "第三段") {
		t.Fatalf("chunks = %v", chunks)
	}
}

func TestSplitChunks_超长段落在句末断开(t *testing.T) {
	long := strings.Repeat("这是一句话。", 200) // 1000 字符
	chunks := SplitChunks(long, 800)
	if len(chunks) < 2 {
		t.Fatalf("超长段应被硬切: %d", len(chunks))
	}
	for _, c := range chunks {
		if len([]rune(c)) > 800 {
			t.Fatalf("分片超限: %d", len([]rune(c)))
		}
	}
}

func TestCategoriesOf_解析过滤表达式(t *testing.T) {
	got := CategoriesOf("category in ['operation','role']")
	if len(got) != 2 || got[0] != "operation" {
		t.Fatalf("got = %v", got)
	}
	if CategoriesOf("nothing here") != nil {
		t.Fatal("无过滤表达式应返回 nil")
	}
}
