# M4 数据合并：第 3 遍首跑（z.ai 故障窗口）的有效题数据 并入 现存 pass3 数据集
# 背景：首跑 JSONL 被重跑覆盖，但控制台日志留存于任务输出文件（命中数/回炉/降级/耗时），
#       改写文本在覆盖前已抓取（会话记录）。合并行标记 merged=true，回答全文未留存。
# 原则：只合并有控制台实证的字段，绝不编造判官结论与回答内容。
# 用法: uv run python scripts/m4_merge.py
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "docs"
# 相对项目根定位（原为写死的 D:/Code/Java/... 绝对路径，换机器即崩；
# 同类问题 m4_eval.py 已修过一次，这里是漏网的第三、四处）
EVAL_SET = ROOT.parent / "aismp-agent/src/main/resources/eval/eval-set.json"
# 首跑控制台日志（任务输出文件，GBK 编码）——本机历史运行痕迹，缺失时脚本会给出明确提示
RUN1_LOG = Path(
    r"C:\Users\rrrrrw\AppData\Local\Temp\claude\D--Code-Java-JavaCode-jl-bpmn-back"
    r"\c1dc5a3e-4e65-41c6-a24a-da9ef4f6b849\tasks\bulx7973t.output")

# 要合并的题：现数据集里是超时错误、但首跑有有效结果
MERGE_IDS = [2, 4, 6, 14, 19]
# 首跑改写文本（覆盖前从首跑 JSONL 抓取；控制台不打印改写，此处为唯一留存来源）
RUN1_REWRITES = {
    2: "阶梯电价如何计算，电费报表中的阶梯计费规则是什么",
    4: "电费稽核业务办理流程说明：包括稽核工单发起、审核、审批、归档各环节节点及具体",
    6: "报表无法导出，如何解决报表导出失败的问题",
    14: "如何在 Flowable 工作流流程中配置审批人",
    19: "通过工单申请修改系统数据库数据的完整流程：工单类型如何选择、需填写哪些具体数",
}

_LINE_RE = re.compile(
    r"\[\s*(\d+)\]\s+([\d.]+)s 命中(\d+)/(\d+) 回炉(\d+)( 降级)?( \[错误\])? (.+)")


def main() -> int:
    items = {it["id"]: it for it in json.loads(EVAL_SET.read_text(encoding="utf-8"))}
    # 解析首跑控制台
    log = RUN1_LOG.read_bytes().decode("gbk", errors="replace")
    run1 = {}
    for m in _LINE_RE.finditer(log):
        i = int(m.group(1))
        run1[i] = {"duration_s": float(m.group(2)), "keyword_hits": int(m.group(3)),
                   "keywords_total": int(m.group(4)), "revision_count": int(m.group(5)),
                   "degraded": bool(m.group(6)), "error": bool(m.group(7)),
                   "question_in_log": m.group(8).strip()}
    missing = [i for i in MERGE_IDS if i not in run1 or run1[i]["error"]]
    if missing:
        print(f"[中止] 首跑控制台里这些题无有效结果: {missing}"); return 1

    src = OUT_DIR / "m4_eval_pass3_answers.jsonl"
    rows = {r["id"]: r for r in [json.loads(l) for l in src.read_text(encoding="utf-8").splitlines()]}
    for i in MERGE_IDS:
        it, r1 = items[i], run1[i]
        kws = it.get("keywords", [])
        rows[i] = {
            "answer": "",  # 首跑全文未留存——宁可有痕不编造
            "id": i, "type": it.get("type", ""), "question": it["question"],
            "expected_doc": it.get("expected_doc", ""),
            "keywords_total": len(kws),
            "keyword_hits": r1["keyword_hits"],
            # 全命中的题 hit_words 可由评估集无损推出；部分命中（q6）无法确证是哪个词，留空并注明
            "hit_words": " ".join(kws) if r1["keyword_hits"] == len(kws) else "",
            "answer_len": 0, "duration_s": r1["duration_s"],
            "revision_count": r1["revision_count"],
            "critic_degraded": True if r1["degraded"] else "",
            "critic_reason": "（未留存）" if r1["degraded"] else "",
            "rewritten_query": RUN1_REWRITES[i],
            "answer_excerpt": "（合并自首跑，回答全文未留存）",
            "error": "", "conversation_id": "", "merged": True,
        }
        print(f"  合并 q{i}: 命中{r1['keyword_hits']}/{r1['keywords_total']} 回炉{r1['revision_count']}"
              f"{' 降级' if r1['degraded'] else ''} {r1['duration_s']}s")

    with src.open("w", encoding="utf-8") as f:
        for i in sorted(rows):
            f.write(json.dumps(rows[i], ensure_ascii=False) + "\n")

    # judged 侧：合并行的首跑判官结论未留存——从 judged 里移除（报告显示"未留存"、
    # 不计入不合格率分母），绝不拿关键词命中冒充判官结论
    jsrc = OUT_DIR / "m4_eval_pass3_judged.jsonl"
    jrows = [json.loads(l) for l in jsrc.read_text(encoding="utf-8").splitlines()]
    jrows = [v for v in jrows if v["id"] not in MERGE_IDS]
    with jsrc.open("w", encoding="utf-8") as f:
        for v in sorted(jrows, key=lambda x: x["id"]):
            f.write(json.dumps(v, ensure_ascii=False) + "\n")
    print(f"[完成] pass3 合并 {len(MERGE_IDS)} 题（q3/q9 两跑皆超时仍缺），judged 剔除同 id")
    return 0


if __name__ == "__main__":
    exit(main())
