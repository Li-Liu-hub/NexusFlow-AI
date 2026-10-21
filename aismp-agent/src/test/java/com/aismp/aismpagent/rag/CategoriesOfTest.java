package com.aismp.aismpagent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 从 Spring AI 的过滤表达式里抠分类值。
 *
 * <p>为什么要有这个：内核的查询转换器每次都注入 {@code category in ['operation','process']}。
 * 向量路可以原样透传（Spring AI 会翻译成 SQL），但关键词路是自己写 SQL 的，
 * 必须拿到同一份分类值，否则它会**绕过分区**把别的分类捞进来 —— 那属于数据越界，不是精度问题。
 *
 * <p>解析不出来时返回空 → 关键词路不参与（宁可少一路，也不能漏过滤）。
 */
class CategoriesOfTest {

    @Test
    void parsesSingleAndMultipleValues() {
        assertThat(HybridRetrievalService.categoriesOf("category in ['operation']"))
                .containsExactly("operation");
        assertThat(HybridRetrievalService.categoriesOf("category in ['operation', 'process']"))
                .containsExactly("operation", "process");
        assertThat(HybridRetrievalService.categoriesOf("CATEGORY IN ['A','B']"))
                .containsExactly("A", "B");
    }

    @Test
    void blankOrMissingReturnsEmpty() {
        assertThat(HybridRetrievalService.categoriesOf(null)).isEmpty();
        assertThat(HybridRetrievalService.categoriesOf("  ")).isEmpty();
        assertThat(HybridRetrievalService.categoriesOf("source_name == 'x'")).isEmpty();
    }

    @Test
    void toleratesExtraWhitespace() {
        assertThat(HybridRetrievalService.categoriesOf("category  in  [ 'operation' , 'process' ]"))
                .containsExactly("operation", "process");
    }

    @Test
    void listIsImmutableSafe() {
        List<String> cats = HybridRetrievalService.categoriesOf("category in ['a']");
        assertThat(cats).hasSize(1);
    }
}
