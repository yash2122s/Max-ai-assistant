"""
MAX AI — Skills AST Security Audit Guard
Performs static AST analysis on skill scripts to detect dangerous patterns,
reverse shells, unauthorized dynamic code evaluation, and unsafe system imports.
"""

import ast
import logging
from typing import Dict, Any, List, Set

logger = logging.getLogger("MAXWindowsAgent.SkillsGuard")

# Blocked high-risk modules in untrusted skills
BLOCKED_MODULES: Set[str] = {
    "ctypes",
    "_ctypes",
    "winreg",
}

# Dangerous built-in function names
BLOCKED_CALLS: Set[str] = {
    "eval",
    "exec",
    "__import__",
}


class SkillAstAudit:
    """
    Static security analyzer for skill scripts.
    """

    @classmethod
    def audit_code(cls, source_code: str) -> Dict[str, Any]:
        """
        Audits Python source code string for malicious AST patterns.
        """
        if not source_code or not source_code.strip():
            return {"is_safe": True, "violations": [], "warnings": []}

        try:
            tree = ast.parse(source_code)
        except SyntaxError as syn:
            return {
                "is_safe": False,
                "violations": [f"Syntax error in script: line {syn.lineno} ({syn.msg})"],
                "warnings": []
            }

        violations: List[str] = []
        warnings: List[str] = []

        for node in ast.walk(tree):
            # 1. Check Imports
            if isinstance(node, ast.Import):
                for alias in node.names:
                    mod_root = alias.name.split(".")[0]
                    if mod_root in BLOCKED_MODULES:
                        violations.append(f"Line {node.lineno}: Prohibited import of security-restricted module '{alias.name}'")

            elif isinstance(node, ast.ImportFrom):
                if node.module:
                    mod_root = node.module.split(".")[0]
                    if mod_root in BLOCKED_MODULES:
                        violations.append(f"Line {node.lineno}: Prohibited import from restricted module '{node.module}'")

            # 2. Check Calls
            elif isinstance(node, ast.Call):
                if isinstance(node.func, ast.Name):
                    if node.func.id in BLOCKED_CALLS:
                        violations.append(f"Line {node.lineno}: Prohibited call to dynamic execution function '{node.func.id}()'")

                # Check socket connect patterns
                elif isinstance(node.func, ast.Attribute):
                    if node.func.attr == "connect" and isinstance(node.func.value, ast.Name) and "sock" in node.func.value.id:
                        warnings.append(f"Line {node.lineno}: External socket connection detected")

        is_safe = len(violations) == 0
        return {
            "is_safe": is_safe,
            "violations": violations,
            "warnings": warnings,
            "risk_level": "LOW" if is_safe and not warnings else ("MEDIUM" if is_safe else "HIGH")
        }


# Singleton helper
def audit_skill_script(source_code: str) -> Dict[str, Any]:
    """Helper to perform AST security audit on skill code."""
    return SkillAstAudit.audit_code(source_code)
