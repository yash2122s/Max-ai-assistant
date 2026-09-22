"""
MAX UI — Devices Workspace
Connected multi-device companion ecosystem (Windows PC, Android Phone, Tablet).
"""

from typing import List, Dict, Any
from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QFrame, QGridLayout, QPushButton
)

from ..services.state_store import state_store


class DevicesWorkspace(QWidget):
    """
    Renders ecosystem devices and cross-device routing status.
    """

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("DevicesWorkspace")
        self._init_ui()

        state_store.state.devices_updated.connect(self._on_devices_updated)

    def _init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 12, 16, 16)
        layout.setSpacing(14)

        # Header
        hdr_row = QHBoxLayout()
        title_lbl = QLabel("CONNECTED DEVICES ECOSYSTEM")
        title_lbl.setStyleSheet("font-weight: bold; font-size: 13px; color: #38BDF8; letter-spacing: 1px;")
        hdr_row.addWidget(title_lbl)
        hdr_row.addStretch()

        sync_lbl = QLabel("● REALTIME SYNC")
        sync_lbl.setStyleSheet("color: #10B981; font-weight: bold; font-size: 11px;")
        hdr_row.addWidget(sync_lbl)
        layout.addLayout(hdr_row)

        # Device Cards Grid
        self.grid_container = QWidget()
        self.grid_layout = QGridLayout(self.grid_container)
        self.grid_layout.setContentsMargins(0, 0, 0, 0)
        self.grid_layout.setSpacing(12)

        self._render_device_cards(state_store.state.devices)

        layout.addWidget(self.grid_container)
        layout.addStretch()

    def _render_device_cards(self, devices: List[Dict[str, Any]]):
        while self.grid_layout.count():
            item = self.grid_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        for idx, dev in enumerate(devices):
            frame = QFrame()
            frame.setStyleSheet("""
                QFrame {
                    background-color: rgba(17, 24, 39, 0.8);
                    border: 1px solid rgba(56, 189, 248, 0.2);
                    border-radius: 12px;
                    padding: 12px;
                }
            """)
            d_layout = QVBoxLayout(frame)
            d_layout.setSpacing(6)

            h_box = QHBoxLayout()
            icon_str = "💻" if dev.get("type") == "windows" else "📱"
            icon_lbl = QLabel(icon_str)
            icon_lbl.setStyleSheet("font-size: 20px;")
            h_box.addWidget(icon_lbl)

            name_lbl = QLabel(dev.get("name", "Device"))
            name_lbl.setStyleSheet("font-weight: bold; font-size: 13px; color: #F9FAFB;")
            h_box.addWidget(name_lbl)
            h_box.addStretch()

            status = dev.get("status", "online")
            st_color = "#10B981" if status == "online" else "#6B7280"
            st_lbl = QLabel(f"● {status.upper()}")
            st_lbl.setStyleSheet(f"color: {st_color}; font-weight: bold; font-size: 10.5px;")
            h_box.addWidget(st_lbl)
            d_layout.addLayout(h_box)

            info_lbl = QLabel("Local Agent Host" if dev.get("is_local") else "Paired Companion via WebSocket")
            info_lbl.setStyleSheet("color: #9CA3AF; font-size: 11px;")
            d_layout.addWidget(info_lbl)

            self.grid_layout.addWidget(frame, idx // 2, idx % 2)

    def _on_devices_updated(self, devices: List[Dict[str, Any]]):
        self._render_device_cards(devices)
