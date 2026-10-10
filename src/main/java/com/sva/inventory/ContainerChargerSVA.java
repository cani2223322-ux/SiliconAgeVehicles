package com.sva.inventory;

import com.sc.util.ArmorGasSC.Gas;
import com.sva.block.TileEntityChargerSVA;
import com.sva.block.TileEntityGasColumnSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.item.ItemStationModuleSVA;
import com.sva.net.NetSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ICrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * Charging station screen: 4 upgrades + 3 modules + the player's 36. Syncs (as progress bars, ints split in 16-bit
 * halves) the buffer, the measured rates, the served vehicle, the platform, status / settings and the devices beside
 * it; the served vehicle's owner name goes in a NetSVA.StationInfo message when it changes. Fast charging (2026-10-10): the
 * boost heat, the stations on the same platform (count, rate and measured intake together), the linked energy storages
 * and supercapacitors (count, EU) and the flags (fast charge, boost on / allowed, plates' master, SV station).
 */
public class ContainerChargerSVA extends Container {

    // layout (shared with GuiChargerSVA): item positions of slot tops
    public static final int UPG_X = 258, UPG_Y = 31, UPG_STEP = 18, MOD_X = 258, MOD_Y = 61, MOD_STEP = 22, INV_X = 193, INV_Y = 153;
    public static final int PLAYER_START = TileEntityChargerSVA.SIZE, PLAYER_END = PLAYER_START + 36;
    // synced value ids
    public static final int V_E = 0, V_MAX = 2, V_EU_T = 4, V_GAS = 6, V_VEH = 7, V_PLATES = 9, V_SX = 10, V_SZ = 11, V_STATUS = 12,
            V_SET = 13, V_STEEL = 14, V_CHESTS = 15, V_COLUMN = 16, V_COL_GAS = 17, V_EDGE = V_COL_GAS + 8, V_AVIA = V_EDGE + 1,
            V_RATE = V_AVIA + 1, V_TIER = V_RATE + 2, V_HEAT = V_TIER + 1, V_PEERS = V_HEAT + 1, V_SUM = V_PEERS + 1, V_SUM_EU = V_SUM + 2,
            V_STORES = V_SUM_EU + 2, V_STORE_E = V_STORES + 1, V_CAPS = V_STORE_E + 2, V_CAP_E = V_CAPS + 1, V_FLAGS = V_CAP_E + 2,
            V_COUNT = V_FLAGS + 1;
    /** Extra bits in V_SET: modules present. */
    public static final int M_ACCESS = 8, M_GAS = 16, M_REPAIR = 32;
    /** V_FLAGS bits. */
    public static final int F_FAST = 1, F_BOOST = 2, F_BOOST_CFG = 4, F_MASTER = 8, F_SV = 16;
    private static final int[] WIDE = {V_E, V_MAX, V_EU_T, V_VEH, V_RATE, V_SUM, V_SUM_EU, V_STORE_E, V_CAP_E};

    private final TileEntityChargerSVA tile;
    private final int[] last = new int[V_COUNT];
    private final int[] client = new int[V_COUNT];
    private int lastVehicle = Integer.MIN_VALUE;
    /** Client: the served vehicle's owner (StationInfo). */
    public String vehicleOwner = "";

    public ContainerChargerSVA(InventoryPlayer inv, TileEntityChargerSVA te) {
        this.tile = te;
        for (int i = 0; i < TileEntityChargerSVA.UPGRADE_SLOTS; i++) {
            addSlotToContainer(new Filtered(te, TileEntityChargerSVA.FIRST_UPGRADE + i, UPG_X + i * UPG_STEP, UPG_Y));
        }
        for (int i = 0; i < TileEntityChargerSVA.MODULE_SLOTS; i++) {
            addSlotToContainer(new Filtered(te, TileEntityChargerSVA.FIRST_MODULE + i, MOD_X + i * MOD_STEP, MOD_Y));
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                addSlotToContainer(new Slot(inv, c + r * 9 + 9, INV_X + c * 18, INV_Y + r * 18));
            }
        }
        for (int c = 0; c < 9; c++) {
            addSlotToContainer(new Slot(inv, c, INV_X + c * 18, INV_Y + 58));
        }
        for (int i = 0; i < last.length; i++) {
            last[i] = Integer.MIN_VALUE;
        }
    }

    public TileEntityChargerSVA getTile() {
        return tile;
    }

    public boolean isAt(int x, int y, int z) {
        return tile.xCoord == x && tile.yCoord == y && tile.zCoord == z;
    }

    @Override
    public boolean canInteractWith(EntityPlayer p) {
        return tile.isUseableByPlayer(p);
    }

    // ------------------------------------------------------------------ sync

    private int[] values() {
        int[] v = new int[V_COUNT];
        split(v, V_E, tile.getEnergyStored());
        split(v, V_MAX, tile.getMaxEnergyStored());
        split(v, V_EU_T, tile.getMeasuredEu());
        v[V_GAS] = Math.min(32767, tile.getMeasuredGas());
        EntityVehicleSVA cur = tile.getCurrent();
        split(v, V_VEH, cur == null ? 0 : cur.getEntityId() + 1);
        v[V_PLATES] = tile.plateCount();
        int[] s = tile.span();
        v[V_SX] = s[0];
        v[V_SZ] = s[1];
        v[V_STATUS] = tile.getStatus();
        v[V_SET] = tile.getSettings() & TileEntityChargerSVA.SET_DEFAULT | (tile.hasModule(ItemStationModuleSVA.ACCESS) ? M_ACCESS : 0)
                | (tile.hasModule(ItemStationModuleSVA.GAS) ? M_GAS : 0) | (tile.hasModule(ItemStationModuleSVA.REPAIR) ? M_REPAIR : 0);
        v[V_STEEL] = Math.min(32767, tile.steelAvailable());
        v[V_CHESTS] = tile.chestCount();
        TileEntityGasColumnSVA col = tile.column();
        v[V_COLUMN] = col == null ? 0 : 1;
        for (Gas g : Gas.values()) {
            if (g.ordinal() < 8) {
                v[V_COL_GAS + g.ordinal()] = col == null ? 0 : Math.min(32767, col.amount(g));
            }
        }
        v[V_EDGE] = tile.getEdgePlates();
        v[V_AVIA] = tile.getAviaPlates();
        split(v, V_RATE, cur == null ? tile.rate() : tile.rateFor(tile.bonusFor(cur)));
        v[V_TIER] = tile.inputTier().ordinal();
        v[V_HEAT] = tile.getHeat();
        v[V_PEERS] = tile.peerStations().size();
        split(v, V_SUM, v[V_PEERS] > 0 ? tile.summedRate() : 0);
        split(v, V_SUM_EU, v[V_PEERS] > 0 ? tile.summedMeasured() : 0);
        v[V_STORES] = tile.storageCount();
        split(v, V_STORE_E, (int) Math.min(Integer.MAX_VALUE, tile.storageEnergy()));
        long capE = 0;
        int caps = 0;
        for (com.sva.block.TileEntitySupercapSVA c : tile.supercaps()) {
            capE += c.getEnergyStored();
            caps++;
        }
        v[V_CAPS] = caps;
        split(v, V_CAP_E, (int) Math.min(Integer.MAX_VALUE, capE));
        v[V_FLAGS] = (tile.hasFastCharge() ? F_FAST : 0) | ((tile.getSettings() & TileEntityChargerSVA.SET_BOOST) != 0 ? F_BOOST : 0)
                | (com.sva.ConfigSVA.stationBoost ? F_BOOST_CFG : 0) | (tile.isMaster() ? F_MASTER : 0)
                | (tile.getTier() == com.sc.energy.Tier.SV ? F_SV : 0);
        return v;
    }

    private static void split(int[] v, int at, int value) {
        v[at] = value & 0xFFFF;
        v[at + 1] = value >>> 16;
    }

    @Override
    public void addCraftingToCrafters(ICrafting c) {
        super.addCraftingToCrafters(c);
        int[] v = values();
        for (int i = 0; i < v.length; i++) {
            c.sendProgressBarUpdate(this, i, v[i]);
        }
        lastVehicle = Integer.MIN_VALUE;
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        int[] v = values();
        for (int i = 0; i < v.length; i++) {
            if (v[i] != last[i]) {
                for (Object o : crafters) {
                    ((ICrafting) o).sendProgressBarUpdate(this, i, v[i]);
                }
                last[i] = v[i];
            }
        }
        EntityVehicleSVA cur = tile.getCurrent();
        int id = cur == null ? -1 : cur.getEntityId();
        if (id != lastVehicle) {
            lastVehicle = id;
            String owner = cur == null || cur.state.ownerName == null ? "" : cur.state.ownerName;
            for (Object o : crafters) {
                if (o instanceof EntityPlayerMP) {
                    NetSVA.CHANNEL.sendTo(new NetSVA.StationInfo(owner), (EntityPlayerMP) o);
                }
            }
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void updateProgressBar(int id, int value) {
        if (id < 0 || id >= client.length) {
            return;
        }
        client[id] = value & 0xFFFF;
        tile.setEnergyStoredClient(get(V_E));
    }

    /** Client: a synced value (two-slot ones joined). */
    public int get(int id) {
        for (int w : WIDE) {
            if (id == w) {
                return client[id] | client[id + 1] << 16;
            }
        }
        return (short) client[id];
    }

    // ------------------------------------------------------------------ shift-click

    @Override
    public ItemStack transferStackInSlot(EntityPlayer p, int index) {
        Slot slot = (Slot) inventorySlots.get(index);
        if (slot == null || !slot.getHasStack()) {
            return null;
        }
        ItemStack s = slot.getStack();
        ItemStack copy = s.copy();
        if (index < PLAYER_START) {
            if (!mergeItemStack(s, PLAYER_START, PLAYER_END, true)) {
                return null;
            }
        } else if (TileEntityChargerSVA.upgradeAccepted(s)) {
            if (!mergeItemStack(s, TileEntityChargerSVA.FIRST_UPGRADE, TileEntityChargerSVA.FIRST_UPGRADE + TileEntityChargerSVA.UPGRADE_SLOTS, false)) {
                return null;
            }
        } else if (TileEntityChargerSVA.moduleAccepted(s)) {
            if (!mergeModule(s)) {
                return null;
            }
        } else if (index < PLAYER_START + 27) {
            if (!mergeItemStack(s, PLAYER_START + 27, PLAYER_END, false)) {
                return null;
            }
        } else if (!mergeItemStack(s, PLAYER_START, PLAYER_START + 27, false)) {
            return null;
        }
        if (s.stackSize == 0) {
            slot.putStack(null);
        } else {
            slot.onSlotChanged();
        }
        return s.stackSize == copy.stackSize ? null : copy;
    }

    /** A module goes into the first empty module slot, one at a time. */
    private boolean mergeModule(ItemStack s) {
        for (int i = TileEntityChargerSVA.FIRST_MODULE; i < TileEntityChargerSVA.SIZE; i++) {
            Slot m = (Slot) inventorySlots.get(i);
            if (!m.getHasStack()) {
                m.putStack(s.splitStack(1));
                return true;
            }
        }
        return false;
    }

    static class Filtered extends Slot {
        Filtered(TileEntityChargerSVA te, int i, int x, int y) {
            super(te, i, x, y);
        }

        @Override
        public boolean isItemValid(ItemStack s) {
            return inventory.isItemValidForSlot(getSlotIndex(), s);
        }

        @Override
        public int getSlotStackLimit() {
            return getSlotIndex() >= TileEntityChargerSVA.FIRST_MODULE ? 1 : 64;
        }
    }
}
