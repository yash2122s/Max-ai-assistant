"""
MAX UI — Confirmation Card Component
Security-critical human-in-the-loop action approval card.
"""

from PyQt6.QtCore import Qt, QTimer, pyqtSignal
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QProgressBar, QPushButton
)

from .dynamic_card import DynamicCard
from ..models.task_models import ConfirmationRequest
from ..services.ui_bridge_client import bridge_client
from ..services.state_store import state_store


class ConfirmationCard(DynamicCard):
    """
    Renders security approval requests with a visual expiry progress bar
    and interactive Approve / Reject glass buttons.
    """

    approved = pyqtSignal(str)  # ticket_id
    rejected = pyqtSignal(str)  # ticket_id

    def __init__(self, request: ConfirmationRequest, parent=None):
        super().__init__(
            title=f"⚠️ {request.action_name}",
            subtitle=f"Target: {request.target}" if request.target else "Approval Required",
            icon="🛡️",
            badge="ACTION REQUIRED",
            badge_color="#F59E0B",
            parent=parent,
        )
        self.request = request
        self._init_confirm_body()

        # 1-second countdown timer
        self._timer = QTimer(self)
        self._timer.timeout.connect(self._on_tick)
        self._timer.start(100)

    def _init_confirm_body(self):
        # Description
        desc_lbl = QLabel(self.request.description)
        desc_lbl.setStyleSheet("color: #F9FAFB; font-size: 12px; margin-bottom: 4px;")
        desc_lbl.setWordWrap(True)
        self.body_layout.addWidget(desc_lbl)

        # Expiry Bar
        exp_box = QHBoxLayout()
        self.expiry_bar = QProgressBar()
        self.expiry_bar.setRange(0, 100)
        self.expiry_bar.setValue(100)
        self.expiry_bar.setStyleSheet("""
            QProgressBar::chunk {
                background-color: #F59E0B;
            }
        """)
        exp_box.addWidget(self.expiry_bar)

        self.time_lbl = QLabel(f"{int(self.request.remaining_seconds)}s")
        self.time_lbl.setStyleSheet("color: #F59E0B; font-weight: bold; font-size: 11px;")
        exp_box.addWidget(self.time_lbl)
        self.body_layout.addLayout(exp_box)

        # Action Buttons
        btn_box = QHBoxLayout()
        btn_box.addStretch()

        self.reject_btn = QPushButton("Reject")
        self.reject_btn.setObjectName("DangerButton")
        self.reject_btn.setFixedSize(84, 28)
        self.reject_btn.clicked.connect(self._on_reject)
        btn_box.addWidget(self.reject_btn)

        self.approve_btn = QPushButton("Approve")
        self.approve_btn.setObjectName("SuccessButton")
        self.approve_btn.setFixedSize(84, 28)
        self.approve_btn.clicked.connect(self._on_approve)
        btn_box.addWidget(self.approve_btn)

        self.body_layout.addLayout(btn_box)

    def _on_tick(self):
        rem = self.request.remaining_seconds
        pct = int((rem / max(1.0, self.request.expires_in_seconds)) * 100)
        self.expiry_bar.setValue(pct)
        self.time_lbl.setText(f"{int(rem)}s")

        if self.request.is_expired:
            self._timer.stop()
            self._on_reject()

    def _on_approve(self):
        self._timer.stop()
        bridge_client.send_confirmation_response(self.request.ticket_id, "approve")
        state_store.clear_confirmation()
        self.approved.emit(self.request.ticket_id)

    def _on_reject(self):
        self._timer.stop()
        bridge_client.send_confirmation_response(self.request.ticket_id, "reject", "User rejected in Desktop UI")
        state_store.clear_confirmation()
        self.rejected.emit(self.request.ticket_id)
