package com.sva.debug;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

import com.sva.init.ModContentSVA;
import com.sva.item.ItemTrailerSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.AccessMath;
import com.sva.vehicle.MapMath;
import com.sva.vehicle.TrailerMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;
import com.sva.workbench.WorkbenchRecipeSVA;
import com.sva.workbench.WorkbenchRecipesSVA;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Wave 3 self-tests (★15 trailer kinds, ★19 the vehicle registry and map) - pure logic, run by SelfTestSVA on the
 * dedicated server ([SVA-TEST] lines, the same counters).
 */
final class SelfTestWave3SVA {

    private SelfTestWave3SVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    static void run() throws Exception {
        trailerRules();
        tankMath();
        chargerMath();
        trailerState();
        trailerItems();
        registry();
        mapMath();
        content();
    }

    // ---------------------------------------------------------------- ★15

    private static void trailerRules() {
        check(TrailerMath.valid(-1) == TrailerMath.CARGO && TrailerMath.valid(TrailerMath.COUNT) == TrailerMath.CARGO
                        && TrailerMath.valid(TrailerMath.CHARGER) == TrailerMath.CHARGER && TrailerMath.addsCargo(TrailerMath.CARGO)
                        && !TrailerMath.addsCargo(TrailerMath.TANK) && !TrailerMath.addsCargo(TrailerMath.PLATFORM),
                "trailers: kinds clamp to the cargo trailer; only the cargo trailer adds slots");
        check(TrailerMath.platformFits(VehicleType.BUGGY) && TrailerMath.platformFits(VehicleType.HOVER) && !TrailerMath.platformFits(VehicleType.DRILL)
                        && !TrailerMath.platformFits(VehicleType.TRUCK) && !TrailerMath.platformFits(VehicleType.HELI)
                        && !TrailerMath.platformFits(VehicleType.SHIP) && !TrailerMath.platformFits(null),
                "trailers: the platform carries the buggy and the hoverbike, nothing bigger");
        check(near(TrailerMath.speedMul(TrailerMath.TANK, 8000, false), 1) && near(TrailerMath.speedMul(TrailerMath.TANK, 8001, false), 0.85)
                        && near(TrailerMath.speedMul(TrailerMath.PLATFORM, 0, true), 0.80) && near(TrailerMath.speedMul(TrailerMath.PLATFORM, 0, false), 1)
                        && near(TrailerMath.speedMul(TrailerMath.CHARGER, 0, true), 1) && near(TrailerMath.speedMul(TrailerMath.CARGO, 16000, true), 1),
                "trailers: speed x0.85 over half a tank, x0.8 with a vehicle on the platform, else full");
        String[] keys = TrailerMath.KEYS;
        check(keys.length == TrailerMath.COUNT && TrailerMath.MODELS.length == TrailerMath.COUNT && TrailerMath.MODELS[0] == null
                        && "trailer_tank".equals(TrailerMath.MODELS[TrailerMath.TANK]),
                "trailers: one key / model per kind (the cargo trailer is truck.obj's own part)");
        // the carried vehicle's size on the deck: true size when it fits, else shrunk (the buggy ~x0.6)
        float deck = TrailerMath.DECK_X1 - TrailerMath.DECK_X0;
        float sb = TrailerMath.carriedScale(VehicleType.BUGGY.modelScale / VehicleType.TRUCK.modelScale, 2.6F, 1.5F);
        float small = TrailerMath.carriedScale(0.5F, 1F, 1F);
        check(sb * 2.6F <= deck * 1.25F + 1e-4 && sb >= 0.5F && sb <= 1.16F && small == 0.5F,
                "trailers: the carried vehicle fits the deck (buggy x" + sb + "), a small one keeps its true size");
    }

    private static void tankMath() {
        check(TrailerMath.tankRoom("", 0, "water", 1000) == 1000 && TrailerMath.tankRoom(null, 0, "hydrogen", 20000) == TrailerMath.TANK_CAP,
                "tank: an empty tank takes any fluid up to 16 000 mB");
        check(TrailerMath.tankRoom("water", 15500, "water", 1000) == 500 && TrailerMath.tankRoom("water", 100, "lava", 1000) == 0
                        && TrailerMath.tankRoom("water", 100, null, 1000) == 0 && TrailerMath.tankRoom("water", 100, "water", 0) == 0,
                "tank: the same fluid tops up to the cap, another fluid is refused");
        check(TrailerMath.tankTake("water", 700, null, 1000) == 700 && TrailerMath.tankTake("water", 700, "water", 300) == 300
                        && TrailerMath.tankTake("water", 700, "lava", 300) == 0 && TrailerMath.tankTake("", 0, null, 300) == 0,
                "tank: drains what is there, only the asked fluid");
        check(near(TrailerMath.tankFill(8000), 0.5) && near(TrailerMath.tankFill(99999), 1) && near(TrailerMath.tankFill(-5), 0), "tank: fill 0..1");
    }

    private static void chargerMath() {
        check(TrailerMath.solar(true, true, false, 1e9) == TrailerMath.SOLAR_EU && TrailerMath.solar(false, true, false, 1e9) == 0
                        && TrailerMath.solar(true, false, false, 1e9) == 0 && TrailerMath.solar(true, true, true, 1e9) == 0
                        && TrailerMath.solar(true, true, false, 10) == 10,
                "charger: the sun gives 32 EU/t by day under the open sky, not at night / under a roof / in the rain, bounded by the room");
        check(TrailerMath.feed(true, 2000000, 2000000, 1e9, 128) == 128 && TrailerMath.feed(false, 2000000, 2000000, 1e9, 128) == 0
                        && TrailerMath.feed(true, 400050, 2000000, 1e9, 128) == 50 && TrailerMath.feed(true, 300000, 2000000, 1e9, 128) == 0
                        && TrailerMath.feed(true, 2000000, 2000000, 7, 128) == 7 && TrailerMath.feed(true, Double.NaN, 2000000, 1e9, 128) == 0,
                "charger: fed by the crawler only while driving, never taking it under 20 %, bounded by the room");
        check(TrailerMath.carryCharge(2000000, 2000000, 1e9) == TrailerMath.CARRY_EU && TrailerMath.carryCharge(300000, 2000000, 1e9) == 0,
                "platform: the carried vehicle's trickle - 32 EU/t, the crawler not under 20 %");
        double[] o = TrailerMath.trailerOffset();
        check(o[0] < -3 && o[0] > -6 && o[1] == 0, "trailers: the trailer stands " + (-o[0]) + " blocks behind the crawler");
    }

    private static void trailerState() {
        VehicleState s = new VehicleState(VehicleType.TRUCK);
        s.setTrailer(true, TrailerMath.TANK);
        check(s.trailer && s.trailerType == TrailerMath.TANK && s.cargoSize() == VehicleType.TRUCK.cargoSlots && s.cargo.length == s.cargoSize(),
                "trailer state: a tanker adds no cargo slots");
        s.tankFluid = "water";
        s.tankAmount = 12345;
        s.tankIn = new ItemStack(net.minecraft.init.Items.bucket);
        NBTTagCompound t = new NBTTagCompound();
        s.writeNBT(t);
        VehicleState r = new VehicleState(VehicleType.TRUCK);
        r.readNBT(t);
        check(r.trailer && r.trailerType == TrailerMath.TANK && "water".equals(r.tankFluid) && r.tankAmount == 12345 && r.tankIn != null
                        && r.tankOut == null && r.cargo.length == VehicleType.TRUCK.cargoSlots,
                "trailer state: the tanker's kind, fluid, amount and slots round-trip (NBT)");
        // an old save: Trailer true, no TrailerType - the cargo trailer with its 54 slots
        NBTTagCompound old = new NBTTagCompound();
        VehicleState c = new VehicleState(VehicleType.TRUCK);
        c.setTrailer(true);
        c.cargo[60] = new ItemStack(net.minecraft.init.Items.apple, 3);
        c.writeNBT(old);
        old.removeTag(VehicleState.K_TRAILER_TYPE);
        old.removeTag(VehicleState.K_TRAILER_DATA);
        VehicleState o = new VehicleState(VehicleType.TRUCK);
        o.readNBT(old);
        check(o.trailer && o.trailerType == TrailerMath.CARGO && o.cargo.length == 108 && o.cargo[60] != null && o.cargo[60].stackSize == 3,
                "trailer state: a crawler saved before wave 3 keeps its cargo trailer and the 54 slots' contents");
        // the platform: the carried vehicle's item NBT; the charger: clamped to its buffer
        VehicleState p = new VehicleState(VehicleType.TRUCK);
        p.setTrailer(true, TrailerMath.PLATFORM);
        VehicleState bug = new VehicleState(VehicleType.BUGGY);
        bug.energy = 123456;
        if (ModContentSVA.vehicle != null) {
            p.carried = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.BUGGY.ordinal()), bug).writeToNBT(new NBTTagCompound());
            NBTTagCompound pt = new NBTTagCompound();
            p.writeNBT(pt);
            VehicleState pr = new VehicleState(VehicleType.TRUCK);
            pr.readNBT(pt);
            ItemStack back = pr.carried == null ? null : ItemStack.loadItemStackFromNBT(pr.carried);
            VehicleState bs = back == null ? null : ItemVehicleSVA.stateOf(back);
            check(bs != null && bs.type == VehicleType.BUGGY && near(bs.energy, 123456) && bug.vehId.equals(bs.vehId),
                    "trailer state: the platform's buggy round-trips with its charge and id");
        }
        VehicleState ch = new VehicleState(VehicleType.TRUCK);
        ch.setTrailer(true, TrailerMath.CHARGER);
        NBTTagCompound d = new NBTTagCompound();
        d.setDouble(VehicleState.T_ENERGY, 9e9);
        ch.readTrailerData(d);
        check(near(ch.chargerEnergy, TrailerMath.CHARGER_BUFFER), "trailer state: the charger's EU clamped to its 4M buffer");
        ch.setTrailer(false);
        check(!ch.trailer && ch.chargerEnergy == 0 && ch.trailerType == TrailerMath.CARGO, "trailer state: unhitching clears the contents");
        // the drill can't hitch anything
        VehicleState dr = new VehicleState(VehicleType.DRILL);
        dr.setTrailer(true, TrailerMath.TANK);
        check(!dr.trailer, "trailer state: only the crawler hitches trailers");
    }

    private static void trailerItems() {
        if (ModContentSVA.trailer == null) {
            return;
        }
        ItemStack tank = new ItemStack(ModContentSVA.trailer, 1, TrailerMath.TANK);
        check(ItemTrailerSVA.kindOf(tank) == TrailerMath.TANK && ItemTrailerSVA.kindOf(new ItemStack(ModContentSVA.trailer, 1, 77)) == TrailerMath.CARGO
                        && !ItemTrailerSVA.hasContents(tank),
                "trailer items: the metadata is the kind (unknown -> cargo); a fresh one is empty");
        NBTTagCompound d = new NBTTagCompound();
        d.setString(VehicleState.T_FLUID, "water");
        d.setInteger(VehicleState.T_AMOUNT, 4000);
        ItemTrailerSVA.withData(tank, d);
        VehicleState c = ItemTrailerSVA.contents(tank);
        check(ItemTrailerSVA.hasContents(tank) && c.tankAmount == 4000 && "water".equals(c.tankFluid),
                "trailer items: the tanker item keeps its fluid");
        ItemTrailerSVA.withData(tank, new NBTTagCompound());
        check(!tank.hasTagCompound(), "trailer items: emptied, it is a plain item again (stacks / crafts)");
        ItemStack cargo = new ItemStack(ModContentSVA.trailer);
        ItemStack[] in = new ItemStack[ItemTrailerSVA.slots()];
        in[3] = new ItemStack(net.minecraft.init.Items.apple);
        ItemTrailerSVA.withCargo(cargo, in);
        Object want = new ItemStack(ModContentSVA.trailer, 1, TrailerMath.CARGO);
        check(WorkbenchRecipeSVA.matches(want, new ItemStack(ModContentSVA.trailer)) && !WorkbenchRecipeSVA.matches(want, cargo),
                "trailer items: the workbench takes an empty cargo trailer, never one with cargo in it");
    }

    // ---------------------------------------------------------------- ★19

    private static void registry() {
        VehicleRegistrySVA r = new VehicleRegistrySVA("test");
        AccessMath.Access a = new AccessMath.Access();
        a.add("Friend_9");
        a.mode = AccessMath.FRIENDS;
        a.lockVersion = 3;
        r.update("veh-1", "owner-1", "Owner", VehicleType.HELI.ordinal(), 12, -99.5, 70, -1286.25, -1, 0.66F, 0.95F, 123456L, a);
        r.add("veh-2", "owner-1");
        NBTTagCompound t = new NBTTagCompound();
        r.writeToNBT(t);
        VehicleRegistrySVA b = new VehicleRegistrySVA("test");
        b.readFromNBT(t);
        VehicleRegistrySVA.Entry e = b.entry("veh-1"), e2 = b.entry("veh-2");
        check(e != null && e.type == VehicleType.HELI.ordinal() && e.board == 12 && near(e.x, -99.5) && near(e.z, -1286.25) && e.dim == -1
                        && Math.abs(e.charge - 0.66F) < 1e-6 && Math.abs(e.hull - 0.95F) < 1e-6 && e.seen == 123456L && e.placed
                        && "Owner".equals(e.ownerName) && e.access.listed("friend_9") >= 0 && e.access.lockVersion == 3,
                "registry: a vehicle's card (type, number, place, dimension, charge, hull, seen, access) round-trips (NBT)");
        check(e2 != null && !e2.placed && e2.access.mode == AccessMath.OWNER && b.count("owner-1") == 2,
                "registry: an entry without a card yet (placed, not loaded) has no place and shows to the owner only");
        // the pre-wave-3 save: only Id / Owner
        NBTTagCompound old = new NBTTagCompound();
        net.minecraft.nbt.NBTTagList l = new net.minecraft.nbt.NBTTagList();
        NBTTagCompound oe = new NBTTagCompound();
        oe.setString("Id", "veh-old");
        oe.setString("Owner", "owner-2");
        l.appendTag(oe);
        old.setTag("Vehicles", l);
        VehicleRegistrySVA o = new VehicleRegistrySVA("test");
        o.readFromNBT(old);
        check(o.has("veh-old") && o.count("owner-2") == 1 && !o.entry("veh-old").placed, "registry: an old save still counts its vehicles (limit)");
        r.update("veh-1", "owner-1", "Owner", 1, 12, Double.NaN, 70, Double.POSITIVE_INFINITY, 0, Float.NaN, 7F, 1L, null);
        e = r.entry("veh-1");
        check(e.x == 0 && e.z == 0 && e.charge == 0 && e.hull == 1F, "registry: NaN / infinite / out-of-range values are sanitised");
    }

    private static void mapMath() {
        double[] p = MapMath.toMap(110, 80, 100, 100, 2);
        double[] w = MapMath.toWorld(p[0], p[1], 100, 100, 2);
        check(near(p[0], 5) && near(p[1], -10) && near(w[0], 110) && near(w[1], 80), "map: world -> pixel (north up, east right) and back");
        check(MapMath.inside(100, -100, 100) && !MapMath.inside(100.5, 0, 100), "map: inside the square panel");
        double[] e = MapMath.edge(400, -200, 100);
        check(near(e[0], 100) && near(e[1], -50) && near(e[2], Math.atan2(-200, 400)), "map: a far vehicle is pinned to the edge along its bearing");
        double[] in = MapMath.edge(30, 40, 100);
        check(near(in[0], 30) && near(in[1], 40), "map: a vehicle on the panel stays where it is");
        check(MapMath.distance(0, 0, 3, 4) == 5 && MapMath.distance(Double.NaN, 0, 0, 0) == 0, "map: distance in whole blocks");
        check(MapMath.zoomStep(0, -1) == 0 && MapMath.zoomStep(0, 9) == MapMath.ZOOMS.length - 1 && MapMath.zoomStep(1, 1) == 2,
                "map: zoom steps clamp");
        check(MapMath.ageSeconds(1200, 0) == 60 && MapMath.ageSeconds(0, 100) == 0, "map: seconds since the vehicle was seen");
        check(MapMath.low(0.19) && !MapMath.low(0.2), "map: \"low charge!\" under 20 %");
        int dr = AccessMath.DRIVE, cg = AccessMath.CARGO, pa = AccessMath.PASSENGER;
        check(MapMath.visible(true, true, AccessMath.OWNER, -1, -1) && MapMath.visible(true, false, AccessMath.FRIENDS, -1, -1),
                "map visibility: the owner always sees his vehicle");
        check(MapMath.visible(false, true, AccessMath.FRIENDS, dr, -1) && MapMath.visible(false, true, AccessMath.OPEN, cg, -1)
                        && MapMath.visible(false, true, AccessMath.FRIENDS, -1, AccessMath.REPAIR),
                "map visibility: a listed player (drive / cargo / repair) or a valid key card sees it");
        check(!MapMath.visible(false, true, AccessMath.FRIENDS, -1, -1) && !MapMath.visible(false, true, AccessMath.OPEN, -1, -1)
                        && !MapMath.visible(false, true, AccessMath.FRIENDS, pa, -1) && !MapMath.visible(false, true, AccessMath.OWNER, dr, dr)
                        && !MapMath.visible(false, false, AccessMath.OPEN, dr, dr),
                "map visibility: strangers, passenger-only rows, \"Owner only\" locks and locks off hide it");
    }

    private static void content() throws Exception {
        Set<String> ids = new HashSet<String>();
        for (WorkbenchRecipeSVA r : WorkbenchRecipesSVA.all()) {
            ids.add(r.id);
        }
        WorkbenchRecipeSVA tank = WorkbenchRecipesSVA.byId("trailerTank");
        check(ids.contains("trailerTank") && ids.contains("trailerPlatform") && ids.contains("trailerCharger") && tank != null
                        && tank.category == WorkbenchRecipeSVA.Category.VEHICLES
                        && WorkbenchRecipesSVA.indexOf(tank) > WorkbenchRecipesSVA.indexOf(WorkbenchRecipesSVA.byId("keyCard")),
                "wave 3: the three trailer recipes are appended after wave 2's (old sync indices unchanged), vehicles tab");
        check(tank != null && ItemTrailerSVA.kindOf(tank.result()) == TrailerMath.TANK
                        && ItemTrailerSVA.kindOf(WorkbenchRecipesSVA.byId("trailerCharger").result()) == TrailerMath.CHARGER,
                "wave 3: the recipes make the right kinds");
        Set<String> ru = keys("/assets/siliconagevehicles/lang/ru_RU.lang"), en = keys("/assets/siliconagevehicles/lang/en_US.lang");
        boolean lang = ru.equals(en) && ru.contains("key.sva.map") && ru.contains("sva.map.title") && ru.contains("sva.gui.tip.tab.trailer");
        for (int k = 1; k < TrailerMath.COUNT; k++) {
            String key = TrailerMath.KEYS[k];
            lang &= ru.contains("item.siliconagevehicles.trailer_" + key + ".name") && ru.contains("sva.tooltip.trailer." + key + ".summary")
                    && ru.contains("sva.tooltip.trailer." + key + ".details");
        }
        check(lang, "wave 3: trailer names, tooltips, the map and its key in ru and en (the same keys)");
        boolean files = true;
        for (int k = 1; k < TrailerMath.COUNT; k++) {
            files &= exists("/assets/siliconagevehicles/textures/items/trailer_" + TrailerMath.KEYS[k] + ".png")
                    && exists("/assets/siliconagevehicles/models/" + TrailerMath.MODELS[k] + ".obj")
                    && exists("/assets/siliconagevehicles/models/" + TrailerMath.MODELS[k] + ".groups.txt");
        }
        check(files, "wave 3: every trailer kind's model, colours and icon are in the jar");
        // the models share truck.obj's space: the hitch bar ends where truck.obj's does (x -1.006) and they stand on y 0
        for (int k = 1; k < TrailerMath.COUNT; k++) {
            float[] b = bounds(TrailerMath.MODELS[k]);
            check(b != null && Math.abs(b[3] - (-1.006F)) < 0.02F && b[1] > -0.02F && b[1] < 0.03F && b[0] > -3.6F,
                    "wave 3: " + TrailerMath.MODELS[k] + " sits in truck.obj's model space (x " + (b == null ? 0 : b[0]) + ".." + (b == null ? 0 : b[3])
                            + ", floor " + (b == null ? 0 : b[1]) + ")");
        }
        check(com.sva.ConfigSVA.trailerKinds && com.sva.ConfigSVA.vehicleMap, "wave 3: vehicles.trailerKinds / vehicles.vehicleMap on by default");
    }

    private static float[] bounds(String model) throws Exception {
        InputStream in = SelfTestWave3SVA.class.getResourceAsStream("/assets/siliconagevehicles/models/" + model + ".obj");
        if (in == null) {
            return null;
        }
        float[] b = null;
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"), 1 << 16);
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("v ")) {
                    continue;
                }
                String[] p = line.trim().split("\\s+");
                float x = Float.parseFloat(p[1]), y = Float.parseFloat(p[2]), z = Float.parseFloat(p[3]);
                if (b == null) {
                    b = new float[] {x, y, z, x, y, z};
                }
                b[0] = Math.min(b[0], x);
                b[1] = Math.min(b[1], y);
                b[2] = Math.min(b[2], z);
                b[3] = Math.max(b[3], x);
                b[4] = Math.max(b[4], y);
                b[5] = Math.max(b[5], z);
            }
        } finally {
            r.close();
        }
        return b;
    }

    private static boolean exists(String path) throws Exception {
        InputStream in = SelfTestWave3SVA.class.getResourceAsStream(path);
        if (in == null) {
            return false;
        }
        in.close();
        return true;
    }

    private static Set<String> keys(String path) throws Exception {
        Set<String> out = new HashSet<String>();
        InputStream in = SelfTestWave3SVA.class.getResourceAsStream(path);
        if (in == null) {
            return out;
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                int i = line.indexOf('=');
                if (i > 0 && !line.startsWith("#")) {
                    out.add(line.substring(0, i));
                }
            }
        } finally {
            r.close();
        }
        return out;
    }
}
