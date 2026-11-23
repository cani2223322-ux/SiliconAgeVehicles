package com.sva.debug;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.sc.dim.SubstrateConfigSC;
import com.sc.embassy.EmbassyAirDefenceSC;
import com.sc.embassy.EmbassyAirRulesSC;
import com.sc.embassy.EmbassyAirServiceSC;
import com.sc.embassy.EmbassyAirfieldSC;
import com.sc.embassy.EmbassyLayoutSC;
import com.sc.embassy.EmbassySC;
import com.sc.embassy.EmbassySiteSC;
import com.sc.embassy.MapGenEmbassySC;
import com.sc.embassy.StructureEmbassyStartSC;
import com.sc.settle.SettlementRegistrySC;
import com.sc.settle.SettlementSC;
import com.sc.settle.SquadSC;
import com.sva.ConfigSVA;
import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.EmbassyAdMath;
import com.sva.airdefense.EmbassyAdSVA;
import com.sva.airdefense.RadarMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.XpdrMath;
import com.sva.airdefense.ZoneMath;
import com.sva.airport.Cpdlc;
import com.sva.airport.EmbassyAirportMath;
import com.sva.airport.EmbassyAirportSVA;
import com.sva.airport.EmbassyAtcMath;
import com.sva.airport.IlsCat;
import com.sva.airport.IlsPairing;
import com.sva.airport.TowerMath;
import com.sva.block.AirportDataSVA;
import com.sva.block.AtcServerSVA;
import com.sva.block.BlockAirDefenseSVA;
import com.sva.block.EmbassyParkingDataSVA;
import com.sva.block.EmbassyServicesSVA;
import com.sva.block.IlsDataSVA;
import com.sva.block.NavDataSVA;
import com.sva.block.TileEntityAirDefenseSVA;
import com.sva.block.TileEntityCommandPostSVA;
import com.sva.block.TileEntityLauncherSVA;
import com.sva.block.TileEntityRadarSVA;
import com.sva.compat.EmbassyCompatSVA;
import com.sva.compat.EmbassyFieldSVA;
import com.sva.entity.EntitySeatSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.LinerAfs;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerIls;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.NavDb;
import com.sva.vehicle.RunwaySurfaceSVA;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.Container;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * PACKAGE D (docs/plan-embassy-airfield-addon.md §6.2): the world test of «Лётное поле посольства» in the add-on, run by WorldTestSVA
 * from tick START (the coordinator's hook). It makes a NEW airfield embassy as the core's WorldTestAirfieldSC does (embassy.airfield
 * ALWAYS for the moment of its decision, a fresh site, the start made with a FakePlayer at the future runway's middle, the start's chunks
 * kept loaded; the old value back), or takes the one an earlier run of this test or the core's test marked (SVATestAirfield /
 * SCTestAirfield in the record's extra), then checks on the real server, step after step:
 * <ol>
 * <li>the API and the field: the hook is the add-on's, the field's copy = the core's numbers, the levels and the admission of two test
 * players (a «Чужак» and a «Знакомый» - LAN: two at once), the runway surface;</li>
 * <li>the virtual airport: the runway ends, the tower online with an X code of its own, 5100..5177, the runway numbers, the ILS stations
 * CAT III at the FULL ends with ILS, not one virtual key in the saves' NBT (AirportDataSVA, IlsDataSVA); the client copy of a player who
 * does not know the embassy has none of it, of one who does - has; the MCDU's database (NavDataSVA) - SRC_EMBASSY only for a knower;</li>
 * <li>ATIS: a liner 700 from the field - no line of the embassy tower in the chat, 450 - a line; bound for it in the MCDU - at 2000 too;</li>
 * <li>the embassy's air defence (EmbassyAdSVA; the machines held in the air): the warning (the core's line, the HUD, the radio), 10 s,
 * the fire from a post (the shells' home = a post's gun) and the damage; out of the zone - AD_LEFT, back the same day - the fire without
 * a warning; closing by 40 / descending under 40 - the fire at once; an admitted pilot, an admitted passenger, an admitted squad mate
 * near - no warning; 7700 - the pass, its end, the second one the same day refused; on foot / flying without a machine / a machine on
 * the ground - nothing; an unmanned machine - its owner warned, then the fire; airdefense.embassyAd off - nothing;</li>
 * <li>the neutral sky: a player's command post with a radar and two guns «по всем» - the gun by the approach corridor never gets to fire
 * at the aircraft over it, the other one at the aircraft off the corridor does; the command post's forbidden zone over the field
 * refused (sva.emb.zoneNeutral), one far off taken;</li>
 * <li>the ILS landing: an SA-320 1000 out on the FULL end with ILS, APPR + AP 1 + 2, autobrake MED - the tower's clearance, ROLL OUT,
 * stopped on the embassy's runway within its landing distance; on a SHORT field (if the world has one) the liner refused («liner»);</li>
 * <li>the stands (two a player, the third not on one), the tow off the runway (to a stand / the lot, never gone), the service window and
 * a paid charge (the chips taken by the core, the buffer filled), the «Враг» and a FakePlayer refused, the «Лётная книжка» once;</li>
 * <li>everything set back: the test's machines, blocks, players, the config, the squad, the reputations; the land made stays.</li>
 * </ol>
 * The test players are real EntityPlayerMP without a network (their chat kept; a muted connection drops every packet - the add-on's
 * and the core's sends treat them as any player), in the world's and the server's lists while the test runs (the core's services, the
 * owner's «online», the towers' reports see them). The only add-on class besides EmbassyCompatSVA that names the core's embassy classes
 * (com.sc.embassy.*, its registry, its config, its squads - test-side helpers); all of that lives in the nested Run, loaded only with the
 * API on (an older core: SKIP). Methods the packages added past the shared core (the per-player client copy, the viewers' database) are
 * asked by reflection - a package not written yet fails its own line.
 * <p>
 * Output «[SVA-WORLDTEST] PASS / FAIL embassy world «step»: ...», INFO / SKIP lines for what this world can't show.
 */
final class WorldTestEmbassySVA {

    /** After the other suites' areas are made (its own embassy far out). */
    static final int START = 3400;

    private final WorldTestSVA t;
    boolean done;
    /** The test itself (loaded only with the core's API on). */
    private Run run;

    WorldTestEmbassySVA(WorldTestSVA t) {
        this.t = t;
    }

    private void check(boolean ok, String name) {
        t.check(ok, name);
    }

    void tick(World w, int ticks) {
        if (run == null) {
            if (!EmbassyCompatSVA.on() || !(w instanceof WorldServer)) {
                System.out.println("[SVA-WORLDTEST] SKIP embassy airfield: no embassy API in this core (" + EmbassyCompatSVA.why() + ")");
                done = true;
                return;
            }
            run = new Run(this, (WorldServer) w);
        }
        run.tick(ticks);
        if (run.finished) {
            done = true;
        }
    }

    /** An exception: everything set back, done. */
    void abort(World w) {
        try {
            if (run != null) {
                run.finish();
            }
        } catch (Throwable x) {
            System.out.println("[SVA-WORLDTEST] FAIL embassy airfield: the clean-up threw " + x);
        }
        done = true;
    }

    // ================================================================== the test players

    /** A real player without a network: never ticks, his chat kept (WorldTestArmourerSC.StationPlayer's way). */
    static final class TestPlayer extends EntityPlayerMP {
        final List<String> said = new ArrayList<String>();

        TestPlayer(WorldServer w, GameProfile profile) {
            super(MinecraftServer.getServer(), w, profile, new ItemInWorldManager(w));
        }

        @Override
        public void addChatComponentMessage(IChatComponent c) {
            said.add(c.getUnformattedText());
        }

        @Override
        public void addChatMessage(IChatComponent c) {
            said.add(c.getUnformattedText());
        }

        @Override
        public void sendContainerToPlayer(Container c) {
        }

        @Override
        public void onUpdate() {
        }
    }

    /**
     * A connection that drops every packet: vanilla's sends end in sendPacket; FML's find no dispatcher on the quiet channel (no throw -
     * a NetworkManager without a channel would make FMLOutboundHandler throw inside the add-on's shared channel).
     */
    static final class Mute extends NetHandlerPlayServer {
        Mute(EntityPlayerMP p) {
            super(MinecraftServer.getServer(), quiet(), p);
        }

        static NetworkManager quiet() {
            NetworkManager m = new NetworkManager(false);
            try {
                for (java.lang.reflect.Field f : NetworkManager.class.getDeclaredFields()) {
                    if (Channel.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        f.set(m, new EmbeddedChannel(new ChannelInboundHandlerAdapter()));
                    }
                }
            } catch (Exception e) {
                System.out.println("[SVA-WORLDTEST] INFO embassy: the quiet channel could not be set (" + e + ")");
            }
            return m;
        }

        @Override
        public void sendPacket(Packet p) {
        }
    }

    // ================================================================== the run

    /** One step: called every tick from its start with its own tick count; true - done (or past `max`). */
    abstract static class Step {
        final String name;
        final int max;
        int at = -1;

        Step(String name, int max) {
            this.name = name;
            this.max = max;
        }

        abstract boolean tick(int k, long now);
    }

    static final class Run {

        private static final String MARK = "SVATestAirfield", CORE_MARK = "SCTestAirfield";
        private static final int RINGS = 14, SITES = 8;
        private static final String KP_OWNER = "TestEmbKP";
        private static final String SPARE_ID = "00000000-5bad-4e57-0000-0000000e0b00", SPARE_NAME = "TestEmbSpare";
        private static final int PILOT = EntityVehicleSVA.IN_CRUISE;
        /** A callsign no board has: the AD lines are matched round it. */
        private static final String CS = "@@SVA-CS@@";

        private final WorldTestEmbassySVA o;
        private final WorldServer w;
        boolean finished;
        private int ticks, si;
        private final List<Step> steps = new ArrayList<Step>();
        private SettlementRegistrySC reg;
        private SettlementRegistrySC.Settlement rec;
        private EmbassyAirfieldSC.Field cf;
        private EmbassyFieldSVA f;
        private int id = -1;
        private boolean reused;
        private int modeWas = -1, towWas = -1, domeWas = -1;
        private Boolean adWas;
        /** The embassy tower whose runway in use a step chose, and its own choice before (rwyBack). */
        private AirportDataSVA.Tower selTower;
        private String selWas;
        private TestPlayer p1, p2, p3;
        private FakePlayer fake;
        private final List<EntityVehicleSVA> vehicles = new ArrayList<EntityVehicleSVA>();
        private final Map<EntityVehicleSVA, double[]> hold = new LinkedHashMap<EntityVehicleSVA, double[]>();
        /** Chunks kept loaded: the field's box {minCX, minCZ, maxCX, maxCZ} and anchors {x, z, radius in chunks}; the liners followed. */
        private int[] box;
        private final List<int[]> anchors = new ArrayList<int[]>();
        private final List<EntityVehicleSVA> followed = new ArrayList<EntityVehicleSVA>();
        /** Blocks the test replaced {x, y, z, block id, meta} - set back at the end. */
        private final List<int[]> replaced = new ArrayList<int[]>();
        private final Set<Long> replacedAt = new HashSet<Long>();
        private final List<TileEntityAirDefenseSVA> adBlocks = new ArrayList<TileEntityAirDefenseSVA>();
        /** The test's platforms {x0, z0, x1, z1, y} (cleared to air before the old blocks go back). */
        private final List<int[]> platforms = new ArrayList<int[]>();
        private boolean squad;
        /** The AD's numbers: the post's gun its scenarios fly at, the point Q near it in the zone, Qout out of it, the unit from the core. */
        private double[] q, qOut, ux;
        private int[] post;

        Run(WorldTestEmbassySVA o, WorldServer w) {
            this.o = o;
            this.w = w;
            plan();
        }

        private void check(boolean ok, String name) {
            o.check(ok, "embassy world " + name);
        }

        private static void info(String s) {
            System.out.println("[SVA-WORLDTEST] INFO embassy: " + s);
        }

        // ------------------------------------------------------------------ the schedule

        void tick(int t) {
            ticks = t;
            if (finished) {
                return;
            }
            keepLoaded();
            holdAll();
            long now = w.getTotalWorldTime();
            while (si < steps.size()) {
                Step s = steps.get(si);
                if (s.at < 0) {
                    s.at = ticks;
                }
                boolean end;
                try {
                    end = s.tick(ticks - s.at, now);
                    if (!end && ticks - s.at >= s.max) {
                        check(false, "«" + s.name + "»: not finished in " + s.max + " ticks");
                        abnormal();
                        end = true;
                    }
                } catch (Throwable x) {
                    check(false, "«" + s.name + "»: exception " + x);
                    x.printStackTrace(System.out);
                    abnormal();
                    end = true;
                }
                if (!end) {
                    return;
                }
                si++;
                if (rec == null || f == null) {
                    break;                                          // no field: nothing more to do
                }
            }
            finish();
        }

        private void step(Step s) {
            steps.add(s);
        }

        /** A step cut short: its machines gone (the parked ones kept for the next), the switches back, the players at the desk. */
        private void abnormal() {
            for (EntityVehicleSVA v : new ArrayList<EntityVehicleSVA>(vehicles)) {
                if (!parked.contains(v)) {
                    remove(v);
                }
            }
            adBack();
            rwyBack();
            if (towWas >= 0) {
                ConfigSVA.aptEmbassyTowMinutes = towWas;
                towWas = -1;
            }
            if (squad && reg != null && p1 != null) {
                SquadSC.leave(reg, p1.getUniqueID(), p1.getCommandSenderName());
                squad = false;
            }
            if (p1 != null && f != null) {
                resetPlayers();
            }
        }

        private void plan() {
            step(new Step("generation", 5) {
                boolean tick(int k, long now) {
                    generation();
                    return true;
                }
            });
            step(new Step("players and API", 40) {
                boolean tick(int k, long now) {
                    if (k == 0) {
                        players();
                    }
                    if (k < 5) {
                        return false;
                    }
                    api(now);
                    return true;
                }
            });
            step(new Step("airport", 400) {
                boolean tick(int k, long now) {
                    if (k < EmbassyAirportSVA.KNOW_TICKS + 20) {
                        return false;
                    }
                    airport(now);
                    return true;
                }
            });
            atis();
            adSteps();
            neutral();
            ils();
            shortField();
            stands();
            services();
            wrongStands();
            logbook();
        }

        // ------------------------------------------------------------------ 0. the embassy (as the core's WorldTestAirfieldSC.generate)

        private void generation() {
            reg = SettlementRegistrySC.get(w);
            if (reg == null || !SettlementSC.embassiesGenerate() || !EmbassySiteSC.worldFits(w)) {
                System.out.println("[SVA-WORLDTEST] SKIP embassy airfield: the embassies don't generate here (embassies " + SubstrateConfigSC.embassies + ")");
                return;
            }
            for (SettlementRegistrySC.Settlement s : reg.embassies()) {
                EmbassyAirfieldSC.Field ff = EmbassyAirfieldSC.fieldOf(s);
                if (s.dim == EmbassySiteSC.DIM && ff != null && ff.kind == EmbassyAirfieldSC.FULL && (s.extra.getBoolean(MARK) || s.extra.getBoolean(CORE_MARK))) {
                    rec = s;
                    reused = true;
                    break;
                }
            }
            if (rec == null) {
                modeWas = SubstrateConfigSC.embassyAirfield();
                SubstrateConfigSC.setEmbassyAirfield(SubstrateConfigSC.AIRFIELD_ALWAYS);
                try {
                    make();
                } finally {
                    SubstrateConfigSC.setEmbassyAirfield(modeWas);
                    check(SubstrateConfigSC.embassyAirfield() == modeWas, "«generation»: embassy.airfield set back after the decision");
                    modeWas = -1;
                }
            }
            if (rec == null) {
                return;
            }
            cf = EmbassyAirfieldSC.fieldOf(rec);
            id = rec.id;
            boxOf(cf);
            keepLoaded();
            f = EmbassyCompatSVA.field(w, id);
            check(f != null && cf != null && cf.kind == EmbassyAirfieldSC.FULL, "«generation»: a FULL airfield embassy #" + id + " " + (reused ? "of an earlier run"
                    : "made now") + " at " + rec.x + " " + rec.z + " - the add-on's copy " + f);
        }

        private void make() {
            int sx = w.getWorldInfo().getSpawnX(), sz = w.getWorldInfo().getSpawnZ();
            EmbassySiteSC.Site site = null;
            int[] decided = null;
            String kinds = "";
            for (EmbassySiteSC.Site s : freshSites(sx, sz)) {
                int[] d = EmbassyAirfieldSC.decideFor(w, s);
                kinds += (kinds.length() == 0 ? "" : " ") + (d == null ? "null" : String.valueOf(d[0]));
                if (d != null && d[0] == EmbassyAirfieldSC.FULL) {
                    site = s;
                    decided = d;
                    break;
                }
            }
            if (site == null) {
                System.out.println("[SVA-WORLDTEST] SKIP embassy airfield: no fresh site with a FULL field within " + RINGS + " rings (" + kinds + ")");
                return;
            }
            EmbassyLayoutSC.Plan plan = EmbassyLayoutSC.plan(w.getSeed(), site, site.floorY, decided);
            EmbassyAirfieldSC.Field ff = plan.field;
            int[] b = EmbassyLayoutSC.footprintChunks(plan).clone();
            grow(b, ff);
            box = b;
            int mx = ff.x(0, ff.axis), mz = ff.z(0, ff.axis);
            fake = FakePlayerFactory.get(w, new GameProfile(UUID.fromString("5c0e7a1e-a1f1-4000-8000-0000000005a1"), "[SVATestAirfield]"));
            fake.setLocationAndAngles(mx + 0.5D, site.floorY + 1, mz + 0.5D, 0F, 0F);   // its chunk - the future runway's middle - first
            w.getChunkFromBlockCoords(mx, mz);
            MapGenEmbassySC gen = EmbassySC.mapGen(w);
            StructureEmbassyStartSC start = gen == null ? null : gen.startAt(w, site.chunkX(), site.chunkZ());
            for (int x = b[0] - 1; x <= b[2] + 1; x++) {
                for (int z = b[1] - 1; z <= b[3] + 1; z++) {
                    w.getChunkFromChunkCoords(x, z);
                }
            }
            rec = reg.atCentre(EmbassySiteSC.DIM, site.x, site.z);
            if (rec != null) {
                rec.extra.setBoolean(MARK, true);                    // this test's own: a later run takes it again
                reg.markDirty();
            }
            check(start != null && start.isSizeableStructure() && rec != null && EmbassyAirfieldSC.fieldOf(rec) != null,
                    "«generation»: a new embassy with a FULL field made at " + site + " (decisions " + kinds + "), the start with a FakePlayer at the runway's"
                            + " middle " + mx + " " + mz + ", " + ((b[2] - b[0] + 3) * (b[3] - b[1] + 3)) + " chunks loaded");
        }

        /** The fresh sites round the spawn (no start, no record, unexplored, no zone near) - nearest first (the core test's way). */
        private List<EmbassySiteSC.Site> freshSites(final int sx, final int sz) {
            int g = EmbassySiteSC.grid(), rx0 = EmbassySiteSC.region(sx, g), rz0 = EmbassySiteSC.region(sz, g);
            List<EmbassySiteSC.Site> out = new ArrayList<EmbassySiteSC.Site>();
            MapGenEmbassySC gen = EmbassySC.mapGen(w);
            for (int r = 0; r <= RINGS && out.size() < SITES; r++) {
                for (int i = -r; i <= r; i++) {
                    for (int j = -r; j <= r; j++) {
                        if (Math.max(Math.abs(i), Math.abs(j)) != r) {
                            continue;
                        }
                        EmbassySiteSC.Site s = EmbassySiteSC.site(w, rx0 + i, rz0 + j);
                        if (s != null && !s.made && (gen == null || gen.startAt(w, s.chunkX(), s.chunkZ()) == null)
                                && reg.atCentre(EmbassySiteSC.DIM, s.x, s.z) == null && !EmbassySiteSC.explored(w, s) && !EmbassySiteSC.zoneNear(w, s)) {
                            out.add(s);
                        }
                    }
                }
            }
            Collections.sort(out, new Comparator<EmbassySiteSC.Site>() {
                @Override
                public int compare(EmbassySiteSC.Site a, EmbassySiteSC.Site b) {
                    return Double.compare(a.distSq(sx, sz), b.distSq(sx, sz));
                }
            });
            return out;
        }

        /** The chunk box grown by the field's zone and funnels (and the dome's level ground). */
        private static void grow(int[] b, EmbassyAirfieldSC.Field ff) {
            List<int[]> boxes = new ArrayList<int[]>();
            boxes.add(ff.zoneBox());
            for (int[] q : ff.funnels) {
                boxes.add(ff.world(q));
            }
            boxes.add(new int[] {ff.coreX - ff.radius - 48, ff.coreZ - ff.radius - 48, ff.coreX + ff.radius + 48, ff.coreZ + ff.radius + 48});
            for (int[] x : boxes) {
                b[0] = Math.min(b[0], x[0] >> 4);
                b[1] = Math.min(b[1], x[1] >> 4);
                b[2] = Math.max(b[2], x[2] >> 4);
                b[3] = Math.max(b[3], x[3] >> 4);
            }
        }

        private void boxOf(EmbassyAirfieldSC.Field ff) {
            if (box == null && ff != null) {
                int c = ff.coreX >> 4, d = ff.coreZ >> 4;
                int[] b = {c, d, c, d};
                grow(b, ff);
                box = b;
            }
        }

        // ------------------------------------------------------------------ chunks, positions, machines, players

        private void keepLoaded() {
            if (box != null) {
                for (int x = box[0]; x <= box[2]; x++) {
                    for (int z = box[1]; z <= box[3]; z++) {
                        w.theChunkProviderServer.loadChunk(x, z);
                    }
                }
            }
            for (int[] a : anchors) {
                load(a[0], a[1], a[2]);
            }
            for (EntityVehicleSVA v : followed) {
                if (!v.isDead) {
                    load(v.posX, v.posZ, 3);
                }
            }
        }

        private void load(double x, double z, int r) {
            int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
            for (int i = -r; i <= r; i++) {
                for (int j = -r; j <= r; j++) {
                    w.theChunkProviderServer.loadChunk(cx + i, cz + j);
                }
            }
        }

        /** A point whose chunks stay loaded for the rest of the test. */
        private void anchor(double x, double z, int r) {
            anchors.add(new int[] {(int) Math.floor(x), (int) Math.floor(z), r});
            load(x, z, r);
        }

        private void holdAll() {
            for (Map.Entry<EntityVehicleSVA, double[]> e : hold.entrySet()) {
                EntityVehicleSVA v = e.getKey();
                double[] p = e.getValue();
                if (!v.isDead) {
                    v.setPosition(p[0], p[1], p[2]);
                    v.motionX = v.motionY = v.motionZ = 0;
                }
            }
        }

        private EntityVehicleSVA spawn(VehicleType type, double x, double y, double z, float yaw, String ownerId, String ownerName, boolean held) {
            anchor(x, z, 2);
            VehicleState st = new VehicleState(type);
            st.energy = st.maxEnergy();
            if (type == VehicleType.LINER) {
                st.fillAllGases();
            }
            EntityVehicleSVA v = ItemVehicleSVA.spawn(w, ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, type.ordinal()), st), x, y, z, yaw,
                    ownerId, ownerName);
            if (v != null) {
                vehicles.add(v);
                if (held) {
                    hold.put(v, new double[] {x, y, z});
                }
            }
            return v;
        }

        private EntityVehicleSVA plane(double[] at, TestPlayer pilot, String ownerId, String ownerName) {
            EntityVehicleSVA v = spawn(VehicleType.PLANE, at[0], at[1], at[2], 0F, ownerId, ownerName, true);
            if (v != null && pilot != null) {
                ride(pilot, v);
            }
            return v;
        }

        private EntityVehicleSVA plane(double[] at, TestPlayer pilot) {
            return plane(at, pilot, pilot.getUniqueID().toString(), pilot.getCommandSenderName());
        }

        private void move(EntityVehicleSVA v, double[] to) {
            if (v != null) {
                hold.put(v, to.clone());
                v.setPosition(to[0], to[1], to[2]);
                for (Entity e : v.riders()) {
                    e.setPosition(to[0], to[1], to[2]);           // the riders never tick: their place goes with the machine
                }
            }
        }

        /** Seats / unseats a player by the fields (no mounting packets). */
        private static void ride(EntityPlayer p, Entity m) {
            if (p.ridingEntity != null) {
                p.ridingEntity.riddenByEntity = null;
            }
            p.ridingEntity = m;
            if (m != null) {
                m.riddenByEntity = p;
                p.setPosition(m.posX, m.posY, m.posZ);
            }
        }

        private void remove(EntityVehicleSVA v) {
            if (v == null) {
                return;
            }
            for (Entity e : v.riders()) {
                if (e instanceof EntityPlayer) {
                    ride((EntityPlayer) e, null);
                }
            }
            hold.remove(v);
            followed.remove(v);
            EmbassyParkingDataSVA pd = EmbassyParkingDataSVA.get(w);
            if (pd != null && v.state.vehId != null) {
                pd.release(v.state.vehId);
            }
            if (!v.isDead) {
                v.setDead();
            }
        }

        private void removeAll(EntityVehicleSVA... vs) {
            for (EntityVehicleSVA v : vs) {
                remove(v);
            }
        }

        private TestPlayer player(String name) {
            TestPlayer p = new TestPlayer(w, new GameProfile(UUID.nameUUIDFromBytes(("sva-embassy-test:" + name).getBytes()), name));
            new Mute(p);
            p.capabilities.disableDamage = true;
            return p;
        }

        @SuppressWarnings("unchecked")
        private void online(EntityPlayerMP p, boolean on) {
            List<Object> world = w.playerEntities;
            List<Object> server = MinecraftServer.getServer().getConfigurationManager().playerEntityList;
            if (on) {
                if (!world.contains(p)) {
                    world.add(p);
                }
                if (!server.contains(p)) {
                    server.add(p);
                }
            } else {
                world.remove(p);
                server.remove(p);
            }
        }

        private void at(EntityPlayer p, double x, double y, double z) {
            p.setPosition(x, y, z);
        }

        /** `p`'s record at `rep` with today's counters fresh, not the embassy's «Враг», no AD ledger, no logbook entry. */
        private SettlementRegistrySC.PlayerRec trust(EntityPlayer p, int rep) {
            SettlementRegistrySC.PlayerRec pr = reg.player(p.getUniqueID());
            long today = SettlementSC.day(w);
            pr.reputation = rep;
            pr.rollDay(today - 1);
            pr.rollDay(today);
            pr.extra.removeTag(EmbassyAirRulesSC.LOGBOOK);
            SettlementRegistrySC.LocalRec l = rec.local(p.getUniqueID());
            l.enemyUntil = 0L;
            l.rollDay(today - 1);
            l.rollDay(today);
            l.extra.removeTag(EmbassyAirRulesSC.LEDGER);
            reg.markDirty();
            return pr;
        }

        /** A fresh game day for `p`'s air defence ledgers (the warning, the 7700 pass). */
        private void newDay(EntityPlayer p) {
            rec.local(p.getUniqueID()).extra.removeTag(EmbassyAirRulesSC.LEDGER);
        }

        /** airdefense.embassyAd off for a step about something else (its value kept once). */
        private void adOff() {
            if (adWas == null) {
                adWas = Boolean.valueOf(ConfigSVA.adEmbassy);
            }
            ConfigSVA.adEmbassy = false;
        }

        private void adBack() {
            if (adWas != null) {
                ConfigSVA.adEmbassy = adWas.booleanValue();
                adWas = null;
            }
        }

        /** The embassy tower's runway in use: the end `endKey` a step lands over (as a player picks it on a tower's screen) - not the wind's. */
        private void useEnd(String endKey) {
            AirportDataSVA d = AirportDataSVA.get(w);
            String tk = EmbassyAirportSVA.INSTANCE.towerKey(id);
            AirportDataSVA.Tower t = d == null || tk == null ? null : d.tower(tk);
            if (t != null) {
                if (selTower == null) {
                    selTower = t;
                    selWas = t.rwySel;
                }
                t.rwySel = endKey;
            }
        }

        private void rwyBack() {
            if (selTower != null) {
                selTower.rwySel = selWas;
                selTower = null;
                selWas = null;
            }
        }

        private void enemy(EntityPlayer p, boolean e) {
            rec.local(p.getUniqueID()).enemyUntil = e ? w.getTotalWorldTime() + 2000L : 0L;
        }

        // ------------------------------------------------------------------ 1. the players and the API

        private void players() {
            p1 = player("SVATestEmbA");                               // a «Чужак» (never on the ground here)
            p2 = player("SVATestEmbB");                               // a «Знакомый» (admitted)
            p3 = player("SVATestEmbC");                               // far away, never there: does not know the embassy
            trust(p1, 0);
            trust(p2, 150);
            trust(p3, 0);
            int[] desk = f.towerDesk();
            for (TestPlayer p : new TestPlayer[] {p1, p2}) {
                at(p, desk[0] + 0.5, f.floorY + 1, desk[2] + 0.5);
                online(p, true);
            }
            double sx = w.getWorldInfo().getSpawnX(), sz = w.getWorldInfo().getSpawnZ();
            if (Math.hypot(sx - f.coreX, sz - f.coreZ) < 1500) {
                sx = f.coreX + 2000;                                  // the spawn too near: a point well out (its chunks loaded)
                sz = f.coreZ;
                anchor(sx, sz, 1);
            }
            at(p3, sx + 0.5, 200, sz + 0.5);
            online(p3, true);
            fake = fake != null ? fake : FakePlayerFactory.get(w, new GameProfile(UUID.fromString("5c0e7a1e-a1f1-4000-8000-0000000005a1"), "[SVATestAirfield]"));
            // the AD's places: the post farthest from the core, Q 50 out from it in the zone (60 over the field), Qout at 300 the same way
            int[][] posts = f.adPosts();
            post = new int[] {f.coreX + 60, f.floorY + 2, f.coreZ};
            double far = -1;
            for (int[] g : posts) {
                if (f.fromCore(g[0] + 0.5, g[2] + 0.5) > far) {
                    far = f.fromCore(g[0] + 0.5, g[2] + 0.5);
                    post = g;
                }
            }
            double dx = post[0] + 0.5 - f.coreX, dz = post[2] + 0.5 - f.coreZ, dl = Math.max(1, Math.hypot(dx, dz));
            ux = new double[] {dx / dl, dz / dl};
            double out = dl + 50;
            // ЛП-1 of the core audit 2026-11-04: the guns fire only over the embassy's square and the approaches - Q back inside it
            while (out > 10 && EmbassyCompatSVA.adZoneAt(w, f.coreX + ux[0] * out, f.coreZ + ux[1] * out) != id) {
                out -= 5;
            }
            q = new double[] {f.coreX + ux[0] * out, f.floorY + 60, f.coreZ + ux[1] * out};
            qOut = new double[] {f.coreX + ux[0] * 300, f.floorY + 60, f.coreZ + ux[1] * 300};
            anchor(q[0], q[2], 2);
            anchor(qOut[0], qOut[2], 2);
            for (int[] p : posts) {
                anchor(p[0], p[2], 1);
            }
        }

        private void api(long now) {
            boolean hook = EmbassyCompatSVA.debugHookIsOurs();
            EmbassyFieldSVA again = EmbassyCompatSVA.field(w, id);
            boolean listed = false;
            for (EmbassyFieldSVA x : EmbassyCompatSVA.fields(w)) {
                listed |= x.id == id;
            }
            int[] rb = f.runwayBox();
            int mx = (rb[0] + rb[2]) / 2, mz = (rb[1] + rb[3]) / 2;
            EmbassyFieldSVA at = EmbassyCompatSVA.fieldAt(w, mx, mz);
            boolean copy = again != null && again.id == id && f.kind == EmbassyCompatSVA.FULL && f.width == 9 && f.length == 201 && f.floorY == cf.floorY
                    && f.coreX == cf.coreX && f.coreZ == cf.coreZ && f.icao.length() == 4 && f.icao.charAt(0) == 'X' && listed && at != null && at.id == id
                    && f.adRadius == EmbassyCompatSVA.adRadius() && f.adPosts().length > 0 && f.spots() >= 4 && f.columns().length > 0;
            boolean where = EmbassyCompatSVA.domeAt(w, f.coreX + 0.5, f.coreZ + 0.5) == id && EmbassyCompatSVA.adZoneAt(w, q[0], q[2]) == id
                    && EmbassyCompatSVA.adZoneAt(w, qOut[0], qOut[2]) == -1 && EmbassyCompatSVA.neutralAt(w, mx + 0.5, f.floorY + 150, mz + 0.5)
                    && EmbassyCompatSVA.inZone(f, mx, mz) && f.inAdCircle(q[0], q[2]) && !f.inAdCircle(qOut[0], qOut[2]);
            boolean surface = RunwaySurfaceSVA.is(w, mx, f.floorY, mz) && RunwaySurfaceSVA.embassy(w, mx, f.floorY, mz) && f.onRunway(mx + 0.5, mz + 0.5);
            int[] ab = f.apronBox();
            int ax = (ab[0] + ab[2]) / 2, az = (ab[1] + ab[3]) / 2;
            surface &= !RunwaySurfaceSVA.is(w, ax, f.floorY, az) && !RunwaySurfaceSVA.is(w, mx, f.floorY + 3, mz);
            check(hook && copy && where && surface, "«API»: the core's hook is the add-on's " + hook + "; the field's copy = the core's (FULL 201 x 9, " + f.icao
                    + ", AD " + f.adRadius + ", " + f.adPosts().length + " posts, " + f.spots() + " stands, listed, found at the runway) " + copy + "; the dome, the AD zone (Q in,"
                    + " 300 out), the neutral sky over the runway at any height, the protected zone " + where + "; the runway's paving is a runway surface (an"
                    + " embassy's), the apron not " + surface);
            int l1 = EmbassyCompatSVA.levelOf(p1, id), l2 = EmbassyCompatSVA.levelOf(p2, id);
            boolean adm = !EmbassyCompatSVA.admitted(p1, id) && EmbassyCompatSVA.admitted(p2, id) && EmbassyCompatSVA.holdFire(p2, id)
                    && !EmbassyCompatSVA.holdFire(p1, id) && !EmbassyCompatSVA.admitted(fake, id) && EmbassyCompatSVA.knows(p1, id) && !EmbassyCompatSVA.knows(p3, id);
            enemy(p2, true);
            int le = EmbassyCompatSVA.levelOf(p2, id);
            boolean enemyNot = !EmbassyCompatSVA.admitted(p2, id);
            enemy(p2, false);
            boolean keys = java.util.Arrays.equals(EmbassyCompatSVA.AD_KEYS, EmbassyAirDefenceSC.AD_KEYS) && EmbassyCompatSVA.STRANGER == l1
                    && EmbassyCompatSVA.ACQUAINTANCE == l2 && EmbassyCompatSVA.ENEMY == le && EmbassyCompatSVA.adRadius() == EmbassyAirRulesSC.AD_RADIUS
                    && EmbassyAirRulesSC.ADMIT_LEVEL == EmbassyCompatSVA.ACQUAINTANCE;
            check(adm && enemyNot && keys, "«API»: two players at once - the «Чужак» (level " + l1 + ") not admitted, the «Знакомый» (" + l2 + ") admitted and"
                    + " holds the fire, as the embassy's «Враг» (" + le + ") not " + enemyNot + ", a FakePlayer never; both near know it, a far one not " + adm
                    + "; the AD lines' keys and the levels' numbers = the core's (EmbassyAirDefenceSC, ReputationSC) " + keys);
        }

        // ------------------------------------------------------------------ 2. the virtual airport (§6.2.1)

        private void airport(long now) {
            AirportDataSVA d = AirportDataSVA.get(w);
            String lo = EmbassyAirportMath.key(0, f.thresholdKey(true)), hi = EmbassyAirportMath.key(0, f.thresholdKey(false));
            boolean ends = d != null && d.end(lo) != null && d.end(hi) != null && EmbassyAirportSVA.isVirtual(lo) && EmbassyAirportSVA.isVirtual(hi)
                    && EmbassyAirportSVA.embassyOf(lo) == id;
            String tk = EmbassyAirportSVA.INSTANCE.towerKey(id);
            if (tk == null) {
                tk = EmbassyAirportMath.key(0, f.towerDesk());
            }
            AirportDataSVA.Tower t = d == null ? null : d.tower(tk);
            boolean tower = t != null && t.online(now) && EmbassyCompatSVA.ownerOf(id).equals(t.owner) && t.icao.length() == 4
                    && Character.toUpperCase(t.icao.charAt(0)) == 'X' && TowerMath.unique(t.icao, d.icaosExcept(t.key)) && t.codeLo == EmbassyAtcMath.CODE_LO
                    && t.codeHi == EmbassyAtcMath.CODE_HI && EmbassyAirportSVA.isVirtual(t.key);
            String names = "";
            boolean numbers = false;
            for (IlsPairing.Rwy r : d == null ? new ArrayList<IlsPairing.Rwy>() : d.runways()) {
                if (r.a.key.equals(lo) || r.a.key.equals(hi) || r.b.key.equals(lo) || r.b.key.equals(hi)) {
                    names = r.nameA + "/" + r.nameB;
                    Set<String> got = new HashSet<String>(), want = new HashSet<String>();
                    got.add(r.nameA);
                    got.add(r.nameB);
                    want.add(f.numberLow);
                    want.add(f.numberHigh);
                    numbers = got.equals(want);
                }
            }
            StringBuilder ils = new StringBuilder();
            boolean cat3 = true;                                    // no ILS end (a hill over the plane, ЛП-2): nothing to check
            if (!f.ilsLow && !f.ilsHigh) {
                ils.append(" - none on this field");
            }
            for (int e = 0; e < 2; e++) {
                boolean low = e == 0;
                if (low ? !f.ilsLow : !f.ilsHigh) {
                    continue;
                }
                String lk = EmbassyAirportMath.key(0, f.localizer(low));
                LinerIls.Station s = d == null ? null : d.station(lk);
                IlsCat.Result res = d == null ? null : d.result(lk);
                boolean ok = s != null && s.cat == 3 && res != null && res.cat == 3 && EmbassyAirportSVA.isVirtual(lk);
                cat3 &= ok;
                ils.append(' ').append(low ? f.numberLow : f.numberHigh).append(':').append(s == null ? "none" : "CAT " + s.cat + (res == null ? "" : "/" + res.cat));
                for (Integer i : res == null || ok ? new ArrayList<Integer>() : res.issues) {
                    ils.append(i == res.issues.get(0) ? " (" : ", ").append(IlsCat.ISSUE_KEYS[i.intValue()]);
                }
                ils.append(res == null || ok || res.issues.isEmpty() ? "" : ", tch " + Math.round(res.tch * 100) / 100.0 + ")");
            }
            check(ends && tower && numbers && cat3, "«airport»: the runway ends at the displaced thresholds " + ends + "; the tower " + tk + " online, the"
                    + " settlement's own, code " + (t == null ? "-" : t.icao) + " unique, 5100..5177 " + tower + "; the runway " + names + " = "
                    + f.numberLow + "/" + f.numberHigh + " " + numbers + "; ILS CAT III at the ends with ILS" + ils + " " + cat3);
            // not one virtual key in the saves' NBT
            Set<String> mine = new HashSet<String>();
            for (String k : EmbassyAirportSVA.virtualKeys()) {
                if (EmbassyAirportSVA.embassyOf(k) == id) {
                    mine.add(k);
                }
            }
            NBTTagCompound a = new NBTTagCompound(), b = new NBTTagCompound();
            if (d != null) {
                d.writeToNBT(a);
            }
            IlsDataSVA il = IlsDataSVA.get(w);
            if (il != null) {
                il.writeToNBT(b);
            }
            Set<String> saved = new HashSet<String>();
            strings(a, saved);
            strings(b, saved);
            Set<String> leak = new HashSet<String>(mine);
            leak.retainAll(saved);
            check(mine.size() >= 4 && leak.isEmpty(), "«airport»: " + mine.size() + " virtual records of the embassy, none in the saves' NBT (AirportDataSVA,"
                    + " IlsDataSVA)" + (leak.isEmpty() ? "" : " - LEAKED " + leak));
            // the client copies: per player (the knower has it, the stranger far away not)
            Set<String> h1 = EmbassyAirportSVA.INSTANCE.hiddenKeys(p1), h3 = EmbassyAirportSVA.INSTANCE.hiddenKeys(p3);
            NBTTagCompound c1 = clientData(d, h1), c3 = clientData(d, h3);
            Set<String> s1 = new HashSet<String>(), s3 = new HashSet<String>();
            strings(c1, s1);
            strings(c3, s3);
            Set<String> in3 = new HashSet<String>(mine);
            in3.retainAll(s3);
            boolean copies = c1 != null && c3 != null && s1.contains(tk) && in3.isEmpty() && h3.contains(tk) && !h1.contains(tk);
            check(copies, "«airport»: the client copy (D_SYNC) per player - the one who knows the embassy has its tower " + s1.contains(tk) + ", the one far away"
                    + " who was never there has none of its records " + in3.isEmpty() + " (hidden " + h3.size() + " / " + h1.size() + "; copies " + (c1 != null)
                    + ")");
            int[] near = navDb(p1), far = navDb(p3);
            check(near[0] > 0 && far[0] == 0 && far[1] >= 0, "«airport»: the MCDU's database (NavDataSVA by viewers) - SRC_EMBASSY with the embassy for the knower ("
                    + near[0] + "), none for the one who does not know it (" + far[0] + ") of " + far[1] + " airports");
        }

        /** Every string value and every compound key under `t` (any depth). */
        private static void strings(NBTBase t, Set<String> out) {
            if (t instanceof NBTTagCompound) {
                NBTTagCompound c = (NBTTagCompound) t;
                for (Object k : c.func_150296_c()) {
                    out.add((String) k);
                    strings(c.getTag((String) k), out);
                }
            } else if (t instanceof NBTTagList) {
                NBTTagList l = (NBTTagList) t;
                int type = l.func_150303_d();
                for (int i = 0; i < l.tagCount(); i++) {
                    if (type == 10) {
                        strings(l.getCompoundTagAt(i), out);
                    } else if (type == 8) {
                        out.add(l.getStringTagAt(i));
                    }
                }
            } else if (t instanceof net.minecraft.nbt.NBTTagString) {
                out.add(((net.minecraft.nbt.NBTTagString) t).func_150285_a_());
            }
        }

        /** AirportDataSVA.clientData(w, dim, hidden) - package A's (by reflection: null when it is not there). */
        private NBTTagCompound clientData(AirportDataSVA d, Set<String> hidden) {
            try {
                Method m = AirportDataSVA.class.getMethod("clientData", World.class, int.class, Set.class);
                return (NBTTagCompound) m.invoke(d, w, Integer.valueOf(0), hidden);
            } catch (Exception e) {
                info("AirportDataSVA.clientData(w, dim, hidden) - " + e);
                return null;
            }
        }

        /** {SRC_EMBASSY airports of this embassy, all airports} in viewer `p`'s database (NavDataSVA.db(w, dim, base, viewers) - package A's). */
        private int[] navDb(EntityPlayer p) {
            try {
                Method m = NavDataSVA.class.getMethod("db", World.class, int.class, int[].class, List.class);
                List<EntityPlayer> viewers = new ArrayList<EntityPlayer>();
                viewers.add(p);
                NavDb db = (NavDb) m.invoke(NavDataSVA.get(w), w, Integer.valueOf(0), null, viewers);
                int n = 0;
                for (NavDb.Airport a : db.airports) {
                    n += a.src == NavDb.SRC_EMBASSY && a.embassy == id ? 1 : 0;
                }
                return new int[] {n, db.airports.size()};
            } catch (Exception e) {
                info("NavDataSVA.db(w, dim, base, viewers) - " + e);
                return new int[] {-1, -1};
            }
        }

        // ------------------------------------------------------------------ 3. ATIS (§6.2.2)

        private EntityVehicleSVA atisA, atisB, atisC;

        private void atis() {
            step(new Step("ATIS", 900) {
                double[] far, near, dest, stranger, p3was;
                String tk, icao;
                int saidAt, heard700 = -1;

                /** `p`'s ATIS lines of the embassy's tower (sva.atc.atisChat with its code). */
                int atisLines(TestPlayer p) {
                    int n = 0;
                    for (String x : p.said) {
                        n += icao.length() > 0 && x.contains(icao) || x.startsWith("sva.atc.atisChat") ? 1 : 0;
                    }
                    return n;
                }

                boolean tick(int k, long now) {
                    AirportDataSVA d = AirportDataSVA.get(w);
                    if (k == 0) {
                        tk = EmbassyAirportSVA.INSTANCE.towerKey(id);
                        tk = tk == null ? EmbassyAirportMath.key(0, f.towerDesk()) : tk;
                        AirportDataSVA.Tower t = d.tower(tk);
                        double[] c = t == null ? new double[] {f.coreX, f.coreZ} : d.centre(t);
                        // outward from the airport's centre, away from the core: 700 (no ATIS), 450 (ATIS); bound for it at 2000
                        double ox = c[0] - f.coreX, oz = c[1] - f.coreZ, ol = Math.max(1, Math.hypot(ox, oz));
                        ox /= ol;
                        oz /= ol;
                        far = new double[] {c[0] + ox * 700, f.floorY + 100, c[1] + oz * 700};
                        near = new double[] {c[0] + ox * 450, f.floorY + 100, c[1] + oz * 450};
                        dest = new double[] {c[0] - ox * 2000, f.floorY + 100, c[1] - oz * 2000};
                        // review 2026-10-27: only a crew that knows the embassy gets a session with its tower - the pilot bound for it
                        // has been there (the core's «seen»); the stranger (p3) 1500 out, across, never there - none
                        com.sc.embassy.EmbassyTicketSC.see(reg.player(p2.getUniqueID()), id);
                        reg.markDirty();
                        stranger = new double[] {c[0] - oz * 1500, f.floorY + 100, c[1] + ox * 1500};
                        p3was = new double[] {p3.posX, p3.posY, p3.posZ};
                        atisA = spawn(VehicleType.LINER, far[0], far[1], far[2], 0F, p1.getUniqueID().toString(), p1.getCommandSenderName(), true);
                        atisB = spawn(VehicleType.LINER, dest[0], dest[1], dest[2], 0F, p2.getUniqueID().toString(), p2.getCommandSenderName(), true);
                        atisC = spawn(VehicleType.LINER, stranger[0], stranger[1], stranger[2], 0F, p3.getUniqueID().toString(), p3.getCommandSenderName(), true);
                        if (atisA == null || atisB == null || atisC == null) {
                            check(false, "«ATIS»: the liners could not be placed");
                            return true;
                        }
                        anchor(near[0], near[2], 2);
                        ride(p1, atisA);
                        ride(p2, atisB);
                        ride(p3, atisC);
                        icao = t == null ? f.icao : t.icao;
                        L(atisB).fms.to = icao;
                        saidAt = atisLines(p1);
                        return false;
                    }
                    AtcServerSVA.Session sa = AtcServerSVA.INSTANCE.session(atisA.getEntityId()), sb = AtcServerSVA.INSTANCE.session(atisB.getEntityId());
                    if (k == 200) {
                        heard700 = sa != null && sa.atisHeard.containsKey(tk) ? 1 : 0;
                        boolean quiet = heard700 == 0 && atisLines(p1) == saidAt;
                        check(quiet, "«ATIS»: a liner 700 from the embassy's airport - no ATIS of its tower in the chat (session " + (sa != null) + ", heard "
                                + heard700 + ", ATIS lines " + (atisLines(p1) - saidAt) + ")");
                        move(atisA, near);
                        saidAt = atisLines(p1);
                        return false;
                    }
                    if (k > 200 && sa != null && sa.atisHeard.containsKey(tk) && sb != null && sb.atisHeard.containsKey(tk) || k == 700) {
                        boolean a = sa != null && sa.atisHeard.containsKey(tk) && atisLines(p1) > saidAt;
                        boolean b = sb != null && sb.atisHeard.containsKey(tk);
                        check(a && b, "«ATIS»: at 450 - the embassy's ATIS in the pilot's chat " + a + (a ? " («" + p1.said.get(p1.said.size() - 1) + "»)" : "")
                                + "; a liner 2000 out bound for it in the MCDU - heard too " + b);
                        AtcServerSVA.Session sc = AtcServerSVA.INSTANCE.session(atisC.getEntityId());
                        boolean none = !EmbassyAirportSVA.INSTANCE.knownBy(p3).contains(Integer.valueOf(id)) && (sc == null || !tk.equals(sc.tower))
                                && (sc == null || !sc.atisHeard.containsKey(tk));
                        check(none, "«ATIS»: a liner 1500 out whose crew never was at the embassy - no session with its tower (session " + (sc == null ? "none"
                                : "«" + sc.tower + "»") + ", knows " + EmbassyAirportSVA.INSTANCE.knownBy(p3) + ")");
                        ride(p1, null);
                        ride(p2, null);
                        ride(p3, null);
                        removeAll(atisA, atisB, atisC);
                        at(p3, p3was[0], p3was[1], p3was[2]);
                        resetPlayers();
                        return true;
                    }
                    return false;
                }
            });
        }

        private void resetPlayers() {
            int[] desk = f.towerDesk();
            for (TestPlayer p : new TestPlayer[] {p1, p2}) {
                if (p.ridingEntity != null) {
                    ride(p, null);
                }
                at(p, desk[0] + 0.5, f.floorY + 1, desk[2] + 0.5);
            }
        }

        private static LinerSystems L(EntityVehicleSVA v) {
            return v.state.liner();
        }

        // ------------------------------------------------------------------ 4. the embassy's air defence (§6.2.3)

        /** How many of `p`'s chat lines are the core's AD line `line` (to any board: its text round the callsign). */
        private int lines(TestPlayer p, int line) {
            IChatComponent c = EmbassyCompatSVA.adLine(w, id, line, CS);
            String t = c == null ? null : c.getUnformattedText();
            int at = t == null ? -1 : t.indexOf(CS);
            if (at < 0) {
                return 0;
            }
            String head = t.substring(0, at), tail = t.substring(at + CS.length());
            int n = 0;
            for (String s : p.said) {
                n += s.length() > head.length() + tail.length() && s.startsWith(head) && s.endsWith(tail) ? 1 : 0;
            }
            return n;
        }

        /** Any of the core's AD lines in `p`'s chat. */
        private int anyLines(TestPlayer p) {
            int n = 0;
            for (int l = EmbassyCompatSVA.AD_WARN; l <= EmbassyCompatSVA.AD_LEFT; l++) {
                n += lines(p, l);
            }
            return n;
        }

        /**
         * `p`'s HUD lines of lang key `key`: the embassy's air defence sends its HUD line alone (NetADSVA.D_MSG - EmbassyAdSVA.debugHud
         * counts them; the chat line is the core's), other HUD lines come with the same text in the chat (AirDefenseSVA.tell).
         */
        private int hud(TestPlayer p, String key) {
            if (key.startsWith("sva.emb.ad.hud.")) {
                return EmbassyAdSVA.INSTANCE.debugHud(p, key);
            }
            String fmt = StatCollector.translateToLocal(key);
            int cut = fmt.indexOf('%');
            String head = cut < 0 ? fmt : fmt.substring(0, cut);
            int n = 0;
            for (String s : p.said) {
                n += head.length() > 0 && s.startsWith(head) ? 1 : 0;
            }
            return n;
        }

        /** U_EMB_AD uplinks of line `line` in `v`'s datalink log (the radio). */
        private static int radio(EntityVehicleSVA v, int line) {
            AtcServerSVA.Session s = v == null ? null : AtcServerSVA.INSTANCE.session(v.getEntityId());
            int n = 0;
            for (Cpdlc.Msg m : s == null ? new ArrayList<Cpdlc.Msg>() : s.log.msgs) {
                n += m.up && m.type == Cpdlc.U_EMB_AD && m.arg == line ? 1 : 0;
            }
            return n;
        }

        private static EmbassyAdSVA.Track track(EntityVehicleSVA v) {
            return EmbassyAdSVA.INSTANCE.debugTrack(v);
        }

        private static int stage(EntityVehicleSVA v) {
            EmbassyAdSVA.Track t = track(v);
            return t == null ? -1 : t.stage;
        }

        private static String st(EntityVehicleSVA v) {
            EmbassyAdSVA.Track t = track(v);
            return t == null ? "no track" : EmbassyAdMath.A_KEYS[Math.max(0, Math.min(4, t.stage))] + "/" + EmbassyAdMath.WHY_KEYS[Math.max(0, Math.min(4, t.why))];
        }

        /** The embassy's shells in the air now: {its posts' guns hit as the shells' home, shells at `v`}. */
        private int[] shells(EntityVehicleSVA v) {
            int home = 0, at = 0;
            String pre = "emb:" + id + ":";
            for (AirDefenseSVA.Projectile p : AirDefenseSVA.INSTANCE.shots()) {
                if (p.launcher == null || !p.launcher.startsWith(pre)) {
                    continue;
                }
                for (int[] g : f.adPosts()) {
                    if (p.hx == g[0] && p.hz == g[2] && Math.abs(p.hy - g[1]) <= 2) {
                        home++;
                        break;
                    }
                }
                at += v != null && p.target == v.getEntityId() ? 1 : 0;
            }
            return new int[] {home, at};
        }

        /** A point 10 blocks past the field's protected zone's box on its side nearest `p` (40 over the field) - a flight in starts there. */
        private double[] outsideField(double[] p) {
            int[] zb = f.zoneBox();
            double[] d = {p[0] - zb[0], zb[2] - p[0], p[2] - zb[1], zb[3] - p[2]};
            int side = 0;
            for (int i = 1; i < 4; i++) {
                if (d[i] < d[side]) {
                    side = i;
                }
            }
            double x = side == 0 ? zb[0] - 10.5 : side == 1 ? zb[2] + 11.5 : p[0], z = side == 2 ? zb[1] - 10.5 : side == 3 ? zb[3] + 11.5 : p[2];
            return new double[] {x, f.floorY + 40, z};
        }

        private double[] above(double[] p, double up) {
            return new double[] {p[0], p[1] + up, p[2]};
        }

        private double[] toward(double[] p, double blocks) {
            return new double[] {p[0] - ux[0] * blocks, p[1], p[2] - ux[1] * blocks};
        }

        /** A scenario's plane: staged out of the zone (its datalink session with the embassy's tower made), then flown in. */
        private abstract class AdStep extends Step {
            EntityVehicleSVA v;
            int in = -1;

            AdStep(String name, int max) {
                super(name, max);
            }

            /** k < 30: the staging; then the scenario from `in` (ticks since the plane entered the zone). */
            boolean tick(int k, long now) {
                if (k == 0) {
                    start(now);
                    return v == null && fail("no plane");
                }
                if (k < 30) {
                    return false;
                }
                if (k == 30) {
                    move(v, q);
                    in = 0;
                    return false;
                }
                in++;
                return scenario(in, now);
            }

            void start(long now) {
                newDay(p1);
                v = plane(qOut, p1);
            }

            boolean fail(String why) {
                check(false, "«" + name + "»: " + why);
                return true;
            }

            abstract boolean scenario(int n, long now);

            boolean end() {
                remove(v);
                resetPlayers();
                return true;
            }
        }

        private void adSteps() {
            // the warning, 10 s, the fire from a post, the damage
            step(new AdStep("AD: warning and fire", 600) {
                int warns0, warnAt = -1, fireAt = -1, homeShells, atShells;
                long end0;
                boolean warned;

                @Override
                void start(long now) {
                    super.start(now);
                    warns0 = lines(p1, EmbassyCompatSVA.AD_WARN);
                }

                boolean scenario(int n, long now) {
                    int[] s = shells(v);
                    homeShells += s[0];
                    atShells += s[1];
                    if (warnAt < 0 && lines(p1, EmbassyCompatSVA.AD_WARN) > warns0) {
                        warnAt = n;
                        EmbassyAdSVA.Track t = track(v);
                        end0 = t == null ? 0 : t.end;
                        boolean secs = t != null && t.warned && Math.abs(t.end - (now + ConfigSVA.adEmbassyWarnSeconds * 20L)) <= 10;
                        boolean hudOk = hud(p1, "sva.emb.ad.hud.warn") > 0, radioOk = radio(v, EmbassyCompatSVA.AD_WARN) > 0;
                        warned = secs && hudOk && radioOk;
                        check(warned && homeShells == 0, "«AD: warning»: a plane with a «Чужак» at the controls in the zone (" + (int) f.fromCore(q[0], q[2])
                                + " from the core, " + (int) (q[1] - f.floorY) + " up) - the core's AD_WARN in his chat «" + p1.said.get(p1.said.size() - 1) + "», the HUD"
                                + " line, the radio's U_EMB_AD, the track " + st(v) + " with " + ConfigSVA.adEmbassyWarnSeconds + " s (end " + end0 + ", now " + now
                                + "); no shell yet " + warned + SelfTestLinerFmsSVA.flags(secs, hudOk, radioOk, homeShells == 0));
                    }
                    if (warnAt < 0 && n > 40) {
                        return fail("no warning within 2 s (track " + st(v) + ", chat " + p1.said + ")");
                    }
                    if (warnAt >= 0 && n == warnAt + ConfigSVA.adEmbassyWarnSeconds * 20 - 20) {
                        check(stage(v) == EmbassyAdMath.A_TURN && homeShells == 0 && lines(p1, EmbassyCompatSVA.AD_FIRE) == 0,
                                "«AD: warning»: a second before the 10 s are over - still turning away (" + st(v) + "), no fire");
                    }
                    if (warnAt >= 0 && fireAt < 0 && stage(v) == EmbassyAdMath.A_FIRE) {
                        fireAt = n;
                    }
                    if (fireAt >= 0 && n == fireAt + 80) {
                        boolean why = track(v) != null && track(v).why == EmbassyAdMath.WHY_TIME, once = lines(p1, EmbassyCompatSVA.AD_FIRE) == 1;
                        boolean hudOk = hud(p1, "sva.emb.ad.hud.fire") > 0, radioOk = radio(v, EmbassyCompatSVA.AD_FIRE) > 0;
                        boolean fired = why && once && hudOk && radioOk;
                        boolean hit = v.isDead || v.isWrecked() || v.getHp() < v.getMaxHp();
                        check(fired && homeShells > 0 && atShells > 0 && hit && fireAt >= warnAt + ConfigSVA.adEmbassyWarnSeconds * 20 - 10,
                                "«AD: fire»: " + (fireAt - warnAt) + " ticks after the warning - AD_FIRE once (chat, HUD, radio; why time) " + fired
                                        + SelfTestLinerFmsSVA.flags(why, once, hudOk, radioOk) + "; the shells"
                                        + " leave a post's gun (" + homeShells + " shell-ticks, " + atShells + " at the plane) and hit it (hp " + v.getHp() + " / "
                                        + v.getMaxHp() + ") " + hit);
                        return end();
                    }
                    if (warnAt >= 0 && fireAt < 0 && n > warnAt + ConfigSVA.adEmbassyWarnSeconds * 20 + 60) {
                        end();
                        return fail("no fire 13 s after the warning (track " + st(v) + ")");
                    }
                    return false;
                }
            });
            // out of the zone and back the same day: AD_LEFT, then the fire at once without a warning
            step(new AdStep("AD: left and back", 300) {
                int warns0, fireAt = -1, leftAt = -1, left0;

                @Override
                void start(long now) {
                    v = plane(qOut, p1);                                 // the day's warning was given in the step before (no new day)
                    warns0 = lines(p1, EmbassyCompatSVA.AD_WARN);
                    left0 = lines(p1, EmbassyCompatSVA.AD_LEFT);
                }

                boolean scenario(int n, long now) {
                    if (fireAt < 0 && stage(v) == EmbassyAdMath.A_FIRE) {
                        fireAt = n;
                        check(n <= 20 && track(v).why == EmbassyAdMath.WHY_REENTRY && lines(p1, EmbassyCompatSVA.AD_WARN) == warns0,
                                "«AD: re-entry»: a new machine of the same «Чужак» back in the zone the same game day - the fire at once (" + n + " ticks, " + st(v)
                                        + "), no second warning");
                        move(v, qOut);
                        return false;
                    }
                    if (fireAt < 0 && n > 30) {
                        end();
                        return fail("no fire at once on the re-entry (" + st(v) + ")");
                    }
                    if (fireAt >= 0 && leftAt < 0 && lines(p1, EmbassyCompatSVA.AD_LEFT) > left0) {
                        leftAt = n;
                    }
                    if (fireAt >= 0 && n == fireAt + 40) {
                        boolean left = leftAt >= 0 && track(v) == null && hud(p1, "sva.emb.ad.hud.left") > 0;
                        check(left, "«AD: left»: out of the zone (300 from the core) - AD_LEFT in the chat and on the HUD, the machine forgotten (" + st(v) + ")");
                        move(v, q);
                        return false;
                    }
                    if (fireAt >= 0 && n == fireAt + 70) {
                        check(stage(v) == EmbassyAdMath.A_FIRE && lines(p1, EmbassyCompatSVA.AD_WARN) == warns0, "«AD: re-entry»: back in again - the"
                                + " fire at once again, no warning (" + st(v) + ")");
                        return end();
                    }
                    return false;
                }
            });
            // closing in / descending during the warning: the fire at once
            for (final int kind : new int[] {EmbassyAdMath.WHY_CLOSE, EmbassyAdMath.WHY_DESCEND}) {
                final String what = kind == EmbassyAdMath.WHY_CLOSE ? "closing 40 in" : "descending to 25 over the field";
                step(new AdStep("AD: " + what, 200) {
                    int warnAt = -1;

                    boolean scenario(int n, long now) {
                        if (warnAt < 0 && stage(v) == EmbassyAdMath.A_WARN || warnAt < 0 && stage(v) == EmbassyAdMath.A_TURN) {
                            warnAt = n;
                            move(v, kind == EmbassyAdMath.WHY_CLOSE ? toward(q, 40) : new double[] {q[0], f.floorY + 25, q[2]});
                            return false;
                        }
                        if (warnAt < 0 && n > 40) {
                            end();
                            return fail("no warning (" + st(v) + ")");
                        }
                        if (warnAt >= 0 && n == warnAt + 30) {
                            EmbassyAdSVA.Track t = track(v);
                            check(t != null && t.stage == EmbassyAdMath.A_FIRE && t.why == kind, "«AD: " + what + "»: within 3 s of the warning - the fire at once ("
                                    + st(v) + ", the 10 s not over)");
                            return end();
                        }
                        return false;
                    }
                });
            }
            // an admitted pilot; a «Чужак» with an admitted passenger; a «Чужак» with an admitted squad mate near: no warning, no fire
            step(new AdStep("AD: admitted pilot", 120) {
                int lines0;

                @Override
                void start(long now) {
                    v = plane(qOut, p2);
                    lines0 = anyLines(p2);
                }

                boolean scenario(int n, long now) {
                    if (n < 50) {
                        return false;
                    }
                    int s = stage(v);
                    check(anyLines(p2) == lines0 && (s == -1 || s == EmbassyAdMath.A_HOLD || s == EmbassyAdMath.A_NONE),
                            "«AD: admitted pilot»: a «Знакомый» flying in the zone - no line, no warning, no fire (" + st(v) + ")");
                    return end();
                }
            });
            step(new AdStep("AD: admitted passenger", 120) {
                int lines1, lines2;

                @Override
                void start(long now) {
                    super.start(now);
                    lines1 = anyLines(p1);
                    lines2 = anyLines(p2);
                }

                boolean tick(int k, long now) {
                    if (k == 5 && v != null) {
                        EntitySeatSVA s = v.freeSeat();
                        if (s != null) {
                            ride(p2, s);
                        }
                    }
                    return super.tick(k, now);
                }

                boolean scenario(int n, long now) {
                    if (n < 50) {
                        return false;
                    }
                    int s = stage(v);
                    boolean seated = v.riders().contains(p2);
                    check(seated && anyLines(p1) == lines1 && anyLines(p2) == lines2 && (s == -1 || s == EmbassyAdMath.A_HOLD),
                            "«AD: admitted passenger»: a «Чужак» at the controls, a «Знакомый» in the passenger seat (" + seated + ") - no warning to either, no"
                                    + " fire (" + st(v) + ")");
                    return end();
                }
            });
            step(new AdStep("AD: squad escort", 140) {
                EntityVehicleSVA mate;
                int lines1;

                @Override
                void start(long now) {
                    super.start(now);
                    lines1 = anyLines(p1);
                    squad = SubstrateConfigSC.squads && SquadSC.join(reg, p2.getUniqueID(), p2.getCommandSenderName(), p1.getUniqueID(),
                            p1.getCommandSenderName(), now);
                    mate = plane(new double[] {qOut[0] + 12, qOut[1], qOut[2]}, p2);
                }

                boolean tick(int k, long now) {
                    if (k == 30 && mate != null) {
                        move(mate, new double[] {q[0] + 12 * ux[1], q[1] + 4, q[2] - 12 * ux[0]});
                    }
                    return super.tick(k, now);
                }

                boolean scenario(int n, long now) {
                    if (n < 50) {
                        return false;
                    }
                    if (!squad) {
                        info("«AD: squad escort» skipped - squads are off on this server (squads " + SubstrateConfigSC.squads + ")");
                    } else {
                        int s = stage(v);
                        boolean covered = EmbassyCompatSVA.escortOf(p1, id) == p2;
                        check(covered && anyLines(p1) == lines1 && (s == -1 || s == EmbassyAdMath.A_HOLD), "«AD: squad escort»: a «Чужак» alone in his plane,"
                                + " his admitted squad mate in his own 12 blocks away (the core's escort " + covered + ") - no warning, no fire (" + st(v) + ")");
                        SquadSC.leave(reg, p1.getUniqueID(), p1.getCommandSenderName());
                        squad = false;
                    }
                    remove(mate);
                    return end();
                }
            });
            // 7700: the pass, its end, the second one the same day refused
            step(new AdStep("AD: 7700", 700) {
                int phase, mark, pass0, over0, used0, fire0;

                @Override
                void start(long now) {
                    super.start(now);
                    if (v != null) {
                        v.state.xpdr.mode = XpdrMath.C;
                        v.state.xpdr.setCode(XpdrMath.EMERGENCY, now);
                    }
                    fire0 = lines(p1, EmbassyCompatSVA.AD_FIRE);       // his chat keeps the fire lines of the steps before
                    pass0 = lines(p1, EmbassyCompatSVA.AD_PASS);
                    over0 = lines(p1, EmbassyCompatSVA.AD_PASS_OVER);
                    used0 = lines(p1, EmbassyCompatSVA.AD_PASS_USED);
                }

                boolean scenario(int n, long now) {
                    if (phase == 0 && n == 60) {
                        boolean once = lines(p1, EmbassyCompatSVA.AD_PASS) == pass0 + 1, running = EmbassyCompatSVA.passUntil(p1, id) > now;
                        boolean held = stage(v) == EmbassyAdMath.A_HOLD && shells(v)[1] == 0 && lines(p1, EmbassyCompatSVA.AD_FIRE) == fire0;
                        boolean hudOk = hud(p1, "sva.emb.ad.hud.pass") > 0;
                        boolean pass = once && running && held && hudOk && AirDefenseSVA.xpdrOn(v);
                        check(pass, "«AD: 7700»: squawking 7700 in the zone - the pass once (chat, HUD), 60 s without fire (" + st(v) + ", until "
                                + EmbassyCompatSVA.passUntil(p1, id) + ", now " + now + ")" + SelfTestLinerFmsSVA.flags(once, running, held, hudOk));
                        NBTTagCompound x = rec.local(p1.getUniqueID()).extra.getCompoundTag(EmbassyAirRulesSC.LEDGER);
                        x.setLong(EmbassyAirRulesSC.PASS_UNTIL, now - 1L);                 // the 60 s over (the same game day)
                        rec.local(p1.getUniqueID()).extra.setTag(EmbassyAirRulesSC.LEDGER, x);
                        phase = 1;
                        mark = n;
                        return false;
                    }
                    if (phase == 1 && n == mark + 30) {
                        check(lines(p1, EmbassyCompatSVA.AD_PASS_OVER) == over0 + 1 && stage(v) != EmbassyAdMath.A_HOLD && hud(p1, "sva.emb.ad.hud.passOver") > 0,
                                "«AD: 7700»: the pass over - AD_PASS_OVER, the machine a target again (" + st(v) + ")");
                        v.state.xpdr.setCode(XpdrMath.VFR, now);
                        phase = 2;
                        mark = n;
                        return false;
                    }
                    if (phase == 2 && n == mark + 20) {
                        v.state.xpdr.setCode(XpdrMath.EMERGENCY, now);       // a new 7700 episode the same day
                        phase = 3;
                        mark = n;
                        return false;
                    }
                    if (phase == 3 && n == mark + 30) {
                        check(lines(p1, EmbassyCompatSVA.AD_PASS_USED) == used0 + 1 && lines(p1, EmbassyCompatSVA.AD_PASS) == pass0 + 1
                                && stage(v) != EmbassyAdMath.A_HOLD && hud(p1, "sva.emb.ad.hud.passUsed") > 0,
                                "«AD: 7700»: a second 7700 the same game day - AD_PASS_USED, no pass, the fire not held (" + st(v) + ")");
                        phase = 4;
                        mark = n;
                        return false;
                    }
                    if (phase == 4 && (stage(v) == EmbassyAdMath.A_FIRE || n > mark + ConfigSVA.adEmbassyWarnSeconds * 20 + 60)) {
                        check(stage(v) == EmbassyAdMath.A_FIRE, "«AD: 7700»: after the used pass the fire comes (" + st(v) + ")");
                        return end();
                    }
                    return false;
                }
            });
            // on foot / flying without a machine / a machine on the ground: nothing
            step(new Step("AD: foot, armour, ground", 200) {
                EntityVehicleSVA g;
                int lines0;

                boolean tick(int k, long now) {
                    if (k == 0) {
                        newDay(p1);
                        resetPlayers();
                        at(p1, q[0], q[1], q[2]);                            // in the zone in the air, no machine (armour / a jetpack)
                        lines0 = anyLines(p1);
                        double[] c = groundSpot();                           // the liner's stand МС-1 (a plane takes no stand there)
                        g = spawn(VehicleType.PLANE, c[0], c[1], c[2], 0F, p1.getUniqueID().toString(), p1.getCommandSenderName(), false);
                        return false;
                    }
                    if (k == 60) {
                        check(anyLines(p1) == lines0, "«AD: on foot / in armour»: a «Чужак» in the zone in the air without a machine - nothing ("
                                + (anyLines(p1) - lines0) + " lines)");
                        resetPlayers();
                        if (g != null) {
                            ride(p1, g);
                            at(p1, g.posX, g.posY, g.posZ);
                        }
                        lines0 = anyLines(p1);
                        return false;
                    }
                    if (k == 140) {
                        int s = stage(g);
                        check(g != null && !g.airborneForAtc() && anyLines(p1) == lines0 && (s == -1 || s == EmbassyAdMath.A_NONE),
                                "«AD: on the ground»: a «Чужак» in his plane on the apron, in the zone - nothing (" + st(g) + ", airborne "
                                        + (g != null && g.airborneForAtc()) + ")");
                        remove(g);
                        resetPlayers();
                        return true;
                    }
                    return false;
                }
            });
            // an unmanned machine: the owner (online, not admitted) warned, then the fire
            step(new AdStep("AD: unmanned", 500) {
                int warns0, warnAt = -1;

                @Override
                void start(long now) {
                    newDay(p1);
                    v = spawn(VehicleType.PLANE, qOut[0], qOut[1], qOut[2], 0F, p1.getUniqueID().toString(), p1.getCommandSenderName(), true);
                    warns0 = lines(p1, EmbassyCompatSVA.AD_WARN);
                }

                boolean scenario(int n, long now) {
                    if (warnAt < 0 && (lines(p1, EmbassyCompatSVA.AD_WARN) > warns0 || stage(v) == EmbassyAdMath.A_TURN)) {
                        warnAt = n;
                        check(lines(p1, EmbassyCompatSVA.AD_WARN) > warns0 && v.riders().isEmpty(), "«AD: unmanned»: a plane with nobody aboard in the zone - its"
                                + " owner (online, a «Чужак») warned (" + st(v) + ")");
                    }
                    if (warnAt < 0 && n > 60) {
                        end();
                        return fail("no warning to the owner (" + st(v) + ")");
                    }
                    if (warnAt >= 0 && (stage(v) == EmbassyAdMath.A_FIRE || n > warnAt + ConfigSVA.adEmbassyWarnSeconds * 20 + 60)) {
                        check(stage(v) == EmbassyAdMath.A_FIRE, "«AD: unmanned»: then the fire (" + st(v) + ", " + (n - warnAt) + " ticks after the warning)");
                        return end();
                    }
                    return false;
                }
            });
            step(new Step("AD: over a player's base", 1) {
                boolean tick(int k, long now) {
                    info("«AD: over a player's base» not flown here - a switched-on Field Generator can't be set up from the add-on's test (the core's zone"
                            + " registry); the core's adZoneAt answers -1 over a base (its world test), and a machine out of the zone is A_NONE (the self-test's"
                            + " matrix)");
                    return true;
                }
            });
            // airdefense.embassyAd off: nothing
            step(new AdStep("AD: embassyAd off", 160) {
                int lines0;

                @Override
                void start(long now) {
                    adOff();
                    super.start(now);
                    lines0 = anyLines(p1);
                }

                boolean scenario(int n, long now) {
                    if (n < 60) {
                        return false;
                    }
                    check(anyLines(p1) == lines0 && track(v) == null && shells(v)[1] == 0, "«AD: off»: airdefense.embassyAd false - a «Чужак» in the zone gets"
                            + " no warning and no fire (" + st(v) + ")");
                    adBack();
                    return end();
                }
            });
        }

        // ------------------------------------------------------------------ 5. the neutral sky (§6.2.4)

        private void neutral() {
            step(new Step("neutral sky", 900) {
                TileEntityCommandPostSVA kp;
                TileEntityLauncherSVA gunN, gunK;
                TileEntityRadarSVA radar;
                EntityVehicleSVA vn, vk;
                int farX, farZ;
                double p3x, p3z;
                boolean wantedN, readyN, readyK;

                boolean tick(int k, long now) {
                    if (k == 0) {
                        adOff();                                            // the embassy's own guns stay out of it (their own steps)
                        if (!site()) {
                            info("«neutral sky»: no place for the test's post beside an approach corridor out of the protected zone - skipped");
                            adBack();
                            return true;
                        }
                        return false;
                    }
                    if (kp == null) {
                        removeAll(vn, vk);
                        adBack();
                        return true;
                    }
                    for (TileEntityAirDefenseSVA te : adBlocks) {
                        te.fillForTest();
                    }
                    if (k == 1) {
                        zones();
                    }
                    if (vn != null && vk != null) {
                        wantedN |= gunN.debugWanted(vn.getEntityId());
                        readyN |= gunN.debugReady(vn.getEntityId());
                        readyK |= gunK.debugReady(vk.getEntityId());
                    }
                    if (k > 40 && (readyK && k > 400 || k > 850)) {
                        boolean neutralN = EmbassyCompatSVA.neutralAt(w, vn.posX, vn.posY, vn.posZ), neutralK = EmbassyCompatSVA.neutralAt(w, vk.posX, vk.posY, vk.posZ);
                        check(neutralN && !neutralK && wantedN && !readyN && readyK, "«neutral sky»: a player's post «по всем» with two guns - the one by the"
                                + " approach corridor warns the plane over it (wanted " + wantedN + ") but never gets to fire (" + readyN + "), the other fires at the"
                                + " plane off the corridor (" + readyK + "); neutral there " + neutralN + " / " + neutralK + (readyK ? "" : " (the other: in its picture "
                                + gunK.lastPicture().containsKey(Integer.valueOf(vk.getEntityId())) + ", wanted " + gunK.debugWanted(vk.getEntityId()) + ", block "
                                + (gunK.debugBlock(vk.getEntityId()) < 0 ? "-" : com.sva.airdefense.RoeMath.BLOCK_KEYS[gunK.debugBlock(vk.getEntityId())]) + ", "
                                + (int) Math.hypot(vk.posX - gunK.xCoord, vk.posZ - gunK.zCoord) + " off, manned " + AirDefenseSVA.manned(vk) + ")")
                                + (wantedN ? "" : " (the first: in its picture " + gunN.lastPicture().containsKey(Integer.valueOf(vn.getEntityId())) + ", the post's tracks "
                                        + kp.tracks().size() + ", linked " + (AirDefenseSVA.INSTANCE.linkedPost(gunN) == kp) + " / " + (AirDefenseSVA.INSTANCE.linkedPost(gunK) == kp)
                                        + " / " + (AirDefenseSVA.INSTANCE.linkedPost(radar) == kp) + ", the radar's contacts " + radar.contacts().size() + ")"));
                        removeAll(vn, vk);
                        resetPlayers();
                        at(p3, p3x, 200, p3z);
                        adBack();
                        return true;
                    }
                    return false;
                }

                /**
                 * The corridor's point C (a FULL end's approach, 250 / 200 / 150 out), the guns 40 and 90 aside with the post and its radar
                 * between them at 65 (both guns linked: BallisticsMath.LINK 64 - a gun off its post looks with its own eyes, EYES 48, at
                 * «нарушителей» only), the other plane 96 aside by the second gun.
                 */
                boolean site() {
                    for (int e = 0; e < 2; e++) {
                        boolean low = e == 0;
                        int[] th = f.threshold(low);
                        double h = low ? f.headingLow : f.headingHigh;
                        double[] d = dir(h), l = left(h);
                        for (int along : new int[] {250, 200, 150, 280}) {
                            for (int side : new int[] {1, -1}) {
                                double cx = th[0] + 0.5 - d[0] * along, cz = th[2] + 0.5 - d[1] * along;
                                double gx = cx + side * l[0] * 40, gz = cz + side * l[1] * 40, bx = cx + side * l[0] * 90, bz = cz + side * l[1] * 90;
                                double px = cx + side * l[0] * 65, pz = cz + side * l[1] * 65;
                                double kx = cx + side * l[0] * 96, kz = cz + side * l[1] * 96;
                                farX = (int) Math.floor(kx);
                                farZ = (int) Math.floor(kz);
                                if (!EmbassyCompatSVA.neutralAt(w, cx, f.floorY + 60, cz) || EmbassyCompatSVA.neutralAt(w, kx, f.floorY + 60, kz)
                                        || inZone(gx, gz, 16) || inZone(bx, bz, 8) || inZone(px, pz, 12) || EmbassyCompatSVA.neutralAt(w, gx, f.floorY + 60, gz)) {
                                    continue;
                                }
                                int y = f.floorY + 30;
                                anchor(cx, cz, 2);
                                anchor(gx, gz, 2);
                                anchor(bx, bz, 2);
                                anchor(px, pz, 2);
                                anchor(kx, kz, 2);
                                int gxi = (int) Math.floor(gx), gzi = (int) Math.floor(gz), bxi = (int) Math.floor(bx), bzi = (int) Math.floor(bz);
                                int pxi = (int) Math.floor(px), pzi = (int) Math.floor(pz);
                                platform(gxi - 4, gzi - 4, gxi + 4, gzi + 4, y);
                                platform(pxi - 4, pzi - 4, pxi + 10, pzi + 10, y);
                                platform(bxi - 4, bzi - 4, bxi + 4, bzi + 4, y);
                                gunN = build(gxi, y, gzi, ModContentSVA.launcher, TileEntityLauncherSVA.GUN, TileEntityLauncherSVA.class);
                                kp = build(pxi, y, pzi, ModContentSVA.commandPost, 2, TileEntityCommandPostSVA.class);
                                radar = build(pxi + 6, y, pzi + 6, ModContentSVA.radar, RadarMath.SURVEY, TileEntityRadarSVA.class);
                                gunK = build(bxi, y, bzi, ModContentSVA.launcher, TileEntityLauncherSVA.GUN, TileEntityLauncherSVA.class);
                                if (kp == null || radar == null || gunN == null || gunK == null) {
                                    check(false, "«neutral sky»: the post, the radar and two guns could not be built at " + gxi + " " + gzi);
                                    kp = null;
                                    return true;
                                }
                                radar.setKpLink(kp.key());
                                for (TileEntityLauncherSVA g : new TileEntityLauncherSVA[] {gunN, gunK}) {
                                    g.setKpLink(kp.key());
                                    g.debugSettings(RoeMath.FOREIGN_ALL, true, false, 2);
                                    g.debugFireMode = true;                    // no ammunition: nothing is ever shot
                                }
                                vn = spawn(VehicleType.PLANE, cx, f.floorY + 60, cz, 0F, SPARE_ID, SPARE_NAME, true);
                                vk = spawn(VehicleType.PLANE, kx, f.floorY + 60, kz, 0F, SPARE_ID, SPARE_NAME, true);
                                for (EntityVehicleSVA v : new EntityVehicleSVA[] {vn, vk}) {
                                    if (v != null) {
                                        v.state.xpdr.mode = XpdrMath.OFF;
                                    }
                                }
                                if (vn == null || vk == null) {
                                    return false;
                                }
                                p3x = p3.posX;
                                p3z = p3.posZ;
                                ride(p1, vn);                                 // the players' AD never fires at an empty machine (fireOnUnmanned)
                                ride(p3, vk);
                                return true;
                            }
                        }
                    }
                    return false;
                }

                /** The post's forbidden zone over the field refused (sva.emb.zoneNeutral), one far off taken (and taken back). */
                void zones() {
                    int[] rb = f.runwayBox();
                    int n0 = kp.zones().size(), said0 = p2.said.size();
                    kp.command(p2, zoneCmd((rb[0] + rb[2]) / 2, (rb[1] + rb[3]) / 2, 20));
                    String no = StatCollector.translateToLocal("sva.emb.zoneNeutral");
                    boolean refused = kp.zones().size() == n0 && p2.said.size() > said0 && p2.said.get(p2.said.size() - 1).equals(no);
                    kp.command(p2, zoneCmd(farX, farZ, 15));
                    boolean taken = kp.zones().size() == n0 + 1;
                    if (taken) {
                        NBTTagCompound del = new NBTTagCompound();
                        del.setString("Op", "zoneDel");
                        del.setInteger("Idx", n0);
                        kp.command(p2, del);
                    }
                    check(refused && taken && kp.zones().size() == n0, "«neutral sky»: the post's forbidden zone over the embassy's runway refused («" + no
                            + "») " + refused + ", one far off the field taken " + taken);
                }
            });
        }

        private static NBTTagCompound zoneCmd(int x, int z, int r) {
            ZoneMath.Zone zn = new ZoneMath.Zone();
            zn.name = "EMB";
            zn.type = ZoneMath.FORBIDDEN;
            zn.shape = ZoneMath.CIRCLE;
            zn.cx = x;
            zn.cz = z;
            zn.r = r;
            NBTTagCompound zt = new NBTTagCompound();
            zn.write(zt);
            NBTTagCompound d = new NBTTagCompound();
            d.setString("Op", "zone");
            d.setTag("Z", zt);
            d.setInteger("Idx", -1);
            return d;
        }

        private boolean inZone(double x, double z, int r) {
            for (int i = -r; i <= r; i += 4) {
                for (int j = -r; j <= r; j += 4) {
                    if (EmbassyCompatSVA.inZone(f, (int) Math.floor(x) + i, (int) Math.floor(z) + j)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** A stone floor at `y` over the box and air 12 above it (the old blocks kept to set back). */
        private void platform(int x0, int z0, int x1, int z1, int y) {
            platforms.add(new int[] {x0, z0, x1, z1, y});
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    for (int yy = y; yy <= y + 12; yy++) {
                        set(x, yy, z, yy == y ? Blocks.stone : Blocks.air);
                    }
                }
            }
        }

        private void set(int x, int y, int z, Block b) {
            Block old = w.getBlock(x, y, z);
            if (old == b) {
                return;
            }
            long k = ((long) x & 0x3FFFFFF) << 38 | ((long) y & 0xFFF) << 26 | (long) z & 0x3FFFFFF;
            if (replacedAt.add(Long.valueOf(k))) {
                replaced.add(new int[] {x, y, z, Block.getIdFromBlock(old), w.getBlockMetadata(x, y, z)});
            }
            w.setBlock(x, y, z, b, 0, 2);
        }

        @SuppressWarnings("unchecked")
        private <T extends TileEntity> T build(int x, int y, int z, BlockAirDefenseSVA bl, int meta, Class<T> c) {
            BlockAirDefenseSVA.build(w, x, y + 1, z, bl, meta, 2);
            TileEntity te = w.getTileEntity(x, y + 1, z);
            if (te instanceof TileEntityAirDefenseSVA) {
                ((TileEntityAirDefenseSVA) te).setOwnerName(KP_OWNER);
                adBlocks.add((TileEntityAirDefenseSVA) te);
            }
            return c.isInstance(te) ? (T) te : null;
        }

        private static double[] dir(double hdg) {
            double r = Math.toRadians(hdg);
            return new double[] {Math.sin(r), -Math.cos(r)};
        }

        private static double[] left(double hdg) {
            double r = Math.toRadians(hdg);
            return new double[] {-Math.cos(r), -Math.sin(r)};
        }

        // ------------------------------------------------------------------ 6. the ILS landing on FULL; SHORT refuses the liner (§6.2.5)

        private void ils() {
            step(new Step("ILS landing", 2400) {
                EntityVehicleSVA l;
                boolean low, asked, cleared, dual;
                int land = -1, flare = -1, roll = -1;
                int[] th;
                double h;

                boolean tick(int k, long now) {
                    if (k == 0) {
                        if (!f.ilsLow && !f.ilsHigh) {
                            info("«ILS landing»: neither end of this FULL field has an ILS (its funnels) - skipped");
                            return true;
                        }
                        adOff();                                            // the landing alone (the AD's rules have their own steps)
                        low = f.ilsLow;
                        useEnd(EmbassyAirportMath.key(0, f.thresholdKey(low)));   // the automatic tower clears the runway in use only
                        th = f.threshold(low);
                        h = low ? f.headingLow : f.headingHigh;
                        double[] d = dir(h), lf = left(h);
                        double sx = th[0] + 0.5 - d[0] * 1000 + lf[0] * 30, sz = th[2] + 0.5 - d[1] * 1000 + lf[1] * 30;
                        l = flyer(sx, f.floorY + 1 + 45, sz, h + 20);
                        if (l == null) {
                            return fail("no liner");
                        }
                        followed.add(l);
                        return false;
                    }
                    engage(l, k);
                    if (k < 7) {
                        return false;
                    }
                    AtcServerSVA.Session s = AtcServerSVA.INSTANCE.session(l.getEntityId());
                    if (!asked && s != null && k > 12) {
                        asked = AtcServerSVA.INSTANCE.downlink(w, AirportDataSVA.get(w), l, Cpdlc.D_LANDING, 0, "", now);
                    }
                    for (Cpdlc.Msg m : s == null ? new ArrayList<Cpdlc.Msg>() : s.log.msgs) {
                        cleared |= m.up && m.type == Cpdlc.U_LAND;
                    }
                    LinerAfs F = L(l).afs;
                    if ((F.thr == LinerAfs.T_RETARD || F.call == LinerAfs.CALL_RETARD) && l.throttle > 0) {
                        l.throttle = 0;
                    }
                    dual |= F.capability() == LinerAfs.CAP_CAT3_DUAL;
                    land = land < 0 && F.lat == LinerAfs.L_LAND ? k : land;
                    flare = flare < 0 && F.ver == LinerAfs.V_FLARE ? k : flare;
                    roll = roll < 0 && F.lat == LinerAfs.L_ROLLOUT ? k : roll;
                    boolean stopped = roll > 0 && l.onGround && l.speed < 0.01;
                    if (stopped || k > 2300 || l.isDead) {
                        double[] d = dir(h), lf = left(h);
                        double along = (l.posX - th[0] - 0.5) * d[0] + (l.posZ - th[2] - 0.5) * d[1];
                        double across = (l.posX - th[0] - 0.5) * lf[0] + (l.posZ - th[2] - 0.5) * lf[1];
                        boolean onRunway = RunwaySurfaceSVA.is(w, (int) Math.floor(l.posX), f.floorY, (int) Math.floor(l.posZ))
                                && RunwaySurfaceSVA.embassy(w, (int) Math.floor(l.posX), f.floorY, (int) Math.floor(l.posZ));
                        check(stopped && dual && land > 0 && flare > land && roll > flare && along > 0 && along <= f.landingDistance(low)
                                && Math.abs(across) <= f.width / 2.0 && onRunway && l.getHp() >= l.getMaxHp() - 1e-3, "«ILS landing»: an SA-320 1000 out on "
                                + (low ? f.numberLow : f.numberHigh) + " (APPR, AP 1 + 2, autobrake MED) - CAT 3 DUAL " + dual + ", LAND " + land + ", FLARE "
                                + flare + ", ROLL OUT " + roll + ", stopped " + (int) along + " past the threshold (landing distance " + f.landingDistance(low) + "), "
                                + String.format("%.1f", across) + " off the axis, on the embassy's runway " + onRunway + ", hp " + l.getHp() + " / " + l.getMaxHp()
                                + (l.isDead ? " (gone at " + (int) l.posX + " " + (int) l.posY + " " + (int) l.posZ + ")" : ""));
                        check(asked && cleared, "«ILS landing»: the liner's request to land (CPDLC) - the embassy's automatic tower cleared it (asked " + asked
                                + ", cleared " + cleared + ")");
                        remove(l);
                        adBack();
                        rwyBack();
                        return true;
                    }
                    return false;
                }

                boolean fail(String why) {
                    check(false, "«ILS landing»: " + why);
                    adBack();
                    rwyBack();
                    return true;
                }
            });
        }

        /** A liner in the air (the airport test's flyer): heading `hdg`, gear down, flaps 3, its transponder on. */
        private EntityVehicleSVA flyer(double x, double y, double z, double hdg) {
            EntityVehicleSVA v = spawn(VehicleType.LINER, x, y, z, (float) (hdg - 180), SPARE_ID, SPARE_NAME, false);
            if (v == null) {
                return null;
            }
            v.rotationYaw = v.prevRotationYaw = (float) (hdg - 180);
            LinerSystems L = L(v);
            L.set(LinerControls.BAT1, 1);
            L.set(LinerControls.BAT2, 1);
            L.set(LinerControls.ADIRS1, 1);
            L.set(LinerControls.ADIRS2, 1);
            L.set(LinerControls.ADIRS3, 1);
            L.set(LinerControls.PARK_BRK, 0);
            L.set(LinerControls.FLAPS, 3);
            L.fctl.flapPos = 3;
            L.fctl.slatPos = 1;
            L.set(LinerControls.GEAR, 1);
            L.gear.pos = 0;
            L.set(LinerControls.SPD_BRK, 0);
            L.set(LinerControls.LS_L, 1);
            v.state.xpdr.mode = XpdrMath.C;
            v.debugLinerAir = false;
            v.speed = 1.3;
            v.motionY = 0;
            v.throttle = LinerAfs.DET_CL;
            L.afs.alt = (int) Math.round(y);
            v.debugDrive(PILOT);
            return v;
        }

        /** The engines (tick 3), A/THR, the autobrake MED, AP 1, APPR, AP 2 (tick 6). */
        private static void engage(EntityVehicleSVA v, int k) {
            if (k == 3) {
                v.debugEngineManual = true;
            }
            if (k == 6) {
                click(v, LinerControls.FCU_ATHR);
                click(v, LinerControls.ABRK_MED);
                click(v, LinerControls.FCU_AP1);
                click(v, LinerControls.FCU_APPR);
                click(v, LinerControls.FCU_AP2);
            }
        }

        private static void click(EntityVehicleSVA v, LinerControls.Ctl c) {
            L(v).control(c.index, LinerControls.A_PRIMARY);
        }

        private void shortField() {
            step(new Step("SHORT liner", 300) {
                EmbassyFieldSVA g;
                EntityVehicleSVA l;
                boolean asked;
                double[] spot;

                boolean tick(int k, long now) {
                    if (k == 0) {
                        for (EmbassyFieldSVA x : EmbassyCompatSVA.fields(w)) {
                            if (x.kind == EmbassyCompatSVA.SHORT && x.hasRunway()) {
                                g = x;
                                break;
                            }
                        }
                        if (g == null) {
                            info("«SHORT liner»: this world has no SHORT embassy airfield - the refusal is the self-test's (EmbassyAtcMath.landingRefusal)");
                            return true;
                        }
                        adOff();
                        int[] th = g.threshold(true);
                        double[] d = dir(g.headingLow);
                        spot = new double[] {th[0] + 0.5 - d[0] * 300, g.floorY + 25, th[2] + 0.5 - d[1] * 300};
                        at(p2, g.coreX + 0.5, g.floorY + 1, g.coreZ + 0.5);   // a player near its tower (online)
                        l = spawn(VehicleType.LINER, spot[0], spot[1], spot[2], (float) (g.headingLow - 180), SPARE_ID, SPARE_NAME, true);
                        return l == null && done(false, "no liner");
                    }
                    AtcServerSVA.Session s = AtcServerSVA.INSTANCE.session(l.getEntityId());
                    if (!asked && s != null && k > 60) {
                        asked = AtcServerSVA.INSTANCE.downlink(w, AirportDataSVA.get(w), l, Cpdlc.D_LANDING, 0, "", now);
                    }
                    if (asked && s != null) {
                        for (Cpdlc.Msg m : s.log.msgs) {
                            if (m.up && m.type == Cpdlc.U_REFUSED && "liner".equals(m.s)) {
                                return done(true, "refused «liner» (#" + g.id + " " + g.icao + ")");
                            }
                            if (m.up && m.type == Cpdlc.U_LAND) {
                                return done(false, "cleared to land on the SHORT runway of #" + g.id);
                            }
                        }
                    }
                    if (k > 280) {
                        return done(false, "no answer (session " + (s != null) + ", asked " + asked + ")");
                    }
                    return false;
                }

                boolean done(boolean ok, String what) {
                    check(ok, "«SHORT liner»: a liner asking to land on a SHORT embassy runway - " + what);
                    remove(l);
                    adBack();
                    resetPlayers();
                    return true;
                }
            });
        }

        // ------------------------------------------------------------------ 7. the stands, the tow, the services, the logbook (§6.2.6)

        private final List<EntityVehicleSVA> parked = new ArrayList<EntityVehicleSVA>();

        private double[] spotCentre(int i) {
            int[] b = f.spotBox(i);
            return new double[] {(b[0] + b[2]) / 2 + 0.5, f.floorY + 1.2, (b[1] + b[3]) / 2 + 0.5};
        }

        /** A free place on the paving off the runway, the pads and the taxiway (the liner's stand; else the apron's middle, the monument aside). */
        private double[] groundSpot() {
            for (int i = 0; i < f.spots(); i++) {
                if (f.spotKind(i) == EmbassyCompatSVA.SPOT_LINER) {
                    return spotCentre(i);
                }
            }
            int[] ab = f.apronBox(), mb = f.monumentBox();
            double x = (ab[0] + ab[2]) / 2 + 0.5, z = (ab[1] + ab[3]) / 2 + 0.5;
            if (mb != null && EmbassyFieldSVA.in(mb, x, z)) {
                x = ab[0] + 3.5;
                z = ab[1] + 3.5;
            }
            return new double[] {x, f.floorY + 1.2, z};
        }

        /** The stand nearest to column 0 within the window's reach (else beside the column, toward the apron). */
        private double[] nearColumn() {
            int[] c = f.columns()[0];
            double[] best = null;
            double reach = com.sva.vehicle.EmbassyServiceMath.SERVE_REACH - 2, bd = reach * reach;
            for (int i = 0; i < f.spots(); i++) {
                double[] s = spotCentre(i);
                double d = (s[0] - c[0] - 0.5) * (s[0] - c[0] - 0.5) + (s[2] - c[2] - 0.5) * (s[2] - c[2] - 0.5);
                if (f.spotKind(i) != EmbassyCompatSVA.SPOT_LINER && d < bd) {
                    bd = d;
                    best = s;
                }
            }
            if (best != null) {
                return best;
            }
            int[] ab = f.apronBox();
            double ax = (ab[0] + ab[2]) / 2 + 0.5 - c[0], az = (ab[1] + ab[3]) / 2 + 0.5 - c[2], al = Math.max(1, Math.hypot(ax, az));
            return new double[] {c[0] + 0.5 + ax / al * 5, f.floorY + 1.2, c[2] + 0.5 + az / al * 5};
        }

        private void stands() {
            step(new Step("stands", 200) {
                List<Integer> small = new ArrayList<Integer>();

                boolean tick(int k, long now) {
                    EmbassyParkingDataSVA pd = EmbassyParkingDataSVA.get(w);
                    if (k == 0) {
                        for (int i = 0; i < f.spots(); i++) {
                            if (f.spotKind(i) == EmbassyCompatSVA.SPOT_SMALL) {
                                small.add(Integer.valueOf(i));
                            }
                        }
                        if (small.size() < 3) {
                            info("«stands»: only " + small.size() + " small stands - the third machine's case skipped");
                        }
                        for (int n = 0; n < Math.min(3, small.size()); n++) {
                            double[] c = spotCentre(small.get(n).intValue());
                            EntityVehicleSVA v = spawn(VehicleType.PLANE, c[0], c[1], c[2], 0F, p1.getUniqueID().toString(), p1.getCommandSenderName(), false);
                            if (v != null) {
                                parked.add(v);
                            }
                        }
                        return parked.isEmpty() && done(false, "no planes");
                    }
                    if (k < 3 * EmbassyServicesSVA.TICK_EVERY + 10) {
                        return false;
                    }
                    int onStand = 0, alive = 0;
                    for (EntityVehicleSVA v : parked) {
                        EmbassyParkingDataSVA.Stand s = pd.standOf(v.state.vehId);
                        onStand += s != null && s.embassy == id && p1.getUniqueID().toString().equals(s.owner) ? 1 : 0;
                        alive += v.isDead ? 0 : 1;
                    }
                    int held = pd.standsOf(id, p1.getUniqueID().toString()).size();
                    return done(onStand == Math.min(2, parked.size()) && held == onStand && alive == parked.size(), "a «Чужак»'s " + parked.size() + " planes"
                            + " stopped on the small stands - " + onStand + " booked to him (" + held + " held, at most 2), none gone (" + alive + ")");
                }

                boolean done(boolean ok, String what) {
                    check(ok, "«stands»: " + what);
                    return true;
                }
            });
            step(new Step("tow", 300) {
                EntityVehicleSVA v;
                double[] at;

                boolean tick(int k, long now) {
                    EmbassyParkingDataSVA pd = EmbassyParkingDataSVA.get(w);
                    if (k == 0) {
                        towWas = ConfigSVA.aptEmbassyTowMinutes;
                        ConfigSVA.aptEmbassyTowMinutes = 0;                   // the 2 minutes passed (no world clock moved under the other tests)
                        int[] rb = f.runwayBox();
                        at = new double[] {(rb[0] + rb[2]) / 2 + 0.5, f.floorY + 1.2, (rb[1] + rb[3]) / 2 + 0.5};
                        v = spawn(VehicleType.PLANE, at[0], at[1], at[2], 0F, p2.getUniqueID().toString(), p2.getCommandSenderName(), false);
                        return v == null && done(false, "no plane");
                    }
                    boolean moved = !f.onRunway(v.posX, v.posZ);
                    if (moved && k > 2 * EmbassyServicesSVA.TICK_EVERY || k > 260) {
                        EmbassyParkingDataSVA.Stand s = pd.standOf(v.state.vehId);
                        boolean lot = false;
                        for (EmbassyParkingDataSVA.Stand x : pd.lot(id)) {
                            lot |= x.vid.equals(v.state.vehId);
                        }
                        return done(moved && !v.isDead && (s != null || lot), "a plane left on the runway with nobody aboard past " + "airport.embassyTowMinutes"
                                + " - towed off it " + moved + " to " + (s != null ? "stand " + f.spotKey(s.spot) : lot ? "the lot" : "nowhere") + ", not gone " + !v.isDead
                                + " (now " + (int) v.posX + " " + (int) v.posZ + ")");
                    }
                    return false;
                }

                boolean done(boolean ok, String what) {
                    check(ok, "«tow»: " + what);
                    ConfigSVA.aptEmbassyTowMinutes = towWas;
                    towWas = -1;
                    remove(v);
                    return true;
                }
            });
        }

        private int chips(EntityPlayer p) {
            Item c = (Item) Item.itemRegistry.getObject("SiliconAge:creditChip");
            int n = 0;
            for (ItemStack s : p.inventory.mainInventory) {
                n += s != null && s.getItem() == c ? s.stackSize : 0;
            }
            return n;
        }

        private void giveChips(EntityPlayer p, int n) {
            Item c = (Item) Item.itemRegistry.getObject("SiliconAge:creditChip");
            if (c != null) {
                p.inventory.addItemStackToInventory(new ItemStack(c, n));
            }
        }

        private static void clearInv(EntityPlayer p) {
            for (int i = 0; i < p.inventory.mainInventory.length; i++) {
                p.inventory.mainInventory[i] = null;
            }
        }

        private void services() {
            step(new Step("services", 120) {
                EntityVehicleSVA v;

                boolean tick(int k, long now) {
                    if (k == 0) {
                        for (EntityVehicleSVA x : parked) {
                            remove(x);
                        }
                        parked.clear();
                        int[] c = f.columns()[0];
                        double[] pv = nearColumn();
                        at(p2, c[0] + 0.5, c[1] + 1, c[2] + 1.5);
                        at(p1, c[0] - 0.5, c[1] + 1, c[2] + 0.5);
                        v = spawn(VehicleType.PLANE, pv[0], pv[1], pv[2], 0F, p2.getUniqueID().toString(), p2.getCommandSenderName(), false);
                        if (v != null) {
                            v.state.energy = 0;
                        }
                        return v == null && fail("no plane");
                    }
                    if (k < 25) {
                        return false;
                    }
                    int[] c = f.columns()[0];
                    trust(p2, 150);
                    clearInv(p2);
                    giveChips(p2, 20);
                    boolean open = EmbassyServicesSVA.INSTANCE.openService(p2, f, 0), open1 = EmbassyServicesSVA.INSTANCE.openService(p1, f, 0);
                    List<IChatComponent> rows = EmbassyServicesSVA.INSTANCE.serviceRows(p2, f);
                    long missing = (long) v.state.maxEnergy() - (long) v.state.energy;
                    int chips0 = chips(p2);
                    double e0 = v.state.energy;
                    EmbassyServicesSVA.INSTANCE.command(p2, id, 0, buy(v, EmbassyCompatSVA.SERVICE_KEYS[EmbassyCompatSVA.S_CHARGE], missing));
                    int paid = chips0 - chips(p2);
                    double got = v.state.energy - e0;
                    check(open && open1 && rows != null && !rows.isEmpty() && rows.size() <= EmbassyCompatSVA.TAB_ROWS && paid > 0 && got > 0,
                            "«services»: the column's window opens for both players at once (" + open + " / " + open1 + "), the Dispatcher's rows " + (rows == null
                                    ? "none" : rows.size()) + " (<= 8); a «Знакомый» buys a charge of " + missing + " EU - the core took " + paid + " chips, the buffer"
                                    + " got " + (long) got + " EU");
                    // the embassy's «Враг»: refused, nothing taken; a FakePlayer: never served
                    v.state.energy = 0;
                    enemy(p2, true);
                    int chips1 = chips(p2);
                    EmbassyServicesSVA.INSTANCE.command(p2, id, 0, buy(v, EmbassyCompatSVA.SERVICE_KEYS[EmbassyCompatSVA.S_CHARGE], missing));
                    boolean enemyRefused = chips(p2) == chips1 && v.state.energy == 0;
                    enemy(p2, false);
                    at(fake, c[0] + 0.5, c[1] + 1, c[2] + 0.5);
                    boolean fakeOpen = EmbassyServicesSVA.INSTANCE.openService(fake, f, 0);
                    EmbassyServicesSVA.INSTANCE.command(fake, id, 0, buy(v, EmbassyCompatSVA.SERVICE_KEYS[EmbassyCompatSVA.S_CHARGE], missing));
                    check(enemyRefused && !fakeOpen && v.state.energy == 0, "«services»: the embassy's «Враг» refused, nothing taken " + enemyRefused + "; a FakePlayer"
                            + " gets no window " + !fakeOpen + " and no service " + (v.state.energy == 0));
                    clearInv(p2);
                    remove(v);
                    resetPlayers();
                    return true;
                }

                boolean fail(String why) {
                    check(false, "«services»: " + why);
                    return true;
                }
            });
        }

        /**
         * Review 2026-10-27: a stand is never blocked for free - a buggy left on a small stand («МС-2») and a plane left on the helipad «Н»
         * (stands they do not fit) are towed off after airport.embassyTowMinutes: the buggy to the lot, the plane to a small stand or the lot.
         */
        private void wrongStands() {
            step(new Step("wrong stands", 300) {
                EntityVehicleSVA buggy, onPad;
                int small = -1, pad = -1;

                boolean tick(int k, long now) {
                    EmbassyParkingDataSVA pd = EmbassyParkingDataSVA.get(w);
                    if (k == 0) {
                        for (int i = 0; i < f.spots(); i++) {
                            if (f.spotKind(i) == EmbassyCompatSVA.SPOT_SMALL && small < 0) {
                                small = i;
                            }
                            if (f.spotKind(i) == EmbassyCompatSVA.SPOT_HELI) {
                                pad = i;
                            }
                        }
                        if (small < 0 || pad < 0) {
                            check(false, "«wrong stands»: no small stand (" + small + ") or no helipad (" + pad + ")");
                            return true;
                        }
                        towWas = ConfigSVA.aptEmbassyTowMinutes;
                        ConfigSVA.aptEmbassyTowMinutes = 0;                   // the 2 minutes passed (no world clock moved under the other tests)
                        double[] a = spotCentre(small), b = spotCentre(pad);
                        buggy = spawn(VehicleType.BUGGY, a[0], a[1], a[2], 0F, p1.getUniqueID().toString(), p1.getCommandSenderName(), false);
                        onPad = spawn(VehicleType.PLANE, b[0], b[1], b[2], 0F, p1.getUniqueID().toString(), p1.getCommandSenderName(), false);
                        return (buggy == null || onPad == null) && done(false, "the machines could not be placed");
                    }
                    boolean bOff = f.spotAt(buggy.posX, buggy.posZ) != small, pOff = f.spotAt(onPad.posX, onPad.posZ) != pad;
                    if (bOff && pOff && k > 2 * EmbassyServicesSVA.TICK_EVERY || k > 260) {
                        EmbassyParkingDataSVA.Stand bl = pd.lotOf(buggy.state.vehId), ps = pd.standOf(onPad.state.vehId), pl = pd.lotOf(onPad.state.vehId);
                        boolean planeOk = ps != null && ps.embassy == id && f.spotKind(ps.spot) == EmbassyCompatSVA.SPOT_SMALL || pl != null && pl.embassy == id;
                        return done(bOff && pOff && !buggy.isDead && !onPad.isDead && bl != null && bl.embassy == id && planeOk, "a buggy left on "
                                + f.spotKey(small) + " - towed off it " + bOff + " to the lot " + (bl != null) + "; a plane left on " + f.spotKey(pad)
                                + " - towed off it " + pOff + " to " + (ps != null ? f.spotKey(ps.spot) : pl != null ? "the lot" : "nowhere") + "; none gone "
                                + (!buggy.isDead && !onPad.isDead));
                    }
                    return false;
                }

                boolean done(boolean ok, String what) {
                    check(ok, "«wrong stands»: " + what);
                    ConfigSVA.aptEmbassyTowMinutes = towWas;
                    towWas = -1;
                    removeAll(buggy, onPad);
                    return true;
                }
            });
        }

        private static NBTTagCompound buy(EntityVehicleSVA v, String key, long amount) {
            NBTTagCompound d = new NBTTagCompound();
            d.setString("Op", EmbassyServicesSVA.OP_BUY);
            d.setInteger("E", v.getEntityId());
            d.setString("K", key);
            d.setLong("A", amount);
            return d;
        }

        private void logbook() {
            step(new Step("logbook", 600) {
                EntityVehicleSVA v;
                double[] up, down;
                int phase, mark, chips0, rep0, chips1, rep1;
                SettlementRegistrySC.PlayerRec pr;

                boolean tick(int k, long now) {
                    if (k == 0) {
                        int spot = -1;
                        for (int i = 0; i < f.spots() && spot < 0; i++) {
                            if (f.spotKind(i) == EmbassyCompatSVA.SPOT_SMALL) {
                                spot = i;
                            }
                        }
                        if (spot < 0) {
                            check(false, "«logbook»: no small stand");
                            return true;
                        }
                        pr = trust(p2, 150);
                        clearInv(p2);
                        down = spotCentre(spot);
                        up = outsideField(down);                              // review 2026-10-27: a flight IN - from beyond the field's box
                        v = plane(up, p2);
                        if (v != null) {
                            v.state.xpdr.mode = XpdrMath.C;
                        }
                        chips0 = chips(p2);
                        rep0 = pr.reputation;
                        return v == null;
                    }
                    if (phase == 0 && k == 3 * EmbassyServicesSVA.TICK_EVERY) {
                        hold.remove(v);                                       // seen in the air: now down on the stand, stopped
                        v.setPosition(down[0], down[1], down[2]);
                        v.motionX = v.motionY = v.motionZ = 0;
                        v.speed = 0;                                          // still: its flight's speed would roll it onto the stand's fence
                        v.throttle = 0;
                        phase = 1;
                        mark = k;
                    }
                    if (phase == 1 && k == mark + 4 * EmbassyServicesSVA.TICK_EVERY) {
                        chips1 = chips(p2);
                        rep1 = pr.reputation;
                        boolean first = chips1 - chips0 == EmbassyAirRulesSC.LOGBOOK_CHIPS && rep1 - rep0 == EmbassyAirRulesSC.LOGBOOK_TRUST
                                && EmbassyAirServiceSC.landed(pr, id) && AirDefenseSVA.xpdrOn(v);
                        long[] mem = EmbassyServicesSVA.INSTANCE.debugLastAir(v);
                        check(first, "«logbook»: a «Знакомый» lands on a stand with the transponder on - the «Лётная книжка»: +" + (chips1 - chips0) + " chips, +"
                                + (rep1 - rep0) + " «Доверия», noted " + EmbassyAirServiceSC.landed(pr, id) + (first ? "" : " (air " + v.airborneForAtc() + ", ground "
                                + v.onGround + ", speed " + String.format("%.3f", v.measuredSpeed()) + ", stand " + f.spotAt(v.posX, v.posZ) + ", rider "
                                + (v.riddenByEntity == p2) + ", memory " + (mem == null ? "none" : java.util.Arrays.toString(mem)) + ", "
                                + String.format("%.2f", v.posY - f.floorY) + " over the floor)"));
                        move(v, up);
                        phase = 2;
                        mark = k;
                    }
                    if (phase == 2 && k == mark + 3 * EmbassyServicesSVA.TICK_EVERY) {
                        hold.remove(v);
                        v.setPosition(down[0], down[1], down[2]);
                        v.motionX = v.motionY = v.motionZ = 0;
                        v.speed = 0;                                          // still: its flight's speed would roll it onto the stand's fence
                        v.throttle = 0;
                        phase = 3;
                        mark = k;
                    }
                    if (phase == 3 && k == mark + 4 * EmbassyServicesSVA.TICK_EVERY) {
                        check(chips(p2) == chips1 && pr.reputation == rep1, "«logbook»: the second landing at the same embassy - nothing more (chips "
                                + (chips(p2) - chips1) + ", «Доверие» " + (pr.reputation - rep1) + ")");
                        remove(v);
                        clearInv(p2);
                        return true;
                    }
                    return false;
                }
            });
        }

        // ------------------------------------------------------------------ 8. everything set back

        void finish() {
            if (finished) {
                return;
            }
            finished = true;
            for (EntityVehicleSVA v : new ArrayList<EntityVehicleSVA>(vehicles)) {
                try {
                    remove(v);
                } catch (Throwable x) {
                    v.setDead();
                }
            }
            hold.clear();
            followed.clear();
            if (modeWas >= 0) {
                SubstrateConfigSC.setEmbassyAirfield(modeWas);
                modeWas = -1;
            }
            adBack();
            rwyBack();
            if (towWas >= 0) {
                ConfigSVA.aptEmbassyTowMinutes = towWas;
                towWas = -1;
            }
            if (domeWas >= 0) {
                ConfigSVA.aptEmbassyDomeMinutes = domeWas;
                domeWas = -1;
            }
            if (squad && reg != null && p1 != null) {
                SquadSC.leave(reg, p1.getUniqueID(), p1.getCommandSenderName());
                squad = false;
            }
            for (TestPlayer p : new TestPlayer[] {p1, p2, p3}) {
                if (p != null) {
                    ride(p, null);
                    clearInv(p);
                    online(p, false);
                    if (p.addedToChunk && w.getChunkProvider().chunkExists(p.chunkCoordX, p.chunkCoordZ)) {
                        w.getChunkFromChunkCoords(p.chunkCoordX, p.chunkCoordZ).removeEntity(p);   // a rider was put in its chunk's list
                    }
                    p.setDead();
                    if (reg != null && rec != null) {
                        trust(p, 0);
                    }
                }
            }
            for (TileEntityAirDefenseSVA te : adBlocks) {
                te.noDrop = true;
            }
            AirDefenseDataSVA dd = AirDefenseDataSVA.get(w);
            for (TileEntityAirDefenseSVA te : adBlocks) {
                if (te instanceof TileEntityCommandPostSVA && dd != null) {
                    dd.removeKp(te.key());
                }
            }
            for (int[] b : platforms) {
                for (int x = b[0]; x <= b[2]; x++) {
                    for (int z = b[1]; z <= b[3]; z++) {
                        for (int y = b[4]; y <= b[4] + 12; y++) {
                            if (w.getBlock(x, y, z) != Blocks.air) {
                                w.setBlock(x, y, z, Blocks.air, 0, 2);      // the post, the radar, the guns (no drops) and the floor
                            }
                        }
                    }
                }
            }
            platforms.clear();
            for (int i = replaced.size() - 1; i >= 0; i--) {
                int[] r = replaced.get(i);
                w.setBlock(r[0], r[1], r[2], Block.getBlockById(r[3]), r[4], 2);
            }
            replaced.clear();
            VehicleRegistrySVA vr = VehicleRegistrySVA.get(w);
            if (vr != null) {
                vr.removeOwner(SPARE_ID);
                for (TestPlayer p : new TestPlayer[] {p1, p2, p3}) {
                    if (p != null) {
                        vr.removeOwner(p.getUniqueID().toString());
                    }
                }
            }
            anchors.clear();
            box = null;
            System.out.println("[SVA-WORLDTEST] INFO embassy: done" + (rec == null ? " (no airfield embassy)" : " - embassy #" + id + " stays (the land made"
                    + " stays, as the core's test)"));
        }
    }
}
