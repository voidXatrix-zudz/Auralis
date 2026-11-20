import json, os, re, sqlite3, sys

ROOT = r"C:\Users\Dipanjan\Downloads\Workspace\AURALIS-FULL"
KT = os.path.join(ROOT, "app", "src", "main", "java", "com", "auralis", "app", "data", "db", "AuralisDatabase.kt")
SCHEMA_DIR = os.path.join(ROOT, "app", "schemas", "com.auralis.app.data.db.AuralisDatabase")


def schema(version):
    with open(os.path.join(SCHEMA_DIR, f"{version}.json"), encoding="utf-8") as f:
        return json.load(f)["database"]


def extract_execsql(path):
    src = open(path, encoding="utf-8").read()
    out = []
    i = 0
    while True:
        j = src.find("execSQL(", i)
        if j < 0:
            break
        k = j + len("execSQL(")
        depth = 1
        parts = []
        while k < len(src) and depth > 0:
            c = src[k]
            if src.startswith('"""', k):
                end = src.find('"""', k + 3)
                parts.append(src[k + 3:end])
                k = end + 3
            elif c == '"':
                end = k + 1
                buf = []
                while end < len(src) and src[end] != '"':
                    if src[end] == '\\':
                        buf.append(src[end:end + 2]); end += 2; continue
                    buf.append(src[end]); end += 1
                parts.append("".join(buf))
                k = end + 1
            elif c == '(':
                depth += 1; k += 1
            elif c == ')':
                depth -= 1; k += 1
            else:
                k += 1
        if parts:
            out.append("".join(parts))
        i = k
    return out


def create_from_schema(db, sch):
    for e in sch["entities"]:
        name = e["tableName"]
        db.execute(e["createSql"].replace("${TABLE_NAME}", name))
        for idx in e.get("indices", []):
            db.execute(idx["createSql"].replace("${TABLE_NAME}", name))


def actual_schema(db):
    tables = {}
    rows = db.execute("SELECT name, sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' AND name != 'room_master_table'").fetchall()
    for name, sql in rows:
        cols = {}
        for cid, cn, ctype, notnull, dflt, pk in db.execute(f"PRAGMA table_info('{name}')").fetchall():
            cols[cn] = {"type": (ctype or "").upper(), "notNull": bool(notnull), "default": dflt, "pk": pk}
        idx = {}
        for seq, iname, unique, origin, partial in db.execute(f"PRAGMA index_list('{name}')").fetchall():
            if origin == 'pk':
                continue
            icols = [r[2] for r in db.execute(f"PRAGMA index_info('{iname}')").fetchall()]
            idx[iname] = {"unique": bool(unique), "columns": icols}
        fks = []
        for f in db.execute(f"PRAGMA foreign_key_list('{name}')").fetchall():
            fks.append(f)
        tables[name] = {"cols": cols, "idx": idx, "fks": fks}
    return tables


def expected_schema(sch):
    tables = {}
    for e in sch["entities"]:
        name = e["tableName"]
        cols = {}
        for f in e["fields"]:
            cols[f["columnName"]] = {
                "type": (f.get("affinity") or "").upper(),
                "notNull": bool(f.get("notNull", False)),
                "default": f.get("defaultValue"),
                "pk": 1 if f.get("primaryKeyPosition", 0) and f.get("primaryKeyPosition", 0) > 0 else 0,
            }
        idx = {}
        for ix in e.get("indices", []):
            idx[ix["name"]] = {"unique": bool(ix.get("unique", False)), "columns": ix.get("columnNames", [])}
        fks = []
        for fk in e.get("foreignKeys", []):
            fks.append(fk)
        tables[name] = {"cols": cols, "idx": idx, "fks": fks}
    return tables


def norm_type(t):
    t = (t or "").upper()
    if "INT" in t: return "INTEGER"
    if "CHAR" in t or "CLOB" in t or "TEXT" in t: return "TEXT"
    if "BLOB" in t: return "BLOB"
    if "REAL" in t or "FLOA" in t or "DOUB" in t: return "REAL"
    return t


def main():
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys=ON")
    create_from_schema(db, schema(1))
    stmts = extract_execsql(KT)
    print(f"extracted {len(stmts)} execSQL statements")
    for s in stmts:
        db.execute(s)
    db.commit()
    act = actual_schema(db)
    exp = expected_schema(schema(11))

    problems = []
    for name, e in exp.items():
        if name not in act:
            problems.append(f"MISSING TABLE {name}")
            continue
        a = act[name]
        for cn, ec in e["cols"].items():
            if cn not in a["cols"]:
                problems.append(f"{name}: missing column {cn}")
                continue
            ac = a["cols"][cn]
            if norm_type(ac["type"]) != norm_type(ec["type"]):
                problems.append(f"{name}.{cn}: type actual={ac['type']} expected={ec['type']}")
            if ac["notNull"] != ec["notNull"]:
                problems.append(f"{name}.{cn}: notNull actual={ac['notNull']} expected={ec['notNull']}")
            ad = (str(ac["default"]) if ac["default"] is not None else None)
            ed = (str(ec["default"]) if ec["default"] is not None else None)
            # Room only compares a default when the entity declares one (see TableInfo.equalsCommon).
            if ec["default"] is not None:
                def norm_default(v):
                    if v is None: return None
                    return v.strip().strip("'").strip('"')
                if norm_default(ad) != norm_default(ed):
                    problems.append(f"{name}.{cn}: default actual={ad} expected={ed}")
        for ixname, eix in e["idx"].items():
            if ixname not in a["idx"]:
                problems.append(f"{name}: missing index {ixname} (expected cols {eix['columns']})")
            else:
                aix = a["idx"][ixname]
                if list(aix["columns"]) != list(eix["columns"]):
                    problems.append(f"{name}: index {ixname} cols actual={aix['columns']} expected={eix['columns']}")
                if aix["unique"] != eix["unique"]:
                    problems.append(f"{name}: index {ixname} unique actual={aix['unique']} expected={eix['unique']}")
        # foreign keys
        a_fks = sorted((f[2], f[3], f[4]) for f in a["fks"])  # (table, from, to)
        e_fks = sorted(
            (fk["table"], c, r)
            for fk in e["fks"]
            for c, r in zip(fk.get("columns", []), fk.get("referencedColumns", []))
        )
        if a_fks != e_fks:
            problems.append(f"{name}: foreign keys actual={a_fks} expected={e_fks}")
    # extra tables/cols that migrations added but schema doesn't define
    for name in act:
        if name not in exp:
            problems.append(f"EXTRA TABLE {name} (not in v11 schema)")
    if problems:
        print("PROBLEMS:")
        for p in problems:
            print("  -", p)
    else:
        print("OK: migrated schema matches v11 for all tables/columns/indices")
    # also print table list
    print("tables in migrated db:", sorted(act.keys()))
    print("tables in v11 schema:", sorted(exp.keys()))


main()
