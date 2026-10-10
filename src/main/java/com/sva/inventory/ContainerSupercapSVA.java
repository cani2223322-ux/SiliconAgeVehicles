package com.sva.inventory;

import com.sva.block.TileEntitySupercapSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ICrafting;
import net.minecraft.item.ItemStack;

/** The supercapacitor screen: no slots; syncs (16-bit halves) its charge, the grid input, the output and the stations beside it. */
public class ContainerSupercapSVA extends Container {

    public static final int V_E = 0, V_IN = 2, V_OUT = 4, V_STATIONS = 6, V_COUNT = 7;

    private final TileEntitySupercapSVA tile;
    private final int[] last = new int[V_COUNT];
    private final int[] client = new int[V_COUNT];

    public ContainerSupercapSVA(TileEntitySupercapSVA te) {
        this.tile = te;
        for (int i = 0; i < last.length; i++) {
            last[i] = Integer.MIN_VALUE;
        }
    }

    public TileEntitySupercapSVA getTile() {
        return tile;
    }

    @Override
    public boolean canInteractWith(EntityPlayer p) {
        return tile.getWorldObj() != null && tile.getWorldObj().getTileEntity(tile.xCoord, tile.yCoord, tile.zCoord) == tile
                && p.getDistanceSq(tile.xCoord + 0.5, tile.yCoord + 0.5, tile.zCoord + 0.5) <= 64;
    }

    private int[] values() {
        int[] v = new int[V_COUNT];
        split(v, V_E, tile.getEnergyStored());
        split(v, V_IN, tile.getMeasuredIn());
        split(v, V_OUT, tile.getMeasuredOut());
        v[V_STATIONS] = tile.stationCount();
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
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void updateProgressBar(int id, int value) {
        if (id >= 0 && id < client.length) {
            client[id] = value & 0xFFFF;
        }
    }

    /** Client: a synced value. */
    public int get(int id) {
        if (id == V_E || id == V_IN || id == V_OUT) {
            return client[id] | client[id + 1] << 16;
        }
        return (short) client[id];
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer p, int index) {
        return null;
    }
}
