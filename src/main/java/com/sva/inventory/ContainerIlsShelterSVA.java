package com.sva.inventory;

import com.sva.airdefense.AdUpgradeMath;
import com.sva.block.TileEntityIlsShelterSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ICrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * Airport wave B1: «Аппаратная ILS»'s screen - its four upgrade slots (only the modules that fit) and the player's
 * inventory; the state (energy, its buffer, the upkeep, the antennas served, powered) goes as progress bars (two shorts a
 * number). Layout matches client.GuiIlsShelterSVA.
 */
public class ContainerIlsShelterSVA extends Container {

    public static final int UPG_X = 8, UPG_Y = 84, INV_X = 8, INV_Y = 110;

    public final TileEntityIlsShelterSVA tile;
    /** Client mirror: energy, buffer, upkeep, antennas served, powered (1/0). */
    public final int[] vals = new int[5];
    private final int[] sent = {-1, -1, -1, -1, -1};

    public ContainerIlsShelterSVA(InventoryPlayer inv, TileEntityIlsShelterSVA tile) {
        this.tile = tile;
        for (int i = 0; i < AdUpgradeMath.SLOTS; i++) {
            addSlotToContainer(new UpgradeSlot(tile, i, UPG_X + 18 * i, UPG_Y));
        }
        for (int r = 0; r < 3; r++) {
            for (int k = 0; k < 9; k++) {
                addSlotToContainer(new Slot(inv, k + r * 9 + 9, INV_X + k * 18, INV_Y + r * 18));
            }
        }
        for (int k = 0; k < 9; k++) {
            addSlotToContainer(new Slot(inv, k, INV_X + k * 18, INV_Y + 58));
        }
    }

    private int[] now() {
        return new int[] {tile.getEnergyStored(), tile.getMaxEnergyStored(), tile.upkeep(), tile.servedCount(), tile.isPowered() ? 1 : 0};
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        int[] v = now();
        for (int i = 0; i < v.length; i++) {
            if (v[i] != sent[i]) {
                for (Object o : crafters) {
                    ((ICrafting) o).sendProgressBarUpdate(this, i * 2, v[i] & 0xFFFF);
                    ((ICrafting) o).sendProgressBarUpdate(this, i * 2 + 1, v[i] >>> 16 & 0xFFFF);
                }
                sent[i] = v[i];
            }
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void updateProgressBar(int id, int value) {
        int i = id / 2;
        if (i < 0 || i >= vals.length) {
            return;
        }
        if (id % 2 == 0) {
            vals[i] = vals[i] & 0xFFFF0000 | value & 0xFFFF;
        } else {
            vals[i] = vals[i] & 0xFFFF | (value & 0xFFFF) << 16;
        }
    }

    @Override
    public boolean canInteractWith(EntityPlayer p) {
        return tile.upgrades.isUseableByPlayer(p);
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer p, int i) {
        Slot s = (Slot) inventorySlots.get(i);
        if (s == null || !s.getHasStack()) {
            return null;
        }
        ItemStack st = s.getStack(), copy = st.copy();
        int n = AdUpgradeMath.SLOTS;
        if (i < n) {
            if (!mergeItemStack(st, n, inventorySlots.size(), true)) {
                return null;
            }
        } else if (!tile.upgradeAccepted(st) || !mergeItemStack(st, 0, n, false)) {
            return null;
        }
        if (st.stackSize <= 0) {
            s.putStack(null);
        } else {
            s.onSlotChanged();
        }
        return st.stackSize == copy.stackSize ? null : copy;
    }

    public static class UpgradeSlot extends Slot {
        private final TileEntityIlsShelterSVA tile;

        public UpgradeSlot(TileEntityIlsShelterSVA tile, int i, int x, int y) {
            super(tile.upgrades, i, x, y);
            this.tile = tile;
        }

        @Override
        public boolean isItemValid(ItemStack s) {
            return tile.upgradeAccepted(s);
        }
    }
}
