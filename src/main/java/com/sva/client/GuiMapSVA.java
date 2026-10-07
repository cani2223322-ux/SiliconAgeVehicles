package com.sva.client;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.sc.energy.Tier;
import com.sc.inventory.TextFitSC;
import com.sc.manual.Lang;
import com.sva.init.ModContentSVA;
import com.sva.net.NetSVA;
import com.sva.vehicle.MapMath;
import com.sva.vehicle.SkinMath;
import com.sva.vehicle.VehicleType;

import net.minecraft.block.Block;
import net.minecraft.block.material.MapColor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/**
 * Wave 3, ★19 «Моя техника» (mock-up docs/mockups/veh_more_7_map.png, in the addon's dark style): on the left a
 * top-down map round the player (key M) or the charging station (its «Техника» button) - the terrain from the blocks
 * this client has loaded (each pixel: the top block's MapColor, a little darker or lighter by the slope, unknown land
 * dark), the vehicles as arrows in their tier's colour with their name and board number, the station, the player; a
 * vehicle off the panel is pinned to its edge with a small arrow toward it and its distance. On the right the list:
 * the item icon, name + board number, coordinates and distance, charge and wear bars, «Показать» (centres the map on
 * it), «мало заряда!» under 20 %; another dimension / an unknown place said instead of the coordinates.
 * Zoom: the mouse wheel over the map or the - / + buttons (1..16 blocks a pixel); drag the map to look round; «Ко мне»
 * centres it again. The rows come from the server (NetSVA.MapRequest / MapInfo - only the vehicles the player may see,
 * MapMath.visible), asked again every MapMath.REFRESH_EVERY ticks while the screen is open.
 */
public class GuiMapSVA extends GuiScreen {

    private static final int W = 400, H = 228, MAP_X = 6, MAP_Y = 18, MAP = 200, LIST_X = 212, LIST_W = 182, ROW_H = 40, ROWS = 5;
    private static final int BG = 0xFF1B1F27, EDGE = 0xFF0B0D11, PANEL = 0xFF232833, LINE = 0xFF3A4150, GOLD = 0xF2A040, CYAN = 0x5EC8F0,
            TEXT = 0xD8DCE4, MUTED = 0x8A93A3, GREEN = 0x62E06A, RED = 0xF06060, AMBER = 0xF0B040, UNKNOWN = 0xFF15181E;
    private static final int B_OUT = 0, B_IN = 1, B_HOME = 2;
    private static final RenderItem ITEMS = new RenderItem();

    /** The rows last received (kept while the game runs: the screen reopens with them at once). */
    private static NetSVA.MapInfo last;

    /** The map's centre (world x, z), the home it returns to, and whether that home is a station. */
    private double cx, cz;
    private final double homeX, homeZ;
    private final boolean station;
    private int zoom = 1, scroll, askedAt = -1000, builtAt = -1000;
    private double builtX = Double.NaN, builtZ, builtScale;
    private int left, top;
    private DynamicTexture terrain;
    private boolean dragging;
    private int dragX, dragY;

    private GuiMapSVA(double x, double z, boolean station) {
        this.cx = this.homeX = x;
        this.cz = this.homeZ = z;
        this.station = station;
    }

    /** The key: the map round the player. */
    public static void openAtPlayer(Minecraft mc) {
        mc.displayGuiScreen(new GuiMapSVA(mc.thePlayer.posX, mc.thePlayer.posZ, false));
    }

    /** The charging station's button: the map round the station (its container screen closes first). */
    public static void openAtStation(Minecraft mc, int x, int z) {
        mc.thePlayer.closeScreen();
        mc.displayGuiScreen(new GuiMapSVA(x + 0.5, z + 0.5, true));
    }

    /** New rows from the server (client thread). */
    public static void receive(NetSVA.MapInfo m) {
        last = m;
    }

    private double scale() {
        return MapMath.ZOOMS[zoom];
    }

    @Override
    public void initGui() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        buttonList.clear();
        buttonList.add(new GuiCargoSVA.Dark(B_OUT, left + MAP_X + MAP - 70, top + 3, 14, 12, "-", Lang.tr("sva.map.tip.out")));
        buttonList.add(new GuiCargoSVA.Dark(B_IN, left + MAP_X + MAP - 54, top + 3, 14, 12, "+", Lang.tr("sva.map.tip.in")));
        buttonList.add(new GuiCargoSVA.Dark(B_HOME, left + MAP_X + MAP - 38, top + 3, 38, 12, Lang.tr(station ? "sva.map.home.station" : "sva.map.home"),
                Lang.tr("sva.map.tip.home")));
        ask();
    }

    private void ask() {
        askedAt = mc.thePlayer == null ? 0 : mc.thePlayer.ticksExisted;
        NetSVA.CHANNEL.sendToServer(new NetSVA.MapRequest());
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (mc.thePlayer != null && mc.thePlayer.ticksExisted - askedAt >= MapMath.REFRESH_EVERY) {
            ask();
        }
    }

    @Override
    public void onGuiClosed() {
        if (terrain != null) {
            terrain.deleteGlTexture();
            terrain = null;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id == B_OUT || b.id == B_IN) {
            zoom = MapMath.zoomStep(zoom, b.id == B_OUT ? 1 : -1);
        } else if (b.id == B_HOME) {
            cx = homeX;
            cz = homeZ;
        }
    }

    // ------------------------------------------------------------------ input

    private boolean overMap(int mx, int my) {
        return mx >= left + MAP_X && mx < left + MAP_X + MAP && my >= top + MAP_Y && my < top + MAP_Y + MAP;
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d == 0) {
            return;
        }
        int mx = Mouse.getEventX() * width / mc.displayWidth, my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (overMap(mx, my)) {
            zoom = MapMath.zoomStep(zoom, d > 0 ? -1 : 1);
        } else {
            scroll = Math.max(0, Math.min(Math.max(0, rows().size() - ROWS), scroll + (d > 0 ? -1 : 1)));
        }
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        super.mouseClicked(mx, my, button);
        if (overMap(mx, my) && button == 0) {
            dragging = true;
            dragX = mx;
            dragY = my;
            return;
        }
        List<NetSVA.MapInfo.Row> rows = rows();
        for (int i = 0; i < ROWS && scroll + i < rows.size(); i++) {
            int bx = left + LIST_X + 122, by = top + MAP_Y + i * ROW_H + 21;
            NetSVA.MapInfo.Row r = rows.get(scroll + i);
            if (mx >= bx && mx < bx + 56 && my >= by && my < by + 13 && showable(r)) {
                cx = r.x + 0.5;
                cz = r.z + 0.5;
                mc.getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.func_147674_a(
                        new net.minecraft.util.ResourceLocation("gui.button.press"), 1.0F));
            }
        }
    }

    @Override
    protected void mouseClickMove(int mx, int my, int button, long time) {
        if (dragging) {
            cx -= (mx - dragX) * scale();
            cz -= (my - dragY) * scale();
            dragX = mx;
            dragY = my;
        }
    }

    @Override
    protected void mouseMovedOrUp(int mx, int my, int button) {
        super.mouseMovedOrUp(mx, my, button);
        if (button == 0) {
            dragging = false;
        }
    }

    // ------------------------------------------------------------------ data

    private List<NetSVA.MapInfo.Row> rows() {
        return last == null ? new ArrayList<NetSVA.MapInfo.Row>() : last.rows;
    }

    private int dim() {
        return mc.theWorld == null ? 0 : mc.theWorld.provider.dimensionId;
    }

    /** In this dimension with a known place - drawn on the map, «Показать» works. */
    private boolean showable(NetSVA.MapInfo.Row r) {
        return r.placed && r.dim == dim();
    }

    private static String name(NetSVA.MapInfo.Row r) {
        return Lang.tr("sva.map.name", new ItemStack(ModContentSVA.vehicle, 1, r.type).getDisplayName(), SkinMath.boardText(r.board));
    }

    static int tierColour(Tier t) {
        switch (t) {
            case MV: return 0xF0B838;
            case HV: return 0xF08A30;
            case EV: return 0xE04848;
            case QV: return 0x4AB8F0;
            case XV: return 0x50E0B0;
            case SV: return 0xB070F0;
            default: return 0xB0B8C0;
        }
    }

    // ------------------------------------------------------------------ the terrain

    /**
     * The terrain under the panel into a MAP x MAP texture: each pixel the top block at its world column (nearest block
     * at this zoom), MapColor shaded by the height step to the pixel north of it; chunks this client doesn't have stay
     * dark. Rebuilt when the view moved / zoomed, else every 2 s.
     */
    private void buildTerrain() {
        World w = mc.theWorld;
        if (w == null) {
            return;
        }
        if (terrain == null) {
            terrain = new DynamicTexture(MAP, MAP);
        }
        int[] px = terrain.getTextureData();
        double s = scale();
        int[] lastH = new int[MAP];
        for (int y = 0; y < MAP; y++) {
            for (int x = 0; x < MAP; x++) {
                int bx = MathHelper.floor_double(cx + (x - MAP / 2 + 0.5) * s), bz = MathHelper.floor_double(cz + (y - MAP / 2 + 0.5) * s);
                Chunk c = w.getChunkFromBlockCoords(bx, bz);
                if (c == null || c.isEmpty()) {
                    px[y * MAP + x] = ((bx >> 4) + (bz >> 4) & 1) == 0 ? UNKNOWN : 0xFF181C23;
                    lastH[x] = -1;
                    continue;
                }
                int h = w.getHeightValue(bx, bz);
                int yy = Math.max(0, h - 1);
                Block b = w.getBlock(bx, yy, bz);
                while (yy > 0 && b.getMaterial() == net.minecraft.block.material.Material.air) {
                    b = w.getBlock(bx, --yy, bz);
                }
                MapColor mcol = b.getMapColor(w.getBlockMetadata(bx, yy, bz));
                int col = mcol == null ? 0x7F7F7F : mcol.colorValue;
                if (col == 0) {
                    col = 0x5A5A5A;
                }
                float shade = lastH[x] < 0 || b.getMaterial().isLiquid() ? 1F : yy > lastH[x] ? 1.12F : yy < lastH[x] ? 0.86F : 1F;
                lastH[x] = yy;
                int r = Math.min(255, (int) ((col >> 16 & 255) * shade)), g = Math.min(255, (int) ((col >> 8 & 255) * shade)),
                        bl = Math.min(255, (int) ((col & 255) * shade));
                px[y * MAP + x] = 0xFF000000 | r << 16 | g << 8 | bl;
            }
        }
        terrain.updateDynamicTexture();
        builtX = cx;
        builtZ = cz;
        builtScale = s;
        builtAt = mc.thePlayer == null ? 0 : mc.thePlayer.ticksExisted;
    }

    // ------------------------------------------------------------------ drawing

    private void rect(int x, int y, int w, int h, int c) {
        if (w > 0 && h > 0) {
            drawRect(x, y, x + w, y + h, c);
        }
    }

    private void frame(int x, int y, int w, int h, int border, int fill) {
        rect(x, y, w, h, border);
        rect(x + 1, y + 1, w - 2, h - 2, fill);
    }

    private void bar(int x, int y, int w, int h, double f, int colour) {
        frame(x, y, w, h, EDGE, 0xFF0E1116);
        rect(x + 1, y + 1, (int) Math.round((w - 2) * Math.max(0, Math.min(1, f))), h - 2, 0xFF000000 | colour);
    }

    /** A filled arrow (triangle with a notch) of size r at (x, y) pointing at angle a (radians, 0 = east, map coordinates). */
    private static void arrow(double x, double y, double a, double r, int rgb) {
        double ca = Math.cos(a), sa = Math.sin(a);
        double[][] p = {{r, 0}, {-r * 0.8, r * 0.7}, {-r * 0.35, 0}, {-r * 0.8, -r * 0.7}};
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        Tessellator t = Tessellator.instance;
        for (int pass = 0; pass < 2; pass++) {
            double k = pass == 0 ? 1.35 : 1.0;
            GL11.glColor4f(pass == 0 ? 0.05F : (rgb >> 16 & 255) / 255F, pass == 0 ? 0.05F : (rgb >> 8 & 255) / 255F,
                    pass == 0 ? 0.07F : (rgb & 255) / 255F, 1F);
            t.startDrawing(GL11.GL_TRIANGLES);
            int[][] tri = {{0, 1, 2}, {0, 2, 3}};
            for (int[] q : tri) {
                for (int i : q) {
                    double px = p[i][0] * k, py = p[i][1] * k;
                    t.addVertex(x + px * ca - py * sa, y + px * sa + py * ca, 0);
                }
            }
            t.draw();
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        TextFitSC.beginFrame();
        drawDefaultBackground();
        frame(left, top, W, H, EDGE, BG);
        rect(left + 1, top + 1, W - 2, 1, LINE);
        rect(left + 3, top + 15, W - 6, 1, 0xFFE08A30);
        fontRendererObj.drawString(Lang.tr("sva.map.title"), left + 6, top + 4, GOLD);
        int mapL = left + MAP_X, mapT = top + MAP_Y;
        frame(mapL - 1, mapT - 1, MAP + 2, MAP + 2, LINE, UNKNOWN);
        boolean disabled = last != null && !last.enabled;
        if (!disabled) {
            int now = mc.thePlayer == null ? 0 : mc.thePlayer.ticksExisted;
            if (terrain == null || builtX != cx || builtZ != cz || builtScale != scale() || now - builtAt >= 40) {
                buildTerrain();
            }
            if (terrain != null) {
                GL11.glColor4f(1F, 1F, 1F, 1F);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, terrain.getGlTextureId());
                Tessellator t = Tessellator.instance;
                t.startDrawingQuads();
                t.addVertexWithUV(mapL, mapT + MAP, 0, 0, 1);
                t.addVertexWithUV(mapL + MAP, mapT + MAP, 0, 1, 1);
                t.addVertexWithUV(mapL + MAP, mapT, 0, 1, 0);
                t.addVertexWithUV(mapL, mapT, 0, 0, 0);
                t.draw();
            }
            drawMarkers(mapL, mapT);
        }
        String sc = Lang.tr("sva.map.scale", Integer.valueOf((int) scale()));
        fontRendererObj.drawString(sc, mapL + MAP - 74 - fontRendererObj.getStringWidth(sc), top + 5, MUTED);
        drawList(mx, my);
        if (disabled) {
            TextFitSC.drawCentered(fontRendererObj, Lang.tr("sva.map.disabled"), mapL, mapT + MAP / 2 - 4, MAP, RED, false, 0, 0);
        }
        super.drawScreen(mx, my, pt);
        for (Object o : buttonList) {
            if (o instanceof GuiCargoSVA.Dark && ((GuiCargoSVA.Dark) o).tip != null) {
                GuiCargoSVA.Dark b = (GuiCargoSVA.Dark) o;
                TextFitSC.hover(b.xPosition, b.yPosition, b.width, b.height, b.tip);
            }
        }
        List<String> tip = TextFitSC.hoverAt(mx, my);
        if (tip != null && !tip.isEmpty()) {
            drawHoveringText(tip, mx, my, fontRendererObj);
        }
    }

    private void drawMarkers(int mapL, int mapT) {
        double s = scale(), half = MAP / 2.0;
        double ox = mapL + half, oy = mapT + half;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        net.minecraft.client.gui.ScaledResolution res = new net.minecraft.client.gui.ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int f = res.getScaleFactor();
        GL11.glScissor(mapL * f, mc.displayHeight - (mapT + MAP) * f, MAP * f, MAP * f);
        // the station
        if (station) {
            double[] p = MapMath.toMap(homeX, homeZ, cx, cz, s);
            if (MapMath.inside(p[0], p[1], half)) {
                rect((int) (ox + p[0]) - 4, (int) (oy + p[1]) - 4, 8, 8, 0xFF101010);
                rect((int) (ox + p[0]) - 3, (int) (oy + p[1]) - 3, 6, 6, 0xFFF0B040);
                TextFitSC.drawCentered(fontRendererObj, Lang.tr("sva.map.station"), (int) (ox + p[0]) - 30, (int) (oy + p[1]) + 6, 60, TEXT, true, 0, 0);
            }
        }
        // the vehicles (this dimension, known place); off the panel: pinned to the edge with the distance
        for (NetSVA.MapInfo.Row r : rows()) {
            if (!showable(r)) {
                continue;
            }
            int col = tierColour(VehicleType.byOrdinal(r.type).tier);
            double[] p = MapMath.toMap(r.x + 0.5, r.z + 0.5, cx, cz, s);
            if (MapMath.inside(p[0], p[1], half - 4)) {
                arrow(ox + p[0], oy + p[1], -Math.PI / 2, 5, col);
                fontRendererObj.drawStringWithShadow(name(r), (int) (ox + p[0]) + 7, (int) (oy + p[1]) - 4, 0xFFFFFF);
            } else {
                double[] e = MapMath.edge(p[0], p[1], half - 7);
                arrow(ox + e[0], oy + e[1], e[2], 4, col);
                String d = Lang.tr("sva.map.far", Integer.valueOf(MapMath.distance(r.x + 0.5, r.z + 0.5, cx, cz)));
                int tw = fontRendererObj.getStringWidth(d);
                int tx = (int) Math.max(mapL + 2, Math.min(mapL + MAP - tw - 2, ox + e[0] - tw / 2.0 - Math.cos(e[2]) * 12));
                int ty = (int) Math.max(mapT + 2, Math.min(mapT + MAP - 10, oy + e[1] - 4 - Math.sin(e[2]) * 10));
                fontRendererObj.drawStringWithShadow(d, tx, ty, col);
            }
        }
        // the player: a white arrow along his look
        if (mc.thePlayer != null) {
            double[] p = MapMath.toMap(mc.thePlayer.posX, mc.thePlayer.posZ, cx, cz, s);
            if (MapMath.inside(p[0], p[1], half)) {
                arrow(ox + p[0], oy + p[1], Math.toRadians(mc.thePlayer.rotationYaw + 90), 4, 0xFFFFFF);
            }
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private void drawList(int mx, int my) {
        List<NetSVA.MapInfo.Row> rows = rows();
        int lx = left + LIST_X, ly = top + MAP_Y;
        frame(lx - 1, ly - 1, LIST_W + 2, ROWS * ROW_H + 2, LINE, PANEL);
        if (last == null) {
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.map.loading"), lx + 6, ly + 8, LIST_W - 12, MUTED, 0, 0);
            return;
        }
        if (rows.isEmpty()) {
            int yy = ly + 8;
            for (Object l : fontRendererObj.listFormattedStringToWidth(Lang.tr("sva.map.none"), LIST_W - 12)) {
                fontRendererObj.drawString((String) l, lx + 6, yy, MUTED);
                yy += 10;
            }
            return;
        }
        double fromX = station ? homeX : mc.thePlayer.posX, fromZ = station ? homeZ : mc.thePlayer.posZ;
        for (int i = 0; i < ROWS && scroll + i < rows.size(); i++) {
            NetSVA.MapInfo.Row r = rows.get(scroll + i);
            int rx = lx, ry = ly + i * ROW_H;
            boolean low = MapMath.low(r.charge / 100.0);
            rect(rx + 1, ry + 1, LIST_W - 2, ROW_H - 2, low ? 0xFF3A2428 : i % 2 == 0 ? 0xFF262C37 : 0xFF222832);
            GL11.glColor4f(1F, 1F, 1F, 1F);
            RenderHelper.enableGUIStandardItemLighting();
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            ITEMS.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), new ItemStack(ModContentSVA.vehicle, 1, r.type), rx + 3, ry + 4);
            RenderHelper.disableStandardItemLighting();
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glColor4f(1F, 1F, 1F, 1F);
            TextFitSC.draw(fontRendererObj, name(r) + (r.own ? "" : " (" + r.owner + ")"), rx + 22, ry + 2, LIST_W - 26,
                    tierColour(VehicleType.byOrdinal(r.type).tier), 0, 0);
            String where;
            if (!r.placed) {
                where = Lang.tr("sva.map.unknown");
            } else if (r.dim != dim()) {
                where = Lang.tr("sva.map.otherDim", Integer.valueOf(r.dim));
            } else {
                where = Lang.tr(station ? "sva.map.coordsStation" : "sva.map.coords", Integer.valueOf(r.x), Integer.valueOf(r.z),
                        Integer.valueOf(MapMath.distance(r.x + 0.5, r.z + 0.5, fromX, fromZ)));
            }
            TextFitSC.draw(fontRendererObj, where, rx + 22, ry + 11, low ? 96 : LIST_W - 26, MUTED, 0, 0);
            if (low) {
                TextFitSC.draw(fontRendererObj, Lang.tr("sva.map.low"), rx + 122, ry + 11, 56, RED, 0, 0);
            }
            fontRendererObj.drawString(Lang.tr("sva.map.charge"), rx + 4, ry + 21, TEXT);
            bar(rx + 40, ry + 22, 78, 6, r.charge / 100.0, low ? RED : GREEN);
            fontRendererObj.drawString(Lang.tr("sva.map.wear"), rx + 4, ry + 30, TEXT);
            double wear = 1 - r.hull / 100.0;
            bar(rx + 40, ry + 31, 78, 6, wear, wear > 0.5 ? AMBER : GREEN);
            boolean can = showable(r);
            int bx = rx + 122, by = ry + 21;
            boolean hov = can && mx >= bx && mx < bx + 56 && my >= by && my < by + 13;
            frame(bx, by, 56, 13, hov ? 0xFF7A8496 : LINE, can ? hov ? 0xFF3A4252 : 0xFF2E3542 : 0xFF1E232C);
            TextFitSC.drawCentered(fontRendererObj, Lang.tr("sva.map.show"), bx + 2, by + 3, 52, can ? (hov ? 0xFFFFA0 : TEXT) : 0x6A7282, false, 0, 0);
            if (r.age > MapMath.LIVE_SECONDS && r.placed) {
                TextFitSC.hover(rx + 22, ry + 11, 96, 9, Lang.tr("sva.map.seen", age(r.age)));
            }
        }
        if (rows.size() > ROWS) {
            int track = ROWS * ROW_H - 4, knob = Math.max(10, track * ROWS / rows.size());
            int pos = (track - knob) * scroll / Math.max(1, rows.size() - ROWS);
            rect(lx + LIST_W - 3, ly + 2, 2, track, 0xFF15181E);
            rect(lx + LIST_W - 3, ly + 2 + pos, 2, knob, 0xFF5A6272);
        }
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.map.count", Integer.valueOf(rows.size())), lx, top + 5, LIST_W, MUTED, 0, 0);
    }

    private static String age(int sec) {
        return sec >= 3600 ? Lang.tr("sva.map.ageH", Integer.valueOf(sec / 3600)) : sec >= 60 ? Lang.tr("sva.map.ageM", Integer.valueOf(sec / 60))
                : Lang.tr("sva.map.ageS", Integer.valueOf(sec));
    }
}
