import os
import logging

logger = logging.getLogger("MAXWindowsAgent.File")

class FileHelper:
    def __init__(self):
        self.user_profile = os.path.abspath(os.path.expanduser("~"))

    def is_path_safe(self, path: str) -> bool:
        """
        Validates that path resides inside the User Profile directory to prevent traversal.
        Also permits launching system executables inside standard Program Files / System32 paths.
        """
        abs_path = os.path.abspath(os.path.expandvars(os.path.expanduser(path)))
        if abs_path.lower().startswith(self.user_profile.lower()):
            return True
            
        # Allow launching system applications (executables only) in default system paths
        if abs_path.lower().endswith(".exe"):
            if os.path.basename(abs_path).lower() in ("cmd.exe", "powershell.exe"):
                return False
            allowed_system_roots = [
                os.environ.get("ProgramFiles", "C:\\Program Files").lower(),
                os.environ.get("ProgramFiles(x86)", "C:\\Program Files (x86)").lower(),
                os.path.join(os.environ.get("SystemRoot", "C:\\Windows"), "System32").lower(),
                os.environ.get("SystemRoot", "C:\\Windows").lower()
            ]
            for root in allowed_system_roots:
                if abs_path.lower().startswith(root):
                    return True
        return False



    def search_files(self, query: str, root_path: str = None, max_results: int = 100, max_depth: int = 4, timeout: float = 5.0) -> list:
        import time
        start_time = time.time()
        
        if not root_path:
            root_path = self.user_profile
            
        root_path = os.path.abspath(os.path.expandvars(os.path.expanduser(root_path)))
        
        if not self.is_path_safe(root_path):
            raise PermissionError(f"Access denied: Search path {root_path} is outside allowed user profile sandbox.")

        if not os.path.exists(root_path):
            raise FileNotFoundError(f"Search root folder not found: {root_path}")

        matches = []
        query_lower = query.lower()
        excluded_names = {"appdata", "node_modules", ".git", "virtualbox vms", "local settings", "cookies"}
        
        for root, dirs, files in os.walk(root_path):
            # Check timeout limit
            if time.time() - start_time > timeout:
                logger.info(f"File search timed out after {timeout}s. Returning {len(matches)} results.")
                break

            # Enforce max depth constraint
            rel_path = os.path.relpath(root, root_path)
            depth = 0 if rel_path == "." else len(rel_path.split(os.sep))
            if depth >= max_depth:
                dirs.clear()  # Do not walk subdirectories of this directory
                continue

            # Prune hidden/system and excluded directories
            dirs[:] = [d for d in dirs if not d.startswith(".") and not d.startswith("$") and d.lower() not in excluded_names]
            
            for file in files:
                if query_lower in file.lower():
                    full_path = os.path.join(root, file)
                    try:
                        stat = os.stat(full_path)
                        matches.append({
                            "path": full_path,
                            "size_bytes": stat.st_size,
                            "modified_at": int(stat.st_mtime)
                        })
                    except Exception:
                        pass
                        
                    if len(matches) >= max_results:
                        return matches
                        
        return matches

    def open_file(self, file_path: str) -> bool:
        file_path = os.path.abspath(os.path.expandvars(os.path.expanduser(file_path)))
        
        if not self.is_path_safe(file_path):
            raise PermissionError(f"Access denied: Launch target {file_path} is outside allowed user profile sandbox.")

        if not os.path.exists(file_path):
            raise FileNotFoundError(f"Launch target not found: {file_path}")

        logger.info(f"Opening file/application: {file_path}")
        # Safe execution using OS default application handler
        os.startfile(file_path)
        return True

file_helper = FileHelper()
