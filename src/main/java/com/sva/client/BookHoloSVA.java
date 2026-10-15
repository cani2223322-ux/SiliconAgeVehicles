package com.sva.client;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sva.ConfigSVA;
import com.sva.airport.IlsPairing;
import com.sva.airport.PlannerMath;
import com.sva.book.BookSchematics;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * Wave C, item 43: «Голограмма в мире» - a build schematic of the handbook shown as a client-side ghost where the player
 * looks (the block face under the crosshair; P pins it there), turned by R, taken away by Backspace. Every cell is checked
 * against the world each frame: the right block there - green, a free cell - cyan (the block to place by hand - bright, with
 * its name), anything else in the way - RED. The status panel says what to place, looking which way (a structure faces the
 * placer), and how many cells are done / blocked; when everything stands the panel says so and the ghost goes. The ILS
 * runway is the planner tablet's hologram (AirportPlannerSVA - the same boxes, colours, critical areas and occupancy scan)
 * for a RWY_LEN runway landing over the looked-at block. Nothing is placed or sent to the server.
 */
@SideOnly(Side.CLIENT)
public final class BookHoloSVA {

    private static String id;
    private static int facing;
    private static boolean pinned, have;
    private static int ax, ay, az;
    private static boolean keyR, keyP, keyB;
    /** Cells ok / free / blocked at the last frame; when all stood (ms). */
    private static int ok, free, blocked;
    private static long doneAt;
    /** The airport's occupancy {LOC crit, G/S crit, LOC sens, G/S sens} (scanned every 40 ticks). */
    private static int[] occ = new int[4];
    private static long scanAt = Long.MIN_VALUE;

    private BookHoloSVA() {
    }

    static void register() {
        MinecraftForge.EVENT_BUS.register(new Render());
    }

    public static boolean active() {
        return id != null;
    }

    /** Starts the hologram of a schematic, facing as if placed from where the player stands now. */
    public static void start(String schem) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!ConfigSVA.bookHologram || BookSchematics.get(schem, 0) == null || mc.thePlayer == null) {
            return;
        }
        id = schem;
        int look = net.minecraft.util.MathHelper.floor_double(mc.thePlayer.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        facing = schem.startsWith("ils_") || "airport".equals(schem) ? look : (look + 2) & 3;
        pinned = false;
        have = false;
        doneAt = 0;
        scanAt = Long.MIN_VALUE;
    }

    public static void stop() {
        id = null;
        have = false;
    }

    /** Client tick: the keys (no screen open) and the anchor under the crosshair. */
    static void tick(Minecraft mc) {
        if (id == null) {
            return;
        }
        boolean r = false, p = false, b = false;
        if (mc.currentScreen == null) {
            r = Keyboard.isKeyDown(Keyboard.KEY_R);
            p = Keyboard.isKeyDown(Keyboard.KEY_P);
            b = Keyboard.isKeyDown(Keyboard.KEY_BACK) || Keyboard.isKeyDown(Keyboard.KEY_DELETE);
        }
        if (r && !keyR) {
            facing = (facing + 1) & 3;
            scanAt = Long.MIN_VALUE;
        }
        if (p && !keyP && have) {
            pinned = !pinned;
        }
        if (b && !keyB) {
            stop();
        }
        keyR = r;
        keyP = p;
        keyB = b;
        if (id == null || pinned) {
            return;
        }
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            ForgeDirection d = ForgeDirection.getOrientation(hit.sideHit);
            ax = hit.blockX + d.offsetX;
            ay = hit.blockY + d.offsetY;
            az = hit.blockZ + d.offsetZ;
            have = true;
        } else {
            have = false;
        }
        if (doneAt > 0 && net.minecraft.client.Minecraft.getSystemTime() - doneAt > 8000) {
            stop();
        }
    }

    /** The cell's state: 0 the right block, 1 free (to build), 2 something else in the way. */
    static int state(World w, BookSchematics.Cell c, int x, int y, int z) {
        Block b = w.getBlock(x, y, z);
        Block want = com.sva.book.BookRecipesSVA.block(c.block);
        if (want != null && b == want) {
            return 0;
        }
        return b.isReplaceable(w, x, y, z) ? 1 : 2;
    }

    public static final class Render {
        @SubscribeEvent
        public void onRenderLast(RenderWorldLastEvent e) {
            Minecraft mc = Minecraft.getMinecraft();
            if (id == null || !have || mc.theWorld == null || mc.thePlayer == null) {
                return;
            }
            BookSchematics.Schem s = BookSchematics.get(id, facing);
            if (s == null) {
                return;
            }
            GL11.glPushMatrix();
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_CURRENT_BIT
                    | GL11.GL_TEXTURE_BIT | GL11.GL_FOG_BIT | GL11.GL_LINE_BIT);
            try {
                GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_FOG);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glDepthMask(false);
                OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
                float pulse = (float) (0.75 + 0.25 * Math.sin(Minecraft.getSystemTime() / 160.0));
                if (s.plan) {
                    airport(mc, s, pulse);
                } else {
                    cells(mc, s, pulse);
                }
            } finally {
                GL11.glDepthMask(true);
                GL11.glPopAttrib();
                GL11.glPopMatrix();
                GL11.glColor4f(1F, 1F, 1F, 1F);
            }
        }
    }

    static void cells(Minecraft mc, BookSchematics.Schem s, float pulse) {
        World w = mc.theWorld;
        int nOk = 0, nFree = 0, nBad = 0;
        List<double[]> labels = new ArrayList<double[]>();
        for (BookSchematics.Cell c : s.cells) {
            int x = ax + c.x, y = ay + c.y, z = az + c.z;
            int st = state(w, c, x, y, z);
            nOk += st == 0 ? 1 : 0;
            nFree += st == 1 ? 1 : 0;
            nBad += st == 2 ? 1 : 0;
            float h = c.role == BookSchematics.PAD ? 0.125F : 1F;
            float[] col = st == 0 ? new float[] {0.3F, 1F, 0.4F, 0.25F} : st == 2 ? new float[] {1F, 0.15F, 0.1F, 0.45F}
                    : c.role == BookSchematics.MASTER ? new float[] {0.35F, 0.9F, 1F, 0.55F} : new float[] {0.35F, 0.85F, 1F, 0.22F};
            box(x + 0.02, y + 0.01, z + 0.02, x + 0.98, y + h, z + 0.98, col[0], col[1], col[2], col[3] * pulse);
            if (c.role == BookSchematics.MASTER && st != 0) {
                labels.add(new double[] {x + 0.5, y + 1.6, z + 0.5, c.sub});
            }
        }
        ok = nOk;
        free = nFree;
        blocked = nBad;
        if (nFree == 0 && nBad == 0 && doneAt == 0) {
            doneAt = Minecraft.getSystemTime();
            mc.thePlayer.playSound("random.levelup", 0.5F, 1.2F);
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        FontRenderer fr = mc.fontRenderer;
        for (double[] l : labels) {
            int sub = (int) l[3];
            String name = itemName(sub >= 0 ? s.subs.get(sub).shape : -1, s);
            AirportPlannerSVA.label(fr, name, l[0], l[1], l[2], 0x5EC8F0, mc.thePlayer);
        }
    }

    /** The name of the item placed at a master (a composite's sub shape, or the schematic's first list entry). */
    static String itemName(int shape, BookSchematics.Schem s) {
        String ref = shape >= 0 ? BookSchematics.adBlock(shape) + ":" + BookSchematics.adItemMeta(shape) : s.list.get(0).item;
        ItemStack st = com.sva.book.BookRecipesSVA.item(ref);
        return st == null ? ref : st.getDisplayName();
    }

    /** The ILS runway: the planner tablet's hologram for a runway landing over the anchor toward the facing. */
    static void airport(Minecraft mc, BookSchematics.Schem s, float pulse) {
        World w = mc.theWorld;
        double course = (facing * 90 + 180) % 360;
        double[] far = BookSchematics.runwayWorld(BookSchematics.RWY_LEN, 0, facing);
        IlsPairing.End a = new IlsPairing.End("a", 0, ax + 0.5, az + 0.5, ay, course, BookSchematics.RWY_WIDTH);
        IlsPairing.End b = new IlsPairing.End("b", 0, ax + far[0], az + far[1], ay, (course + 180) % 360, BookSchematics.RWY_WIDTH);
        IlsPairing.Rwy r = new IlsPairing.Rwy();
        r.a = a;
        r.b = b;
        r.course = course;
        r.len = BookSchematics.RWY_LEN;
        r.width = BookSchematics.RWY_WIDTH;
        r.elev = ay;
        List<PlannerMath.Item> items = PlannerMath.plan(BookSchematics.RWY_LEN, BookSchematics.RWY_WIDTH, BookSchematics.RWY_ANGLE);
        long now = w.getTotalWorldTime();
        if (now - scanAt >= 40 || now < scanAt) {
            scanAt = now;
            occ = AirportPlannerSVA.scan(w, r, items);
        }
        // the strip itself (grey) and its ends
        double[][] c = {r.world(1, 0, -r.width / 2.0), r.world(1, r.len, -r.width / 2.0), r.world(1, r.len, r.width / 2.0), r.world(1, 0, r.width / 2.0)};
        GL11.glColor4f(0.8F, 0.8F, 0.85F, 0.18F * pulse);
        GL11.glBegin(GL11.GL_QUADS);
        for (double[] p : c) {
            GL11.glVertex3d(p[0], ay + 0.02, p[1]);
        }
        GL11.glEnd();
        for (PlannerMath.Item it : items) {
            float[] col = AirportPlannerSVA.colour(it.kind, occ);
            double h = AirportPlannerSVA.height(it.kind);
            double y0 = ay + (it.kind >= PlannerMath.LOC_CRIT ? 0.03 : 0);
            AirportPlannerSVA.prism(r, it, y0, y0 + h, col[0], col[1], col[2], col[3] * pulse);
        }
        blocked = occ[0] + occ[1];
        free = items.size();
        ok = 0;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        FontRenderer fr = mc.fontRenderer;
        for (PlannerMath.Item it : items) {
            if (it.kind == PlannerMath.EDGE || it.kind == PlannerMath.LOC_SENS || it.kind == PlannerMath.GS_SENS) {
                continue;
            }
            double[] wp = r.world(1, it.along(), it.across());
            String t = Lang.tr("sva.apt.plan." + PlannerMath.KEYS[it.kind]) + (it.arg.length() > 0 && it.kind != PlannerMath.GS ? " " + it.arg : "");
            AirportPlannerSVA.label(fr, t, wp[0], ay + AirportPlannerSVA.height(it.kind) + 0.6, wp[1], 0xFFFFFF, mc.thePlayer);
        }
    }

    /** A translucent box with its outline. */
    static void box(double x0, double y0, double z0, double x1, double y1, double z1, float r, float g, float b, float a) {
        GL11.glColor4f(r, g, b, a);
        GL11.glBegin(GL11.GL_QUADS);
        double[][] f = {{x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0}, {x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1},
            {x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0}, {x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0},
            {x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1}, {x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1}};
        for (double[] q : f) {
            for (int i = 0; i < 12; i += 3) {
                GL11.glVertex3d(q[i], q[i + 1], q[i + 2]);
            }
        }
        GL11.glEnd();
        GL11.glColor4f(r, g, b, Math.min(1F, a * 2.4F));
        GL11.glLineWidth(2F);
        GL11.glBegin(GL11.GL_LINES);
        double[][] e = {{x0, y0, z0, x1, y0, z0}, {x1, y0, z0, x1, y0, z1}, {x1, y0, z1, x0, y0, z1}, {x0, y0, z1, x0, y0, z0},
            {x0, y1, z0, x1, y1, z0}, {x1, y1, z0, x1, y1, z1}, {x1, y1, z1, x0, y1, z1}, {x0, y1, z1, x0, y1, z0},
            {x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x1, y0, z1, x1, y1, z1}, {x0, y0, z1, x0, y1, z1}};
        for (double[] l : e) {
            GL11.glVertex3d(l[0], l[1], l[2]);
            GL11.glVertex3d(l[3], l[4], l[5]);
        }
        GL11.glEnd();
        GL11.glLineWidth(1F);
    }

    /** The status panel (left, under the chat's top). */
    static void overlay(Minecraft mc, ScaledResolution sr) {
        if (id == null || mc.currentScreen != null) {
            return;
        }
        FontRenderer f = mc.fontRenderer;
        BookSchematics.Schem s = BookSchematics.get(id, facing);
        if (s == null) {
            return;
        }
        List<String> l = new ArrayList<String>();
        l.add("§b" + Lang.tr("sva.book.holo.title", Lang.tr("sva.book.schem." + id)));
        if (!have) {
            l.add("§7" + Lang.tr("sva.book.holo.look"));
        } else if (s.plan) {
            l.add(Lang.tr("sva.book.holo.runway", Integer.valueOf(BookSchematics.RWY_LEN), Lang.tr("sva.book.dir." + facing)));
            l.add((blocked > 0 ? "§c" : "§a") + Lang.tr(blocked > 0 ? "sva.book.holo.critBlocked" : "sva.book.holo.critClear"));
        } else if (doneAt > 0) {
            l.add("§a" + Lang.tr("sva.book.holo.done"));
        } else {
            String what = s.subs.isEmpty() ? itemName(-1, s) : Lang.tr("sva.book.holo.masters");
            l.add(Lang.tr("sva.book.holo.place", what, Lang.tr("sva.book.dir." + BookSchematics.lookFor(id, facing))));
            l.add(Lang.tr("sva.book.holo.status", Integer.valueOf(ok), Integer.valueOf(s.cells.size()))
                    + (blocked > 0 ? "  §c" + Lang.tr("sva.book.holo.blocked", Integer.valueOf(blocked)) : ""));
        }
        l.add("§8" + Lang.tr(pinned ? "sva.book.holo.keysPinned" : "sva.book.holo.keys"));
        int w = 0;
        for (String t : l) {
            w = Math.max(w, f.getStringWidth(t));
        }
        int x = 4, y = sr.getScaledHeight() / 3;
        Gui.drawRect(x - 2, y - 2, x + w + 4, y + l.size() * 10 + 1, 0xA0101820);
        for (String t : l) {
            f.drawStringWithShadow(t, x, y, 0xFFFFFF);
            y += 10;
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }
}
