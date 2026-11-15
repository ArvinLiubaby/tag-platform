#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
补录《标签体系清单-第一阶段-1012》中库内尚未注册的 3 个标签：
    年龄（RK001）、性别（RK002）、驾龄（RK003）

生成 Flyway 迁移 SQL：tag_meta + 版本基线 + 租户扩展信息（屏幕 / 风控优先级 / 话术）。
用法：
    python3 data/gen-missing-tags-1012.py > server/src/main/resources/db/migration/V9013__tag_dict_baseline_1012.sql
"""
import os
import re
import openpyxl

BASE = os.path.dirname(os.path.abspath(__file__))
XLSX = os.path.join(BASE, "..", "..", "标签体系清单-第一阶段-1012-含话术.xlsx")
TENANT = "risk_control"

# 标签定义：按清单「判断逻辑」的精算分组映射为枚举取值
TAGS = [
    {
        "code": "driver_age", "name": "年龄", "no": "RK001",
        "options": ["25岁以下", "25-29岁", "30-39岁", "40-59岁", "60岁以上", "未知"],
    },
    {
        "code": "gender", "name": "性别", "no": "RK002",
        "options": ["男", "女", "未知"],
    },
    {
        "code": "driving_years", "name": "驾龄", "no": "RK003",
        "options": ["1年以下", "1-2年", "3年以上", "未知"],
    },
]
PRIORITY = 4          # 清单「人工打标签标签」列
SCREEN = "主"          # 清单「主副屏」列
DIM1 = "baseline"
DIM2 = "demographic"


def q(v):
    if v is None:
        return "NULL"
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def parse_scripts(ws):
    blocks, cur = {}, None
    for r in range(1, ws.max_row + 1):
        a = ws.cell(r, 1).value
        if a and re.match(r"^\s*\d+[.,，、\s]", str(a)):
            cur = re.sub(r"^\s*\d+[.,，、\s]+", "", str(a)).strip()
            blocks.setdefault(cur, [])
            continue
        if cur is None:
            continue
        cells = []
        for c in range(1, 5):
            v = ws.cell(r, c).value
            if v is not None and str(v).strip():
                cells.append(str(v).strip())
        if cells:
            blocks[cur].append(" | ".join(cells))
    return {k: "\n".join(v).strip() for k, v in blocks.items()}


def main():
    wb = openpyxl.load_workbook(XLSX, data_only=True)
    scripts = parse_scripts(wb["话术参考"])

    print("-- ============================================================================")
    print("-- V9013 补录「人员基础属性 / 人口特征」3 个标签（年龄 / 性别 / 驾龄）")
    print("-- 由 data/gen-missing-tags-1012.py 生成，请勿手工修改")
    print("--   取值按清单「判断逻辑」的精算分组枚举化；优先级取「人工打标签标签」=4")
    print("-- ============================================================================")
    print()

    for t in TAGS:
        enum_json = '{"enum":[' + ",".join('"' + o + '"' for o in t["options"]) + "]}"
        print(f"-- ---------- {t['name']}（{t['no']} → {t['code']}） ----------")
        print(f"""INSERT INTO tag_meta
  (tag_code, tag_name, category_code, dim1_code, dim2_code, priority, value_type, value_range,
   value_multiple, tag_type, need_value_audit, value_ttl_days, tenant_id, sensitivity, status,
   version, object_type, source_mode, writable, created_at, updated_at)
VALUES
  ({q(t['code'])}, {q(t['name'])}, {q(DIM2)}, {q(DIM1)}, {q(DIM2)}, {PRIORITY}, 'enum', {q(enum_json)},
   0, 'fact', 0, 0, {q(TENANT)}, 'L2', 'online', 1, 'driver', 'manual', 1, NOW(), NOW());""")

        print(f"""INSERT INTO tag_meta_version
  (tag_code, version, change_type, change_by, change_summary, tag_name, category_code,
   value_type, value_range, value_multiple, tag_type, need_value_audit, value_ttl_days,
   tenant_id, sensitivity, object_type, source_mode, writable, dim1_code, dim2_code, priority,
   created_at)
VALUES
  ({q(t['code'])}, 1, 'create', 'import', '清单-1012 补录：{t['no']} {t['name']}',
   {q(t['name'])}, {q(DIM2)}, 'enum', {q(enum_json)}, 0, 'fact', 0, 0,
   {q(TENANT)}, 'L2', 'driver', 'manual', 1, {q(DIM1)}, {q(DIM2)}, {PRIORITY}, NOW());""")

        # 扩展信息：展示屏幕 / 风控展示优先级
        for key, val in (("show_screen", SCREEN), ("risk_priority", str(PRIORITY))):
            print(f"""INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value,
  updated_by, created_at, updated_at)
VALUES ({q(TENANT)}, 'TAG', {q(t['code'])}, {q(key)}, {q(val)}, 'import', NOW(), NOW())
ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();""")

        script = scripts.get(t["name"])
        if script:
            print(f"""INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value,
  updated_by, created_at, updated_at)
VALUES ({q(TENANT)}, 'TAG', {q(t['code'])}, 'script', {q(script)}, 'import', NOW(), NOW())
ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();""")
        print()

    print("-- ---------- 摘要 ----------")
    hit = [t["name"] for t in TAGS if t["name"] in scripts]
    miss = [t["name"] for t in TAGS if t["name"] not in scripts]
    print(f"-- 新增 3 个标签；话术命中：{'、'.join(hit) if hit else '无'}；无话术：{'、'.join(miss) if miss else '无'}")


if __name__ == "__main__":
    main()
