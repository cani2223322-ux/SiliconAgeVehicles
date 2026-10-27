package com.sva.inventory;

import com.sva.block.TileEntityLauncherSVA;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * Air-defence wave: the launcher's screen - its 4 ammunition slots (the gun's shells by the stack, one missile a tube)
 * and the player's inventory. The settings and the state travel apart (NetADSVA LAUNCHER_SET / D_LAUNCHER).
 * Layout matches client.GuiLauncherSVA: the slots at (8 + 20 i, SLOT_Y), the inventory under them.
 * Upgrades wave: the four upgrade slots (the core's modules that fit a launcher) beside the ammunition, at (UPG_X + 18 i,
 * SLOT_Y); container slots 0-3 ammunition, 4-7 upgrades, then the player's.
 */
public class ContainerLauncherSVA extends Container {

    /**
     * Code-only wave (item 41): the banner «КП ...: ПО КОДУ» and the readiness line take the rows over the slots. Review
     * 2026-10-27 (batch 3): the ammunition and the upgrade slots stand in a column each beside the player's inventory (SLOT_STEP
     * apart, from SLOT_Y down) - the window is 240 px high again (262 with them in a row of their own: at 1280x720, auto GUI
     * scale, 240 scaled px, its title and bottom went off the screen).
     */
    public static final int SLOT_X = 8, SLOT_Y = 156, SLOT_STEP = 18, UPG_X = 236, INV_X = 49, INV_Y = 156;

    public final TileEntityLauncherSVA tile;

    public ContainerLauncherSVA(InventoryPlayer inv, TileEntityLauncherSVA tile) {
        this.tile = tile;
        for (int i = 0; i < TileEntityLauncherSVA.SLOTS; i++) {
            addSlotToContainer(new Slot(tile, i, SLOT_X, SLOT_Y + SLOT_STEP * i) {
                @Override
                public boolean isItemValid(ItemStack s) {
                    return ContainerLauncherSVA.this.tile.isAmmo(s);
                }

                @Override
                public int getSlotStackLimit() {
                    return ContainerLauncherSVA.this.tile.getInventoryStackLimit();
                }
            });
        }
        for (int i = 0; i < com.sva.airdefense.AdUpgradeMath.SLOTS; i++) {
            addSlotToContainer(new ContainerAdSVA.UpgradeSlot(tile, i, UPG_X, SLOT_Y + SLOT_STEP * i));
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                addSlotToContainer(new Slot(inv, c + r * 9 + 9, INV_X + c * 18, INV_Y + r * 18));
            }
        }
        for (int c = 0; c < 9; c++) {
            addSlotToContainer(new Slot(inv, c, INV_X + c * 18, INV_Y + 58));
        }
    }

    @Override
    public boolean canInteractWith(EntityPlayer p) {
        return tile.isUseableByPlayer(p);
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer p, int i) {
        Slot s = (Slot) inventorySlots.get(i);
        if (s == null || !s.getHasStack()) {
            return null;
        }
        ItemStack st = s.getStack(), copy = st.copy();
        int n = TileEntityLauncherSVA.SLOTS + com.sva.airdefense.AdUpgradeMath.SLOTS;
        if (i < n) {
            if (!mergeItemStack(st, n, inventorySlots.size(), true)) {
                return null;
            }
        } else if (tile.upgradeAccepted(st)) {
            if (!mergeItemStack(st, TileEntityLauncherSVA.SLOTS, n, false)) {
                return null;
            }
        } else {
            if (!tile.isAmmo(st)) {
                return null;
            }
            int before = st.stackSize;
            tile.insert(st);
            if (st.stackSize == before) {
                return null;
            }
        }
        if (st.stackSize <= 0) {
            s.putStack(null);
        } else {
            s.onSlotChanged();
        }
        return copy;
    }
}
