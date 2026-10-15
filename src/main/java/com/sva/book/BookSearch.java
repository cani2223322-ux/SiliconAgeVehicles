package com.sva.book;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Wave C, item 41: the handbook's search - every block's text (titles, headings, paragraphs, rows, notes; a vehicle's and a
 * recipe's block by its type / id too) indexed once per language, a query matched word by word (every word must be in the
 * block; case and «ё» / «е» do not matter; a word of 3+ letters also matches as a word's start), the hits ordered titles
 * first, then headings, then text, in book order. Pure: the self-test runs it.
 */
public final class BookSearch {

    /** The hit's weight by the block kind: a chapter / page title over a heading over text. */
    static final int W_TITLE = 3, W_HEAD = 2, W_TEXT = 1;
    /** At most this many hits are returned. */
    public static final int MAX_HITS = 60;

    public final String lang;
    private final List<Entry> entries = new ArrayList<Entry>();

    private BookSearch(String lang) {
        this.lang = lang;
    }

    /** One indexed block. */
    public static final class Entry {
        public final int chapter, block, weight;
        public final String text, norm;

        Entry(int chapter, int block, int weight, String text) {
            this.chapter = chapter;
            this.block = block;
            this.weight = weight;
            this.text = text;
            this.norm = norm(text);
        }
    }

    /** A hit: the chapter / block, the snippet around the first match. */
    public static final class Hit {
        public final int chapter, block, weight;
        public final String snippet;

        Hit(Entry e, String snippet) {
            this.chapter = e.chapter;
            this.block = e.block;
            this.weight = e.weight;
            this.snippet = snippet;
        }
    }

    /** Indexes a book. */
    public static BookSearch build(BookDoc d) {
        BookSearch s = new BookSearch(d.lang);
        for (int ci = 0; ci < d.chapters.size(); ci++) {
            BookDoc.Chapter c = d.chapters.get(ci);
            for (BookDoc.Block b : c.blocks) {
                String t = b.plain();
                int w;
                switch (b.kind) {
                    case BookDoc.TITLE:
                    case BookDoc.PAGE:
                        w = W_TITLE;
                        break;
                    case BookDoc.H:
                        w = W_HEAD;
                        break;
                    case BookDoc.VEHICLE:
                    case BookDoc.MODEL:
                    case BookDoc.RECIPE:
                    case BookDoc.SCHEM:
                    case BookDoc.CHECK:
                        t = b.arg;
                        w = W_TEXT;
                        break;
                    case BookDoc.BREAK:
                    case BookDoc.TUTORIAL:
                        continue;
                    default:
                        w = W_TEXT;
                }
                if (t.trim().length() > 0) {
                    s.entries.add(new Entry(ci, b.index, w, t));
                }
            }
        }
        return s;
    }

    public int size() {
        return entries.size();
    }

    /** Lower case, «ё» as «е», punctuation as spaces. */
    public static String norm(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if (c == 'ё') {
                c = 'е';
            }
            b.append(Character.isLetterOrDigit(c) ? c : ' ');
        }
        return b.toString();
    }

    /** The query's words (normalized, empty ones dropped). */
    public static List<String> words(String q) {
        List<String> l = new ArrayList<String>();
        for (String w : norm(q == null ? "" : q).split(" ")) {
            if (w.length() > 0) {
                l.add(w);
            }
        }
        return l;
    }

    /** Where `w` is in the normalized text (a whole word, or a word's start for 3+ letters), or -1. */
    static int find(String norm, String w) {
        int i = 0;
        while ((i = norm.indexOf(w, i)) >= 0) {
            boolean start = i == 0 || norm.charAt(i - 1) == ' ';
            int end = i + w.length();
            boolean whole = end >= norm.length() || norm.charAt(end) == ' ';
            if (start && (whole || w.length() >= 3)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /** The hits of a query (empty for an empty query). */
    public List<Hit> query(String q) {
        List<String> ws = words(q);
        List<Hit> hits = new ArrayList<Hit>();
        if (ws.isEmpty()) {
            return hits;
        }
        for (Entry e : entries) {
            int first = -1;
            boolean all = true;
            for (String w : ws) {
                int at = find(e.norm, w);
                if (at < 0) {
                    all = false;
                    break;
                }
                first = first < 0 ? at : Math.min(first, at);
            }
            if (all) {
                hits.add(new Hit(e, snippet(e.text, first, 64)));
            }
        }
        Collections.sort(hits, new Comparator<Hit>() {
            public int compare(Hit a, Hit b) {
                if (a.weight != b.weight) {
                    return b.weight - a.weight;
                }
                return a.chapter != b.chapter ? a.chapter - b.chapter : a.block - b.block;
            }
        });
        return hits.size() > MAX_HITS ? new ArrayList<Hit>(hits.subList(0, MAX_HITS)) : hits;
    }

    /** About `len` characters of the text around position `at` (norm keeps the positions), with "..." where cut. */
    static String snippet(String text, int at, int len) {
        if (text.length() <= len) {
            return text;
        }
        int s = Math.max(0, Math.min(at - len / 3, text.length() - len));
        int e = Math.min(text.length(), s + len);
        return (s > 0 ? "..." : "") + text.substring(s, e).trim() + (e < text.length() ? "..." : "");
    }
}
