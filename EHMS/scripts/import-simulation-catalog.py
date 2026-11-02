"""Read the equipment scope workbook without modifying it. Developer-time only."""
import argparse
import hashlib
import json
from pathlib import Path
import openpyxl

p = argparse.ArgumentParser()
p.add_argument("workbook", type=Path)
p.add_argument("output", type=Path)
args = p.parse_args()
book = openpyxl.load_workbook(args.workbook, read_only=True, data_only=True)
aliases = {
    "EHM-GT-01": ["EQ-IMG-01", "EQ-IMG-07"],
    "EHM-QT-01": ["EQ-IMG-02", "EQ-IMG-03", "EQ-IMG-04"],
    "EHM-FX-01": ["EQ-IMG-08"], "EHM-TS-01": ["EQ-IMG-11"],
    "EHM-XL-01": ["EQ-IMG-14", "EQ-EXT-04"],
    "EHM-CC-01": ["EQ-IMG-10", "EQ-IMG-13"],
}
groups = {}
references = []
for sheet in book:
    for row_number, row in enumerate(sheet.iter_rows(min_row=2, values_only=True), 2):
        if not row[6]:
            continue
        code, name = str(row[6]).strip(), str(row[8]).strip()
        references.append({"sheet": sheet.title, "row": row_number, "scopeCode": code,
                           "name": name, "coveredBy": aliases.get(code, [code])})
        if code in aliases:
            continue
        if code not in groups:
            profile = "ROTATING"
            if code in ("EQ-IMG-05", "EQ-EXT-01"):
                profile = "WEIGHING"
            elif code in ("EQ-IMG-06", "EQ-IMG-12", "EQ-EXT-05"):
                profile = "PROCESS"
            elif code.startswith("IF-") or code in ("EQ-IMG-17", "EQ-EXT-02", "EQ-EXT-03", "EQ-EXT-06", "EQ-EXT-07"):
                profile = "ELECTRONIC"
            groups[code] = {"scopeCode": code, "name": name, "profile": profile,
                "sourceRows": [], "sourceModules": [], "simulationQuantity": 1,
                "actualQuantity": None, "quantityConfirmed": False,
                "controlBoundary": "EHM只模拟状态采集、健康分析与维保建议，不下发设备控制指令"}
        entry = groups[code]
        entry["sourceRows"].append(row_number)
        if row[0] not in entry["sourceModules"]:
            entry["sourceModules"].append(row[0])
for ref in references:
    assert all(code in groups for code in ref["coveredBy"]), ref
result = {"version": "scope-20260826-running-v1", "sourceFile": args.workbook.name,
    "sourceSha256": hashlib.sha256(args.workbook.read_bytes()).hexdigest(),
    "sourceRowCount": len(references), "assetTypeCount": len(groups),
    "assumptions": ["实际数量、实例编号和厂家参数未确定；每个去重范围项仅创建一个虚拟实例",
        "6个EHM单台设备模板关联已有范围项，不重复统计设备",
        "测点、量程、阈值和周期全部为可追溯演示假设，不能作为现场安全或设计寿命依据",
        "IF开头编号是接口研究范围，不是现场设备ID；仅用于映射虚拟健康监测对象"],
    "entries": list(groups.values()), "coverage": references}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"sourceRows": len(references), "virtualAssets": len(groups),
                  "sourceSha256": result["sourceSha256"]}))
