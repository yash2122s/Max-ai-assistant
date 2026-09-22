"""
MAX UI — Dark Theme Definition
Deep slate with sky cyan accents, translucent panels, and sleek borders.
"""

DARK_THEME_PALETTE = {
    "name": "dark",
    "bg_main": "#0B0F19",
    "bg_surface": "#111827",
    "bg_card": "rgba(17, 24, 39, 0.85)",
    "bg_hover": "#1F2937",
    "bg_active": "#374151",
    "border_subtle": "rgba(56, 189, 248, 0.20)",
    "border_focus": "#38BDF8",
    "text_primary": "#F9FAFB",
    "text_secondary": "#9CA3AF",
    "text_muted": "#6B7280",
    "accent_cyan": "#38BDF8",
    "accent_cyan_glow": "#00F0FF",
    "accent_violet": "#8B5CF6",
    "accent_emerald": "#10B981",
    "accent_amber": "#F59E0B",
    "accent_crimson": "#EF4444",
}

DARK_STYLESHEET = """
QMainWindow, QDialog {
    background-color: #0B0F19;
    color: #F9FAFB;
    font-family: "Segoe UI Variable", "Segoe UI", sans-serif;
}

QWidget {
    color: #F9FAFB;
    font-family: "Segoe UI Variable", "Segoe UI", sans-serif;
    font-size: 13px;
}

/* Scrollbars */
QScrollBar:vertical {
    border: none;
    background: #0B0F19;
    width: 6px;
    margin: 0px;
}

QScrollBar::handle:vertical {
    background: #374151;
    min-height: 20px;
    border-radius: 3px;
}

QScrollBar::handle:vertical:hover {
    background: #38BDF8;
}

QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {
    height: 0px;
}

QScrollBar:horizontal {
    border: none;
    background: #0B0F19;
    height: 6px;
    margin: 0px;
}

QScrollBar::handle:horizontal {
    background: #374151;
    min-width: 20px;
    border-radius: 3px;
}

QScrollBar::handle:horizontal:hover {
    background: #38BDF8;
}

QScrollBar::add-line:horizontal, QScrollBar::sub-line:horizontal {
    width: 0px;
}

/* Push Buttons */
QPushButton {
    background-color: #1F2937;
    color: #F9FAFB;
    border: 1px solid rgba(56, 189, 248, 0.25);
    border-radius: 8px;
    padding: 6px 14px;
    font-weight: 500;
}

QPushButton:hover {
    background-color: #374151;
    border-color: #38BDF8;
}

QPushButton:pressed {
    background-color: #111827;
}

QPushButton:disabled {
    background-color: #111827;
    color: #4B5563;
    border-color: #1F2937;
}

/* Primary Cyan Action Buttons */
QPushButton#PrimaryButton {
    background-color: rgba(56, 189, 248, 0.18);
    color: #38BDF8;
    border: 1px solid #38BDF8;
    font-weight: bold;
}

QPushButton#PrimaryButton:hover {
    background-color: rgba(56, 189, 248, 0.35);
    border: 1px solid #00F0FF;
}

/* Danger / Reject Buttons */
QPushButton#DangerButton {
    background-color: rgba(239, 68, 68, 0.18);
    color: #EF4444;
    border: 1px solid #EF4444;
    font-weight: bold;
}

QPushButton#DangerButton:hover {
    background-color: rgba(239, 68, 68, 0.35);
}

/* Success / Approve Buttons */
QPushButton#SuccessButton {
    background-color: rgba(16, 185, 129, 0.18);
    color: #10B981;
    border: 1px solid #10B981;
    font-weight: bold;
}

QPushButton#SuccessButton:hover {
    background-color: rgba(16, 185, 129, 0.35);
}

/* Text Inputs */
QLineEdit, QTextEdit, QPlainTextEdit {
    background-color: #111827;
    color: #F9FAFB;
    border: 1px solid rgba(56, 189, 248, 0.25);
    border-radius: 8px;
    padding: 8px 12px;
    selection-background-color: #38BDF8;
    selection-color: #0B0F19;
}

QLineEdit:focus, QTextEdit:focus, QPlainTextEdit:focus {
    border: 1px solid #38BDF8;
    background-color: #131D31;
}

/* Progress Bars */
QProgressBar {
    background-color: #1F2937;
    border: 1px solid rgba(56, 189, 248, 0.2);
    border-radius: 4px;
    height: 6px;
    text-align: right;
}

QProgressBar::chunk {
    background-color: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #38BDF8, stop:1 #8B5CF6);
    border-radius: 3px;
}

/* Tooltips */
QToolTip {
    background-color: #111827;
    color: #38BDF8;
    border: 1px solid #38BDF8;
    padding: 4px 8px;
    border-radius: 4px;
}

/* Splitter */
QSplitter::handle {
    background-color: rgba(56, 189, 248, 0.15);
}

QSplitter::handle:hover {
    background-color: #38BDF8;
}
"""
