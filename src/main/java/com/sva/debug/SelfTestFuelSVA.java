package com.sva.debug;

import com.sc.util.ArmorGasSC.Gas;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemBatterySVA;
import com.sva.vehicle.BatteryMath;
import com.sva.vehicle.CockpitDials;
import com.sva.vehicle.CockpitLamps;
import com.sva.vehicle.FuelMath;
import com.sva.vehicle.TuningMath;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Hydrogen wave self-test (pure logic, the dedicated server's -Dsva.selftest): FuelMath (which types, tanks, the burn
 * and its multipliers, the lamps, the range, the electrics' balance), BatteryMath (capacities, tiers, the start rule,
 * wear, the garage / kit services and their limit, volts / amps, the jump start, the item NBT), the VehicleState on
 * hydrogen (the battery is the EU limit, in / out, an old save loads with a full H2 tank and its EU in the battery), the
 * battery item, the new cockpit lamp / dial roles. Runs with FuelMath.legacyTest off (SelfTestSVA sets it for the
 * older suites); its states force the mode themselves.
 */
final class SelfTestFuelSVA {

    private SelfTestFuelSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    private static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }

    static void run() {
        types();
        burn();
        electrics();
        battery();
        services();
        meters();
        stateOnHydrogen();
        legacyLoad();
        item();
        cockpit();
    }

    private static void types() {
        int n = 0;
        for (VehicleType t : VehicleType.values()) {
            n += FuelMath.uses(t) ? 1 : 0;
        }
        check(n == 9 && FuelMath.uses(VehicleType.LINER) && !FuelMath.uses(VehicleType.SHUTTLE) && !FuelMath.uses(VehicleType.SHIP),
                "fuel: 9 types on hydrogen (the liner wave 1's SA-320 too), the shuttle and the Singular Ship stay on EU");
        check(FuelMath.tank(VehicleType.BUGGY) == 4000 && FuelMath.tank(VehicleType.HOVER) == 6000 && FuelMath.tank(VehicleType.TRUCK) == 16000
                && FuelMath.tank(VehicleType.DRILL) == 24000 && FuelMath.tank(VehicleType.HELI) == 12000 && FuelMath.tank(VehicleType.PLANE) == 16000
                && FuelMath.tank(VehicleType.JET) == 32000 && FuelMath.tank(VehicleType.TILT) == 24000, "fuel: the H2 tanks as on the mock-up");
        check(FuelMath.hydrogenMode("HYDROGEN") && FuelMath.hydrogenMode(" hydrogen ") && !FuelMath.hydrogenMode("EU") && !FuelMath.hydrogenMode("eu")
                && FuelMath.MODE_EU.equals(FuelMath.mode("Eu")) && FuelMath.MODE_HYDROGEN.equals(FuelMath.mode("junk")),
                "fuel: vehicles.fuelMode parses HYDROGEN / EU (anything else: HYDROGEN)");
        boolean was = FuelMath.legacyTest;
        FuelMath.legacyTest = true;
        boolean legacy = !FuelMath.on(VehicleType.BUGGY, -1, true) && FuelMath.on(VehicleType.BUGGY, 1, false);
        FuelMath.legacyTest = false;
        check(legacy && FuelMath.on(VehicleType.BUGGY, -1, true) && !FuelMath.on(VehicleType.BUGGY, -1, false) && !FuelMath.on(VehicleType.BUGGY, 0, true)
                && !FuelMath.on(VehicleType.SHIP, 1, true), "fuel: the mode - the server's, a test's force, the legacy suites' EU");
        FuelMath.legacyTest = was;
        java.util.List<Gas> hover = FuelMath.gases(VehicleType.HOVER, true), buggy = FuelMath.gases(VehicleType.BUGGY, true);
        check(hover.get(0) == Gas.HYDROGEN && hover.size() == 2 && buggy.size() == 1 && buggy.get(0) == Gas.HYDROGEN
                && FuelMath.gases(VehicleType.BUGGY, false).isEmpty() && FuelMath.gases(VehicleType.SHIP, true) == VehicleType.SHIP.gases.gases()
                && FuelMath.capacity(VehicleType.HOVER, Gas.HYDROGEN, true) == 6000 && FuelMath.capacity(VehicleType.HOVER, Gas.HYDROGEN, false) == 4000
                && FuelMath.capacity(VehicleType.HELI, Gas.HELIUM, true) == 1000, "fuel: the tank list (H2 first, no duplicate) and sizes by mode");
    }

    private static void burn() {
        check(near(FuelMath.throttleMul(0), FuelMath.IDLE, 1e-9) && near(FuelMath.throttleMul(FuelMath.CRUISE_SHARE), 1, 1e-9)
                && near(FuelMath.throttleMul(1), FuelMath.FULL, 1e-9) && near(FuelMath.throttleMul(1.3), FuelMath.FULL, 1e-9)
                && FuelMath.throttleMul(0.3) > FuelMath.IDLE && FuelMath.throttleMul(0.3) < 1, "fuel burn: idle 15 %, cruise 100 %, full throttle 160 %");
        double fullTrailer = FuelMath.mul(1, 0, true, false, 0, 64, null, false);
        check(near(fullTrailer, 1.6 * 1.3, 1e-9), "fuel burn: the multipliers multiply (full throttle with a trailer = 160 % x 1.3)");
        check(near(FuelMath.mul(FuelMath.CRUISE_SHARE, 1, false, false, 0, 64, null, false), 1.15, 1e-9)
                && near(FuelMath.mul(FuelMath.CRUISE_SHARE, 0, false, true, 0.2, 64, null, false), 1.4, 1e-9)
                && near(FuelMath.mul(FuelMath.CRUISE_SHARE, 0, false, true, 0, 250, null, false), 0.9, 1e-9)
                && near(FuelMath.mul(FuelMath.CRUISE_SHARE, 0, false, false, 0.2, 250, null, false), 1, 1e-9),
                "fuel burn: full cargo +15 %, climbing +40 % and high -10 % (aircraft only)");
        int[] m1 = TuningMath.empty(), m2 = TuningMath.empty();
        m1[0] = TuningMath.MOTOR_I;
        m2[0] = TuningMath.MOTOR_II;
        check(near(FuelMath.mul(FuelMath.CRUISE_SHARE, 0, false, false, 0, 64, m1, false), 1.15, 1e-9)
                && near(FuelMath.mul(FuelMath.CRUISE_SHARE, 0, false, false, 0, 64, m2, false), 1.3, 1e-9)
                && near(FuelMath.mul(FuelMath.CRUISE_SHARE, 0, false, true, 0, 64, null, true), 2, 1e-9), "fuel burn: Мотор+ I / II +15 / +30 %, the tiltrotor vertical x2");
        boolean minutes = true;
        for (VehicleType t : VehicleType.values()) {
            if (FuelMath.uses(t)) {
                double m = FuelMath.tank(t) / (FuelMath.burn(t, 1, 1) * 1200);
                minutes &= near(m, FuelMath.minutes(t), 1e-6);
            }
        }
        check(minutes && near(FuelMath.burn(VehicleType.BUGGY, 1, 2), 2 * FuelMath.cruisePerTick(VehicleType.BUGGY), 1e-12),
                "fuel burn: a full tank lasts the mock-up's minutes at cruise; vehicles.consumption scales it");
        check(FuelMath.lamp(0, 100) == FuelMath.LAMP_EMPTY && FuelMath.lamp(4, 100) == FuelMath.LAMP_RESERVE && FuelMath.lamp(14, 100) == FuelMath.LAMP_LOW
                && FuelMath.lamp(15, 100) == FuelMath.LAMP_OK, "fuel lamps: МАЛО ТОПЛИВА under 15 %, РЕЗЕРВ under 5 %");
        double ema = 0;
        for (int i = 0; i < 2000; i++) {
            ema = FuelMath.flowStep(ema, 0.2);
        }
        double cruise = FuelMath.cruisePerTick(VehicleType.BUGGY) * 1200;
        check(near(ema, 240, 1) && near(FuelMath.minutesLeft(VehicleType.BUGGY, 4000, 0, 1), 4000 / cruise, 1e-6)
                && near(FuelMath.minutesLeft(VehicleType.BUGGY, 4000, 400, 1), 10, 1e-9)
                && near(FuelMath.blocksLeft(VehicleType.BUGGY, 10, 0.5), 6000, 1e-6) && Double.isInfinite(FuelMath.minutesLeft(VehicleType.BUGGY, 10, 0, 0)),
                "fuel range: the flow average (mB/min), minutes by the flow (parked: at cruise) and blocks at the speed");
    }

    private static void electrics() {
        boolean ok = true, hover = true;
        for (VehicleType t : VehicleType.values()) {
            if (FuelMath.uses(t)) {
                double c = BatteryMath.capacity(t);
                ok &= FuelMath.generator(c, 1) > FuelMath.boardLoad(c) * 3;
            }
        }
        double c = BatteryMath.capacity(VehicleType.HOVER);
        hover = FuelMath.generator(c, 1) > FuelMath.boardLoad(c) + FuelMath.cushionLoad(c, false)
                && FuelMath.generator(c, 1) < FuelMath.boardLoad(c) + FuelMath.cushionLoad(c, true);
        check(ok && hover, "fuel electrics: a running engine's generator charges the battery over the board (the hover's cushion too, boost drains it)");
        check(near(FuelMath.starterPerTick(40000, 20) * 20, 1200, 1e-9) && near(FuelMath.generator(6000, 0.5), 0.5, 1e-12),
                "fuel electrics: a start costs 3 % of the battery; the generator follows the warm-up");
    }

    private static void battery() {
        check(BatteryMath.capacity(VehicleType.BUGGY) == 40000 && BatteryMath.capacity(VehicleType.HOVER) == 40000
                && BatteryMath.capacity(VehicleType.TRUCK) == 200000 && BatteryMath.capacity(VehicleType.DRILL) == 200000
                && BatteryMath.capacity(VehicleType.HELI) == 120000 && BatteryMath.capacity(VehicleType.PLANE) == 120000
                && BatteryMath.capacity(VehicleType.JET) == 400000 && BatteryMath.capacity(VehicleType.TILT) == 400000
                && BatteryMath.capacity(VehicleType.SHIP) == 0, "battery: capacities as on the mock-up");
        check(BatteryMath.tier(VehicleType.BUGGY) == com.sc.energy.Tier.MV && BatteryMath.tier(VehicleType.PLANE) == com.sc.energy.Tier.HV
                && BatteryMath.tier(VehicleType.DRILL) == com.sc.energy.Tier.HV && BatteryMath.tier(VehicleType.JET) == com.sc.energy.Tier.EV
                && BatteryMath.tier(VehicleType.HELI).getVoltage() == 512, "battery: tiers MV / HV / EV (HV charges at 512 EU/t)");
        check(BatteryMath.start(false, 1, 1000, 1000) == BatteryMath.NONE && BatteryMath.start(true, 0.19, 1000, 1000) == BatteryMath.DEAD
                && BatteryMath.start(true, 1, 49, 1000) == BatteryMath.FLAT && BatteryMath.start(true, 0.2, 50, 1000) == BatteryMath.OK,
                "battery start: НЕТ АКБ, worn under 20 % - no start, under 5 % charge - АКБ РАЗРЯЖЕН");
        check(BatteryMath.misfire(0.49) == BatteryMath.WEAK_MISFIRE && BatteryMath.misfire(0.5) == 0 && BatteryMath.needsService(0.79)
                && !BatteryMath.needsService(0.8), "battery health: under 50 % starts on the 2nd-3rd try, under 80 % «ОБСЛУЖИТЕ АКБ»");
        check(near(BatteryMath.cycleWear(1, 40000, 40000), 0.9995, 1e-12) && near(BatteryMath.cycleWear(1, 20000, 40000), 0.99975, 1e-12)
                && BatteryMath.cycleWear(1, -5, 40000) == 1, "battery wear: 0.05 % a full charge cycle, in proportion");
        BatteryMath.Battery b = new BatteryMath.Battery();
        b.charge = 12345;
        b.health = 0.76;
        b.limit = 0.9;
        b.services = 2;
        b.cycle = 77;
        b.deep = true;
        NBTTagCompound t = new NBTTagCompound();
        b.write(t);
        BatteryMath.Battery r = new BatteryMath.Battery().read(t, 10000);
        check(r.charge == 10000 && near(r.health, 0.76, 1e-12) && near(r.limit, 0.9, 1e-12) && r.services == 2 && r.deep
                && new BatteryMath.Battery().read(new NBTTagCompound(), 100).health == 1, "battery NBT: round trip (the charge clamped to the capacity)");
    }

    private static void services() {
        double[] g = BatteryMath.garageService(0.76, 0.9);
        double[] k = BatteryMath.kitService(0.61, 0.95);
        double[] kl = BatteryMath.kitService(0.9, 0.9);
        check(near(g[0], 0.9, 1e-12) && near(g[1], 0.85, 1e-12), "battery service (garage): 76 % -> 90 % (the limit), the limit drops to 85 %");
        check(near(k[0], 0.76, 1e-12) && near(k[1], 0.9, 1e-12) && near(kl[0], 0.9, 1e-12) && !BatteryMath.serviceUseful(0.9, 0.9)
                && BatteryMath.serviceUseful(0.6, 0.9), "battery service (kit): +15 % not over the limit, the limit -5 %");
        double lim = 1;
        for (int i = 0; i < 30; i++) {
            lim = BatteryMath.garageService(0.1, lim)[1];
        }
        check(near(lim, BatteryMath.MIN_CAP, 1e-12), "battery service: the limit never goes under " + Math.round(BatteryMath.MIN_CAP * 100) + " %");
    }

    private static void meters() {
        boolean range = true;
        for (double f = 0.01; f <= 1; f += 0.07) {
            for (double net : new double[] {-50, 0, 20}) {
                float v = BatteryMath.volts(true, f, net, 40000);
                range &= v >= BatteryMath.V_MIN && v <= BatteryMath.V_MAX;
            }
        }
        check(range && BatteryMath.volts(false, 1, 0, 40000) == 0 && BatteryMath.volts(true, 1, 5, 40000) > BatteryMath.volts(true, 1, 0, 40000)
                && BatteryMath.volts(true, 0.9, 0, 40000) > BatteryMath.volts(true, 0.2, 0, 40000), "battery voltmeter: 20..32 V, up with the charge and when charging");
        check(BatteryMath.amps(5, 26) > 0 && BatteryMath.amps(-5, 26) < 0 && BatteryMath.amps(5, 0) == 0 && BatteryMath.trend(0.5) == BatteryMath.UP
                && BatteryMath.trend(-0.5) == BatteryMath.DOWN && BatteryMath.trend(0.001) == BatteryMath.FLAT_TREND, "battery ammeter and the HUD's arrow: + charging");
        double per = BatteryMath.jumpPerTick(40000);
        check(near(per * BatteryMath.JUMP_TICKS, 10000, 1e-6) && near(BatteryMath.jumpStep(per, 1e9, 200000, 200000, 1e9), per, 1e-9)
                && near(BatteryMath.jumpStep(per, 1e9, 60010, 200000, 1e9), 10, 1e-9) && BatteryMath.jumpStep(per, 1e9, 50000, 200000, 1e9) == 0
                && near(BatteryMath.jumpStep(per, 3, 200000, 200000, 1e9), 3, 1e-12) && near(BatteryMath.jumpStep(per, 1e9, 200000, 200000, 1), 1, 1e-12),
                "battery jump start: 25 % in 10 s, the donor kept at 30 %, not over the room");
    }

    private static VehicleState h2(VehicleType t) {
        VehicleState s = new VehicleState(t);
        s.fuelForce = 1;
        return s;
    }

    private static void stateOnHydrogen() {
        VehicleState s = h2(VehicleType.BUGGY);
        check(s.batIn && near(s.maxEnergy(), com.sva.ConfigSVA.tuning ? 40000 : 40000, 1e-9) && s.gasCapacity(Gas.HYDROGEN) == 4000
                && s.tanks().size() == 1, "fuel state: a new hydrogen buggy - a battery in (the EU limit 40 000), a 4 000 mB H2 tank");
        s.energy = 30000;
        BatteryMath.Battery out = s.takeBattery();
        boolean taken = out != null && out.charge == 30000 && !s.batIn && s.energy == 0 && s.maxEnergy() == 0 && s.takeBattery() == null;
        out.health = 0.7;
        s.putBattery(out);
        check(taken && s.batIn && s.energy == 30000 && near(s.batHealth, 0.7, 1e-12), "fuel state: the battery out (its charge with it, the limit 0) and back in");
        VehicleState eu = new VehicleState(VehicleType.BUGGY);
        eu.fuelForce = 0;
        check(eu.maxEnergy() == VehicleType.BUGGY.buffer * (com.sva.ConfigSVA.tuning ? TuningMath.bufferMul(eu.tuning) : 1)
                && eu.gasCapacity(Gas.HYDROGEN) == 0 && eu.tanks().isEmpty(), "fuel state: EU mode keeps the old buffer and no H2 tank");
        VehicleState w = h2(VehicleType.PLANE);
        w.energy = 50000;
        w.batHealth = 0.81;
        w.batLimit = 0.95;
        w.batServices = 1;
        w.fillGas(Gas.HYDROGEN, 9920, false);
        NBTTagCompound t = new NBTTagCompound();
        w.writeNBT(t);
        VehicleState r = h2(VehicleType.PLANE);
        r.readNBT(t);
        check(r.batIn && r.energy == 50000 && near(r.batHealth, 0.81, 1e-12) && near(r.batLimit, 0.95, 1e-12) && r.batServices == 1
                && r.gas(Gas.HYDROGEN) == 9920, "fuel state: NBT round trip (charge, health, limit, services, H2 - no refill once seen)");
        w.takeBattery();
        NBTTagCompound t2 = new NBTTagCompound();
        w.writeNBT(t2);
        VehicleState r2 = h2(VehicleType.PLANE);
        r2.readNBT(t2);
        check(!r2.batIn && r2.energy == 0, "fuel state: a vehicle saved without its battery loads without one");
    }

    private static void legacyLoad() {
        // a crawler saved before the wave: 1 500 000 EU, some helium, no battery / H2 keys
        NBTTagCompound old = new NBTTagCompound();
        old.setInteger(VehicleState.K_TYPE, VehicleType.TRUCK.ordinal());
        old.setDouble(VehicleState.K_ENERGY, 1500000);
        NBTTagCompound g = new NBTTagCompound();
        g.setInteger(Gas.HELIUM.key(), 1500);
        old.setTag(VehicleState.K_GASES, g);
        VehicleState s = h2(VehicleType.TRUCK);
        s.readNBT(old);
        check(s.batIn && near(s.batHealth, 1, 1e-12) && s.energy == s.maxEnergy() && s.maxEnergy() >= 200000 && s.gas(Gas.HYDROGEN) == s.gasCapacity(Gas.HYDROGEN)
                && s.gasCapacity(Gas.HYDROGEN) >= 16000 && s.gas(Gas.HELIUM) == 1500, "fuel old saves: a full H2 tank, the old EU in the battery (up to its capacity), a new battery");
        NBTTagCompound again = new NBTTagCompound();
        s.drainGas(Gas.HYDROGEN, 10000, false);
        s.writeNBT(again);
        VehicleState s2 = h2(VehicleType.TRUCK);
        s2.readNBT(again);
        check(s2.gas(Gas.HYDROGEN) == s.gas(Gas.HYDROGEN), "fuel old saves: the refill happens once (saved on hydrogen: kept as it is)");
    }

    private static void item() {
        if (ModContentSVA.battery == null) {
            check(false, "battery item: registered");
            return;
        }
        BatteryMath.Battery b = new BatteryMath.Battery();
        b.charge = 1000;
        ItemStack s = ItemBatterySVA.stackOf(ModContentSVA.battery, VehicleType.PLANE, b);
        check(ItemBatterySVA.fits(s, VehicleType.PLANE) && !ItemBatterySVA.fits(s, VehicleType.HELI) && ItemBatterySVA.typeOf(s) == VehicleType.PLANE
                && ItemBatterySVA.typeOf(new ItemStack(ModContentSVA.battery, 1, VehicleType.SHIP.ordinal())) == null,
                "battery item: one kind per vehicle - another type's doesn't fit, none for the ship");
        long took = ItemBatterySVA.charge(s, 600, false), sim = ItemBatterySVA.charge(s, 1e9, true);
        BatteryMath.Battery after = ItemBatterySVA.batteryOf(s);
        check(took == 600 && after.charge == 1600 && sim == 120000 - 1600 && after.health < 1 && ItemBatterySVA.transferLimit(s) == 512
                && !ModContentSVA.battery.canProvideEnergy(s), "battery item: charges like an electric item (HV packet), wears, never gives charge back");
        check(com.sva.item.ItemBatteryToolSVA.kind(ModContentSVA.batteryTool.stackOf(com.sva.item.ItemBatteryToolSVA.ELECTROLYTE, 1)) == 0
                && ModContentSVA.batteryTool.getItemStackLimit(ModContentSVA.batteryTool.stackOf(com.sva.item.ItemBatteryToolSVA.KIT, 1)) == 1
                && ModContentSVA.NAOH_BUCKET >= 0, "battery tools: electrolyte (stacks), the kit and the cables (one); the core's NaOH bucket found");
        boolean tex = true;
        for (String n : new String[] {"battery_buggy", "battery_hover", "battery_truck", "battery_drill", "battery_heli", "battery_plane", "battery_jet",
                "battery_tilt", "electrolyte", "batteryKit", "jumpCables"}) {
            tex &= SelfTestFuelSVA.class.getResource("/assets/siliconagevehicles/textures/items/" + n + ".png") != null;
        }
        check(tex, "battery items: the 32 x 32 textures are in the jar");
    }

    private static void cockpit() {
        CockpitLamps.Snapshot s = new CockpitLamps.Snapshot();
        s.powered = false;
        s.hydrogen = true;
        s.batPresent = false;
        boolean dark = CockpitLamps.state(CockpitLamps.BAT, s) == CockpitLamps.ON && CockpitLamps.state(CockpitLamps.LOW_FUEL, s) == CockpitLamps.OFF;
        s.powered = true;
        s.batPresent = true;
        s.batCharge = 0.5F;
        s.fuel = 0.1F;
        boolean low = CockpitLamps.state(CockpitLamps.LOW_FUEL, s) == CockpitLamps.ON && CockpitLamps.state(CockpitLamps.RESERVE, s) == CockpitLamps.OFF
                && CockpitLamps.state(CockpitLamps.BAT, s) == CockpitLamps.OFF;
        s.fuel = 0.04F;
        s.batHealth = 0.7F;
        boolean reserve = CockpitLamps.state(CockpitLamps.RESERVE, s) == CockpitLamps.BLINK && CockpitLamps.state(CockpitLamps.LOW_FUEL, s) == CockpitLamps.OFF
                && CockpitLamps.state(CockpitLamps.BAT_SERVICE, s) == CockpitLamps.ON;
        s.hydrogen = false;
        boolean eu = CockpitLamps.state(CockpitLamps.RESERVE, s) == CockpitLamps.OFF && CockpitLamps.state(CockpitLamps.BAT_SERVICE, s) == CockpitLamps.OFF;
        check(dark && low && reserve && eu && CockpitLamps.KEYS.length > CockpitLamps.BAT_SERVICE, "cockpit lamps: АКБ (lit with the board dark), МАЛО ТОПЛИВА, РЕЗЕРВ, ОБСЛУЖИТЕ АКБ");
        check(CockpitDials.KEYS.length > CockpitDials.AMPS && CockpitDials.min(CockpitDials.VOLTS, VehicleType.BUGGY) == BatteryMath.V_MIN
                && CockpitDials.max(CockpitDials.VOLTS, VehicleType.BUGGY, 0) == BatteryMath.V_MAX
                && CockpitDials.min(CockpitDials.AMPS, VehicleType.PLANE) == -CockpitDials.max(CockpitDials.AMPS, VehicleType.PLANE, 0)
                && CockpitDials.max(CockpitDials.AMPS, VehicleType.PLANE, 0) >= FuelMath.generator(BatteryMath.capacity(VehicleType.PLANE), 1) * 20 / 27.0,
                "cockpit dials: H2, the voltmeter (20..32 V) and the battery ammeter (centred) for the cockpits that get them");
    }
}
