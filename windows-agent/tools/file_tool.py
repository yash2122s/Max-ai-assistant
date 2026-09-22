"""
MAX AI — Legacy File Tool Compatibility Adapter
Delegates all operations to the authoritative controller: tools.file_manager
"""

import os
import logging
from typing import List, Dict, Any, Optional

from core.security import security_guard
from tools import file_manager

logger = logging.getLogger("MAXWindowsAgent.File")


class FileHelper:
    """
    Compatibility adapter for legacy FileHelper invocations.
    All operations are routed through tools.file_manager.
    """

    def __init__(self):
        self.user_profile = os.path.abspath(os.path.expanduser("~"))

    def is_path_safe(self, path: str) -> bool:
        """Validates path safety against sandbox."""
        return security_guard.is_safe_path(path, allow_system_executables=True)

    def search_files(
        self,
        query: str,
        root_path: Optional[str] = None,
        max_results: int = 100,
        max_depth: int = 4,
        timeout: float = 5.0
    ) -> List[Dict[str, Any]]:
        """Delegates file search to authoritative file_manager."""
        res = file_manager.search_files(query, directory=root_path, max_results=max_results)
        return res.get("results", [])

    def open_file(self, file_path: str) -> bool:
        """Delegates file launch to authoritative file_manager."""
        res = file_manager.open_file(file_path)
        if res.get("status") == "error":
            if "Access denied" in str(res.get("message")):
                raise PermissionError(res["message"])
            if "does not exist" in str(res.get("message")) or "not found" in str(res.get("message")):
                raise FileNotFoundError(res["message"])
            raise RuntimeError(res["message"])
        return res.get("status") == "success"


file_helper = FileHelper()
