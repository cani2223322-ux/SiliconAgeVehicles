package com.sva.client;

import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sc.util.ArmorGasSC.Gas;
import com.sva.vehicle.FlightMath;
import com.sva.vehicle.HoverMath;
import com.sva.vehicle.Movement;
import com.sva.vehicle.PlaneMath;
import com.sva.vehicle.VehicleGases;
import com.sva.entity.EntitySeatSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

/**
 * The vehicle panel (bottom left, mock-up veh_3_hud.png) while riding: speed, EU + range, heat, hull, gas tanks
 * (core gas colours; the lift gas with the time it lasts at the current rate), cargo, warnings.
 * Aircraft (mock-up veh_3_hud.png, heli / plane): an altimeter tape on the left (Y, vertical speed, the ceiling),
 * a status line at the top («АВТОРОТАЦИЯ готова» / «АВТОРОТАЦИЯ» / «ПЛАНИРОВАНИЕ» / «СВАЛИВАНИЕ») and a flight panel
 * bottom right: heli - collective bar, mode (hover / flight) with EU/t, flight time left; plane - airspeed bar with
 * the stall mark, throttle, mode (take-off run on a runway / cruise at Y >= 100 -20 % / glide), flight time left.
 */
public class HudVehicleSVA {

    private static final int W = 128;
    private static final int BG = 0xD0161A22, EDGE = 0xFF3A4150, TEXT = 0xE0E0E0, DIM = 0x9A9AA6, WARN = 0xFF5A4A, AMBER = 0xFFD24D;
    private static final int C_EU = 0xFFE05454, C_HEAT = 0xFFFFA040, C_HP = 0xFF3CCB6E, C_TRACK = 0xFF0E1014;

    private double shownSpeed, shownVy;
    // the «Зарядка» line: EU rate measured from the synced charge over >= 1 s
    private int rateVehicle = -1, rateEnergy;
    private long rateTime;
    private double rateEma;

    public static EntityVehicleSVA vehicleOf(Entity e) {
        if (e == null) {
            return null;
        }
        if (e.ridingEntity instanceof EntityVehicleSVA) {
            return (EntityVehicleSVA) e.ridingEntity;
        }
        if (e.ridingEntity instanceof EntitySeatSVA) {
            return ((EntitySeatSVA) e.ridingEntity).getParent();
        }
        return null;
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post e) {
        if (e.type != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityVehicleSVA v = vehicleOf(mc.thePlayer);
        if (v == null || mc.gameSettings.hideGUI) {
            return;
        }
        VehicleType t = v.getVehicleType();
        FontRenderer fr = mc.fontRenderer;
        double dx = v.posX - v.prevPosX, dz = v.posZ - v.prevPosZ;
        shownSpeed = shownSpeed * 0.7 + Math.sqrt(dx * dx + dz * dz) * 0.3;

        boolean wear = v.wearOn();
        int energy = v.getEnergy();
        double ePct = energy / (double) t.buffer;
        double heat = v.getHeat();
        double hpPct = v.getHp() / t.maxHp;
        String warn = null;
        int warnColor = WARN;
        if (v.isWrecked()) {
            warn = Lang.tr("sva.hud.warn.wrecked");
        } else if (v.isHeatStopped()) {
            warn = Lang.tr("sva.hud.warn.overheat");
        } else if (energy <= 0) {
            warn = Lang.tr(t.movement.flies ? "sva.hud.warn.empty." + t.key : t.movement.lifts ? "sva.hud.warn.emptyLand" : "sva.hud.warn.empty");
        } else if (ePct < 0.1) {
            warn = Lang.tr("sva.hud.warn.lowEnergy");
            warnColor = AMBER;
        } else if (heat > 80) {
            warn = Lang.tr("sva.hud.warn.hot");
            warnColor = AMBER;
        }
        VehicleGases gs = t.gases;
        Gas lift = gs.liftGas;
        double liftRate = lift == null ? 0 : (v.isBoosting() ? gs.boostPerSecond : gs.liftPerSecond);
        if (warn == null || warnColor == AMBER) {
            String gw = gasWarning(v, gs, liftRate);
            if (gw != null) {
                warn = gw;
                warnColor = gw.startsWith("!") ? WARN : AMBER;
                if (gw.startsWith("!")) {
                    warn = gw.substring(1);
                }
            }
        }
        int gasRows = gs.gases().size() + (lift != null ? 1 : 0);
        boolean charging = v.isCharging();
        long now = mc.theWorld.getTotalWorldTime();
        if (v.getEntityId() != rateVehicle || now < rateTime) {
            rateVehicle = v.getEntityId();
            rateTime = now;
            rateEnergy = energy;
            rateEma = 0;
        } else if (now - rateTime >= 20) {
            double r = (energy - rateEnergy) / (double) (now - rateTime);
            rateEma = rateEma <= 0 || r <= 0 ? r : rateEma * 0.6 + r * 0.4;
            rateTime = now;
            rateEnergy = energy;
        }
        int rows = 5 + (wear ? 1 : 0) + gasRows + (warn != null ? 1 : 0) + (charging ? 1 : 0);
        int h = 24 + (rows - 1) * 10 + 4;
        int x = 4, y = e.resolution.getScaledHeight() - h - 4;

        Gui.drawRect(x - 1, y - 1, x + W + 1, y + h + 1, EDGE);
        Gui.drawRect(x, y, x + W, y + h, BG);
        // speed
        String sp = String.valueOf(VehicleMath.kmh(shownSpeed));
        GL11.glPushMatrix();
        GL11.glTranslatef(x + 6, y + 5, 0);
        GL11.glScalef(2F, 2F, 1F);
        fr.drawStringWithShadow(sp, 0, 0, 0xFFFFFF);
        GL11.glPopMatrix();
        fr.drawString(Lang.tr("sva.hud.kmh"), x + 10 + fr.getStringWidth(sp) * 2, y + 11, DIM);
        int ry = y + 24;
        // EU
        bar(fr, x, ry, Lang.tr("sva.hud.eu"), ePct, C_EU, (int) Math.floor(ePct * 100) + "%");
        ry += 10;
        float cruise = v.getCruiseCost();
        String range = cruise > 0 ? fmt((long) (energy / cruise * t.topSpeed)) : "∞";
        fr.drawString(Lang.tr("sva.hud.range", range), x + 6, ry, DIM);
        ry += 10;
        if (charging) {
            double sec = com.sva.platform.PlatformMath.etaSeconds(t.buffer - energy, rateEma);
            String pctS = (int) Math.floor(ePct * 100) + "%";
            String line = Double.isInfinite(sec) || sec <= 0 ? Lang.tr("sva.hud.charging", pctS)
                    : Lang.tr("sva.hud.chargingEta", pctS, String.format("%d:%02d", Long.valueOf((long) sec / 60), Long.valueOf((long) sec % 60)));
            fr.drawString(line, x + 6, ry, 0x5EC8F0);
            ry += 10;
        }
        bar(fr, x, ry, Lang.tr("sva.hud.heat"), heat / 100.0, v.isHeatStopped() ? 0xFFFF4040 : C_HEAT, (int) heat + "%");
        ry += 10;
        if (wear) {
            bar(fr, x, ry, Lang.tr("sva.hud.hull"), hpPct, hpPct < 0.25 ? 0xFFFF4040 : C_HP, (int) Math.round(hpPct * 100) + "%");
            ry += 10;
        }
        for (Gas g : gs.gases()) {
            int cap = Math.max(1, gs.capacity(g));
            int mb = v.getGas(g);
            bar(fr, x, ry, Lang.tr("sva.gas." + g.key()), mb / (double) cap, 0xFF000000 | g.color, (int) Math.floor(mb * 100.0 / cap) + "%");
            ry += 10;
        }
        if (lift != null) {
            double sec = HoverMath.secondsLeft(v.getGas(lift), v.isLifting() ? liftRate : gs.liftPerSecond);
            String left = Double.isInfinite(sec) ? "∞" : String.format("%d:%02d", Long.valueOf((long) sec / 60), Long.valueOf((long) sec % 60));
            fr.drawString(Lang.tr("sva.hud.gasLeft", Lang.tr("sva.gas." + lift.key()), left), x + 6, ry, v.isBoosting() ? AMBER : DIM);
            ry += 10;
        }
        fr.drawString(Lang.tr("sva.hud.cargo", Integer.valueOf(v.getCargoUsed()), Integer.valueOf(t.cargoSlots)), x + 6, ry, TEXT);
        ry += 10;
        if (warn != null) {
            boolean blink = (mc.theWorld.getTotalWorldTime() / 10) % 2 == 0 || warnColor == AMBER;
            fr.drawStringWithShadow(warn, x + 6, ry, blink ? warnColor : 0x803030);
        }
        if (t.movement.flies) {
            drawFlight(mc, fr, e.resolution.getScaledWidth(), e.resolution.getScaledHeight(), v, t);
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    private static final int GREEN = 0x5EE07A, ORANGE = 0xFF9A3C, BLUE = 0x5EC8F0;

    /** The aircraft instruments: altimeter tape, status line, flight panel. */
    private void drawFlight(Minecraft mc, FontRenderer fr, int sw, int sh, EntityVehicleSVA v, VehicleType t) {
        boolean heli = t.movement == Movement.HELI;
        shownVy = shownVy * 0.7 + (v.posY - v.prevPosY) * 0.3;
        long now = mc.theWorld.getTotalWorldTime();
        boolean blink = (now / 8) % 2 == 0;
        // ---- altimeter tape (left middle): 2 px a block, a tick every 5 blocks, a label every 10
        int aw = 48, ah = 120, ax = 4, ay = Math.max(4, sh / 2 - ah / 2 - 40);
        Gui.drawRect(ax - 1, ay - 1, ax + aw + 1, ay + ah + 1, EDGE);
        Gui.drawRect(ax, ay, ax + aw, ay + ah, BG);
        fr.drawString(Lang.tr("sva.hud.alt"), ax + (aw - fr.getStringWidth(Lang.tr("sva.hud.alt"))) / 2, ay + 3, DIM);
        int top = ay + 14, bottom = ay + ah - 26, mid = (top + bottom) / 2;
        double y = v.posY;
        int first = (int) Math.floor((y - (mid - top) / 2.0) / 5) * 5;
        for (int k = first; k <= y + (bottom - mid) / 2.0 + 5; k += 5) {
            int py = mid - (int) Math.round((k - y) * 2);
            if (py < top || py > bottom) {
                continue;
            }
            boolean ceiling = k == (int) FlightMath.CEILING;
            int col = ceiling ? 0xFFFF5040 : 0xFFE0E0E0;
            Gui.drawRect(ax + 6, py, ax + (k % 10 == 0 ? 16 : 11), py + 1, col);
            if (k % 10 == 0) {
                fr.drawString(String.valueOf(k), ax + 19, py - 4, ceiling ? 0xFF5040 : TEXT);
            }
        }
        Gui.drawRect(ax + 1, mid - 3, ax + 4, mid + 4, 0xFFFFD24D);                         // the marker
        Gui.drawRect(ax + 4, mid - 1, ax + 6, mid + 2, 0xFFFFD24D);
        String ys = Lang.tr("sva.hud.y", Integer.valueOf((int) Math.floor(y)));
        fr.drawStringWithShadow(ys, ax + (aw - fr.getStringWidth(ys)) / 2, ay + ah - 22, AMBER);
        double vs = shownVy * 20;
        String vss = Lang.tr("sva.hud.vs", (vs >= 0.05 ? "+" : "") + String.format("%.1f", Double.valueOf(vs)).replace('.', ','));
        fr.drawString(vss, ax + (aw - fr.getStringWidth(vss)) / 2, ay + ah - 11, vs > 0.5 ? GREEN : vs < -0.5 ? ORANGE : DIM);
        // ---- status line (top centre)
        String status = null;
        int sc = GREEN;
        if (v.isAutorotating()) {
            status = Lang.tr(heli ? "sva.hud.status.auto" : "sva.hud.status.glide");
            sc = blink ? WARN : 0x803030;
        } else if (v.isStalling()) {
            status = Lang.tr("sva.hud.status.stall");
            sc = blink ? WARN : 0x803030;
        } else if (heli && v.isLifting()) {
            status = Lang.tr("sva.hud.status.autoReady");
        } else if (v.posY >= FlightMath.CEILING - 2) {
            status = Lang.tr("sva.hud.status.ceiling");
            sc = AMBER;
        }
        if (status != null) {
            fr.drawStringWithShadow(status, (sw - fr.getStringWidth(status)) / 2, 30, sc);
        }
        // ---- flight panel (bottom right)
        int pw = 168, rows = heli ? 3 : 4, ph = rows * 11 + 8;
        int px = sw - pw - 4, py = sh - ph - 4;
        Gui.drawRect(px - 1, py - 1, px + pw + 1, py + ph + 1, EDGE);
        Gui.drawRect(px, py, px + pw, py + ph, BG);
        int ry = py + 5;
        float cost = v.getCostNow();
        String eu = fmt(Math.round(cost));
        boolean air = v.airborneAt(v.posX, v.posY, v.posZ);
        if (heli) {
            double coll;
            if (v.riddenByEntity == mc.thePlayer) {
                int in = com.sva.SVAMod.proxy.driverInput();
                boolean up = (in & EntityVehicleSVA.IN_BRAKE) != 0, down = (in & (EntityVehicleSVA.IN_DOWN | EntityVehicleSVA.IN_BOOST)) != 0;
                coll = up && !down ? 1 : down && !up ? 0.15 : 0.55;
            } else {
                coll = VehicleMath.clamp(0.55 + shownVy * 1.8, 0, 1);
            }
            wideBar(fr, px, ry, Lang.tr("sva.hud.collective"), v.isLifting() ? coll : 0, BLUE, pw);
            ry += 11;
            String mode;
            int mc2;
            if (v.isAutorotating()) {
                mode = Lang.tr("sva.hud.mode.auto");
                mc2 = WARN;
            } else if (!air) {
                mode = Lang.tr("sva.hud.mode.ground", eu);
                mc2 = DIM;
            } else if (shownSpeed < 0.15) {
                mode = Lang.tr("sva.hud.mode.hover", eu);
                mc2 = ORANGE;
            } else {
                mode = Lang.tr("sva.hud.mode.fly", eu);
                mc2 = GREEN;
            }
            fr.drawStringWithShadow(mode, px + 6, ry, mc2);
            ry += 11;
        } else {
            double vmax = t.topSpeed * PlaneMath.MAX_SPEED_MUL;
            fr.drawString(Lang.tr("sva.hud.stallMin", Integer.valueOf(VehicleMath.kmh(PlaneMath.STALL))), px + 6, ry, DIM);
            ry += 11;
            int bx = px + 6, bw = pw - 12;
            Gui.drawRect(bx, ry + 1, bx + bw, ry + 7, C_TRACK);
            int fill = (int) Math.round(bw * VehicleMath.clamp(shownSpeed / vmax, 0, 1));
            boolean stall = air && shownSpeed < PlaneMath.STALL;
            if (fill > 0) {
                Gui.drawRect(bx, ry + 1, bx + fill, ry + 7, stall ? 0xFFFF4040 : 0xFF5EC8F0);
            }
            int sx = bx + (int) Math.round(bw * PlaneMath.STALL / vmax);
            Gui.drawRect(sx - 1, ry - 1, sx + 1, ry + 9, 0xFFFF5040);                    // the stall mark
            ry += 11;
            wideBar(fr, px, ry, Lang.tr("sva.hud.throttle"), v.getThrottle(), ORANGE | 0xFF000000, pw);
            ry += 11;
            String mode;
            int mc2;
            if (v.isAutorotating()) {
                mode = Lang.tr("sva.hud.mode.glide");
                mc2 = AMBER;
            } else if (!air) {
                boolean runway = v.onRunway();
                mode = Lang.tr(runway ? "sva.hud.mode.runway" : "sva.hud.mode.taxi", eu);
                mc2 = runway ? BLUE : DIM;
            } else if (v.posY >= FlightMath.CRUISE_Y) {
                mode = Lang.tr("sva.hud.mode.cruise", Integer.valueOf((int) Math.floor(v.posY)), eu);
                mc2 = GREEN;
            } else {
                mode = Lang.tr("sva.hud.mode.low", eu, Integer.valueOf((int) FlightMath.CRUISE_Y));
                mc2 = TEXT;
            }
            fr.drawStringWithShadow(mode, px + 6, ry, mc2);
            ry += 11;
        }
        int energy = v.getEnergy();
        double sec = cost > 0 ? energy / (double) cost / 20.0 : Double.POSITIVE_INFINITY;
        String left = Double.isInfinite(sec) ? "∞" : sec >= 3600 ? String.format("%d:%02d:%02d", Long.valueOf((long) sec / 3600),
                Long.valueOf((long) sec / 60 % 60), Long.valueOf((long) sec % 60)) : String.format("%d:%02d", Long.valueOf((long) sec / 60), Long.valueOf((long) sec % 60));
        fr.drawString(Lang.tr("sva.hud.flightTime", Integer.valueOf((int) Math.floor(energy * 100.0 / t.buffer)), left), px + 6, ry, TEXT);
    }

    /** A label + a bar across the flight panel + a percentage. */
    private static void wideBar(FontRenderer fr, int px, int y, String label, double frac, int color, int pw) {
        fr.drawString(label, px + 6, y, DIM);
        int bx = px + 62, bw = pw - 62 - 34;
        Gui.drawRect(bx, y + 1, bx + bw, y + 7, C_TRACK);
        int fill = (int) Math.round(bw * VehicleMath.clamp(frac, 0, 1));
        if (fill > 0) {
            Gui.drawRect(bx, y + 1, bx + fill, y + 7, 0xFF000000 | color);
        }
        fr.drawString((int) Math.round(VehicleMath.clamp(frac, 0, 1) * 100) + "%", bx + bw + 4, y, TEXT);
    }

    /** Lift gas out / low, coolant out / low; a leading "!" = red. Null when all is fine. */
    private static String gasWarning(EntityVehicleSVA v, VehicleGases gs, double liftRate) {
        Gas lift = gs.liftGas;
        if (lift != null) {
            int mb = v.getGas(lift);
            if (mb <= 0) {
                return "!" + Lang.tr("sva.hud.warn.noLift." + lift.key());
            }
            if (HoverMath.secondsLeft(mb, Math.max(liftRate, gs.liftPerSecond)) < LOW_SECONDS || mb < gs.capacity(lift) / 10) {
                return Lang.tr("sva.hud.warn.low." + lift.key());
            }
        }
        Gas c = gs.coolant;
        if (c != null && c != lift) {
            int mb = v.getGas(c);
            if (mb <= 0 && v.getHeat() > VehicleMath.HE_START) {
                return Lang.tr("sva.hud.warn.noCoolant." + c.key());
            }
            if (mb < gs.capacity(c) / 10) {
                return Lang.tr("sva.hud.warn.low." + c.key());
            }
        }
        return null;
    }

    /** "Low" when the lift gas lasts under this many seconds. */
    private static final int LOW_SECONDS = 120;

    private static void bar(FontRenderer fr, int x, int y, String label, double frac, int color, String value) {
        fr.drawString(label, x + 6, y, DIM);
        int bx = x + 44, bw = 50;
        Gui.drawRect(bx, y + 1, bx + bw, y + 7, C_TRACK);
        int fill = (int) Math.round(bw * VehicleMath.clamp(frac, 0, 1));
        if (fill > 0) {
            Gui.drawRect(bx, y + 1, bx + fill, y + 7, color);
        }
        fr.drawString(value, bx + bw + 4, y, TEXT);
    }

    private static String fmt(long n) {
        return String.format("%,d", Long.valueOf(n)).replace(',', ' ');
    }
}
