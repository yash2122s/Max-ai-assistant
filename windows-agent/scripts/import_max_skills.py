"""
MAX AI — MAX Skill Ingestion Script
Copies curated, high-value MAX skills into storage/skills/
"""

import os
import shutil

SRC_BASE = r"D:\MAX AI\Max-ai\gemini-live\hermes-agent-main\hermes-agent-main\skills"
DST_BASE = r"D:\max app testing\gemini-live\windows-agent\storage\skills"

SELECTED_SKILLS = [
    ("software-development", "plan"),
    ("software-development", "systematic-debugging"),
    ("software-development", "test-driven-development"),
    ("software-development", "simplify-code"),
    ("software-development", "python-debugpy"),
    ("autonomous-ai-agents", "computer-use"),
    ("github", "codebase-inspection"),
    ("github", "github-code-review"),
    ("github", "github-pr-workflow"),
    ("github", "github-issues"),
    ("devops", "sdlc-review"),
    ("research", "arxiv"),
    ("research", "grounded-citations"),
    ("research", "research-paper-writing"),
    ("productivity", "document-to-action-items"),
    ("productivity", "weekly-review-planning"),
    ("productivity", "docx"),
    ("productivity", "pdf"),
    ("productivity", "xlsx"),
    ("productivity", "meeting-action-items"),
    ("note-taking", "obsidian"),
    ("smart-home", "openhue"),
]

def main():
    copied = 0
    for cat, name in SELECTED_SKILLS:
        src_dir = os.path.join(SRC_BASE, cat, name)
        dst_dir = os.path.join(DST_BASE, cat, name)
        if os.path.exists(src_dir):
            os.makedirs(os.path.dirname(dst_dir), exist_ok=True)
            shutil.copytree(src_dir, dst_dir, dirs_exist_ok=True)
            print(f"Copied: {cat}/{name}")
            copied += 1
        else:
            # Check flat category search
            alt_src = os.path.join(SRC_BASE, name)
            if os.path.exists(alt_src):
                os.makedirs(os.path.dirname(dst_dir), exist_ok=True)
                shutil.copytree(alt_src, dst_dir, dirs_exist_ok=True)
                print(f"Copied (alt): {cat}/{name}")
                copied += 1
            else:
                # Check directory search
                found = False
                for root, dirs, files in os.walk(SRC_BASE):
                    if os.path.basename(root) == name and "SKILL.md" in files:
                        os.makedirs(os.path.dirname(dst_dir), exist_ok=True)
                        shutil.copytree(root, dst_dir, dirs_exist_ok=True)
                        print(f"Copied (discovered): {cat}/{name} from {root}")
                        copied += 1
                        found = True
                        break
                if not found:
                    print(f"Not found: {cat}/{name}")

    print(f"Total skills successfully copied: {copied}")

if __name__ == "__main__":
    main()
