"""Rebuild the Android TV banner from the icon and outlined Avenir Next lettering.

Run from work/fast-tv with a Python environment containing fonttools.
The generated vector is committed with the local project; building the app needs no font.
"""
from copy import deepcopy
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from fontTools.ttLib import TTCollection
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app/src/main/res/drawable"
NS = "http://schemas.android.com/apk/res/android"
A = "{" + NS + "}"
ET.register_namespace("android", NS)
font = TTCollection("/System/Library/Fonts/Avenir Next.ttc").fonts[2]
glyphs = font.getGlyphSet()
cmap = font.getBestCmap()


def lettering(text, size, x, baseline, tracking=0):
    scale = size / font["head"].unitsPerEm
    pen = SVGPathPen(glyphs)
    for char in text:
        name = cmap[ord(char)]
        glyphs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, x, baseline)))
        x += font["hmtx"].metrics[name][0] * scale + tracking
    return re.sub(r"(-?\d+\.\d{3})\d+", r"\1", pen.getCommands())


banner = ET.Element("vector", {A + "width": "320dp", A + "height": "180dp",
                               A + "viewportWidth": "320", A + "viewportHeight": "180"})
ET.SubElement(banner, "path", {A + "fillColor": "#F5F2E9", A + "pathData": "M0,0h320v180h-320z"})
mark = ET.SubElement(banner, "group", {A + "translateX": "14", A + "translateY": "27.6",
                                     A + "scaleX": "1.2", A + "scaleY": "1.2"})
for child in ET.parse(DRAWABLE / "icon.xml").getroot():
    mark.append(deepcopy(child))
ET.SubElement(banner, "path", {A + "fillColor": "#234635",
                               A + "pathData": lettering("Claire’s", 40, 151, 85)})
ET.SubElement(banner, "path", {A + "fillColor": "#4A6C59",
                               A + "pathData": lettering("TV", 26, 152, 126, 3)})
ET.indent(banner, space="    ")
ET.ElementTree(banner).write(DRAWABLE / "banner.xml", encoding="unicode")


def export_svg(vector, path):
    svg = ET.Element("svg", {"xmlns": "http://www.w3.org/2000/svg",
                             "viewBox": "0 0 " + vector.get(A + "viewportWidth") + " " + vector.get(A + "viewportHeight")})
    def convert(element, parent):
        if element.tag == "group":
            transform = "translate(%s %s) scale(%s %s)" % (element.get(A + "translateX", "0"), element.get(A + "translateY", "0"), element.get(A + "scaleX", "1"), element.get(A + "scaleY", "1"))
            group = ET.SubElement(parent, "g", {"transform": transform})
            for child in element:
                convert(child, group)
        elif element.tag == "path":
            keys = {"fillColor": "fill", "pathData": "d", "strokeColor": "stroke", "strokeWidth": "stroke-width", "strokeLineCap": "stroke-linecap", "strokeLineJoin": "stroke-linejoin"}
            ET.SubElement(parent, "path", {dest: element.get(A + src) for src, dest in keys.items() if element.get(A + src) is not None})
    for element in vector:
        convert(element, svg)
    ET.ElementTree(svg).write(path, encoding="unicode")


export_svg(banner, ROOT / "design/banner.svg")
export_svg(ET.parse(DRAWABLE / "icon.xml").getroot(), ROOT / "design/icon.svg")
