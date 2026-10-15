package com.sva.book;

import java.util.ArrayList;
import java.util.List;

/**
 * Wave C, item 41: the handbook's pages - BookDoc's blocks laid out on pages of a given size (GUI pixels): text wrapped by
 * the font's widths (Measure - the client's FontRenderer; the self-test and the exporter an estimate), the fixed blocks
 * (recipes, models, schematics, checklists, the tutorial, pictures) at their heights, rows that can split (a vehicle's
 * numbers, a checklist's items, a schematic's block list) one by one. Each chapter starts a page, a page title (#) starts a
 * page, a heading never ends a page alone. Pure: the GUI draws what this places, the self-test counts the pages.
 * <p>
 * Bold (**) becomes the font's §l ... §r inside the lines (carried over a line break).
 */
public final class BookLayout {

    /** The font's widths (§ codes take no room - FontRenderer's rule). */
    public interface Measure {
        int width(String s);
    }

    /** The runtime facts the layout needs (the GUI's: the registered recipes, the picture sizes...). */
    public interface Resolver {
        /** A vehicle's rows {label, value}; empty for an unknown type. */
        List<String[]> vehicleRows(String type);

        /** A recipe's box {width, height}, or null when the ref does not resolve. */
        int[] recipeSize(String ref);

        /** A picture's height at this width, or -1 when it is missing. */
        int imageHeight(String name, int width);

        /** The block list's lines of a schematic (-1: unknown). */
        int schemList(String id);

        /** A checklist's items (-1: unknown). */
        int checkItems(String id);
    }

    /** Heights (GUI px). */
    public static final int LINE = 10, HEAD = 14, PAGE_T = 16, TITLE_H = 26, MODEL_H = 110, SCHEM_H = 138, TUT_H = 104, ROW = 12, LIST_ROW = 18,
            LINK_H = 12, GAP = 3, MISSING_H = 12;
    /** A row's left column share of the width. */
    public static final double KV_LEFT = 0.42;
    /** The bullet's indent; the note's padding. */
    public static final int INDENT = 9, NOTE_PAD = 4;

    /** A placed item: its block, its part (a row's index; -1 the whole block), y and height, its wrapped lines. */
    public static final class Item {
        public final BookDoc.Block block;
        public final int row;
        public int y, h;
        /** Text lines (paragraphs, headings, notes, bullets; a row's right column). */
        public List<String> lines = new ArrayList<String>();
        /** A row's left column lines. */
        public List<String> left = new ArrayList<String>();

        Item(BookDoc.Block block, int row) {
            this.block = block;
            this.row = row;
        }

        public int kind() {
            return block.kind;
        }
    }

    /** A page: its chapter and items. */
    public static final class Page {
        public final int chapter;
        public final List<Item> items = new ArrayList<Item>();
        int used;

        Page(int chapter) {
            this.chapter = chapter;
        }
    }

    public final int width, height;
    public final List<Page> pages = new ArrayList<Page>();
    /** The first page of each chapter. */
    public final int[] chapterStart;

    private BookLayout(int width, int height, int chapters) {
        this.width = width;
        this.height = height;
        this.chapterStart = new int[chapters];
    }

    /** Lays a book out on pages `w` x `h`. */
    public static BookLayout layout(BookDoc d, Measure m, Resolver r, int w, int h) {
        BookLayout L = new BookLayout(w, h, d.chapters.size());
        for (int ci = 0; ci < d.chapters.size(); ci++) {
            BookDoc.Chapter c = d.chapters.get(ci);
            L.chapterStart[ci] = L.pages.size();
            Page p = new Page(ci);
            L.pages.add(p);
            List<BookDoc.Block> bl = c.blocks;
            for (int bi = 0; bi < bl.size(); bi++) {
                BookDoc.Block b = bl.get(bi);
                List<Item> items = L.items(b, m, r);
                if (b.kind == BookDoc.BREAK || b.kind == BookDoc.PAGE && !onlyTitle(p)) {
                    if (!p.items.isEmpty()) {
                        p = new Page(ci);
                        L.pages.add(p);
                    }
                    if (b.kind == BookDoc.BREAK) {
                        continue;
                    }
                }
                for (int k = 0; k < items.size(); k++) {
                    Item it = items.get(k);
                    int need = it.h;
                    if (b.kind == BookDoc.H || b.kind == BookDoc.PAGE) {
                        need += LINE;                               // a heading keeps a line of what follows with it
                    }
                    if (p.used + need > h && !p.items.isEmpty()) {
                        p = new Page(ci);
                        L.pages.add(p);
                    }
                    it.y = p.used;
                    p.items.add(it);
                    p.used += it.h;
                }
            }
        }
        return L;
    }

    /** The page holds nothing but the chapter's title (a page title may join it). */
    private static boolean onlyTitle(Page p) {
        return p.items.size() == 1 && p.items.get(0).block.kind == BookDoc.TITLE || p.items.isEmpty();
    }

    /** The block's items (one, or one a row). */
    List<Item> items(BookDoc.Block b, Measure m, Resolver r) {
        List<Item> l = new ArrayList<Item>();
        Item it;
        switch (b.kind) {
            case BookDoc.TITLE:
                it = text(b, m, width - 4, LINE);
                it.h = Math.max(TITLE_H, it.lines.size() * LINE + 16);
                l.add(it);
                break;
            case BookDoc.PAGE:
                it = text(b, m, width, LINE);
                it.h = it.lines.size() * LINE + 6;
                l.add(it);
                break;
            case BookDoc.H:
                it = text(b, m, width, LINE);
                it.h = it.lines.size() * LINE + 4;
                l.add(it);
                break;
            case BookDoc.P:
            case BookDoc.LI:
                // a long paragraph splits by lines across pages: one item per line, the gap after the last
                int ww = b.kind == BookDoc.LI ? width - INDENT : width;
                List<String> lines = wrap(b.text, m, ww);
                for (int i = 0; i < lines.size(); i++) {
                    Item li = new Item(b, i);
                    li.lines.add(lines.get(i));
                    li.h = LINE + (i == lines.size() - 1 ? GAP : 0);
                    l.add(li);
                }
                break;
            case BookDoc.NOTE:
                it = new Item(b, -1);
                it.lines = wrap(b.text, m, width - 2 * NOTE_PAD - 2);
                it.h = it.lines.size() * LINE + 2 * NOTE_PAD + GAP;
                l.add(it);
                break;
            case BookDoc.KV:
                l.add(row(b, -1, b.text, b.arg, m));
                break;
            case BookDoc.VEHICLE: {
                List<String[]> rows = r.vehicleRows(b.arg);
                if (rows.isEmpty()) {
                    l.add(missing(b));
                }
                for (int i = 0; i < rows.size(); i++) {
                    l.add(row(b, i, rows.get(i)[0], rows.get(i)[1], m));
                }
                if (!l.isEmpty()) {
                    l.get(l.size() - 1).h += GAP;
                }
                break;
            }
            case BookDoc.RECIPE: {
                int[] sz = r.recipeSize(b.arg);
                if (sz == null) {
                    l.add(missing(b));
                } else {
                    it = new Item(b, -1);
                    it.h = sz[1] + GAP;
                    l.add(it);
                }
                break;
            }
            case BookDoc.MODEL:
                it = new Item(b, -1);
                it.h = MODEL_H + GAP;
                l.add(it);
                break;
            case BookDoc.SCHEM: {
                int n = r.schemList(b.arg);
                if (n < 0) {
                    l.add(missing(b));
                    break;
                }
                it = new Item(b, -1);
                it.h = SCHEM_H;
                l.add(it);
                for (int i = 0; i < n; i++) {
                    Item ri = new Item(b, i);
                    ri.h = LIST_ROW + (i == n - 1 ? GAP : 0);
                    l.add(ri);
                }
                break;
            }
            case BookDoc.IMG: {
                int ih = r.imageHeight(b.arg, width);
                if (ih < 0) {
                    l.add(missing(b));
                    break;
                }
                it = new Item(b, -1);
                it.lines = b.text.length() > 0 ? wrap(b.text, m, width) : new ArrayList<String>();
                it.h = ih + it.lines.size() * LINE + GAP + 2;
                l.add(it);
                break;
            }
            case BookDoc.CHECK: {
                int n = r.checkItems(b.arg);
                if (n < 0) {
                    l.add(missing(b));
                    break;
                }
                it = new Item(b, -1);
                it.h = ROW + 2;
                l.add(it);
                for (int i = 0; i < n; i++) {
                    Item ri = new Item(b, i);
                    ri.h = ROW + (i == n - 1 ? GAP : 0);
                    l.add(ri);
                }
                break;
            }
            case BookDoc.TUTORIAL:
                it = new Item(b, -1);
                it.h = TUT_H;
                l.add(it);
                break;
            case BookDoc.LINK:
                it = text(b, m, width - INDENT, LINE);
                it.h = it.lines.size() * LINE + 2;
                l.add(it);
                break;
            default:
                break;
        }
        return l;
    }

    private Item text(BookDoc.Block b, Measure m, int w, int lineH) {
        Item it = new Item(b, -1);
        it.lines = wrap(b.text, m, w);
        it.h = it.lines.size() * lineH;
        return it;
    }

    private Item row(BookDoc.Block b, int idx, String left, String right, Measure m) {
        Item it = new Item(b, idx);
        int lw = (int) (width * KV_LEFT);
        it.left = wrap(left, m, lw - 4);
        it.lines = wrap(right, m, width - lw);
        it.h = Math.max(it.left.size(), it.lines.size()) * LINE + 1;
        return it;
    }

    private static Item missing(BookDoc.Block b) {
        Item it = new Item(b, -2);
        it.h = MISSING_H;
        return it;
    }

    /**
     * Word wrap with the bold marks: ** toggles §l / §r; a bold run cut by a line break goes on bold on the next line; a word
     * longer than the width is cut by characters.
     */
    public static List<String> wrap(String text, Measure m, int w) {
        List<String> out = new ArrayList<String>();
        String s = bold(text);
        String[] words = s.split(" ");
        StringBuilder line = new StringBuilder();
        boolean boldOn = false;
        for (String word : words) {
            if (word.length() == 0) {
                continue;
            }
            String cand = line.length() == 0 ? word : line + " " + word;
            if (m.width(cand) <= w) {
                line.setLength(0);
                line.append(cand);
            } else {
                if (line.length() > 0) {
                    out.add(line.toString());
                    boldOn = boldAtEnd(line.toString(), boldOn);
                    line.setLength(0);
                    line.append(boldOn ? "§l" : "").append(word);
                } else {
                    line.append(word);
                }
                while (m.width(line.toString()) > w && line.length() > 1) {
                    int cut = line.length() - 1;
                    while (cut > 1 && m.width(line.substring(0, cut)) > w) {
                        cut--;
                    }
                    if (line.charAt(cut - 1) == '§') {
                        cut--;                                      // never split a format code
                    }
                    out.add(line.substring(0, cut));
                    boldOn = boldAtEnd(line.substring(0, cut), boldOn);
                    String rest = line.substring(cut);
                    line.setLength(0);
                    line.append(boldOn ? "§l" : "").append(rest);
                }
            }
        }
        if (line.length() > 0 || out.isEmpty()) {
            out.add(line.toString());
        }
        return out;
    }

    /** ** -> §l / §r. */
    static String bold(String s) {
        StringBuilder b = new StringBuilder();
        boolean on = false;
        int i = 0;
        while (i < s.length()) {
            if (s.startsWith("**", i)) {
                b.append(on ? "§r" : "§l");
                on = !on;
                i += 2;
            } else {
                b.append(s.charAt(i++));
            }
        }
        return b.toString();
    }

    /** Is bold still on at a line's end (it started `on`)? */
    static boolean boldAtEnd(String line, boolean on) {
        for (int i = 0; i + 1 < line.length(); i++) {
            if (line.charAt(i) == '§') {
                char k = line.charAt(i + 1);
                on = k == 'l' || (on && k != 'r');
            }
        }
        return on;
    }

    /** The page that shows a block (its first part) of a chapter; the chapter's first page if none. */
    public int pageOf(int chapter, int block) {
        for (int pi = chapterStart.length > chapter && chapter >= 0 ? chapterStart[chapter] : 0; pi < pages.size(); pi++) {
            Page p = pages.get(pi);
            if (p.chapter != chapter) {
                break;
            }
            for (Item it : p.items) {
                if (it.block.index == block) {
                    return pi;
                }
            }
        }
        return chapter >= 0 && chapter < chapterStart.length ? chapterStart[chapter] : 0;
    }

    /** The first block shown on a page (its anchor for bookmarks: survives a new layout). */
    public int anchorBlock(int page) {
        if (page < 0 || page >= pages.size() || pages.get(page).items.isEmpty()) {
            return 0;
        }
        return pages.get(page).items.get(0).block.index;
    }

    /** An estimate of the vanilla font for the self-test / exporter: 6 px a character (5 + the gap), § codes free. */
    public static final Measure ESTIMATE = new Measure() {
        public int width(String s) {
            int n = 0;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) == '§' && i + 1 < s.length()) {
                    i++;
                    continue;
                }
                char c = s.charAt(i);
                n += c == ' ' || c == '.' || c == ',' || c == 'i' || c == 'l' || c == '!' || c == '\'' || c == ':' ? 3 : 6;
            }
            return n;
        }
    };
}
