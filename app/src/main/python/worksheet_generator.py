import os
import re
import html
from java import jclass

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib import colors
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    SimpleDocTemplate,
    Table,
    TableStyle,
    Paragraph,
    Spacer,
    PageBreak,
)
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.enums import TA_CENTER, TA_LEFT


Python = jclass("com.chaquo.python.Python")

_FONTS_REGISTERED = False


# ============================================================
# APP FILE ACCESS
# ============================================================

def _app_context():
    return Python.getPlatform().getApplication()


def _files_dir():
    return str(_app_context().getFilesDir().getAbsolutePath())


def _copy_asset_font(asset_name, dest_name):
    app = _app_context()
    dest_dir = os.path.join(_files_dir(), "fonts")
    os.makedirs(dest_dir, exist_ok=True)

    dest_path = os.path.join(dest_dir, dest_name)

    if os.path.exists(dest_path):
        return dest_path

    stream = app.getAssets().open("fonts/" + asset_name)

    try:
        with open(dest_path, "wb") as out:
            buffer = bytearray(64 * 1024)

            while True:
                count = stream.read(buffer)

                if count <= 0:
                    break

                out.write(buffer[:count])

    finally:
        stream.close()

    return dest_path


def _register_fonts():
    global _FONTS_REGISTERED

    if _FONTS_REGISTERED:
        return

    deva_path = _copy_asset_font(
        "NotoSansDevanagari-Regular.ttf",
        "NotoSansDevanagari-Regular.ttf",
    )

    olck_path = _copy_asset_font(
        "NotoSansOlChiki-Regular.ttf",
        "NotoSansOlChiki-Regular.ttf",
    )

    pdfmetrics.registerFont(
        TTFont("NotoDevanagari", deva_path)
    )

    pdfmetrics.registerFont(
        TTFont("NotoOlChiki", olck_path)
    )

    _FONTS_REGISTERED = True

    print("Worksheet fonts registered successfully")


# ============================================================
# FILE / TEXT HELPERS
# ============================================================

def _worksheets_dir():
    out_dir = os.path.join(
        _files_dir(),
        "worksheets"
    )

    os.makedirs(out_dir, exist_ok=True)

    return out_dir


def _safe_filename(title):
    value = re.sub(
        r"[^A-Za-z0-9._-]+",
        "_",
        str(title or "").strip()
    )

    return value.strip("_") or "worksheet"


def _java_list_to_python(value):
    """
    Convert Chaquopy Java/Kotlin ArrayList values without using
    Python iteration directly on the Java object.
    """
    if value is None:
        return []

    if hasattr(value, "size") and hasattr(value, "get"):
        result = []

        for i in range(value.size()):
            result.append(value.get(i))

        return result

    return value


def _normalise_pairs(word_pairs):
    """
    Convert Kotlin/Java pairs into normal Python tuples.

    Also removes duplicate Hindi + Santali pairs while preserving
    their original order.
    """
    raw_pairs = _java_list_to_python(word_pairs)

    cleaned_pairs = []
    seen = set()

    for pair in raw_pairs:
        if pair is None:
            continue

        pair = _java_list_to_python(pair)

        if len(pair) < 2:
            continue

        hindi = str(pair[0] or "").strip()
        santali = str(pair[1] or "").strip()

        if not hindi or not santali:
            continue

        key = (hindi, santali)

        if key in seen:
            continue

        seen.add(key)
        cleaned_pairs.append(key)

    return cleaned_pairs


# ============================================================
# MIXED-SCRIPT TEXT
# ============================================================

def _mixed_markup(text):
    """
    Render Latin/English, Devanagari and Ol Chiki with the
    correct font so English never appears as boxes.

    The fonts are switched character-by-character where needed.
    """
    text = str(text or "")

    if not text:
        return ""

    parts = []
    current_font = None
    current_text = []

    def flush():
        nonlocal current_font, current_text

        if not current_text:
            return

        escaped = html.escape(
            "".join(current_text),
            quote=False
        )

        if current_font:
            parts.append(
                '<font name="%s">%s</font>'
                % (current_font, escaped)
            )
        else:
            parts.append(escaped)

        current_text = []

    for char in text:
        code = ord(char)

        if 0x0900 <= code <= 0x097F:
            font = "NotoDevanagari"

        elif 0x1C50 <= code <= 0x1C7F:
            font = "NotoOlChiki"

        else:
            font = "Helvetica"

        if font != current_font:
            flush()
            current_font = font

        current_text.append(char)

    flush()

    return "".join(parts)


def _paragraph(
    text,
    font_name="Helvetica",
    size=12,
    alignment=TA_LEFT,
    color=None,
    leading=None,
):
    """
    Create a Paragraph with a reliable font.

    For mixed UI text, use mixed=True so each script gets the
    correct installed font.
    """
    if leading is None:
        leading = size * 1.35

    style = ParagraphStyle(
        "Inline_%s_%s_%s"
        % (font_name, size, alignment),
        fontName=font_name,
        fontSize=size,
        leading=leading,
        alignment=alignment,
    )

    if color is not None:
        style.textColor = color

    return Paragraph(
        html.escape(str(text or ""), quote=False),
        style
    )


def _mixed_paragraph(
    text,
    size=12,
    alignment=TA_LEFT,
    color=None,
    leading=None,
):
    if leading is None:
        leading = size * 1.35

    style = ParagraphStyle(
        "Mixed_%s_%s" % (size, alignment),
        fontName="Helvetica",
        fontSize=size,
        leading=leading,
        alignment=alignment,
    )

    if color is not None:
        style.textColor = color

    return Paragraph(
        _mixed_markup(text),
        style
    )


# ============================================================
# PAGE DECORATION
# ============================================================

def _footer(canvas, doc):
    canvas.saveState()

    canvas.setFont(
        "Helvetica",
        8
    )

    canvas.setFillColor(
        colors.HexColor("#64748B")
    )

    canvas.drawCentredString(
        A4[0] / 2,
        8 * mm,
        "BhashaSetu  |  Hindi + Santali Learning Material",
    )

    canvas.restoreState()


def _header_bar(text):
    return Table(
        [[
            _mixed_paragraph(
                text,
                size=11,
                alignment=TA_CENTER,
            )
        ]],
        colWidths=[164 * mm],
        rowHeights=[9 * mm],
        style=TableStyle([
            (
                "BACKGROUND",
                (0, 0),
                (-1, -1),
                colors.HexColor("#0F766E"),
            ),
            (
                "TEXTCOLOR",
                (0, 0),
                (-1, -1),
                colors.white,
            ),
            (
                "VALIGN",
                (0, 0),
                (-1, -1),
                "MIDDLE",
            ),
            (
                "LEFTPADDING",
                (0, 0),
                (-1, -1),
                8,
            ),
            (
                "RIGHTPADDING",
                (0, 0),
                (-1, -1),
                8,
            ),
        ])
    )


# ============================================================
# WORKSHEET GENERATION
# ============================================================

def generate_worksheet(title, word_pairs, topic=None):
    """
    Create a clean 3-page bilingual educational worksheet.

    Backwards compatible with the Kotlin call:
        generate_worksheet(title, word_pairs)

    word_pairs:
        list / Kotlin List of [hindi, santali]

    topic:
        optional topic label.
    """

    _register_fonts()

    cleaned_pairs = _normalise_pairs(word_pairs)

    if not cleaned_pairs:
        raise ValueError(
            "No saved translations were selected."
        )

    worksheet_title = str(
        title or "My Santali Worksheet"
    ).strip()

    topic_label = str(
        topic or ""
    ).strip()

    output_path = os.path.join(
        _worksheets_dir(),
        _safe_filename(worksheet_title) + ".pdf",
    )

    doc = SimpleDocTemplate(
        output_path,
        pagesize=A4,
        topMargin=15 * mm,
        bottomMargin=16 * mm,
        leftMargin=15 * mm,
        rightMargin=15 * mm,
        title=worksheet_title,
        author="BhashaSetu",
    )

    styles = getSampleStyleSheet()

    title_style = ParagraphStyle(
        "WorksheetTitle",
        parent=styles["Heading1"],
        fontName="Helvetica",
        fontSize=22,
        leading=27,
        alignment=TA_CENTER,
        spaceAfter=3 * mm,
    )

    subtitle_style = ParagraphStyle(
        "Subtitle",
        parent=styles["Normal"],
        fontName="Helvetica",
        fontSize=11,
        leading=15,
        alignment=TA_CENTER,
        textColor=colors.HexColor("#475569"),
        spaceAfter=3 * mm,
    )

    section_style = ParagraphStyle(
        "Section",
        parent=styles["Heading2"],
        fontName="Helvetica",
        fontSize=16,
        leading=20,
        spaceBefore=3 * mm,
        spaceAfter=3 * mm,
        textColor=colors.HexColor("#0F766E"),
    )

    instruction_style = ParagraphStyle(
        "Instruction",
        parent=styles["Normal"],
        fontName="Helvetica",
        fontSize=10.5,
        leading=14,
        textColor=colors.HexColor("#475569"),
        spaceAfter=4 * mm,
    )

    story = []

    # ========================================================
    # PAGE 1 — LEARN THE WORDS
    # ========================================================

    story.append(
        _mixed_paragraph(
            worksheet_title,
            size=22,
            alignment=TA_CENTER,
            leading=27,
        )
    )

    if topic_label:
        story.append(
            _mixed_paragraph(
                "Topic: " + topic_label,
                size=11,
                alignment=TA_CENTER,
                color=colors.HexColor("#475569"),
            )
        )

    story.append(
        Spacer(1, 2 * mm)
    )

    story.append(
        _mixed_paragraph(
            "Hindi → Santali bilingual learning worksheet",
            size=11,
            alignment=TA_CENTER,
            color=colors.HexColor("#475569"),
        )
    )

    story.append(
        Spacer(1, 5 * mm)
    )

    student_data = [[
        _mixed_paragraph(
            "Name: ______________________________",
            size=10.5,
        ),
        _mixed_paragraph(
            "Date: ________________",
            size=10.5,
        ),
    ]]

    student_table = Table(
        student_data,
        colWidths=[105 * mm, 59 * mm],
    )

    student_table.setStyle(
        TableStyle([
            (
                "VALIGN",
                (0, 0),
                (-1, -1),
                "MIDDLE",
            ),
            (
                "LEFTPADDING",
                (0, 0),
                (-1, -1),
                0,
            ),
            (
                "RIGHTPADDING",
                (0, 0),
                (-1, -1),
                0,
            ),
            (
                "TOPPADDING",
                (0, 0),
                (-1, -1),
                2,
            ),
            (
                "BOTTOMPADDING",
                (0, 0),
                (-1, -1),
                2,
            ),
        ])
    )

    story.append(student_table)
    story.append(Spacer(1, 7 * mm))

    story.append(
        _mixed_paragraph(
            "1. Learn the Words",
            size=16,
            color=colors.HexColor("#0F766E"),
            leading=20,
        )
    )

    story.append(
        _mixed_paragraph(
            "Read each Hindi word and its Santali translation.",
            size=10.5,
            color=colors.HexColor("#475569"),
        )
    )

    story.append(Spacer(1, 2 * mm))

    table_data = [[
        _paragraph(
            "Hindi",
            "Helvetica",
            11,
            TA_CENTER,
        ),
        _paragraph(
            "Santali (Ol Chiki)",
            "Helvetica",
            11,
            TA_CENTER,
        ),
    ]]

    for hindi, santali in cleaned_pairs:
        table_data.append([
            _paragraph(
                hindi,
                "NotoDevanagari",
                12,
                TA_LEFT,
            ),
            _paragraph(
                santali,
                "NotoOlChiki",
                13,
                TA_LEFT,
            ),
        ])

    learn_table = Table(
        table_data,
        colWidths=[82 * mm, 82 * mm],
        repeatRows=1,
    )

    learn_table.setStyle(
        TableStyle([
            (
                "BACKGROUND",
                (0, 0),
                (-1, 0),
                colors.HexColor("#0F766E"),
            ),
            (
                "TEXTCOLOR",
                (0, 0),
                (-1, 0),
                colors.white,
            ),
            (
                "GRID",
                (0, 0),
                (-1, -1),
                0.6,
                colors.HexColor("#CBD5E1"),
            ),
            (
                "VALIGN",
                (0, 0),
                (-1, -1),
                "MIDDLE",
            ),
            (
                "LEFTPADDING",
                (0, 0),
                (-1, -1),
                8,
            ),
            (
                "RIGHTPADDING",
                (0, 0),
                (-1, -1),
                8,
            ),
            (
                "TOPPADDING",
                (0, 0),
                (-1, -1),
                9,
            ),
            (
                "BOTTOMPADDING",
                (0, 0),
                (-1, -1),
                9,
            ),
        ])
    )

    story.append(learn_table)

    story.append(PageBreak())

    # ========================================================
    # PAGE 2 — PRACTICE WRITING
    # ========================================================

    story.append(
        _mixed_paragraph(
            "2. Practice Writing",
            size=20,
            color=colors.HexColor("#0F766E"),
            leading=24,
        )
    )

    story.append(
        _mixed_paragraph(
            "Write the Santali word in Ol Chiki for each Hindi word.",
            size=11,
            color=colors.HexColor("#475569"),
        )
    )

    story.append(Spacer(1, 5 * mm))

    practice_data = [[
        _paragraph(
            "Hindi",
            "Helvetica",
            11,
            TA_CENTER,
        ),
        _paragraph(
            "Write in Santali (Ol Chiki)",
            "Helvetica",
            11,
            TA_CENTER,
        ),
    ]]

    for index, (hindi, _) in enumerate(cleaned_pairs, start=1):
        practice_data.append([
            _paragraph(
                "%d. %s" % (index, hindi),
                "NotoDevanagari",
                12,
                TA_LEFT,
            ),
            _paragraph(
                "________________________________",
                "Helvetica",
                12,
                TA_LEFT,
            ),
        ])

    practice_table = Table(
        practice_data,
        colWidths=[67 * mm, 97 * mm],
        repeatRows=1,
    )

    practice_table.setStyle(
        TableStyle([
            (
                "BACKGROUND",
                (0, 0),
                (-1, 0),
                colors.HexColor("#E6F4F1"),
            ),
            (
                "GRID",
                (0, 0),
                (-1, -1),
                0.6,
                colors.HexColor("#CBD5E1"),
            ),
            (
                "VALIGN",
                (0, 0),
                (-1, -1),
                "MIDDLE",
            ),
            (
                "LEFTPADDING",
                (0, 0),
                (-1, -1),
                9,
            ),
            (
                "RIGHTPADDING",
                (0, 0),
                (-1, -1),
                9,
            ),
            (
                "TOPPADDING",
                (0, 0),
                (-1, -1),
                14,
            ),
            (
                "BOTTOMPADDING",
                (0, 0),
                (-1, -1),
                14,
            ),
        ])
    )

    story.append(practice_table)

    story.append(Spacer(1, 8 * mm))

    story.append(
        _mixed_paragraph(
            "Tip: Write carefully inside the lines.",
            size=10.5,
            color=colors.HexColor("#64748B"),
        )
    )

    story.append(PageBreak())

    # ========================================================
    # PAGE 3 — MATCH THE WORDS
    # ========================================================

    story.append(
        _mixed_paragraph(
            "3. Match the Words",
            size=20,
            color=colors.HexColor("#0F766E"),
            leading=24,
        )
    )

    story.append(
        _mixed_paragraph(
            "Match each Hindi word with the correct Santali word.",
            size=11,
            color=colors.HexColor("#475569"),
        )
    )

    story.append(Spacer(1, 5 * mm))

    # Create a visibly separate Santali word bank.
    # A deterministic rotation keeps the activity different from
    # the learning table without making the translations look wrong.
    shuffled = [
        santali
        for _, santali in cleaned_pairs
    ]

    if len(shuffled) > 1:
        shift = max(1, len(shuffled) // 2)
        shuffled = shuffled[shift:] + shuffled[:shift]

    left_data = [[
        _paragraph(
            "Hindi",
            "Helvetica",
            11,
            TA_CENTER,
        ),
        _paragraph(
            "Your Answer",
            "Helvetica",
            11,
            TA_CENTER,
        ),
    ]]

    for index, (hindi, _) in enumerate(cleaned_pairs, start=1):
        left_data.append([
            _paragraph(
                "%d. %s" % (index, hindi),
                "NotoDevanagari",
                12,
            ),
            _paragraph(
                "______________",
                "Helvetica",
                12,
            ),
        ])

    left_table = Table(
        left_data,
        colWidths=[82 * mm, 82 * mm],
        repeatRows=1,
    )

    left_table.setStyle(
        TableStyle([
            (
                "BACKGROUND",
                (0, 0),
                (-1, 0),
                colors.HexColor("#0F766E"),
            ),
            (
                "TEXTCOLOR",
                (0, 0),
                (-1, 0),
                colors.white,
            ),
            (
                "GRID",
                (0, 0),
                (-1, -1),
                0.6,
                colors.HexColor("#CBD5E1"),
            ),
            (
                "VALIGN",
                (0, 0),
                (-1, -1),
                "MIDDLE",
            ),
            (
                "LEFTPADDING",
                (0, 0),
                (-1, -1),
                9,
            ),
            (
                "RIGHTPADDING",
                (0, 0),
                (-1, -1),
                9,
            ),
            (
                "TOPPADDING",
                (0, 0),
                (-1, -1),
                12,
            ),
            (
                "BOTTOMPADDING",
                (0, 0),
                (-1, -1),
                12,
            ),
        ])
    )

    story.append(left_table)

    story.append(Spacer(1, 8 * mm))

    story.append(
        _mixed_paragraph(
            "Santali Word Bank",
            size=14,
            color=colors.HexColor("#0F766E"),
            leading=18,
        )
    )

    story.append(
        _mixed_paragraph(
            "Use these words to complete the matching activity.",
            size=10.5,
            color=colors.HexColor("#475569"),
        )
    )

    story.append(Spacer(1, 3 * mm))

    word_bank_data = []

    for index, santali in enumerate(shuffled, start=1):
        word_bank_data.append([
            _paragraph(
                chr(64 + index) + ".",
                "Helvetica",
                11,
                TA_CENTER,
            ),
            _paragraph(
                santali,
                "NotoOlChiki",
                14,
                TA_CENTER,
            ),
        ])

    # Put the word bank into two columns when possible.
    bank_rows = []
    i = 0

    while i < len(word_bank_data):
        row = [word_bank_data[i][0], word_bank_data[i][1]]

        if i + 1 < len(word_bank_data):
            row.extend([
                word_bank_data[i + 1][0],
                word_bank_data[i + 1][1],
            ])
        else:
            row.extend(["", ""])

        bank_rows.append(row)
        i += 2

    bank_table = Table(
        bank_rows,
        colWidths=[10 * mm, 66 * mm, 10 * mm, 66 * mm],
    )

    bank_table.setStyle(
        TableStyle([
            (
                "BACKGROUND",
                (0, 0),
                (-1, -1),
                colors.HexColor("#F1F5F9"),
            ),
            (
                "BOX",
                (0, 0),
                (-1, -1),
                0.6,
                colors.HexColor("#CBD5E1"),
            ),
            (
                "INNERGRID",
                (0, 0),
                (-1, -1),
                0.4,
                colors.HexColor("#CBD5E1"),
            ),
            (
                "VALIGN",
                (0, 0),
                (-1, -1),
                "MIDDLE",
            ),
            (
                "LEFTPADDING",
                (0, 0),
                (-1, -1),
                7,
            ),
            (
                "RIGHTPADDING",
                (0, 0),
                (-1, -1),
                7,
            ),
            (
                "TOPPADDING",
                (0, 0),
                (-1, -1),
                10,
            ),
            (
                "BOTTOMPADDING",
                (0, 0),
                (-1, -1),
                10,
            ),
        ])
    )

    story.append(bank_table)

    story.append(Spacer(1, 10 * mm))

    story.append(
        _mixed_paragraph(
            "Great work! Review the words once more when you finish.",
            size=11,
            alignment=TA_CENTER,
            color=colors.HexColor("#475569"),
        )
    )

    # ========================================================
    # BUILD PDF
    # ========================================================

    doc.build(
        story,
        onFirstPage=_footer,
        onLaterPages=_footer,
    )

    print(
        "Worksheet generated:",
        output_path
    )

    return output_path
