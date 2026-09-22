"""
MAX UI — Chat Workspace
Interactive conversational interface with markdown, code snippets, and tools.
"""

from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QScrollArea, QLineEdit, QPushButton, QLabel, QFrame
)

from ..components.chat_message import ChatMessageWidget
from ..components.waveform import AudioWaveform
from ..models.chat_models import ChatMessage, MessageRole
from ..services.state_store import state_store
from ..services.ui_bridge_client import bridge_client


class ChatWorkspace(QWidget):
    """
    Renders conversational stream with rich message widgets, tool cards,
    and message submission bar.
    """

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("ChatWorkspace")
        self._init_ui()

        state_store.state.chat_message_added.connect(self._on_message_added)
        state_store.state.chat_cleared.connect(self._on_chat_cleared)
        state_store.state.user_transcript_updated.connect(self._on_user_transcript)
        state_store.state.assistant_transcript_updated.connect(self._on_assistant_transcript)

    def _init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 12, 16, 16)
        layout.setSpacing(10)

        # ── Scroll Area for Messages ─────────────────────────────────────────
        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        self.scroll.setStyleSheet("QScrollArea { border: none; background: transparent; }")

        self.messages_container = QWidget()
        self.messages_layout = QVBoxLayout(self.messages_container)
        self.messages_layout.setContentsMargins(0, 0, 0, 0)
        self.messages_layout.setSpacing(10)
        self.messages_layout.addStretch()

        self.scroll.setWidget(self.messages_container)
        layout.addWidget(self.scroll)

        # ── Live Subtitle Stream Bar (During Voice Conversations) ─────────────
        self.live_stream_box = QFrame()
        self.live_stream_box.setStyleSheet("""
            QFrame {
                background-color: rgba(56, 189, 248, 0.1);
                border: 1px dashed rgba(56, 189, 248, 0.3);
                border-radius: 8px;
                padding: 4px 8px;
            }
        """)
        ls_layout = QHBoxLayout(self.live_stream_box)
        ls_layout.setContentsMargins(6, 4, 6, 4)
        
        self.live_text_lbl = QLabel("")
        self.live_text_lbl.setStyleSheet("color: #38BDF8; font-size: 12px; font-style: italic;")
        ls_layout.addWidget(self.live_text_lbl)
        
        self.live_waveform = AudioWaveform(bar_count=12, height=18)
        self.live_waveform.setFixedWidth(80)
        ls_layout.addWidget(self.live_waveform)
        
        self.live_stream_box.setVisible(False)
        layout.addWidget(self.live_stream_box)

        # ── Input Box & Send Bar ──────────────────────────────────────────────
        input_frame = QFrame()
        input_frame.setStyleSheet("""
            QFrame {
                background-color: rgba(17, 24, 39, 0.85);
                border: 1px solid rgba(56, 189, 248, 0.25);
                border-radius: 10px;
                padding: 4px 8px;
            }
        """)
        input_layout = QHBoxLayout(input_frame)
        input_layout.setContentsMargins(4, 4, 4, 4)
        input_layout.setSpacing(8)

        self.input_edit = QLineEdit()
        self.input_edit.setPlaceholderText("Type a message or command for MAX...")
        self.input_edit.setStyleSheet("border: none; background: transparent; font-size: 13px; color: #F9FAFB;")
        self.input_edit.returnPressed.connect(self._send_message)
        input_layout.addWidget(self.input_edit)

        self.send_btn = QPushButton("Send")
        self.send_btn.setObjectName("PrimaryButton")
        self.send_btn.setFixedSize(65, 30)
        self.send_btn.clicked.connect(self._send_message)
        input_layout.addWidget(self.send_btn)

        layout.addWidget(input_frame)

    def _send_message(self):
        text = self.input_edit.text().strip()
        if not text:
            return

        self.input_edit.clear()
        state_store.add_user_message(text)
        bridge_client.send_command(text)

    def _on_message_added(self, message: ChatMessage):
        # Insert before bottom stretch item
        widget = ChatMessageWidget(message)
        count = self.messages_layout.count()
        self.messages_layout.insertWidget(max(0, count - 1), widget)
        # Scroll to bottom
        QTimer_single = self.scroll.verticalScrollBar()
        QTimer_single.setValue(QTimer_single.maximum())

    def _on_chat_cleared(self):
        while self.messages_layout.count() > 1:
            item = self.messages_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

    def _on_user_transcript(self, text: str):
        if text:
            self.live_text_lbl.setText(f"🗣️ {text}")
            self.live_stream_box.setVisible(True)
        else:
            if not state_store.state.assistant_transcript:
                self.live_stream_box.setVisible(False)

    def _on_assistant_transcript(self, text: str):
        if text:
            self.live_text_lbl.setText(f"✨ {text}")
            self.live_stream_box.setVisible(True)
        else:
            if not state_store.state.user_transcript:
                self.live_stream_box.setVisible(False)
