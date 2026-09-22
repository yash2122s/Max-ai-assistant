"""
MAX UI — Computer Use Workspace
Visualizes live desktop automation, screen perception, and interactive action pipeline.
"""

from typing import Optional
from PyQt6.QtCore import Qt, QThread, pyqtSignal, pyqtSlot
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QFrame, QSplitter,
    QTextEdit, QPushButton, QLineEdit
)

from ..components.computer_view import ComputerView
from ..models.computer_use_models import ComputerUseState, ActionPhase
from ..services.state_store import state_store
from ..services.ui_bridge_client import bridge_client


class DirectObservationWorker(QThread):
    """Fallback worker to directly observe screen if UIBridge backend is offline."""
    observation_finished = pyqtSignal(dict)

    def __init__(self, include_ocr: bool = True, parent=None):
        super().__init__(parent)
        self.include_ocr = include_ocr

    def run(self):
        try:
            from core.computer_use.computer_use_engine import computer_use_engine
            res = computer_use_engine.observe_screen(include_ocr=self.include_ocr)
            self.observation_finished.emit(res)
        except Exception as e:
            self.observation_finished.emit({"status": "error", "error": str(e)})


class ComputerUseWorkspace(QWidget):
    """
    Renders computer-use observation frame, bounding boxes, target crosshairs,
    action pipeline status, and interactive controls to observe and control the PC.
    """

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("ComputerUseWorkspace")
        self._obs_worker: Optional[DirectObservationWorker] = None
        self._init_ui()

        state_store.state.computer_use_updated.connect(self._on_computer_use_updated)

    def _init_ui(self):
        main_layout = QVBoxLayout(self)
        main_layout.setContentsMargins(16, 12, 16, 16)
        main_layout.setSpacing(10)

        # ── 1. Pipeline Status Phase Header ──────────────────────────────────
        pipeline_frame = QFrame()
        pipeline_frame.setStyleSheet("""
            QFrame {
                background-color: rgba(17, 24, 39, 0.7);
                border: 1px solid rgba(56, 189, 248, 0.15);
                border-radius: 10px;
                padding: 6px;
            }
        """)
        pipe_layout = QHBoxLayout(pipeline_frame)
        pipe_layout.setContentsMargins(8, 4, 8, 4)

        self.phase_labels = {}
        phases = [
            ("OBSERVE", "👁️ Observe"),
            ("RESOLVE", "🎯 Resolve"),
            ("SAFETY", "🛡️ Safety"),
            ("ACT", "⚡ Act"),
            ("VERIFY", "✓ Verify"),
        ]

        for code, label in phases:
            lbl = QLabel(label)
            lbl.setStyleSheet("color: #6B7280; font-weight: bold; font-size: 11px; padding: 4px 10px;")
            self.phase_labels[code] = lbl
            pipe_layout.addWidget(lbl)
            if code != "VERIFY":
                arrow = QLabel("→")
                arrow.setStyleSheet("color: #374151; font-weight: bold;")
                pipe_layout.addWidget(arrow)

        pipe_layout.addStretch()

        # Elements counter badge
        self.elements_badge = QLabel("0 Elements")
        self.elements_badge.setStyleSheet("""
            background-color: rgba(56, 189, 248, 0.12);
            color: #38BDF8;
            border: 1px solid rgba(56, 189, 248, 0.3);
            border-radius: 6px;
            padding: 3px 8px;
            font-size: 10.5px;
            font-weight: bold;
        """)
        pipe_layout.addWidget(self.elements_badge)

        main_layout.addWidget(pipeline_frame)

        # ── 2. Interactive Control Bar ───────────────────────────────────────
        control_frame = QFrame()
        control_frame.setStyleSheet("""
            QFrame {
                background-color: rgba(17, 24, 39, 0.85);
                border: 1px solid rgba(56, 189, 248, 0.2);
                border-radius: 10px;
                padding: 6px 10px;
            }
        """)
        ctrl_layout = QHBoxLayout(control_frame)
        ctrl_layout.setContentsMargins(4, 4, 4, 4)
        ctrl_layout.setSpacing(8)

        # Primary Observe Button
        self.observe_btn = QPushButton("👁️ Observe Screen")
        self.observe_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.observe_btn.setStyleSheet("""
            QPushButton {
                background-color: rgba(0, 240, 255, 0.15);
                color: #00F0FF;
                border: 1px solid #00F0FF;
                border-radius: 6px;
                padding: 6px 14px;
                font-weight: bold;
                font-size: 12px;
            }
            QPushButton:hover {
                background-color: rgba(0, 240, 255, 0.3);
                border-color: #38BDF8;
            }
            QPushButton:pressed {
                background-color: rgba(0, 240, 255, 0.4);
            }
        """)
        self.observe_btn.clicked.connect(self._trigger_observe)
        ctrl_layout.addWidget(self.observe_btn)

        # Target Query / Action Input
        self.target_input = QLineEdit()
        self.target_input.setPlaceholderText("Describe UI target or action (e.g., 'Search button', 'Start', 'Chrome tab')...")
        self.target_input.setStyleSheet("""
            QLineEdit {
                background-color: #0B0F19;
                border: 1px solid rgba(56, 189, 248, 0.25);
                border-radius: 6px;
                padding: 6px 10px;
                color: #F9FAFB;
                font-size: 12px;
            }
            QLineEdit:focus {
                border-color: #00F0FF;
            }
        """)
        self.target_input.returnPressed.connect(self._trigger_resolve)
        ctrl_layout.addWidget(self.target_input, 1)

        # Locate Button
        self.locate_btn = QPushButton("🎯 Locate")
        self.locate_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.locate_btn.setStyleSheet("""
            QPushButton {
                background-color: rgba(56, 189, 248, 0.15);
                color: #38BDF8;
                border: 1px solid rgba(56, 189, 248, 0.3);
                border-radius: 6px;
                padding: 6px 12px;
                font-weight: 600;
                font-size: 11.5px;
            }
            QPushButton:hover {
                background-color: rgba(56, 189, 248, 0.25);
                border-color: #38BDF8;
            }
        """)
        self.locate_btn.clicked.connect(self._trigger_resolve)
        ctrl_layout.addWidget(self.locate_btn)

        # Execute Click Button
        self.click_btn = QPushButton("⚡ Click Target")
        self.click_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.click_btn.setStyleSheet("""
            QPushButton {
                background-color: rgba(16, 185, 129, 0.15);
                color: #10B981;
                border: 1px solid #10B981;
                border-radius: 6px;
                padding: 6px 12px;
                font-weight: 600;
                font-size: 11.5px;
            }
            QPushButton:hover {
                background-color: rgba(16, 185, 129, 0.25);
            }
        """)
        self.click_btn.clicked.connect(self._trigger_click)
        ctrl_layout.addWidget(self.click_btn)

        main_layout.addWidget(control_frame)

        # ── 3. Main Viewport & Action Details Splitter ───────────────────────
        splitter = QSplitter(Qt.Orientation.Vertical)

        # Computer View Canvas
        self.computer_view = ComputerView()
        self.computer_view.coordinates_clicked.connect(self._on_canvas_clicked)
        splitter.addWidget(self.computer_view)

        # Status & Action Log Card
        details_frame = QFrame()
        details_frame.setStyleSheet("""
            QFrame {
                background-color: rgba(17, 24, 39, 0.85);
                border: 1px solid rgba(56, 189, 248, 0.2);
                border-radius: 10px;
                padding: 8px;
            }
        """)
        details_layout = QVBoxLayout(details_frame)
        details_layout.setContentsMargins(8, 8, 8, 8)
        details_layout.setSpacing(6)

        hdr_row = QHBoxLayout()
        self.status_title = QLabel("Action Authority: Standby")
        self.status_title.setStyleSheet("font-weight: bold; color: #38BDF8; font-size: 12px;")
        hdr_row.addWidget(self.status_title)

        self.coord_label = QLabel("Click screen to select coordinates")
        self.coord_label.setStyleSheet("color: #6B7280; font-size: 11px;")
        hdr_row.addWidget(self.coord_label)

        hdr_row.addStretch()

        self.app_info = QLabel("App: Desktop")
        self.app_info.setStyleSheet("color: #9CA3AF; font-size: 11px;")
        hdr_row.addWidget(self.app_info)
        details_layout.addLayout(hdr_row)

        self.action_log = QTextEdit()
        self.action_log.setReadOnly(True)
        self.action_log.setFixedHeight(85)
        self.action_log.setStyleSheet("""
            QTextEdit {
                background-color: #0B0F19;
                border: 1px solid rgba(56, 189, 248, 0.15);
                border-radius: 6px;
                color: #F9FAFB;
                font-family: 'Consolas', monospace;
                font-size: 11px;
            }
        """)
        self.action_log.setPlaceholderText("Computer-use step log and verification output will appear here...")
        details_layout.addWidget(self.action_log)

        splitter.addWidget(details_frame)
        splitter.setStretchFactor(0, 4)
        splitter.setStretchFactor(1, 1)

        main_layout.addWidget(splitter)

    def _trigger_observe(self):
        """Dispatches screen observation request via UIBridge or direct worker fallback."""
        self.status_title.setText("Action: Observing Screen | Phase: OBSERVE")
        self.action_log.append("[OBSERVE] Capturing desktop screen and analyzing UI elements...")
        self._highlight_phase("OBSERVE")

        if state_store.state.is_connected:
            bridge_client.request_observe_screen(include_ocr=True)
        else:
            # Fallback to local background worker
            self._obs_worker = DirectObservationWorker(include_ocr=True)
            self._obs_worker.observation_finished.connect(self._on_direct_obs_finished)
            self._obs_worker.start()

    def _on_direct_obs_finished(self, res: dict):
        if res.get("status") == "success":
            shot_path = res.get("screenshot_path")
            if shot_path:
                self.computer_view.set_screenshot_file(shot_path)
            self.action_log.append(f"[OBSERVE] Captured {res.get('element_count', 0)} elements in '{res.get('active_window', 'Desktop')}'")
            self.elements_badge.setText(f"{res.get('element_count', 0)} Elements")
            self.app_info.setText(f"App: {res.get('active_window', 'Desktop')}")
            self.status_title.setText("Action: Observed | Phase: READY")
        else:
            self.action_log.append(f"[ERROR] Observation error: {res.get('error')}")

    def _trigger_resolve(self):
        """Resolves the target specified in the text input."""
        query = self.target_input.text().strip()
        if not query:
            self.action_log.append("[RESOLVE] Please enter a target description (e.g. 'Search button').")
            return

        self.status_title.setText(f"Action: Resolving '{query}' | Phase: RESOLVE")
        self.action_log.append(f"[RESOLVE] Resolving natural language target: '{query}'...")
        self._highlight_phase("RESOLVE")

        if state_store.state.is_connected:
            bridge_client.request_resolve_target(query)
        else:
            try:
                from core.computer_use.computer_use_engine import computer_use_engine
                res = computer_use_engine.resolve_target(query)
                if res.get("status") == "success":
                    self.action_log.append(f"[RESOLVE] Found: [{res.get('role')}] '{res.get('name')}' at {res.get('center')}")
                else:
                    self.action_log.append(f"[RESOLVE] {res.get('message')}")
            except Exception as e:
                self.action_log.append(f"[ERROR] Resolve error: {e}")

    def _trigger_click(self):
        """Executes a computer-use click on the resolved target or query."""
        query = self.target_input.text().strip()
        if not query:
            # If coordinates are selected on canvas, click them
            coords = self.computer_view._state.target_coordinates
            if coords:
                self.action_log.append(f"[ACT] Clicking coordinate ({coords[0]}, {coords[1]})...")
                self._highlight_phase("ACT")
                if state_store.state.is_connected:
                    bridge_client.request_computer_use_step("click", target_query=f"({coords[0]}, {coords[1]})")
                return
            self.action_log.append("[ACT] Please enter a target query or click on the canvas to set coordinates.")
            return

        self.status_title.setText(f"Action: Clicking '{query}' | Phase: ACT")
        self.action_log.append(f"[ACT] Executing click on '{query}'...")
        self._highlight_phase("ACT")

        if state_store.state.is_connected:
            bridge_client.request_computer_use_step("click", target_query=query)
        else:
            try:
                from core.computer_use.computer_use_engine import computer_use_engine
                res = computer_use_engine.step("click", target_query=query)
                self.action_log.append(f"[RESULT] {res.get('message', res.get('status'))}")
            except Exception as e:
                self.action_log.append(f"[ERROR] Action error: {e}")

    def _on_canvas_clicked(self, x: int, y: int):
        self.coord_label.setText(f"Target: ({x}, {y})")
        self.action_log.append(f"[TARGET] Coordinates locked at ({x}, {y})")

    def _highlight_phase(self, curr_phase: str):
        for code, lbl in self.phase_labels.items():
            if code == curr_phase:
                lbl.setStyleSheet("""
                    background-color: rgba(56, 189, 248, 0.25);
                    color: #00F0FF;
                    font-weight: bold;
                    font-size: 11px;
                    border: 1px solid #38BDF8;
                    border-radius: 6px;
                    padding: 4px 10px;
                """)
            else:
                lbl.setStyleSheet("color: #6B7280; font-weight: bold; font-size: 11px; padding: 4px 10px;")

    def _on_computer_use_updated(self, state: ComputerUseState):
        self.computer_view.update_state(state)
        phase_val = state.phase.value

        action_display = state.action_type or "Observing"
        if phase_val == "IDLE":
            action_display = "Standby"

        self.status_title.setText(f"Action: {action_display} | Phase: {phase_val}")
        self.app_info.setText(f"App: {state.current_application or 'Desktop'}")

        if state.all_boxes:
            self.elements_badge.setText(f"{len(state.all_boxes)} Elements")

        # Update pipeline phases highlighting
        if phase_val in ("IDLE", "STANDBY"):
            self._highlight_phase("NONE")
        else:
            self._highlight_phase(phase_val)

        if state.status_message:
            self.action_log.append(f"[{phase_val}] {state.status_message}")
