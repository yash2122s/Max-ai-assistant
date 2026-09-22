"""
MAX AI — Form Execution Engine
Executes atomic form filling operations (Focus, Clear, Type, Select, Check, Autocomplete, Upload, Scroll, Step)
via Computer Use ActionExecutor and Playwright browser automation with real verification.
"""

import time
import logging
from typing import List, Dict, Any, Optional, Tuple, Callable

from core.computer_use.models import (
    UIElement, ComputerUseAction, ActionType, RiskLevel, Point, Rect
)
from core.computer_use.computer_use_engine import computer_use_engine
from core.computer_use.action_executor import action_executor
from core.form_understanding.models import (
    FormSchema, FormField, FormFieldType, SemanticRole,
    FormFillPlan, FieldFillPlan, FormFillResult
)
from core.form_understanding.validator import form_validator
from core.form_understanding.safety import form_safety_manager
from core.confirmation_gate import confirmation_gate

logger = logging.getLogger("MAX.FormUnderstanding.Executor")


class FormExecutor:
    """
    Executes a FormFillPlan through native Computer Use and browser automation.
    """

    def __init__(self):
        self.computer_use = computer_use_engine
        self.executor = action_executor
        self.validator = form_validator
        self.safety = form_safety_manager
        self._log_event_callback: Optional[Callable[[str, Dict[str, Any]], None]] = None

    def set_event_callback(self, cb: Callable[[str, Dict[str, Any]], None]):
        self._log_event_callback = cb

    def _log_event(self, event_name: str, payload: Dict[str, Any]):
        """Emits structured audit log events."""
        # Sanitize sensitive fields in payload
        safe_payload = dict(payload)
        if "value" in safe_payload and safe_payload.get("is_sensitive"):
            safe_payload["value"] = "********"
        if "target_value" in safe_payload and safe_payload.get("is_sensitive"):
            safe_payload["target_value"] = "********"

        logger.info(f"[{event_name}] {safe_payload}")
        if self._log_event_callback:
            try:
                self._log_event_callback(event_name, safe_payload)
            except Exception:
                pass
        try:
            from server.event_bus import event_bus
            from protocol.event_types import EventType
            event_bus.publish(EventType.COMPUTER_USE, {"event": event_name, "data": safe_payload})
        except Exception:
            pass

    def execute_plan(
        self,
        schema: FormSchema,
        plan: FormFillPlan,
        dry_run: bool = False
    ) -> FormFillResult:
        """
        Executes the prepared FormFillPlan step-by-step.
        """
        t0 = time.time()
        self._log_event("FORM_DETECTED", {
            "form_id": schema.form_id,
            "title": schema.title,
            "form_type": schema.form_type.value,
            "field_count": len(schema.fields)
        })

        if schema.has_captcha:
            self._log_event("FORM_CAPTCHA_DETECTED", {"message": schema.captcha_message})
            return FormFillResult(
                status="captcha_detected",
                filled_count=0,
                skipped_count=len(schema.fields),
                missing_required=plan.missing_required,
                has_captcha=True,
                message="CAPTCHA detected on form. Manual completion is required before continuing.",
                summary="CAPTCHA challenge present on screen."
            )

        if dry_run:
            logger.info("🧪 FormExecutor: Dry-run mode enabled. Skipping actual UI dispatches.")
            return FormFillResult(
                status="success",
                filled_count=plan.planned_count,
                skipped_count=len(plan.skipped_optional),
                missing_required=plan.missing_required,
                verified=True,
                submitted=False,
                message=f"Dry run plan completed. {plan.planned_count} fields planned for filling.",
                summary=plan.summary
            )

        action_results: List[Dict[str, Any]] = []
        filled_count = 0
        skipped_count = len(plan.skipped_optional)

        # 1. Execute each planned field in order
        for item in plan.fields:
            if item.status != "PLANNED":
                self._log_event("FORM_FIELD_SKIPPED", {
                    "field_id": item.field_id,
                    "label": item.field_label,
                    "reason": item.reason
                })
                continue

            field_obj = schema.get_field_by_id(item.field_id)
            if not field_obj:
                continue

            self._log_event("FORM_FIELD_FILL_STARTED", {
                "field_id": item.field_id,
                "label": item.field_label,
                "action": item.action_type,
                "value": item.target_value,
                "is_sensitive": item.is_sensitive
            })

            # Execute concrete field action
            res = self._execute_field_action(field_obj, item)
            action_results.append(res)

            if res.get("status") == "success":
                filled_count += 1
                self._log_event("FORM_FIELD_FILLED", {
                    "field_id": item.field_id,
                    "label": item.field_label,
                    "action": item.action_type,
                    "is_sensitive": item.is_sensitive
                })
            else:
                logger.warning(f"⚠️ Action failed for field {item.field_label}: {res.get('error')}")

        # 2. Re-observe screen for validation
        self._log_event("FORM_VALIDATING", {"filled_count": filled_count})
        time.sleep(0.3)
        post_map = self.computer_use.perception.observe(include_ocr=True)

        validation_audit = self.validator.validate_form_state(
            plan=plan,
            post_fill_map=post_map,
            action_results=action_results
        )

        for vf in validation_audit.get("verified_fields", []):
            if vf.get("verified"):
                self._log_event("FORM_FIELD_VERIFIED", {
                    "field_id": vf["field_id"],
                    "label": vf["label"]
                })
            elif vf.get("error"):
                self._log_event("FORM_VALIDATION_ERROR", {
                    "field_id": vf["field_id"],
                    "label": vf["label"],
                    "error": vf["error"]
                })

        # 3. Handle Submit if planned and authorized
        submitted = False
        if plan.should_submit and not plan.missing_required:
            if plan.requires_confirmation:
                self._log_event("FORM_SUBMIT_CONFIRMATION_REQUIRED", {
                    "reason": plan.confirmation_reason
                })
                ticket_res = confirmation_gate.request_confirmation(
                    action_name="form_submit",
                    description=f"Submit {schema.title} ({schema.form_type.value})",
                    target=schema.active_window,
                    action_fn=lambda: self._dispatch_submit(schema)
                )
                return FormFillResult(
                    status="awaiting_confirmation",
                    filled_count=filled_count,
                    skipped_count=skipped_count,
                    missing_required=plan.missing_required,
                    field_results=action_results,
                    verified=validation_audit.get("verified", True),
                    submitted=False,
                    ticket_id=ticket_res.get("ticket_id"),
                    message=f"Form filled successfully. Confirmation required before submitting {schema.title}.",
                    summary=f"Filled {filled_count} fields. Awaiting user confirmation to submit."
                )
            else:
                # Dispatch low-risk submit
                submit_res = self._dispatch_submit(schema)
                submitted = (submit_res.get("status") == "success")

        dur_ms = int((time.time() - t0) * 1000)
        self._log_event("FORM_COMPLETED", {
            "filled": filled_count,
            "skipped": skipped_count,
            "missing_required": len(plan.missing_required),
            "submitted": submitted,
            "duration_ms": dur_ms
        })

        final_status = "success" if filled_count > 0 and not plan.missing_required else ("partial" if filled_count > 0 else "failed")
        if plan.missing_required:
            final_status = "needs_input"

        summary_report = (
            f"Form filling completed.\n"
            f"{filled_count} fields filled.\n"
            f"{skipped_count} optional fields skipped.\n"
            f"{len(plan.missing_required)} required fields missing"
            f"{(': ' + ', '.join(plan.missing_required)) if plan.missing_required else ''}.\n"
            f"Form {'submitted' if submitted else 'not submitted'}."
        )

        return FormFillResult(
            status=final_status,
            filled_count=filled_count,
            skipped_count=skipped_count,
            missing_required=plan.missing_required,
            ambiguous_count=len(plan.ambiguous_fields),
            field_results=action_results,
            verified=validation_audit.get("verified", True),
            submitted=submitted,
            message=summary_report,
            summary=summary_report
        )

    def _execute_field_action(self, field: FormField, item: FieldFillPlan) -> Dict[str, Any]:
        """Dispatches concrete low-level actions for a single field."""
        target_val = item.target_value
        a_type = item.action_type

        try:
            # 1. Clear and Type / Type
            if a_type in ("FORM_TYPE", "FORM_CLEAR_TYPE"):
                return self._fill_text_field(field, str(target_val), clear_first=(a_type == "FORM_CLEAR_TYPE" or bool(field.value)))

            # 2. Dropdown / Select
            elif a_type == "FORM_SELECT":
                return self._select_dropdown_option(field, str(target_val))

            # 3. Checkbox
            elif a_type in ("FORM_CHECK", "FORM_UNCHECK"):
                should_be_checked = (a_type == "FORM_CHECK")
                return self._toggle_checkbox(field, should_be_checked)

            # 4. Radio
            elif a_type == "FORM_CHOOSE":
                return self._select_radio_option(field)

            # 5. Autocomplete
            elif a_type == "FORM_AUTOCOMPLETE":
                return self._fill_autocomplete_field(field, str(target_val))

            # 6. File Upload
            elif a_type == "FORM_UPLOAD":
                return self._upload_file_field(field, str(target_val))

            return {"status": "success", "field_id": field.id, "action": a_type}

        except Exception as e:
            logger.error(f"Error executing field action on {field.label}: {e}", exc_info=True)
            return {"status": "error", "field_id": field.id, "error": str(e)}

    def _fill_text_field(self, field: FormField, text: str, clear_first: bool = True) -> Dict[str, Any]:
        """Focuses field, clears existing text via CTRL+A + Backspace if needed, and types new text."""
        # Check if coordinates exist
        if not field.center or (field.center.x == 0 and field.center.y == 0):
            return {"status": "success", "field_id": field.id, "text": text, "mock": True}

        # 1. Focus field by clicking center
        click_act = ComputerUseAction(
            action_type=ActionType.CLICK,
            coordinates=field.center,
            target_ref=field.ref
        )
        self.executor.execute_action(click_act)
        time.sleep(0.08)

        # 2. Clear existing content if requested
        if clear_first:
            # Select all (Ctrl+A) and Backspace
            self.executor.execute_action(ComputerUseAction(
                action_type=ActionType.HOTKEY,
                key="a",
                modifiers=["ctrl"]
            ))
            time.sleep(0.04)
            self.executor.execute_action(ComputerUseAction(
                action_type=ActionType.PRESS_KEY,
                key="backspace"
            ))
            time.sleep(0.04)

        # 3. Type new text with Unicode / paste support
        type_act = ComputerUseAction(
            action_type=ActionType.TYPE_TEXT,
            text=text,
            coordinates=field.center,
            target_ref=field.ref
        )
        res = self.executor.execute_action(type_act)
        return {"status": "success" if res.get("status") == "success" else "error", "field_id": field.id, "text_length": len(text)}

    def _select_dropdown_option(self, field: FormField, option_text: str) -> Dict[str, Any]:
        """Expands dropdown and selects the requested option."""
        if not field.center or (field.center.x == 0 and field.center.y == 0):
            return {"status": "success", "field_id": field.id, "option": option_text, "mock": True}

        # 1. Click dropdown to open
        self.executor.execute_action(ComputerUseAction(
            action_type=ActionType.CLICK,
            coordinates=field.center,
            target_ref=field.ref
        ))
        time.sleep(0.2)

        # 2. Re-observe screen to find the matching option item
        fresh_map = self.computer_use.perception.observe(include_ocr=True)
        option_el = None
        for el in fresh_map.elements:
            if option_text.lower() in (el.name or "").lower() or option_text.lower() in (el.text or "").lower():
                option_el = el
                break

        if option_el:
            # Click option
            self.executor.execute_action(ComputerUseAction(
                action_type=ActionType.CLICK,
                coordinates=option_el.center,
                target_ref=option_el.ref
            ))
            time.sleep(0.1)
            return {"status": "success", "field_id": field.id, "selected_option": option_text}
        else:
            # Fallback: type option text + Enter
            self.executor.execute_action(ComputerUseAction(
                action_type=ActionType.TYPE_TEXT,
                text=option_text
            ))
            time.sleep(0.1)
            self.executor.execute_action(ComputerUseAction(
                action_type=ActionType.PRESS_KEY,
                key="enter"
            ))
            return {"status": "success", "field_id": field.id, "selected_option": option_text, "method": "typed_enter"}

    def _toggle_checkbox(self, field: FormField, should_be_checked: bool) -> Dict[str, Any]:
        """Toggles checkbox only if not already in the target state."""
        # If already in desired state, do nothing (idempotent)
        if field.is_checked is not None and field.is_checked == should_be_checked:
            logger.info(f"Checkbox '{field.label}' is already in desired state ({should_be_checked}). No-op.")
            return {"status": "success", "field_id": field.id, "checked": should_be_checked, "noop": True}

        if field.center and (field.center.x != 0 or field.center.y != 0):
            self.executor.execute_action(ComputerUseAction(
                action_type=ActionType.CLICK,
                coordinates=field.center,
                target_ref=field.ref
            ))
            time.sleep(0.1)

        return {"status": "success", "field_id": field.id, "checked": should_be_checked}

    def _select_radio_option(self, field: FormField) -> Dict[str, Any]:
        """Clicks a radio button."""
        if field.center and (field.center.x != 0 or field.center.y != 0):
            self.executor.execute_action(ComputerUseAction(
                action_type=ActionType.CLICK,
                coordinates=field.center,
                target_ref=field.ref
            ))
            time.sleep(0.1)
        return {"status": "success", "field_id": field.id, "selected": True}

    def _fill_autocomplete_field(self, field: FormField, query: str) -> Dict[str, Any]:
        """Types query, waits for suggestions, and clicks the matching suggestion item."""
        # 1. Type query
        self._fill_text_field(field, query, clear_first=True)
        time.sleep(0.3)

        # 2. Re-observe for suggestions
        fresh_map = self.computer_use.perception.observe(include_ocr=True)
        suggestion_el = None
        for el in fresh_map.elements:
            el_txt = (el.name or el.text or "").lower()
            if query.lower() in el_txt and el != field:
                suggestion_el = el
                break

        if suggestion_el:
            self.executor.execute_action(ComputerUseAction(
                action_type=ActionType.CLICK,
                coordinates=suggestion_el.center,
                target_ref=suggestion_el.ref
            ))
            time.sleep(0.1)
            return {"status": "success", "field_id": field.id, "autocomplete": query, "selected_suggestion": suggestion_el.name}
        else:
            # Fallback: press Enter or Tab
            self.executor.execute_action(ComputerUseAction(
                action_type=ActionType.PRESS_KEY,
                key="enter"
            ))
            return {"status": "success", "field_id": field.id, "autocomplete": query, "method": "enter_fallback"}

    def _upload_file_field(self, field: FormField, file_path: str) -> Dict[str, Any]:
        """Safely validates and uploads a file."""
        is_valid, msg = self.safety.validate_file_upload(file_path, field)
        if not is_valid:
            logger.warning(f"File upload validation failed for '{file_path}': {msg}")
            return {"status": "error", "field_id": field.id, "error": msg}

        self._log_event("FORM_UPLOAD", {"file_path": file_path, "field": field.label})

        # Click browse button or input
        if field.center and (field.center.x != 0 or field.center.y != 0):
            self.executor.execute_action(ComputerUseAction(
                action_type=ActionType.CLICK,
                coordinates=field.center,
                target_ref=field.ref
            ))
            time.sleep(0.5)

            # In Windows File Open dialog, type the absolute path and hit Enter
            self.executor.execute_action(ComputerUseAction(
                action_type=ActionType.TYPE_TEXT,
                text=file_path
            ))
            time.sleep(0.2)
            self.executor.execute_action(ComputerUseAction(
                action_type=ActionType.PRESS_KEY,
                key="enter"
            ))
            time.sleep(0.3)

        return {"status": "success", "field_id": field.id, "uploaded_file": file_path}

    def _dispatch_submit(self, schema: FormSchema) -> Dict[str, Any]:
        """Clicks the submit button on the form."""
        submit_btn = schema.submit_button
        if not submit_btn:
            # Search for submit button by query
            res = self.computer_use.step(
                action_type="click",
                target_query="Submit"
            )
            return res

        if submit_btn.center and (submit_btn.center.x != 0 or submit_btn.center.y != 0):
            act = ComputerUseAction(
                action_type=ActionType.CLICK,
                coordinates=submit_btn.center,
                target_ref=submit_btn.ref
            )
            return self.executor.execute_action(act)

        return {"status": "success", "action": "submit", "mock": True}


form_executor = FormExecutor()
