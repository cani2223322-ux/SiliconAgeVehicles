package com.sva.client;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sva.entity.EntitySeatSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.net.NetSVA;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.VehicleFn;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Liner wave 1: clicking the SA-320's cockpit in 3D. Held «Курсор кабины» (VehicleFn.CURSOR, Left Alt by default) in a
 * pilot seat in the first-person view opens a see-through screen (Cursor): the mouse is free and the view stops turning;
 * the ray from the cursor (the camera's matrices, saved where the cockpit was drawn) is cut with every panel's plane
 * (LinerControls' placements - the nearest panel the ray meets wins) and the hit point looked up in the control table.
 * LMB - on / up / next, RMB - off / down / previous, the wheel - selectors and levers; a guarded switch takes two clicks
 * (the guard first). A tooltip names the control in Russian with its position; the hovered control gets an outline. The
 * click goes to the server (NetSVA.LinerControl), which checks the seat and syncs the result. Letting go of the key
 * closes the screen.
 */
@SideOnly(Side.CLIENT)
public final class LinerClickSVA {

    private static final FloatBuffer MV = BufferUtils.createFloatBuffer(16), PR = BufferUtils.createFloatBuffer(16);
    private static final IntBuffer VP = BufferUtils.createIntBuffer(16);
    private static final float[] mv = new float[16], pr = new float[16];
    private static final int[] vp = new int[4];
    private static boolean have;
    private static long savedAt;

    private LinerClickSVA() {
    }

    /** The player sits in one of the liner's two pilot seats (the captain drives; the F/O is seat 1). */
    public static boolean pilotSeat(EntityPlayer p, EntityVehicleSVA v) {
        if (p == null || v == null) {
            return false;
        }
        if (p.ridingEntity == v) {
            return true;
        }
        return p.ridingEntity instanceof EntitySeatSVA && ((EntitySeatSVA) p.ridingEntity).getParent() == v
                && ((EntitySeatSVA) p.ridingEntity).getIndex() == 1;
    }

    /** LinerCockpitSVA: the matrices in the cockpit's frame this frame (the ray is built in it). */
    static void saveMatrices() {
        MV.clear();
        PR.clear();
        VP.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MV);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PR);
        GL11.glGetInteger(GL11.GL_VIEWPORT, VP);
        MV.get(mv);
        PR.get(pr);
        VP.get(vp);
        have = true;
        savedAt = Minecraft.getSystemTime();
    }

    /** Client tick: the key held in a pilot seat, first person, no screen - open the cursor screen. */
    public static void tick(Minecraft mc) {
        if (mc.thePlayer == null || mc.currentScreen != null || mc.gameSettings.thirdPersonView != 0) {
            return;
        }
        EntityVehicleSVA v = HudVehicleSVA.vehicleOf(mc.thePlayer);
        if (v != null && v.getVehicleType() == VehicleType.LINER && !pilotSeat(mc.thePlayer, v)) {
            readingKeys(mc, v);                                     // wave 6: a passenger's reading light
            return;
        }
        readHeld = org.lwjgl.input.Mouse.isButtonDown(1) || VehicleKeyBindsSVA.cursorHeld();
        if (v == null || v.getVehicleType() != VehicleType.LINER || !pilotSeat(mc.thePlayer, v)) {
            return;
        }
        if (VehicleKeyBindsSVA.cursorHeld()) {
            mc.displayGuiScreen(new Cursor(v));
        }
    }

    /** Wave 6: the passenger's reading light keys were down last tick (a press is their edge). */
    private static boolean readHeld = true;

    /**
     * Wave 6: a passenger (not a pilot seat) toggles his own reading light - the «Курсор кабины» key (Left Alt) or the right
     * mouse button with an empty hand; one press, one toggle (the server checks the seat).
     */
    static void readingKeys(Minecraft mc, EntityVehicleSVA v) {
        boolean rmb = org.lwjgl.input.Mouse.isButtonDown(1) && mc.thePlayer.getCurrentEquippedItem() == null;
        boolean down = rmb || VehicleKeyBindsSVA.cursorHeld();
        if (down && !readHeld) {
            com.sva.net.NetSVA.CHANNEL.sendToServer(new com.sva.net.NetSVA.Action(v.getEntityId(), EntityVehicleSVA.A_LINER_READING));
        }
        readHeld = down;
    }

    // ---------------------------------------------------------------- the ray

    /** The control under screen pixel (mx, my - from the bottom) or null; out = {panel, tx, ty}. */
    static LinerControls.Ctl pick(int mx, int my, float[] out) {
        if (!have || Minecraft.getSystemTime() - savedAt > 1000) {
            return null;
        }
        float[] a = unproject(mx, my, -1), b = unproject(mx, my, 1);
        if (a == null || b == null) {
            return null;
        }
        float[] d = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        float best = Float.MAX_VALUE;
        LinerControls.Ctl hit = null;
        float[] tmp = new float[3];
        for (int panel = 0; panel < LinerControls.PANELS; panel++) {
            for (int face = 0; face < (panel == LinerControls.PED ? 2 : 1); face++) {
                float[] f = face(panel, face);
                float t = cut(a, d, f, LinerControls.PANEL_W[panel], tmp);
                if (t < 0 || t >= best) {
                    continue;
                }
                float tx = tmp[0], ty = tmp[1];
                if (tx < 0 || tx > LinerControls.PANEL_W[panel] || ty < f[9] || ty > f[10]) {
                    continue;
                }
                best = t;
                hit = LinerControls.hit(panel, tx, ty);
                if (out != null) {
                    out[0] = panel;
                    out[1] = tx;
                    out[2] = ty;
                }
            }
        }
        return hit;
    }

    /**
     * A panel's face as a parallelogram in the cockpit frame: {tl(3), u(3), v(3), ty0, ty1} - u spans the panel's width,
     * v its height range ty0..ty1 (the pedestal's two faces split at PED_SPLIT).
     */
    static float[] face(int panel, int face) {
        if (panel == LinerControls.PED) {
            float sp = LinerControls.PED_SPLIT, H = LinerControls.PANEL_H[panel];
            float[] p0 = LinerControls.point(panel, 0, face == 0 ? 0 : sp, new float[3]);
            float[] p1 = LinerControls.point(panel, LinerControls.PANEL_W[panel], face == 0 ? 0 : sp, new float[3]);
            float[] p2 = LinerControls.point(panel, 0, face == 0 ? sp : H, new float[3]);
            return new float[] {p0[0], p0[1], p0[2], p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2], p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2],
                    face == 0 ? 0 : sp, face == 0 ? sp : H};
        }
        float[] f = LinerControls.FLAT[panel];
        float[] r = new float[11];
        System.arraycopy(f, 0, r, 0, 9);
        r[9] = 0;
        r[10] = LinerControls.PANEL_H[panel];
        return r;
    }

    /** The ray a + t d against the face: t (or -1) and the panel point (tx, ty) into out. */
    static float cut(float[] a, float[] d, float[] f, float width, float[] out) {
        float[] u = {f[3], f[4], f[5]}, v = {f[6], f[7], f[8]};
        float[] n = LinerGfxSVA.cross(u, v);
        float den = n[0] * d[0] + n[1] * d[1] + n[2] * d[2];
        if (Math.abs(den) < 1e-9F) {
            return -1;
        }
        float[] w = {f[0] - a[0], f[1] - a[1], f[2] - a[2]};
        float t = (n[0] * w[0] + n[1] * w[1] + n[2] * w[2]) / den;
        if (t < 0) {
            return -1;
        }
        float[] p = {a[0] + d[0] * t - f[0], a[1] + d[1] * t - f[1], a[2] + d[2] * t - f[2]};
        // solve p = s u + r v (least squares over the plane)
        float uu = dot(u, u), uv = dot(u, v), vv = dot(v, v), pu = dot(p, u), pv = dot(p, v);
        float det = uu * vv - uv * uv;
        if (Math.abs(det) < 1e-12F) {
            return -1;
        }
        float s = (pu * vv - pv * uv) / det, r = (pv * uu - pu * uv) / det;
        out[0] = s * width;
        out[1] = f[9] + r * (f[10] - f[9]);
        return t;
    }

    static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /** Window pixel (mx, my from the bottom) at depth ndcZ -1 (near) / 1 (far) -> the cockpit frame. */
    static float[] unproject(int mx, int my, float ndcZ) {
        float[] m = mul(pr, mv);
        float[] inv = invert(m);
        if (inv == null) {
            return null;
        }
        float x = (mx - vp[0]) / (float) vp[2] * 2 - 1, y = (my - vp[1]) / (float) vp[3] * 2 - 1;
        float[] in = {x, y, ndcZ, 1};
        float[] o = new float[4];
        for (int i = 0; i < 4; i++) {
            o[i] = inv[i] * in[0] + inv[4 + i] * in[1] + inv[8 + i] * in[2] + inv[12 + i] * in[3];
        }
        if (Math.abs(o[3]) < 1e-9F) {
            return null;
        }
        return new float[] {o[0] / o[3], o[1] / o[3], o[2] / o[3]};
    }

    /** Column-major 4x4 product a b. */
    static float[] mul(float[] a, float[] b) {
        float[] r = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int rr = 0; rr < 4; rr++) {
                float s = 0;
                for (int k = 0; k < 4; k++) {
                    s += a[k * 4 + rr] * b[c * 4 + k];
                }
                r[c * 4 + rr] = s;
            }
        }
        return r;
    }

    /** A 4x4 inverse (column-major; Gauss-Jordan), null when singular. */
    static float[] invert(float[] m) {
        double[][] a = new double[4][8];
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                a[r][c] = m[c * 4 + r];
            }
            a[r][4 + r] = 1;
        }
        for (int c = 0; c < 4; c++) {
            int piv = c;
            for (int r = c + 1; r < 4; r++) {
                if (Math.abs(a[r][c]) > Math.abs(a[piv][c])) {
                    piv = r;
                }
            }
            if (Math.abs(a[piv][c]) < 1e-12) {
                return null;
            }
            double[] t = a[c];
            a[c] = a[piv];
            a[piv] = t;
            double k = a[c][c];
            for (int j = 0; j < 8; j++) {
                a[c][j] /= k;
            }
            for (int r = 0; r < 4; r++) {
                if (r != c) {
                    double f = a[r][c];
                    for (int j = 0; j < 8; j++) {
                        a[r][j] -= f * a[c][j];
                    }
                }
            }
        }
        float[] o = new float[16];
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                o[c * 4 + r] = (float) a[r][4 + c];
            }
        }
        return o;
    }

    /** The tooltip's lines for a control (name: position; a guard's hint). */
    static String[] tooltip(LinerControls.Ctl c, LinerSystems L) {
        String name = Lang.tr(c.nameKey());
        String pos = "";
        if (c.kind != LinerControls.INFO && c.kind != LinerControls.PBM && c.pos != null) {
            pos = ": " + Lang.tr("sva.liner.pos." + c.pos[Math.max(0, Math.min(c.n() - 1, L.at(c)))]);
            if (c.kind == LinerControls.PB) {
                pos = ": " + Lang.tr(L.at(c) == 1 ? "sva.liner.gui.in" : "sva.liner.gui.out");
            }
        }
        if (c.kind == LinerControls.KNOB) {
            pos = knobValue(c, L);                                  // wave 4: the FCU window's value
        }
        if (c == LinerControls.TL1 || c == LinerControls.TL2) {
            return new String[] {name + ": " + leverWord(L), Lang.tr("sva.liner.gui.hint.lever")};
        }
        if (c == LinerControls.TRIM_L || c == LinerControls.TRIM_R) {
            // wave 7: the trim wheels work in DIRECT / MECH (the auto-trim otherwise)
            String t = L.fctl.manualTrim() ? Lang.tr("sva.liner.gui.trimMan", LinerGfxSVA.num(L.fctl.manTrim, 1)) : Lang.tr("sva.liner.gui.trimAuto");
            return new String[] {name + ": " + t, Lang.tr("sva.liner.gui.hint.trim")};
        }
        if (c == LinerControls.AI_PROBE && L.at(c) == 0) {
            return new String[] {name + ": " + Lang.tr("sva.liner.pos.auto"), Lang.tr("sva.liner.gui.hint.pb")};   // wave 7: out = АВТО
        }
        String hint = c.guard && !L.guard[c.index] ? Lang.tr("sva.liner.gui.guard") : c.kind == LinerControls.INFO ? "" : c.kind == LinerControls.KNOB
                ? Lang.tr("sva.liner.gui.hint.knob") : Lang.tr(
                c.kind == LinerControls.PB || c.kind == LinerControls.PBM ? "sva.liner.gui.hint.pb" : c.kind == LinerControls.TOG
                        ? "sva.liner.gui.hint.tog" : "sva.liner.gui.hint.rot");
        return hint.length() > 0 ? new String[] {name + pos, hint} : new String[] {name + pos};
    }

    static void send(EntityVehicleSVA v, LinerControls.Ctl c, int action) {
        if (c == LinerControls.MCDU1 || c == LinerControls.MCDU2) {
            // wave 5: an MCDU clicked (the cockpit, the «Пульты» screen) - its keyboard screen
            Minecraft.getMinecraft().displayGuiScreen(new GuiMcduSVA(v, c == LinerControls.MCDU1 ? 0 : 1));
            return;
        }
        if (c.kind == LinerControls.INFO && c != LinerControls.TL1 && c != LinerControls.TL2 && c != LinerControls.TRIM_L && c != LinerControls.TRIM_R) {
            return;                                                 // wave 4: the thrust levers click through their detents; wave 7: the trim wheels
        }
        NetSVA.CHANNEL.sendToServer(new NetSVA.LinerControl(v.getEntityId(), c.index, action));
        Minecraft.getMinecraft().getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.func_147674_a(
                new net.minecraft.util.ResourceLocation("gui.button.press"), 1.8F));
    }

    /** Wave 4: the wheel's action on a control (-1: none): an FCU knob turns (Shift: the fine step), a selector / lever / thrust lever steps. */
    static int wheelAction(LinerControls.Ctl c, int w, boolean shift) {
        if (c.kind == LinerControls.KNOB) {
            return w > 0 ? (shift ? LinerControls.A_FINE_UP : LinerControls.A_WHEEL_UP) : shift ? LinerControls.A_FINE_DOWN : LinerControls.A_WHEEL_DOWN;
        }
        if (c.kind == LinerControls.ROT || c.kind == LinerControls.LEV || c.kind == LinerControls.TOG || c == LinerControls.TL1 || c == LinerControls.TL2
                || c == LinerControls.TRIM_L || c == LinerControls.TRIM_R) {
            return w > 0 ? LinerControls.A_WHEEL_UP : LinerControls.A_WHEEL_DOWN;
        }
        return -1;
    }

    /** Wave 4: an FCU knob's value for its tooltip. */
    static String knobValue(LinerControls.Ctl c, LinerSystems L) {
        com.sva.vehicle.LinerAfs F = L.afs;
        if (c == LinerControls.FCU_SPD) {
            return ": " + (F.spdMan ? Lang.tr("sva.liner.gui.managed") + " (" + Math.round(F.spdT * 72) + ")" : F.spd + " " + Lang.tr("sva.liner.du.kmh"));
        }
        if (c == LinerControls.FCU_HDG) {
            return ": " + (F.hdgMan && F.lat != com.sva.vehicle.LinerAfs.L_HDG ? Lang.tr("sva.liner.gui.managed") : String.format("%03d", Integer.valueOf(F.hdg)));
        }
        if (c == LinerControls.FCU_ALT) {
            return ": " + F.alt + " " + Lang.tr("sva.liner.du.m");
        }
        if (c == LinerControls.FCU_VS) {
            return ": " + (F.trk ? LinerGfxSVA.num(F.fpa10 / 10.0, 1) + " FPA" : LinerGfxSVA.num(F.vs10 / 10.0, 1) + " " + Lang.tr("sva.liner.du.ms"));
        }
        return ": " + (F.std ? "STD" : String.valueOf(F.qnh));
    }

    /** Wave 4: the thrust levers' position word (a detent, between, the reverse). */
    static String leverWord(LinerSystems L) {
        com.sva.vehicle.LinerAfs F = L.afs;
        if (F.rev > 0) {
            return Lang.tr(F.rev >= 0.99 ? "sva.liner.pos.revMax" : "sva.liner.pos.revIdle");
        }
        int d = com.sva.vehicle.LinerAfs.detent(F.lever);
        String[] k = {"idle", "cl", "flx", "toga"};
        return d >= 0 ? Lang.tr("sva.liner.pos." + k[d]) : Math.round(F.lever * 100) + " %";
    }

    // ---------------------------------------------------------------- the cursor screen

    /** The see-through screen while «Курсор кабины» is held: no background, the world goes on (the game doesn't pause). */
    static final class Cursor extends GuiScreen {
        private final EntityVehicleSVA v;
        private LinerControls.Ctl hover;

        Cursor(EntityVehicleSVA v) {
            this.v = v;
        }

        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }

        private boolean held() {
            return VehicleKeyBindsSVA.heldNow(VehicleFn.CURSOR);
        }

        @Override
        public void updateScreen() {
            if (!held() || v.isDead || HudVehicleSVA.vehicleOf(mc.thePlayer) != v) {
                LinerCockpitSVA.hovered = -1;
                mc.displayGuiScreen(null);
            }
        }

        @Override
        public void onGuiClosed() {
            LinerCockpitSVA.hovered = -1;
        }

        @Override
        public void drawScreen(int x, int y, float pt) {
            hover = pick(Mouse.getX(), Mouse.getY(), null);
            LinerCockpitSVA.hovered = hover == null ? -1 : hover.index;
            LinerSystems L = v.liner();
            if (hover != null && L != null) {
                java.util.List<String> lines = java.util.Arrays.asList(tooltip(hover, L));
                func_146283_a(lines, x, y);
            }
            // a small crosshair-free hint at the bottom
            drawCenteredString(fontRendererObj, Lang.tr("sva.liner.gui.cursor"), width / 2, height - 46, 0xC0C0C0);
            super.drawScreen(x, y, pt);
        }

        @Override
        protected void mouseClicked(int x, int y, int btn) {
            LinerControls.Ctl c = pick(Mouse.getX(), Mouse.getY(), null);
            if (c != null && (btn == 0 || btn == 1)) {
                send(v, c, btn == 0 ? LinerControls.A_PRIMARY : LinerControls.A_SECONDARY);
            }
        }

        @Override
        public void handleMouseInput() {
            super.handleMouseInput();
            int w = Mouse.getEventDWheel();
            if (w != 0) {
                LinerControls.Ctl c = pick(Mouse.getX(), Mouse.getY(), null);
                int a = c == null ? -1 : wheelAction(c, w, isShiftKeyDown());
                if (a >= 0) {
                    send(v, c, a);
                }
            }
        }

        @Override
        protected void keyTyped(char ch, int key) {
            if (key == Keyboard.KEY_ESCAPE) {
                mc.displayGuiScreen(null);
            }
        }
    }
}
