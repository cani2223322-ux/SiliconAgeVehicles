package com.sva.debug;

import java.util.Set;

import com.sc.energy.Tier;
import com.sc.init.ModItems;
import com.sc.machine.UpgradeType;
import com.sva.ConfigSVA;
import com.sva.airdefense.AdUpgradeMath;
import com.sva.airdefense.RadarMath;
import com.sva.block.TileEntityCommandPostSVA;
import com.sva.block.TileEntityLauncherSVA;
import com.sva.block.TileEntityRadarSVA;
import com.sva.inventory.ContainerAdSVA;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * Upgrades wave self-test (2026-10-12): the air-defence blocks' core modules - which fit which block (and nothing else),
 * the input tier with N transformers and with the universal one, the buffer with storages, the intervals and the EU with
 * overclockers (their floors, the useless ones costing nothing), economizers, overdrive, a shot's EU, the scope's
 * mapping, the seconds' text; then on bare tiles (no world): the slot checks, the counts, the universal transformer
 * taking an SV packet that a bare radar refuses (a simulated offer: nothing explodes), the config switch, the lang keys.
 */
final class SelfTestAdUpgradesSVA {

    private SelfTestAdUpgradesSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws Exception {
        math();
        tiles();
        lang();
    }

    private static void math() {
        int R = AdUpgradeMath.B_RADAR, L = AdUpgradeMath.B_LAUNCHER, K = AdUpgradeMath.B_KP;
        boolean all = true;
        for (int b = 0; b < 3; b++) {
            all &= AdUpgradeMath.accepts(b, UpgradeType.UNIVERSAL_TRANSFORMER) && AdUpgradeMath.accepts(b, UpgradeType.TRANSFORMER)
                    && AdUpgradeMath.accepts(b, UpgradeType.ENERGY_STORAGE) && AdUpgradeMath.accepts(b, UpgradeType.ECONOMIZER);
            for (UpgradeType t : new UpgradeType[] {UpgradeType.ADAPTIVE_TRANSFORMER, UpgradeType.OUTPUT_SPLITTER, UpgradeType.HEAT_SINK, UpgradeType.QUALITY,
                    UpgradeType.EJECTOR, UpgradeType.TANK_EXTENSION, UpgradeType.CHARGE_BOOSTER, UpgradeType.RAD_SHIELDING}) {
                all &= !AdUpgradeMath.accepts(b, t);
            }
        }
        check(all && !AdUpgradeMath.accepts(R, null), "ad upgrades: every block takes the universal transformer, transformers, storage, "
                + "economizers - never the storage-only, heat, quality, ejector, tank, booster or shielding modules");
        check(AdUpgradeMath.accepts(R, UpgradeType.OVERCLOCKER) && AdUpgradeMath.accepts(L, UpgradeType.OVERCLOCKER) && !AdUpgradeMath.accepts(K, UpgradeType.OVERCLOCKER)
                && AdUpgradeMath.accepts(R, UpgradeType.OVERDRIVE) && !AdUpgradeMath.accepts(L, UpgradeType.OVERDRIVE) && !AdUpgradeMath.accepts(K, UpgradeType.OVERDRIVE)
                && AdUpgradeMath.accepts(L, UpgradeType.PULLER) && !AdUpgradeMath.accepts(R, UpgradeType.PULLER) && !AdUpgradeMath.accepts(K, UpgradeType.PULLER),
                "ad upgrades: overclockers - radars and launchers; overdrive - radars; the puller - launchers");
        check(AdUpgradeMath.accepted(R).length == 6 && AdUpgradeMath.accepted(L).length == 6 && AdUpgradeMath.accepted(K).length == 4
                && AdUpgradeMath.accepted(K)[0] == UpgradeType.UNIVERSAL_TRANSFORMER && AdUpgradeMath.SLOTS == 4,
                "ad upgrades: the lists the screens show (6 / 6 / 4, the universal transformer first), four slots as the core machines");
        check(AdUpgradeMath.inputTier(Tier.MV, 0, false) == Tier.MV && AdUpgradeMath.inputTier(Tier.MV, 1, false) == Tier.HV
                && AdUpgradeMath.inputTier(Tier.MV, 2, false) == Tier.EV && AdUpgradeMath.inputTier(Tier.HV, 64, false) == Tier.max()
                && AdUpgradeMath.inputTier(Tier.MV, 0, true) == Tier.SV && AdUpgradeMath.inputTier(Tier.EV, 1, true) == Tier.max(),
                "ad upgrades: the input tier - +1 a transformer (MV, HV, EV), capped at the top, the universal one: SV");
        check(AdUpgradeMath.buffer(Tier.MV, 0, 32) == 12800 && AdUpgradeMath.buffer(Tier.MV, 3, 32) == 42800
                && AdUpgradeMath.buffer(Tier.HV, 64, 256) == 51200 + 16 * UpgradeType.STORAGE_PER_UPGRADE && AdUpgradeMath.buffer(Tier.MV, 0, 20000) == 40000,
                "ad upgrades: the buffer - the tier's + 10 000 a storage (16 count), never under two ticks of upkeep");
        check(AdUpgradeMath.interval(120, 0, 5) == 120 && AdUpgradeMath.interval(120, 1, 5) == 84 && AdUpgradeMath.interval(120, 2, 5) == 59
                && AdUpgradeMath.interval(120, 16, 5) == 5 && AdUpgradeMath.interval(10, 3, 5) == 5 && AdUpgradeMath.interval(3, 4, 5) == 3
                && AdUpgradeMath.interval(60, 1, 10) == 42 && AdUpgradeMath.interval(0, 3, 5) == 0,
                "ad upgrades: the interval x0.7 an overclocker, never under the floor (a shorter base stays)");
        check(AdUpgradeMath.usefulOc(120, 16, 5) == 9 && AdUpgradeMath.usefulOc(10, 16, 5) == 2 && AdUpgradeMath.usefulOc(120, 2, 5) == 2
                && AdUpgradeMath.usefulOc(60, 0, 10) == 0, "ad upgrades: overclockers past the floor change nothing and are not paid for");
        check(AdUpgradeMath.upkeep(256, 0, 0, 0) == 256 && AdUpgradeMath.upkeep(256, 1, 0, 0) == 410 && AdUpgradeMath.upkeep(256, 0, 1, 0) == 205
                && AdUpgradeMath.upkeep(256, 0, 9, 0) == 105 && AdUpgradeMath.upkeep(256, 0, 0, 1) == 448 && AdUpgradeMath.upkeep(256, 9, 0, 0) == 17593
                && AdUpgradeMath.upkeep(0, 3, 0, 0) == 0 && AdUpgradeMath.upkeep(1, 0, 4, 0) == 1,
                "ad upgrades: EU a tick x1.6 an overclocker, x0.8 an economizer (4 count), x1.75 an overdrive");
        check(AdUpgradeMath.shotEu(20000, 1) == 16000 && AdUpgradeMath.shotEu(1500, 4) == 614 && AdUpgradeMath.shotEu(1500, 0) == 1500,
                "ad upgrades: a shot's EU x0.8 an economizer");
        check(Math.abs(AdUpgradeMath.rangeMul(2) - 1.3225) < 1e-9 && Math.abs(AdUpgradeMath.rangeMul(9) - Math.pow(1.15, 4)) < 1e-9 && AdUpgradeMath.rangeMul(0) == 1,
                "ad upgrades: the overdrive's range x1.15 each, four count");
        int[] a = AdUpgradeMath.scope(300, 0, 600, 52), b = AdUpgradeMath.scope(0, -1000, 600, 52), c = AdUpgradeMath.scope(5, 5, 0, 52);
        check(a[0] == 26 && a[1] == 0 && b[0] == 0 && b[1] == -52 && c[0] == 0 && c[1] == 0, "ad upgrades: the scope - east right, north up, past the range on the rim");
        check("6,0".equals(AdUpgradeMath.seconds(120, ',')) && "0.4".equals(AdUpgradeMath.seconds(7, '.')) && "3,0".equals(AdUpgradeMath.seconds(60, ','))
                && "0,1".equals(AdUpgradeMath.seconds(2, ',')), "ad upgrades: the seconds' text (one decimal, the lang's sign)");
    }

    private static ItemStack up(UpgradeType t, int n) {
        ItemStack s = ModItems.upgrade.stackOf(t);
        s.stackSize = n;
        return s;
    }

    private static void tiles() {
        boolean cfg = ConfigSVA.adUpgrades;
        try {
            ConfigSVA.adUpgrades = true;
            TileEntityRadarSVA r = new TileEntityRadarSVA();
            TileEntityLauncherSVA l = new TileEntityLauncherSVA();
            TileEntityCommandPostSVA k = new TileEntityCommandPostSVA();
            check(r.upgrades.getSizeInventory() == 4 && r.upgrades.isItemValidForSlot(0, up(UpgradeType.UNIVERSAL_TRANSFORMER, 1))
                    && r.upgrades.isItemValidForSlot(0, up(UpgradeType.OVERDRIVE, 1)) && !r.upgrades.isItemValidForSlot(0, up(UpgradeType.PULLER, 1))
                    && l.upgrades.isItemValidForSlot(2, up(UpgradeType.PULLER, 1)) && !l.upgrades.isItemValidForSlot(2, up(UpgradeType.OVERDRIVE, 1))
                    && !k.upgrades.isItemValidForSlot(1, up(UpgradeType.OVERCLOCKER, 1)) && k.upgrades.isItemValidForSlot(1, up(UpgradeType.UNIVERSAL_TRANSFORMER, 1))
                    && !r.upgrades.isItemValidForSlot(0, up(UpgradeType.ADAPTIVE_TRANSFORMER, 1)) && !r.upgrades.isItemValidForSlot(0, new ItemStack(Items.iron_ingot))
                    && !r.upgrades.isItemValidForSlot(0, null) && r.upgrades.getInventoryStackLimit() == 64,
                    "ad upgrades: the slots take only the modules that fit the block (radar / launcher / post), no other item");
            ContainerAdSVA.UpgradeSlot slot = new ContainerAdSVA.UpgradeSlot(r, 0, 0, 0);
            check(slot.isItemValid(up(UpgradeType.TRANSFORMER, 1)) && !slot.isItemValid(up(UpgradeType.PULLER, 1)),
                    "ad upgrades: the screen's slot checks the same");
            // bare radar: HV in, an SV packet refused (simulated - no explosion)
            int bare = r.receiveEnergy(ForgeDirection.UNKNOWN, Tier.SV.getVoltage(), 1000, true);
            check(r.inputTier() == Tier.HV && !r.acceptsAnyVoltage() && bare == 0 && r.getMaxEnergyStored() == Tier.HV.getBuffer(),
                    "ad upgrades: a bare radar takes HV, refuses an SV packet (" + bare + "), holds the HV buffer");
            r.upgrades.setInventorySlotContents(0, up(UpgradeType.UNIVERSAL_TRANSFORMER, 1));
            r.upgrades.setInventorySlotContents(1, up(UpgradeType.ENERGY_STORAGE, 3));
            r.upgrades.setInventorySlotContents(2, up(UpgradeType.OVERCLOCKER, 2));
            r.upgrades.setInventorySlotContents(3, up(UpgradeType.ECONOMIZER, 1));
            int any = r.receiveEnergy(ForgeDirection.UNKNOWN, Tier.SV.getVoltage(), 1000, true);
            int oc = AdUpgradeMath.usefulOc(RadarMath.INTERVAL[RadarMath.SURVEY], 2, AdUpgradeMath.MIN_SCAN);
            check(r.acceptsAnyVoltage() && r.inputTier() == Tier.max() && any == 1000 && r.getMaxEnergyStored() == Tier.HV.getBuffer() + 30000
                    && r.scanInterval() == 59 && r.upkeep() == AdUpgradeMath.upkeep(256, oc, 1, 0) && r.upgradeCount(UpgradeType.OVERCLOCKER) == 2,
                    "ad upgrades: the radar with a universal transformer takes SV (" + any + "), 3 storages +30 000 EU, 2 overclockers scan every 59 ticks, "
                            + "an economizer in the upkeep (" + r.upkeep() + ")");
            r.upgrades.setInventorySlotContents(0, up(UpgradeType.TRANSFORMER, 2));
            check(!r.acceptsAnyVoltage() && r.inputTier() == Tier.IV, "ad upgrades: two transformers on an HV radar - IV in");
            ConfigSVA.adUpgrades = false;
            check(r.inputTier() == Tier.HV && r.scanInterval() == RadarMath.INTERVAL[RadarMath.SURVEY] && r.upkeep() == 256
                    && !r.upgrades.isItemValidForSlot(0, up(UpgradeType.TRANSFORMER, 1)), "ad upgrades: airdefense.upgrades off - the modules do nothing, "
                            + "none goes in");
            ConfigSVA.adUpgrades = true;
            l.upgrades.setInventorySlotContents(0, up(UpgradeType.OVERCLOCKER, 1));
            l.upgrades.setInventorySlotContents(1, up(UpgradeType.ECONOMIZER, 1));
            l.upgrades.setInventorySlotContents(2, up(UpgradeType.OVERDRIVE, 4));           // not a launcher's: never counts
            check(l.fireInterval() == 7 && l.shotCost() == 1200 && l.upkeep() == AdUpgradeMath.upkeep(32, 1, 1, 0) && l.upgradeCount(UpgradeType.OVERDRIVE) == 0,
                    "ad upgrades: the gun with an overclocker bursts every 7 ticks, an economizer: 1200 EU a burst; a module that doesn't fit counts nothing");
            k.upgrades.setInventorySlotContents(0, up(UpgradeType.ENERGY_STORAGE, 1));
            check(k.getMaxEnergyStored() == 12800 + 10000 && k.inputTier() == Tier.MV, "ad upgrades: the command post - 12 800 EU + a storage");
            check(ConfigSVA.adUpgrades && cfg, "ad upgrades: config default airdefense.upgrades = true");
        } finally {
            ConfigSVA.adUpgrades = cfg;
        }
    }

    private static void lang() throws Exception {
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        StringBuilder miss = new StringBuilder();
        for (int b = 0; b < 3; b++) {
            for (UpgradeType t : AdUpgradeMath.accepted(b)) {
                String key = "sva.ad.upg." + t.name().toLowerCase(java.util.Locale.ROOT);
                if (t == UpgradeType.OVERCLOCKER) {
                    for (String s : new String[] {".radar", ".gun", ".missile"}) {
                        need(ru, en, key + s, miss);
                    }
                } else {
                    need(ru, en, key, miss);
                }
            }
        }
        for (String s : new String[] {"sva.ad.eq.rel0", "sva.ad.eq.rel1", "sva.ad.eq.rel2", "sva.ad.l.burstWas", "sva.ad.l.reloadWas"}) {
            need(ru, en, s, miss);
        }
        check(miss.length() == 0, "ad upgrades: the dynamic lang keys (module effects, relations, the rate lines) in ru and en" + miss);
    }

    private static void need(Set<String> ru, Set<String> en, String k, StringBuilder miss) {
        if (ru == null || en == null || !ru.contains(k) || !en.contains(k)) {
            miss.append(' ').append(k);
        }
    }
}
