r"""Compare Claude models on saved scanner captures (known cards) - accuracy and cost.

Run from your own shell with the key in the environment (never paste it anywhere):
    $env:ANTHROPIC_API_KEY = "<your key>"
    python scripts\claude_scan_bakeoff.py [captures_dir]

captures_dir defaults to the scratchpad folder holding the pulled scan_debug_*.jpg files.
Sends the same downscaled JPEG (max 1024px, quality 80) and the same prompt the app sends to Gemini.
"""
import base64
import io
import json
import os
import sys

import anthropic
from PIL import Image

DEFAULT_DIR = (r"C:\Users\SkylerMayday\AppData\Local\Temp\claude\D--Claude-Projects-PokedexBinderV2"
               r"\a0d84467-8aa4-423d-93fa-af5b697928f9\scratchpad\scans\files")

# $ per 1M tokens (input, output) from the claude-api skill's cached table, 2026-06-04.
MODELS = {
    "claude-haiku-4-5": (1.00, 5.00),
    "claude-sonnet-4-6": (3.00, 15.00),
}

# file stamp -> (name contains, number, setTotal, language). Confirmed by Skyler / TCGdex lookups.
TRUTH = {
    "1790789282014": ("Castform", "116", "172", "en"),
    "1790789302596": ("Furfrou", "126", "198", "en"),
    "1790789381468": ("Kyurem", "11", "64", "ja"),
    "1790789403873": ("Wishiwashi", "21", "76", "ja"),
    "1790789442453": ("Klawf", "88", "78", "ja"),
    "1790789464283": ("Malamar", "48", "76", "ja"),
    "1790831418012": ("Kyurem", "11", "64", "ja"),
}

PROMPT = (
    "Analyze this Pokémon TCG card. The card may be in any language. "
    "Return ONLY a JSON object with no markdown:\n"
    '{"name":"Pokémon name in English regardless of card language, or null",'
    '"number":"card number without leading zeros or null",'
    '"setTotal":"total cards in set or null",'
    '"hp":"HP value as digits only or null",'
    '"artist":"illustrator name or null",'
    '"dexNumber":"National Pokédex number as integer or null",'
    '"language":"ISO 639-1 code of the language the card is printed in (e.g. en, ja, fr), or null",'
    '"printedName":"card name exactly as printed, in its printed language and script, or null"}'
)


def encode(path):
    im = Image.open(path).convert("RGB")
    s = 1024 / max(im.size)
    if s < 1:
        im = im.resize((int(im.width * s), int(im.height * s)), Image.LANCZOS)
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=80)
    return base64.b64encode(buf.getvalue()).decode()


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_DIR
    client = anthropic.Anthropic()  # reads ANTHROPIC_API_KEY
    totals = {m: [0, 0, 0] for m in MODELS}  # in tokens, out tokens, correct fields
    for stamp, (name, number, total, lang) in TRUTH.items():
        path = os.path.join(folder, f"scan_debug_{stamp}.jpg")
        if not os.path.exists(path):
            print("missing", path)
            continue
        img = encode(path)
        print(f"\n== {stamp}  expect {name} {number}/{total} {lang}")
        for model in MODELS:
            r = client.messages.create(
                model=model, max_tokens=300,
                messages=[{"role": "user", "content": [
                    {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": img}},
                    {"type": "text", "text": PROMPT}]}])
            text = r.content[0].text.strip().removeprefix("```json").removeprefix("```").removesuffix("```").strip()
            try:
                d = json.loads(text)
            except json.JSONDecodeError:
                d = {}
            ok = [
                name.lower() in str(d.get("name") or "").lower() or name.lower() in str(d.get("printedName") or "").lower(),
                str(d.get("number")).lstrip("0") == number,
                str(d.get("setTotal")) == total,
                str(d.get("language") or "").lower() == lang,
            ]
            t = totals[model]
            t[0] += r.usage.input_tokens
            t[1] += r.usage.output_tokens
            t[2] += sum(ok)
            print(f"  {model:18s} name/num/total/lang ok={ok}  got {d.get('name')!r} {d.get('number')}/{d.get('setTotal')} {d.get('language')}  tokens in={r.usage.input_tokens} out={r.usage.output_tokens}")
    n = len(TRUTH)
    print("\n== summary (fields correct out of %d)" % (n * 4))
    for model, (i, o, c) in totals.items():
        pin, pout = MODELS[model]
        cost = (i * pin + o * pout) / 1e6
        print(f"  {model:18s} {c}/{n*4} fields, {i} in / {o} out tokens, ${cost:.4f} total, ${cost/n:.4f} per scan")


if __name__ == "__main__":
    main()
