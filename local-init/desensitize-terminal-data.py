#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
终端业务数据脱敏脚本
====================================================================
背景：
  local_data/terminal-data.sql.gz 是【真实业务数据】的快照（运营商固网终端库存），
  含真实地名（吉林省长春市 / 长春市九台区分公司）、真实设备串码、真实操作人工号、
  以及暴露组织结构的采购单号前缀。用于比赛演示前必须脱敏。

输入：local_data/terminal-data.sql.gz
输出：local_data/terminal-data-desensitized.sql   （不覆盖原件，只生成新文件）

脱敏原则（重要，别改成"真乱改"）：
  1. 只换值，不动 SQL 结构 —— 语句数、列清单、行数必须完全一致
  2. 保持业务约束：
     - serialNo 保持【唯一】（它是业务主键）
     - 枚举值（terminalStatus / inboundType / businessType / purchaseType）原样保留
     - 数值（unitPrice / agingDays）与日期原样保留 —— 否则补货算法的演示结果会变
  3. 引用一致：warehouseCode 与各仓位列（lv1~lv4Warehouse / ownerWarehouse）
     必须同步替换，否则 warehouse 表和 terminal 表对不上
  4. 映射是确定性的：同一原值 → 同一伪值（用映射表，不用随机数）
  5. 覆盖范围（2026-10-06 起）：地名、仓库编码、串码、采购单号前缀、**操作人姓名与工号**
     —— 上一版只换了 cc_ 前缀工号，漏掉了 5 个真实姓名/拼音工号（李树勋/lisx9 等），本版已补

用法：
  python local-init/desensitize-terminal-data.py
"""
import gzip
import hashlib
import re
import sys
from pathlib import Path

# ---------------------------------------------------------------- 配置
BASE = Path(__file__).resolve().parent.parent          # agent-platform/
SRC = BASE / "local_data" / "terminal-data.sql.gz"
DST = BASE / "local_data" / "terminal-data-desensitized.sql"

# 虚构地名（中性、不指向任何真实单位，但保持"省-市-区县"三级结构以支撑业务语义）
FAKE_PROVINCE = "东宁"
FAKE_CITY = "东宁市"
FAKE_DISTRICT = "东宁市新区分公司"

# 长串必须排在短串前面，否则替换会互相破坏
TEXT_REPLACEMENTS = [
    # ---- 地名（长 → 短）----
    # 实测数据里含「公司」的中文串只有三种（用脚本全量扫过）：
    #   吉林省长春市分公司 51817 / 长春市九台区分公司 9790 / 长春市农安县分公司 1
    # 前两种对照着写规则容易想到，第三种（农安县，也是长春下辖县）极易漏，
    # 所以短地名兜底（长春/九台/农安）必须都列上。
    ("吉林省长春市分公司", f"{FAKE_CITY}分公司"),
    ("长春市九台区分公司", FAKE_DISTRICT),
    ("长春市农安县分公司", f"{FAKE_CITY}安宁县分公司"),
    ("吉林省", FAKE_PROVINCE),
    ("长春", FAKE_PROVINCE),
    ("九台", "新区"),
    ("农安县", "安宁县"),
    ("农安", "安宁"),
    # ---- 仓库编码（7A00 / 7AZA → 保持格式特征）----
    ("'7A00'", "'9B00'"),
    ("'7AZA'", "'9BZA'"),
    # ---- 操作人 / 工号 ----
    # 实测有两套：`长春账管员`/`cc_zg`（50 处）、`长春市管理员`/`cc_kg`（2 处）。
    # `长春市管理员` 由上面的「长春」规则处理成「东宁市管理员」；
    # 工号则按 `'cc_` 前缀统一替换，避免只写死 zg 漏掉其他岗位（kg 就是这样漏的）。
    ("长春账管员", f"{FAKE_PROVINCE}账管员"),
    ("'cc_", "'dn_"),
    # ---- 采购单号前缀（暴露省份/组织缩写，长 → 短）----
    # 实测数据里出现过的形态（按频次）：
    #   PO-jl-          26357   ← 主形态，jl=吉林
    #   PO-jtgksc-        613
    #   PO-jljzcg-        225
    #   PO-cost-           70   ← 非地名（"成本"业务术语），保留不动
    #   PO-jlszysc-         8
    #   PO-jlgksc-          6
    #   PO-gz-gzgksc-       1   ← gz=广州/贵州，另一个省的组织
    # 坑：`PO-jlszysc-` 不含 `PO-jl-` 子串（jl 后直接跟 s，无连字符），
    #     只靠 `PO-jl-` 兜底会残留 `jl`。所以既要列长前缀，也要加 token 级兜底。
    ("PO-jl-jlszysc-", "PO-dn-dnszysc-"),
    ("PO-jl-jlgksc-", "PO-dn-dngksc-"),
    ("PO-jl-jljzcg-", "PO-dn-dnjzcg-"),
    ("PO-jl-jtgksc-", "PO-dn-dngksc-"),
    ("PO-jlszysc-", "PO-dnszysc-"),
    ("PO-jlgksc-", "PO-dngksc-"),
    ("PO-jljzcg-", "PO-dnjzcg-"),
    ("PO-jtgksc-", "PO-dngksc-"),
    ("PO-jl-", "PO-dn-"),
    ("PO-gz-gzgksc-", "PO-dn-dngksc-"),
    ("PO-gz-", "PO-dn-"),
    # token 级兜底：处理 `PO-jl-<token>-` 形态被 `PO-jl-` 规则部分替换后残留的部分
    ("jlszysc", "dnszysc"),
    ("jlgksc", "dngksc"),
    ("jljzcg", "dnjzcg"),
    ("gzgksc", "dngksc"),
    # `jt` = 九台（长春下辖），藏在 `jtzysc` 里 —— token 级校验只查 jl/gz 会漏掉它
    ("jtzysc", "dnszysc"),
    # ---- 操作人真实姓名/工号 → 虚构操作员（2026-10-06 补漏）----
    # 实测全量（按频次）：李树勋/lisx9(6621)、朱健/zhujian2(3767)、张慧敏/zhanghm21(1510)、
    #                    张雪晴/NEWD0001(3)、王洪宇/wanghy69(1)
    # 工号用「带引号」形态锚定 —— 小写 token 也可能出现在别的字段，避免误伤
    ("李树勋", "东宁操作员A"), ("'lisx9'", "'dnop01'"),
    ("朱健", "东宁操作员B"), ("'zhujian2'", "'dnop02'"),
    ("张慧敏", "东宁操作员C"), ("'zhanghm21'", "'dnop03'"),
    ("张雪晴", "东宁操作员D"), ("'NEWD0001'", "'dnop04'"),
    ("王洪宇", "东宁操作员E"), ("'wanghy69'", "'dnop05'"),
]

# serialNo 出现在每条 INSERT 元组的第一个位置：('串码',...
SERIAL_PATTERN = re.compile(r"\((\')([^\']{6,32})\1\s*,\s*(\'|NULL)")


def fake_serial(orig: str) -> str:
    """按原串码生成等长伪串码（md5 派生 → 确定、唯一、不可逆）。

    保留原长度与大小写风格，使数据看起来仍像真实设备串码；
    不做顺序编号（顺序号会让"串码递增"这类规律露馅）。
    """
    h = hashlib.md5(("salt::" + orig).encode("utf-8")).hexdigest().upper()
    while len(h) < len(orig):
        h += hashlib.md5(h.encode()).hexdigest().upper()
    return h[: len(orig)]


def main() -> int:
    if not SRC.exists():
        print(f"ERROR: 找不到源文件 {SRC}", file=sys.stderr)
        return 1

    print(f"读取真实数据：{SRC.name}")
    raw = gzip.decompress(SRC.read_bytes()).decode("utf-8")
    orig_lines = raw.count("\n")
    orig_ins_terminal = raw.count("INSERT INTO terminal")
    print(f"  原始：{orig_lines} 行 / terminal INSERT 语句 {orig_ins_terminal} 条")

    # ---- 1) 串码替换（先做，用映射表保证一致与唯一）----
    serial_map: dict[str, str] = {}

    def _sub_serial(m: re.Match) -> str:
        serial = m.group(2)
        # 只处理看起来像串码的（排除 SQL 里的普通短字符串）
        if serial in serial_map:
            return f"('{serial_map[serial]}'," if m.group(3) == "'" else f"('{serial_map[serial]}',"
        faked = fake_serial(serial)
        serial_map[serial] = faked
        return m.group(0).replace(serial, faked)

    text = SERIAL_PATTERN.sub(_sub_serial, raw)
    print(f"  串码映射：{len(serial_map)} 条（唯一性校验中…）")
    if len(set(serial_map.values())) != len(serial_map):
        print("ERROR: 伪串码出现碰撞，请更换 salt！", file=sys.stderr)
        return 2
    print(f"  ✓ 伪串码 {len(serial_map)} 个全部唯一")

    # ---- 2) 文本替换（地名 / 编码 / 操作人 / 单号）----
    for old, new in TEXT_REPLACEMENTS:
        n = text.count(old)
        if n:
            text = text.replace(old, new)
            print(f"  {old!r} → {new!r}  ({n} 次)")

    # ---- 3) 一致性校验：不应再有真实地名残留 ----
    # 注意：仓库编码必须用【带引号】形式校验 —— md5 派生的伪串码是 hex 字符集
    #（0-9A-F），其中随机出现 "7A00" 这类子串是正常现象，直接 text.count("7A00")
    # 会误报。而 hex 里不可能出现单引号，所以 " '7A00' " 一定是真实残留。
    checks = [
        ("吉林省", text.count("吉林省")),
        ("长春", text.count("长春")),
        ("九台", text.count("九台")),
        ("农安", text.count("农安")),
        ("'7A00'", text.count("'7A00'")),
        ("'7AZA'", text.count("'7AZA'")),
        ("长春账管员", text.count("长春账管员")),
        ("'cc_zg'", text.count("'cc_zg'")),
        ("PO-jl", text.count("PO-jl")),
        ("PO-gz", text.count("PO-gz")),
        # token 级：任何仍含省份/城市拼音缩写的字母串
        # jl=吉林 / cc=长春 / jt=九台 / gz=广州·贵州
        # 伪串码是大写 hex（[0-9A-F]），不含小写 j/l/t/z，因此这些检查不会误报
        ("含 jl 的 token", len(re.findall(r"[A-Za-z]*jl[A-Za-z]*", text))),
        ("含 gz 的 token", len(re.findall(r"[A-Za-z]*gz[A-Za-z]*", text))),
        ("含 jt 的 token", len(re.findall(r"[A-Za-z]*jt[A-Za-z]*", text))),
        ("含 cc 的 token", len(re.findall(r"[A-Za-z]*cc[A-Za-z]*", text))),
        # 操作人真实姓名/工号残留（2026-10-06 补漏）
        ("李树勋", text.count("李树勋")),
        ("朱健", text.count("朱健")),
        ("张慧敏", text.count("张慧敏")),
        ("张雪晴", text.count("张雪晴")),
        ("王洪宇", text.count("王洪宇")),
        ("'lisx9'", text.count("'lisx9'")),
        ("'zhujian2'", text.count("'zhujian2'")),
        ("'zhanghm21'", text.count("'zhanghm21'")),
        ("'NEWD0001'", text.count("'NEWD0001'")),
        ("'wanghy69'", text.count("'wanghy69'")),
    ]
    leaks = [f"{kw}={c}" for kw, c in checks if c]
    if leaks:
        print(f"ERROR: 仍有真实值残留：{', '.join(leaks)}", file=sys.stderr)
        return 3
    print("  ✓ 真实地名/编码/工号/单号前缀已无残留")

    # 强校验：所有「xx公司」形态的中文机构名都必须是虚构值。
    # 这条最管用 —— 它不依赖我事先想到有哪些地名（农安县 就是这样被发现的）。
    known_fake = {f"{FAKE_CITY}分公司", FAKE_DISTRICT, f"{FAKE_CITY}安宁县分公司"}
    companies = set(re.findall(r"[\u4e00-\u9fa5]{2,20}公司", text))
    unexpected = companies - known_fake
    if unexpected:
        print(f"ERROR: 发现未脱敏的机构名：{sorted(unexpected)}", file=sys.stderr)
        return 6
    print(f"  ✓ 机构名全部为虚构值：{sorted(companies)}")

    # 额外确认：所有串码位都已是伪串码
    # 用正则扫描（O(n)），不要写 `for s in serial_map: s in text`（那是 O(n²)，11MB 文本会卡死）
    fake_set = set(serial_map.values())
    slots = [m[1] for m in SERIAL_PATTERN.findall(text)]
    bad = [s for s in slots if s not in fake_set]
    if bad:
        print(f"ERROR: 有 {len(bad)} 个串码位未被替换，示例：{bad[:3]}", file=sys.stderr)
        return 5
    print(f"  ✓ 串码位 {len(slots)} 个全部为伪串码")

    # ---- 4) 结构一致性校验 ----
    new_lines = text.count("\n")
    new_ins_terminal = text.count("INSERT INTO terminal")
    if new_lines != orig_lines or new_ins_terminal != orig_ins_terminal:
        print(
            f"ERROR: 结构被破坏！行数 {orig_lines}→{new_lines}，"
            f"terminal INSERT {orig_ins_terminal}→{new_ins_terminal}",
            file=sys.stderr,
        )
        return 4
    print(f"  ✓ 结构未变：{new_lines} 行 / terminal INSERT {new_ins_terminal} 条")

    # ---- 5) 写出 ----
    DST.write_text(text, encoding="utf-8")
    print(f"\n已生成脱敏文件：{DST}")
    print(f"  大小 {DST.stat().st_size / 1024 / 1024:.1f} MB")
    print("\n原件未改动：" + str(SRC))
    return 0


if __name__ == "__main__":
    sys.exit(main())
