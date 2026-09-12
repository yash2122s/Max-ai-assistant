"""
JARVIS File Manager Tools
Search, open, create, move files and directories.
Includes dedicated special folder support (Desktop, Downloads, Documents) and ActionVerifier.
⚠️ move_file and delete operations are flagged as DANGEROUS in config.py.
"""

import os
import glob
import shutil
import subprocess
import logging

from core.security import security_guard
from core.verifier import action_verifier

logger = logging.getLogger("MAXWindowsAgent.FileManager")

# Windows Well-Known Special Folders
USER_HOME = os.path.abspath(os.path.realpath(os.path.expanduser("~")))
SPECIAL_FOLDERS = {
    "desktop": os.path.join(USER_HOME, "Desktop"),
    "downloads": os.path.join(USER_HOME, "Downloads"),
    "download": os.path.join(USER_HOME, "Downloads"),
    "documents": os.path.join(USER_HOME, "Documents"),
    "document": os.path.join(USER_HOME, "Documents"),
    "pictures": os.path.join(USER_HOME, "Pictures"),
    "photos": os.path.join(USER_HOME, "Pictures"),
    "music": os.path.join(USER_HOME, "Music"),
    "videos": os.path.join(USER_HOME, "Videos"),
    "home": USER_HOME,
    "user": USER_HOME,
}


def _resolve_user_path(path: str = None) -> str:
    """
    Robustly resolves a path string into a canonical absolute path.
    Seamlessly handles special folder aliases ('Desktop', 'Downloads', 'Documents')
    and relative subpaths ('Desktop/my_folder', 'Downloads/file.pdf').
    """
    if not path or not str(path).strip():
        return USER_HOME

    raw = str(path).strip().strip('"').strip("'")
    raw_normalized = raw.lower().replace("\\", "/").rstrip("/")

    # 1. Direct match for special folder name (e.g. "downloads", "desktop", "documents")
    if raw_normalized in SPECIAL_FOLDERS:
        return SPECIAL_FOLDERS[raw_normalized]

    # 2. Check if path starts with a special folder prefix (e.g. "desktop/my_folder" or "Downloads/report.pdf")
    for key, folder_path in SPECIAL_FOLDERS.items():
        if raw_normalized.startswith(key + "/"):
            remainder = raw[len(key) + 1:].lstrip("/\\")
            return os.path.abspath(os.path.join(folder_path, remainder))

    # 3. Expand environment variables (%USERPROFILE%, %TEMP%, etc.) and ~
    expanded = os.path.expanduser(os.path.expandvars(raw))
    if os.path.isabs(expanded):
        return os.path.abspath(expanded)

    # 4. Relative path fallback: resolve relative to user's home directory
    return os.path.abspath(os.path.join(USER_HOME, expanded))


def search_files(query: str, directory: str = None, **kwargs) -> dict:
    """
    Search for files by name pattern in a directory.
    
    Args:
        query: File name or glob pattern to search for (e.g., '*.pdf', 'report').
        directory: Directory to search in. Defaults to user's home directory.
    """
    try:
        dir_raw = directory or kwargs.get("folder") or kwargs.get("path")
        target_dir = _resolve_user_path(dir_raw)
        
        if not security_guard.is_safe_path(target_dir):
            return {"status": "error", "message": f"Access denied: Directory '{target_dir}' is outside the authorized sandbox."}
        
        if not os.path.isdir(target_dir):
            return {"status": "error", "message": f"Directory not found: {target_dir}"}
        
        # Add wildcards if not present
        if "*" not in query and "?" not in query:
            pattern_query = f"*{query}*"
        else:
            pattern_query = query
        
        pattern = os.path.join(target_dir, "**", pattern_query)
        matches = glob.glob(pattern, recursive=True)
        
        # Limit results to prevent overwhelming output
        max_results = 20
        results = []
        for match in matches[:max_results]:
            try:
                if not security_guard.is_safe_path(match):
                    continue
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


def open_file(path: str = None, **kwargs) -> dict:
    """
    Open a file with its default application or a folder in Explorer.
    
    Args:
        path: Full path or relative folder/file path to open.
    """
    try:
        raw_path = path or kwargs.get("file_path") or kwargs.get("file_name") or kwargs.get("target")
        if not raw_path:
            return {"status": "error", "message": "Path is required."}

        target = _resolve_user_path(raw_path)
        if not security_guard.is_safe_path(target, allow_system_executables=True):
            return {"status": "error", "message": f"Access denied: Path '{target}' is outside the authorized sandbox."}
            
        if not os.path.exists(target):
            return {"status": "error", "message": f"Path not found: {target}"}
        
        if os.path.isdir(target):
            return open_folder(path=target)

        os.startfile(target)
        return {
            "status": "success",
            "message": f"Opened {os.path.basename(target)}",
            "path": target,
            "verified": True
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def open_folder(path: str = "Desktop", **kwargs) -> dict:
    """
    Open a folder in Windows File Explorer (e.g. 'Downloads', 'Desktop', 'Documents', or full directory path).
    
    Args:
        path: Folder name or directory path (e.g. 'Downloads', 'Desktop', 'Documents').
    """
    try:
        raw_path = path or kwargs.get("folder_name") or kwargs.get("directory") or kwargs.get("folder") or "Desktop"
        target_dir = _resolve_user_path(raw_path)

        if not security_guard.is_safe_path(target_dir):
            return {"status": "error", "message": f"Access denied: Folder '{target_dir}' is outside the sandbox."}

        if not os.path.exists(target_dir):
            return {"status": "error", "message": f"Folder not found: {target_dir}"}

        # Open directory with Windows Explorer
        try:
            os.startfile(target_dir)
        except Exception:
            subprocess.Popen(["explorer.exe", target_dir], shell=False)

        folder_name = os.path.basename(target_dir) or target_dir
        return {
            "status": "success",
            "message": f"Opened {folder_name} folder",
            "path": target_dir,
            "verified": True
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to open folder: {str(e)}"}


def create_file(path: str = None, content: str = "", **kwargs) -> dict:
    """
    Create a new text file with optional content.
    Accepts full path or 'file_name' + 'location' (e.g. file_name='abc.txt', location='Desktop/test_folder').
    
    Args:
        path: Path for the new file (e.g. 'Desktop/test/abc.txt').
        content: Text content to write into the file.
    """
    try:
        raw_path = path or kwargs.get("file_path") or kwargs.get("target")
        file_name = kwargs.get("file_name") or kwargs.get("name")
        location = kwargs.get("location") or kwargs.get("folder") or kwargs.get("in_folder") or kwargs.get("directory")
        text_content = content if content is not None else kwargs.get("text", "")

        if not raw_path:
            if file_name and location:
                loc_resolved = _resolve_user_path(location)
                target_path = os.path.abspath(os.path.join(loc_resolved, file_name))
            elif file_name:
                target_path = os.path.abspath(os.path.join(SPECIAL_FOLDERS["desktop"], file_name))
            else:
                return {"status": "error", "message": "File path or name is required."}
        else:
            if file_name and not raw_path.endswith(file_name):
                base_resolved = _resolve_user_path(raw_path)
                target_path = os.path.abspath(os.path.join(base_resolved, file_name))
            else:
                target_path = _resolve_user_path(raw_path)

        if not security_guard.is_safe_path(target_path):
            return {"status": "error", "message": f"Access denied: Cannot create file '{target_path}' outside sandbox."}

        # Create parent directories if they don't exist
        parent_dir = os.path.dirname(target_path)
        if parent_dir and not os.path.exists(parent_dir):
            os.makedirs(parent_dir, exist_ok=True)

        with open(target_path, "w", encoding="utf-8") as f:
            f.write(text_content)

        verified = action_verifier.verify_file_created_sync(target_path, text_content)
        return {
            "status": "success",
            "message": f"Created text file: {os.path.basename(target_path)}",
            "path": target_path,
            "bytes_written": len(text_content.encode("utf-8")),
            "verified": verified
        }
    except Exception as e:
        logger.error(f"Error creating file: {e}")
        return {"status": "error", "message": f"Failed to create file: {str(e)}"}


def create_text_file(path: str = None, content: str = "", **kwargs) -> dict:
    """Explicit tool alias for creating a text file."""
    return create_file(path=path, content=content, **kwargs)


def write_file(path: str = None, content: str = "", **kwargs) -> dict:
    """Explicit tool alias for writing content to a file."""
    return create_file(path=path, content=content, **kwargs)


def read_file(path: str = None, **kwargs) -> dict:
    """
    Read content from a text file.
    
    Args:
        path: Path to the file to read.
    """
    try:
        raw_path = path or kwargs.get("file_path") or kwargs.get("target")
        if not raw_path:
            return {"status": "error", "message": "File path is required."}

        target_path = _resolve_user_path(raw_path)
        if not security_guard.is_safe_path(target_path):
            return {"status": "error", "message": f"Access denied: Cannot read file '{target_path}' outside sandbox."}

        if not os.path.exists(target_path):
            return {"status": "error", "message": f"File not found: {target_path}"}

        if not os.path.isfile(target_path):
            return {"status": "error", "message": f"Path is a directory, not a file: {target_path}"}

        with open(target_path, "r", encoding="utf-8", errors="ignore") as f:
            content = f.read(100000)  # Read up to 100KB

        return {
            "status": "success",
            "path": target_path,
            "content": content,
            "size_bytes": os.path.getsize(target_path),
            "verified": True
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to read file: {str(e)}"}


def create_folder(path: str = None, **kwargs) -> dict:
    """
    Create a new folder/directory.
    Accepts full path or 'folder_name' + 'location' (e.g. folder_name='Reports', location='Desktop').
    
    Args:
        path: Path for the new folder (e.g. 'Desktop/MyFolder' or 'Downloads/Project').
    """
    try:
        raw_path = path or kwargs.get("folder_path") or kwargs.get("dir_path")
        folder_name = kwargs.get("folder_name") or kwargs.get("name")
        location = kwargs.get("location") or kwargs.get("parent_dir") or kwargs.get("in_folder")

        if not raw_path:
            if folder_name and location:
                loc_resolved = _resolve_user_path(location)
                target_path = os.path.abspath(os.path.join(loc_resolved, folder_name))
            elif folder_name:
                # Default to Desktop if only folder name is given
                target_path = os.path.abspath(os.path.join(SPECIAL_FOLDERS["desktop"], folder_name))
            else:
                return {"status": "error", "message": "Folder path or name is required."}
        else:
            if folder_name and not raw_path.endswith(folder_name):
                base_resolved = _resolve_user_path(raw_path)
                target_path = os.path.abspath(os.path.join(base_resolved, folder_name))
            else:
                target_path = _resolve_user_path(raw_path)

        if not security_guard.is_safe_path(target_path):
            return {"status": "error", "message": f"Access denied: Cannot create folder '{target_path}' outside sandbox."}

        # Semantic mismatch guard: prevent creating directories named after file extensions
        common_file_exts = (".txt", ".csv", ".py", ".json", ".md", ".doc", ".docx", ".pdf", ".log", ".html", ".xml", ".yaml", ".yml", ".ini", ".cfg", ".png", ".jpg", ".zip")
        if any(target_path.lower().endswith(ext) for ext in common_file_exts):
            return {
                "status": "error",
                "message": f"Cannot create folder '{os.path.basename(target_path)}' because it ends with a file extension. Use 'create_text_file' to create a file instead.",
                "verified": False
            }
            
        if os.path.exists(target_path):
            return {
                "status": "info",
                "message": f"Folder already exists: {target_path}",
                "path": target_path,
                "verified": os.path.isdir(target_path)
            }
        
        os.makedirs(target_path, exist_ok=True)
        verified = action_verifier.verify_folder_created_sync(target_path)
        
        return {
            "status": "success",
            "message": f"Created folder: {os.path.basename(target_path)}",
            "path": target_path,
            "verified": verified
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to create folder: {str(e)}"}


def move_file(source: str, destination: str, **kwargs) -> dict:
    """
    Move or rename a file/folder.
    ⚠️ DANGEROUS: Requires confirmation gate in tool_executor.
    
    Args:
        source: Current path of the file/folder.
        destination: New path/location for the file/folder.
    """
    try:
        src = _resolve_user_path(source)
        dst = _resolve_user_path(destination)
        
        if not security_guard.is_safe_path(src) or not security_guard.is_safe_path(dst):
            return {"status": "error", "message": "Access denied: Source and destination must reside within sandbox."}
            
        if not os.path.exists(src):
            return {"status": "error", "message": f"Source not found: {src}"}
        
        shutil.move(src, dst)
        verified = action_verifier.verify_file_moved_sync(src, dst)
        return {
            "status": "success",
            "message": f"Moved {os.path.basename(src)} to {dst}",
            "verified": verified
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def copy_file(source: str, destination: str, **kwargs) -> dict:
    """
    Copy a file to a new destination.
    
    Args:
        source: Current path of the file to copy.
        destination: Destination path or folder.
    """
    try:
        src = _resolve_user_path(source)
        dst = _resolve_user_path(destination)

        if not security_guard.is_safe_path(src) or not security_guard.is_safe_path(dst):
            return {"status": "error", "message": "Access denied: Source and destination must reside within sandbox."}

        if not os.path.exists(src) or not os.path.isfile(src):
            return {"status": "error", "message": f"Source file not found: {src}"}

        if os.path.isdir(dst):
            dst = os.path.join(dst, os.path.basename(src))

        shutil.copy2(src, dst)
        verified = action_verifier.verify_file_copied_sync(src, dst)
        return {
            "status": "success",
            "message": f"Copied {os.path.basename(src)} to {dst}",
            "verified": verified
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to copy file: {str(e)}"}


def rename_file(source: str, new_name: str, **kwargs) -> dict:
    """
    Rename a file or folder in place.
    
    Args:
        source: Current file path.
        new_name: New file name (e.g. 'new_report.txt').
    """
    try:
        src = _resolve_user_path(source)
        if not os.path.exists(src):
            return {"status": "error", "message": f"File not found: {src}"}

        parent_dir = os.path.dirname(src)
        dst = os.path.join(parent_dir, os.path.basename(new_name))
        shutil.move(src, dst)
        verified = action_verifier.verify_file_moved_sync(src, dst)
        return {
            "status": "success",
            "message": f"Renamed {os.path.basename(src)} to {os.path.basename(dst)}",
            "path": dst,
            "verified": verified
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to rename file: {str(e)}"}


def delete_file(path: str, **kwargs) -> dict:
    """
    Delete a file.
    ⚠️ DANGEROUS: Requires confirmation gate in tool_executor.
    
    Args:
        path: Path to the file to delete.
    """
    try:
        target = _resolve_user_path(path)
        if not security_guard.is_safe_path(target):
            return {"status": "error", "message": f"Access denied: Cannot delete path '{target}' outside sandbox."}
            
        if not os.path.exists(target):
            return {"status": "error", "message": f"Not found: {target}"}
        
        if os.path.isdir(target):
            return {"status": "error", "message": f"Path '{target}' is a directory. Use delete_folder to delete directories."}
        
        os.remove(target)
        verified = action_verifier.verify_path_deleted_sync(target)
        return {"status": "success", "message": f"Deleted file: {os.path.basename(target)}", "verified": verified}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def delete_folder(path: str, **kwargs) -> dict:
    """
    Delete an empty folder.
    ⚠️ DANGEROUS: Requires confirmation gate in tool_executor.
    
    Args:
        path: Path to the empty directory to delete.
    """
    try:
        target = _resolve_user_path(path)
        if not security_guard.is_safe_path(target):
            return {"status": "error", "message": f"Access denied: Cannot delete path '{target}' outside sandbox."}

        if not os.path.exists(target):
            return {"status": "error", "message": f"Not found: {target}"}

        if os.path.isfile(target):
            return {"status": "error", "message": f"Path '{target}' is a file. Use delete_file to delete files."}

        if os.listdir(target):
            return {"status": "error", "message": "Folder is not empty. Refusing to delete non-empty folders for safety."}

        os.rmdir(target)
        verified = action_verifier.verify_path_deleted_sync(target)
        return {"status": "success", "message": f"Deleted folder: {os.path.basename(target)}", "verified": verified}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def get_folder_contents(path: str = None, **kwargs) -> dict:
    """
    List the contents of a directory.
    
    Args:
        path: Directory path or special folder (e.g. 'Desktop', 'Downloads', 'Documents').
    """
    try:
        raw_path = path or kwargs.get("folder") or kwargs.get("directory") or "Desktop"
        target_dir = _resolve_user_path(raw_path)
            
        if not security_guard.is_safe_path(target_dir):
            return {"status": "error", "message": f"Access denied: Path '{target_dir}' is outside sandbox."}
        
        if not os.path.isdir(target_dir):
            return {"status": "error", "message": f"Directory not found: {target_dir}"}
        
        items = []
        for item in sorted(os.listdir(target_dir)):
            full_path = os.path.join(target_dir, item)
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
            "path": target_dir,
            "contents": items[:50],
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
                    "description": "Directory to search in (e.g. 'Desktop', 'Downloads', 'Documents'). Defaults to user home.",
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
                    "description": "Full path or relative path to the file to open (e.g., 'Downloads/report.pdf', 'Desktop/notes.txt')",
                }
            },
            "required": ["path"],
        },
    },
    {
        "name": "open_folder",
        "description": "Open a folder in Windows File Explorer (e.g. 'Downloads', 'Desktop', 'Documents', or any folder path).",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Folder name or path to open (e.g. 'Downloads', 'Desktop', 'Documents', 'C:/Projects')",
                }
            },
            "required": ["path"],
        },
    },
    {
        "name": "create_text_file",
        "description": "Create a new text file (.txt, .md, .py, etc.) with specified content on Desktop or in any directory.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "File path or name to create (e.g. 'Desktop/abc.txt', 'notes.md', 'C:/Users/name/Desktop/test.txt')",
                },
                "content": {
                    "type": "string",
                    "description": "Text content to write into the file",
                }
            },
            "required": ["path"],
        },
    },
    {
        "name": "create_file",
        "description": "Create a new file with specified content at a target path.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "File path to create (e.g. 'Desktop/test.txt', 'Downloads/notes.md')",
                },
                "content": {
                    "type": "string",
                    "description": "Content to write into the file",
                }
            },
            "required": ["path"],
        },
    },
    {
        "name": "read_file",
        "description": "Read and return text content from an existing file.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "File path to read (e.g. 'Desktop/notes.txt', 'Downloads/report.csv')",
                }
            },
            "required": ["path"],
        },
    },
    {
        "name": "create_folder",
        "description": "Create a new directory/folder. DO NOT use for text files. (e.g. 'Desktop/MyFolder', 'Downloads/Project').",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Folder path to create (e.g. 'Desktop/NewFolder', 'Downloads/Project')",
                }
            },
            "required": ["path"],
        },
    },
    {
        "name": "copy_file",
        "description": "Copy a file to a new destination or folder.",
        "parameters": {
            "type": "object",
            "properties": {
                "source": {
                    "type": "string",
                    "description": "Source file path",
                },
                "destination": {
                    "type": "string",
                    "description": "Destination file path or directory",
                },
            },
            "required": ["source", "destination"],
        },
    },
    {
        "name": "rename_file",
        "description": "Rename a file or folder in place.",
        "parameters": {
            "type": "object",
            "properties": {
                "source": {
                    "type": "string",
                    "description": "Current file path",
                },
                "new_name": {
                    "type": "string",
                    "description": "New file name (e.g. 'new_name.txt')",
                },
            },
            "required": ["source", "new_name"],
        },
    },
    {
        "name": "move_file",
        "description": "Move or relocate a file or folder. DANGEROUS: will ask for confirmation.",
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
        "description": "Delete a file. DANGEROUS: will ask for confirmation.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Path to the file to delete",
                }
            },
            "required": ["path"],
        },
    },
    {
        "name": "delete_folder",
        "description": "Delete an empty folder. DANGEROUS: will ask for confirmation.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Path to the empty folder to delete",
                }
            },
            "required": ["path"],
        },
    },
    {
        "name": "get_folder_contents",
        "description": "List all files and folders in a directory (e.g. 'Desktop', 'Downloads', 'Documents').",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Directory path (e.g. 'Desktop', 'Downloads', 'Documents'). Defaults to Desktop.",
                }
            },
        },
    },
]

TOOL_FUNCTIONS = {
    "search_files": search_files,
    "open_file": open_file,
    "open_folder": open_folder,
    "open_directory": open_folder,
    "create_file": create_file,
    "create_text_file": create_text_file,
    "write_file": write_file,
    "read_file": read_file,
    "read_text_file": read_file,
    "create_folder": create_folder,
    "create_directory": create_folder,
    "copy_file": copy_file,
    "rename_file": rename_file,
    "move_file": move_file,
    "delete_file": delete_file,
    "delete_folder": delete_folder,
    "delete_directory": delete_folder,
    "get_folder_contents": get_folder_contents,
    "list_folder": get_folder_contents,
    "list_files": get_folder_contents,
}
