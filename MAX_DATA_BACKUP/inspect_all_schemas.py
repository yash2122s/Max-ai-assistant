import sqlite3
import os
import json
import sys

sys.stdout.reconfigure(encoding='utf-8')

db_path = os.path.join('MAX_DATA_BACKUP', 'database_original_copy', 'jarvis_database')
con = sqlite3.connect(db_path)
con.row_factory = sqlite3.Row
cur = con.cursor()

cur.execute("SELECT name, sql FROM sqlite_master WHERE type='table'")
tables = cur.fetchall()

print("==================================================")
print("TABLES AND SCHEMAS IN jarvis_database (com.aistudio.geminilive.abcde)")
print("==================================================")

for t in tables:
    name = t['name']
    sql = t['sql']
    cur.execute(f"SELECT COUNT(*) FROM `{name}`")
    count = cur.fetchone()[0]
    print(f"\nTABLE: {name} (Row count: {count})")
    print(f"SCHEMA: {sql}")
    if count > 0:
        cur.execute(f"SELECT * FROM `{name}` LIMIT 5")
        rows = [dict(r) for r in cur.fetchall()]
        print("SAMPLE ROWS:")
        for r in rows:
            clean_r = {k: (f"<BLOB {len(v)} bytes>" if isinstance(v, bytes) else v) for k, v in r.items()}
            print("  ", clean_r)
