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
    // wave 4 (★15): the «Рекуперация» line - the last synced rate, held REGEN_HOLD ticks so it doesn't flicker
    private static final int REGEN_HOLD = 10;
    private double regenShown;
    private long regenUntil;

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
        if (v == null || mc.gameSettings.hideGUI || mc.gameSettings.showDebugInfo) {
            return;                                                 // hidden under F3, like the core's HUDs
        }
        GlassCockpitSVA.drawHudFlash(mc, e.resolution);             // cockpit wave: the radar altimeter's callout, no chat
        VehicleType t = v.getVehicleType();
        FontRenderer fr = mc.fontRenderer;
        double dx = v.posX - v.prevPosX, dz = v.posZ - v.prevPosZ;
        shownSpeed = shownSpeed * 0.7 + Math.sqrt(dx * dx + dz * dz) * 0.3;
        if (mc.gameSettings.thirdPersonView == 0 && v.frost() > 0) {
            frost(e.resolution.getScaledWidth(), e.resolution.getScaledHeight(), v.frost());   // D2: ОБОГРЕВ off in the cold
        }

        boolean wear = v.wearOn();
        int energy = v.getEnergy();
        double ePct = energy / v.getMaxEnergy();
        double heat = v.getHeat();
        double hpPct = v.getHp() / v.getMaxHp();
        String warn = null;
        int warnColor = WARN;
        boolean h2 = v.onHydrogen();
        int fuelLamp = h2 ? v.fuelLamp() : com.sva.vehicle.FuelMath.LAMP_OK;
        if (v.isWrecked()) {
            warn = Lang.tr("sva.hud.warn.wrecked");
        } else if (v.isHeatStopped()) {
            warn = Lang.tr("sva.hud.warn.overheat");
        } else if (h2 && fuelLamp == com.sva.vehicle.FuelMath.LAMP_EMPTY) {
            warn = Lang.tr("sva.hud.warn.noH2");                    // hydrogen wave: «НЕТ ВОДОРОДА - ДВИГАТЕЛЬ ЗАГЛОХ»
        } else if (h2 && !v.batteryPresent()) {
            warn = Lang.tr("sva.hud.bat.none");
        } else if (h2 && energy <= 0) {
            warn = Lang.tr("sva.hud.warn.batFlat");
        } else if (h2 && fuelLamp == com.sva.vehicle.FuelMath.LAMP_RESERVE) {
            warn = Lang.tr("sva.hud.warn.reserve");
        } else if (h2 && fuelLamp == com.sva.vehicle.FuelMath.LAMP_LOW) {
            warn = Lang.tr("sva.hud.warn.lowFuel");
            warnColor = AMBER;
        } else if (h2) {
            if (heat > 80) {
                warn = Lang.tr("sva.hud.warn.hot");
                warnColor = AMBER;
            }
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
            String gw = gasWarning(v, gs, liftRate, h2);
            if (gw != null) {
                warn = gw;
                warnColor = gw.startsWith("!") ? WARN : AMBER;
                if (gw.startsWith("!")) {
                    warn = gw.substring(1);
                }
            }
        }
        java.util.List<Gas> tanks = v.tankList();
        // hydrogen wave: H2 has its own bar + the flow line + the battery line (in place of EU + range); no lift-gas line
        int gasRows = tanks.size() - (h2 ? 1 : 0) + (lift != null && !h2 ? 1 : 0) + (h2 ? 1 : 0);
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
        boolean autoLine = t.autoDelivery && (v.isAutoOn() || v.getAutoState() == com.sva.vehicle.AutoDelivery.STOPPED);
        String trailerLine = trailerLine(v);                        // ★15: the tanker's fluid / the carried vehicle / the charger
        double regen = v.getRegenNow();                             // wave 4 (★15): recovering - braking, downhill, windmilling
        if (regen > 0) {
            regenShown = regen;
            regenUntil = now + REGEN_HOLD;
        }
        boolean regenLine = !charging && now < regenUntil && now + REGEN_HOLD + 1 > regenUntil;
        int rows = 5 + (wear ? 1 : 0) + gasRows + (warn != null ? 1 : 0) + (charging ? 1 : 0) + (autoLine ? 1 : 0) + (trailerLine != null ? 1 : 0)
                + (regenLine ? 1 : 0);
        int h = 24 + (rows - 1) * 10 + 4;
        int x = 4, y = e.resolution.getScaledHeight() - h - bottomGap(e.resolution.getScaledWidth(), x, x + W);
        // the chat (bottom left, its last line at height - 48): while it shows messages the panel moves up over them
        int chat = chatTop(mc, e.resolution.getScaledHeight());
        if (chat > 0 && y + h > chat - 2) {
            y = Math.max(2, chat - 2 - h);
        }

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
        if (h2) {
            ry = drawFuel(fr, x, ry, v, now);
        } else {
            // EU
            bar(fr, x, ry, Lang.tr("sva.hud.eu"), ePct, C_EU, (int) Math.floor(ePct * 100) + "%");
            ry += 10;
            float cruise = v.getCruiseCost();
            String range = cruise > 0 ? fmt((long) (energy / cruise * t.topSpeed)) : "--";
            fit(fr, Lang.tr("sva.hud.range", range), x + 6, ry, W - 12, DIM, false);
            ry += 10;
        }
        if (charging) {
            double sec = com.sva.platform.PlatformMath.etaSeconds(v.getMaxEnergy() - energy, rateEma);
            String pctS = (int) Math.floor(ePct * 100) + "%";
            String line = Double.isInfinite(sec) || sec <= 0 ? Lang.tr("sva.hud.charging", pctS)
                    : Lang.tr("sva.hud.chargingEta", pctS, String.format("%d:%02d", Long.valueOf((long) sec / 60), Long.valueOf((long) sec % 60)));
            fit(fr, line, x + 6, ry, W - 12, 0x5EC8F0, false);
            ry += 10;
        }
        if (regenLine) {
            fit(fr, Lang.tr("sva.hud.regen", com.sva.vehicle.RegenMath.fmt(regenShown)), x + 6, ry, W - 12, 0x5EE07A, false);
            ry += 10;
        }
        bar(fr, x, ry, Lang.tr("sva.hud.heat"), heat / 100.0, v.isHeatStopped() ? 0xFFFF4040 : C_HEAT, (int) heat + "%");
        ry += 10;
        if (wear) {
            bar(fr, x, ry, Lang.tr("sva.hud.hull"), hpPct, hpPct < 0.25 ? 0xFFFF4040 : C_HP, (int) Math.round(hpPct * 100) + "%");
            ry += 10;
        }
        for (Gas g : tanks) {
            if (h2 && g == com.sva.vehicle.FuelMath.H2) {
                continue;                                           // drawn at the top
            }
            int cap = Math.max(1, v.getGasCapacity(g));
            int mb = v.getGas(g);
            bar(fr, x, ry, Lang.tr("sva.gas." + g.key()), mb / (double) cap, 0xFF000000 | g.color, (int) Math.floor(mb * 100.0 / cap) + "%");
            ry += 10;
        }
        if (lift != null && !h2) {
            double sec = HoverMath.secondsLeft(v.getGas(lift), v.isLifting() ? liftRate : gs.liftPerSecond);
            String left = Double.isInfinite(sec) ? "--" : String.format("%d:%02d", Long.valueOf((long) sec / 60), Long.valueOf((long) sec % 60));
            fit(fr, Lang.tr("sva.hud.gasLeft", Lang.tr("sva.gas." + lift.key()), left), x + 6, ry, W - 12, v.isBoosting() ? AMBER : DIM, false);
            ry += 10;
        }
        fit(fr, Lang.tr("sva.hud.cargo", Integer.valueOf(v.getCargoUsed()), Integer.valueOf(v.cargoCapacity())), x + 6, ry, W - 12, TEXT, false);
        ry += 10;
        if (trailerLine != null) {
            fit(fr, trailerLine, x + 6, ry, W - 12, BLUE, false);
            ry += 10;
        }
        if (autoLine) {
            boolean stop = v.getAutoState() == com.sva.vehicle.AutoDelivery.STOPPED || v.isAutoPaused();   // ТС-6: paused at the loaded edge - yellow as stopped
            fit(fr, GuiCargoSVA.autoStatus(v), x + 6, ry, W - 12, stop ? WARN : BLUE, false);
            ry += 10;
        }
        if (warn != null) {
            boolean blink = (mc.theWorld.getTotalWorldTime() / 10) % 2 == 0 || warnColor == AMBER;
            fit(fr, warn, x + 6, ry, W - 12, blink ? warnColor : 0x803030, true);
        }
        int rightTop = e.resolution.getScaledHeight();
        if (t.movement.flies) {
            rightTop = drawFlight(mc, fr, e.resolution.getScaledWidth(), e.resolution.getScaledHeight(), v, t, y);
        }
        if (t.hasDrill) {
            rightTop = Math.min(rightTop, drawDrill(fr, e.resolution.getScaledWidth(), e.resolution.getScaledHeight(), v));
        }
        if (v.riddenByEntity == mc.thePlayer) {
            CruiseClientSVA.drawHud(mc, fr, e.resolution.getScaledWidth(), rightTop, v);   // ★4 (the driver's own state)
        }
        if (t.bridgeDock) {
            drawSpace(mc, fr, e.resolution.getScaledWidth(), v, t);
        }
        if (t == VehicleType.LINER && v.liner() != null) {
            drawLiner(mc, fr, e.resolution.getScaledWidth(), v, v.liner());
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /**
     * Liner wave 1: the SA-320's compact block (top right): N1 left / right, H2 in each tank (L / C / R), the APU's state,
     * where the electrics come from, the first warning / caution while the master lamp is lit.
     */
    private void drawLiner(Minecraft mc, FontRenderer fr, int sw, EntityVehicleSVA v, com.sva.vehicle.LinerSystems L) {
        String msg = null;
        int mc0 = AMBER;
        int top = L.ecam.top();                                     // wave 3: the ECAM's top message (red before amber)
        if (top >= 0) {
            msg = Lang.tr("sva.liner.msg." + com.sva.vehicle.LinerEcam.KEYS[top]);
            mc0 = com.sva.vehicle.LinerEcam.LEVEL[top] == com.sva.vehicle.LinerEcam.WARN ? WARN : AMBER;
        }
        int rows = 5 + (msg != null ? 1 : 0);
        int pw = 190, ph = rows * 11 + 8, px = sw - pw - 4, py = 4;
        Gui.drawRect(px - 1, py - 1, px + pw + 1, py + ph + 1, EDGE);
        Gui.drawRect(px, py, px + pw, py + ph, BG);
        int ry = py + 5;
        fit(fr, Lang.tr("item.siliconagevehicles.vehicle.liner.name"), px + 6, ry, pw - 12, ORANGE, false);
        ry += 11;
        fit(fr, Lang.tr("sva.liner.hud.n1", com.sva.client.LinerGfxSVA.num(L.eng[0].n1, 1), com.sva.client.LinerGfxSVA.num(L.eng[1].n1, 1)), px + 6, ry,
                pw - 12, L.anyRunning() ? GREEN : DIM, false);
        ry += 11;
        fit(fr, Lang.tr("sva.liner.hud.h2", fmt(Math.round(L.tankL)), fmt(Math.round(L.tankC)), fmt(Math.round(L.tankR))), px + 6, ry, pw - 12, TEXT,
                false);
        ry += 11;
        String apu;
        int ac = DIM;
        if (L.apuFault) {
            apu = Lang.tr("sva.liner.hud.apu.fault");
            ac = WARN;
        } else if (L.apu == com.sva.vehicle.LinerSystems.A_AVAIL) {
            apu = Lang.tr("sva.liner.hud.apu.avail");
            ac = GREEN;
        } else if (L.apu == com.sva.vehicle.LinerSystems.A_START) {
            apu = Lang.tr("sva.liner.hud.apu.start", Integer.valueOf((int) Math.round(L.apuN)));
            ac = AMBER;
        } else if (L.apu == com.sva.vehicle.LinerSystems.A_COOL) {
            apu = Lang.tr("sva.liner.hud.apu.cool");
        } else if (L.apu == com.sva.vehicle.LinerSystems.A_FLAP) {
            apu = Lang.tr("sva.liner.hud.apu.flap");
        } else {
            apu = Lang.tr("sva.liner.hud.apu.off");
        }
        fit(fr, Lang.tr("sva.liner.hud.apu", apu), px + 6, ry, pw - 12, ac, false);
        ry += 11;
        String src;
        int scol = GREEN;
        if (L.gen1On || L.gen2On) {
            src = Lang.tr("sva.liner.hud.src.gen", (L.gen1On ? "1" : "") + (L.gen1On && L.gen2On ? "+" : "") + (L.gen2On ? "2" : ""));
        } else if (L.extOn) {
            src = Lang.tr("sva.liner.hud.src.ext");
        } else if (L.apuGenOn) {
            src = Lang.tr("sva.liner.hud.src.apu");
        } else if (L.essOnly) {
            src = Lang.tr("sva.liner.hud.src.bat", Integer.valueOf((int) Math.round(L.batteryMinutes(LinerDisplaysSVA.estimateBat1(v, L)))));
            scol = AMBER;
        } else {
            src = Lang.tr("sva.liner.hud.src.none");
            scol = DIM;
        }
        fit(fr, Lang.tr("sva.liner.hud.elec", src), px + 6, ry, pw - 12, scol, false);
        ry += 11;
        if (msg != null) {
            boolean blink = (mc.theWorld.getTotalWorldTime() / 10) % 2 == 0;
            fit(fr, msg, px + 6, ry, pw - 12, blink || mc0 == AMBER ? mc0 : 0x803030, true);
        }
    }


    /**
     * Stage E, the space panel (top right) of the shuttle and the ship: the cabin's oxygen (time for the riders aboard),
     * «Дальний режим» (working / waiting for height and speed / off), «Стыковка с мостом» near an open vortex; the
     * ship adds its jump target, distance and cost, the jump's state (ready / cooldown / why not / the portal opening),
     * the gravity anchor and invisibility.
     */
    private void drawSpace(Minecraft mc, FontRenderer fr, int sw, EntityVehicleSVA v, VehicleType t) {
        String[] info = v.getSpaceInfo().split(";", -1);
        int riders = num(info, 0);
        boolean ship = t.singular;
        boolean dock = v.isDockNear();
        int rows = 2 + (dock ? 1 : 0) + (ship ? 5 : 0);
        int pw = 200, ph = rows * 11 + 8, px = sw - pw - 4, py = 4;
        long now = mc.theWorld.getTotalWorldTime();
        boolean blink = (now / 8) % 2 == 0;
        Gui.drawRect(px - 1, py - 1, px + pw + 1, py + ph + 1, EDGE);
        Gui.drawRect(px, py, px + pw, py + ph, BG);
        int ry = py + 5;
        // oxygen
        int o2 = v.getGas(Gas.OXYGEN);
        if (o2 <= 0) {
            fit(fr, Lang.tr("sva.hud.o2.empty"), px + 6, ry, pw - 12, WARN, false);
        } else {
            double sec = com.sva.vehicle.SpaceMath.o2Seconds(o2, Math.max(1, riders));
            fit(fr, Lang.tr("sva.hud.o2", time(sec), Integer.valueOf(Math.max(1, riders))), px + 6, ry, pw - 12, o2 < 200 ? AMBER : BLUE, false);
        }
        ry += 11;
        // long-range mode
        String lr;
        int lc;
        if (v.isLongRangeActive()) {
            lr = Lang.tr("sva.hud.long.active", fmt((long) com.sva.vehicle.SpaceMath.LONG_D_PER_SECOND));
            lc = GREEN;
        } else if (!v.isLongRangeOn()) {
            lr = Lang.tr("sva.hud.long.off", VehicleKeyBindsSVA.name(com.sva.vehicle.VehicleFn.MODE));
            lc = DIM;
        } else if (v.getGas(Gas.DEUTERIUM) <= 0) {
            lr = Lang.tr("sva.hud.long.noD");
            lc = WARN;
        } else {
            lr = Lang.tr("sva.hud.long.wait", Integer.valueOf((int) com.sva.vehicle.SpaceMath.LONG_Y),
                    Integer.valueOf(VehicleMath.kmh(t.topSpeed * com.sva.vehicle.SpaceMath.LONG_SPEED)));
            lc = AMBER;
        }
        fit(fr, lr, px + 6, ry, pw - 12, lc, false);
        ry += 11;
        if (dock) {
            fit(fr, Lang.tr("sva.hud.dock"), px + 6, ry, pw - 12, blink ? BLUE : 0x3A7CA0, true);
            ry += 11;
        }
        if (!ship) {
            return;
        }
        int code = num(info, 1);
        long dist = num(info, 2), eu = num(info, 3), sm = num(info, 4);
        int cd = num(info, 5), dim = num(info, 6);
        boolean home = num(info, 7) == 1;
        String name = info.length > 8 ? info[8] : "";
        boolean other = dim != v.worldObj.provider.dimensionId;
        String tgt;
        if (code == com.sva.vehicle.SpaceMath.NO_TARGET) {
            tgt = Lang.tr("sva.hud.jump.noTarget", VehicleKeyBindsSVA.name(com.sva.vehicle.VehicleFn.TARGET));
        } else if (home) {
            tgt = Lang.tr("sva.hud.jump.home", other ? Lang.tr("sva.hud.jump.otherDim", Integer.valueOf(dim)) : fmt(dist));
        } else {
            tgt = Lang.tr("sva.hud.jump.coord", name.length() == 0 ? "—" : name,
                    other ? Lang.tr("sva.hud.jump.otherDim", Integer.valueOf(dim)) : fmt(dist));
        }
        fit(fr, tgt, px + 6, ry, pw - 12, code == com.sva.vehicle.SpaceMath.NO_TARGET ? DIM : TEXT, false);
        ry += 11;
        fit(fr, code == com.sva.vehicle.SpaceMath.NO_TARGET ? "" : Lang.tr("sva.hud.jump.cost", fmt(eu), fmt(sm)), px + 6, ry, pw - 12, DIM, false);
        ry += 11;
        String st;
        int sc;
        if (v.isPortalOpening()) {
            st = Lang.tr("sva.hud.jump.portal");
            sc = blink ? 0xC85AFF : 0x8040B0;
        } else if (code == com.sva.vehicle.SpaceMath.OK) {
            st = Lang.tr("sva.hud.jump.ready", VehicleKeyBindsSVA.name(com.sva.vehicle.VehicleFn.JUMP));
            sc = GREEN;
        } else if (code == com.sva.vehicle.SpaceMath.COOLDOWN) {
            st = Lang.tr("sva.hud.jump.cooldown", Integer.valueOf(cd));
            sc = AMBER;
        } else {
            st = Lang.tr("sva.hud.jump.why." + code, Integer.valueOf(com.sva.vehicle.SpaceMath.MIN_JUMP));
            sc = code == com.sva.vehicle.SpaceMath.NO_TARGET ? DIM : WARN;
        }
        fit(fr, st, px + 6, ry, pw - 12, sc, true);
        ry += 11;
        boolean anchor = v.isAnchored(), cloak = v.isInvisible();
        fit(fr, Lang.tr(anchor ? "sva.hud.anchor.on" : "sva.hud.anchor.off", VehicleKeyBindsSVA.name(com.sva.vehicle.VehicleFn.ANCHOR)), px + 6, ry, pw - 12,
                anchor ? BLUE : DIM, false);
        ry += 11;
        fit(fr, Lang.tr(cloak ? "sva.hud.invis.on" : "sva.hud.invis.off", VehicleKeyBindsSVA.name(com.sva.vehicle.VehicleFn.CLOAK)), px + 6, ry, pw - 12,
                cloak ? 0xC85AFF : DIM, false);
    }

    private static int num(String[] p, int i) {
        if (i >= p.length) {
            return 0;
        }
        try {
            return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Long.parseLong(p[i].trim())));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String time(double sec) {
        if (Double.isInfinite(sec)) {
            return "--";
        }
        return sec >= 3600 ? String.format("%d:%02d:%02d", Long.valueOf((long) sec / 3600), Long.valueOf((long) sec / 60 % 60),
                Long.valueOf((long) sec % 60)) : String.format("%d:%02d", Long.valueOf((long) sec / 60), Long.valueOf((long) sec % 60));
    }

    private static final int GREEN = 0x5EE07A, ORANGE = 0xFF9A3C, BLUE = 0x5EC8F0;

    /** The aircraft instruments: altimeter tape, status line, flight panel. */
    /** @return the flight panel's top (the cruise panel goes above it) */
    private int drawFlight(Minecraft mc, FontRenderer fr, int sw, int sh, EntityVehicleSVA v, VehicleType t, int leftTop) {
        boolean tilt = t.movement == Movement.TILT;
        boolean heli = t.movement == Movement.HELI || tilt && !v.planeNow();
        boolean afterRow = t.gases.boostGas != null && !heli;      // the afterburner: jet, shuttle, tiltrotor in plane mode
        // aircraft stage A: «Закрылки 20 гр. · предкр. · ТОРМОЗ» (the light plane, the jet, the tiltrotor in airplane mode)
        boolean hlRow = !heli && com.sva.vehicle.HighLiftMath.spec(t) != null;
        // round 2, D2: the board systems - the engine start, the failures, the battery
        // D5: the helicopter (no board systems) gets the line only while its INS is not aligned
        boolean sysRow = com.sva.vehicle.SystemsMath.applies(t) || com.sva.vehicle.SystemsMath.insApplies(t) && !v.insReady();
        int extraRows = (tilt ? 1 : 0) + (afterRow ? 1 : 0) + (hlRow ? 1 : 0) + (sysRow ? 1 : 0);
        shownVy = shownVy * 0.7 + (v.posY - v.prevPosY) * 0.3;
        long now = mc.theWorld.getTotalWorldTime();
        boolean blink = (now / 8) % 2 == 0;
        // ---- altimeter tape (left middle): 2 px a block, a tick every 5 blocks, a label every 10
        int aw = 48, ah = 120, ax = 4, ay = Math.max(4, sh / 2 - ah / 2 - 40);
        if (ay + ah + 4 > leftTop) {
            // clear of the vehicle panel below: shorter above it, else beside it
            if (leftTop - 8 >= 80) {
                ah = Math.min(ah, leftTop - 8);
                ay = Math.min(ay, leftTop - 4 - ah);
            } else {
                ax = 4 + W + 6;
            }
        }
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
        } else if (sysRow && v.engineGated(t) && (v.engineState() == com.sva.vehicle.SystemsMath.CRANK
                || v.engineState() == com.sva.vehicle.SystemsMath.WARM)) {
            status = Lang.tr("sva.hud.status.starting");               // D2: «Запуск двигателя...»
            sc = AMBER;
        }
        if (status != null) {
            fr.drawStringWithShadow(status, (sw - fr.getStringWidth(status)) / 2, 30, sc);
        }
        // ---- flight panel (bottom right)
        int pw = 168, rows = (heli ? 3 : 5) + extraRows, ph = rows * 11 + 8;
        int px = sw - pw - 4, py = sh - ph - bottomGap(sw, px, px + pw);
        Gui.drawRect(px - 1, py - 1, px + pw + 1, py + ph + 1, EDGE);
        Gui.drawRect(px, py, px + pw, py + ph, BG);
        int ry = py + 5;
        if (tilt) {
            // «Режим: самолёт / вертикальный · переход 60%»
            double pos = v.getTiltPos();
            boolean toPlane = v.getTiltTarget();
            String m = Lang.tr(toPlane ? "sva.hud.tilt.plane" : "sva.hud.tilt.vertical");
            String line = com.sva.vehicle.TiltMath.transition(pos)
                    ? Lang.tr("sva.hud.tilt.turning", m, Integer.valueOf((int) Math.round((toPlane ? pos : 1 - pos) * 100)))
                    : Lang.tr("sva.hud.tilt.mode", m, VehicleKeyBindsSVA.name(com.sva.vehicle.VehicleFn.MODE));
            fit(fr, line, px + 6, ry, pw - 12, com.sva.vehicle.TiltMath.transition(pos) ? AMBER : BLUE, true);
            ry += 11;
        }
        if (afterRow) {
            // «Форсаж: ВКЛ · H₂ 12:30»
            Gas g = t.gases.boostGas;
            int mb = v.getGas(g);
            double sec = HoverMath.secondsLeft(mb, t.gases.boostGasPerSecond);
            String left = Double.isInfinite(sec) ? "--" : String.format("%d:%02d", Long.valueOf((long) sec / 60), Long.valueOf((long) sec % 60));
            String gas = Lang.tr("sva.gas." + g.key());
            String line;
            int col;
            if (v.isBoosting()) {
                line = Lang.tr("sva.hud.after.on", gas, left);
                col = blink ? ORANGE : AMBER;
            } else if (mb <= 0) {
                line = Lang.tr("sva.hud.after.empty", gas);
                col = WARN;
            } else {
                line = Lang.tr("sva.hud.after.ready", gas, left, VehicleKeyBindsSVA.name(com.sva.vehicle.VehicleFn.BOOST));
                col = DIM;
            }
            fit(fr, line, px + 6, ry, pw - 12, col, true);
            ry += 11;
        }
        if (hlRow) {
            com.sva.vehicle.HighLiftMath.Spec hs = com.sva.vehicle.HighLiftMath.spec(t);
            String line;
            int col;
            if (v.flapWarning()) {
                // the protection: «V>120 ЗАКР» blinking (a refused / blown-back setting)
                line = Lang.tr("sva.hud.hl.warn", Integer.valueOf(com.sva.vehicle.HighLiftMath.limitKmh(hs, v.getFlaps() + 1)));
                col = blink ? WARN : 0x803030;
            } else {
                StringBuilder sb = new StringBuilder(Lang.tr(hs.flaperons ? "sva.hud.hl.flaperons" : "sva.hud.hl.flaps",
                        Integer.valueOf(Math.round(v.getFlapAngle()))));
                if (v.slatsOut()) {
                    sb.append(" \u00b7 ").append(Lang.tr("sva.hud.hl.slats"));
                }
                if (v.isAirbrake()) {
                    sb.append(" \u00b7 ").append(Lang.tr("sva.hud.hl.airbrake"));
                } else if (v.groundSpoilersUp()) {
                    sb.append(" \u00b7 ").append(Lang.tr("sva.hud.hl.rollout"));
                }
                line = sb.toString();
                col = v.isAirbrake() ? ORANGE : v.getFlaps() > 0 ? AMBER : DIM;
            }
            fit(fr, line, px + 6, ry, pw - 12, col, true);
            ry += 11;
        }
        if (sysRow) {
            String[] line = systemsLine(v, t, blink);
            fit(fr, line[0], px + 6, ry, pw - 12, Integer.parseInt(line[1]), true);
            ry += 11;
        }
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
            fit(fr, mode, px + 6, ry, pw - 12, mc2, true);
            ry += 11;
        } else {
            double vmax = PlaneMath.maxSpeed(t, t.gases.boostMul);
            double stallV = v.stallSpeed();                     // stage A: lowered by the flaps / slats
            fit(fr, Lang.tr("sva.hud.stallMin", Integer.valueOf(VehicleMath.kmh(stallV))), px + 6, ry, pw - 12, DIM, false);
            ry += 11;
            int bx = px + 6, bw = pw - 12;
            Gui.drawRect(bx, ry + 1, bx + bw, ry + 7, C_TRACK);
            int fill = (int) Math.round(bw * VehicleMath.clamp(shownSpeed / vmax, 0, 1));
            boolean stall = air && shownSpeed < stallV;
            if (fill > 0) {
                Gui.drawRect(bx, ry + 1, bx + fill, ry + 7, stall ? 0xFFFF4040 : 0xFF5EC8F0);
            }
            int sx = bx + (int) Math.round(bw * stallV / vmax);
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
            fit(fr, mode, px + 6, ry, pw - 12, mc2, true);
            ry += 11;
        }
        int energy = v.getEnergy();
        double sec = cost > 0 ? energy / (double) cost / 20.0 : Double.POSITIVE_INFINITY;
        String left = Double.isInfinite(sec) ? "--" : sec >= 3600 ? String.format("%d:%02d:%02d", Long.valueOf((long) sec / 3600),
                Long.valueOf((long) sec / 60 % 60), Long.valueOf((long) sec % 60)) : String.format("%d:%02d", Long.valueOf((long) sec / 60), Long.valueOf((long) sec % 60));
        fit(fr, Lang.tr("sva.hud.flightTime", Integer.valueOf((int) Math.floor(energy * 100.0 / v.getMaxEnergy())), left), px + 6, ry, pw - 12, TEXT, false);
        return py;
    }

    /**
     * Round 2, D2: the board systems' line of the flight panel - the engine off («Двигатель выкл. - ; или W»), starting
     * («Запуск 40 %»), the failures («ОТКАЗ: двигатель, закрылки» blinking red), the board dark («АКБ ВЫКЛ»), else the
     * battery and ГЕН / ОБОГРЕВ («АКБ 100 % · ГЕН · обогрев»). {text, colour}.
     */
    static String[] systemsLine(EntityVehicleSVA v, VehicleType t, boolean blink) {
        int es = v.engineState(), fails = v.failures();
        boolean gated = v.engineGated(t);
        // D5: the INS not aligned - its state after the engine's («... · ИНС: выставка 7 с»), the line amber
        String ins = v.insApplies() && !v.insReady() ? insText(v) : null;
        if (!com.sva.vehicle.SystemsMath.applies(t)) {
            return new String[] {ins == null ? "" : ins, String.valueOf(v.insStatus() == com.sva.vehicle.SystemsMath.INS_FAULT && blink ? WARN : AMBER)};
        }
        String text;
        int c;
        if (!v.switchOn(com.sva.vehicle.SystemsMath.SW_BAT)) {
            text = Lang.tr("sva.hud.sys.noMaster", VehicleKeyBindsSVA.name(com.sva.vehicle.VehicleFn.ENGINE));
            c = blink ? WARN : 0x803030;
        } else if (fails != 0 || v.pitotIced()) {
            StringBuilder b = new StringBuilder();
            int[] bits = {com.sva.vehicle.SystemsMath.F_ENGINE, com.sva.vehicle.SystemsMath.F_FLAP, com.sva.vehicle.SystemsMath.F_INSTR};
            String[] keys = {"engine", "flap", "instr"};
            for (int k = 0; k < 3; k++) {
                if ((fails & bits[k]) != 0) {
                    b.append(b.length() > 0 ? ", " : "").append(Lang.tr("sva.hud.sys.fail." + keys[k]));
                }
            }
            if (v.pitotIced()) {
                b.append(b.length() > 0 ? ", " : "").append(Lang.tr("sva.hud.sys.iced"));
            }
            text = Lang.tr("sva.hud.sys.fails", b.toString());
            c = blink ? WARN : 0xC04030;
        } else if (gated && es == com.sva.vehicle.SystemsMath.OFF) {
            text = Lang.tr("sva.hud.sys.off", VehicleKeyBindsSVA.name(com.sva.vehicle.VehicleFn.ENGINE));
            c = DIM;
        } else if (gated && es != com.sva.vehicle.SystemsMath.RUN) {
            int pct = Math.round(100 * com.sva.vehicle.SystemsMath.progress(es, v.engineTicks(), com.sva.vehicle.SystemsMath.crankTicks(t, false),
                    com.sva.vehicle.SystemsMath.warmTicks(t)));
            text = Lang.tr(es == com.sva.vehicle.SystemsMath.CRANK ? "sva.hud.sys.crank" : "sva.hud.sys.warm", Integer.valueOf(pct));
            c = AMBER;
        } else {
            StringBuilder b = new StringBuilder(Lang.tr("sva.hud.sys.battery", Integer.valueOf(v.batteryPct())));
            b.append(" \u00b7 ").append(Lang.tr(v.switchOn(com.sva.vehicle.SystemsMath.SW_GEN) ? "sva.hud.sys.gen" : "sva.hud.sys.noGen"));
            if (v.heating()) {
                b.append(" \u00b7 ").append(Lang.tr("sva.hud.sys.heat"));
            } else if (v.coldAir()) {
                b.append(" \u00b7 ").append(Lang.tr("sva.hud.sys.cold"));
            }
            text = b.toString();
            c = !v.switchOn(com.sva.vehicle.SystemsMath.SW_GEN) || v.batteryPct() < 20 ? AMBER : DIM;
        }
        if (ins != null && v.switchOn(com.sva.vehicle.SystemsMath.SW_BAT)) {
            text = text + " \u00b7 " + ins;
            c = c == DIM ? AMBER : c;
        }
        return new String[] {text, String.valueOf(c)};
    }

    /**
     * Stage D5: the INS's state in words - «ИНС: выставка 7 с» (+ «сначала, стойте» after a restart), «ИНС: нет питания»,
     * «ИНС выкл.», «ИНС выставлена» (the menu's tip, the HUD's systems line).
     */
    static String insText(EntityVehicleSVA v) {
        int st = v.insStatus();
        if (st == com.sva.vehicle.SystemsMath.INS_ALIGN) {
            String s = Lang.tr("sva.ins.align", Integer.valueOf(v.insSecondsLeft()));
            return v.insRestarted() ? s + " " + Lang.tr("sva.ins.restart") : s;
        }
        return Lang.tr("sva.ins." + com.sva.vehicle.SystemsMath.INS_KEYS[st]);
    }

    /**
     * Round 2, D2: the windscreen frosting over (ОБОГРЕВ off in the cold, level 1..3): a white rime creeping in from the
     * screen's edges - the first-person view only, translucent so the instruments still read.
     */
    private static void frost(int sw, int sh, int level) {
        float a = 0.18F * level;
        int band = Math.max(8, Math.min(sw, sh) * (2 + 3 * level) / 24);
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
        org.lwjgl.opengl.GL11.glBlendFunc(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);
        org.lwjgl.opengl.GL11.glShadeModel(org.lwjgl.opengl.GL11.GL_SMOOTH);
        org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
        int[][] edges = {{0, 0, sw, 0, sw, band, 0, band}, {0, sh, sw, sh, sw, sh - band, 0, sh - band},
                {0, 0, 0, sh, band, sh, band, 0}, {sw, 0, sw, sh, sw - band, sh, sw - band, 0}};
        for (int[] q : edges) {
            org.lwjgl.opengl.GL11.glColor4f(0.86F, 0.93F, 1F, a);
            org.lwjgl.opengl.GL11.glVertex2f(q[0], q[1]);
            org.lwjgl.opengl.GL11.glVertex2f(q[2], q[3]);
            org.lwjgl.opengl.GL11.glColor4f(0.86F, 0.93F, 1F, 0F);
            org.lwjgl.opengl.GL11.glVertex2f(q[4], q[5]);
            org.lwjgl.opengl.GL11.glVertex2f(q[6], q[7]);
        }
        org.lwjgl.opengl.GL11.glEnd();
        org.lwjgl.opengl.GL11.glShadeModel(org.lwjgl.opengl.GL11.GL_FLAT);
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
        org.lwjgl.opengl.GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /**
     * The drill panel (bottom right): «БУР: ВКЛ · Туннель 3x3», the head's wear bar and blocks left («вечная» for
     * Exo), blocks dug, the argon field.
     */
    /** @return the drill panel's top */
    private int drawDrill(FontRenderer fr, int sw, int sh, EntityVehicleSVA v) {
        int pw = 168;
        // no head: where it goes - «Нет головки бура - L или Shift+ПКМ по машине: слот слева», wrapped to the panel
        java.util.List<?> hint = v.hasHead() ? java.util.Collections.emptyList()
                : fr.listFormattedStringToWidth(Lang.tr("sva.hud.drill.noHead", com.sva.proxy.ClientProxySVA.cargoKeyName()), pw - 12);
        int ph = (3 + Math.max(1, hint.size())) * 11 + 8;
        int px = sw - pw - 4, py = sh - ph - bottomGap(sw, px, px + pw);
        Gui.drawRect(px - 1, py - 1, px + pw + 1, py + ph + 1, EDGE);
        Gui.drawRect(px, py, px + pw, py + ph, BG);
        int ry = py + 5;
        boolean on = v.isDrillOn();
        String mode = Lang.tr("sva.drill.mode." + v.getDrillMode()) + " " + (v.hasWidener() ? "5x5" : "3x3");
        fit(fr, Lang.tr(on ? "sva.hud.drill.on" : "sva.hud.drill.off", mode), px + 6, ry, pw - 12, on ? GREEN : DIM, true);
        ry += 11;
        int left = v.getHeadLeft(), max = v.getHeadMax();
        if (!v.hasHead()) {
            for (Object line : hint) {
                fr.drawString(String.valueOf(line), px + 6, ry, WARN);
                ry += 11;
            }
            ry -= 11;
        } else if (left < 0 || max <= 0) {
            fit(fr, Lang.tr("sva.hud.drill.headForever"), px + 6, ry, pw - 12, GREEN, false);
        } else {
            double frac = left / (double) max;
            wideBar(fr, px, ry, Lang.tr("sva.hud.drill.headShort"), frac, frac < 0.15 ? 0xFF4040 : 0xC0C4CC, pw);
        }
        ry += 11;
        fit(fr, Lang.tr("sva.hud.drill.dug", fmt(v.getDug())), px + 6, ry, pw - 12, TEXT, false);
        ry += 11;
        fit(fr, Lang.tr(v.isFieldOn() ? "sva.hud.field.on" : "sva.hud.field.off"), px + 6, ry, pw - 12, v.isFieldOn() ? 0xB27CFF : DIM, false);
        return py;
    }

    /**
     * The gap under a bottom panel spanning x0..x1: 4 px, or 40 px (above the hotbar, health and food) when the panel
     * reaches the vanilla bars in the middle (182 px wide) - on narrow screens (scaled width under ~520).
     */
    private static java.lang.reflect.Field chatLines;
    private static boolean chatLinesTried;

    /**
     * The top of the chat's visible messages (scaled px), 0 when it shows none. Vanilla draws the chat from height - 48
     * upward, 9 px a line: unfocused only the lines younger than 200 ticks, at most chatHeightUnfocused's lines; with
     * the chat open all of them up to chatHeightFocused. The line list is private (GuiNewChat.drawnChatLines).
     */
    private static int chatTop(Minecraft mc, int sh) {
        net.minecraft.client.gui.GuiNewChat chat = mc.ingameGUI.getChatGUI();
        if (!chatLinesTried) {
            chatLinesTried = true;
            try {
                chatLines = cpw.mods.fml.relauncher.ReflectionHelper.findField(net.minecraft.client.gui.GuiNewChat.class,
                        "field_146253_i", "drawnChatLines");
            } catch (Throwable t) {
                chatLines = null;                               // unknown layout: the panel stays where it is
            }
        }
        if (chatLines == null || mc.gameSettings.chatVisibility == net.minecraft.entity.player.EntityPlayer.EnumChatVisibility.HIDDEN) {
            return 0;
        }
        java.util.List<?> lines;
        try {
            lines = (java.util.List<?>) chatLines.get(chat);
        } catch (Throwable t) {
            return 0;
        }
        boolean open = chat.getChatOpen();
        int max = chat.func_146232_i();                         // lines that fit (focused / unfocused height)
        int now = mc.ingameGUI.getUpdateCounter(), n = 0;
        for (int i = 0; i < lines.size() && n < max; i++) {
            net.minecraft.client.gui.ChatLine l = (net.minecraft.client.gui.ChatLine) lines.get(i);
            if (open || now - l.getUpdatedCounter() < 200) {
                n++;
            } else {
                break;                                          // newest first: the rest are older
            }
        }
        if (n == 0) {
            return 0;
        }
        float scale = chat.func_146244_h();                     // the chat scale option
        return sh - 48 - (int) Math.ceil(n * 9 * scale) - 2;
    }

    private static int bottomGap(int sw, int x0, int x1) {
        int hl = sw / 2 - 91 - 2, hr = sw / 2 + 91 + 2;
        return x1 + 1 > hl && x0 - 1 < hr ? 40 : 4;
    }

    /** A panel line in at most maxW px: scaled down to 3/4, then cut with "..." (no tooltips in the HUD). */
    private static void fit(FontRenderer fr, String s, int x, int y, int maxW, int color, boolean shadow) {
        int w = fr.getStringWidth(s);
        if (w <= maxW) {
            fr.drawString(s, x, y, color, shadow);
            return;
        }
        float k = Math.max(0.75F, maxW / (float) w);
        if (w * 3 > maxW * 4) {                                     // too wide even at 3/4 (int test: no float round-off cut)
            s = fr.trimStringToWidth(s, Math.max(0, (int) (maxW / k) - fr.getStringWidth("..."))) + "...";
        }
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y + (1 - k) * fr.FONT_HEIGHT / 2F, 0);
        GL11.glScalef(k, k, 1F);
        fr.drawString(s, 0, 0, color, shadow);
        GL11.glPopMatrix();
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
    private static String gasWarning(EntityVehicleSVA v, VehicleGases gs, double liftRate, boolean h2) {
        Gas lift = h2 ? null : gs.liftGas;                          // hydrogen wave: the cushion runs on the battery
        if (lift != null) {
            int mb = v.getGas(lift);
            if (mb <= 0) {
                return "!" + Lang.tr("sva.hud.warn.noLift." + lift.key());
            }
            if (HoverMath.secondsLeft(mb, Math.max(liftRate, gs.liftPerSecond)) < LOW_SECONDS || mb < v.getGasCapacity(lift) / 10) {
                return Lang.tr("sva.hud.warn.low." + lift.key());
            }
        }
        Gas c = gs.coolant;
        if (c != null && c != lift) {
            int mb = v.getGas(c);
            if (mb <= 0 && v.getHeat() > VehicleMath.HE_START) {
                return Lang.tr("sva.hud.warn.noCoolant." + c.key());
            }
            if (mb < v.getGasCapacity(c) / 10) {
                return Lang.tr("sva.hud.warn.low." + c.key());
            }
        }
        return null;
    }

    /** "Low" when the lift gas lasts under this many seconds. */
    private static final int LOW_SECONDS = 120;

    /**
     * ★15: one HUD / cockpit line for a hitched tanker («Цистерна: Водород 45%»), car platform («Платформа: Багги N 07»
     * / «пусто») or mobile charger («Зарядка: 62%, заряжает 1»); null without one (or with the cargo trailer).
     */
    public static String trailerLine(EntityVehicleSVA v) {
        if (!v.hasTrailer()) {
            return null;
        }
        int k = v.trailerKind();
        if (k == com.sva.vehicle.TrailerMath.TANK) {
            int a = v.tankAmount();
            return a <= 0 ? Lang.tr("sva.hud.trailer.tankEmpty") : Lang.tr("sva.hud.trailer.tank", com.sva.item.ItemTrailerSVA.fluidName(v.tankFluid()),
                    Integer.valueOf((int) Math.floor(com.sva.vehicle.TrailerMath.tankFill(a) * 100)));
        }
        if (k == com.sva.vehicle.TrailerMath.PLATFORM) {
            int[] c = v.carried();
            return c[0] < 0 ? Lang.tr("sva.hud.trailer.platformEmpty") : Lang.tr("sva.hud.trailer.platform",
                    Lang.tr("item.siliconagevehicles.vehicle." + VehicleType.byOrdinal(c[0]).key + ".name"), com.sva.vehicle.SkinMath.boardText(c[1]));
        }
        if (k == com.sva.vehicle.TrailerMath.CHARGER) {
            return Lang.tr("sva.hud.trailer.charger", Integer.valueOf((int) Math.floor(v.chargerEnergy() * 100 / com.sva.vehicle.TrailerMath.CHARGER_BUFFER)),
                    Integer.valueOf(v.chargerServed().size()));
        }
        return null;
    }

    /**
     * Hydrogen wave (mock-ups veh_fuel_2_refuel.png, veh_fuel_4_display_wear.png): the H2 bar (amber under 15 %, red
     * under 5 %), «расход N мБ/мин, запас M мин / K бл.» («--» while the flow jumps - parked / the first seconds), the
     * battery line: «АКБ», a drawn arrow (up charging / down discharging - no font glyph), %, the net EU/t, «генератор»
     * or «ОБСЛУЖИТЕ АКБ (здоровье 76%)»; «НЕТ АКБ» red. @return the next row's y
     */
    private int drawFuel(FontRenderer fr, int x, int ry, EntityVehicleSVA v, long now) {
        int mb = v.h2Mb(), cap = Math.max(1, v.h2Max());
        int lamp = com.sva.vehicle.FuelMath.lamp(mb, cap);
        boolean blink = (now / 5) % 2 == 0;                         // РЕЗЕРВ: 2 Hz
        int c = lamp == com.sva.vehicle.FuelMath.LAMP_OK ? 0xFF5EC8F0 : lamp == com.sva.vehicle.FuelMath.LAMP_LOW ? 0xFFFFC23A
                : blink ? 0xFFFF4636 : 0xFF802018;
        bar(fr, x, ry, Lang.tr("sva.gas.hydrogen"), mb / (double) cap, c, (int) Math.floor(mb * 100.0 / cap) + "%");
        ry += 10;
        double flow = v.h2FlowPerMin(), min = v.h2RangeMinutes(), blocks = v.h2RangeBlocks();
        String left = Double.isInfinite(min) ? "--" : Lang.tr("sva.hud.h2.left", Long.valueOf(Math.round(min)), fmt(Math.round(blocks)));
        fit(fr, Lang.tr("sva.hud.h2.flow", fmt(Math.round(flow)), left), x + 6, ry, W - 12, DIM, false);
        ry += 10;
        fr.drawString(Lang.tr("sva.hud.bat"), x + 6, ry, DIM);
        int tx = x + 8 + fr.getStringWidth(Lang.tr("sva.hud.bat"));
        if (!v.batteryPresent()) {
            fit(fr, Lang.tr("sva.hud.bat.none"), tx + 2, ry, W - 14 - (tx - x), 0xFF5A4A, false);
            return ry + 10;
        }
        int trend = v.batteryTrend();
        if (trend != com.sva.vehicle.BatteryMath.FLAT_TREND) {
            arrow(tx, ry, trend > 0, trend > 0 ? 0xFF5EE07A : 0xFFFFC23A);
        }
        tx += 8;
        String pct = Math.round(v.batteryFrac() * 100) + "% ";
        String tail;
        int tc = TEXT;
        if (v.batteryNeedsService()) {
            tail = Lang.tr("sva.hud.bat.service", Integer.valueOf((int) Math.round(v.batteryHealth() * 100)));
            tc = AMBER;
        } else {
            double net = v.batteryNetPerTick();
            tail = signed(net) + " " + Lang.tr("sva.hud.bat.euT") + (v.generatorPerTick() > 0 ? " " + Lang.tr("sva.hud.bat.gen") : "");
        }
        if (v.batteryFrac() < com.sva.vehicle.BatteryMath.LOW) {
            tc = WARN;
        }
        int sp = v.batteryServiceProgress(), jp = v.jumpProgress();
        if (sp >= 0) {
            tail = Lang.tr("sva.hud.bat.kit", Integer.valueOf(sp));
            tc = 0x5EC8F0;
        } else if (jp >= 0) {
            tail = Lang.tr("sva.hud.bat.jump", Integer.valueOf(jp));
            tc = 0x5EC8F0;
        }
        fit(fr, pct + tail, tx, ry, W - 6 - (tx - x), tc, false);
        return ry + 10;
    }

    /** A small filled triangle (5 x 4 px) at (x, y + 2): pointing up or down - the HUD's / menu's arrow, drawn, not a glyph. */
    public static void arrow(int x, int y, boolean up, int argb) {
        for (int r = 0; r < 4; r++) {
            int half = up ? r : 3 - r;
            Gui.drawRect(x + 3 - half, y + 2 + r, x + 4 + half, y + 3 + r, argb);
        }
    }

    /** "+38" / "-120" / "0" (EU/t, whole numbers; small values one decimal: "+0,4"). */
    public static String signed(double v) {
        double a = Math.abs(v);
        String n = a >= 10 || a < 0.05 ? String.valueOf(Math.round(a)) : String.format(java.util.Locale.ROOT, "%.1f", a).replace('.', ',');
        return (v > 0.05 ? "+" : v < -0.05 ? "-" : "") + n;
    }

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
        return com.sva.FormatSVA.group(n);
    }
}
