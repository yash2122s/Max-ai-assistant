"""
MAX AI — Universal Multimodal File Processor
Comprehensive multimodal document and media processing subsystem.
Ported and natively architected for MAX Windows Agent from Mark-LIII feature libraries.

Supports:
  - PDF: text extraction, AI/statistical summary, page slicing, PDF->DOCX conversion
  - DOCX: reading, text extraction, structured reformatting, document generation
  - Excel/CSV: reading, statistical breakdown, column filtering, sorting, multi-format conversion
  - PPTX: slide & shape text extraction, structured presentation summary
  - Images: OCR, resizing, quality compression, format conversion, metadata extraction
  - Audio/Video: transcription, lossless/re-encoded trimming, audio/frame extraction, FFmpeg compression
  - Archives: secure listing, zip-slip protected extraction, archive creation (.zip/.tar.gz)
  - Universal Dispatcher: automated file type detection and intelligent action execution

Integrates natively with MAX SecurityGuard, ActionVerifier, and UndoManager.
"""

import os
import sys
import json
import shutil
import logging
import tempfile
import subprocess
from pathlib import Path
from typing import Tuple, Optional, Dict, Any, List, Union

from core.security import security_guard
from core.verifier import action_verifier
from core.undo_manager import undo_manager
from tools.file_manager import _resolve_user_path, SPECIAL_FOLDERS

logger = logging.getLogger("MAXWindowsAgent.UniversalFileTool")


# ─── Helper Functions & Gemini Client Integration ───────────────────────────

def _get_gemini_client():
    """Returns a client for Gemini AI generation with fallback handling."""
    api_key = os.getenv("GEMINI_API_KEY", "")
    if not api_key:
        return None

    try:
        from google import genai
        client = genai.Client(api_key=api_key)

        class _GeminiWrapper:
            def generate_content(self, contents):
                for model_name in ("gemini-2.5-flash", "gemini-1.5-flash", "gemini-1.5-flash-8b"):
                    try:
                        return client.models.generate_content(model=model_name, contents=contents)
                    except Exception as me:
                        logger.debug(f"Model {model_name} failed: {me}")
                raise RuntimeError("All Gemini models failed to generate content.")

        return _GeminiWrapper()
    except Exception as e:
        logger.warning(f"Could not initialize google.genai: {e}")
        return None


def _detect_type(path: Path) -> str:
    """Detects canonical file type from extension."""
    ext = path.suffix.lower().lstrip(".")
    image_exts = {"jpg", "jpeg", "png", "gif", "webp", "bmp", "tiff", "svg", "ico"}
    video_exts = {"mp4", "avi", "mov", "mkv", "wmv", "flv", "webm", "m4v", "3gp"}
    audio_exts = {"mp3", "wav", "ogg", "m4a", "aac", "flac", "wma", "opus"}
    code_exts = {
        "py", "js", "ts", "jsx", "tsx", "html", "css", "java", "c",
        "cpp", "cs", "go", "rs", "rb", "php", "swift", "kt", "sh",
        "bash", "ps1", "lua", "r", "sql", "yaml", "yml", "toml"
    }
    archive_exts = {"zip", "rar", "tar", "gz", "7z", "bz2", "xz", "tgz"}

    if ext in image_exts:
        return "image"
    if ext in video_exts:
        return "video"
    if ext in audio_exts:
        return "audio"
    if ext in code_exts:
        return "code"
    if ext in archive_exts:
        return "archive"
    if ext == "pdf":
        return "pdf"
    if ext in ("docx", "doc"):
        return "docx"
    if ext in ("txt", "md", "rst", "log", "ini", "cfg"):
        return "text"
    if ext in ("csv", "tsv"):
        return "csv"
    if ext in ("xlsx", "xls", "ods"):
        return "excel"
    if ext == "json":
        return "json"
    if ext == "xml":
        return "xml"
    if ext in ("pptx", "ppt"):
        return "pptx"
    return "unknown"


def _format_size(size_bytes: int) -> str:
    if size_bytes < 1024:
        return f"{size_bytes} B"
    if size_bytes < 1024 ** 2:
        return f"{size_bytes / 1024:.1f} KB"
    if size_bytes < 1024 ** 3:
        return f"{size_bytes / (1024 ** 2):.1f} MB"
    return f"{size_bytes / (1024 ** 3):.1f} GB"


def _output_path(src: Path, suffix: str, new_ext: str = None) -> Path:
    """Computes a structured output path in the same parent directory."""
    ext = new_ext if new_ext else src.suffix
    if not ext.startswith("."):
        ext = "." + ext
    name = f"{src.stem}_{suffix}{ext}"
    return src.parent / name


def _register_created_file_undo(file_path: str, action_desc: str):
    """Registers a self-validating undo closure for a newly generated output file."""
    abs_p = os.path.abspath(file_path)
    def _rollback_created_file(p=abs_p):
        if not os.path.exists(p):
            return False, f"File '{os.path.basename(p)}' no longer exists."
        if not os.path.isfile(p):
            return False, f"Path '{os.path.basename(p)}' is not a file."
        try:
            os.remove(p)
            return True, f"Removed generated file '{os.path.basename(p)}'."
        except Exception as ex:
            return False, f"Could not remove file: {ex}"

    undo_manager.push_undo(
        description=action_desc,
        undo_fn=_rollback_created_file,
        metadata={"type": "created_derivative_file", "path": abs_p}
    )


# ─── 1. PDF Processor ────────────────────────────────────────────────────────

def extract_pdf_text(file_path: str, max_pages: Optional[int] = None, output_path: Optional[str] = None, **kwargs) -> dict:
    """
    Extracts text content from a PDF document.
    Optionally saves the extracted text to a .txt file.
    """
    try:
        resolved = _resolve_user_path(file_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": f"Access denied: PDF '{resolved}' is outside sandbox."}
        if not os.path.exists(resolved) or not os.path.isfile(resolved):
            return {"status": "error", "message": f"PDF file not found: {resolved}"}

        import pypdf
        reader = pypdf.PdfReader(resolved)
        total_pages = len(reader.pages)
        limit = min(total_pages, max_pages) if max_pages else total_pages

        pages_text = []
        for idx in range(limit):
            page_text = reader.pages[idx].extract_text() or ""
            pages_text.append(f"--- Page {idx + 1} ---\n{page_text.strip()}")

        full_text = "\n\n".join(pages_text)
        result: Dict[str, Any] = {
            "status": "success",
            "file_path": resolved,
            "total_pages": total_pages,
            "pages_extracted": limit,
            "char_count": len(full_text),
            "extracted_text": full_text[:4000],
        }

        save_target = output_path or kwargs.get("save_path")
        if save_target or kwargs.get("save", False):
            out_p = Path(_resolve_user_path(save_target)) if save_target else _output_path(Path(resolved), "extracted", ".txt")
            if not security_guard.is_safe_path(str(out_p)):
                return {"status": "error", "message": f"Access denied: Save path '{out_p}' is outside sandbox."}
            out_p.write_text(full_text, encoding="utf-8")
            _register_created_file_undo(str(out_p), f"Extract PDF text from '{os.path.basename(resolved)}'")
            result["output_file"] = str(out_p)
            result["message"] = f"Extracted {limit} pages from '{os.path.basename(resolved)}' -> saved to '{out_p.name}'."

        return result
    except Exception as e:
        logger.error(f"Error extracting PDF text: {e}")
        return {"status": "error", "message": f"Failed to extract PDF text: {e}"}


def summarize_pdf(file_path: str, max_pages: int = 10, instruction: str = "", **kwargs) -> dict:
    """
    Summarizes a PDF document using Gemini AI or structured statistical extraction.
    """
    try:
        extract_res = extract_pdf_text(file_path, max_pages=max_pages)
        if extract_res.get("status") != "success":
            return extract_res

        content = extract_res.get("extracted_text", "")
        if not content.strip():
            return {"status": "error", "message": "PDF appears empty or contains only non-extractable raster scans."}

        client = _get_gemini_client()
        if client:
            prompt = f"{instruction or 'Summarize this PDF document concisely with key findings and bullet points'}:\n\n{content[:30000]}"
            try:
                response = client.generate_content(prompt)
                summary_text = response.text.strip()
                return {
                    "status": "success",
                    "file_path": extract_res["file_path"],
                    "total_pages": extract_res["total_pages"],
                    "summary": summary_text,
                    "model": "gemini",
                }
            except Exception as ai_e:
                logger.warning(f"AI summarization fallback triggered: {ai_e}")

        lines = [line.strip() for line in content.split("\n") if line.strip() and not line.startswith("--- Page")]
        headings = [line for line in lines if len(line) < 80 and (line.isupper() or line.endswith(":"))]
        summary_preview = "\n".join(lines[:10])
        return {
            "status": "success",
            "file_path": extract_res["file_path"],
            "total_pages": extract_res["total_pages"],
            "summary": f"Document Summary ({extract_res['total_pages']} pages):\n{summary_preview}\n...",
            "key_sections": headings[:5],
            "model": "local_extractor"
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to summarize PDF: {e}"}


def extract_pdf_pages(file_path: str, start_page: int = 1, end_page: Optional[int] = None, output_path: Optional[str] = None, **kwargs) -> dict:
    """
    Extracts a page range from a PDF into a new PDF document.
    """
    try:
        resolved = _resolve_user_path(file_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": "Access denied: Path outside sandbox."}
        if not os.path.exists(resolved):
            return {"status": "error", "message": f"PDF not found: {resolved}"}

        import pypdf
        reader = pypdf.PdfReader(resolved)
        total = len(reader.pages)
        start = max(1, int(start_page))
        end = min(total, int(end_page)) if end_page else total

        if start > total or start > end:
            return {"status": "error", "message": f"Invalid page range: {start} to {end} (Total pages: {total})"}

        writer = pypdf.PdfWriter()
        for p_idx in range(start - 1, end):
            writer.add_page(reader.pages[p_idx])

        out_p = Path(_resolve_user_path(output_path)) if output_path else _output_path(Path(resolved), f"p{start}-p{end}", ".pdf")
        if not security_guard.is_safe_path(str(out_p)):
            return {"status": "error", "message": f"Access denied: Output path '{out_p}' is outside sandbox."}

        with open(out_p, "wb") as f_out:
            writer.write(f_out)

        _register_created_file_undo(str(out_p), f"Extract pages {start}-{end} from '{os.path.basename(resolved)}'")

        return {
            "status": "success",
            "source_file": resolved,
            "output_file": str(out_p),
            "pages_extracted": end - start + 1,
            "start_page": start,
            "end_page": end,
            "message": f"Extracted pages {start} to {end} -> '{out_p.name}'"
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to extract PDF pages: {e}"}


def pdf_to_docx(file_path: str, output_path: Optional[str] = None, **kwargs) -> dict:
    """
    Converts a PDF document into an editable Microsoft Word (.docx) file.
    """
    try:
        resolved = _resolve_user_path(file_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": "Access denied: Path outside sandbox."}
        if not os.path.exists(resolved):
            return {"status": "error", "message": f"PDF not found: {resolved}"}

        import pypdf
        from docx import Document

        reader = pypdf.PdfReader(resolved)
        doc = Document()
        doc.add_heading(Path(resolved).stem, level=0)

        total_extracted = 0
        for page_idx, page in enumerate(reader.pages, 1):
            text = (page.extract_text() or "").strip()
            if text:
                doc.add_heading(f"Page {page_idx}", level=2)
                for paragraph in text.split("\n\n"):
                    cleaned = paragraph.strip()
                    if cleaned:
                        doc.add_paragraph(cleaned)
                        total_extracted += 1

        out_p = Path(_resolve_user_path(output_path)) if output_path else _output_path(Path(resolved), "converted", ".docx")
        if not security_guard.is_safe_path(str(out_p)):
            return {"status": "error", "message": "Access denied: Output path outside sandbox."}

        doc.save(str(out_p))
        _register_created_file_undo(str(out_p), f"Convert PDF '{os.path.basename(resolved)}' to Word")

        return {
            "status": "success",
            "source_file": resolved,
            "output_file": str(out_p),
            "total_pages": len(reader.pages),
            "paragraphs_created": total_extracted,
            "message": f"Converted PDF to Word document: '{out_p.name}'"
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to convert PDF to DOCX: {e}"}


# ─── 2. DOCX Processor ───────────────────────────────────────────────────────

def read_docx(file_path: str, **kwargs) -> dict:
    """
    Reads paragraphs, headings, and tables from a Microsoft Word .docx file.
    """
    try:
        resolved = _resolve_user_path(file_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": "Access denied: Path outside sandbox."}
        if not os.path.exists(resolved):
            return {"status": "error", "message": f"DOCX file not found: {resolved}"}

        from docx import Document
        doc = Document(resolved)

        paragraphs = [p.text for p in doc.paragraphs if p.text.strip()]
        tables_data = []
        for table in doc.tables:
            t_rows = []
            for row in table.rows:
                t_rows.append([cell.text.strip() for cell in row.cells])
            tables_data.append(t_rows)

        full_text = "\n".join(paragraphs)
        return {
            "status": "success",
            "file_path": resolved,
            "paragraph_count": len(paragraphs),
            "table_count": len(tables_data),
            "content": full_text[:4000],
            "char_count": len(full_text),
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to read DOCX: {e}"}


def extract_docx_text(file_path: str, output_path: Optional[str] = None, **kwargs) -> dict:
    """
    Extracts text from a DOCX file and saves it to a .txt file.
    """
    try:
        res = read_docx(file_path)
        if res.get("status") != "success":
            return res

        src_p = Path(res["file_path"])
        out_p = Path(_resolve_user_path(output_path)) if output_path else _output_path(src_p, "extracted", ".txt")
        if not security_guard.is_safe_path(str(out_p)):
            return {"status": "error", "message": "Access denied: Output path outside sandbox."}

        out_p.write_text(res.get("content", ""), encoding="utf-8")
        _register_created_file_undo(str(out_p), f"Extract text from '{src_p.name}'")

        return {
            "status": "success",
            "source_file": str(src_p),
            "output_file": str(out_p),
            "message": f"Extracted text saved to '{out_p.name}'"
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to extract DOCX text: {e}"}


def reformat_docx(file_path: str, output_path: Optional[str] = None, title: Optional[str] = None, style_guide: Optional[str] = None, **kwargs) -> dict:
    """
    Cleans, reformats, and applies standard heading and paragraph structure to a DOCX document.
    """
    try:
        read_res = read_docx(file_path)
        if read_res.get("status") != "success":
            return read_res

        from docx import Document
        src_p = Path(read_res["file_path"])
        doc = Document()

        doc_title = title or src_p.stem.replace("_", " ").title()
        doc.add_heading(doc_title, level=0)

        raw_paragraphs = read_res.get("content", "").split("\n")
        cleaned_count = 0
        for p in raw_paragraphs:
            text = p.strip()
            if not text:
                continue
            if len(text) < 60 and (text.isupper() or text.endswith(":")):
                doc.add_heading(text.rstrip(":"), level=1)
            else:
                doc.add_paragraph(text)
            cleaned_count += 1

        out_p = Path(_resolve_user_path(output_path)) if output_path else _output_path(src_p, "reformatted", ".docx")
        if not security_guard.is_safe_path(str(out_p)):
            return {"status": "error", "message": "Access denied: Output path outside sandbox."}

        doc.save(str(out_p))
        _register_created_file_undo(str(out_p), f"Reformat Word document '{src_p.name}'")

        return {
            "status": "success",
            "source_file": str(src_p),
            "output_file": str(out_p),
            "paragraphs_formatted": cleaned_count,
            "message": f"Reformatted DOCX saved to '{out_p.name}'"
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to reformat DOCX: {e}"}


def write_docx(file_path: str, title: str, paragraphs: List[str], **kwargs) -> dict:
    """
    Creates or overwrites a formatted Word (.docx) document.
    """
    try:
        resolved = _resolve_user_path(file_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": "Access denied: Path outside sandbox."}

        from docx import Document
        os.makedirs(os.path.dirname(resolved), exist_ok=True)
        doc = Document()
        doc.add_heading(title, level=0)
        for p in paragraphs:
            doc.add_paragraph(str(p))

        doc.save(resolved)
        _register_created_file_undo(resolved, f"Create Word document '{os.path.basename(resolved)}'")

        return {
            "status": "success",
            "file_path": resolved,
            "paragraphs": len(paragraphs),
            "message": f"Document '{os.path.basename(resolved)}' created successfully."
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to write DOCX: {e}"}


# ─── 3. Excel & CSV Processor ────────────────────────────────────────────────

def _load_dataframe(file_path: str, sheet_name: Optional[str] = None):
    """Loads a CSV or Excel file into a pandas DataFrame."""
    import pandas as pd
    resolved = _resolve_user_path(file_path)
    if not security_guard.is_safe_path(resolved):
        raise PermissionError(f"Access denied: Path '{resolved}' outside sandbox.")
    if not os.path.exists(resolved):
        raise FileNotFoundError(f"File not found: {resolved}")

    ext = Path(resolved).suffix.lower()
    if ext in (".csv", ".tsv"):
        sep = "\t" if ext == ".tsv" else ","
        df = pd.read_csv(resolved, sep=sep, encoding="utf-8", encoding_errors="replace")
    elif ext in (".xlsx", ".xls", ".ods"):
        df = pd.read_excel(resolved, sheet_name=sheet_name or 0)
    else:
        raise ValueError(f"Unsupported spreadsheet format: {ext}")
    return df, resolved


def read_tabular(file_path: str, sheet_name: Optional[str] = None, max_rows: int = 50, **kwargs) -> dict:
    """
    Reads a CSV or Excel spreadsheet and returns headers, row counts, and data preview.
    """
    try:
        df, resolved = _load_dataframe(file_path, sheet_name)
        preview_rows = df.head(min(max_rows, 100)).fillna("").to_dict(orient="records")
        return {
            "status": "success",
            "file_path": resolved,
            "total_rows": len(df),
            "total_columns": len(df.columns),
            "columns": list(df.columns.astype(str)),
            "preview_rows": preview_rows,
            "preview_count": len(preview_rows),
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to read tabular data: {e}"}


def get_tabular_statistics(file_path: str, sheet_name: Optional[str] = None, **kwargs) -> dict:
    """
    Computes summary statistics, null counts, and column distributions for a dataset.
    """
    try:
        df, resolved = _load_dataframe(file_path, sheet_name)
        desc = df.describe(include="all").fillna("").to_dict()
        null_counts = df.isnull().sum().to_dict()
        return {
            "status": "success",
            "file_path": resolved,
            "row_count": len(df),
            "column_count": len(df.columns),
            "null_counts": {str(k): int(v) for k, v in null_counts.items()},
            "statistics": {str(k): v for k, v in desc.items()},
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to compute statistics: {e}"}


def filter_tabular(file_path: str, column: str, value: Any, condition: str = "equals", output_path: Optional[str] = None, **kwargs) -> dict:
    """
    Filters rows in a spreadsheet by condition (equals, contains, gt, lt, gte, lte, not_equals) and saves the result.
    """
    try:
        df, resolved = _load_dataframe(file_path)
        if column not in df.columns:
            return {"status": "error", "message": f"Column '{column}' not found. Available: {list(df.columns)}"}

        val_str = str(value)
        cond = condition.lower().strip()
        if cond == "equals":
            filtered = df[df[column].astype(str) == val_str]
        elif cond == "contains":
            filtered = df[df[column].astype(str).str.contains(val_str, case=False, na=False)]
        elif cond in ("gt", ">"):
            filtered = df[df[column] > float(value)]
        elif cond in ("lt", "<"):
            filtered = df[df[column] < float(value)]
        elif cond in ("gte", ">="):
            filtered = df[df[column] >= float(value)]
        elif cond in ("lte", "<="):
            filtered = df[df[column] <= float(value)]
        elif cond in ("not_equals", "!="):
            filtered = df[df[column].astype(str) != val_str]
        else:
            filtered = df[df[column].astype(str) == val_str]

        src_p = Path(resolved)
        out_p = Path(_resolve_user_path(output_path)) if output_path else _output_path(src_p, f"filtered_{column}")
        if not security_guard.is_safe_path(str(out_p)):
            return {"status": "error", "message": "Access denied: Output path outside sandbox."}

        if out_p.suffix.lower() == ".xlsx":
            filtered.to_excel(str(out_p), index=False)
        else:
            filtered.to_csv(str(out_p), index=False, encoding="utf-8")

        _register_created_file_undo(str(out_p), f"Filter '{src_p.name}' on {column}={value}")

        return {
            "status": "success",
            "source_file": resolved,
            "output_file": str(out_p),
            "matched_rows": len(filtered),
            "total_rows": len(df),
            "message": f"Filtered {len(filtered)} / {len(df)} rows -> '{out_p.name}'"
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to filter tabular data: {e}"}


def sort_tabular(file_path: str, column: str, ascending: bool = True, output_path: Optional[str] = None, **kwargs) -> dict:
    """
    Sorts a spreadsheet by one or more columns and saves to a new file.
    """
    try:
        df, resolved = _load_dataframe(file_path)
        if column not in df.columns:
            return {"status": "error", "message": f"Column '{column}' not found. Available: {list(df.columns)}"}

        asc = bool(ascending) if isinstance(ascending, bool) else str(ascending).lower() in ("true", "1")
        sorted_df = df.sort_values(by=column, ascending=asc)

        src_p = Path(resolved)
        out_p = Path(_resolve_user_path(output_path)) if output_path else _output_path(src_p, f"sorted_{column}")
        if not security_guard.is_safe_path(str(out_p)):
            return {"status": "error", "message": "Access denied: Output path outside sandbox."}

        if out_p.suffix.lower() == ".xlsx":
            sorted_df.to_excel(str(out_p), index=False)
        else:
            sorted_df.to_csv(str(out_p), index=False, encoding="utf-8")

        _register_created_file_undo(str(out_p), f"Sort '{src_p.name}' by {column}")

        return {
            "status": "success",
            "source_file": resolved,
            "output_file": str(out_p),
            "sorted_by": column,
            "ascending": asc,
            "row_count": len(sorted_df),
            "message": f"Sorted dataset by '{column}' ({'ASC' if asc else 'DESC'}) -> '{out_p.name}'"
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to sort tabular data: {e}"}


def convert_tabular(file_path: str, target_format: str = "csv", output_path: Optional[str] = None, **kwargs) -> dict:
    """
    Converts between tabular formats: CSV, XLSX, JSON.
    """
    try:
        df, resolved = _load_dataframe(file_path)
        fmt = target_format.lower().strip(".").strip()

        src_p = Path(resolved)
        out_p = Path(_resolve_user_path(output_path)) if output_path else _output_path(src_p, "converted", f".{fmt}")
        if not security_guard.is_safe_path(str(out_p)):
            return {"status": "error", "message": "Access denied: Output path outside sandbox."}

        if fmt in ("csv", "tsv"):
            sep = "\t" if fmt == "tsv" else ","
            df.to_csv(str(out_p), sep=sep, index=False, encoding="utf-8")
        elif fmt in ("xlsx", "xls"):
            df.to_excel(str(out_p), index=False)
        elif fmt == "json":
            df.to_json(str(out_p), orient="records", indent=2, force_ascii=False)
        else:
            return {"status": "error", "message": f"Unsupported target format: {fmt}. Options: csv, xlsx, json, tsv"}

        _register_created_file_undo(str(out_p), f"Convert '{src_p.name}' to {fmt.upper()}")

        return {
            "status": "success",
            "source_file": resolved,
            "output_file": str(out_p),
            "format": fmt,
            "rows": len(df),
            "message": f"Converted dataset to {fmt.upper()} -> '{out_p.name}'"
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to convert tabular data: {e}"}


# ─── 4. PPTX Processor ───────────────────────────────────────────────────────

def extract_pptx_text(file_path: str, output_path: Optional[str] = None, **kwargs) -> dict:
    """
    Extracts text, slide titles, and notes from a PowerPoint presentation.
    """
    try:
        resolved = _resolve_user_path(file_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": "Access denied: Path outside sandbox."}
        if not os.path.exists(resolved):
            return {"status": "error", "message": f"PPTX not found: {resolved}"}

        from pptx import Presentation
        prs = Presentation(resolved)

        slides_data = []
        for s_idx, slide in enumerate(prs.slides, 1):
            slide_lines = []
            for shape in slide.shapes:
                if shape.has_text_frame:
                    for para in shape.text_frame.paragraphs:
                        t = para.text.strip()
                        if t:
                            slide_lines.append(t)
            slides_data.append({
                "slide_number": s_idx,
                "text": "\n".join(slide_lines),
                "line_count": len(slide_lines)
            })

        formatted_text = "\n\n".join(f"--- Slide {s['slide_number']} ---\n{s['text']}" for s in slides_data)

        res: Dict[str, Any] = {
            "status": "success",
            "file_path": resolved,
            "total_slides": len(prs.slides),
            "extracted_text": formatted_text[:4000],
            "slides": slides_data
        }

        save_target = output_path or kwargs.get("save_path")
        if save_target or kwargs.get("save", False):
            out_p = Path(_resolve_user_path(save_target)) if save_target else _output_path(Path(resolved), "extracted", ".txt")
            if not security_guard.is_safe_path(str(out_p)):
                return {"status": "error", "message": "Access denied: Output path outside sandbox."}
            out_p.write_text(formatted_text, encoding="utf-8")
            _register_created_file_undo(str(out_p), f"Extract text from PPTX '{Path(resolved).name}'")
            res["output_file"] = str(out_p)
            res["message"] = f"Extracted text from {len(prs.slides)} slides -> '{out_p.name}'"

        return res
    except Exception as e:
        return {"status": "error", "message": f"Failed to extract PPTX text: {e}"}


def summarize_pptx(file_path: str, instruction: str = "", **kwargs) -> dict:
    """
    Summarizes a PowerPoint presentation.
    """
    try:
        extract_res = extract_pptx_text(file_path)
        if extract_res.get("status") != "success":
            return extract_res

        content = extract_res.get("extracted_text", "")
        if not content.strip():
            return {"status": "error", "message": "Presentation contains no readable text."}

        client = _get_gemini_client()
        if client:
            prompt = f"{instruction or 'Summarize this PowerPoint presentation by slide themes and key points'}:\n\n{content[:30000]}"
            try:
                resp = client.generate_content(prompt)
                return {
                    "status": "success",
                    "file_path": extract_res["file_path"],
                    "total_slides": extract_res["total_slides"],
                    "summary": resp.text.strip(),
                    "model": "gemini"
                }
            except Exception as ai_e:
                logger.warning(f"AI PPTX summary fallback: {ai_e}")

        summary_lines = []
        for s in extract_res.get("slides", []):
            lines = [l for l in s["text"].split("\n") if l.strip()]
            title = lines[0] if lines else "(No Title)"
            summary_lines.append(f"Slide {s['slide_number']}: {title}")

        return {
            "status": "success",
            "file_path": extract_res["file_path"],
            "total_slides": extract_res["total_slides"],
            "summary": "Presentation Outline:\n" + "\n".join(summary_lines),
            "model": "local_extractor"
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to summarize PPTX: {e}"}


# ─── 5. Image Processor ──────────────────────────────────────────────────────

def get_image_info(file_path: str, **kwargs) -> dict:
    """
    Retrieves dimensions, color format, mode, and file size of an image.
    """
    try:
        resolved = _resolve_user_path(file_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": "Access denied: Path outside sandbox."}
        if not os.path.exists(resolved):
            return {"status": "error", "message": f"Image not found: {resolved}"}

        from PIL import Image
        with Image.open(resolved) as img:
            w, h = img.size
            fmt = img.format
            mode = img.mode

        sz = os.path.getsize(resolved)
        return {
            "status": "success",
            "file_path": resolved,
            "width": w,
            "height": h,
            "format": fmt,
            "mode": mode,
            "size_bytes": sz,
            "size_formatted": _format_size(sz),
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to get image info: {e}"}


def image_ocr(file_path: str, lang: str = "eng", **kwargs) -> dict:
    """
    Extracts text from an image using local pytesseract or Gemini Vision OCR.
    """
    try:
        resolved = _resolve_user_path(file_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": "Access denied: Path outside sandbox."}
        if not os.path.exists(resolved):
            return {"status": "error", "message": f"Image not found: {resolved}"}

        from PIL import Image
        img = Image.open(resolved)

        # 1. Try local pytesseract if available
        try:
            import pytesseract
            ocr_text = pytesseract.image_to_string(img, lang=lang).strip()
            if ocr_text:
                return {
                    "status": "success",
                    "file_path": resolved,
                    "extracted_text": ocr_text,
                    "engine": "pytesseract",
                    "char_count": len(ocr_text)
                }
        except Exception as ocr_err:
            logger.debug(f"Pytesseract not available or failed: {ocr_err}")

        # 2. Try Gemini Vision client
        client = _get_gemini_client()
        if client:
            try:
                response = client.generate_content(["Extract and transcribe all text visible in this image verbatim.", img])
                text = response.text.strip()
                return {
                    "status": "success",
                    "file_path": resolved,
                    "extracted_text": text,
                    "engine": "gemini_vision",
                    "char_count": len(text)
                }
            except Exception as ai_e:
                logger.warning(f"Gemini vision OCR error: {ai_e}")

        return {
            "status": "error",
            "message": "OCR engine not available. Please install Tesseract OCR or configure GEMINI_API_KEY."
        }
    except Exception as e:
        return {"status": "error", "message": f"Image OCR failed: {e}"}


def resize_image(file_path: str, width: Optional[int] = None, height: Optional[int] = None, scale: Optional[float] = None, output_path: Optional[str] = None, **kwargs) -> dict:
    """
    Resizes an image by explicit dimensions (width/height) or scaling factor.
    """
    try:
        resolved = _resolve_user_path(file_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": "Access denied: Path outside sandbox."}
        if not os.path.exists(resolved):
            return {"status": "error", "message": f"Image not found: {resolved}"}

        from PIL import Image
        with Image.open(resolved) as img:
            orig_w, orig_h = img.size

            if scale:
                new_w, new_h = int(orig_w * float(scale)), int(orig_h * float(scale))
            elif width and height:
                new_w, new_h = int(width), int(height)
            elif width:
                new_w = int(width)
                new_h = int(orig_h * new_w / orig_w)
            elif height:
                new_h = int(height)
                new_w = int(orig_w * new_h / orig_h)
            else:
                return {"status": "error", "message": "Specify width, height, or scale factor."}

            resized = img.resize((new_w, new_h), Image.Resampling.LANCZOS)
            src_p = Path(resolved)
            out_p = Path(_resolve_user_path(output_path)) if output_path else _output_path(src_p, f"resized_{new_w}x{new_h}")
            if not security_guard.is_safe_path(str(out_p)):
                return {"status": "error", "message": "Access denied: Output path outside sandbox."}

            resized.save(str(out_p))

        _register_created_file_undo(str(out_p), f"Resize image '{src_p.name}' to {new_w}x{new_h}")

        return {
            "status": "success",
            "source_file": resolved,
            "output_file": str(out_p),
            "original_dimensions": [orig_w, orig_h],
            "new_dimensions": [new_w, new_h],
            "message": f"Resized image from {orig_w}x{orig_h} to {new_w}x{new_h} -> '{out_p.name}'"
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to resize image: {e}"}


def compress_image(file_path: str, quality: int = 70, output_path: Optional[str] = None, **kwargs) -> dict:
    """
    Compresses an image to reduce file size while preserving visual quality.
    """
    try:
        resolved = _resolve_user_path(file_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": "Access denied: Path outside sandbox."}
        if not os.path.exists(resolved):
            return {"status": "error", "message": f"Image not found: {resolved}"}

        from PIL import Image
        q = max(1, min(100, int(quality)))
        src_p = Path(resolved)
        out_p = Path(_resolve_user_path(output_path)) if output_path else _output_path(src_p, f"compressed_q{q}", ".jpg")
        if not security_guard.is_safe_path(str(out_p)):
            return {"status": "error", "message": "Access denied: Output path outside sandbox."}

        with Image.open(resolved) as img:
            rgb_img = img.convert("RGB")
            rgb_img.save(str(out_p), "JPEG", quality=q, optimize=True)

        orig_sz = os.path.getsize(resolved)
        new_sz = os.path.getsize(str(out_p))
        saved_pct = round((1.0 - (new_sz / max(1, orig_sz))) * 100, 1)

        _register_created_file_undo(str(out_p), f"Compress image '{src_p.name}' (quality {q})")

        return {
            "status": "success",
            "source_file": resolved,
            "output_file": str(out_p),
            "original_size": _format_size(orig_sz),
            "compressed_size": _format_size(new_sz),
            "reduction_percent": saved_pct,
            "message": f"Compressed image: {_format_size(orig_sz)} -> {_format_size(new_sz)} ({saved_pct}% reduction)"
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to compress image: {e}"}


def convert_image(file_path: str, target_format: str = "png", output_path: Optional[str] = None, **kwargs) -> dict:
    """
    Converts image formats between PNG, JPEG, WEBP, BMP, TIFF, ICO.
    """
    try:
        resolved = _resolve_user_path(file_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": "Access denied: Path outside sandbox."}
        if not os.path.exists(resolved):
            return {"status": "error", "message": f"Image not found: {resolved}"}

        from PIL import Image
        fmt = target_format.lower().lstrip(".").strip()
        pil_fmt_map = {
            "jpg": "JPEG", "jpeg": "JPEG", "png": "PNG",
            "webp": "WEBP", "bmp": "BMP", "tiff": "TIFF", "ico": "ICO"
        }
        pil_fmt = pil_fmt_map.get(fmt, fmt.upper())

        src_p = Path(resolved)
        out_p = Path(_resolve_user_path(output_path)) if output_path else _output_path(src_p, "converted", f".{fmt}")
        if not security_guard.is_safe_path(str(out_p)):
            return {"status": "error", "message": "Access denied: Output path outside sandbox."}

        with Image.open(resolved) as img:
            if fmt in ("jpg", "jpeg") and img.mode in ("RGBA", "P"):
                img = img.convert("RGB")
            img.save(str(out_p), pil_fmt)

        _register_created_file_undo(str(out_p), f"Convert image '{src_p.name}' to {fmt.upper()}")

        return {
            "status": "success",
            "source_file": resolved,
            "output_file": str(out_p),
            "target_format": fmt.upper(),
            "message": f"Converted image to {fmt.upper()} -> '{out_p.name}'"
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to convert image: {e}"}


# ─── 6. Audio & Video Processor ──────────────────────────────────────────────

def _is_ffmpeg_available() -> bool:
    """Checks if FFmpeg binary is installed and reachable."""
    return shutil.which("ffmpeg") is not None


def get_media_info(file_path: str, **kwargs) -> dict:
    """
    Retrieves duration, channels, bitrate, resolution, and codecs for audio/video media.
    """
    try:
        resolved = _resolve_user_path(file_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": "Access denied: Path outside sandbox."}
        if not os.path.exists(resolved):
            return {"status": "error", "message": f"Media file not found: {resolved}"}

        sz = os.path.getsize(resolved)
        res_info = {
            "status": "success",
            "file_path": resolved,
            "size_bytes": sz,
            "size_formatted": _format_size(sz),
        }

        # Try ffprobe if available
        if shutil.which("ffprobe"):
            try:
                cmd = ["ffprobe", "-v", "quiet", "-print_format", "json", "-show_format", "-show_streams", resolved]
                probe_res = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
                if probe_res.returncode == 0 and probe_res.stdout.strip():
                    data = json.loads(probe_res.stdout.strip())
                    fmt = data.get("format", {})
                    res_info["duration_sec"] = float(fmt.get("duration", 0))
                    res_info["bit_rate"] = fmt.get("bit_rate")
                    res_info["streams_count"] = len(data.get("streams", []))

                    video_stream = next((s for s in data.get("streams", []) if s.get("codec_type") == "video"), None)
                    if video_stream:
                        res_info["video_codec"] = video_stream.get("codec_name")
                        res_info["width"] = video_stream.get("width")
                        res_info["height"] = video_stream.get("height")
                        res_info["fps"] = video_stream.get("r_frame_rate")

                    audio_stream = next((s for s in data.get("streams", []) if s.get("codec_type") == "audio"), None)
                    if audio_stream:
                        res_info["audio_codec"] = audio_stream.get("codec_name")
                        res_info["channels"] = audio_stream.get("channels")
                        res_info["sample_rate"] = audio_stream.get("sample_rate")

                    return res_info
            except Exception as probe_e:
                logger.debug(f"ffprobe parse error: {probe_e}")

        # Fallback to pydub for audio
        try:
            from pydub import AudioSegment
            audio = AudioSegment.from_file(resolved)
            res_info["duration_sec"] = len(audio) / 1000.0
            res_info["channels"] = audio.channels
            res_info["sample_rate"] = audio.frame_rate
            return res_info
        except Exception:
            pass

        return res_info
    except Exception as e:
        return {"status": "error", "message": f"Failed to get media info: {e}"}


def trim_audio(file_path: str, start_sec: float = 0.0, end_sec: Optional[float] = None, output_path: Optional[str] = None, **kwargs) -> dict:
    """
    Trims an audio file to the specified start and end timestamps.
    """
    try:
        resolved = _resolve_user_path(file_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": "Access denied: Path outside sandbox."}
        if not os.path.exists(resolved):
            return {"status": "error", "message": f"Audio file not found: {resolved}"}

        src_p = Path(resolved)
        st = float(start_sec or 0.0)
        en = float(end_sec) if end_sec is not None else None

        out_p = Path(_resolve_user_path(output_path)) if output_path else _output_path(src_p, f"trim_{int(st)}s_{int(en or 0)}s")
        if not security_guard.is_safe_path(str(out_p)):
            return {"status": "error", "message": "Access denied: Output path outside sandbox."}

        # Try pydub
        try:
            from pydub import AudioSegment
            audio = AudioSegment.from_file(resolved)
            end_ms = int(en * 1000) if en else len(audio)
            trimmed = audio[int(st * 1000):end_ms]
            trimmed.export(str(out_p), format=src_p.suffix.lstrip("."))
            _register_created_file_undo(str(out_p), f"Trim audio '{src_p.name}'")
            return {
                "status": "success",
                "source_file": resolved,
                "output_file": str(out_p),
                "duration_sec": len(trimmed) / 1000.0,
                "message": f"Trimmed audio ({st}s -> {en or 'end'}s) -> '{out_p.name}'"
            }
        except Exception as pydub_err:
            logger.debug(f"Pydub trim fallback: {pydub_err}")

        # Fallback to ffmpeg
        if _is_ffmpeg_available():
            cmd = ["ffmpeg", "-i", resolved, "-ss", str(st)]
            if en is not None:
                cmd += ["-to", str(en)]
            cmd += ["-c", "copy", str(out_p), "-y"]
            subprocess.run(cmd, capture_output=True, timeout=120)
            _register_created_file_undo(str(out_p), f"Trim audio '{src_p.name}'")
            return {
                "status": "success",
                "source_file": resolved,
                "output_file": str(out_p),
                "message": f"Trimmed audio using FFmpeg -> '{out_p.name}'"
            }

        return {"status": "error", "message": "Neither pydub nor ffmpeg is available for audio trimming."}
    except Exception as e:
        return {"status": "error", "message": f"Failed to trim audio: {e}"}


def trim_video(file_path: str, start_time: str = "00:00:00", end_time: Optional[str] = None, output_path: Optional[str] = None, **kwargs) -> dict:
    """
    Trims a video file using FFmpeg.
    """
    try:
        resolved = _resolve_user_path(file_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": "Access denied: Path outside sandbox."}
        if not os.path.exists(resolved):
            return {"status": "error", "message": f"Video file not found: {resolved}"}
        if not _is_ffmpeg_available():
            return {"status": "error", "message": "FFmpeg binary is required for video trimming."}

        src_p = Path(resolved)
        out_p = Path(_resolve_user_path(output_path)) if output_path else _output_path(src_p, "trimmed")
        if not security_guard.is_safe_path(str(out_p)):
            return {"status": "error", "message": "Access denied: Output path outside sandbox."}

        cmd = ["ffmpeg", "-i", resolved, "-ss", str(start_time)]
        if end_time:
            cmd += ["-to", str(end_time)]
        cmd += ["-c", "copy", str(out_p), "-y"]

        res = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
        if res.returncode != 0:
            return {"status": "error", "message": f"FFmpeg failed: {res.stderr[:300]}"}

        _register_created_file_undo(str(out_p), f"Trim video '{src_p.name}'")
        return {
            "status": "success",
            "source_file": resolved,
            "output_file": str(out_p),
            "message": f"Trimmed video ({start_time} to {end_time or 'end'}) -> '{out_p.name}'"
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to trim video: {e}"}


def extract_video_audio(file_path: str, target_format: str = "mp3", output_path: Optional[str] = None, **kwargs) -> dict:
    """
    Extracts the audio track from a video file into an MP3 or WAV file.
    """
    try:
        resolved = _resolve_user_path(file_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": "Access denied: Path outside sandbox."}
        if not os.path.exists(resolved):
            return {"status": "error", "message": f"Video not found: {resolved}"}
        if not _is_ffmpeg_available():
            return {"status": "error", "message": "FFmpeg is required to extract audio from video."}

        fmt = target_format.lower().lstrip(".")
        src_p = Path(resolved)
        out_p = Path(_resolve_user_path(output_path)) if output_path else _output_path(src_p, "audio", f".{fmt}")
        if not security_guard.is_safe_path(str(out_p)):
            return {"status": "error", "message": "Access denied: Output path outside sandbox."}

        cmd = ["ffmpeg", "-i", resolved, "-q:a", "0", "-map", "a", str(out_p), "-y"]
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
        if res.returncode != 0:
            return {"status": "error", "message": f"FFmpeg error: {res.stderr[:300]}"}

        _register_created_file_undo(str(out_p), f"Extract audio from '{src_p.name}'")
        return {
            "status": "success",
            "source_file": resolved,
            "output_file": str(out_p),
            "format": fmt,
            "message": f"Audio track extracted successfully -> '{out_p.name}'"
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to extract audio: {e}"}


def extract_video_frame(file_path: str, timestamp: str = "00:00:01", output_path: Optional[str] = None, **kwargs) -> dict:
    """
    Extracts a high-resolution still frame from a video at a specified timestamp.
    """
    try:
        resolved = _resolve_user_path(file_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": "Access denied: Path outside sandbox."}
        if not os.path.exists(resolved):
            return {"status": "error", "message": f"Video not found: {resolved}"}
        if not _is_ffmpeg_available():
            return {"status": "error", "message": "FFmpeg is required to extract video frames."}

        ts_clean = str(timestamp).replace(":", "")
        src_p = Path(resolved)
        out_p = Path(_resolve_user_path(output_path)) if output_path else _output_path(src_p, f"frame_{ts_clean}", ".jpg")
        if not security_guard.is_safe_path(str(out_p)):
            return {"status": "error", "message": "Access denied: Output path outside sandbox."}

        cmd = ["ffmpeg", "-i", resolved, "-ss", str(timestamp), "-vframes", "1", str(out_p), "-y"]
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        if res.returncode != 0:
            return {"status": "error", "message": f"FFmpeg error: {res.stderr[:300]}"}

        _register_created_file_undo(str(out_p), f"Extract frame at {timestamp} from '{src_p.name}'")
        return {
            "status": "success",
            "source_file": resolved,
            "output_file": str(out_p),
            "timestamp": timestamp,
            "message": f"Extracted frame at {timestamp} -> '{out_p.name}'"
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to extract frame: {e}"}


def compress_video(file_path: str, crf: int = 28, output_path: Optional[str] = None, **kwargs) -> dict:
    """
    Compresses video bitrate and size using H.264 CRF encoding.
    """
    try:
        resolved = _resolve_user_path(file_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": "Access denied: Path outside sandbox."}
        if not os.path.exists(resolved):
            return {"status": "error", "message": f"Video not found: {resolved}"}
        if not _is_ffmpeg_available():
            return {"status": "error", "message": "FFmpeg is required to compress videos."}

        crf_val = max(18, min(40, int(crf)))
        src_p = Path(resolved)
        out_p = Path(_resolve_user_path(output_path)) if output_path else _output_path(src_p, f"compressed_crf{crf_val}", ".mp4")
        if not security_guard.is_safe_path(str(out_p)):
            return {"status": "error", "message": "Access denied: Output path outside sandbox."}

        cmd = [
            "ffmpeg", "-i", resolved,
            "-c:v", "libx264", "-crf", str(crf_val),
            "-preset", "medium", "-c:a", "copy",
            str(out_p), "-y"
        ]
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
        if res.returncode != 0:
            return {"status": "error", "message": f"FFmpeg error: {res.stderr[:300]}"}

        orig_sz = os.path.getsize(resolved)
        new_sz = os.path.getsize(str(out_p))
        saved_pct = round((1.0 - (new_sz / max(1, orig_sz))) * 100, 1)

        _register_created_file_undo(str(out_p), f"Compress video '{src_p.name}' (CRF {crf_val})")

        return {
            "status": "success",
            "source_file": resolved,
            "output_file": str(out_p),
            "original_size": _format_size(orig_sz),
            "compressed_size": _format_size(new_sz),
            "reduction_percent": saved_pct,
            "message": f"Compressed video: {_format_size(orig_sz)} -> {_format_size(new_sz)} ({saved_pct}% reduction)"
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to compress video: {e}"}


def transcribe_media(file_path: str, output_path: Optional[str] = None, **kwargs) -> dict:
    """
    Transcribes spoken dialogue in an audio or video file using Gemini multimodal speech recognition.
    """
    try:
        resolved = _resolve_user_path(file_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": "Access denied: Path outside sandbox."}
        if not os.path.exists(resolved):
            return {"status": "error", "message": f"File not found: {resolved}"}

        src_p = Path(resolved)
        media_type = _detect_type(src_p)

        temp_audio = None
        target_audio_p = resolved
        if media_type == "video":
            if not _is_ffmpeg_available():
                return {"status": "error", "message": "FFmpeg required to extract audio from video for transcription."}
            temp_audio = tempfile.mktemp(suffix=".mp3")
            cmd = ["ffmpeg", "-i", resolved, "-q:a", "0", "-map", "a", temp_audio, "-y"]
            subprocess.run(cmd, capture_output=True, timeout=120)
            target_audio_p = temp_audio

        try:
            client = _get_gemini_client()
            if not client:
                return {"status": "error", "message": "Gemini API key is required for multimodal speech transcription."}

            with open(target_audio_p, "rb") as f_aud:
                audio_bytes = f_aud.read()

            mime_map = {
                "mp3": "audio/mp3", "wav": "audio/wav", "ogg": "audio/ogg",
                "m4a": "audio/mp4", "aac": "audio/aac", "flac": "audio/flac"
            }
            mime = mime_map.get(Path(target_audio_p).suffix.lstrip(".").lower(), "audio/mpeg")

            response = client.generate_content([
                "Transcribe all speech and dialogue in this audio accurately. Format clearly with punctuation.",
                {"mime_type": mime, "data": audio_bytes}
            ])
            transcript = response.text.strip()

            res: Dict[str, Any] = {
                "status": "success",
                "source_file": resolved,
                "transcript": transcript,
                "char_count": len(transcript)
            }

            save_target = output_path or kwargs.get("save_path")
            if save_target or kwargs.get("save", True):
                out_p = Path(_resolve_user_path(save_target)) if save_target else _output_path(src_p, "transcript", ".txt")
                if security_guard.is_safe_path(str(out_p)):
                    out_p.write_text(transcript, encoding="utf-8")
                    _register_created_file_undo(str(out_p), f"Transcribe '{src_p.name}'")
                    res["output_file"] = str(out_p)
                    res["message"] = f"Transcription complete -> saved to '{out_p.name}'"

            return res
        finally:
            if temp_audio and os.path.exists(temp_audio):
                try:
                    os.remove(temp_audio)
                except Exception:
                    pass
    except Exception as e:
        return {"status": "error", "message": f"Transcription failed: {e}"}


# ─── 7. Archive Processor ───────────────────────────────────────────────────

def list_archive(file_path: str, **kwargs) -> dict:
    """
    Lists the files, directories, and uncompressed sizes inside a compressed archive (.zip, .tar, .gz, .bz2, .xz).
    """
    try:
        resolved = _resolve_user_path(file_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": "Access denied: Path outside sandbox."}
        if not os.path.exists(resolved):
            return {"status": "error", "message": f"Archive not found: {resolved}"}

        import zipfile
        import tarfile

        src_p = Path(resolved)
        ext = src_p.suffix.lower()
        items = []

        if ext == ".zip":
            with zipfile.ZipFile(resolved, "r") as z:
                for info in z.infolist():
                    items.append({
                        "filename": info.filename,
                        "size_bytes": info.file_size,
                        "compressed_size_bytes": info.compress_size,
                        "is_dir": info.is_dir(),
                    })
        elif ext in (".tar", ".gz", ".bz2", ".xz", ".tgz"):
            with tarfile.open(resolved, "r:*") as t:
                for member in t.getmembers():
                    items.append({
                        "filename": member.name,
                        "size_bytes": member.size,
                        "is_dir": member.isdir(),
                    })
        else:
            return {"status": "error", "message": f"Unsupported archive format: {ext}"}

        return {
            "status": "success",
            "archive_path": resolved,
            "total_items": len(items),
            "files": items[:50],
            "message": f"Archive contains {len(items)} items."
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to list archive: {e}"}


def extract_archive(file_path: str, destination: Optional[str] = None, **kwargs) -> dict:
    """
    Safely extracts a compressed archive (.zip, .tar.gz, etc.) with zip-slip traversal protection.
    """
    try:
        resolved = _resolve_user_path(file_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": "Access denied: Archive path outside sandbox."}
        if not os.path.exists(resolved):
            return {"status": "error", "message": f"Archive not found: {resolved}"}

        src_p = Path(resolved)
        dest_p = Path(_resolve_user_path(destination)) if destination else src_p.parent / src_p.stem
        if not security_guard.is_safe_path(str(dest_p)):
            return {"status": "error", "message": "Access denied: Destination directory outside sandbox."}

        dest_p.mkdir(parents=True, exist_ok=True)
        dest_canon = dest_p.resolve()

        import zipfile
        import tarfile

        ext = src_p.suffix.lower()
        extracted_count = 0

        if ext == ".zip":
            with zipfile.ZipFile(resolved, "r") as z:
                for member in z.infolist():
                    target_member_path = (dest_canon / member.filename).resolve()
                    if not str(target_member_path).startswith(str(dest_canon)):
                        raise PermissionError(f"Potential zip-slip traversal attack detected: {member.filename}")
                    z.extract(member, str(dest_canon))
                    extracted_count += 1
        elif ext in (".tar", ".gz", ".bz2", ".xz", ".tgz"):
            with tarfile.open(resolved, "r:*") as t:
                for member in t.getmembers():
                    target_member_path = (dest_canon / member.name).resolve()
                    if not str(target_member_path).startswith(str(dest_canon)):
                        raise PermissionError(f"Potential tar-slip traversal attack detected: {member.name}")
                    t.extract(member, str(dest_canon))
                    extracted_count += 1
        else:
            shutil.unpack_archive(resolved, str(dest_canon))
            extracted_count = len(os.listdir(str(dest_canon)))

        return {
            "status": "success",
            "source_archive": resolved,
            "destination": str(dest_canon),
            "items_extracted": extracted_count,
            "message": f"Extracted {extracted_count} items to '{dest_canon.name}'"
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to extract archive: {e}"}


def create_archive(source_path: str, archive_type: str = "zip", output_path: Optional[str] = None, **kwargs) -> dict:
    """
    Creates a compressed zip or tar.gz archive from a file or folder.
    """
    try:
        resolved = _resolve_user_path(source_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": "Access denied: Source path outside sandbox."}
        if not os.path.exists(resolved):
            return {"status": "error", "message": f"Source not found: {resolved}"}

        src_p = Path(resolved)
        arch_type = archive_type.lower().strip(".").strip()
        if arch_type not in ("zip", "tar", "gztar", "bztar", "xztar"):
            arch_type = "zip"

        ext = ".zip" if arch_type == "zip" else ".tar.gz"
        out_p = Path(_resolve_user_path(output_path)) if output_path else src_p.parent / f"{src_p.stem}_archive{ext}"
        if not security_guard.is_safe_path(str(out_p)):
            return {"status": "error", "message": "Access denied: Output archive path outside sandbox."}

        base_name = str(out_p.parent / out_p.stem.replace(".tar", ""))
        root_dir = str(src_p.parent)
        base_dir = src_p.name

        created_archive = shutil.make_archive(base_name, arch_type, root_dir=root_dir, base_dir=base_dir)
        _register_created_file_undo(created_archive, f"Create archive '{os.path.basename(created_archive)}'")

        return {
            "status": "success",
            "source": resolved,
            "archive_path": created_archive,
            "size_formatted": _format_size(os.path.getsize(created_archive)),
            "message": f"Created {arch_type.upper()} archive -> '{os.path.basename(created_archive)}'"
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to create archive: {e}"}


# ─── 8. Universal Dispatcher ─────────────────────────────────────────────────

def process_file(file_path: str, action: str = "", instruction: str = "", **kwargs) -> dict:
    """
    Universal dispatcher for processing any document, image, media, or archive file.
    Automatically detects file type and routes to the appropriate capability handler.
    """
    try:
        resolved = _resolve_user_path(file_path)
        if not security_guard.is_safe_path(resolved):
            return {"status": "error", "message": f"Access denied: Path '{resolved}' outside sandbox."}
        if not os.path.exists(resolved):
            return {"status": "error", "message": f"File not found: {resolved}"}
        if not os.path.isfile(resolved):
            return {"status": "error", "message": f"Path is a directory: {resolved}"}

        path_obj = Path(resolved)
        ftype = _detect_type(path_obj)
        act = (action or "").lower().strip()

        # PDF Dispatch
        if ftype == "pdf":
            if act in ("extract", "extract_text", "read", "text"):
                return extract_pdf_text(resolved, **kwargs)
            elif act in ("summarize", "summary", "analyze"):
                return summarize_pdf(resolved, instruction=instruction, **kwargs)
            elif act in ("extract_pages", "slice", "pages"):
                return extract_pdf_pages(resolved, **kwargs)
            elif act in ("to_word", "to_docx", "convert", "word"):
                return pdf_to_docx(resolved, **kwargs)
            return summarize_pdf(resolved, instruction=instruction, **kwargs)

        # DOCX Dispatch
        if ftype == "docx":
            if act in ("extract", "extract_text", "text"):
                return extract_docx_text(resolved, **kwargs)
            elif act in ("reformat", "format", "clean", "structure"):
                return reformat_docx(resolved, **kwargs)
            return read_docx(resolved, **kwargs)

        # Excel / CSV Tabular Dispatch
        if ftype in ("csv", "excel"):
            if act in ("stats", "statistics", "describe"):
                return get_tabular_statistics(resolved, **kwargs)
            elif act in ("filter", "query"):
                return filter_tabular(resolved, column=kwargs.get("column", ""), value=kwargs.get("value", ""), condition=kwargs.get("condition", "equals"), **kwargs)
            elif act in ("sort", "order"):
                return sort_tabular(resolved, column=kwargs.get("column", ""), ascending=kwargs.get("ascending", True), **kwargs)
            elif act in ("convert", "to_csv", "to_excel", "to_json"):
                target_fmt = kwargs.get("format") or ("xlsx" if act == "to_excel" else ("json" if act == "to_json" else "csv"))
                return convert_tabular(resolved, target_format=target_fmt, **kwargs)
            return read_tabular(resolved, **kwargs)

        # PPTX Dispatch
        if ftype == "pptx":
            if act in ("extract", "extract_text", "text"):
                return extract_pptx_text(resolved, **kwargs)
            return summarize_pptx(resolved, instruction=instruction, **kwargs)

        # Image Dispatch
        if ftype == "image":
            if act in ("ocr", "read", "extract_text"):
                return image_ocr(resolved, **kwargs)
            elif act in ("resize", "scale"):
                return resize_image(resolved, width=kwargs.get("width"), height=kwargs.get("height"), scale=kwargs.get("scale"), **kwargs)
            elif act in ("compress", "optimize"):
                return compress_image(resolved, quality=kwargs.get("quality", 70), **kwargs)
            elif act in ("convert", "format"):
                return convert_image(resolved, target_format=kwargs.get("format", "png"), **kwargs)
            return get_image_info(resolved, **kwargs)

        # Audio Dispatch
        if ftype == "audio":
            if act in ("transcribe", "speech_to_text"):
                return transcribe_media(resolved, **kwargs)
            elif act in ("trim", "cut"):
                return trim_audio(resolved, start_sec=kwargs.get("start_sec", kwargs.get("start", 0)), end_sec=kwargs.get("end_sec", kwargs.get("end")), **kwargs)
            return get_media_info(resolved, **kwargs)

        # Video Dispatch
        if ftype == "video":
            if act in ("extract_audio", "to_mp3", "audio"):
                return extract_video_audio(resolved, target_format=kwargs.get("format", "mp3"), **kwargs)
            elif act in ("extract_frame", "frame", "screenshot"):
                return extract_video_frame(resolved, timestamp=kwargs.get("timestamp", "00:00:01"), **kwargs)
            elif act in ("trim", "cut"):
                return trim_video(resolved, start_time=kwargs.get("start", "00:00:00"), end_time=kwargs.get("end"), **kwargs)
            elif act in ("compress", "shrink"):
                return compress_video(resolved, crf=kwargs.get("crf", kwargs.get("quality", 28)), **kwargs)
            elif act in ("transcribe", "speech_to_text"):
                return transcribe_media(resolved, **kwargs)
            return get_media_info(resolved, **kwargs)

        # Archive Dispatch
        if ftype == "archive":
            if act in ("extract", "unzip", "unpack"):
                return extract_archive(resolved, destination=kwargs.get("destination"), **kwargs)
            return list_archive(resolved, **kwargs)

        # Plain Text / Code / JSON fallback
        try:
            content = path_obj.read_text(encoding="utf-8", errors="ignore")[:10000]
            return {
                "status": "success",
                "file_path": resolved,
                "file_type": ftype,
                "size_formatted": _format_size(len(content)),
                "content_preview": content[:2000]
            }
        except Exception as read_err:
            return {"status": "error", "message": f"Cannot process file of type {ftype}: {read_err}"}

    except Exception as e:
        logger.error(f"process_file exception: {e}", exc_info=True)
        return {"status": "error", "message": f"Universal file processing error: {e}"}


def file_processor(parameters: Optional[dict] = None, **kwargs) -> dict:
    """Compat handler matching Mark-LIII function signature."""
    params = dict(parameters) if parameters is not None else dict(kwargs)
    f_path = params.pop("file_path", "")
    act = params.pop("action", "")
    inst = params.pop("instruction", "")
    return process_file(file_path=f_path, action=act, instruction=inst, **params)


# ─── Tool Declarations ──────────────────────────────────────────────────────

TOOL_DECLARATIONS = [
    {
        "name": "process_file",
        "description": "Universal processor for documents, spreadsheets, presentations, images, audio, video, and archives.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the file to process (e.g. 'Downloads/report.pdf', 'Desktop/data.csv').",
                },
                "action": {
                    "type": "string",
                    "description": "Action to perform: 'summarize', 'extract_text', 'to_word', 'filter', 'sort', 'convert', 'ocr', 'resize', 'compress', 'trim', 'extract_audio', 'extract_frame', 'extract_archive', 'list_archive'.",
                },
                "instruction": {
                    "type": "string",
                    "description": "Optional free-form instruction for AI summarization or analysis.",
                },
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "extract_pdf_text",
        "description": "Extract text content from a PDF file with optional page limit.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the PDF file.",
                },
                "max_pages": {
                    "type": "integer",
                    "description": "Maximum number of pages to extract (default: all).",
                },
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "summarize_pdf",
        "description": "Summarize a PDF document concisely using AI or structured section extraction.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the PDF document.",
                },
                "max_pages": {
                    "type": "integer",
                    "description": "Number of pages to analyze (default: 10).",
                },
                "instruction": {
                    "type": "string",
                    "description": "Custom focus or question for the summary.",
                },
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "pdf_to_docx",
        "description": "Convert a PDF document into an editable Microsoft Word (.docx) file.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the PDF file to convert.",
                },
                "output_path": {
                    "type": "string",
                    "description": "Optional destination path for the .docx file.",
                },
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "read_docx",
        "description": "Read paragraphs, headings, and tables from a Word (.docx) file.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the .docx file.",
                }
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "reformat_docx",
        "description": "Clean, format, and apply standard headings to a Word document.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the .docx file to reformat.",
                },
                "title": {
                    "type": "string",
                    "description": "Optional title for the document header.",
                },
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "read_tabular",
        "description": "Read spreadsheet rows and column headers from CSV or Excel files.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the CSV or Excel file.",
                },
                "max_rows": {
                    "type": "integer",
                    "description": "Maximum rows to preview (default: 50).",
                },
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "get_tabular_statistics",
        "description": "Compute statistical summary (mean, min, max, nulls) for CSV or Excel data.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the CSV or Excel spreadsheet.",
                }
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "filter_tabular",
        "description": "Filter a CSV or Excel dataset by column condition and save the result.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the CSV/Excel file.",
                },
                "column": {
                    "type": "string",
                    "description": "Column name to filter on.",
                },
                "value": {
                    "description": "Value to match or compare against.",
                },
                "condition": {
                    "type": "string",
                    "description": "Condition: 'equals', 'contains', 'gt', 'lt', 'gte', 'lte', 'not_equals'.",
                },
            },
            "required": ["file_path", "column", "value"],
        },
    },
    {
        "name": "sort_tabular",
        "description": "Sort a CSV or Excel dataset by a specific column.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the CSV or Excel file.",
                },
                "column": {
                    "type": "string",
                    "description": "Column name to sort by.",
                },
                "ascending": {
                    "type": "boolean",
                    "description": "Sort order: true for ASC, false for DESC (default: true).",
                },
            },
            "required": ["file_path", "column"],
        },
    },
    {
        "name": "convert_tabular",
        "description": "Convert datasets between CSV, Excel (XLSX), and JSON formats.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Source dataset path.",
                },
                "target_format": {
                    "type": "string",
                    "description": "Target format: 'csv', 'xlsx', 'json'.",
                },
            },
            "required": ["file_path", "target_format"],
        },
    },
    {
        "name": "extract_pptx_text",
        "description": "Extract text, slide outlines, and content from a PowerPoint presentation.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the PPTX file.",
                }
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "image_ocr",
        "description": "Extract text from an image using Optical Character Recognition (OCR).",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the image file (PNG, JPG, etc.).",
                }
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "resize_image",
        "description": "Resize an image by width, height, or scale factor.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the image file.",
                },
                "width": {
                    "type": "integer",
                    "description": "Target width in pixels.",
                },
                "height": {
                    "type": "integer",
                    "description": "Target height in pixels.",
                },
                "scale": {
                    "type": "number",
                    "description": "Scale multiplier (e.g. 0.5 for 50%).",
                },
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "compress_image",
        "description": "Compress an image to reduce file size while keeping visual quality.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the image file.",
                },
                "quality": {
                    "type": "integer",
                    "description": "Compression quality 1-100 (default: 70).",
                },
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "convert_image",
        "description": "Convert an image to a different format (PNG, JPG, WEBP, BMP, ICO).",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the source image.",
                },
                "target_format": {
                    "type": "string",
                    "description": "Target format: 'png', 'jpg', 'webp', 'bmp', 'ico'.",
                },
            },
            "required": ["file_path", "target_format"],
        },
    },
    {
        "name": "trim_audio",
        "description": "Trim an audio file between start and end seconds.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the audio file.",
                },
                "start_sec": {
                    "type": "number",
                    "description": "Start time in seconds (default: 0).",
                },
                "end_sec": {
                    "type": "number",
                    "description": "End time in seconds.",
                },
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "trim_video",
        "description": "Trim a video file between start and end timestamps (HH:MM:SS).",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the video file.",
                },
                "start_time": {
                    "type": "string",
                    "description": "Start timestamp (e.g. '00:00:10').",
                },
                "end_time": {
                    "type": "string",
                    "description": "End timestamp (e.g. '00:01:30').",
                },
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "extract_video_audio",
        "description": "Extract the soundtrack from a video file into an MP3 or WAV audio file.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the video file.",
                },
                "target_format": {
                    "type": "string",
                    "description": "Audio format: 'mp3' or 'wav' (default: 'mp3').",
                },
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "extract_video_frame",
        "description": "Extract a still frame screenshot from a video at a specific timestamp.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the video file.",
                },
                "timestamp": {
                    "type": "string",
                    "description": "Timestamp in HH:MM:SS format (default: '00:00:01').",
                },
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "compress_video",
        "description": "Compress a video file to reduce file size using H.264 CRF encoding.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the video file.",
                },
                "crf": {
                    "type": "integer",
                    "description": "CRF compression factor 18-35 (default: 28, higher = smaller size).",
                },
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "transcribe_media",
        "description": "Transcribe speech and dialogue from an audio or video file.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the audio or video file.",
                }
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "list_archive",
        "description": "List all contents, files, and directories inside a compressed archive (zip/tar/gz).",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the archive file (.zip, .tar.gz, etc.).",
                }
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "extract_archive",
        "description": "Safely unpack/extract a compressed archive to a destination folder.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to the archive file.",
                },
                "destination": {
                    "type": "string",
                    "description": "Optional destination directory.",
                },
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "create_archive",
        "description": "Create a compressed zip or tar.gz archive from a file or folder.",
        "parameters": {
            "type": "object",
            "properties": {
                "source_path": {
                    "type": "string",
                    "description": "Path of the folder or file to archive.",
                },
                "archive_type": {
                    "type": "string",
                    "description": "Archive format: 'zip' or 'tar' or 'gztar' (default: 'zip').",
                },
            },
            "required": ["source_path"],
        },
    },
]

TOOL_FUNCTIONS = {
    "process_file": process_file,
    "file_processor": file_processor,
    "extract_pdf_text": extract_pdf_text,
    "summarize_pdf": summarize_pdf,
    "extract_pdf_pages": extract_pdf_pages,
    "pdf_to_docx": pdf_to_docx,
    "read_docx": read_docx,
    "extract_docx_text": extract_docx_text,
    "reformat_docx": reformat_docx,
    "write_docx": write_docx,
    "read_tabular": read_tabular,
    "get_tabular_statistics": get_tabular_statistics,
    "filter_tabular": filter_tabular,
    "sort_tabular": sort_tabular,
    "convert_tabular": convert_tabular,
    "extract_pptx_text": extract_pptx_text,
    "summarize_pptx": summarize_pptx,
    "get_image_info": get_image_info,
    "image_ocr": image_ocr,
    "resize_image": resize_image,
    "compress_image": compress_image,
    "convert_image": convert_image,
    "get_media_info": get_media_info,
    "trim_audio": trim_audio,
    "trim_video": trim_video,
    "extract_video_audio": extract_video_audio,
    "extract_video_frame": extract_video_frame,
    "compress_video": compress_video,
    "transcribe_media": transcribe_media,
    "list_archive": list_archive,
    "extract_archive": extract_archive,
    "create_archive": create_archive,
}
