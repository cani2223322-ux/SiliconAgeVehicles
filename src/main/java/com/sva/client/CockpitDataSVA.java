package com.sva.client;

import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.CockpitDials;
import com.sva.vehicle.CockpitLamps;
import com.sva.vehicle.FuelMath;
import com.sva.vehicle.SystemsMath;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Cockpit wave (plan-next §5 / §6): the one place the C+ cockpits' glass (client/GlassCockpitSVA, the lamp strips, the
 * overhead panel) read the board's hydrogen, battery, radar warning and transponder from. Merged with the hydrogen /
 * removable battery wave: on hydrogen (EntityVehicleSVA.onHydrogen) the methods read its accessors (H2 mB / max / range,
 * battery frac / volts / amps / present, the generator); in EU mode (vehicles.fuelMode = EU) they keep the cockpit wave's
 * readings - the main charge for the tank, the board systems' battery for the battery. The radar warning and the transponder
 * come from the air-defence wave (AirDefenseClientSVA: the server's copies for the riders).
 */
@SideOnly(Side.CLIENT)
public final class CockpitDataSVA {

    /** The transponder's code before the server has told - 7000 (octal), «flying on my own». */
    public static final int XPDR_DEFAULT = com.sva.airdefense.XpdrMath.VFR;

    private CockpitDataSVA() {
    }

    // ---------------------------------------------------------------- hydrogen

    /** H2 left 0..1: the tank on hydrogen; EU mode - the main energy buffer (the charge is the fuel there). */
    public static float h2Frac(EntityVehicleSVA v) {
        if (v.onHydrogen()) {
            int max = v.h2Max();
            return max <= 0 ? 0F : Math.max(0F, Math.min(1F, v.h2Mb() / (float) max));
        }
        return (float) Math.max(0, Math.min(1, v.getEnergy() / Math.max(1.0, v.getMaxEnergy())));
    }

    /** H2 left in % (0..100). */
    public static int h2Pct(EntityVehicleSVA v) {
        return (int) Math.floor(h2Frac(v) * 100);
    }

    /**
     * Minutes of flight left: on hydrogen the tank at the current flow (parked: at cruise - EntityVehicleSVA.h2RangeMinutes);
     * EU mode the charge at the cruise draw (the HUD's range estimate's cost per tick). -1 when unknown (nothing burns, a
     * type without a cruise draw); 999 at most (the MFD's field).
     */
    public static int h2Minutes(EntityVehicleSVA v) {
        if (v.onHydrogen()) {
            double m = v.h2RangeMinutes();
            return m >= 0 && !Double.isInfinite(m) && !Double.isNaN(m) ? (int) Math.min(999, m) : -1;
        }
        float cost = v.getCruiseCost();
        if (!(cost > 0)) {
            return -1;
        }
        return (int) Math.min(999, v.getEnergy() / cost / 20 / 60);
    }

    /** The fuel lamps (FuelMath.LAMP_*): the tank on hydrogen, the charge in EU mode - CockpitLamps.fuelLeft's rule. */
    public static int fuelLamp(EntityVehicleSVA v) {
        return v.onHydrogen() ? v.fuelLamp() : FuelMath.lamp(h2Pct(v), 100);
    }

    /** The heli overhead's КРАН / НАСОС Н2: hydrogen in the tank (EU mode: charge left - the old reading). */
    public static boolean h2Flowing(EntityVehicleSVA v) {
        return v.onHydrogen() ? v.h2Mb() > 0 : h2Pct(v) > 0;
    }

    // ---------------------------------------------------------------- the battery

    /** A battery is fitted (the removable one on hydrogen; EU mode: always). */
    public static boolean batPresent(EntityVehicleSVA v) {
        return v.batteryPresent();
    }

    /**
     * The battery 0..100 %: on hydrogen the removable battery (0 when out); EU mode - the board systems' battery
     * (SystemsMath: the light plane, the jet, the tiltrotor; synced), the helicopter has none: full while the board has power.
     */
    public static int batPct(EntityVehicleSVA v) {
        if (v.onHydrogen()) {
            return v.batteryPresent() ? (int) Math.floor(v.batteryFrac() * 100) : 0;
        }
        VehicleType t = v.getVehicleType();
        if (SystemsMath.applies(t)) {
            return v.batteryPct();
        }
        return v.powered() ? 100 : 0;
    }

    /** The «АКБ» lamp: EntityVehicleSVA.batteryLamp on hydrogen; EU mode - the board battery under CockpitLamps.BAT_LOW. */
    public static boolean batLamp(EntityVehicleSVA v) {
        return v.onHydrogen() ? v.batteryLamp() : batPct(v) < CockpitLamps.BAT_LOW * 100;
    }

    /** The battery's volts: on hydrogen the voltmeter (BatteryMath.volts); EU mode a 24 V battery, 22.0 flat .. 28.0 full. */
    public static float batVolts(EntityVehicleSVA v) {
        if (v.onHydrogen()) {
            return v.batteryVolts();
        }
        return batPresent(v) ? 22F + 6F * batPct(v) / 100F : 0F;
    }

    /**
     * The battery's amps, + charging / - discharging: on hydrogen the ammeter (its net flow); EU mode - ГЕН on and cells
     * with charge: +4 A while it is not full (0 when full); ГЕН off (or the cells flat) - the board draws it: -4 A while powered.
     */
    public static float batAmps(EntityVehicleSVA v) {
        if (v.onHydrogen()) {
            return v.batteryAmps();
        }
        if (!genOn(v)) {
            return v.powered() ? -4F : 0F;
        }
        return batPct(v) < 100 ? 4F : 0F;
    }

    /** The ammeter's full scale (A): the generator's full output (CockpitDials.max AMPS); EU mode the stand-in's 10 A. */
    public static float ampsScale(EntityVehicleSVA v) {
        return v.onHydrogen() ? Math.max(1F, CockpitDials.max(CockpitDials.AMPS, v.getVehicleType(), 0)) : 10F;
    }

    /**
     * The generator charges the board - ГЕН on (the board systems) and: on hydrogen the generator turning (the engine
     * runs - EntityVehicleSVA.generatorPerTick), EU mode the cells not flat.
     */
    public static boolean genOn(EntityVehicleSVA v) {
        VehicleType t = v.getVehicleType();
        boolean sw = !SystemsMath.applies(t) || v.switchOn(SystemsMath.SW_GEN);
        if (v.onHydrogen()) {
            return sw && v.generatorPerTick() > 0;
        }
        return sw && v.getEnergy() > 0;
    }

    // ---------------------------------------------------------------- radar warning / transponder

    /**
     * Air-defence wave: the radar threats painting the vehicle, each {bearing deg relative to the nose (clockwise), distance
     * blocks, kind RadarMath.T_* (0 search, 1 track lock, 2 missile, 3 height finder)} - the server's copy for the riders
     * (AirDefenseClientSVA); empty without the «Приёмник облучения» upgrade - the scope shows only its rings.
     */
    public static float[][] rwrThreat(EntityVehicleSVA v) {
        return AirDefenseClientSVA.rwrThreats(v);
    }

    /** The transponder's code (octal; 7000 until the server has told). */
    public static int xpdrCode(EntityVehicleSVA v) {
        return AirDefenseClientSVA.xpdrCode(v);
    }

    /** The transponder's mode, a lang key (sva.xpdr.mode.*: ВЫКЛ / ДЕЖ / A / C / S). */
    public static String xpdrMode(EntityVehicleSVA v) {
        int m = AirDefenseClientSVA.xpdrMode(v);
        return "sva.xpdr.mode." + com.sva.airdefense.XpdrMath.MODE_KEYS[Math.max(0, Math.min(com.sva.airdefense.XpdrMath.MODES - 1, m))];
    }

    /** The transponder replied to an interrogation just now (the ОТВЕТ lamp). */
    public static boolean xpdrReply(EntityVehicleSVA v) {
        return AirDefenseClientSVA.xpdrReply(v);
    }

    /** IDENT is flashing (the IDENT button lit). */
    public static boolean xpdrIdent(EntityVehicleSVA v) {
        return AirDefenseClientSVA.xpdrIdent(v);
    }

    /** The КЛЮЧ ДНЯ lamp: the code is today's from a code card. */
    public static boolean xpdrKeyLamp(EntityVehicleSVA v) {
        return AirDefenseClientSVA.xpdrKeyLamp(v);
    }
}
