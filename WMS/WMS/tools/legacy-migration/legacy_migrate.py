#!/usr/bin/env python3
"""MT-WMS legacy MySQL/SQLite to openGauss migration utility.

The utility intentionally migrates business data into an existing target company.
Authentication configuration and passwords remain owned by the rebuilt system.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sqlite3
import sys
import time
import uuid
from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from typing import Any, Callable, Iterable

import pymysql
import psycopg2
from pymysql.cursors import DictCursor
from psycopg2.extras import RealDictCursor


REQUIRED_TABLES = {
    "w_warehouse": {"id", "code", "name", "company_code"},
    "w_area": {"id", "code", "name", "warehouse_code", "company_code"},
    "w_workarea": {"id", "code", "name", "warehouse_code", "company_code"},
    "w_location": {"id", "code", "warehouse_code", "area_code", "workarea_code", "company_code"},
    "u_partner": {"id", "code", "name", "xtype", "company_code"},
    "inv_category": {"id", "code", "name", "owner_code", "company_code"},
    "inv_good": {"id", "code", "name", "barcode", "owner_code", "company_code"},
    "inv": {"id", "sku", "location_code", "owner_code", "warehouse_code", "company_code", "qty"},
    "inv_rfid": {"id", "rfid", "sku", "location_code", "owner_code", "warehouse_code", "company_code"},
    "i_stockin": {"id", "order_code", "warehouse_code", "owner_code", "company_code", "state"},
    "i_stockin_line": {"id", "stockin_id", "sku", "qty", "qty_real"},
    "o_stockout": {"id", "order_code", "warehouse_code", "owner_code", "company_code", "state"},
    "o_stockout_line": {"id", "stockout_id", "sku", "qty"},
    "f_money": {"id", "code", "company_code", "warehouse_code", "owner_code"},
    "f_money_account": {"id", "company_code", "warehouse_code", "owner_code"},
}

ARCHIVE_TABLES = (
    "i_stockin_line_trans", "i_disassemble", "i_disassemble_line", "i_disassemble_item",
    "ext_async", "o_stockout_line_trans", "o_alloc", "o_box", "o_box_line", "o_stockout_merge",
    "f_money_line", "f_money_summary", "inv_rfid_trans", "inv_trans", "inv_good_map",
    "inv_adjust", "inv_count", "inv_move", "inv_warn", "u_company", "u_user", "s_big",
    "s_config", "s_seq", "s_did", "ext_contact",
)


INBOUND_TYPES = {
    "purchase": "PURCHASE", "return": "RETURN", "transfer": "TRANSFER",
    "consign": "CONSIGN", "custom": "CUSTOM", "produce": "PRODUCE",
    "material_return": "MATERIAL_RETURN", "fix": "FIX", "borrow_return": "BORROW_RETURN",
    "normal": "NORMAL", "coop_return": "COOP_RETURN", "produce_return": "PRODUCE_RETURN",
}
OUTBOUND_TYPES = {
    "sale": "SALE", "produce": "PRODUCE", "normal": "NORMAL", "return": "RETURN",
    "coop": "COOP", "material_pick": "MATERIAL_PICK", "fix": "FIX", "scrap": "SCRAP",
    "borrow": "BORROW", "transfer": "TRANSFER", "consign": "CONSIGN", "custom": "CUSTOM",
}
IN_STATES = {"create": "DRAFT", "part": "RECEIVING", "all": "COMPLETED", "done": "COMPLETED", "cancel": "CANCELLED"}
OUT_STATES = {"create": "DRAFT", "doing": "PROCESSING", "alloc": "PROCESSING", "pick": "PROCESSING", "part": "PROCESSING", "all": "COMPLETED", "done": "COMPLETED", "cancel": "CANCELLED"}
QUALITY = {"ZP", "CC", "DJ", "ZT", "JS", "XS"}
INITIAL_PASSWORD_HASH = "$2a$12$nwO2QEoxTQBkFbm0HD3sG.qWqrVb1/bn4N4/luqXvERiuG4OxYTC."


@dataclass
class Stats:
    source: dict[str, int] = field(default_factory=dict)
    target_before: dict[str, int] = field(default_factory=dict)
    written: dict[str, int] = field(default_factory=dict)
    skipped: dict[str, int] = field(default_factory=dict)
    target_after: dict[str, int] = field(default_factory=dict)
    warnings: list[str] = field(default_factory=list)

    def bump(self, bucket: str, name: str, amount: int = 1) -> None:
        values = getattr(self, bucket)
        values[name] = values.get(name, 0) + amount

    def json(self) -> str:
        return json.dumps(self.__dict__, ensure_ascii=False, sort_keys=True, default=str)


def text(value: Any, default: str = "", limit: int | None = None) -> str:
    result = default if value is None else str(value).strip()
    if not result:
        result = default
    return result[:limit] if limit else result


def number(value: Any, default: str = "0") -> Decimal:
    try:
        result = Decimal(str(value if value not in (None, "") else default))
        return result if result >= 0 else Decimal(default)
    except (InvalidOperation, ValueError):
        return Decimal(default)


def boolean(value: Any) -> bool:
    return str(value).strip().lower() in {"1", "true", "yes", "on", "y"}


def enum(value: Any, mapping: dict[str, str], fallback: str) -> str:
    return mapping.get(text(value).lower(), fallback)


def first_value(row: Any) -> Any:
    if isinstance(row, dict):
        return next(iter(row.values()))
    return row[0]


def redact(row: dict[str, Any]) -> dict[str, Any]:
    sensitive = re.compile(r"password|passwd|secret|sharekey|coopkey|token", re.IGNORECASE)
    return {key: ("***REDACTED***" if sensitive.search(key) and value not in (None, "") else value)
            for key, value in row.items()}


class SQLiteCursorAdapter:
    def __init__(self, connection: sqlite3.Connection):
        self.cursor = connection.cursor()

    def __enter__(self) -> "SQLiteCursorAdapter":
        return self

    def __exit__(self, *_: Any) -> None:
        self.cursor.close()

    def execute(self, sql: str, params: tuple[Any, ...] = ()) -> "SQLiteCursorAdapter":
        for table in ("u_company", "u_user"):
            sql = re.sub(rf"(?i)(FROM|JOIN)\s+`?{table}`?", rf"\1 auth.`{table}`", sql)
        self.cursor.execute(sql.replace("%s", "?"), params)
        return self

    def fetchone(self) -> dict[str, Any] | None:
        row = self.cursor.fetchone()
        return dict(row) if row is not None else None

    def fetchall(self) -> list[dict[str, Any]]:
        return [dict(row) for row in self.cursor.fetchall()]


class SQLiteSourceAdapter:
    dialect = "sqlite"

    def __init__(self, business_path: str, auth_path: str):
        for path in (business_path, auth_path):
            if not os.path.isfile(path):
                raise FileNotFoundError(f"SQLite 文件不存在：{path}")
        self.business_path = os.path.abspath(business_path)
        self.auth_path = os.path.abspath(auth_path)
        self.db = self.business_path
        self.connection = sqlite3.connect(f"file:{self.business_path}?mode=ro", uri=True)
        self.connection.row_factory = sqlite3.Row
        self.connection.execute("ATTACH DATABASE ? AS auth", (f"file:{self.auth_path}?mode=ro",))

    def cursor(self) -> SQLiteCursorAdapter:
        return SQLiteCursorAdapter(self.connection)

    def close(self) -> None:
        try:
            self.connection.execute("DETACH DATABASE auth")
        finally:
            self.connection.close()


def source_fingerprint(connection: Any, database: str) -> str:
    if getattr(connection, "dialect", "mysql") == "sqlite":
        digest = hashlib.sha256()
        for path in (connection.business_path, connection.auth_path):
            with open(path, "rb") as source_file:
                for chunk in iter(lambda: source_file.read(1024 * 1024), b""):
                    digest.update(chunk)
        return digest.hexdigest()
    with connection.cursor() as cursor:
        cursor.execute("SELECT table_name, table_rows FROM information_schema.tables WHERE table_schema=%s ORDER BY table_name", (database,))
        material = json.dumps(cursor.fetchall(), ensure_ascii=False, sort_keys=True, default=str)
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


class Migrator:
    def __init__(self, source: Any, target: Any, source_company: str, target_company: str, run_code: str):
        self.source = source
        self.target = target
        self.source_company = source_company
        self.target_company = target_company
        self.run_code = run_code
        self.stats = Stats()
        self.company_id = 0
        self.admin_id = 0

    def source_rows(self, table: str, where: str = "company_code=%s", params: tuple[Any, ...] | None = None,
                    track: bool = True) -> list[dict[str, Any]]:
        with self.source.cursor() as cursor:
            cursor.execute(f"SELECT * FROM `{table}` WHERE {where}", params if params is not None else (self.source_company,))
            rows = list(cursor.fetchall())
        if track:
            self.stats.source[table] = self.stats.source.get(table, 0) + len(rows)
        return rows

    def one_id(self, sql: str, params: tuple[Any, ...], label: str) -> int:
        with self.target.cursor() as cursor:
            cursor.execute(sql, params)
            row = cursor.fetchone()
        if not row:
            raise RuntimeError(f"目标数据缺失：{label}")
        return int(first_value(row))

    def target_id(self, table: str, code: str, owner: int | None = None, warehouse: int | None = None) -> int | None:
        scope_col, scope = ("owner_id", owner) if owner else (("warehouse_id", warehouse) if warehouse else ("company_id", self.company_id))
        with self.target.cursor() as cursor:
            cursor.execute(f"SELECT id FROM {table} WHERE {scope_col}=%s AND code=%s", (scope, code))
            row = cursor.fetchone()
        return int(first_value(row)) if row else None

    def prepare(self) -> None:
        self.company_id = self.one_id("SELECT id FROM auth_company WHERE code=%s", (self.target_company,), f"公司 {self.target_company}")
        self.admin_id = self.one_id(
            "SELECT id FROM auth_user WHERE company_id=%s AND status='ENABLED' ORDER BY CASE WHEN username='admin' THEN 0 ELSE 1 END,id LIMIT 1",
            (self.company_id,), "可用操作用户")

    def upsert_returning(self, sql: str, params: tuple[Any, ...]) -> int:
        table_match = re.search(r"INSERT\s+INTO\s+([a-z_]+)", sql, re.IGNORECASE)
        if not table_match:
            raise RuntimeError("无法识别目标插入表")
        table = table_match.group(1).lower()
        existing = self.existing_id(table, params)
        if existing is not None:
            return existing
        insert_sql = re.sub(r"\s+ON\s+CONFLICT[\s\S]*?RETURNING\s+id\s*$", " RETURNING id", sql, flags=re.IGNORECASE)
        with self.target.cursor() as cursor:
            cursor.execute(insert_sql, params)
            return int(first_value(cursor.fetchone()))

    def existing_id(self, table: str, params: tuple[Any, ...]) -> int | None:
        keys: dict[str, tuple[str, tuple[int, ...]]] = {
            "wms_warehouse": ("company_id=%s AND code=%s", (0, 1)),
            "wms_owner": ("company_id=%s AND code=%s", (0, 1)),
            "wms_partner": ("company_id=%s AND code=%s", (0, 1)),
            "wms_area": ("warehouse_id=%s AND code=%s", (1, 2)),
            "wms_work_area": ("warehouse_id=%s AND code=%s", (1, 2)),
            "wms_location": ("warehouse_id=%s AND code=%s", (1, 4)),
            "wms_category": ("owner_id=%s AND code=%s", (1, 2)),
            "wms_good": ("owner_id=%s AND code=%s", (1, 3)),
            "inv_balance": ("warehouse_id=%s AND owner_id=%s AND location_id=%s AND good_id=%s AND batch_code=%s AND quality_type=%s AND supplier_code=%s AND lpn=%s", (1,2,3,4,5,6,7,10)),
            "inv_serial": ("company_id=%s AND serial_code=%s", (0, 6)),
            "stockin_order": ("company_id=%s AND order_code=%s", (0, 3)),
            "stockout_order": ("company_id=%s AND order_code=%s", (0, 3)),
            "finance_account": ("company_id=%s AND warehouse_id=%s AND owner_id=%s AND long_name=%s", (0,1,2,6)),
        }
        if table not in keys:
            raise RuntimeError(f"未配置幂等键：{table}")
        where, indexes = keys[table]
        with self.target.cursor() as cursor:
            cursor.execute(f"SELECT id FROM {table} WHERE {where}", tuple(params[index] for index in indexes))
            row = cursor.fetchone()
        return int(first_value(row)) if row else None

    def migrate(self) -> Stats:
        self.prepare()
        self.migrate_warehouses()
        self.migrate_owners_and_partners()
        self.migrate_storage_structure()
        self.migrate_categories_and_goods()
        self.migrate_users()
        self.migrate_inventory()
        self.migrate_orders()
        self.migrate_finance()
        self.archive_legacy_history()
        self.grant_admin_scope()
        return self.stats

    def migrate_warehouses(self) -> None:
        for row in self.source_rows("w_warehouse"):
            code = text(row.get("code"), f"legacy-{row['id']}", 64)
            self.upsert_returning("""
                INSERT INTO wms_warehouse(company_id,code,name,contact,telephone,email,address,express_code,remark)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s)
                ON CONFLICT(company_id,code) DO UPDATE SET name=EXCLUDED.name,contact=EXCLUDED.contact,
                  telephone=EXCLUDED.telephone,email=EXCLUDED.email,address=EXCLUDED.address,
                  express_code=EXCLUDED.express_code,remark=EXCLUDED.remark,updated_at=CURRENT_TIMESTAMP RETURNING id
            """, (self.company_id, code, text(row.get("name"), code, 128), text(row.get("contact"), limit=64),
                   text(row.get("tel"), limit=32), text(row.get("email"), limit=128), text(row.get("address"), limit=256),
                   text(row.get("express_code"), limit=64), text(row.get("remark"), "旧系统迁移", 256)))
            self.stats.bump("written", "wms_warehouse")

    def migrate_owners_and_partners(self) -> None:
        rows = self.source_rows("u_partner")
        for row in rows:
            code = text(row.get("code"), f"legacy-{row['id']}", 64)
            xtype = text(row.get("xtype"), "client").lower()
            if xtype == "owner":
                self.upsert_returning("""
                    INSERT INTO wms_owner(company_id,code,name,contact,telephone,email,address,remark)
                    VALUES(%s,%s,%s,%s,%s,%s,%s,%s)
                    ON CONFLICT(company_id,code) DO UPDATE SET name=EXCLUDED.name,contact=EXCLUDED.contact,
                      telephone=EXCLUDED.telephone,email=EXCLUDED.email,address=EXCLUDED.address,
                      remark=EXCLUDED.remark,updated_at=CURRENT_TIMESTAMP RETURNING id
                """, (self.company_id, code, text(row.get("name"), code, 128), text(row.get("contact"), limit=64),
                       text(row.get("tel") or row.get("phone"), limit=32), text(row.get("email"), limit=128),
                       text(row.get("address"), limit=256), text(row.get("remark"), "旧系统迁移", 256)))
                self.stats.bump("written", "wms_owner")
            else:
                partner_type = {"supplier": "SUPPLIER", "client": "CLIENT", "express": "EXPRESS"}.get(xtype, "CLIENT_SUPPLIER")
                self.upsert_returning("""
                    INSERT INTO wms_partner(company_id,code,name,partner_type,contact,telephone,email,address,remark)
                    VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s)
                    ON CONFLICT(company_id,code) DO UPDATE SET name=EXCLUDED.name,partner_type=EXCLUDED.partner_type,
                      contact=EXCLUDED.contact,telephone=EXCLUDED.telephone,email=EXCLUDED.email,address=EXCLUDED.address,
                      remark=EXCLUDED.remark,updated_at=CURRENT_TIMESTAMP RETURNING id
                """, (self.company_id, code, text(row.get("name"), code, 256), partner_type, text(row.get("contact"), limit=64),
                       text(row.get("tel") or row.get("phone"), limit=32), text(row.get("email"), limit=128),
                       text(row.get("address"), limit=256), text(row.get("remark"), "旧系统迁移", 256)))
                self.stats.bump("written", "wms_partner")

    def warehouse(self, code: Any) -> int | None:
        return self.target_id("wms_warehouse", text(code, "default", 64))

    def owner(self, code: Any) -> int | None:
        return self.target_id("wms_owner", text(code, "default", 64))

    def migrate_storage_structure(self) -> None:
        for source_table, target_table, extra in (
            ("w_area", "wms_area", "area_type"), ("w_workarea", "wms_work_area", "system_defined")):
            for row in self.source_rows(source_table):
                warehouse = self.warehouse(row.get("warehouse_code"))
                if not warehouse:
                    self.stats.bump("skipped", source_table); continue
                code = text(row.get("code"), f"legacy-{row['id']}", 64)
                if target_table == "wms_area":
                    area_type = {"storage":"STORAGE","temp":"TEMP","piece":"PIECE","bad":"BAD","return":"RETURN","repair":"REPAIR"}.get(text(row.get("xtype")).lower(), "STORAGE")
                    sql = """INSERT INTO wms_area(company_id,warehouse_id,code,name,area_type,remark) VALUES(%s,%s,%s,%s,%s,%s)
                             ON CONFLICT(warehouse_id,code) DO UPDATE SET name=EXCLUDED.name,area_type=EXCLUDED.area_type,remark=EXCLUDED.remark,updated_at=CURRENT_TIMESTAMP RETURNING id"""
                    args = (self.company_id, warehouse, code, text(row.get("name"), code, 128), area_type, text(row.get("remark"), "旧系统迁移", 256))
                else:
                    sql = """INSERT INTO wms_work_area(company_id,warehouse_id,code,name,system_defined,remark) VALUES(%s,%s,%s,%s,%s,%s)
                             ON CONFLICT(warehouse_id,code) DO UPDATE SET name=EXCLUDED.name,system_defined=EXCLUDED.system_defined,remark=EXCLUDED.remark,updated_at=CURRENT_TIMESTAMP RETURNING id"""
                    args = (self.company_id, warehouse, code, text(row.get("name"), code, 128), boolean(row.get("is_inner")), text(row.get("remark"), "旧系统迁移", 256))
                self.upsert_returning(sql, args); self.stats.bump("written", target_table)

        for row in self.source_rows("w_location"):
            warehouse = self.warehouse(row.get("warehouse_code"))
            if not warehouse:
                self.stats.bump("skipped", "w_location"); continue
            area = self.target_id("wms_area", text(row.get("area_code"), "default", 64), warehouse=warehouse)
            work = self.target_id("wms_work_area", text(row.get("workarea_code"), "default", 64), warehouse=warehouse)
            if not area or not work:
                self.stats.bump("skipped", "w_location"); continue
            code = text(row.get("code"), f"legacy-{row['id']}", 64)
            self.upsert_returning("""
                INSERT INTO wms_location(company_id,warehouse_id,area_id,work_area_id,code,operation_order,priority,system_defined,length_cm,width_cm,height_cm,max_weight_kg,remark)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                ON CONFLICT(warehouse_id,code) DO UPDATE SET area_id=EXCLUDED.area_id,work_area_id=EXCLUDED.work_area_id,
                  operation_order=EXCLUDED.operation_order,priority=EXCLUDED.priority,length_cm=EXCLUDED.length_cm,
                  width_cm=EXCLUDED.width_cm,height_cm=EXCLUDED.height_cm,max_weight_kg=EXCLUDED.max_weight_kg,
                  remark=EXCLUDED.remark,updated_at=CURRENT_TIMESTAMP RETURNING id
            """, (self.company_id, warehouse, area, work, code, int(number(row.get("index"))),
                   text(row.get("priority"), "L3") if text(row.get("priority")) in {"L1","L2","L3","L4","L5"} else "L3",
                   boolean(row.get("is_inner")), number(row.get("length")), number(row.get("width")), number(row.get("height")),
                   number(row.get("weight")), text(row.get("remark"), "旧系统迁移", 256)))
            self.stats.bump("written", "wms_location")

    def migrate_categories_and_goods(self) -> None:
        for row in self.source_rows("inv_category"):
            owner = self.owner(row.get("owner_code"))
            if not owner:
                self.stats.bump("skipped", "inv_category"); continue
            code = text(row.get("code"), f"legacy-{row['id']}", 64)
            self.upsert_returning("""INSERT INTO wms_category(company_id,owner_id,code,name,remark) VALUES(%s,%s,%s,%s,%s)
                ON CONFLICT(owner_id,code) DO UPDATE SET name=EXCLUDED.name,remark=EXCLUDED.remark,updated_at=CURRENT_TIMESTAMP RETURNING id""",
                (self.company_id, owner, code, text(row.get("name"), code, 128), text(row.get("remark"), "旧系统迁移", 256)))
            self.stats.bump("written", "wms_category")
        for row in self.source_rows("inv_good"):
            owner = self.owner(row.get("owner_code"))
            if not owner:
                self.stats.bump("skipped", "inv_good"); continue
            category_code = text(row.get("category_code"), "default", 64)
            category = self.target_id("wms_category", category_code, owner=owner) or self.target_id("wms_category", "default", owner=owner)
            if not category:
                self.stats.bump("skipped", "inv_good"); continue
            code = text(row.get("code"), f"legacy-{row['id']}", 64)
            barcode = text(row.get("barcode"), f"LEGACY-{owner}-{code}", 128)
            self.upsert_returning("""
                INSERT INTO wms_good(company_id,owner_id,category_id,code,name,barcode,specification,unit,brand,item_type,shelf_life_enabled,quality_months,min_quantity,max_quantity,weight_kg,price,cost_price,remark)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                ON CONFLICT(owner_id,code) DO UPDATE SET category_id=EXCLUDED.category_id,name=EXCLUDED.name,
                  barcode=EXCLUDED.barcode,specification=EXCLUDED.specification,unit=EXCLUDED.unit,brand=EXCLUDED.brand,
                  shelf_life_enabled=EXCLUDED.shelf_life_enabled,quality_months=EXCLUDED.quality_months,
                  min_quantity=EXCLUDED.min_quantity,max_quantity=EXCLUDED.max_quantity,weight_kg=EXCLUDED.weight_kg,
                  price=EXCLUDED.price,cost_price=EXCLUDED.cost_price,remark=EXCLUDED.remark,updated_at=CURRENT_TIMESTAMP RETURNING id
            """, (self.company_id, owner, category, code, text(row.get("name"), code, 256), barcode,
                   text(row.get("spec"), limit=128), text(row.get("unit"), limit=32), text(row.get("brand"), limit=64), "ZC",
                   int(number(row.get("quality_month"))) > 0, int(number(row.get("quality_month"))), number(row.get("min_qty")),
                   number(row.get("max_qty")), number(row.get("weight")), number(row.get("price")), number(row.get("cost_price")),
                   text(row.get("remark"), "旧系统迁移", 256)))
            self.stats.bump("written", "wms_good")

    def migrate_users(self) -> None:
        if not table_columns(self.source, self.source.db, "u_user"):
            self.stats.warnings.append("认证库缺少 u_user，未迁移旧用户")
            return
        for row in self.source_rows("u_user"):
            username = text(row.get("code"), f"legacy-user-{row['id']}", 64)
            with self.target.cursor() as cursor:
                cursor.execute("SELECT id FROM auth_user WHERE company_id=%s AND username=%s", (self.company_id,username))
                existing = cursor.fetchone()
                if existing:
                    user_id = int(first_value(existing))
                    self.stats.bump("skipped", "auth_user_duplicate")
                else:
                    cursor.execute("""INSERT INTO auth_user(company_id,username,mobile,display_name,password_hash,status,login_enabled,password_change_required,created_at,updated_at)
                        VALUES(%s,%s,%s,%s,%s,%s,%s,TRUE,COALESCE(%s,CURRENT_TIMESTAMP),COALESCE(%s,CURRENT_TIMESTAMP)) RETURNING id""",
                        (self.company_id,username,text(row.get("tel"),limit=32) or None,text(row.get("name"),username,128),INITIAL_PASSWORD_HASH,
                         "ENABLED" if text(row.get("state"),"on").lower()=="on" else "DISABLED",text(row.get("state"),"on").lower()=="on",row.get("create_time"),row.get("update_time")))
                    user_id = int(first_value(cursor.fetchone()))
                    self.stats.bump("written", "auth_user")
                legacy_roles = {item.strip().lower() for item in text(row.get("roles")).split(",") if item.strip()}
                role_code = "ADMIN" if username == "admin" or "admin" in legacy_roles else ("MANAGER" if "manager" in legacy_roles or text(row.get("xtype")).lower() == "monitor" else "NORMAL")
                cursor.execute("DELETE FROM auth_user_role WHERE user_id=%s AND role_id IN (SELECT id FROM auth_role WHERE code IN ('ADMIN','MANAGER','NORMAL'))", (user_id,))
                cursor.execute("""INSERT INTO auth_user_role(user_id,role_id)
                    SELECT %s,r.id FROM auth_role r WHERE r.code=%s
                    AND NOT EXISTS(SELECT 1 FROM auth_user_role ur WHERE ur.user_id=%s AND ur.role_id=r.id)""", (user_id,role_code,user_id))
                cursor.execute("""INSERT INTO auth_user_warehouse(user_id,warehouse_id)
                    SELECT %s,w.id FROM wms_warehouse w WHERE w.company_id=%s
                    AND NOT EXISTS(SELECT 1 FROM auth_user_warehouse x WHERE x.user_id=%s AND x.warehouse_id=w.id)""", (user_id,self.company_id,user_id))
                cursor.execute("""INSERT INTO auth_user_owner(user_id,owner_id)
                    SELECT %s,o.id FROM wms_owner o WHERE o.company_id=%s
                    AND NOT EXISTS(SELECT 1 FROM auth_user_owner x WHERE x.user_id=%s AND x.owner_id=o.id)""", (user_id,self.company_id,user_id))

    def migrate_inventory(self) -> None:
        for row in self.source_rows("inv"):
            warehouse, owner = self.warehouse(row.get("warehouse_code")), self.owner(row.get("owner_code"))
            if not warehouse or not owner:
                self.stats.bump("skipped", "inv"); continue
            location = self.target_id("wms_location", text(row.get("location_code"), "STAGE", 64), warehouse=warehouse)
            good = self.target_id("wms_good", text(row.get("sku"), limit=64), owner=owner)
            if not location or not good:
                self.stats.bump("skipped", "inv"); continue
            quality = text(row.get("quality_type"), "ZP").upper()
            quality = quality if quality in QUALITY else "ZP"
            available = number(row.get("qty_able", row.get("qty")))
            allocated = number(row.get("qty_alloc"))
            frozen = number(row.get("qty_freeze"))
            self.upsert_returning("""
                INSERT INTO inv_balance(company_id,warehouse_id,owner_id,location_id,good_id,batch_code,quality_type,supplier_code,product_date,expire_date,lpn,available_qty,allocated_qty,frozen_qty)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                ON CONFLICT(warehouse_id,owner_id,location_id,good_id,batch_code,quality_type,supplier_code,lpn)
                DO UPDATE SET available_qty=EXCLUDED.available_qty,allocated_qty=EXCLUDED.allocated_qty,
                  frozen_qty=EXCLUDED.frozen_qty,product_date=EXCLUDED.product_date,expire_date=EXCLUDED.expire_date,
                  updated_at=CURRENT_TIMESTAMP RETURNING id
            """, (self.company_id, warehouse, owner, location, good, text(row.get("batch_code"), "-", 64), quality,
                   text(row.get("supplier_code"), "-", 64), row.get("product_date"), row.get("expire_date"),
                   text(row.get("lpn"), "-", 64), available, allocated, frozen))
            self.stats.bump("written", "inv_balance")
        for row in self.source_rows("inv_rfid"):
            warehouse, owner = self.warehouse(row.get("warehouse_code")), self.owner(row.get("owner_code"))
            if not warehouse or not owner: self.stats.bump("skipped", "inv_rfid"); continue
            location = self.target_id("wms_location", text(row.get("location_code"), "STAGE", 64), warehouse=warehouse)
            good = self.target_id("wms_good", text(row.get("sku"), limit=64), owner=owner)
            if not location or not good: self.stats.bump("skipped", "inv_rfid"); continue
            with self.target.cursor() as cursor:
                cursor.execute("SELECT id FROM inv_balance WHERE warehouse_id=%s AND owner_id=%s AND location_id=%s AND good_id=%s ORDER BY id LIMIT 1", (warehouse, owner, location, good))
                balance = cursor.fetchone()
            serial = text(row.get("rfid"), limit=128)
            if not balance or not serial: self.stats.bump("skipped", "inv_rfid"); continue
            self.upsert_returning("""INSERT INTO inv_serial(company_id,warehouse_id,owner_id,balance_id,good_id,location_id,serial_code,quantity,weight_kg,source,state,printed)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                ON CONFLICT(company_id,serial_code) DO UPDATE SET balance_id=EXCLUDED.balance_id,good_id=EXCLUDED.good_id,
                  location_id=EXCLUDED.location_id,quantity=EXCLUDED.quantity,weight_kg=EXCLUDED.weight_kg,
                  state=EXCLUDED.state,printed=EXCLUDED.printed,updated_at=CURRENT_TIMESTAMP RETURNING id""",
                (self.company_id, warehouse, owner, int(first_value(balance)), good, location, serial, max(number(row.get("qty"), "1"), Decimal("0.001")),
                 number(row.get("weight")), text(row.get("source"), "LEGACY", 16).upper(), "ACTIVE" if text(row.get("state"), "on") == "on" else "VOID", boolean(row.get("printed"))))
            self.stats.bump("written", "inv_serial")

    def migrate_orders(self) -> None:
        self._orders(True)
        self._orders(False)

    def _orders(self, inbound: bool) -> None:
        header_table, line_table = ("i_stockin", "i_stockin_line") if inbound else ("o_stockout", "o_stockout_line")
        target_header, target_line = ("stockin_order", "stockin_order_line") if inbound else ("stockout_order", "stockout_order_line")
        fk_name = "stockin_id" if inbound else "stockout_id"
        for row in self.source_rows(header_table):
            warehouse, owner = self.warehouse(row.get("warehouse_code")), self.owner(row.get("owner_code"))
            if not warehouse or not owner: self.stats.bump("skipped", header_table); continue
            order_code = text(row.get("order_code"), f"LEGACY-{row['id']}", 64)
            partner = self.target_id("wms_partner", text(row.get("partner_code"), limit=64)) if row.get("partner_code") else None
            source = text(row.get("source"), "custom").upper(); source = source if source in ({"ERP","CUSTOM","IMPORT","QUICK"} if inbound else {"ERP","CUSTOM","IMPORT"}) else "CUSTOM"
            state = enum(row.get("state"), IN_STATES if inbound else OUT_STATES, "DRAFT")
            if inbound:
                header_id = self.upsert_returning("""INSERT INTO stockin_order(company_id,warehouse_id,owner_id,order_code,external_order_code,related_order_code,inbound_type,source,state,partner_id,planned_date,total_amount,received_at,finished_at,created_by,remark,idempotency_key,created_at,updated_at)
                    VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,COALESCE(%s,CURRENT_TIMESTAMP),COALESCE(%s,CURRENT_TIMESTAMP))
                    ON CONFLICT(company_id,order_code) DO UPDATE SET state=EXCLUDED.state,partner_id=EXCLUDED.partner_id,planned_date=EXCLUDED.planned_date,total_amount=EXCLUDED.total_amount,remark=EXCLUDED.remark,updated_at=EXCLUDED.updated_at RETURNING id""",
                    (self.company_id,warehouse,owner,order_code,text(row.get("erp_order_code"),limit=64) or None,text(row.get("middle_order_code"),limit=64) or None,enum(row.get("xtype"),INBOUND_TYPES,"NORMAL"),source,state,partner,row.get("date_planned"),number(row.get("price")),row.get("date_finished") if state != "DRAFT" else None,row.get("date_finished"),self.admin_id,text(row.get("remark"),"旧系统迁移",256),f"legacy:stockin:{row['id']}",row.get("create_time"),row.get("update_time")))
            else:
                header_id = self.upsert_returning("""INSERT INTO stockout_order(company_id,warehouse_id,owner_id,order_code,external_order_code,related_order_code,outbound_type,source,state,allocation_state,pick_state,ship_state,partner_id,planned_date,receiver_name,receiver_phone,receiver_address,carrier_code,tracking_code,total_amount,completed_at,created_by,remark,idempotency_key,created_at,updated_at)
                    VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,COALESCE(%s,CURRENT_TIMESTAMP),COALESCE(%s,CURRENT_TIMESTAMP))
                    ON CONFLICT(company_id,order_code) DO UPDATE SET state=EXCLUDED.state,partner_id=EXCLUDED.partner_id,planned_date=EXCLUDED.planned_date,total_amount=EXCLUDED.total_amount,remark=EXCLUDED.remark,updated_at=EXCLUDED.updated_at RETURNING id""",
                    (self.company_id,warehouse,owner,order_code,text(row.get("erp_order_code"),limit=64) or None,text(row.get("middle_order_code"),limit=64) or None,enum(row.get("xtype") or row.get("order_type"),OUTBOUND_TYPES,"NORMAL"),source,state,"FULL" if state=="COMPLETED" else "NO","FULL" if state=="COMPLETED" else "NO","FULL" if state=="COMPLETED" else "NO",partner,row.get("date_planned"),text(row.get("receiver_name"),limit=64),text(row.get("receiver_tel"),limit=32),text(row.get("receiver_address"),limit=256),text(row.get("express_code"),limit=64),text(row.get("bill_code"),limit=128),number(row.get("price")),row.get("date_finished"),self.admin_id,text(row.get("remark"),"旧系统迁移",256),f"legacy:stockout:{row['id']}",row.get("create_time"),row.get("update_time")))
            self.stats.bump("written", target_header)
            lines = self.source_rows(line_table, f"{fk_name}=%s", (row["id"],))
            for index, line in enumerate(lines, 1):
                good = self.target_id("wms_good", text(line.get("sku"), limit=64), owner=owner)
                qty = number(line.get("qty"))
                if not good or qty <= 0: self.stats.bump("skipped", line_table); continue
                line_no = int(number(line.get("lineno"), str(index))) or index
                quality = text(line.get("quality_type"), "ZP").upper(); quality = quality if quality in QUALITY else "ZP"
                if inbound:
                    location = self.target_id("wms_location", text(line.get("location_code"), "STAGE", 64), warehouse=warehouse)
                    with self.target.cursor() as cursor:
                        cursor.execute("SELECT 1 FROM stockin_order_line WHERE order_id=%s AND line_no=%s", (header_id,line_no))
                        if not cursor.fetchone():
                            cursor.execute("""INSERT INTO stockin_order_line(order_id,line_no,good_id,planned_qty,received_qty,preferred_location_id,supplier_code,quality_type,product_date,expire_date,batch_code,unit_price,remark)
                                VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
                                (header_id,line_no,good,qty,min(number(line.get("qty_real")),qty),location,text(line.get("supplier_code"),"-",64),quality,line.get("product_date"),line.get("expire_date"),text(line.get("batch_code"),"-",64),number(line.get("price")),text(line.get("remark"),"旧系统迁移",256)))
                else:
                    allocated = min(number(line.get("qty_alloc")), qty)
                    picked = min(number(line.get("qty_pick")), allocated)
                    shipped = min(number(line.get("qty_ship")), picked)
                    with self.target.cursor() as cursor:
                        cursor.execute("SELECT 1 FROM stockout_order_line WHERE order_id=%s AND line_no=%s", (header_id,line_no))
                        if not cursor.fetchone():
                            cursor.execute("""INSERT INTO stockout_order_line(order_id,line_no,good_id,planned_qty,allocated_qty,picked_qty,shipped_qty,supplier_code,quality_type,batch_code,unit_price,remark)
                                VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
                                (header_id,line_no,good,qty,allocated,picked,shipped,text(line.get("supplier_code"),"-",64),quality,text(line.get("batch_code"),"-",64),number(line.get("price")),text(line.get("remark"),"旧系统迁移",256)))
                self.stats.bump("written", target_line)

    def migrate_finance(self) -> None:
        for row in self.source_rows("f_money_account"):
            warehouse, owner = self.warehouse(row.get("warehouse_code")), self.owner(row.get("owner_code"))
            if not warehouse or not owner: self.stats.bump("skipped", "f_money_account"); continue
            code = text(row.get("code") or row.get("account"), f"legacy-{row['id']}", 100)
            self.upsert_returning("""INSERT INTO finance_account(company_id,warehouse_id,owner_id,organ,name,account_no,long_name,remark)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT(company_id,warehouse_id,owner_id,long_name)
                DO UPDATE SET name=EXCLUDED.name,account_no=EXCLUDED.account_no,remark=EXCLUDED.remark,updated_at=CURRENT_TIMESTAMP RETURNING id""",
                (self.company_id,warehouse,owner,text(row.get("organ"),"旧系统",100),text(row.get("name"),code,100),code,text(row.get("long_name") or row.get("longname"),code,320),text(row.get("remark"),"旧系统迁移",256)))
            self.stats.bump("written", "finance_account")
        for row in self.source_rows("f_money"):
            warehouse, owner = self.warehouse(row.get("warehouse_code")), self.owner(row.get("owner_code"))
            if not warehouse or not owner: self.stats.bump("skipped", "f_money"); continue
            code = text(row.get("code"), f"LEGACY-F-{row['id']}", 64)
            legacy_type = text(row.get("xtype") or row.get("direction")).lower()
            direction = "INCOME" if legacy_type in {"stockout","in","income","receive","receipt"} else "OUTCOME"
            amount = number(row.get("amount", row.get("price")))
            paid = min(number(row.get("real", row.get("amount_real", row.get("paid_amount")))), amount)
            bad_debt = min(number(row.get("bad")), max(amount-paid, Decimal("0")))
            state = {"create":"DRAFT","doing":"PROCESSING","done":"COMPLETED","partdone":"PART_COMPLETED","cancel":"CANCELLED"}.get(text(row.get("state")).lower(), "DRAFT")
            with self.target.cursor() as cursor:
                cursor.execute("SELECT 1 FROM finance_money WHERE company_id=%s AND money_code=%s", (self.company_id,code))
                if not cursor.fetchone():
                    cursor.execute("""INSERT INTO finance_money(company_id,warehouse_id,owner_id,money_code,related_order_code,direction,fee_type,partner_code,partner_name,pay_type,amount,paid_amount,bad_debt_amount,state,accounting_date,finished_at,created_by,remark,idempotency_key,created_at,updated_at)
                        VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,COALESCE(%s,CURRENT_DATE),%s,%s,%s,%s,COALESCE(%s,CURRENT_TIMESTAMP),COALESCE(%s,CURRENT_TIMESTAMP))""",
                        (self.company_id,warehouse,owner,code,text(row.get("subcode"),limit=64),direction,("STOCK_OUT" if direction=="INCOME" else "STOCK_IN"),text(row.get("partner_code"),limit=64),text(row.get("partner_name"),limit=120),text(row.get("pay_type"),limit=32),amount,paid,bad_debt,state,row.get("date_forcount") or row.get("create_time"),row.get("date_finished"),self.admin_id,text(row.get("remark"),"旧系统迁移",256),f"legacy:money:{row['id']}",row.get("create_time"),row.get("update_time")))
            self.stats.bump("written", "finance_money")

    def grant_admin_scope(self) -> None:
        with self.target.cursor() as cursor:
            cursor.execute("INSERT INTO auth_user_warehouse(user_id,warehouse_id) SELECT %s,w.id FROM wms_warehouse w WHERE w.company_id=%s AND NOT EXISTS(SELECT 1 FROM auth_user_warehouse x WHERE x.user_id=%s AND x.warehouse_id=w.id)", (self.admin_id,self.company_id,self.admin_id))
            cursor.execute("INSERT INTO auth_user_owner(user_id,owner_id) SELECT %s,o.id FROM wms_owner o WHERE o.company_id=%s AND NOT EXISTS(SELECT 1 FROM auth_user_owner x WHERE x.user_id=%s AND x.owner_id=o.id)", (self.admin_id,self.company_id,self.admin_id))

    def archive_legacy_history(self) -> None:
        for table in ARCHIVE_TABLES:
            columns = table_columns(self.source, self.source.db.decode() if isinstance(self.source.db, bytes) else self.source.db, table)
            if not columns:
                self.stats.warnings.append(f"可选历史表不存在：{table}")
                continue
            where = "company_code=%s" if "company_code" in columns else "1=1"
            params: tuple[Any, ...] = (self.source_company,) if "company_code" in columns else ()
            rows = self.source_rows(table, where, params, track=False)
            for index, row in enumerate(rows, 1):
                source_pk = text(row.get("id"), str(index), 128)
                payload = json.dumps(redact(row), ensure_ascii=False, sort_keys=True, default=str)
                with self.target.cursor() as cursor:
                    cursor.execute("SELECT 1 FROM legacy_archive_record WHERE company_id=%s AND source_table=%s AND source_pk=%s", (self.company_id,table,source_pk))
                    if not cursor.fetchone():
                        cursor.execute("INSERT INTO legacy_archive_record(company_id,source_table,source_pk,source_json) VALUES(%s,%s,%s,%s)", (self.company_id,table,source_pk,payload))
                self.stats.bump("written", "legacy_archive_record")


def table_columns(connection: Any, database: str, table: str) -> set[str]:
    if getattr(connection, "dialect", "mysql") == "sqlite":
        schema = "auth" if table in {"u_company", "u_user"} else "main"
        rows = connection.connection.execute(f'PRAGMA {schema}.table_info("{table}")').fetchall()
        return {row[1] for row in rows}
    with connection.cursor() as cursor:
        cursor.execute("SELECT column_name FROM information_schema.columns WHERE table_schema=%s AND table_name=%s", (database, table))
        return {row["column_name"] for row in cursor.fetchall()}


def preflight(source: Any, database: str) -> list[str]:
    problems: list[str] = []
    for table, required in REQUIRED_TABLES.items():
        actual = table_columns(source, database, table)
        if not actual:
            problems.append(f"缺少旧表 {table}")
        else:
            missing = sorted(required - actual)
            if missing:
                problems.append(f"旧表 {table} 缺少字段：{','.join(missing)}")
    return problems


def connect_source(args: argparse.Namespace) -> Any:
    if args.source_type == "sqlite" or (args.source_type == "auto" and args.source_sqlite_business):
        return SQLiteSourceAdapter(args.source_sqlite_business, args.source_sqlite_auth)
    return pymysql.connect(host=args.source_host, port=args.source_port, user=args.source_user,
                           password=args.source_password, database=args.source_database,
                           charset="utf8mb4", cursorclass=DictCursor, autocommit=False)


def connect_target(args: argparse.Namespace) -> Any:
    return psycopg2.connect(host=args.target_host, port=args.target_port, user=args.target_user,
                            password=args.target_password, dbname=args.target_database,
                            cursor_factory=RealDictCursor)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="MT-WMS 旧 MySQL/SQLite 数据迁移到 openGauss")
    parser.add_argument("mode", choices=("precheck", "execute", "verify"))
    parser.add_argument("--source-type", choices=("auto","mysql","sqlite"), default="auto")
    parser.add_argument("--source-sqlite-business", default=os.getenv("LEGACY_SQLITE_BUSINESS", ""))
    parser.add_argument("--source-sqlite-auth", default=os.getenv("LEGACY_SQLITE_AUTH", ""))
    parser.add_argument("--source-host", default=os.getenv("LEGACY_MYSQL_HOST", "127.0.0.1"))
    parser.add_argument("--source-port", type=int, default=int(os.getenv("LEGACY_MYSQL_PORT", "3306")))
    parser.add_argument("--source-database", default=os.getenv("LEGACY_MYSQL_DATABASE", "wmsbase"))
    parser.add_argument("--source-user", default=os.getenv("LEGACY_MYSQL_USER", "wms"))
    parser.add_argument("--source-password", default=os.getenv("LEGACY_MYSQL_PASSWORD", ""))
    parser.add_argument("--source-company", default=os.getenv("LEGACY_COMPANY_CODE", "default"))
    parser.add_argument("--target-host", default=os.getenv("WMS_DB_HOST", "127.0.0.1"))
    parser.add_argument("--target-port", type=int, default=int(os.getenv("WMS_DB_PORT", "5432")))
    parser.add_argument("--target-database", default=os.getenv("GS_DB", "mt_wms"))
    parser.add_argument("--target-user", default=os.getenv("WMS_DB_USERNAME", "wms_app"))
    parser.add_argument("--target-password", default=os.getenv("WMS_DB_PASSWORD", ""))
    parser.add_argument("--target-company", default=os.getenv("TARGET_COMPANY_CODE", "default"))
    parser.add_argument("--run-code", default=f"MIG-{datetime.now():%Y%m%d%H%M%S}-{uuid.uuid4().hex[:6]}")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    started = time.perf_counter()
    source = connect_source(args)
    try:
        problems = preflight(source, args.source_database)
        fingerprint = source_fingerprint(source, args.source_database)
        if problems:
            print(json.dumps({"ok": False, "mode": args.mode, "sourceFingerprint": fingerprint, "problems": problems}, ensure_ascii=False, indent=2))
            return 2
        if args.mode == "precheck":
            print(json.dumps({"ok": True, "mode": "precheck", "sourceFingerprint": fingerprint, "requiredTables": len(REQUIRED_TABLES)}, ensure_ascii=False, indent=2))
            return 0
        target = connect_target(args)
        try:
            if args.mode == "verify":
                with target.cursor() as cursor:
                    cursor.execute("SELECT statistics FROM legacy_migration_run WHERE source_fingerprint=%s AND target_company_code=%s AND state='SUCCEEDED' ORDER BY id DESC LIMIT 1", (fingerprint,args.target_company))
                    prior = cursor.fetchone()
                result = {"ok": bool(prior), "mode":"verify", "sourceFingerprint":fingerprint, "lastStatistics": json.loads(prior["statistics"]) if prior and prior["statistics"] else None}
                print(json.dumps(result, ensure_ascii=False, indent=2, default=str)); return 0 if prior else 3
            with target.cursor() as cursor:
                cursor.execute("INSERT INTO legacy_migration_run(run_code,source_name,source_fingerprint,target_company_code,mode,state) VALUES(%s,%s,%s,%s,'EXECUTE','RUNNING') RETURNING id", (args.run_code,args.source_database,fingerprint,args.target_company))
                audit_id = cursor.fetchone()["id"]
            target.commit()
            try:
                stats = Migrator(source,target,args.source_company,args.target_company,args.run_code).migrate()
                with target.cursor() as cursor:
                    cursor.execute("UPDATE legacy_migration_run SET state='SUCCEEDED',statistics=%s,completed_at=CURRENT_TIMESTAMP WHERE id=%s", (stats.json(),audit_id))
                target.commit()
                print(json.dumps({"ok":True,"mode":"execute","runCode":args.run_code,"sourceFingerprint":fingerprint,"elapsedSeconds":round(time.perf_counter()-started,3),"statistics":stats.__dict__}, ensure_ascii=False, indent=2, default=str))
                return 0
            except Exception as error:
                target.rollback()
                with target.cursor() as cursor:
                    cursor.execute("UPDATE legacy_migration_run SET state='FAILED',error_message=%s,completed_at=CURRENT_TIMESTAMP WHERE id=%s", (text(error,limit=2000),audit_id))
                target.commit(); raise
        finally:
            target.close()
    finally:
        source.close()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(json.dumps({"ok":False,"error":str(error)}, ensure_ascii=False, indent=2), file=sys.stderr)
        raise SystemExit(1)
