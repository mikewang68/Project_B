#!/usr/bin/env python3
"""Generate reproducible phase-0 inventories from the legacy MT-WMS code.

This tool is intentionally read-only for the legacy projects. It writes CSV and
JSON evidence into ``mt-wms-rebuild/docs/phase-0/generated``.
"""

from __future__ import annotations

import ast
import csv
import json
import re
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Iterable


PROJECT_ROOT = Path(__file__).resolve().parents[2]
BACKEND_ROOT = PROJECT_ROOT / "mt-wms-demo"
FRONTEND_ROOT = PROJECT_ROOT / "mt-wms-demo-frontend"
OUTPUT_ROOT = PROJECT_ROOT / "mt-wms-rebuild" / "docs" / "phase-0" / "generated"

TENANT_FIELDS = {"company_code", "warehouse_code", "owner_code", "factory_code"}
STATE_FIELD_RE = re.compile(r"^(state|status)(?:_|$)")


@dataclass(frozen=True)
class ModelRecord:
    module: str
    class_name: str
    table_name: str
    bind_key: str
    line: int
    column_count: int
    columns: str
    tenant_fields: str
    state_fields: str


@dataclass(frozen=True)
class ApiRecord:
    module: str
    blueprint: str
    route: str
    full_path: str
    methods: str
    function_name: str
    auth_guards: str
    line: int


@dataclass(frozen=True)
class PageRecord:
    path: str
    name: str
    component: str
    component_file: str
    domain: str


@dataclass(frozen=True)
class FrontendApiRecord:
    source_file: str
    line: int
    method: str
    path_expression: str
    first_segment: str
    legacy_backend_match: str


def source_text(path: Path) -> str:
    return path.read_text(encoding="utf-8-sig")


def literal_string(node: ast.AST | None, default: str = "") -> str:
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    return default


def dotted_name(node: ast.AST) -> str:
    parts: list[str] = []
    current: ast.AST | None = node
    while isinstance(current, ast.Attribute):
        parts.append(current.attr)
        current = current.value
    if isinstance(current, ast.Name):
        parts.append(current.id)
    return ".".join(reversed(parts))


def call_is_column(node: ast.AST) -> bool:
    return isinstance(node, ast.Call) and dotted_name(node.func).endswith(".Column")


def column_type(node: ast.Call) -> str:
    if not node.args:
        return "Unknown"
    arg = node.args[0]
    if isinstance(arg, ast.Call):
        base = dotted_name(arg.func).split(".")[-1]
        enum_values = [literal_string(item) for item in arg.args]
        enum_values = [value for value in enum_values if value]
        if base == "Enum" and enum_values:
            return f"Enum({','.join(enum_values)})"
        if arg.args and isinstance(arg.args[0], ast.Constant):
            return f"{base}({arg.args[0].value})"
        return base
    return dotted_name(arg).split(".")[-1] or type(arg).__name__


def parse_models() -> tuple[list[ModelRecord], list[dict[str, Any]]]:
    models: list[ModelRecord] = []
    columns_out: list[dict[str, Any]] = []
    for path in sorted((BACKEND_ROOT / "models").glob("*.py")):
        tree = ast.parse(source_text(path), filename=str(path))
        for node in tree.body:
            if not isinstance(node, ast.ClassDef):
                continue
            if not any(dotted_name(base).endswith("db.Model") for base in node.bases):
                continue
            table_name = ""
            bind_key = "default"
            columns: list[dict[str, Any]] = []
            for item in node.body:
                if not isinstance(item, (ast.Assign, ast.AnnAssign)):
                    continue
                targets = item.targets if isinstance(item, ast.Assign) else [item.target]
                value = item.value
                for target in targets:
                    if not isinstance(target, ast.Name):
                        continue
                    if target.id == "__tablename__":
                        table_name = literal_string(value)
                    elif target.id == "__bind_key__":
                        bind_key = literal_string(value, "default")
                    elif call_is_column(value):
                        assert isinstance(value, ast.Call)
                        enum_values: list[str] = []
                        if value.args and isinstance(value.args[0], ast.Call):
                            type_call = value.args[0]
                            if dotted_name(type_call.func).endswith(".Enum"):
                                enum_values = [literal_string(v) for v in type_call.args]
                                enum_values = [v for v in enum_values if v]
                        columns.append(
                            {
                                "module": path.name,
                                "class_name": node.name,
                                "table_name": table_name,
                                "column": target.id,
                                "type": column_type(value),
                                "enum_values": "|".join(enum_values),
                                "primary_key": any(
                                    kw.arg == "primary_key" and isinstance(kw.value, ast.Constant) and kw.value.value is True
                                    for kw in value.keywords
                                ),
                                "nullable_declared": next(
                                    (
                                        str(kw.value.value)
                                        for kw in value.keywords
                                        if kw.arg == "nullable" and isinstance(kw.value, ast.Constant)
                                    ),
                                    "",
                                ),
                                "line": item.lineno,
                            }
                        )
            names = [column["column"] for column in columns]
            tenant = sorted(TENANT_FIELDS.intersection(names))
            state_fields = sorted(name for name in names if STATE_FIELD_RE.match(name))
            models.append(
                ModelRecord(
                    module=path.name,
                    class_name=node.name,
                    table_name=table_name,
                    bind_key=bind_key,
                    line=node.lineno,
                    column_count=len(columns),
                    columns="|".join(names),
                    tenant_fields="|".join(tenant),
                    state_fields="|".join(state_fields),
                )
            )
            columns_out.extend(columns)
    return models, columns_out


def parse_model_relations() -> list[dict[str, Any]]:
    """Extract explicit SQLAlchemy foreign keys from the legacy models."""
    records: list[dict[str, Any]] = []
    for path in sorted((BACKEND_ROOT / "models").glob("*.py")):
        tree = ast.parse(source_text(path), filename=str(path))
        for node in tree.body:
            if not isinstance(node, ast.ClassDef):
                continue
            table_name = ""
            for item in node.body:
                if isinstance(item, ast.Assign):
                    for target in item.targets:
                        if isinstance(target, ast.Name) and target.id == "__tablename__":
                            table_name = literal_string(item.value)
                if not isinstance(item, (ast.Assign, ast.AnnAssign)):
                    continue
                targets = item.targets if isinstance(item, ast.Assign) else [item.target]
                value = item.value
                if not call_is_column(value):
                    continue
                assert isinstance(value, ast.Call)
                column = next((target.id for target in targets if isinstance(target, ast.Name)), "")
                for arg in value.args:
                    if not isinstance(arg, ast.Call) or not dotted_name(arg.func).endswith(".ForeignKey"):
                        continue
                    reference = literal_string(arg.args[0]) if arg.args else ""
                    target_table, _, target_column = reference.partition(".")
                    records.append(
                        {
                            "module": path.name,
                            "source_class": node.name,
                            "source_table": table_name,
                            "source_column": column,
                            "target_table": target_table,
                            "target_column": target_column,
                            "line": item.lineno,
                        }
                    )
    return records


def object_literal_body(text: str, key: str) -> str:
    match = re.search(rf"\b{re.escape(key)}\s*:\s*\{{", text)
    if not match:
        return ""
    depth = 1
    index = match.end()
    while index < len(text) and depth:
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
        index += 1
    return text[match.end() : index - 1]


def parse_frontend_registries() -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    """Extract legacy menu, feature-toggle and role evidence from App.vue."""
    app_path = FRONTEND_ROOT / "src" / "App.vue"
    text = source_text(app_path)
    menus: list[dict[str, Any]] = []
    menu_labels = {
        key: label
        for key, label in re.findall(r"\b(m_[A-Za-z0-9_]+)\s*:\s*['\"]([^'\"]+)['\"]", object_literal_body(text, "menus"))
    }
    menu_template_re = re.compile(
        r"<a-menu-item[^>]*?(?:v-if=\"(?P<condition>[^\"]+)\")?[^>]*>.*?"
        r"<router-link\s+:to=\"\{\s*name:\s*'(?P<route>[^']+)'\s*\}\"[^>]*>(?P<label>.*?)</router-link>",
        re.DOTALL,
    )
    for match in menu_template_re.finditer(text):
        label = re.sub(r"<[^>]+>", "", match.group("label")).strip()
        condition = (match.group("condition") or "").strip()
        permission_codes = sorted(set(re.findall(r"vmenus\.(m_[A-Za-z0-9_]+)", condition)))
        menus.append(
            {
                "route_name": match.group("route"),
                "label": label,
                "permission_codes": "|".join(permission_codes),
                "visibility_condition": condition,
                "source_file": "src/App.vue",
                "line": text.count("\n", 0, match.start()) + 1,
            }
        )
    for code, label in sorted(menu_labels.items()):
        if not any(code in record["permission_codes"].split("|") for record in menus):
            menus.append(
                {
                    "route_name": "",
                    "label": label,
                    "permission_codes": code,
                    "visibility_condition": "declared_only",
                    "source_file": "src/App.vue",
                    "line": text.count("\n", 0, text.find(code)) + 1,
                }
            )

    configs: list[dict[str, Any]] = []
    config_labels = {
        key: label
        for key, label in re.findall(r"\b([A-Za-z][A-Za-z0-9_]+)\s*:\s*['\"]([^'\"]+)['\"]", object_literal_body(text, "conf_code"))
    }
    owner_defaults = object_literal_body(text, "owner_conf")
    for key, raw_value in re.findall(r"\b([A-Za-z][A-Za-z0-9_]+)\s*:\s*([^,\n}]+)", owner_defaults):
        configs.append(
            {
                "config_key": key,
                "legacy_label": config_labels.get(key.removeprefix("is_"), ""),
                "default_value": raw_value.strip(),
                "source_file": "src/App.vue",
            }
        )

    permissions: list[dict[str, Any]] = []
    permission_path = BACKEND_ROOT / "extensions" / "permissions.py"
    permission_text = source_text(permission_path)
    for role, inherited in re.findall(r"['\"]([A-Za-z0-9_]+)['\"]\s*:\s*\(([^)]*)\)", permission_text):
        permissions.append(
            {
                "role": role,
                "inherits_or_grants": "|".join(re.findall(r"['\"]([A-Za-z0-9_]+)['\"]", inherited)),
                "source_file": "extensions/permissions.py",
                "line": permission_text.count("\n", 0, permission_text.find(f"'{role}'")) + 1,
            }
        )
    return menus, configs, permissions


def parse_blueprint_prefixes() -> dict[str, str]:
    tree = ast.parse(source_text(BACKEND_ROOT / "app.py"))
    result: dict[str, str] = {}
    for node in tree.body:
        if not isinstance(node, ast.Assign):
            continue
        if not any(isinstance(target, ast.Name) and target.id == "_BLUEPRINTS" for target in node.targets):
            continue
        if not isinstance(node.value, (ast.Tuple, ast.List)):
            continue
        for item in node.value.elts:
            if isinstance(item, ast.Tuple) and len(item.elts) == 2 and isinstance(item.elts[0], ast.Name):
                result[item.elts[0].id] = literal_string(item.elts[1])
    return result


def normalized_path(prefix: str, route: str) -> str:
    combined = f"/{prefix.strip('/')}/{route.strip('/')}"
    return re.sub(r"/+", "/", combined).rstrip("/") or "/"


def decorator_methods(call: ast.Call) -> list[str]:
    for keyword in call.keywords:
        if keyword.arg != "methods" or not isinstance(keyword.value, (ast.List, ast.Tuple)):
            continue
        return [literal_string(item).upper() for item in keyword.value.elts if literal_string(item)]
    return ["GET"]


def parse_apis() -> list[ApiRecord]:
    prefixes = parse_blueprint_prefixes()
    records: list[ApiRecord] = []
    for path in sorted((BACKEND_ROOT / "blueprints").rglob("*.py")):
        tree = ast.parse(source_text(path), filename=str(path))
        for node in ast.walk(tree):
            if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                continue
            guard_names: list[str] = []
            route_calls: list[ast.Call] = []
            for decorator in node.decorator_list:
                call = decorator if isinstance(decorator, ast.Call) else None
                name = dotted_name(call.func if call else decorator)
                if name.endswith(".route") and call:
                    route_calls.append(call)
                elif any(token in name.lower() for token in ("login", "permission", "role", "auth")):
                    guard_names.append(name)
            for call in route_calls:
                blueprint = dotted_name(call.func).split(".")[0]
                route = literal_string(call.args[0]) if call.args else ""
                records.append(
                    ApiRecord(
                        module=path.relative_to(BACKEND_ROOT).as_posix(),
                        blueprint=blueprint,
                        route=route or "/",
                        full_path=normalized_path(prefixes.get(blueprint, ""), route),
                        methods="|".join(decorator_methods(call)),
                        function_name=node.name,
                        auth_guards="|".join(sorted(set(guard_names))),
                        line=call.lineno,
                    )
                )
    return sorted(records, key=lambda r: (r.full_path, r.module, r.line))


def parse_pages() -> list[PageRecord]:
    router_path = FRONTEND_ROOT / "src" / "router" / "index.js"
    text = source_text(router_path)
    imports = dict(
        re.findall(r"import\s+(\w+)\s+from\s+['\"](@/components/[^'\"]+)['\"]", text)
    )
    block_re = re.compile(
        r"\{\s*path:\s*['\"](?P<path>[^'\"]+)['\"]\s*,.*?"
        r"name:\s*['\"](?P<name>[^'\"]+)['\"]\s*,.*?"
        r"component:\s*(?P<component>\w+)",
        re.DOTALL,
    )
    records: list[PageRecord] = []
    for match in block_re.finditer(text):
        component = match.group("component")
        import_path = imports.get(component, "")
        domain = match.group("path").strip("/").split("/", 1)[0] or "home"
        records.append(
            PageRecord(
                path=match.group("path"),
                name=match.group("name"),
                component=component,
                component_file=(import_path.replace("@/", "src/") + ".vue") if import_path else "",
                domain=domain,
            )
        )
    return records


def first_path_segment(expression: str) -> str:
    match = re.search(r"['\"]/(\w+)", expression)
    return match.group(1) if match else "dynamic"


def parse_frontend_api_usage(api_records: Iterable[ApiRecord]) -> list[FrontendApiRecord]:
    backend_first_segments = {record.full_path.strip("/").split("/", 1)[0] for record in api_records}
    call_re = re.compile(r"\$http\.(get|post|put|patch|delete)\s*\(([^\n;]+)", re.IGNORECASE)
    records: list[FrontendApiRecord] = []
    for path in sorted((FRONTEND_ROOT / "src").rglob("*")):
        if path.suffix.lower() not in {".vue", ".js"}:
            continue
        text = source_text(path)
        for match in call_re.finditer(text):
            expression = match.group(2).strip()
            segment = first_path_segment(expression)
            line = text.count("\n", 0, match.start()) + 1
            records.append(
                FrontendApiRecord(
                    source_file=path.relative_to(FRONTEND_ROOT).as_posix(),
                    line=line,
                    method=match.group(1).upper(),
                    path_expression=expression[:500],
                    first_segment=segment,
                    legacy_backend_match="yes" if segment in backend_first_segments else "no_or_dynamic",
                )
            )
    return records


def parse_state_literals() -> list[dict[str, Any]]:
    result: set[tuple[str, int, str, str, str]] = set()
    roots = [BACKEND_ROOT / "models", BACKEND_ROOT / "blueprints"]
    for root in roots:
        for path in sorted(root.rglob("*.py")):
            tree = ast.parse(source_text(path), filename=str(path))
            for node in ast.walk(tree):
                if isinstance(node, ast.Compare):
                    candidates = [node.left, *node.comparators]
                    fields = [dotted_name(item).split(".")[-1] for item in candidates]
                    values = [literal_string(item) for item in candidates]
                    for field in fields:
                        if STATE_FIELD_RE.match(field):
                            for value in values:
                                if value:
                                    result.add((path.relative_to(BACKEND_ROOT).as_posix(), node.lineno, field, value, "compare"))
                elif isinstance(node, (ast.Assign, ast.AnnAssign)):
                    targets = node.targets if isinstance(node, ast.Assign) else [node.target]
                    value = literal_string(node.value)
                    if not value:
                        continue
                    for target in targets:
                        field = dotted_name(target).split(".")[-1]
                        if STATE_FIELD_RE.match(field):
                            result.add((path.relative_to(BACKEND_ROOT).as_posix(), node.lineno, field, value, "assign"))
    return [
        {"source_file": file, "line": line, "field": field, "value": value, "usage": usage}
        for file, line, field, value, usage in sorted(result)
    ]


def write_csv(path: Path, records: Iterable[Any], fields: list[str] | None = None) -> None:
    records = list(records)
    path.parent.mkdir(parents=True, exist_ok=True)
    if records and hasattr(records[0], "__dataclass_fields__"):
        rows = [asdict(record) for record in records]
    else:
        rows = records
    fieldnames = fields or (list(rows[0].keys()) if rows else [])
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    models, columns = parse_models()
    relations = parse_model_relations()
    apis = parse_apis()
    pages = parse_pages()
    frontend_apis = parse_frontend_api_usage(apis)
    states = parse_state_literals()
    menus, configs, permissions = parse_frontend_registries()

    write_csv(OUTPUT_ROOT / "model-inventory.csv", models)
    write_csv(OUTPUT_ROOT / "model-column-inventory.csv", columns)
    write_csv(OUTPUT_ROOT / "model-relation-inventory.csv", relations)
    write_csv(OUTPUT_ROOT / "api-inventory.csv", apis)
    write_csv(OUTPUT_ROOT / "page-route-inventory.csv", pages)
    write_csv(OUTPUT_ROOT / "frontend-api-usage.csv", frontend_apis)
    write_csv(OUTPUT_ROOT / "state-literals.csv", states)
    write_csv(OUTPUT_ROOT / "menu-inventory.csv", menus)
    write_csv(OUTPUT_ROOT / "config-inventory.csv", configs)
    write_csv(OUTPUT_ROOT / "permission-inventory.csv", permissions)

    api_domain_counts = Counter(record.full_path.strip("/").split("/", 1)[0] or "root" for record in apis)
    page_domain_counts = Counter(record.domain for record in pages)
    frontend_api_domain_counts = Counter(record.first_segment for record in frontend_apis)
    unimplemented_segments = Counter(
        record.first_segment for record in frontend_apis if record.legacy_backend_match != "yes"
    )
    enum_states: dict[str, set[str]] = defaultdict(set)
    for column in columns:
        if column["enum_values"] and STATE_FIELD_RE.match(column["column"]):
            enum_states[column["column"]].update(column["enum_values"].split("|"))

    summary = {
        "source": {
            "backend": str(BACKEND_ROOT),
            "frontend": str(FRONTEND_ROOT),
        },
        "counts": {
            "models": len(models),
            "model_columns": len(columns),
            "explicit_model_relations": len(relations),
            "api_route_decorators": len(apis),
            "frontend_routes": len(pages),
            "frontend_http_calls": len(frontend_apis),
            "state_literal_evidence": len(states),
            "menu_entries": len(menus),
            "configuration_defaults": len(configs),
            "legacy_roles": len(permissions),
        },
        "api_domains": dict(sorted(api_domain_counts.items())),
        "page_domains": dict(sorted(page_domain_counts.items())),
        "frontend_api_domains": dict(sorted(frontend_api_domain_counts.items())),
        "frontend_segments_without_legacy_backend_match": dict(sorted(unimplemented_segments.items())),
        "declared_state_enums": {key: sorted(values) for key, values in sorted(enum_states.items())},
    }
    (OUTPUT_ROOT / "inventory-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
