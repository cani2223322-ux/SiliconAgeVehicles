package com.sva.debug;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;

import com.sva.block.BlockRoadSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.net.NetSVA;
import com.sva.vehicle.CameraMath;
import com.sva.vehicle.CockpitLamps;
import com.sva.vehicle.CruiseMath;
import com.sva.vehicle.FlightMath;
import com.sva.vehicle.Movement;
import com.sva.vehicle.PlaneMath;
import com.sva.vehicle.PlaneParams;
import com.sva.vehicle.RoadMath;
import com.sva.vehicle.SoundMath;
import com.sva.vehicle.TrafficLightMath;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleType;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;

/**
 * Wave 1 self-tests (sounds ★1, camera ★3, cruise / autopilot ★4, roads ★11) - pure logic, run by SelfTestSVA on the
 * dedicated server ([SVA-TEST] lines, the same counters).
 */
final class SelfTestWave1SVA {

    private SelfTestWave1SVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws Exception {
        cruise();
        autopilot();
        camera();
        roads();
        sounds();
        soundFiles();
        trafficLight();
        content();
    }

    // ---------------------------------------------------------------- ★4 cruise

    private static void cruise() {
        VehicleType b = VehicleType.BUGGY;
        double set = CruiseMath.fromKmh(30);
        double v = 0;
        double worst = 0;
        for (int i = 0; i < 400; i++) {
            v = CruiseMath.groundStep(v, set, b.accel, 0.006, true);
            if (i > 100) {
                worst = Math.max(worst, Math.abs(v - set));
            }
        }
        check(worst < 1e-9 && Math.abs(VehicleMath.kmh(v) - 30) <= 0, "cruise: the buggy reaches 30 km/h and holds it exactly (" + VehicleMath.kmh(v) + ")");
        // downhill overspeed coasts back; no thrust - it rolls down
        double fast = CruiseMath.groundStep(set + 0.1, set, b.accel, 0.006, true);
        double coast = CruiseMath.groundStep(set, set, b.accel, 0.006, false);
        check(fast < set + 0.1 && fast >= set && coast < set, "cruise: overspeed rolls back to the set speed, without thrust it rolls down");
        // the wheel: +-5 km/h on the grid, MIN..top
        double s35 = CruiseMath.adjust(set, 1, b.topSpeed), s25 = CruiseMath.adjust(set, -1, b.topSpeed);
        double top = CruiseMath.adjust(set, 100, b.topSpeed), low = CruiseMath.adjust(set, -100, b.topSpeed);
        check(VehicleMath.kmh(s35) == 35 && VehicleMath.kmh(s25) == 25 && Math.abs(top - b.topSpeed) < 1e-9 && VehicleMath.kmh(low) == 5,
                "cruise: the wheel steps 5 km/h (35 / 25), never past the top speed or under 5 km/h");
        check(CruiseMath.engageSpeed(0, b.topSpeed) == CruiseMath.fromKmh(5) && CruiseMath.engageSpeed(9, b.topSpeed) == b.topSpeed,
                "cruise: switched on at the current speed, at least 5 km/h, at most the top speed");
        check(CruiseMath.cruiseCancelled(true, false) && CruiseMath.cruiseCancelled(false, true) && !CruiseMath.cruiseCancelled(false, false),
                "cruise: S or Space cancels it, W only overrides");
        check(CruiseMath.kind(Movement.GROUND, false) == CruiseMath.CRUISE && CruiseMath.kind(Movement.CRAWLER, false) == CruiseMath.CRUISE
                        && CruiseMath.kind(Movement.HOVER, false) == CruiseMath.CRUISE && CruiseMath.kind(Movement.PLANE, false) == CruiseMath.AUTOPILOT
                        && CruiseMath.kind(Movement.TILT, true) == CruiseMath.AUTOPILOT && CruiseMath.kind(Movement.TILT, false) == CruiseMath.NONE
                        && CruiseMath.kind(Movement.HELI, false) == CruiseMath.NONE,
                "cruise: ground / tracks / hover cruise, planes and the tiltrotor in airplane mode autopilot, helicopters nothing");
        check(EntityVehicleSVA.IN_CRUISE == 256 && (EntityVehicleSVA.IN_MASK & EntityVehicleSVA.IN_CRUISE) != 0
                        && (EntityVehicleSVA.IN_MASK & EntityVehicleSVA.IN_AFTER) != 0,
                "cruise: IN_CRUISE is bit 9 of the input, kept by IN_MASK with the old bits");
        // the Input packet: 6 bytes without cruise, + 3 floats with it, round trip
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        new NetSVA.Input(7, EntityVehicleSVA.IN_FWD).toBytes(buf);
        int plain = buf.readableBytes();
        buf.clear();
        new NetSVA.Input(7, EntityVehicleSVA.IN_CRUISE | EntityVehicleSVA.IN_LEFT, 0.25F, -91.5F, 120F).toBytes(buf);
        NetSVA.Input back = new NetSVA.Input();
        int full = buf.readableBytes();
        back.fromBytes(buf);
        check(plain == 6 && full == 23 && back.entity == 7 && back.flags == (EntityVehicleSVA.IN_CRUISE | EntityVehicleSVA.IN_LEFT)
                        && back.cruise == 0.25F && back.yaw == -91.5F && back.alt == 120F,
                "cruise: the Input packet carries 9 key bits and, only with IN_CRUISE, the targets + stage C's modes byte and vertical speed ("
                        + plain + " / " + full + " bytes)");
    }

    // ---------------------------------------------------------------- ★4 autopilot

    /** A plane in the air under the autopilot from a 40 deg / 12 block offset: {alt error, yaw error, min speed, max |vy|}. */
    private static double[] fly(VehicleType t, double thr, double v0, int ticks) {
        PlaneParams p = t.plane;
        double y = 100, vy = 0, yaw = 0, speed = v0, minSpeed = v0, maxVy = 0;
        double alt = 112, target = 40;
        for (int i = 0; i < ticks; i++) {
            double climb = PlaneMath.climbCapacity(p, speed) * FlightMath.ceilingFactor(y);
            vy = CruiseMath.holdVy(vy, alt, y, climb, p.dive, PlaneMath.VACC);
            speed += PlaneMath.airAccel(t, speed, thr, vy, vy > 0, true);
            speed = VehicleMath.clamp(speed, 0, PlaneMath.maxSpeed(t, 1));
            yaw = CruiseMath.headingStep(yaw, target, t.turnRate * VehicleMath.clamp(speed / p.stall, 0, 1));
            y += vy;
            minSpeed = Math.min(minSpeed, speed);
            maxVy = Math.max(maxVy, Math.abs(vy));
        }
        return new double[] {Math.abs(alt - y), Math.abs(CruiseMath.wrap(target - yaw)), minSpeed, maxVy};
    }

    private static void autopilot() {
        double[] pl = fly(VehicleType.PLANE, 0.8, 0.9, 600);
        check(pl[0] < 0.3 && pl[1] < 0.5 && !PlaneMath.stalled(VehicleType.PLANE.plane, pl[2]),
                "autopilot: the light plane turns 40 deg and climbs 12 blocks onto the held heading / height (err " + fmt(pl[0]) + " bl, "
                        + fmt(pl[1]) + " deg), never stalling (min " + fmt(pl[2]) + " b/t)");
        double[] jet = fly(VehicleType.JET, 0.7, 1.4, 600);
        check(jet[0] < 0.3 && jet[1] < 0.5 && !PlaneMath.stalled(VehicleType.JET.plane, jet[2]),
                "autopilot: the jet holds heading and height the same way (err " + fmt(jet[0]) + " bl, " + fmt(jet[1]) + " deg)");
        check(pl[3] <= VehicleType.PLANE.plane.climb + 1e-9 && CruiseMath.wantedVy(100, 100.1, 0.2, 0.3) == 0
                        && CruiseMath.wantedVy(200, 100, 0.2, 0.3) == 0.2 && CruiseMath.wantedVy(0, 100, 0.2, 0.3) == -0.3,
                "autopilot: smooth - the vertical speed within the climb the airspeed allows / the dive, no hunting inside 0.15 block");
        double y0 = CruiseMath.headingStep(0, 170, 2), y1 = CruiseMath.headingStep(0, -170, 2), y2 = CruiseMath.headingStep(350, 10, 2);
        check(y0 == 2 && y1 == -2 && Math.abs(y2 - 352) < 1e-9 && Math.abs(CruiseMath.headingStep(0, 1, 2) - 0.12) < 1e-9,
                "autopilot: the heading hold turns the short way, at most the turn rate, gently near the heading");
        check(CruiseMath.autopilotCancel(true, 1, false, true, true) == CruiseMath.OFF_KEYS
                        && CruiseMath.autopilotCancel(false, 0.04, false, true, true) == CruiseMath.OFF_CHARGE
                        && CruiseMath.autopilotCancel(false, 1, true, true, true) == CruiseMath.OFF_STALL
                        && CruiseMath.autopilotCancel(false, 1, false, false, true) == CruiseMath.OFF_GROUND
                        && CruiseMath.autopilotCancel(false, 1, false, true, false) == CruiseMath.OFF_THRUST
                        && CruiseMath.autopilotCancel(false, 0.05, false, true, true) == 0,
                "autopilot: any W/S/A/D, charge under 5 %, a stall, the ground or no thrust switch it off");
        check(CruiseMath.heading(180) == 0 && CruiseMath.heading(-90) == 90 && CruiseMath.heading(0) == 180 && CruiseMath.heading(90) == 270,
                "autopilot: the HUD heading is a compass (north 0, east 90) from the Minecraft yaw");
    }

    private static String fmt(double d) {
        return String.format("%.3f", Double.valueOf(d));
    }

    // ---------------------------------------------------------------- ★3 camera

    private static void camera() {
        double buggy = CameraMath.base(VehicleType.BUGGY.halfLength), ship = CameraMath.base(VehicleType.SHIP.halfLength);
        check(buggy > 7.5 && buggy < 9 && ship > 21 && ship < 23, "camera: 1.5 x the length - the buggy " + fmt(buggy) + " blocks, the ship "
                + fmt(ship));
        check(CameraMath.base(0.5) == CameraMath.MIN && CameraMath.base(100) == CameraMath.MAX, "camera: the base distance kept in 6..24");
        boolean all = true;
        for (VehicleType t : VehicleType.values()) {
            double d = CameraMath.base(t.halfLength);
            all &= d >= 6 && d <= 24;
        }
        check(all, "camera: every type's distance is within 6..24 blocks");
        double z = 1;
        for (int i = 0; i < 40; i++) {
            z = CameraMath.wheel(z, 1);
        }
        double zMax = z;
        for (int i = 0; i < 80; i++) {
            z = CameraMath.wheel(z, -1);
        }
        check(zMax == CameraMath.ZOOM_MAX && z == CameraMath.ZOOM_MIN && Math.abs(CameraMath.wheel(1, 1) - 1.1) < 1e-9,
                "camera: the wheel zooms x1.1 a notch within 0.5x..2x");
        check(Math.abs(CameraMath.distance(VehicleType.BUGGY.halfLength, 2, 1) - buggy * 2) < 1e-9
                        && CameraMath.distance(VehicleType.SHIP.halfLength, 2, 4) == CameraMath.ABS_MAX
                        && CameraMath.distance(0.1, 0.5, 0.25) == CameraMath.ABS_MIN
                        && CameraMath.distance(VehicleType.BUGGY.halfLength, 1, Double.NaN) == buggy,
                "camera: zoom x config on top, kept in 3..48, a NaN config counts as 1");
        check(CameraMath.pitchOffset(0, 20) == 20 && CameraMath.pitchOffset(80, 20) == 9 && CameraMath.pitchOffset(89.5F, 20) == 0,
                "camera: the back view looks 20 deg steeper, never past the vanilla +90 clamp");
        check(Math.abs(CameraMath.follow(0.3, 0.3) - (1 - Math.exp(-1))) < 1e-9 && CameraMath.follow(0, 0.3) == 0 && CameraMath.follow(0.05, 0) == 1,
                "camera: the turn follow closes 63 % of the gap in 0.3 s (the lag)");
    }

    // ---------------------------------------------------------------- ★11 roads

    private static void roads() {
        check(RoadMath.speedMul(RoadMath.ASPHALT, Movement.GROUND) == 1.3 && RoadMath.speedMul(RoadMath.ASPHALT, Movement.CRAWLER) == 1.3
                        && RoadMath.speedMul(RoadMath.CONCRETE, Movement.GROUND) == 1.2 && RoadMath.speedMul(RoadMath.NONE, Movement.GROUND) == 1
                        && RoadMath.speedMul(RoadMath.ASPHALT, Movement.HOVER) == 1 && RoadMath.speedMul(RoadMath.ASPHALT, Movement.PLANE) == 1,
                "roads: asphalt +30 %, concrete +20 % for wheels and tracks only (hover / aircraft x1)");
        check(RoadMath.wearMul(RoadMath.ASPHALT, Movement.GROUND) == 0.5 && RoadMath.wearMul(RoadMath.CONCRETE, Movement.CRAWLER) == 0.75
                        && RoadMath.wearMul(RoadMath.NONE, Movement.GROUND) == 1 && RoadMath.wearMul(RoadMath.ASPHALT, Movement.HELI) == 1,
                "roads: hull wear x0.5 on asphalt, x0.75 on concrete");
        // placement: lines along the look, the edge on the right, the arrow ahead
        check(BlockRoadSVA.placed(BlockRoadSVA.DASH, 0) == 2 && BlockRoadSVA.placed(BlockRoadSVA.DASH, 1) == 3
                        && BlockRoadSVA.placed(BlockRoadSVA.SOLID, 3) == 5 && BlockRoadSVA.placed(BlockRoadSVA.EDGE, 2) == BlockRoadSVA.EDGE + 1
                        && BlockRoadSVA.placed(BlockRoadSVA.EDGE, 0) == BlockRoadSVA.EDGE + 3 && BlockRoadSVA.placed(BlockRoadSVA.ARROW, 2) == BlockRoadSVA.ARROW
                        && BlockRoadSVA.placed(BlockRoadSVA.ARROW, 3) == BlockRoadSVA.ARROW + 1 && BlockRoadSVA.placed(BlockRoadSVA.ASPHALT, 3) == 0,
                "roads: markings turn by the look (looking north: the edge line east, the arrow north)");
        boolean back = true;
        for (int m = 0; m < BlockRoadSVA.METAS; m++) {
            int v = BlockRoadSVA.variant(m);
            back &= java.util.Arrays.binarySearch(BlockRoadSVA.VARIANTS, v) >= 0 && BlockRoadSVA.variant(BlockRoadSVA.placed(v, m & 3)) == v;
            back &= BlockRoadSVA.surface(m) == (m == BlockRoadSVA.CONCRETE ? RoadMath.CONCRETE : RoadMath.ASPHALT);
        }
        check(back, "roads: every placed meta drops its item variant and is asphalt but the concrete slab");
        check(com.sva.block.BlockGuardRailSVA.VEHICLE_HEIGHT > VehicleType.TRUCK.stepHeight + 0.5
                        && com.sva.block.BlockGuardRailSVA.VEHICLE_HEIGHT > VehicleType.DRILL.stepHeight + 0.5,
                "roads: the guard rail is taller for vehicles than any step (tracks climb " + VehicleType.TRUCK.stepHeight + ")");
    }

    // ---------------------------------------------------------------- ★1 sounds

    private static void sounds() {
        boolean ranges = true, layers = true;
        for (VehicleType t : VehicleType.values()) {
            int[] ls = SoundMath.layers(t);
            layers &= ls.length > 0;
            for (int l : ls) {
                layers &= l >= 0 && l < SoundMath.FILES.length;
                for (int k = 0; k < 64; k++) {
                    SoundMath.Input in = new SoundMath.Input();
                    in.engine = (k & 1) != 0;
                    in.boost = (k & 2) != 0;
                    in.drilling = (k & 4) != 0;
                    in.speedFrac = (k >> 3) * 0.3;
                    in.kmh = (k >> 3) * 60;
                    in.throttle = (k >> 3) * 0.4;
                    in.rotor = (k >> 3) * 25;
                    for (boolean inside : new boolean[] {false, true}) {
                        float[] m = SoundMath.finalMix(l, t, in, inside, 2.0);
                        ranges &= m[0] >= 0 && m[0] <= 1 && m[1] >= 0.5F && m[1] <= 2F;
                    }
                }
            }
        }
        check(layers && ranges, "sounds: every type has layers; volume 0..1 and pitch 0.5..2 over speed / throttle / rotor / boost / inside");
        SoundMath.Input slow = new SoundMath.Input(), fast = new SoundMath.Input();
        slow.engine = fast.engine = true;
        slow.speedFrac = 0.1;
        fast.speedFrac = 1.0;
        float[] a = SoundMath.mix(SoundMath.ELECTRIC, VehicleType.BUGGY, slow), b = SoundMath.mix(SoundMath.ELECTRIC, VehicleType.BUGGY, fast);
        float[] truck = SoundMath.mix(SoundMath.ELECTRIC, VehicleType.TRUCK, fast);
        check(b[1] > a[1] && b[0] > a[0] && truck[1] < b[1], "sounds: the motor goes higher and louder with speed; the crawler hums lower ("
                + fmt(a[1]) + " -> " + fmt(b[1]) + ", truck " + fmt(truck[1]) + ")");
        SoundMath.Input off = new SoundMath.Input();
        off.speedFrac = 0.5;
        check(SoundMath.mix(SoundMath.ELECTRIC, VehicleType.BUGGY, off)[0] == 0 && SoundMath.mix(SoundMath.TRACKS, VehicleType.TRUCK, off)[0] > 0,
                "sounds: a motor that is off is silent, rolling tracks still clank");
        float[] out = SoundMath.finalMix(SoundMath.JET, VehicleType.JET, fast, false, 1), in = SoundMath.finalMix(SoundMath.JET, VehicleType.JET, fast, true, 1);
        check(in[0] < out[0] && in[1] < out[1] && "jet_in".equals(SoundMath.file(SoundMath.JET, true)) && "wind".equals(SoundMath.file(SoundMath.WIND, true))
                        && SoundMath.closed(VehicleType.JET) && !SoundMath.closed(VehicleType.BUGGY),
                "sounds: from a closed cabin quieter, lower, the muffled _in file (open buggy / hoverbike: outside sound)");
        SoundMath.Input slowRotor = new SoundMath.Input(), fullRotor = new SoundMath.Input();
        slowRotor.rotor = 10;
        fullRotor.rotor = 42;
        fullRotor.engine = true;
        check(SoundMath.mix(SoundMath.BLADES, VehicleType.HELI, fullRotor)[1] > SoundMath.mix(SoundMath.BLADES, VehicleType.HELI, slowRotor)[1]
                        && SoundMath.mix(SoundMath.TURBINE, VehicleType.HELI, slowRotor)[0] == 0
                        && SoundMath.mix(SoundMath.AFTER, VehicleType.JET, fast)[0] == 0,
                "sounds: blade slap follows the rotor rate; autorotation - no turbine; the afterburner roars only on boost");
        check(SoundMath.finalMix(SoundMath.HOVER, VehicleType.HOVER, fast, false, 0)[0] == 0 && Math.abs(SoundMath.rangeVolume() * 16 - 48) < 1e-6,
                "sounds: vehicles.soundVolume 0 mutes; loops are started to be heard 48 blocks away");
    }

    /** sounds.json lists every layer (+ its _in) and one-shot, and every file it names is in the jar as an OGG. */
    private static void soundFiles() throws Exception {
        InputStream in = SelfTestWave1SVA.class.getResourceAsStream("/assets/siliconagevehicles/sounds.json");
        check(in != null, "sound files: sounds.json is in the jar");
        if (in == null) {
            return;
        }
        com.google.gson.JsonObject js;
        try {
            js = new com.google.gson.JsonParser().parse(new InputStreamReader(in, "UTF-8")).getAsJsonObject();
        } finally {
            in.close();
        }
        boolean listed = true;
        StringBuilder missing = new StringBuilder();
        for (int l = 0; l < SoundMath.FILES.length; l++) {
            for (boolean inside : new boolean[] {false, true}) {
                String f = SoundMath.file(l, inside);
                if (!js.has(f)) {
                    listed = false;
                    missing.append(' ').append(f);
                }
            }
        }
        for (String s : SoundMath.ONE_SHOTS) {
            if (!js.has(s)) {
                listed = false;
                missing.append(' ').append(s);
            }
        }
        check(listed, "sound files: sounds.json has every layer, its _in and every one-shot" + missing);
        boolean files = true;
        int n = 0;
        long bytes = 0;
        StringBuilder bad = new StringBuilder();
        for (Map.Entry<String, com.google.gson.JsonElement> e : js.entrySet()) {
            com.google.gson.JsonObject ev = e.getValue().getAsJsonObject();
            files &= "player".equals(ev.get("category").getAsString());
            for (com.google.gson.JsonElement s : ev.getAsJsonArray("sounds")) {
                String name = s.isJsonObject() ? s.getAsJsonObject().get("name").getAsString() : s.getAsString();
                InputStream f = SelfTestWave1SVA.class.getResourceAsStream("/assets/siliconagevehicles/sounds/" + name + ".ogg");
                if (f == null) {
                    files = false;
                    bad.append(' ').append(name);
                    continue;
                }
                try {
                    byte[] head = new byte[4];
                    int r = f.read(head);
                    files &= r == 4 && head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S';
                    bytes += 4 + skipAll(f);
                } finally {
                    f.close();
                }
                n++;
            }
        }
        check(files && n == js.entrySet().size(), "sound files: all " + n + " files named in sounds.json are OGG in the jar, category 'player' ("
                + bytes / 1024 + " KB)" + bad);
    }

    private static long skipAll(InputStream f) throws java.io.IOException {
        long t = 0;
        byte[] b = new byte[4096];
        int r;
        while ((r = f.read(b)) > 0) {
            t += r;
        }
        return t;
    }

    // ---------------------------------------------------------------- ★11 traffic light

    private static void trafficLight() {
        int s = TrafficLightMath.RED;
        s = TrafficLightMath.onPower(s, true);
        boolean amber1 = s == TrafficLightMath.AMBER;
        s = TrafficLightMath.onPower(s, false);                  // flipped back during the amber: still amber
        boolean held = s == TrafficLightMath.AMBER;
        s = TrafficLightMath.amberDone(false);
        boolean red = s == TrafficLightMath.RED;
        s = TrafficLightMath.onPower(TrafficLightMath.RED, true);
        s = TrafficLightMath.amberDone(true);
        boolean green = s == TrafficLightMath.GREEN && TrafficLightMath.onPower(TrafficLightMath.GREEN, true) == TrafficLightMath.GREEN;
        boolean down = TrafficLightMath.onPower(TrafficLightMath.GREEN, false) == TrafficLightMath.AMBER
                && TrafficLightMath.amberDone(false) == TrafficLightMath.RED;
        check(amber1 && held && red && green && down && TrafficLightMath.AMBER_TICKS == 20,
                "traffic light: red -> amber 1 s -> green on power, green -> amber -> red without; a flip during the amber waits for its end");
        check(TrafficLightMath.lit(TrafficLightMath.RED, 0) && !TrafficLightMath.lit(TrafficLightMath.RED, 2) && TrafficLightMath.lit(TrafficLightMath.GREEN, 2),
                "traffic light: exactly the state's lamp is lit");
    }

    // ---------------------------------------------------------------- content: blocks, recipes, cockpit

    private static void content() {
        check(ModContentSVA.road != null && ModContentSVA.curb != null && ModContentSVA.roadSign != null && ModContentSVA.roadSignArrow != null
                && ModContentSVA.trafficLight != null && ModContentSVA.guardRail != null, "roads: the 6 road blocks are registered");
        Item road = Item.getItemFromBlock(ModContentSVA.road);
        java.util.Set<Integer> roadOut = new java.util.HashSet<Integer>();
        java.util.Set<Item> others = new java.util.HashSet<Item>();
        int signCycles = 0;
        for (Object o : CraftingManager.getInstance().getRecipeList()) {
            ItemStack out = ((IRecipe) o).getRecipeOutput();
            if (out == null) {
                continue;
            }
            if (out.getItem() == road) {
                roadOut.add(Integer.valueOf(out.getItemDamage()));
            } else {
                others.add(out.getItem());
            }
            if ((out.getItem() == Item.getItemFromBlock(ModContentSVA.roadSign) || out.getItem() == Item.getItemFromBlock(ModContentSVA.roadSignArrow))
                    && o instanceof net.minecraftforge.oredict.ShapelessOreRecipe && ((IRecipe) o).getRecipeSize() == 1) {
                signCycles++;
            }
        }
        boolean allRoads = true;
        for (int v : BlockRoadSVA.VARIANTS) {
            allRoads &= roadOut.contains(Integer.valueOf(v));
        }
        check(allRoads && others.contains(Item.getItemFromBlock(ModContentSVA.curb)) && others.contains(Item.getItemFromBlock(ModContentSVA.roadSign))
                        && others.contains(Item.getItemFromBlock(ModContentSVA.roadSignArrow)) && others.contains(Item.getItemFromBlock(ModContentSVA.trafficLight))
                        && others.contains(Item.getItemFromBlock(ModContentSVA.guardRail)) && signCycles == 8,
                "roads: crafting-table recipes for every road variant, curb, both sign sets (+8 picture cycles), traffic light, guard rail");
        CockpitLamps.Snapshot s = new CockpitLamps.Snapshot();
        s.powered = true;
        int offState = CockpitLamps.state(CockpitLamps.AUTOP, s);
        s.autopilot = true;
        int onState = CockpitLamps.state(CockpitLamps.AUTOP, s);
        int[] jet = CockpitLamps.table(VehicleType.JET);
        check(offState == CockpitLamps.OFF && onState == CockpitLamps.ON && jet[1] == CockpitLamps.AUTOP
                        && CockpitLamps.colour(CockpitLamps.AUTOP) == CockpitLamps.GREEN && "autop".equals(CockpitLamps.KEYS[CockpitLamps.AUTOP]),
                "cockpit: the jet's 'A/P' button lights green while the autopilot holds");
    }
}
