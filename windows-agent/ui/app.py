"""
MAX UI — Main Execution Entry Point
Launches the PyQt6 Desktop application and connects to the backend UI Bridge.
"""

import sys
import logging
from PyQt6.QtCore import Qt

from .application import MAXApplication
from .shell.main_window import MainWindow
from .services.ui_bridge_client import bridge_client

logger = logging.getLogger("MAX.UI.App")


def run_desktop_ui(bridge_uri: str = "ws://127.0.0.1:9002"):
    """
    Launch the full-screen PyQt6 MAX desktop client.
    """
    app = MAXApplication(sys.argv)

    # Initialize Main Window
    main_window = MainWindow()
    main_window.show()

    # Start non-blocking background UIBridge client
    bridge_client.uri = bridge_uri
    bridge_client.start()

    exit_code = app.exec()

    # Clean up bridge on exit
    bridge_client.stop()
    return exit_code


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(message)s")
    sys.exit(run_desktop_ui())
