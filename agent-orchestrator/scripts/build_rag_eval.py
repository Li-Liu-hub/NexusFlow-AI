#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""构造 RAG 准确率评测测试集（领域 QA + DuReader 风格域外基线）。

## 为什么自建领域 QA 而不用现成通用数据集

本项目的知识库是**运营商固网终端管理**领域（库存调拨/领用/回收/盘点/报表），
公开中文检索集（DuReader-Retrieval 等）是**通用网页**领域（"太阳花怎么养"）。
拿通用集测本项目 RAG，命中率必然趋近 0 —— 不是系统差，是**问的东西知识库里没有**。
所以产出两份：

  · `aismp-qa-eval.json`  领域题 —— **真实准确率**（写进比赛报告的主数字）
  · `aismp-qa-oob.json`   域外题 —— **下界基线**（量化证明通用集不适合评本项目）

## ★ 出题必须对齐【真实灌库内容】

这一点踩过坑：第一次按 `local-init/kb-seed/*.txt`（31 篇业务语料）出题，
校验全过；但查 PG `vector_store` 发现**实际灌库的是 `faq/**/*.md`（16 篇手册切片）**，
两套语料根本不重合 —— 按 kb-seed 出的题会全部落空。

因此本脚本的 `CORPUS_DIRS` 只认**真实入向量库的目录**，
`--check` 会逐条验证关键词确实在目标文档正文里，防止"出题与实际语料脱节"。

## 出题原则（对齐 DuReader-Retrieval 的考察点）

论文点明中文检索难点是 salient phrase mismatch（显著短语不匹配）与
syntactic mismatch（句法不匹配）。照此设计 6 类题型：

  1. 简单事实 —— 直接问文档明写的事实（基线）
  2. 口语化   —— 用户不说术语，用大白话问（考改写是否有效）
  3. 多意图   —— 一句话两个诉求，需检索到两篇文档（考召回宽度）
  4. 专有名词 —— 含精确术语/状态码/字段名（考关键词锚定）
  5. 跨文档   —— 答案分散在多篇，需综合（考多跳）
  6. 超纲     —— 知识库里确实没有（考**拒答**，不计入准确率分母）

## 用法
    python scripts/build_rag_eval.py           # 生成两份 json
    python scripts/build_rag_eval.py --check   # 校验关键词确实在语料里
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROJ = ROOT.parent
OUT_DIR = ROOT / "docs" / "eval"

# ★ 只认真实灌进 pgvector 的目录（faq/**/*.md）。
#   当初把 local-init/kb-seed 也算进来导致出题落空，故此处收窄并有注释说明。
CORPUS_DIRS = [PROJ / "aismp-agent/src/main/resources/faq"]


# ---------------------------------------------------------------------------
# 领域 QA —— expected_doc 用 faq 下的文件名，keywords 全部来自该文档原文
# ---------------------------------------------------------------------------
DOMAIN_QA = [
    # ── 1. 简单事实 ──
    {
        "id": 1, "type": "简单事实",
        "question": "跨本地网调拨需要经过哪些环节",
        "expected_doc": "003-库存管理.md",
        "keywords": ["跨本地网调拨", "账管员创建调拨单", "省级终端主管", "出库", "入库"],
        "answer_points": ["账管员创建调拨单", "省级终端主管审批",
                          "出库方库管出库", "入库方库管入库", "仅支持单一物料类型调拨"],
    },
    {
        "id": 2, "type": "简单事实",
        "question": "终端盘点单是谁创建的，审核由谁完成",
        "expected_doc": "011-终端盘点.md",
        "keywords": ["省级终端主管", "创建盘点单", "二级账管", "盘点审核"],
        "answer_points": ["省级终端主管创建盘点单", "汇总到二级账管盘点审核处",
                          "二级账管提交后省级终端主管可查"],
    },
    {
        "id": 3, "type": "简单事实",
        "question": "终端进出库统计表的口径是什么",
        "expected_doc": "013-报表管理.md",
        "keywords": ["终端进出库统计表", "期初在库数量", "本期采购入库", "本期调配入库"],
        "answer_points": ["按仓位/终端大类/时间筛选", "时间默认查询本月",
                          "期初在库含待检、可用、待报废、备机、调拨在途等状态",
                          "本期调配入库统计跨本地网调拨"],
    },
    {
        "id": 4, "type": "简单事实",
        "question": "回收回来的终端怎么变成可以再用的",
        "expected_doc": "006-回收管理.md",
        "keywords": ["回收统计", "确认收货", "待检", "打标"],
        "answer_points": ["四级库管在回收统计确认收货", "状态由待回收转待检",
                          "按质检结果打标为可用或故障返厂"],
    },
    {
        "id": 5, "type": "简单事实",
        "question": "智家工程师领用终端后什么条件下算超长备机",
        "expected_doc": "004-领用管理（四级库管权限）.md",
        "keywords": ["备机", "30日", "超长备机"],
        "answer_points": ["领用后终端状态变备机并与领用人绑定", "备机领用超30日未使用为超长备机"],
    },

    # ── 2. 口语化（用户不说术语）──
    {
        "id": 6, "type": "口语化",
        "question": "东西从一个仓库挪到另一个仓库怎么弄",
        "expected_doc": "003-库存管理.md",
        "keywords": ["调拨", "账管员创建调拨单"],
        "answer_points": ["识别为调拨诉求", "走建调拨单→出库→入库确认"],
    },
    {
        "id": 7, "type": "口语化",
        "question": "我把串码录错了怎么办",
        "expected_doc": "008-终端信息维护（二级账管权限）.md",
        "keywords": ["串码", "修改", "12位"],
        "answer_points": ["走终端改串码", "旧串码不限位数、新串码需12位标准串码"],
    },
    {
        "id": 8, "type": "口语化",
        "question": "营业厅拆机了但当时没录终端会怎样",
        "expected_doc": "006-回收管理.md",
        "keywords": ["24:00", "回收录入", "拆机"],
        "answer_points": ["需在办理 CB 拆机业务当天 24:00 前完成回收录入",
                          "否则系统无法统计到回收终端"],
    },
    {
        "id": 9, "type": "口语化",
        "question": "我登录进去啥都看不见是咋回事",
        "expected_doc": "014-登录问题.md",
        "keywords": ["权限", "空白", "二级账管", "角色"],
        "answer_points": ["登录后权限空白需找二级账管修改角色对应标签（营业员/智家）"],
    },

    # ── 3. 多意图（一句话两个诉求）──
    {
        "id": 10, "type": "多意图",
        "question": "设备已经入库了怎么转到别的库，导入的时候要注意什么",
        "expected_docs": ["015-功能问题.md", "003-库存管理.md"],
        "keywords": ["建调拨单", "批量调拨", "菜单"],
        "answer_points": ["通过建调拨单形式调拨", "用批量调拨则导入与确认入库菜单需一致"],
    },
    {
        "id": 11, "type": "多意图",
        "question": "盘点差异怎么看，什么时候能看",
        "expected_docs": ["011-终端盘点.md"],
        "keywords": ["盘点差异", "四级账管", "差异原因"],
        "answer_points": ["四级账管查看并填写差异原因", "盘点差异需盘点完成后第二天查看"],
    },
    {
        "id": 12, "type": "多意图",
        "question": "智家拒绝领用的终端怎么处理，处理完能重新分配吗",
        "expected_docs": ["004-领用管理（四级库管权限）.md"],
        "keywords": ["拒绝领用", "领用退回待处理", "可用", "重新分配"],
        "answer_points": ["智家拒绝后终端退回四级库", "库管在领用退回待处理确认",
                          "确认后状态变为可用方可重新分配"],
    },

    # ── 4. 专有名词（精确术语/状态码）──
    {
        "id": 13, "type": "专有名词",
        "question": "待智家领用确认这个状态是什么意思",
        "expected_doc": "004-领用管理（四级库管权限）.md",
        "keywords": ["待智家领用确认", "无法使用"],
        "answer_points": ["库管下发备机但智家未领用时为该状态", "此时智家无法使用"],
    },
    {
        "id": 14, "type": "专有名词",
        "question": "回收时无法识别串码会怎么生成串码",
        "expected_doc": "006-回收管理.md",
        "keywords": ["无法识别", "2位地市", "6位年月", "4位串码", "12位"],
        "answer_points": ["按 2位地市+6位年月+4位串码 格式自动生成 12 位标准串码"],
    },
    {
        "id": 15, "type": "专有名词",
        "question": "终端库龄是怎么计算的",
        "expected_doc": "013-报表管理.md",
        "keywords": ["库龄时长", "首次入库时间", "翻新利旧"],
        "answer_points": ["新终端按首次入库时间起算", "利旧终端按最后一次翻新利旧入库开始计算"],
    },
    {
        "id": 16, "type": "专有名词",
        "question": "0元入库单有哪些类型",
        "expected_doc": "003-库存管理.md",
        "keywords": ["0元新机入库", "旧机入库", "代维入库"],
        "answer_points": ["0元新机入库/旧机入库/代维入库三类"],
    },

    # ── 5. 跨文档（需综合多篇）──
    {
        "id": 17, "type": "跨文档",
        "question": "一台终端从到货到装到用户家里要经过哪些状态",
        "expected_docs": ["003-库存管理.md", "004-领用管理（四级库管权限）.md", "005-装机出库.md"],
        "keywords": ["备机", "占用", "装机"],
        "answer_points": ["采购入库→调拨→领用变备机→装机报竣转占用"],
    },
    {
        "id": 18, "type": "跨文档",
        "question": "哪些操作只有四级库管有权限",
        "expected_docs": ["006-回收管理.md", "004-领用管理（四级库管权限）.md"],
        "keywords": ["四级库管", "回收管理"],
        "answer_points": ["回收管理只有四级库管有操作权限", "领用下发/释放也是四级库管"],
    },

    # ── 6. 超纲（知识库确实没有，考拒答，不计入准确率）──
    {
        "id": 19, "type": "超纲",
        "question": "今天天气怎么样",
        "expected_doc": None, "keywords": [],
        "answer_points": ["应拒答或走 direct 通道，不得编造"],
    },
    {
        "id": 20, "type": "超纲",
        "question": "帮我写个 Python 爬虫",
        "expected_doc": None, "keywords": [],
        "answer_points": ["明确超出固网终端管理领域"],
    },
    {
        "id": 21, "type": "超纲",
        "question": "运营商今年的财报怎么样",
        "expected_doc": None, "keywords": [],
        "answer_points": ["知识库无财报数据，应拒答"],
    },
    {
        "id": 22, "type": "超纲",
        "question": "怎么直接改数据库里的库存数字",
        "expected_doc": None, "keywords": [],
        "answer_points": ["属越权操作，应拒绝并说明红线"],
    },
]


# ---------------------------------------------------------------------------
# 域外基线：问题风格照搬 DuReader-Retrieval（通用领域真实搜索 query）
# 预期全部不命中 —— 这正是它存在的意义：给出「通用集评本项目」的下界
# ---------------------------------------------------------------------------
OOB_QA = [
    {"id": 1001, "type": "域外-生活常识", "question": "太阳花怎么养",
     "expected_doc": None, "keywords": ["太阳花", "播种"]},
    {"id": 1002, "type": "域外-生活常识", "question": "感冒了吃什么好得快",
     "expected_doc": None, "keywords": ["感冒"]},
    {"id": 1003, "type": "域外-历史人物", "question": "李白是哪个朝代的诗人",
     "expected_doc": None, "keywords": ["李白", "唐朝"]},
    {"id": 1004, "type": "域外-地理", "question": "长江有多长",
     "expected_doc": None, "keywords": ["长江", "公里"]},
    {"id": 1005, "type": "域外-科技", "question": "5G 和 4G 有什么区别",
     "expected_doc": None, "keywords": ["5G", "4G"]},
    {"id": 1006, "type": "域外-烹饪", "question": "红烧肉怎么做才好吃",
     "expected_doc": None, "keywords": ["红烧肉"]},
    {"id": 1007, "type": "域外-教育", "question": "考研数学怎么复习",
     "expected_doc": None, "keywords": ["考研", "数学"]},
    {"id": 1008, "type": "域外-医疗", "question": "高血压要注意什么",
     "expected_doc": None, "keywords": ["高血压"]},
    {"id": 1009, "type": "域外-旅游", "question": "三亚旅游攻略",
     "expected_doc": None, "keywords": ["三亚", "攻略"]},
    {"id": 1010, "type": "域外-金融", "question": "基金定投是什么意思",
     "expected_doc": None, "keywords": ["基金定投"]},
    {"id": 1011, "type": "域外-法律", "question": "劳动法规定试用期最长多久",
     "expected_doc": None, "keywords": ["试用期", "劳动法"]},
    {"id": 1012, "type": "域外-影视", "question": "最近有什么好看的电影",
     "expected_doc": None, "keywords": ["电影"]},
    {"id": 1013, "type": "域外-体育", "question": "世界杯几年举办一次",
     "expected_doc": None, "keywords": ["世界杯"]},
    {"id": 1014, "type": "域外-生活技巧", "question": "白衣服发黄怎么洗白",
     "expected_doc": None, "keywords": ["白衣服", "发黄"]},
    {"id": 1015, "type": "域外-汽车", "question": "新能源车充电要多久",
     "expected_doc": None, "keywords": ["新能源", "充电"]},
    {"id": 1016, "type": "域外-宠物", "question": "小猫多大可以打疫苗",
     "expected_doc": None, "keywords": ["小猫", "疫苗"]},
    {"id": 1017, "type": "域外-职场", "question": "面试自我介绍怎么说",
     "expected_doc": None, "keywords": ["面试", "自我介绍"]},
    {"id": 1018, "type": "域外-健康", "question": "每天喝多少水合适",
     "expected_doc": None, "keywords": ["喝水"]},
    {"id": 1019, "type": "域外-家居", "question": "怎么去除冰箱异味",
     "expected_doc": None, "keywords": ["冰箱", "异味"]},
    {"id": 1020, "type": "域外-数码", "question": "手机电池怎么保养",
     "expected_doc": None, "keywords": ["手机电池"]},
]


def load_corpus() -> dict[str, str]:
    """加载真实灌库语料：{文件名: 正文}。"""
    corpus: dict[str, str] = {}
    for d in CORPUS_DIRS:
        for p in d.rglob("*.md"):
            corpus[p.name] = p.read_text(encoding="utf-8")
    return corpus


def check_keywords_in_corpus() -> int:
    """校验每道领域题的 keywords 确实出现在目标文档里。"""
    corpus = load_corpus()
    print(f"[语料] 载入 {len(corpus)} 篇文档（来源：{CORPUS_DIRS[0].name}/）")
    bad = 0
    for q in DOMAIN_QA:
        docs = q.get("expected_docs") or ([q["expected_doc"]] if q.get("expected_doc") else [])
        if not docs:
            continue  # 超纲题不校验
        missing_doc = [d for d in docs if d not in corpus]
        if missing_doc:
            print(f"  [缺文档] q{q['id']} 目标文档不在语料: {missing_doc}")
            bad += 1
            continue
        text = "".join(corpus[d] for d in docs)
        missing = [k for k in q.get("keywords", []) if k not in text]
        if missing:
            print(f"  [关键词不在语料] q{q['id']}「{q['question']}」缺: {missing}")
            bad += 1
    print(f"\n领域题 {len(DOMAIN_QA)} 道，校验失败 {bad} 道")
    return bad


def main() -> int:
    if "--check" in sys.argv:
        return 1 if check_keywords_in_corpus() else 0

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    domain_out = OUT_DIR / "aismp-qa-eval.json"
    oob_out = OUT_DIR / "aismp-qa-oob.json"

    domain_out.write_text(json.dumps(DOMAIN_QA, ensure_ascii=False, indent=2),
                          encoding="utf-8")
    oob_out.write_text(json.dumps(OOB_QA, ensure_ascii=False, indent=2),
                       encoding="utf-8")

    by_type: dict[str, int] = {}
    for q in DOMAIN_QA:
        by_type[q["type"]] = by_type.get(q["type"], 0) + 1
    print(f"[领域 QA]  {domain_out}  ({len(DOMAIN_QA)} 题)")
    for t, n in sorted(by_type.items(), key=lambda kv: -kv[1]):
        print(f"    {t:<8} {n}")
    print(f"[域外基线] {oob_out}  ({len(OOB_QA)} 题)")
    print("\n提示：加 --check 可校验领域题关键词确实落在语料中。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
