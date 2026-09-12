import sqlite3
import json
import csv
import os

def dump_database(db_path, output_dir, label):
    print(f"\n==========================================")
    print(f"Dumping database: {label}")
    print(f"Path: {db_path}")
    print(f"==========================================")
    
    if not os.path.exists(db_path):
        print(f"ERROR: File does not exist: {db_path}")
        return

    os.makedirs(output_dir, exist_ok=True)
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    cur = con.cursor()

    cur.execute("SELECT name FROM sqlite_master WHERE type='table'")
    tables = [r[0] for r in cur.fetchall()]
    print("Tables found:", tables)

    summary = {}

    for table in tables:
        if table in ('sqlite_sequence', 'android_metadata', 'room_master_table'):
            continue
        
        cur.execute(f"SELECT * FROM `{table}`")
        rows = cur.fetchall()
        row_dicts = []
        for r in rows:
            d = dict(r)
            # convert byte arrays (embeddings or BLOBs) to hex/string representation if present
            for k, v in d.items():
                if isinstance(v, bytes):
                    d[k] = f"<BLOB {len(v)} bytes>"
            row_dicts.append(d)

        summary[table] = len(row_dicts)
        print(f"  - Table '{table}': {len(row_dicts)} rows")

        # Save JSON
        json_file = os.path.join(output_dir, f"{table}.json")
        with open(json_file, 'w', encoding='utf-8') as f:
            json.dump(row_dicts, f, indent=2, ensure_ascii=False)

        # Save CSV if records exist
        if row_dicts:
            csv_file = os.path.join(output_dir, f"{table}.csv")
            with open(csv_file, 'w', newline='', encoding='utf-8') as f:
                writer = csv.DictWriter(f, fieldnames=row_dicts[0].keys())
                writer.writeheader()
                writer.writerows(row_dicts)

    # Save summary metadata
    with open(os.path.join(output_dir, "_summary.json"), 'w', encoding='utf-8') as f:
        json.dump({"label": label, "db_path": db_path, "summary": summary}, f, indent=2)

dump_database(
    os.path.join('MAX_DATA_BACKUP', 'database_original_copy', 'jarvis_database'),
    os.path.join('MAX_DATA_BACKUP', 'current_max_extracted'),
    'Current MAX App (com.aistudio.geminilive.abcde)'
)

dump_database(
    os.path.join('MAX_DATA_BACKUP', 'database_original_copy', 'jarvis_vqzrtp', 'jarvis_vqzrtp_backup', 'jarvis_database'),
    os.path.join('MAX_DATA_BACKUP', 'previous_jarvis_extracted'),
    'Previous Jarvis App (com.aistudio.jarvis.vqzrtp)'
)
