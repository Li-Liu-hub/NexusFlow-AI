#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把《终端智能管理系统-操作手册》切成知识库文档（M3）。

输入：工作区根目录的 终端智能管理系统-操作手册0528-有改动标红.docx
输出：aismp-agent/src/main/resources/faq/<category>/*.md

为什么必须切分：现有 OaFaqDocumentLoader 是「一篇文章 = 一个 Document」不切分，
而操作手册约 1 万字。整篇向量化有两个问题：
  ① 可能超出嵌入模型窗口（本机 llama.cpp 时代实测报过
     "input (539 tokens) is too large to process"）；
  ② 检索粒度太粗 —— 命中一段等于命中整本手册。
云端嵌入窗口虽大，但问题②依然存在，所以切分是必须的。

怎么切：**按 docx 的标题样式**，不按文本编号 ——
这本手册的正文标题没有编号（编号只存在于目录页），文本正则完全匹配不到。
实测样式映射（来自 word/styles.xml）：
    2  -> heading 1      3 -> heading 2      4 -> heading 3
    9/12/13 -> toc 1/2/3（目录，需跳过）
    1  -> Normal（正文）
    18 -> 标题-1（WPS 风格标题）
category 取业务域，供 RAG 的 filter 用（对齐现有 faq 的 category 约定）。

用法：python gen-handbook-md.py
"""
import os
import re
import sys
import zipfile
from xml.etree import ElementTree as ET

NS = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
WORKSPACE = os.path.dirname(PROJECT)
SRC = os.path.join(WORKSPACE, '终端智能管理系统-操作手册0528-有改动标红.docx')
OUT_ROOT = os.path.join(PROJECT, 'aismp-agent', 'src', 'main', 'resources', 'faq')

# docx 样式 → 标题层级
HEADING_STYLES = {'2': 1, '18': 1, '3': 2, '4': 3, '5': 4}
# 目录类样式：必须跳过，否则目录条目会被当成正文标题切出一堆空文档
TOC_STYLES = {'9', '12', '13', '19', '20', '21'}

# 一级标题（章） → category。RAG 检索时用 category 做 filter。
CATEGORY_OF = {
    '引言': 'intro',
    '系统流程': 'process',
    '系统角色职能': 'role',
    '系统功能': 'operation',
    '常见问题': 'trouble',
}


def read_paragraphs(path):
    """读出所有段落 (text, style_id)；style_id 来自 w:pPr/w:pStyle。"""
    z = zipfile.ZipFile(path)
    root = ET.fromstring(z.read('word/document.xml'))
    body = root.find(NS + 'body')
    out = []
    for el in body.iter():
        if el.tag != NS + 'p':
            continue
        text = ''.join(t.text or '' for t in el.iter(NS + 't')).strip()
        if not text:
            continue
        style = ''
        ppr = el.find(NS + 'pPr')
        if ppr is not None:
            ps = ppr.find(NS + 'pStyle')
            if ps is not None:
                style = ps.get(NS + 'val') or ''
        out.append((text, style))
    return out


def classify(text, style):
    """返回 (level, title) 或 None。

    优先看样式；样式缺失时退回到文本编号规则（这份手册的次级标题有时是
    手打的「（一）」「1.」，没有挂样式）。
    """
    if style in TOC_STYLES:
        return None
    level = HEADING_STYLES.get(style)
    if level:
        return level, text
    if re.match(r'^（[一二三四五六七八九十]+）', text) and len(text) < 40:
        return 2, text
    if re.match(r'^\d+[.、]\s*\S', text) and len(text) < 40:
        return 3, text
    return None


def safe_filename(name, idx):
    """文件名：去掉路径不友好字符，保留中文（可读性优先）"""
    cleaned = re.sub(r'[\\/:*?"<>|\s]+', '-', name).strip('-')
    cleaned = re.sub(r'-{2,}', '-', cleaned)
    return f'{idx:03d}-{cleaned[:60]}.md'


STOPWORDS = {'的', '了', '和', '与', '及', '或', '在', '是', '有', '为', '对', '进行',
             '可以', '需要', '通过', '系统', '终端', '管理', '功能'}


def extract_keywords(title, body, limit=8):
    """挑关键词：简单词频 + 去停用词。

    目的不是分词，而是给人工排查提供"词面命中"的信号；
    keywords 不参与向量化（loader 会 strip frontmatter）。
    """
    freq = {}
    for word in re.findall(r'[\u4e00-\u9fa5]{2,6}', title + ' ' + body):
        if word in STOPWORDS:
            continue
        freq[word] = freq.get(word, 0) + 1
    ranked = sorted(freq.items(), key=lambda kv: (-kv[1], -len(kv[0])))
    picked, seen = [], set()
    for word, _ in ranked:
        if any(word in s or s in word for s in seen):
            continue
        picked.append(word)
        seen.add(word)
        if len(picked) >= limit:
            break
    return picked


def main():
    if not os.path.isfile(SRC):
        print(f'!! 找不到 {SRC}', file=sys.stderr)
        return 1

    paras = read_paragraphs(SRC)
    print(f'读出 {len(paras)} 段')

    # 跳过目录页：从正文第一个标题（样式为 heading 且文本是"引言"）开始
    body_start = 0
    for i, (text, style) in enumerate(paras):
        if text == '引言' and style in HEADING_STYLES:
            body_start = i
            break
    print(f'正文从第 {body_start} 段开始（跳过目录与封面）')

    docs = []
    cur_cat, cur_level, cur_title = 'intro', 1, '引言'
    cur_lines: list[str] = []

    def flush():
        if cur_lines:
            docs.append((cur_cat, cur_level, cur_title, list(cur_lines)))
            cur_lines.clear()

    for text, style in paras[body_start:]:
        hit = classify(text, style)
        if hit:
            level, title = hit
            flush()
            cur_level, cur_title = level, title
            if level == 1:
                cur_cat = CATEGORY_OF.get(title, cur_cat)
            continue
        cur_lines.append(text)
    flush()

    # 丢掉只剩标题、没有实质内容的碎片（避免污染检索）
    docs = [d for d in docs if sum(len(x) for x in d[3]) >= 40]
    print(f'切出 {len(docs)} 篇有效文档')

    # 清掉旧知识库 md（OA 场景已决定删除）
    removed = 0
    if os.path.isdir(OUT_ROOT):
        for cat in os.listdir(OUT_ROOT):
            cat_dir = os.path.join(OUT_ROOT, cat)
            if not os.path.isdir(cat_dir):
                continue
            for fn in os.listdir(cat_dir):
                if fn.endswith('.md'):
                    os.remove(os.path.join(cat_dir, fn))
                    removed += 1
    print(f'清掉旧知识库 md {removed} 篇')

    written = {}
    for n, (cat, level, title, lines) in enumerate(docs, 1):
        cat_dir = os.path.join(OUT_ROOT, cat)
        os.makedirs(cat_dir, exist_ok=True)
        body = '\n'.join(lines)
        keywords = extract_keywords(title, body)
        content = (
            '---\n'
            f'category: {cat}\n'
            'type: knowledge\n'
            f'keywords: [{", ".join(keywords)}]\n'
            '---\n\n'
            f'# {title}\n\n'
            f'{body}\n'
        )
        path = os.path.join(cat_dir, safe_filename(title, n))
        with open(path, 'w', encoding='utf-8', newline='\n') as f:
            f.write(content)
        written.setdefault(cat, []).append(os.path.basename(path))

    print()
    total = 0
    for cat, files in sorted(written.items()):
        total += len(files)
        print(f'  [{cat}] {len(files)} 篇')
        for fn in files[:5]:
            print(f'      {fn}')
        if len(files) > 5:
            print(f'      … 另有 {len(files) - 5} 篇')
    print(f'\n合计 {total} 篇，输出到 {OUT_ROOT}')

    print('\n⚠️ Java 侧需要同步的 category 常量（漏改会导致检索 filter 失效）：')
    print('  ' + ', '.join(sorted(written.keys())))
    return 0


if __name__ == '__main__':
    sys.exit(main())
