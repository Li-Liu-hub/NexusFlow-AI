# -*- coding: utf-8 -*-
"""还原含 U+FFFD/'?' 的损坏注释（原始字节已丢失，按语义定向映射）。

这些注释在源库里已是「错误转码 + 半角问号替换」的产物，例如：
    '鏄?惁鍒犻櫎'  ← '是否删除'
    '鐘舵?锛?-寰呭?鏍? 1-閫氳繃, 2-鎷掔粷锛' ← '状态（0-待审核, 1-通过, 2-拒绝）'

推理依据：
  '鏄?惁' → 是否   '鍒犻櫎' → 删除   '鐘舵? t' → 状态
  '閫氳繃' → 通过   '鎷掔粷' → 拒绝   '寰呭?鏍?' → 待审核
"""
import io
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))

# 先做「可逆」的 GBK 链还原，再做「不可逆」的定向修正
FIXED = {
    # 通用
    "鏄?惁鍒犻櫎(0-鏈?垹, 1-宸插垹)": "是否删除（0-未删除, 1-已删除）",
    "鏄?惁鍒犻櫎 (0-鏈?垹锛?-宸插垹)": "是否删除（0-未删除, 1-已删除）",
    "鏄?惁鍒犻櫎": "是否删除",
    "鐘舵?锛?-寰呭?鏍? 1-閫氳繃, 2-鎷掔粷锛": "状态（0-待审核, 1-通过, 2-拒绝）",
    "鎺ュ彛鐘舵? (0-鍏抽棴锛?-寮?惎)": "接口状态 (0-关闭, 1-开启)",
    "鎬у埆锛?-鐢? 1-濂筹級": "性别（0-男, 1-女）",
    "0-姝ｅ父锛?-绂佺敤": "0-正常, 1-禁用",
    "1=璋冪敤闇?汉宸ュ?鎵规斁琛": "1=调用需人工审批放行",
    "娴忚?鏁": "浏览数",
    "寰呮墽琛屽叆鍙傦紙鏀捐?鍚庡師鏍锋墽琛岋級": "待执行入参（放行后原样执行）",
    "瀹℃壒鍗曞彿锛堝?澶栨毚闇诧級": "审批单号（对外暴露）",
    "琚?嫤涓嬬殑宸ュ叿": "被拦下的工具",
    "蹇?収鑷?a_llm_tool": "快照自 a_llm_tool",
    "鍐呭?锛堜釜浜轰粙缁嶏級": "内容（个人介绍）",
    "鎬昏皟鐢ㄦ?鏁": "总调用次数",
    "鎵ц?缁撴灉鎽樿?锛堟斁琛屽悗鍥炲～锛": "执行结果摘要（放行后回填）",
}


def process(path: str) -> int:
    with io.open(path, encoding="utf-8") as f:
        src = f.read()
    n = [0]

    def repl(m):
        q, body = m.group(1), m.group(2)
        if body in FIXED:
            n[0] += 1
            return "COMMENT " + q + FIXED[body] + q
        return m.group(0)

    src = re.sub(r"COMMENT (['\"])(.*?)\1", repl, src)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(src)
    return n[0]


if __name__ == "__main__":
    for fn in ("01-schema-api_db.sql", "02-schema-terminal_db.sql"):
        p = os.path.join(HERE, fn)
        if os.path.exists(p):
            print("%-28s 定向还原 %d 处" % (fn, process(p)))
