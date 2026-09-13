"""
MAX AI — Central Security & Validation Module
Enforces filesystem sandboxing, command validation, risk classification,
and secret masking across all Windows Agent tool interfaces.
"""

import os
import re
import shlex
import logging
from typing import Tuple, List, Optional, Set

logger = logging.getLogger("MAXWindowsAgent.Security")

# Allowed root paths for filesystem operations (User profile and Project root)
USER_PROFILE = os.path.abspath(os.path.realpath(os.path.expanduser("~")))
PROJECT_ROOT = os.path.abspath(os.path.realpath(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

ALLOWED_ROOTS = [
    USER_PROFILE,
    PROJECT_ROOT
]

# Sensitive patterns that must never be exposed in logs or command outputs
STANDALONE_SECRET_PATTERNS = [
    re.compile(r'\bAIza[0-9A-Za-z-_]{35}\b'),
]

KEY_VALUE_SECRET_PATTERNS = [
    re.compile(r'(?i)(api[_-]?key\s*[:=]\s*["\']?)(AIza[0-9A-Za-z-_]{35}|[A-Za-z0-9-_]{20,})'),
    re.compile(r'(?i)(token\s*[:=]\s*["\']?)([A-Za-z0-9-_]{16,})'),
    re.compile(r'(?i)(password\s*[:=]\s*["\']?)([^\s"\']+)'),
    re.compile(r'(?i)(secret\s*[:=]\s*["\']?)([A-Za-z0-9-_]{16,})')
]



# Dangerous destructive commands that must be blocked or require explicit native confirmation
CRITICAL_COMMAND_PATTERNS = [
    r'(?i)\bformat\s+[a-z]:',
    r'(?i)\bdiskpart\b',
    r'(?i)\brmdir\s+/[sq]\s+[a-z]:\\',
    r'(?i)\bdel\s+/[fsq]\s+[a-z]:\\',
    r'(?i)\bremove-item\s+.*-recurse.*-force\s+[a-z]:\\',
    r'(?i)\breg\s+delete\s+hklm',
    r'(?i)\bshutdown\s+/[srf]',
    r'(?i)\bcd\s+/\s*&&\s*rm\b',
    r'(?i)\btaskkill\s+/[ft]\s+/im\s+(csrss|lsass|smss|services|wininit)\.exe'
]

# Known deterministic safe commands
SAFE_COMMANDS = frozenset({
    "dir", "echo", "cls", "ipconfig", "systeminfo", "hostname", "whoami",
    "ping", "tracert", "nslookup", "tasklist", "netstat", "wmic",
    "where", "date", "time", "ver", "vol", "route", "arp"
})

# Developer/Tool commands that are allowed under controlled execution
DEV_COMMANDS = frozenset({
    "python", "py", "node", "npm", "npx", "git", "gradlew", "gradle",
    "cargo", "rustc", "go", "pytest", "tsc", "dotnet", "code"
})


class RiskLevel:
    SAFE = "SAFE"
    DEV = "DEV"
    CONFIRM_REQUIRED = "CONFIRM_REQUIRED"
    CRITICAL_BLOCKED = "CRITICAL_BLOCKED"
    DANGEROUS = "DANGEROUS"


class SecurityGuard:
    """
    Central security validator for all MAX operations.
    """

    @staticmethod
    def classify_risk(action_name: str, params: Optional[dict] = None) -> str:
        """
        Classifies risk level for tool/agent action name.
        """
        act = (action_name or "").lower()
        if any(d in act for d in ("shutdown", "restart", "delete", "format", "rmdir", "erase", "wipe")):
            return RiskLevel.DANGEROUS
        return RiskLevel.SAFE



    @staticmethod
    def is_safe_path(path: str, allow_system_executables: bool = False) -> bool:
        """
        Validates whether a path is safely within the allowed user/workspace sandbox.
        Prevents relative traversal, UNC paths, and symlink escapes.
        """
        if not path or not isinstance(path, str):
            return False

        # Reject dangerous UNC paths (e.g. \\attacker\share)
        if path.startswith(r"\\") or path.startswith("//"):
            return False

        try:
            # Expand environment variables and user home
            expanded = os.path.expandvars(os.path.expanduser(path))
            
            # Canonicalize path resolving symlinks and relative tokens
            if os.path.exists(expanded):
                abs_path = os.path.abspath(os.path.realpath(expanded))
            else:
                # If path doesn't exist yet (e.g. write/create), resolve parent
                parent_dir = os.path.dirname(expanded) or "."
                real_parent = os.path.abspath(os.path.realpath(parent_dir))
                abs_path = os.path.join(real_parent, os.path.basename(expanded))

            # Case-insensitive comparison for Windows
            abs_path_lower = abs_path.lower()

            # Check sandbox containment
            for root in ALLOWED_ROOTS:
                root_lower = root.lower()
                if abs_path_lower == root_lower or abs_path_lower.startswith(root_lower + os.sep):
                    return True

            # Optional: Allow reading/launching standard system executables in Windows/Program Files
            if allow_system_executables and abs_path_lower.endswith(".exe"):
                # Disallow raw shell binaries as target 'executables'
                base_name = os.path.basename(abs_path_lower)
                if base_name in ("cmd.exe", "powershell.exe", "powershell_ise.exe", "wscript.exe", "cscript.exe"):
                    return False

                allowed_system_roots = [
                    os.environ.get("ProgramFiles", r"C:\Program Files").lower(),
                    os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)").lower(),
                    os.path.join(os.environ.get("SystemRoot", r"C:\Windows"), "System32").lower(),
                    os.environ.get("SystemRoot", r"C:\Windows").lower()
                ]
                for sys_root in allowed_system_roots:
                    if abs_path_lower.startswith(sys_root + os.sep) or abs_path_lower == sys_root:
                        return True

            return False
        except Exception as e:
            logger.warning(f"Path validation error for '{path}': {e}")
            return False

    @staticmethod
    def ensure_safe_path(path: str, allow_system_executables: bool = False) -> str:
        """
        Validates path and returns normalized absolute path.
        Raises PermissionError if path violates the sandbox.
        """
        if not SecurityGuard.is_safe_path(path, allow_system_executables=allow_system_executables):
            raise PermissionError(f"Access denied: Path '{path}' is outside the authorized MAX workspace sandbox.")
        
        expanded = os.path.expandvars(os.path.expanduser(path))
        return os.path.abspath(os.path.realpath(expanded) if os.path.exists(expanded) else expanded)

    @staticmethod
    def classify_command_risk(command: str) -> str:
        """
        Classifies a terminal command into 'SAFE', 'DEV', 'CONFIRM_REQUIRED', or 'CRITICAL_BLOCKED'.
        """
        if not command or not command.strip():
            return "SAFE"

        cmd_clean = command.strip()

        # Check for critical destructive patterns
        for pattern in CRITICAL_COMMAND_PATTERNS:
            if re.search(pattern, cmd_clean):
                return "CRITICAL_BLOCKED"

        # Check command keywords
        cmd_lower = cmd_clean.lower()
        if any(kw in cmd_lower for kw in ["shutdown", "format ", "rmdir /s", "del /f", "taskkill /f", "remove-item -recurse"]):
            return "CONFIRM_REQUIRED"

        # Extract base executable name
        try:
            parts = shlex.split(cmd_clean, posix=False)
            base_cmd = os.path.basename(parts[0]).lower().replace(".exe", "") if parts else ""
        except Exception:
            parts = cmd_clean.split()
            base_cmd = parts[0].lower().replace(".exe", "") if parts else ""

        if base_cmd in SAFE_COMMANDS:
            # If it's a simple safe command without shell chaining characters, it is safe
            if not any(char in cmd_clean for char in ["&", "|", ";", "`", "$"]):
                return "SAFE"
            return "DEV"

        if base_cmd in DEV_COMMANDS:
            return "DEV"

        return "CONFIRM_REQUIRED"

    @staticmethod
    def scan_threat_patterns(command: str) -> Tuple[bool, Optional[str]]:
        """
        Scans a command string against security threat patterns (reverse shells, exfiltration).
        Returns (is_threat, description).
        """
        if not command or not command.strip():
            return False, None

        cmd = command.strip().lower()
        threat_patterns = [
            (r"\b(nc|ncat|netcat)\b.*-e\s+", "Reverse shell payload via netcat"),
            (r"/dev/tcp/\d+\.\d+\.\d+\.\d+/\d+", "Bash direct TCP socket connection"),
            (r"powershell.*-e(?:nc|ncodedcommand)?\s+[A-Za-z0-9+/=]{20,}", "Encoded PowerShell execution"),
            (r"curl\s+.*\|\s*(?:bash|sh|powershell|cmd)", "Direct remote code download and piped execution"),
            (r"wget\s+.*\|\s*(?:bash|sh|powershell|cmd)", "Direct remote code download and piped execution"),
            (r"\bmkfifo\b.*&&.*nc\b", "Named pipe reverse shell"),
            (r":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:", "Fork bomb malicious payload"),
        ]

        for pat, desc in threat_patterns:
            if re.search(pat, cmd):
                return True, desc

        return False, None

    @staticmethod
    def mask_secrets(text: str) -> str:
        """
        Masks API keys, tokens, and credentials in text to prevent log leakage.
        """
        if not text:
            return ""
        masked = text
        for pattern in KEY_VALUE_SECRET_PATTERNS:
            masked = pattern.sub(r'\1***MASKED***', masked)
        for pattern in STANDALONE_SECRET_PATTERNS:
            masked = pattern.sub('***MASKED***', masked)
        return masked


security_guard = SecurityGuard()
