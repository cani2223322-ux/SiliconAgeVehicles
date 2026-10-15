package com.sva.client;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sva.ConfigSVA;
import com.sva.book.BookChecklists;
import com.sva.book.BookMarkup;
import com.sva.book.TutorialMath;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerGuide;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.LinerView;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;

/**
 * Wave C (items 41, 45, 46): the handbook's client state - the reader's bookmarks and the place he left the book (a small
 * file per player in config/, client-side only), the control outline the book asks for (LinerCockpitSVA.highlight: the
 * checklist's «Показать» for 30 s / until done, or the tutorial's step), the tutorial's state machine (TutorialMath, fed every
 * client tick from the liner the player pilots - its synced LinerSystems and the entity's motion) with its prompt panel on
 * the screen, and the way back into the book after NEI's recipe screens.
 */
@SideOnly(Side.CLIENT)
public final class BookClientSVA {

    public static final BookClientSVA INSTANCE = new BookClientSVA();

    /** The tutorial's state (one per client). */
    public static final TutorialMath.State TUTORIAL = new TutorialMath.State();
    /** «Показать»: the control and until when (ms); -1 none. */
    private static int showCtl = -1, showList = -1, showItem = -1;
    private static long showUntil;
    public static final long SHOW_MS = 30000;
    /** The book that opened NEI's recipes (it comes back when they close). */
    private static GuiBookSVA afterNei;

    private BookClientSVA() {
    }

    public static void register() {
        FMLCommonHandler.instance().bus().register(INSTANCE);
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        BookHoloSVA.register();
    }

    // ---------------------------------------------------------------- bookmarks and the last place (config/SiliconAgeVehicles_book.txt)

    /** player uuid -> "lang|last|mark;mark..." (a mark = chapterId#block). */
    private static Map<String, String> store;

    private static File file() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "config"), "SiliconAgeVehicles_book.txt");
    }

    private static Map<String, String> store() {
        if (store == null) {
            store = new LinkedHashMap<String, String>();
            try {
                File f = file();
                if (f.isFile()) {
                    for (String line : new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").split("\n")) {
                        int eq = line.indexOf('=');
                        if (eq > 0) {
                            store.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                        }
                    }
                }
            } catch (Exception e) {
                // unreadable: no bookmarks
            }
        }
        return store;
    }

    private static void save() {
        try {
            StringBuilder b = new StringBuilder("# Silicon Age: Vehicles - the handbook's bookmarks (per player and language)\n");
            for (Map.Entry<String, String> e : store().entrySet()) {
                b.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            File f = file();
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), b.toString().getBytes("UTF-8"));
        } catch (Exception e) {
            // the bookmarks stay for this session
        }
    }

    private static String who(String lang) {
        Minecraft mc = Minecraft.getMinecraft();
        String id = mc.thePlayer != null ? mc.thePlayer.getUniqueID().toString() : mc.getSession() != null ? mc.getSession().getUsername() : "anon";
        return id + "." + lang;
    }

    /** The bookmarks (anchors "chapter#block") of the local player in a language. */
    public static List<String> marks(String lang) {
        List<String> l = new ArrayList<String>();
        String v = store().get(who(lang) + ".marks");
        if (v != null) {
            for (String s : v.split(";")) {
                if (s.contains("#")) {
                    l.add(s);
                }
            }
        }
        return l;
    }

    public static void setMarks(String lang, List<String> marks) {
        StringBuilder b = new StringBuilder();
        for (String m : marks) {
            b.append(b.length() > 0 ? ";" : "").append(m);
        }
        store().put(who(lang) + ".marks", b.toString());
        save();
    }

    /** Where the book was left ("chapter#block"), or null. */
    public static String last(String lang) {
        return store().get(who(lang) + ".last");
    }

    public static void setLast(String lang, String anchor) {
        if (anchor != null && !anchor.equals(store().get(who(lang) + ".last"))) {
            store().put(who(lang) + ".last", anchor);
            save();
        }
    }

    /** The book's language now (the game's). */
    public static String lang() {
        Minecraft mc = Minecraft.getMinecraft();
        return BookMarkup.langOf(mc.gameSettings == null ? "en_US" : mc.gameSettings.language);
    }

    // ---------------------------------------------------------------- the liner the player pilots

    /** The liner the local player sits in a pilot seat of (the checklists are live, «Показать» works), or null. */
    public static EntityVehicleSVA pilotedLiner() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) {
            return null;
        }
        EntityVehicleSVA v = HudVehicleSVA.vehicleOf(mc.thePlayer);
        return v != null && !v.isDead && v.getVehicleType() == VehicleType.LINER && LinerClickSVA.pilotSeat(mc.thePlayer, v) ? v : null;
    }

    /** Its systems (the client's synced copy), or null. */
    public static LinerSystems pilotedSystems() {
        EntityVehicleSVA v = pilotedLiner();
        return v == null ? null : v.liner();
    }

    /** «Показать» on a checklist item: outline its control, turn the look to its panel. */
    public static void show(int list, int item) {
        LinerControls.Ctl c = BookChecklists.control(list, item);
        showCtl = c.index;
        showList = list;
        showItem = item;
        showUntil = Minecraft.getSystemTime() + SHOW_MS;
        LinerViewSVA.show(viewOf(c));
    }

    /** The quick view that looks at a control's panel. */
    static int viewOf(LinerControls.Ctl c) {
        switch (c.panel) {
            case LinerControls.OVH:
                return LinerView.OVHD;
            case LinerControls.PED:
                return LinerView.PED;
            case LinerControls.GS:
                return LinerView.PFD_ND;
            case LinerControls.MP:
                return c.cx > 900 && c.cx < 1400 ? LinerView.ECAM : LinerView.PFD_ND;
            default:
                return LinerView.FWD;
        }
    }

    // ---------------------------------------------------------------- the tutorial

    public static void tutorialStart() {
        TutorialMath.restart(TUTORIAL);
        LinerViewSVA.show(LinerView.OVHD);
    }

    // ---------------------------------------------------------------- ticks

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        if (e.phase != TickEvent.Phase.END) {
            return;
        }
        if (mc.thePlayer == null) {
            LinerCockpitSVA.highlight = -1;
            return;
        }
        BookHoloSVA.tick(mc);
        EntityVehicleSVA v = pilotedLiner();
        LinerSystems L = v == null ? null : v.liner();
        int hl = -1;
        if (TUTORIAL.active && ConfigSVA.bookTutorial) {
            if (L != null) {
                TutorialMath.Obs o = new TutorialMath.Obs();
                o.L = L;
                o.ground = v.onGround;
                o.kmh = VehicleMath.kmh(Math.sqrt((v.posX - v.prevPosX) * (v.posX - v.prevPosX) + (v.posZ - v.prevPosZ) * (v.posZ - v.prevPosZ)));
                o.y = v.posY;
                o.hdg = LinerGuide.compass(v.rotationYaw);
                if (TutorialMath.update(TUTORIAL, o)) {
                    mc.thePlayer.playSound("random.orb", 0.4F, 1.4F);
                }
                if (!TUTORIAL.paused && !TUTORIAL.done()) {
                    LinerControls.Ctl c = TutorialMath.control(TUTORIAL.step);
                    hl = c == null ? -1 : c.index;
                }
            }
        }
        if (showCtl >= 0) {
            boolean over = Minecraft.getSystemTime() > showUntil || L == null || BookChecklists.done(showList, showItem, L);
            if (over) {
                showCtl = -1;
            } else {
                hl = showCtl;
            }
        }
        LinerCockpitSVA.highlight = ConfigSVA.bookHighlight ? hl : -1;
    }

    /** The tutorial's prompt panel (top centre) while it runs. */
    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post e) {
        if (e.type != RenderGameOverlayEvent.ElementType.TEXT) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        BookHoloSVA.overlay(mc, e.resolution);
        if (!TUTORIAL.active || !ConfigSVA.bookTutorial || mc.currentScreen instanceof GuiBookSVA) {
            return;
        }
        drawTutorial(mc, e.resolution);
    }

    static void drawTutorial(Minecraft mc, ScaledResolution sr) {
        FontRenderer f = mc.fontRenderer;
        String key = TutorialMath.key(TUTORIAL);
        List<String> lines = new ArrayList<String>();
        String head = TUTORIAL.done() ? Lang.tr("sva.book.tut.finished", Integer.valueOf(TutorialMath.doneCount(TUTORIAL)), Integer.valueOf(TutorialMath.DONE))
                : Lang.tr("sva.book.tut.head", Integer.valueOf(TUTORIAL.step + 1), Integer.valueOf(TutorialMath.DONE))
                        + (TUTORIAL.paused ? "  " + Lang.tr("sva.book.tut.pausedTag") : "");
        lines.addAll(f.listFormattedStringToWidth(Lang.tr("sva.book.tut." + key), 260));
        List<String> hint = f.listFormattedStringToWidth(Lang.tr("sva.book.tut." + key + ".hint"), 260);
        boolean noLiner = pilotedLiner() == null;
        int w = 270, h = 16 + lines.size() * 10 + hint.size() * 10 + 12 + (noLiner ? 10 : 0);
        int x = sr.getScaledWidth() / 2 - w / 2, y = 4;
        Gui.drawRect(x - 2, y - 2, x + w + 2, y + h, 0xC0101820);
        Gui.drawRect(x - 2, y - 2, x + w + 2, y - 1, 0xFF5EC8F0);
        f.drawStringWithShadow(head, x + 3, y + 2, 0x5EC8F0);
        int yy = y + 14;
        for (String s : lines) {
            f.drawStringWithShadow(s, x + 3, yy, 0xFFFFFF);
            yy += 10;
        }
        for (String s : hint) {
            f.drawStringWithShadow(s, x + 3, yy, 0xB8C0CC);
            yy += 10;
        }
        if (noLiner) {
            f.drawStringWithShadow(Lang.tr("sva.book.tut.noLiner"), x + 3, yy, 0xF0B040);
            yy += 10;
        }
        f.drawStringWithShadow(Lang.tr("sva.book.tut.keys"), x + 3, yy + 1, 0x8890A0);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    // ---------------------------------------------------------------- back from NEI

    /** The book just opened NEI's recipes: bring it back when NEI's screens close. */
    public static void returnAfterNei(GuiBookSVA book) {
        afterNei = book;
    }

    private static boolean neiRecipes(Object gui) {
        return gui != null && gui.getClass().getName().startsWith("codechicken.nei.recipe.");
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent e) {
        GuiBookSVA book = afterNei;
        if (book == null || neiRecipes(e.gui)) {
            return;
        }
        afterNei = null;
        if (e.gui == null && neiRecipes(Minecraft.getMinecraft().currentScreen) && Minecraft.getMinecraft().thePlayer != null) {
            e.gui = book;
        }
    }
}
