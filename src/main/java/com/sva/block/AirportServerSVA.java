package com.sva.block;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sva.airport.IlsPairing;
import com.sva.net.NetAptSVA;
import com.sva.vehicle.LinerIls;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

/**
 * Airport wave B1: the server's side of the airport - every tick each loaded dimension's AirportDataSVA.tick (the monitor,
 * the flight inspections, the RVR, the weather); the antennas' tiles told their station's status (their models' lamps,
 * their screens); the clients' copy (NetAptSVA D_SYNC) sent on login, on a dimension change and whenever the data's version
 * moves (at most every SYNC_MIN ticks per dimension). Registered on the FML bus (CommonProxySVA).
 */
public final class AirportServerSVA {

    public static final AirportServerSVA INSTANCE = new AirportServerSVA();
    public static final int SYNC_MIN = 20, TILE_EVERY = 20;

    private final Map<Integer, Integer> sentVersion = new HashMap<Integer, Integer>();
    private final Map<Integer, Long> sentAt = new HashMap<Integer, Long>();

    private AirportServerSVA() {
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) {
            return;
        }
        WorldServer[] ws = DimensionManager.getWorlds();
        if (ws == null || ws.length == 0) {
            return;
        }
        AirportDataSVA d = AirportDataSVA.get(DimensionManager.getWorld(0) != null ? DimensionManager.getWorld(0) : ws[0]);
        if (d == null) {
            return;
        }
        for (WorldServer w : ws) {
            if (w == null) {
                continue;
            }
            long now = w.getTotalWorldTime();
            d.tick(w, now);
            AtcServerSVA.INSTANCE.tick(w, d, now);                  // airport wave B2: the towers' services
            if (now % TILE_EVERY == 0) {
                tiles(w, d);
            }
            Integer dim = Integer.valueOf(w.provider.dimensionId);
            Integer v = sentVersion.get(dim);
            Long at = sentAt.get(dim);
            if ((v == null || v.intValue() != d.version()) && (at == null || now - at.longValue() >= SYNC_MIN || now < at.longValue())) {
                sentVersion.put(dim, Integer.valueOf(d.version()));
                sentAt.put(dim, Long.valueOf(now));
                syncDim(w, d);
            }
        }
    }

    /** The loaded antennas' tiles get their station's status. */
    static void tiles(World w, AirportDataSVA d) {
        for (IlsPairing.LocAnt l : locsOf(d, w)) {
            push(w, d, l.key, l.key);
        }
        for (IlsPairing.Pair p : d.pairs()) {
            if (p.gs != null && p.gs.dim == w.provider.dimensionId) {
                push(w, d, p.gs.key, p.key());
            }
        }
    }

    static List<IlsPairing.LocAnt> locsOf(AirportDataSVA d, World w) {
        List<IlsPairing.LocAnt> l = new java.util.ArrayList<IlsPairing.LocAnt>();
        for (IlsPairing.Pair p : d.pairs()) {
            if (p.loc.dim == w.provider.dimensionId) {
                l.add(p.loc);
            }
        }
        return l;
    }

    static void push(World w, AirportDataSVA d, String antennaKey, String stationKey) {
        int[] pos = TileEntityIlsAntennaSVA.parse(antennaKey);
        if (pos == null || !w.blockExists(pos[0], pos[1], pos[2])) {
            return;
        }
        TileEntity te = w.getTileEntity(pos[0], pos[1], pos[2]);
        if (!(te instanceof TileEntityIlsAntennaSVA)) {
            return;
        }
        LinerIls.Station s = d.station(stationKey);
        com.sva.airport.IlsCat.Result r = d.result(stationKey);
        if (s == null) {
            ((TileEntityIlsAntennaSVA) te).status(-1, 0, 0, null, "");
        } else {
            ((TileEntityIlsAntennaSVA) te).status(s.cat, s.flags, s.calib, r == null ? null : r.issues, s.rwy);
        }
    }

    /** Every player of the dimension gets the copy. */
    static void syncDim(World w, AirportDataSVA d) {
        net.minecraft.nbt.NBTTagCompound t = null;
        for (Object o : w.playerEntities) {
            if (o instanceof EntityPlayerMP) {
                if (t == null) {
                    t = d.clientData(w, w.provider.dimensionId);
                }
                NetAptSVA.send((EntityPlayerMP) o, NetAptSVA.D_SYNC, t);
            }
        }
    }

    static void syncPlayer(EntityPlayerMP p) {
        if (p == null || p.worldObj == null) {
            return;
        }
        AirportDataSVA d = AirportDataSVA.get(p.worldObj);
        if (d != null) {
            NetAptSVA.send(p, NetAptSVA.D_SYNC, d.clientData(p.worldObj, p.worldObj.provider.dimensionId));
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.player instanceof EntityPlayerMP) {
            syncPlayer((EntityPlayerMP) e.player);
        }
    }

    @SubscribeEvent
    public void onDim(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.player instanceof EntityPlayerMP) {
            syncPlayer((EntityPlayerMP) e.player);
        }
    }
}
