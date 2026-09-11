import os
import json
from java import jclass

Python = jclass("com.chaquo.python.Python")


def _app_files_dir():
    app = Python.getPlatform().getApplication()
    return str(app.getFilesDir().getAbsolutePath())




def _store_path():
    return os.path.join(_app_files_dir(), "vocab_store.json")


def _load():
    path = _store_path()

    if not os.path.exists(path):
        return []

    with open(path, "r", encoding="utf-8") as f:
        try:
            return json.load(f)
        except Exception:
            return []


def _save(entries):
    path = _store_path()

    with open(path, "w", encoding="utf-8") as f:
        json.dump(entries, f, ensure_ascii=False, indent=2)


def add_pair(hindi_text, santali_text):
    """Save a Hindi/Santali pair, skipping exact duplicates."""

    hindi_text = (hindi_text or "").strip()
    santali_text = (santali_text or "").strip()

    if not hindi_text or not santali_text:
        return _load()

    entries = _load()

    for entry in entries:
        if entry.get("hindi") == hindi_text and entry.get("santali") == santali_text:
            return entries

    entries.append({"hindi": hindi_text, "santali": santali_text})
    _save(entries)

    return entries


def get_all_pairs():
    """Return all saved pairs as [[hindi, santali], ...] — Chaquopy-friendly shape."""

    return [[e.get("hindi", ""), e.get("santali", "")] for e in _load()]


def clear_all():
    _save([])