package com.sva.book;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wave C, item 48: the handbook's source format - plain UTF-8 text files, one per chapter and language, so the same files
 * are read by the game (BookDoc for GuiBookSVA) and by the website exporter. assets/siliconagevehicles/book/index.txt lists
 * the chapter ids in order (one a line, # comments); book/ru/ID.txt and book/en/ID.txt hold the chapter:
 * <pre>
 * &#64;chapter Title                the chapter's title (first line)
 * &#64;icon mod:item:meta           the chapter's icon in the contents
 * # Page title                    a new page with its title
 * ## Heading
 * text lines                      a paragraph (lines join until a blank line); **bold**
 * - bullet                        (a following line starting with two spaces continues it)
 * &#64;kv Left | Right              a two-column row
 * &#64;note text                    a framed note
 * &#64;vehicle TYPE                 the vehicle's numbers (VehicleType)
 * &#64;recipe wb:ID | craft:mod:item[:meta]   the recipe drawn from the registered one
 * &#64;model TYPE                   a turning 3D model
 * &#64;schem ID                     a build schematic by layers + the hologram button (BookSchematics)
 * &#64;img NAME [| caption]         textures/book/NAME.png
 * &#64;check LIST                   a live checklist (BookChecklists)
 * &#64;tutorial                     the tutorial's box (TutorialMath)
 * &#64;link CHAPTER | text           a link
 * &#64;break                        a page break
 * </pre>
 * The book is in-game text: only ASCII and Cyrillic letters (glyphSafe - the 1.7.10 font shows nothing else right: no
 * guillemets, dashes, multiplication signs, degree signs, special spaces).
 */
public final class BookMarkup {

    public static final String ROOT = "/assets/siliconagevehicles/book/";
    public static final String[] LANGS = {"ru", "en"};

    private static final Map<String, BookDoc> CACHE = new HashMap<String, BookDoc>();
    private static final Map<String, List<String>> ERRORS = new HashMap<String, List<String>>();

    private BookMarkup() {
    }

    /** "ru" for a Russian game language, else "en". */
    public static String langOf(String gameLanguage) {
        return gameLanguage != null && gameLanguage.toLowerCase().startsWith("ru") ? "ru" : "en";
    }

    /** The book of a language (loaded once; never null - a missing file leaves an empty chapter and an error). */
    public static synchronized BookDoc get(String lang) {
        BookDoc d = CACHE.get(lang);
        if (d == null) {
            List<String> err = new ArrayList<String>();
            d = load(lang, err);
            CACHE.put(lang, d);
            ERRORS.put(lang, err);
        }
        return d;
    }

    /** The problems found while loading a language (the self-test). */
    public static synchronized List<String> errors(String lang) {
        get(lang);
        return ERRORS.get(lang);
    }

    /** The chapter ids in order (index.txt). */
    public static List<String> index() {
        List<String> ids = new ArrayList<String>();
        String s = read(ROOT + "index.txt");
        if (s == null) {
            return ids;
        }
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (t.length() > 0 && !t.startsWith("#")) {
                ids.add(t);
            }
        }
        return ids;
    }

    static BookDoc load(String lang, List<String> err) {
        BookDoc d = new BookDoc(lang);
        for (String id : index()) {
            String s = read(ROOT + lang + "/" + id + ".txt");
            if (s == null) {
                err.add(lang + "/" + id + ".txt: missing");
                BookDoc.Chapter c = new BookDoc.Chapter(id);
                c.title = id;
                d.chapters.add(c);
                continue;
            }
            d.chapters.add(parse(id, s, err));
        }
        return d;
    }

    /** A resource as text (UTF-8, \r dropped), or null. */
    public static String read(String path) {
        InputStream in = BookMarkup.class.getResourceAsStream(path);
        if (in == null) {
            return null;
        }
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder b = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) {
                b.append(buf, 0, n);
            }
            r.close();
            String s = b.toString().replace("\r", "");
            return s.startsWith("﻿") ? s.substring(1) : s;
        } catch (IOException e) {
            return null;
        }
    }

    /** Parses one chapter's text (errors: "id:line: what"). */
    public static BookDoc.Chapter parse(String id, String text, List<String> err) {
        BookDoc.Chapter c = new BookDoc.Chapter(id);
        String[] lines = text.split("\n", -1);
        StringBuilder para = null;
        int paraLine = 0, paraKind = BookDoc.P;
        for (int n = 0; n < lines.length; n++) {
            String raw = lines[n];
            int ln = n + 1;
            String bad = glyphProblem(raw);
            if (bad != null) {
                err.add(id + ":" + ln + ": " + bad);
            }
            if (para != null && paraKind == BookDoc.LI && raw.startsWith("  ") && raw.trim().length() > 0) {
                para.append(' ').append(raw.trim());
                continue;
            }
            String line = raw.trim();
            boolean plain = line.length() > 0 && !line.startsWith("#") && !line.startsWith("@") && !line.startsWith("- ")
                    && !line.startsWith("//");
            if (plain && para != null && paraKind == BookDoc.P) {
                para.append(' ').append(line);
                continue;
            }
            if (para != null) {
                add(c, paraKind, para.toString(), "", paraLine);
                para = null;
            }
            if (line.length() == 0 || line.startsWith("//")) {
                continue;
            }
            if (plain) {
                para = new StringBuilder(line);
                paraLine = ln;
                paraKind = BookDoc.P;
            } else if (line.startsWith("- ")) {
                para = new StringBuilder(line.substring(2).trim());
                paraLine = ln;
                paraKind = BookDoc.LI;
            } else if (line.startsWith("## ")) {
                add(c, BookDoc.H, line.substring(3).trim(), "", ln);
            } else if (line.startsWith("# ")) {
                add(c, BookDoc.PAGE, line.substring(2).trim(), "", ln);
            } else if (line.startsWith("@")) {
                directive(c, line, ln, err);
            } else {
                err.add(id + ":" + ln + ": not understood: " + line);
            }
        }
        if (para != null) {
            add(c, paraKind, para.toString(), "", paraLine);
        }
        if (c.title.length() == 0) {
            err.add(id + ": no @chapter title");
            c.title = id;
        }
        checkMarks(c, err);
        return c;
    }

    private static void directive(BookDoc.Chapter c, String line, int ln, List<String> err) {
        int sp = line.indexOf(' ');
        String name = (sp < 0 ? line : line.substring(0, sp)).substring(1);
        String rest = sp < 0 ? "" : line.substring(sp + 1).trim();
        String left = rest, right = "";
        int bar = rest.indexOf('|');
        if (bar >= 0) {
            left = rest.substring(0, bar).trim();
            right = rest.substring(bar + 1).trim();
        }
        if ("chapter".equals(name)) {
            c.title = rest;
            add(c, BookDoc.TITLE, rest, "", ln);
        } else if ("icon".equals(name)) {
            c.icon = rest;
        } else if ("kv".equals(name)) {
            add(c, BookDoc.KV, left, right, ln);
        } else if ("note".equals(name)) {
            add(c, BookDoc.NOTE, rest, "", ln);
        } else if ("vehicle".equals(name)) {
            add(c, BookDoc.VEHICLE, "", rest, ln);
        } else if ("recipe".equals(name)) {
            add(c, BookDoc.RECIPE, "", rest, ln);
        } else if ("model".equals(name)) {
            add(c, BookDoc.MODEL, "", rest, ln);
        } else if ("schem".equals(name)) {
            add(c, BookDoc.SCHEM, "", rest, ln);
        } else if ("img".equals(name)) {
            add(c, BookDoc.IMG, right, left, ln);
        } else if ("check".equals(name)) {
            add(c, BookDoc.CHECK, "", rest, ln);
        } else if ("tutorial".equals(name)) {
            add(c, BookDoc.TUTORIAL, "", "", ln);
        } else if ("link".equals(name)) {
            add(c, BookDoc.LINK, right, left, ln);
        } else if ("break".equals(name)) {
            add(c, BookDoc.BREAK, "", "", ln);
        } else {
            err.add(c.id + ":" + ln + ": unknown directive @" + name);
        }
    }

    private static void add(BookDoc.Chapter c, int kind, String text, String arg, int line) {
        BookDoc.Block b = new BookDoc.Block(kind, text, arg, line);
        b.index = c.blocks.size();
        c.blocks.add(b);
    }

    /** Every ** must close in its block. */
    private static void checkMarks(BookDoc.Chapter c, List<String> err) {
        for (BookDoc.Block b : c.blocks) {
            if (count(b.text, "**") % 2 != 0 || count(b.arg, "**") % 2 != 0) {
                err.add(c.id + ":" + b.line + ": unclosed **");
            }
        }
    }

    static int count(String s, String what) {
        int n = 0, i = 0;
        while ((i = s.indexOf(what, i)) >= 0) {
            n++;
            i += what.length();
        }
        return n;
    }

    /** The text without the bold marks. */
    public static String stripMarks(String s) {
        return s.replace("**", "");
    }

    /**
     * The handbook's own font rule: printable ASCII and the Cyrillic block (U+0400..U+04FF), nothing else - the vanilla
     * 1.7.10 font draws the ascii.png extras as other letters under Russian packs and has no glyphs for dashes,
     * guillemets, arrows, stars, special spaces (GlyphCheckSVA's list is a subset of what this refuses).
     */
    public static boolean glyphSafe(char c) {
        return c >= 0x20 && c <= 0x7E || c >= 'Ѐ' && c <= 'ӿ';
    }

    /** "U+XXXX at column n" for the first unsafe character of a line, or null. */
    public static String glyphProblem(String line) {
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '\t' || !glyphSafe(ch)) {
                return "glyph U+" + Integer.toHexString(0x10000 | ch).substring(1).toUpperCase() + " at column " + (i + 1);
            }
        }
        return null;
    }

    /** Forget the loaded books (a resource reload). */
    public static synchronized void clear() {
        CACHE.clear();
        ERRORS.clear();
    }
}
