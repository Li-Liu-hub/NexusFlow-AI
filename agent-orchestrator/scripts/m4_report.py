# M4 评估报告生成：逐题明细 + 判官双口径 + 题型归因 → docs/eval_report.md
# 用法: uv run python scripts/m4_report.py 1 2   # 对比第 1、2 遍（M5 后可加第 3 列）
# 读入: docs/m4_eval_pass{N}_answers.jsonl（全文）+ m4_eval_pass{N}_judged.jsonl（判官）
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "docs"


def load(pass_n: int, suffix: str) -> dict[int, dict]:
    src = OUT_DIR / f"m4_eval_pass{pass_n}_{suffix}.jsonl"
    rows = [json.loads(line) for line in src.read_text(encoding="utf-8").splitlines()]
    return {r["id"]: r for r in rows}


def main() -> int:
    passes = [int(x) for x in sys.argv[1:]] or [1, 2]
    answers = {n: load(n, "answers") for n in passes}
    judged = {n: load(n, "judged") for n in passes}
    ids = sorted(answers[passes[0]])

    lines = ["# M4 评估报告（自纠环前后对比）", "",
             "> 数据来源：20 题评估集（Java 侧 eval-set.json）真链路跑分（并发 5 路）。"
             "指标双轨：关键词命中（客观锚，纯字符串匹配）+ 判官合格率（LLM-as-judge，"
             "与图内 critic 同提示词同口径）。", ""]

    # ── 汇总：双口径对比表 ──
    lines += ["## 汇总对比", "",
              "> **指标口径说明**（看数字前先读这个）：",
              "> - **关键词命中率**：评估集每题预置「应命中的关键词」，在回答文本里做纯字符串匹配的命中比例——客观锚，零 LLM 参与，不会判官主观放水；",
              "> - **剔错命中率**：剔除运行错误题后重算的命中率。错误题（z.ai 超时等基础设施失败）回答为空、按 0 分计入会拉低表观值，剔错才反映真实能力；",
              "> - **全命中题数**：该题关键词全部命中的题目数；**零命中题数**：一个关键词都没命中的题目数；",
              "> - **判官不合格率**：LLM-as-judge 判「未有效回答查询」的题占比（与图内 critic 同一份提示词、同 temperature=0.2，口径全链路一致）；运行错误题按不合格计；",
              "> - **回炉**：critic 判不合格后带着失败原因定向重改写的题数（上限 2 轮）；**降级**：回炉 2 轮仍不合格、明示带病放行（meta.critic_degraded=true）的题数；",
              "> - **运行错误**：超时/连接失败等基础设施错误数，回答为空——属环境噪声，不是系统能力问题。", "",
              "| 指标 | " + " | ".join(f"第 {n} 遍" for n in passes) + " |",
              "|---|" + "---|" * len(passes)]
    for label, fn in [
        ("关键词命中率", lambda n: "{:.1%} ({}/{})".format(
            sum(r["keyword_hits"] for r in answers[n].values())
            / max(sum(r["keywords_total"] for r in answers[n].values()), 1),
            sum(r["keyword_hits"] for r in answers[n].values()),
            sum(r["keywords_total"] for r in answers[n].values()))),
        ("剔错命中率", lambda n: "{:.1%} ({}/{})".format(
            (lambda h, t: h / max(t, 1))(
                sum(r["keyword_hits"] for r in answers[n].values() if not r["error"]),
                sum(r["keywords_total"] for r in answers[n].values() if not r["error"])),
            sum(r["keyword_hits"] for r in answers[n].values() if not r["error"]),
            sum(r["keywords_total"] for r in answers[n].values() if not r["error"]))),
        ("全命中题数", lambda n: "{}/{}".format(
            sum(1 for r in answers[n].values() if r["keyword_hits"] == r["keywords_total"] and not r["error"]),
            len(answers[n]))),
        ("零命中题数", lambda n: str(sum(
            1 for r in answers[n].values() if r["keyword_hits"] == 0 and not r["error"]))),
        # 合并行（merged）判官结论未留存，从合格率分母剔除——不冒充判官口径
        ("判官合格率", lambda n: "{:.1%} ({}/{})".format(
            sum(1 for v in judged[n].values() if v["passed"]) / len(judged[n]),
            sum(1 for v in judged[n].values() if v["passed"]), len(judged[n]))),
        ("判官不合格率", lambda n: "{:.1%} ({}/{})".format(
            sum(1 for v in judged[n].values() if not v["passed"]) / len(judged[n]),
            sum(1 for v in judged[n].values() if not v["passed"]), len(judged[n]))),
        ("运行错误", lambda n: str(sum(1 for r in answers[n].values() if r["error"]))),
    ]:
        lines.append(f"| {label} | " + " | ".join(fn(n) for n in passes) + " |")
    lines.append("")

    # ── 逐题明细 ──
    lines += ["## 逐题明细", "",
              "| 题 | 类型 | 问题 | " + " | ".join(f"第{n}遍命中" for n in passes) + " | 回炉 | 判官(基) | 判官(自纠) | 状态 |",
              "|---|---|---|" + "---|" * (len(passes) + 4)]
    for i in ids:
        r1 = answers[passes[0]][i]
        r2 = answers[passes[-1]][i]
        v1 = judged[passes[0]].get(i)
        v2 = judged[passes[-1]].get(i)
        kw = " | ".join(f"{answers[n][i]['keyword_hits']}/{answers[n][i]['keywords_total']}"
                        + ("⚠️" if answers[n][i]["error"] else "") for n in passes)
        status = ("✅ 提升" if r2["keyword_hits"] > r1["keyword_hits"]
                  else "↔ 持平" if r2["keyword_hits"] == r1["keyword_hits"]
                  else "❌ 下降")
        lines.append(f"| {i} | {r1['type']} | {r1['question']} | {kw} | {r2['revision_count']} "
                     f"| {'合格' if v1 and v1['passed'] else '不合格' if v1 else '未留存'} "
                     f"| {'合格' if v2 and v2['passed'] else '不合格' if v2 else '未留存'} | {status} |")
    lines.append("")

    # ── 题型归因 ──
    lines += ["## 题型归因", "",
              "| 类型 | 题数 | " + " | ".join(f"第{n}遍平均命中" for n in passes) + " |",
              "|---|---|" + "---|" * len(passes)]
    types = sorted({r["type"] for r in answers[passes[0]].values()})
    for t in types:
        ids_t = [i for i in ids if answers[passes[0]][i]["type"] == t]
        avgs = ["{:.2f}".format(sum(answers[n][i]["keyword_hits"] / max(answers[n][i]["keywords_total"], 1)
                                    for i in ids_t) / len(ids_t)) for n in passes]
        lines.append(f"| {t} | {len(ids_t)} | " + " | ".join(avgs) + " |")
    lines.append("")

    # ── 提示词对比实例（首轮改写：轻量 vs 正式，同题实改对照）──
    # 前提：passes 里首遍=critic off 轻量提示词时代之外，取"倒数第二遍 vs 末遍"做提示词对照
    if len(passes) >= 2:
        base_p, new_p = passes[-2], passes[-1]
        # 有 rewritten_query 才有对照价值（错误题 rewrite 没跑成，跳过）
        demo_ids = [i for i in ids
                    if answers[base_p][i].get("rewritten_query")
                    and answers[new_p][i].get("rewritten_query")
                    and answers[base_p][i]["rewritten_query"] != answers[new_p][i]["rewritten_query"]]
        lines += ["## 提示词前后实例（同题实改对照，非编造）", "",
                  "**轻量提示词（第 2 遍用）**：`请把用户的查询改写成一句更清晰、更适合知识库检索的话，"
                  "保持原意，只输出改写结果，不要解释。`——只有一句泛泛要求，无领域术语引导、无示例。",
                  "",
                  "**正式提示词（第 3 遍用，搬自 Java 侧五轮迭代版）**：角色设定（OA 系统查询分析助手）"
                  "+ 术语引导（工单、审批、权限、报表、附件、电费）+ 3 个 few-shot 示例"
                  "（如 `密码忘了 → 忘记登录密码，需要重置密码`）。", ""]
        lines += ["| 题 | 原问题 | 轻量提示词改写 | 正式提示词改写 |",
                  "|---|---|---|---|"]
        for i in demo_ids:
            lines.append(f"| q{i} | {answers[base_p][i]['question']} "
                         f"| {answers[base_p][i]['rewritten_query']} "
                         f"| {answers[new_p][i]['rewritten_query']} |")
        lines.append("")

    # ── 剩余未命中的题：预期是什么、实际答了什么、差在哪 ──
    # 评估集原文（拿每题完整关键词列表，算"未命中关键词"）
    # 相对项目根定位（原为写死的 D:/Code/Java/... 绝对路径，换机器即崩）
    eval_set = Path(__file__).resolve().parent.parent.parent / \
        "aismp-agent/src/main/resources/eval/eval-set.json"
    kw_map = {it["id"]: it.get("keywords", []) for it in json.loads(eval_set.read_text(encoding="utf-8"))}
    lines += ["## 剩余未命中题归因（末遍：预期 vs 实际）", ""]
    for i in ids:
        r = answers[passes[-1]][i]
        v = judged[passes[-1]].get(i)
        if r["keyword_hits"] == r["keywords_total"] and not r["error"]:
            continue
        why = r["error"] or (v["reason"] if v else "") or \
            ("（合并行，首跑回答全文未留存——命中数来自控制台实证）"
             if r.get("merged") else "（关键词未全中，判官判合格——口径差异，见讨论）")
        missed = [k for k in kw_map.get(i, []) if k not in r["hit_words"].split()]
        lines.append(f"**q{i}「{r['question']}」（{r['type']}，命中 {r['keyword_hits']}/{r['keywords_total']}，"
                     f"回炉 {r['revision_count']}）**")
        lines.append(f"- 预期：命中知识库文档「{r['expected_doc']}」；未命中关键词：{missed or '（错误题未计）'}")
        if r["error"]:
            lines.append(f"- 实际：**运行错误**——{why}（回答为空，非能力问题）")
        else:
            excerpt = r.get("answer_excerpt") or r["answer"][:200]
            lines.append(f"- 实际回答摘录：{excerpt}")
            lines.append(f"- 判官意见：{why if v else '（未留存，不计入判官口径分母）'}")
        lines.append("")

    out = OUT_DIR / "eval_report.md"
    out.write_text("\n".join(lines), encoding="utf-8")
    print(f"[报告] {out}（{len(lines)} 行）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
