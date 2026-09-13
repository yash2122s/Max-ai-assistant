"""
Unit tests for Phase 2: Universal Multimodal File Processor (tools/universal_file_tool.py)
and extended file manager capabilities.
"""

import os
import io
import wave
import struct
import shutil
import tempfile
import pytest
from pathlib import Path
from PIL import Image

import pypdf
import pandas as pd
from docx import Document
from pptx import Presentation
from pptx.util import Inches

from tools import universal_file_tool
from tools import file_manager
from core.unified_tool_registry import unified_tool_registry
from core.undo_manager import undo_manager


@pytest.fixture
def temp_sandbox():
    """Creates an isolated temporary directory within the safe user workspace."""
    temp_dir = tempfile.mkdtemp(prefix="max_file_test_")
    yield Path(temp_dir)
    if os.path.exists(temp_dir):
        shutil.rmtree(temp_dir, ignore_errors=True)


# ─── 1. PDF Capability Tests ──────────────────────────────────────────────────

def test_pdf_capabilities(temp_sandbox):
    pdf_path = temp_sandbox / "sample_doc.pdf"

    # Create a 2-page PDF
    writer = pypdf.PdfWriter()
    p1 = writer.add_blank_page(width=200, height=200)
    p2 = writer.add_blank_page(width=200, height=200)
    with open(pdf_path, "wb") as f:
        writer.write(f)

    # 1. Extract text (blank PDF returns 0 chars but success status)
    extract_res = universal_file_tool.extract_pdf_text(str(pdf_path))
    assert extract_res["status"] == "success"
    assert extract_res["total_pages"] == 2

    # 2. Extract pages (slice page 1)
    slice_out = temp_sandbox / "sliced.pdf"
    slice_res = universal_file_tool.extract_pdf_pages(str(pdf_path), start_page=1, end_page=1, output_path=str(slice_out))
    assert slice_res["status"] == "success"
    assert os.path.exists(slice_out)
    assert slice_res["pages_extracted"] == 1

    # 3. PDF to DOCX
    docx_out = temp_sandbox / "from_pdf.docx"
    conv_res = universal_file_tool.pdf_to_docx(str(pdf_path), output_path=str(docx_out))
    assert conv_res["status"] == "success"
    assert os.path.exists(docx_out)


# ─── 2. DOCX Capability Tests ─────────────────────────────────────────────────

def test_docx_capabilities(temp_sandbox):
    docx_path = temp_sandbox / "test_doc.docx"

    # 1. Write DOCX
    write_res = universal_file_tool.write_docx(
        str(docx_path),
        title="Quarterly System Status",
        paragraphs=["SECTION 1: OVERVIEW", "All systems are nominal across Windows and Hermes nodes.", "SECTION 2: METRICS", "CPU load is 12%."]
    )
    assert write_res["status"] == "success"
    assert os.path.exists(docx_path)

    # 2. Read DOCX
    read_res = universal_file_tool.read_docx(str(docx_path))
    assert read_res["status"] == "success"
    assert read_res["paragraph_count"] >= 4
    assert "Quarterly System Status" in read_res["content"]

    # 3. Extract text to .txt
    txt_out = temp_sandbox / "extracted_docx.txt"
    ext_res = universal_file_tool.extract_docx_text(str(docx_path), output_path=str(txt_out))
    assert ext_res["status"] == "success"
    assert os.path.exists(txt_out)
    assert "nominal" in txt_out.read_text(encoding="utf-8")

    # 4. Reformat DOCX
    reformat_out = temp_sandbox / "reformatted.docx"
    ref_res = universal_file_tool.reformat_docx(str(docx_path), output_path=str(reformat_out), title="Standardized Status")
    assert ref_res["status"] == "success"
    assert os.path.exists(reformat_out)


# ─── 3. Tabular (CSV & Excel) Tests ───────────────────────────────────────────

def test_tabular_capabilities(temp_sandbox):
    csv_path = temp_sandbox / "telemetry.csv"

    # Create test CSV
    df = pd.DataFrame({
        "Device": ["Phone 1", "Phone 2", "Tablet", "Workstation", "Server"],
        "OS": ["Android", "Android", "Android", "Windows", "Linux"],
        "Battery": [92, 45, 78, 100, 100],
        "Online": [True, True, False, True, True]
    })
    df.to_csv(csv_path, index=False)

    # 1. Read Tabular
    read_res = universal_file_tool.read_tabular(str(csv_path))
    assert read_res["status"] == "success"
    assert read_res["total_rows"] == 5
    assert "Device" in read_res["columns"]
    assert "Battery" in read_res["columns"]

    # 2. Statistics
    stats_res = universal_file_tool.get_tabular_statistics(str(csv_path))
    assert stats_res["status"] == "success"
    assert stats_res["row_count"] == 5
    assert "Battery" in stats_res["statistics"]

    # 3. Filter Tabular
    filter_out = temp_sandbox / "filtered_android.csv"
    filt_res = universal_file_tool.filter_tabular(
        str(csv_path),
        column="OS",
        value="Android",
        condition="equals",
        output_path=str(filter_out)
    )
    assert filt_res["status"] == "success"
    assert filt_res["matched_rows"] == 3
    assert os.path.exists(filter_out)

    # 4. Sort Tabular
    sort_out = temp_sandbox / "sorted_battery.csv"
    sort_res = universal_file_tool.sort_tabular(
        str(csv_path),
        column="Battery",
        ascending=False,
        output_path=str(sort_out)
    )
    assert sort_res["status"] == "success"
    assert os.path.exists(sort_out)
    sorted_df = pd.read_csv(sort_out)
    assert sorted_df.iloc[0]["Battery"] == 100

    # 5. Convert Tabular (CSV -> JSON and CSV -> XLSX)
    json_out = temp_sandbox / "telemetry.json"
    conv_json = universal_file_tool.convert_tabular(str(csv_path), target_format="json", output_path=str(json_out))
    assert conv_json["status"] == "success"
    assert os.path.exists(json_out)

    xlsx_out = temp_sandbox / "telemetry.xlsx"
    conv_xlsx = universal_file_tool.convert_tabular(str(csv_path), target_format="xlsx", output_path=str(xlsx_out))
    assert conv_xlsx["status"] == "success"
    assert os.path.exists(xlsx_out)


# ─── 4. PPTX Capability Tests ─────────────────────────────────────────────────

def test_pptx_capabilities(temp_sandbox):
    pptx_path = temp_sandbox / "presentation.pptx"

    prs = Presentation()
    slide_layout = prs.slide_layouts[0]
    slide = prs.slides.add_slide(slide_layout)
    title = slide.shapes.title
    subtitle = slide.placeholders[1]
    title.text = "MAX Windows Agent Phase 2"
    subtitle.text = "Universal Multimodal File Processing Architecture"
    prs.save(str(pptx_path))

    # 1. Extract PPTX text
    ext_res = universal_file_tool.extract_pptx_text(str(pptx_path))
    assert ext_res["status"] == "success"
    assert ext_res["total_slides"] == 1
    assert "Phase 2" in ext_res["extracted_text"]

    # 2. Summarize PPTX
    sum_res = universal_file_tool.summarize_pptx(str(pptx_path))
    assert sum_res["status"] == "success"
    assert "Phase 2" in sum_res["summary"]


# ─── 5. Image Capability Tests ────────────────────────────────────────────────

def test_image_capabilities(temp_sandbox):
    img_path = temp_sandbox / "test_image.png"

    # Create 200x100 solid color image
    img = Image.new("RGB", (200, 100), color=(73, 109, 137))
    img.save(str(img_path))

    # 1. Info
    info_res = universal_file_tool.get_image_info(str(img_path))
    assert info_res["status"] == "success"
    assert info_res["width"] == 200
    assert info_res["height"] == 100
    assert info_res["format"] == "PNG"

    # 2. Resize
    resize_out = temp_sandbox / "resized.png"
    res_res = universal_file_tool.resize_image(str(img_path), width=100, height=50, output_path=str(resize_out))
    assert res_res["status"] == "success"
    assert os.path.exists(resize_out)
    assert res_res["new_dimensions"] == [100, 50]

    # 3. Compress
    compress_out = temp_sandbox / "compressed.jpg"
    comp_res = universal_file_tool.compress_image(str(img_path), quality=60, output_path=str(compress_out))
    assert comp_res["status"] == "success"
    assert os.path.exists(compress_out)

    # 4. Convert
    conv_out = temp_sandbox / "converted.webp"
    conv_res = universal_file_tool.convert_image(str(img_path), target_format="webp", output_path=str(conv_out))
    assert conv_res["status"] == "success"
    assert os.path.exists(conv_out)


# ─── 6. Audio Capability Tests ────────────────────────────────────────────────

def test_audio_capabilities(temp_sandbox):
    audio_path = temp_sandbox / "sine_wave.wav"

    # Create 1 second 440Hz sine wave WAV file in pure python
    sample_rate = 16000
    duration_sec = 1.0
    num_samples = int(sample_rate * duration_sec)

    with wave.open(str(audio_path), "wb") as wav_file:
        wav_file.setnchannels(1)  # Mono
        wav_file.setsampwidth(2)  # 16-bit
        wav_file.setframerate(sample_rate)
        # Write silence / low tone
        data = bytearray(num_samples * 2)
        wav_file.writeframes(data)

    # 1. Media info
    info_res = universal_file_tool.get_media_info(str(audio_path))
    assert info_res["status"] == "success"
    assert info_res["duration_sec"] >= 0.9

    # 2. Audio trim
    trim_out = temp_sandbox / "trimmed.wav"
    trim_res = universal_file_tool.trim_audio(str(audio_path), start_sec=0.0, end_sec=0.5, output_path=str(trim_out))
    assert trim_res["status"] == "success"
    assert os.path.exists(trim_out)


# ─── 7. Archive Capability Tests ──────────────────────────────────────────────

def test_archive_capabilities(temp_sandbox):
    source_dir = temp_sandbox / "archive_source"
    source_dir.mkdir(parents=True, exist_ok=True)

    (source_dir / "file1.txt").write_text("Alpha content", encoding="utf-8")
    (source_dir / "file2.txt").write_text("Beta content", encoding="utf-8")

    # 1. Create archive
    zip_out = temp_sandbox / "packaged.zip"
    arch_res = universal_file_tool.create_archive(str(source_dir), archive_type="zip", output_path=str(zip_out))
    assert arch_res["status"] == "success"
    created_zip = arch_res["archive_path"]
    assert os.path.exists(created_zip)

    # 2. List archive
    list_res = universal_file_tool.list_archive(created_zip)
    assert list_res["status"] == "success"
    assert list_res["total_items"] >= 2

    # 3. Extract archive
    extract_dest = temp_sandbox / "extracted_archive"
    ext_res = universal_file_tool.extract_archive(created_zip, destination=str(extract_dest))
    assert ext_res["status"] == "success"
    assert os.path.exists(extract_dest)


# ─── 8. Universal Dispatcher Tests ────────────────────────────────────────────

def test_universal_dispatcher(temp_sandbox):
    sample_txt = temp_sandbox / "plain.txt"
    sample_txt.write_text("Universal file dispatcher test content.", encoding="utf-8")

    # Call process_file
    res = universal_file_tool.process_file(str(sample_txt))
    assert res["status"] == "success"
    assert res["file_type"] == "text"

    # Call file_processor wrapper
    compat_res = universal_file_tool.file_processor({"file_path": str(sample_txt)})
    assert compat_res["status"] == "success"


# ─── 9. File Manager Desktop & Largest Files Tests ────────────────────────────

def test_file_manager_extensions(temp_sandbox):
    # Test get_largest_files
    f1 = temp_sandbox / "small.dat"
    f2 = temp_sandbox / "large.dat"
    f1.write_bytes(b"0" * 100)
    f2.write_bytes(b"0" * 5000)

    res = file_manager.get_largest_files(directory=str(temp_sandbox), count=5)
    assert res["status"] == "success"
    assert len(res["largest_files"]) >= 2
    assert res["largest_files"][0]["name"] == "large.dat"


# ─── 10. Unified Tool Registry Pipeline Integration ──────────────────────────

def test_unified_registry_phase2_dispatch(temp_sandbox):
    csv_file = temp_sandbox / "pipeline_test.csv"
    csv_file.write_text("Key,Value\nA,1\nB,2\nC,3\n", encoding="utf-8")

    # 1. Direct tool execution via UnifiedToolRegistry
    res_direct = unified_tool_registry.execute("read_tabular", {"file_path": str(csv_file)})
    assert res_direct["status"] == "success"
    assert res_direct["total_rows"] == 3

    # 2. Alias execution ("read_csv", "csv_stats", "file_processor")
    res_alias1 = unified_tool_registry.execute("read_csv", {"file_path": str(csv_file)})
    assert res_alias1["status"] == "success"

    res_alias2 = unified_tool_registry.execute("csv_stats", {"file_path": str(csv_file)})
    assert res_alias2["status"] == "success"

    res_alias3 = unified_tool_registry.execute("file_processor", {"file_path": str(csv_file), "action": "stats"})
    assert res_alias3["status"] == "success"
