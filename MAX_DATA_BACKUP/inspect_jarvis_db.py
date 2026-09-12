import sqlite3
import os

db_path = os.path.join('MAX_DATA_BACKUP', 'database_original_copy', 'jarvis_vqzrtp', 'jarvis_vqzrtp_backup', 'jarvis_database')
con = sqlite3.connect(db_path)
con.row_factory = sqlite3.Row
cur = con.cursor()

cur.execute("SELECT name FROM sqlite_master WHERE type='table'")
tables = [r[0] for r in cur.fetchall()]
print("Tables in jarvis_vqzrtp database:", tables)

for t in tables:
    if t == 'sqlite_sequence':
        continue
    cur.execute(f"SELECT count(*) FROM `{t}`")
    cnt = cur.fetchone()[0]
    print(f"Table: {t} -> {cnt} records")
