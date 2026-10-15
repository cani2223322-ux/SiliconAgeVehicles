package com.sva.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sva.airdefense.RadarMath;
import com.sva.airdefense.XpdrMath;
import com.sva.airdefense.ZoneMath;
import com.sva.entity.EntityVehicleSVA;
import com.sva.net.NetADSVA;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

/**
 * Air-defence wave, client: what the server sent (NetADSVA.Data - queued off the network thread, applied in the client
 * tick) - the riders' transponder and RWR copies (the cockpit displays read them through CockpitDataSVA), the zones
 * pilots see (the map, the HSI, the HUD), the messages to the pilot, the command post's / launcher's pictures for their
 * open screens. Draws the pilot's HUD lines: the air defence's / command post's message (5 s, big), the zone warning
 * («Через 20 блоков запретная зона 3-1», inside - red) with its tones at 20 and 10 blocks, and the RWR's line and tones
 * when no cockpit scope is in view (third person).
 */
@SideOnly(Side.CLIENT)
public final class AirDefenseClientSVA {

    private static final ConcurrentLinkedQueue<Object[]> IN = new ConcurrentLinkedQueue<Object[]>();

    private static final class Xpdr {
        int mode = XpdrMath.C, code = XpdrMath.VFR, identLeft;
        boolean dayKey = true, keyLamp, reply;
        long at;
    }

    private static final Map<Integer, Xpdr> XPDR = new HashMap<Integer, Xpdr>();
    private static final Map<Integer, Object[]> RWR = new HashMap<Integer, Object[]>();
    private static final List<ZoneMath.Zone> ZONES = new ArrayList<ZoneMath.Zone>();
    private static String msg;
    private static int msgColour;
    private static long msgUntil;
    /** The zone warning: its text, colour, level; the level last sounded; the RWR level last sounded. */
    private static String zoneLine;
    private static int zoneColour, zoneLevel, zoneSounded, rwrSounded;
    private static long ticks;

    public AirDefenseClientSVA() {
    }

    /** Network thread: queue it. */
    public static void receive(int kind, NBTTagCompound t) {
        if (IN.size() < 64) {
            IN.add(new Object[] {Integer.valueOf(kind), t});
        }
    }

    // ------------------------------------------------------------------ the client tick

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        ticks++;
        Object[] o;
        while ((o = IN.poll()) != null) {
            try {
                apply(mc, ((Integer) o[0]).intValue(), (NBTTagCompound) o[1]);
            } catch (Throwable t) {
                System.out.println("[SVA-AD] client: " + t);
            }
        }
        if (mc.theWorld == null) {
            XPDR.clear();
            RWR.clear();
            return;
        }
        zoneWarning(mc);
        predict(mc);
        rwrTones(mc);
    }

    // ------------------------------------------------------------------ wave A: the zone entry predicted (HUD / ND / HSI / ECAM)

    /** The zone the track enters next within airdefense.zoneWarnSeconds (null none), the seconds, ZoneMath.predictLevel. */
    public static ZoneMath.Zone predZone;
    public static double predSec = -1;
    public static int predLevel;
    private static int predSounded;
    /** The track (blocks a tick, smoothed over a few ticks) from the positions the client sees. */
    private static double svx, svz, lastX = Double.NaN, lastZ;
    private static int lastId = -1;

    /** The track's ground velocity now (blocks a tick) - the ND's / HUD's. */
    public static double[] track() {
        return new double[] {svx, svz};
    }

    private static void predict(Minecraft mc) {
        EntityVehicleSVA v = mc.thePlayer == null ? null : HudVehicleSVA.vehicleOf(mc.thePlayer);
        predZone = null;
        predSec = -1;
        predLevel = 0;
        if (v == null || !v.getVehicleType().movement.flies || !com.sva.ConfigSVA.zoneFrames) {
            predSounded = 0;
            lastX = Double.NaN;
            LinerEwdSVA.zoneMemo = null;
            return;
        }
        if (v.getEntityId() != lastId || Double.isNaN(lastX)) {
            svx = svz = 0;
        } else {
            svx = svx * 0.6 + (v.posX - lastX) * 0.4;
            svz = svz * 0.6 + (v.posZ - lastZ) * 0.4;
        }
        lastId = v.getEntityId();
        lastX = v.posX;
        lastZ = v.posZ;
        double warn = com.sva.ConfigSVA.adZoneWarnSeconds;
        List<ZoneMath.Zone> zs = zones(v.dimension);
        double course = RadarMath.compass(svx, svz);
        for (ZoneMath.Zone c : zs) {
            if (ZoneMath.corridorPass(c, v.posX, v.posY, v.posZ, course, true)) {
                LinerEwdSVA.zoneMemo = null;
                predSounded = 0;
                return;                                             // in a corridor on its course: quiet
            }
        }
        double bs = Double.MAX_VALUE;
        for (ZoneMath.Zone z : zs) {
            if (z.type != ZoneMath.FORBIDDEN && z.type != ZoneMath.RESTRICTED) {
                continue;
            }
            double s = ZoneMath.secondsToEntry(z, v.posX, v.posY, v.posZ, svx, svz, true, warn);
            if (s >= 0 && (s < bs || s == bs && z.type == ZoneMath.FORBIDDEN)) {
                bs = s;
                predZone = z;
            }
        }
        if (predZone != null) {
            predSec = bs;
            predLevel = ZoneMath.predictLevel(bs, warn);
        }
        if (predLevel >= 1 && predLevel <= 2 && predLevel > predSounded) {
            for (int i = 0; i < predLevel; i++) {
                mc.getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("note.pling"), predLevel == 2 ? 1.6F : 1.25F));
            }
        }
        predSounded = predLevel == 0 ? 0 : Math.max(predSounded, predLevel);
        // the liner's E/WD memo: amber before, red inside
        if (v.getVehicleType() == com.sva.vehicle.VehicleType.LINER && predZone != null && predLevel > 0) {
            LinerEwdSVA.zoneMemo = predLevel == 3 ? Lang.tr("sva.ad.ecam.inside", predZone.name) : Lang.tr("sva.ad.ecam.ahead", predZone.name,
                    Integer.valueOf((int) Math.ceil(predSec)));
            LinerEwdSVA.zoneMemoRed = predLevel >= 2 && predZone.type == ZoneMath.FORBIDDEN;
        } else {
            LinerEwdSVA.zoneMemo = null;
        }
    }

    /** The prediction's HUD / ND line («ВХОД В ЗОНУ 3-1 ЧЕРЕЗ 12 С»; inside: «В ЗОНЕ 3-1»), null none. */
    public static String predLine() {
        if (predZone == null || predLevel == 0) {
            return null;
        }
        return predLevel == 3 ? Lang.tr("sva.ad.hud.inZone", predZone.name) : Lang.tr("sva.ad.hud.entry", predZone.name, Integer.valueOf((int) Math.ceil(predSec)));
    }

    /** The prediction's colour: red for a forbidden zone at level 2+, else amber. */
    public static int predColour() {
        return predZone != null && predZone.type == ZoneMath.FORBIDDEN && predLevel >= 2 ? 0xFF5050 : 0xF0B040;
    }

    /**
     * The nearest forbidden / restricted zone within `max` blocks of a vehicle (its outline): {zone, distance, bearing to its
     * nearest point relative to the nose (deg clockwise)} or null - the HUDs' zone indicator.
     */
    public static Object[] nearby(EntityVehicleSVA v, double max) {
        if (v == null) {
            return null;
        }
        ZoneMath.Zone best = null;
        double bd = max;
        for (ZoneMath.Zone z : zones(v.dimension)) {
            if ((z.type == ZoneMath.FORBIDDEN || z.type == ZoneMath.RESTRICTED) && ZoneMath.altIn(z, v.posY, true)) {
                double d = ZoneMath.distance2D(z, v.posX, v.posZ);
                if (d <= bd) {
                    bd = d;
                    best = z;
                }
            }
        }
        if (best == null) {
            return null;
        }
        double[] p = ZoneMath.nearestPoint(best, v.posX, v.posZ);
        double rel = bd <= 0 ? 0 : RadarMath.relBearing(v.posX, v.posZ, v.rotationYaw, p[0], p[1]);
        return new Object[] {best, Double.valueOf(bd), Double.valueOf(rel)};
    }

    private static void apply(Minecraft mc, int kind, NBTTagCompound t) {
        switch (kind) {
            case NetADSVA.D_XPDR: {
                Xpdr x = new Xpdr();
                x.mode = t.getInteger("M");
                x.code = t.getInteger("C");
                x.dayKey = t.getBoolean("D");
                x.identLeft = t.getInteger("I");
                x.keyLamp = t.getBoolean("K");
                x.reply = t.getBoolean("R");
                x.at = ticks;
                XPDR.put(Integer.valueOf(t.getInteger("E")), x);
                break;
            }
            case NetADSVA.D_RWR: {
                List<double[]> l = new ArrayList<double[]>();
                NBTTagList tl = t.getTagList("T", 10);
                for (int i = 0; i < tl.tagCount(); i++) {
                    NBTTagCompound c = tl.getCompoundTagAt(i);
                    l.add(new double[] {c.getByte("K"), c.getDouble("X"), c.getDouble("Z")});
                }
                RWR.put(Integer.valueOf(t.getInteger("E")), new Object[] {Boolean.valueOf(t.getBoolean("On")), l});
                break;
            }
            case NetADSVA.D_ZONES: {
                ZONES.clear();
                NBTTagList zl = t.getTagList("Z", 10);
                for (int i = 0; i < zl.tagCount(); i++) {
                    ZoneMath.Zone z = new ZoneMath.Zone();
                    z.read(zl.getCompoundTagAt(i));
                    ZONES.add(z);
                }
                break;
            }
            case NetADSVA.D_MSG: {
                NBTTagList a = t.getTagList("A", 8);
                Object[] args = new Object[a.tagCount()];
                for (int i = 0; i < args.length; i++) {
                    String s = a.getStringTagAt(i);
                    args[i] = s.startsWith("#") && s.length() > 1 ? Lang.tr(s.substring(1)) : s;   // airport wave B2: a key argument
                }
                msg = Lang.tr(t.getString("K"), args);
                msgColour = t.getInteger("C");
                msgUntil = System.currentTimeMillis() + 5000;
                if (mc.thePlayer != null) {
                    mc.getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("note.pling"), 1.6F));
                }
                break;
            }
            case NetADSVA.D_KP:
                GuiCommandPostSVA.receive(mc, t);
                break;
            case NetADSVA.D_LAUNCHER:
                GuiLauncherSVA.receive(t);
                break;
            case NetADSVA.D_EQUIP:
                GuiAdEquipSVA.receive(t);
                break;
            case NetADSVA.D_COVER:
                CoverClientSVA.receive(t);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ the cockpits' readings

    private static Xpdr x(EntityVehicleSVA v) {
        return v == null ? null : XPDR.get(Integer.valueOf(v.getEntityId()));
    }

    /** The transponder's code (7000 until the server has told). */
    public static int xpdrCode(EntityVehicleSVA v) {
        Xpdr x = x(v);
        return x == null ? XpdrMath.VFR : x.code;
    }

    public static int xpdrMode(EntityVehicleSVA v) {
        Xpdr x = x(v);
        return x == null ? XpdrMath.C : x.mode;
    }

    public static boolean xpdrDayKey(EntityVehicleSVA v) {
        Xpdr x = x(v);
        return x == null || x.dayKey;
    }

    /** The ОТВЕТ lamp: interrogated within the last second. */
    public static boolean xpdrReply(EntityVehicleSVA v) {
        Xpdr x = x(v);
        return x != null && x.reply && ticks - x.at < XpdrMath.REPLY_TICKS;
    }

    /** IDENT still flashing. */
    public static boolean xpdrIdent(EntityVehicleSVA v) {
        Xpdr x = x(v);
        return x != null && x.identLeft > 0 && ticks - x.at < x.identLeft;
    }

    /** The КЛЮЧ ДНЯ lamp. */
    public static boolean xpdrKeyLamp(EntityVehicleSVA v) {
        Xpdr x = x(v);
        return x != null && x.keyLamp;
    }

    /** The RWR module is fitted (and the server's tuning on). */
    public static boolean rwrFitted(EntityVehicleSVA v) {
        Object[] r = v == null ? null : RWR.get(Integer.valueOf(v.getEntityId()));
        return r != null && ((Boolean) r[0]).booleanValue();
    }

    /** The threats painting the vehicle: {bearing deg from the nose clockwise, distance blocks, RadarMath.T_* kind}. */
    public static float[][] rwrThreats(EntityVehicleSVA v) {
        Object[] r = v == null ? null : RWR.get(Integer.valueOf(v.getEntityId()));
        if (r == null || !((Boolean) r[0]).booleanValue()) {
            return new float[0][];
        }
        @SuppressWarnings("unchecked")
        List<double[]> l = (List<double[]>) r[1];
        float[][] out = new float[l.size()][];
        for (int i = 0; i < out.length; i++) {
            double[] t = l.get(i);
            double d = Math.sqrt((t[1] - v.posX) * (t[1] - v.posX) + (t[2] - v.posZ) * (t[2] - v.posZ));
            out[i] = new float[] {(float) RadarMath.relBearing(v.posX, v.posZ, v.rotationYaw, t[1], t[2]), (float) d, (float) t[0]};
        }
        return out;
    }

    /** The strongest threat level now (RadarMath.rwrLevel). */
    public static int rwrLevel(EntityVehicleSVA v) {
        float[][] th = rwrThreats(v);
        int[] k = new int[th.length];
        for (int i = 0; i < k.length; i++) {
            k[i] = (int) th[i][2];
        }
        return RadarMath.rwrLevel(k);
    }

    // ------------------------------------------------------------------ zones

    /** The zones pilots see in this dimension. */
    public static List<ZoneMath.Zone> zones(int dim) {
        List<ZoneMath.Zone> out = new ArrayList<ZoneMath.Zone>();
        for (ZoneMath.Zone z : ZONES) {
            if (z.dim == dim) {
                out.add(z);
            }
        }
        return out;
    }

    /** The nearest forbidden / restricted zone border ahead of a vehicle within WARN_FAR: {zone, distance} or null. */
    public static Object[] zoneAhead(EntityVehicleSVA v, double max) {
        if (v == null || v.worldObj == null) {
            return null;
        }
        List<ZoneMath.Zone> zs = zones(v.dimension);
        double course = RadarMath.headingOfYaw(v.rotationYaw);
        for (ZoneMath.Zone c : zs) {
            if (ZoneMath.corridorPass(c, v.posX, v.posY, v.posZ, course, true)) {
                return null;                                        // in a corridor at its height and on its course: quiet
            }
        }
        ZoneMath.Zone best = null;
        double bd = Double.MAX_VALUE;
        for (ZoneMath.Zone z : zs) {
            if (z.type != ZoneMath.FORBIDDEN && z.type != ZoneMath.RESTRICTED || !ZoneMath.altIn(z, v.posY, true)) {
                continue;
            }
            double d = ZoneMath.ahead(z, v.posX, v.posZ, course, max);
            if (d < 0) {
                double side = ZoneMath.distance2D(z, v.posX, v.posZ);
                d = side <= ZoneMath.WARN_CLOSE ? side : -1;          // skimming along the border counts as close
            }
            if (d >= 0 && (d < bd || d == bd && z.type == ZoneMath.FORBIDDEN)) {
                bd = d;
                best = z;
            }
        }
        return best == null ? null : new Object[] {best, Double.valueOf(bd)};
    }

    private static void zoneWarning(Minecraft mc) {
        EntityVehicleSVA v = mc.thePlayer == null ? null : HudVehicleSVA.vehicleOf(mc.thePlayer);
        zoneLine = null;
        if (v == null || !v.getVehicleType().movement.flies) {
            zoneSounded = 0;
            return;
        }
        Object[] a = zoneAhead(v, ZoneMath.WARN_FAR);
        int level = a == null ? ZoneMath.W_NONE : ZoneMath.warnLevel(((Double) a[1]).doubleValue());
        zoneLevel = level;
        if (a == null || level == ZoneMath.W_NONE) {
            zoneSounded = 0;
            return;
        }
        ZoneMath.Zone z = (ZoneMath.Zone) a[0];
        String type = Lang.tr("sva.ad.zone.type." + ZoneMath.TYPE_KEYS[z.type]);
        int dist = (int) Math.round(((Double) a[1]).doubleValue());
        zoneColour = z.type == ZoneMath.FORBIDDEN ? 0xFF5050 : 0xF0B040;
        zoneLine = level == ZoneMath.W_INSIDE ? Lang.tr("sva.ad.hud.inside", type, z.label())
                : level == ZoneMath.W_FAR ? null : Lang.tr("sva.ad.hud.ahead", Integer.valueOf(dist), type, z.label());
        if (level >= ZoneMath.W_NEAR && level <= ZoneMath.W_CLOSE && level > zoneSounded) {
            for (int i = 0; i < 2; i++) {
                mc.getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("note.pling"), level == ZoneMath.W_CLOSE ? 1.4F : 1.1F));
            }
        }
        zoneSounded = Math.max(zoneSounded, level);
    }

    /** The RWR's tones: a soft beep when a search radar paints, a tone every half second while locked, a siren on a missile. */
    private static void rwrTones(Minecraft mc) {
        EntityVehicleSVA v = mc.thePlayer == null ? null : HudVehicleSVA.vehicleOf(mc.thePlayer);
        int level = v == null ? 0 : rwrLevel(v);
        if (level == 3 && ticks % 5 == 0) {
            mc.getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("note.bassattack"), ticks % 10 == 0 ? 1.6F : 1.2F));
        } else if (level == 2 && ticks % 10 == 0) {
            mc.getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("note.pling"), 1.9F));
        } else if (level == 1 && rwrSounded < 1) {
            mc.getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("note.hat"), 1.5F));
        }
        rwrSounded = level;
    }

    /** The HSI (AvionicsSVA): the zone border ahead within 60 blocks - {zone label, distance, type} or null. */
    public static Object[] hsiZone(EntityVehicleSVA v) {
        Object[] a = zoneAhead(v, 60);
        return a == null ? null : new Object[] {((ZoneMath.Zone) a[0]).name, a[1], Integer.valueOf(((ZoneMath.Zone) a[0]).type)};
    }

    // ------------------------------------------------------------------ the HUD

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post e) {
        if (e.type != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.gameSettings.hideGUI || mc.thePlayer == null) {
            return;
        }
        FontRenderer fr = mc.fontRenderer;
        int sw = e.resolution.getScaledWidth();
        int y = 26;
        if (msg != null && System.currentTimeMillis() < msgUntil) {
            List<String> lines = fr.listFormattedStringToWidth(msg, Math.min(300, sw - 20));
            int w = 0;
            for (String s : lines) {
                w = Math.max(w, fr.getStringWidth(s));
            }
            int h = lines.size() * 10 + 6;
            Gui.drawRect(sw / 2 - w / 2 - 6, y - 3, sw / 2 + w / 2 + 6, y + h - 3, 0xC0101418);
            Gui.drawRect(sw / 2 - w / 2 - 6, y - 3, sw / 2 + w / 2 + 6, y - 2, 0xFF000000 | msgColour);
            for (int i = 0; i < lines.size(); i++) {
                fr.drawStringWithShadow(lines.get(i), sw / 2 - fr.getStringWidth(lines.get(i)) / 2, y + i * 10, msgColour);
            }
            y += h + 4;
        }
        EntityVehicleSVA v = HudVehicleSVA.vehicleOf(mc.thePlayer);
        if (v == null || mc.gameSettings.showDebugInfo) {
            return;
        }
        if (zoneLine != null) {
            boolean blink = zoneLevel < ZoneMath.W_CLOSE || ticks / 5 % 2 == 0;
            int w = fr.getStringWidth(zoneLine);
            Gui.drawRect(sw / 2 - w / 2 - 4, y - 2, sw / 2 + w / 2 + 4, y + 10, 0xA0101418);
            fr.drawStringWithShadow(zoneLine, sw / 2 - w / 2, y, blink ? zoneColour : 0x803030);
            y += 14;
        }
        if (com.sva.ConfigSVA.zoneFrames && v.getVehicleType().movement.flies) {
            y = zoneFrame(fr, v, sw, y);
        }
        // the RWR in words when the cockpit's scope is out of sight (third person) or the vehicle has none
        if (rwrFitted(v) && (mc.gameSettings.thirdPersonView != 0 || !com.sva.vehicle.GlassMath.hasMfd(v.getVehicleType()))) {
            float[][] th = rwrThreats(v);
            int level = rwrLevel(v);
            if (level > 0) {
                float[] worst = null;
                for (float[] t : th) {
                    int l = t[2] == RadarMath.T_MISSILE ? 3 : t[2] == RadarMath.T_LOCK ? 2 : 1;
                    if (worst == null || l > (worst[2] == RadarMath.T_MISSILE ? 3 : worst[2] == RadarMath.T_LOCK ? 2 : 1)) {
                        worst = t;
                    }
                }
                int clock = Math.round(worst[0] / 30F) % 12;
                String s = Lang.tr("sva.ad.hud.rwr." + (level == 3 ? "missile" : level == 2 ? "lock" : "search"), Integer.valueOf(clock == 0 ? 12 : clock));
                int c = level == 3 ? 0xFF4040 : level == 2 ? 0xF07030 : 0xF0C040;
                int w = fr.getStringWidth(s);
                Gui.drawRect(sw / 2 - w / 2 - 4, y - 2, sw / 2 + w / 2 + 4, y + 10, 0xA0101418);
                fr.drawStringWithShadow(s, sw / 2 - w / 2, y, level == 3 && ticks / 4 % 2 == 0 ? 0xFFFFFF : c);
            }
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /**
     * Wave A (plan-next §9 A2, mock-up veh_ad_impl_a_2_hud_nd): the zone frame on the pilot's HUD (every aircraft; the liner's
     * HUD is this one) - a box in the zone type's colour round the nearest zone within WARN_FAR x 3: its name and type, the
     * blocks to it, a pointer to its nearest point (turned by the bearing from the nose), and the entry prediction («ВХОД В
     * ЗОНУ 3-1 ЧЕРЕЗ 12 С», blinking red at a third of the warning time). @return the next line's y
     */
    private static int zoneFrame(FontRenderer fr, EntityVehicleSVA v, int sw, int y) {
        Object[] nb = nearby(v, ZoneMath.WARN_FAR * 3);
        String pl = predLine();
        if (nb == null && pl == null) {
            return y;
        }
        String l1 = null;
        int col = 0xF0B040;
        double rel = 0;
        if (nb != null) {
            ZoneMath.Zone z = (ZoneMath.Zone) nb[0];
            int d = (int) Math.round(((Double) nb[1]).doubleValue());
            rel = ((Double) nb[2]).doubleValue();
            col = ZoneMath.COLOURS[z.type];
            l1 = d <= 0 ? Lang.tr("sva.ad.hud.frameIn", Lang.tr("sva.ad.zone.type." + ZoneMath.TYPE_KEYS[z.type]), z.label())
                    : Lang.tr("sva.ad.hud.frame", Lang.tr("sva.ad.zone.type." + ZoneMath.TYPE_KEYS[z.type]), z.label(), Integer.valueOf(d));
        }
        int w = Math.max(l1 == null ? 0 : fr.getStringWidth(l1) + 16, pl == null ? 0 : fr.getStringWidth(pl));
        int h = (l1 == null ? 0 : 11) + (pl == null ? 0 : 11) + 4;
        int x0 = sw / 2 - w / 2 - 5, x1 = sw / 2 + w / 2 + 5, y0 = y - 3, y1 = y - 3 + h;
        boolean blink = predLevel >= 2 && ticks / 5 % 2 == 0;
        int frame = 0xFF000000 | (pl != null ? predColour() : col);
        Gui.drawRect(x0, y0, x1, y1, 0x90101418);
        Gui.drawRect(x0, y0, x1, y0 + 1, frame);
        Gui.drawRect(x0, y1 - 1, x1, y1, frame);
        Gui.drawRect(x0, y0, x0 + 1, y1, frame);
        Gui.drawRect(x1 - 1, y0, x1, y1, frame);
        int ty = y;
        if (l1 != null) {
            fr.drawStringWithShadow(l1, x0 + 16, ty, col);
            pointer(x0 + 8, ty + 4, rel, col);
            ty += 11;
        }
        if (pl != null) {
            fr.drawStringWithShadow(pl, sw / 2 - fr.getStringWidth(pl) / 2, ty, blink ? 0xFFFFFF : predColour());
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
        return y1 + 5;
    }

    /** A small triangle at (x, y) pointing `rel` degrees clockwise from up (the nose). */
    static void pointer(double x, double y, double rel, int rgb) {
        double r = 4.5;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F, 1F);
        Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_TRIANGLES);
        for (double d : new double[] {0, -140, 140}) {
            double a = Math.toRadians(rel + d), k = d == 0 ? r : r * 0.8;
            t.addVertex(x + Math.sin(a) * k, y - Math.cos(a) * k, 0);
        }
        t.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    // ------------------------------------------------------------------ the map (GuiMapSVA, GuiCommandPostSVA)

    /**
     * Draws zones on a map panel: (ox, oy) the panel's centre in GUI pixels, (cx, cz) the world point there, `scale` blocks
     * a pixel. Outlines in the type's colour (dashed for corridors), a light hatch inside, the label at the top.
     */
    public static void drawZones(FontRenderer fr, List<ZoneMath.Zone> zs, double ox, double oy, double cx, double cz, double scale, int highlight) {
        for (int i = 0; i < zs.size(); i++) {
            ZoneMath.Zone z = zs.get(i);
            int col = ZoneMath.COLOURS[z.type];
            double w = i == highlight ? 2.2 : 1.2;
            if (z.shape == ZoneMath.RECT) {
                // wave A: a turned rectangle - its four corners (ZoneMath.corners), the quad filled between them
                double[][] c = ZoneMath.corners(z);
                double[] px = new double[4], py = new double[4];
                for (int k = 0; k < 4; k++) {
                    px[k] = ox + (c[k][0] - cx) / scale;
                    py[k] = oy + (c[k][1] - cz) / scale;
                }
                quad(px, py, col, 0.16F);
                for (int k = 0; k < 4; k++) {
                    edge(px[k], py[k], px[(k + 1) % 4], py[(k + 1) % 4], w, col, z.type == ZoneMath.CORRIDOR);
                }
                fr.drawStringWithShadow(z.label(), (int) Math.min(Math.min(px[0], px[1]), Math.min(px[2], px[3])) + 2,
                        (int) Math.min(Math.min(py[0], py[1]), Math.min(py[2], py[3])) + 2, col);
            } else {
                double px = ox + (z.cx + 0.5 - cx) / scale, py = oy + (z.cz + 0.5 - cz) / scale, r = z.r / scale;
                disc(px, py, r, col, 0.16F);
                int n = Math.max(24, Math.min(96, (int) (r * 0.8)));
                for (int k = 0; k < n; k++) {
                    double a0 = k * Math.PI * 2 / n, a1 = (k + 1) * Math.PI * 2 / n;
                    if (z.type != ZoneMath.CORRIDOR || k % 2 == 0) {
                        edge(px + Math.cos(a0) * r, py + Math.sin(a0) * r, px + Math.cos(a1) * r, py + Math.sin(a1) * r, w, col, false);
                    }
                }
                String l = z.label();
                fr.drawStringWithShadow(l, (int) (px - fr.getStringWidth(l) / 2.0), (int) (py - r) + 2, col);
            }
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /** A filled quad of four points (in order). */
    static void quad(double[] px, double[] py, int rgb, float a) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F, a);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        for (int k = 3; k >= 0; k--) {
            t.addVertex(px[k], py[k], 0);
        }
        t.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    static void fill(double x0, double y0, double x1, double y1, int rgb, float a) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F, a);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.addVertex(x0, y1, 0);
        t.addVertex(x1, y1, 0);
        t.addVertex(x1, y0, 0);
        t.addVertex(x0, y0, 0);
        t.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private static void disc(double x, double y, double r, int rgb, float a) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F, a);
        Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_TRIANGLE_FAN);
        t.addVertex(x, y, 0);
        int n = 48;
        for (int k = n; k >= 0; k--) {
            double ang = k * Math.PI * 2 / n;
            t.addVertex(x + Math.cos(ang) * r, y + Math.sin(ang) * r, 0);
        }
        t.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** A line `w` px wide (dashed: 4 px dashes). */
    static void edge(double x0, double y0, double x1, double y1, double w, int rgb, boolean dashed) {
        double dx = x1 - x0, dy = y1 - y0, l = Math.sqrt(dx * dx + dy * dy);
        if (l < 1e-6) {
            return;
        }
        if (dashed) {
            int n = Math.max(1, (int) (l / 4));
            for (int k = 0; k < n; k += 2) {
                double a = k / (double) n, b = Math.min(1, (k + 1) / (double) n);
                edge(x0 + dx * a, y0 + dy * a, x0 + dx * b, y0 + dy * b, w, rgb, false);
            }
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
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }
}
