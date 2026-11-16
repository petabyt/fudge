#!/usr/bin/env python3
"""Download missing drawable icons; pass --all to refresh existing ones.

Add icon names to the appropriate list below (without the style or _24.xml).
The lists distinguish the original Material Icons from Material Symbols, whose
designs can differ even when they have the same name. Requires Python 3 only.
"""

import argparse
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET


# Android Studio's baseline (filled) Material Icons.
BASELINE_ICONS = """
arrow_back bug_report data_array download fact_check folder_open grid_view help
landscape movie photo_camera question_mark settings settings_remote share stop
terminal usb view_list wifi_tethering
""".split()

# Android Studio's older outlined Material Icons.
OUTLINE_ICONS = """
camera info iso javascript watch_later
""".split()

# Android Studio's outlined Material Symbols.
OUTLINE_SYMBOLS = """
add archive arrow_back arrow_drop_down arrow_forward award_star battery_android_0
battery_android_frame_1 battery_android_frame_2 battery_android_frame_3
battery_android_frame_4 battery_android_frame_5 battery_android_frame_6
battery_android_frame_bolt battery_android_frame_full bluetooth buttons_alt
camera_video car_gear car_repair close connected_tv construction content_copy
delete deployed_code deselect developer_board device_thermostat devices_other
directions_car download_done dynamic_feed earbuds_2 equalizer exclamation
eyeglasses_2 feedback files fullscreen fullscreen_exit general_device
globe_location_pin headphones home humidity_percentage lightbulb_2 menu numbers
photo_camera photo_library play_circle save sd_card security select_all
shutter_speed sim_card_download smart_display sort speaker style timelapse
tools_power_drill tv usb user_attributes videocam videogame_asset watch wb_sunny
wifi refresh
""".split()

ANDROID = "http://schemas.android.com/apk/res/android"
SVG = "http://www.w3.org/2000/svg"
ROOT = "https://raw.githubusercontent.com/google/material-design-icons/master"
DRAWABLE = Path(__file__).resolve().parent / "composeResources" / "drawable"
ET.register_namespace("android", ANDROID)


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "drawable-icon-downloader"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def material_metadata():
    # Google's metadata supplies the category needed by the Material Icons URLs.
    data = fetch("https://fonts.google.com/metadata/icons").decode("utf-8")
    data = data[data.index("{"):]
    return {icon["name"]: icon for icon in json.loads(data)["icons"]}


def legacy_vector(name, style, metadata):
    icon = metadata.get(name)
    if not icon:
        raise ValueError("not found in Google Material Icons metadata")
    family = "materialicons" if style == "baseline" else "materialiconsoutlined"
    last_error = None
    for category in icon["categories"]:
        url = f"{ROOT}/src/{category}/{name}/{family}/24px.svg"
        try:
            svg = ET.fromstring(fetch(url))
            break
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            last_error = error
    else:
        raise ValueError(f"no {style} Material Icons SVG found: {last_error}")

    if svg.tag != f"{{{SVG}}}svg" or svg.get("viewBox") != "0 0 24 24":
        raise ValueError("unexpected SVG format or viewBox")
    vector = ET.Element("vector", {
        f"{{{ANDROID}}}width": "24dp", f"{{{ANDROID}}}height": "24dp",
        f"{{{ANDROID}}}viewportWidth": "24", f"{{{ANDROID}}}viewportHeight": "24",
        f"{{{ANDROID}}}tint": "#FFFFFF",
    })
    def convert(element, fill_rule=None):
        if element.tag == f"{{{SVG}}}g":
            if set(element.attrib) - {"fill-rule"}:
                raise ValueError("unsupported SVG group attributes")
            for child in element:
                convert(child, element.get("fill-rule", fill_rule))
            return
        if element.get("fill") == "none":
            return
        if element.get("fill") not in (None, "#000", "#000000", "black"):
            raise ValueError("unsupported SVG fill color")
        tag = element.tag.removeprefix(f"{{{SVG}}}")
        allowed = {"fill", "fill-rule", "clip-rule"}
        if tag == "path":
            allowed.add("d")
            data = element.get("d")
        elif tag == "polygon":
            allowed.add("points")
            points = element.get("points", "").split()
            data = "M" + " L".join(points) + " Z" if points else None
        elif tag == "circle":
            allowed.update({"cx", "cy", "r"})
            cx, cy, radius = (float(element.attrib[key]) for key in ("cx", "cy", "r"))
            if radius <= 0:
                raise ValueError("invalid SVG circle radius")
            data = f"M{cx-radius},{cy} a{radius},{radius} 0 1,0 {2*radius},0 a{radius},{radius} 0 1,0 {-2*radius},0 Z"
        else:
            raise ValueError(f"unsupported SVG element: {tag}")
        if set(element.attrib) - allowed or not data:
            raise ValueError("unsupported SVG attributes or missing path data")
        attrs = {f"{{{ANDROID}}}fillColor": "#FFFFFF", f"{{{ANDROID}}}pathData": data}
        fill_rule = element.get("fill-rule", fill_rule)
        if fill_rule == "evenodd":
            attrs[f"{{{ANDROID}}}fillType"] = "evenOdd"
        elif fill_rule not in (None, "nonzero"):
            raise ValueError("unsupported SVG fill rule")
        ET.SubElement(vector, "path", attrs)

    for child in svg:
        convert(child)
    return vector


def symbol_vector(name):
    url = f"{ROOT}/symbols/android/{name}/materialsymbolsoutlined/{name}_24px.xml"
    vector = ET.fromstring(fetch(url))
    vector.set(f"{{{ANDROID}}}tint", "#FFFFFF")
    for path in vector.iter("path"):
        path.set(f"{{{ANDROID}}}fillColor", "#FFFFFF")
    return vector


def save_vector(path, vector):
    if vector.tag != "vector" or not any(p.get(f"{{{ANDROID}}}pathData") for p in vector.iter("path")):
        raise ValueError("download is not a vector with path data")
    ET.indent(vector, space="    ")
    content = ET.tostring(vector, encoding="utf-8", xml_declaration=True) + b"\n"
    # Write beside the target so a failed download or write never truncates it.
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as file:
            temporary = Path(file.name)
            file.write(content)
        os.chmod(temporary, 0o644)
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--all", action="store_true", help="redownload even existing icons")
    parser.add_argument("--dry-run", action="store_true", help="show what would be downloaded")
    args = parser.parse_args()
    icons = [("baseline", name, False) for name in BASELINE_ICONS]
    icons += [("outline", name, False) for name in OUTLINE_ICONS]
    icons += [("outline", name, True) for name in OUTLINE_SYMBOLS]
    targets = set()
    for style, name, _ in icons:
        if not re.fullmatch(r"[a-z][a-z0-9_]*", name):
            parser.error(f"invalid icon name: {name!r}")
        filename = f"{style}_{name}_24.xml"
        if filename in targets:
            parser.error(f"duplicate output filename: {filename}")
        targets.add(filename)
    pending = [(style, name, symbol) for style, name, symbol in icons
               if args.all or not (DRAWABLE / f"{style}_{name}_24.xml").exists()]
    if args.dry_run:
        for style, name, _ in pending:
            print(f"Would download {style}_{name}_24.xml")
        print(f"{len(pending)} icon(s) to download")
        return 0
    if not pending:
        print("All listed icons already exist. Use --all to redownload them.")
        return 0
    DRAWABLE.mkdir(exist_ok=True)
    metadata = None
    metadata_error = None
    if any(not symbol for _, _, symbol in pending):
        try:
            metadata = material_metadata()
        except (OSError, ValueError, KeyError) as error:
            metadata_error = error
    failures = 0
    for style, name, symbol in pending:
        filename = f"{style}_{name}_24.xml"
        try:
            if not symbol and metadata is None:
                raise ValueError(f"could not load Material Icons metadata: {metadata_error}")
            vector = symbol_vector(name) if symbol else legacy_vector(name, style, metadata)
            save_vector(DRAWABLE / filename, vector)
            print(f"Downloaded {filename}")
        except (OSError, ValueError, ET.ParseError, KeyError) as error:
            print(f"Failed {filename}: {error}", file=sys.stderr)
            failures += 1
    print(f"Downloaded {len(pending) - failures}; failed {failures}.")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
