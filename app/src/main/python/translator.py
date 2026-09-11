import os
import json
import re
import unicodedata
import numpy as np

import sentencepiece as spm
from java import jclass
from santali_dictionary_ALL import (
    lookup_santali as _dictionary_lookup,
    SANTALI_DICTIONARY as _SANTALI_DICTIONARY,
)
print("Local Santali dictionary loaded successfully (ALL supplied workbook sources)")
print("Runtime dictionary entry count:", len(_SANTALI_DICTIONARY))
print("Runtime dictionary test [नमस्कार]:", _dictionary_lookup("नमस्कार"))
print("Runtime dictionary test [नमस्कार।]:", _dictionary_lookup("नमस्कार।"))

# ============================================================
# ANDROID
# ============================================================

Python = jclass("com.chaquo.python.Python")
OrtEnvironment = jclass("ai.onnxruntime.OrtEnvironment")
OnnxTensor = jclass("ai.onnxruntime.OnnxTensor")
HashMap = jclass("java.util.HashMap")


# ============================================================
# MODEL FILES
# ============================================================

MODEL_FILES = [
    "config.json",
    "decoder_model.onnx",
    "decoder_shared.onnx.data",
    "decoder_with_past_model.onnx",
    "dict.SRC.json",
    "dict.TGT.json",
    "encoder_model.onnx",
    "encoder_model.onnx.data",
    "generation_config.json",
    "model.SRC",
    "model.TGT",
    "special_tokens_map.json",
    "tokenizer_config.json",
    "tokenizer_meta.json",
]


# ============================================================
# COPY ASSETS TO INTERNAL STORAGE
# ============================================================

def _prepare_model_directory():

    app = Python.getPlatform().getApplication()
    asset_manager = app.getAssets()

    model_dir = os.path.join(
        str(app.getFilesDir().getAbsolutePath()),
        "indictrans2"
    )

    os.makedirs(model_dir, exist_ok=True)

    for filename in MODEL_FILES:

        destination = os.path.join(model_dir, filename)

        if os.path.exists(destination):
            continue

        source = "models/" + filename

        print("Copying:", source)

        stream = asset_manager.open(source)

        try:
            with open(destination, "wb") as output:

                buffer = bytearray(1024 * 1024)

                while True:

                    count = stream.read(buffer)

                    if count <= 0:
                        break

                    output.write(buffer[:count])

        finally:
            stream.close()

    print("MODEL DIRECTORY:", model_dir)

    return model_dir


_MODEL_DIR = _prepare_model_directory()


# ============================================================
# JSON LOADER
# ============================================================

def _load_json(filename):

    path = os.path.join(_MODEL_DIR, filename)

    print("Loading JSON:", path)

    if not os.path.exists(path):
        raise FileNotFoundError("Model file not found: " + path)

    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


# ============================================================
# LOAD VOCAB DICTS (dict.SRC.json / dict.TGT.json)
# ============================================================
#
# CONFIRMED FORMAT: these are already {"token": id, ...} maps, straight
# from AI4Bharat's own tokenization_indictrans.py (self.encoder =
# self._load_json(self.src_vocab_fp) — used directly, no offset math).

_src_token_to_id = _load_json("dict.SRC.json")
_tgt_token_to_id = _load_json("dict.TGT.json")

_src_id_to_token = {v: k for k, v in _src_token_to_id.items()}
_tgt_id_to_token = {v: k for k, v in _tgt_token_to_id.items()}

# The ONNX bundles released for IndicTrans2 also contain these metadata files.
# They let us validate that the vocabularies and generated IDs match the graph
# instead of silently decoding an ID with the wrong vocabulary.
_model_config = _load_json("config.json")
_tokenizer_meta = _load_json("tokenizer_meta.json")

_SRC_VOCAB_SIZE = int(_tokenizer_meta.get("src_dict_size", len(_src_token_to_id)))
_TGT_VOCAB_SIZE = int(_tokenizer_meta.get("tgt_dict_size", len(_tgt_token_to_id)))
_META_UNK_ID = int(_tokenizer_meta.get("unk_id", 3))

print("SRC vocab size:", len(_src_token_to_id), "metadata:", _SRC_VOCAB_SIZE)
print("TGT vocab size:", len(_tgt_token_to_id), "metadata:", _TGT_VOCAB_SIZE)
print("Model name:", _model_config.get("_name_or_path", "unknown"))
print("Model type:", _model_config.get("model_type", "unknown"))

if len(_src_token_to_id) != _SRC_VOCAB_SIZE:
    print("WARNING: SRC dictionary size does not match tokenizer_meta.json")

if len(_tgt_token_to_id) != _TGT_VOCAB_SIZE:
    print("WARNING: TGT dictionary size does not match tokenizer_meta.json")

# Hindi -> Santali requires the Indic-to-Indic checkpoint.  Do not hard-fail
# on custom model names, but make an accidental en-indic checkpoint obvious.
_model_name_lower = str(_model_config.get("_name_or_path", "")).lower()
if _model_name_lower and "indic-indic" not in _model_name_lower:
    print("WARNING: config does not identify this checkpoint as an indic-indic model.")
    print("         Hindi -> Santali requires the Indic-to-Indic checkpoint.")


# ============================================================
# SPECIAL TOKENS (confirmed from special_tokens_map.json contents
# seen for this model family: bos=<s>, eos=</s>, pad=<pad>, unk=<unk>)
# ============================================================

_BOS_TOKEN = "<s>"
_PAD_TOKEN = "<pad>"
_EOS_TOKEN = "</s>"
_UNK_TOKEN = "<unk>"

_BOS_ID = _src_token_to_id[_BOS_TOKEN]
_PAD_ID = _src_token_to_id[_PAD_TOKEN]
_EOS_ID = _src_token_to_id[_EOS_TOKEN]
_UNK_ID = _src_token_to_id[_UNK_TOKEN]

# Keep target-side special IDs separate.  They are normally 0/1/2/3 for
# IndicTrans2, but decoding should never assume that across arbitrary bundles.
_TGT_BOS_ID = _tgt_token_to_id[_BOS_TOKEN]
_TGT_PAD_ID = _tgt_token_to_id[_PAD_TOKEN]
_TGT_EOS_ID = _tgt_token_to_id[_EOS_TOKEN]
_TGT_UNK_ID = _tgt_token_to_id[_UNK_TOKEN]

print("SRC special IDs:", _BOS_ID, _PAD_ID, _EOS_ID, _UNK_ID)
print("TGT special IDs:", _TGT_BOS_ID, _TGT_PAD_ID, _TGT_EOS_ID, _TGT_UNK_ID)

if _META_UNK_ID != _TGT_UNK_ID:
    print("WARNING: tokenizer_meta unk_id differs from target <unk> ID:",
          _META_UNK_ID, _TGT_UNK_ID)


# ============================================================
# LANGUAGE / SPECIAL TAGS
# ============================================================
# CONFIRMED from AI4Bharat's tokenization_indictrans.py SPECIAL_TAGS set.
# Tokens in this set are never run through SentencePiece — they're
# looked up directly in the vocab dict.

SPECIAL_TAGS = {
    "_bt_", "_ft_",
    "asm_Beng", "awa_Deva", "ben_Beng", "bho_Deva", "brx_Deva",
    "doi_Deva", "eng_Latn", "gom_Deva", "gon_Deva", "guj_Gujr",
    "hin_Deva", "hne_Deva", "kan_Knda", "kas_Arab", "kas_Deva",
    "kha_Latn", "lus_Latn", "mag_Deva", "mai_Deva", "mal_Mlym",
    "mar_Deva", "mni_Beng", "mni_Mtei", "npi_Deva", "ory_Orya",
    "pan_Guru", "san_Deva", "sat_Olck", "snd_Arab", "snd_Deva",
    "tam_Taml", "tel_Telu", "urd_Arab", "unr_Deva",
}


# ============================================================
# LOAD SENTENCEPIECE MODEL
# ============================================================
# CONFIRMED: model.SRC and model.TGT are byte-identical (same MD5) —
# a single shared multilingual SentencePiece vocab. One processor
# is enough, but we load by name for clarity / future-proofing.

_src_sp_path = os.path.join(_MODEL_DIR, "model.SRC")
_tgt_sp_path = os.path.join(_MODEL_DIR, "model.TGT")

if not os.path.exists(_src_sp_path):
    raise FileNotFoundError("Missing model.SRC: " + _src_sp_path)

if not os.path.exists(_tgt_sp_path):
    raise FileNotFoundError("Missing model.TGT: " + _tgt_sp_path)

_src_sp = spm.SentencePieceProcessor()
_src_sp.load(_src_sp_path)

_tgt_sp = spm.SentencePieceProcessor()
_tgt_sp.load(_tgt_sp_path)

print("SentencePiece models loaded, piece count:", _src_sp.get_piece_size())


# ============================================================
# GENERATION CONFIG (decoder start id read dynamically, not guessed)
# ============================================================

_generation_config = _load_json("generation_config.json")

# IndicTrans2 generation_config.json uses decoder_start_token_id=2,
# which is the EOS token for this model family. Read it from the bundle
# instead of hard-coding it.

_DECODER_START_ID = int(_generation_config.get(
    "decoder_start_token_id",
    _TGT_EOS_ID
))

print("DECODER_START_ID:", _DECODER_START_ID)
print("EOS_ID:", int(_generation_config.get("eos_token_id", _TGT_EOS_ID)))
print("PAD_ID:", int(_generation_config.get("pad_token_id", _TGT_PAD_ID)))

if not (0 <= _DECODER_START_ID < _TGT_VOCAB_SIZE):
    raise RuntimeError(
        "decoder_start_token_id is outside the target vocabulary: "
        + str(_DECODER_START_ID)
    )


# ============================================================
# INDIC NMT PREPROCESSING
# ============================================================
#
# IndicTrans2's official inference pipeline does not feed raw Hindi
# directly into SentencePiece. It first normalizes punctuation/text
# and applies the Indic punctuation tokenizer.
#
# We reproduce the lightweight parts needed for Hindi here instead
# of adding another large runtime dependency to the Android app.

_MULTI_SPACE_RE = re.compile(r"[ ]{2,}")
_MULTI_DOTS_RE = re.compile(r"\.{2,}")
_END_BRACKET_SPACE_PUNC_RE = re.compile(r"\) ([\.!:?;,])")
_DIGIT_SPACE_PERCENT_RE = re.compile(r"(\d) %")
_DOUBLE_QUOT_PUNC_RE = re.compile(r"\"([,\.]+)")
_DIGIT_NBSP_DIGIT_RE = re.compile(r"(\d)\u00a0(\d)")

# Indic NLP's trivial tokenizer separates punctuation boundaries.
# These are the major punctuation characters used for Indic scripts,
# including Devanagari danda characters.
_INDIC_PUNCT_RE = re.compile(
    r"([!\"#$%&'()*+,\-./:;<=>?@\[\\\]^_`{|}~"
    r"\u0964\u0965\uAAF1\uAAF0\uABEB\uABEC\uABED\uABEE\uABEF"
    r"\u1C7E\u1C7F])"
)


def _normalize_hindi_text(text):
    """Apply the lightweight normalization used before IndicTrans2 SPM."""
    text = unicodedata.normalize("NFC", text)
    text = text.replace("\r", "").replace("\t", " ")

    # Same punctuation normalization used by IndicTrans2's
    # normalize_punctuation.py.
    text = (
        text
        .replace("(", " (")
        .replace(")", ") ")
        .replace("( ", "(")
        .replace(" )", ")")
        .replace(" :", ":")
        .replace(" ;", ";")
        .replace("`", "'")
        .replace("„", '"')
        .replace("“", '"')
        .replace("”", '"')
        .replace("–", "-")
        .replace("—", " - ")
        .replace("´", "'")
        .replace("‘", "'")
        .replace("‚", "'")
        .replace("’", "'")
        .replace("''", '"')
        .replace("´´", '"')
        .replace("…", "...")
        .replace("\u00a0«\u00a0", ' "')
        .replace("«\u00a0", '"')
        .replace("«", '"')
        .replace("\u00a0»\u00a0", '" ')
        .replace("\u00a0»", '"')
        .replace("»", '"')
        .replace("\u00a0%", "%")
        .replace("nº\u00a0", "nº ")
        .replace("\u00a0:", ":")
        .replace("\u00a0ºC", " ºC")
        .replace("\u00a0cm", " cm")
        .replace("\u00a0?", "?")
        .replace("\u00a0!", "!")
        .replace("\u00a0;", ";")
        .replace(",\u00a0", ", ")
    )

    text = _MULTI_SPACE_RE.sub(" ", text)
    text = _MULTI_DOTS_RE.sub(".", text)
    text = _END_BRACKET_SPACE_PUNC_RE.sub(r")\1", text)
    text = _DIGIT_SPACE_PERCENT_RE.sub(r"\1%", text)
    text = _DOUBLE_QUOT_PUNC_RE.sub(r'\1"', text)
    text = _DIGIT_NBSP_DIGIT_RE.sub(r"\1.\2", text)

    return text.strip()


def _indic_trivial_tokenize(text):
    """
    Lightweight equivalent of Indic NLP's trivial_tokenize_indic()
    for Devanagari/Hindi.
    """
    text = text.replace("\t", " ")
    tokenized = _INDIC_PUNCT_RE.sub(r" \1 ", text)
    tokenized = re.sub(r"[ ]+", " ", tokenized).strip()

    # Preserve number/date sequences such as 1,000 or 12/10/2026.
    number_pattern = re.compile(r"([0-9]+ [,.:/] )+[0-9]+")
    rebuilt = []
    previous = 0

    for match in number_pattern.finditer(tokenized):
        start = match.start()
        end = match.end()

        rebuilt.append(tokenized[previous:start])
        rebuilt.append(tokenized[start:end].replace(" ", ""))
        previous = end

    rebuilt.append(tokenized[previous:])
    return " ".join(" ".join(rebuilt).split())


def _preprocess_hindi(hindi_text):
    """Android-safe lightweight IndicTrans2 text preprocessing."""
    normalized = _normalize_hindi_text(hindi_text)
    tokenized = _indic_trivial_tokenize(normalized)
    print("Preprocessed Hindi:", tokenized)
    return tokenized


# ============================================================
# SOURCE TOKENIZE: text -> ids
# ============================================================

def _split_tags(tokens):
    """Split whitespace-split tokens into (tags, non_tags), preserving order."""

    tags = []
    non_tags = []

    for token in tokens:

        if token in SPECIAL_TAGS:
            tags.append(token)
        else:
            non_tags.append(token)

    return tags, non_tags


def _encode_src(tagged_text):
    """
    Convert an IndicTrans2 tagged sentence to source vocabulary IDs.

    This mirrors AI4Bharat's IndicTransTokenizer:
        [src_lang, tgt_lang] + SentencePiece(source_text) + EOS

    IMPORTANT: the language tags stay BEFORE the SentencePiece pieces.
    """

    parts = tagged_text.split(" ", 2)

    if len(parts) != 3:
        raise ValueError(
            "Expected '<src_lang> <tgt_lang> <sentence>', got: "
            + repr(tagged_text)
        )

    src_lang, tgt_lang, sentence = parts

    if src_lang not in SPECIAL_TAGS:
        raise ValueError("Unknown source language tag: " + src_lang)

    if tgt_lang not in SPECIAL_TAGS:
        raise ValueError("Unknown target language tag: " + tgt_lang)

    # The official tokenizer receives the already-preprocessed sentence and
    # then applies SentencePiece to only the sentence portion.
    sentence = _preprocess_hindi(sentence)
    pieces = _src_sp.encode(sentence, out_type=str)

    # DO NOT reorder these. AI4Bharat's tokenizer uses this exact order.
    all_tokens = [src_lang, tgt_lang] + pieces

    ids = []
    for token in all_tokens:
        token_id = int(_src_token_to_id.get(token, _UNK_ID))
        if token_id < 0 or token_id >= _SRC_VOCAB_SIZE:
            token_id = _UNK_ID
        ids.append(token_id)

    # IndicTransTokenizer.build_inputs_with_special_tokens() appends EOS.
    ids.append(_EOS_ID)

    print("Source pieces:", pieces[:40])
    print("Source IDs:", ids[:60])

    return ids


# ============================================================
# TARGET DETOKENIZE: ids -> text
# ============================================================

# ============================================================
# ONNX RUNTIME
# ============================================================

_env = OrtEnvironment.getEnvironment()

_session_options = jclass(
    "ai.onnxruntime.OrtSession$SessionOptions"
)()

_encoder_path = os.path.join(_MODEL_DIR, "encoder_model.onnx")
_decoder_path = os.path.join(_MODEL_DIR, "decoder_model.onnx")
_decoder_with_past_path = os.path.join(
    _MODEL_DIR,
    "decoder_with_past_model.onnx"
)

_encoder = _env.createSession(
    _encoder_path,
    _session_options
)

_decoder = _env.createSession(
    _decoder_path,
    _session_options
)

_decoder_with_past = _env.createSession(
    _decoder_with_past_path,
    _session_options
)

print("ONNX models loaded successfully")


# ============================================================
# TENSOR HELPERS
# ============================================================

def _java_to_numpy(value):
    return np.asarray(value)

# ============================================================
# ANDROID  (add these two imports near your existing jclass() calls)
# ============================================================

ByteBuffer = jclass("java.nio.ByteBuffer")
ByteOrder = jclass("java.nio.ByteOrder")

_NATIVE_ORDER = ByteOrder.nativeOrder()


# ============================================================
# TENSOR HELPER (replaces the old _tensor() function)
# ============================================================
#
# NumPy arrays passed directly into an overloaded Java method (like
# OnnxTensor.createTensor) can crash Chaquopy's overload resolution —
# it falls back to building a generic Object[] and fails boxing NumPy
# scalars (this is a known Chaquopy limitation, see chaquo/chaquopy#976).
#
# Fix: convert to raw bytes -> direct ByteBuffer -> typed Buffer view,
# then call the (env, Buffer, shape) overload, which is unambiguous
# and is also ONNX Runtime's recommended zero-copy path.

def _tensor(np_array):

    np_array = np.ascontiguousarray(np_array)

    shape = [int(d) for d in np_array.shape]

    raw_bytes = np_array.tobytes()

    byte_buffer = ByteBuffer.allocateDirect(len(raw_bytes))
    byte_buffer.order(_NATIVE_ORDER)
    byte_buffer.put(bytearray(raw_bytes))
    byte_buffer.rewind()

    if np_array.dtype == np.int64:
        typed_buffer = byte_buffer.asLongBuffer()

    elif np_array.dtype == np.float32:
        typed_buffer = byte_buffer.asFloatBuffer()

    elif np_array.dtype == np.int32:
        typed_buffer = byte_buffer.asIntBuffer()

    elif np_array.dtype == np.float64:
        typed_buffer = byte_buffer.asDoubleBuffer()

    else:
        raise RuntimeError(
            "Unsupported numpy dtype for tensor: " + str(np_array.dtype)
        )

    return OnnxTensor.createTensor(_env, typed_buffer, shape)

# ============================================================
# ENCODER
# ============================================================

def _run_encoder(input_ids, attention_mask):

    inputs = HashMap()

    input_names = [
        str(x) for x in _encoder.getInputNames().toArray()
    ]

    print("Encoder inputs:", input_names)

    for name in input_names:

        if name == "input_ids":
            inputs.put(name, _tensor(input_ids))

        elif name == "attention_mask":
            inputs.put(name, _tensor(attention_mask))

    result = _encoder.run(inputs)

    try:
        hidden = _java_to_numpy(result.get(0).getValue())
    finally:
        result.close()

    return hidden


# ============================================================
# DECODER
# ============================================================
#
# IMPORTANT:
# IndicTrans2 ONNX is exported as two decoder graphs:
#
#   decoder_model.onnx
#       First decoding step. Produces logits + KV cache.
#
#   decoder_with_past_model.onnx
#       Every later decoding step. Takes the KV cache from the
#       previous step and only receives the newly generated token.
#
# The previous implementation repeatedly called decoder_model.onnx
# with the entire generated prefix. That is NOT the intended ONNX
# inference path and can produce bad/repetitive decoding.
#
# This implementation follows the ONNX inference helper used for
# this IndicTrans2 ONNX bundle: first decoder -> cached KV ->
# decoder_with_past for subsequent tokens.
# ============================================================

_decoder_input_names = [
    str(x) for x in _decoder.getInputNames().toArray()
]

_decoder_output_names = [
    str(x) for x in _decoder.getOutputNames().toArray()
]

_decoder_past_input_names = [
    str(x) for x in _decoder_with_past.getInputNames().toArray()
]

_decoder_past_output_names = [
    str(x) for x in _decoder_with_past.getOutputNames().toArray()
]

print("Decoder input names:", _decoder_input_names)
print("Decoder output names:", _decoder_output_names)
print("Decoder-with-past input names:", _decoder_past_input_names)
print("Decoder-with-past output names:", _decoder_past_output_names)

# First decoder output = logits.
# The remaining outputs are four KV tensors per transformer layer:
# decoder key, decoder value, encoder key, encoder value.
if (len(_decoder_output_names) - 1) % 4 != 0:
    raise RuntimeError(
        "Unexpected decoder output count: "
        + str(len(_decoder_output_names))
        + ". Expected 1 logits output + 4 KV outputs per layer."
    )

_NUM_DECODER_LAYERS = (len(_decoder_output_names) - 1) // 4

print("Decoder layers:", _NUM_DECODER_LAYERS)


def _run_first_decoder(
    decoder_input_ids,
    encoder_hidden_states,
    encoder_attention_mask,
):
    """
    Run decoder_model.onnx for the first generated token.

    Returns:
        logits, past_outputs
    """

    inputs = HashMap()

    for name in _decoder_input_names:

        if name == "input_ids":
            inputs.put(
                name,
                _tensor(decoder_input_ids)
            )

        elif name == "decoder_input_ids":
            inputs.put(
                name,
                _tensor(decoder_input_ids)
            )

        elif name == "encoder_hidden_states":
            inputs.put(
                name,
                _tensor(encoder_hidden_states)
            )

        elif name == "encoder_attention_mask":
            inputs.put(
                name,
                _tensor(encoder_attention_mask)
            )

        elif name == "attention_mask":
            # Some exported graphs expose a decoder attention mask.
            mask = np.ones(
                decoder_input_ids.shape,
                dtype=np.int64
            )

            inputs.put(
                name,
                _tensor(mask)
            )

        else:
            print(
                "WARNING: Unhandled first-decoder input:",
                name
            )

    result = _decoder.run(inputs)

    try:
        logits = _java_to_numpy(
            result.get(0).getValue()
        )

        past_outputs = []

        for i in range(1, len(_decoder_output_names)):
            past_outputs.append(
                _java_to_numpy(
                    result.get(i).getValue()
                )
            )

    finally:
        result.close()

    return logits, past_outputs


def _run_decoder_with_past(
    decoder_input_ids,
    encoder_attention_mask,
    past_outputs,
):
    """
    Run decoder_with_past_model.onnx for subsequent tokens.

    The ONNX export names the cache inputs:
        past_key_values.{layer}.decoder.key
        past_key_values.{layer}.decoder.value
        past_key_values.{layer}.encoder.key
        past_key_values.{layer}.encoder.value

    The first decoder returns these tensors in exactly that same
    per-layer order.
    """

    expected_past_count = _NUM_DECODER_LAYERS * 4

    if len(past_outputs) != expected_past_count:
        raise RuntimeError(
            "Unexpected KV cache count: "
            + str(len(past_outputs))
            + "; expected "
            + str(expected_past_count)
        )

    inputs = HashMap()

    for name in _decoder_past_input_names:

        if name == "input_ids":
            inputs.put(
                name,
                _tensor(decoder_input_ids)
            )

        elif name == "decoder_input_ids":
            inputs.put(
                name,
                _tensor(decoder_input_ids)
            )

        elif name == "encoder_attention_mask":
            inputs.put(
                name,
                _tensor(encoder_attention_mask)
            )

        elif name.startswith("past_key_values."):

            # Parse:
            # past_key_values.<layer>.<side>.<key/value>
            #
            # Example:
            # past_key_values.0.decoder.key

            parts = name.split(".")

            if len(parts) != 4:
                raise RuntimeError(
                    "Unexpected past-cache input name: "
                    + name
                )

            layer_index = int(parts[1])
            side = parts[2]
            kind = parts[3]

            if side == "decoder" and kind == "key":
                offset = 0

            elif side == "decoder" and kind == "value":
                offset = 1

            elif side == "encoder" and kind == "key":
                offset = 2

            elif side == "encoder" and kind == "value":
                offset = 3

            else:
                raise RuntimeError(
                    "Unexpected past-cache input name: "
                    + name
                )

            cache_index = layer_index * 4 + offset

            inputs.put(
                name,
                _tensor(past_outputs[cache_index])
            )

        else:
            print(
                "WARNING: Unhandled decoder-with-past input:",
                name
            )

    result = _decoder_with_past.run(inputs)

    try:
        logits = _java_to_numpy(
            result.get(0).getValue()
        )

        past_outputs_next = []

        for i in range(1, len(_decoder_past_output_names)):
            past_outputs_next.append(
                _java_to_numpy(
                    result.get(i).getValue()
                )
            )

    finally:
        result.close()

    return logits, past_outputs_next


# ============================================================
# GREEDY GENERATION
# ============================================================
#
# Start with decoder_start_token_id.
# First token -> decoder_model.onnx.
# Every later token -> decoder_with_past_model.onnx.
#
# This is deliberately greedy for the Android prototype.
# It is much safer than the previous custom beam-search path and
# matches the reference ONNX helper for this model family.
# ============================================================

_MODEL_MAX_TARGET = int(
    _model_config.get(
        "max_target_positions",
        256
    )
)

MAX_LENGTH = min(
    128,
    _MODEL_MAX_TARGET
)


def _generate(
    encoder_hidden_states,
    encoder_attention_mask,
    max_length=MAX_LENGTH,
):
    decoder_input_ids = np.array(
        [[_DECODER_START_ID]],
        dtype=np.int64
    )

    output_ids = [
        _DECODER_START_ID
    ]

    past_outputs = None

    for step in range(max_length):

        if step == 0:

            logits, past_outputs = _run_first_decoder(
                decoder_input_ids,
                encoder_hidden_states,
                encoder_attention_mask,
            )

        else:

            logits, past_outputs = _run_decoder_with_past(
                decoder_input_ids,
                encoder_attention_mask,
                past_outputs,
            )

        # Expected:
        # [batch, sequence, vocabulary]
        if logits.ndim != 3:
            raise RuntimeError(
                "Unexpected decoder logits shape: "
                + str(logits.shape)
            )

        next_logits = logits[0, -1, :]

        if next_logits.shape[0] != _TGT_VOCAB_SIZE:
            raise RuntimeError(
                "Decoder vocabulary mismatch: logits have "
                + str(next_logits.shape[0])
                + " classes, but dict.TGT.json has "
                + str(_TGT_VOCAB_SIZE)
                + ". Make sure all ONNX/tokenizer files "
                + "come from the same checkpoint."
            )

        next_id = int(
            np.argmax(next_logits)
        )

        output_ids.append(next_id)

        print(
            "Decode step",
            step,
            "-> token",
            next_id,
            "piece =",
            _tgt_id_to_token.get(
                next_id,
                _UNK_TOKEN
            )
        )

        # Stop exactly at EOS.
        if next_id == _TGT_EOS_ID:
            break

        # PAD should never normally be generated, but stopping here
        # prevents an accidental padding loop.
        if next_id == _TGT_PAD_ID:
            break

        # IMPORTANT:
        # After the first step, decoder_with_past expects ONLY the
        # newly generated token, not the complete generated prefix.
        decoder_input_ids = np.array(
            [[next_id]],
            dtype=np.int64
        )

    return output_ids


# ============================================================
# TARGET DETOKENIZE
# ============================================================

def _decode_tgt(ids):
    """Decode target IDs using dict.TGT.json token strings."""
    pieces = []
    special_ids = {_TGT_PAD_ID, _TGT_EOS_ID, _TGT_BOS_ID}
    safe_ids = []

    for raw_id in ids:
        token_id = int(raw_id)
        if token_id < 0 or token_id >= _TGT_VOCAB_SIZE:
            token_id = _TGT_UNK_ID
        safe_ids.append(token_id)
        if token_id in special_ids:
            continue
        piece = _tgt_id_to_token.get(token_id, _UNK_TOKEN)
        if piece in SPECIAL_TAGS:
            continue
        pieces.append(piece)

    if not pieces:
        return ""

    text = "".join(pieces).replace("\u2581", " ").strip()
    text = unicodedata.normalize("NFC", text)
    text = re.sub(r"\s+([।॥.!?,;:])", r"\1", text)

    print("Target IDs:", safe_ids[:80])
    print("Target pieces:", pieces[:80])
    print("Raw target text:", text)
    return text.strip()

# ============================================================
# TRANSLATION
# ============================================================

def translate(hindi_text):

    if hindi_text is None:
        return ""

    hindi_text = str(hindi_text).strip()

    if not hindi_text:
        return ""

    print("============================================================")
    print("Translating:", hindi_text)

    # --------------------------------------------------------
    # VERIFIED DICTIONARY FIRST
    # --------------------------------------------------------

    dictionary_result = _dictionary_lookup(hindi_text)

    print("Dictionary lookup result:", repr(dictionary_result))

    if dictionary_result:
        print("Local dictionary hit for:", hindi_text)

        print(
            "Dictionary match:",
            hindi_text,
            "->",
            dictionary_result
        )

        return dictionary_result.strip()

    # --------------------------------------------------------
    # INDIC TRANS2 TAGGED INPUT
    # --------------------------------------------------------

    tagged_text = (
        "hin_Deva sat_Olck "
        + hindi_text
    )

    print(
        "Tagged input:",
        tagged_text
    )

    # --------------------------------------------------------
    # TOKENIZE
    # --------------------------------------------------------

    source_ids = _encode_src(
        tagged_text
    )

    print(
        "Source token count:",
        len(source_ids)
    )

    print(
        "Source IDs:",
        source_ids
    )

    if not source_ids:
        return ""

    _MODEL_MAX_SOURCE = int(
        _model_config.get(
            "max_source_positions",
            256
        )
    )

    if len(source_ids) > _MODEL_MAX_SOURCE:

        raise ValueError(
            "Input is too long for this IndicTrans2 checkpoint: "
            + str(len(source_ids))
            + " tokens; maximum is "
            + str(_MODEL_MAX_SOURCE)
            + ". Split the text into shorter sentences."
        )

    # --------------------------------------------------------
    # INPUT TENSORS
    # --------------------------------------------------------

    input_ids = np.array(
        [source_ids],
        dtype=np.int64
    )

    attention_mask = np.ones(
        input_ids.shape,
        dtype=np.int64
    )

    # --------------------------------------------------------
    # ENCODER
    # --------------------------------------------------------

    print("Running encoder...")

    encoder_hidden_states = _run_encoder(
        input_ids,
        attention_mask
    )

    print(
        "Encoder output shape:",
        encoder_hidden_states.shape
    )

    # --------------------------------------------------------
    # DECODER
    # --------------------------------------------------------

    print(
        "Running decoder with KV cache..."
    )

    output_ids = _generate(
        encoder_hidden_states,
        attention_mask,
        max_length=MAX_LENGTH,
    )

    if len(output_ids) <= 1:
        print(
            "WARNING: decoder generated no target tokens."
        )
        return ""

    # Remove decoder start token.
    output_ids = output_ids[1:]

    # Remove terminal special tokens.
    output_ids = [
        token_id
        for token_id in output_ids
        if token_id not in (
            _TGT_EOS_ID,
            _TGT_PAD_ID,
        )
    ]

    # --------------------------------------------------------
    # DETOKENIZE
    # --------------------------------------------------------

    result = _decode_tgt(
        output_ids
    )

    print(
        "Translation:",
        result
    )

    print("============================================================")

    return result.strip()
