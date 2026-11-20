import collections, re, sys, xml.etree.ElementTree as ET

path = r"C:\Users\Dipanjan\Downloads\Workspace\AURALIS-FULL\app\build\reports\lint-results-debug.xml"
try:
    root = ET.parse(path).getroot()
    by_sev = collections.Counter()
    by_id = collections.Counter()
    for issue in root.iter("issue"):
        sev = issue.get("severity")
        iid = issue.get("id")
        by_sev[sev] += 1
        by_id[(sev, iid)] += 1
    print("severity totals:", dict(by_sev))
    print()
    for (sev, iid), n in sorted(by_id.items(), key=lambda kv: (-kv[1], kv[0])):
        print(f"  {n:4d}  {sev:8s} {iid}")
except FileNotFoundError:
    print("no xml report at", path)
