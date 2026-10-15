package com.sva.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.sc.manual.Lang;
import com.sva.ConfigSVA;
import com.sva.ReferenceSVA;
import com.sva.book.BookChecklists;
import com.sva.book.BookDoc;
import com.sva.book.BookLayout;
import com.sva.book.BookMarkup;
import com.sva.book.BookRecipesSVA;
import com.sva.book.BookResolveSVA;
import com.sva.book.BookSchematics;
import com.sva.book.BookSearch;
import com.sva.book.TutorialMath;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * Wave C, item 41: the handbook's screen - an open book of two pages on a dark cover. The pages come from BookLayout (the
 * book's text files laid out for this screen's page size and the real font); the cover's strip holds Contents, Back, the
 * search box (all pages of this language, then the other's), Bookmarks and the bookmark toggle, the page arrows. Pages turn
 * with the arrows (on the cover and the keyboard's), PgUp / PgDn and the wheel; Home - contents; Backspace - back; Ctrl+F or
 * «/» - the search; Esc - out of the search, then closed.
 * <p>
 * Live content: the recipes are the REGISTERED ones (BookRecipesSVA; a click opens NEI's recipe / usage screen when NEI is
 * loaded and the book comes back after it), the vehicles' models turn (drag to turn them), the schematics show layer by
 * layer with «Голограмма в мире» (BookHoloSVA), the checklists tick from the liner's systems when the book is opened in its
 * pilot seat with «Показать» per item (BookClientSVA.show), the tutorial's box starts / pauses / skips / restarts it.
 * <p>
 * Drawn at a fixed logical size scaled down on small screens (the layout stays the same), so the page numbers stay put.
 */
@SideOnly(Side.CLIENT)
public class GuiBookSVA extends GuiScreen {

    // the core handbook's colours (GuiBook) - the two books look alike
    static final int COVER = 0xFF1C2740, COVER_EDGE = 0xFF0E1526, PAPER = 0xFFF3EBD6, PAPER_EDGE = 0xFFC9B993, INK = 0x2A2622, INK_DIM = 0x6A6258,
            INK_HEAD = 0x1C3A6A, INK_H2 = 0x7A3E12, LINK = 0x1F5FB0, GOLD = 0xE6C850, GREEN = 0x2E8B3A, RED = 0xB0302A;
    static final int BAR = 20, HEAD = 14, FOOT = 14, PADX = 14;
    static final int M_TOC = 0, M_PAGES = 1, M_SEARCH = 2, M_MARKS = 3;

    private String lang;
    private BookDoc doc;
    private BookLayout lay;
    private BookSearch index;
    private BookResolveSVA res;
    private int mode = M_TOC, spread, tocHover = -1, hitScroll;
    private GuiTextField search;
    private List<BookSearch.Hit> hits = new ArrayList<BookSearch.Hit>();
    private boolean hitsOther;
    private final Deque<int[]> history = new ArrayDeque<int[]>();
    private int flashChapter = -1, flashBlock = -1;
    private long flashUntil;
    private String startAt;

    // geometry (logical, before the scale)
    private int bw, bh, ox, oy, pw, ch;
    private float scale = 1F;
    private int mx, my;

    // the frame's hover / clicks
    private ItemStack hoverStack;
    private List<String> hoverText;
    private final List<int[]> boxes = new ArrayList<int[]>();
    private final List<Object[]> acts = new ArrayList<Object[]>();

    // models / schematics: per block state
    private static final Map<String, float[]> MODEL = new HashMap<String, float[]>();
    private static final Map<String, int[]> SCHEM = new HashMap<String, int[]>();
    private String dragModel;
    private int dragX, dragY;

    /** A = action kinds. */
    static final int A_SPREAD = 0, A_TOC = 1, A_CHAPTER = 2, A_PAGE = 3, A_HIT = 4, A_MARKS = 5, A_MARK_TOGGLE = 6, A_MARK_OPEN = 7, A_MARK_DEL = 8,
            A_NEI = 9, A_LAYER = 10, A_ROTATE = 11, A_HOLO = 12, A_SHOW = 13, A_TUT = 14, A_LINK = 15, A_BACK = 16, A_ALL_LAYERS = 17;

    /** @param chapter a chapter id to open at, or null (where the book was left). */
    public GuiBookSVA(String chapter) {
        startAt = chapter;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;                                                // the liner keeps flying on its autopilot
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        String l = BookClientSVA.lang();
        boolean first = doc == null || !l.equals(lang);
        lang = l;
        doc = BookMarkup.get(lang);
        index = BookSearch.build(doc);
        res = new BookResolveSVA(new BookResolveSVA.Tr() {
            public String tr(String key) {
                return Lang.tr(key);
            }
        });
        bw = Math.min(560, Math.max(380, width - 12));
        bh = Math.min(330, Math.max(236, height - 10));
        scale = Math.min(1F, Math.min((width - 4) / (float) bw, (height - 4) / (float) bh));
        ox = (int) ((width - bw * scale) / 2 / scale);
        oy = (int) ((height - bh * scale) / 2 / scale);
        pw = (bw - 3 * PADX) / 2;
        ch = bh - BAR - HEAD - FOOT - 4;
        int anchorChapter = -1, anchorBlock = 0;
        if (lay != null && mode == M_PAGES && spread < lay.pages.size()) {
            anchorChapter = lay.pages.get(spread).chapter;
            anchorBlock = lay.anchorBlock(spread);
        }
        lay = BookLayout.layout(doc, new BookLayout.Measure() {
            public int width(String s) {
                return fontRendererObj.getStringWidth(s);
            }
        }, res, pw, ch);
        String q = search == null ? "" : search.getText();
        search = new GuiTextField(fontRendererObj, 0, 0, 120, 12);
        search.setMaxStringLength(40);
        search.setText(q);
        if (first) {
            if (startAt != null && doc.indexOf(startAt) >= 0) {
                open(doc.indexOf(startAt), 0, false);
            } else {
                String last = BookClientSVA.last(lang);
                int[] a = anchor(last);
                if (a != null) {
                    open(a[0], a[1], false);
                } else {
                    mode = M_TOC;
                }
            }
        } else if (anchorChapter >= 0) {
            open(anchorChapter, anchorBlock, false);
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        if (mode == M_PAGES && lay != null && spread < lay.pages.size()) {
            BookClientSVA.setLast(lang, anchorOf(spread));
        }
    }

    // ---------------------------------------------------------------- navigation

    /** "chapterId#block" of a page. */
    String anchorOf(int page) {
        BookLayout.Page p = lay.pages.get(page);
        return doc.chapters.get(p.chapter).id + "#" + lay.anchorBlock(page);
    }

    /** {chapter, block} of an anchor, or null. */
    int[] anchor(String a) {
        if (a == null || !a.contains("#")) {
            return null;
        }
        int ci = doc.indexOf(a.substring(0, a.indexOf('#')));
        if (ci < 0) {
            return null;
        }
        try {
            return new int[] {ci, Integer.parseInt(a.substring(a.indexOf('#') + 1))};
        } catch (NumberFormatException e) {
            return new int[] {ci, 0};
        }
    }

    /** Opens the spread showing a block. */
    void open(int chapter, int block, boolean remember) {
        if (remember) {
            history.push(new int[] {mode, spread});
        }
        int p = lay.pageOf(chapter, block);
        spread = p - p % 2;
        mode = M_PAGES;
    }

    void turn(int d) {
        if (mode != M_PAGES) {
            if (d > 0) {
                mode = M_PAGES;
                spread = 0;
            }
            return;
        }
        int n = spread + 2 * d;
        if (n < 0) {
            mode = M_TOC;
            return;
        }
        if (n < lay.pages.size()) {
            spread = n;
        }
    }

    void back() {
        if (!history.isEmpty()) {
            int[] h = history.pop();
            mode = h[0];
            spread = h[1];
        } else {
            mode = M_TOC;
        }
    }

    void runSearch() {
        String q = search.getText();
        hits = index.query(q);
        hitsOther = false;
        if (hits.isEmpty() && q.trim().length() > 0) {
            BookDoc other = BookMarkup.get("ru".equals(lang) ? "en" : "ru");
            List<BookSearch.Hit> h2 = BookSearch.build(other).query(q);
            if (!h2.isEmpty()) {
                hits = h2;                                          // found only in the other language: its hits open
                hitsOther = true;                                   // the same chapter's start here (the chapters match)
            }
        }
        hitScroll = 0;
        mode = q.trim().length() > 0 ? M_SEARCH : mode == M_SEARCH ? M_TOC : mode;
    }

    // ---------------------------------------------------------------- input

    @Override
    protected void keyTyped(char c, int key) {
        if (search.isFocused()) {
            if (key == Keyboard.KEY_ESCAPE) {
                if (search.getText().length() > 0) {
                    search.setText("");
                    runSearch();
                } else {
                    search.setFocused(false);
                }
                return;
            }
            if (key == Keyboard.KEY_RETURN && !hits.isEmpty()) {
                openHit(hits.get(0));
                search.setFocused(false);
                return;
            }
            if (search.textboxKeyTyped(c, key)) {
                runSearch();
            }
            return;
        }
        if (key == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        if (key == Keyboard.KEY_LEFT || key == Keyboard.KEY_PRIOR) {
            turn(-1);
        } else if (key == Keyboard.KEY_RIGHT || key == Keyboard.KEY_NEXT) {
            turn(1);
        } else if (key == Keyboard.KEY_HOME) {
            mode = M_TOC;
        } else if (key == Keyboard.KEY_BACK) {
            back();
        } else if (c == '/' || key == Keyboard.KEY_F && (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL))) {
            search.setFocused(true);
        } else if (key == mc.gameSettings.keyBindInventory.getKeyCode()) {
            mc.displayGuiScreen(null);
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d != 0) {
            if (mode == M_SEARCH) {
                hitScroll = Math.max(0, hitScroll + (d > 0 ? -1 : 1));
            } else {
                turn(d > 0 ? -1 : 1);
            }
        }
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        int lx = lx(x), ly = ly(y);
        int[] sb = searchBox();
        search.setFocused(lx >= sb[0] && lx < sb[0] + sb[2] && ly >= sb[1] && ly < sb[1] + sb[3]);
        if (search.isFocused()) {
            return;
        }
        for (int i = boxes.size() - 1; i >= 0; i--) {
            int[] b = boxes.get(i);
            if (lx >= b[0] && lx < b[0] + b[2] && ly >= b[1] && ly < b[1] + b[3]) {
                act(acts.get(i), button);
                return;
            }
        }
        dragModel = null;
    }

    @Override
    protected void mouseClickMove(int x, int y, int button, long time) {
        if (dragModel != null) {
            float[] r = MODEL.get(dragModel);
            if (r != null) {
                r[0] += (lx(x) - dragX) * 2F;
                r[1] = Math.max(-10F, Math.min(80F, r[1] + (ly(y) - dragY)));
                r[2] = net.minecraft.client.Minecraft.getSystemTime();
            }
            dragX = lx(x);
            dragY = ly(y);
        }
    }

    @Override
    protected void mouseMovedOrUp(int x, int y, int button) {
        if (button >= 0) {
            dragModel = null;
        }
    }

    int lx(int x) {
        return (int) (x / scale) - ox;
    }

    int ly(int y) {
        return (int) (y / scale) - oy;
    }

    void act(Object[] a, int button) {
        int kind = ((Integer) a[0]).intValue();
        mc.getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
        switch (kind) {
            case A_SPREAD:
                turn(((Integer) a[1]).intValue());
                break;
            case A_TOC:
                mode = M_TOC;
                break;
            case A_CHAPTER:
                open(((Integer) a[1]).intValue(), 0, true);
                break;
            case A_PAGE:
                open(((Integer) a[1]).intValue(), ((Integer) a[2]).intValue(), true);
                break;
            case A_HIT:
                openHit((BookSearch.Hit) a[1]);
                break;
            case A_MARKS:
                mode = mode == M_MARKS ? M_TOC : M_MARKS;
                break;
            case A_MARK_TOGGLE:
                toggleMark();
                break;
            case A_MARK_OPEN: {
                int[] an = anchor((String) a[1]);
                if (an != null) {
                    open(an[0], an[1], true);
                }
                break;
            }
            case A_MARK_DEL: {
                List<String> m = BookClientSVA.marks(lang);
                m.remove(a[1]);
                BookClientSVA.setMarks(lang, m);
                break;
            }
            case A_NEI:
                nei((ItemStack) a[1], button == 1);
                break;
            case A_LAYER: {
                int[] st = schemState((String) a[1]);
                int n = BookSchematics.get((String) a[1], st[1]).layers().length;
                st[0] = Math.max(0, Math.min(n - 1, (st[0] < 0 ? n - 1 : st[0]) + ((Integer) a[2]).intValue()));
                break;
            }
            case A_ALL_LAYERS:
                schemState((String) a[1])[0] = -1;
                break;
            case A_ROTATE: {
                int[] st = schemState((String) a[1]);
                st[1] = (st[1] + 1) & 3;
                break;
            }
            case A_HOLO:
                BookHoloSVA.start((String) a[1]);
                mc.displayGuiScreen(null);
                break;
            case A_SHOW:
                BookClientSVA.show(((Integer) a[1]).intValue(), ((Integer) a[2]).intValue());
                mc.displayGuiScreen(null);
                break;
            case A_TUT:
                tutorial(((Integer) a[1]).intValue());
                break;
            case A_LINK: {
                int ci = doc.indexOf((String) a[1]);
                if (ci >= 0) {
                    open(ci, 0, true);
                }
                break;
            }
            case A_BACK:
                back();
                break;
            default:
                break;
        }
    }

    void openHit(BookSearch.Hit h) {
        if (hitsOther) {
            open(Math.min(h.chapter, doc.chapters.size() - 1), 0, true);
            return;
        }
        open(h.chapter, h.block, true);
        flashChapter = h.chapter;
        flashBlock = h.block;
        flashUntil = net.minecraft.client.Minecraft.getSystemTime() + 2200;
    }

    void toggleMark() {
        if (mode != M_PAGES) {
            return;
        }
        String a = anchorOf(spread);
        List<String> m = BookClientSVA.marks(lang);
        if (!m.remove(a)) {
            m.add(a);
        }
        BookClientSVA.setMarks(lang, m);
    }

    boolean marked(int page) {
        return page < lay.pages.size() && BookClientSVA.marks(lang).contains(anchorOf(page));
    }

    void nei(ItemStack s, boolean usage) {
        if (s == null || !cpw.mods.fml.common.Loader.isModLoaded("NotEnoughItems")) {
            return;
        }
        BookClientSVA.returnAfterNei(this);
        if (usage) {
            com.sva.nei.NeiBookSVA.usage(s);
        } else {
            com.sva.nei.NeiBookSVA.recipes(s);
        }
    }

    /** The tutorial box's buttons: 0 start / restart, 1 pause / go on, 2 skip, 3 stop. */
    void tutorial(int b) {
        TutorialMath.State t = BookClientSVA.TUTORIAL;
        if (b == 0) {
            BookClientSVA.tutorialStart();
            mc.displayGuiScreen(null);
        } else if (b == 1) {
            TutorialMath.pause(t, !t.paused);
        } else if (b == 2) {
            TutorialMath.skip(t);
        } else {
            TutorialMath.stop(t);
        }
    }

    static String key(String kind, String id) {
        return kind + ":" + id;
    }

    int[] schemState(String id) {
        int[] s = SCHEM.get(id);
        if (s == null) {
            s = new int[] {-1, 0};
            SCHEM.put(id, s);
        }
        return s;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void drawScreen(int x, int y, float pt) {
        drawDefaultBackground();
        mx = lx(x);
        my = ly(y);
        hoverStack = null;
        hoverText = null;
        boxes.clear();
        acts.clear();
        GL11.glPushMatrix();
        GL11.glScalef(scale, scale, 1F);
        GL11.glTranslatef(ox, oy, 0F);
        cover();
        int lx = PADX, rx = PADX * 2 + pw, top = BAR + HEAD + 2;
        switch (mode) {
            case M_TOC:
                toc(lx, rx, top);
                break;
            case M_SEARCH:
                results(lx, rx, top);
                break;
            case M_MARKS:
                marksPage(lx, rx, top);
                break;
            default:
                page(spread, lx, top);
                page(spread + 1, rx, top);
                break;
        }
        bar();
        GL11.glPopMatrix();
        if (hoverStack != null) {
            renderToolTip(hoverStack, x, y);
        } else if (hoverText != null) {
            func_146283_a(hoverText, x, y);
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    void cover() {
        drawRect(-3, -3, bw + 3, bh + 3, COVER_EDGE);
        drawRect(0, 0, bw, bh, COVER);
        int py0 = BAR, py1 = bh - 4;
        drawRect(PADX - 6, py0, bw / 2 - 1, py1, PAPER_EDGE);
        drawRect(PADX - 5, py0 + 1, bw / 2 - 2, py1 - 1, PAPER);
        drawRect(bw / 2 + 1, py0, bw - PADX + 6, py1, PAPER_EDGE);
        drawRect(bw / 2 + 2, py0 + 1, bw - PADX + 5, py1 - 1, PAPER);
        // the spine's shade
        for (int i = 0; i < 6; i++) {
            int a = 0x30 - i * 7;
            drawRect(bw / 2 - 2 - i, py0 + 1, bw / 2 - 1 - i, py1 - 1, a << 24);
            drawRect(bw / 2 + 2 + i, py0 + 1, bw / 2 + 3 + i, py1 - 1, a << 24);
        }
    }

    /** The cover's strip: contents, back, search, bookmarks, the mark toggle, the arrows; the title. */
    void bar() {
        int x = 6;
        x += button(x, 4, Lang.tr("sva.book.gui.toc"), mode == M_TOC, new Object[] {Integer.valueOf(A_TOC)}) + 3;
        x += button(x, 4, "<", true, new Object[] {Integer.valueOf(A_BACK)}) + 8;
        int[] sb = searchBox();
        search.xPosition = sb[0];
        search.yPosition = sb[1];
        search.width = sb[2];
        search.drawTextBox();
        if (search.getText().length() == 0 && !search.isFocused()) {
            fontRendererObj.drawString(Lang.tr("sva.book.gui.searchHint"), sb[0] + 4, sb[1] + 2, 0x707070);
        }
        int rx = bw - 6;
        if (mode == M_PAGES) {
            String t = Lang.tr(marked(spread) ? "sva.book.gui.unmark" : "sva.book.gui.mark");
            rx -= fontRendererObj.getStringWidth(t) + 8;
            button(rx, 4, t, marked(spread), new Object[] {Integer.valueOf(A_MARK_TOGGLE)});
            rx -= 3;
        }
        String mk = Lang.tr("sva.book.gui.marks", Integer.valueOf(BookClientSVA.marks(lang).size()));
        rx -= fontRendererObj.getStringWidth(mk) + 8;
        button(rx, 4, mk, mode == M_MARKS, new Object[] {Integer.valueOf(A_MARKS)});
        // the page arrows at the lower corners
        if (mode == M_PAGES || mode == M_TOC) {
            arrow(PADX - 2, bh - 16, false);
            arrow(bw - PADX - 14, bh - 16, true);
        }
    }

    int[] searchBox() {
        int x0 = 6 + fontRendererObj.getStringWidth(Lang.tr("sva.book.gui.toc")) + 8 + 3 + fontRendererObj.getStringWidth("<") + 8 + 8;
        return new int[] {x0, 4, Math.min(150, bw / 3), 13};
    }

    void arrow(int x, int y, boolean right) {
        boolean hov = mx >= x && mx < x + 16 && my >= y && my < y + 12;
        String s = right ? ">>" : "<<";
        drawRect(x, y, x + 16, y + 12, hov ? 0xFF3A4A6A : 0xFF263452);
        fontRendererObj.drawString(s, x + 8 - fontRendererObj.getStringWidth(s) / 2, y + 2, hov ? 0xFFFFFF : 0xC8D0E0);
        click(x, y, 16, 12, new Object[] {Integer.valueOf(A_SPREAD), Integer.valueOf(right ? 1 : -1)});
    }

    /** A flat button; returns its width. */
    int button(int x, int y, String label, boolean on, Object[] act) {
        int w = fontRendererObj.getStringWidth(label) + 8;
        boolean hov = mx >= x && mx < x + w && my >= y && my < y + 13;
        drawRect(x, y, x + w, y + 13, on ? 0xFF3C6A4A : hov ? 0xFF3A4A6A : 0xFF263452);
        drawRect(x, y, x + w, y + 1, on ? 0xFF62E06A : 0xFF4A5A7A);
        fontRendererObj.drawString(label, x + 4, y + 3, hov || on ? 0xFFFFFF : 0xC8D0E0);
        click(x, y, w, 13, act);
        return w;
    }

    /** A small button on paper; returns its width. */
    int paperButton(int x, int y, String label, boolean enabled, Object[] act) {
        int w = fontRendererObj.getStringWidth(label) + 6;
        boolean hov = enabled && mx >= x && mx < x + w && my >= y && my < y + 11;
        drawRect(x, y, x + w, y + 11, enabled ? hov ? 0xFF5A7AB0 : 0xFF46608C : 0xFFB8B0A0);
        fontRendererObj.drawString(label, x + 3, y + 2, enabled ? 0xFFFFFF : 0xE8E0D0);
        if (enabled) {
            click(x, y, w, 11, act);
        }
        return w;
    }

    void click(int x, int y, int w, int h, Object[] act) {
        boxes.add(new int[] {x, y, w, h});
        acts.add(act);
    }

    boolean over(int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ---------------------------------------------------------------- the contents, the results, the bookmarks

    void toc(int lx, int rx, int top) {
        title(lx, top - HEAD, Lang.tr("sva.book.gui.contents"));
        fontRendererObj.drawString(Lang.tr("item.siliconagevehicles.handbook.name"), rx, top - HEAD, INK_DIM);
        int y = top + 4;
        int hov = -1;
        for (int ci = 0; ci < doc.chapters.size(); ci++) {
            BookDoc.Chapter c = doc.chapters.get(ci);
            boolean h = over(lx, y - 1, pw, 16);
            if (h) {
                hov = ci;
                drawRect(lx - 2, y - 2, lx + pw, y + 15, 0x20000000);
            }
            ItemStack icon = c.icon.length() > 0 ? BookRecipesSVA.item(c.icon) : null;
            if (icon != null) {
                item(icon, lx, y - 1, false);
            }
            String num = String.valueOf(lay.chapterStart[ci] + 1);
            String t = (ci + 1) + ". " + c.title;
            t = fontRendererObj.trimStringToWidth(t, pw - 20 - fontRendererObj.getStringWidth(num) - 6);
            fontRendererObj.drawString(t, lx + 20, y + 3, ci == tocHover ? LINK : INK);
            fontRendererObj.drawString(num, lx + pw - fontRendererObj.getStringWidth(num) - 2, y + 3, INK_DIM);
            click(lx - 2, y - 2, pw + 2, 17, new Object[] {Integer.valueOf(A_CHAPTER), Integer.valueOf(ci)});
            y += 17;
        }
        if (hov >= 0) {
            tocHover = hov;
        }
        int ci = tocHover < 0 ? 0 : tocHover;
        BookDoc.Chapter c = doc.chapters.get(ci);
        int ry = top + 4;
        fontRendererObj.drawString("§l" + fontRendererObj.trimStringToWidth(c.title, pw), rx, ry, INK_HEAD);
        ry += 14;
        for (BookDoc.Block b : c.pages()) {
            if (ry > top + ch - 10) {
                fontRendererObj.drawString("...", rx, ry, INK_DIM);
                break;
            }
            String num = String.valueOf(lay.pageOf(ci, b.index) + 1);
            String t = fontRendererObj.trimStringToWidth(b.text, pw - fontRendererObj.getStringWidth(num) - 10);
            boolean h = over(rx, ry - 1, pw, 11);
            fontRendererObj.drawString(t, rx + 4, ry, h ? LINK : INK);
            fontRendererObj.drawString(num, rx + pw - fontRendererObj.getStringWidth(num) - 2, ry, INK_DIM);
            click(rx, ry - 1, pw, 11, new Object[] {Integer.valueOf(A_PAGE), Integer.valueOf(ci), Integer.valueOf(b.index)});
            ry += 11;
        }
        footer(lx, Lang.tr("sva.book.gui.tocHint"));
    }

    void results(int lx, int rx, int top) {
        title(lx, top - HEAD, Lang.tr("sva.book.gui.results", Integer.valueOf(hits.size())) + (hitsOther ? " " + Lang.tr("sva.book.gui.otherLang") : ""));
        if (hits.isEmpty()) {
            fontRendererObj.drawSplitString(Lang.tr("sva.book.gui.noHits"), lx, top + 4, pw, INK_DIM);
            return;
        }
        int per = ch / 24;
        hitScroll = Math.max(0, Math.min(hitScroll, Math.max(0, hits.size() - 2 * per)));
        BookDoc d = hitsOther ? BookMarkup.get("ru".equals(lang) ? "en" : "ru") : doc;
        for (int i = 0; i < 2 * per && hitScroll + i < hits.size(); i++) {
            BookSearch.Hit h = hits.get(hitScroll + i);
            int x = i < per ? lx : rx, y = top + 2 + (i % per) * 24;
            boolean hv = over(x, y, pw, 23);
            if (hv) {
                drawRect(x - 2, y - 1, x + pw, y + 22, 0x18000000);
            }
            String ct = h.chapter < d.chapters.size() ? d.chapters.get(h.chapter).title : "";
            fontRendererObj.drawString(fontRendererObj.trimStringToWidth(ct, pw), x, y, INK_DIM);
            List<?> ls = fontRendererObj.listFormattedStringToWidth(h.snippet, pw);
            for (int k = 0; k < Math.min(2, ls.size()); k++) {
                fontRendererObj.drawString((String) ls.get(k), x, y + 10 + k * 9, hv ? LINK : INK);
            }
            click(x - 2, y - 1, pw + 2, 23, new Object[] {Integer.valueOf(A_HIT), h});
        }
        footer(lx, Lang.tr("sva.book.gui.searchFoot"));
    }

    void marksPage(int lx, int rx, int top) {
        List<String> m = BookClientSVA.marks(lang);
        title(lx, top - HEAD, Lang.tr("sva.book.gui.marksTitle"));
        int y = top + 4;
        if (m.isEmpty()) {
            fontRendererObj.drawSplitString(Lang.tr("sva.book.gui.noMarks"), lx, y, pw, INK_DIM);
        }
        for (String a : m) {
            int[] an = anchor(a);
            if (an == null || y > top + ch - 12) {
                continue;
            }
            int page = lay.pageOf(an[0], an[1]);
            BookDoc.Chapter c = doc.chapters.get(an[0]);
            String t = fontRendererObj.trimStringToWidth(c.title + ": " + pageTitle(page), pw - 40);
            boolean hv = over(lx, y - 1, pw - 16, 11);
            fontRendererObj.drawString(t, lx, y, hv ? LINK : INK);
            fontRendererObj.drawString(String.valueOf(page + 1), lx + pw - 34, y, INK_DIM);
            click(lx, y - 1, pw - 16, 11, new Object[] {Integer.valueOf(A_MARK_OPEN), a});
            paperButton(lx + pw - 14, y - 1, "x", true, new Object[] {Integer.valueOf(A_MARK_DEL), a});
            y += 13;
        }
        fontRendererObj.drawSplitString(Lang.tr("sva.book.gui.marksHelp"), rx, top + 4, pw, INK_DIM);
    }

    /** The page title (#) a page is under, or its chapter's title. */
    String pageTitle(int page) {
        BookLayout.Page p = lay.pages.get(page);
        String t = doc.chapters.get(p.chapter).title;
        for (int i = page; i >= 0 && lay.pages.get(i).chapter == p.chapter; i--) {
            for (BookLayout.Item it : lay.pages.get(i).items) {
                if (it.kind() == BookDoc.PAGE) {
                    return it.block.text;
                }
            }
        }
        return t;
    }

    void title(int x, int y, String t) {
        fontRendererObj.drawString("§l" + t, x, y + 2, INK_HEAD);
    }

    void footer(int x, String t) {
        fontRendererObj.drawString(fontRendererObj.trimStringToWidth(t, pw * 2), x, bh - 15, INK_DIM);
    }

    // ---------------------------------------------------------------- a page

    void page(int pi, int x, int top) {
        if (pi >= lay.pages.size()) {
            return;
        }
        BookLayout.Page p = lay.pages.get(pi);
        BookDoc.Chapter c = doc.chapters.get(p.chapter);
        fontRendererObj.drawString(fontRendererObj.trimStringToWidth(c.title, pw - 20), x, top - HEAD + 1, INK_DIM);
        if (marked(pi)) {
            drawRect(x + pw - 10, BAR, x + pw - 4, BAR + 16, 0xFFB0302A);
        }
        String num = String.valueOf(pi + 1);
        fontRendererObj.drawString(num, x + pw / 2 - fontRendererObj.getStringWidth(num) / 2, bh - 15, INK_DIM);
        long now = net.minecraft.client.Minecraft.getSystemTime();
        for (BookLayout.Item it : p.items) {
            int y = top + it.y;
            if (it.block.index == flashBlock && p.chapter == flashChapter && now < flashUntil) {
                drawRect(x - 2, y - 1, x + pw, y + it.h - 1, 0x60F0D040);
            }
            item(it, p.chapter, x, y);
        }
    }

    void item(BookLayout.Item it, int chapter, int x, int y) {
        BookDoc.Block b = it.block;
        if (it.row == -2) {
            fontRendererObj.drawString(Lang.tr("sva.book.gui.missing", BookDoc.KIND_NAMES[b.kind] + " " + b.arg), x, y, RED);
            return;
        }
        switch (b.kind) {
            case BookDoc.TITLE: {
                GL11.glPushMatrix();
                GL11.glTranslatef(x, y + 2, 0);
                GL11.glScalef(1.25F, 1.25F, 1F);
                int yy = 0;
                for (String s : it.lines) {
                    fontRendererObj.drawString(s, 0, yy, INK_HEAD);
                    yy += 10;
                }
                GL11.glPopMatrix();
                drawRect(x, y + it.h - 6, x + pw, y + it.h - 5, 0xFF1C3A6A);
                break;
            }
            case BookDoc.PAGE: {
                int yy = y;
                for (String s : it.lines) {
                    fontRendererObj.drawString("§l" + s, x, yy, INK_HEAD);
                    yy += 10;
                }
                drawRect(x, yy, x + pw, yy + 1, 0x60C9B993 | 0xFF000000);
                break;
            }
            case BookDoc.H:
                lines(it.lines, x, y, INK_H2);
                break;
            case BookDoc.P:
                lines(it.lines, x, y, INK);
                break;
            case BookDoc.LI:
                if (it.row == 0) {
                    drawRect(x + 2, y + 3, x + 5, y + 6, 0xFF7A3E12);
                }
                lines(it.lines, x + BookLayout.INDENT, y, INK);
                break;
            case BookDoc.NOTE:
                drawRect(x, y, x + pw, y + it.h - BookLayout.GAP, 0xFF9DB4CC);
                drawRect(x + 1, y + 1, x + pw - 1, y + it.h - BookLayout.GAP - 1, 0xFFE4ECF4);
                lines(it.lines, x + BookLayout.NOTE_PAD + 1, y + BookLayout.NOTE_PAD, INK);
                break;
            case BookDoc.KV:
            case BookDoc.VEHICLE: {
                if (b.kind == BookDoc.VEHICLE && it.row % 2 == 0) {
                    drawRect(x - 1, y - 1, x + pw, y + it.h - 1, 0x14000000);
                }
                int lw = (int) (pw * BookLayout.KV_LEFT);
                lines(it.left, x, y, INK_DIM);
                lines(it.lines, x + lw, y, INK);
                break;
            }
            case BookDoc.RECIPE:
                recipe(b, x, y);
                break;
            case BookDoc.MODEL:
                model(b, chapter, x, y);
                break;
            case BookDoc.SCHEM:
                if (it.row < 0) {
                    schem(b, x, y);
                } else {
                    schemRow(b, it.row, x, y);
                }
                break;
            case BookDoc.IMG:
                image(b, it, x, y);
                break;
            case BookDoc.CHECK:
                check(b, it.row, x, y);
                break;
            case BookDoc.TUTORIAL:
                tutorialBox(x, y);
                break;
            case BookDoc.LINK: {
                boolean hv = over(x, y, pw, it.h);
                lines(it.lines, x + BookLayout.INDENT, y, hv ? 0x3F8FE0 : LINK);
                fontRendererObj.drawString(">", x + 1, y, LINK);
                click(x, y, pw, it.h, new Object[] {Integer.valueOf(A_LINK), b.arg});
                break;
            }
            default:
                break;
        }
    }

    void lines(List<String> l, int x, int y, int col) {
        for (String s : l) {
            fontRendererObj.drawString(s, x, y, col);
            y += BookLayout.LINE;
        }
    }

    /** An item icon (and its hover / NEI click). */
    void item(ItemStack s, int x, int y, boolean clickable) {
        if (s == null) {
            return;
        }
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        RenderHelper.enableGUIStandardItemLighting();
        itemRender.zLevel = 50F;
        try {
            itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), s, x, y);
            if (s.stackSize > 1) {
                itemRender.renderItemOverlayIntoGUI(fontRendererObj, mc.getTextureManager(), s, x, y);
            }
        } catch (Throwable t) {
            // a broken icon: nothing drawn
        }
        itemRender.zLevel = 0F;
        RenderHelper.disableStandardItemLighting();
        GL11.glPopAttrib();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        if (over(x, y, 16, 16)) {
            hoverStack = s;
            if (clickable) {
                drawRect(x, y, x + 16, y + 16, 0x40FFFFFF);
            }
        }
        if (clickable) {
            click(x, y, 16, 16, new Object[] {Integer.valueOf(A_NEI), s});
        }
    }

    void slot(int x, int y) {
        drawRect(x, y, x + 18, y + 18, 0xFF8B8B8B);
        drawRect(x, y, x + 17, y + 17, 0xFF373737);
        drawRect(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
        drawRect(x + 1, y + 1, x + 17, y + 17, 0xFFC6C6C6 & 0xFF9A9A9A);
    }

    /** The resolved recipes (looked up once a screen: a crafting-table ref walks the whole recipe list). */
    private final Map<String, BookRecipesSVA.Grid> grids = new HashMap<String, BookRecipesSVA.Grid>();

    void recipe(BookDoc.Block b, int x, int y) {
        if (!grids.containsKey(b.arg)) {
            grids.put(b.arg, BookRecipesSVA.resolve(b.arg));
        }
        BookRecipesSVA.Grid g = grids.get(b.arg);
        if (g == null) {
            return;
        }
        String cap = g.workbench ? Lang.tr("sva.book.gui.wb", g.tier) : Lang.tr(g.shapeless ? "sva.book.gui.craftShapeless" : "sva.book.gui.craft");
        fontRendererObj.drawString(fontRendererObj.trimStringToWidth(cap, pw), x, y, INK_DIM);
        int gy = y + 10, gx = x + 2;
        long now = net.minecraft.client.Minecraft.getSystemTime();
        for (int i = 0; i < g.w * g.w; i++) {
            int cx = gx + (i % g.w) * 18, cy = gy + (i / g.w) * 18;
            slot(cx, cy);
        }
        for (int i = 0; i < g.w * g.w; i++) {
            int cx = gx + (i % g.w) * 18, cy = gy + (i / g.w) * 18;
            ItemStack s = BookRecipesSVA.pick(g.cells[i], now);
            if (s != null) {
                item(s, cx + 1, cy + 1, true);
            }
        }
        int ax = gx + g.w * 18 + 6, ay = gy + g.w * 9 - 4;
        fontRendererObj.drawString("=>", ax, ay, INK);
        slot(ax + 16, ay - 5);
        item(g.out, ax + 17, ay - 4, true);
        if (g.workbench) {
            String info = (g.eu > 0 ? BookResolveSVA.group(g.eu) + " EU, " + (g.ticks / 20) + " " + Lang.tr("sva.book.unit.sec") : Lang.tr("sva.book.gui.instant"));
            fontRendererObj.drawString(fontRendererObj.trimStringToWidth(info, pw), x, gy + g.w * 18 + 2, INK_DIM);
        }
        if (over(gx, gy, g.w * 18, g.w * 18) && hoverStack == null && cpw.mods.fml.common.Loader.isModLoaded("NotEnoughItems")) {
            hoverText = java.util.Collections.singletonList(Lang.tr("sva.book.gui.neiHint"));
        }
    }

    void model(BookDoc.Block b, int chapter, int x, int y) {
        int h = BookLayout.MODEL_H;
        drawRect(x, y, x + pw, y + h, 0xFF22303E);
        drawRect(x + 1, y + 1, x + pw - 1, y + h - 1, 0xFF34495E);
        VehicleType t = BookResolveSVA.type(b.arg);
        if (t == null || !ConfigSVA.bookModels) {
            fontRendererObj.drawString(Lang.tr("sva.book.gui.modelOff"), x + 4, y + 4, 0xC8D0E0);
            return;
        }
        String k = key("m", b.arg);
        float[] r = MODEL.get(k);
        if (r == null) {
            r = new float[] {30F, 25F, 0F};
            MODEL.put(k, r);
        }
        long now = net.minecraft.client.Minecraft.getSystemTime();
        if (dragModel == null && now - r[2] > 1500) {
            r[0] = (r[0] + 0.6F) % 360F;
        }
        GL11.glPushMatrix();
        GL11.glTranslatef(x + pw / 2F, y + h / 2F + 4, 0F);
        try {
            RenderItemVehicleSVA.renderInBook(t, r[0], r[1], h - 12);
        } catch (Throwable e) {
            // a model that cannot draw: the box stays empty
        }
        GL11.glPopMatrix();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        fontRendererObj.drawString(Lang.tr("sva.book.gui.drag"), x + 4, y + h - 11, 0x9AA8B8);
        if (over(x, y, pw, h) && Mouse.isButtonDown(0) && dragModel == null) {
            dragModel = k;
            dragX = mx;
            dragY = my;
        }
    }

    void schem(BookDoc.Block b, int x, int y) {
        int[] st = schemState(b.arg);
        BookSchematics.Schem s = BookSchematics.get(b.arg, st[1]);
        if (s == null) {
            return;
        }
        int vh = 104;
        drawRect(x, y, x + pw, y + vh, 0xFFDCD4BE);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        scissor(x, y, pw, vh);
        if (s.plan) {
            BookDrawSVA.plan(s, fontRendererObj, x + 2, y + 2, pw - 4, vh - 4);
        } else {
            BookDrawSVA.iso(s, st[0], x, y, pw, vh);
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        int n = s.layers().length;
        int cur = st[0] < 0 ? n : st[0] + 1;
        int ry = y + vh + 3;
        int bx = x;
        if (!s.plan) {
            bx += paperButton(bx, ry, "<", n > 1, new Object[] {Integer.valueOf(A_LAYER), b.arg, Integer.valueOf(-1)}) + 3;
            String lt = Lang.tr("sva.book.gui.layer", Integer.valueOf(cur), Integer.valueOf(n));
            fontRendererObj.drawString(lt, bx, ry + 2, INK);
            bx += fontRendererObj.getStringWidth(lt) + 3;
            bx += paperButton(bx, ry, ">", n > 1, new Object[] {Integer.valueOf(A_LAYER), b.arg, Integer.valueOf(1)}) + 3;
            bx += paperButton(bx, ry, Lang.tr("sva.book.gui.allLayers"), st[0] >= 0, new Object[] {Integer.valueOf(A_ALL_LAYERS), b.arg}) + 3;
        } else {
            fontRendererObj.drawString(Lang.tr("sva.book.gui.planNote"), bx, ry + 2, INK_DIM);
        }
        int r2 = ry + 14;
        bx = x;
        bx += paperButton(bx, r2, Lang.tr("sva.book.gui.rotate", Lang.tr("sva.book.dir." + st[1])), true,
                new Object[] {Integer.valueOf(A_ROTATE), b.arg}) + 3;
        paperButton(bx, r2, Lang.tr("sva.book.gui.holo"), ConfigSVA.bookHologram, new Object[] {Integer.valueOf(A_HOLO), b.arg});
    }

    void schemRow(BookDoc.Block b, int row, int x, int y) {
        BookSchematics.Schem s = BookSchematics.get(b.arg, 0);
        if (s == null || row >= s.list.size()) {
            return;
        }
        BookSchematics.Entry e = s.list.get(row);
        ItemStack st = BookRecipesSVA.item(e.item);
        if (st != null && !e.auto) {
            item(st, x + 1, y + 1, true);
        } else {
            drawRect(x + 3, y + 3, x + 15, y + 15, 0xFFB8B0A0);
        }
        String name = e.key.length() > 0 ? Lang.tr(e.key) : st != null ? st.getDisplayName() : e.item;
        String cnt = "x " + e.count;
        fontRendererObj.drawString(fontRendererObj.trimStringToWidth(name, pw - 30 - fontRendererObj.getStringWidth(cnt)), x + 20, y + 5,
                e.auto ? INK_DIM : INK);
        fontRendererObj.drawString(cnt, x + pw - fontRendererObj.getStringWidth(cnt) - 2, y + 5, INK_H2);
    }

    void image(BookDoc.Block b, BookLayout.Item it, int x, int y) {
        int[] sz = res.imageSize(b.arg);
        if (sz == null) {
            return;
        }
        int h = res.imageHeight(b.arg, pw);
        int w = (int) Math.round(sz[0] * (h / (double) sz[1]));
        int ix = x + (pw - w) / 2;
        mc.getTextureManager().bindTexture(new ResourceLocation(ReferenceSVA.ASSETS, "textures/book/" + b.arg + ".png"));
        GL11.glColor4f(1F, 1F, 1F, 1F);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.addVertexWithUV(ix, y + h, zLevel, 0, 1);
        t.addVertexWithUV(ix + w, y + h, zLevel, 1, 1);
        t.addVertexWithUV(ix + w, y, zLevel, 1, 0);
        t.addVertexWithUV(ix, y, zLevel, 0, 0);
        t.draw();
        lines(it.lines, x, y + h + 2, INK_DIM);
    }

    void check(BookDoc.Block b, int row, int x, int y) {
        int list = BookChecklists.byId(b.arg);
        if (list < 0) {
            return;
        }
        LinerSystems L = ConfigSVA.bookLiveChecklists ? BookClientSVA.pilotedSystems() : null;
        if (row < 0) {
            drawRect(x, y, x + pw, y + 12, 0xFF1C3A6A);
            fontRendererObj.drawString("§l" + Lang.tr("sva.book.chk." + BookChecklists.IDS[list]), x + 3, y + 2, 0xFFFFFF);
            String st = Lang.tr(L != null ? "sva.book.gui.live" : "sva.book.gui.notLive");
            fontRendererObj.drawString(st, x + pw - fontRendererObj.getStringWidth(st) - 3, y + 2, L != null ? 0x62E06A : 0xA8B4C8);
            return;
        }
        boolean done = L != null && BookChecklists.done(list, row, L);
        boolean next = L != null && BookChecklists.next(list, L) == row;
        if (next) {
            drawRect(x - 1, y - 1, x + pw, y + 11, 0x30F0B040);
        }
        drawRect(x + 1, y + 1, x + 10, y + 10, 0xFF6A6258);
        drawRect(x + 2, y + 2, x + 9, y + 9, done ? 0xFF2E8B3A : 0xFFF8F2E2);
        if (done) {
            // a tick of two strokes (no glyph: the font has none)
            drawRect(x + 3, y + 5, x + 5, y + 7, 0xFFFFFFFF);
            drawRect(x + 5, y + 4, x + 7, y + 6, 0xFFFFFFFF);
            drawRect(x + 6, y + 3, x + 8, y + 5, 0xFFFFFFFF);
        }
        String[] p = Lang.tr(BookChecklists.key(list, row)).split("\\|");
        String what = p[0], set = p.length > 1 ? p[1] : "";
        int bwid = L != null ? fontRendererObj.getStringWidth(Lang.tr("sva.book.gui.show")) + 8 : 0;
        int avail = pw - 14 - bwid;
        String setW = fontRendererObj.trimStringToWidth(set, avail / 2);
        String whatW = fontRendererObj.trimStringToWidth(what, avail - fontRendererObj.getStringWidth(setW) - 8);
        fontRendererObj.drawString(whatW, x + 13, y + 2, done ? GREEN : INK);
        fontRendererObj.drawString(setW, x + 13 + avail - fontRendererObj.getStringWidth(setW), y + 2, done ? GREEN : LINK);
        if (L != null) {
            paperButton(x + pw - bwid + 2, y, Lang.tr("sva.book.gui.show"), !done,
                    new Object[] {Integer.valueOf(A_SHOW), Integer.valueOf(list), Integer.valueOf(row)});
        }
    }

    void tutorialBox(int x, int y) {
        int h = BookLayout.TUT_H - 4;
        drawRect(x, y, x + pw, y + h, 0xFF1C3A6A);
        drawRect(x + 1, y + 1, x + pw - 1, y + h - 1, 0xFFE4ECF4);
        TutorialMath.State t = BookClientSVA.TUTORIAL;
        fontRendererObj.drawString("§l" + Lang.tr("sva.book.tut.title"), x + 4, y + 4, INK_HEAD);
        String st;
        if (!ConfigSVA.bookTutorial) {
            st = Lang.tr("sva.book.tut.off");
        } else if (!t.active) {
            st = Lang.tr("sva.book.tut.idle");
        } else if (t.done()) {
            st = Lang.tr("sva.book.tut.finished", Integer.valueOf(TutorialMath.doneCount(t)), Integer.valueOf(TutorialMath.DONE));
        } else {
            st = Lang.tr("sva.book.tut.head", Integer.valueOf(t.step + 1), Integer.valueOf(TutorialMath.DONE)) + ": "
                    + Lang.tr("sva.book.tut." + TutorialMath.STEPS[t.step]) + (t.paused ? " (" + Lang.tr("sva.book.tut.pausedTag") + ")" : "");
        }
        List<?> ls = fontRendererObj.listFormattedStringToWidth(st, pw - 8);
        for (int i = 0; i < Math.min(4, ls.size()); i++) {
            fontRendererObj.drawString((String) ls.get(i), x + 4, y + 16 + i * 10, INK);
        }
        boolean liner = BookClientSVA.pilotedLiner() != null;
        if (!liner) {
            fontRendererObj.drawString(fontRendererObj.trimStringToWidth(Lang.tr("sva.book.tut.noLiner"), pw - 8), x + 4, y + h - 30, RED);
        }
        int bx = x + 4, by = y + h - 15;
        boolean on = ConfigSVA.bookTutorial;
        bx += paperButton(bx, by, Lang.tr(t.active ? "sva.book.tut.restart" : "sva.book.tut.start"), on, new Object[] {Integer.valueOf(A_TUT), Integer.valueOf(0)})
                + 3;
        bx += paperButton(bx, by, Lang.tr(t.paused ? "sva.book.tut.resume" : "sva.book.tut.pause"), on && t.active && !t.done(),
                new Object[] {Integer.valueOf(A_TUT), Integer.valueOf(1)}) + 3;
        bx += paperButton(bx, by, Lang.tr("sva.book.tut.skip"), on && t.active && !t.done(), new Object[] {Integer.valueOf(A_TUT), Integer.valueOf(2)})
                + 3;
        paperButton(bx, by, Lang.tr("sva.book.tut.stop"), t.active, new Object[] {Integer.valueOf(A_TUT), Integer.valueOf(3)});
    }

    /** glScissor for a logical box (the screen's scale and the book's). */
    void scissor(int x, int y, int w, int h) {
        net.minecraft.client.gui.ScaledResolution sr = new net.minecraft.client.gui.ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        double k = sr.getScaleFactor() * scale;
        int sx = (int) ((x + ox) * k), sy = (int) (mc.displayHeight - (y + oy + h) * k);
        GL11.glScissor(Math.max(0, sx), Math.max(0, sy), Math.max(1, (int) (w * k)), Math.max(1, (int) (h * k)));
    }
}
