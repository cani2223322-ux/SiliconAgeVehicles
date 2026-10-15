package com.sva.block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.XpdrMath;
import com.sva.airport.ApproachMath;
import com.sva.airport.ChartMath;
import com.sva.airport.Cpdlc;
import com.sva.airport.IlsPairing;
import com.sva.airport.RunwayLayout;
import com.sva.airport.TaxiGraph;
import com.sva.airport.TowerMath;
import com.sva.airport.WindField;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.vehicle.LinerIls;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;

/**
 * Airport wave B2 (plan-next §9, items 29, 32, 34): the server's side of the tower's screen (client.GuiTowerSVA) - the
 * picture it gets (snapshot(): the airport, its runways with their ILS and lights, the weather, ATIS, the TRAFFIC - every
 * transponder answering within TowerMath.TRAFFIC_RANGE and what the owner's radars within AtcServerSVA.RADAR_LINK see -
 * the flight strips of the aircraft talking to it, its datalink log, the approaches and the taxi graph for the map) and
 * its commands (command(): checked against the block's access; the clearances only from the player in the controller
 * seat; the airport's code, name, codes and access list only from the owner).
 */
public final class TowerScreenSVA {

    /** Traffic sources (bits). */
    public static final int SRC_XPDR = 1, SRC_RADAR = 2;
    /** The map's taxi lights at most this far from the tower (blocks) and this many. */
    public static final double MAP_TAXI = 900;
    public static final int MAP_TAXI_MAX = 400, LOG_SHOWN = 16;

    private TowerScreenSVA() {
    }

    // ---------------------------------------------------------------- the picture

    static NBTTagCompound station(AirportDataSVA d, AirportDataSVA.Tower t, IlsPairing.Rwy r, int dir) {
        for (IlsPairing.Pair p : d.pairs()) {
            if (p.r == r && p.dir == dir) {
                LinerIls.Station s = d.station(p.key());
                if (s == null) {
                    return null;
                }
                NBTTagCompound e = new NBTTagCompound();
                e.setString("K", p.key());
                e.setString("Id", s.ident);
                e.setInteger("Fq", s.freq);
                e.setInteger("Cat", s.cat);
                e.setInteger("F", s.flags);
                e.setBoolean("Off", t.ilsOff.contains(p.key()));
                return e;
            }
        }
        return null;
    }

    public static NBTTagCompound snapshot(World w, AirportDataSVA d, TileEntityTowerSVA te, AirportDataSVA.Tower t, EntityPlayer p, long now) {
        NBTTagCompound s = new NBTTagCompound();
        NBTTagCompound h = new NBTTagCompound();
        h.setString("Key", t.key);
        h.setIntArray("Blk", new int[] {te.xCoord, te.yCoord, te.zCoord});
        h.setString("Icao", t.icao);
        h.setString("Name", t.name);
        h.setString("Own", te.getOwnerName());
        h.setInteger("Elev", t.elev);
        h.setIntArray("Codes", new int[] {t.codeLo, t.codeHi});
        h.setIntArray("Lt", new int[] {t.ltRwy, t.ltAppr, t.ltTaxi});
        h.setBoolean("Online", t.online(now));
        h.setBoolean("Pow", te.isPowered());
        h.setInteger("E", te.getEnergyStored());
        h.setInteger("EMax", te.getMaxEnergyStored());
        h.setInteger("Use", te.upkeep());
        h.setString("Ctl", te.controller());
        h.setBoolean("Me", te.controller().equals(p.getCommandSenderName()));
        h.setBoolean("Owner", te.isOwner(p));
        h.setBoolean("Auto", AtcServerSVA.automatic(t));
        h.setString("InUse", t.inUse);
        h.setString("Sel", t.rwySel);
        h.setInteger("Ver", t.chartVer);
        h.setInteger("Letter", t.letter);
        h.setString("Atis", t.atisText);
        h.setLong("AtisAt", t.atisAt);
        StringBuilder acc = new StringBuilder();
        for (String a : te.getAccess()) {
            acc.append(acc.length() > 0 ? "," : "").append(a);
        }
        h.setString("Acc", acc.toString());
        h.setLong("Time", w.getWorldTime());
        h.setBoolean("IffAlarm", IffServerSVA.INSTANCE.alarm(t.key));    // wave A: an intruder near the airport
        s.setTag("T", h);
        // wave A: the IFF list (intruders: id, state, reason, seconds to the handoff) and the radars for the coverage map
        NBTTagList il = new NBTTagList();
        for (int[] e : IffServerSVA.INSTANCE.list(t.key, now)) {
            il.appendTag(new net.minecraft.nbt.NBTTagIntArray(e));
        }
        s.setTag("Iff", il);
        NBTTagList rd = new NBTTagList();
        for (TileEntityRadarSVA r : radars(w, te)) {
            NBTTagCompound e = new NBTTagCompound();
            e.setString("Key", r.key());
            e.setInteger("X", r.xCoord);
            e.setInteger("Z", r.zCoord);
            e.setByte("K", (byte) r.kind());
            rd.appendTag(e);
        }
        s.setTag("Rd", rd);
        // the runways
        NBTTagList rl = new NBTTagList();
        for (IlsPairing.Rwy r : t.rwys) {
            NBTTagCompound e = new NBTTagCompound();
            e.setString("Ka", r.a.key);
            e.setString("Kb", r.b.key);
            e.setString("Na", r.nameA);
            e.setString("Nb", r.nameB);
            e.setDouble("Ax", r.a.x);
            e.setDouble("Az", r.a.z);
            e.setDouble("Bx", r.b.x);
            e.setDouble("Bz", r.b.z);
            e.setInteger("W", r.width);
            e.setInteger("Len", (int) Math.round(r.len));
            e.setInteger("La", r.a.lights);
            e.setInteger("Lb", r.b.lights);
            e.setBoolean("Sa", r.a.als != 0);
            e.setBoolean("Sb", r.b.als != 0);
            int[] ra = d.rvr(r, 1), rb = d.rvr(r, -1);
            e.setIntArray("Ra", ra);
            e.setIntArray("Rb", rb);
            NBTTagCompound ia = station(d, t, r, 1), ib = station(d, t, r, -1);
            if (ia != null) {
                e.setTag("Ia", ia);
            }
            if (ib != null) {
                e.setTag("Ib", ib);
            }
            rl.appendTag(e);
        }
        s.setTag("Rw", rl);
        // the weather
        AirportDataSVA.Device wx = AtcServerSVA.weather(d, t);
        s.setIntArray("Wx", wx == null ? new int[] {-1, 0, 0, 0, 0} : new int[] {(int) Math.round(wx.windFrom) % 360, WindField.kmh(wx.windSpeed),
            WindField.kmh(wx.windGust), Double.isNaN(wx.qnh) ? 1013 : (int) Math.round(wx.qnh), (int) Math.round(wx.temp)});
        // the traffic
        s.setTag("Tr", traffic(w, d, te, t, now));
        // the strips: the aircraft talking to this tower
        NBTTagList sl = new NBTTagList();
        for (AtcServerSVA.Session x : AtcServerSVA.INSTANCE.sessionsOf(t.key)) {
            NBTTagCompound e = new NBTTagCompound();
            e.setInteger("Id", x.id);
            e.setString("C", x.ac.call);
            e.setInteger("Ty", x.type);
            e.setInteger("Sq", x.code);
            e.setInteger("Rq", x.ac.req);
            e.setInteger("St", x.ac.st);
            e.setBoolean("Mc", x.mcdu);
            e.setBoolean("Gnd", x.ac.ground);
            e.setInteger("Alt", (int) Math.round(x.ac.y));
            int open = 0;
            for (Cpdlc.Msg m : x.log.msgs) {
                open += m.open() ? 1 : 0;
            }
            e.setInteger("Op", open);
            e.setBoolean("Rt", x.route != null);
            sl.appendTag(e);
        }
        s.setTag("Sp", sl);
        // the log
        NBTTagList ll = new NBTTagList();
        for (Cpdlc.Msg m : AtcServerSVA.INSTANCE.towerLog(t.key).recent(LOG_SHOWN)) {
            ll.appendTag(msgTag(m));
        }
        s.setTag("Lg", ll);
        // the approaches (the map)
        NBTTagList al = new NBTTagList();
        for (ApproachMath.Approach a : d.approaches(t)) {
            NBTTagCompound e = new NBTTagCompound();
            e.setString("Id", a.id);
            e.setString("Rwy", a.rwy);
            List<double[]> path = ApproachMath.path(a, null);
            int[] pts = new int[path.size() * 3];
            for (int i = 0; i < path.size(); i++) {
                pts[i * 3] = (int) Math.round(path.get(i)[0]);
                pts[i * 3 + 1] = (int) Math.round(path.get(i)[1]);
                pts[i * 3 + 2] = (int) Math.round(path.get(i)[2]);
            }
            e.setIntArray("P", pts);
            e.setInteger("Cat", a.cat);
            e.setInteger("Fq", a.freq);
            al.appendTag(e);
        }
        s.setTag("Ap", al);
        taxiMap(s, d, t);
        return s;
    }

    static NBTTagCompound msgTag(Cpdlc.Msg m) {
        NBTTagCompound e = new NBTTagCompound();
        e.setInteger("Ac", m.aircraft);
        e.setString("C", m.call);
        e.setBoolean("Up", m.up);
        e.setInteger("Ty", m.type);
        e.setInteger("A", m.arg);
        e.setString("S", m.s);
        e.setString("S2", m.s2);
        e.setInteger("St", m.state);
        e.setLong("T", m.sent);
        e.setInteger("Id", m.id);
        return e;
    }

    /** A message from its tag (the client's log). */
    public static Cpdlc.Msg msgOf(NBTTagCompound e) {
        Cpdlc.Msg m = new Cpdlc.Msg();
        m.aircraft = e.getInteger("Ac");
        m.call = e.getString("C");
        m.up = e.getBoolean("Up");
        m.type = e.getInteger("Ty");
        m.arg = e.getInteger("A");
        m.s = e.getString("S");
        m.s2 = e.getString("S2");
        m.state = e.getInteger("St");
        m.sent = e.getLong("T");
        m.id = e.getInteger("Id");
        return m;
    }

    /** Wave A: the tower's radars - its owner's, powered, within AtcServerSVA.RADAR_LINK (the traffic's and the coverage map's). */
    static List<TileEntityRadarSVA> radars(World w, TileEntityTowerSVA te) {
        List<TileEntityRadarSVA> l = new ArrayList<TileEntityRadarSVA>();
        for (TileEntityRadarSVA r : AirDefenseSVA.INSTANCE.loaded(w, TileEntityRadarSVA.class)) {
            if (r.getOwnerName().equalsIgnoreCase(te.getOwnerName()) && r.isPowered()
                    && r.getDistanceFrom(te.xCoord + 0.5, te.yCoord + 0.5, te.zCoord + 0.5) <= AtcServerSVA.RADAR_LINK * AtcServerSVA.RADAR_LINK) {
                l.add(r);
            }
        }
        return l;
    }

    /** The traffic: transponders within TRAFFIC_RANGE, the owner's radars' contacts (primary returns too). */
    static NBTTagList traffic(World w, AirportDataSVA d, TileEntityTowerSVA te, AirportDataSVA.Tower t, long now) {
        Map<Integer, NBTTagCompound> out = new HashMap<Integer, NBTTagCompound>();
        double r2 = TowerMath.TRAFFIC_RANGE * TowerMath.TRAFFIC_RANGE;
        for (EntityVehicleSVA v : AirportDataSVA.aircraft(w)) {
            double dx = v.posX - t.x, dz = v.posZ - t.z;
            if (dx * dx + dz * dz > r2) {
                continue;
            }
            XpdrMath.Xpdr x = v.state.xpdr;
            if (!x.replying()) {
                continue;
            }
            NBTTagCompound e = target(v, now);
            e.setInteger("Src", SRC_XPDR);
            e.setInteger("Sq", x.code);
            e.setInteger("Md", x.mode);
            boolean alt = XpdrMath.givesAltitude(x.mode) && v.xpdrAltitude();
            e.setInteger("Y", alt ? (int) Math.round(v.posY) : -1);
            out.put(Integer.valueOf(v.getEntityId()), e);
        }
        for (TileEntityRadarSVA r : AirDefenseSVA.INSTANCE.loaded(w, TileEntityRadarSVA.class)) {
            if (!r.getOwnerName().equalsIgnoreCase(te.getOwnerName()) || !r.isPowered()
                    || r.getDistanceFrom(te.xCoord + 0.5, te.yCoord + 0.5, te.zCoord + 0.5) > AtcServerSVA.RADAR_LINK * AtcServerSVA.RADAR_LINK) {
                continue;
            }
            for (AirDefenseSVA.Contact c : r.contacts().values()) {
                if (c.v == null || c.v.isDead) {
                    continue;
                }
                NBTTagCompound e = out.get(Integer.valueOf(c.id));
                if (e == null) {
                    e = target(c.v, now);
                    e.setInteger("Sq", -1);
                    e.setInteger("Md", XpdrMath.OFF);
                    e.setDouble("X", c.x);
                    e.setDouble("Z", c.z);
                    e.setInteger("Y", c.altKnown ? (int) Math.round(c.y) : -1);
                    e.setInteger("Src", SRC_RADAR);
                    e.setString("C", "");
                    out.put(Integer.valueOf(c.id), e);
                } else {
                    e.setInteger("Src", e.getInteger("Src") | SRC_RADAR);
                    if (e.getInteger("Y") < 0 && c.altKnown) {
                        e.setInteger("Y", (int) Math.round(c.y));
                    }
                }
            }
        }
        NBTTagList l = new NBTTagList();
        for (Map.Entry<Integer, NBTTagCompound> e : out.entrySet()) {
            com.sva.airdefense.IffMath.Track tr = IffServerSVA.INSTANCE.track(t.key, e.getKey().intValue());
            e.getValue().setByte("Iff", (byte) (tr == null ? 0 : tr.state));      // wave A: the IFF mark
            l.appendTag(e.getValue());
        }
        return l;
    }

    static NBTTagCompound target(EntityVehicleSVA v, long now) {
        NBTTagCompound e = new NBTTagCompound();
        e.setInteger("Id", v.getEntityId());
        e.setString("C", AtcServerSVA.callsign(v));
        e.setInteger("Ty", v.getVehicleType().ordinal());
        e.setDouble("X", v.posX);
        e.setDouble("Z", v.posZ);
        double[] vel = AirDefenseSVA.INSTANCE.velocity(v);
        double gs = Math.sqrt(vel[0] * vel[0] + vel[2] * vel[2]);
        e.setInteger("Kmh", (int) Math.round(gs * 72));
        e.setInteger("Crs", (int) Math.round(gs < 1e-3 ? com.sva.airdefense.RadarMath.headingOfYaw(v.rotationYaw) : com.sva.airdefense.RadarMath.compass(vel[0], vel[2])));
        e.setBoolean("Gnd", !v.airborneForAtc());
        return e;
    }

    /** The taxi lights near the tower, their links and the routes (the map). */
    @SuppressWarnings("unchecked")
    static void taxiMap(NBTTagCompound s, AirportDataSVA d, AirportDataSVA.Tower t) {
        Object[] g = d.taxi(t.dim);
        List<TaxiGraph.Node> nodes = (List<TaxiGraph.Node>) g[0];
        List<List<Integer>> adj = (List<List<Integer>>) g[1];
        List<Integer> shown = new ArrayList<Integer>();
        Map<Integer, Integer> local = new HashMap<Integer, Integer>();
        for (int i = 0; i < nodes.size() && shown.size() < MAP_TAXI_MAX; i++) {
            TaxiGraph.Node n = nodes.get(i);
            if (Math.hypot(n.x - t.x, n.z - t.z) <= MAP_TAXI) {
                local.put(Integer.valueOf(i), Integer.valueOf(shown.size()));
                shown.add(Integer.valueOf(i));
            }
        }
        int[] pts = new int[shown.size() * 3];
        NBTTagList keys = new NBTTagList();
        for (int k = 0; k < shown.size(); k++) {
            TaxiGraph.Node n = nodes.get(shown.get(k).intValue());
            pts[k * 3] = n.x;
            pts[k * 3 + 1] = n.z;
            pts[k * 3 + 2] = n.kind;
            keys.appendTag(new net.minecraft.nbt.NBTTagString(n.key));
        }
        List<Integer> edges = new ArrayList<Integer>();
        for (int k = 0; k < shown.size(); k++) {
            int i = shown.get(k).intValue();
            for (Integer j : adj.get(i)) {
                Integer lj = local.get(j);
                if (lj != null && lj.intValue() > k) {
                    edges.add(Integer.valueOf(k));
                    edges.add(lj);
                }
            }
        }
        int[] ea = new int[edges.size()];
        for (int k = 0; k < ea.length; k++) {
            ea[k] = edges.get(k).intValue();
        }
        s.setIntArray("Tn", pts);
        s.setTag("Tk", keys);
        s.setIntArray("Te", ea);
        NBTTagList routes = new NBTTagList();
        for (AtcServerSVA.Session x : AtcServerSVA.INSTANCE.sessionsOf(t.key)) {
            if (x.route == null) {
                continue;
            }
            List<Integer> r = new ArrayList<Integer>();
            for (String key : x.route) {
                int i = TaxiGraph.index(nodes, key);
                Integer li = i < 0 ? null : local.get(Integer.valueOf(i));
                if (li != null) {
                    r.add(li);
                }
            }
            int[] ra = new int[r.size() + 1];
            ra[0] = x.id;
            for (int k = 0; k < r.size(); k++) {
                ra[k + 1] = r.get(k).intValue();
            }
            routes.appendTag(new net.minecraft.nbt.NBTTagIntArray(ra));
        }
        s.setTag("Tr2", routes);
    }

    // ---------------------------------------------------------------- the commands

    /** A command of the screen (NetAptSVA.TOWER) on the server thread. */
    public static void command(EntityPlayerMP p, TileEntityTowerSVA te, NBTTagCompound c) {
        World w = te.getWorldObj();
        AirportDataSVA d = AirportDataSVA.get(w);
        AirportDataSVA.Tower t = te.record();
        if (d == null || t == null || !te.mayUse(p)) {
            return;
        }
        long now = w.getTotalWorldTime();
        String op = c.getString("Op");
        if ("cov".equals(op)) {
            // wave A (A9): the coverage maps of the tower's radars at a height, to this player
            for (TileEntityRadarSVA r : radars(w, te)) {
                com.sva.airdefense.CoverageSVA.INSTANCE.request(p, r, c.getInteger("A"));
            }
        } else if ("hb".equals(op)) {
            te.viewer(p, c.getBoolean("Seat"));
        } else if ("close".equals(op)) {
            te.closed(p);
        } else if ("seat".equals(op)) {
            te.seat(p, c.getBoolean("On"));
            te.viewer(p, true);
        } else if ("set".equals(op)) {
            settings(p, te, d, t, c);
        } else if ("lights".equals(op)) {
            int m = AirportDataSVA.clampLights(c.getInteger("M"));
            int which = c.getInteger("W");
            if (which == 0) {
                t.ltRwy = m;
            } else if (which == 1) {
                t.ltAppr = m;
            } else {
                t.ltTaxi = m;
            }
            te.recordChanged(t);
        } else if ("ils".equals(op)) {
            String k = c.getString("K");
            if (c.getBoolean("On")) {
                t.ilsOff.remove(k);
            } else {
                t.ilsOff.add(k);
            }
            te.recordChanged(t);
        } else if ("rwy".equals(op)) {
            t.rwySel = c.getString("K");
            AtcServerSVA.runwayInUse(d, t);
            te.recordChanged(t);
        } else if ("reset".equals(op)) {
            resetEnds(w, d, t);
        } else if ("up".equals(op)) {
            if (!te.controller().equals(p.getCommandSenderName())) {
                p.addChatComponentMessage(new ChatComponentTranslation("sva.tower.msg.notController"));
                return;
            }
            AtcServerSVA.Session s = AtcServerSVA.INSTANCE.session(c.getInteger("Ac"));
            if (s == null || !s.tower.equals(t.key)) {
                return;
            }
            int type = c.getInteger("Ty");
            String str = c.getString("S");
            if ((type == Cpdlc.U_TAXI || type == Cpdlc.U_LINEUP || type == Cpdlc.U_TAKEOFF || type == Cpdlc.U_LAND || type == Cpdlc.U_HOLDSHORT)
                    && str.length() == 0) {
                str = inUseName(t);
            }
            AtcServerSVA.INSTANCE.uplink(w, d, t, s, type, c.getInteger("A"), str, type == Cpdlc.U_STBY || type == Cpdlc.U_UNABLE
                    ? AtcServerSVA.stbyRef(s) : -2, now);
        } else if ("print".equals(op)) {
            print(p, te, d, t, c.getString("Ap"));
        } else if ("equip".equals(op)) {
            p.openGui(com.sva.SVAMod.instance, com.sva.inventory.GuiHandlerSVA.TOWER_EQUIP, w, te.xCoord, te.yCoord, te.zCoord);
        } else if ("link".equals(op)) {
            link(d, t, c.getString("A"), c.getString("B"));
            te.recordChanged(t);
        }
    }

    /** The runway in use's name (a clearance's default runway). */
    static String inUseName(AirportDataSVA.Tower t) {
        com.sva.airport.AutoTower.Rwy r = AtcServerSVA.useRwy(t);
        return r == null ? "" : r.name();
    }

    static void settings(EntityPlayerMP p, TileEntityTowerSVA te, AirportDataSVA d, AirportDataSVA.Tower t, NBTTagCompound c) {
        if (!te.isOwner(p)) {
            p.addChatComponentMessage(new ChatComponentTranslation("sva.tower.msg.ownerOnly"));
            return;
        }
        String icao = TowerMath.cleanIcao(c.getString("Icao"));
        if (icao.length() == 4 && !icao.equals(t.icao)) {
            if (!TowerMath.unique(icao, d.icaosExcept(t.key)) || icao.startsWith("ZZ")) {
                p.addChatComponentMessage(new ChatComponentTranslation("sva.tower.msg.icaoTaken", icao));
            } else {
                t.icao = icao;
            }
        } else if (c.getString("Icao").length() > 0 && icao.length() != 4) {
            p.addChatComponentMessage(new ChatComponentTranslation("sva.tower.msg.icaoBad"));
        }
        t.name = TowerMath.cleanName(c.getString("Name"));
        int[] codes = TowerMath.codeRange(XpdrMath.parse(c.getString("Lo")), XpdrMath.parse(c.getString("Hi")));
        if (codes != null) {
            t.codeLo = codes[0];
            t.codeHi = codes[1];
        } else if (c.hasKey("Lo")) {
            p.addChatComponentMessage(new ChatComponentTranslation("sva.tower.msg.codesBad"));
        }
        if (c.hasKey("Acc")) {
            List<String> l = new ArrayList<String>();
            for (String n : c.getString("Acc").split(",")) {
                l.add(n);
            }
            te.setAccess(l);
        }
        te.recordChanged(t);
    }

    /** Every end of the tower's runways back to «ВЫШКА» (the loaded end blocks too). */
    static void resetEnds(World w, AirportDataSVA d, AirportDataSVA.Tower t) {
        for (String k : d.setEndsMode(t, RunwayLayout.LIGHTS_TOWER)) {
            int[] pos = TileEntityIlsAntennaSVA.parse(k);
            if (pos != null && w.blockExists(pos[0], pos[1], pos[2])) {
                TileEntity e = w.getTileEntity(pos[0], pos[1], pos[2]);
                if (e instanceof TileEntityRunwayEndSVA) {
                    TileEntityRunwayEndSVA r = (TileEntityRunwayEndSVA) e;
                    r.lights = RunwayLayout.LIGHTS_TOWER;
                    r.markDirty();
                    w.markBlockForUpdate(pos[0], pos[1], pos[2]);
                }
            }
        }
    }

    /** The map's link between two taxi lights toggled: an auto link cut (or restored), a new one added (or removed). */
    @SuppressWarnings("unchecked")
    static void link(AirportDataSVA d, AirportDataSVA.Tower t, String a, String b) {
        if (a.equals(b) || a.length() == 0 || b.length() == 0) {
            return;
        }
        String k = TaxiGraph.link(a, b);
        if (t.taxiExtra.remove(k) || t.taxiCut.remove(k)) {
            d.taxiChanged();
            return;
        }
        Object[] g = d.taxi(t.dim);
        List<TaxiGraph.Node> nodes = (List<TaxiGraph.Node>) g[0];
        List<List<Integer>> adj = (List<List<Integer>>) g[1];
        int i = TaxiGraph.index(nodes, a), j = TaxiGraph.index(nodes, b);
        if (i < 0 || j < 0) {
            return;
        }
        if (adj.get(i).contains(Integer.valueOf(j))) {
            t.taxiCut.add(k);
        } else {
            t.taxiExtra.add(k);
        }
        d.taxiChanged();
    }

    /** «Карта подхода»: one sheet of paper (none in creative) becomes the chart of an approach. */
    public static boolean print(EntityPlayer p, TileEntityTowerSVA te, AirportDataSVA d, AirportDataSVA.Tower t, String apId) {
        ApproachMath.Approach ap = null;
        for (ApproachMath.Approach a : d.approaches(t)) {
            if (a.id.equals(apId)) {
                ap = a;
            }
        }
        if (ap == null) {
            return false;
        }
        if (p != null && !p.capabilities.isCreativeMode && !p.inventory.consumeInventoryItem(Items.paper)) {
            p.addChatComponentMessage(new ChatComponentTranslation("sva.tower.msg.noPaper"));
            return false;
        }
        ItemStack chart = chart(te.getWorldObj(), d, t, ap);
        if (p != null) {
            if (!p.inventory.addItemStackToInventory(chart)) {
                p.dropPlayerItemWithRandomChoice(chart, false);
            }
            p.addChatComponentMessage(new ChatComponentTranslation("sva.tower.msg.printed", ap.id, t.icao));
            if (p instanceof EntityPlayerMP) {
                ((EntityPlayerMP) p).inventoryContainer.detectAndSendChanges();
            }
        }
        return true;
    }

    /** The chart item of an approach (its runway's length / width, ALS, PAPI near the threshold, the lights' mode). */
    public static ItemStack chart(World w, AirportDataSVA d, AirportDataSVA.Tower t, ApproachMath.Approach ap) {
        IlsPairing.Rwy rw = null;
        int dir = 1;
        for (IlsPairing.Rwy r : t.rwys) {
            if (r.nameA.equals(ap.rwy)) {
                rw = r;
                dir = 1;
            } else if (r.nameB.equals(ap.rwy)) {
                rw = r;
                dir = -1;
            }
        }
        boolean als = rw != null && rw.end(dir).als != 0;
        boolean lights = rw == null || d.rwyMode(rw.end(dir)) != 0;
        ChartMath.Chart c = ChartMath.of(t.icao, t.name, ap, rw == null ? 0 : (int) Math.round(rw.len), rw == null ? 5 : rw.width, als,
                papi(w, ap.tx, ap.elev, ap.tz), lights, t.chartVer);
        ItemStack s = new ItemStack(ModContentSVA.approachChart);
        NBTTagCompound tag = new NBTTagCompound();
        ChartMath.write(c, tag);
        s.setTagCompound(tag);
        return s;
    }

    /** A PAPI block within 60 blocks of the threshold, at its height (loaded chunks only). */
    static boolean papi(World w, double tx, int y, double tz) {
        int x0 = (int) Math.floor(tx), z0 = (int) Math.floor(tz);
        for (int dx = -60; dx <= 60; dx++) {
            for (int dz = -60; dz <= 60; dz++) {
                if (!w.blockExists(x0 + dx, y, z0 + dz)) {
                    continue;
                }
                for (int dy = -2; dy <= 2; dy++) {
                    if (w.getBlock(x0 + dx, y + dy, z0 + dz) instanceof BlockPapiSVA) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
