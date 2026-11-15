#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
标签字典导入（《标签体系清单-第一阶段-1012-含话术.xlsx》）
生成 Flyway 迁移 SQL：标签维度/优先级 + 租户级扩展信息（展示屏幕、话术、风控展示优先级）。

用法：
    python3 data/import-tag-dict-1012.py            # 生成 SQL 到 stdout 重定向
    python3 data/import-tag-dict-1012.py > server/src/main/resources/db/migration/V9012__tag_dict_data_1012.sql
"""
import os
import re
import subprocess
import sys
import openpyxl

BASE = os.path.dirname(os.path.abspath(__file__))
XLSX = os.path.join(BASE, "..", "..", "标签体系清单-第一阶段-1012-含话术.xlsx")
TENANT = "risk_control"

# 名称差异别名（Excel → 库内 tag_name）
ALIAS = {
    "日均分神驾驶次数": "日均分心驾驶次数",
}
# 一级维度优先级：按 Excel 中出现顺序（可在标签管理里调整）
DIM1_ORDER = ["性格特征", "驾驶与运营习惯", "服务交互表现", "运营模式", "人员基础属性"]


def q(v):
    if v is None:
        return "NULL"
    s = str(v).replace("\\", "\\\\").replace("'", "''")
    return "'" + s + "'"


def load_db_maps():
    """从运行中的 MySQL 读取 tag_name→code 与 category_name→(code,parent_code)。"""
    sql = ("SELECT tag_name, tag_code FROM tag_platform.tag_meta "
           "WHERE tenant_id LIKE '%risk_control%' AND status = 'online';")
    out = subprocess.run(
        ["docker", "exec", "tp-mysql", "mysql", "--default-character-set=utf8mb4",
         "-utag_app", "-ptag_app_demo", "-N", "-e", sql],
        capture_output=True, text=True, check=True).stdout
    tags = {}
    for line in out.strip().split("\n"):
        if "\t" in line:
            name, code = line.split("\t", 1)
            tags[name.strip()] = code.strip()

    sql2 = "SELECT category_name, category_code, IFNULL(parent_code,'') FROM tag_platform.tag_category;"
    out2 = subprocess.run(
        ["docker", "exec", "tp-mysql", "mysql", "--default-character-set=utf8mb4",
         "-utag_app", "-ptag_app_demo", "-N", "-e", sql2],
        capture_output=True, text=True, check=True).stdout
    cats = {}
    for line in out2.strip().split("\n"):
        parts = line.split("\t")
        if len(parts) == 3:
            cats[parts[0].strip()] = (parts[1].strip(), parts[2].strip())
    return tags, cats


def parse_tags(ws):
    """解析「标签」Sheet：返回 [{dim1,dim2,name,no,priority,screen,insurer}]。"""
    rows = []
    for r in range(2, ws.max_row + 1):
        dim1 = ws.cell(r, 1).value
        if not dim1:
            continue
        rows.append({
            "dim1": str(dim1).strip(),
            "dim2": str(ws.cell(r, 2).value or "").strip(),
            "name": str(ws.cell(r, 3).value or "").strip(),
            "no": str(ws.cell(r, 4).value or "").strip(),
            "priority": ws.cell(r, 9).value,
            "insurer": ws.cell(r, 10).value,
            "screen": str(ws.cell(r, 11).value or "").strip(),
        })
    return rows


def parse_scripts(ws):
    """解析「话术参考」Sheet：按「N.标签名」切块，块内文本按行列拼接。"""
    blocks, cur = {}, None
    for r in range(1, ws.max_row + 1):
        a = ws.cell(r, 1).value
        if a and re.match(r"^\s*\d+[.,，、\s]", str(a)):
            title = re.sub(r"^\s*\d+[.,，、\s]+", "", str(a)).strip()
            cur = title
            blocks.setdefault(cur, [])
            continue
        if cur is None:
            continue
        cells = []
        for c in range(1, 5):
            v = ws.cell(r, c).value
            if v is None:
                continue
            s = str(v).strip()
            if s:
                cells.append(s)
        if cells:
            blocks[cur].append(" | ".join(cells))
    return {k: "\n".join(v).strip() for k, v in blocks.items()}


def main():
    wb = openpyxl.load_workbook(XLSX, data_only=True)
    tags = parse_tags(wb["标签"])
    scripts = parse_scripts(wb["话术参考"])
    db_tags, cats = load_db_maps()

    print("-- ============================================================================")
    print("-- V9012 标签字典数据（《标签体系清单-第一阶段-1012-含话术.xlsx》）")
    print("-- 由 data/import-tag-dict-1012.py 生成，请勿手工修改")
    print("--   1) 回填 32 个存量标签的一级/二级维度、优先级")
    print("--   2) 写入租户级扩展信息：展示屏幕 / 标签话术 / 风控展示优先级")
    print("-- 说明：清单中「年龄/性别/驾龄」3 个标签库内尚未注册，本次跳过")
    print("-- ============================================================================")
    print()

    dim1_priority = {}
    for i, name in enumerate(DIM1_ORDER, start=1):
        dim1_priority[name] = i

    updates, ext_rows, missing, script_hit = [], [], [], 0
    for t in tags:
        db_name = ALIAS.get(t["name"], t["name"])
        code = db_tags.get(db_name)
        if not code:
            missing.append(t["name"])
            continue
        dim2 = cats.get(t["dim2"])
        dim1 = cats.get(t["dim1"])
        if not dim2 or not dim1:
            missing.append(f"{t['name']}（维度未登记：{t['dim1']}/{t['dim2']}）")
            continue

        prio = t["priority"]
        prio_sql = str(int(prio)) if isinstance(prio, (int, float)) else "NULL"
        updates.append(
            f"UPDATE tag_meta SET dim1_code = {q(dim1[0])}, dim2_code = {q(dim2[0])}, "
            f"priority = {prio_sql}, updated_at = NOW() WHERE tag_code = {q(code)};")

        # 展示屏幕
        if t["screen"] in ("主", "副"):
            ext_rows.append((code, "show_screen", t["screen"]))
        # 风控展示优先级：初始取人工打标优先级；无优先级则留空
        if prio_sql != "NULL":
            ext_rows.append((code, "risk_priority", prio_sql))
        # 话术
        script = scripts.get(t["name"])
        if script:
            ext_rows.append((code, "script", script))
            script_hit += 1

    print("-- ---------- 1. 标签维度 / 优先级 ----------")
    print("\n".join(updates))
    print()
    print("-- ---------- 2. 一级维度优先级 ----------")
    for dim, prio in dim1_priority.items():
        cat = cats.get(dim)
        if not cat:
            continue
        print(f"INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, "
              f"updated_by, created_at, updated_at) VALUES ('{TENANT}', 'DIM1', {q(cat[0])}, "
              f"'dim1_priority', '{prio}', 'import', NOW(), NOW()) "
              f"ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();")
    print()
    print("-- ---------- 3. 标签扩展信息（展示屏幕 / 话术 / 风控展示优先级） ----------")
    for code, key, val in ext_rows:
        print(f"INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, "
              f"updated_by, created_at, updated_at) VALUES ('{TENANT}', 'TAG', {q(code)}, {q(key)}, "
              f"{q(val)}, 'import', NOW(), NOW()) "
              f"ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();")

    print()
    print("-- ---------- 导入摘要 ----------")
    print(f"-- 标签更新 {len(updates)} 条；扩展值 {len(ext_rows) + len(dim1_priority)} 条；话术命中 {script_hit} 个")
    if missing:
        print(f"-- 未导入 {len(missing)} 条：" + "、".join(missing))


if __name__ == "__main__":
    main()
