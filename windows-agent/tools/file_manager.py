"""
JARVIS File Manager Tools
Search, open, create, move files and directories.
⚠️ move_file and delete operations are flagged as DANGEROUS in config.py.
"""

import os
import glob
import shutil
import subprocess


def search_files(query: str, directory: str = None) -> dict:
    """
    Search for files by name pattern in a directory.
    
    Args:
        query: File name or glob pattern to search for (e.g., '*.pdf', 'report').
        directory: Directory to search in. Defaults to user's home directory.
    """
    try:
        if directory is None:
            directory = os.path.expanduser("~")
        
        if not os.path.isdir(directory):
            return {"status": "error", "message": f"Directory not found: {directory}"}
        
        # Add wildcards if not present
        if "*" not in query and "?" not in query:
            query = f"*{query}*"
        
        pattern = os.path.join(directory, "**", query)
        matches = glob.glob(pattern, recursive=True)
        
        # Limit results to prevent overwhelming output
        max_results = 20
        results = []
        for match in matches[:max_results]:
            try:
                stat = os.stat(match)
                results.append({
                    "path": match,
                    "name": os.path.basename(match),
                    "size_kb": round(stat.st_size / 1024, 1),
                    "is_directory": os.path.isdir(match),
                })
            except OSError:
                continue
        
        return {
            "status": "success",
            "results": results,
            "total_found": len(matches),
            "showing": min(len(matches), max_results),
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def open_file(path: str) -> dict:
    """
    Open a file with its default application.
    
    Args:
        path: Full path to the file to open.
    """
    try:
        if not os.path.exists(path):
            return {"status": "error", "message": f"File not found: {path}"}
        
        os.startfile(path)
        return {"status": "success", "message": f"Opened {os.path.basename(path)}"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def create_folder(path: str) -> dict:
    """
    Create a new folder/directory.
    
    Args:
        path: Full path for the new folder.
    """
    try:
        if os.path.exists(path):
            return {"status": "error", "message": f"Folder already exists: {path}"}
        
        os.makedirs(path, exist_ok=True)
        return {"status": "success", "message": f"Created folder: {path}"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def move_file(source: str, destination: str) -> dict:
    """
    Move or rename a file/folder.
    ⚠️ DANGEROUS: Requires confirmation gate in tool_executor.
    
    Args:
        source: Current path of the file/folder.
        destination: New path/location for the file/folder.
    """
    try:
        if not os.path.exists(source):
            return {"status": "error", "message": f"Source not found: {source}"}
        
        shutil.move(source, destination)
        return {
            "status": "success",
            "message": f"Moved {os.path.basename(source)} to {destination}",
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def delete_file(path: str) -> dict:
    """
    Delete a file or empty folder.
    ⚠️ DANGEROUS: Requires confirmation gate in tool_executor.
    
    Args:
        path: Path to the file or empty folder to delete.
    """
    try:
        if not os.path.exists(path):
            return {"status": "error", "message": f"Not found: {path}"}
        
        if os.path.isfile(path):
            os.remove(path)
            return {"status": "success", "message": f"Deleted file: {os.path.basename(path)}"}
        elif os.path.isdir(path):
            # Only delete empty directories for safety
            if os.listdir(path):
                return {
                    "status": "error",
                    "message": f"Folder is not empty. Refusing to delete non-empty folders for safety.",
                }
            os.rmdir(path)
            return {"status": "success", "message": f"Deleted empty folder: {path}"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def get_folder_contents(path: str = None) -> dict:
    """
    List the contents of a directory.
    
    Args:
        path: Directory path. Defaults to Desktop if not provided.
    """
    try:
        if path is None:
            path = os.path.join(os.path.expanduser("~"), "Desktop")
        
        if not os.path.isdir(path):
            return {"status": "error", "message": f"Directory not found: {path}"}
        
        items = []
        for item in sorted(os.listdir(path)):
            full_path = os.path.join(path, item)
            try:
                stat = os.stat(full_path)
                items.append({
                    "name": item,
                    "is_directory": os.path.isdir(full_path),
                    "size_kb": round(stat.st_size / 1024, 1) if os.path.isfile(full_path) else None,
                })
            except OSError:
                items.append({"name": item, "is_directory": False, "size_kb": None})
        
        return {
            "status": "success",
            "path": path,
            "contents": items[:50],  # Limit to 50 items
            "total_items": len(items),
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


# ─── Tool Declarations ──────────────────────────────────────────────────────

TOOL_DECLARATIONS = [
    {
        "name": "search_files",
        "description": "Search for files by name or pattern in a directory. Supports wildcards like *.pdf, *.docx.",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "File name or glob pattern (e.g., '*.pdf', 'report', '*.py')",
                },
                "directory": {
                    "type": "string",
                    "description": "Directory to search in. Defaults to user home if not specified.",
                },
            },
            "required": ["query"],
        },
    },
    {
        "name": "open_file",
        "description": "Open a file with its default Windows application.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Full path to the file to open",
                }
            },
            "required": ["path"],
        },
    },
    {
        "name": "create_folder",
        "description": "Create a new folder/directory at the specified path.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Full path for the new folder",
                }
            },
            "required": ["path"],
        },
    },
    {
        "name": "move_file",
        "description": "Move or rename a file or folder. DANGEROUS: will ask for confirmation.",
        "parameters": {
            "type": "object",
            "properties": {
                "source": {
                    "type": "string",
                    "description": "Current path of the file/folder",
                },
                "destination": {
                    "type": "string",
                    "description": "New path/location",
                },
            },
            "required": ["source", "destination"],
        },
    },
    {
        "name": "delete_file",
        "description": "Delete a file or empty folder. DANGEROUS: will ask for confirmation. Refuses to delete non-empty folders.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Path to the file or empty folder to delete",
                }
            },
            "required": ["path"],
        },
    },
    {
        "name": "get_folder_contents",
        "description": "List all files and folders in a directory.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Directory path. Defaults to Desktop if not specified.",
                }
            },
        },
    },
]

TOOL_FUNCTIONS = {
    "search_files": search_files,
    "open_file": open_file,
    "create_folder": create_folder,
    "move_file": move_file,
    "delete_file": delete_file,
    "get_folder_contents": get_folder_contents,
}
