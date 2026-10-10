package com.sva.block;

import com.sc.energy.TileEntityEnergyBase;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * Fast charging (2026-10-10): an IC2 energy storage (BatBox, CESU, MFE, MFSU - ic2.api.tile.IEnergyStorage) whose output
 * faces the charging station feeds the vehicle directly, like the core's storages. Only loaded when IC2 is (the station
 * checks Loader.isModLoaded first), so nothing of IC2's is touched without it.
 */
final class Ic2StorageLinkSVA {

    private Ic2StorageLinkSVA() {
    }

    /** An IC2 storage that emits towards the station (`towards` = from the storage to the station). */
    static boolean feeds(TileEntity te, TileEntity station, ForgeDirection towards) {
        return te instanceof ic2.api.tile.IEnergyStorage && te instanceof ic2.api.energy.tile.IEnergyEmitter && !(te instanceof TileEntityEnergyBase)
                && ((ic2.api.energy.tile.IEnergyEmitter) te).emitsEnergyTo(station, towards);
    }

    /** EU stored (the screen). */
    static int stored(TileEntity te) {
        return te instanceof ic2.api.tile.IEnergyStorage ? Math.max(0, ((ic2.api.tile.IEnergyStorage) te).getStored()) : 0;
    }

    /** Takes up to `max` EU out through IC2's own accounting (addEnergy with a negative amount). @return EU taken */
    static int take(TileEntity te, int max) {
        if (!(te instanceof ic2.api.tile.IEnergyStorage) || max <= 0) {
            return 0;
        }
        ic2.api.tile.IEnergyStorage s = (ic2.api.tile.IEnergyStorage) te;
        int n = Math.min(max, s.getStored());
        if (n <= 0) {
            return 0;
        }
        s.addEnergy(-n);
        te.markDirty();
        return n;
    }
}
