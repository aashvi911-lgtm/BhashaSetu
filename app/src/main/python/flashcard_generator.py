import os
import re

from java import jclass

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib import colors
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont


# =========================================================
# ANDROID / CHAQUOPY HELPERS
# =========================================================

Python = jclass("com.chaquo.python.Python")

_FONTS_REGISTERED = False


def _app_context():
    """
    Get the Android application context.
    """
    return Python.getPlatform().getApplication()


def _files_dir():
    """
    Return the app's internal files directory.
    """
    return str(
        _app_context()
        .getFilesDir()
        .getAbsolutePath()
    )


def _copy_asset_font(asset_name, dest_name):
    """
    Copy a font from Android assets/fonts/ into the app's
    internal files/fonts/ directory if it does not already exist.
    """

    app = _app_context()

    dest_dir = os.path.join(
        _files_dir(),
        "fonts"
    )

    os.makedirs(
        dest_dir,
        exist_ok=True
    )

    dest_path = os.path.join(
        dest_dir,
        dest_name
    )

    if os.path.exists(dest_path):
        return dest_path

    stream = app.getAssets().open(
        "fonts/" + asset_name
    )

    try:
        with open(dest_path, "wb") as out:

            buffer = bytearray(
                64 * 1024
            )

            while True:

                count = stream.read(
                    buffer
                )

                if count <= 0:
                    break

                out.write(
                    buffer[:count]
                )

    finally:
        stream.close()

    return dest_path


def _register_fonts():
    """
    Register the Devanagari and Ol Chiki fonts used by the
    flashcard PDF.
    """

    global _FONTS_REGISTERED

    if _FONTS_REGISTERED:
        return

    devanagari_path = _copy_asset_font(
        "NotoSansDevanagari-Regular.ttf",
        "NotoSansDevanagari-Regular.ttf"
    )

    ol_chiki_path = _copy_asset_font(
        "NotoSansOlChiki-Regular.ttf",
        "NotoSansOlChiki-Regular.ttf"
    )

    pdfmetrics.registerFont(
        TTFont(
            "NotoDevanagari",
            devanagari_path
        )
    )

    pdfmetrics.registerFont(
        TTFont(
            "NotoOlChiki",
            ol_chiki_path
        )
    )

    _FONTS_REGISTERED = True

    print(
        "Flashcard fonts registered successfully"
    )


# =========================================================
# FLASHCARD LAYOUT
# =========================================================

CARD_COLS = 2
CARD_ROWS = 4

CARD_MARGIN = 10 * mm


# =========================================================
# JAVA / PYTHON CONVERSION
# =========================================================

def _as_python_list(value):
    """
    Convert a Chaquopy Java ArrayList / Java array / Python
    list or tuple into a normal Python list.

    Kotlin currently sends the flashcard pairs through
    Chaquopy as Java ArrayList objects. In this environment,
    directly doing:

        for pair in word_pairs:

    causes:

        TypeError: 'ArrayList' object is not iterable

    This helper safely converts those Java collections first.
    """

    if value is None:
        return []

    # Already a normal Python collection.
    if isinstance(value, (list, tuple)):
        return list(value)

    # Java ArrayList and similar Java collections expose toArray().
    try:
        return list(
            value.toArray()
        )

    except (AttributeError, TypeError):
        pass

    # Fallback for objects that Python can already iterate.
    try:
        return list(value)

    except TypeError:
        return [value]


# =========================================================
# FLASHCARD OUTPUT DIRECTORY
# =========================================================

def _flashcards_dir():
    """
    Return the directory where generated flashcard PDFs
    should be stored.
    """

    directory = os.path.join(
        _files_dir(),
        "flashcards"
    )

    os.makedirs(
        directory,
        exist_ok=True
    )

    return directory


# =========================================================
# TEXT HELPERS
# =========================================================

def _fit_font_size(
    text,
    font_name,
    max_width,
    start_size=18,
    min_size=8
):
    """
    Find the largest font size that fits inside max_width.
    """

    size = start_size

    while (
        size > min_size
        and pdfmetrics.stringWidth(
            str(text),
            font_name,
            size
        ) > max_width
    ):
        size -= 1

    return size


def _draw_centered(
    c,
    text,
    center_x,
    y,
    max_width,
    font_name,
    start_size=18
):
    """
    Draw text centered horizontally while automatically
    reducing its font size if necessary.
    """

    size = _fit_font_size(
        text,
        font_name,
        max_width,
        start_size=start_size
    )

    c.setFont(
        font_name,
        size
    )

    c.drawCentredString(
        center_x,
        y,
        str(text)
    )


# =========================================================
# DRAW ONE FLASHCARD
# =========================================================

def _draw_card(
    c,
    x,
    y,
    width,
    height,
    hindi_word,
    santali_word,
    number
):
    """
    Draw a single bilingual flashcard.
    """

    # Card border
    c.setStrokeColor(
        colors.HexColor("#444444")
    )

    c.setLineWidth(
        0.8
    )

    c.roundRect(
        x,
        y,
        width,
        height,
        5 * mm,
        stroke=1,
        fill=0
    )

    center_x = x + width / 2

    # -----------------------------------------------------
    # Card number
    # -----------------------------------------------------

    c.setFont(
        "Helvetica",
        7
    )

    c.setFillColor(
        colors.HexColor("#666666")
    )

    c.drawString(
        x + 4 * mm,
        y + height - 6 * mm,
        f"#{number}"
    )

    # -----------------------------------------------------
    # Hindi
    # -----------------------------------------------------

    hindi_y = (
        y
        + height
        - 20 * mm
    )

    c.setFillColor(
        colors.black
    )

    _draw_centered(
        c,
        hindi_word,
        center_x,
        hindi_y,
        width - 14 * mm,
        "NotoDevanagari",
        start_size=18
    )

    # -----------------------------------------------------
    # Divider
    # -----------------------------------------------------

    divider_y = (
        y
        + height / 2
    )

    c.setStrokeColor(
        colors.HexColor("#AAAAAA")
    )

    c.setLineWidth(
        0.5
    )

    c.line(
        x + 8 * mm,
        divider_y,
        x + width - 8 * mm,
        divider_y
    )

    # -----------------------------------------------------
    # Santali / Ol Chiki
    # -----------------------------------------------------

    santali_y = (
        y
        + 18 * mm
    )

    c.setFillColor(
        colors.black
    )

    _draw_centered(
        c,
        santali_word,
        center_x,
        santali_y,
        width - 14 * mm,
        "NotoOlChiki",
        start_size=18
    )


# =========================================================
# FLASHCARD GENERATION
# =========================================================

def generate_flashcards(
    word_pairs,
    title="Santali Flashcards"
):
    """
    Create printable bilingual flashcards from SAVED material.

    Backwards compatible with the old Kotlin call:

        generate_flashcards(word_pairs)

    The PDF contains 8 cut-out cards per A4 page.
    """

    _register_fonts()

    # -----------------------------------------------------
    # IMPORTANT:
    #
    # Kotlin/Chaquopy may give us a Java ArrayList rather
    # than a Python list. Convert it before iterating.
    # -----------------------------------------------------

    python_pairs = _as_python_list(
        word_pairs
    )

    cleaned_pairs = []

    for pair in python_pairs:

        # Nested pairs can ALSO arrive as Java ArrayLists,
        # so convert each pair separately.
        pair = _as_python_list(
            pair
        )

        if pair is None:
            continue

        if len(pair) < 2:
            continue

        hindi = str(
            pair[0] or ""
        ).strip()

        santali = str(
            pair[1] or ""
        ).strip()

        if hindi and santali:
            cleaned_pairs.append(
                (
                    hindi,
                    santali
                )
            )

    # -----------------------------------------------------
    # Make sure there is something to generate.
    # -----------------------------------------------------

    if not cleaned_pairs:
        raise ValueError(
            "No saved translations were selected."
        )

    # -----------------------------------------------------
    # Safe filename
    # -----------------------------------------------------

    safe_title = re.sub(
        r"[^A-Za-z0-9._-]+",
        "_",
        str(title).strip()
    )

    safe_title = (
        safe_title.strip("_")
        or "flashcards"
    )

    output_path = os.path.join(
        _flashcards_dir(),
        safe_title + "_flashcards.pdf"
    )

    # -----------------------------------------------------
    # Create PDF
    # -----------------------------------------------------

    page_width, page_height = A4

    c = canvas.Canvas(
        output_path,
        pagesize=A4,
        title=str(title)
    )

    # -----------------------------------------------------
    # Calculate card dimensions
    # -----------------------------------------------------

    usable_width = (
        page_width
        - 2 * CARD_MARGIN
    )

    usable_height = (
        page_height
        - 2 * CARD_MARGIN
    )

    card_width = (
        usable_width
        / CARD_COLS
    )

    card_height = (
        usable_height
        / CARD_ROWS
    )

    cards_per_page = (
        CARD_COLS
        * CARD_ROWS
    )

    # -----------------------------------------------------
    # Draw cards
    # -----------------------------------------------------

    for index, (
        hindi_word,
        santali_word
    ) in enumerate(cleaned_pairs):

        position = (
            index
            % cards_per_page
        )

        # Start a new page after every 8 cards.
        if (
            position == 0
            and index != 0
        ):
            c.showPage()

        col = (
            position
            % CARD_COLS
        )

        row = (
            position
            // CARD_COLS
        )

        x = (
            CARD_MARGIN
            + col * card_width
        )

        y = (
            page_height
            - CARD_MARGIN
            - (row + 1) * card_height
        )

        _draw_card(
            c,
            x,
            y,
            card_width,
            card_height,
            hindi_word,
            santali_word,
            index + 1
        )

    # -----------------------------------------------------
    # Save PDF
    # -----------------------------------------------------

    c.save()

    print(
        "Flashcards generated:",
        output_path
    )

    return output_path