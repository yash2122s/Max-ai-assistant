import os
import shutil
import logging
import subprocess
import time
from .base_tool import BaseTool

logger = logging.getLogger("MAXWindowsAgent.FileOps")


class FileOpsHelper:
    def __init__(self):
        self.user_profile = os.path.abspath(os.path.expanduser("~"))

    def is_path_safe(self, path: str) -> bool:
        abs_path = os.path.abspath(os.path.expandvars(os.path.expanduser(path)))
        return abs_path.lower().startswith(self.user_profile.lower())

    def _ensure_safe(self, path: str):
        if not self.is_path_safe(path):
            raise PermissionError(f"Access denied: {path} is outside user profile sandbox")

    def read_file(self, path: str, encoding: str = "utf-8", max_size_mb: int = 5) -> dict:
        try:
            abs_path = os.path.abspath(os.path.expandvars(os.path.expanduser(path)))
            self._ensure_safe(abs_path)

            if not os.path.exists(abs_path):
                return {"status": "failed", "error": f"File not found: {path}"}
            if os.path.isdir(abs_path):
                return {"status": "failed", "error": f"Path is a directory: {path}"}

            size_mb = os.path.getsize(abs_path) / (1024 * 1024)
            if size_mb > max_size_mb:
                return {"status": "failed", "error": f"File too large: {size_mb:.1f}MB (limit: {max_size_mb}MB)"}

            with open(abs_path, "r", encoding=encoding) as f:
                content = f.read()

            return {"status": "success", "output": {"path": abs_path, "content": content, "size_bytes": len(content), "encoding": encoding}}
        except PermissionError:
            raise
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def write_file(self, path: str, content: str, encoding: str = "utf-8", append: bool = False) -> dict:
        try:
            abs_path = os.path.abspath(os.path.expandvars(os.path.expanduser(path)))
            self._ensure_safe(abs_path)

            os.makedirs(os.path.dirname(abs_path), exist_ok=True)
            mode = "a" if append else "w"
            with open(abs_path, mode, encoding=encoding) as f:
                f.write(content)

            action = "Appended to" if append else "Written"
            return {"status": "success", "message": f"{action} {abs_path} ({len(content)} bytes)"}
        except PermissionError:
            raise
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def copy(self, source: str, dest: str, overwrite: bool = False) -> dict:
        try:
            src = os.path.abspath(os.path.expandvars(os.path.expanduser(source)))
            dst = os.path.abspath(os.path.expandvars(os.path.expanduser(dest)))
            self._ensure_safe(src)
            self._ensure_safe(dst)

            if not os.path.exists(src):
                return {"status": "failed", "error": f"Source not found: {source}"}

            if os.path.isdir(src):
                shutil.copytree(src, dst, dirs_exist_ok=overwrite)
            else:
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copy2(src, dst)

            return {"status": "success", "message": f"Copied {source} -> {dest}"}
        except PermissionError:
            raise
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def move(self, source: str, dest: str, overwrite: bool = False) -> dict:
        try:
            src = os.path.abspath(os.path.expandvars(os.path.expanduser(source)))
            dst = os.path.abspath(os.path.expandvars(os.path.expanduser(dest)))
            self._ensure_safe(src)
            self._ensure_safe(dst)

            if not os.path.exists(src):
                return {"status": "failed", "error": f"Source not found: {source}"}

            if os.path.isdir(src):
                shutil.copytree(src, dst, dirs_exist_ok=overwrite)
                shutil.rmtree(src)
            else:
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.move(src, dst)

            return {"status": "success", "message": f"Moved {source} -> {dest}"}
        except PermissionError:
            raise
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def delete(self, path: str, permanent: bool = False) -> dict:
        try:
            abs_path = os.path.abspath(os.path.expandvars(os.path.expanduser(path)))
            self._ensure_safe(abs_path)

            if not os.path.exists(abs_path):
                return {"status": "failed", "error": f"Not found: {path}"}

            if permanent:
                if os.path.isdir(abs_path):
                    shutil.rmtree(abs_path)
                else:
                    os.remove(abs_path)
                return {"status": "success", "message": f"Permanently deleted: {path}"}
            else:
                subprocess.run(["powershell", "-NoProfile", "-Command",
                    f"$shell = New-Object -ComObject Shell.Application; $ns = $shell.Namespace(0); $item = $ns.ParseName('{abs_path}'); $item.InvokeVerb('delete')"],
                    capture_output=True, timeout=10)
                return {"status": "success", "message": f"Sent to recycle bin: {path}"}
        except PermissionError:
            raise
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def rename(self, path: str, new_name: str) -> dict:
        try:
            abs_path = os.path.abspath(os.path.expandvars(os.path.expanduser(path)))
            self._ensure_safe(abs_path)

            if not os.path.exists(abs_path):
                return {"status": "failed", "error": f"Not found: {path}"}

            dir_name = os.path.dirname(abs_path)
            new_path = os.path.join(dir_name, new_name)
            os.rename(abs_path, new_path)
            return {"status": "success", "message": f"Renamed {path} -> {new_name}"}
        except PermissionError:
            raise
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def list_directory(self, path: str = ".", detailed: bool = False) -> dict:
        try:
            abs_path = os.path.abspath(os.path.expandvars(os.path.expanduser(path)))
            self._ensure_safe(abs_path)

            if not os.path.exists(abs_path):
                return {"status": "failed", "error": f"Directory not found: {path}"}
            if not os.path.isdir(abs_path):
                return {"status": "failed", "error": f"Not a directory: {path}"}

            entries = []
            for entry in os.listdir(abs_path):
                entry_path = os.path.join(abs_path, entry)
                is_dir = os.path.isdir(entry_path)
                stat_info = os.stat(entry_path) if detailed else None
                entry_info = {
                    "name": entry,
                    "type": "directory" if is_dir else "file",
                    "size_bytes": stat_info.st_size if stat_info else (0 if is_dir else os.path.getsize(entry_path)),
                    "modified_at": int(stat_info.st_mtime) if stat_info else int(os.path.getmtime(entry_path)),
                    "created_at": int(stat_info.st_ctime) if stat_info else int(os.path.getctime(entry_path)),
                }
                entries.append(entry_info)

            entries.sort(key=lambda x: (0 if x["type"] == "directory" else 1, x["name"].lower()))

            return {"status": "success", "output": {
                "path": abs_path,
                "entries": entries,
                "total_files": sum(1 for e in entries if e["type"] == "file"),
                "total_dirs": sum(1 for e in entries if e["type"] == "directory"),
            }}
        except PermissionError:
            raise
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def create_directory(self, path: str) -> dict:
        try:
            abs_path = os.path.abspath(os.path.expandvars(os.path.expanduser(path)))
            self._ensure_safe(abs_path)
            os.makedirs(abs_path, exist_ok=True)
            return {"status": "success", "message": f"Directory created: {path}"}
        except PermissionError:
            raise
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def get_file_info(self, path: str) -> dict:
        try:
            abs_path = os.path.abspath(os.path.expandvars(os.path.expanduser(path)))
            self._ensure_safe(abs_path)

            if not os.path.exists(abs_path):
                return {"status": "failed", "error": f"Not found: {path}"}

            stat_info = os.stat(abs_path)
            is_dir = os.path.isdir(abs_path)

            info = {
                "path": abs_path,
                "name": os.path.basename(abs_path),
                "type": "directory" if is_dir else "file",
                "size_bytes": stat_info.st_size,
                "size_display": self._format_size(stat_info.st_size),
                "created_at": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(stat_info.st_ctime)),
                "modified_at": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(stat_info.st_mtime)),
                "accessed_at": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(stat_info.st_atime)),
                "permissions": oct(stat_info.st_mode)[-3:] if not is_dir else "N/A",
                "hidden": os.path.basename(abs_path).startswith(".") or bool(os.stat(abs_path).st_file_attributes & 2),
            }

            if not is_dir:
                _, ext = os.path.splitext(abs_path)
                info["extension"] = ext.lower()

            return {"status": "success", "output": info}
        except PermissionError:
            raise
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def _format_size(self, bytes_val: int) -> str:
        for unit in ["B", "KB", "MB", "GB", "TB"]:
            if bytes_val < 1024:
                return f"{bytes_val:.1f} {unit}"
            bytes_val /= 1024
        return f"{bytes_val:.1f} PB"

    def compress(self, source: str, dest: str = "") -> dict:
        try:
            src = os.path.abspath(os.path.expandvars(os.path.expanduser(source)))
            self._ensure_safe(src)

            if not os.path.exists(src):
                return {"status": "failed", "error": f"Source not found: {source}"}

            if not dest:
                dest = src + ".zip"
            dst = os.path.abspath(os.path.expandvars(os.path.expanduser(dest)))
            self._ensure_safe(dst)

            src_parent = os.path.dirname(src)
            src_name = os.path.basename(src)

            subprocess.run(["powershell", "-NoProfile", "-Command", f"""
                Compress-Archive -Path '{src}' -DestinationPath '{dst}' -Force -ErrorAction Stop
                Write-Output 'success'
            """], capture_output=True, text=True, timeout=60)

            return {"status": "success", "message": f"Compressed {source} -> {dest}"}
        except PermissionError:
            raise
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def extract(self, archive: str, dest: str = "") -> dict:
        try:
            src = os.path.abspath(os.path.expandvars(os.path.expanduser(archive)))
            self._ensure_safe(src)

            if not os.path.exists(src):
                return {"status": "failed", "error": f"Archive not found: {archive}"}

            if not dest:
                dest = os.path.splitext(src)[0]
            dst = os.path.abspath(os.path.expandvars(os.path.expanduser(dest)))
            self._ensure_safe(dst)

            subprocess.run(["powershell", "-NoProfile", "-Command", f"""
                Expand-Archive -Path '{src}' -DestinationPath '{dst}' -Force -ErrorAction Stop
                Write-Output 'success'
            """], capture_output=True, text=True, timeout=60)

            return {"status": "success", "message": f"Extracted {archive} -> {dest}"}
        except PermissionError:
            raise
        except Exception as e:
            return {"status": "failed", "error": str(e)}


fileops_helper = FileOpsHelper()


class FileOpsTool(BaseTool):
    @property
    def name(self) -> str:
        return "fileops"

    async def execute(self, action: str, arguments: dict, send_progress) -> dict:
        await send_progress(f"Running fileops/{action}...")
        try:
            if action == "read":
                path = arguments.get("path", arguments.get("file", ""))
                encoding = arguments.get("encoding", "utf-8")
                max_size = int(arguments.get("max_size_mb", 5))
                if not path:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' parameter"}}
                return fileops_helper.read_file(path, encoding, max_size)

            elif action == "write":
                path = arguments.get("path", arguments.get("file", ""))
                content = arguments.get("content", arguments.get("data", arguments.get("text", "")))
                encoding = arguments.get("encoding", "utf-8")
                append = arguments.get("append", False)
                if isinstance(append, str):
                    append = append.lower() == "true"
                if not path:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' parameter"}}
                return fileops_helper.write_file(path, content, encoding, append)

            elif action == "append":
                path = arguments.get("path", arguments.get("file", ""))
                content = arguments.get("content", arguments.get("data", arguments.get("text", "")))
                if not path:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' parameter"}}
                return fileops_helper.write_file(path, content, append=True)

            elif action == "copy":
                source = arguments.get("source", arguments.get("from", ""))
                dest = arguments.get("dest", arguments.get("to", arguments.get("destination", "")))
                overwrite = arguments.get("overwrite", False)
                if isinstance(overwrite, str):
                    overwrite = overwrite.lower() == "true"
                if not source or not dest:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'source' or 'dest' parameter"}}
                return fileops_helper.copy(source, dest, overwrite)

            elif action == "move":
                source = arguments.get("source", arguments.get("from", ""))
                dest = arguments.get("dest", arguments.get("to", arguments.get("destination", "")))
                overwrite = arguments.get("overwrite", False)
                if isinstance(overwrite, str):
                    overwrite = overwrite.lower() == "true"
                if not source or not dest:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'source' or 'dest' parameter"}}
                return fileops_helper.move(source, dest, overwrite)

            elif action == "delete":
                path = arguments.get("path", arguments.get("file", ""))
                permanent = arguments.get("permanent", False)
                if isinstance(permanent, str):
                    permanent = permanent.lower() == "true"
                if not path:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' parameter"}}
                return fileops_helper.delete(path, permanent)

            elif action == "rename":
                path = arguments.get("path", arguments.get("file", ""))
                new_name = arguments.get("new_name", arguments.get("name", ""))
                if not path or not new_name:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' or 'new_name' parameter"}}
                return fileops_helper.rename(path, new_name)

            elif action in ("list", "dir", "ls"):
                path = arguments.get("path", ".")
                detailed = arguments.get("detailed", False)
                if isinstance(detailed, str):
                    detailed = detailed.lower() == "true"
                return fileops_helper.list_directory(path, detailed)

            elif action in ("mkdir", "createdir"):
                path = arguments.get("path", arguments.get("name", ""))
                if not path:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' parameter"}}
                return fileops_helper.create_directory(path)

            elif action == "info":
                path = arguments.get("path", arguments.get("file", ""))
                if not path:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' parameter"}}
                return fileops_helper.get_file_info(path)

            elif action == "compress":
                source = arguments.get("source", arguments.get("path", ""))
                dest = arguments.get("dest", arguments.get("to", ""))
                if not source:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'source' parameter"}}
                return fileops_helper.compress(source, dest)

            elif action == "extract":
                archive = arguments.get("path", arguments.get("archive", ""))
                dest = arguments.get("dest", arguments.get("to", ""))
                if not archive:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' parameter"}}
                return fileops_helper.extract(archive, dest)

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
