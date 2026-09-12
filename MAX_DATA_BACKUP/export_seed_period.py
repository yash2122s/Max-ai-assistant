import json
import csv
import os

base_dir = "MAX_DATA_BACKUP"

# Hardcoded seed records in PeriodDataSeeder.kt
seed_cycles = [
    {
        "id": 1,
        "startDate": "2025-05-28 09:00",
        "endDate": None,
        "durationDays": 0,
        "notes": "May 28, 2025 cycle start"
    },
    {
        "id": 2,
        "startDate": "2025-07-09 09:00",
        "endDate": None,
        "durationDays": 0,
        "notes": "Bittu's period started (July 9, 2025)"
    },
    {
        "id": 3,
        "startDate": "2025-08-08 09:00",
        "endDate": None,
        "durationDays": 0,
        "notes": "August 8, 2025 cycle start"
    },
    {
        "id": 4,
        "startDate": "2025-09-10 09:00",
        "endDate": None,
        "durationDays": 0,
        "notes": "September 10, 2025 cycle start"
    },
    {
        "id": 5,
        "startDate": "2025-10-16 09:00",
        "endDate": None,
        "durationDays": 0,
        "notes": "October 16, 2025 cycle start"
    },
    {
        "id": 6,
        "startDate": "2026-01-02 09:00",
        "endDate": None,
        "durationDays": 0,
        "notes": "January 2, 2026 cycle start"
    },
    {
        "id": 7,
        "startDate": "2026-02-07 09:00",
        "endDate": None,
        "durationDays": 0,
        "notes": "February 7, 2026 cycle start"
    },
    {
        "id": 8,
        "startDate": "2026-03-10 09:00",
        "endDate": None,
        "durationDays": 0,
        "notes": "March 10, 2026 cycle start"
    },
    {
        "id": 9,
        "startDate": "2026-06-08 00:00",
        "endDate": "2026-06-08 23:59",
        "durationDays": 1,
        "notes": "Started ninna night 12 - June 7 night / June 8 early"
    }
]

json_path = os.path.join(base_dir, "period_seed_history.json")
with open(json_path, 'w', encoding='utf-8') as f:
    json.dump(seed_cycles, f, indent=2, ensure_ascii=False)

csv_path = os.path.join(base_dir, "period_seed_history.csv")
with open(csv_path, 'w', newline='', encoding='utf-8') as f:
    writer = csv.DictWriter(f, fieldnames=seed_cycles[0].keys())
    writer.writeheader()
    writer.writerows(seed_cycles)

print(f"Exported {len(seed_cycles)} historical period cycle records to {json_path} and {csv_path}")
