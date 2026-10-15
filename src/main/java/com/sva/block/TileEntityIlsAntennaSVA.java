package com.sva.block;

import java.util.ArrayList;
import java.util.List;

import com.sva.airport.IlsCat;
import com.sva.airport.IlsPairing;
import com.sva.vehicle.LinerIls;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

/**
 * Airport wave B1: the master of an ILS antenna (block.BlockIlsAntennaSVA): the localizer's 5 x 1 array (meta LOC: four
 * BlockAptPartSVA elements across the placer's facing, two each side) or the glide slope's mast (meta GS: two parts above).
 * It keeps the settings - the localizer's ident and frequency, the mast's angle - and the facing; it puts itself into the
 * save's airport list (AirportDataSVA - the pairing, the monitor), and mirrors the station's status for its model's lamps
 * and its screen (the category, the flags, the issues, the inspection - the server sets them when they change).
 */
public class TileEntityIlsAntennaSVA extends TileEntity {

    public static final int LOC = 0, GS = 1;

    /** The placer's facing (MathHelper: 0 south +z, 1 west -x, 2 north -z, 3 east +x). */
    public int facing;
    public String ident = "";
    public int freq = LinerIls.FREQ_DEFAULT;
    public double angle = LinerIls.ANGLE_DEFAULT;
    /** The station's status (server -> client): the category (-1 not paired), the flags, the inspection word, the issues, the runway. */
    public int cat = -1, flags, calib;
    public int[] issues = new int[0];
    public String rwy = "";

    @Override
    public boolean canUpdate() {
        return false;
    }

    public int kind() {
        return worldObj == null ? LOC : getBlockMetadata() & 1;
    }

    /** The parts' offsets from the master {dx, dy, dz} for a kind and facing. */
    public static int[][] parts(int kind, int facing) {
        if (kind == GS) {
            return new int[][] {{0, 1, 0}, {0, 2, 0}};
        }
        boolean alongX = (facing & 1) == 0;                        // looking along z: the array runs along x
        int[][] o = new int[4][];
        int[] d = {-2, -1, 1, 2};
        for (int i = 0; i < 4; i++) {
            o[i] = alongX ? new int[] {d[i], 0, 0} : new int[] {0, 0, d[i]};
        }
        return o;
    }

    public int[][] parts() {
        return parts(kind(), facing);
    }

    /** The parts standing now. */
    public boolean[] present() {
        int[][] p = parts();
        boolean[] b = new boolean[p.length];
        for (int i = 0; i < p.length; i++) {
            int x = xCoord + p[i][0], y = yCoord + p[i][1], z = zCoord + p[i][2];
            b[i] = worldObj != null && y >= 0 && y < 256 && worldObj.getBlock(x, y, z) instanceof BlockAptPartSVA;
        }
        return b;
    }

    /** The antenna at a key is whole (its master and every part). */
    public static boolean intact(World w, String key) {
        int[] p = parse(key);
        if (p == null || !w.blockExists(p[0], p[1], p[2])) {
            return true;                                             // unknown / unloaded: as last known
        }
        TileEntity te = w.getTileEntity(p[0], p[1], p[2]);
        if (!(te instanceof TileEntityIlsAntennaSVA)) {
            return false;
        }
        for (boolean b : ((TileEntityIlsAntennaSVA) te).present()) {
            if (!b) {
                return false;
            }
        }
        return true;
    }

    static int[] parse(String key) {
        String[] k = key.split(":");
        if (k.length != 4) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(k[1]), Integer.parseInt(k[2]), Integer.parseInt(k[3])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String key() {
        return AirportDataSVA.key(worldObj, xCoord, yCoord, zCoord);
    }

    /** Server: into the save's airport list (placed / set). */
    public void register() {
        if (worldObj == null || worldObj.isRemote) {
            return;
        }
        AirportDataSVA d = AirportDataSVA.get(worldObj);
        if (d == null) {
            return;
        }
        if (kind() == LOC) {
            IlsPairing.LocAnt a = new IlsPairing.LocAnt();
            a.key = key();
            a.dim = worldObj.provider.dimensionId;
            a.x = xCoord + 0.5;
            a.z = zCoord + 0.5;
            a.y = yCoord;
            a.ident = ident;
            a.freq = freq;
            d.putLoc(a);
        } else {
            IlsPairing.GsAnt g = new IlsPairing.GsAnt();
            g.key = key();
            g.dim = worldObj.provider.dimensionId;
            g.x = xCoord + 0.5;
            g.z = zCoord + 0.5;
            g.y = yCoord;
            g.angle = angle;
            d.putGs(g);
        }
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    /** Server: the screen's values (sanitised). */
    public void set(String newIdent, int newFreq, double newAngle) {
        String id = LinerIls.cleanIdent(newIdent);
        if (id.length() > 0) {
            ident = id;
        }
        freq = LinerIls.clampFreq(newFreq);
        angle = LinerIls.clampAngle(newAngle);
        register();
    }

    /** Server: the station's status changed (AirportDataSVA) - the model's lamps and the screen. */
    public void status(int newCat, int newFlags, int newCalib, List<Integer> newIssues, String newRwy) {
        int[] is = new int[newIssues == null ? 0 : newIssues.size()];
        for (int i = 0; i < is.length; i++) {
            is[i] = newIssues.get(i).intValue();
        }
        boolean same = cat == newCat && flags == newFlags && calib == newCalib && java.util.Arrays.equals(is, issues) && rwy.equals(newRwy);
        cat = newCat;
        flags = newFlags;
        calib = newCalib;
        issues = is;
        rwy = newRwy == null ? "" : newRwy;
        if (!same && worldObj != null) {
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }

    /** The issues as a list (the screen). */
    public List<Integer> issueList() {
        List<Integer> l = new ArrayList<Integer>();
        for (int i : issues) {
            if (i >= 0 && i < IlsCat.ISSUES) {
                l.add(Integer.valueOf(i));
            }
        }
        return l;
    }

    private void writeCommon(NBTTagCompound t) {
        t.setInteger("Facing", facing);
        t.setString("Ident", ident);
        t.setInteger("Freq", freq);
        t.setDouble("Angle", angle);
    }

    private void readCommon(NBTTagCompound t) {
        facing = t.getInteger("Facing") & 3;
        ident = LinerIls.cleanIdent(t.getString("Ident"));
        freq = t.hasKey("Freq") ? LinerIls.clampFreq(t.getInteger("Freq")) : LinerIls.FREQ_DEFAULT;
        angle = t.hasKey("Angle") ? LinerIls.clampAngle(t.getDouble("Angle")) : LinerIls.ANGLE_DEFAULT;
    }

    @Override
    public void writeToNBT(NBTTagCompound t) {
        super.writeToNBT(t);
        writeCommon(t);
    }

    @Override
    public void readFromNBT(NBTTagCompound t) {
        super.readFromNBT(t);
        readCommon(t);
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound t = new NBTTagCompound();
        writeCommon(t);
        t.setInteger("Cat", cat);
        t.setInteger("Flags", flags);
        t.setInteger("Calib", calib);
        t.setIntArray("Issues", issues);
        t.setString("Rwy", rwy);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, t);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        NBTTagCompound t = pkt.func_148857_g();
        readCommon(t);
        cat = t.getInteger("Cat");
        flags = t.getInteger("Flags");
        calib = t.getInteger("Calib");
        issues = t.getIntArray("Issues");
        rwy = t.getString("Rwy");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public double getMaxRenderDistanceSquared() {
        return 384.0 * 384.0;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public AxisAlignedBB getRenderBoundingBox() {
        return AxisAlignedBB.getBoundingBox(xCoord - 3, yCoord - 1, zCoord - 3, xCoord + 4, yCoord + 4, zCoord + 4);
    }
}
