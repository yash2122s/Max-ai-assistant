"""
Unit tests for AdaptiveUiGrounder in MAX Windows Agent.
"""

import pytest
from core.computer_use.models import UIElement, UIMap, Rect, Point, ElementSource
from core.skills.runtime.adaptive_grounder import AdaptiveUiGrounder, GroundedTarget


@pytest.fixture
def mock_ui_map():
    elements = [
        UIElement(
            ref="@e1",
            role="button",
            name="Search",
            bounds=Rect(left=10, top=10, right=50, bottom=30),
            center=Point(x=30, y=20),
            automation_id="search_button"
        ),
        UIElement(
            ref="@e2",
            role="row",
            name="yaswanth.1432 (Sponsored)",
            bounds=Rect(left=10, top=40, right=200, bottom=70),
            center=Point(x=105, y=55),
            automation_id="account_row_sponsored"
        ),
        UIElement(
            ref="@e3",
            role="row",
            name="yaswanth.1432",
            bounds=Rect(left=10, top=80, right=200, bottom=110),
            center=Point(x=105, y=95),
            automation_id="account_row_exact"
        ),
        UIElement(
            ref="@e4",
            role="button",
            name="Message",
            bounds=Rect(left=50, top=120, right=120, bottom=150),
            center=Point(x=85, y=135),
            automation_id="message_btn"
        )
    ]
    return UIMap(
        generation=1,
        active_window="Instagram",
        active_process="Instagram.exe",
        elements=elements,
        ref_map={e.ref: e for e in elements}
    )


def test_ground_direct_target(mock_ui_map):
    grounder = AdaptiveUiGrounder()
    res = grounder.ground_target("Search", ui_map=mock_ui_map)
    assert res.success is True
    assert res.element is not None
    assert res.element.ref == "@e1"
    assert res.center_x == 30
    assert res.center_y == 20


def test_ground_with_avoid_filter(mock_ui_map):
    grounder = AdaptiveUiGrounder()
    # "yaswanth.1432" matches both @e2 and @e3, but @e2 has "Sponsored".
    # With avoid=["sponsored"], it should pick @e3.
    res = grounder.ground_target(
        "yaswanth.1432",
        arguments={"avoid": ["sponsored", "ad"], "role": "row"},
        ui_map=mock_ui_map
    )
    assert res.success is True
    assert res.element is not None
    assert res.element.ref == "@e3"
    assert res.center_y == 95


def test_ground_missing_target(mock_ui_map):
    grounder = AdaptiveUiGrounder()
    res = grounder.ground_target("NonExistentElement", ui_map=mock_ui_map)
    assert res.success is False
    assert "Failed to ground target" in res.reason


def test_ground_empty_ui_map():
    grounder = AdaptiveUiGrounder()
    empty_map = UIMap(elements=[])
    res = grounder.ground_target("Search", ui_map=empty_map)
    assert res.success is False
    assert "No active Windows UIMap" in res.reason
