package com.sva.client;

import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.SystemsMath;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Cockpit wave (plan-next §5 / §6): the one place the C+ cockpits' glass (client/GlassCockpitSVA, the lamp strips, the
 * overhead panel) read the board's hydrogen, battery, radar warning and transponder from. The hydrogen / removable
 * battery wave adds the real accessors to EntityVehicleSVA (H2 mB / max / flow / range; battery pct / volts / amps /
 * health / present) - the lead rewires the methods marked STAND-IN below to them after the merge; nothing else in the
 * cockpit needs to change. PLACEHOLDER marks what has no game side at all yet (the radar warning, the transponder).
 */
@SideOnly(Side.CLIENT)
public final class CockpitDataSVA {

    /** PLACEHOLDER: the transponder's code until it has a game side - the VFR squawk 7000 (octal). */
    public static final int XPDR_DEFAULT = 07000;

    private CockpitDataSVA() {
    }

    // ---------------------------------------------------------------- hydrogen

    /** STAND-IN (hydrogen wave): H2 left 0..1 - the main energy buffer stands in for the tank. */
    public static float h2Frac(EntityVehicleSVA v) {
        return (float) Math.max(0, Math.min(1, v.getEnergy() / Math.max(1.0, v.getMaxEnergy())));
    }

    /** STAND-IN (hydrogen wave): H2 left in % (0..100). */
    public static int h2Pct(EntityVehicleSVA v) {
        return (int) Math.floor(h2Frac(v) * 100);
    }

    /**
     * STAND-IN (hydrogen wave: the flow and the range): minutes of flight left at the cruise draw (the HUD's range
     * estimate's cost per tick), -1 when the cruise draw is unknown (parked, a type without one).
     */
    public static int h2Minutes(EntityVehicleSVA v) {
        float cost = v.getCruiseCost();
        if (!(cost > 0)) {
            return -1;
        }
        return (int) Math.min(999, v.getEnergy() / cost / 20 / 60);
    }

    // ---------------------------------------------------------------- the battery

    /** STAND-IN (removable battery wave): a battery is fitted - always, until the battery can be taken out. */
    public static boolean batPresent(EntityVehicleSVA v) {
        return true;
    }

    /**
     * STAND-IN (removable battery wave): the battery 0..100 % - the board systems' battery (SystemsMath: the light
     * plane, the jet, the tiltrotor; synced); the helicopter has none yet: full while the board has power.
     */
    public static int batPct(EntityVehicleSVA v) {
        VehicleType t = v.getVehicleType();
        if (SystemsMath.applies(t)) {
            return v.batteryPct();
        }
        return v.powered() ? 100 : 0;
    }

    /** STAND-IN (removable battery wave): the battery's volts - a 24 V battery, 22.0 flat .. 28.0 full on its charge. */
    public static float batVolts(EntityVehicleSVA v) {
        return batPresent(v) ? 22F + 6F * batPct(v) / 100F : 0F;
    }

    /**
     * STAND-IN (removable battery wave): the battery's amps, + charging / - discharging: ГЕН on and cells with charge -
     * +4 A while it is not full (0 when full); ГЕН off (or the cells flat) - the board draws it: -4 A while powered.
     */
    public static float batAmps(EntityVehicleSVA v) {
        if (!genOn(v)) {
            return v.powered() ? -4F : 0F;
        }
        return batPct(v) < 100 ? 4F : 0F;
    }

    /** STAND-IN (removable battery wave): the generator charges the board - ГЕН on (the board systems) and the cells not flat. */
    public static boolean genOn(EntityVehicleSVA v) {
        VehicleType t = v.getVehicleType();
        boolean sw = !SystemsMath.applies(t) || v.switchOn(SystemsMath.SW_GEN);
        return sw && v.getEnergy() > 0;
    }

    // ---------------------------------------------------------------- radar warning / transponder

    /**
     * PLACEHOLDER (air defence wave): the radar threats painting the vehicle, each {bearing deg relative to the nose
     * (clockwise), distance blocks, kind 0 search / 1 track / 2 missile}; none yet - the scope shows only its rings.
     */
    public static float[][] rwrThreat(EntityVehicleSVA v) {
        return new float[0][];
    }

    /** PLACEHOLDER (air defence wave): the transponder's code (octal), 7000 until it has a game side. */
    public static int xpdrCode(EntityVehicleSVA v) {
        return XPDR_DEFAULT;
    }

    /** PLACEHOLDER (air defence wave): the transponder's mode, a lang key - «ОЖИД» (standby) until it has a game side. */
    public static String xpdrMode(EntityVehicleSVA v) {
        return "sva.xpdr.mode.stby";
    }

    /** PLACEHOLDER (air defence wave): the transponder replied to an interrogation just now (the ОТВЕТ lamp) - never yet. */
    public static boolean xpdrReply(EntityVehicleSVA v) {
        return false;
    }
}
