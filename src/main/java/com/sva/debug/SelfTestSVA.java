package com.sva.debug;

import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.nbt.NBTTagCompound;

/** -Dsva.selftest=true: pure checks of the stage 1 formulas and NBT, printed as [SVA-TEST] lines. */
public final class SelfTestSVA {

    private static int passed, failed;

    private SelfTestSVA() {
    }

    private static void check(boolean ok, String name) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        System.out.println("[SVA-TEST] " + (ok ? "PASS " : "FAIL ") + name);
    }

    private static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }

    public static void run() {
        passed = failed = 0;
        try {
            consumption();
            range();
            crawl();
            heat();
            wear();
            limit();
            nbt();
            charger();
            config();
            moves();
            recipes();
        } catch (Throwable t) {
            failed++;
            System.out.println("[SVA-TEST] FAIL exception " + t);
            t.printStackTrace(System.out);
        }
        System.out.println("[SVA-TEST] SUMMARY passed=" + passed + " failed=" + failed);
    }

    private static void consumption() {
        VehicleType b = VehicleType.BUGGY;
        check(near(VehicleMath.cost(b, b.topSpeed, 0, true, 1), 20, 1e-9), "consumption: full throttle cruise = 20 EU/t");
        check(near(VehicleMath.cost(b, 0, 0, true, 1), 1, 1e-9), "consumption: idle = 1 EU/t");
        check(VehicleMath.cost(b, 0.5, 1, false, 1) == 0, "consumption: motor off = 0");
        check(near(VehicleMath.cost(b, b.topSpeed, 1, true, 1), 39, 1e-9), "consumption: full cargo doubles the speed part (39 EU/t)");
        check(near(VehicleMath.cost(b, b.topSpeed, 0, true, 2), 40, 1e-9), "consumption: multiplier x2 = 40 EU/t");
        check(near(VehicleMath.cost(b, b.topSpeed / 2, 0, true, 1), 1 + 19 / 4.0, 1e-9), "consumption: v^2 law (half speed = 5.75 EU/t)");
        check(VehicleMath.cost(b, 0.5, 5, true, 1) == VehicleMath.cost(b, 0.5, 1, true, 1), "consumption: cargo fill clamped to 1");
    }

    private static void range() {
        VehicleType b = VehicleType.BUGGY;
        check(near(VehicleMath.range(b, b.buffer, 0, 1), 10000, 1e-6), "range: full buggy ~10 000 blocks at top speed");
        check(near(VehicleMath.range(b, b.buffer / 2.0, 0, 1), 5000, 1e-6), "range: half buffer = half range");
        check(VehicleMath.range(b, 0, 0, 1) == 0, "range: empty = 0 (crawl only)");
        check(VehicleMath.range(b, 100, 0, 0) == Double.POSITIVE_INFINITY, "range: consumption 0 = endless");
        check(VehicleMath.kmh(0.5) == 36, "hud: 0.5 b/t = 36 km/h");
    }

    private static void crawl() {
        VehicleType b = VehicleType.BUGGY;
        check(near(VehicleMath.maxSpeed(b, 0), b.topSpeed * 0.1, 1e-12), "crawl: empty buffer = 10% speed");
        check(VehicleMath.maxSpeed(b, 1) == b.topSpeed, "crawl: any charge = full speed");
        check(VehicleMath.maxSpeed(b, 0) > 0, "crawl: never a dead stop");
    }

    private static void heat() {
        double h = 0;
        boolean stop = false;
        int t;
        for (t = 0; t < 20000 && !stop; t++) {
            h = VehicleMath.heatStep(h, 2, 0);
            stop = VehicleMath.heatStopped(stop, h);
        }
        check(stop && h >= 100, "heat: full cargo at top speed overheats (" + t + " ticks)");
        double h1 = 0;
        for (int i = 0; i < 20000; i++) {
            h1 = VehicleMath.heatStep(h1, 1, 0);
        }
        check(h1 < 100 && h1 > 50, "heat: empty cruise settles below 100% (" + Math.round(h1) + "%)");
        check(VehicleMath.heatStopped(true, 60), "heat: stays stopped above 50%");
        check(!VehicleMath.heatStopped(true, 50), "heat: restarts at 50%");
        check(!VehicleMath.heatStopped(false, 99.9), "heat: runs below 100%");
        check(VehicleMath.heatStopped(false, 100), "heat: stops at 100%");
        check(VehicleMath.heatStep(50, 0, 0) < 50, "heat: cools when idle");
        check(VehicleMath.heatStep(50, 0, 1) < VehicleMath.heatStep(50, 0, 0), "heat: cooling hook adds cooling");
        check(VehicleMath.heatStep(0, 0, 0) == 0, "heat: never below 0");
    }

    private static void wear() {
        check(VehicleMath.damage(200, 50) == 150, "wear: damage subtracts");
        check(VehicleMath.damage(10, 50) == 0 && VehicleMath.wrecked(0), "wear: 0 HP = wrecked, never negative");
        check(VehicleMath.damage(100, Float.NaN) == 100 && VehicleMath.damage(100, -5) == 100, "wear: NaN / negative damage ignored");
        check(VehicleMath.repair(0, 200) == 20, "wear: steel ingot repairs 10%");
        check(VehicleMath.repair(195, 200) == 200, "wear: repair capped at max");
        check(!VehicleMath.wrecked(VehicleMath.repair(0, 200)), "wear: repair lifts the wreck");
        check(near(VehicleMath.hitDamage(0.5), 24, 1e-4) && VehicleMath.hitDamage(0.15) == 0, "wear: wall hit 24 HP at top speed, none slow");
    }

    private static void limit() {
        check(VehicleMath.limitAllows(1000, 0), "limit: 0 = unlimited");
        check(VehicleMath.limitAllows(2, 3), "limit: under the limit");
        check(!VehicleMath.limitAllows(3, 3), "limit: at the limit refused");
        check(VehicleMath.limitAllows(5, -1), "limit: negative = unlimited");
    }

    private static void nbt() {
        VehicleState s = new VehicleState(VehicleType.BUGGY);
        s.energy = 12345.5;
        s.hp = 77;
        s.heat = 33;
        s.heatStop = true;
        s.cargo[0] = new ItemStack(Items.diamond, 5);
        s.cargo[8] = new ItemStack(Blocks.stone, 64);
        s.ownerId = "0f1e2d3c-0000-0000-0000-000000000001";
        s.ownerName = "Tester";
        NBTTagCompound t = new NBTTagCompound();
        s.writeNBT(t);
        VehicleState r = new VehicleState(VehicleType.BUGGY);
        r.readNBT(t);
        check(same(s, r), "nbt: state round trip (EU, HP, heat, cargo, owner, id)");
        ItemStack item = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, 0), s);
        VehicleState fromItem = ItemVehicleSVA.stateOf(item);
        check(same(s, fromItem), "nbt: item -> state keeps everything");
        check(item.getTagCompound().hasKey(EntityVehicleSVA.NBT_ROOT), "nbt: item root key " + EntityVehicleSVA.NBT_ROOT);
        VehicleState fresh = ItemVehicleSVA.stateOf(new ItemStack(ModContentSVA.vehicle, 1, 0));
        check(fresh.energy == 0 && fresh.hp == VehicleType.BUGGY.maxHp && fresh.cargoUsed() == 0, "nbt: plain item = empty, full hull");
        NBTTagCompound bad = new NBTTagCompound();
        bad.setDouble(VehicleState.K_ENERGY, Double.NaN);
        bad.setFloat(VehicleState.K_HP, 1e9F);
        VehicleState b = new VehicleState(VehicleType.BUGGY);
        b.readNBT(bad);
        check(b.energy == 0 && b.hp == VehicleType.BUGGY.maxHp, "nbt: NaN / oversized values clamped");
        check(near(s.cargoFill(), (5 / 64.0 + 1) / 9, 1e-9), "cargo: fill by stack limits");
        check(s.addEnergy(1e9) == s.maxEnergy() - 12345.5 && s.energy == s.maxEnergy(), "energy: add capped at buffer");
    }

    private static boolean same(VehicleState a, VehicleState b) {
        if (a.type != b.type || a.energy != b.energy || a.hp != b.hp || a.heat != b.heat || a.heatStop != b.heatStop
                || !a.ownerId.equals(b.ownerId) || !a.ownerName.equals(b.ownerName) || !a.vehId.equals(b.vehId)
                || a.cargo.length != b.cargo.length) {
            return false;
        }
        for (int i = 0; i < a.cargo.length; i++) {
            if (!ItemStack.areItemStacksEqual(a.cargo[i], b.cargo[i])) {
                return false;
            }
        }
        return true;
    }

    private static void charger() {
        check(VehicleMath.chargeTransfer(12800, 128, 1e6) == 128, "charger: MV moves 128 EU/t");
        check(VehicleMath.chargeTransfer(50, 128, 1e6) == 50, "charger: limited by its buffer");
        check(VehicleMath.chargeTransfer(12800, 128, 10.7) == 10, "charger: limited by the vehicle's room");
        check(VehicleMath.chargeTransfer(12800, 128, 0) == 0 && VehicleMath.chargeTransfer(0, 128, 99) == 0, "charger: nothing to move");
    }

    private static void config() {
        check(ConfigSVA.sanitize(Float.NaN, 1F, 0F, 100F) == 1F, "config: NaN -> default");
        check(ConfigSVA.sanitize(Float.POSITIVE_INFINITY, 1F, 0F, 100F) == 1F, "config: infinity -> default");
        check(ConfigSVA.sanitize(-5F, 1F, 0F, 100F) == 0F, "config: negative clamped to min");
        check(ConfigSVA.sanitize(500F, 1F, 0.1F, 100F) == 100F, "config: too big clamped to max");
        check(ConfigSVA.maxPerPlayer >= 0 && ConfigSVA.chargerRate > 0 && ConfigSVA.consumption >= 0, "config: loaded values sane");
    }

    private static void moves() {
        check(EntityVehicleSVA.moveAllowed(0.6, 0, 1, 0.5), "net: a top-speed move is accepted");
        check(!EntityVehicleSVA.moveAllowed(2.0, 0, 1, 0.5), "net: too fast refused");
        check(!EntityVehicleSVA.moveAllowed(12, 0, 10, 5), "net: teleport refused");
        check(EntityVehicleSVA.moveAllowed(0.3, 1.0, 1, 0.5), "net: a 1-block step accepted");
        check(!EntityVehicleSVA.moveAllowed(0.3, 2.0, 1, 0.5), "net: a 2-block jump refused");
        check(EntityVehicleSVA.moveAllowed(2.5, 0, 4, 0.5), "net: bunched packets (4 ticks) accepted");
    }

    private static void recipes() {
        boolean charger = false, buggy = false;
        for (Object o : CraftingManager.getInstance().getRecipeList()) {
            ItemStack out = ((IRecipe) o).getRecipeOutput();
            if (out == null) {
                continue;
            }
            if (out.getItem() == net.minecraft.item.Item.getItemFromBlock(ModContentSVA.charger)) {
                charger = true;
            }
            if (out.getItem() == ModContentSVA.vehicle) {
                buggy = true;
            }
        }
        check(charger, "recipe: charging station registered");
        check(buggy, "recipe: buggy (temporary) registered");
    }
}
