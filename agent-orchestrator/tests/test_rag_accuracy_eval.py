# rag_accuracy_eval 的评分口径单测（纯函数，不连任何服务）
#
# 评测脚本本身也是代码，它的判分口径如果不测，就会出现
#「指标看着漂亮，其实是算法放水」——这比系统本身出错更危险，
# 因为它会让报告里的数字失去可信度。这里把三种口径钉死。
import importlib.util
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

# scripts/ 下都是可执行脚本（无 __init__.py，不加包语义），用 importlib 直接按路径加载，
# 避免为了一次测试就给该目录塞一个 __init__.py。
_spec = importlib.util.spec_from_file_location(
    "rag_accuracy_eval",
    Path(__file__).resolve().parent.parent / "scripts" / "rag_accuracy_eval.py")
ev = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ev)


# ── 拒答判定 ──

def test_is_rejection_detects_fallback_phrases():
    assert ev.is_rejection("知识库暂时不可用（连接超时）")
    assert ev.is_rejection("知识库中没有相关内容")
    assert ev.is_rejection("该问题超出终端管理领域范围")


def test_is_rejection_false_on_normal_answer():
    assert not ev.is_rejection("调拨流程分为申请、出库、入库三个环节。")


# ── 要点覆盖率 ──

def test_point_coverage_full_hit():
    """要点里的实义词都在回答里 → 覆盖率 1.0。"""
    answer = "调拨流程：A仓账管员先发起申请，B仓确认出库，A仓仓管员确认入库。"
    points = ["A仓账管员发起申请", "B仓确认出库", "A仓仓管员确认入库"]
    assert ev.point_coverage(answer, points, []) == 1.0


def test_point_coverage_partial():
    answer = "调拨需要先发起申请。"
    points = ["A仓账管员发起申请", "B仓确认出库", "A仓确认入库", "状态流转"]
    cov = ev.point_coverage(answer, points, [])
    assert 0 < cov < 1, f"部分命中不应是 0 或 1，实际 {cov}"


def test_point_coverage_zero_for_unrelated():
    answer = "今天天气不错。"
    points = ["APPLIED 状态", "OUTBOUND 状态"]
    assert ev.point_coverage(answer, points, []) == 0.0


def test_point_coverage_empty_points_is_one():
    """超纲题没有要点 → 记 1.0（其正确性由 is_rejection 单独判定，不能在这里扣分）。"""
    assert ev.point_coverage("随便什么回答", [], []) == 1.0


def test_point_coverage_matches_english_terms():
    """英文术语/状态码必须能被识别（正则要覆盖 [A-Za-z]{2,}）。"""
    answer = "状态从 APPLIED 变为 OUTBOUND。"
    points = ["APPLIED 状态", "OUTBOUND 状态"]
    assert ev.point_coverage(answer, points, []) == 1.0


# ── 汇总口径 ──

def _row(**kw):
    base = {"id": 1, "type": "简单事实", "question": "q", "expected_doc": "d",
            "keyword_hits": 2, "keywords_total": 2, "point_coverage": 0.8,
            "is_oob": False, "rejection": None, "hallucination": None,
            "answer_len": 100, "duration_s": 10.0, "revision_count": 0,
            "channel": "kb", "error": "", "conversation_id": "c",
            "answer": "a", "answer_excerpt": "a"}
    base.update(kw)
    return base


def test_summarize_excludes_oob_from_accuracy():
    """★ 核心口径：超纲题绝不能进准确率分母（否则「拒答」会被算成「答错」）。"""
    rows = [
        _row(id=1, point_coverage=0.9),
        _row(id=2, point_coverage=0.3),
        _row(id=3, is_oob=True, rejection=True, keyword_hits=0, keywords_total=0),
        _row(id=4, is_oob=True, hallucination=True, rejection=False,
             keyword_hits=0, keywords_total=0),
    ]
    s = ev.summarize(rows, threshold=0.6, label="t")
    assert s["valid"] == 2, "有效题只应含非超纲题"
    assert s["strict_pass"] == 1, "仅 id=1 达到 0.6 阈值"
    assert s["strict_acc"] == 0.5
    assert s["oob_total"] == 2
    assert s["oob_rejected"] == 1
    assert s["oob_hallucinated"] == 1


def test_summarize_excludes_errors_from_denominator():
    """运行错误（环境噪声）必须剔除，否则会低估真实能力。"""
    rows = [
        _row(id=1, point_coverage=0.9),
        _row(id=2, error="Timeout: xxx", point_coverage=0.0, keyword_hits=0),
    ]
    s = ev.summarize(rows, threshold=0.6, label="t")
    assert s["valid"] == 1
    assert s["errors"] == 1
    assert s["strict_acc"] == 1.0, "错误题不能拉低准确率"


def test_summarize_recall_computation():
    rows = [_row(id=1, keyword_hits=1, keywords_total=4),
            _row(id=2, keyword_hits=3, keywords_total=4)]
    s = ev.summarize(rows, threshold=0.6, label="t")
    assert abs(s["recall"] - 4 / 8) < 1e-9


def test_summarize_empty_input_is_safe():
    """空输入不能除零崩溃。"""
    s = ev.summarize([], threshold=0.6, label="t")
    assert s["strict_acc"] == 0.0
    assert s["recall"] == 0.0
    assert s["valid"] == 0
