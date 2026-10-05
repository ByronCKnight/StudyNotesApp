"""Generates test documents for StudyNotes into testdata/.

Requires PyMuPDF (`import pymupdf`). DOCX files are built by hand from raw OOXML (no python-docx).
Run:  python tools/make_fixtures.py
"""
import io
import os
import struct
import zipfile
import zlib

import pymupdf

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "testdata")

LOREM = (
    "Photosynthesis converts light energy into chemical energy stored in glucose. "
    "In the light-dependent reactions, water is split and oxygen is released, while ATP and NADPH "
    "are produced. The Calvin cycle then uses these to fix carbon dioxide into sugars. "
    "Factors such as light intensity, temperature and CO2 concentration limit the overall rate. "
)


def text_page(doc, title, n, width=595, height=842, rotate=0):
    page = doc.new_page(width=width, height=height)
    page.insert_text((56, 72), title, fontsize=20, fontname="helv")
    y = 110
    para = 0
    while y < height - 90:
        rect = pymupdf.Rect(56, y, width - 56, y + 120)
        page.insert_textbox(rect, f"{para + 1}. " + LOREM, fontsize=11, fontname="tiro", align=3)
        y += 132
        para += 1
    page.insert_text((width / 2 - 20, height - 30), f"Page {n}", fontsize=9, fontname="helv")
    if rotate:
        page.set_rotation(rotate)
    return page


def png_bytes(w, h, color_fn):
    raw = b"".join(b"\x00" + b"".join(bytes(color_fn(x, y)) for x in range(w)) for y in range(h))

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def gradient(x, y):
    return (40 + x % 200, 90 + y % 150, 200)


def make_lecture_notes():
    doc = pymupdf.open()
    for i in range(1, 13):
        if i == 5:
            text_page(doc, f"Chapter {i}: rotated page (/Rotate 90)", i, rotate=90)
        elif i == 7:
            text_page(doc, f"Chapter {i}: Letter landscape", i, width=792, height=612)
        else:
            page = text_page(doc, f"Chapter {i}: Photosynthesis", i)
            if i == 3:
                page.insert_image(pymupdf.Rect(150, 600, 445, 760), stream=png_bytes(120, 60, gradient))
            if i == 6:
                page.set_cropbox(pymupdf.Rect(40, 40, 555, 802))
    doc.set_metadata({"title": "Biology Lecture Notes"})
    doc.save(os.path.join(OUT, "Biology Lecture Notes.pdf"))


def make_existing_annotations():
    doc = pymupdf.open()
    page = text_page(doc, "Already annotated elsewhere", 1)
    hits = page.search_for("Calvin cycle")
    if hits:
        page.add_highlight_annot(hits[0])
    page.add_text_annot((520, 120), "A sticky note from another app")
    doc.save(os.path.join(OUT, "Annotated Elsewhere.pdf"))


def make_password():
    doc = pymupdf.open()
    text_page(doc, "Secret", 1)
    doc.save(os.path.join(OUT, "Password Protected.pdf"), encryption=pymupdf.PDF_ENCRYPT_AES_256,
             user_pw="study", owner_pw="owner")


def make_scanned():
    doc = pymupdf.open()
    page = doc.new_page(width=595, height=842)
    page.insert_image(page.rect, stream=png_bytes(150, 210, lambda x, y: (235, 235, 228) if (y // 6) % 3 else (60, 60, 60)))
    doc.save(os.path.join(OUT, "Scanned Handout.pdf"))


def make_textbook(pages=300):
    doc = pymupdf.open()
    for i in range(1, pages + 1):
        text_page(doc, f"Section {i}", i)
    doc.save(os.path.join(OUT, "Big Textbook (300 pages).pdf"), garbage=4, deflate=True)


# ----------------------------------------------------------------------------- DOCX

W_NS = ('xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
        'xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" '
        'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
        'xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture" '
        'xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"')


def run(text, rpr=""):
    return f'<w:r>{"<w:rPr>" + rpr + "</w:rPr>" if rpr else ""}<w:t xml:space="preserve">{text}</w:t></w:r>'


def para(content, ppr=""):
    return f'<w:p>{"<w:pPr>" + ppr + "</w:pPr>" if ppr else ""}{content}</w:p>'


def make_docx():
    body = []
    body.append(para(run("Cell Biology — Study Guide"), '<w:pStyle w:val="Title"/>'))
    body.append(para(run("1. The cell membrane"), '<w:pStyle w:val="Heading1"/>'))
    body.append(para(
        run("The membrane is a ") + run("phospholipid bilayer", "<w:b/>") + run(" with ")
        + run("embedded proteins", "<w:i/>") + run(". It is ") + run("selectively permeable", "<w:u w:val=\"single\"/>")
        + run(", meaning ") + run("some substances", '<w:highlight w:val="yellow"/>') + run(" cross freely while ")
        + run("others", '<w:color w:val="C00000"/>') + run(" need transport proteins. Water molecules (H")
        + run("2", '<w:vertAlign w:val="subscript"/>') + run("O) move by osmosis; area scales as r")
        + run("2", '<w:vertAlign w:val="superscript"/>') + run(".")
        + '<w:r><w:rPr><w:vertAlign w:val="superscript"/></w:rPr><w:footnoteReference w:id="1"/></w:r>',
        '<w:jc w:val="both"/><w:spacing w:after="160" w:line="276" w:lineRule="auto"/>'))
    body.append(para(run("Key transport mechanisms:"), '<w:spacing w:before="120"/>'))
    for t in ["Simple diffusion", "Facilitated diffusion", "Active transport (uses ATP)"]:
        body.append(para(run(t), '<w:pStyle w:val="ListParagraph"/><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr>'))
    body.append(para(run("Endocytosis and exocytosis"), '<w:pStyle w:val="ListParagraph"/><w:numPr><w:ilvl w:val="1"/><w:numId w:val="1"/></w:numPr>'))
    body.append(para(run("Steps of the sodium-potassium pump:"), '<w:spacing w:before="120"/>'))
    for t in ["3 Na+ bind inside", "ATP is hydrolysed", "Pump changes shape", "2 K+ bind outside"]:
        body.append(para(run(t), '<w:numPr><w:ilvl w:val="0"/><w:numId w:val="2"/></w:numPr>'))
    body.append(para(run("Term") + '<w:r><w:tab/></w:r>' + run("Definition (tab separated)")))
    body.append(para(run("Centred paragraph in Courier", '<w:rFonts w:ascii="Courier New" w:hAnsi="Courier New"/>'), '<w:jc w:val="center"/>'))
    body.append(para(run("Right aligned, 14pt, strike", '<w:sz w:val="28"/><w:strike/>'), '<w:jc w:val="right"/>'))
    body.append(para('<w:hyperlink r:id="rIdLink">' + run("Khan Academy: membranes", '<w:rStyle w:val="Hyperlink"/>') + "</w:hyperlink>"))
    body.append(para(run("Indented quote with a hanging first line that wraps across multiple lines to show the hanging indent working properly in the converter."),
                     '<w:ind w:left="720" w:right="720" w:hanging="360"/>'))
    # table
    rows = [("Organelle", "Function", "Found in"), ("Nucleus", "Holds DNA", "Plants & animals"),
            ("Chloroplast", "Photosynthesis", "Plants"), ("Mitochondrion", "Respiration (ATP)", "Plants & animals")]
    tbl = ['<w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/><w:tblW w:w="0" w:type="auto"/>'
           '<w:tblBorders><w:top w:val="single" w:sz="4"/><w:left w:val="single" w:sz="4"/><w:bottom w:val="single" w:sz="4"/>'
           '<w:right w:val="single" w:sz="4"/><w:insideH w:val="single" w:sz="4"/><w:insideV w:val="single" w:sz="4"/></w:tblBorders></w:tblPr>'
           '<w:tblGrid><w:gridCol w:w="2400"/><w:gridCol w:w="3600"/><w:gridCol w:w="3000"/></w:tblGrid>']
    for i, r in enumerate(rows):
        cells = []
        for c in r:
            shade = '<w:tcPr><w:shd w:val="clear" w:color="auto" w:fill="DCE3F5"/></w:tcPr>' if i == 0 else ""
            cells.append(f'<w:tc>{shade}{para(run(c, "<w:b/>" if i == 0 else ""))}</w:tc>')
        tbl.append("<w:tr>" + "".join(cells) + "</w:tr>")
    tbl.append('<w:tr><w:tc><w:tcPr><w:gridSpan w:val="3"/></w:tcPr>' + para(run("Merged row spanning all three columns"), '<w:jc w:val="center"/>') + "</w:tc></w:tr>")
    tbl.append("</w:tbl>")
    body.append("".join(tbl))
    # image
    body.append(para(
        '<w:r><w:drawing><wp:inline><wp:extent cx="3048000" cy="1524000"/><wp:docPr id="1" name="Picture 1"/>'
        '<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic>'
        '<pic:nvPicPr><pic:cNvPr id="1" name="diagram.png"/><pic:cNvPicPr/></pic:nvPicPr>'
        '<pic:blipFill><a:blip r:embed="rIdImg"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>'
        '<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="3048000" cy="1524000"/></a:xfrm><a:prstGeom prst="rect"/></pic:spPr>'
        '</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>', '<w:jc w:val="center"/>'))
    body.append(para(run("Figure 1: a simple diagram", "<w:i/>"), '<w:jc w:val="center"/>'))
    # page break then more content
    body.append(para('<w:r><w:br w:type="page"/></w:r>'))
    body.append(para(run("2. Cell division"), '<w:pStyle w:val="Heading1"/>'))
    body.append(para(run("2.1 Mitosis"), '<w:pStyle w:val="Heading2"/>'))
    for i in range(14):
        body.append(para(run(f"({i + 1}) " + LOREM), '<w:spacing w:after="120"/>'))
    body.append(para(run("Deleted text should not appear: ") + '<w:del w:id="9" w:author="x"><w:r><w:delText>GONE</w:delText></w:r></w:del>'
                     + '<w:ins w:id="10" w:author="x">' + run("inserted text should appear.") + "</w:ins>"))
    body.append('<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>')

    document = f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document {W_NS}><w:body>{"".join(body)}</w:body></w:document>'

    styles = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles {W_NS}>
<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri"/><w:sz w:val="22"/></w:rPr></w:rPrDefault>
<w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="240"/></w:pPr><w:rPr><w:sz w:val="52"/><w:color w:val="1F3864"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="80"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/><w:color w:val="2F5496"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Heading1"/><w:pPr><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:sz w:val="26"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/><w:pPr><w:ind w:left="720"/><w:contextualSpacing/></w:pPr></w:style>
<w:style w:type="character" w:styleId="Hyperlink"><w:name w:val="Hyperlink"/><w:rPr><w:color w:val="0563C1"/><w:u w:val="single"/></w:rPr></w:style>
<w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/></w:style>
</w:styles>'''

    numbering = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:numbering {W_NS}>
<w:abstractNum w:abstractNumId="0">
 <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl>
 <w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="o"/><w:pPr><w:ind w:left="1440" w:hanging="360"/></w:pPr></w:lvl>
</w:abstractNum>
<w:abstractNum w:abstractNumId="1">
 <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl>
 <w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="lowerLetter"/><w:lvlText w:val="%2)"/><w:pPr><w:ind w:left="1440" w:hanging="360"/></w:pPr></w:lvl>
</w:abstractNum>
<w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
<w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>
</w:numbering>'''

    footnotes = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:footnotes {W_NS}>
<w:footnote w:type="separator" w:id="-1"><w:p><w:r><w:separator/></w:r></w:p></w:footnote>
<w:footnote w:type="continuationSeparator" w:id="0"><w:p><w:r><w:continuationSeparator/></w:r></w:p></w:footnote>
<w:footnote w:id="1"><w:p><w:r><w:rPr><w:vertAlign w:val="superscript"/></w:rPr><w:footnoteRef/></w:r>{run(" Singer & Nicolson, fluid mosaic model (1972).")}</w:p></w:footnote>
</w:footnotes>'''

    rels = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
<Relationship Id="rIdNum" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>
<Relationship Id="rIdFoot" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footnotes" Target="footnotes.xml"/>
<Relationship Id="rIdImg" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/>
<Relationship Id="rIdLink" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://www.khanacademy.org" TargetMode="External"/>
</Relationships>'''

    content_types = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Default Extension="png" ContentType="image/png"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>
<Override PartName="/word/footnotes.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml"/>
</Types>'''

    root_rels = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>'''

    path = os.path.join(OUT, "Cell Biology Study Guide.docx")
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", content_types)
        z.writestr("_rels/.rels", root_rels)
        z.writestr("word/document.xml", document)
        z.writestr("word/styles.xml", styles)
        z.writestr("word/numbering.xml", numbering)
        z.writestr("word/footnotes.xml", footnotes)
        z.writestr("word/_rels/document.xml.rels", rels)
        z.writestr("word/media/image1.png", png_bytes(160, 80, gradient))

    # Minimal docx: no styles/numbering parts at all, no sectPr, so the converter default page size (A4).
    minimal = f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document {W_NS}><w:body>' \
              + para(run("Minimal document with no styles part.")) + para(run("Second paragraph.")) + "</w:body></w:document>"
    with zipfile.ZipFile(os.path.join(OUT, "Minimal.docx"), "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", content_types)
        z.writestr("_rels/.rels", root_rels)
        z.writestr("word/document.xml", minimal)

    # Not a real docx (corrupt) — converter must fail gracefully.
    with open(os.path.join(OUT, "Corrupt.docx"), "wb") as f:
        f.write(b"PK\x03\x04 this is not really a zip file")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    make_lecture_notes()
    make_existing_annotations()
    make_password()
    make_scanned()
    make_textbook()
    make_docx()
    for name in sorted(os.listdir(OUT)):
        print(f"{os.path.getsize(os.path.join(OUT, name)):>10}  {name}")
