package com.sva.book;

import java.util.ArrayList;
import java.util.List;

/**
 * Wave C (plan-next §9, items 41-48): the handbook «Справочник: Кремниевый век - Техника» as data - one language's
 * chapters, each a flat list of blocks parsed from assets/siliconagevehicles/book/LANG/CHAPTER.txt (BookMarkup). The same
 * data feeds the in-game book (client.GuiBookSVA lays it out with BookLayout), its search (BookSearch), the self-test and
 * the website exporter (BookExportSVA) - one content source for the book and the site (item 48).
 * <p>
 * Pure (no Minecraft classes): the dedicated server's self-test loads and checks it.
 */
public final class BookDoc {

    /**
     * Block kinds (the markup's line types): the chapter's title, a page title (# - starts a page), a heading (##), a
     * paragraph, a bullet (-), a two-column row (@kv), a note box (@note), a vehicle's stats (@vehicle TYPE), a recipe
     * (@recipe REF), a turning 3D model (@model TYPE), a build schematic (@schem ID), a picture (@img NAME), a live checklist
     * (@check LIST), the tutorial's box (@tutorial), a link to a chapter (@link ID | TEXT), a forced page break (@break).
     */
    public static final int TITLE = 0, PAGE = 1, H = 2, P = 3, LI = 4, KV = 5, NOTE = 6, VEHICLE = 7, RECIPE = 8, MODEL = 9, SCHEM = 10,
            IMG = 11, CHECK = 12, TUTORIAL = 13, LINK = 14, BREAK = 15;
    /** The kinds' names (the exporter's JSON). */
    public static final String[] KIND_NAMES = {"title", "page", "h", "p", "li", "kv", "note", "vehicle", "recipe", "model", "schem", "img",
        "check", "tutorial", "link", "break"};

    /** The language ("ru" / "en"). */
    public final String lang;
    public final List<Chapter> chapters = new ArrayList<Chapter>();

    public BookDoc(String lang) {
        this.lang = lang;
    }

    /** A chapter by its id, or null. */
    public Chapter chapter(String id) {
        for (Chapter c : chapters) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }

    public int indexOf(String id) {
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** Every block of every chapter (the checks). */
    public List<Block> allBlocks() {
        List<Block> l = new ArrayList<Block>();
        for (Chapter c : chapters) {
            l.addAll(c.blocks);
        }
        return l;
    }

    /** One chapter: its id (the file name), title, icon (an item ref "mod:name:meta" or ""), blocks. */
    public static final class Chapter {
        public final String id;
        public String title = "", icon = "";
        public final List<Block> blocks = new ArrayList<Block>();

        public Chapter(String id) {
            this.id = id;
        }

        /** The page titles (# lines) in order - the contents' second level. */
        public List<Block> pages() {
            List<Block> l = new ArrayList<Block>();
            for (Block b : blocks) {
                if (b.kind == PAGE) {
                    l.add(b);
                }
            }
            return l;
        }
    }

    /**
     * One block: its kind, its text (a paragraph, a heading; @kv the left column; @link the shown text), its argument (@kv
     * the right column; @vehicle / @model the type; @recipe the ref; @schem / @img / @check the id; @link the chapter id),
     * where it came from (the file's line, for the checks' messages) and its index in the chapter (bookmarks / search).
     */
    public static final class Block {
        public final int kind;
        public final String text, arg;
        public final int line;
        public int index;

        public Block(int kind, String text, String arg, int line) {
            this.kind = kind;
            this.text = text == null ? "" : text;
            this.arg = arg == null ? "" : arg;
            this.line = line;
        }

        /** Text the reader sees (searchable): the text and, for a row, its right column. */
        public String plain() {
            String t = BookMarkup.stripMarks(text);
            return kind == KV ? t + " " + BookMarkup.stripMarks(arg) : t;
        }

        @Override
        public String toString() {
            return KIND_NAMES[kind] + "@" + line + ":" + (text.length() > 30 ? text.substring(0, 30) : text);
        }
    }
}
