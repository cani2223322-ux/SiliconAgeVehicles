"""
Silicon Age: Vehicles - the handbook as web content (wave C, item 48: one content source for the book and the website).

The in-game handbook is written once, in src/main/resources/assets/siliconagevehicles/book/{ru,en}/*.txt. A server run with
-Dsva.bookexport=DIR (com.sva.book.BookExportSVA) resolves it against the game's own data and writes DIR/book.json (both
languages, the vehicles' numbers, the REGISTERED recipes, the checklists, the tutorial) and the PNG renders (DIR/schem: the
build schematics layer by layer; DIR/img: the book's pictures). This script turns that export into HTML fragments:

    python book_html.py EXPORT_DIR [OUT_DIR]     ->  OUT_DIR/book.html (all chapters), OUT_DIR/toc.html, OUT_DIR/<chapter>.html

The fragments are Russian with the English in data-en attributes (the site's convention: site.js swaps them in). As a
module (the site's tools/build_vehicles_book.py imports it) render(export, icon=..., asset=...) takes callbacks for the
item icons (the site's wiki icons) and the asset paths.

Run the export:  cd srv && java -Dsva.bookexport=C:/.../build/book_export -jar forge.jar nogui   (the jar in mods/)
"""
import html
import io
import json
import os
import re
import sys

FORMAT = 1


def esc(s):
    return html.escape(str(s), quote=True)


def inline(s):
    """Book text -> HTML: escaped, **bold** -> <b>."""
    out, bold = [], False
    for part in re.split(r"(\*\*)", s):
        if part == "**":
            out.append("</b>" if bold else "<b>")
            bold = not bold
        else:
            out.append(esc(part))
    if bold:
        out.append("</b>")
    return "".join(out)


def default_icon(stack, lang, size=32):
    """A slot with the item's name (no picture): the plain export's fallback."""
    if not stack:
        return '<span class="vb-slot vb-empty"></span>'
    name = stack.get(lang) or stack.get("en") or stack.get("reg", "?")
    count = stack.get("count", 1)
    return '<span class="vb-slot vb-noimg" title="%s">%s%s</span>' % (esc(name), esc(name[:2]), '<i>%d</i>' % count if count and count > 1 else "")


def default_asset(path):
    return path


class Renderer:
    def __init__(self, export, icon=default_icon, asset=default_asset):
        self.x = export
        self.icon = icon
        self.asset = asset

    # ------------------------------------------------------------- blocks

    def recipe(self, r, lang):
        if r.get("missing"):
            return '<p class="vb-missing">?</p>'
        w = r["w"]
        cells = "".join(self.icon(c, lang) for c in r["cells"])
        out = dict(r["out"])
        info = ""
        if r["kind"] == "wb":
            eu = r.get("eu", 0)
            sec = r.get("ticks", 0) // 20
            info = '<div class="vb-info">%s</div>' % (esc("{:,} EU, {} {}".format(eu, sec, "с" if lang == "ru" else "s").replace(",", " "))
                                                       if eu else esc("мгновенно" if lang == "ru" else "instant"))
        return ('<div class="vb-recipe"><div class="vb-cap">%s</div><div class="vb-row"><div class="vb-grid vb-w%d">%s</div>'
                '<span class="vb-arrow">&rarr;</span>%s</div>%s</div>') % (esc(r.get("caption", "")), w, cells, self.icon(out, lang, 44), info)

    def schem(self, sid, lang):
        s = self.x["schematics"][sid]
        name = s[lang]
        img = '<img src="%s" alt="%s" loading="lazy">' % (self.asset("schem/%s.png" % sid), esc(name))
        layers = ""
        if not s["plan"] and s["layers"] > 1:
            layers = '<div class="vb-layers">%s</div>' % "".join(
                '<figure><img src="%s" alt="" loading="lazy"><figcaption>%s %d / %d</figcaption></figure>'
                % (self.asset("schem/%s_L%d.png" % (sid, k)), "Слой" if lang == "ru" else "Layer", k, s["layers"]) for k in range(1, s["layers"] + 1))
        rows = []
        for e in s["list"]:
            nm = e.get(lang) or e.get("en") or e.get("reg")
            auto = ' class="vb-auto"' if e.get("auto") else ""
            ic = "" if e.get("auto") else self.icon(e, lang, 24)
            rows.append('<tr%s><td>%s %s</td><td>&times; %d</td></tr>' % (auto, ic, esc(nm), e["count"]))
        note = ("Голограмма этой схемы - в книге в игре: кнопка \"Голограмма в мире\"." if lang == "ru"
                else "The hologram of this schematic is in the book in game: the \"Hologram in the world\" button.")
        return ('<figure class="vb-schem"><div class="vb-main">%s</div><figcaption>%s</figcaption>%s'
                '<table class="gn-table vb-list"><tbody>%s</tbody></table><p class="vb-hint">%s</p></figure>') % (img, esc(name), layers, "".join(rows), esc(note))

    def section(self, blocks, lang):
        out, i = [], 0
        while i < len(blocks):
            b = blocks[i]
            k = b["k"]
            t = b.get("text", "")
            if k == "page":
                out.append('<h3 class="vb-page">%s</h3>' % inline(t))
            elif k == "h":
                out.append('<h4>%s</h4>' % inline(t))
            elif k == "p":
                out.append('<p>%s</p>' % inline(t))
            elif k == "li":
                items = []
                while i < len(blocks) and blocks[i]["k"] == "li":
                    items.append('<li>%s</li>' % inline(blocks[i].get("text", "")))
                    i += 1
                out.append('<ul class="vg-list">%s</ul>' % "".join(items))
                continue
            elif k == "kv":
                rows = []
                while i < len(blocks) and blocks[i]["k"] == "kv":
                    rows.append('<tr><td>%s</td><td>%s</td></tr>' % (inline(blocks[i].get("text", "")), inline(blocks[i].get("arg", ""))))
                    i += 1
                out.append('<table class="gn-table vb-kv"><tbody>%s</tbody></table>' % "".join(rows))
                continue
            elif k == "note":
                out.append('<div class="vb-note">%s</div>' % inline(t))
            elif k == "vehicle":
                out.append('<table class="gn-table vb-kv vb-veh"><tbody>%s</tbody></table>' % "".join(
                    '<tr><td>%s</td><td>%s</td></tr>' % (esc(r[0]), esc(r[1])) for r in b.get("rows", [])))
            elif k == "recipe":
                out.append(self.recipe(b["recipe"], lang))
            elif k == "model":
                out.append('<p class="vb-model"><a href="vehicles.html#viewer" data-model="%s">%s</a></p>' % (
                    esc(b.get("model", "")), "3D-модель - на странице техники &rarr;" if lang == "ru" else "The 3D model - on the vehicles page &rarr;"))
            elif k == "schem":
                out.append(self.schem(b["arg"], lang))
            elif k == "img":
                out.append('<figure class="vb-img"><img src="%s" alt="%s" loading="lazy"><figcaption>%s</figcaption></figure>' % (
                    self.asset("img/%s.png" % b["arg"]), esc(t), inline(t)))
            elif k == "check":
                rows = "".join('<tr><td>&#9744; %s</td><td>%s</td></tr>' % (esc(a), esc(s)) for a, s in b.get("items", []))
                out.append('<table class="gn-table vb-check"><thead><tr><th colspan="2">%s</th></tr></thead><tbody>%s</tbody></table>' % (
                    esc(b.get("title", "")), rows))
            elif k == "tutorial":
                steps = "".join('<li><b>%s</b> <span>%s</span></li>' % (esc(a), esc(h)) for a, h in b.get("steps", []))
                out.append('<div class="vb-tut"><div class="vb-cap">%s</div><ol>%s</ol></div>' % (esc(b.get("title", "")), steps))
            elif k == "link":
                out.append('<p class="vb-link"><a href="#vb-%s">%s &rarr;</a></p>' % (esc(b.get("arg", "")), inline(t)))
            i += 1
        return "".join(out)

    # ------------------------------------------------------------- chapters

    @staticmethod
    def split(blocks):
        """A chapter's blocks -> its sections: what comes before the first page title, then one per page title."""
        secs, cur = [], []
        for b in blocks:
            if b["k"] == "title":
                continue
            if b["k"] == "page" and cur:
                secs.append(cur)
                cur = []
            cur.append(b)
        if cur:
            secs.append(cur)
        return secs

    def chapters(self):
        ru, en = self.x["langs"]["ru"]["chapters"], self.x["langs"]["en"]["chapters"]
        out = []
        for c_ru, c_en in zip(ru, en):
            s_ru, s_en = self.split(c_ru["blocks"]), self.split(c_en["blocks"])
            if len(s_ru) != len(s_en):
                raise SystemExit("book_html: %s has %d ru / %d en sections" % (c_ru["id"], len(s_ru), len(s_en)))
            secs = []
            for k, (a, b) in enumerate(zip(s_ru, s_en)):
                secs.append('<div class="vb-sec" id="vb-%s-%d" data-en="%s">%s</div>' % (c_ru["id"], k, esc(self.section(b, "en")), self.section(a, "ru")))
            out.append((c_ru, c_en, '<section class="vb-ch" id="vb-%s"><h2 class="gn-h" data-en="%s">%s</h2>%s</section>' % (
                c_ru["id"], esc(c_en["title"]), esc(c_ru["title"]), "".join(secs))))
        return out

    def toc(self, chapters):
        items = []
        for c_ru, c_en, _ in chapters:
            pages = [b for b in c_ru["blocks"] if b["k"] == "page"]
            pages_en = [b for b in c_en["blocks"] if b["k"] == "page"]
            sub = "".join('<li><a href="#vb-%s-%d" data-en="%s">%s</a></li>' % (c_ru["id"], k + (0 if c_ru["blocks"][1]["k"] == "page" else 1),
                                                                                esc(pe.get("text", "")), esc(p.get("text", "")))
                          for k, (p, pe) in enumerate(zip(pages, pages_en)))
            items.append('<li><a href="#vb-%s" data-en="%s">%s</a><ul>%s</ul></li>' % (c_ru["id"], esc(c_en["title"]), esc(c_ru["title"]), sub))
        return '<ol class="vb-toc">%s</ol>' % "".join(items)


def load(export_dir):
    x = json.load(io.open(os.path.join(export_dir, "book.json"), encoding="utf-8"))
    if x.get("format") != FORMAT:
        raise SystemExit("book_html: export format %s, expected %d" % (x.get("format"), FORMAT))
    return x


def render(export_dir, icon=default_icon, asset=default_asset):
    """{'toc': html, 'chapters': [(id, html)], 'pages': {'ru': n, 'en': n}, 'export': json}"""
    x = load(export_dir)
    r = Renderer(x, icon, asset)
    ch = r.chapters()
    return {"toc": r.toc(ch), "chapters": [(c[0]["id"], c[2]) for c in ch], "export": x,
            "pages": {l: x["langs"][l]["pages"] for l in ("ru", "en")}}


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    src = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(src, "html")
    os.makedirs(out, exist_ok=True)
    res = render(src)
    io.open(os.path.join(out, "toc.html"), "w", encoding="utf-8", newline="\n").write(res["toc"])
    for cid, h in res["chapters"]:
        io.open(os.path.join(out, cid + ".html"), "w", encoding="utf-8", newline="\n").write(h)
    io.open(os.path.join(out, "book.html"), "w", encoding="utf-8", newline="\n").write(res["toc"] + "\n" + "\n".join(h for _, h in res["chapters"]))
    print("book_html: %d chapters, %d / %d pages (ru / en) -> %s" % (len(res["chapters"]), res["pages"]["ru"], res["pages"]["en"], out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
