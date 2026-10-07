package com.sva.debug;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemKeyCardSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.AccessMath;
import com.sva.vehicle.CrawlerMath;
import com.sva.vehicle.GarageMath;
import com.sva.vehicle.TuningMath;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;
import com.sva.workbench.WorkbenchRecipeSVA;
import com.sva.workbench.WorkbenchRecipesSVA;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Wave 2 self-tests (★7 garage, ★8 tuning, ★10 access) - pure logic, run by SelfTestSVA on the dedicated server
 * ([SVA-TEST] lines, the same counters).
 */
final class SelfTestWave2SVA {

    private SelfTestWave2SVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    static void run() throws Exception {
        tuningRules();
        tuningStats();
        tuningState();
        access();
        cards();
        nbt();
        repairCost();
        content();
    }

    // ---------------------------------------------------------------- ★8 rules

    private static int[] slots(int... k) {
        int[] s = TuningMath.empty();
        for (int i = 0; i < k.length && i < s.length; i++) {
            s[i] = k[i];
        }
        return s;
    }

    private static void tuningRules() {
        VehicleType b = VehicleType.BUGGY, tr = VehicleType.TRUCK, dr = VehicleType.DRILL, hv = VehicleType.HOVER, pl = VehicleType.PLANE,
                he = VehicleType.HELI, sh = VehicleType.SHIP;
        check(TuningMath.fits(b, TuningMath.MOTOR_I) && TuningMath.fits(tr, TuningMath.MOTOR_II) && TuningMath.fits(hv, TuningMath.LIGHT_HULL)
                        && !TuningMath.fits(pl, TuningMath.MOTOR_I) && !TuningMath.fits(he, TuningMath.MOTOR_II) && !TuningMath.fits(sh, TuningMath.LIGHT_HULL),
                "tuning: motors / light hull fit wheels, tracks and the hoverbike, not aircraft");
        check(TuningMath.fits(b, TuningMath.BIG_WHEELS) && !TuningMath.fits(tr, TuningMath.BIG_WHEELS) && !TuningMath.fits(hv, TuningMath.BIG_WHEELS)
                        && !TuningMath.fits(pl, TuningMath.BIG_WHEELS),
                "tuning: big wheels only on the wheeled buggy (not tracks, hover, aircraft)");
        check(TuningMath.fits(dr, TuningMath.DRILL_PLUS) && !TuningMath.fits(tr, TuningMath.DRILL_PLUS)
                        && TuningMath.fits(he, TuningMath.EXTRA_TANK) && TuningMath.fits(VehicleType.SHUTTLE, TuningMath.EXTRA_TANK)
                        && !TuningMath.fits(b, TuningMath.EXTRA_TANK) && !TuningMath.fits(hv, TuningMath.EXTRA_TANK),
                "tuning: the reinforced drill only on the drill vehicle; the extra tank on aircraft with tanks (heli, shuttle), not ground / hover");
        boolean all = true;
        for (VehicleType t : VehicleType.values()) {
            all &= TuningMath.fits(t, TuningMath.BATTERY) && TuningMath.fits(t, TuningMath.ARMOR) && TuningMath.fits(t, TuningMath.LIGHT_BAR);
        }
        check(all, "tuning: battery+, armour and the light bar fit every vehicle");
        check(TuningMath.canInstall(b, slots(TuningMath.MOTOR_I), TuningMath.MOTOR_II) == TuningMath.CONFLICT
                        && TuningMath.canInstall(b, slots(TuningMath.ARMOR), TuningMath.LIGHT_HULL) == TuningMath.CONFLICT
                        && TuningMath.canInstall(b, slots(TuningMath.LIGHT_HULL), TuningMath.ARMOR) == TuningMath.CONFLICT
                        && TuningMath.canInstall(b, slots(TuningMath.BATTERY), TuningMath.BATTERY) == TuningMath.CONFLICT,
                "tuning: one motor only, armour vs light hull, no duplicates");
        int[] four = slots(TuningMath.MOTOR_II, TuningMath.BATTERY, TuningMath.ARMOR, TuningMath.LIGHT_BAR);
        check(TuningMath.canInstall(b, four, TuningMath.BIG_WHEELS) == TuningMath.FULL && TuningMath.count(four) == 4
                        && TuningMath.canInstall(b, slots(TuningMath.MOTOR_II, TuningMath.BATTERY, TuningMath.ARMOR), TuningMath.BIG_WHEELS) == TuningMath.OK
                        && TuningMath.canInstall(pl, TuningMath.empty(), TuningMath.MOTOR_I) == TuningMath.NO_FIT
                        && TuningMath.canInstall(b, TuningMath.empty(), 99) == TuningMath.BAD,
                "tuning: at most 4 upgrades; a 5th is refused, the 4th fits; no fit / bad kind refused");
        int[] dirty = TuningMath.sanitize(b, new int[] {TuningMath.MOTOR_I, TuningMath.MOTOR_II, TuningMath.EXTRA_TANK, 42, TuningMath.ARMOR});
        check(dirty.length == 4 && dirty[0] == TuningMath.MOTOR_I && dirty[1] == -1 && dirty[2] == -1 && dirty[3] == -1
                        && TuningMath.sanitize(b, null).length == 4,
                "tuning: a saved slot list is cleaned (conflict, misfit, unknown and the 5th dropped)");
        int[] p = TuningMath.unpack(TuningMath.pack(four));
        check(java.util.Arrays.equals(p, four) && TuningMath.pack(TuningMath.empty()) == 0
                        && java.util.Arrays.equals(TuningMath.unpack(TuningMath.pack(four) | 1 << 20), four),
                "tuning: the data watcher packing round-trips (the flag bit above it ignored)");
    }

    // ---------------------------------------------------------------- ★8 stats

    private static void tuningStats() {
        VehicleType b = VehicleType.BUGGY, pl = VehicleType.PLANE;
        check(near(TuningMath.speedMul(b, slots(TuningMath.MOTOR_I)), 1.15) && near(TuningMath.speedMul(b, slots(TuningMath.MOTOR_II)), 1.30)
                        && near(TuningMath.speedMul(b, slots(TuningMath.LIGHT_HULL)), 1.10) && near(TuningMath.speedMul(b, slots(TuningMath.ARMOR)), 0.95)
                        && near(TuningMath.speedMul(b, slots(TuningMath.MOTOR_II, TuningMath.ARMOR)), 1.235)
                        && near(TuningMath.speedMul(pl, slots(TuningMath.ARMOR)), 1) && near(TuningMath.speedMul(b, TuningMath.empty()), 1),
                "tuning: speed x1.15 / x1.30 / x1.10 / armour x0.95, multiplied (motor II + armour x1.235); aircraft unchanged");
        check(near(TuningMath.bufferMul(slots(TuningMath.BATTERY)), 1.5) && near(TuningMath.damageMul(slots(TuningMath.ARMOR)), 0.6)
                        && near(TuningMath.hpMul(slots(TuningMath.LIGHT_HULL)), 0.8) && near(TuningMath.lightMul(slots(TuningMath.LIGHT_BAR)), 2)
                        && near(TuningMath.digMul(slots(TuningMath.DRILL_PLUS)), 1.5) && near(TuningMath.gasMul(slots(TuningMath.EXTRA_TANK)), 1.5)
                        && near(TuningMath.bufferMul(TuningMath.empty()), 1) && near(TuningMath.damageMul(TuningMath.empty()), 1),
                "tuning: buffer x1.5, damage x0.6, max hull x0.8, lights x2, dig x1.5, gas x1.5; nothing installed = x1");
        check(TuningMath.stepHeight(b, slots(TuningMath.BIG_WHEELS)) == 1.5F && TuningMath.stepHeight(b, TuningMath.empty()) == b.stepHeight
                        && TuningMath.stepHeight(VehicleType.TRUCK, slots(TuningMath.BIG_WHEELS)) == CrawlerMath.STEP
                        && com.sva.vehicle.CrawlerMath.climbLimit(1.5F) >= 1.5 && !EntityVehicleSVA.moveAllowed(0.3, 1.5, 1, 0.4)
                        && EntityVehicleSVA.moveAllowed(0.3, 1.5, 1, 0.4, com.sva.vehicle.CrawlerMath.climbLimit(1.5F)),
                "tuning: big wheels step 1.5 blocks (the server accepts that climb only with them); tracks keep their own step");
    }

    private static void tuningState() {
        boolean was = ConfigSVA.tuning;
        try {
            ConfigSVA.tuning = true;
            VehicleState s = new VehicleState(VehicleType.HELI);
            s.setTuning(slots(TuningMath.BATTERY, TuningMath.EXTRA_TANK));
            com.sc.util.ArmorGasSC.Gas g = VehicleType.HELI.gases.gases().isEmpty() ? null : VehicleType.HELI.gases.gases().get(0);
            s.energy = s.maxEnergy();
            if (g != null) {
                s.gas[g.ordinal()] = s.gasCapacity(g);
            }
            boolean bigger = near(s.maxEnergy(), VehicleType.HELI.buffer * 1.5)
                    && (g == null || s.gasCapacity(g) == Math.round(VehicleType.HELI.gases.capacity(g) * 1.5));
            s.setTuning(TuningMath.empty());
            check(bigger && near(s.energy, VehicleType.HELI.buffer) && (g == null || s.gas(g) == VehicleType.HELI.gases.capacity(g)),
                    "tuning: battery+ / extra tank grow the buffer and the tanks; taken out, the charge and gas are cut to the old size");
            VehicleState b = new VehicleState(VehicleType.BUGGY);
            b.setTuning(slots(TuningMath.LIGHT_HULL));
            check(near(b.maxHp(), VehicleType.BUGGY.maxHp * 0.8) && b.hp <= b.maxHp(), "tuning: light hull - max hull -20 %, the hull clamped to it");
            ConfigSVA.tuning = false;
            check(near(b.maxHp(), VehicleType.BUGGY.maxHp), "tuning: with vehicles.tuning off the upgrades change nothing");
        } finally {
            ConfigSVA.tuning = was;
        }
    }

    // ---------------------------------------------------------------- ★10 access matrix

    private static void access() {
        int all = AccessMath.ALL, pa = AccessMath.PASSENGER, dr = AccessMath.DRIVE, ca = AccessMath.CARGO, re = AccessMath.REPAIR;
        boolean ownerAll = true;
        for (int mode = 0; mode < AccessMath.MODES; mode++) {
            ownerAll &= AccessMath.rights(true, true, false, mode, -1, 0, -1) == all && AccessMath.rights(false, true, false, mode, -1, 0, -1) == all;
        }
        check(ownerAll, "access: the owner may do everything in every mode, locks on or off");
        int friend = dr | pa | ca;
        check(AccessMath.rights(true, false, false, AccessMath.FRIENDS, friend, 0, -1) == friend
                        && AccessMath.rights(true, false, false, AccessMath.FRIENDS, -1, 0, -1) == 0
                        && AccessMath.rights(true, false, false, AccessMath.FRIENDS, -1, pa, -1) == pa
                        && AccessMath.rights(true, false, false, AccessMath.FRIENDS, ca, pa, -1) == ca,
                "access, Friends: a friend gets his row, a stranger the \"Everyone else\" row (a listed player only his own)");
        check(AccessMath.rights(true, false, false, AccessMath.OPEN, -1, 0, -1) == all
                        && AccessMath.rights(true, false, false, AccessMath.OWNER, friend, all, all) == 0,
                "access: \"Open\" - everyone everything; \"Owner\" - nobody, list and cards ignored");
        check(AccessMath.rights(true, false, false, AccessMath.FRIENDS, -1, 0, dr) == (dr | pa)
                        && AccessMath.rights(true, false, false, AccessMath.FRIENDS, ca, 0, re) == (ca | re)
                        && AccessMath.rights(true, false, false, AccessMath.FRIENDS, -1, 0, -1) == 0,
                "access: a valid card adds its rights (driving brings a seat); no card - nothing");
        check(AccessMath.rights(true, false, true, AccessMath.OWNER, -1, 0, -1) == all && AccessMath.rights(true, false, true, AccessMath.FRIENDS, -1, 0, -1) == all,
                "access: a server op (lockOpBypass) passes any lock");
        check(AccessMath.rights(false, false, false, AccessMath.OWNER, -1, 0, all) == pa && AccessMath.rights(false, false, true, AccessMath.OPEN, -1, 0, -1) == pa,
                "access: vehicles.locks off - the old rules: a stranger may only ride as a passenger");
        check(EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_CARGO, false, false, true) && EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_SORT, false, false, true)
                        && !EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_CARGO, false, false, false)
                        && !EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_AUTO_TOGGLE, false, false, true)
                        && !EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_TRAILER_DETACH, false, true, true),
                "access: the \"Cargo\" right opens / sorts the cargo, nothing else");
        AccessMath.Access a = new AccessMath.Access();
        boolean names = a.add("Friend_1") && !a.add("friend_1") && !a.add("bad name") && !a.add("") && !a.add("x12345678901234567");
        a.set("FRIEND_1", AccessMath.DRIVE);
        check(names && a.listed("friend_1") == (dr | pa) && a.remove("Friend_1") && a.listed("Friend_1") == -1,
                "access: names checked (letters, digits, _; <= 16), case-insensitive, no doubles; drive brings a seat");
        boolean cap = true;
        for (int i = 0; i < AccessMath.MAX_FRIENDS + 2; i++) {
            cap &= a.add("P" + i) == i < AccessMath.MAX_FRIENDS;
        }
        check(cap && a.names.size() == AccessMath.MAX_FRIENDS, "access: at most " + AccessMath.MAX_FRIENDS + " players on the list");
    }

    private static void cards() {
        check(AccessMath.cardRights("veh-1", 2, AccessMath.DRIVE, "veh-1", 2) == (AccessMath.DRIVE | AccessMath.PASSENGER)
                        && AccessMath.cardRights("veh-1", 1, AccessMath.ALL, "veh-1", 2) == -1
                        && AccessMath.cardRights("veh-2", 2, AccessMath.ALL, "veh-1", 2) == -1
                        && AccessMath.cardRights(null, 0, AccessMath.ALL, "veh-1", 0) == -1,
                "key card: valid only for its vehicle and the current lock version");
        if (ModContentSVA.keyCard == null) {
            check(false, "key card: item registered");
            return;
        }
        ItemStack blank = new ItemStack(ModContentSVA.keyCard);
        ItemStack card = ItemKeyCardSVA.write(blank, "veh-9", 41, VehicleType.TRUCK, "Owner", "Friend_1", 3, AccessMath.CARGO);
        check(!ItemKeyCardSVA.isBound(blank) && ItemKeyCardSVA.isBound(card) && "veh-9".equals(ItemKeyCardSVA.vehicleOf(card))
                        && ItemKeyCardSVA.rightsFor(card, "veh-9", 3) == AccessMath.CARGO && ItemKeyCardSVA.rightsFor(card, "veh-9", 4) == -1
                        && ItemKeyCardSVA.rightsFor(blank, "veh-9", 3) == -1,
                "key card: written with vehicle / lock / rights; a blank card opens nothing");
        ItemKeyCardSVA.relock(card, 4);
        check(ItemKeyCardSVA.rightsFor(card, "veh-9", 4) == AccessMath.CARGO, "key card: the owner's card follows a lock change");
    }

    // ---------------------------------------------------------------- NBT

    private static void nbt() {
        VehicleState s = new VehicleState(VehicleType.TRUCK);
        check(s.access.mode == AccessMath.FRIENDS && s.access.others == AccessMath.NEW_OTHERS && s.access.names.isEmpty(),
                "access: a new vehicle starts in \"Friends\" with nobody else allowed");
        s.setTuning(slots(TuningMath.MOTOR_I, TuningMath.ARMOR, TuningMath.LIGHT_BAR));
        s.access.add("Friend_1");
        s.access.set("Friend_1", AccessMath.CARGO | AccessMath.REPAIR);
        s.access.mode = AccessMath.OWNER;
        s.access.others = AccessMath.PASSENGER;
        s.access.lockVersion = 7;
        NBTTagCompound t = new NBTTagCompound();
        s.writeNBT(t);
        VehicleState r = new VehicleState(VehicleType.TRUCK);
        r.readNBT(t);
        check(java.util.Arrays.equals(r.tuning, s.tuning) && r.access.mode == AccessMath.OWNER && r.access.others == AccessMath.PASSENGER
                        && r.access.lockVersion == 7 && r.access.listed("friend_1") == (AccessMath.CARGO | AccessMath.REPAIR),
                "nbt: upgrades and the access list (mode, others, lock version, players) round-trip");
        if (ModContentSVA.vehicle != null) {
            ItemStack it = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.TRUCK.ordinal()), s);
            VehicleState back = ItemVehicleSVA.stateOf(it);
            check(java.util.Arrays.equals(back.tuning, s.tuning) && back.access.lockVersion == 7 && back.access.mode == AccessMath.OWNER,
                    "nbt: and through the vehicle item (pick up / place)");
        }
        // a vehicle saved before wave 2: an id, no "Access" - the old rules (anyone rides, the owner drives)
        NBTTagCompound old = new NBTTagCompound();
        s.writeNBT(old);
        old.removeTag(VehicleState.K_ACCESS);
        old.removeTag(VehicleState.K_TUNING);
        VehicleState o = new VehicleState(VehicleType.TRUCK);
        o.readNBT(old);
        check(o.access.mode == AccessMath.FRIENDS && o.access.others == AccessMath.LEGACY_OTHERS && o.access.names.isEmpty()
                        && TuningMath.count(o.tuning) == 0
                        && AccessMath.rights(true, false, false, o.access.mode, -1, o.access.others, -1) == AccessMath.PASSENGER,
                "nbt: an old save's vehicle keeps working as before - strangers may ride, only the owner drives");
        NBTTagCompound bad = new NBTTagCompound();
        s.writeNBT(bad);
        bad.setIntArray(VehicleState.K_TUNING, new int[] {TuningMath.BIG_WHEELS, -7, 500, TuningMath.MOTOR_II, TuningMath.MOTOR_I, 3, 3});
        VehicleState x = new VehicleState(VehicleType.TRUCK);
        x.readNBT(bad);
        check(x.tuning.length == 4 && TuningMath.count(x.tuning) == 1 && x.tuning[3] == TuningMath.MOTOR_II,
                "nbt: a broken upgrade list is cleaned (misfit, garbage, extra slots dropped)");
    }

    // ---------------------------------------------------------------- ★7 repair cost

    private static void repairCost() {
        float max = VehicleType.BUGGY.maxHp;
        check(GarageMath.euCost(max * 0.62F, max, 0, GarageMath.HULL) == 15200 && GarageMath.steelCost(max * 0.62F, max) == 4,
                "repair: a buggy at 62 % - 76 HP x 200 = 15 200 EU and 4 steel (38 % of 8, rounded up)");
        check(GarageMath.euCost(max * 0.62F, max, 60, GarageMath.ALL) == 21200 && GarageMath.euCost(max * 0.62F, max, 60, GarageMath.HULL) == 15200,
                "repair: \"Repair all\" adds 100 EU per % of heat, \"Hull only\" doesn't");
        check(GarageMath.euCost(max, max, 0, GarageMath.ALL) == 0 && GarageMath.steelCost(max, max) == 0 && GarageMath.steelCost(max - 1, max) == 1
                        && GarageMath.steelCost(0, max) == GarageMath.STEEL_FULL && !GarageMath.needed(max, max, 0, GarageMath.ALL)
                        && GarageMath.needed(max, max, 5, GarageMath.ALL) && !GarageMath.needed(max, max, 5, GarageMath.HULL),
                "repair: a whole hull costs nothing (cooling alone needs no metal), any wear at least 1 steel, a wreck 8");
        int[] p1 = GarageMath.pay(4, 2, 10), p2 = GarageMath.pay(4, 9, 0), p3 = GarageMath.pay(4, 1, 5), p4 = GarageMath.pay(0, 0, 0);
        check(p1 != null && p1[0] == 2 && p1[1] == 4 && p2 != null && p2[0] == 4 && p2[1] == 0 && p3 == null && p4 != null && p4[0] == 0,
                "repair: steel first, the rest as iron x2; not enough - refused");
        check(GarageMath.duration(15200) == GarageMath.DURATION_MIN && GarageMath.duration(10000000L) == GarageMath.DURATION_MAX
                        && GarageMath.perTick(21200) * GarageMath.duration(21200) >= 21200 && GarageMath.perTick(0) == 0,
                "repair: 5 s at least, 30 s at most; the per-tick draw pays the whole price in time");
        check(GarageMath.comparator(false, 0.5) == 0 && GarageMath.comparator(true, 0) == 1 && GarageMath.comparator(true, 0.5) == 8
                        && GarageMath.comparator(true, 1) == 15,
                "repair: comparator 0 idle, 1..14 with progress, 15 done");
    }

    // ---------------------------------------------------------------- content

    private static void content() throws Exception {
        check(ModContentSVA.garage != null && ModContentSVA.garagePlate != null && ModContentSVA.tuning != null && ModContentSVA.keyCard != null,
                "wave 2: garage, garage plates, tuning upgrades and the key card registered");
        Set<String> ids = new HashSet<String>();
        for (WorkbenchRecipeSVA r : WorkbenchRecipesSVA.all()) {
            ids.add(r.id);
        }
        boolean rec = ids.contains("garage") && ids.contains("garagePad") && ids.contains("garageLamp") && ids.contains("keyCard");
        String[] tu = {"tuningMotor1", "tuningMotor2", "tuningBattery", "tuningArmor", "tuningLightHull", "tuningBigWheels", "tuningLightBar",
                "tuningDrillPlus", "tuningExtraTank"};
        for (String s : tu) {
            rec &= ids.contains(s);
        }
        check(rec && tu.length == TuningMath.KINDS, "wave 2: Engineering Workbench recipes for the garage, its plates, all 9 upgrades and the key card");
        WorkbenchRecipeSVA g = WorkbenchRecipesSVA.byId("garage");
        check(g != null && g.category == WorkbenchRecipeSVA.Category.BLOCKS && WorkbenchRecipesSVA.byId("tuningArmor").category == WorkbenchRecipeSVA.Category.MODULES
                        && WorkbenchRecipesSVA.byId("keyCard").category == WorkbenchRecipeSVA.Category.MODULES
                        && WorkbenchRecipesSVA.indexOf(g) > WorkbenchRecipesSVA.indexOf(WorkbenchRecipesSVA.byId("moduleAccess")),
                "wave 2: the recipes are appended (sync index of the old ones unchanged), blocks / modules tabs");
        Set<String> ru = keys("/assets/siliconagevehicles/lang/ru_RU.lang"), en = keys("/assets/siliconagevehicles/lang/en_US.lang");
        boolean lang = ru.contains("tile.siliconagevehicles.garage.name") && ru.contains("tile.siliconagevehicles.garagePlate.1.name")
                && ru.contains("item.siliconagevehicles.keyCard.bound.name") && ru.contains("sva.msg.locked") && ru.equals(en);
        for (String k : TuningMath.KEYS) {
            lang &= ru.contains("item.siliconagevehicles.tuning." + k + ".name") && ru.contains("sva.tooltip.tuning." + k)
                    && ru.contains("sva.tooltip.tuning." + k + ".details") && ru.contains("sva.tuning.short." + k);
        }
        check(lang, "wave 2: names, tooltips and short names in ru and en (the same keys)");
        String[] tex = {"blocks/garageFront", "blocks/garageFrontOn", "blocks/garageSide", "blocks/garageTop", "blocks/garagePad",
                "blocks/garageLamp", "blocks/garagePlate_side", "items/keyCard", "items/keyCardBound"};
        boolean files = true;
        for (String s : tex) {
            files &= exists("/assets/siliconagevehicles/textures/" + s + ".png");
        }
        for (String k : TuningMath.KEYS) {
            files &= exists("/assets/siliconagevehicles/textures/items/tuning_" + k + ".png");
        }
        check(files, "wave 2: every new block face and item icon is in the jar");
    }

    private static boolean exists(String path) throws Exception {
        InputStream in = SelfTestWave2SVA.class.getResourceAsStream(path);
        if (in == null) {
            return false;
        }
        in.close();
        return true;
    }

    private static Set<String> keys(String path) throws Exception {
        Set<String> out = new HashSet<String>();
        InputStream in = SelfTestWave2SVA.class.getResourceAsStream(path);
        if (in == null) {
            return out;
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0 && !line.startsWith("#")) {
                    out.add(line.substring(0, eq));
                }
            }
        } finally {
            r.close();
        }
        return out;
    }
}
