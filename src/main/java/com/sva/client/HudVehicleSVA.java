package com.sva.client;

import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sc.util.ArmorGasSC.Gas;
import com.sva.vehicle.HoverMath;
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
 */
public class HudVehicleSVA {

    private static final int W = 128;
    private static final int BG = 0xD0161A22, EDGE = 0xFF3A4150, TEXT = 0xE0E0E0, DIM = 0x9A9AA6, WARN = 0xFF5A4A, AMBER = 0xFFD24D;
    private static final int C_EU = 0xFFE05454, C_HEAT = 0xFFFFA040, C_HP = 0xFF3CCB6E, C_TRACK = 0xFF0E1014;

    private double shownSpeed;
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
            warn = Lang.tr(t.movement.lifts ? "sva.hud.warn.emptyLand" : "sva.hud.warn.empty");
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
        GL11.glColor4f(1F, 1F, 1F, 1F);
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
