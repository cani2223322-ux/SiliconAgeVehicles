package com.sva.vehicle;

/**
 * Liner wave 6 (plan-next §7, mock-ups veh_liner_5 «внешние огни», 7 «СВЕТ ВНЕШН. / СВЕТ ВНУТР. / ТАБЛО», 9 the cabin at
 * night, 6 the cockpit at night): the SA-320's lights as pure, server-authoritative logic - which exterior light shines,
 * the landing lights' travel out of their wells, the cabin signs, the emergency lighting and the interior's light levels.
 * LinerSystems ticks it after the electrics; the result goes to every client in the sync string (ext / signs / the
 * landing lights' travel / the reading lights), so other players see the very same lights (client.LinerLightsSVA draws
 * them, client.LinerCockpitSVA the inside).
 * <p>
 * <b>Exterior</b> (sheet 5, overhead group 11): НАВ И ЛОГО 1 / 2 (the nav lights + the logo light on the fin; ВЫКЛ none),
 * МАЯК (the red beacons top and bottom, ~1 Hz), СТРОБ ВКЛ / АВТО / ВЫКЛ (АВТО: with the main gear not compressed - in the
 * air), ПОСАД. Л / П (УБРАНА / ВЫКЛ / ВКЛ: out of the well in LAND_TICKS - shines only fully out and ВКЛ), НОС (ВЫКЛ /
 * РУЛЁЖ / ВЗЛЁТ on the nose gear - only with it down and locked: it goes up with the gear), РУЛЁЖ. БОК. (the runway
 * turn-off lights, also on the nose gear), КРЫЛО (the wing / nacelle inspection light). Everything only with the board
 * powered (the batteries at least; the landing / taxi / turn-off / wing lights need AC - the A320's AC buses).
 * <p>
 * <b>Signs</b> (СИГНАЛ ПРИСТЕГН. / НЕ КУРИТЬ: ВКЛ / АВТО / ВЫКЛ): ВКЛ lit, АВТО lit with the gear not up or the flaps out
 * (the slats / flaps lever off 0), with power; a change of either lights the event E_SIGN (the cabin's chime).
 * <p>
 * <b>Emergency lighting</b> (АВАР. ВЫХОД ВКЛ / ВЗВЕД / ВЫКЛ): ВКЛ lit, ВЗВЕД lit by itself when the normal (AC) power is
 * lost; from their own batteries (EMER_BAT ticks, ~12 min, charged from AC) - the floor path and the EXIT signs.
 * <p>
 * <b>Interior</b>: the cockpit's light map floor from ПЛАФОН (ЯРКО / ТУСКЛО), the flood knobs and the console / floor
 * lights; the cabin's from САЛОН (НОЧЬ dim blue, 1 / 2 / 3) - one step dimmer while the galley is shed (one generator,
 * the A320's cabin lighting reduction); the passengers' reading lights (one bit a passenger seat 2..9, each toggled by its
 * own passenger); the screens' automatic brightness by the ambient light. Loads on the generators (% of one, LOAD_*).
 */
public final class LinerLights {

    // ---------------------------------------------------------------- the exterior lights (sync bits)

    public static final int X_NAV = 1, X_LOGO = 2, X_BEACON = 4, X_STROBE = 8, X_LAND_L = 16, X_LAND_R = 32, X_TAXI = 64, X_TO = 128, X_RTO = 256,
            X_WING = 512;
    /** The signs / emergency lighting lit (sync bits). */
    public static final int S_BELTS = 1, S_NOSMOKE = 2, S_EMER = 4;
    /** The landing lights' travel out of / into the wells (ticks: ~3 s) and the share out at which they shine. */
    public static final int LAND_TICKS = 60;
    public static final double LAND_SHINE = 0.95;
    /**
     * The landing light housing's swing into its well (deg about the rig's hinge at its rear edge - LinerModelSVA's pose): the
     * rig's rest pose (0) is the housing hanging out under the wing (sheet 5: «выпущена»), LAND_DEG folds it up into the wing.
     */
    public static final float LAND_DEG = 80F;

    /** The housing's angle for a travel (0 in the well .. 1 out). */
    public static float landAngle(double travel) {
        return LAND_DEG * (float) (1 - Math.max(0, Math.min(1, travel)));
    }
    /** The nose gear counts as down for its lights under this travel (LinerGear.pos: 0 down .. 1 up). */
    public static final double NOSE_DOWN = 0.05;
    /** The emergency lights' batteries (ticks: 12 min) and their charge a tick of AC (full in ~3 min). */
    public static final int EMER_BAT = 14400, EMER_CHARGE = 4;
    /** Passenger seats (VehicleType.LINER's seat indices 2..9) - one reading light each. */
    public static final int FIRST_PAX = 2, PAX = 8;

    // ---------------------------------------------------------------- the loads (% of one 90 kVA generator)

    /** Landing light (each, out and on), nose taxi / T.O., turn-off, wing, nav + logo, strobes, beacons. */
    public static final int LOAD_LAND = 3, LOAD_NOSE = 1, LOAD_RTO = 1, LOAD_WING = 1, LOAD_NAV = 1, LOAD_STROBE = 1, LOAD_BEACON = 1;
    /** The cabin's lights by САЛОН (НОЧЬ, 1, 2, 3) and the reading lights (all 8). */
    public static final int[] LOAD_CABIN = {1, 2, 4, 6};
    public static final int LOAD_READING = 1;

    // ---------------------------------------------------------------- state (server; the client's mirror from the sync)

    /** The exterior lights shining now (X_*), the signs lit (S_*). */
    public int ext, signs;
    /** The landing lights' travel 0 (in the well) .. 1 (out), left / right. */
    public double landL, landR;
    /** The passengers' reading lights: bit (seat - FIRST_PAX). */
    public int reading;
    /** The emergency lights' batteries (ticks left). */
    public double emerBat;
    /** The signs' state last tick (the chime on a change); -1 before the first tick. */
    private int signsBefore = -1;

    /**
     * One server tick: power - the board powered (the batteries' bus at least), ac - AC on board, ground - the main gear
     * compressed, gearPos - LinerGear.pos (0 down .. 1 up), flapsLever - the slats / flaps lever's setting. @return the sign
     * changed (the chime)
     */
    public boolean tick(LinerSystems L, boolean power, boolean ac, boolean ground, double gearPos, int flapsLever) {
        // the landing lights: out of the wells unless УБРАНА (needs AC to move; without it they stay where they are)
        int sl = L.at(LinerControls.LAND_L), sr = L.at(LinerControls.LAND_R);
        if (ac) {
            landL = travel(landL, sl != 2);
            landR = travel(landR, sr != 2);
        }
        ext = exterior(L, power, ac, ground, gearPos, landL, landR);
        // the signs, the emergency lighting and its batteries
        int s = signs(L, power, gearPos, flapsLever);
        boolean emer = emergency(L.at(LinerControls.EMER_EXIT), power, ac, emerBat);
        if (ac) {
            emerBat = Math.min(EMER_BAT, emerBat + EMER_CHARGE);
        } else if (emer) {
            emerBat = Math.max(0, emerBat - 1);
        }
        signs = s | (emer ? S_EMER : 0);
        boolean chime = signsBefore >= 0 && (s & (S_BELTS | S_NOSMOKE)) != (signsBefore & (S_BELTS | S_NOSMOKE));
        signsBefore = s;
        return chime;
    }

    /** A landing light's travel one tick toward out (true) / in. */
    public static double travel(double pos, boolean out) {
        double step = 1.0 / LAND_TICKS;
        return out ? Math.min(1, pos + step) : Math.max(0, pos - step);
    }

    /** The exterior lights shining (X_*) for these switches and this state. */
    public static int exterior(LinerSystems L, boolean power, boolean ac, boolean ground, double gearPos, double landL, double landR) {
        if (!power) {
            return 0;
        }
        int x = 0;
        int nav = L.at(LinerControls.NAV_LOGO);
        if (nav != 1) {
            x |= X_NAV | X_LOGO;                                    // 1 and 2 (the A320's two systems): both the nav and the logo lights
        }
        if (L.at(LinerControls.BEACON) == 0) {
            x |= X_BEACON;
        }
        if (strobe(L.at(LinerControls.STROBE), ground)) {
            x |= X_STROBE;
        }
        if (!ac) {
            return x;                                               // the lamps below are on the AC buses
        }
        if (L.at(LinerControls.LAND_L) == 0 && landL >= LAND_SHINE) {
            x |= X_LAND_L;
        }
        if (L.at(LinerControls.LAND_R) == 0 && landR >= LAND_SHINE) {
            x |= X_LAND_R;
        }
        boolean noseDown = gearPos <= NOSE_DOWN;
        int nose = L.at(LinerControls.NOSE_LT);
        if (noseDown && nose == 0) {
            x |= X_TO;
        } else if (noseDown && nose == 1) {
            x |= X_TAXI;
        }
        if (noseDown && L.at(LinerControls.RWY_TURN) == 0) {
            x |= X_RTO;
        }
        if (L.at(LinerControls.WING_LT) == 0) {
            x |= X_WING;
        }
        return x;
    }

    /** СТРОБ: ВКЛ (0) always, АВТО (1) with the main gear not compressed, ВЫКЛ (2) never. */
    public static boolean strobe(int sw, boolean ground) {
        return sw == 0 || sw == 1 && !ground;
    }

    /** A sign's switch (ВКЛ 0 / АВТО 1 / ВЫКЛ 2): АВТО lit with the gear not up or the flaps lever off 0. */
    public static boolean sign(int sw, double gearPos, int flapsLever) {
        return sw == 0 || sw == 1 && (gearPos < 1 - 1e-6 || flapsLever > 0);
    }

    /** The signs lit (S_BELTS / S_NOSMOKE) with power. */
    public static int signs(LinerSystems L, boolean power, double gearPos, int flapsLever) {
        if (!power) {
            return 0;
        }
        return (sign(L.at(LinerControls.SEAT_BELTS), gearPos, flapsLever) ? S_BELTS : 0)
                | (sign(L.at(LinerControls.NO_SMOKING), gearPos, flapsLever) ? S_NOSMOKE : 0);
    }

    /**
     * АВАР. ВЫХОД: ВКЛ (0) lit (from the board, else the batteries), ВЗВЕД (1) lit when the normal power (AC) is lost and
     * the batteries hold charge, ВЫКЛ (2) dark.
     */
    public static boolean emergency(int sw, boolean power, boolean ac, double bat) {
        if (sw == 0) {
            return power || bat > 0;
        }
        return sw == 1 && !ac && bat > 0;
    }

    // ---------------------------------------------------------------- the reading lights

    /** A passenger's reading light toggled (seat index; the pilots' seats 0 / 1 have none). @return it changed */
    public boolean toggleReading(int seat) {
        int i = seat - FIRST_PAX;
        if (i < 0 || i >= PAX) {
            return false;
        }
        reading ^= 1 << i;
        return true;
    }

    public boolean readingOn(int seat) {
        int i = seat - FIRST_PAX;
        return i >= 0 && i < PAX && (reading & 1 << i) != 0;
    }

    // ---------------------------------------------------------------- the interior's light

    /** The light map's top (the game's 0..240 block light scale). */
    public static final int LIT = 240;

    /**
     * The cockpit's light (0..240, the light map's floor - the world's own light comes on top): ПЛАФОН ЯРКО 240 / ТУСКЛО 170,
     * the flood knobs (the brighter one, 0..4: 0 / 100..220), the console / floor lights (ЯРКО 130 / ТУСКЛО 90); none
     * without power. At night with all off the cockpit is as dark as the night outside.
     */
    public static int cockpitLight(LinerSystems L, boolean power) {
        if (!power) {
            return 0;
        }
        int dome = L.at(LinerControls.DOME);
        int k = dome == 0 ? LIT : dome == 1 ? 170 : 0;
        int flood = Math.max(L.at(LinerControls.FLOOD_L), L.at(LinerControls.FLOOD_R));
        if (flood > 0) {
            k = Math.max(k, 60 + 40 * flood);
        }
        for (LinerControls.Ctl f : new LinerControls.Ctl[] {LinerControls.FLOOR_L, LinerControls.FLOOR_R}) {
            int p = L.at(f);
            k = Math.max(k, p == 0 ? 130 : p == 1 ? 90 : 0);
        }
        return k;
    }

    /** The cabin's lighting step now: САЛОН (0 НОЧЬ .. 3), one step dimmer with the galley shed; -1 dark (no AC). */
    public static int cabinStep(LinerSystems L, boolean ac, boolean shed) {
        if (!ac) {
            return -1;
        }
        int lv = L.at(LinerControls.CABIN_LT);
        return shed && lv > 1 ? lv - 1 : lv;
    }

    /** The cabin's light map floor for a step (НОЧЬ 70 - with the cove lights blue, 1 150, 2 195, 3 240; dark 0). */
    public static int cabinLight(int step) {
        return step < 0 ? 0 : step == 0 ? 70 : step == 1 ? 150 : step == 2 ? 195 : LIT;
    }

    /** The DUs' automatic brightness by the ambient light: full in the day, 75 % at night (dark 0 .. 1 night). */
    public static float screenAuto(float dark) {
        return 1F - 0.25F * Math.max(0, Math.min(1, dark));
    }

    // ---------------------------------------------------------------- loads

    /** The lights' share of the generators' load (%), from the lights shining and the cabin step. */
    public static int load(int ext, int cabinStep, int reading) {
        int l = 0;
        l += (ext & X_LAND_L) != 0 ? LOAD_LAND : 0;
        l += (ext & X_LAND_R) != 0 ? LOAD_LAND : 0;
        l += (ext & (X_TAXI | X_TO)) != 0 ? LOAD_NOSE : 0;
        l += (ext & X_RTO) != 0 ? LOAD_RTO : 0;
        l += (ext & X_WING) != 0 ? LOAD_WING : 0;
        l += (ext & X_NAV) != 0 ? LOAD_NAV : 0;
        l += (ext & X_STROBE) != 0 ? LOAD_STROBE : 0;
        l += (ext & X_BEACON) != 0 ? LOAD_BEACON : 0;
        l += cabinStep >= 0 ? LOAD_CABIN[Math.min(3, cabinStep)] : 0;
        l += reading != 0 ? LOAD_READING : 0;
        return l;
    }

    // ---------------------------------------------------------------- the lamps' places (model units, measured from liner_lod0.obj)

    /** Kinds of a lamp (the renderer's colour / flashing). */
    public static final int K_NAV_R = 0, K_NAV_G = 1, K_NAV_W = 2, K_LOGO = 3, K_BEACON = 4, K_STROBE = 5, K_LAND = 6, K_TAXI = 7, K_TO = 8,
            K_RTO = 9, K_WING = 10;

    /** One lamp: kind, side (-1 left / 0 / 1 right), centre (x, y, z; landing lights: in the well), the X_* bit lighting it. */
    public static final class Lamp {
        public final int kind, side, bit;
        public final float x, y, z;

        Lamp(int kind, int side, int bit, float x, float y, float z) {
            this.kind = kind;
            this.side = side;
            this.bit = bit;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /**
     * The lamps (the emissive spheres' centres in liner_lod0.obj; the nose gear's taxi / turn-off lamps in the nlg group at
     * the gear down): nav red / green on the wingtips, white aft on them, the logo lights on the stabiliser's roots, the
     * beacons top / bottom, the strobes on the tips and the tail cone, the landing lights under the wing roots, the nose
     * lights, the wing lights on the fuselage over the wing.
     */
    public static final Lamp[] LAMPS = {
            new Lamp(K_NAV_R, -1, X_NAV, -1.90F, 1.98F, -8.35F), new Lamp(K_NAV_G, 1, X_NAV, -1.90F, 1.98F, 8.35F),
            new Lamp(K_NAV_W, -1, X_NAV, -2.68F, 1.98F, -8.34F), new Lamp(K_NAV_W, 1, X_NAV, -2.68F, 1.98F, 8.34F),
            new Lamp(K_LOGO, -1, X_LOGO, -7.17F, 2.85F, -1.00F), new Lamp(K_LOGO, 1, X_LOGO, -7.17F, 2.85F, 1.00F),
            new Lamp(K_BEACON, 0, X_BEACON, -0.20F, 3.30F, 0F), new Lamp(K_BEACON, 0, X_BEACON, -1.60F, 0.90F, 0F),
            new Lamp(K_STROBE, -1, X_STROBE, -2.01F, 1.96F, -8.36F), new Lamp(K_STROBE, 1, X_STROBE, -2.01F, 1.96F, 8.36F),
            new Lamp(K_STROBE, 0, X_STROBE, -9.20F, 2.92F, 0F),
            new Lamp(K_LAND, -1, X_LAND_L, 0.54F, 1.19F, -1.62F), new Lamp(K_LAND, 1, X_LAND_R, 0.54F, 1.19F, 1.62F),
            new Lamp(K_TAXI, 0, X_TAXI, 6.69F, 0.78F, 0F), new Lamp(K_TO, 0, X_TO, 6.69F, 0.78F, 0F),
            new Lamp(K_RTO, -1, X_RTO, 6.65F, 0.92F, -0.15F), new Lamp(K_RTO, 1, X_RTO, 6.65F, 0.92F, 0.15F),
            new Lamp(K_WING, -1, X_WING, 3.15F, 1.93F, -1.19F), new Lamp(K_WING, 1, X_WING, 3.15F, 1.93F, 1.19F)};

    /** A lamp's colour (0xRRGGBB). */
    public static int colour(int kind) {
        switch (kind) {
            case K_NAV_R:
                return 0xFF2818;
            case K_NAV_G:
                return 0x28FF5A;
            case K_BEACON:
                return 0xFF2414;
            case K_STROBE:
            case K_NAV_W:
                return 0xF4F8FF;
            default:
                return 0xFFF4DC;                                    // the halogen lamps' warm white
        }
    }

    /**
     * A beamed lamp's beam: {direction x, y, z (unit, model frame), length (blocks), end radius (blocks), strength 0..1} -
     * the landing lights forward and 6 deg down, the take-off light forward 4 deg down (the taxi light 10 deg down, dimmer
     * and shorter), the turn-off lights 55 deg out to their side and 8 deg down, the wing lights out over the wing (aft,
     * out, down) to the slats and the nacelle; null - no beam.
     */
    public static float[] beam(Lamp l) {
        switch (l.kind) {
            case K_LAND:
                return dir(-6, 0, 30F, 4.0F, 1F);
            case K_TO:
                return dir(-4, 0, 26F, 3.4F, 0.9F);
            case K_TAXI:
                return dir(-10, 0, 14F, 2.6F, 0.55F);
            case K_RTO:
                return dir(-8, 55 * l.side, 14F, 3.0F, 0.6F);
            case K_WING:
                return new float[] {-0.40F, -0.25F, 0.88F * l.side, 7F, 1.6F, 0.4F};
            default:
                return null;
        }
    }

    /** A direction `down` deg below the horizon, `out` deg to the right of forward (+x), and the beam's numbers. */
    static float[] dir(float down, float out, float len, float r, float k) {
        double d = Math.toRadians(down), o = Math.toRadians(out);
        return new float[] {(float) (Math.cos(d) * Math.cos(o)), (float) Math.sin(d), (float) (Math.cos(d) * Math.sin(o)), len, r, k};
    }

    /** Is this lamp lit for the synced bits (the flashing lamps: the bit only - the renderer flashes them). */
    public static boolean lit(Lamp l, int ext) {
        return (ext & l.bit) != 0;
    }

    /** The beacon's flash (0..1) at a tick: a rotating red beam, the bottom one half a turn later; ~1 Hz. */
    public static float beaconAt(double t, boolean bottom) {
        double a = (t + (bottom ? 10 : 0)) * Math.PI * 2 / 20.0;
        double c = Math.max(0, Math.cos(a));
        return (float) Math.pow(c, 6);
    }

    /** The strobe's double flash: two 1-tick flashes 3 ticks apart every 20 ticks (the A320's white double flash). */
    public static boolean strobeAt(long t, int phase) {
        long k = ((t + phase) % 20 + 20) % 20;
        return k == 0 || k == 3;
    }

    // ---------------------------------------------------------------- sync / save

    /** The sync words: ext | signs << 10 | reading << 13, the landing lights' travel (%) L and R. */
    public int[] pack() {
        return new int[] {ext | signs << 10 | reading << 13, (int) Math.round(landL * 100), (int) Math.round(landR * 100)};
    }

    public void unpack(int[] w, int o) {
        ext = w[o] & 1023;
        signs = w[o] >> 10 & 7;
        reading = w[o] >> 13 & 255;
        landL = w[o + 1] / 100.0;
        landR = w[o + 2] / 100.0;
    }

    /** Words in pack(). */
    public static final int WORDS = 3;

    public void write(net.minecraft.nbt.NBTTagCompound t) {
        t.setInteger("Reading", reading);
        t.setInteger("EmerBat", (int) Math.round(emerBat));
        t.setInteger("LandL", (int) Math.round(landL * 1000));
        t.setInteger("LandR", (int) Math.round(landR * 1000));
    }

    public void read(net.minecraft.nbt.NBTTagCompound t) {
        reading = t.getInteger("Reading") & 255;
        emerBat = Math.max(0, Math.min(EMER_BAT, t.getInteger("EmerBat")));
        landL = Math.max(0, Math.min(1, t.getInteger("LandL") / 1000.0));
        landR = Math.max(0, Math.min(1, t.getInteger("LandR") / 1000.0));
    }
}
