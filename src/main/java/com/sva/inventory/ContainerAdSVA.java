package com.sva.inventory;

import com.sva.airdefense.AdUpgradeMath;
import com.sva.block.TileEntityAirDefenseSVA;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * Upgrades wave (2026-10-12): an air-defence block's equipment screen (the radar's; the command post's «Оборудование»
 * tab) - its four upgrade slots (the core's modules, only those that fit: TileEntityAirDefenseSVA.upgradeAccepted) and the
 * player's inventory. The state travels apart (NetADSVA D_EQUIP every 10 ticks while open). Layout matches
 * client.GuiAdEquipSVA: the slots at (UPG_X + 18 i, UPG_Y), the inventory under them.
 */
public class ContainerAdSVA extends Container {

    public static final int UPG_X = 8, UPG_Y = 138, INV_X = 49, INV_Y = 160;

    public final TileEntityAirDefenseSVA tile;

    public ContainerAdSVA(InventoryPlayer inv, TileEntityAirDefenseSVA tile) {
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

    /** An upgrade slot: only a module that fits this block. */
    public static class UpgradeSlot extends Slot {
        private final TileEntityAirDefenseSVA tile;

        public UpgradeSlot(TileEntityAirDefenseSVA tile, int i, int x, int y) {
            super(tile.upgrades, i, x, y);
            this.tile = tile;
        }

        @Override
        public boolean isItemValid(ItemStack s) {
            return tile.upgradeAccepted(s);
        }
    }
}
