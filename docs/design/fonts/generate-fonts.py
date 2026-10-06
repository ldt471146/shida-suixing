"""Make the bundled static instances from the official Google Fonts downloads.

Run with PYTHONDONTWRITEBYTECODE=1 and TEMP/TMP pointing at a D: directory.
Only fontTools is needed; downloads remain in the ignored .local/fonts/source.
"""

import argparse
import hashlib
import json
from pathlib import Path

import fontTools
from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont


def gb2312_codepoints():
    characters = set()
    for lead in range(0xA1, 0xF8):
        for trail in range(0xA1, 0xFF):
            try:
                characters.add(ord(bytes((lead, trail)).decode("gb2312")))
            except UnicodeDecodeError:
                pass
    return characters


def rename(font, family, style, license_text):
    names = font["name"]
    changed_ids = {1, 2, 3, 4, 6, 16, 17, 25, 13}
    names.names = [record for record in names.names if record.nameID not in changed_ids]
    postscript_name = family.replace(" ", "") + "-" + style
    replacement = {
        1: family,
        2: style,
        3: postscript_name + ";campus-static-subset;1",
        4: family + " " + style,
        6: postscript_name,
        16: family,
        17: style,
        13: license_text,
    }
    for name_id, value in replacement.items():
        names.setName(value, name_id, 3, 1, 0x409)
    names.setName("https://openfontlicense.org/", 14, 3, 1, 0x409)


def main():
    parser = argparse.ArgumentParser()
    root = Path(__file__).resolve().parents[3]
    parser.add_argument("--source-dir", type=Path, default=root / ".local/fonts/source")
    parser.add_argument("--output-dir", type=Path, default=root / "app/src/main/res/font")
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    license_dir = Path(__file__).parent
    gb2312 = gb2312_codepoints()
    requested_sc = gb2312 | set(range(0x20, 0x7F)) | set(range(0x3000, 0x3040))
    requested_sc |= set(range(0xFF01, 0xFF61)) | set(range(0xFFE0, 0xFFE7))
    requested_sc |= set(range(0x2010, 0x2028)) | {0xA0, 0xB7, 0x20AC, 0x2212}
    profiles = [
        ("NotoSansSC-variable.ttf", "notosanssc", "NotoSansSC-OFL.txt", "Campus Sans SC",
         [(400, "Regular", "campus_sans_regular"), (600, "Semibold", "campus_sans_semibold")], requested_sc),
        ("PlusJakartaSans-variable.ttf", "plusjakartasans", "PlusJakartaSans-OFL.txt", "Campus Latin",
         [(500, "Medium", "campus_latin_medium"), (700, "Bold", "campus_latin_bold")], None),
    ]
    manifest = {"retrieved": "2026-10-06", "fonttools_version": fontTools.__version__, "sources": [], "fonts": []}
    for source_name, source_folder, license_name, family, instances, requested in profiles:
        source_path = args.source_dir / source_name
        data = source_path.read_bytes()
        font = TTFont(source_path, recalcTimestamp=False)
        manifest["sources"].append({
            "filename": source_name,
            "url": "https://github.com/google/fonts/tree/main/ofl/" + source_folder,
            "sha256": hashlib.sha256(data).hexdigest(),
            "git_blob_sha1": hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest(),
            "copyright": font["name"].getDebugName(0),
        })
        if requested:
            available = set(font.getBestCmap())
            missing_gb2312 = gb2312 - available
            if missing_gb2312:
                raise ValueError("Source font lacks GB2312 characters: " + repr(sorted(missing_gb2312)))
            options = subset.Options()
            options.layout_features = ["*"]
            options.name_IDs = ["*"]
            options.name_languages = ["*"]
            options.name_legacy = True
            options.notdef_glyph = True
            options.notdef_outline = True
            options.recalc_timestamp = False
            subsetter = subset.Subsetter(options=options)
            subsetter.populate(unicodes=requested)
            subsetter.subset(font)
        for weight, style, resource in instances:
            static = instantiateVariableFont(font, {"wght": weight}, inplace=False, optimize=True)
            rename(static, family, style, (license_dir / license_name).read_text(encoding="utf-8"))
            destination = args.output_dir / (resource + ".ttf")
            static.save(destination)
            saved = TTFont(destination, checkChecksums=2)
            cmap = saved.getBestCmap()
            assert "fvar" not in saved
            assert saved["OS/2"].usWeightClass == weight
            assert saved["name"].getDebugName(13).startswith("Copyright")
            if requested:
                assert gb2312 <= set(cmap)
            entry = {
                "resource": resource, "filename": destination.name, "weight": weight,
                "bytes": destination.stat().st_size, "unicode_characters": len(cmap),
                "sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
                "family": family, "source": source_name,
            }
            manifest["fonts"].append(entry)
            print(json.dumps(entry, ensure_ascii=False), flush=True)
    (license_dir / "fonts.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
