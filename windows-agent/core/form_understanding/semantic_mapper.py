"""
MAX AI — Deterministic Semantic Field Mapper
Maps diverse natural language field labels, placeholders, aria attributes,
and surrounding text to canonical SemanticRole enums with confidence scoring.
"""

import re
import logging
from typing import Tuple, Optional, Dict, List, Any

from core.form_understanding.models import SemanticRole, FormFieldType

logger = logging.getLogger("MAX.FormUnderstanding.SemanticMapper")


# Exact / High-Confidence Canonical Mappings
EXACT_SYNONYMS: Dict[str, SemanticRole] = {
    # First Name
    "first name": SemanticRole.FIRST_NAME,
    "firstname": SemanticRole.FIRST_NAME,
    "first_name": SemanticRole.FIRST_NAME,
    "first-name": SemanticRole.FIRST_NAME,
    "given name": SemanticRole.FIRST_NAME,
    "given_name": SemanticRole.FIRST_NAME,
    "forename": SemanticRole.FIRST_NAME,
    "fname": SemanticRole.FIRST_NAME,
    "applicant first name": SemanticRole.FIRST_NAME,
    "candidate first name": SemanticRole.FIRST_NAME,
    "first": SemanticRole.FIRST_NAME,
    
    # Last Name
    "last name": SemanticRole.LAST_NAME,
    "lastname": SemanticRole.LAST_NAME,
    "last_name": SemanticRole.LAST_NAME,
    "last-name": SemanticRole.LAST_NAME,
    "surname": SemanticRole.LAST_NAME,
    "family name": SemanticRole.LAST_NAME,
    "family_name": SemanticRole.LAST_NAME,
    "lname": SemanticRole.LAST_NAME,
    "applicant last name": SemanticRole.LAST_NAME,
    "candidate last name": SemanticRole.LAST_NAME,
    "last": SemanticRole.LAST_NAME,
    
    # Full Name
    "full name": SemanticRole.FULL_NAME,
    "fullname": SemanticRole.FULL_NAME,
    "full_name": SemanticRole.FULL_NAME,
    "your name": SemanticRole.FULL_NAME,
    "candidate name": SemanticRole.FULL_NAME,
    "applicant name": SemanticRole.FULL_NAME,
    "complete name": SemanticRole.FULL_NAME,
    "name": SemanticRole.FULL_NAME,
    "display name": SemanticRole.FULL_NAME,
    
    # Middle Name
    "middle name": SemanticRole.MIDDLE_NAME,
    "middlename": SemanticRole.MIDDLE_NAME,
    "mname": SemanticRole.MIDDLE_NAME,
    "middle initial": SemanticRole.MIDDLE_NAME,
    
    # Email
    "email": SemanticRole.EMAIL,
    "email address": SemanticRole.EMAIL,
    "e-mail": SemanticRole.EMAIL,
    "e-mail address": SemanticRole.EMAIL,
    "email id": SemanticRole.EMAIL,
    "emailid": SemanticRole.EMAIL,
    "mail": SemanticRole.EMAIL,
    "contact email": SemanticRole.EMAIL,
    "work email": SemanticRole.EMAIL,
    "personal email": SemanticRole.EMAIL,
    
    # Phone
    "phone": SemanticRole.PHONE,
    "phone number": SemanticRole.PHONE,
    "phonenumber": SemanticRole.PHONE,
    "mobile": SemanticRole.PHONE,
    "mobile number": SemanticRole.PHONE,
    "mobilenumber": SemanticRole.PHONE,
    "contact number": SemanticRole.PHONE,
    "contact no": SemanticRole.PHONE,
    "cell": SemanticRole.PHONE,
    "cell phone": SemanticRole.PHONE,
    "cellphone": SemanticRole.PHONE,
    "telephone": SemanticRole.PHONE,
    "tel": SemanticRole.PHONE,
    "whatsapp number": SemanticRole.PHONE,
    
    # Alternate Phone
    "alternate phone": SemanticRole.ALTERNATE_PHONE,
    "secondary phone": SemanticRole.ALTERNATE_PHONE,
    "alt phone": SemanticRole.ALTERNATE_PHONE,
    "home phone": SemanticRole.ALTERNATE_PHONE,
    "work phone": SemanticRole.ALTERNATE_PHONE,
    
    # Date of Birth
    "date of birth": SemanticRole.DATE_OF_BIRTH,
    "dob": SemanticRole.DATE_OF_BIRTH,
    "birth date": SemanticRole.DATE_OF_BIRTH,
    "birthdate": SemanticRole.DATE_OF_BIRTH,
    "birthday": SemanticRole.DATE_OF_BIRTH,
    "born on": SemanticRole.DATE_OF_BIRTH,
    "birth": SemanticRole.DATE_OF_BIRTH,
    
    # Gender
    "gender": SemanticRole.GENDER,
    "sex": SemanticRole.GENDER,
    
    # Address
    "address": SemanticRole.ADDRESS,
    "street address": SemanticRole.ADDRESS,
    "address line 1": SemanticRole.ADDRESS_LINE_1,
    "address line 2": SemanticRole.ADDRESS_LINE_2,
    "address 1": SemanticRole.ADDRESS_LINE_1,
    "address 2": SemanticRole.ADDRESS_LINE_2,
    "street": SemanticRole.ADDRESS,
    "permanent address": SemanticRole.ADDRESS,
    "residential address": SemanticRole.ADDRESS,
    "current address": SemanticRole.ADDRESS,
    "apartment": SemanticRole.ADDRESS_LINE_2,
    "apt": SemanticRole.ADDRESS_LINE_2,
    "suite": SemanticRole.ADDRESS_LINE_2,
    "unit": SemanticRole.ADDRESS_LINE_2,
    "building": SemanticRole.ADDRESS_LINE_2,
    
    # City / State / Zip / Country
    "city": SemanticRole.CITY,
    "town": SemanticRole.CITY,
    "district": SemanticRole.CITY,
    "state": SemanticRole.STATE,
    "province": SemanticRole.STATE,
    "region": SemanticRole.STATE,
    "state/province": SemanticRole.STATE,
    "zip": SemanticRole.POSTAL_CODE,
    "zip code": SemanticRole.POSTAL_CODE,
    "zipcode": SemanticRole.POSTAL_CODE,
    "postal code": SemanticRole.POSTAL_CODE,
    "postalcode": SemanticRole.POSTAL_CODE,
    "pin": SemanticRole.POSTAL_CODE,
    "pin code": SemanticRole.POSTAL_CODE,
    "pincode": SemanticRole.POSTAL_CODE,
    "postcode": SemanticRole.POSTAL_CODE,
    "country": SemanticRole.COUNTRY,
    "nation": SemanticRole.COUNTRY,
    "country/region": SemanticRole.COUNTRY,
    "nationality": SemanticRole.NATIONALITY,
    
    # Education
    "college": SemanticRole.EDUCATION_INSTITUTION,
    "university": SemanticRole.EDUCATION_INSTITUTION,
    "institution": SemanticRole.EDUCATION_INSTITUTION,
    "school": SemanticRole.EDUCATION_INSTITUTION,
    "institute": SemanticRole.EDUCATION_INSTITUTION,
    "education": SemanticRole.EDUCATION_INSTITUTION,
    "alma mater": SemanticRole.EDUCATION_INSTITUTION,
    "degree": SemanticRole.DEGREE,
    "qualification": SemanticRole.DEGREE,
    "highest qualification": SemanticRole.DEGREE,
    "major": SemanticRole.FIELD_OF_STUDY,
    "field of study": SemanticRole.FIELD_OF_STUDY,
    "branch": SemanticRole.FIELD_OF_STUDY,
    "course": SemanticRole.FIELD_OF_STUDY,
    "specialization": SemanticRole.FIELD_OF_STUDY,
    "graduation year": SemanticRole.GRADUATION_YEAR,
    "passing year": SemanticRole.GRADUATION_YEAR,
    "year of passing": SemanticRole.GRADUATION_YEAR,
    "passout year": SemanticRole.GRADUATION_YEAR,
    "completion year": SemanticRole.GRADUATION_YEAR,
    "yop": SemanticRole.GRADUATION_YEAR,
    "gpa": SemanticRole.GPA,
    "cgpa": SemanticRole.GPA,
    "percentage": SemanticRole.GPA,
    "marks": SemanticRole.GPA,
    "grade": SemanticRole.GPA,
    
    # Skills & Experience
    "skills": SemanticRole.SKILLS,
    "technical skills": SemanticRole.SKILLS,
    "core skills": SemanticRole.SKILLS,
    "key skills": SemanticRole.SKILLS,
    "technologies": SemanticRole.SKILLS,
    "programming languages": SemanticRole.SKILLS,
    "experience": SemanticRole.EXPERIENCE,
    "years of experience": SemanticRole.EXPERIENCE,
    "total experience": SemanticRole.EXPERIENCE,
    "work experience": SemanticRole.EXPERIENCE,
    "relevant experience": SemanticRole.EXPERIENCE,
    "company": SemanticRole.CURRENT_COMPANY,
    "current company": SemanticRole.CURRENT_COMPANY,
    "current employer": SemanticRole.CURRENT_COMPANY,
    "organization": SemanticRole.CURRENT_COMPANY,
    "employer": SemanticRole.CURRENT_COMPANY,
    "job title": SemanticRole.JOB_TITLE,
    "designation": SemanticRole.JOB_TITLE,
    "role": SemanticRole.JOB_TITLE,
    "position": SemanticRole.JOB_TITLE,
    "current role": SemanticRole.JOB_TITLE,
    "notice period": SemanticRole.NOTICE_PERIOD,
    "current ctc": SemanticRole.CURRENT_CTC,
    "expected ctc": SemanticRole.EXPECTED_CTC,
    "current salary": SemanticRole.CURRENT_CTC,
    "expected salary": SemanticRole.EXPECTED_CTC,
    
    # Resume / Files
    "resume": SemanticRole.RESUME_FILE,
    "cv": SemanticRole.RESUME_FILE,
    "upload cv": SemanticRole.RESUME_FILE,
    "upload resume": SemanticRole.RESUME_FILE,
    "attach resume": SemanticRole.RESUME_FILE,
    "attach cv": SemanticRole.RESUME_FILE,
    "curriculum vitae": SemanticRole.RESUME_FILE,
    "cover letter": SemanticRole.COVER_LETTER,
    "upload cover letter": SemanticRole.COVER_LETTER,
    "photo": SemanticRole.PHOTO_FILE,
    "photograph": SemanticRole.PHOTO_FILE,
    "upload photo": SemanticRole.PHOTO_FILE,
    "certificate": SemanticRole.CERTIFICATE_FILE,
    
    # Links & Portfolio
    "portfolio": SemanticRole.PORTFOLIO_URL,
    "portfolio url": SemanticRole.PORTFOLIO_URL,
    "website": SemanticRole.WEBSITE_URL,
    "website url": SemanticRole.WEBSITE_URL,
    "personal website": SemanticRole.WEBSITE_URL,
    "linkedin": SemanticRole.LINKEDIN_URL,
    "linkedin profile": SemanticRole.LINKEDIN_URL,
    "linkedin url": SemanticRole.LINKEDIN_URL,
    "github": SemanticRole.GITHUB_URL,
    "github profile": SemanticRole.GITHUB_URL,
    "github url": SemanticRole.GITHUB_URL,
    
    # Auth & Security
    "username": SemanticRole.USERNAME,
    "user name": SemanticRole.USERNAME,
    "login": SemanticRole.USERNAME,
    "password": SemanticRole.PASSWORD,
    "pass": SemanticRole.PASSWORD,
    "enter password": SemanticRole.PASSWORD,
    "confirm password": SemanticRole.CONFIRM_PASSWORD,
    "re-enter password": SemanticRole.CONFIRM_PASSWORD,
    "verify password": SemanticRole.CONFIRM_PASSWORD,
    "otp": SemanticRole.OTP,
    "one time password": SemanticRole.OTP,
    "verification code": SemanticRole.OTP,
    
    # Actions & Agreements
    "search": SemanticRole.SEARCH_QUERY,
    "search query": SemanticRole.SEARCH_QUERY,
    "find": SemanticRole.SEARCH_QUERY,
    "i agree to terms": SemanticRole.TERMS_AGREEMENT,
    "terms and conditions": SemanticRole.TERMS_AGREEMENT,
    "terms of service": SemanticRole.TERMS_AGREEMENT,
    "accept terms": SemanticRole.TERMS_AGREEMENT,
    "i accept": SemanticRole.TERMS_AGREEMENT,
    "remember me": SemanticRole.REMEMBER_ME,
    "subscribe to newsletter": SemanticRole.NEWSLETTER,
    "newsletter": SemanticRole.NEWSLETTER,
    "submit": SemanticRole.SUBMIT_BUTTON,
    "apply": SemanticRole.SUBMIT_BUTTON,
    "apply now": SemanticRole.SUBMIT_BUTTON,
    "register": SemanticRole.SUBMIT_BUTTON,
    "sign up": SemanticRole.SUBMIT_BUTTON,
    "log in": SemanticRole.SUBMIT_BUTTON,
    "login button": SemanticRole.SUBMIT_BUTTON,
    "save": SemanticRole.SUBMIT_BUTTON,
    "send": SemanticRole.SUBMIT_BUTTON,
    "next": SemanticRole.NEXT_STEP_BUTTON,
    "continue": SemanticRole.NEXT_STEP_BUTTON,
    "proceed": SemanticRole.NEXT_STEP_BUTTON,
    "previous": SemanticRole.PREV_STEP_BUTTON,
    "back": SemanticRole.PREV_STEP_BUTTON,
    "cancel": SemanticRole.CANCEL_BUTTON,
}

# Regex Pattern Rules for Fallback Matching
REGEX_PATTERNS: List[Tuple[re.Pattern, SemanticRole, float]] = [
    # First Name
    (re.compile(r"\b(first|given|fore)\s*name\b", re.I), SemanticRole.FIRST_NAME, 0.98),
    (re.compile(r"\bapplicant\s*first\b", re.I), SemanticRole.FIRST_NAME, 0.95),
    (re.compile(r"\bfname\b", re.I), SemanticRole.FIRST_NAME, 0.95),
    
    # Last Name
    (re.compile(r"\b(last|sur|family)\s*name\b", re.I), SemanticRole.LAST_NAME, 0.98),
    (re.compile(r"\bapplicant\s*last\b", re.I), SemanticRole.LAST_NAME, 0.95),
    (re.compile(r"\blname\b", re.I), SemanticRole.LAST_NAME, 0.95),
    
    # Email
    (re.compile(r"\be-?mail(\s*id|\s*address)?\b", re.I), SemanticRole.EMAIL, 0.99),
    
    # Phone / Mobile
    (re.compile(r"\b(phone|mobile|cell|telephone)(\s*no|\s*number)?\b", re.I), SemanticRole.PHONE, 0.98),
    (re.compile(r"\bcontact\s*(number|no)\b", re.I), SemanticRole.PHONE, 0.96),
    (re.compile(r"\bwhatsapp\s*(number|no)\b", re.I), SemanticRole.PHONE, 0.95),
    
    # DOB
    (re.compile(r"\b(date\s*of\s*birth|dob|birth\s*date|birthday)\b", re.I), SemanticRole.DATE_OF_BIRTH, 0.99),
    
    # Gender
    (re.compile(r"\b(gender|sex)\b", re.I), SemanticRole.GENDER, 0.98),
    
    # Address
    (re.compile(r"\b(street\s*address|address\s*line\s*1|residential\s*address|perm\s*address)\b", re.I), SemanticRole.ADDRESS_LINE_1, 0.95),
    (re.compile(r"\b(address\s*line\s*2|apt|suite|unit|flat\s*no)\b", re.I), SemanticRole.ADDRESS_LINE_2, 0.95),
    (re.compile(r"\baddress\b", re.I), SemanticRole.ADDRESS, 0.92),
    
    # City / State / Zip / Country
    (re.compile(r"\b(city|town|municipality)\b", re.I), SemanticRole.CITY, 0.95),
    (re.compile(r"\b(state|province|region)\b", re.I), SemanticRole.STATE, 0.95),
    (re.compile(r"\b(postal\s*code|zip\s*code|pincode|pin\s*code|postcode|zip|pin)\b", re.I), SemanticRole.POSTAL_CODE, 0.98),
    (re.compile(r"\b(country|nation)\b", re.I), SemanticRole.COUNTRY, 0.95),
    
    # Education
    (re.compile(r"\b(college|university|institute|institution|school)\s*(name)?\b", re.I), SemanticRole.EDUCATION_INSTITUTION, 0.95),
    (re.compile(r"\b(degree|qualification|highest\s*degree)\b", re.I), SemanticRole.DEGREE, 0.95),
    (re.compile(r"\b(field\s*of\s*study|major|branch|stream|course)\b", re.I), SemanticRole.FIELD_OF_STUDY, 0.92),
    (re.compile(r"\b(graduat(ion|ed)|passing|passout|completion)\s*year\b", re.I), SemanticRole.GRADUATION_YEAR, 0.95),
    (re.compile(r"\b(gpa|cgpa|percentage|marks|score)\b", re.I), SemanticRole.GPA, 0.95),
    
    # Skills / Experience
    (re.compile(r"\b(technical\s*skills|core\s*skills|key\s*skills|skills|technologies)\b", re.I), SemanticRole.SKILLS, 0.98),
    (re.compile(r"\b(years\s*of\s*experience|total\s*experience|work\s*experience|experience)\b", re.I), SemanticRole.EXPERIENCE, 0.95),
    (re.compile(r"\b(current\s*company|current\s*employer|organization|company\s*name)\b", re.I), SemanticRole.CURRENT_COMPANY, 0.95),
    (re.compile(r"\b(job\s*title|designation|current\s*role|position)\b", re.I), SemanticRole.JOB_TITLE, 0.95),
    
    # Resume / Files
    (re.compile(r"\b(upload\s*resume|upload\s*cv|attach\s*resume|attach\s*cv|resume|cv)\b", re.I), SemanticRole.RESUME_FILE, 0.98),
    (re.compile(r"\b(cover\s*letter|upload\s*cover\s*letter)\b", re.I), SemanticRole.COVER_LETTER, 0.95),
    (re.compile(r"\b(photo|photograph|profile\s*picture|avatar)\b", re.I), SemanticRole.PHOTO_FILE, 0.95),
    
    # Links
    (re.compile(r"\blinkedin(\s*profile|\s*url)?\b", re.I), SemanticRole.LINKEDIN_URL, 0.98),
    (re.compile(r"\bgithub(\s*profile|\s*url)?\b", re.I), SemanticRole.GITHUB_URL, 0.98),
    (re.compile(r"\b(portfolio|personal\s*website)(\s*url)?\b", re.I), SemanticRole.PORTFOLIO_URL, 0.95),
    
    # Auth
    (re.compile(r"\b(confirm|re-?enter|verify)\s*password\b", re.I), SemanticRole.CONFIRM_PASSWORD, 0.98),
    (re.compile(r"\b(password|passcode|secret\s*key)\b", re.I), SemanticRole.PASSWORD, 0.98),
    (re.compile(r"\b(otp|one\s*time\s*password|verification\s*code)\b", re.I), SemanticRole.OTP, 0.98),
    (re.compile(r"\b(username|user\s*id|login\s*id)\b", re.I), SemanticRole.USERNAME, 0.95),
    
    # Buttons & Controls
    (re.compile(r"\b(submit|apply\s*now|register|sign\s*up|send\s*application)\b", re.I), SemanticRole.SUBMIT_BUTTON, 0.95),
    (re.compile(r"\b(next|continue|proceed|step\s*2|next\s*step)\b", re.I), SemanticRole.NEXT_STEP_BUTTON, 0.95),
    (re.compile(r"\b(previous|prev|back)\b", re.I), SemanticRole.PREV_STEP_BUTTON, 0.95),
    (re.compile(r"\b(i\s*agree|terms\s*and\s*conditions|agree\s*to\s*terms)\b", re.I), SemanticRole.TERMS_AGREEMENT, 0.95),
]


class SemanticFieldMapper:
    """
    Deterministic mapper from unstructured UI text & labels to structured SemanticRole.
    """

    @classmethod
    def clean_text(cls, text: str) -> str:
        """Normalizes text by removing punctuation, extra whitespace, and trailing colons / asterisks."""
        if not text:
            return ""
        # Remove asterisks and colons commonly attached to labels
        cleaned = re.sub(r"[\*\:\(\)\[\]\{\}\#\?]", " ", text)
        cleaned = re.sub(r"\s+", " ", cleaned).strip().lower()
        return cleaned

    @classmethod
    def map_field(
        cls,
        label: str,
        placeholder: str = "",
        nearby_text: str = "",
        field_type: FormFieldType = FormFieldType.UNKNOWN,
        control_type: str = "",
        group_context: str = ""
    ) -> Tuple[SemanticRole, float, str]:
        """
        Determines the semantic role and confidence score for a field given all available textual cues.
        Returns: (SemanticRole, confidence, explanation)
        """
        norm_label = cls.clean_text(label)
        norm_placeholder = cls.clean_text(placeholder)
        norm_nearby = cls.clean_text(nearby_text)
        norm_group = cls.clean_text(group_context)

        # 1. Exact Match on clean label
        if norm_label in EXACT_SYNONYMS:
            role = EXACT_SYNONYMS[norm_label]
            # Disambiguate "name" vs "first name" if in a group
            if role == SemanticRole.FULL_NAME and "first" in norm_label:
                return SemanticRole.FIRST_NAME, 0.98, "EXACT_SYNONYM_FIRST_NAME"
            return role, 1.0, f"EXACT_SYNONYM_MATCH ('{norm_label}')"

        # 2. Exact Match on placeholder
        if norm_placeholder in EXACT_SYNONYMS:
            return EXACT_SYNONYMS[norm_placeholder], 0.95, f"EXACT_PLACEHOLDER_MATCH ('{norm_placeholder}')"

        # 3. Disambiguate single tokens based on group context (e.g. "First" under "Name" group)
        if norm_label == "first" or "first" in norm_label.split():
            return SemanticRole.FIRST_NAME, 0.95, "TOKEN_FIRST_NAME"
        if norm_label == "last" or "last" in norm_label.split() or "surname" in norm_label.split():
            return SemanticRole.LAST_NAME, 0.95, "TOKEN_LAST_NAME"
        if norm_label == "middle" or "middle" in norm_label.split():
            return SemanticRole.MIDDLE_NAME, 0.95, "TOKEN_MIDDLE_NAME"

        # 4. Regex Pattern Matching across label, placeholder, and nearby text
        candidates: List[Tuple[SemanticRole, float, str]] = []
        combined_text = f"{norm_label} {norm_placeholder} {norm_nearby}".strip()

        for pattern, role, conf in REGEX_PATTERNS:
            if pattern.search(norm_label):
                candidates.append((role, conf, f"LABEL_REGEX_MATCH ({pattern.pattern})"))
            elif pattern.search(norm_placeholder):
                candidates.append((role, conf * 0.92, f"PLACEHOLDER_REGEX_MATCH ({pattern.pattern})"))
            elif norm_nearby and pattern.search(norm_nearby):
                candidates.append((role, conf * 0.85, f"NEARBY_TEXT_REGEX_MATCH ({pattern.pattern})"))

        if candidates:
            # Pick candidate with highest confidence
            candidates.sort(key=lambda c: c[1], reverse=True)
            return candidates[0]

        # 5. Type-Specific Fallbacks
        if field_type == FormFieldType.EMAIL or "email" in control_type.lower():
            return SemanticRole.EMAIL, 0.85, "FIELD_TYPE_EMAIL_FALLBACK"
        if field_type == FormFieldType.PHONE or "tel" in control_type.lower():
            return SemanticRole.PHONE, 0.85, "FIELD_TYPE_PHONE_FALLBACK"
        if field_type == FormFieldType.PASSWORD or "password" in control_type.lower():
            return SemanticRole.PASSWORD, 0.90, "FIELD_TYPE_PASSWORD_FALLBACK"
        if field_type == FormFieldType.SEARCH:
            return SemanticRole.SEARCH_QUERY, 0.85, "FIELD_TYPE_SEARCH_FALLBACK"
        if field_type == FormFieldType.DATE:
            return SemanticRole.DATE_OF_BIRTH, 0.70, "FIELD_TYPE_DATE_FALLBACK"

        # 6. Group Context Matching
        if norm_group:
            for pattern, role, conf in REGEX_PATTERNS:
                if pattern.search(norm_group):
                    return role, conf * 0.75, f"GROUP_CONTEXT_MATCH ({norm_group})"

        return SemanticRole.UNKNOWN, 0.0, "NO_SEMANTIC_MATCH"


semantic_mapper = SemanticFieldMapper()
