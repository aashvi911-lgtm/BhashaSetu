import os
import re
from java import jclass

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib import colors
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

Python = jclass("com.chaquo.python.Python")

_FONTS_REGISTERED = False

CARD_COLS = 2
CARD_ROWS = 4
CARD_MARGIN = 10 * mm


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

    pdfmetrics.registerFont(TTFont("NotoDevanagari", deva_path))
    pdfmetrics.registerFont(TTFont("NotoOlChiki", olck_path))
    _FONTS_REGISTERED = True

    print("Flashcard fonts registered successfully")


def _flashcards_dir():
    out_dir = os.path.join(_files_dir(), "flashcards")
    os.makedirs(out_dir, exist_ok=True)
    return out_dir


# ============================================================
# CARD DRAWING
# ============================================================

def _fit_font_size(font_name, text, max_width, start_size, min_size):
    size = start_size
    while size > min_size and pdfmetrics.stringWidth(
        str(text), font_name, size
    ) > max_width:
        size -= 1
    return size


def _draw_centered(c, text, font_name, x, y, width, start_size, min_size):
    size = _fit_font_size(
        font_name,
        text,
        width - 14 * mm,
        start_size,
        min_size,
    )
    c.setFont(font_name, size)
    c.drawCentredString(x + width / 2, y, str(text))


def _draw_card(c, x, y, width, height, hindi_word, santali_word, number):
    # Outer card / cut border.
    c.setStrokeColor(colors.HexColor("#94A3B8"))
    c.setLineWidth(0.8)
    c.setDash(3, 3)
    c.roundRect(x + 1, y + 1, width - 2, height - 2, 5 * mm, stroke=1, fill=0)
    c.setDash()

    # Small card number.
    c.setFillColor(colors.HexColor("#64748B"))
    c.setFont("NotoDevanagari", 8)
    c.drawString(x + 5 * mm, y + height - 7 * mm, str(number))

    # Hindi.
    _draw_centered(
        c,
        hindi_word,
        "NotoDevanagari",
        x,
        y + height * 0.61,
        width,
        19,
        11,
    )

    # Divider.
    c.setStrokeColor(colors.HexColor("#0F766E"))
    c.setLineWidth(1)
    c.line(
        x + 10 * mm,
        y + height * 0.49,
        x + width - 10 * mm,
        y + height * 0.49,
    )

    # Santali.
    _draw_centered(
        c,
        santali_word,
        "NotoOlChiki",
        x,
        y + height * 0.29,
        width,
        20,
        10,
    )


# ============================================================
# FLASHCARD GENERATION
# ============================================================

def generate_flashcards(word_pairs, title="Santali Flashcards"):
    """
    Create printable bilingual flashcards from SAVED material.

    Backwards compatible with the old Kotlin call:
        generate_flashcards(word_pairs)

    The PDF contains 8 cut-out cards per A4 page.
    """
    _register_fonts()

    cleaned_pairs = []
    for pair in (word_pairs or []):
        if pair is None or len(pair) < 2:
            continue
        hindi = str(pair[0] or "").strip()
        santali = str(pair[1] or "").strip()
        if hindi and santali:
            cleaned_pairs.append((hindi, santali))

    if not cleaned_pairs:
        raise ValueError("No saved translations were selected.")

    safe_title = re.sub(r"[^A-Za-z0-9._-]+", "_", str(title).strip())
    safe_title = safe_title.strip("_") or "flashcards"
    output_path = os.path.join(
        _flashcards_dir(),
        safe_title + "_flashcards.pdf",
    )

    page_width, page_height = A4
    c = canvas.Canvas(output_path, pagesize=A4, title=str(title))

    usable_width = page_width - 2 * CARD_MARGIN
    usable_height = page_height - 2 * CARD_MARGIN

    card_width = usable_width / CARD_COLS
    card_height = usable_height / CARD_ROWS
    cards_per_page = CARD_COLS * CARD_ROWS

    for index, (hindi_word, santali_word) in enumerate(cleaned_pairs):
        position = index % cards_per_page

        if position == 0 and index != 0:
            c.showPage()

        col = position % CARD_COLS
        row = position // CARD_COLS

        x = CARD_MARGIN + col * card_width
        y = page_height - CARD_MARGIN - (row + 1) * card_height

        _draw_card(
            c,
            x,
            y,
            card_width,
            card_height,
            hindi_word,
            santali_word,
            index + 1,
        )

    c.save()

    print("Flashcards generated:", output_path)
    return output_path
