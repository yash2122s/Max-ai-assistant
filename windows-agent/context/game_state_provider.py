"""
MAX AI — Local Game State Detection (P2-8)
Non-invasive process-based game detection without memory injection or anti-cheat interference.
Exposes detected game state as passive environmental context.
"""

import time
import logging
from enum import Enum
from typing import Dict, Any, Optional, Set

from context.models import ContextRecord, ContextCategory, ContextOrigin

logger = logging.getLogger("MAXWindowsAgent.GameStateProvider")


class GameState(str, Enum):
    NOT_RUNNING = "NOT_RUNNING"
    RUNNING = "RUNNING"
    FOCUSED = "FOCUSED"
    BACKGROUND = "BACKGROUND"
    UNKNOWN = "UNKNOWN"


class LocalGameStateDetector:
    """
    Detects active Steam / Epic / EA / Riot / Blizzard / Xbox game titles
    using non-invasive OS process scanning and foreground window title inspection.
    """

    KNOWN_GAME_PROCESSES: Dict[str, str] = {
        "steam.exe": "Steam Client",
        "epicgameslauncher.exe": "Epic Games Launcher",
        "riotclientservices.exe": "Riot Client",
        "valorant.exe": "Valorant",
        "leagueclient.exe": "League of Legends",
        "cs2.exe": "Counter-Strike 2",
        "csgo.exe": "Counter-Strike: Global Offensive",
        "dota2.exe": "Dota 2",
        "gta5.exe": "Grand Theft Auto V",
        "cyberpunk2077.exe": "Cyberpunk 2077",
        "witcher3.exe": "The Witcher 3",
        "minecraft.exe": "Minecraft",
        "javaw.exe": "Minecraft (Java Edition)",
        "genshinimpact.exe": "Genshin Impact",
        "starrail.exe": "Honkai: Star Rail",
        "fortniteclient-win64-shipping.exe": "Fortnite",
        "rocketleague.exe": "Rocket League",
        "apexlegends.exe": "Apex Legends",
        "r5apex.exe": "Apex Legends",
        "eldenring.exe": "Elden Ring",
        "overwatch.exe": "Overwatch 2",
    }

    def __init__(self, cache_ttl_sec: float = 10.0):
        self.cache_ttl_sec = cache_ttl_sec
        self._last_scan_time: float = 0.0
        self._cached_state: Dict[str, Any] = {
            "state": GameState.NOT_RUNNING.value,
            "detected_games": [],
            "focused_game": None
        }

    def scan_game_state(self, force: bool = False) -> Dict[str, Any]:
        """
        Scans local processes for known game executables.
        Results are cached to prevent CPU overhead.
        """
        now = time.time()
        if not force and (now - self._last_scan_time < self.cache_ttl_sec):
            return self._cached_state

        detected_games = []
        focused_game = None
        current_state = GameState.NOT_RUNNING

        try:
            import psutil
            import win32gui
            import win32process

            # 1. Get foreground window process name
            fg_hwnd = win32gui.GetForegroundWindow()
            fg_proc_name = ""
            fg_window_title = ""
            if fg_hwnd:
                fg_window_title = win32gui.GetWindowText(fg_hwnd)
                try:
                    _, pid = win32process.GetWindowThreadProcessId(fg_hwnd)
                    proc = psutil.Process(pid)
                    fg_proc_name = proc.name().lower()
                except Exception:
                    pass

            # 2. Iterate running processes
            for p in psutil.process_iter(['name']):
                try:
                    p_name = p.info['name'].lower()
                    if p_name in self.KNOWN_GAME_PROCESSES:
                        game_title = self.KNOWN_GAME_PROCESSES[p_name]
                        if game_title not in detected_games:
                            detected_games.append(game_title)

                        if p_name == fg_proc_name or game_title.lower() in fg_window_title.lower():
                            focused_game = game_title
                except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
                    continue

            if focused_game:
                current_state = GameState.FOCUSED
            elif detected_games:
                current_state = GameState.BACKGROUND
            else:
                current_state = GameState.NOT_RUNNING

        except Exception as e:
            logger.debug(f"Game state detection notice: {e}")
            current_state = GameState.UNKNOWN

        self._last_scan_time = now
        self._cached_state = {
            "state": current_state.value,
            "detected_games": detected_games,
            "focused_game": focused_game,
            "timestamp": now
        }
        return self._cached_state

    def provide_context(self) -> Optional[ContextRecord]:
        """
        Adapts game detection into an active ContextRecord for ContextStore.
        """
        state_data = self.scan_game_state()
        if state_data.get("state") == GameState.NOT_RUNNING.value:
            return None

        return ContextRecord(
            record_id="local_game_state",
            category=ContextCategory.DEVICE_STATE,
            origin=ContextOrigin.LOCAL_WINDOWS,
            payload={
                "game_state": state_data.get("state"),
                "detected_games": state_data.get("detected_games", []),
                "focused_game": state_data.get("focused_game")
            },
            updated_at=time.time(),
            ttl_seconds=30.0
        )


# Global singleton instance
game_state_detector = LocalGameStateDetector()
