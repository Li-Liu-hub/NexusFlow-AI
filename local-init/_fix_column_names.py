#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""一次性工具：把终端业务表的下划线列名改写成驼峰。

背景：aismp-backend 的 application.yml 设了
    mybatis-plus.configuration.map-underscore-to-camel-case: false
且全项目 DDL 的列名都用驼峰（createTime / userId）。
如果终端表用下划线列名，MyBatis-Plus 的 BaseMapper 查询会找不到列。

用法：python _fix_column_names.py
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# 需要改写的所有下划线列名（覆盖 7 张表）
WORDS = """
serial_no big_type small_type material_code material_name purchase_no
terminal_status inbound_type business_type purchase_type status_changed_at
lv1_warehouse lv2_warehouse lv3_warehouse lv4_warehouse owner_warehouse
warehouse_code target_warehouse unit_price customer_phone receiver_no box_no
first_inbound_at aging_days warranty_start warranty_end operator_no
second_inbound_at lost_compensation parent_code created_at order_no
operation_type from_warehouse to_warehouse item_count total_quantity
need_approval reject_reason suggestion_id outbound_at inbound_at serial_nos
notice_type is_read user_id usage_date tokens_in tokens_out daily_limit
updated_at
""".split()


def camel(s: str) -> str:
    head, *rest = s.split('_')
    return head + ''.join(w.capitalize() for w in rest)


MAPPING = {w: camel(w) for w in WORDS}
# 更长的先替换，避免 serial_no 在 serial_nos 之前被替换
ORDERED = sorted(MAPPING.items(), key=lambda kv: -len(kv[0]))


def convert(path):
    with open(path, encoding='utf-8') as f:
        src = f.read()
    out = src
    for old, new in ORDERED:
        # 只替换独立单词（前后不是字母/数字/下划线）
        out = re.sub(r'(?<![A-Za-z0-9_])' + old + r'(?![A-Za-z0-9_])', new, out)
    if out != src:
        with open(path, 'w', encoding='utf-8', newline='\n') as f:
            f.write(out)
        return True
    return False


if __name__ == '__main__':
    targets = [
        os.path.join(HERE, '..', 'deploy', 'server-secure', '40-terminal-schema.sql'),
        os.path.join(HERE, 'gen-terminal-sql.py'),
    ]
    for t in targets:
        t = os.path.normpath(t)
        changed = convert(t)
        print(f'{"改写" if changed else "无变化"}: {t}')
    print('\n映射表：')
    for k, v in MAPPING.items():
        print(f'  {k:22s} -> {v}')
