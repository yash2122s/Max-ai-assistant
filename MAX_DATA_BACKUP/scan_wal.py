import re
import os

def scan_wal_for_strings(wal_path):
    print(f"\nScanning: {wal_path}")
    if not os.path.exists(wal_path):
        print("File not found")
        return
    
    with open(wal_path, 'rb') as f:
        data = f.read()
    
    print(f"Total bytes in WAL: {len(data)}")
    
    # Search for common text patterns
    patterns = [
        rb'enc_v1:[A-Za-z0-9+/=]+',
        rb'Bittu',
        rb'Period',
        rb'period_logs',
        rb'permanent_memory',
        rb'2025-05-28',
        rb'shop lights',
        rb'Vinipistunda'
    ]
    
    for pat in patterns:
        matches = re.findall(pat, data)
        print(f"  Pattern {pat}: {len(matches)} matches")
        if matches and len(matches) < 10:
            for m in matches:
                print(f"    - {m[:100]}")

# Current WAL
scan_wal_for_strings(os.path.join('MAX_DATA_BACKUP', 'database_original_copy', 'jarvis_database-wal'))

# Previous WAL
scan_wal_for_strings(os.path.join('MAX_DATA_BACKUP', 'database_original_copy', 'jarvis_vqzrtp', 'jarvis_vqzrtp_backup', 'jarvis_database-wal'))
