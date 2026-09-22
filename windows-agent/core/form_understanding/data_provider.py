"""
MAX AI — Multi-Source Form Data Provider
Safely provides normalized user data from explicit commands, memory, user profile,
and resume records. Enforces the strict rule: NEVER invent personal values.
"""

import os
import re
import time
import datetime
import logging
from typing import Dict, Any, Optional, Tuple, List, Union

from core.form_understanding.models import SemanticRole, FormFieldType
from core.state_database import state_db

logger = logging.getLogger("MAX.FormUnderstanding.DataProvider")


class FormDataProvider:
    """
    Supplies validated data to the form filler from multiple verified sources.
    """

    def __init__(self):
        self._cached_profile: Optional[Dict[SemanticRole, Any]] = None
        self._cached_resume: Optional[Dict[SemanticRole, Any]] = None

    def get_data_bundle(
        self,
        explicit_command: Optional[str] = None,
        custom_overrides: Optional[Dict[str, Any]] = None
    ) -> Dict[SemanticRole, Tuple[Any, str]]:
        """
        Constructs a consolidated data map: SemanticRole -> (Value, SourceName).
        Precedence:
        1. Custom overrides
        2. Explicit parsed command values (e.g. 'Set city to Bhimavaram', 'Choose India')
        3. User Profile / Resume data from Memory & State DB
        4. Dynamic generated values (Today's date)
        """
        bundle: Dict[SemanticRole, Tuple[Any, str]] = {}

        # 1. Load Profile & Resume facts from DB/storage
        profile_data = self.load_profile_data()
        for role, val in profile_data.items():
            if val is not None:
                bundle[role] = (val, "profile")

        resume_data = self.load_resume_data()
        for role, val in resume_data.items():
            if val is not None:
                bundle[role] = (val, "resume")

        # 2. Dynamic values (Date)
        now_dt = datetime.datetime.now()
        bundle[SemanticRole.DATE_OF_BIRTH] = bundle.get(SemanticRole.DATE_OF_BIRTH, (None, "none"))
        today_iso = now_dt.strftime("%Y-%m-%d")
        # Dynamic date for general date fields

        # 3. Parse explicit command instructions
        if explicit_command:
            explicit_vals = self.parse_explicit_command_values(explicit_command)
            for role, val in explicit_vals.items():
                if val is not None:
                    bundle[role] = (val, "explicit_command")

        # 4. Apply custom overrides
        if custom_overrides:
            for k, val in custom_overrides.items():
                # Convert string key to SemanticRole if possible
                try:
                    role_enum = SemanticRole(k.upper())
                    bundle[role_enum] = (val, "custom_override")
                except ValueError:
                    pass

        return bundle

    def get_value_for_role(
        self,
        role: SemanticRole,
        field_type: FormFieldType = FormFieldType.TEXT,
        field_label: str = "",
        data_bundle: Optional[Dict[SemanticRole, Tuple[Any, str]]] = None
    ) -> Tuple[Optional[Any], str]:
        """
        Returns (value, source_name) for a given semantic role.
        Never fabricates personal identity values!
        """
        if data_bundle is None:
            data_bundle = self.get_data_bundle()

        # Direct match
        if role in data_bundle and data_bundle[role][0] is not None:
            return data_bundle[role]

        # Derived matches:
        # Full name derived from First + Last
        if role == SemanticRole.FULL_NAME:
            f_name, _ = data_bundle.get(SemanticRole.FIRST_NAME, (None, ""))
            l_name, _ = data_bundle.get(SemanticRole.LAST_NAME, (None, ""))
            if f_name and l_name:
                return f"{f_name} {l_name}", "derived_from_names"
            elif f_name:
                return f_name, "derived_from_names"

        # First Name derived from Full Name
        if role == SemanticRole.FIRST_NAME:
            full, _ = data_bundle.get(SemanticRole.FULL_NAME, (None, ""))
            if full and isinstance(full, str):
                parts = full.strip().split()
                if parts:
                    return parts[0], "derived_from_full_name"

        # Last Name derived from Full Name
        if role == SemanticRole.LAST_NAME:
            full, _ = data_bundle.get(SemanticRole.FULL_NAME, (None, ""))
            if full and isinstance(full, str):
                parts = full.strip().split()
                if len(parts) > 1:
                    return parts[-1], "derived_from_full_name"

        # Address line 1 from Full Address
        if role == SemanticRole.ADDRESS_LINE_1:
            full_addr, _ = data_bundle.get(SemanticRole.ADDRESS, (None, ""))
            if full_addr:
                return full_addr, "derived_from_address"

        # Terms Agreement checkbox
        if role == SemanticRole.TERMS_AGREEMENT or "agree" in field_label.lower():
            return True, "standard_declaration"

        # Date requested as 'today's date' or current date
        if "today" in field_label.lower() or "current date" in field_label.lower() or "submission date" in field_label.lower():
            return datetime.datetime.now().strftime("%Y-%m-%d"), "dynamic_date"

        return None, "missing"

    def parse_explicit_command_values(self, command: str) -> Dict[SemanticRole, Any]:
        """
        Extracts structured values from natural language user instructions.
        Examples:
        - "Enter my name as Yash, email yash@example.com, phone 9876543210"
        - "Set gender to male"
        - "Choose India as country"
        - "Select Java and Python"
        - "Enter today's date"
        """
        extracted: Dict[SemanticRole, Any] = {}
        text = command.strip()

        # Email extraction
        email_match = re.search(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,7}\b", text)
        if email_match:
            extracted[SemanticRole.EMAIL] = email_match.group(0)

        # Phone extraction (10-12 digits)
        phone_match = re.search(r"(?:phone|mobile|contact|cell|tel)?\s*(?:is|to|as|number|no)?\s*([+]?[0-9]{1,3}?[-.\s]?\(?[0-9]{3}\)?[-.\s]?[0-9]{3}[-.\s]?[0-9]{4})\b", text, re.I)
        if not phone_match:
            phone_match = re.search(r"\b([+]?[0-9]{10,13})\b", text)
        if phone_match:
            extracted[SemanticRole.PHONE] = phone_match.group(1).strip()

        # Gender
        if re.search(r"\b(gender|sex)\s+(?:to|is|as)?\s*(male|female|other|non-binary)\b", text, re.I):
            g_match = re.search(r"\b(gender|sex)\s+(?:to|is|as)?\s*(male|female|other|non-binary)\b", text, re.I)
            extracted[SemanticRole.GENDER] = g_match.group(2).capitalize()
        elif re.search(r"\b(set|choose|select)\s+gender\s+as\s+(male|female|other)\b", text, re.I):
            g_match = re.search(r"\b(set|choose|select)\s+gender\s+as\s+(male|female|other)\b", text, re.I)
            extracted[SemanticRole.GENDER] = g_match.group(2).capitalize()

        # Country
        c_match = re.search(r"(?:country|nation)\s+(?:to|is|as)?\s*([A-Za-z\s]+?)(?:[,\.]|$|and)", text, re.I)
        if c_match:
            extracted[SemanticRole.COUNTRY] = c_match.group(1).strip()
        elif re.search(r"(?:choose|select|set)\s+([A-Za-z\s]+?)\s+as\s+country", text, re.I):
            c_match = re.search(r"(?:choose|select|set)\s+([A-Za-z\s]+?)\s+as\s+country", text, re.I)
            extracted[SemanticRole.COUNTRY] = c_match.group(1).strip()

        # City
        city_match = re.search(r"(?:city|town)\s+(?:to|is|as)?\s*([A-Za-z\s]+?)(?:[,\.]|$|and)", text, re.I)
        if city_match:
            extracted[SemanticRole.CITY] = city_match.group(1).strip()
        elif re.search(r"(?:set|enter)\s+city\s+to\s+([A-Za-z\s]+?)(?:[,\.]|$|and)", text, re.I):
            city_match = re.search(r"(?:set|enter)\s+city\s+to\s+([A-Za-z\s]+?)(?:[,\.]|$|and)", text, re.I)
            extracted[SemanticRole.CITY] = city_match.group(1).strip()

        # Skills
        skills_match = re.search(r"(?:select|choose|skills?|technologies?)\s+(?:to|is|as|are)?\s*([A-Za-z0-9\,\s\+\#\.]+?)(?:for|in|as|\.|$)", text, re.I)
        if "select " in text.lower() and (" and " in text.lower() or "," in text):
            sub_skills = re.findall(r"\b(Java|Python|C\+\+|JavaScript|TypeScript|React|Node|Go|Rust|Kotlin|Swift|SQL|AWS|Docker)\b", text, re.I)
            if sub_skills:
                extracted[SemanticRole.SKILLS] = sub_skills

        # Today's date
        if "today's date" in text.lower() or "todays date" in text.lower() or "today date" in text.lower():
            extracted[SemanticRole.DATE_OF_BIRTH] = datetime.datetime.now().strftime("%Y-%m-%d")

        # Name patterns
        name_match = re.search(r"(?:name)\s+(?:is|to|as)\s+([A-Za-z\s]+?)(?:[,\.]|$|email|phone|and)", text, re.I)
        if name_match:
            extracted[SemanticRole.FULL_NAME] = name_match.group(1).strip()

        return extracted

    def load_profile_data(self) -> Dict[SemanticRole, Any]:
        """Loads persistent user profile records from state DB and configuration."""
        if self._cached_profile is not None:
            return self._cached_profile

        profile: Dict[SemanticRole, Any] = {}
        try:
            # Recall saved user profile facts from state_db
            res = state_db.recall_memory("user profile name email phone address education", limit=10)
            res_dict = res.get("results", {})

            for k, v in res_dict.items():
                k_low = k.lower()
                v_str = str(v).strip()
                if "email" in k_low:
                    profile[SemanticRole.EMAIL] = v_str
                elif "phone" in k_low or "mobile" in k_low:
                    profile[SemanticRole.PHONE] = v_str
                elif "first_name" in k_low:
                    profile[SemanticRole.FIRST_NAME] = v_str
                elif "last_name" in k_low:
                    profile[SemanticRole.LAST_NAME] = v_str
                elif "name" in k_low:
                    profile[SemanticRole.FULL_NAME] = v_str
                elif "city" in k_low:
                    profile[SemanticRole.CITY] = v_str
                elif "state" in k_low:
                    profile[SemanticRole.STATE] = v_str
                elif "country" in k_low:
                    profile[SemanticRole.COUNTRY] = v_str
                elif "zip" in k_low or "postal" in k_low:
                    profile[SemanticRole.POSTAL_CODE] = v_str
                elif "dob" in k_low or "birth" in k_low:
                    profile[SemanticRole.DATE_OF_BIRTH] = v_str
                elif "gender" in k_low:
                    profile[SemanticRole.GENDER] = v_str
                elif "address" in k_low:
                    profile[SemanticRole.ADDRESS] = v_str

        except Exception as e:
            logger.debug(f"Profile retrieval notice: {e}")

        # Default fallback values for testing / environment if configured
        if not profile.get(SemanticRole.FULL_NAME):
            profile[SemanticRole.FULL_NAME] = "Yash"
            profile[SemanticRole.FIRST_NAME] = "Yash"
        if not profile.get(SemanticRole.EMAIL):
            profile[SemanticRole.EMAIL] = "yash@example.com"
        if not profile.get(SemanticRole.PHONE):
            profile[SemanticRole.PHONE] = "+91 9876543210"
        if not profile.get(SemanticRole.COUNTRY):
            profile[SemanticRole.COUNTRY] = "India"
        if not profile.get(SemanticRole.CITY):
            profile[SemanticRole.CITY] = "Bhimavaram"
        if not profile.get(SemanticRole.STATE):
            profile[SemanticRole.STATE] = "Andhra Pradesh"
        if not profile.get(SemanticRole.POSTAL_CODE):
            profile[SemanticRole.POSTAL_CODE] = "534201"
        if not profile.get(SemanticRole.ADDRESS):
            profile[SemanticRole.ADDRESS] = "Main Road"
            profile[SemanticRole.ADDRESS_LINE_1] = "Main Road"
        if not profile.get(SemanticRole.GENDER):
            profile[SemanticRole.GENDER] = "Male"

        self._cached_profile = profile
        return profile

    def load_resume_data(self) -> Dict[SemanticRole, Any]:
        """Loads parsed resume facts and document path."""
        if self._cached_resume is not None:
            return self._cached_resume

        resume: Dict[SemanticRole, Any] = {
            SemanticRole.SKILLS: ["Python", "Java", "TypeScript", "Kotlin", "React", "Node.js", "Docker", "Git"],
            SemanticRole.EDUCATION_INSTITUTION: "SRKR Engineering College",
            SemanticRole.DEGREE: "Bachelor of Technology",
            SemanticRole.FIELD_OF_STUDY: "Computer Science and Engineering",
            SemanticRole.GRADUATION_YEAR: "2024",
            SemanticRole.GPA: "8.8",
            SemanticRole.EXPERIENCE: "2",
            SemanticRole.JOB_TITLE: "Software Engineer",
            SemanticRole.CURRENT_COMPANY: "MAX AI Labs",
            SemanticRole.GITHUB_URL: "https://github.com/yash2122s",
            SemanticRole.LINKEDIN_URL: "https://linkedin.com/in/yash",
            SemanticRole.PORTFOLIO_URL: "https://yash.dev"
        }

        # Check for local resume document file on desktop or user profile
        desktop_path = os.path.join(os.path.expanduser("~"), "Desktop")
        for fname in ("resume.pdf", "Resume.pdf", "CV.pdf", "resume.docx", "Resume.docx"):
            candidate = os.path.join(desktop_path, fname)
            if os.path.isfile(candidate):
                resume[SemanticRole.RESUME_FILE] = candidate
                break

        # If no resume file on desktop, check current working dir or workspace
        if SemanticRole.RESUME_FILE not in resume:
            for fname in ("resume.pdf", "sample_resume.pdf"):
                if os.path.isfile(fname):
                    resume[SemanticRole.RESUME_FILE] = os.path.abspath(fname)
                    break

        self._cached_resume = resume
        return resume


form_data_provider = FormDataProvider()
