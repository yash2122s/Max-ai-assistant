import sqlite3
import json
import csv
import os

base_dir = "MAX_DATA_BACKUP"
curr_db_path = os.path.join(base_dir, "database_original_copy", "jarvis_database")
prev_db_path = os.path.join(base_dir, "database_original_copy", "jarvis_vqzrtp", "jarvis_vqzrtp_backup", "jarvis_database")

def extract_table(db_path, table_name):
    if not os.path.exists(db_path):
        return []
    try:
        con = sqlite3.connect(db_path)
        con.row_factory = sqlite3.Row
        cur = con.cursor()
        cur.execute(f"SELECT name FROM sqlite_master WHERE type='table' AND name='{table_name}'")
        if not cur.fetchone():
            return []
        cur.execute(f"SELECT * FROM `{table_name}`")
        rows = cur.fetchall()
        result = []
        for r in rows:
            d = dict(r)
            for k, v in d.items():
                if isinstance(v, bytes):
                    d[k] = f"<BLOB {len(v)} bytes>"
            result.append(d)
        return result
    except Exception as e:
        print(f"Error querying {table_name} in {db_path}: {e}")
        return []

def save_json_csv(records, base_name):
    json_path = os.path.join(base_dir, f"{base_name}.json")
    with open(json_path, 'w', encoding='utf-8') as f:
        json.dump(records, f, indent=2, ensure_ascii=False)
    print(f"Wrote {len(records)} records to {json_path}")

    csv_path = os.path.join(base_dir, f"{base_name}.csv")
    if records:
        with open(csv_path, 'w', newline='', encoding='utf-8') as f:
            writer = csv.DictWriter(f, fieldnames=records[0].keys())
            writer.writeheader()
            writer.writerows(records)
    else:
        with open(csv_path, 'w', newline='', encoding='utf-8') as f:
            f.write("# No records found\n")
    print(f"Wrote {csv_path}")

# 1. Memories (from current DB)
curr_memories = extract_table(curr_db_path, "permanent_memory")
save_json_csv(curr_memories, "memories")

# 2. Period Tracker (from current DB)
curr_period = extract_table(curr_db_path, "period_logs")
save_json_csv(curr_period, "period_tracker")

# 3. Reminders (combine current and previous DB)
curr_reminders = extract_table(curr_db_path, "reminders")
prev_reminders = extract_table(prev_db_path, "reminders")
all_reminders = curr_reminders + [dict(r, source_pkg="com.aistudio.jarvis.vqzrtp") for r in prev_reminders]
save_json_csv(all_reminders, "reminders")

# 4. Chat History
curr_chat = extract_table(curr_db_path, "chat_messages")
save_json_csv(curr_chat, "chat_history")

# 5. Action Rewards / Automation logs
prev_rewards = extract_table(prev_db_path, "action_rewards")
save_json_csv(prev_rewards, "action_rewards")

# 6. Installed Apps cache
curr_apps = extract_table(curr_db_path, "installed_apps")
save_json_csv(curr_apps, "installed_apps")

print("\nAll extractions completed successfully!")
