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
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.AutopilotMath;
import com.sva.vehicle.CruiseMath;
import com.sva.vehicle.MapMath;
import com.sva.vehicle.RouteMath;
import com.sva.vehicle.RunwayMath;
import com.sva.vehicle.SkinMath;
import com.sva.vehicle.VehicleMath;
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
 * top-down map round the player (key «Карта», M by default - riding or on foot; the same key, the menu key or the
 * inventory key close it) or the charging station (its «Техника» button) - the terrain from the blocks
 * this client has loaded (each pixel: the top block's MapColor, a little darker or lighter by the slope, unknown land
 * dark), the vehicles as diamonds in their tier's colour with their name and board number (no heading is known, so no
 * arrow), the station, the player as an arrow along his look; a
 * vehicle off the panel is pinned to its edge with a small arrow toward it and its distance. On the right the list:
 * the item icon, name + board number, coordinates and distance, charge and wear bars, «Показать» (centres the map on
 * it), «мало заряда!» under 20 %; another dimension / an unknown place said instead of the coordinates.
 * Zoom: the mouse wheel over the map or the - / + buttons (1..16 blocks a pixel); drag the map to look round; «Ко мне»
 * centres it again. The rows come from the server (NetSVA.MapRequest / MapInfo - only the vehicles the player may see,
 * MapMath.visible), asked again every MapMath.REFRESH_EVERY ticks while the screen is open.
 * <p>
 * Round 2, D4 (★22 the flight plan, mock-up docs/mockups/veh_air3_5_flightplan.png): tabs «Техника» / «Маршрут». The route
 * tab edits the plan of an aircraft the player may drive (the one he sits in first, else the loaded ones of the list -
 * MapInfo.Row.entity / drive; «<» / «>» pick another): LMB on the map - a point there, drag a point - move it, RMB on a
 * point - delete it (a drag of the empty map still pans it); on the right the points with their height - / + (Shift: x10),
 * up / down, delete; «+ Точка» (at the map's centre), «Очистить», «В автопилот» (NetSVA.RouteSet - the server keeps it in
 * the vehicle; the driver's own: МАРШ engaged at once). The edits are a draft until sent. On the map the legs flown are
 * grey, the active one magenta, the next ones dashed (the server's NetSVA.RouteInfo - the active point - while the draft
 * is not changed).
 */
public class GuiMapSVA extends GuiScreen {

    private static final int W = 400, H = 228, MAP_X = 6, MAP_Y = 18, MAP = 200, LIST_X = 212, LIST_W = 182, ROW_H = 40, ROWS = 5;
    private static final int BG = 0xFF1B1F27, EDGE = 0xFF0B0D11, PANEL = 0xFF232833, LINE = 0xFF3A4150, GOLD = 0xF2A040, CYAN = 0x5EC8F0,
            TEXT = 0xD8DCE4, MUTED = 0x8A93A3, GREEN = 0x62E06A, RED = 0xF06060, AMBER = 0xF0B040, UNKNOWN = 0xFF15181E;
    private static final int B_OUT = 0, B_IN = 1, B_HOME = 2;
    /** D4: the tabs, the aircraft picker, the route's buttons. */
    private static final int B_TAB_VEH = 3, B_TAB_ROUTE = 4, B_PREV = 5, B_NEXT = 6, B_ADD = 7, B_CLEAR = 8, B_SEND = 9;
    /** D4: the route list - rows from LIST_Y + R_TOP, R_ROWS of R_H; the legs' colours. */
    private static final int R_TOP = 24, R_H = 20, R_ROWS = 6, MAGENTA = 0xEC5ADC, PINK = 0xECA0E6, DONE = 0x9696A0;
    /** D4: a map press moving less than this (px) is a click (a point), more - a pan; a point is hit within HIT px. */
    private static final int CLICK_SLOP = 3, HIT = 6;
    private static final RenderItem ITEMS = new RenderItem();

    /** The rows last received (kept while the game runs: the screen reopens with them at once). */
    private static NetSVA.MapInfo last;
    /** D4: the open tab (kept: the screen reopens on it), the last flight plan received, the draft per aircraft. */
    private static int tabNow;
    private static NetSVA.RouteInfo lastRoute;
    private static int draftFor = -1;
    private static RouteMath.Plan draft = new RouteMath.Plan();
    private static boolean dirty;

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
    /** D4: the route tab - the aircraft edited (entity id, -1 none), the plan received for it, the point dragged, the press. */
    private int routeVeh = -1, dragPoint = -1, pressX, pressY, moved, routeScroll, askedRouteAt = -1000;
    private NetSVA.RouteInfo shownRoute;
    private boolean pressing;
    /** D4: the runway by the last point (looked for again when it moves): «после последней: ЗАХВ». */
    private int rwyKeyX = Integer.MIN_VALUE, rwyKeyZ;
    private boolean rwyByLast;

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

    /** D4: a flight plan from the server (client thread) - the route tab picks it up if it is its aircraft's. */
    public static void receiveRoute(NetSVA.RouteInfo m) {
        lastRoute = m;
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
        // D4: the tabs «Техника» / «Маршрут», the route's aircraft picker and buttons
        GuiCargoSVA.Dark tv = new GuiCargoSVA.Dark(B_TAB_VEH, left + 280, top + 3, 52, 11, Lang.tr("sva.map.tab.vehicles"), null);
        GuiCargoSVA.Dark tr = new GuiCargoSVA.Dark(B_TAB_ROUTE, left + 336, top + 3, 56, 11, Lang.tr("sva.map.tab.route"), Lang.tr("sva.map.tip.route"));
        tv.tab = tr.tab = true;
        buttonList.add(tv);
        buttonList.add(tr);
        int lx = left + LIST_X, by = top + MAP_Y + 180;
        buttonList.add(new GuiCargoSVA.Dark(B_PREV, lx + LIST_W - 52, top + MAP_Y + 2, 10, 9, "<", Lang.tr("sva.map.route.tip.pick")));
        buttonList.add(new GuiCargoSVA.Dark(B_NEXT, lx + LIST_W - 41, top + MAP_Y + 2, 10, 9, ">", Lang.tr("sva.map.route.tip.pick")));
        buttonList.add(new GuiCargoSVA.Dark(B_ADD, lx + 2, by, 56, 14, Lang.tr("sva.map.route.add"), Lang.tr("sva.map.route.tip.add")));
        buttonList.add(new GuiCargoSVA.Dark(B_CLEAR, lx + 61, by, 56, 14, Lang.tr("sva.map.route.clear"), Lang.tr("sva.map.route.tip.clear")));
        buttonList.add(new GuiCargoSVA.Dark(B_SEND, lx + 120, by, 60, 14, Lang.tr("sva.map.route.send"), Lang.tr("sva.map.route.tip.send")));
        ask();
        if (tabNow == 1) {
            pickRoute(0);
        }
        layout();
    }

    /** D4: which buttons the open tab shows (and their state). */
    private void layout() {
        boolean route = tabNow == 1, ed = editable();
        int n = candidates().size();
        for (Object o : buttonList) {
            GuiCargoSVA.Dark b = (GuiCargoSVA.Dark) o;
            if (b.id == B_TAB_VEH || b.id == B_TAB_ROUTE) {
                b.active = (b.id == B_TAB_ROUTE) == route;
            } else if (b.id == B_PREV || b.id == B_NEXT) {
                b.visible = route && n > 1;
            } else if (b.id == B_ADD || b.id == B_CLEAR || b.id == B_SEND) {
                b.visible = route;
                b.enabled = routeVeh >= 0 && ed && (b.id != B_ADD || draft.size() < RouteMath.MAX) && (b.id != B_CLEAR || !draft.isEmpty());
            }
        }
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
        if (tabNow == 1) {
            if (routeVeh < 0 || !hasCandidate(routeVeh)) {
                pickRoute(0);
            }
            NetSVA.RouteInfo r = lastRoute;
            if (r != null && r != shownRoute && r.entity == routeVeh) {
                shownRoute = r;                                       // the server's plan: the draft follows it unless edited
                if (!dirty || draftFor != routeVeh) {
                    draft = r.plan();
                    draftFor = routeVeh;
                    dirty = false;
                }
            }
            int now = mc.thePlayer == null ? 0 : mc.thePlayer.ticksExisted;
            if (routeVeh >= 0 && now - askedRouteAt >= MapMath.REFRESH_EVERY) {
                askedRouteAt = now;                                   // the active point moves on in flight
                NetSVA.CHANNEL.sendToServer(new NetSVA.RouteRequest(routeVeh));
            }
        }
        layout();
    }

    // ------------------------------------------------------------------ D4: the route tab's aircraft

    /** An aircraft to plan for: {entity id, type, board, x, z, alt hint}. */
    private static final class Cand {
        int entity, type, board;
        double x, z, y;
    }

    private static boolean aircraftType(int type) {
        return CruiseMath.kind(VehicleType.byOrdinal(type).movement, true) == CruiseMath.AUTOPILOT;
    }

    /** The aircraft the route tab may edit: the one the player sits in first, then the loaded ones he may drive (MapInfo). */
    private List<Cand> candidates() {
        List<Cand> c = new ArrayList<Cand>();
        EntityVehicleSVA rid = mc.thePlayer != null && mc.thePlayer.ridingEntity instanceof EntityVehicleSVA ? (EntityVehicleSVA) mc.thePlayer.ridingEntity : null;
        if (rid != null && !rid.isDead && rid.isAircraft()) {
            Cand k = new Cand();
            k.entity = rid.getEntityId();
            k.type = rid.getVehicleType().ordinal();
            k.board = rid.getBoardNumber();
            k.x = rid.posX;
            k.z = rid.posZ;
            k.y = rid.posY;
            c.add(k);
        }
        for (NetSVA.MapInfo.Row r : rows()) {
            if (r.entity >= 0 && r.drive && r.dim == dim() && aircraftType(r.type) && (rid == null || r.entity != rid.getEntityId())) {
                Cand k = new Cand();
                k.entity = r.entity;
                k.type = r.type;
                k.board = r.board;
                net.minecraft.entity.Entity e = mc.theWorld == null ? null : mc.theWorld.getEntityByID(r.entity);
                k.x = e != null ? e.posX : r.x + 0.5;
                k.z = e != null ? e.posZ : r.z + 0.5;
                k.y = e != null ? e.posY : r.y;
                c.add(k);
            }
        }
        return c;
    }

    private boolean hasCandidate(int id) {
        return cand(id) != null;
    }

    private Cand cand(int id) {
        for (Cand k : candidates()) {
            if (k.entity == id) {
                return k;
            }
        }
        return null;
    }

    /** The next aircraft (dir -1 / +1; 0: keep the one shown if it still may be, else the first); asks its plan. */
    private void pickRoute(int dir) {
        List<Cand> c = candidates();
        if (c.isEmpty()) {
            routeVeh = -1;
            return;
        }
        int i = 0;
        for (int k = 0; k < c.size(); k++) {
            if (c.get(k).entity == routeVeh) {
                i = k;
            }
        }
        int was = routeVeh;
        routeVeh = c.get(((i + dir) % c.size() + c.size()) % c.size()).entity;
        if (routeVeh != was) {
            routeScroll = 0;
            shownRoute = null;
            if (draftFor != routeVeh) {
                draft = new RouteMath.Plan();
                draftFor = routeVeh;
                dirty = false;
            }
            askedRouteAt = mc.thePlayer == null ? 0 : mc.thePlayer.ticksExisted;
            NetSVA.CHANNEL.sendToServer(new NetSVA.RouteRequest(routeVeh));
        }
    }

    /** The server said this player may set the shown aircraft's plan. */
    private boolean editable() {
        return shownRoute != null && shownRoute.entity == routeVeh && shownRoute.editable;
    }

    /** The active point shown (the server's), -1 while the draft differs from it. */
    private int activeShown() {
        return shownRoute != null && shownRoute.entity == routeVeh && !dirty ? shownRoute.active : -1;
    }

    private void edited() {
        dirty = true;
        draftFor = routeVeh;
    }

    private void click() {
        mc.getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.func_147674_a(
                new net.minecraft.util.ResourceLocation("gui.button.press"), 1.0F));
    }

    /** A new point's height: the last point's, else the aircraft's (to 5 blocks), else RouteMath.ALT_DEFAULT. */
    private int newAlt() {
        if (!draft.isEmpty()) {
            return draft.pts.get(draft.size() - 1)[2];
        }
        Cand k = cand(routeVeh);
        return k == null ? RouteMath.ALT_DEFAULT : RouteMath.clampAlt((int) Math.round(k.y / RouteMath.ALT_STEP) * RouteMath.ALT_STEP);
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
        if (b.id == B_TAB_VEH || b.id == B_TAB_ROUTE) {
            tabNow = b.id == B_TAB_ROUTE ? 1 : 0;
            if (tabNow == 1) {
                pickRoute(0);
            }
            layout();
            return;
        }
        if (b.id == B_PREV || b.id == B_NEXT) {
            pickRoute(b.id == B_PREV ? -1 : 1);
            return;
        }
        if (b.id == B_ADD && editable()) {
            if (draft.add(MathHelper.floor_double(cx), MathHelper.floor_double(cz), newAlt())) {
                edited();
            }
            return;
        }
        if (b.id == B_CLEAR && editable()) {
            draft.clear();
            edited();
            return;
        }
        if (b.id == B_SEND && editable() && routeVeh >= 0) {
            // to the server (it keeps the plan in the vehicle); the aircraft the player drives flies it at once
            RouteMath.Plan p = draft.copy();
            p.active = 0;
            NetSVA.CHANNEL.sendToServer(new NetSVA.RouteSet(routeVeh, p));
            dirty = false;
            shownRoute = null;
            EntityVehicleSVA v = CruiseClientSVA.driven(mc);
            if (v != null && v.getEntityId() == routeVeh) {
                v.setClientPlan(p.copy());
                if (!p.isEmpty()) {
                    CruiseClientSVA.routeEngage(v);
                }
            }
            return;
        }
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

    /** Esc, «Карта», the menu key or the inventory key: closed (like the «Техника» menu). */
    @Override
    protected void keyTyped(char c, int key) {
        int[] map = VehicleKeyBindsSVA.get(com.sva.vehicle.VehicleFn.MAP);
        int menu = VehicleKeyBindsSVA.KEY_MENU.getKeyCode();
        if (key == org.lwjgl.input.Keyboard.KEY_ESCAPE || key != 0 && (map != null && key == map[0] || key == menu
                || key == mc.gameSettings.keyBindInventory.getKeyCode())) {
            mc.displayGuiScreen(null);
        }
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
        } else if (tabNow == 1) {
            routeScroll = Math.max(0, Math.min(Math.max(0, draft.size() - R_ROWS), routeScroll + (d > 0 ? -1 : 1)));
        } else {
            scroll = Math.max(0, Math.min(Math.max(0, rows().size() - ROWS), scroll + (d > 0 ? -1 : 1)));
        }
    }

    /** D4: the draft's point under the mouse on the map (within HIT px), -1 none. */
    private int pointAt(int mx, int my) {
        double s = scale(), ox = left + MAP_X + MAP / 2.0, oy = top + MAP_Y + MAP / 2.0;
        int best = -1;
        double bd = HIT * HIT;
        for (int i = 0; i < draft.size(); i++) {
            int[] w = draft.pts.get(i);
            double[] p = MapMath.toMap(w[0] + 0.5, w[1] + 0.5, cx, cz, s);
            double dx = ox + p[0] - mx, dy = oy + p[1] - my, d = dx * dx + dy * dy;
            if (d <= bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    /** D4: the world block column under a map pixel. */
    private int[] worldAt(int mx, int my) {
        double s = scale();
        return new int[] {MathHelper.floor_double(cx + (mx - (left + MAP_X + MAP / 2.0)) * s),
                MathHelper.floor_double(cz + (my - (top + MAP_Y + MAP / 2.0)) * s)};
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        super.mouseClicked(mx, my, button);
        if (tabNow == 1 && routeClicked(mx, my, button)) {
            return;
        }
        if (overMap(mx, my) && button == 0) {
            dragging = true;
            dragX = mx;
            dragY = my;
            return;
        }
        if (tabNow == 1) {
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

    /** D4: a press in the route tab - on the map (a point: drag / delete; empty: a click adds, a drag pans) or a row's button. */
    private boolean routeClicked(int mx, int my, int button) {
        boolean ed = editable();
        if (overMap(mx, my)) {
            int hit = ed ? pointAt(mx, my) : -1;
            if (button == 1 && hit >= 0) {
                draft.remove(hit);
                edited();
                return true;
            }
            if (button == 0) {
                dragPoint = hit;
                pressing = hit < 0 && ed;
                pressX = mx;
                pressY = my;
                moved = 0;
                if (hit < 0) {
                    dragging = true;                                // a drag pans as on the other tab
                    dragX = mx;
                    dragY = my;
                }
                return true;
            }
            return false;
        }
        if (button != 0 || !ed) {
            return false;
        }
        int lx = left + LIST_X, ry0 = top + MAP_Y + R_TOP;
        for (int i = 0; i < R_ROWS && routeScroll + i < draft.size(); i++) {
            int k = routeScroll + i, ry = ry0 + i * R_H + 5;
            if (my < ry || my >= ry + 10) {
                continue;
            }
            int step = RouteMath.ALT_STEP * (GuiScreen.isShiftKeyDown() ? 10 : 1);
            if (mx >= lx + 98 && mx < lx + 108) {
                draft.addAlt(k, -step);
            } else if (mx >= lx + 132 && mx < lx + 142) {
                draft.addAlt(k, step);
            } else if (mx >= lx + 146 && mx < lx + 156) {
                if (!draft.move(k, -1)) {
                    return true;
                }
            } else if (mx >= lx + 157 && mx < lx + 167) {
                if (!draft.move(k, 1)) {
                    return true;
                }
            } else if (mx >= lx + 168 && mx < lx + 178) {
                draft.remove(k);
                routeScroll = Math.max(0, Math.min(routeScroll, draft.size() - R_ROWS));
            } else {
                continue;
            }
            edited();
            click();
            return true;
        }
        return false;
    }

    @Override
    protected void mouseClickMove(int mx, int my, int button, long time) {
        if (tabNow == 1 && dragPoint >= 0 && dragPoint < draft.size()) {
            int[] w = worldAt(Math.max(left + MAP_X, Math.min(left + MAP_X + MAP - 1, mx)), Math.max(top + MAP_Y, Math.min(top + MAP_Y + MAP - 1, my)));
            int[] p = draft.pts.get(dragPoint);
            if (p[0] != w[0] || p[1] != w[1]) {
                p[0] = RouteMath.clampCoord(w[0]);
                p[1] = RouteMath.clampCoord(w[1]);
                edited();
            }
            return;
        }
        moved += Math.abs(mx - dragX) + Math.abs(my - dragY);
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
            if (tabNow == 1 && pressing && moved < CLICK_SLOP && overMap(mx, my) && editable()) {
                int[] w = worldAt(mx, my);                          // a click, not a drag: a new point there
                if (draft.add(w[0], w[1], newAlt())) {
                    edited();
                    routeScroll = Math.max(0, draft.size() - R_ROWS);
                }
            }
            pressing = false;
            dragPoint = -1;
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

    /** A vehicle on the map: a diamond of half-size r at (x, y) with a dark rim (no heading - only the player is an arrow). */
    private static void diamond(double x, double y, double r, int rgb) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        Tessellator t = Tessellator.instance;
        for (int pass = 0; pass < 2; pass++) {
            double k = pass == 0 ? r + 1.5 : r;
            GL11.glColor4f(pass == 0 ? 0.05F : (rgb >> 16 & 255) / 255F, pass == 0 ? 0.05F : (rgb >> 8 & 255) / 255F,
                    pass == 0 ? 0.07F : (rgb & 255) / 255F, 1F);
            t.startDrawingQuads();
            t.addVertex(x, y - k, 0);
            t.addVertex(x - k, y, 0);
            t.addVertex(x, y + k, 0);
            t.addVertex(x + k, y, 0);
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
            if (tabNow == 1) {
                drawRouteOnMap(mapL, mapT);
            }
        }
        String sc = Lang.tr("sva.map.scale", Integer.valueOf((int) scale()));
        fontRendererObj.drawString(sc, mapL + MAP - 74 - fontRendererObj.getStringWidth(sc), top + 5, MUTED);
        if (tabNow == 1) {
            drawRouteList(mx, my);
        } else {
            drawList(mx, my);
        }
        if (disabled) {
            TextFitSC.drawCentered(fontRendererObj, Lang.tr("sva.map.disabled"), mapL, mapT + MAP / 2 - 4, MAP, RED, false, 0, 0);
        }
        super.drawScreen(mx, my, pt);
        for (Object o : buttonList) {
            if (o instanceof GuiCargoSVA.Dark && ((GuiCargoSVA.Dark) o).tip != null && ((GuiCargoSVA.Dark) o).visible) {
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
        // air-defence wave: the airspace zones pilots see (their type's colour, the name and the band)
        if (mc.theWorld != null) {
            AirDefenseClientSVA.drawZones(fontRendererObj, AirDefenseClientSVA.zones(mc.theWorld.provider.dimensionId), ox, oy, cx, cz, s, -1);
        }
        // the vehicles (this dimension, known place); off the panel: pinned to the edge with the distance
        for (NetSVA.MapInfo.Row r : rows()) {
            if (!showable(r)) {
                continue;
            }
            int col = tierColour(VehicleType.byOrdinal(r.type).tier);
            double[] p = MapMath.toMap(r.x + 0.5, r.z + 0.5, cx, cz, s);
            if (MapMath.inside(p[0], p[1], half - 4)) {
                diamond(ox + p[0], oy + p[1], 4, col);
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
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.map.count", Integer.valueOf(rows.size())), lx, top + 5, 64, MUTED, 0, 0);
    }

    // ------------------------------------------------------------------ D4: the route tab

    /** A thick line (quad) between two GUI points, `w` px wide. */
    private static void line(double x0, double y0, double x1, double y1, double w, int rgb) {
        double dx = x1 - x0, dy = y1 - y0, l = Math.sqrt(dx * dx + dy * dy);
        if (l < 1e-6) {
            return;
        }
        double nx = -dy / l * w / 2, ny = dx / l * w / 2;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F, 1F);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.addVertex(x0 + nx, y0 + ny, 0);
        t.addVertex(x1 + nx, y1 + ny, 0);
        t.addVertex(x1 - nx, y1 - ny, 0);
        t.addVertex(x0 - nx, y0 - ny, 0);
        t.draw();
        GL11.glColor4f(1F, 1F, 1F, 1F);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** A dashed line: dashes of `dash` px with gaps as long. */
    private static void dashed(double x0, double y0, double x1, double y1, double w, double dash, int rgb) {
        double l = Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0));
        int n = Math.max(1, (int) (l / dash));
        for (int k = 0; k < n; k += 2) {
            double a = k / (double) n, b = Math.min(1, (k + 1) / (double) n);
            line(x0 + (x1 - x0) * a, y0 + (y1 - y0) * a, x0 + (x1 - x0) * b, y0 + (y1 - y0) * b, w, rgb);
        }
    }

    /** A small triangle (up / down) in a 10 x 10 button at (x, y). */
    private static void triangle(int x, int y, boolean up, int rgb) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F, 1F);
        Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_TRIANGLES);
        if (up) {
            t.addVertex(x + 5, y + 3, 0);
            t.addVertex(x + 2.5, y + 7, 0);
            t.addVertex(x + 7.5, y + 7, 0);
        } else {
            t.addVertex(x + 2.5, y + 3, 0);
            t.addVertex(x + 5, y + 7, 0);
            t.addVertex(x + 7.5, y + 3, 0);
        }
        t.draw();
        GL11.glColor4f(1F, 1F, 1F, 1F);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** A point's name: «Т3». */
    private static String wpName(int i) {
        return Lang.tr("sva.ap.route.wp", Integer.valueOf(i + 1));
    }

    /** The plan on the map: the legs (flown grey, the active magenta, the next dashed), the points as diamonds with height. */
    private void drawRouteOnMap(int mapL, int mapT) {
        if (routeVeh < 0 || draft.isEmpty()) {
            return;
        }
        double s = scale(), ox = mapL + MAP / 2.0, oy = mapT + MAP / 2.0;
        int act = activeShown(), n = draft.size();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        net.minecraft.client.gui.ScaledResolution res = new net.minecraft.client.gui.ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int f = res.getScaleFactor();
        GL11.glScissor(mapL * f, mc.displayHeight - (mapT + MAP) * f, MAP * f, MAP * f);
        double[][] p = new double[n][];
        for (int i = 0; i < n; i++) {
            int[] w = draft.pts.get(i);
            double[] m = MapMath.toMap(w[0] + 0.5, w[1] + 0.5, cx, cz, s);
            p[i] = new double[] {ox + m[0], oy + m[1]};
        }
        Cand k = cand(routeVeh);
        if (act == 0 && k != null) {
            double[] m = MapMath.toMap(k.x, k.z, cx, cz, s);
            line(ox + m[0], oy + m[1], p[0][0], p[0][1], 1.5, MAGENTA);   // direct to the first point
        }
        for (int i = 0; i + 1 < n; i++) {
            int to = i + 1;
            if (act >= 0 && to < act) {
                line(p[i][0], p[i][1], p[to][0], p[to][1], 1, DONE);
            } else if (act >= 0 && to == act) {
                line(p[i][0], p[i][1], p[to][0], p[to][1], 1.5, MAGENTA);
            } else {
                dashed(p[i][0], p[i][1], p[to][0], p[to][1], 1.2, 5, PINK);
            }
        }
        for (int i = 0; i < n; i++) {
            int col = act < 0 ? PINK : i < act ? DONE : i == act ? MAGENTA : PINK;
            diamond(p[i][0], p[i][1], i == dragPoint ? 4.5 : 3.5, col);
            String lab = wpName(i) + "  " + draft.pts.get(i)[2];
            fontRendererObj.drawStringWithShadow(lab, (int) p[i][0] + 6, (int) p[i][1] - 4, 0xFFFFFF);
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        rect(mapL, mapT + MAP - 11, MAP, 11, 0xE00E1016);
        int tx = mapL + 3;
        for (String h : new String[] {"sva.map.route.hint.add", "sva.map.route.hint.drag", "sva.map.route.hint.del"}) {
            TextFitSC.draw(fontRendererObj, Lang.tr(h), tx, mapT + MAP - 9, 64, TEXT, 0, 0);
            tx += 66;
        }
    }

    /** The route tab's right panel: the aircraft, the points with their buttons, the totals, the status. */
    private void drawRouteList(int mx, int my) {
        int lx = left + LIST_X, ly = top + MAP_Y;
        frame(lx - 1, ly - 1, LIST_W + 2, 202, LINE, PANEL);
        Cand k = cand(routeVeh);
        if (routeVeh < 0 || k == null) {
            int yy = ly + 8;
            for (Object l : fontRendererObj.listFormattedStringToWidth(Lang.tr("sva.map.route.none"), LIST_W - 12)) {
                fontRendererObj.drawString((String) l, lx + 6, yy, MUTED);
                yy += 10;
            }
            return;
        }
        String nm = Lang.tr("sva.map.name", new ItemStack(ModContentSVA.vehicle, 1, k.type).getDisplayName(), SkinMath.boardText(k.board));
        boolean pick = candidates().size() > 1;
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.map.route.title", nm), lx + 4, ly + 3, LIST_W - (pick ? 62 : 34), GOLD, 0, 0);
        String cnt = draft.size() + "/" + RouteMath.MAX;
        fontRendererObj.drawString(cnt, lx + LIST_W - 4 - fontRendererObj.getStringWidth(cnt), ly + 3, MUTED);
        int hy = ly + 14;
        fontRendererObj.drawString(Lang.tr("sva.map.route.colN"), lx + 4, hy, MUTED);
        fontRendererObj.drawString(Lang.tr("sva.map.route.colPoint"), lx + 16, hy, MUTED);
        fontRendererObj.drawString(Lang.tr("sva.map.route.colXZ"), lx + 44, hy, MUTED);
        fontRendererObj.drawString(Lang.tr("sva.map.route.colAlt"), lx + 108, hy, MUTED);
        int act = activeShown();
        boolean ed = editable();
        for (int i = 0; i < R_ROWS && routeScroll + i < draft.size(); i++) {
            int n = routeScroll + i, ry = ly + R_TOP + i * R_H;
            int[] w = draft.pts.get(n);
            boolean on = n == act, done = act >= 0 && n < act;
            rect(lx + 1, ry, LIST_W - 2, R_H - 2, on ? 0xFF3A2A44 : i % 2 == 0 ? 0xFF262C37 : 0xFF222832);
            if (on) {
                rect(lx + 1, ry, 2, R_H - 2, 0xFF000000 | MAGENTA);
            }
            fontRendererObj.drawString(String.valueOf(n + 1), lx + 5, ry + 6, on ? MAGENTA : MUTED);
            fontRendererObj.drawString(wpName(n), lx + 16, ry + 6, done ? DONE : on ? MAGENTA : 0xFFFFFF);
            TextFitSC.draw(fontRendererObj, w[0] + " / " + w[1], lx + 40, ry + 6, 56, done ? MUTED : TEXT, 0, 0);
            TextFitSC.drawCentered(fontRendererObj, String.valueOf(w[2]), lx + 108, ry + 6, 24, AMBER, false, 0, 0);
            smallButton(lx + 98, ry + 5, "-", ed, mx, my, TEXT);
            smallButton(lx + 132, ry + 5, "+", ed, mx, my, TEXT);
            smallButton(lx + 146, ry + 5, "", ed && n > 0, mx, my, TEXT);
            triangle(lx + 146, ry + 5, true, ed && n > 0 ? TEXT : 0x6A7282);
            smallButton(lx + 157, ry + 5, "", ed && n < draft.size() - 1, mx, my, TEXT);
            triangle(lx + 157, ry + 5, false, ed && n < draft.size() - 1 ? TEXT : 0x6A7282);
            smallButton(lx + 168, ry + 5, "x", ed, mx, my, RED);
            if (ed) {
                TextFitSC.hover(lx + 98, ry + 5, 44, 10, Lang.tr("sva.map.route.tip.alt"));
                TextFitSC.hover(lx + 146, ry + 5, 32, 10, Lang.tr("sva.map.route.tip.order"));
            }
        }
        if (draft.size() > R_ROWS) {
            int track = R_ROWS * R_H - 4, knob = Math.max(10, track * R_ROWS / draft.size());
            int pos = (track - knob) * routeScroll / Math.max(1, draft.size() - R_ROWS);
            rect(lx + LIST_W - 3, ly + R_TOP, 2, track, 0xFF15181E);
            rect(lx + LIST_W - 3, ly + R_TOP + pos, 2, knob, 0xFF5A6272);
        }
        if (draft.isEmpty()) {
            int yy = ly + R_TOP + 4;
            for (Object l : fontRendererObj.listFormattedStringToWidth(Lang.tr(ed ? "sva.map.route.empty" : "sva.map.route.emptyView"), LIST_W - 12)) {
                fontRendererObj.drawString((String) l, lx + 6, yy, MUTED);
                yy += 10;
            }
        }
        // the totals: the length from the aircraft on, the time at its set / cruising speed; what happens after the last point
        int fy = ly + R_TOP + R_ROWS * R_H + 4;
        if (!draft.isEmpty()) {
            RouteMath.Plan p = draft.copy();
            p.active = Math.max(0, activeShown());
            double len = RouteMath.length(p, k.x, k.z);
            int kmh = kmhFor(k);
            int sec = (int) Math.round(len / Math.max(1e-3, CruiseMath.fromKmh(kmh)) / 20);
            String time = Lang.tr("sva.map.route.time", Integer.valueOf(sec / 60), (sec % 60 < 10 ? "0" : "") + sec % 60);
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.map.route.total", Integer.valueOf((int) Math.round(len)), time, Integer.valueOf(kmh)), lx + 4, fy,
                    LIST_W - 8, MUTED, 0, 0);
            int[] lastP = draft.pts.get(draft.size() - 1);
            TextFitSC.draw(fontRendererObj, Lang.tr(runwayByLast(lastP[0], lastP[1]) ? "sva.map.route.after.cap" : "sva.map.route.after.hold"), lx + 4,
                    fy + 10, LIST_W - 8, CYAN, 0, 0);
        }
        String st = !ed ? (shownRoute == null || shownRoute.entity != routeVeh ? "sva.map.loading" : "sva.map.route.readonly")
                : dirty ? "sva.map.route.unsent" : draft.size() >= RouteMath.MAX ? "sva.map.route.full" : null;
        if (st != null) {
            TextFitSC.draw(fontRendererObj, Lang.tr(st), lx + 4, fy + 20, LIST_W - 8, dirty && ed ? AMBER : MUTED, 0, 0);
        }
    }

    /** A 10 x 10 button drawn in place (the list's rows scroll - no GuiButton). */
    private void smallButton(int x, int y, String t, boolean on, int mx, int my, int colour) {
        boolean hov = on && mx >= x && mx < x + 10 && my >= y && my < y + 10;
        frame(x, y, 10, 10, hov ? 0xFF7A8496 : LINE, on ? hov ? 0xFF3A4252 : 0xFF2E3542 : 0xFF1E232C);
        if (t.length() > 0) {
            TextFitSC.drawCentered(fontRendererObj, t, x, y + 1, 10, on ? colour : 0x6A7282, false, 0, 0);
        }
    }

    /** The speed the totals use: the AP's set speed of the aircraft the player drives, else 80 % of the type's top. */
    private int kmhFor(Cand k) {
        EntityVehicleSVA v = CruiseClientSVA.driven(mc);
        if (v != null && v.getEntityId() == k.entity) {
            int s = CruiseClientSVA.panel(v).kmh;
            if (s >= AutopilotMath.MIN_KMH) {
                return s;
            }
        }
        return Math.max(AutopilotMath.MIN_KMH, (int) Math.round(VehicleMath.kmh(VehicleType.byOrdinal(k.type).topSpeed) * 0.8));
    }

    /** A runway (in the loaded chunks) within RouteMath.HANDOVER of the last point: ЗАХВ takes over there (looked for when it moves). */
    private boolean runwayByLast(int x, int z) {
        if (x == rwyKeyX && z == rwyKeyZ) {
            return rwyByLast;
        }
        rwyKeyX = x;
        rwyKeyZ = z;
        final World w = mc.theWorld;
        RunwayMath.Runway r = w == null || ModContentSVA.runway == null ? null : RunwayMath.find(new RunwayMath.World() {
            @Override
            public int top(int bx, int bz) {
                if (!w.blockExists(bx, 64, bz)) {
                    return -1;
                }
                int h = w.getHeightValue(bx, bz);
                return h <= 0 ? -1 : h - 1;
            }

            @Override
            public boolean runway(int bx, int by, int bz) {
                return w.blockExists(bx, by, bz) && w.getBlock(bx, by, bz) == ModContentSVA.runway;
            }
        }, x + 0.5, z + 0.5);
        rwyByLast = r != null && RouteMath.handOver(true, r.distance(x + 0.5, z + 0.5));
        return rwyByLast;
    }

    private static String age(int sec) {
        return sec >= 3600 ? Lang.tr("sva.map.ageH", Integer.valueOf(sec / 3600)) : sec >= 60 ? Lang.tr("sva.map.ageM", Integer.valueOf(sec / 60))
                : Lang.tr("sva.map.ageS", Integer.valueOf(sec));
    }
}
