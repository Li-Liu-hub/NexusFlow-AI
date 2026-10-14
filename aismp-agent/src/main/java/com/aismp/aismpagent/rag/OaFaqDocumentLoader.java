package com.aismp.aismpagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OA FAQ 知识库文档加载器
 * 遍历 faq/ 下 5 个分类子目录，每篇 .md 文件整体作为一个 Document
 * category 从子目录名提取，注入 Document 的 metadata
 */
@Component
@Slf4j
public class OaFaqDocumentLoader {

    // Spring 的资源解析器，用于按通配符匹配 classpath 下的文件
    private final ResourcePatternResolver resourcePatternResolver;

    // 构造函数注入：Spring 启动时自动注入 ResourcePatternResolver
    OaFaqDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    /**
     * 加载 faq/ 下所有 Markdown 文档
     * 每篇 FAQ 整体作为一个 Document（不按标题拆分），保留完整语义
     * category 来自子目录名，filename 来自文件名
     */
    public List<Document> loadFaqDocuments() {
        // 收集所有加载到的 Document
        List<Document> allDocuments = new ArrayList<>();
        try {
            // 匹配 faq/ 下所有子目录中的 .md 文件（** 表示跨目录递归匹配）
            Resource[] resources = resourcePatternResolver.getResources("classpath:faq/**/*.md");
            for (Resource resource : resources) {
                // 从文件路径提取分类名（如 faq/workflow/xxx.md → workflow）
                String category = extractCategory(resource);
                // 获取文件名（如 01-密码忘了怎么重置.md）
                String fileName = resource.getFilename();

                // 读取整个文件内容，不做切分，UTF-8 编码
                String content = resource.getContentAsString(StandardCharsets.UTF_8);

                // 去掉 YAML frontmatter（--- 包裹的元数据块），对检索无价值
                content = stripFrontmatter(content);

                // 创建 Document 对象，把 category 和 filename 作为元数据注入
                Document doc = new Document(content, Map.of(
                        "category", category,
                        "filename", fileName
                ));
                allDocuments.add(doc);
                log.info("加载 FAQ 文档: {}/{}", category, fileName);
            }
            log.info("FAQ 文档加载完成，共 {} 个 Document", allDocuments.size());
        } catch (IOException e) {
            log.error("FAQ 文档加载失败", e);
        }
        return allDocuments;
    }

    /**
     * 去掉文件开头的 YAML frontmatter
     * 格式：---\ncategory: xxx\n---\n 后面才是正文
     */
    private String stripFrontmatter(String content) {
        // 如果文件以 --- 开头，说明有 frontmatter
        if (content.startsWith("---")) {
            // 找第二个 ---（frontmatter 的结束标记），从第 4 个字符开始搜索
            int endIndex = content.indexOf("---", 3);
            if (endIndex != -1) {
                // 截取结束标记之后的内容，去掉前后空白
                return content.substring(endIndex + 3).trim();
            }
        }
        // 没有 frontmatter，原样返回
        return content;
    }

    /**
     * 从资源路径中提取分类名
     * 路径格式: .../faq/workflow/01-xxx.md → workflow
     */
    private String extractCategory(Resource resource) throws IOException {
        // 获取资源的完整 URL 路径
        URL url = resource.getURL();
        String path = url.getPath();
        // 找到 /faq/ 的位置
        int faqIndex = path.indexOf("/faq/");
        if (faqIndex == -1) {
            return "unknown";
        }
        // 截取 /faq/ 后面的部分（如 workflow/01-xxx.md）
        String afterFaq = path.substring(faqIndex + 5);
        // 找到下一个 /，取它前面的部分就是分类名
        int slashIndex = afterFaq.indexOf("/");
        if (slashIndex == -1) {
            return "unknown";
        }
        // 返回分类名（如 workflow）
        return afterFaq.substring(0, slashIndex);
    }
}
