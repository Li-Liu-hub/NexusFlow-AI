# -*- coding: utf-8 -*-
"""修正 mysqldump 产物中的「双重编码」乱码注释。

背景（已用 HEX 逐字节验证）：
  源库 api_db 的少数表是历史用错字符集建的，COMMENT 里存的是错误转码结果。
  编码链：正确中文 UTF-8 字节 → 被误按 GBK 解码成乱码字符串 → 再存为 UTF-8。

  例：'账号' 的 UTF-8 字节 e8b4a6e58fb7
      → 按 GBK 解码得 '璐﹀彿'
      → 存进库的字节就是 '璐﹀彿' 的 UTF-8

  验证：HEX(column_comment) = E79290EFB980E5BDBF = '璐﹀彿'.encode('utf-8')

修法（逆运算）：
  乱码串 → latin1 编码回原始字节 → 按 GBK 解码 = 正确中文
  注意 U+FE40('﹀') 是替换符，说明个别字符信息已丢失，无法还原时保留原样。
"""
import io
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))


def fix(messy: str) -> str:
    """乱码 → 正确中文；不可还原则原样返回。"""
    try:
        # 先拿到「乱码字符串按 GBK 解出来的原始字节」
        raw = messy.encode("gbk")
    except UnicodeEncodeError:
        # 含 U+FE40 等 GBK 无法编码的替换符 —— 逐字符尽力还原
        out = []
        for ch in messy:
            try:
                out.append(ch.encode("gbk"))
            except UnicodeEncodeError:
                out.append(ch.encode("utf-8"))
        raw = b"".join(out)
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return messy


def process(path: str) -> int:
    with io.open(path, encoding="utf-8") as f:
        src = f.read()

    n = [0]

    def repl(m):
        quote, body = m.group(1), m.group(2)
        fixed = fix(body)
        if fixed != body:
            n[0] += 1
            return "COMMENT " + quote + fixed + quote
        return m.group(0)

    src = re.sub(r"COMMENT (['\"])(.*?)\1", repl, src)

    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(src)
    return n[0]


if __name__ == "__main__":
    for fn in ("01-schema-api_db.sql", "02-schema-terminal_db.sql",
               "10-seed-api_db.sql"):
        p = os.path.join(HERE, fn)
        if os.path.exists(p):
            print("%-28s 修正注释 %d 处" % (fn, process(p)))
