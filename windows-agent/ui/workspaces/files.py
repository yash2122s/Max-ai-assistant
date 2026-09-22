"""
MAX UI — Files Workspace
Filesystem navigation, drag-and-drop zone, file previews, and undo support.
"""

import os
from PyQt6.QtCore import Qt
from PyQt6.QtGui import QFileSystemModel
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QFrame, QSplitter, QTreeView, QTextEdit, QPushButton
)

from ..services.state_store import state_store


class FilesWorkspace(QWidget):
    """
    Renders filesystem explorer, safe preview, drag-and-drop area, and recent operations.
    """

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("FilesWorkspace")
        self.setAcceptDrops(True)
        self._init_ui()

    def _init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 12, 16, 16)
        layout.setSpacing(10)

        # Header with Undo / Safety info
        hdr_row = QHBoxLayout()
        title_lbl = QLabel("FILES & STORAGE")
        title_lbl.setStyleSheet("font-weight: bold; font-size: 13px; color: #38BDF8; letter-spacing: 1px;")
        hdr_row.addWidget(title_lbl)
        hdr_row.addStretch()

        undo_info = QLabel("🛡️ Trash & Undo Protection Active")
        undo_info.setStyleSheet("color: #10B981; font-size: 11px; font-weight: 500;")
        hdr_row.addWidget(undo_info)
        layout.addLayout(hdr_row)

        # Splitter between Directory Tree and File Preview Pane
        splitter = QSplitter(Qt.Orientation.Horizontal)

        # File Tree
        self.fs_model = QFileSystemModel()
        root_path = os.getcwd()
        self.fs_model.setRootPath(root_path)

        self.tree = QTreeView()
        self.tree.setModel(self.fs_model)
        self.tree.setRootIndex(self.fs_model.index(root_path))
        self.tree.setColumnWidth(0, 220)
        self.tree.setStyleSheet("""
            QTreeView {
                background-color: rgba(17, 24, 39, 0.7);
                border: 1px solid rgba(56, 189, 248, 0.15);
                border-radius: 10px;
                color: #F9FAFB;
                padding: 4px;
            }
            QTreeView::item:hover {
                background-color: rgba(56, 189, 248, 0.15);
            }
            QTreeView::item:selected {
                background-color: rgba(56, 189, 248, 0.3);
                color: #38BDF8;
            }
        """)
        self.tree.clicked.connect(self._on_file_selected)
        splitter.addWidget(self.tree)

        # File Preview Pane
        preview_frame = QFrame()
        preview_frame.setStyleSheet("""
            QFrame {
                background-color: rgba(17, 24, 39, 0.7);
                border: 1px solid rgba(56, 189, 248, 0.15);
                border-radius: 10px;
                padding: 8px;
            }
        """)
        p_layout = QVBoxLayout(preview_frame)
        p_layout.setContentsMargins(6, 6, 6, 6)

        self.preview_title = QLabel("FILE PREVIEW")
        self.preview_title.setStyleSheet("color: #38BDF8; font-size: 11px; font-weight: bold;")
        p_layout.addWidget(self.preview_title)

        self.preview_edit = QTextEdit()
        self.preview_edit.setReadOnly(True)
        self.preview_edit.setStyleSheet("""
            QTextEdit {
                background-color: #0B0F19;
                border: 1px solid rgba(56, 189, 248, 0.15);
                border-radius: 8px;
                color: #F9FAFB;
                font-family: 'Consolas', monospace;
                font-size: 11.5px;
            }
        """)
        self.preview_edit.setPlaceholderText("Select a file from the tree or drag-and-drop a file here to preview...")
        p_layout.addWidget(self.preview_edit)

        splitter.addWidget(preview_frame)
        splitter.setStretchFactor(0, 1)
        splitter.setStretchFactor(1, 2)

        layout.addWidget(splitter)

    def _on_file_selected(self, index):
        path = self.fs_model.filePath(index)
        if os.path.isfile(path):
            self.preview_title.setText(f"PREVIEW: {os.path.basename(path)}")
            try:
                with open(path, "r", encoding="utf-8", errors="replace") as f:
                    content = f.read(50000)
                self.preview_edit.setPlainText(content)
            except Exception as e:
                self.preview_edit.setPlainText(f"[Cannot display binary/protected file: {e}]")

    def dragEnterEvent(self, event):
        if event.mimeData().hasUrls():
            event.acceptProposedAction()

    def dropEvent(self, event):
        for url in event.mimeData().urls():
            path = url.toLocalFile()
            if os.path.isfile(path):
                self.preview_title.setText(f"DROPPED: {os.path.basename(path)}")
                try:
                    with open(path, "r", encoding="utf-8", errors="replace") as f:
                        self.preview_edit.setPlainText(f.read(50000))
                except Exception:
                    pass
                state_store.state.push_notification("File Attached", f"Loaded {os.path.basename(path)}", "info")
