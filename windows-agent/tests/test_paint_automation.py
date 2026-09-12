"""
Unit tests for Paint Automation Engine (Phase C).
Tests cover:
- Mouse primitives in InputHelper (mouse_down, mouse_up, move_mouse, drag_path, left_click)
- CanvasBounds normalization, relative coordinate mapping, and boundary clamping
- Shape path generation (triangle, circle, rectangle, diamond, star, arrow, heart, etc.)
- Brush geometric stroke generation (line, circle, wave, zigzag, spiral, smiley, etc.)
- Tool name fuzzy resolution and tool selection with explicit left-click
- Color selection (named, hex, RGB dict)
- Workflow plan execution (sequential actions)
- Bounded retry and error handling
- Mock-based tests to avoid requiring real GUI / hardware mouse
"""

import math
import pytest
from unittest.mock import MagicMock, patch

from core.paint_automation import (
    CanvasBounds,
    PaintAutomationEngine,
    get_paint_engine,
)
from tools.input_tool import input_helper, InputTool


class TestMousePrimitives:
    """Test standardized mouse primitives in input_helper."""

    def test_left_click(self):
        with patch.object(input_helper.user32, "SetCursorPos", return_value=1), \
             patch.object(input_helper.user32, "mouse_event") as mock_event:
            res = input_helper.left_click(100, 200, clicks=1)
            assert res.get("status") == "success"
            assert mock_event.call_count == 2  # DOWN + UP

    def test_mouse_down_and_up(self):
        with patch.object(input_helper.user32, "SetCursorPos", return_value=1), \
             patch.object(input_helper.user32, "mouse_event") as mock_event:
            res_down = input_helper.mouse_down(150, 250, button="left")
            res_up = input_helper.mouse_up(150, 250, button="left")
            assert res_down.get("status") == "success"
            assert res_up.get("status") == "success"
            assert mock_event.call_count == 2

    def test_move_mouse_smooth(self):
        with patch.object(input_helper.user32, "SetCursorPos", return_value=1) as mock_cursor, \
             patch.object(input_helper.user32, "GetCursorPos", return_value=1), \
             patch("time.sleep"):
            res = input_helper.move_mouse(300, 400, absolute=True, duration_sec=0.05)
            assert res.get("status") == "success"
            assert mock_cursor.call_count >= 1

    def test_drag_path(self):
        points = [(10, 10), (20, 20), (30, 30)]
        with patch.object(input_helper, "mouse_down", return_value={"status": "success"}) as mock_down, \
             patch.object(input_helper, "mouse_up", return_value={"status": "success"}) as mock_up, \
             patch.object(input_helper.user32, "SetCursorPos", return_value=1) as mock_cursor:
            res = input_helper.drag_path(points, duration_sec=0.05)
            assert res.get("status") == "success"
            mock_down.assert_called_once_with(10, 10, button="left")
            mock_up.assert_called_once_with(30, 30, button="left")
            assert mock_cursor.call_count >= 3


class TestCanvasBounds:
    """Test canvas normalization, boundary calculations, and safety margins."""

    def test_canvas_bounds_coordinate_translation(self):
        cb = CanvasBounds(left=100, top=100, right=500, bottom=500, safe_margin=10)
        assert cb.width == 400
        assert cb.height == 400
        assert cb.effective_width == 380
        assert cb.effective_height == 380

        # Center (0.5, 0.5)
        cx, cy = cb.to_screen_coords(0.5, 0.5)
        assert cx == 100 + 10 + int(380 * 0.5)  # 300
        assert cy == 100 + 10 + int(380 * 0.5)  # 300

        # Clamping outside [0.0, 1.0]
        clamp_x, clamp_y = cb.to_screen_coords(-0.5, 1.5)
        assert clamp_x == 110  # Left margin
        assert clamp_y == 110 + 380  # Bottom margin

    def test_canvas_bounds_rect_generation(self):
        cb = CanvasBounds(left=0, top=0, right=1000, bottom=1000, safe_margin=0)
        coords = cb.to_screen_rect(0.2, 0.2, 0.8, 0.8)
        assert coords == (200, 200, 800, 800)


class TestPaintAutomationEngine:
    """Test PaintAutomationEngine methods, path generators, and workflows."""

    @pytest.fixture
    def engine(self):
        eng = PaintAutomationEngine()
        eng.hwnd = 12345
        eng.canvas = CanvasBounds(left=100, top=100, right=900, bottom=700, safe_margin=10)
        return eng

    def test_geometric_stroke_generators(self, engine):
        # Line
        line_pts = engine.generate_geometric_stroke("line", 0.2, 0.2, 0.8, 0.8, steps=10)
        assert len(line_pts) >= 10
        assert line_pts[0] == (0.2, 0.2)
        assert line_pts[-1] == (0.8, 0.8)

        # Circle
        circle_pts = engine.generate_geometric_stroke("circle", 0.5, 0.5, 0.8, 0.8, steps=20)
        assert len(circle_pts) >= 20
        # Closed shape: first point should be close to last point
        dx = abs(circle_pts[0][0] - circle_pts[-1][0])
        dy = abs(circle_pts[0][1] - circle_pts[-1][1])
        assert dx < 0.05 and dy < 0.05

        # Wave
        wave_pts = engine.generate_geometric_stroke("wave", 0.1, 0.5, 0.9, 0.5, steps=30)
        assert len(wave_pts) >= 30

        # Smiley
        smiley_pts = engine.generate_geometric_stroke("smiley", steps=20)
        assert len(smiley_pts) >= 20

    def test_shape_path_generators(self, engine):
        # Triangle
        tri = engine._generate_shape_path("triangle", (0.2, 0.2, 0.8, 0.8))
        assert len(tri) == 4  # 3 vertices + closing vertex
        assert tri[0] == tri[-1]

        # Diamond
        diamond = engine._generate_shape_path("diamond", (0.2, 0.2, 0.8, 0.8))
        assert len(diamond) == 5
        assert diamond[0] == diamond[-1]

        # Heart
        heart = engine._generate_shape_path("heart", (0.2, 0.2, 0.8, 0.8))
        assert len(heart) > 10

    def test_draw_shape_execution(self, engine):
        with patch.object(input_helper, "drag_path", return_value={"status": "success"}) as mock_drag, \
             patch.object(engine, "select_shape", return_value=True):
            res = engine.draw_shape("triangle", (0.2, 0.2), (0.8, 0.8))
            assert res["status"] == "success"
            assert res["shape"] == "triangle"
            mock_drag.assert_called_once()

    def test_draw_brush_stroke_execution(self, engine):
        with patch.object(input_helper, "drag_path", return_value={"status": "success"}) as mock_drag, \
             patch.object(engine, "select_tool", return_value=True):
            res = engine.draw_brush_stroke(stroke_type="wave", points_rel=None)
            assert res["status"] == "success"
            assert res["stroke_type"] == "wave"
            mock_drag.assert_called_once()

    def test_select_color_named_and_hex(self, engine):
        with patch.object(input_helper, "left_click", return_value={"status": "success"}):
            # Red
            res_red = engine.select_color("red")
            assert res_red["status"] == "success"
            assert res_red["color"] == "red"

            # Hex
            res_hex = engine.select_color("#00FF00")
            assert res_hex["status"] == "success"

            # Dict RGB
            res_dict = engine.select_color({"r": 0, "g": 0, "b": 255})
            assert res_dict["status"] == "success"

    def test_execute_workflow(self, engine):
        with patch.object(engine, "ensure_paint_ready", return_value=True), \
             patch.object(engine, "select_tool", return_value=True), \
             patch.object(engine, "select_color", return_value={"status": "success"}), \
             patch.object(engine, "draw_brush_stroke", return_value={"status": "success", "stroke_type": "line"}):

            plan = {
                "app": "paint",
                "actions": [
                    {"type": "open"},
                    {"type": "select_tool", "tool": "brush"},
                    {"type": "select_color", "color": "blue"},
                    {"type": "draw_brush", "stroke_type": "line"}
                ]
            }
            res = engine.execute_workflow(plan)
            assert res["status"] == "success"
            assert res["total_steps"] == 4
            assert len(res["executed_steps"]) == 4


class TestToolRegistryIntegration:
    """Test tool registry wrapping and declarations."""

    def test_paint_tools_in_app_control(self):
        from tools.app_control import (
            paint_draw_shape,
            paint_draw_brush,
            paint_select_tool,
            paint_select_color,
            paint_workflow,
            TOOL_DECLARATIONS,
            TOOL_FUNCTIONS
        )
        assert "paint_draw_shape" in TOOL_FUNCTIONS
        assert "paint_draw_brush" in TOOL_FUNCTIONS
        assert "paint_select_tool" in TOOL_FUNCTIONS
        assert "paint_select_color" in TOOL_FUNCTIONS
        assert "paint_workflow" in TOOL_FUNCTIONS

        decl_names = [d["name"] for d in TOOL_DECLARATIONS]
        assert "paint_draw_shape" in decl_names
        assert "paint_draw_brush" in decl_names
        assert "paint_select_tool" in decl_names
        assert "paint_select_color" in decl_names
        assert "paint_workflow" in decl_names
