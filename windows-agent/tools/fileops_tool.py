"""
MAX AI — FileOps Tool Compatibility Adapter
Consolidates legacy FileOpsTool requests into the authoritative tools.file_manager controller.
"""

import os
import logging
from typing import Dict, Any, Optional

from .base_tool import BaseTool
from core.security import security_guard
from tools import file_manager

logger = logging.getLogger("MAXWindowsAgent.FileOps")


class FileOpsHelper:
    """
    Adapter helper routing legacy FileOpsHelper calls to authoritative file_manager.
    """

    def __init__(self):
        self.user_profile = os.path.abspath(os.path.expanduser("~"))

    def is_path_safe(self, path: str) -> bool:
        return security_guard.is_safe_path(path)

    def _ensure_safe(self, path: str):
        security_guard.ensure_safe_path(path)

    def read_file(self, path: str, encoding: str = "utf-8", max_size_mb: int = 5) -> dict:
        res = file_manager.read_file(path, encoding=encoding)
        if res.get("status") == "success":
            return {
                "status": "success",
                "output": {
                    "path": res["path"],
                    "content": res["content"],
                    "size_bytes": res["size_bytes"],
                    "encoding": res["encoding"]
                }
            }
        return {"status": "failed", "error": res.get("message", "Read error")}

    def write_file(self, path: str, content: str, encoding: str = "utf-8", append: bool = False) -> dict:
        res = file_manager.write_file(path, content, append=append, encoding=encoding)
        if res.get("status") == "success":
            action = "Appended to" if append else "Written"
            return {"status": "success", "message": f"{action} {res['path']} ({res.get('bytes_written', 0)} bytes)"}
        return {"status": "failed", "error": res.get("message", "Write error")}

    def copy(self, source: str, dest: str, overwrite: bool = False) -> dict:
        res = file_manager.copy_file(source, dest, overwrite=overwrite)
        if res.get("status") == "success":
            return {"status": "success", "message": res["message"]}
        return {"status": "failed", "error": res.get("message", "Copy error")}

    def move(self, source: str, dest: str, overwrite: bool = False) -> dict:
        res = file_manager.move_file(source, dest, overwrite=overwrite)
        if res.get("status") == "success":
            return {"status": "success", "message": res["message"]}
        return {"status": "failed", "error": res.get("message", "Move error")}

    def delete(self, path: str, permanent: bool = False) -> dict:
        res = file_manager.delete_file(path)
        if res.get("status") == "success":
            return {"status": "success", "message": res["message"]}
        return {"status": "failed", "error": res.get("message", "Delete error")}

    def rename(self, path: str, new_name: str) -> dict:
        res = file_manager.rename_file(path, new_name)
        if res.get("status") == "success":
            return {"status": "success", "message": res["message"]}
        return {"status": "failed", "error": res.get("message", "Rename error")}

    def list_directory(self, path: str = ".", detailed: bool = False) -> dict:
        res = file_manager.get_folder_contents(path)
        if res.get("status") == "success":
            return {
                "status": "success",
                "output": {
                    "path": res["path"],
                    "entries": res["contents"],
                    "total_files": res["total_files"],
                    "total_dirs": res["total_dirs"]
                }
            }
        return {"status": "failed", "error": res.get("message", "List error")}

    def create_directory(self, path: str) -> dict:
        res = file_manager.create_folder(path)
        if res.get("status") == "success":
            return {"status": "success", "message": res["message"]}
        return {"status": "failed", "error": res.get("message", "Create directory error")}

    def get_file_info(self, path: str) -> dict:
        res = file_manager.get_file_info(path)
        if res.get("status") == "success":
            return {"status": "success", "output": res.get("output", res)}
        return {"status": "failed", "error": res.get("message", "File info error")}

    def compress(self, source: str, dest: str = "") -> dict:
        res = file_manager.create_archive(source, output_path=dest)
        if res.get("status") == "success":
            return {"status": "success", "message": res["message"]}
        return {"status": "failed", "error": res.get("message", "Compress error")}

    def extract(self, archive: str, dest: str = "") -> dict:
        res = file_manager.extract_archive(archive, destination=dest)
        if res.get("status") == "success":
            return {"status": "success", "message": res["message"]}
        return {"status": "failed", "error": res.get("message", "Extract error")}


fileops_helper = FileOpsHelper()


class FileOpsTool(BaseTool):
    """
    WebSocket/HTTP BaseTool adapter routing all filesystem requests to authoritative file_manager.
    """

    @property
    def name(self) -> str:
        return "fileops"

    async def execute(self, action: str, arguments: dict, send_progress) -> dict:
        await send_progress(f"Running fileops/{action}...")
        try:
            act = (action or "").lower().strip()

            if act == "read":
                path = arguments.get("path", arguments.get("file", ""))
                encoding = arguments.get("encoding", None)
                start_line = arguments.get("start_line")
                end_line = arguments.get("end_line")
                tail = arguments.get("tail")
                if not path:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' parameter"}}
                res = file_manager.read_file(path, start_line=start_line, end_line=end_line, tail=tail, encoding=encoding)
                if res.get("status") == "success":
                    return {"status": "success", "output": res}
                return {"status": "failed", "error": res.get("message", "Read error")}

            elif act in ("write", "create"):
                path = arguments.get("path", arguments.get("file", ""))
                content = arguments.get("content", arguments.get("data", arguments.get("text", "")))
                encoding = arguments.get("encoding", "utf-8")
                append = arguments.get("append", False)
                if isinstance(append, str):
                    append = append.lower() == "true"
                if not path:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' parameter"}}
                res = file_manager.write_file(path, content, encoding=encoding, append=append)
                if res.get("status") == "success":
                    return {"status": "success", "message": res["message"], "output": res}
                return {"status": "failed", "error": res.get("message", "Write error")}

            elif act == "append":
                path = arguments.get("path", arguments.get("file", ""))
                content = arguments.get("content", arguments.get("data", arguments.get("text", "")))
                if not path:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' parameter"}}
                res = file_manager.write_file(path, content, append=True)
                if res.get("status") == "success":
                    return {"status": "success", "message": res["message"]}
                return {"status": "failed", "error": res.get("message", "Append error")}

            elif act == "copy":
                source = arguments.get("source", arguments.get("from", ""))
                dest = arguments.get("dest", arguments.get("to", arguments.get("destination", "")))
                overwrite = arguments.get("overwrite", False)
                if isinstance(overwrite, str):
                    overwrite = overwrite.lower() == "true"
                if not source or not dest:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'source' or 'dest' parameter"}}
                res = file_manager.copy_file(source, dest, overwrite=overwrite)
                if res.get("status") == "success":
                    return {"status": "success", "message": res["message"]}
                return {"status": "failed", "error": res.get("message", "Copy error")}

            elif act == "move":
                source = arguments.get("source", arguments.get("from", ""))
                dest = arguments.get("dest", arguments.get("to", arguments.get("destination", "")))
                overwrite = arguments.get("overwrite", False)
                if isinstance(overwrite, str):
                    overwrite = overwrite.lower() == "true"
                if not source or not dest:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'source' or 'dest' parameter"}}
                res = file_manager.move_file(source, dest, overwrite=overwrite)
                if res.get("status") == "success":
                    return {"status": "success", "message": res["message"]}
                return {"status": "failed", "error": res.get("message", "Move error")}

            elif act == "delete":
                path = arguments.get("path", arguments.get("file", ""))
                if not path:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' parameter"}}
                res = file_manager.delete_file(path)
                if res.get("status") == "success":
                    return {"status": "success", "message": res["message"]}
                return {"status": "failed", "error": res.get("message", "Delete error")}

            elif act == "rename":
                path = arguments.get("path", arguments.get("file", ""))
                new_name = arguments.get("new_name", arguments.get("name", ""))
                if not path or not new_name:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' or 'new_name' parameter"}}
                res = file_manager.rename_file(path, new_name)
                if res.get("status") == "success":
                    return {"status": "success", "message": res["message"]}
                return {"status": "failed", "error": res.get("message", "Rename error")}

            elif act in ("list", "dir", "ls"):
                path = arguments.get("path", ".")
                res = file_manager.get_folder_contents(path)
                if res.get("status") == "success":
                    return {"status": "success", "output": res}
                return {"status": "failed", "error": res.get("message", "List error")}

            elif act in ("mkdir", "createdir"):
                path = arguments.get("path", arguments.get("name", ""))
                if not path:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' parameter"}}
                res = file_manager.create_folder(path)
                if res.get("status") == "success":
                    return {"status": "success", "message": res["message"]}
                return {"status": "failed", "error": res.get("message", "Create directory error")}

            elif act == "info":
                path = arguments.get("path", arguments.get("file", ""))
                if not path:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' parameter"}}
                res = file_manager.get_file_info(path)
                if res.get("status") == "success":
                    return {"status": "success", "output": res}
                return {"status": "failed", "error": res.get("message", "Info error")}

            elif act == "hash":
                path = arguments.get("path", arguments.get("file", ""))
                algo = arguments.get("algorithm", "sha256")
                if not path:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' parameter"}}
                res = file_manager.get_file_hash(path, algorithm=algo)
                if res.get("status") == "success":
                    return {"status": "success", "output": res}
                return {"status": "failed", "error": res.get("message", "Hash error")}

            elif act in ("storage", "disk"):
                drive = arguments.get("drive", arguments.get("path", "C:"))
                return file_manager.get_disk_storage(drive)

            elif act in ("duplicates", "find_duplicates"):
                directory = arguments.get("directory", "Desktop")
                return file_manager.find_duplicate_files(directory)

            elif act == "compress":
                source = arguments.get("source", arguments.get("path", ""))
                dest = arguments.get("dest", arguments.get("to", ""))
                if not source:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'source' parameter"}}
                res = file_manager.create_archive(source, output_path=dest)
                if res.get("status") == "success":
                    return {"status": "success", "message": res["message"]}
                return {"status": "failed", "error": res.get("message", "Compress error")}

            elif act == "extract":
                archive = arguments.get("path", arguments.get("archive", ""))
                dest = arguments.get("dest", arguments.get("to", ""))
                if not archive:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' parameter"}}
                res = file_manager.extract_archive(archive, destination=dest)
                if res.get("status") == "success":
                    return {"status": "success", "message": res["message"]}
                return {"status": "failed", "error": res.get("message", "Extract error")}

            else:
                return {
                    "status": "failed",
                    "error": {"code": "UNSUPPORTED_ACTION", "message": f"Unknown fileops action: {action}"}
                }
        except PermissionError as pe:
            return {"status": "failed", "error": {"code": "PERMISSION_DENIED", "message": str(pe)}}
        except Exception as e:
            logger.error(f"FileOpsTool error: {e}", exc_info=True)
            return {"status": "failed", "error": {"code": "EXECUTION_EXCEPTION", "message": str(e)}}
