"""
MAX AI — Form Understanding & Form Filling Data Models
Defines typed contracts for form detection, field extraction, semantic mapping,
multi-source data, fill plans, validation, and safety.
"""

from dataclasses import dataclass, field
from enum import Enum
import time
from typing import Dict, Any, List, Optional, Tuple, Union

from core.computer_use.models import Point, Rect, ElementSource


class FormFieldType(str, Enum):
    TEXT = "text"
    TEXTAREA = "textarea"
    EMAIL = "email"
    PHONE = "phone"
    NUMBER = "number"
    PASSWORD = "password"
    DATE = "date"
    TIME = "time"
    URL = "url"
    CHECKBOX = "checkbox"
    RADIO = "radio"
    DROPDOWN = "dropdown"
    COMBOBOX = "combobox"
    AUTOCOMPLETE = "autocomplete"
    SEARCH = "search"
    FILE_UPLOAD = "file_upload"
    SLIDER = "slider"
    TOGGLE = "toggle"
    ADDRESS = "address"
    MULTI_SELECT = "multi_select"
    UNKNOWN = "unknown"


class SemanticRole(str, Enum):
    # Personal Identity
    FIRST_NAME = "FIRST_NAME"
    LAST_NAME = "LAST_NAME"
    FULL_NAME = "FULL_NAME"
    MIDDLE_NAME = "MIDDLE_NAME"
    DATE_OF_BIRTH = "DATE_OF_BIRTH"
    GENDER = "GENDER"
    NATIONALITY = "NATIONALITY"
    
    # Contact
    EMAIL = "EMAIL"
    PHONE = "PHONE"
    ALTERNATE_PHONE = "ALTERNATE_PHONE"
    
    # Address & Location
    ADDRESS = "ADDRESS"
    ADDRESS_LINE_1 = "ADDRESS_LINE_1"
    ADDRESS_LINE_2 = "ADDRESS_LINE_2"
    CITY = "CITY"
    STATE = "STATE"
    POSTAL_CODE = "POSTAL_CODE"
    COUNTRY = "COUNTRY"
    
    # Education
    EDUCATION_INSTITUTION = "EDUCATION_INSTITUTION"
    DEGREE = "DEGREE"
    FIELD_OF_STUDY = "FIELD_OF_STUDY"
    GRADUATION_YEAR = "GRADUATION_YEAR"
    GPA = "GPA"
    
    # Professional / Career
    SKILLS = "SKILLS"
    EXPERIENCE = "EXPERIENCE"
    CURRENT_COMPANY = "CURRENT_COMPANY"
    JOB_TITLE = "JOB_TITLE"
    NOTICE_PERIOD = "NOTICE_PERIOD"
    CURRENT_CTC = "CURRENT_CTC"
    EXPECTED_CTC = "EXPECTED_CTC"
    
    # Documents / Attachments
    RESUME_FILE = "RESUME_FILE"
    COVER_LETTER = "COVER_LETTER"
    PHOTO_FILE = "PHOTO_FILE"
    CERTIFICATE_FILE = "CERTIFICATE_FILE"
    
    # Links & Web
    PORTFOLIO_URL = "PORTFOLIO_URL"
    LINKEDIN_URL = "LINKEDIN_URL"
    GITHUB_URL = "GITHUB_URL"
    WEBSITE_URL = "WEBSITE_URL"
    
    # Authentication & Security
    USERNAME = "USERNAME"
    PASSWORD = "PASSWORD"
    CONFIRM_PASSWORD = "CONFIRM_PASSWORD"
    OTP = "OTP"
    SECURITY_QUESTION = "SECURITY_QUESTION"
    
    # Legal / Preferences / Actions
    SEARCH_QUERY = "SEARCH_QUERY"
    TERMS_AGREEMENT = "TERMS_AGREEMENT"
    REMEMBER_ME = "REMEMBER_ME"
    NEWSLETTER = "NEWSLETTER"
    SUBMIT_BUTTON = "SUBMIT_BUTTON"
    NEXT_STEP_BUTTON = "NEXT_STEP_BUTTON"
    PREV_STEP_BUTTON = "PREV_STEP_BUTTON"
    CANCEL_BUTTON = "CANCEL_BUTTON"
    
    UNKNOWN = "UNKNOWN"


class FormType(str, Enum):
    WEB_FORM = "web_form"
    DESKTOP_FORM = "desktop_form"
    DIALOG_FORM = "dialog_form"
    LOGIN_FORM = "login_form"
    REGISTRATION_FORM = "registration_form"
    JOB_APPLICATION_FORM = "job_application_form"
    PROFILE_FORM = "profile_form"
    SEARCH_FORM = "search_form"
    ADDRESS_FORM = "address_form"
    CONTACT_FORM = "contact_form"
    SETTINGS_FORM = "settings_form"
    MULTI_STEP_FORM = "multi_step_form"
    MIXED_FORM = "mixed_form"
    UNKNOWN = "unknown"


@dataclass
class FormField:
    """Normalized representation of a single interactive or input control in a form."""
    id: str  # Unique field identifier e.g. "f1", "@e4"
    label: str  # Discovered visible label or accessibility name
    nearby_text: str = ""  # Surrounding OCR or helper text
    field_type: FormFieldType = FormFieldType.TEXT
    control_type: str = "Edit"  # UIA or DOM control tag
    value: str = ""  # Current entered value if any
    placeholder: str = ""  # Ghost / watermark text
    required: bool = False  # Is marked required (via *, aria-required, UIA, or label)
    enabled: bool = True
    visible: bool = True
    editable: bool = True
    options: List[str] = field(default_factory=list)  # Available options for select/dropdown/radio
    selected_option: Optional[str] = None
    is_checked: Optional[bool] = None  # Checkbox / Radio state
    bounds: Rect = field(default_factory=lambda: Rect(0, 0, 0, 0))
    center: Point = field(default_factory=lambda: Point(0, 0))
    source: ElementSource = ElementSource.UIA
    confidence: float = 1.0
    semantic_role: SemanticRole = SemanticRole.UNKNOWN
    semantic_confidence: float = 0.0
    group: str = ""  # Logical grouping e.g. "personal_info", "address", "radio_group_gender"
    order: int = 0  # Visual top-to-bottom reading order
    validation_error: Optional[str] = None  # Validation message if present
    is_sensitive: bool = False  # Passwords, OTP, Payment, Govt IDs
    ref: Optional[str] = None  # Ephemeral UI map @ref if available
    selector: Optional[str] = None  # DOM CSS/XPath selector if available
    native_handle: Optional[int] = None
    extra_attributes: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self, mask_sensitive: bool = True) -> Dict[str, Any]:
        val_display = self.value
        if self.is_sensitive and mask_sensitive and val_display:
            val_display = "********"
        return {
            "id": self.id,
            "label": self.label,
            "nearby_text": self.nearby_text,
            "field_type": self.field_type.value if isinstance(self.field_type, FormFieldType) else str(self.field_type),
            "control_type": self.control_type,
            "value": val_display,
            "placeholder": self.placeholder,
            "required": self.required,
            "enabled": self.enabled,
            "visible": self.visible,
            "editable": self.editable,
            "options": self.options,
            "selected_option": self.selected_option,
            "is_checked": self.is_checked,
            "bounds": self.bounds.to_dict() if hasattr(self.bounds, "to_dict") else {},
            "center": [self.center.x, self.center.y] if hasattr(self.center, "x") else [0, 0],
            "source": self.source.value if isinstance(self.source, ElementSource) else str(self.source),
            "confidence": round(self.confidence, 3),
            "semantic_role": self.semantic_role.value if isinstance(self.semantic_role, SemanticRole) else str(self.semantic_role),
            "semantic_confidence": round(self.semantic_confidence, 3),
            "group": self.group,
            "order": self.order,
            "validation_error": self.validation_error,
            "is_sensitive": self.is_sensitive,
            "ref": self.ref,
            "selector": self.selector
        }


@dataclass
class FormSchema:
    """Complete snapshot of a discovered form and its structural metadata."""
    form_id: str
    title: str
    form_type: FormType
    fields: List[FormField] = field(default_factory=list)
    active_window: str = ""
    active_process: str = ""
    active_hwnd: int = 0
    is_multi_step: bool = False
    current_step: int = 1
    total_steps: Optional[int] = None
    submit_button: Optional[FormField] = None
    next_button: Optional[FormField] = None
    prev_button: Optional[FormField] = None
    has_captcha: bool = False
    captcha_message: Optional[str] = None
    confidence: float = 1.0
    timestamp: float = field(default_factory=time.time)

    def get_field_by_id(self, field_id: str) -> Optional[FormField]:
        for f in self.fields:
            if f.id == field_id or f.ref == field_id:
                return f
        return None

    def get_field_by_role(self, role: SemanticRole) -> Optional[FormField]:
        for f in self.fields:
            if f.semantic_role == role:
                return f
        return None

    def get_fields_by_group(self, group: str) -> List[FormField]:
        return [f for f in self.fields if f.group.lower() == group.lower()]

    def to_summary(self) -> str:
        lines = [
            f"=== FORM DETECTED [{self.form_type.value.upper()}] ===",
            f"Title: '{self.title}' | Window: '{self.active_window}' ({self.active_process})",
            f"Multi-step: {self.is_multi_step} (Step {self.current_step}/{self.total_steps or '?'})",
            f"Fields ({len(self.fields)} discovered):"
        ]
        for f in self.fields:
            req_flag = " [REQUIRED]" if f.required else ""
            sens_flag = " [SENSITIVE]" if f.is_sensitive else ""
            val_info = f" (Value: '{'***' if f.is_sensitive else f.value}')" if f.value else ""
            lines.append(
                f"  - [{f.id}] '{f.label}' -> {f.semantic_role.value} ({f.field_type.value}){req_flag}{sens_flag}{val_info}"
            )
        if self.submit_button:
            lines.append(f"Submit Action: '{self.submit_button.label}'")
        if self.has_captcha:
            lines.append("⚠️ CAPTCHA DETECTED: Manual intervention required.")
        return "\n".join(lines)


@dataclass
class FieldFillPlan:
    """Action plan for filling an individual field."""
    field_id: str
    semantic_role: SemanticRole
    field_label: str
    field_type: FormFieldType
    target_value: Any
    action_type: str  # FORM_TYPE, FORM_CLEAR_TYPE, FORM_SELECT, FORM_CHECK, FORM_UNCHECK, FORM_CHOOSE, FORM_AUTOCOMPLETE, FORM_UPLOAD, FORM_SKIP
    confidence: float = 1.0
    is_required: bool = False
    is_sensitive: bool = False
    status: str = "PLANNED"  # "PLANNED", "FILLED", "SKIPPED", "MISSING", "AMBIGUOUS", "SENSITIVE"
    reason: str = ""
    source: str = "profile"  # "explicit_command", "profile", "resume", "generated", "none"


@dataclass
class FormFillPlan:
    """Structured holistic plan for filling all applicable form fields."""
    form_id: str
    form_type: FormType
    title: str
    fields: List[FieldFillPlan] = field(default_factory=list)
    planned_count: int = 0
    missing_required: List[str] = field(default_factory=list)
    skipped_optional: List[str] = field(default_factory=list)
    ambiguous_fields: List[str] = field(default_factory=list)
    sensitive_fields: List[str] = field(default_factory=list)
    should_submit: bool = False
    requires_confirmation: bool = False
    confirmation_reason: Optional[str] = None
    summary: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return {
            "form_id": self.form_id,
            "form_type": self.form_type.value,
            "title": self.title,
            "planned_count": self.planned_count,
            "missing_required": self.missing_required,
            "skipped_optional": self.skipped_optional,
            "ambiguous_fields": self.ambiguous_fields,
            "sensitive_fields": self.sensitive_fields,
            "should_submit": self.should_submit,
            "requires_confirmation": self.requires_confirmation,
            "confirmation_reason": self.confirmation_reason,
            "fields": [
                {
                    "field_id": f.field_id,
                    "semantic_role": f.semantic_role.value,
                    "field_label": f.field_label,
                    "field_type": f.field_type.value,
                    "action": f.action_type,
                    "value": "********" if f.is_sensitive else f.target_value,
                    "confidence": round(f.confidence, 3),
                    "status": f.status,
                    "reason": f.reason
                }
                for f in self.fields
            ]
        }


@dataclass
class FormFillResult:
    """Execution outcome after attempting to fill and verify a form."""
    status: str  # "success", "partial", "failed", "needs_input", "captcha_detected", "awaiting_confirmation"
    filled_count: int = 0
    skipped_count: int = 0
    missing_required: List[str] = field(default_factory=list)
    ambiguous_count: int = 0
    field_results: List[Dict[str, Any]] = field(default_factory=list)
    verified: bool = True
    submitted: bool = False
    has_captcha: bool = False
    message: str = ""
    summary: str = ""
    ticket_id: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "status": self.status,
            "filled_count": self.filled_count,
            "skipped_count": self.skipped_count,
            "missing_required": self.missing_required,
            "ambiguous_count": self.ambiguous_count,
            "field_results": self.field_results,
            "verified": self.verified,
            "submitted": self.submitted,
            "has_captcha": self.has_captcha,
            "message": self.message,
            "summary": self.summary,
            "ticket_id": self.ticket_id
        }
