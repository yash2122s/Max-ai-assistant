"""
MAX AI — Form Understanding & Filling Tool Suite
Provides unified tool bindings for Realtime Voice, HTTP API, and WebSocket pipelines.
"""

from typing import Dict, Any, Optional
from core.form_understanding import form_understanding_engine


def form_understand(include_ocr: bool = True) -> Dict[str, Any]:
    """
    Inspect the desktop/browser screen, detect any visible form, and return structured field schemas and summary.
    Use when the user asks: "What is this form?", "Inspect this form", "What fields are on this page?".
    """
    return form_understanding_engine.understand_current_form(include_ocr=include_ocr)


def form_fill(
    instruction: str = "Fill this form",
    custom_data: Optional[Dict[str, Any]] = None,
    dry_run: bool = False
) -> Dict[str, Any]:
    """
    Intelligently detect, plan, and fill the visible form on screen using user profile, resume, and command instructions.
    Use when the user says: "Fill this form", "Fill this job application", "Enter my name, email and phone",
    "Fill the form using my resume", "Fill all required fields", "Fill this registration form but don't submit".
    """
    return form_understanding_engine.fill_current_form(
        instruction=instruction,
        custom_data=custom_data,
        dry_run=dry_run
    )


def form_analyze(instruction: str = "") -> Dict[str, Any]:
    """
    Analyze the visible form on screen, check available user data, and report what is ready to fill vs what required info is missing.
    Use when the user asks: "What is missing from this form?", "Tell me what info is needed", "Check what fields are missing".
    """
    return form_understanding_engine.analyze_missing_info(instruction=instruction)


def form_fill_field(
    field_name: str,
    value: Any,
    is_checked: Optional[bool] = None
) -> Dict[str, Any]:
    """
    Directly update, type, or toggle a specific field in the visible form.
    Use when the user says: "Set city to Bhimavaram", "Select Java and Python", "Set gender to male", "Choose India as country".
    """
    return form_understanding_engine.fill_single_field_direct(
        field_target=field_name,
        value=value,
        is_checked=is_checked
    )


def form_submit() -> Dict[str, Any]:
    """
    Submit the currently active form. Gated by human confirmation for payment/high-risk forms.
    Use when the user explicitly commands: "Submit this form", "Submit application", "Click submit".
    """
    from core.computer_use.computer_use_engine import computer_use_engine
    return computer_use_engine.step(
        action_type="click",
        target_query="Submit",
        description="Submit form"
    )
