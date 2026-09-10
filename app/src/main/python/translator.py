import os
import json
import re
import unicodedata
import numpy as np

import sentencepiece as spm
from java import jclass


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

print("SRC vocab size:", len(_src_token_to_id))
print("TGT vocab size:", len(_tgt_token_to_id))


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

_TGT_PAD_ID = _tgt_token_to_id[_PAD_TOKEN]
_TGT_EOS_ID = _tgt_token_to_id[_EOS_TOKEN]

print("BOS:", _BOS_ID, "PAD:", _PAD_ID, "EOS:", _EOS_ID, "UNK:", _UNK_ID)


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

# VERIFY: fairseq-derived seq2seq models (this lineage) conventionally
# start decoding from the eos id, not from <s>. We prefer whatever
# generation_config.json says; fall back to EOS (fairseq convention)
# rather than BOS if the key is missing.

_DECODER_START_ID = _generation_config.get(
    "decoder_start_token_id",
    _TGT_EOS_ID
)

print("DECODER_START_ID:", _DECODER_START_ID)


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
    """
    Prepare Hindi in the same general order as IndicTrans2:
      normalization -> Indic punctuation tokenization.
    """
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
    tagged_text: "hin_Deva sat_Olck <the actual sentence>"
    Returns: list of vocab ids (tags looked up directly, rest via SentencePiece)
    """

    tokens = tagged_text.split(" ")

    tags, non_tags = _split_tags(tokens)

    rest_text = " ".join(non_tags)

    # IndicTrans2 preprocesses the Hindi sentence before SPM.
    # Language tags are already removed above, so only the actual
    # Hindi sentence is normalized/tokenized here.
    rest_text = _preprocess_hindi(rest_text)

    pieces = _src_sp.encode(rest_text, out_type=str)

    all_tokens = tags + pieces

    ids = [
        _src_token_to_id.get(tok, _UNK_ID)
        for tok in all_tokens
    ]

    # VERIFY: fairseq convention appends </s> at the end of the
    # encoder input. If translations look truncated/off by one,
    # try removing this line first.
    ids.append(_EOS_ID)

    return ids


# ============================================================
# TARGET DETOKENIZE: ids -> text
# ============================================================

def _decode_tgt(ids):

    pieces = []

    for token_id in ids:

        if token_id in (_TGT_PAD_ID, _TGT_EOS_ID, _BOS_ID):
            continue

        piece = _tgt_id_to_token.get(token_id)

        if piece is not None and piece not in SPECIAL_TAGS:
            pieces.append(piece)

    if not pieces:
        return ""

    # CONFIRMED: target-side detokenization is plain string
    # concatenation + replacing the SentencePiece "▁" marker with
    # a space — not a call into sp.decode().
    text = "".join(pieces).replace("\u2581", " ").strip()

    return text


# ============================================================
# ONNX RUNTIME
# ============================================================

_env = OrtEnvironment.getEnvironment()

_session_options = jclass(
    "ai.onnxruntime.OrtSession$SessionOptions"
)()

_encoder_path = os.path.join(_MODEL_DIR, "encoder_model.onnx")
_decoder_path = os.path.join(_MODEL_DIR, "decoder_model.onnx")

_encoder = _env.createSession(_encoder_path, _session_options)
_decoder = _env.createSession(_decoder_path, _session_options)

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

def _run_decoder(decoder_input_ids, encoder_hidden_states, encoder_attention_mask):

    inputs = HashMap()

    input_names = [
        str(x) for x in _decoder.getInputNames().toArray()
    ]

    print("Decoder inputs:", input_names)

    for name in input_names:

        if name == "input_ids":
            inputs.put(name, _tensor(decoder_input_ids))

        elif name == "decoder_input_ids":
            inputs.put(name, _tensor(decoder_input_ids))

        elif name == "encoder_hidden_states":
            inputs.put(name, _tensor(encoder_hidden_states))

        elif name == "encoder_attention_mask":
            inputs.put(name, _tensor(encoder_attention_mask))

        elif name == "attention_mask":

            mask = np.ones(decoder_input_ids.shape, dtype=np.int64)
            inputs.put(name, _tensor(mask))

    result = _decoder.run(inputs)

    try:
        logits = _java_to_numpy(result.get(0).getValue())
    finally:
        result.close()

    return logits


# ============================================================
# BEAM SEARCH DECODER
# ============================================================
#
# IndicTrans2's official CT2 inference uses beam_size=5.
# The previous Android implementation used greedy argmax decoding,
# which could fall into long repetition loops.
#
# This implementation keeps the same ONNX encoder/decoder but runs
# all active beams together as one batch. That avoids running the
# decoder separately for every beam.
# ============================================================

BEAM_SIZE = 5
MAX_LENGTH = 64
LENGTH_PENALTY = 1.0


def _log_softmax(x):
    """
    Numerically stable log-softmax for a 1-D NumPy array.
    """
    x = np.asarray(x, dtype=np.float64)

    max_x = np.max(x)
    shifted = x - max_x

    log_sum_exp = max_x + np.log(np.sum(np.exp(shifted)))

    return x - log_sum_exp


def _repeat_encoder_for_beams(encoder_hidden_states, beam_count):
    """
    Repeat encoder hidden states across the beam dimension.

    Input:
        [1, source_length, hidden_size]

    Output:
        [beam_count, source_length, hidden_size]
    """
    if encoder_hidden_states.ndim != 3:
        raise RuntimeError(
            "Unexpected encoder hidden-state shape: "
            + str(encoder_hidden_states.shape)
        )

    return np.repeat(
        encoder_hidden_states,
        beam_count,
        axis=0
    )


def _beam_search(
    encoder_hidden_states,
    encoder_attention_mask,
    beam_size=BEAM_SIZE,
    max_length=MAX_LENGTH,
    length_penalty=LENGTH_PENALTY,
):
    """
    Beam-search decoding for the ONNX IndicTrans2 decoder.

    Each beam is represented as:
        (token_ids, cumulative_log_probability, finished)

    token_ids includes the decoder start token.
    """

    # --------------------------------------------------------
    # Initial beam
    # --------------------------------------------------------

    beams = [
        ([_DECODER_START_ID], 0.0, False)
    ]

    print(
        "Beam search:",
        "beam_size =", beam_size,
        "max_length =", max_length
    )

    for step in range(max_length):

        active_beams = [
            beam for beam in beams
            if not beam[2]
        ]

        finished_beams = [
            beam for beam in beams
            if beam[2]
        ]

        # Nothing left to expand.
        if not active_beams:
            break

        # ----------------------------------------------------
        # Batch all active beams into one decoder call
        # ----------------------------------------------------

        decoder_input_ids = np.array(
            [beam[0] for beam in active_beams],
            dtype=np.int64
        )

        beam_count = len(active_beams)

        beam_encoder_hidden = _repeat_encoder_for_beams(
            encoder_hidden_states,
            beam_count
        )

        beam_attention_mask = np.repeat(
            encoder_attention_mask,
            beam_count,
            axis=0
        )

        logits = _run_decoder(
            decoder_input_ids,
            beam_encoder_hidden,
            beam_attention_mask
        )

        if logits.ndim == 3:
            next_logits = logits[:, -1, :]
        elif logits.ndim == 2:
            next_logits = logits
        else:
            raise RuntimeError(
                "Unexpected decoder output shape: "
                + str(logits.shape)
            )

        # ----------------------------------------------------
        # Expand every active beam
        # ----------------------------------------------------

        candidates = list(finished_beams)

        for beam_index, beam in enumerate(active_beams):

            token_ids, score, _ = beam

            log_probs = _log_softmax(
                next_logits[beam_index]
            )

            # Only the best beam_size tokens from each beam are
            # needed to construct the next beam set.
            top_k = min(
                beam_size,
                log_probs.shape[0]
            )

            top_ids = np.argpartition(
                -log_probs,
                top_k - 1
            )[:top_k]

            top_ids = top_ids[
                np.argsort(-log_probs[top_ids])
            ]

            for token_id in top_ids:

                token_id = int(token_id)

                new_score = (
                    score + float(log_probs[token_id])
                )

                if token_id == _TGT_EOS_ID:
                    new_tokens = token_ids.copy()
                    finished = True

                elif token_id == _TGT_PAD_ID:
                    new_tokens = token_ids.copy()
                    finished = True

                else:
                    new_tokens = token_ids + [token_id]
                    finished = False

                candidates.append(
                    (
                        new_tokens,
                        new_score,
                        finished
                    )
                )

        # ----------------------------------------------------
        # Keep the best cumulative-score beams
        # ----------------------------------------------------

        candidates.sort(
            key=lambda beam: beam[1],
            reverse=True
        )

        beams = candidates[:beam_size]

        # ----------------------------------------------------
        # Logging
        # ----------------------------------------------------

        print(
            "Beam step",
            step,
            "active:",
            sum(1 for b in beams if not b[2]),
            "finished:",
            sum(1 for b in beams if b[2])
        )

        for beam_index, beam in enumerate(beams):
            print(
                "  Beam",
                beam_index,
                "score:",
                round(beam[1], 4),
                "length:",
                len(beam[0]) - 1,
                "last:",
                beam[0][-1]
            )

        # ----------------------------------------------------
        # Early stopping
        # ----------------------------------------------------
        #
        # Once every retained beam has reached EOS/PAD, there
        # is nothing left to decode.
        # ----------------------------------------------------

        if all(beam[2] for beam in beams):
            break

    # --------------------------------------------------------
    # Select final hypothesis
    # --------------------------------------------------------
    #
    # IMPORTANT:
    # The previous version normalized the score by raw token
    # length and could therefore select a very long repetitive
    # unfinished beam over a much better completed translation.
    #
    # For this Android implementation, a completed EOS/PAD beam
    # is preferred. Among completed beams, use the highest
    # cumulative log-probability. If none completed, use the
    # highest-scoring active beam.
    # --------------------------------------------------------

    finished = [
        beam for beam in beams
        if beam[2]
    ]

    if finished:
        best_beam = max(
            finished,
            key=lambda beam: beam[1]
        )
        selection_type = "finished"
    else:
        best_beam = max(
            beams,
            key=lambda beam: beam[1]
        )
        selection_type = "active"

    best_tokens = best_beam[0]

    # Remove decoder start token.
    output_ids = best_tokens[1:]

    # Remove EOS/PAD if they were retained in the sequence.
    output_ids = [
        token_id
        for token_id in output_ids
        if token_id not in (
            _TGT_EOS_ID,
            _TGT_PAD_ID,
        )
    ]

    print(
        "Selected beam type:",
        selection_type
    )

    print(
        "Selected beam score:",
        best_beam[1]
    )

    print(
        "Selected token count:",
        len(output_ids)
    )

    print(
        "Selected token ids:",
        output_ids
    )

    return output_ids


# ============================================================
# TRANSLATION
# ============================================================

def translate(hindi_text):

    if hindi_text is None:
        return ""

    hindi_text = str(hindi_text).strip()

    if not hindi_text:
        return ""

    print("Translating:", hindi_text)

    # --------------------------------------------------------
    # IndicTrans2 tagged input format:
    # "{src_lang} {tgt_lang} {sentence}"
    # --------------------------------------------------------

    tagged_text = "hin_Deva sat_Olck " + hindi_text

    print("Tagged input:", tagged_text)

    # --------------------------------------------------------
    # TOKENIZE
    # --------------------------------------------------------

    source_ids = _encode_src(tagged_text)

    print("Source token count:", len(source_ids))
    print("Source ids:", source_ids)

    if not source_ids:
        return ""

    # --------------------------------------------------------
    # INPUT
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

    encoder_hidden_states = _run_encoder(
        input_ids,
        attention_mask
    )

    # --------------------------------------------------------
    # DECODER
    # --------------------------------------------------------

    output_ids = _beam_search(
        encoder_hidden_states=encoder_hidden_states,
        encoder_attention_mask=attention_mask,
        beam_size=BEAM_SIZE,
        max_length=MAX_LENGTH,
        length_penalty=LENGTH_PENALTY,
    )

    if not output_ids:
        return ""

    # --------------------------------------------------------
    # DETOKENIZE
    # --------------------------------------------------------

    result = _decode_tgt(output_ids)

    print("Translation:", result)

    return result.strip()