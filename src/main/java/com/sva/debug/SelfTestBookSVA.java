package com.sva.debug;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.sva.ConfigSVA;
import com.sva.airdefense.AdShapeMath;
import com.sva.airport.PlannerMath;
import com.sva.book.BookChecklists;
import com.sva.book.BookDoc;
import com.sva.book.BookExportSVA;
import com.sva.book.BookGiveSVA;
import com.sva.book.BookLang;
import com.sva.book.BookLayout;
import com.sva.book.BookMarkup;
import com.sva.book.BookRecipesSVA;
import com.sva.book.BookResolveSVA;
import com.sva.book.BookSchematics;
import com.sva.book.BookSearch;
import com.sva.book.TutorialMath;
import com.sva.vehicle.BatteryMath;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.VehicleType;

/**
 * Wave C self-test (plan-next §9, items 41-48: the handbook) - the dedicated server runs it after the recipes are registered:
 * <ul>
 * <li>the book's files load for ru and en, every chapter in both languages, the same structure (pages, recipes, schematics,
 * models, checklists in the same places), no parse errors;</li>
 * <li>every text glyph-safe (ASCII + Cyrillic) - the book's files and its lang lines;</li>
 * <li>every reference resolves: recipes against the REGISTERED recipes, schematics, pictures, checklists, vehicle types,
 * links, chapter icons;</li>
 * <li>the layout (pages, no item over a page, the anchors) and the search (words, word starts, «ё», the order, the snippets);</li>
 * <li>the schematics against the real multiblock definitions (AdShapeMath, the tower's parts, the ILS antennas' parts, the
 * complex without overlaps, the runway plan = PlannerMath.plan);</li>
 * <li>the checklists' conditions against a simulated liner (a real start-up through LinerSystems), every item's control;</li>
 * <li>the tutorial's step machine (advance, pause, skip, restart, the start-up on a simulated liner, the flight's bookkeeping);</li>
 * <li>the give-on-join rule, the config, the lang families; the exporter's output parsed back.</li>
 * </ul>
 */
final class SelfTestBookSVA {

    private SelfTestBookSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws Exception {
        load();
        glyphs();
        refs();
        layout();
        search();
        schematics();
        checklists();
        tutorial();
        giveAndConfig();
        langFamilies();
        export();
    }

    // ---------------------------------------------------------------- loading

    static void load() {
        List<String> ids = BookMarkup.index();
        check(ids.size() == 11 && ids.get(0).equals("intro") && ids.contains("liner") && ids.contains("airdefense") && ids.contains("airport")
                && ids.contains("whatsnew") && ids.contains("faq") && ids.contains("config"), "book: index.txt lists the 11 chapters " + ids);
        BookDoc ru = BookMarkup.get("ru"), en = BookMarkup.get("en");
        List<String> er = BookMarkup.errors("ru"), ee = BookMarkup.errors("en");
        check(er.isEmpty() && ee.isEmpty(), "book: ru / en parse without errors" + (er.isEmpty() ? "" : " ru " + er) + (ee.isEmpty() ? "" : " en " + ee));
        boolean same = ru.chapters.size() == ids.size() && en.chapters.size() == ids.size();
        StringBuilder bad = new StringBuilder();
        for (int i = 0; same && i < ids.size(); i++) {
            BookDoc.Chapter a = ru.chapters.get(i), b = en.chapters.get(i);
            if (!a.id.equals(b.id) || a.title.length() == 0 || b.title.length() == 0 || a.blocks.size() < 5 || b.blocks.size() < 5) {
                bad.append(' ').append(a.id);
                continue;
            }
            if (!signature(a).equals(signature(b))) {
                bad.append(' ').append(a.id).append(":structure");
            }
            if (!a.icon.equals(b.icon)) {
                bad.append(' ').append(a.id).append(":icon");
            }
        }
        check(same && bad.length() == 0, "book: every chapter in both languages with the same pages / recipes / schematics / models / checklists" + bad);
        check(ru.chapter("liner").pages().size() >= 15 && en.chapter("liner").pages().size() == ru.chapter("liner").pages().size(),
                "book: the SA-320 pilot's manual has " + ru.chapter("liner").pages().size() + " pages of sections");
        // the markup itself
        List<String> err = new ArrayList<String>();
        BookDoc.Chapter t = BookMarkup.parse("t", "@chapter T\n# P\nline one\nline two\n\n- a\n  b\n@kv L | R\n@recipe wb:x\n**b** c\n@break\n@what\n", err);
        check(t.blocks.size() == 8 && t.blocks.get(2).kind == BookDoc.P && t.blocks.get(2).text.equals("line one line two")
                && t.blocks.get(3).kind == BookDoc.LI && t.blocks.get(3).text.equals("a b") && t.blocks.get(4).kind == BookDoc.KV
                && t.blocks.get(4).arg.equals("R") && t.blocks.get(5).arg.equals("wb:x") && t.blocks.get(7).kind == BookDoc.BREAK
                && err.size() == 1 && err.get(0).contains("@what"), "book markup: paragraphs join, bullets continue, rows, refs, breaks, unknown directives reported");
        err.clear();
        BookMarkup.parse("t", "@chapter T\nbad « quote » and — dash\n**open\n", err);
        check(err.size() == 2 && err.get(0).contains("U+00AB") && err.get(1).contains("unclosed"), "book markup: a guillemet / dash and an unclosed ** are refused " + err);
    }

    /** A chapter's structure: its page titles and special blocks in order (not the words). */
    static String signature(BookDoc.Chapter c) {
        StringBuilder b = new StringBuilder();
        for (BookDoc.Block x : c.blocks) {
            switch (x.kind) {
                case BookDoc.PAGE:
                case BookDoc.TUTORIAL:
                case BookDoc.BREAK:
                    b.append(BookDoc.KIND_NAMES[x.kind]).append(';');
                    break;
                case BookDoc.RECIPE:
                case BookDoc.SCHEM:
                case BookDoc.MODEL:
                case BookDoc.VEHICLE:
                case BookDoc.CHECK:
                case BookDoc.IMG:
                case BookDoc.LINK:
                    b.append(BookDoc.KIND_NAMES[x.kind]).append('=').append(x.arg).append(';');
                    break;
                default:
                    break;
            }
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- glyphs

    static void glyphs() throws Exception {
        StringBuilder bad = new StringBuilder();
        int chars = 0;
        for (String lang : BookMarkup.LANGS) {
            for (BookDoc.Chapter c : BookMarkup.get(lang).chapters) {
                for (String s : texts(c)) {
                    chars += s.length();
                    String p = BookMarkup.glyphProblem(s);
                    if (p != null) {
                        bad.append(' ').append(lang).append('/').append(c.id).append(':').append(p);
                    }
                }
            }
        }
        check(bad.length() == 0 && chars > 50000, "book glyphs: all " + chars + " characters of the book are ASCII / Cyrillic (no guillemets, dashes, x, degrees, special spaces)" + bad);
        // the book's lang lines (the UI, the checklists, the tutorial): the same rule, colour codes aside
        StringBuilder bl = new StringBuilder();
        int n = 0;
        for (String lang : BookMarkup.LANGS) {
            for (java.util.Map.Entry<String, String> e : BookLang.map(lang).entrySet()) {
                if (!e.getKey().startsWith("sva.book.") && !e.getKey().startsWith("sva.tooltip.book.") && !e.getKey().equals("sva.tooltip.howto.book")
                        && !e.getKey().equals("item.siliconagevehicles.handbook.name")) {
                    continue;
                }
                n++;
                String v = e.getValue().replaceAll("§.", "");
                String p = BookMarkup.glyphProblem(v);
                if (p != null) {
                    bl.append(' ').append(e.getKey()).append(':').append(p);
                }
            }
        }
        check(bl.length() == 0 && n > 300, "book glyphs: the " + n + " lang lines of the book (ru + en) are glyph-safe" + bl);
        check(BookMarkup.glyphSafe('ё') && BookMarkup.glyphSafe('A') && !BookMarkup.glyphSafe('«') && !BookMarkup.glyphSafe('—')
                && !BookMarkup.glyphSafe('×') && !BookMarkup.glyphSafe('°') && !BookMarkup.glyphSafe(' ') && !BookMarkup.glyphSafe('→')
                && !BookMarkup.glyphSafe('★') && !BookMarkup.glyphSafe('№'), "book glyphs: ё / A allowed; guillemets, dash, x, degree, nbsp, arrow, star, numero refused");
    }

    static List<String> texts(BookDoc.Chapter c) {
        List<String> l = new ArrayList<String>();
        l.add(c.title);
        for (BookDoc.Block b : c.blocks) {
            l.add(b.text);
            l.add(b.arg);
        }
        return l;
    }

    // ---------------------------------------------------------------- references

    static void refs() {
        Set<String> chapters = new HashSet<String>(BookMarkup.index());
        BookResolveSVA res = new BookResolveSVA(BookResolveSVA.files("ru"));
        int recipes = 0, wb = 0, craft = 0, schem = 0, img = 0, check = 0, model = 0, links = 0;
        StringBuilder bad = new StringBuilder();
        for (String lang : BookMarkup.LANGS) {
            for (BookDoc.Chapter c : BookMarkup.get(lang).chapters) {
                if (c.icon.length() > 0 && BookRecipesSVA.item(c.icon) == null) {
                    bad.append(" icon:").append(c.icon);
                }
                for (BookDoc.Block b : c.blocks) {
                    switch (b.kind) {
                        case BookDoc.RECIPE: {
                            recipes++;
                            BookRecipesSVA.Grid g = BookRecipesSVA.resolve(b.arg);
                            if (g == null || g.out == null) {
                                bad.append(" recipe:").append(b.arg);
                            } else {
                                wb += g.workbench ? 1 : 0;
                                craft += g.workbench ? 0 : 1;
                                int filled = 0;
                                for (Object o : g.cells) {
                                    filled += o != null ? 1 : 0;
                                }
                                if (filled == 0 || g.cells.length != g.w * g.w || res.recipeSize(b.arg) == null) {
                                    bad.append(" emptyRecipe:").append(b.arg);
                                }
                            }
                            break;
                        }
                        case BookDoc.SCHEM:
                            schem++;
                            if (!BookSchematics.known(b.arg)) {
                                bad.append(" schem:").append(b.arg);
                            }
                            break;
                        case BookDoc.IMG:
                            img++;
                            if (res.imageSize(b.arg) == null) {
                                bad.append(" img:").append(b.arg);
                            }
                            break;
                        case BookDoc.CHECK:
                            check++;
                            if (BookChecklists.byId(b.arg) < 0) {
                                bad.append(" check:").append(b.arg);
                            }
                            break;
                        case BookDoc.VEHICLE:
                        case BookDoc.MODEL:
                            model += b.kind == BookDoc.MODEL ? 1 : 0;
                            if (BookResolveSVA.type(b.arg) == null) {
                                bad.append(" type:").append(b.arg);
                            }
                            break;
                        case BookDoc.LINK:
                            links++;
                            if (!chapters.contains(b.arg)) {
                                bad.append(" link:").append(b.arg);
                            }
                            break;
                        default:
                            break;
                    }
                }
            }
        }
        check(bad.length() == 0 && recipes >= 80 && wb >= 50 && craft >= 15 && schem >= 20 && img >= 4 && check >= 8 && model >= 22 && links >= 10,
                "book refs: " + recipes + " recipes (" + wb + " workbench, " + craft + " crafting table) resolve to registered ones, " + schem
                        + " schematics, " + img + " pictures, " + check + " checklists, " + model + " models, " + links + " links" + bad);
        // every vehicle has its page with stats, model and recipe
        StringBuilder miss = new StringBuilder();
        BookDoc.Chapter v = BookMarkup.get("ru").chapter("vehicles");
        for (VehicleType t : VehicleType.values()) {
            boolean st = false, md = false, rc = false;
            for (BookDoc.Block b : v.blocks) {
                st |= b.kind == BookDoc.VEHICLE && b.arg.equals(t.name());
                md |= b.kind == BookDoc.MODEL && b.arg.equals(t.name());
                rc |= b.kind == BookDoc.RECIPE && b.arg.equals("wb:" + t.key);
            }
            if (!(st && md && rc)) {
                miss.append(' ').append(t.name());
            }
        }
        check(miss.length() == 0, "book refs: every vehicle type has its stats, 3D model and workbench recipe" + miss);
        // a vehicle's stats come from VehicleType
        List<String[]> rows = res.vehicleRows("LINER");
        check(rows.size() >= 7 && rows.get(1)[1].startsWith("144 ") && rows.get(2)[1].equals("10") && rows.get(0)[1].equals("EV")
                && rows.get(5)[1].startsWith("48 000 "), "book refs: the liner's rows from VehicleType / FuelMath (EV, 144 km/h, 10 seats, 48 000 mB)");
        check(res.vehicleRows("SHIP").get(5)[1].equals(BookLang.tr("ru", "sva.book.veh.noH2")) && res.vehicleRows("NOPE").isEmpty()
                && BookRecipesSVA.resolve("wb:nope") == null && BookRecipesSVA.resolve("craft:siliconagevehicles:nope") == null,
                "book refs: the ship runs on EU (no H2 row value), unknown types / recipes give nothing");
        BookRecipesSVA.Grid book = BookRecipesSVA.resolve("craft:siliconagevehicles:handbook");
        check(book != null && book.shapeless && book.out.getItem() == com.sva.init.ModContentSVA.handbook && countFilled(book) == 2,
                "book: its own recipe - a book and metallurgical silicon (shapeless, registered)");
    }

    static int countFilled(BookRecipesSVA.Grid g) {
        int n = 0;
        for (Object o : g.cells) {
            n += o != null ? 1 : 0;
        }
        return n;
    }

    // ---------------------------------------------------------------- layout

    static void layout() {
        for (String lang : BookMarkup.LANGS) {
            BookDoc d = BookMarkup.get(lang);
            BookLayout L = BookLayout.layout(d, BookLayout.ESTIMATE, new BookResolveSVA(BookResolveSVA.files(lang)), BookExportSVA.REF_W, BookExportSVA.REF_H);
            boolean fits = true, order = true;
            for (int i = 0; i < L.pages.size(); i++) {
                BookLayout.Page p = L.pages.get(i);
                int y = 0;
                for (BookLayout.Item it : p.items) {
                    if (it.y < y || it.y + it.h > L.height && p.items.size() > 1) {
                        fits = false;
                    }
                    y = it.y;
                }
                if (i > 0 && p.chapter < L.pages.get(i - 1).chapter) {
                    order = false;
                }
            }
            boolean starts = true;
            for (int ci = 0; ci < d.chapters.size(); ci++) {
                BookLayout.Page p = L.pages.get(L.chapterStart[ci]);
                starts &= p.chapter == ci && p.items.get(0).block.kind == BookDoc.TITLE;
            }
            int linerPage = L.pageOf(d.indexOf("liner"), d.chapter("liner").pages().get(3).index);
            check(fits && order && starts && L.pages.size() >= 60 && linerPage > L.chapterStart[d.indexOf("liner")]
                    && L.anchorBlock(linerPage) <= d.chapter("liner").pages().get(3).index,
                    "book layout " + lang + ": " + d.chapters.size() + " chapters, " + L.pages.size() + " pages of " + BookExportSVA.REF_W + " x "
                            + BookExportSVA.REF_H + "; each chapter starts a page with its title, nothing runs over a page, anchors find their pages");
            System.out.println("[SVA-BOOK] " + lang + ": " + d.chapters.size() + " chapters, " + L.pages.size() + " pages");
        }
        List<String> w = BookLayout.wrap("aaa **bbb ccc ddd** eee", BookLayout.ESTIMATE, 50);
        check(w.size() >= 3 && w.get(0).equals("aaa §lbbb") && w.get(1).startsWith("§l") && w.get(w.size() - 1).contains("eee"),
                "book layout: word wrap keeps bold across lines (§l re-opened) " + w);
        List<String> lw = BookLayout.wrap("abcdefghijklmnopqrstuvwxyz", BookLayout.ESTIMATE, 30);
        check(lw.size() == 6 && lw.get(0).equals("abcde"), "book layout: a word longer than the line is cut by characters " + lw);
    }

    // ---------------------------------------------------------------- search

    static void search() {
        BookSearch ru = BookSearch.build(BookMarkup.get("ru")), en = BookSearch.build(BookMarkup.get("en"));
        BookDoc d = BookMarkup.get("ru");
        List<BookSearch.Hit> ils = ru.query("ILS");
        boolean airport = false;
        for (BookSearch.Hit h : ils) {
            airport |= d.chapters.get(h.chapter).id.equals("airport");
        }
        List<BookSearch.Hit> vzlet = ru.query("взлет"), vzlyot = ru.query("взлёт");
        List<BookSearch.Hit> apu = ru.query("запуск ВСУ");
        List<BookSearch.Hit> part = ru.query("автопос");
        check(ru.size() > 500 && en.size() > 500 && !ils.isEmpty() && airport && vzlet.size() == vzlyot.size() && !vzlet.isEmpty()
                && !apu.isEmpty() && !part.isEmpty() && ru.query("").isEmpty() && ru.query("zzzqqq").isEmpty(),
                "book search: " + ru.size() + " / " + en.size() + " blocks indexed; ILS -> the airport, взлет = взлёт, two words (AND), a word start, nothing for nonsense");
        boolean ordered = true, snip = true;
        for (int i = 1; i < ils.size(); i++) {
            ordered &= ils.get(i - 1).weight >= ils.get(i).weight;
        }
        for (BookSearch.Hit h : ils) {
            snip &= h.snippet.length() <= 70;
        }
        check(ordered && snip && ils.get(0).weight == 3 && ils.size() <= BookSearch.MAX_HITS, "book search: titles first, snippets of at most 70 characters, "
                + ils.size() + " hits for ILS");
        check(BookSearch.norm("Ёлка, ВСУ!").equals("елка  всу ") && BookSearch.words(" a  B ").size() == 2 && en.query("autoland").size() > 2,
                "book search: normalizing (case, ё, punctuation), words, English hits");
    }

    // ---------------------------------------------------------------- schematics

    static void schematics() {
        StringBuilder bad = new StringBuilder();
        for (int shape = 0; shape < AdShapeMath.SHAPES; shape++) {
            String id = "ad_" + new String[] {"survey", "track", "height", "gun", "missile", "kp"}[shape];
            for (int f = 0; f < 4; f++) {
                BookSchematics.Schem s = BookSchematics.get(id, f);
                int[][] w = AdShapeMath.worldCells(shape, f);
                if (s == null || s.cells.size() != w.length || s.layers().length != AdShapeMath.layers(shape)) {
                    bad.append(' ').append(id).append(f);
                    continue;
                }
                for (int i = 0; i < w.length; i++) {
                    BookSchematics.Cell c = s.cells.get(i);
                    int role = i == 0 ? BookSchematics.MASTER : w[i][3] == AdShapeMath.PAD ? BookSchematics.PAD : BookSchematics.PART;
                    if (c.x != w[i][0] || c.y != w[i][1] || c.z != w[i][2] || c.role != role) {
                        bad.append(' ').append(id).append(f).append('#').append(i);
                    }
                }
                if (s.list.get(0).count != 1 || s.list.get(0).auto || w.length > 1 && s.list.get(1).count != w.length - 1) {
                    bad.append(' ').append(id).append(":list");
                }
            }
        }
        check(bad.length() == 0, "book schematics: the six air-defence shapes x 4 facings = AdShapeMath.worldCells (cells, roles, layers, the list)" + bad);
        // the tower and the ILS antennas against their tiles' own definitions
        BookSchematics.Schem tw = BookSchematics.get("tower", 0);
        check(BookSchematics.TOWER_PARTS == com.sva.block.TileEntityTowerSVA.PARTS && tw.cells.size() == 1 + com.sva.block.TileEntityTowerSVA.PARTS
                && tw.layers().length == 6 && tw.cells.get(0).role == BookSchematics.MASTER, "book schematics: the tower = its master + TileEntityTowerSVA.PARTS straight up (6 layers)");
        boolean ant = true;
        for (int kind = 0; kind < 2; kind++) {
            for (int f = 0; f < 4; f++) {
                int[][] p = com.sva.block.TileEntityIlsAntennaSVA.parts(kind, f);
                BookSchematics.Schem s = BookSchematics.get(kind == 0 ? "ils_loc" : "ils_gs", f);
                ant &= s.cells.size() == p.length + 1;
                for (int i = 0; ant && i < p.length; i++) {
                    BookSchematics.Cell c = s.cells.get(i + 1);
                    ant &= c.x == p[i][0] && c.y == p[i][1] && c.z == p[i][2];
                }
            }
        }
        check(ant && BookSchematics.get("ils_loc", 0).layers().length == 1 && BookSchematics.get("ils_gs", 0).layers().length == 3,
                "book schematics: LOC (5 x 1 across the look) / G/S (3-block mast) = TileEntityIlsAntennaSVA.parts for every facing");
        // the air-defence complex: six structures, no two cells in one place, each one's cells as AdShapeMath at its offset
        boolean cx = true;
        for (int f = 0; f < 4; f++) {
            BookSchematics.Schem s = BookSchematics.get("ad_complex", f);
            Set<String> seen = new HashSet<String>();
            for (BookSchematics.Cell c : s.cells) {
                cx &= seen.add(c.x + "," + c.y + "," + c.z);
            }
            cx &= s.subs.size() == 6 && s.count(BookSchematics.MASTER) == 6;
            for (BookSchematics.Sub sub : s.subs) {
                cx &= sub.facing == f;
                int[][] w = AdShapeMath.worldCells(sub.shape, sub.facing);
                for (int[] c : w) {
                    cx &= seen.contains((sub.x + c[0]) + "," + c[1] + "," + (sub.z + c[2]));
                }
            }
        }
        check(cx, "book schematics: the air-defence site - six structures, no overlap in any facing, each = AdShapeMath at its master");
        // the ILS runway = the planner tablet's plan
        BookSchematics.Schem ap = BookSchematics.get("airport", 0);
        List<PlannerMath.Item> plan = PlannerMath.plan(BookSchematics.RWY_LEN, BookSchematics.RWY_WIDTH, BookSchematics.RWY_ANGLE);
        boolean locBeyond = false, gsRight = false;
        for (BookSchematics.Cell c : ap.cells) {
            if (c.planKind == PlannerMath.LOC) {
                locBeyond = c.box[0] > BookSchematics.RWY_LEN && c.z > BookSchematics.RWY_LEN;
            }
            if (c.planKind == PlannerMath.GS) {
                gsRight = c.box[2] > 0 && c.x < 0;                  // facing south: right of the landing direction is west (-x)
            }
        }
        double[] w0 = BookSchematics.runwayWorld(10, 0, 0), w2 = BookSchematics.runwayWorld(10, 3, 2);
        check(ap.plan && ap.cells.size() == plan.size() && locBeyond && gsRight && Math.abs(w0[0] - 0.5) < 1e-9 && Math.abs(w0[1] - 10.5) < 1e-9
                && Math.abs(w2[0] - 3.5) < 1e-9 && Math.abs(w2[1] + 9.5) < 1e-9 && ap.list.get(1).count == 2,
                "book schematics: the ILS runway = PlannerMath.plan (" + plan.size() + " items; LOC beyond the far end, G/S right), turned by the facing");
        check(BookSchematics.lookFor("ad_survey", 0) == 2 && BookSchematics.lookFor("ils_loc", 1) == 1 && BookSchematics.lookFor("tower", 3) == 1
                && AdShapeMath.facingFromYaw(180F) == 0, "book schematics: the look to place with (a structure faces its placer; the ILS antennas the look)");
    }

    // ---------------------------------------------------------------- checklists

    static void checklists() {
        StringBuilder bad = new StringBuilder();
        for (int l = 0; l < BookChecklists.LISTS; l++) {
            for (int i = 0; i < BookChecklists.size(l); i++) {
                String k = BookChecklists.key(l, i);
                if (!BookLang.has("ru", k) || !BookLang.has("en", k) || !BookLang.tr("ru", k).contains("|") || BookChecklists.control(l, i) == null) {
                    bad.append(' ').append(k);
                }
            }
        }
        check(bad.length() == 0 && BookChecklists.size(BookChecklists.STARTUP) == 16 && BookChecklists.size(BookChecklists.APPROACH) == 9
                && BookChecklists.size(BookChecklists.AFTER_LANDING) == 7, "book checklists: 4 lists, every item with its «WHAT|SETTING» in ru / en and its control" + bad);
        // the start-up on a simulated liner: the items tick as the real systems come up
        LinerSystems L = SelfTestLinerSVA.cold();
        int cold = BookChecklists.doneBits(BookChecklists.STARTUP, L);
        LinerSystems.Env e = SelfTestLinerSVA.apuUp(L, false);
        int apu = BookChecklists.doneBits(BookChecklists.STARTUP, L);
        L.set(LinerControls.ADIRS1, 1);
        L.set(LinerControls.ADIRS2, 1);
        L.set(LinerControls.ADIRS3, 1);
        L.set(LinerControls.BEACON, 0);
        L.set(LinerControls.PARK_BRK, 1);
        boolean beforeStartDone = (BookChecklists.doneBits(BookChecklists.STARTUP, L) & 0xFF) == 0xFF;
        L.set(LinerControls.ENG_MODE, 2);
        L.set(LinerControls.ENG_MASTER2, 1);
        int t2 = SelfTestLinerSVA.run(L, e, 1200, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems s) {
                return s.eng[1].running();
            }
        });
        L.set(LinerControls.ENG_MASTER1, 1);
        int t1 = SelfTestLinerSVA.run(L, e, 1200, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems s) {
                return s.eng[0].running();
            }
        });
        boolean engs = BookChecklists.done(BookChecklists.STARTUP, 8, L);
        int nextBefore = BookChecklists.next(BookChecklists.STARTUP, L);
        L.set(LinerControls.ENG_MODE, 1);
        L.set(LinerControls.APU_BLEED, 0);
        L.set(LinerControls.APU_MASTER, 0);
        L.set(LinerControls.PACK1, 1);
        L.set(LinerControls.PACK2, 1);
        int after = BookChecklists.doneBits(BookChecklists.STARTUP, L);
        check((cold & 1) == 0 && (apu & 1) != 0 && (apu & 4) != 0 && (apu & 8) != 0 && beforeStartDone && t2 > 0 && t1 > 0 && engs
                && nextBefore == 9 && (after & (0xF << 9)) == (0xF << 9),
                "book checklists: start-up on a simulated liner - cold nothing, APU up ticks BAT / power / APU bleed, before start complete, engines idle in "
                        + t2 + " + " + t1 + " ticks, then ENG MODE / APU / packs");
        // approach and after landing: each item flips with its control
        LinerSystems A = SelfTestLinerSVA.cold();
        int a0 = BookChecklists.doneBits(BookChecklists.APPROACH, A);
        A.set(LinerControls.LS_L, 1);
        A.set(LinerControls.SEAT_BELTS, 0);
        A.set(LinerControls.LAND_L, 0);
        A.set(LinerControls.LAND_R, 0);
        A.gear.abMode = 2;
        A.set(LinerControls.SPD_BRK, 0);
        A.set(LinerControls.GEAR, 1);
        A.set(LinerControls.FLAPS, 4);
        int a1 = BookChecklists.doneBits(BookChecklists.APPROACH, A);
        check((a1 & 0x1F9) == 0x1F9 && (a0 & 1) == 0 && (a0 & 0x100) == 0 && !BookChecklists.done(BookChecklists.APPROACH, 1, A)
                && BookChecklists.landingConfig(A) && BookChecklists.next(BookChecklists.APPROACH, A) == 1,
                "book checklists: approach - LS, belts, landing lights, autobrake MED, spoilers armed, gear down, flaps FULL tick; the ILS / APPR wait for a station");
        A.set(LinerControls.SPD_BRK, 1);
        A.set(LinerControls.FLAPS, 0);
        A.set(LinerControls.STROBE, 1);
        A.set(LinerControls.LAND_L, 2);
        A.set(LinerControls.LAND_R, 1);
        A.set(LinerControls.NOSE_LT, 1);
        A.gear.abMode = 0;
        int al = BookChecklists.doneBits(BookChecklists.AFTER_LANDING, A);
        check(al == 0x5F && BookChecklists.next(BookChecklists.AFTER_LANDING, A) == 5 && BookChecklists.control(BookChecklists.AFTER_LANDING, 5) == LinerControls.APU_START,
                "book checklists: after landing - all but the APU tick; the next item's control is APU START");
        check(BookChecklists.byId("approach") == BookChecklists.APPROACH && BookChecklists.byId("x") == -1
                && BookChecklists.control(BookChecklists.STARTUP, 0) == LinerControls.BAT1 && BookChecklists.control(BookChecklists.BEFORE_TO, 0) == LinerControls.TO_CONFIG,
                "book checklists: ids and the controls «Показать» outlines (BAT 1, T.O. CONFIG...)");
    }

    // ---------------------------------------------------------------- the tutorial

    static void tutorial() {
        TutorialMath.State s = new TutorialMath.State();
        TutorialMath.Obs o = new TutorialMath.Obs();
        o.L = SelfTestLinerSVA.cold();
        check(!TutorialMath.update(s, o) && s.step == TutorialMath.BAT, "tutorial: inactive - nothing moves");
        TutorialMath.restart(s);
        boolean noMove = !TutorialMath.update(s, o);
        o.L.set(LinerControls.BAT1, 1);
        o.L.set(LinerControls.BAT2, 1);
        TutorialMath.pause(s, true);
        boolean paused = !TutorialMath.update(s, o) && s.step == TutorialMath.BAT;
        TutorialMath.pause(s, false);
        boolean moved = TutorialMath.update(s, o) && s.step == TutorialMath.ADIRS;
        TutorialMath.skip(s);
        boolean skipped = s.step == TutorialMath.APU && (s.skipped & (1L << TutorialMath.ADIRS)) != 0 && TutorialMath.doneCount(s) == 1;
        TutorialMath.restart(s);
        check(noMove && paused && moved && skipped && s.step == TutorialMath.BAT && s.skipped == 0 && s.active,
                "tutorial: cold - waits; BAT 1 + 2 - moves on; paused - waits; skip counts as skipped; restart - from the first step");
        // the start-up steps on a simulated liner, one step an update
        TutorialMath.State t = new TutorialMath.State();
        TutorialMath.restart(t);
        LinerSystems L = SelfTestLinerSVA.cold();
        TutorialMath.Obs ob = new TutorialMath.Obs();
        ob.L = L;
        LinerSystems.Env e = SelfTestLinerSVA.env();
        L.set(LinerControls.BAT1, 1);
        L.set(LinerControls.BAT2, 1);
        TutorialMath.update(t, ob);
        L.set(LinerControls.ADIRS1, 1);
        L.set(LinerControls.ADIRS2, 1);
        L.set(LinerControls.ADIRS3, 1);
        TutorialMath.update(t, ob);
        L.set(LinerControls.APU_MASTER, 1);
        SelfTestLinerSVA.click(L, LinerControls.APU_START);
        final TutorialMath.State tt = t;
        final TutorialMath.Obs oo = ob;
        SelfTestLinerSVA.run(L, e, 2000, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems x) {
                TutorialMath.update(tt, oo);
                return tt.step > TutorialMath.APU;
            }
        });
        int afterApu = t.step;
        L.set(LinerControls.APU_BLEED, 1);
        L.set(LinerControls.PACK1, 0);
        L.set(LinerControls.PACK2, 0);
        SelfTestLinerSVA.run(L, e, 5, null);
        TutorialMath.update(t, ob);
        L.set(LinerControls.BEACON, 0);
        TutorialMath.update(t, ob);
        L.set(LinerControls.ENG_MODE, 2);
        TutorialMath.update(t, ob);
        L.set(LinerControls.ENG_MASTER2, 1);
        SelfTestLinerSVA.run(L, e, 1500, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems x) {
                TutorialMath.update(tt, oo);
                return tt.step > TutorialMath.ENG2;
            }
        });
        L.set(LinerControls.ENG_MASTER1, 1);
        SelfTestLinerSVA.run(L, e, 1500, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems x) {
                TutorialMath.update(tt, oo);
                return tt.step > TutorialMath.ENG1;
            }
        });
        L.set(LinerControls.ENG_MODE, 1);
        L.set(LinerControls.APU_BLEED, 0);
        TutorialMath.update(t, ob);
        check(afterApu == TutorialMath.APU_BLEED && t.step == TutorialMath.STARTUP_END + 1 && TutorialMath.doneCount(t) == TutorialMath.STARTUP_END + 1,
                "tutorial: the start-up on a simulated liner - BAT, ADIRS, APU (AVAIL), APU bleed, beacon, ENG MODE, ENG 2, ENG 1, after start - each by the real state");
        // the flight's bookkeeping: taxi, take-off height, climb, the turn's accumulation, touchdown, stop
        TutorialMath.State f = new TutorialMath.State();
        TutorialMath.restart(f);
        f.step = TutorialMath.TAXI;
        TutorialMath.Obs fo = new TutorialMath.Obs();
        fo.L = L;
        fo.ground = true;
        fo.y = 64;
        fo.kmh = 20;
        TutorialMath.update(f, fo);
        fo.ground = false;
        fo.y = 66;
        TutorialMath.update(f, fo);
        boolean y0 = Math.abs(f.y0 - 64) < 1e-9 && f.step == TutorialMath.GEAR_UP && f.airborne;
        L.set(LinerControls.GEAR, 0);
        TutorialMath.update(f, fo);
        fo.y = 64 + TutorialMath.CLIMB_H + 1;
        TutorialMath.update(f, fo);
        boolean climbed = f.step == TutorialMath.AP;
        f.step = TutorialMath.TURN;
        f.turned = 0;
        for (int i = 0; i <= 40; i++) {
            fo.hdg = (90 + i * 5) % 360;
            TutorialMath.update(f, fo);
        }
        boolean turned = f.step == TutorialMath.LS && f.turned >= TutorialMath.TURN_DEG;
        f.step = TutorialMath.TOUCHDOWN;
        fo.ground = true;
        fo.kmh = 80;
        TutorialMath.update(f, fo);
        fo.kmh = 1;
        TutorialMath.update(f, fo);
        check(y0 && climbed && turned && f.done() && TutorialMath.key(f).equals("done"),
                "tutorial: the take-off height (last on the ground), the 40-block climb, a 200 deg turn counted, touchdown after airborne, stopped - done");
        StringBuilder miss = new StringBuilder();
        for (String k : TutorialMath.STEPS) {
            for (String lang : BookMarkup.LANGS) {
                if (!BookLang.has(lang, "sva.book.tut." + k) || !BookLang.has(lang, "sva.book.tut." + k + ".hint")) {
                    miss.append(' ').append(lang).append(':').append(k);
                }
            }
        }
        check(miss.length() == 0 && TutorialMath.STEPS.length == TutorialMath.DONE && TutorialMath.control(TutorialMath.BAT) == LinerControls.BAT1
                && TutorialMath.control(TutorialMath.APPR) == LinerControls.FCU_APPR && TutorialMath.control(TutorialMath.TAXI) == null,
                "tutorial: " + TutorialMath.DONE + " steps, each with its prompt and hint in ru / en, the controls outlined" + miss);
    }

    // ---------------------------------------------------------------- give on join, config, lang families

    static void giveAndConfig() {
        check(BookGiveSVA.shouldGive(true, false) && !BookGiveSVA.shouldGive(true, true) && !BookGiveSVA.shouldGive(false, false),
                "book: given once - on, never given -> give; given before -> not again; off -> never");
        check(ConfigSVA.bookGiveOnJoin && ConfigSVA.bookLiveChecklists && ConfigSVA.bookHighlight && ConfigSVA.bookHologram && ConfigSVA.bookTutorial
                && ConfigSVA.bookModels, "book config: giveOnJoin, liveChecklists, highlight, hologram, tutorial, models - all on by default");
        check(BookMarkup.langOf("ru_RU").equals("ru") && BookMarkup.langOf("en_US").equals("en") && BookMarkup.langOf("de_DE").equals("en")
                && BookMarkup.langOf(null).equals("en"), "book: its language follows the game's (ru -> ru, anything else -> en)");
        check(BatteryMath.capacity(VehicleType.LINER) > 0 && BookResolveSVA.group(48000).equals("48 000") && BookResolveSVA.group(256000000).equals("256 000 000"),
                "book: numbers grouped with plain spaces (no thin space the font lacks)");
    }

    static void langFamilies() {
        StringBuilder miss = new StringBuilder();
        List<String> keys = new ArrayList<String>();
        for (String id : BookSchematics.IDS) {
            keys.add("sva.book.schem." + id);
        }
        for (int i = 0; i < 4; i++) {
            keys.add("sva.book.dir." + i);
        }
        for (String id : BookChecklists.IDS) {
            keys.add("sva.book.chk." + id);
        }
        keys.add("item.siliconagevehicles.handbook.name");
        for (String k : keys) {
            for (String lang : BookMarkup.LANGS) {
                if (!BookLang.has(lang, k)) {
                    miss.append(' ').append(lang).append(':').append(k);
                }
            }
        }
        check(miss.length() == 0, "book lang: the schematics' names, the directions, the checklists' titles, the item's name in ru / en" + miss);
    }

    // ---------------------------------------------------------------- the exporter

    static void export() throws Exception {
        // a fixed temporary folder: each run overwrites the same files (nothing piles up, nothing is deleted)
        File dir = new File(System.getProperty("java.io.tmpdir"), "sva-book-selftest");
        List<File> files = BookExportSVA.export(dir);
        com.google.gson.JsonObject js = new com.google.gson.JsonParser().parse(new java.io.InputStreamReader(new java.io.FileInputStream(new File(dir, "book.json")),
                "UTF-8")).getAsJsonObject();
        boolean ok = js.get("format").getAsInt() == BookExportSVA.FORMAT && js.getAsJsonArray("chapters").size() == 11;
        for (String lang : BookMarkup.LANGS) {
            com.google.gson.JsonObject l = js.getAsJsonObject("langs").getAsJsonObject(lang);
            ok &= l.getAsJsonArray("chapters").size() == 11 && l.get("pages").getAsInt() >= 60;
            com.google.gson.JsonArray blocks = l.getAsJsonArray("chapters").get(2).getAsJsonObject().getAsJsonArray("blocks");
            int rec = 0, rows = 0;
            for (com.google.gson.JsonElement b : blocks) {
                com.google.gson.JsonObject bo = b.getAsJsonObject();
                if (bo.has("recipe")) {
                    ok &= !bo.getAsJsonObject("recipe").has("missing") && bo.getAsJsonObject("recipe").getAsJsonArray("cells").size() == 25;
                    rec++;
                }
                if (bo.has("rows")) {
                    rows += bo.getAsJsonArray("rows").size();
                }
            }
            ok &= rec == 11 && rows >= 77;
        }
        ok &= js.getAsJsonObject("schematics").entrySet().size() == BookSchematics.IDS.length;
        int png = 0;
        for (File f : files) {
            if (f.getName().endsWith(".png")) {
                java.awt.image.BufferedImage im = javax.imageio.ImageIO.read(f);
                ok &= im != null && im.getWidth() > 16 && im.getHeight() > 16;
                png++;
            }
        }
        boolean layered = new File(dir, "schem/tower_L6.png").isFile() && new File(dir, "schem/ad_survey_L3.png").isFile() && new File(dir, "img/liner.png").isFile();
        check(ok && png >= BookSchematics.IDS.length + 10 && layered, "book export: book.json parses (format " + BookExportSVA.FORMAT
                + ", ru / en, 11 chapters, every vehicle's recipe and rows), " + png + " PNG renders by layer + pictures in " + dir.getName());
    }
}
