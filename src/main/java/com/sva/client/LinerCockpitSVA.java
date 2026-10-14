package com.sva.client;

import java.util.List;

import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerInterior;
import com.sva.vehicle.LinerLights;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.SystemsMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Liner wave 1 (mock-ups veh_liner_6 / 7 / 8 / 9): the SA-320's inside, drawn in the liner's model frame.
 * <ul>
 * <li>The cockpit for the two pilots (the own first-person view from a pilot seat), the cabin for the passengers and for
 * anyone standing at the open L1 door (vehicles.linerCabin): LinerInterior's triangles in display lists, textured from the
 * baked atlas (LinerTexSVA), drawn unlit - the world's light through the light map; the dome light (ПЛАФОН, on the board's
 * power) lights the cockpit fully; at night the integral panel lighting (the atlas' GLOW, additive) by ЯРК. ПАНЕЛИ.</li>
 * <li>The DUs (LinerDisplaysSVA) on the main panel's screens, the brightness by the crew panels' knobs.</li>
 * <li>Every control's run-time look from the control table and the synced systems: the lit halves of the push buttons
 * (only with the board powered), the selector bars, the toggle levers and their guards, the window digits, the 3D levers
 * (thrust, flaps, speed brake, ENG MASTERs, the parking brake, the gear), the hovered control's outline (LinerClickSVA).</li>
 * </ul>
 */
@SideOnly(Side.CLIENT)
public final class LinerCockpitSVA {

    static final int L_AMB = 0xFFA828, L_WHT = 0xF4F4EC, L_BLU = 0x46AAFF, L_GRN = 0x3CE86E, L_RED = 0xF0302A;
    /** Lists: cockpit plain / textured / glow, cabin plain / textured / emitters. */
    private static int ckPlain, ckTex, ckGlow, cabPlain, cabTex, cabEmit;
    /** Wave 6: the cabin's cove lights and the emergency floor path (no colour of their own: the light's colour is set). */
    private static int cabCove, cabPath;
    private static boolean compiled;
    /** The control under the mouse in the cursor mode (-1 none) - LinerClickSVA sets it. */
    static int hovered = -1;

    private LinerCockpitSVA() {
    }

    private static void compile() {
        if (compiled) {
            return;
        }
        compiled = true;
        List<LinerInterior.Tri> tris = LinerInterior.build().tris();
        ckPlain = list(tris, LinerInterior.COCKPIT, 0);
        ckTex = list(tris, LinerInterior.COCKPIT, 1);
        ckGlow = list(tris, LinerInterior.COCKPIT, 2);
        cabPlain = list(tris, LinerInterior.CABIN, 0);
        cabTex = list(tris, LinerInterior.CABIN, 1);
        cabEmit = list(tris, LinerInterior.CABIN, 3);
        cabCove = list(tris, LinerInterior.CABIN, 4);
        cabPath = list(tris, LinerInterior.CABIN, 5);
    }

    /** kind 0 plain (no uv, not emitting), 1 textured, 2 the glow panels (textured, glow flag), 3 emitters. */
    private static int list(List<LinerInterior.Tri> tris, int part, int kind) {
        int l = GL11.glGenLists(1);
        GL11.glNewList(l, GL11.GL_COMPILE);
        GL11.glBegin(GL11.GL_TRIANGLES);
        for (LinerInterior.Tri t : tris) {
            if (t.part != part) {
                continue;
            }
            // wave 6: the emitters split - 3 the others (their own colour), 4 the cove lights, 5 the floor path (colour at draw)
            boolean ok = kind == 0 ? t.uv == null && !t.emit : kind == 1 ? t.uv != null : kind == 2 ? t.uv != null && t.glow
                    : kind == 3 ? t.emit && t.light == 0 : kind == 4 ? t.emit && t.light == LinerInterior.LIGHT_COVE : t.emit && t.light == LinerInterior.LIGHT_PATH;
            if (!ok) {
                continue;
            }
            int c = t.rgb;
            if (kind < 4) {
                GL11.glColor3f((c >> 16 & 255) / 255F, (c >> 8 & 255) / 255F, (c & 255) / 255F);
            }
            for (int k = 0; k < 3; k++) {
                if (t.uv != null) {
                    GL11.glTexCoord2f(t.uv[k * 2], t.uv[k * 2 + 1]);
                }
                GL11.glVertex3f(t.p[k * 3], t.p[k * 3 + 1], t.p[k * 3 + 2]);
            }
        }
        GL11.glEnd();
        GL11.glEndList();
        return l;
    }

    /** Draws the inside for this viewer (the model frame is set). */
    static void render(EntityVehicleSVA v, float pt, boolean ownView, EntityPlayer player) {
        LinerSystems L = v.liner();
        if (L == null) {
            return;
        }
        boolean pilot = ownView && LinerClickSVA.pilotSeat(player, v);
        boolean cabin = ConfigSVA.linerCabin && (ownView && !pilot || L.door && nearDoor(v, player));
        if (!pilot && !cabin) {
            return;
        }
        compile();
        int[] atlas = LinerTexSVA.atlas();
        float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
        boolean power = v.powered();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        try {
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
            float night = VehicleLightsSVA.darkness(v);
            if (pilot) {
                if (ConfigSVA.linerCabinLighting) {
                    // wave 6: the dome, the flood knobs, the console / floor lights over the world's own light (night + all off: dark)
                    float k = LinerLights.cockpitLight(L, power);
                    if (k > 0) {
                        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, Math.max(bx, k), Math.max(by, k));
                    }
                } else if (power && L.at(LinerControls.DOME) != 2) {
                    float k = L.at(LinerControls.DOME) == 0 ? 240F : 200F;
                    OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, Math.max(bx, k), Math.max(by, k));
                }
                draw(ckPlain, ckTex, atlas);
                if (power && atlas != null && atlas[1] > 0 && night > 0.05F) {
                    // the integral panel lighting: the glow atlas added, by ЯРК. ПАНЕЛИ and the night
                    float k = night * (0.15F + 0.85F * L.at(LinerControls.ANN_BRT) / 4F);
                    GL11.glEnable(GL11.GL_BLEND);
                    GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
                    GL11.glDepthFunc(GL11.GL_LEQUAL);
                    GL11.glDepthMask(false);
                    GL11.glEnable(GL11.GL_TEXTURE_2D);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas[1]);
                    OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
                    GL11.glColor3f(k, k, k);
                    GL11.glCallList(ckGlow);
                    GL11.glDepthMask(true);
                    GL11.glDisable(GL11.GL_BLEND);
                }
                OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glPushMatrix();
                GL11.glTranslatef(LinerControls.CK_X, LinerControls.CK_Y, 0F);
                LinerClickSVA.saveMatrices();                       // the cursor's ray is built in this frame
                HiResSVA.beginFrame();
                screens(v, L, pt);
                overlays(v, L, power);
                levers(v, L, pt);
                frost(L);                                           // wave 6: the windscreen's frost rim (inside the glass)
                GL11.glPopMatrix();
                OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
            }
            if (cabin) {
                // wave 6: САЛОН's step (one dimmer with the galley shed; dark without AC), НОЧЬ - dim with the cove lights blue
                int step = power ? LinerLights.cabinStep(L, L.acPowered(), L.galleyShed) : -1;
                if (!ConfigSVA.linerCabinLighting) {
                    step = power && L.at(LinerControls.CABIN_LT) > 0 ? L.at(LinerControls.CABIN_LT) : -1;
                }
                int k = LinerLights.cabinLight(step);
                if (k > 0) {
                    OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, Math.max(bx, k), Math.max(by, k));
                }
                draw(cabPlain, cabTex, atlas);
                OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, power ? 240F : bx, power ? 240F : by);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glCallList(cabEmit);
                OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
                cabinLights(L, step);
                OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
            }
        } finally {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
            GL11.glPopAttrib();
            GL11.glColor4f(1F, 1F, 1F, 1F);
        }
    }

    /**
     * Wave 6: the cabin's own lights - the cove lights by САЛОН's step (warm white; НОЧЬ dim blue; dark: unlit grey), the
     * emergency floor path (lit green-white with S_EMER), the signs on the front bulkhead (ПРИСТЕГНИТЕ РЕМНИ / НЕ КУРИТЬ /
     * ВЫХОД), the passengers' reading lights (a bright lamp on the PSU and a soft cone down to the seat).
     */
    static void cabinLights(LinerSystems L, int step) {
        float[] cove = step < 0 ? new float[] {0.42F, 0.42F, 0.40F} : step == 0 ? new float[] {0.20F, 0.32F, 0.85F}
                : new float[] {0.62F + 0.12F * step, 0.60F + 0.12F * step, 0.52F + 0.12F * step};
        GL11.glColor3f(cove[0], cove[1], cove[2]);
        GL11.glCallList(cabCove);
        boolean emer = (L.lights.signs & LinerLights.S_EMER) != 0;
        if (emer) {
            GL11.glColor3f(0.75F, 1F, 0.72F);
        } else {
            GL11.glColor3f(0.36F, 0.40F, 0.36F);
        }
        GL11.glCallList(cabPath);
        float cx = LinerInterior.CAB_X - 0.006F, cy = LinerInterior.CAB_Y, s = LinerInterior.CAB_S;
        // the signs: dark boxes, lit with their words
        sign(cx, cy + 1.86F * s, -0.55F * s, s, (L.lights.signs & LinerLights.S_BELTS) != 0, "belts", 0xFFFFFF);
        sign(cx, cy + 1.86F * s, 0.55F * s, s, (L.lights.signs & LinerLights.S_NOSMOKE) != 0, "noSmoke", 0xFFFFFF);
        sign(cx, cy + 1.62F * s, 0F, s, emer, "exit", 0x40F070);
        // the reading lights
        if (L.lights.reading != 0) {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            GL11.glDepthMask(false);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            for (int seat = LinerLights.FIRST_PAX; seat < LinerLights.FIRST_PAX + LinerLights.PAX; seat++) {
                if (!L.lights.readingOn(seat)) {
                    continue;
                }
                int i = seat - LinerLights.FIRST_PAX;
                float x = LinerInterior.CAB_X + READ_ROWS[i / 2] + 0.05F, z = (i % 2 == 0 ? -0.82F : 0.82F) * s, y = cy + 1.585F * s;
                readingLamp(x, y, z, cy + 0.55F * s);
            }
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_BLEND);
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /** The cabin's seat rows (icabin.py, behind the front bulkhead) - LinerInterior.ROWS. */
    static final float[] READ_ROWS = {-1.30F, -2.15F, -3.00F, -3.85F};

    /** A cabin sign on the front bulkhead (facing aft): centre (cy, cz), lit - its word in `col`, else a dark box. */
    private static void sign(float x, float cy, float cz, float s, boolean lit, String word, int col) {
        float w = 0.40F * s, h = 0.11F * s;
        LinerGfxSVA.frame(new float[] {x, cy + h / 2, cz - w / 2}, new float[] {0, 0, w}, new float[] {0, -h, 0}, 400, 110, 0.002F);
        LinerGfxSVA.resetLayer();
        LinerGfxSVA.bright = 1F;
        LinerGfxSVA.rect(0, 0, 400, 110, lit ? 0x1A1C20 : 0x24262A);
        if (lit) {
            LinerGfxSVA.layer();
            LinerGfxSVA.text(Lang.tr("sva.liner.sign." + word), 200, 55, 60, col, 0, 380);
        }
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glPopMatrix();
    }

    /** A reading light: a bright disc facing down on the PSU and a soft cone to the seat (additive). */
    private static void readingLamp(float x, float y, float z, float floorY) {
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(1F, 0.95F, 0.80F, 1F);
        GL11.glVertex3f(x, y - 0.004F, z);
        for (int k = 0; k <= 12; k++) {
            double a = k * Math.PI * 2 / 12;
            GL11.glVertex3f(x + (float) Math.cos(a) * 0.035F, y - 0.004F, z + (float) Math.sin(a) * 0.035F);
        }
        GL11.glEnd();
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int k = 0; k <= 12; k++) {
            double a = k * Math.PI * 2 / 12;
            float c = (float) Math.cos(a), sn = (float) Math.sin(a);
            GL11.glColor4f(1F, 0.92F, 0.75F, 0.16F);
            GL11.glVertex3f(x + c * 0.03F, y - 0.01F, z + sn * 0.03F);
            GL11.glColor4f(1F, 0.92F, 0.75F, 0F);
            GL11.glVertex3f(x - 0.1F + c * 0.28F, floorY, z * 0.85F + sn * 0.28F);
        }
        GL11.glEnd();
    }

    /**
     * Wave 6: the windscreen's frost (config vehicles.linerIceVisuals) - a white rime creeping in from each pane's frame on the
     * inside of the glass (the cockpit frame: LinerInterior's windshield and corner panes), its band and opacity by the
     * synced frost; the window heat clears it.
     */
    static void frost(LinerSystems L) {
        double f = L.ice.frost;
        if (f < 0.02 || !ConfigSVA.linerIceVisuals) {
            return;
        }
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        float band = (float) (0.12 + 0.30 * f), a = (float) Math.min(0.85, 0.25 + 0.6 * f);
        for (float[][] q : PANES) {
            rim(q, band, a);
        }
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /** The windshield's panes (cockpit frame, pulled 0.012 toward the crew): the two front, the two corner panes. */
    static final float[][][] PANES = {
            {{0.648F, 0.36F, -0.45F}, {0.648F, 0.36F, 0F}, {0.968F, -0.04F, 0F}, {0.968F, -0.04F, -0.62F}},
            {{0.648F, 0.36F, 0F}, {0.648F, 0.36F, 0.45F}, {0.968F, -0.04F, 0.62F}, {0.968F, -0.04F, 0F}},
            {{0.44F, 0.36F, -0.79F}, {0.648F, 0.36F, -0.45F}, {0.968F, -0.04F, -0.62F}, {0.69F, -0.04F, -0.94F}},
            {{0.648F, 0.36F, 0.45F}, {0.44F, 0.36F, 0.79F}, {0.69F, -0.04F, 0.94F}, {0.968F, -0.04F, 0.62F}}};

    /** One pane's rim: from each edge (alpha a) in toward the pane's centre by `band` of the way (alpha 0). */
    private static void rim(float[][] q, float band, float a) {
        float[] c = {0, 0, 0};
        for (float[] p : q) {
            for (int i = 0; i < 3; i++) {
                c[i] += p[i] / 4;
            }
        }
        GL11.glBegin(GL11.GL_QUADS);
        for (int e = 0; e < 4; e++) {
            float[] p0 = q[e], p1 = q[(e + 1) % 4];
            GL11.glColor4f(0.88F, 0.94F, 1F, a);
            GL11.glVertex3f(p0[0], p0[1], p0[2]);
            GL11.glVertex3f(p1[0], p1[1], p1[2]);
            GL11.glColor4f(0.88F, 0.94F, 1F, 0F);
            GL11.glVertex3f(p1[0] + (c[0] - p1[0]) * band, p1[1] + (c[1] - p1[1]) * band, p1[2] + (c[2] - p1[2]) * band);
            GL11.glVertex3f(p0[0] + (c[0] - p0[0]) * band, p0[1] + (c[1] - p0[1]) * band, p0[2] + (c[2] - p0[2]) * band);
        }
        GL11.glEnd();
    }

    private static void draw(int plain, int tex, int[] atlas) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glCallList(plain);
        if (atlas != null && atlas[0] > 0) {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas[0]);
        }
        GL11.glCallList(tex);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    /** The player stands within 8 blocks of the open L1 door. */
    static boolean nearDoor(EntityVehicleSVA v, EntityPlayer p) {
        if (p == null) {
            return false;
        }
        double[] w = v.toWorld(com.sva.entity.LinerLogicSVA.DOOR_X, -1.2);
        double dx = p.posX - (v.posX + w[0]), dz = p.posZ - (v.posZ + w[1]);
        return dx * dx + dz * dz < 64;
    }

    // ---------------------------------------------------------------- the panels' frames

    /** Enters a panel's frame (its units, y down) - the pedestal's top face for ty > PED_SPLIT when `top`. */
    static void panelFrame(int panel, boolean top, float lift) {
        if (panel == LinerControls.PED) {
            float W = LinerControls.PANEL_W[panel];
            if (!top) {
                float[] tl = {LinerControls.PED_FRONT[0], LinerControls.PED_FRONT[1], -LinerControls.PED_Z};
                float[] u = {0, 0, 2 * LinerControls.PED_Z};
                float[] vv = {LinerControls.PED_MID[0] - LinerControls.PED_FRONT[0], LinerControls.PED_MID[1] - LinerControls.PED_FRONT[1], 0};
                LinerGfxSVA.frame(tl, u, vv, W, LinerControls.PED_SPLIT, lift);
            } else {
                float h = LinerControls.PANEL_H[panel] - LinerControls.PED_SPLIT;
                float[] tl = {LinerControls.PED_MID[0], LinerControls.PED_MID[1], -LinerControls.PED_Z};
                float[] u = {0, 0, 2 * LinerControls.PED_Z};
                float[] vv = {LinerControls.PED_REAR_X - LinerControls.PED_MID[0], 0, 0};
                LinerGfxSVA.frame(tl, u, vv, W, h, lift);
                GL11.glTranslatef(0F, -LinerControls.PED_SPLIT, 0F);
            }
            return;
        }
        float[] f = LinerControls.FLAT[panel];
        LinerGfxSVA.frame(new float[] {f[0], f[1], f[2]}, new float[] {f[3], f[4], f[5]}, new float[] {f[6], f[7], f[8]}, LinerControls.PANEL_W[panel],
                LinerControls.PANEL_H[panel], lift);
    }

    // ---------------------------------------------------------------- DUs

    /** The PFD / ND / E/WD / SD / ISIS: each screen's quad on the main panel, its brightness. */
    static void screens(EntityVehicleSVA v, LinerSystems L, float pt) {
        float night = ConfigSVA.linerCabinLighting ? VehicleLightsSVA.darkness(v) : 0F;
        for (int du = 0; du < LinerSystems.DUS; du++) {
            float[] s = LinerPaintSVA.SCREENS[du];
            float[] tl = LinerControls.point(LinerControls.MP, s[0], s[1], new float[3]);
            float[] tr = LinerControls.point(LinerControls.MP, s[2], s[1], new float[3]);
            float[] bl = LinerControls.point(LinerControls.MP, s[0], s[3], new float[3]);
            float[] u = {tr[0] - tl[0], tr[1] - tl[1], tr[2] - tl[2]}, vv = {bl[0] - tl[0], bl[1] - tl[1], bl[2] - tl[2]};
            float w = s[2] - s[0], h = s[3] - s[1];
            LinerGfxSVA.frame(tl, u, vv, w, h, 0.004F);
            LinerGfxSVA.bright = brightness(L, du) * LinerLights.screenAuto(night);     // wave 6: + the ambient light
            LinerGfxSVA.lift = 0.0003F;
            try {
                LinerDisplaysSVA.draw(du, v, L, w, h, pt);
            } catch (Throwable t) {
                if (!failed) {
                    failed = true;
                    cpw.mods.fml.common.FMLLog.log(org.apache.logging.log4j.Level.WARN, t, "[SVA] liner display %d failed", Integer.valueOf(du));
                }
            }
            GL11.glPopMatrix();
        }
        LinerGfxSVA.bright = 1F;
    }

    private static boolean failed;

    /** A DU's brightness from its knob (the F/O's for his own side's DUs). */
    static float brightness(LinerSystems L, int du) {
        LinerControls.Ctl k = du == LinerSystems.DU_PFD1 ? LinerControls.PFD_BRT_L : du == LinerSystems.DU_ND1 ? LinerControls.ND_BRT_L
                : du == LinerSystems.DU_PFD2 ? LinerControls.PFD_BRT_R : du == LinerSystems.DU_ND2 ? LinerControls.ND_BRT_R : null;
        return k == null ? 1F : 0.35F + 0.65F * L.at(k) / 4F;
    }

    // ---------------------------------------------------------------- overlays

    static void overlays(EntityVehicleSVA v, LinerSystems L, boolean power) {
        LinerGfxSVA.lift = 0.0005F;
        for (int panel = 0; panel < LinerControls.PANELS; panel++) {
            for (int pass = 0; pass < (panel == LinerControls.PED ? 2 : 1); pass++) {
                panelFrame(panel, pass == 1, 0.0015F);
                LinerGfxSVA.bright = 1F;
                drawPanel(panel, pass, v, L, power);
                GL11.glPopMatrix();
            }
        }
    }

    /** One panel's (pedestal: one face's) run-time look - shared with the flat screen (GuiLinerPanelsSVA). pass: -1 all. */
    static void drawPanel(int panel, int pass, EntityVehicleSVA v, LinerSystems L, boolean power) {
        for (LinerControls.Ctl c : LinerControls.of(panel)) {
            if (panel == LinerControls.PED && pass >= 0 && (c.cy > LinerControls.PED_SPLIT) != (pass == 1)) {
                continue;
            }
            LinerGfxSVA.resetLayer();
            control(c, L, power);
            if (c.index == hovered) {
                LinerGfxSVA.layer();
                LinerGfxSVA.layer();
                LinerGfxSVA.frameRect(c.cx - c.w / 2 - 3, c.cy - c.h / 2 - 3, c.cx + c.w / 2 + 3, c.cy + c.h / 2 + 3, 2.5F, 0xFFFFFF);
            }
        }
        LinerGfxSVA.resetLayer();
        windows(panel, pass, v, L, power);
    }

    static void control(LinerControls.Ctl c, LinerSystems L, boolean power) {
        int p = L.at(c);
        switch (c.kind) {
            case LinerControls.PB:
            case LinerControls.PBM: {
                if (c == LinerControls.ENG_MASTER1 || c == LinerControls.ENG_MASTER2) {
                    break;
                }
                int[] lit = lamps(c, L);
                float h = c.h / 2 - 2;
                if (c == LinerControls.MW_L || c == LinerControls.MW_R || c == LinerControls.MC_L || c == LinerControls.MC_R) {
                    if (power && lit[0] != 0) {
                        // the master lamps light whole: «ГЛАВН. / ОПАСН.» red, «ГЛАВН. / ВНИМ.» amber
                        boolean warn = c == LinerControls.MW_L || c == LinerControls.MW_R;
                        LinerGfxSVA.layer();
                        LinerGfxSVA.rect(c.cx - h + 4, c.cy - h + 4, c.cx + h - 4, c.cy + h - 4, scale(lit[0], 0.55F));
                        LinerGfxSVA.layer();
                        LinerGfxSVA.text(Lang.tr("sva.liner.du.master"), c.cx, c.cy - h / 2 + 1, c.h * 0.2F, 0xFFFFFF, 0, c.w - 10);
                        LinerGfxSVA.text(Lang.tr(warn ? "sva.liner.du.warn" : "sva.liner.du.caut"), c.cx, c.cy + h / 2 - 1, c.h * 0.2F, 0xFFFFFF, 0, c.w - 10);
                    }
                    break;
                }
                if (power && lit[0] != 0 && c.top != null || power && c.kind == LinerControls.PBM && lit[0] != 0) {
                    lampHalf(c, true, lit[0], c.top != null ? c.top : topWord(c));
                }
                if (power && lit[1] != 0) {
                    lampHalf(c, false, lit[1], c.bottom != null ? c.bottom : bottomWord(c));
                }
                if (c.guard && !L.guard[c.index]) {
                    LinerGfxSVA.layer();
                    LinerGfxSVA.col(0xA01410, 0.55F);
                    GL11.glEnable(GL11.GL_BLEND);
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                    rectA(c.cx - h - 6, c.cy - h - 6, c.cx + h + 6, c.cy + h + 6);
                    GL11.glDisable(GL11.GL_BLEND);
                }
                break;
            }
            case LinerControls.ROT: {
                float r = (c.w - 12) / 2;
                float span = span(c);
                int n = c.n();
                float a = n > 1 ? -span / 2 + span * p / (n - 1) : 0;
                boolean knob = isKnob(c);
                LinerGfxSVA.layer();
                if (knob) {
                    LinerGfxSVA.line(LinerGfxSVA.px(c.cx, r * 0.25F, a), LinerGfxSVA.py(c.cy, r * 0.25F, a), LinerGfxSVA.px(c.cx, r * 0.72F, a),
                            LinerGfxSVA.py(c.cy, r * 0.72F, a), Math.max(1.5F, r * 0.1F), 0xF4F4EC);
                } else {
                    float s = (float) Math.sin(Math.toRadians(a)), co = (float) -Math.cos(Math.toRadians(a)), wv = r * 0.32F;
                    // the bar: along the pointer, a little longer than the knob
                    float tx = c.cx + s * r * 1.05F, ty = c.cy + co * r * 1.05F, bx = c.cx - s * r * 0.95F, by = c.cy - co * r * 0.95F;
                    float nx = -co * wv, ny = s * wv;
                    LinerGfxSVA.poly(new float[] {tx + nx, ty + ny, tx - nx, ty - ny, bx - nx, by - ny, bx + nx, by + ny}, 0x121214);
                    LinerGfxSVA.layer();
                    LinerGfxSVA.line(c.cx, c.cy, tx - s * r * 0.1F, ty - co * r * 0.1F, Math.max(1.2F, r * 0.1F), 0xF4F4EC);
                }
                break;
            }
            case LinerControls.TOG: {
                float z = c.h / 62F;
                int n = c.n();
                float[] ys = n == 2 ? new float[] {c.cy - 20 * z, c.cy + 20 * z} : new float[] {c.cy - 22 * z, c.cy, c.cy + 22 * z};
                LinerGfxSVA.layer();
                float y = ys[Math.max(0, Math.min(n - 1, p))];
                if (Math.abs(y - c.cy) < 1) {
                    LinerGfxSVA.disc(c.cx, c.cy, 5 * z, 0xCDD0D4);
                } else {
                    float tip = c.cy + (y - c.cy) * 1.05F;
                    LinerGfxSVA.poly(new float[] {c.cx - 4.2F * z, c.cy, c.cx + 4.2F * z, c.cy, c.cx + 5.2F * z, tip, c.cx - 5.2F * z, tip}, 0xC4C8CE);
                    LinerGfxSVA.layer();
                    LinerGfxSVA.disc(c.cx, tip, 5.6F * z, 0xE4E6E8);
                }
                if (c.guard) {
                    LinerGfxSVA.layer();
                    GL11.glEnable(GL11.GL_BLEND);
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                    LinerGfxSVA.col(0xA01410, L.guard[c.index] ? 0.25F : 0.75F);
                    if (L.guard[c.index]) {
                        rectA(c.cx - 13 * z, c.cy - 62 * z, c.cx + 13 * z, c.cy - 32 * z);       // lifted: folded up over the switch
                    } else {
                        rectA(c.cx - 13 * z, c.cy - 30 * z, c.cx + 13 * z, c.cy + 30 * z);
                    }
                    GL11.glDisable(GL11.GL_BLEND);
                }
                break;
            }
            default:
                break;
        }
    }

    private static void rectA(float x0, float y0, float x1, float y1) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3f(x0, y0, 0.0012F);
        GL11.glVertex3f(x0, y1, 0.0012F);
        GL11.glVertex3f(x1, y1, 0.0012F);
        GL11.glVertex3f(x1, y0, 0.0012F);
        GL11.glEnd();
    }

    /** One lit lamp half: the dim fill and the word in its colour. */
    static void lampHalf(LinerControls.Ctl c, boolean top, int col, String word) {
        float h = (c.h - 4) / 2, i = Math.max(3.5F, c.w * 0.1F);
        float y0 = top ? c.cy - h + i : c.cy + 1, y1 = top ? c.cy - 1 : c.cy + h - i;
        LinerGfxSVA.layer();
        LinerGfxSVA.rect(c.cx - h + i, y0, c.cx + h - i, y1, scale(col, 0.18F));
        LinerGfxSVA.layer();
        LinerGfxSVA.text(Lang.tr("sva.liner.pos." + word), c.cx, (y0 + y1) / 2, Math.min(c.h * 0.24F, (y1 - y0) * 0.95F), col, 0, c.w - 2 * i - 2);
    }

    static int scale(int rgb, float k) {
        return (int) ((rgb >> 16 & 255) * k) << 16 | (int) ((rgb >> 8 & 255) * k) << 8 | (int) ((rgb & 255) * k);
    }

    private static String topWord(LinerControls.Ctl c) {
        return c == LinerControls.APU_START ? "ready" : "fault";
    }

    private static String bottomWord(LinerControls.Ctl c) {
        return "on";
    }

    /** {top colour or 0, bottom colour or 0} of a push button now. */
    static int[] lamps(LinerControls.Ctl c, LinerSystems L) {
        int top = 0, bot = 0;
        int p = L.at(c);
        if (c.kind == LinerControls.PB && c.bottom != null) {
            boolean lit = c.bottomWhenOut ? p == 0 : p == 1;
            if (lit && !"auto".equals(c.bottom)) {
                bot = "on".equals(c.bottom) ? L_BLU : "decel".equals(c.bottom) ? L_GRN : L_WHT;
                if (c.panel == LinerControls.GS && !"on".equals(c.bottom)) {
                    bot = L_GRN;
                }
                if (c.panel == LinerControls.GS && "on".equals(c.bottom)) {
                    bot = L_GRN;                                    // the EFIS / FD / LS buttons: a green bar
                }
            }
        }
        if (c == LinerControls.APU_MASTER && L.apuFault || c == LinerControls.GALLEY && L.galleyShed
                || (c == LinerControls.BLEED1 || c == LinerControls.BLEED2) && (L.caut & LinerSystems.C_BLEED) != 0) {
            top = L_AMB;
        }
        if (c == LinerControls.EXT_PWR && L.extAvail && !L.extOn) {
            top = L_GRN;
        }
        if (c == LinerControls.APU_START) {
            top = L.apuRunning() ? L_GRN : 0;
            bot = L.apuStartLit ? L_BLU : 0;
        }
        if (L.faultLamp(c)) {
            top = L_AMB;                                            // wave 2: the hydraulic pumps, the computers, ГОРЯЧ
        }
        if (L.greenLamp(c)) {
            top = L_GRN;                                            // wave 2: the autobrake's ТОРМОЖ.
        }
        if (L.at(LinerControls.ANN_LT) == 0 && L.powered() && c.kind == LinerControls.PB) {
            // wave 6: АННУНЦ. ТЕСТ - every push button's halves lit (amber over, its own colour under)
            top = c.top != null ? L_AMB : top;
            bot = c.bottom != null ? ("on".equals(c.bottom) ? L_BLU : L_WHT) : bot;
        }
        boolean flash = (System.currentTimeMillis() / 400) % 2 == 0;
        if ((c == LinerControls.MW_L || c == LinerControls.MW_R) && L.masterWarn && flash) {
            top = bot = L_RED;
        }
        if ((c == LinerControls.MC_L || c == LinerControls.MC_R) && L.masterCaut) {
            top = bot = L_AMB;
        }
        return new int[] {top, bot};
    }

    /** The master warning / caution show both words; their words. */
    static boolean isKnob(LinerControls.Ctl c) {
        return c == LinerControls.ANN_BRT || c.panel == LinerControls.SCL || c.panel == LinerControls.SCR;
    }

    /** A selector's sweep (deg) - the painter's spans. */
    static float span(LinerControls.Ctl c) {
        if (c == LinerControls.XBLEED || c == LinerControls.ENG_MODE) {
            return 110;
        }
        if (c == LinerControls.CABIN_LT) {
            return 120;
        }
        if (c == LinerControls.ND_MODE_L || c == LinerControls.ND_MODE_R) {
            return 150;
        }
        if (c == LinerControls.ND_RANGE_L || c == LinerControls.ND_RANGE_R) {
            return 170;
        }
        if (isKnob(c)) {
            return 240;
        }
        return 100;
    }

    /** The digital windows and the lamps that are no control (ADIRS ВЫСТ. / НА АКБ, the engines' ОТКАЗ, the ECAM key bars). */
    static void windows(int panel, int pass, EntityVehicleSVA v, LinerSystems L, boolean power) {
        if (!power) {
            return;
        }
        LinerGfxSVA.layer();
        if (panel == LinerControls.OVH) {
            if (L.bat1V > 0) {
                LinerGfxSVA.text(LinerGfxSVA.num(L.bat1V, 1) + " " + Lang.tr("sva.liner.du.v"), 1300, 490, 21, L_GRN, 0, 80);
            }
            if (L.bat2V > 0) {
                LinerGfxSVA.text(LinerGfxSVA.num(L.bat2V, 1) + " " + Lang.tr("sva.liner.du.v"), 1440, 490, 21, L_GRN, 0, 80);
            }
            LinerGfxSVA.text(Math.round(Math.max(L.ductL, L.ductR)) + " psi", 220, 1050, 13, L_GRN, 0, 60);
            if (L.apu != LinerSystems.A_OFF) {
                LinerGfxSVA.text("N " + Math.round(L.apuN) + " %", 1110, 1015, 15, L_GRN, 0, 116);
            }
            int ad = Math.max(L.at(LinerControls.ADIRS1), Math.max(L.at(LinerControls.ADIRS2), L.at(LinerControls.ADIRS3)));
            if (ad > 0) {
                LinerGfxSVA.text(Lang.tr("sva.liner.pos." + LinerControls.ADIRS1.pos[ad]), 105, 175, 16, L_GRN, 0, 72);
            }
            boolean aligning = v.insStatus() == SystemsMath.INS_ALIGN;
            LinerControls.Ctl[] sel = {LinerControls.ADIRS1, LinerControls.ADIRS3, LinerControls.ADIRS2};
            float[] xs = {250, 385, 520};
            for (int i = 0; i < 3; i++) {
                if (aligning && L.at(sel[i]) > 0) {
                    LinerGfxSVA.rect(xs[i] + 58 - 20, 352 - 9, xs[i] + 58 + 20, 352 + 9, scale(L_WHT, 0.18F));
                    LinerGfxSVA.layer();
                    LinerGfxSVA.text(Lang.tr("sva.liner.du.align"), xs[i] + 58, 352, 10, L_WHT, 0, 38);
                }
            }
            if (L.essOnly && ad > 0) {
                LinerGfxSVA.rect(105 - 38, 100 - 13, 105 + 38, 100 + 13, scale(L_AMB, 0.18F));
                LinerGfxSVA.layer();
                LinerGfxSVA.text(Lang.tr("sva.liner.du.onBat"), 105, 100, 12.5F, L_AMB, 0, 74);
            }
            // wave 2: the law window of the F/CTL group
            int law = L.fctl.law;
            LinerGfxSVA.text(Lang.tr("sva.liner.law." + com.sva.vehicle.LinerFctl.LAW_KEYS[law]), 1670, 350, 13, law == 0 ? L_GRN : L_AMB, 0, 74);
            // wave 6: the anti-ice group's «ЛЁД» lamp (ice detected) between the push buttons; the zone selectors' °C
            if (L.ice.detected()) {
                LinerGfxSVA.rect(810 - 30, 942 - 12, 810 + 30, 942 + 12, scale(L_AMB, 0.2F));
                LinerGfxSVA.layer();
                LinerGfxSVA.text(Lang.tr("sva.liner.du.ice"), 810, 942, 15, L_AMB, 0, 56);
            }
            for (LinerControls.Ctl t : new LinerControls.Ctl[] {LinerControls.TEMP_CK, LinerControls.TEMP_CAB}) {
                LinerGfxSVA.text(Math.round(com.sva.vehicle.LinerIce.TEMP[L.at(t)]) + " C", t.cx, t.cy + 30, 11, L_GRN, 0, 50);
            }
        }
        if (panel == LinerControls.MP) {
            brakeGauge(L);
            gearLights(L);
        }
        if (panel == LinerControls.GS) {
            fcuWindows(L);                                          // wave 4: the FCU
        }
        if (panel == LinerControls.PED && pass != 0) {
            // wave 2: the gravity extension - the crank's turns, the red ШАССИ lamp while it falls / disagrees
            LinerControls.Ctl k = LinerControls.GEAR_GRAV;
            LinerGfxSVA.text(L.at(k) + " / " + com.sva.vehicle.LinerGear.CRANK_TURNS, k.cx + 70, k.cy + 45, 14, L.at(k) > 0 ? L_AMB : L_WHT, 0, 60);
            if (L.gearRedLamp() && (System.currentTimeMillis() / 500) % 2 == 0) {
                LinerGfxSVA.rect(630 - 43, 1530 - 10, 630 + 43, 1530 + 10, scale(L_RED, 0.3F));
                LinerGfxSVA.layer();
                LinerGfxSVA.text(Lang.tr("sva.liner.du.gear"), 630, 1530, 11, L_RED, 0, 84);
            }
        }
        if (panel == LinerControls.PED && pass != 0) {
            for (int i = 0; i < 2; i++) {
                if (L.eng[i].fault != LinerSystems.F_NONE) {
                    float x = (i == 0 ? 210 : 550) + 38;
                    LinerGfxSVA.rect(x - 30, 1098 - 10, x + 30, 1098 + 10, scale(L_AMB, 0.2F));
                    LinerGfxSVA.layer();
                    LinerGfxSVA.text(Lang.tr("sva.liner.pos.fault"), x, 1098, 10, L_AMB, 0, 56);
                }
            }
        }
        if (panel == LinerControls.PED && pass != 1 && com.sva.ConfigSVA.linerMcdu3d) {
            // wave 5: the two MCDUs' screens (what the server sent the pilots - McduScreenSVA, as the keyboard screen)
            for (int i = 0; i < 2; i++) {
                float x0 = (i == 0 ? 15 : 385) + 62;
                McduScreenSVA.draw(McduClientSVA.screen(v.getEntityId(), i), x0, 30, 236, 182, L.powered());
                LinerGfxSVA.layer();
            }
        }
        if (panel == LinerControls.PED && pass != 1) {
            int page = L.autoPage();
            // wave 3: the checklists light ВСЕ (its cycle reaches them); the cruise page has no key
            int key = page == com.sva.vehicle.LinerEcam.P_CHECK ? com.sva.vehicle.LinerEcam.P_ALL : page;
            if (key < LinerControls.ECAM.length) {
                LinerControls.Ctl k = LinerControls.ECAM[key];
                LinerGfxSVA.rect(k.cx - 20, k.cy + 9, k.cx + 20, k.cy + 12, L_GRN);
            }
        }
        if (panel == LinerControls.MP) {
            long tod = v.worldObj.getWorldTime() % 24000;
            int hh = (int) ((tod / 1000 + 6) % 24), mm = (int) (tod % 1000 * 60 / 1000);
            LinerGfxSVA.text(String.format("%02d:%02d", Integer.valueOf(hh), Integer.valueOf(mm)), 803, 205, 12, L_WHT, 0, 50);
        }
    }

    /**
     * Wave 2: the triple brake indicator on the main panel (the painted dial at 803 / 345): the accumulator's needle over the
     * dial (0..3000 psi across -60..60°), the alternate system's L / R pressures as two bars under it.
     */
    static void brakeGauge(LinerSystems L) {
        float cx = 803, cy = 345;
        float a = (float) (-60 + 120 * Math.max(0, Math.min(1, L.hyd.accu / 3000.0)));
        LinerGfxSVA.line(cx, cy, LinerGfxSVA.px(cx, 34, a), LinerGfxSVA.py(cy, 34, a), 2.5F, L_GRN);
        LinerGfxSVA.text(Lang.tr("sva.liner.du.accu"), cx, cy - 12, 8, L_WHT, 0, 40);
        double[] p = {L.gear.brakeL, L.gear.brakeR};
        for (int i = 0; i < 2; i++) {
            float x = cx + (i == 0 ? -12 : 12), top = cy + 30, h = (float) (22 * Math.max(0, Math.min(1, p[i] / 3000.0)));
            LinerGfxSVA.rect(x - 4, top - 22, x + 4, top, 0x101010);
            LinerGfxSVA.layer();
            if (h > 0) {
                LinerGfxSVA.rect(x - 3, top - h, x + 3, top, L_GRN);
            }
            LinerGfxSVA.text(i == 0 ? "L" : "R", x, top + 7, 7, L_WHT, 0, 12);
        }
    }

    /** Wave 2: the gear's three lights beside the lever: green down and locked, red in transit / disagreeing. */
    static void gearLights(LinerSystems L) {
        boolean down = L.gear.downLocked(), up = L.gear.pos >= 1 - 1e-6;
        int c = down ? L_GRN : up && !L.gearRedLamp() ? 0 : L_RED;
        if (c == 0) {
            return;
        }
        float[][] xy = {{1300, 395}, {1285, 425}, {1315, 425}};
        for (float[] q : xy) {
            LinerGfxSVA.tri(q[0] - 9, q[1] - 7, q[0] + 9, q[1] - 7, q[0], q[1] + 8, c);
        }
    }

    // ---------------------------------------------------------------- 3D levers

    static void levers(EntityVehicleSVA v, LinerSystems L, float pt) {
        LinerGfxSVA.bright = 1F;
        // the pedestal's top: thrust levers, flaps, speed brake, ENG MASTERs, the parking brake
        panelFrame(LinerControls.PED, true, 0F);
        LinerGfxSVA.resetLayer();
        float ty = leverY(L);
        for (float x : new float[] {330, 430}) {
            box(x - 7, ty, x + 7, LinerPaintSVA.TL_MREV + 15, 0.002F, 0.06F, 0x82868C);
            box(x - 46, ty - 18, x + 46, ty + 18, 0.06F, 0.10F, 0x2E3034);
            box(x - 40, ty - 15, x + 40, ty - 6, 0.10F, 0.104F, 0x5A5C62);
        }
        float fy = LinerPaintSVA.FLAP_Y[Math.max(0, Math.min(4, L.at(LinerControls.FLAPS)))];
        box(653, fy - 4, 661, fy + 4, 0.002F, 0.07F, 0x82868C);
        box(615, fy - 16, 700, fy + 16, 0.07F, 0.09F, 0xD2D4D8);
        float[] sb = {642, 678, 780, 890};
        float sy = sb[Math.max(0, Math.min(3, L.at(LinerControls.SPD_BRK)))];
        box(76, sy - 4, 84, sy + 4, 0.002F, 0.06F, 0x82868C);
        box(46, sy - 14, 114, sy + 14, 0.06F, 0.08F, 0xCDD0D4);
        for (int i = 0; i < 2; i++) {
            float x = i == 0 ? 210 : 550;
            float my = L.at(i == 0 ? LinerControls.ENG_MASTER1 : LinerControls.ENG_MASTER2) == 1 ? 1137 : 1183;
            box(x - 30, my - 13, x + 30, my + 13, 0.004F, 0.03F, 0x3C3E42);
            box(x - 26, my - 10, x + 26, my - 4, 0.03F, 0.032F, 0x6E7076);
        }
        boolean park = L.on(LinerControls.PARK_BRK);
        if (park) {
            box(134, 1225, 146, 1285, 0.004F, 0.035F, 0x46484C);
        } else {
            box(110, 1249, 170, 1261, 0.004F, 0.035F, 0x46484C);
        }
        GL11.glPopMatrix();
        // the gear lever: its wheel-shaped knob in the slot on the main panel (up / down)
        panelFrame(LinerControls.MP, false, 0F);
        LinerGfxSVA.resetLayer();
        float gy = L.at(LinerControls.GEAR) == 0 ? 352 : 578;
        box(1222, gy - 5, 1236, gy + 5, 0.002F, 0.07F, 0x96989C);
        box(1206, gy - 16, 1252, gy + 16, 0.06F, 0.085F, 0xE2E4E6);
        GL11.glPopMatrix();
        sticks(v, pt);
    }

    /**
     * Wave 4: the thrust levers' knob on the quadrant (pedestal units): the painted detents TOGA / FLX / CL / IDLE at the
     * LinerAfs detents' thrust (linear between), the reverse from REV IDLE to REV MAX below idle.
     */
    static float leverY(LinerSystems L) {
        com.sva.vehicle.LinerAfs F = L.afs;
        if (F.rev > 0) {
            double k = (F.rev - com.sva.vehicle.LinerAfs.REV_IDLE) / (1 - com.sva.vehicle.LinerAfs.REV_IDLE);
            return (float) (LinerPaintSVA.TL_REV + Math.max(0, Math.min(1, k)) * (LinerPaintSVA.TL_MREV - LinerPaintSVA.TL_REV));
        }
        double[] d = com.sva.vehicle.LinerAfs.DETENTS;
        float[] y = {LinerPaintSVA.TL_IDLE, LinerPaintSVA.TL_CL, LinerPaintSVA.TL_FLX, LinerPaintSVA.TL_TOGA};
        double l = Math.max(0, Math.min(1, F.lever));
        for (int i = 1; i < d.length; i++) {
            if (l <= d[i]) {
                double k = (l - d[i - 1]) / (d[i] - d[i - 1]);
                return (float) (y[i - 1] + k * (y[i] - y[i - 1]));
            }
        }
        return LinerPaintSVA.TL_TOGA;
    }

    /**
     * Wave 4: the FCU's windows and lamps on the glare shield (panel units, LinerPaintSVA.fcu's layout): SPD / HDG / ALT /
     * V/S (dashes and the white managed dot when managed), the AP 1 / AP 2 / A/THR / LOC / EXPED / APPR green bars, the
     * EFIS baro windows (QNH / STD).
     */
    static void fcuWindows(LinerSystems L) {
        com.sva.vehicle.LinerAfs F = L.afs;
        int amb = 0xFFB030;
        boolean spdWin = !F.spdMan || F.spdPrev > 0;
        win(685, spdWin ? String.valueOf(F.spd) : "---", amb);
        if (F.spdMan) {
            LinerGfxSVA.disc(745, 52, 4.5F, L_WHT);
        }
        boolean hdgWin = !F.hdgMan || F.lat == com.sva.vehicle.LinerAfs.L_HDG || F.hdgPrev > 0;
        win(845, hdgWin ? String.format("%03d", Integer.valueOf(F.hdg)) : "---", amb);
        if (F.hdgMan && F.lat != com.sva.vehicle.LinerAfs.L_HDG) {
            LinerGfxSVA.disc(905, 52, 4.5F, L_WHT);
        }
        win(1145, String.valueOf(F.alt), amb);
        boolean altMan = F.ver == com.sva.vehicle.LinerAfs.V_CLB || F.ver == com.sva.vehicle.LinerAfs.V_DES
                || (F.verArm & (com.sva.vehicle.LinerAfs.AV_CLB | com.sva.vehicle.LinerAfs.AV_DES)) != 0;
        if (altMan) {
            LinerGfxSVA.disc(1205, 52, 4.5F, L_WHT);
        }
        boolean vsWin = F.ver == com.sva.vehicle.LinerAfs.V_VS || F.vsPrev > 0;
        String vs = F.trk ? LinerGfxSVA.num(F.fpa10 / 10.0, 1) : LinerGfxSVA.num(F.vs10 / 10.0, 1);
        if (!vs.startsWith("-")) {
            vs = "+" + vs;
        }
        win(1315, vsWin ? vs : "-----", amb);
        // the TRK-FPA labels: «ПУ» / «УН» lit instead of «КУРС» / «В/С»
        LinerGfxSVA.text(F.trk ? "TRK FPA" : "HDG V/S", 770, 140, 9, L_WHT, 0, 60);
        // the buttons' green bars
        bar(975, 49, F.ap1);
        bar(1025, 49, F.ap2);
        bar(1000, 117, F.athr || F.aFloor || F.togaLk);
        boolean locOnly = (F.latArm & com.sva.vehicle.LinerAfs.AL_LOC) != 0 && (F.verArm & com.sva.vehicle.LinerAfs.AV_GS) == 0
                || (F.lat == com.sva.vehicle.LinerAfs.L_LOC || F.lat == com.sva.vehicle.LinerAfs.L_LOC_CAP) && F.ver != com.sva.vehicle.LinerAfs.V_GS
                && F.ver != com.sva.vehicle.LinerAfs.V_GS_CAP;
        boolean appr = !locOnly && F.approachArmedOrOn();
        bar(910, 161.5F, locOnly);
        bar(1085, 161.5F, F.exped);
        bar(1250, 161.5F, appr);
        // the EFIS baro windows
        for (float x : new float[] {190, 1550}) {
            LinerGfxSVA.rect(x - 34, 34, x + 34, 58, 0x050607);
            LinerGfxSVA.layer();
            LinerGfxSVA.text(F.std ? "Std" : String.valueOf(F.qnh), x, 46, 20, amb, 0, 64);
        }
    }

    private static void win(float cx, String t, int c) {
        LinerGfxSVA.rect(cx - 50, 38, cx + 50, 66, 0x050607);
        LinerGfxSVA.layer();
        LinerGfxSVA.text(t, cx, 52, 24, c, 0, 96);
    }

    private static void bar(float cx, float cy, boolean on) {
        if (on) {
            LinerGfxSVA.rect(cx - 13, cy - 2.5F, cx + 13, cy + 2.5F, L_GRN);
        }
    }

    /** Wave 2: the console point of the sidesticks' bases (lateral console units) and the base's height over it. */
    static final float STICK_TX_L = 370, STICK_TX_R = 130, STICK_TY = 500, STICK_BASE = 0.045F;
    /** The sidestick's travel (deg) at full roll / pitch, the pedals' (blocks fore / aft) at full yaw. */
    static final float STICK_DEG = 16, PEDAL_TRAVEL = 0.035F;

    /**
     * Wave 2: the two sidesticks (the captain's commands, the F/O's moves alike - visual) tilted by the stick (pull = the top
     * aft, right = the top to the right) and the rudder pedals (the right one forward for a right yaw) - in the cockpit frame.
     */
    static void sticks(EntityVehicleSVA v, float pt) {
        com.sva.vehicle.AnimMath.State a = v.anim;
        float roll = com.sva.vehicle.AnimMath.lerp(a.prevCtlRoll, a.ctlRoll, pt), pitch = com.sva.vehicle.AnimMath.lerp(a.prevCtlPitch, a.ctlPitch, pt);
        float yaw = com.sva.vehicle.AnimMath.lerp(a.prevCtlYaw, a.ctlYaw, pt);
        for (int s = -1; s <= 1; s += 2) {
            int panel = s < 0 ? LinerControls.SCL : LinerControls.SCR;
            float[] p = LinerControls.point(panel, s < 0 ? STICK_TX_L : STICK_TX_R, STICK_TY, new float[3]);
            GL11.glPushMatrix();
            GL11.glTranslatef(p[0], p[1] + STICK_BASE, p[2]);
            GL11.glRotatef(pitch * STICK_DEG, 0F, 0F, 1F);
            GL11.glRotatef(roll * STICK_DEG, 1F, 0F, 0F);
            prism(0F, 0F, 0F, 0.025F, 0.12F, 0F, 0.02F, 0x28282C);
            prism(0.029F, 0.114F, -s * 0.012F, 0.031F, 0.124F, -s * 0.016F, 0.0075F, 0xE11E1A);
            GL11.glPopMatrix();
            // pedals: two plates in front of each pilot
            float zc = s * 0.52F;
            for (int k = -1; k <= 1; k += 2) {
                float off = yaw * PEDAL_TRAVEL * k;                 // k = +1 the right pedal
                float x = 0.56F + off, z = zc + k * 0.09F;
                quad(x, -1.06F, z - 0.035F, x + 0.03F, -0.95F, z - 0.035F, x + 0.03F, -0.95F, z + 0.035F, x, -1.06F, z + 0.035F, 0x3A3C40);
            }
        }
    }

    /** A square prism (8 sides look round enough at this size) from (x0, y0, z0) to (x1, y1, z1) of radius r. */
    static void prism(float x0, float y0, float z0, float x1, float y1, float z1, float r, int rgb) {
        float[] d = {x1 - x0, y1 - y0, z1 - z0};
        float l = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        float[] u = {d[0] / l, d[1] / l, d[2] / l};
        float[] n1 = LinerGfxSVA.cross(u, Math.abs(u[1]) < 0.9F ? new float[] {0, 1, 0} : new float[] {1, 0, 0});
        float l1 = (float) Math.sqrt(n1[0] * n1[0] + n1[1] * n1[1] + n1[2] * n1[2]);
        n1 = new float[] {n1[0] / l1, n1[1] / l1, n1[2] / l1};
        float[] n2 = LinerGfxSVA.cross(u, n1);
        int seg = 8;
        GL11.glBegin(GL11.GL_QUADS);
        for (int i = 0; i < seg; i++) {
            double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
            float c0 = (float) Math.cos(a0) * r, s0 = (float) Math.sin(a0) * r, c1 = (float) Math.cos(a1) * r, s1 = (float) Math.sin(a1) * r;
            LinerGfxSVA.col(scale(rgb, 0.75F + 0.25F * (float) Math.cos(a0 - 0.8)));
            float[] o0 = {n1[0] * c0 + n2[0] * s0, n1[1] * c0 + n2[1] * s0, n1[2] * c0 + n2[2] * s0};
            float[] o1 = {n1[0] * c1 + n2[0] * s1, n1[1] * c1 + n2[1] * s1, n1[2] * c1 + n2[2] * s1};
            GL11.glVertex3f(x0 + o0[0], y0 + o0[1], z0 + o0[2]);
            GL11.glVertex3f(x0 + o1[0], y0 + o1[1], z0 + o1[2]);
            GL11.glVertex3f(x1 + o1[0], y1 + o1[1], z1 + o1[2]);
            GL11.glVertex3f(x1 + o0[0], y1 + o0[1], z1 + o0[2]);
        }
        GL11.glEnd();
        GL11.glBegin(GL11.GL_POLYGON);
        LinerGfxSVA.col(scale(rgb, 1.1F));
        for (int i = 0; i < seg; i++) {
            double an = 2 * Math.PI * i / seg;
            float c = (float) Math.cos(an) * r, sn = (float) Math.sin(an) * r;
            GL11.glVertex3f(x1 + n1[0] * c + n2[0] * sn, y1 + n1[1] * c + n2[1] * sn, z1 + n1[2] * c + n2[2] * sn);
        }
        GL11.glEnd();
    }

    static void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, int rgb) {
        GL11.glBegin(GL11.GL_QUADS);
        LinerGfxSVA.col(rgb);
        GL11.glVertex3f(x0, y0, z0);
        GL11.glVertex3f(x1, y1, z1);
        GL11.glVertex3f(x2, y2, z2);
        GL11.glVertex3f(x3, y3, z3);
        GL11.glEnd();
    }

    /** A box in a panel frame: x / y in panel units, z0..z1 in model units off the panel (toward the crew). */
    static void box(float x0, float y0, float x1, float y1, float z0, float z1, int rgb) {
        float[][] f = {{x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1}, {x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0},
                {x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1}, {x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1},
                {x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0}};
        float[] sh = {1F, 0.8F, 0.8F, 0.9F, 0.7F};
        GL11.glBegin(GL11.GL_QUADS);
        for (int i = 0; i < f.length; i++) {
            LinerGfxSVA.col(scale(rgb, sh[i]));
            for (int k = 0; k < 4; k++) {
                GL11.glVertex3f(f[i][k * 3], f[i][k * 3 + 1], f[i][k * 3 + 2]);
            }
        }
        GL11.glEnd();
    }
}
