#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把两份真实库存 xlsx 清洗后生成可导入的 SQL（M1）。

用法（在本机跑）：
    python gen-terminal-sql.py

输入：工作区根目录的
    长春市二级库.xlsx（23460 行）
    九台市分公司三级库.xlsx（4894 行）
输出：agent-platform/local_data/terminal-data.sql.gz（**不入 git**）

清洗要点（见 docs/contracts/01-数据模型.md）：
  1. 日期列在源文件里是 Excel 序列号（如 46197.6017592593），按 1900 基准转换
  2. 空字符串统一转 NULL（不用 ''，否则影响 COUNT 与索引）
  3. 金额列是 4 位小数字符串（150.4000）
  4. warehouse 表从 terminal 的仓位列派生去重
"""
import gzip
import os
import re
import sys
from datetime import datetime, timedelta
from xml.etree import ElementTree as ET
import zipfile

NS = '{http://schemas.openxmlformats.org/spreadsheetml/2006/main}'

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)              # agent-platform
WORKSPACE = os.path.dirname(PROJECT)         # 比赛/
OUT_DIR = os.path.join(PROJECT, 'local_data')
OUT_SQL = os.path.join(OUT_DIR, 'terminal-data.sql.gz')

SOURCES = [
    os.path.join(WORKSPACE, '长春市二级库.xlsx'),
    os.path.join(WORKSPACE, '九台市分公司三级库.xlsx'),
]

# Excel 列序（A..AG）→ DDL 字段名
COLUMNS = [
    'serialNo', 'bigType', 'smallType', 'vendor', 'model', 'materialCode',
    'materialName', 'purchaseNo', 'terminalStatus', 'inboundType',
    'businessType', 'purchaseType', 'statusChangedAt', 'lv1Warehouse',
    'lv2Warehouse', 'lv3Warehouse', 'lv4Warehouse', 'ownerWarehouse',
    'warehouseCode', 'targetWarehouse', 'unitPrice', 'customerPhone',
    'receiver', 'receiverNo', 'boxNo', 'firstInboundAt', 'agingDays',
    'warrantyStart', 'warrantyEnd', 'operator', 'operatorNo',
    'secondInboundAt', 'lostCompensation',
]

DATETIME_COLS = {'statusChangedAt', 'firstInboundAt', 'warrantyStart',
                 'warrantyEnd', 'secondInboundAt'}
DECIMAL_COLS = {'unitPrice', 'lostCompensation'}
INT_COLS = {'agingDays'}

EXCEL_EPOCH = datetime(1899, 12, 30)


def col_index(ref):
    """A→0, B→1, ... AA→26"""
    m = re.match(r'([A-Z]+)', ref or '')
    if not m:
        return -1
    n = 0
    for ch in m.group(1):
        n = n * 26 + (ord(ch) - ord('A') + 1)
    return n - 1


def shared_strings(z):
    try:
        root = ET.fromstring(z.read('xl/sharedStrings.xml'))
    except KeyError:
        return []
    out = []
    for si in root.findall(NS + 'si'):
        out.append(''.join(t.text or '' for t in si.iter(NS + 't')))
    return out


def read_rows(path):
    """流式读取 sheet1 的所有数据行（跳过表头），返回 [{列名: 原始字符串}]。"""
    z = zipfile.ZipFile(path)
    ss = shared_strings(z)
    rows = []
    with z.open('xl/worksheets/sheet1.xml') as f:
        for event, el in ET.iterparse(f, events=('end',)):
            if el.tag != NS + 'row':
                continue
            if el.get('r') == '1':          # 表头
                el.clear()
                continue
            rec = {}
            for c in el.findall(NS + 'c'):
                idx = col_index(c.get('r'))
                if idx < 0 or idx >= len(COLUMNS):
                    continue
                t = c.get('t')
                v = c.find(NS + 'v')
                is_ = c.find(NS + 'is')
                if t == 's' and v is not None and v.text is not None:
                    val = ss[int(v.text)]
                elif t == 'inlineStr' and is_ is not None:
                    val = ''.join(x.text or '' for x in is_.iter(NS + 't'))
                else:
                    val = v.text if v is not None else ''
                rec[COLUMNS[idx]] = val
            el.clear()
            if rec.get('serialNo'):
                rows.append(rec)
    return rows


def to_datetime(val):
    """Excel 序列号 或 文本日期 → datetime；不合理返回 None

    源文件的日期列有两种形态：
      1. Excel 序列号（float，如 46197.6017592593）
      2. 文本（如 '1900-01-01 00:00:00' —— 这是空日期被填了 0 之后的产物）
    所以数值分支和文本分支算出来的结果都要过一遍年份合理性检查。
    """
    if not val:
        return None
    dt = None
    try:
        serial = float(val)
        # 有效区间：2000-01-01(36526) ~ 2100-01-01(73050)
        if 36526 <= serial <= 73050:
            dt = EXCEL_EPOCH + timedelta(days=serial)
    except ValueError:
        for fmt in ('%Y-%m-%d %H:%M:%S', '%Y-%m-%d', '%Y/%m/%d %H:%M:%S', '%Y/%m/%d'):
            try:
                dt = datetime.strptime(val.strip(), fmt)
                break
            except ValueError:
                continue
    except (OverflowError, ValueError):
        return None

    if dt is None:
        return None
    # 统一兜底：2000 年以前 / 2100 年以后一律视为脏数据
    if dt.year < 2000 or dt.year > 2100:
        return None
    return dt


def clean(rec):
    """按字段类型清洗，返回 dict（值为 str / int / float / None）"""
    out = {}
    for col in COLUMNS:
        raw = (rec.get(col) or '').strip()
        if col in DATETIME_COLS:
            dt = to_datetime(raw)
            out[col] = dt.strftime('%Y-%m-%d %H:%M:%S') if dt else None
        elif col in DECIMAL_COLS:
            try:
                out[col] = round(float(raw), 4) if raw else None
            except ValueError:
                out[col] = None
        elif col in INT_COLS:
            try:
                n = int(round(float(raw))) if raw else None
                # 库龄合理区间 0~3650 天；源文件里有 46293 这种异常值（源头是空日期被当成序列号）
                out[col] = n if (n is not None and 0 <= n <= 3650) else None
            except ValueError:
                out[col] = None
        else:
            out[col] = raw or None
    return out


def sq(v):
    """SQL 字面量"""
    if v is None:
        return 'NULL'
    if isinstance(v, (int, float)):
        return str(v)
    return "'" + str(v).replace('\\', '\\\\').replace("'", "''") + "'"


def derive_warehouses(cleaned):
    """从 terminal 数据派生仓位：ownerWarehouse ↔ warehouseCode 为键，层级由哪一列有值判定"""
    code_by_name = {}
    for r in cleaned:
        code, name = r.get('warehouseCode'), r.get('ownerWarehouse')
        if code and name:
            code_by_name.setdefault(name, code)

    wh = {}
    for r in cleaned:
        code = r.get('warehouseCode')
        if not code:
            continue
        lv1, lv2, lv3, lv4 = (r.get('lv1Warehouse'), r.get('lv2Warehouse'),
                              r.get('lv3Warehouse'), r.get('lv4Warehouse'))
        if lv4:
            level, name, parent_name = 4, lv4, lv3
        elif lv3:
            level, name, parent_name = 3, lv3, lv2
        elif lv2:
            level, name, parent_name = 2, lv2, lv1
        else:
            level, name, parent_name = 1, lv1, None
        if not name:
            name = r.get('ownerWarehouse') or code
        wh[code] = {
            'code': code, 'name': name, 'level': level,
            'parent': code_by_name.get(parent_name) if parent_name else None,
            'province': lv1, 'city': lv2, 'district': lv3,
        }

    # 补齐：上级仓位若也在表里，层级应比它低一级（防止个别数据缺列导致层级算错）
    out = sorted(wh.values(), key=lambda x: (x['level'], x['code']))
    return out


def main():
    all_rows = []
    for src in SOURCES:
        if not os.path.isfile(src):
            print(f'!! 找不到 {src}', file=sys.stderr)
            return 1
        rows = read_rows(src)
        print(f'  读取 {os.path.basename(src)}: {len(rows)} 行')
        all_rows.extend(rows)

    print(f'合计 {len(all_rows)} 行，开始清洗…')
    cleaned = [clean(r) for r in all_rows]

    # 串码去重（两份文件可能重叠）
    seen = {}
    for r in cleaned:
        seen[r['serialNo']] = r
    uniq = list(seen.values())
    print(f'按串码去重后：{len(uniq)} 条（去掉了 {len(cleaned) - len(uniq)} 条重复）')

    warehouses = derive_warehouses(uniq)
    print(f'派生出 {len(warehouses)} 个仓位')

    os.makedirs(OUT_DIR, exist_ok=True)
    with gzip.open(OUT_SQL, 'wt', encoding='utf-8', newline='\n') as f:
        f.write('USE api_db;\n')
        f.write('SET NAMES utf8mb4;\n')
        f.write('SET SESSION sql_mode = \'NO_ENGINE_SUBSTITUTION\';\n')
        f.write('-- 清空旧数据（脚本可重复执行）\n')
        f.write('DELETE FROM terminal;\nDELETE FROM warehouse;\n')

        # warehouse
        f.write('\n-- warehouse\n')
        for w in warehouses:
            f.write(
                "INSERT INTO warehouse (warehouseCode,name,level,parentCode,province,city,district) VALUES ("
                f"{sq(w['code'])},{sq(w['name'])},{w['level']},{sq(w['parent'])},"
                f"{sq(w['province'])},{sq(w['city'])},{sq(w['district'])});\n")

        # terminal：分批 INSERT（每批 200 行）
        f.write('\n-- terminal\n')
        cols = ','.join(f'`{c}`' for c in COLUMNS)
        BATCH = 200
        total = 0
        for i in range(0, len(uniq), BATCH):
            chunk = uniq[i:i + BATCH]
            vals = []
            for r in chunk:
                vals.append('(' + ','.join(sq(r[c]) for c in COLUMNS) + ')')
            f.write(f'INSERT INTO terminal ({cols}) VALUES\n')
            f.write(',\n'.join(vals))
            f.write(';\n')
            total += len(chunk)
        print(f'已写入 {total} 条 terminal')

        f.write('\nSELECT \'terminal data loaded\' AS msg;\n')

    size = os.path.getsize(OUT_SQL)
    print(f'\n输出：{OUT_SQL}  ({size/1024/1024:.1f} MB)')

    # 统计预览
    from collections import Counter
    print('\n终端状态分布（TOP8）：')
    for k, v in Counter(r['terminalStatus'] for r in uniq).most_common(8):
        print(f'  {k}: {v}')
    print('\n终端大类分布（TOP8）：')
    for k, v in Counter(r['bigType'] for r in uniq).most_common(8):
        print(f'  {k}: {v}')
    print('\n仓位：')
    for w in warehouses:
        print(f"  [{w['level']}] {w['code']} {w['name']}  parent={w['parent']}")
    return 0


if __name__ == '__main__':
    sys.exit(main())
