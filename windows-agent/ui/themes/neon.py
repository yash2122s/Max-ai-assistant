"""
MAX UI — Cyberpunk Neon Theme Definition
High contrast with electric cyan and vivid purple accents.
"""

NEON_THEME_PALETTE = {
    "name": "neon",
    "bg_main": "#05070E",
    "bg_surface": "#0C0F1D",
    "bg_card": "rgba(12, 15, 29, 0.90)",
    "bg_hover": "#171D36",
    "bg_active": "#263059",
    "border_subtle": "rgba(0, 240, 255, 0.25)",
    "border_focus": "#00F0FF",
    "text_primary": "#FFFFFF",
    "text_secondary": "#B8C0EC",
    "text_muted": "#717CB4",
    "accent_cyan": "#00F0FF",
    "accent_cyan_glow": "#00F0FF",
    "accent_violet": "#C084FC",
    "accent_emerald": "#00FF9D",
    "accent_amber": "#FFB800",
    "accent_crimson": "#FF0055",
}

NEON_STYLESHEET = """
QMainWindow, QDialog {
    background-color: #05070E;
    color: #FFFFFF;
    font-family: "Segoe UI Variable", "Segoe UI", sans-serif;
}

QWidget {
    color: #FFFFFF;
    font-family: "Segoe UI Variable", "Segoe UI", sans-serif;
    font-size: 13px;
}

/* Scrollbars */
QScrollBar:vertical {
    border: none;
    background: #05070E;
    width: 6px;
    margin: 0px;
}

QScrollBar::handle:vertical {
    background: #263059;
    min-height: 20px;
    border-radius: 3px;
}

QScrollBar::handle:vertical:hover {
    background: #00F0FF;
}

/* Push Buttons */
QPushButton {
    background-color: #171D36;
    color: #FFFFFF;
    border: 1px solid rgba(0, 240, 255, 0.3);
    border-radius: 8px;
    padding: 6px 14px;
    font-weight: 500;
}

QPushButton:hover {
    background-color: #263059;
    border-color: #00F0FF;
}

/* Primary Neon Buttons */
QPushButton#PrimaryButton {
    background-color: rgba(0, 240, 255, 0.2);
    color: #00F0FF;
    border: 1px solid #00F0FF;
    font-weight: bold;
}

QPushButton#PrimaryButton:hover {
    background-color: rgba(0, 240, 255, 0.4);
    border: 1px solid #00F0FF;
}

/* Text Inputs */
QLineEdit, QTextEdit, QPlainTextEdit {
    background-color: #0C0F1D;
    color: #FFFFFF;
    border: 1px solid rgba(0, 240, 255, 0.3);
    border-radius: 8px;
    padding: 8px 12px;
    selection-background-color: #00F0FF;
    selection-color: #05070E;
}

QLineEdit:focus, QTextEdit:focus, QPlainTextEdit:focus {
    border: 1px solid #00F0FF;
    background-color: #10162B;
}

/* Progress Bars */
QProgressBar {
    background-color: #171D36;
    border: 1px solid rgba(0, 240, 255, 0.3);
    border-radius: 4px;
    height: 6px;
}

QProgressBar::chunk {
    background-color: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #00F0FF, stop:1 #C084FC);
    border-radius: 3px;
}
"""
