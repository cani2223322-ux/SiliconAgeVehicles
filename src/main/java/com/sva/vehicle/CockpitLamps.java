package com.sva.vehicle;

/**
 * Cockpit stage 2 (★11 reactive lamps, startup sequence; ★8 night lighting; mock-ups veh_cab_1_car.png,
 * veh_cab_3_plane.png, veh_cab_4_trim_night.png, veh_cab_5_controls.png): WHAT each warning lamp of a cockpit means, its
 * colour and label, when it is lit, the boarding sequence's timing and the night glow. Pure logic (the self-test runs it
 * on the dedicated server); the client (client/CockpitInstrumentsSVA) fills a Snapshot from the vehicle and draws.
 * <p>
 * The lamps are CockpitLayout.lamps in its order (rows top -> bottom, each row left -> right as the pilot sees it); a
 * type's table has exactly one role per lamp of its model (self-test). Measured from the OBJs (2026-10): buggy / truck
 * the row of 6 under the cluster + the red stop button on the centre console; drill the 3 mode lamps on the drill console
 * (a row above) + the row of 6; heli (cockpit wave, C+) the co-pilot stick's top and the console's lamp - its annunciators
 * are the lamp strip now (strip(): the plane's 13, the heli's 9, drawn on the 'lampbar' face); jet the
 * small lamp over the glass panels, two buttons, the big disc under the left side screen (mirror of the throttle dial);
 * ship the two stick tops + the stop button; tilt the left stick top, the stop button, the right stick top (one row);
 * shuttle 5 overhead buttons + the two stick tops; the plane has no 'warn' lamps (C+: its gear lamps went to the MFD's ДВИГ
 * page - gear() colours them there); the hoverbike has none.
 * <p>
 * Cockpit visibility fix (2026-10): the jet's side screens turned to the pilot lifted their buttons over the big disc,
 * which went under the left screen (it sat behind it, unseen) - the disc (STALL) is now last; the tilt's sticks got
 * shorter (they stood in front of the pods), their tops are now level with the stop button - one row, left -> right.
 */
public final class CockpitLamps {

    // roles
    /** A button / decoration: unlit (lit only in the lamp test). */
    public static final int DECOR = 0;
    /** The old master caution: red, blinks on any warning (stall, autorotation, overheating, low / no charge). */
    public static final int MASTER = 1;
    public static final int TURN_L = 2, TURN_R = 3, HEAD = 4, HANDBRAKE = 5, OVERHEAT = 6, WEAR = 7;
    public static final int AUTOROT = 8, LOW_CHARGE = 9, GEN = 10, NAV = 11, VORTEX = 12, CARGO = 13, DOOR = 14;
    /** The drill console's mode lamps: tunnel / ore only / levelling (DrillMath MODE_*). */
    public static final int MODE0 = 15, MODE1 = 16, MODE2 = 17;
    public static final int STALL = 18;
    /** Wave 1 (★4): «АВТОП.» - green while the autopilot or cruise holds (the jet's first button lights up). */
    public static final int AUTOP = 19;
    /**
     * Aircraft stage C (★18, mock-up veh_air2_5_cockpit.png): the annunciators beside the autopilot panel - «ЗАКР. ВЫП»
     * (green: the flaps out; blinking while they travel), «ПРЕДКР.» (blue: the slats out), «ВОЗД. ТОРМ» (amber: the
     * airbrake / spoilers up), «V>120 ЗАКР» (red, blinking: the flaps' overspeed - the next setting's limit), and STALL.
     */
    public static final int FLAPS_EXT = 20, SLATS = 21, AIRBRAKE = 22, FLAP_LIMIT = 23;
    /**
     * Aircraft round 2, D2 (★7, mock-up veh_air3_2_start_fail.png): the failure annunciators - «ОТКАЗ ДВИГ.» (red), «ЗАКР.
     * ЗАКЛИН.» (amber), «ПРИБОР» (amber: a dead instrument or the iced pitot), «ВНИМАНИЕ» (the master caution, red,
     * blinking while any is on). The light plane's on its overhead panel, the jet's / tiltrotor's as screen lines.
     */
    public static final int ENG_FAIL = 24, FLAP_JAM = 25, INSTR_FAIL = 26, CAUTION = 27;
    /**
     * Hydrogen wave (mock-ups veh_fuel_2_refuel.png, veh_fuel_4_display_wear.png): «МАЛО ТОПЛИВА» (amber, under 15 % of
     * the H2 tank - FuelMath.LOW), «РЕЗЕРВ» (red, blinking, under 5 % - FuelMath.RESERVE), «АКБ» (amber: no battery in or
     * it is under 10 % - lit even with the board dark, it is wired straight to the battery), «ОБСЛУЖИТЕ АКБ» (amber: the
     * battery's health under 80 %). In EU mode the C+ strips (strip()) still show them: the charge stands in for the fuel
     * (fuelLeft) and the board battery 0..1 (Snapshot.battery) for the removable one - under BAT_LOW, blinking under BAT_FLAT.
     */
    public static final int LOW_FUEL = 28, RESERVE = 29, BAT = 30, BAT_SERVICE = 31;
    /**
     * Cockpit wave (plan-next §5 / §6, the C+ cockpits' lamp strips - strip(); with the hydrogen wave's four above):
     * «ОБОРОТЫ ВИНТА» (red, blinking: a rotorcraft in the air with the rotor outside ROTOR_LO..ROTOR_HI %), «ПЕРЕГРУЗ»
     * (red, blinking: the power over 100 %), «ГЕН» (amber: the generator off), «ЗЕМЛЯ» (amber: in the air within
     * GROUND_AGL blocks of the ground), «ОБЛЕДЕН.» (blue: the iced pitot / airframe).
     */
    public static final int ROTOR_RPM = 32, OVERLOAD = 33, GEN_OFF = 34, GROUND = 35, ICING = 36;
    /** Lang key part per role: sva.cockpit.lamp.KEY (the label under the lamp; "" = no label). */
    public static final String[] KEYS = {"", "master", "left", "right", "head", "handbrake", "overheat", "wear", "autorot", "lowCharge",
            "gen", "nav", "vortex", "cargo", "door", "mode0", "mode1", "mode2", "stall", "autop", "flapsExt", "slats", "airbrake", "flapLimit",
            "engFail", "flapJam", "instrFail", "caution", "lowFuel", "reserve", "bat", "batService", "rotorRpm", "overload", "genOff", "ground",
            "icing"};
    /** Cockpit wave: the strips' thresholds - the EU-mode board battery fractions, rotor %, blocks over the ground. */
    public static final float BAT_LOW = 0.2F, BAT_FLAT = 0.05F, ROTOR_LO = 90F, ROTOR_HI = 110F, GROUND_AGL = 6F;

    // colours (0xRRGGBB, mock-up veh_cab_5_controls.png)
    public static final int RED = 0xFF4636, AMBER = 0xFFC23A, GREEN = 0x46E07A, BLUE = 0x6AD4FF, ORANGE = 0xFF8A2A;

    /** Lamp states. */
    public static final int OFF = 0, ON = 1, BLINK = 2;

    /** Turn lamps blink while the wheels are turned this far (deg, AnimMath steer; the driver's A / D). */
    public static final float TURN_DEG = 4F;
    /** Blinking: on for half of this period (ms) - turn signals, warnings. */
    public static final long BLINK_MS = 500;
    /** Parked: slower than this (blocks / tick) on the ground - the handbrake lamp. */
    public static final float PARK_SPEED = 0.005F;
    /** Lamps' share of the master warning (the HUD's thresholds). */
    public static final float LOW_CHARGE_FRAC = 0.1F, HOT = 80F, WORN_HP = 0.25F;
    /**
     * Vortex ring state ("ВИХРЬ"): a rotorcraft sinking faster than VRS_SINK blocks / tick (5 blocks/s) while moving
     * forward slower than VRS_SPEED blocks / tick (3 blocks/s, ~11 km/h) - settling into its own downwash, as a real
     * helicopter does past ~300 ft/min of descent at low airspeed. In the air only.
     */
    public static final float VRS_SINK = 0.25F, VRS_SPEED = 0.15F;

    private CockpitLamps() {
    }

    /** Lamp roles in CockpitLayout.lamps order (see the class comment); an empty table for a type without lamps. */
    public static int[] table(VehicleType t) {
        switch (t) {
            case BUGGY:
            case TRUCK:
                return new int[] {TURN_L, HEAD, HANDBRAKE, OVERHEAT, WEAR, TURN_R, DECOR};
            case DRILL:
                return new int[] {MODE0, MODE1, MODE2, TURN_L, HEAD, HANDBRAKE, OVERHEAT, WEAR, TURN_R};
            case HELI:
                // cockpit wave (C+): the co-pilot's stick top, the console's lamp - the annunciators went to the lamp strip
                return new int[] {MASTER, DECOR};
            case JET:
                return new int[] {MASTER, AUTOP, DECOR, STALL};
            case TILT:
                return new int[] {MASTER, DECOR, OVERHEAT};
            case SHUTTLE:
                return new int[] {DECOR, DECOR, DECOR, DECOR, DECOR, LOW_CHARGE, OVERHEAT};
            case SHIP:
                return new int[] {LOW_CHARGE, OVERHEAT, DECOR};
            default:
                return new int[0];                                 // the plane (gear lamps: okLamps), the hoverbike
        }
    }

    /**
     * Stage C: the annunciator column (the light plane's beside its autopilot panel; the jet's / tiltrotor's become lines
     * on their text screen) - top -> bottom; empty for a type without high-lift devices.
     */
    public static int[] annunciators(VehicleType t) {
        HighLiftMath.Spec s = HighLiftMath.spec(t);
        if (s == null) {
            return new int[0];
        }
        if (!s.slats && !s.airbrake) {
            return new int[] {FLAPS_EXT, FLAP_LIMIT, STALL};       // the tiltrotor's flaperons
        }
        return new int[] {FLAPS_EXT, SLATS, AIRBRAKE, FLAP_LIMIT, STALL};
    }

    /**
     * Cockpit wave (plan-next §5 / §6): the lamp strip under the glare shield ('lampbar', client/GlassCockpitSVA), left ->
     * right - the light plane's 13 (failures, fuel, battery, autopilot, the high-lift annunciators: they left the AP bar's
     * column), the helicopter's 9; empty for the other types.
     */
    public static int[] strip(VehicleType t) {
        if (t == VehicleType.PLANE) {
            return new int[] {ENG_FAIL, FLAP_JAM, INSTR_FAIL, CAUTION, LOW_FUEL, RESERVE, BAT, AUTOP, FLAPS_EXT, SLATS, AIRBRAKE, FLAP_LIMIT, STALL};
        }
        if (t == VehicleType.HELI) {
            return new int[] {AUTOROT, VORTEX, ROTOR_RPM, OVERLOAD, LOW_FUEL, BAT, GEN_OFF, GROUND, ICING};
        }
        return new int[0];
    }

    /** D2: the failure annunciators of a type with the board systems (SystemsMath.applies); the flap lamp only with flaps. */
    public static int[] failureLamps(VehicleType t) {
        if (!SystemsMath.applies(t)) {
            return new int[0];
        }
        return HighLiftMath.spec(t) != null ? new int[] {ENG_FAIL, FLAP_JAM, INSTR_FAIL, CAUTION} : new int[] {ENG_FAIL, INSTR_FAIL, CAUTION};
    }

    /** A role's lit colour (0xRRGGBB). */
    public static int colour(int role) {
        switch (role) {
            case TURN_L:
            case TURN_R:
            case GEN:
            case NAV:
            case AUTOP:
            case FLAPS_EXT:
                return GREEN;
            case HEAD:
            case CARGO:
            case SLATS:
            case ICING:
                return BLUE;
            case AIRBRAKE:
            case FLAP_JAM:
            case INSTR_FAIL:
            case LOW_FUEL:
            case BAT:
            case BAT_SERVICE:
                return AMBER;
            case WEAR:
            case AUTOROT:
            case LOW_CHARGE:
            case DOOR:
            case MODE0:
            case MODE1:
            case MODE2:
            case GEN_OFF:
            case GROUND:
                return AMBER;
            default:
                return RED;                                        // master, reserve, handbrake, overheat, vortex, stall, buttons
        }
    }

    /** What the client knows about its vehicle this frame (filled by CockpitInstrumentsSVA; plain fields for the self-test). */
    public static final class Snapshot {
        /** Has charge and is not a wreck. */
        public boolean powered;
        /** Charge 0..1, heat 0..100, hull 0..1 (1 with wear off). */
        public float charge = 1F, heat, hull = 1F;
        public boolean heatStopped;
        /** Steering (deg, + = right; AnimMath steer). */
        public float steer;
        /** Speed over the ground (blocks / tick), vertical speed (blocks / tick, + = up). */
        public float speed, vy;
        public boolean onGround, braking, headlights, navLights;
        public boolean autorotating, stalling, airborne, heliMode;
        public boolean cargoFull;
        public int drillMode;
        /** ★4: the autopilot / cruise engaged (synced: EntityVehicleSVA.isCruiseOn); the auto-delivery counts as autopilot. */
        public boolean autopilot, cruise;
        /** Stage C: the flap setting, the flaps still travelling, the slats out, the airbrake / spoilers up, the overspeed warning. */
        public int flaps;
        public boolean flapsMoving, slatsOut, airbrake, flapWarning;
        /** D2: the failures (SystemsMath.F_*), the pitot iced. */
        public int fails;
        public boolean iced;
        /**
         * Hydrogen wave: on hydrogen, the H2 tank 0..1 (read only on hydrogen - EU mode's fuel lamps read `charge`), a
         * battery in, its charge 0..1 and health 0..1.
         */
        public boolean hydrogen, batPresent = true;
        public float fuel = 1F, batCharge = 1F, batHealth = 1F;
        /**
         * Cockpit wave: the EU-mode board battery 0..1 (client/CockpitDataSVA: the board systems' battery), the generator
         * on, blocks over the ground, rotor rpm % (100 = the powered spin), power % (100 = the limit).
         */
        public float battery = 1F, agl = 99F, rotor = 100F, power;
        public boolean genOn = true;
    }

    /** The overheating warning (the HUD's: over HOT % or stopped). */
    public static boolean overheat(Snapshot s) {
        return s.heatStopped || s.heat > HOT;
    }

    /** Low charge: under LOW_CHARGE_FRAC (empty included). */
    public static boolean lowCharge(Snapshot s) {
        return s.charge < LOW_CHARGE_FRAC;
    }

    /** The vortex ring state rule (VRS_SINK / VRS_SPEED; a rotorcraft in the air). */
    public static boolean vortex(boolean heliMode, boolean airborne, float vy, float speed) {
        return heliMode && airborne && vy < -VRS_SINK && speed < VRS_SPEED;
    }

    /** The master warning (the stage-1 lamps' rule). */
    public static boolean master(Snapshot s) {
        return s.stalling || s.autorotating || overheat(s) || lowCharge(s) || SystemsMath.caution(s.fails, s.iced);
    }

    /** What the fuel lamps read: the H2 tank on hydrogen, the charge in EU mode (the charge is the fuel there). */
    public static float fuelLeft(Snapshot s) {
        return s.hydrogen ? s.fuel : s.charge;
    }

    /** A lamp's state (OFF / ON / BLINK); an unpowered vehicle's lamps are all off. */
    public static int state(int role, Snapshot s) {
        if (role == BAT && s.hydrogen) {
            // wired to the battery itself: lit with the board dark (none in / flat) - EntityVehicleSVA.batteryLamp()
            return !s.batPresent || s.batCharge < BatteryMath.LOW ? ON : OFF;
        }
        if (!s.powered) {
            return OFF;
        }
        switch (role) {
            case MASTER:
                return master(s) ? BLINK : OFF;
            case TURN_L:
                return s.steer < -TURN_DEG ? BLINK : OFF;
            case TURN_R:
                return s.steer > TURN_DEG ? BLINK : OFF;
            case HEAD:
                return s.headlights ? ON : OFF;
            case HANDBRAKE:
                return s.braking || s.onGround && s.speed < PARK_SPEED ? ON : OFF;
            case OVERHEAT:
                return overheat(s) ? BLINK : OFF;
            case WEAR:
                return s.hull < WORN_HP ? ON : OFF;
            case AUTOROT:
                return s.autorotating ? BLINK : OFF;
            case LOW_CHARGE:
                return lowCharge(s) ? ON : OFF;
            case GEN:
                return ON;                                         // powered (checked above)
            case NAV:
                return s.navLights ? ON : OFF;
            case VORTEX:
                return vortex(s.heliMode, s.airborne, s.vy, s.speed) ? BLINK : OFF;
            case CARGO:
                return s.cargoFull ? ON : OFF;
            case MODE0:
            case MODE1:
            case MODE2:
                return s.drillMode == role - MODE0 ? ON : OFF;     // the selected mode, the drill on or off
            case STALL:
                return s.stalling ? BLINK : OFF;
            case AUTOP:
                return s.autopilot || s.cruise ? ON : OFF;
            case FLAPS_EXT:
                return s.flapsMoving ? BLINK : s.flaps > 0 ? ON : OFF;
            case SLATS:
                return s.slatsOut ? ON : OFF;
            case AIRBRAKE:
                return s.airbrake ? ON : OFF;
            case FLAP_LIMIT:
                return s.flapWarning ? BLINK : OFF;
            case ENG_FAIL:
                return (s.fails & SystemsMath.F_ENGINE) != 0 ? ON : OFF;
            case FLAP_JAM:
                return (s.fails & SystemsMath.F_FLAP) != 0 ? ON : OFF;
            case INSTR_FAIL:
                return (s.fails & SystemsMath.F_INSTR) != 0 || s.iced ? ON : OFF;
            case CAUTION:
                return SystemsMath.caution(s.fails, s.iced) ? BLINK : OFF;
            case LOW_FUEL:
                return fuelLeft(s) < (float) FuelMath.LOW && fuelLeft(s) >= (float) FuelMath.RESERVE ? ON : OFF;
            case RESERVE:
                return fuelLeft(s) < (float) FuelMath.RESERVE ? BLINK : OFF;
            case BAT:
                return s.battery < BAT_FLAT ? BLINK : s.battery < BAT_LOW ? ON : OFF;     // EU mode (hydrogen: above)
            case BAT_SERVICE:
                return s.hydrogen && s.batPresent && BatteryMath.needsService(s.batHealth) ? ON : OFF;
            case ROTOR_RPM:
                return s.heliMode && s.airborne && (s.rotor < ROTOR_LO || s.rotor > ROTOR_HI) ? BLINK : OFF;
            case OVERLOAD:
                return s.power > 100F ? BLINK : OFF;
            case GEN_OFF:
                return s.genOn ? OFF : ON;
            case GROUND:
                return s.airborne && s.agl <= GROUND_AGL ? ON : OFF;
            case ICING:
                return s.iced ? ON : OFF;
            default:
                return OFF;                                        // buttons, the door (never)
        }
    }

    /** Lit now: ON, or BLINK in the first half of its period (ms: any running clock). */
    public static boolean lit(int state, long ms) {
        return state == ON || state == BLINK && (ms % BLINK_MS) < BLINK_MS / 2;
    }

    // ---- the plane's gear lamps (CockpitLayout.okLamps; AnimMath's gear)

    /** Gear lamp colour: green = down and locked, red = moving, 0 = off (up). gear 0 down .. 1 up, gearUp = its target. */
    public static int gear(float gear, boolean gearUp) {
        if (gear <= 0.001F && !gearUp) {
            return GREEN;
        }
        if (gear >= 0.999F && gearUp) {
            return 0;
        }
        return RED;
    }

    // ---- the startup sequence (veh_cab_5_controls.png: «Запуск машины»)

    /** Phases from the moment the local player boards as the driver (or the vehicle gets power again). */
    public static final int PH_DARK = 0, PH_TEST = 1, PH_LOGO = 2, PH_SWEEP = 3, PH_NORMAL = 4;
    /** Phase ends (s): dark until 0.3, the lamp test until 0.8, the SA logo until 1.2, the needle sweep until 1.8. */
    public static final float T_TEST = 0.3F, T_LOGO = 0.8F, T_SWEEP = 1.2F, T_END = 1.8F;

    /** The phase `sec` seconds after the start (negative / NaN = no sequence running: normal). */
    public static int phase(float sec) {
        if (!(sec >= 0) || sec >= T_END) {
            return PH_NORMAL;
        }
        return sec < T_TEST ? PH_DARK : sec < T_LOGO ? PH_TEST : sec < T_SWEEP ? PH_LOGO : PH_SWEEP;
    }

    /** The needles' sweep 0 -> 1 (full scale) -> 0 across PH_SWEEP; 0 outside it. */
    public static float sweep(float sec) {
        if (!(sec >= T_SWEEP) || sec >= T_END) {
            return 0F;
        }
        float f = (sec - T_SWEEP) / (T_END - T_SWEEP);
        return f < 0.5F ? f * 2F : (1F - f) * 2F;
    }

    // ---- ★8 night lighting

    /** Instruments / plafonds glow in the tier's colour (SkinMath liveries WORK, CIVIL, SILICON, SINGULAR). */
    private static final int[] TIER_GLOW = {0xFFBE3C, 0xFFE6AA, 0x5AD2FF, 0xC878FF};

    /** The night glow colour of a livery (SkinMath.livery; out of range = I's amber). */
    public static int tierGlow(int livery) {
        return livery >= 0 && livery < TIER_GLOW.length ? TIER_GLOW[livery] : TIER_GLOW[0];
    }

    /** Darkness (VehicleLightsSVA.darkness 0..1) under which it is day: the headlights' threshold. */
    public static final float DARK_MIN = 0.05F;
    /** Darkness over which the glow is full. */
    public static final float DARK_FULL = 0.5F;

    /**
     * The night glow 0..1: 0 by day or unpowered, `level` (config vehicles.cabinLightLevel) at full darkness, a ramp in
     * between (dusk), so the lights come on with the headlights.
     */
    public static float night(float dark, boolean powered, float level) {
        if (!powered || !(dark > DARK_MIN) || !(level > 0)) {
            return 0F;
        }
        float k = Math.min(1F, (dark - DARK_MIN) / (DARK_FULL - DARK_MIN));
        return Math.min(1F, level) * k;
    }

    /** Mixes a 0xRRGGBB colour toward `to` by k (0..1). */
    public static int mix(int from, int to, float k) {
        k = Math.max(0F, Math.min(1F, k));
        int r = Math.round((from >> 16 & 255) + ((to >> 16 & 255) - (from >> 16 & 255)) * k);
        int g = Math.round((from >> 8 & 255) + ((to >> 8 & 255) - (from >> 8 & 255)) * k);
        int b = Math.round((from & 255) + ((to & 255) - (from & 255)) * k);
        return r << 16 | g << 8 | b;
    }
}
