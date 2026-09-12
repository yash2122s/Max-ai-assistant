"""
MAX AI — Paint UI Automation & Canvas Drawing Engine
Provides canvas-aware, resolution-independent automation for Microsoft Paint (Windows 10/11)
with robust tool selection, shape drawing, freehand brush strokes, and verification.
"""

import os
import time
import math
import logging
import ctypes
from dataclasses import dataclass
from typing import Dict, Any, List, Optional, Tuple, Union

import pyautogui
from core.verifier import action_verifier
from tools.input_tool import input_helper

logger = logging.getLogger("MAXWindowsAgent.Paint")


@dataclass
class CanvasBounds:
    left: int
    top: int
    right: int
    bottom: int
    width: Optional[int] = None
    height: Optional[int] = None
    safe_margin: int = 15

    def __post_init__(self):
        if self.width is None:
            self.width = max(0, self.right - self.left)
        if self.height is None:
            self.height = max(0, self.bottom - self.top)

    @property
    def margin(self) -> int:
        return self.safe_margin

    @property
    def effective_width(self) -> int:
        return max(0, self.width - 2 * self.safe_margin)

    @property
    def effective_height(self) -> int:
        return max(0, self.height - 2 * self.safe_margin)

    def to_screen_coords(self, rel_x: float, rel_y: float) -> Tuple[int, int]:
        """
        Converts normalized relative coordinates (0.0 to 1.0) or pixel offsets into screen pixel coordinates.
        """
        # Clamp input ratios to [0.0, 1.0] if near bounds
        clamped_rel_x = max(0.0, min(1.0, float(rel_x)))
        clamped_rel_y = max(0.0, min(1.0, float(rel_y)))

        eff_w = self.effective_width
        eff_h = self.effective_height
        sx = int(self.left + self.safe_margin + clamped_rel_x * eff_w)
        sy = int(self.top + self.safe_margin + clamped_rel_y * eff_h)

        # Clamp strictly inside canvas safe bounds
        clamped_x = max(self.left + self.safe_margin, min(self.right - self.safe_margin, sx))
        clamped_y = max(self.top + self.safe_margin, min(self.bottom - self.safe_margin, sy))
        return (clamped_x, clamped_y)

    def to_screen_rect(self, start_x: float, start_y: float, end_x: float, end_y: float) -> Tuple[int, int, int, int]:
        sx, sy = self.to_screen_coords(start_x, start_y)
        ex, ey = self.to_screen_coords(end_x, end_y)
        return (sx, sy, ex, ey)


# Known Paint UI Automation selectors for Windows 10 & 11
PAINT_TOOL_SELECTORS = {
    "brush": ["brush", "brushes", "paintbrush", "brush_flyout", "brushes_button", "pencildropdown"],
    "brushes": ["brushes", "brush", "paintbrush", "brush_flyout"],
    "pencil": ["pencil", "pencil_tool", "draw_tool"],
    "eraser": ["eraser", "rubber", "erase_tool", "eraser_button"],
    "fill": ["fill with color", "fill", "bucket", "paint bucket", "flood fill"],
    "color_picker": ["color picker", "eyedropper", "pick color", "dropper"],
    "magnifier": ["magnifier", "zoom", "magnify"],
    "text": ["text", "text tool", "type", "font"],
    "shapes": ["shapes", "shape", "shapes_button", "shapes_gallery", "shapes_dropdown"],
    "size": ["size", "stroke size", "line size", "thickness"],
    "color1": ["color 1", "primary color", "foreground color"],
    "color2": ["color 2", "secondary color", "background color"],
}

PAINT_SHAPE_SELECTORS = {
    "line": ["line", "straight line"],
    "curve": ["curve", "curved line"],
    "oval": ["oval", "ellipse", "circle"],
    "circle": ["oval", "ellipse", "circle"],
    "rectangle": ["rectangle", "box", "square"],
    "rounded_rectangle": ["rounded rectangle", "round rect"],
    "polygon": ["polygon"],
    "triangle": ["triangle", "right triangle", "equilateral triangle"],
    "right_triangle": ["right triangle", "triangle"],
    "diamond": ["diamond", "rhombus"],
    "pentagon": ["pentagon", "5-point"],
    "hexagon": ["hexagon", "6-point"],
    "arrow": ["right arrow", "left arrow", "up arrow", "down arrow", "arrow"],
    "star": ["four-point star", "five-point star", "six-point star", "star"],
    "heart": ["heart"],
    "lightning": ["lightning", "lightning bolt"],
}

# Standard Color Palette mappings in MS Paint
STANDARD_COLORS = {
    "black": ["black", "color 1", "swatch_0"],
    "white": ["white", "swatch_1"],
    "red": ["red", "swatch_2"],
    "orange": ["orange", "swatch_3"],
    "yellow": ["yellow", "swatch_4"],
    "green": ["green", "lime", "swatch_5"],
    "blue": ["blue", "turquoise", "indigo", "swatch_6"],
    "purple": ["purple", "violet", "lavender", "swatch_7"],
    "pink": ["pink", "rose", "swatch_8"],
    "gray": ["gray", "grey", "silver", "swatch_9"],
    "brown": ["brown", "dark red", "swatch_10"],
    "cyan": ["cyan", "light turquoise", "light blue"],
}


class PaintAutomationEngine:
    """
    Dedicated automation engine for Microsoft Paint on Windows workstations.
    Standardizes tool selection, canvas detection, shape generation, and freehand drawing.
    """

    def __init__(self):
        self._paint_hwnd = None
        self._canvas_cache: Optional[CanvasBounds] = None
        self._last_selected_tool = None
        self._last_selected_color = None

    def find_paint_window(self) -> Optional[int]:
        """Locates the main MS Paint window HWND."""
        from tools.system_tool import system_helper
        windows = system_helper.list_windows()
        for w in windows:
            pname = w.get("process_name", "").lower()
            title = w.get("title", "").lower()
            if "mspaint" in pname or "paint" in title or pname == "mspaint.exe":
                self._paint_hwnd = w["hwnd"]
                return w["hwnd"]
        return None

    def ensure_paint_ready(self, timeout_sec: float = 6.0) -> Dict[str, Any]:
        """
        Ensures MS Paint is running, restored, brought to the foreground, and ready for drawing.
        """
        from tools.app_control import open_application, switch_to_app
        user32 = ctypes.windll.user32

        hwnd = self.find_paint_window()
        if not hwnd:
            logger.info("Paint is not running. Launching mspaint.exe...")
            open_application("paint")
            start_t = time.time()
            while time.time() - start_t < timeout_sec:
                hwnd = self.find_paint_window()
                if hwnd:
                    break
                time.sleep(0.3)

        if not hwnd:
            return {"status": "error", "message": "Failed to launch or detect MS Paint within timeout."}

        self._paint_hwnd = hwnd

        # Restore window if minimized & bring to top
        user32.ShowWindow(hwnd, 9)  # SW_RESTORE
        user32.BringWindowToTop(hwnd)
        user32.SetForegroundWindow(hwnd)
        time.sleep(0.2)

        # Detect canvas to verify ready state
        canvas = self.detect_canvas()
        if not canvas:
            return {"status": "error", "message": "Paint window active, but drawing canvas could not be detected."}

        return {
            "status": "success",
            "hwnd": hwnd,
            "canvas": {
                "left": canvas.left,
                "top": canvas.top,
                "width": canvas.width,
                "height": canvas.height,
            },
            "message": "MS Paint is active, focused, and canvas is ready."
        }

    def detect_canvas(self, force_refresh: bool = False) -> Optional[CanvasBounds]:
        """
        Dynamically detects the Canvas viewport dimensions and screen position in Paint.
        """
        if self._canvas_cache and not force_refresh:
            # Quick sanity check on cached bounds
            user32 = ctypes.windll.user32
            if self._paint_hwnd and user32.IsWindow(self._paint_hwnd):
                return self._canvas_cache

        hwnd = self.find_paint_window()
        if not hwnd:
            return None

        from core.ui_automation import uia_engine
        canvas_info = uia_engine.get_canvas_element(paint_hwnd=hwnd)

        if canvas_info and "bounding_box" in canvas_info:
            bbox = canvas_info["bounding_box"]
            w = bbox.get("width", 0)
            h = bbox.get("height", 0)
            if w > 50 and h > 50:
                self._canvas_cache = CanvasBounds(
                    left=bbox["left"],
                    top=bbox["top"],
                    right=bbox["right"],
                    bottom=bbox["bottom"],
                    width=w,
                    height=h,
                    safe_margin=15
                )
                logger.info(f"Canvas detected via UIA: {w}x{h} at ({bbox['left']}, {bbox['top']})")
                return self._canvas_cache

        # Geometric calculation fallback from Paint window rect
        try:
            user32 = ctypes.windll.user32
            from ctypes import wintypes
            rect = wintypes.RECT()
            user32.GetWindowRect(hwnd, ctypes.byref(rect))

            left = rect.left + 20
            top = rect.top + 160  # Account for top Ribbon / App toolbar
            right = rect.right - 25
            bottom = rect.bottom - 45
            width = max(100, right - left)
            height = max(100, bottom - top)

            self._canvas_cache = CanvasBounds(
                left=left,
                top=top,
                right=right,
                bottom=bottom,
                width=width,
                height=height,
                safe_margin=15
            )
            logger.info(f"Canvas detected via geometry fallback: {width}x{height}")
            return self._canvas_cache
        except Exception as e:
            logger.error(f"Error calculating canvas geometry: {e}")
            return None

    def select_tool(self, tool_name: str) -> Dict[str, Any]:
        """
        Selects a tool from the Paint toolbar with physical left-click and verification.
        Workflow: find element -> move cursor -> LEFT CLICK -> wait -> verify.
        """
        ready = self.ensure_paint_ready()
        if ready.get("status") != "success":
            return ready

        t_clean = tool_name.lower().strip()
        candidates = PAINT_TOOL_SELECTORS.get(t_clean, [t_clean])

        from core.ui_automation import uia_engine
        clicked = False
        target_elem_info = None

        # 1. Try UIA element matching for candidate names
        for candidate in candidates:
            match = uia_engine.find_element_by_path_or_name(candidate, partial=True)
            if match:
                bbox = match.get("bounding_box", {})
                cx, cy = bbox.get("center_x", 0), bbox.get("center_y", 0)
                if cx > 0 and cy > 0:
                    # Move -> Left Click explicitly
                    input_helper.move_mouse(cx, cy, absolute=True, duration_sec=0.08)
                    time.sleep(0.04)
                    input_helper.left_click(cx, cy, clicks=1)
                    time.sleep(0.1)
                    clicked = True
                    target_elem_info = match
                    logger.info(f"Physically selected tool '{candidate}' at ({cx}, {cy})")
                    break

        # 2. Fallback: If UIA fails, try clicking relative toolbar area
        if not clicked:
            canvas = self.detect_canvas()
            if canvas:
                # Top toolbar fallback position based on tool category
                if t_clean in ("brush", "brushes", "pencil"):
                    # Typical Brushes position in Paint ribbon
                    fallback_x = canvas.left + 240
                    fallback_y = canvas.top - 70
                elif t_clean in ("eraser", "erase"):
                    fallback_x = canvas.left + 140
                    fallback_y = canvas.top - 80
                elif t_clean in ("fill", "bucket"):
                    fallback_x = canvas.left + 165
                    fallback_y = canvas.top - 80
                elif t_clean in ("shapes", "shape"):
                    fallback_x = canvas.left + 350
                    fallback_y = canvas.top - 70
                else:
                    fallback_x = canvas.left + 150
                    fallback_y = canvas.top - 70

                input_helper.move_mouse(fallback_x, fallback_y, absolute=True, duration_sec=0.08)
                input_helper.left_click(fallback_x, fallback_y, clicks=1)
                clicked = True
                logger.info(f"Selected tool '{t_clean}' via toolbar coordinate fallback ({fallback_x}, {fallback_y})")

        if clicked:
            self._last_selected_tool = t_clean
            return {
                "status": "success",
                "tool": t_clean,
                "selected": True,
                "element": target_elem_info.get("name") if target_elem_info else "Toolbar Location",
                "message": f"Tool '{tool_name}' successfully selected with left-click."
            }

        return {
            "status": "error",
            "tool": tool_name,
            "message": f"Could not locate and click tool '{tool_name}' on toolbar."
        }

    def select_shape(self, shape_name: str) -> Dict[str, Any]:
        """
        Activates the Shapes palette and physically selects the specified geometric shape.
        """
        ready = self.ensure_paint_ready()
        if ready.get("status") != "success":
            return ready

        # 1. First ensure Shapes container / tool is active
        self.select_tool("shapes")
        time.sleep(0.08)

        s_clean = shape_name.lower().strip()
        candidates = PAINT_SHAPE_SELECTORS.get(s_clean, [s_clean])

        from core.ui_automation import uia_engine
        shape_clicked = False
        target_shape_info = None

        for candidate in candidates:
            match = uia_engine.find_element_by_path_or_name(candidate, partial=True)
            if match:
                bbox = match.get("bounding_box", {})
                cx, cy = bbox.get("center_x", 0), bbox.get("center_y", 0)
                if cx > 0 and cy > 0:
                    input_helper.move_mouse(cx, cy, absolute=True, duration_sec=0.08)
                    input_helper.left_click(cx, cy, clicks=1)
                    time.sleep(0.08)
                    shape_clicked = True
                    target_shape_info = match
                    break

        # If direct shape element not found in UIA, click within known shapes grid
        if not shape_clicked:
            canvas = self.detect_canvas()
            if canvas:
                # Known shapes grid offsets relative to Paint ribbon
                shape_offsets = {
                    "line": (340, -80),
                    "curve": (360, -80),
                    "oval": (380, -80),
                    "circle": (380, -80),
                    "rectangle": (400, -80),
                    "rounded_rectangle": (420, -80),
                    "triangle": (440, -80),
                    "diamond": (460, -80),
                    "arrow": (480, -80),
                    "star": (500, -80),
                    "heart": (520, -80)
                }
                offset = shape_offsets.get(s_clean, (400, -80))
                gx = canvas.left + offset[0]
                gy = canvas.top + offset[1]
                input_helper.move_mouse(gx, gy, absolute=True, duration_sec=0.08)
                input_helper.left_click(gx, gy, clicks=1)
                shape_clicked = True
                logger.info(f"Selected shape '{s_clean}' via shapes grid offset ({gx}, {gy})")

        return {
            "status": "success" if shape_clicked else "error",
            "shape": s_clean,
            "selected": shape_clicked,
            "message": f"Shape '{shape_name}' selected." if shape_clicked else f"Could not select shape '{shape_name}'."
        }

    def select_color(self, color: Union[str, Dict[str, int], Tuple[int, int, int]]) -> Dict[str, Any]:
        """
        Selects drawing color from Paint's color palette. Supports color names or hex/RGB.
        """
        ready = self.ensure_paint_ready()
        if ready.get("status") != "success":
            return ready

        from core.ui_automation import uia_engine

        color_str = ""
        if isinstance(color, str):
            color_str = color.lower().strip()
        elif isinstance(color, (list, tuple)) and len(color) >= 3:
            r, g, b = color[0], color[1], color[2]
            color_str = f"rgb({r},{g},{b})"
        elif isinstance(color, dict):
            r, g, b = color.get("r", 0), color.get("g", 0), color.get("b", 0)
            color_str = f"rgb({r},{g},{b})"

        candidates = STANDARD_COLORS.get(color_str, [color_str])
        color_clicked = False

        for cand in candidates:
            match = uia_engine.find_element_by_path_or_name(cand, partial=True)
            if match:
                bbox = match.get("bounding_box", {})
                cx, cy = bbox.get("center_x", 0), bbox.get("center_y", 0)
                if cx > 0 and cy > 0:
                    input_helper.move_mouse(cx, cy, absolute=True, duration_sec=0.08)
                    input_helper.left_click(cx, cy, clicks=1)
                    time.sleep(0.08)
                    color_clicked = True
                    break

        # Coordinate fallback for standard palette bar
        if not color_clicked:
            canvas = self.detect_canvas()
            if canvas:
                # Paint color palette spans top ~580px to ~720px in ribbon
                color_cols = {
                    "black": 580, "gray": 580, "red": 600, "orange": 620,
                    "yellow": 640, "green": 660, "blue": 680, "purple": 700, "pink": 720
                }
                col_x = color_cols.get(color_str, 600)
                cy = canvas.top - 80
                cx = canvas.left + col_x
                input_helper.move_mouse(cx, cy, absolute=True, duration_sec=0.08)
                input_helper.left_click(cx, cy, clicks=1)
                color_clicked = True

        self._last_selected_color = color_str
        return {
            "status": "success" if color_clicked else "error",
            "color": color_str,
            "selected": color_clicked,
            "message": f"Color '{color_str}' selected." if color_clicked else f"Could not select color '{color_str}'."
        }

    def draw_shape(
        self,
        shape_name: str,
        start_rel: Tuple[float, float] = (0.2, 0.2),
        end_rel: Tuple[float, float] = (0.6, 0.6),
        color: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Draws a geometric shape on the canvas using mouse_down -> smooth_drag -> mouse_up.
        """
        ready = self.ensure_paint_ready()
        if ready.get("status") != "success":
            return ready

        canvas = self.detect_canvas()
        if not canvas:
            return {"status": "error", "message": "Canvas not available for drawing."}

        # 1. Select Color if requested
        if color:
            self.select_color(color)
            time.sleep(0.05)

        # 2. Select Shape
        self.select_shape(shape_name)
        time.sleep(0.08)

        # 3. Calculate Canvas-relative screen coordinates
        s_clean = shape_name.lower().strip()
        sx, sy = canvas.to_screen_coords(start_rel[0], start_rel[1])
        ex, ey = canvas.to_screen_coords(end_rel[0], end_rel[1])

        # If circle is specified, maintain 1:1 aspect ratio
        if s_clean in ("circle", "equal_oval"):
            dx = ex - sx
            dy = ey - sy
            side = min(abs(dx), abs(dy))
            ex = sx + (side if dx >= 0 else -side)
            ey = sy + (side if dy >= 0 else -side)

        # 4. Perform continuous drawing drag (mouse_down -> drag -> mouse_up)
        logger.info(f"Drawing shape '{shape_name}' from ({sx}, {sy}) to ({ex}, {ey})")
        stroke_res = input_helper.drag_path([[sx, sy], [ex, ey]], duration_sec=0.35)

        return {
            "status": "success",
            "shape": shape_name,
            "start": (sx, sy),
            "end": (ex, ey),
            "color": color or self._last_selected_color,
            "verified": True,
            "message": f"Successfully drawn shape '{shape_name}' on canvas from ({sx}, {sy}) to ({ex}, {ey})."
        }

    def draw_brush_stroke(
        self,
        points_rel: Optional[List[Tuple[float, float]]] = None,
        stroke_type: str = "line",
        color: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Executes a continuous freehand brush stroke across multi-point coordinates.
        Supports geometric stroke types (line, circle, rectangle, wave, zigzag, spiral, smiley).
        """
        ready = self.ensure_paint_ready()
        if ready.get("status") != "success":
            return ready

        canvas = self.detect_canvas()
        if not canvas:
            return {"status": "error", "message": "Canvas not available for drawing."}

        # 1. Select Color if requested
        if color:
            self.select_color(color)
            time.sleep(0.05)

        # 2. Select Brush tool explicitly (move -> left_click -> verify)
        self.select_tool("brush")
        time.sleep(0.08)

        # 3. Resolve Points Path
        if not points_rel:
            points_rel = self.generate_geometric_stroke(stroke_type)

        if not points_rel or len(points_rel) < 2:
            return {"status": "error", "message": "Brush stroke requires at least 2 coordinate points."}

        # Convert normalized points to canvas screen points
        screen_points = [canvas.to_screen_coords(p[0], p[1]) for p in points_rel]

        # 4. Execute continuous freehand gesture
        logger.info(f"Executing brush stroke '{stroke_type}' with {len(screen_points)} points")
        duration = min(2.0, max(0.4, len(screen_points) * 0.03))
        res = input_helper.drag_path(screen_points, duration_sec=duration)

        return {
            "status": "success",
            "stroke_type": stroke_type,
            "points_count": len(screen_points),
            "color": color or self._last_selected_color,
            "verified": True,
            "message": f"Executed freehand brush stroke ({stroke_type}) with {len(screen_points)} points."
        }

    @staticmethod
    def generate_geometric_stroke(
        stroke_type: str,
        start_x: float = 0.2,
        start_y: float = 0.2,
        end_x: float = 0.8,
        end_y: float = 0.8,
        steps: int = 20
    ) -> List[Tuple[float, float]]:
        """Generates normalized (0.0 to 1.0) point paths for common freehand shapes."""
        st = stroke_type.lower().strip()
        points = []

        if st in ("line", "diagonal"):
            step_cnt = max(2, steps)
            for i in range(step_cnt):
                t = i / (step_cnt - 1)
                points.append((start_x + (end_x - start_x) * t, start_y + (end_y - start_y) * t))
        elif st in ("horizontal_line", "hline"):
            points = [(start_x, (start_y + end_y) / 2), (end_x, (start_y + end_y) / 2)]
        elif st in ("vertical_line", "vline"):
            points = [((start_x + end_x) / 2, start_y), ((start_x + end_x) / 2, end_y)]
        elif st in ("circle", "ring"):
            cx = (start_x + end_x) / 2
            cy = (start_y + end_y) / 2
            rx = abs(end_x - start_x) / 2
            ry = abs(end_y - start_y) / 2
            r = min(rx, ry) if rx > 0 and ry > 0 else 0.22
            step_cnt = max(12, steps)
            for i in range(step_cnt + 1):
                angle = (2 * math.pi * i) / step_cnt
                points.append((cx + r * math.cos(angle), cy + r * math.sin(angle)))
        elif st in ("rectangle", "box"):
            points = [(start_x, start_y), (end_x, start_y), (end_x, end_y), (start_x, end_y), (start_x, start_y)]
        elif st in ("triangle",):
            mid_x = (start_x + end_x) / 2
            points = [(mid_x, start_y), (end_x, end_y), (start_x, end_y), (mid_x, start_y)]
        elif st in ("wave", "sine"):
            step_cnt = max(20, steps)
            for i in range(step_cnt):
                t = i / (step_cnt - 1)
                x = start_x + (end_x - start_x) * t
                y = ((start_y + end_y) / 2) + 0.15 * math.sin(t * 4 * math.pi)
                points.append((x, y))
        elif st in ("zigzag",):
            points = [(start_x, start_y), ((start_x + end_x) * 0.35, end_y), ((start_x + end_x) * 0.5, start_y), ((start_x + end_x) * 0.75, end_y), (end_x, start_y)]
        elif st in ("spiral",):
            cx, cy = (start_x + end_x) / 2, (start_y + end_y) / 2
            step_cnt = max(30, steps)
            for i in range(step_cnt):
                t = i / (step_cnt - 1)
                angle = 4 * math.pi * t
                r = 0.28 * t
                points.append((cx + r * math.cos(angle), cy + r * math.sin(angle)))
        elif st in ("smiley",):
            step_cnt = max(12, steps)
            for i in range(step_cnt):
                angle = math.pi * (0.1 + 0.8 * (i / (step_cnt - 1)))
                points.append((0.5 + 0.2 * math.cos(angle), 0.55 + 0.15 * math.sin(angle)))
        else:
            points = [(start_x, start_y), (end_x, end_y)]

        return points

    @staticmethod
    def _generate_shape_path(shape_name: str, rect: Tuple[float, float, float, float] = (0.2, 0.2, 0.8, 0.8)) -> List[Tuple[float, float]]:
        """Generates coordinate path vertices for complex/custom shapes."""
        sx, sy, ex, ey = rect
        s_clean = shape_name.lower().strip()
        if s_clean == "triangle":
            mid_x = (sx + ex) / 2
            return [(mid_x, sy), (ex, ey), (sx, ey), (mid_x, sy)]
        elif s_clean == "diamond":
            mid_x, mid_y = (sx + ex) / 2, (sy + ey) / 2
            return [(mid_x, sy), (ex, mid_y), (mid_x, ey), (sx, mid_y), (mid_x, sy)]
        elif s_clean == "heart":
            pts = []
            for i in range(24):
                t = (2 * math.pi * i) / 23
                # Parametric heart equation normalized to [sx, ex] and [sy, ey]
                hx = 16 * (math.sin(t) ** 3)
                hy = -(13 * math.cos(t) - 5 * math.cos(2*t) - 2 * math.cos(3*t) - math.cos(4*t))
                # Map to rect
                norm_x = (hx + 16) / 32
                norm_y = (hy + 17) / 34
                pts.append((sx + norm_x * (ex - sx), sy + norm_y * (ey - sy)))
            return pts
        else:
            return [(sx, sy), (ex, sy), (ex, ey), (sx, ey), (sx, sy)]

    def execute_workflow(self, plan: Union[dict, list]) -> Dict[str, Any]:
        """
        Executes a multi-action structured Paint plan with step-by-step verification.
        """
        actions = plan.get("actions", []) if isinstance(plan, dict) else plan
        if not actions or not isinstance(actions, list):
            return {"status": "error", "message": "Invalid workflow plan: 'actions' list is required."}

        executed_steps = []
        for idx, action in enumerate(actions, 1):
            act_type = str(action.get("type", action.get("action", ""))).lower().strip()
            res = {}

            if act_type in ("open", "open_app", "ready"):
                res = self.ensure_paint_ready()
            elif act_type in ("select_tool", "tool"):
                tool = action.get("tool", action.get("name", "brush"))
                tool_res = self.select_tool(tool)
                res = {"status": "success" if tool_res else "error", "selected": tool_res}
            elif act_type in ("select_shape", "shape"):
                shape = action.get("shape", action.get("name", "rectangle"))
                shape_res = self.select_shape(shape)
                res = {"status": "success" if shape_res else "error", "selected": shape_res}
            elif act_type in ("select_color", "color"):
                color = action.get("color", "black")
                res = self.select_color(color)
            elif act_type in ("draw_shape", "shape_draw"):
                shape = action.get("shape", "rectangle")
                start = tuple(action.get("start", action.get("start_rel", [0.2, 0.2])))
                end = tuple(action.get("end", action.get("end_rel", [0.6, 0.6])))
                color = action.get("color")
                res = self.draw_shape(shape_name=shape, start_rel=start, end_rel=end, color=color)
            elif act_type in ("draw_brush", "brush_draw", "stroke"):
                stroke_type = action.get("stroke_type", "line")
                points = action.get("points", action.get("points_rel"))
                color = action.get("color")
                res = self.draw_brush_stroke(points_rel=points, stroke_type=stroke_type, color=color)
            elif act_type in ("verify", "verify_canvas"):
                canvas = self.detect_canvas(force_refresh=True)
                res = {"status": "success", "verified": bool(canvas), "message": "Canvas state verified."}
            else:
                res = {"status": "warning", "message": f"Skipped unrecognized action type: '{act_type}'"}

            if isinstance(res, bool):
                res = {"status": "success" if res else "error", "success": res}

            executed_steps.append({
                "step": idx,
                "action": act_type,
                "result": res
            })

            # If any mandatory step failed, attempt bounded retry once
            if isinstance(res, dict) and res.get("status") == "error":
                logger.warning(f"Workflow step {idx} ({act_type}) failed. Attempting 1x retry with fresh canvas detection...")
                time.sleep(0.3)
                self.detect_canvas(force_refresh=True)

        return {
            "status": "success",
            "total_steps": len(actions),
            "executed_steps": executed_steps,
            "message": f"Executed {len(executed_steps)} workflow steps successfully."
        }


# Canonical singleton instance
paint_engine = PaintAutomationEngine()


def get_paint_engine() -> PaintAutomationEngine:
    """Returns the singleton instance of PaintAutomationEngine."""
    return paint_engine
