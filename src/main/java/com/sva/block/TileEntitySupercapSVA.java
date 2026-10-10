package com.sva.block;

import com.sc.energy.Tier;
import com.sc.energy.TileEntityEnergyBase;
import com.sva.platform.PlatformMath;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * «Суперконденсатор станции» (fast charging, 2026-10-10): a 50M EU buffer placed against a charging station. It fills
 * slowly from the grid - any voltage (nothing overvolts it, like the Universal Transformer), at most CAP_INPUT = 2 048 EU
 * a tick (~20 min from empty) - and the station beside it empties it into the vehicle it charges at CAP_DUMP = 500 000
 * EU a tick (all stations together; 50M in 5 s), on top of the station's own rate. It never gives energy back to the grid.
 * Comparator: its fill. The block's sides show the fill in 5 steps (description packet).
 */
public class TileEntitySupercapSVA extends TileEntityEnergyBase implements KeptDataSVA.Keeper {

    public static final int LEVELS = 5, SCAN_EVERY = 20;
    private static final String D_LEVEL = "Lv";

    private long inTick = -1, outTick = -1;
    private int takenIn, givenOut;
    // measured (screen)
    private long inWindow, outWindow;
    private int windowTicks, measuredIn, measuredOut;
    private int stations, scan;
    private int lastComparator = -1, level = -1;
    // client
    private int clientLevel;

    public TileEntitySupercapSVA() {
        super(Tier.SV);
    }

    @Override
    public boolean acceptsAnyVoltage() {
        return true;
    }

    @Override
    public Tier inputTier() {
        return Tier.max();
    }

    @Override
    public int getMaxEnergyStored() {
        return PlatformMath.CAP_BUFFER;
    }

    private void roll() {
        long now = worldObj == null ? 0 : worldObj.getTotalWorldTime();
        if (now != inTick) {
            inTick = now;
            takenIn = 0;
        }
    }

    /** At most CAP_INPUT a tick from the grid, whatever the voltage. */
    @Override
    public int receiveEnergy(ForgeDirection from, int voltage, int amount, boolean simulate) {
        roll();
        int can = PlatformMath.capInput(amount, getEnergyStored(), takenIn);
        if (can <= 0) {
            return 0;
        }
        int got = super.receiveEnergy(from, voltage, can, simulate);
        if (!simulate && got > 0) {
            takenIn += got;
            inWindow += got;
        }
        return got;
    }

    @Override
    public int demandedEnergy() {
        roll();
        return PlatformMath.capInput(Integer.MAX_VALUE, getEnergyStored(), takenIn);
    }

    /** A station beside it: up to CAP_DUMP this tick (shared by every station touching it), never past `room`. @return EU taken */
    public int dump(double room) {
        long now = worldObj == null ? 0 : worldObj.getTotalWorldTime();
        if (now != outTick) {
            outTick = now;
            givenOut = 0;
        }
        int n = PlatformMath.capDump(getEnergyStored(), givenOut, room);
        if (n > 0) {
            removeEnergy(n);
            givenOut += n;
            outWindow += n;
        }
        return n;
    }

    /** What the vehicle didn't take back in (never lost). */
    public void refund(int eu) {
        if (eu > 0) {
            addEnergy(eu);
            givenOut = Math.max(0, givenOut - eu);
            outWindow = Math.max(0, outWindow - eu);
        }
    }

    public int getMeasuredIn() {
        return measuredIn;
    }

    public int getMeasuredOut() {
        return measuredOut;
    }

    /** Charging stations touching it (screen). */
    public int stationCount() {
        return stations;
    }

    public int comparatorLevel() {
        return PlatformMath.fillSignal(getEnergyStored(), getMaxEnergyStored());
    }

    public int getClientLevel() {
        return clientLevel;
    }

    /** World test / commands: fill the buffer. */
    public void fillForTest(int eu) {
        addEnergy(eu);
    }

    @Override
    public boolean canUpdate() {
        return true;
    }

    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote) {
            return;
        }
        if (--scan <= 0) {
            scan = SCAN_EVERY;
            int n = 0;
            for (ForgeDirection d : ForgeDirection.VALID_DIRECTIONS) {
                TileEntity te = worldObj.blockExists(xCoord + d.offsetX, yCoord + d.offsetY, zCoord + d.offsetZ)
                        ? worldObj.getTileEntity(xCoord + d.offsetX, yCoord + d.offsetY, zCoord + d.offsetZ) : null;
                if (te instanceof TileEntityChargerSVA && !te.isInvalid()) {
                    n++;
                }
            }
            stations = n;
        }
        if (++windowTicks >= 20) {
            measuredIn = (int) (inWindow / windowTicks);
            measuredOut = (int) Math.min(Integer.MAX_VALUE, outWindow / windowTicks);
            inWindow = 0;
            outWindow = 0;
            windowTicks = 0;
        }
        int cmp = comparatorLevel();
        if (cmp != lastComparator) {
            lastComparator = cmp;
            worldObj.func_147453_f(xCoord, yCoord, zCoord, getBlockType());
        }
        int lv = level(getEnergyStored(), getMaxEnergyStored());
        if (lv != level) {
            level = lv;
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }

    /** The sides' look: 0 empty .. 4 full (any charge shows at least 1). */
    public static int level(long stored, long max) {
        if (stored <= 0 || max <= 0) {
            return 0;
        }
        return (int) Math.max(1, Math.min(LEVELS - 1, Math.round(stored * (LEVELS - 1.0) / max)));
    }

    // ------------------------------------------------------------------ ТБ-1: the item keeps the charge

    @Override
    public void writeKept(NBTTagCompound t) {
        if (getEnergyStored() > 0) {
            t.setInteger(KeptDataSVA.K_ENERGY, getEnergyStored());
        }
    }

    @Override
    public void readKept(NBTTagCompound t) {
        restoreEnergy(Math.min(PlatformMath.CAP_BUFFER, KeptDataSVA.energy(t)));
    }

    // ------------------------------------------------------------------ sync

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound t = new NBTTagCompound();
        t.setInteger(D_LEVEL, Math.max(0, level(getEnergyStored(), getMaxEnergyStored())));
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, t);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        int was = clientLevel;
        clientLevel = pkt.func_148857_g().getInteger(D_LEVEL);
        if (was != clientLevel && worldObj != null) {
            worldObj.markBlockRangeForRenderUpdate(xCoord, yCoord, zCoord, xCoord, yCoord, zCoord);
        }
    }
}
