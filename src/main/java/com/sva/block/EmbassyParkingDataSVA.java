package com.sva.block;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

/**
 * PACKAGE S (docs/plan-embassy-airfield-addon.md §2.3, §3.8): the embassies' stands as the add-on keeps them - one per save (the
 * overworld's map storage, as AirportDataSVA): which machine (VehicleState.vehId) of which owner (UUID) holds stand `spot` of embassy `id`
 * since when, paid through which game day; and the «Отстой» lot's machines. Only the add-on's own bookkeeping - the stands themselves are
 * the core's (EmbassyFieldSVA.spotBox), nothing is placed in the world. Per player by UUID (a LAN friend - his own); a machine not found
 * any more is dropped by S's tick (never a machine removed by it).
 * <p>
 * NBT (fixed, only appended): "Stands" [{E id, S spot, V vehId, O owner UUID, T since (world ticks), D since (game day), P paid through
 * (game day, -1 none)}], "Lot" [{E id, V vehId, O owner UUID, T since, D, P (review 2026-10-27, appended: the stay's days - an old entry
 * without them: an unknown stay, nothing owed)}], "Left" [{E, V, O, T, D, P, R the game day it left its stand / the lot at the field}]
 * (review 2026-10-27, appended). Written by the coordinator (the data model); S may add helpers.
 * <p>
 * Package S's helpers: lotOf (a machine's / an owner's lot places), standsAt, all, heldBy. Review 2026-10-27 (the parking fee could not be
 * collected): a STAY's days (since, paid through) go with the machine - from a stand to another, to the lot and back, and off its stand
 * and on again within STAY_GAP_DAYS (leave - the "Left" memory): take / toLot carry them over, so taxiing off and back on, or a tow,
 * never gives a new free first day; setOwner (a machine given to another player keeps its place, the new owner's).
 */
public class EmbassyParkingDataSVA extends WorldSavedData {

    public static final String NAME = "SiliconAgeVehicles_embassy";

    /** One machine on a stand (spot >= 0) or on the lot (spot -1). */
    public static final class Stand {
        public int embassy, spot = -1;
        public String vid = "", owner = "";
        public long since, sinceDay, paidDay = -1;

        /** The "Left" memory's: the game day it left (-1: a stand's / the lot's record). */
        public long leftDay = -1;

        void write(NBTTagCompound e, boolean stand) {
            e.setInteger("E", embassy);
            e.setString("V", vid);
            e.setString("O", owner);
            e.setLong("T", since);
            if (stand) {
                e.setInteger("S", spot);
            }
            e.setLong("D", sinceDay);                               // the lot's and the "Left" memory's too (appended)
            e.setLong("P", paidDay);
            if (leftDay >= 0) {
                e.setLong("R", leftDay);
            }
        }

        static Stand read(NBTTagCompound e, boolean stand) {
            Stand s = new Stand();
            s.embassy = e.getInteger("E");
            s.vid = e.getString("V");
            s.owner = e.getString("O");
            s.since = e.getLong("T");
            if (stand) {
                s.spot = e.getInteger("S");
            }
            s.sinceDay = e.hasKey("D") ? e.getLong("D") : stand ? 0 : -1;   // an old lot entry: an unknown stay (nothing owed)
            s.paidDay = e.hasKey("P") ? e.getLong("P") : -1;
            s.leftDay = e.hasKey("R") ? e.getLong("R") : -1;
            return s;
        }

        /** The stay's days of `o` (an earlier record of the same machine at the same embassy; null / an unknown one - a new stay on `day`). */
        void stayOf(Stand o, long day) {
            if (o != null && o.embassy == embassy && o.sinceDay >= 0) {
                sinceDay = o.sinceDay;
                paidDay = o.paidDay;
            } else {
                sinceDay = day;
                paidDay = -1;
            }
        }
    }

    /** A record left at the field (off its stand / the lot) is remembered this many game days (a stay goes on within them). */
    public static final int STAY_GAP_DAYS = 1;

    private final List<Stand> stands = new ArrayList<Stand>(), lot = new ArrayList<Stand>(), left = new ArrayList<Stand>();

    public EmbassyParkingDataSVA(String name) {
        super(name);
    }

    public static EmbassyParkingDataSVA get(World w) {
        if (w == null || w.mapStorage == null) {
            return null;
        }
        EmbassyParkingDataSVA d = (EmbassyParkingDataSVA) w.mapStorage.loadData(EmbassyParkingDataSVA.class, NAME);
        if (d == null) {
            d = new EmbassyParkingDataSVA(NAME);
            w.mapStorage.setData(NAME, d);
        }
        return d;
    }

    // ------------------------------------------------------------------ the stands

    /** Who holds stand `spot` of embassy `id` (null: free as far as the books go). */
    public Stand stand(int id, int spot) {
        for (Stand s : stands) {
            if (s.embassy == id && s.spot == spot) {
                return s;
            }
        }
        return null;
    }

    /** The stand of a machine (null: on none). */
    public Stand standOf(String vid) {
        for (Stand s : stands) {
            if (s.vid.equals(vid)) {
                return s;
            }
        }
        return null;
    }

    /** The stands `owner` holds at embassy `id`. */
    public List<Stand> standsOf(int id, String owner) {
        List<Stand> l = new ArrayList<Stand>();
        for (Stand s : stands) {
            if (s.embassy == id && s.owner.equals(owner)) {
                l.add(s);
            }
        }
        return l;
    }

    /**
     * A machine takes a stand (its old stand / lot place goes). Review 2026-10-27: the stay's days come along from its earlier record at
     * this embassy (a stand, the lot, or one it left within STAY_GAP_DAYS) - a new stay (day `day` free) only without one.
     */
    public Stand take(int id, int spot, String vid, String owner, long now, long day) {
        Stand old = earlier(id, vid, day);
        release(vid);
        Stand s = new Stand();
        s.embassy = id;
        s.spot = spot;
        s.vid = vid;
        s.owner = owner;
        s.since = now;
        s.stayOf(old, day);
        stands.add(s);
        markDirty();
        return s;
    }

    /** The machine's earlier record at embassy `id` (its stand, its lot place, or the "Left" memory within STAY_GAP_DAYS of `day`; null none). */
    Stand earlier(int id, String vid, long day) {
        Stand o = standOf(vid);
        if (o == null) {
            o = lotOf(vid);
        }
        if (o == null) {
            for (Stand l : left) {
                if (l.vid.equals(vid) && l.embassy == id && day >= l.leftDay && day - l.leftDay <= STAY_GAP_DAYS) {
                    o = l;
                }
            }
        }
        return o != null && o.embassy == id ? o : null;
    }

    /**
     * Review 2026-10-27: a machine leaves its stand / the lot AT the field (taxied off, a rider took it): released, its stay's days
     * remembered on game day `day` (the "Left" memory - back on a stand within STAY_GAP_DAYS: the same stay). @return it held one
     */
    public boolean leave(String vid, long day) {
        Stand o = standOf(vid);
        if (o == null) {
            o = lotOf(vid);
        }
        boolean any = release(vid);
        if (o != null && o.sinceDay >= 0) {
            Stand l = new Stand();
            l.embassy = o.embassy;
            l.vid = o.vid;
            l.owner = o.owner;
            l.since = o.since;
            l.sinceDay = o.sinceDay;
            l.paidDay = o.paidDay;
            l.leftDay = day;
            left.add(l);
            markDirty();
        }
        return any;
    }

    /** The "Left" memory's entries older than STAY_GAP_DAYS go (today `day`). */
    public void pruneLeft(long day) {
        boolean any = false;
        for (Iterator<Stand> it = left.iterator(); it.hasNext();) {
            Stand l = it.next();
            if (day < l.leftDay || day - l.leftDay > STAY_GAP_DAYS) {
                it.remove();
                any = true;
            }
        }
        if (any) {
            markDirty();
        }
    }

    /** Review 2026-10-27: a booked machine given to another player (EntityVehicleSVA.transfer) - its record is the new owner's. */
    public void setOwner(Stand s, String owner) {
        if (s != null && owner != null && !owner.equals(s.owner)) {
            s.owner = owner;
            markDirty();
        }
    }

    /** A machine leaves its stand and the lot (left, flown off, towed, gone; its "Left" memory too). @return it held one */
    public boolean release(String vid) {
        boolean any = false, mem = false;
        for (Iterator<Stand> it = stands.iterator(); it.hasNext();) {
            if (it.next().vid.equals(vid)) {
                it.remove();
                any = true;
            }
        }
        for (Iterator<Stand> it = lot.iterator(); it.hasNext();) {
            if (it.next().vid.equals(vid)) {
                it.remove();
                any = true;
            }
        }
        for (Iterator<Stand> it = left.iterator(); it.hasNext();) {
            if (it.next().vid.equals(vid)) {
                it.remove();
                mem = true;
            }
        }
        if (any || mem) {
            markDirty();
        }
        return any;
    }

    /** The parking paid through game day `day`. */
    public void paid(Stand s, long day) {
        s.paidDay = Math.max(s.paidDay, day);
        markDirty();
    }

    // ------------------------------------------------------------------ the «Отстой» lot

    public List<Stand> lot(int id) {
        List<Stand> l = new ArrayList<Stand>();
        for (Stand s : lot) {
            if (s.embassy == id) {
                l.add(s);
            }
        }
        return l;
    }

    public Stand toLot(int id, String vid, String owner, long now) {
        return toLot(id, vid, owner, now, -1);
    }

    /** Review 2026-10-27: onto the lot on game day `day` (-1: unknown) - the stay's days come along (take's); the lot's days are owed as a stand's. */
    public Stand toLot(int id, String vid, String owner, long now, long day) {
        Stand old = earlier(id, vid, day);
        release(vid);
        Stand s = new Stand();
        s.embassy = id;
        s.vid = vid;
        s.owner = owner;
        s.since = now;
        s.stayOf(old, day);
        lot.add(s);
        markDirty();
        return s;
    }

    /** World tests: {stands, lot} counts. */
    public int[] counts() {
        return new int[] {stands.size(), lot.size()};
    }

    /** Self-tests: the "Left" memory's size. */
    public int leftCount() {
        return left.size();
    }

    // ------------------------------------------------------------------ package S's helpers

    /** The lot place of a machine (null: not on the lot). */
    public Stand lotOf(String vid) {
        for (Stand s : lot) {
            if (s.vid.equals(vid)) {
                return s;
            }
        }
        return null;
    }

    /** The machines `owner` has on embassy `id`'s lot. */
    public List<Stand> lotOf(int id, String owner) {
        List<Stand> l = new ArrayList<Stand>();
        for (Stand s : lot) {
            if (s.embassy == id && s.owner.equals(owner)) {
                l.add(s);
            }
        }
        return l;
    }

    /** Every stand booked at embassy `id`. */
    public List<Stand> standsAt(int id) {
        List<Stand> l = new ArrayList<Stand>();
        for (Stand s : stands) {
            if (s.embassy == id) {
                l.add(s);
            }
        }
        return l;
    }

    /** Every record, the stands' then the lot's (a copy: the tick drops the stale ones while it walks it). */
    public List<Stand> all() {
        List<Stand> l = new ArrayList<Stand>(stands);
        l.addAll(lot);
        return l;
    }

    /** The stands `owner` holds at embassy `id`, not counting machine `vid`'s own (a tow from one of his stands to another). */
    public int heldBy(int id, String owner, String vid) {
        int n = 0;
        for (Stand s : stands) {
            if (s.embassy == id && s.owner.equals(owner) && !s.vid.equals(vid)) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ save

    @Override
    public void readFromNBT(NBTTagCompound t) {
        stands.clear();
        lot.clear();
        NBTTagList l = t.getTagList("Stands", 10);
        for (int i = 0; i < l.tagCount(); i++) {
            Stand s = Stand.read(l.getCompoundTagAt(i), true);
            if (s.vid.length() > 0 && s.spot >= 0) {
                stands.add(s);
            }
        }
        l = t.getTagList("Lot", 10);
        for (int i = 0; i < l.tagCount(); i++) {
            Stand s = Stand.read(l.getCompoundTagAt(i), false);
            if (s.vid.length() > 0) {
                lot.add(s);
            }
        }
        left.clear();
        l = t.getTagList("Left", 10);
        for (int i = 0; i < l.tagCount(); i++) {
            Stand s = Stand.read(l.getCompoundTagAt(i), false);
            if (s.vid.length() > 0 && s.leftDay >= 0) {
                left.add(s);
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound t) {
        NBTTagList l = new NBTTagList();
        for (Stand s : stands) {
            NBTTagCompound e = new NBTTagCompound();
            s.write(e, true);
            l.appendTag(e);
        }
        t.setTag("Stands", l);
        l = new NBTTagList();
        for (Stand s : lot) {
            NBTTagCompound e = new NBTTagCompound();
            s.write(e, false);
            l.appendTag(e);
        }
        t.setTag("Lot", l);
        l = new NBTTagList();
        for (Stand s : left) {
            NBTTagCompound e = new NBTTagCompound();
            s.write(e, false);
            l.appendTag(e);
        }
        t.setTag("Left", l);
    }
}
