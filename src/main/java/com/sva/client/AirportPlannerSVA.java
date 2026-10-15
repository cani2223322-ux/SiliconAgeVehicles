package com.sva.client;

import java.util.List;

import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sva.airport.IlsCat;
import com.sva.airport.IlsPairing;
import com.sva.airport.PlannerMath;
import com.sva.block.AirportDataSVA;
import com.sva.block.TileEntityRunwayEndSVA;
import com.sva.item.ItemAptPlannerSVA;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;

/**
 * Airport wave B1 (plan-next §9, item 39; mock-up veh_airport_impl_6_planner): the planner tablet's hologram - while the
 * player holds «Планшет строителя аэродрома» with two runway ends picked, everything airport.PlannerMath lays out for an
 * ILS runway landing over the first end, as translucent cyan boxes with their names: the localizer array and its
 * shelter, the glide slope mast and its shelter, OM / MM / IM, the RVR sensors, the PAPI row, the weather station and its
 * windsock, the ALS line, the edge lights; the critical areas (amber; RED with a block or a vehicle in them) and the
 * sensitive ones (faint); over the threshold the category expected with it all in place (IlsCat via PlannerMath.expected
 * with the areas' occupants this client sees - scanned every SCAN ticks).
 */
@SideOnly(Side.CLIENT)
public final class AirportPlannerSVA {

    private static final int SCAN = 40;
    private int[] occ = new int[4];
    private long scannedAt = Long.MIN_VALUE;
    private String scannedFor = "";

    @SubscribeEvent
    public void onRenderLast(RenderWorldLastEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer p = mc.thePlayer;
        World w = mc.theWorld;
        if (p == null || w == null) {
            return;
        }
        ItemStack s = p.getHeldItem();
        if (s == null || !(s.getItem() instanceof ItemAptPlannerSVA)) {
            return;
        }
        int[] a = ItemAptPlannerSVA.end(s, "A"), b = ItemAptPlannerSVA.end(s, "B");
        if (a == null || b == null) {
            return;
        }
        IlsPairing.Rwy r = runway(w, a, b);
        double angle = 3;
        List<PlannerMath.Item> items = PlannerMath.plan(r.len, r.width, angle);
        String key = a[0] + ":" + a[2] + ":" + b[0] + ":" + b[2];
        long now = w.getTotalWorldTime();
        if (!key.equals(scannedFor) || now - scannedAt >= SCAN || now < scannedAt) {
            scannedFor = key;
            scannedAt = now;
            occ = scan(w, r, items);
        }
        int cat = PlannerMath.expected(angle, occ[0], occ[1], occ[2], occ[3]);
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_CURRENT_BIT
                | GL11.GL_TEXTURE_BIT | GL11.GL_FOG_BIT);
        GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
        double pulse = 0.75 + 0.25 * Math.sin((now + e.partialTicks) * 0.2);
        for (PlannerMath.Item it : items) {
            float[] c = colour(it.kind, occ);
            double h = height(it.kind);
            double y0 = r.elev + (it.kind >= PlannerMath.LOC_CRIT ? 0.03 : 0);
            prism(r, it, y0, y0 + h, c[0], c[1], c[2], (float) (c[3] * pulse));
        }
        GL11.glDepthMask(true);
        // the labels
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        FontRenderer fr = mc.fontRenderer;
        for (PlannerMath.Item it : items) {
            if (it.kind == PlannerMath.EDGE || it.kind == PlannerMath.LOC_SENS || it.kind == PlannerMath.GS_SENS) {
                continue;
            }
            double[] wp = r.world(1, it.along(), it.across());
            String t = Lang.tr("sva.apt.plan." + PlannerMath.KEYS[it.kind]) + (it.arg.length() > 0 && it.kind != PlannerMath.GS ? " " + it.arg : "");
            label(fr, t, wp[0], r.elev + height(it.kind) + 0.6, wp[1], 0xFFFFFF, p);
        }
        double[] t0 = r.world(1, -2, 0);
        label(fr, Lang.tr("sva.apt.plan.expected", IlsCat.catText(cat), r.nameA.length() > 0 ? r.nameA : "--"), t0[0], r.elev + 3.2, t0[1],
                cat >= 3 ? 0x62E06A : cat == 2 ? 0x5ADCFF : 0xF0B040, p);
        GL11.glPopAttrib();
        GL11.glPopMatrix();
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /** The picked runway (its ends' tiles when loaded; the client's runway copy; else a 5-wide one at the ends' level). */
    static IlsPairing.Rwy runway(World w, int[] a, int[] b) {
        TileEntity ta = w.getTileEntity(a[0], a[1], a[2]), tb = w.getTileEntity(b[0], b[1], b[2]);
        int width = ta instanceof TileEntityRunwayEndSVA ? ((TileEntityRunwayEndSVA) ta).width : 5;
        double elev = ta instanceof TileEntityRunwayEndSVA ? ((TileEntityRunwayEndSVA) ta).elev : a[1] + 1;
        if (elev == 0) {
            elev = a[1] + 1;
        }
        IlsPairing.End ea = new IlsPairing.End("a", 0, a[0] + 0.5, a[2] + 0.5, elev, 0, width);
        IlsPairing.End eb = new IlsPairing.End("b", 0, b[0] + 0.5, b[2] + 0.5, tb instanceof TileEntityRunwayEndSVA ? ((TileEntityRunwayEndSVA) tb).elev : elev, 0,
                width);
        IlsPairing.Rwy r = new IlsPairing.Rwy();
        r.a = ea;
        r.b = eb;
        r.course = IlsPairing.bearing(ea.x, ea.z, eb.x, eb.z);
        r.len = Math.max(1, Math.sqrt((eb.x - ea.x) * (eb.x - ea.x) + (eb.z - ea.z) * (eb.z - ea.z)));
        r.width = width;
        r.elev = elev;
        Object[] known = AirportClientSVA.runwayOfEnd(AirportDataSVA.key(w, a[0], a[1], a[2]));
        if (known != null) {
            IlsPairing.Rwy k = ((AirportClientSVA.Rwy) known[0]).r;
            r.nameA = ((Integer) known[1]).intValue() > 0 ? k.nameA : k.nameB;
        }
        return r;
    }

    /** The occupants of the four areas {LOC crit, G/S crit, LOC sens, G/S sens} this client sees. */
    static int[] scan(World w, IlsPairing.Rwy r, List<PlannerMath.Item> items) {
        int[] n = new int[4];
        int y0 = (int) Math.round(r.elev);
        for (PlannerMath.Item it : items) {
            int i = it.kind == PlannerMath.LOC_CRIT ? 0 : it.kind == PlannerMath.GS_CRIT ? 1 : it.kind == PlannerMath.LOC_SENS ? 2 : it.kind == PlannerMath.GS_SENS ? 3 : -1;
            if (i < 0) {
                continue;
            }
            for (double al = it.a0; al <= it.a1; al += 1) {
                for (double cr = it.c0; cr <= it.c1; cr += 1) {
                    double[] wp = r.world(1, al, cr);
                    int x = (int) Math.floor(wp[0]), z = (int) Math.floor(wp[1]);
                    if (!w.blockExists(x, y0, z)) {
                        continue;
                    }
                    for (int y = y0; y < y0 + IlsCat.CRIT_HEIGHT; y++) {
                        if (AirportDataSVA.obstacle(w, x, y, z)) {
                            n[i]++;
                        }
                    }
                }
            }
            double[] p0 = r.world(1, it.a0, it.c0), p1 = r.world(1, it.a1, it.c1);
            AxisAlignedBB bb = AxisAlignedBB.getBoundingBox(Math.min(p0[0], p1[0]), y0 - 1, Math.min(p0[1], p1[1]), Math.max(p0[0], p1[0]), y0 + 8,
                    Math.max(p0[1], p1[1]));
            for (Object o : w.getEntitiesWithinAABB(Entity.class, bb)) {
                Entity en = (Entity) o;
                double[] lc = r.local(1, en.posX, en.posZ);
                if (lc[0] < it.a0 || lc[0] > it.a1 || lc[1] < it.c0 || lc[1] > it.c1 || en == Minecraft.getMinecraft().thePlayer) {
                    continue;
                }
                n[i] += en instanceof com.sva.entity.EntityVehicleSVA ? IlsCat.OCC_VEHICLE : en instanceof EntityLivingBase ? 1 : 0;
            }
        }
        return n;
    }

    static double height(int kind) {
        switch (kind) {
            case PlannerMath.LOC:
            case PlannerMath.LOC_SHELTER:
            case PlannerMath.GS_SHELTER:
                return 1;
            case PlannerMath.GS:
                return 3;
            case PlannerMath.MARKER:
                return 0.5;
            case PlannerMath.RVR:
                return 1.6;
            case PlannerMath.PAPI:
                return 0.6;
            case PlannerMath.WEATHER:
            case PlannerMath.WINDSOCK:
                return 2.3;
            case PlannerMath.ALS:
            case PlannerMath.EDGE:
                return 0.3;
            default:
                return 0.05;
        }
    }

    /** RGBA: things cyan; critical areas amber (red occupied); sensitive faint yellow (orange occupied). */
    static float[] colour(int kind, int[] occ) {
        switch (kind) {
            case PlannerMath.LOC_CRIT:
                return occ[0] > 0 ? new float[] {1F, 0.15F, 0.1F, 0.4F} : new float[] {1F, 0.7F, 0.15F, 0.22F};
            case PlannerMath.GS_CRIT:
                return occ[1] > 0 ? new float[] {1F, 0.15F, 0.1F, 0.4F} : new float[] {1F, 0.7F, 0.15F, 0.22F};
            case PlannerMath.LOC_SENS:
                return occ[2] > 0 ? new float[] {1F, 0.45F, 0.1F, 0.18F} : new float[] {1F, 0.95F, 0.4F, 0.08F};
            case PlannerMath.GS_SENS:
                return occ[3] > 0 ? new float[] {1F, 0.45F, 0.1F, 0.18F} : new float[] {1F, 0.95F, 0.4F, 0.08F};
            case PlannerMath.ALS:
            case PlannerMath.EDGE:
                return new float[] {1F, 1F, 0.8F, 0.45F};
            default:
                return new float[] {0.35F, 0.85F, 1F, 0.35F};
        }
    }

    /** A box of the runway's frame (its corners turned into the world) from y0 to y1: faces and the outline. */
    static void prism(IlsPairing.Rwy r, PlannerMath.Item it, double y0, double y1, float cr, float cg, float cb, float a) {
        double[][] c = {r.world(1, it.a0, it.c0), r.world(1, it.a1, it.c0), r.world(1, it.a1, it.c1), r.world(1, it.a0, it.c1)};
        GL11.glColor4f(cr, cg, cb, a);
        GL11.glBegin(GL11.GL_QUADS);
        for (int i = 0; i < 4; i++) {
            double[] p = c[i], q = c[(i + 1) % 4];
            GL11.glVertex3d(p[0], y0, p[1]);
            GL11.glVertex3d(q[0], y0, q[1]);
            GL11.glVertex3d(q[0], y1, q[1]);
            GL11.glVertex3d(p[0], y1, p[1]);
        }
        for (double y : new double[] {y0, y1}) {
            for (int i = 0; i < 4; i++) {
                GL11.glVertex3d(c[i][0], y, c[i][1]);
            }
        }
        GL11.glEnd();
        GL11.glColor4f(cr, cg, cb, Math.min(1F, a * 2.2F));
        GL11.glLineWidth(2F);
        for (double y : new double[] {y0, y1}) {
            GL11.glBegin(GL11.GL_LINE_LOOP);
            for (int i = 0; i < 4; i++) {
                GL11.glVertex3d(c[i][0], y, c[i][1]);
            }
            GL11.glEnd();
        }
        GL11.glLineWidth(1F);
    }

    /** A billboard label (the name plates' way) at a world point. */
    static void label(FontRenderer fr, String s, double x, double y, double z, int rgb, EntityPlayer p) {
        double dx = x - RenderManager.renderPosX, dy = y - RenderManager.renderPosY, dz = z - RenderManager.renderPosZ;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d > 160) {
            return;
        }
        float k = (float) (0.016 * Math.max(1, d / 12));
        GL11.glPushMatrix();
        GL11.glTranslated(x, y, z);
        GL11.glRotatef(-RenderManager.instance.playerViewY, 0F, 1F, 0F);
        GL11.glRotatef(RenderManager.instance.playerViewX, 1F, 0F, 0F);
        GL11.glScalef(-k, -k, k);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        int w = fr.getStringWidth(s);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(0F, 0F, 0F, 0.45F);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3f(-w / 2F - 2, -2, 0);
        GL11.glVertex3f(-w / 2F - 2, 9, 0);
        GL11.glVertex3f(w / 2F + 2, 9, 0);
        GL11.glVertex3f(w / 2F + 2, -2, 0);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        fr.drawString(s, -w / 2, 0, 0xFF000000 | rgb);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glPopMatrix();
    }
}
