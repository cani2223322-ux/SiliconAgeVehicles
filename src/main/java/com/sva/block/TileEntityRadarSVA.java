package com.sva.block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sc.energy.Tier;
import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.BallisticsMath;
import com.sva.airdefense.RadarMath;
import com.sva.entity.EntityVehicleSVA;

/**
 * Air-defence wave: a radar (block meta = RadarMath kind: surveillance HV, tracking HV, height finder MV). While powered
 * it scans every RadarMath.INTERVAL ticks: every vehicle in the loaded world that flies high enough, is in its range for
 * that vehicle's RCS / tuning / the weather, with a clear beam over the terrain, becomes a contact (the surveillance
 * radar's position +-8 blocks and the transponder's answer, the height finder's altitude, the tracking radar's exact
 * track - only for its two chosen targets: the command post's priorities, else the nearest). Each contact is painted on
 * the target's RWR. The command post within BallisticsMath.LINK of the same owner merges what its radars see.
 */
public class TileEntityRadarSVA extends TileEntityAirDefenseSVA {

    private final Map<Integer, AirDefenseSVA.Contact> contacts = new LinkedHashMap<Integer, AirDefenseSVA.Contact>();
    private int timer, kindSet = -1;
    private long scanNo;
    /** The voltage follows the kind (the meta is known only once in the world). */
    private boolean tierSet;

    public TileEntityRadarSVA() {
        super(Tier.HV);
    }

    public int kind() {
        int m = worldObj == null ? 0 : getBlockMetadata();
        return RadarMath.validKind(m) ? m : 0;
    }

    @Override
    public int upkeep() {
        return RadarMath.EU_PER_TICK[kind()];
    }

    @Override
    public int nodeKind() {
        return AirDefenseDataSVA.N_RADAR;
    }

    @Override
    public String nameKey() {
        return "sva.ad.radar." + RadarMath.KEYS[kind()];
    }

    /**
     * The model (multiblock wave): power; lock - the tracking radar holds a target / the others see something; alarm -
     * the owner's command post within link range raised it. The tracking radar's dish turns to its first target.
     */
    @Override
    protected int stateBits() {
        int s = super.stateBits();
        if (powered && !contacts.isEmpty()) {
            s |= ST_LOCK;
        }
        TileEntityCommandPostSVA kp = AirDefenseSVA.INSTANCE.commandPost(worldObj, ownerName, xCoord, yCoord, zCoord, BallisticsMath.LINK);
        if (kp != null && kp.isAlarm()) {
            s |= ST_ALARM;
        }
        return s;
    }

    @Override
    protected int targetId() {
        if (!powered || kind() != RadarMath.TRACK || contacts.isEmpty()) {
            return -1;
        }
        return contacts.keySet().iterator().next().intValue();
    }

    /** The last scan's contacts (the command post reads them). */
    public Map<Integer, AirDefenseSVA.Contact> contacts() {
        return contacts;
    }

    @Override
    protected void serverTick() {
        if (!tierSet) {
            tierSet = true;
            setTier(kind() == RadarMath.HEIGHT ? Tier.MV : Tier.HV);
        }
        if (!powered) {
            contacts.clear();
            return;
        }
        int k = kind();
        if (++timer < RadarMath.INTERVAL[k] && kindSet == k) {
            return;
        }
        kindSet = k;
        timer = 0;
        scan(k);
    }

    /** One scan (also the world test's way to run one now). */
    public void scan(int k) {
        long now = worldObj.getTotalWorldTime();
        scanNo++;
        double ax = xCoord + 0.5, ay = yCoord + com.sva.airdefense.AdShapeMath.antennaHeight(shape(), struct == MULTI), az = zCoord + 0.5;
        double weather = RadarMath.weatherMul(worldObj.isRaining(), worldObj.isThundering());
        List<EntityVehicleSVA> seen = new ArrayList<EntityVehicleSVA>();
        for (Object o : worldObj.loadedEntityList) {
            if (!(o instanceof EntityVehicleSVA)) {
                continue;
            }
            EntityVehicleSVA v = (EntityVehicleSVA) o;
            double rcs = RadarMath.rcs(v.getVehicleType());
            double range = RadarMath.detectRange(k, rcs, AirDefenseSVA.stealth(v), weather);
            double[] c = AirDefenseSVA.centre(v);
            if (range <= 0 || BallisticsMath.dist(ax, ay, az, c[0], c[1], c[2]) > range || !AirDefenseSVA.radarVisible(v)
                    || !AirDefenseSVA.losClear(worldObj, ax, ay, az, c[0], c[1], c[2])) {
                continue;
            }
            seen.add(v);
        }
        if (k == RadarMath.TRACK) {
            seen = choose(seen, ax, az);
        }
        contacts.clear();
        long until = now + RadarMath.INTERVAL[k] + 20;
        for (EntityVehicleSVA v : seen) {
            double[] c = AirDefenseSVA.centre(v);
            double e = RadarMath.ERROR[k];
            AirDefenseSVA.Contact ct = new AirDefenseSVA.Contact(v.getEntityId());
            AirDefenseSVA.INSTANCE.fill(ct, v, c[0] + RadarMath.jitter(v.getEntityId(), scanNo, 0, e), c[1] + RadarMath.jitter(v.getEntityId(), scanNo, 1, e),
                    c[2] + RadarMath.jitter(v.getEntityId(), scanNo, 2, e), RadarMath.ALTITUDE[k], now);
            ct.kinds = 1 << k;
            if (k == RadarMath.SURVEY) {
                AirDefenseSVA.INSTANCE.interrogate(ct, v, now);
            }
            contacts.put(Integer.valueOf(v.getEntityId()), ct);
            AirDefenseSVA.INSTANCE.paint(v, k == RadarMath.TRACK ? RadarMath.T_LOCK : k == RadarMath.HEIGHT ? RadarMath.T_HEIGHT : RadarMath.T_SEARCH,
                    ax, az, until);
        }
    }

    /** The tracking radar's two: the command post's priority order, then the nearest. */
    private List<EntityVehicleSVA> choose(List<EntityVehicleSVA> seen, final double ax, final double az) {
        TileEntityCommandPostSVA kp = AirDefenseSVA.INSTANCE.commandPost(worldObj, ownerName, xCoord, yCoord, zCoord, BallisticsMath.LINK);
        final List<Integer> pri = kp == null ? new ArrayList<Integer>() : kp.lockPriority();
        List<EntityVehicleSVA> l = new ArrayList<EntityVehicleSVA>(seen);
        if (kp != null) {
            // own and friendly vehicles are not worth a channel
            for (int i = l.size() - 1; i >= 0; i--) {
                if (!pri.contains(Integer.valueOf(l.get(i).getEntityId())) && relation(l.get(i).getOwnerName()) != 0) {
                    l.remove(i);
                }
            }
        }
        Collections.sort(l, new Comparator<EntityVehicleSVA>() {
            @Override
            public int compare(EntityVehicleSVA a, EntityVehicleSVA b) {
                int ia = pri.indexOf(Integer.valueOf(a.getEntityId())), ib = pri.indexOf(Integer.valueOf(b.getEntityId()));
                ia = ia < 0 ? Integer.MAX_VALUE : ia;
                ib = ib < 0 ? Integer.MAX_VALUE : ib;
                if (ia != ib) {
                    return ia < ib ? -1 : 1;
                }
                double da = (a.posX - ax) * (a.posX - ax) + (a.posZ - az) * (a.posZ - az), db = (b.posX - ax) * (b.posX - ax) + (b.posZ - az) * (b.posZ - az);
                return Double.compare(da, db);
            }
        });
        return l.size() > RadarMath.CHANNELS[RadarMath.TRACK] ? new ArrayList<EntityVehicleSVA>(l.subList(0, RadarMath.CHANNELS[RadarMath.TRACK])) : l;
    }
}
