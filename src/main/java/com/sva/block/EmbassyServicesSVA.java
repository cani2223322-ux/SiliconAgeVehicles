package com.sva.block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.sva.ConfigSVA;
import com.sva.FormatSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airport.ApproachMath;
import com.sva.airport.EmbassyAirportSVA;
import com.sva.airport.IlsCat;
import com.sva.airport.IlsPairing;
import com.sva.compat.EmbassyCompatSVA;
import com.sva.compat.EmbassyFieldSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.net.NetAptSVA;
import com.sva.vehicle.BatteryMath;
import com.sva.vehicle.EmbassyServiceMath;
import com.sva.vehicle.FuelMath;
import com.sva.vehicle.SkinMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.FakePlayer;

/**
 * PACKAGE S (docs/plan-embassy-airfield-addon.md §3.8, §3.9): the embassies' services for the add-on's machines, on the server -
 * <ul>
 * <li>the «Колонка обслуживания» window (IEmbassyAirHookSC.openService through EmbassyCompatSVA; with ConfigSVA.aptEmbassyServices): the
 * player's machines on the stands / within EmbassyServiceMath.SERVE_REACH of the column, the services' prices (EmbassyCompatSVA.quote) -
 * sent as NetAptSVA D_EMB (client.GuiEmbassyServiceSVA opens on it); its buttons come back as NetAptSVA Cmd EMB (command): charging the
 * battery and the buffer (BatteryMath), hydrogen (FuelMath), repair in the hangar (GarageMath; the machine in hangarBox), «Электролит»
 * (the battery's service, as the garage's), the emergency (EMERGENCY_PCT, «Чужак» / «Нарушитель», once a day), parking, a tow, an
 * approach chart printed on the player's paper (TowerScreenSVA.chart) - each paid first (EmbassyCompatSVA.pay; PAY_* refusals with the
 * core's lines sc.airfield.pay.<key>), then done;</li>
 * <li>the Dispatcher's tab (serviceRows - at most EmbassyCompatSVA.TAB_ROWS lines of the add-on's keys; tabAction "service" - the same
 * window, from the Dispatcher);</li>
 * <li>the stands (EmbassyParkingDataSVA): a machine stopped on a stand - its owner's (at most SPOTS_PER_PLAYER a player an embassy), the
 * first day free, then a day's fee or the lot; the towing (ConfigSVA.aptEmbassyTowing): nobody aboard on the runway / a pad / the taxiway
 * longer than aptEmbassyTowMinutes - to a free stand of its kind, none - the «Отстой» lot; under the dome longer than
 * aptEmbassyDomeMinutes - the lot; a machine never disappears;</li>
 * <li>the «Лётная книжка»: a machine that was in the air within LANDED_WINDOW stopped on a stand with its transponder answering
 * (AirDefenseSVA.xpdrOn) - EmbassyCompatSVA.firstLanding(its pilot, id).</li>
 * </ul>
 * Registered on the FML bus by CommonProxySVA (the coordinator); clear() at the server's stop. Stub by the coordinator: the signatures
 * are fixed, the bodies are S's.
 * <p>
 * Package S's details:
 * <ul>
 * <li>the window: one per player (UUID - a LAN friend has his own), opened by the column (its index) or the tab (-1); a command is taken
 * only for the window the server opened him (the same embassy and column), only while he is still there (the field's zone, the column
 * within COLUMN_REACH, from the tab - the dome) and only for his own machine (canManage) within the window's reach; each refreshes it
 * (D_EMB without "Open"); the amounts are measured again on the server (the client's "A" only caps them) and shrunk while the core says
 * PAY_LIMIT (EmbassyServiceMath.largest over quote); a placement the tow needs is tried before the payment;</li>
 * <li>the snapshot (D_EMB): "Open", "E" id, "Col", "Kind", "Name" (the embassy's name, JSON), "Icao", "Lvl" (levelOf), "Refuse" (a lang
 * key: the whole window refused - «Враг»), "Paper", "M" [the machines: "Id" entity id, "T" type, "B" board, "Sp" stand's lang key / "",
 * "Lot", "Hg" in the hangar, "En" / "EnMax" EU, "Hyd", "H2" / "H2Max" mB, "Bat", "Bh" / "Bl" battery health / limit %, "Wear" %, "Crew"
 * somebody aboard, "S" [offers: "K" service key, "A" amount, "P" chips (-1 refused), "R" refusal lang key, "X" the base price's hint,
 * "To" a tow's stand]], "St" [his stands: "S" index, "K" lang key, "T", "B", "Due" days, "Free" 0 / 1 first day / 2 talon, "P", "R"],
 * "Lt" [his machines on the lot: "T", "B"], "Ap" [the virtual tower's ILS approaches: "Id", "P", "R"];</li>
 * <li>the memory (by AirDefenseSVA.vehKey; never saved): the last moment in the air, its pilot (UUID) and transponder then, the towing's
 * area and since when it stands there unmanned, the day of the last line to its owner;</li>
 * <li>the stands' books: a booked machine loaded elsewhere, or not loaded and its card (VehicleRegistrySVA) gone or farther than
 * LEFT_STAND from the stand - the record goes (the machine stays wherever it is). Automatic tows are free.</li>
 * </ul>
 * Review 2026-10-27:
 * <ul>
 * <li>a stand is never blocked for free: a machine with nobody aboard standing still on a stand it does not hold (not its kind - a
 * buggy on «МС-2», a plane on «Н» - or another machine's booking) is towed after aptEmbassyTowMinutes (AREA_SPOT: to a free stand it
 * fits, else the lot), one left on FULL's apron off the stands after aptEmbassyApronMinutes (AREA_APRON; the hangar and the lot left
 * alone);</li>
 * <li>the fee is a STAY's (EmbassyParkingDataSVA.take / toLot / leave): taxiing off and on again, a tow, the lot and back keep the stay's
 * days - the first day free once a stay; the lot's days are owed as a stand's, and a tow off the lot (or between stands) pays the days
 * owed first (tow; the window's price is both);</li>
 * <li>a booked machine given to another player is his (setOwner; at his limit - the limit's case);</li>
 * <li>the «Лётная книжка» wants a flight IN: the machine seen in the air outside the field's protected zone's box (Mem.arrived,
 * EmbassyServiceMath.arrival - a machine set down from the inventory and hopped is none); its pilot - AtcServerSVA.pilotOf (the liner's
 * first officer's seat too);</li>
 * <li>onTick never throws into the server's tick (the first few errors logged).</li>
 * </ul>
 */
public final class EmbassyServicesSVA {

    public static final EmbassyServicesSVA INSTANCE = new EmbassyServicesSVA();
    /** The stands / towing / logbook look this often (ticks). */
    public static final int TICK_EVERY = 20;
    /** NetAptSVA Cmd EMB's "Op" values (the window's buttons; S may append). */
    public static final String OP_REFRESH = "refresh", OP_BUY = "buy", OP_PARK = "park", OP_TOW = "tow", OP_CHART = "chart", OP_CLOSE = "close";
    /** A player keeps the window's services within this far of its column (blocks across). */
    public static final int COLUMN_REACH = 12;
    /** At most this many machines in the window (the nearest the player). */
    public static final int MAX_MACHINES = 8;
    /** The memory follows machines in the air within this far of an embassy's core past its air defence zone (blocks across). */
    public static final int AIR_WATCH = 64;
    /** A machine past its owner's SPOTS_PER_PLAYER goes to the lot this long after it stands there unmanned (ticks). TODO(balance) */
    public static final int LIMIT_GRACE = 200;

    /** Per player (UUID): the window the server opened him - {embassy id, column (-1 the tab)}. Server thread. */
    private final Map<String, int[]> windows = new HashMap<String, int[]>();
    /** Per machine (AirDefenseSVA.vehKey): the tick's memory. Server thread. */
    private final Map<String, Mem> mem = new HashMap<String, Mem>();
    private int ticks, errors;

    /** What the tick remembers of a machine. */
    static final class Mem {
        /** In the air last (world ticks; 0 - not since the last landing counted), its pilot (UUID, "" none) and transponder then. */
        long lastAir;
        /** Review 2026-10-27: the flight in - last in the air outside the field's zone box, kept through the approach (0 none). */
        long arrived;
        String pilot = "";
        boolean xpdr;
        /** The towing's area it stands in unmanned and still (EmbassyServiceMath.AREA_*) since `since` (-1: not so). */
        int area;
        long since = -1;
        /** Unmanned on a stand past the owner's SPOTS_PER_PLAYER since (-1: not so), the stand he was told so at (-1 none). */
        long overSince = -1;
        int overSpot = -1;
        /** The day its owner was last told of the parking owed. */
        long toldDay = -1;
        /** Last looked at (world ticks). */
        long seen;
    }

    private EmbassyServicesSVA() {
    }

    // ================================================================== the window

    /** A column clicked (not sneaking): the window opened - true; false: the core serves the click (its armour gases). */
    public boolean openService(EntityPlayerMP p, EmbassyFieldSVA f, int column) {
        if (!ConfigSVA.aptEmbassyServices || !real(p) || f == null || p.worldObj == null || p.worldObj.isRemote) {
            return false;
        }
        windows.put(uuid(p), new int[] {f.id, column});
        NetAptSVA.send(p, NetAptSVA.D_EMB, snapshot(p, f, column, true));
        return true;
    }

    /** The Dispatcher's tab rows for `p` (null / empty: none). */
    public List<IChatComponent> serviceRows(EntityPlayerMP p, EmbassyFieldSVA f) {
        List<IChatComponent> out = new ArrayList<IChatComponent>();
        if (!real(p) || f == null || p.worldObj == null || p.worldObj.isRemote) {
            return out;
        }
        World w = p.worldObj;
        int lvl = EmbassyCompatSVA.levelOf(p, f.id);
        EmbassyParkingDataSVA d = EmbassyParkingDataSVA.get(w);
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        long today = EmbassyServiceMath.day(w.getWorldTime());
        String u = uuid(p);
        int rows = EmbassyCompatSVA.TAB_ROWS - 2;                  // the prices' and the tower's rows stay
        boolean any = false;
        if (d != null) {
            for (EmbassyParkingDataSVA.Stand s : d.standsOf(f.id, u)) {
                if (s.spot >= f.spots()) {
                    continue;                                       // a stand the field has no more (the next look drops it)
                }
                if (rows-- <= 0) {
                    break;
                }
                out.add(new ChatComponentTranslation("sva.emb.tab.stand", new ChatComponentTranslation(f.spotKey(s.spot)), machineName(reg, s.vid),
                        standState(p, f, s, today)));
                any = true;
            }
            for (EmbassyParkingDataSVA.Stand s : d.lotOf(f.id, u)) {
                if (rows-- <= 0) {
                    break;
                }
                out.add(new ChatComponentTranslation("sva.emb.tab.lot", machineName(reg, s.vid), standState(p, f, s, today)));
                any = true;
            }
        }
        if (!any) {
            out.add(new ChatComponentTranslation("sva.emb.tab.none"));
        }
        if (lvl != EmbassyCompatSVA.ENEMY) {
            out.add(new ChatComponentTranslation("sva.emb.tab.prices", priceText(p, f, EmbassyCompatSVA.S_CHARGE),
                    priceText(p, f, EmbassyCompatSVA.S_HYDROGEN)));
        } else {
            out.add(new ChatComponentTranslation("sc.airfield.pay.enemy"));
        }
        out.add(towerRow(w, f));
        return out;
    }

    /** The tab's button (EmbassyCompatSVA.TAB_ACTION_SERVICE: the service window, as a column's click). */
    public void tabAction(EntityPlayerMP p, EmbassyFieldSVA f, String action) {
        if (!EmbassyCompatSVA.TAB_ACTION_SERVICE.equals(action) || !real(p) || f == null) {
            return;
        }
        if (!ConfigSVA.aptEmbassyServices) {
            tell(p, EnumChatFormatting.YELLOW, "sva.emb.msg.servicesOff");
            return;
        }
        openService(p, f, -1);
    }

    /**
     * NetAptSVA Cmd EMB from the window (the server's queue thread - ServerQueueSVA): embassy `id`, the column it was opened at
     * (-1: from the Dispatcher's tab), the button's data ("Op" OP_*, "E" the machine's entity id, "K" the service key, "A" the amount).
     * S checks the player is still at that embassy (its field's zone / the column's reach) before anything.
     */
    public void command(EntityPlayerMP p, int id, int column, NBTTagCompound data) {
        if (!real(p) || data == null || p.worldObj == null || p.worldObj.isRemote) {
            return;
        }
        String u = uuid(p), op = data.getString("Op");
        int[] win = windows.get(u);
        if (OP_CLOSE.equals(op)) {
            windows.remove(u);
            return;
        }
        if (win == null || win[0] != id || win[1] != column || !EmbassyCompatSVA.on()) {
            return;                                                 // a window the server never opened him
        }
        World w = p.worldObj;
        EmbassyFieldSVA f = EmbassyCompatSVA.field(w, id);
        if (f == null || !ConfigSVA.aptEmbassyServices) {
            windows.remove(u);
            return;
        }
        if (!atEmbassy(p, f, column)) {
            windows.remove(u);
            tell(p, EnumChatFormatting.YELLOW, "sva.emb.msg.away");
            return;
        }
        if (EmbassyCompatSVA.levelOf(p, id) != EmbassyCompatSVA.ENEMY) {
            if (OP_BUY.equals(op)) {
                buy(p, f, column, machine(p, f, column, data.getInteger("E")), data.getString("K"), data.getLong("A"));
            } else if (OP_PARK.equals(op)) {
                park(p, f, data.getInteger("S"));
            } else if (OP_TOW.equals(op)) {
                tow(p, f, machine(p, f, column, data.getInteger("E")), data.hasKey("S") ? data.getInteger("S") : -1);
            } else if (OP_CHART.equals(op)) {
                chart(p, f, data.getString("Ap"));
            }
        }
        NetAptSVA.send(p, NetAptSVA.D_EMB, snapshot(p, f, column, false));
    }

    // ------------------------------------------------------------------ the services

    /** «Купить»: charge / hydrogen / repair / electrolyte / emergency for machine `v` - paid, then done. */
    private void buy(EntityPlayerMP p, EmbassyFieldSVA f, int column, EntityVehicleSVA v, String key, long asked) {
        if (v == null) {
            tell(p, EnumChatFormatting.YELLOW, "sva.emb.msg.noMachine");
            return;
        }
        int s = service(key);
        int lvl = EmbassyCompatSVA.levelOf(p, f.id);
        long want = wanted(v, s, lvl);
        if (want > 0 && asked > 0) {
            want = Math.min(want, asked);
        }
        if (s == EmbassyServiceMath.S_REPAIR && !inHangar(f, v)) {
            tell(p, EnumChatFormatting.YELLOW, "sva.emb.msg.hangarOnly");
            return;
        }
        if (want <= 0 || s == EmbassyCompatSVA.S_PARK || s == EmbassyCompatSVA.S_TOW || s == EmbassyCompatSVA.S_CHART
                || s == EmbassyCompatSVA.S_ARMOUR_GAS) {
            tell(p, EnumChatFormatting.YELLOW, "sva.emb.msg.nothing");
            return;
        }
        long[] pr = priced(p, f.id, key, want);
        if (pr[1] < 0) {
            refuse(p, (int) -pr[1]);
            return;
        }
        int code = EmbassyCompatSVA.pay(p, f.id, key, pr[0], false);
        if (code != EmbassyCompatSVA.PAY_OK) {
            refuse(p, code);
            return;
        }
        IChatComponent amount;
        if (s == EmbassyCompatSVA.S_CHARGE) {
            amount = unit("eu", (long) v.addEnergy(pr[0]));
        } else if (s == EmbassyCompatSVA.S_HYDROGEN) {
            amount = unit("mb", v.addGas(FuelMath.H2, (int) Math.min(Integer.MAX_VALUE, pr[0])));
        } else if (s == EmbassyCompatSVA.S_REPAIR) {
            v.repairHull((float) EmbassyServiceMath.repairHp((int) pr[0], v.getMaxHp()));
            amount = unit("pct", pr[0]);
        } else if (s == EmbassyCompatSVA.S_ELECTROLYTE) {
            int[] r = v.garageServiceBattery();
            amount = new ChatComponentTranslation("sva.emb.msg.battery", Integer.valueOf(r[0]), Integer.valueOf(r[1]));
        } else {
            long eu = (long) v.addEnergy(EmbassyServiceMath.emergencyAmount(v.getEnergy(), (long) v.getMaxEnergy()));
            int mb = v.onHydrogen() ? v.addGas(FuelMath.H2, (int) EmbassyServiceMath.emergencyAmount(v.h2Mb(), v.h2Max())) : 0;
            amount = new ChatComponentTranslation("sva.emb.msg.emergency", unit("eu", eu), unit("mb", mb));
        }
        tell(p, EnumChatFormatting.GREEN, "sva.emb.msg.served", new ChatComponentTranslation("sc.airfield.service." + key), name(v), amount,
                Long.valueOf(Math.max(0, pr[1])));
    }

    /** «Оплатить стоянку»: stand `spot`'s days owed paid (the core: free with a talon). */
    private void park(EntityPlayerMP p, EmbassyFieldSVA f, int spot) {
        World w = p.worldObj;
        EmbassyParkingDataSVA d = EmbassyParkingDataSVA.get(w);
        EmbassyParkingDataSVA.Stand s = d == null || spot < 0 || spot >= f.spots() ? null : d.stand(f.id, spot);
        if (s == null || !s.owner.equals(uuid(p))) {
            tell(p, EnumChatFormatting.YELLOW, "sva.emb.msg.noStand");
            return;
        }
        long today = EmbassyServiceMath.day(w.getWorldTime());
        int due = EmbassyServiceMath.parkDaysDue(s.sinceDay, s.paidDay, today);
        if (due <= 0) {
            tell(p, EnumChatFormatting.YELLOW, "sva.emb.msg.nothing");
            return;
        }
        String key = EmbassyCompatSVA.SERVICE_KEYS[EmbassyCompatSVA.S_PARK];
        long[] pr = priced(p, f.id, key, due);
        if (pr[1] < 0 || pr[0] < due) {
            refuse(p, pr[1] < 0 ? (int) -pr[1] : EmbassyCompatSVA.PAY_LIMIT);
            return;
        }
        int code = EmbassyCompatSVA.pay(p, f.id, key, due, false);
        if (code != EmbassyCompatSVA.PAY_OK) {
            refuse(p, code);
            return;
        }
        d.paid(s, today);
        tell(p, EnumChatFormatting.GREEN, "sva.emb.msg.parkPaid", new ChatComponentTranslation(f.spotKey(spot)), unit("day", due),
                Long.valueOf(Math.max(0, pr[1])));
    }

    /** «Перегнать»: his machine `v` (nobody aboard) to a free stand of its kind (`only` >= 0: that one) - paid (the day's first free). */
    private void tow(EntityPlayerMP p, EmbassyFieldSVA f, EntityVehicleSVA v, int only) {
        if (v == null) {
            tell(p, EnumChatFormatting.YELLOW, "sva.emb.msg.noMachine");
            return;
        }
        if (AirDefenseSVA.manned(v)) {
            tell(p, EnumChatFormatting.YELLOW, "sva.emb.msg.towManned");
            return;
        }
        World w = p.worldObj;
        EmbassyParkingDataSVA d = EmbassyParkingDataSVA.get(w);
        if (d == null) {
            return;
        }
        String vid = AirDefenseSVA.vehKey(v), owner = ownerOf(v);
        long today = EmbassyServiceMath.day(w.getWorldTime());
        EmbassyParkingDataSVA.Stand own = d.standOf(vid);
        if (own != null && own.embassy == f.id && f.spotAt(v.posX, v.posZ) == own.spot) {
            tell(p, EnumChatFormatting.YELLOW, "sva.emb.msg.nothing");    // review 2026-10-27: on its own stand already (a second click)
            return;
        }
        if (!EmbassyServiceMath.mayPark(d.heldBy(f.id, owner, vid))) {
            tell(p, EnumChatFormatting.YELLOW, "sva.emb.msg.limit", Integer.valueOf(EmbassyServiceMath.SPOTS_PER_PLAYER));
            return;
        }
        List<EntityVehicleSVA> others = EmbassyServiceLotSVA.near(w, f);
        int to = EmbassyServiceLotSVA.freeStand(f, v, vid, d, others, only);
        if (to < 0) {
            tell(p, EnumChatFormatting.YELLOW, "sva.emb.msg.noFreeStand");
            return;
        }
        // review 2026-10-27: the stay's days owed (the lot's, another stand's) are paid first - a tow is no way out of the fee
        EmbassyParkingDataSVA.Stand stay = d.earlier(f.id, vid, today);
        int owed = owed(stay, today);
        String parkKey = EmbassyCompatSVA.SERVICE_KEYS[EmbassyCompatSVA.S_PARK];
        long[] pp = owed > 0 ? priced(p, f.id, parkKey, owed) : null;
        if (pp != null && (pp[1] < 0 || pp[0] < owed)) {
            refuse(p, pp[1] < 0 ? (int) -pp[1] : EmbassyCompatSVA.PAY_LIMIT);
            return;
        }
        String key = EmbassyCompatSVA.SERVICE_KEYS[EmbassyCompatSVA.S_TOW];
        long[] pr = priced(p, f.id, key, 1);
        if (pr[1] < 0) {
            refuse(p, (int) -pr[1]);
            return;
        }
        if (pp != null) {
            int pc = EmbassyCompatSVA.pay(p, f.id, parkKey, owed, false);
            if (pc != EmbassyCompatSVA.PAY_OK) {
                refuse(p, pc);
                return;
            }
            d.paid(stay, today);
        }
        int code = EmbassyCompatSVA.pay(p, f.id, key, 1, false);
        if (code != EmbassyCompatSVA.PAY_OK) {
            refuse(p, code);
            return;
        }
        if (!EmbassyServiceLotSVA.toStand(w, f, v, to)) {
            tell(p, EnumChatFormatting.YELLOW, "sva.emb.msg.noFreeStand");  // tried dry the moment before: only a race
            return;
        }
        long now = w.getTotalWorldTime();
        d.take(f.id, to, vid, owner, now, today);
        forgetTimers(vid);
        tell(p, EnumChatFormatting.GREEN, "sva.emb.msg.towed", name(v), new ChatComponentTranslation(f.spotKey(to)),
                Long.valueOf(Math.max(0, pr[1]) + (pp == null ? 0 : Math.max(0, pp[1]))));
    }

    /** The days a stay's record owes today (0: none, paid, its first day, an unknown stay). */
    static int owed(EmbassyParkingDataSVA.Stand s, long today) {
        return s == null || s.sinceDay < 0 ? 0 : EmbassyServiceMath.parkDaysDue(s.sinceDay, s.paidDay, today);
    }

    /** «Карта подхода»: the virtual tower's approach `apId` printed on a sheet of his paper - paid (the first free). */
    private void chart(EntityPlayerMP p, EmbassyFieldSVA f, String apId) {
        World w = p.worldObj;
        AirportDataSVA d = AirportDataSVA.get(w);
        AirportDataSVA.Tower t = towerOf(w, f);
        ApproachMath.Approach ap = null;
        if (d != null && t != null) {
            for (ApproachMath.Approach a : d.approaches(t)) {
                if (a.id.equals(apId)) {
                    ap = a;
                }
            }
        }
        if (ap == null) {
            tell(p, EnumChatFormatting.YELLOW, "sva.emb.msg.noChart");
            return;
        }
        if (!p.capabilities.isCreativeMode && !p.inventory.hasItem(Items.paper)) {
            p.addChatComponentMessage(new ChatComponentTranslation("sva.tower.msg.noPaper"));
            return;
        }
        String key = EmbassyCompatSVA.SERVICE_KEYS[EmbassyCompatSVA.S_CHART];
        long[] pr = priced(p, f.id, key, 1);
        if (pr[1] < 0) {
            refuse(p, (int) -pr[1]);
            return;
        }
        int code = EmbassyCompatSVA.pay(p, f.id, key, 1, false);
        if (code != EmbassyCompatSVA.PAY_OK) {
            refuse(p, code);
            return;
        }
        if (!p.capabilities.isCreativeMode) {
            p.inventory.consumeInventoryItem(Items.paper);
        }
        ItemStack chart = TowerScreenSVA.chart(w, d, t, ap);
        if (!p.inventory.addItemStackToInventory(chart)) {
            p.dropPlayerItemWithRandomChoice(chart, false);
        }
        p.addChatComponentMessage(new ChatComponentTranslation("sva.tower.msg.printed", ap.id, t.icao));
        if (p.playerNetServerHandler != null) {
            p.inventoryContainer.detectAndSendChanges();
        }
    }

    // ------------------------------------------------------------------ the snapshot

    /** The window's picture for `p` (see the class comment). */
    NBTTagCompound snapshot(EntityPlayerMP p, EmbassyFieldSVA f, int column, boolean open) {
        World w = p.worldObj;
        NBTTagCompound t = new NBTTagCompound();
        t.setBoolean("Open", open);
        t.setInteger("E", f.id);
        t.setInteger("Col", column);
        t.setInteger("Kind", f.kind);
        t.setString("Name", IChatComponent.Serializer.func_150696_a(f.name()));
        AirportDataSVA.Tower tw = towerOf(w, f);
        t.setString("Icao", tw != null && tw.icao.length() > 0 ? tw.icao : f.icao);
        int lvl = EmbassyCompatSVA.levelOf(p, f.id);
        t.setInteger("Lvl", lvl);
        if (lvl == EmbassyCompatSVA.ENEMY) {
            t.setString("Refuse", payKey(EmbassyCompatSVA.PAY_ENEMY));
            return t;
        }
        boolean paper = p.capabilities.isCreativeMode || p.inventory.hasItem(Items.paper);
        t.setBoolean("Paper", paper);
        EmbassyParkingDataSVA d = EmbassyParkingDataSVA.get(w);
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        long today = EmbassyServiceMath.day(w.getWorldTime());
        String u = uuid(p);
        List<EntityVehicleSVA> others = EmbassyServiceLotSVA.near(w, f);
        NBTTagList ml = new NBTTagList();
        for (EntityVehicleSVA v : machines(p, f, column)) {
            ml.appendTag(machineTag(p, f, v, lvl, d, others));
        }
        t.setTag("M", ml);
        NBTTagList sl = new NBTTagList(), ll = new NBTTagList();
        if (d != null) {
            for (EmbassyParkingDataSVA.Stand s : d.standsOf(f.id, u)) {
                if (s.spot >= f.spots()) {
                    continue;
                }
                NBTTagCompound e = new NBTTagCompound();
                e.setInteger("S", s.spot);
                e.setString("K", f.spotKey(s.spot));
                card(e, reg, s.vid);
                int due = EmbassyServiceMath.parkDaysDue(s.sinceDay, s.paidDay, today);
                e.setInteger("Due", due);
                e.setInteger("Free", EmbassyCompatSVA.hasTalon(p, f.id) ? 2 : EmbassyServiceMath.freeDay(s.sinceDay, today) ? 1 : 0);
                if (due > 0) {
                    offer(e, priced(p, f.id, EmbassyCompatSVA.SERVICE_KEYS[EmbassyCompatSVA.S_PARK], due));
                }
                sl.appendTag(e);
            }
            for (EmbassyParkingDataSVA.Stand s : d.lotOf(f.id, u)) {
                NBTTagCompound e = new NBTTagCompound();
                card(e, reg, s.vid);
                e.setInteger("Due", EmbassyCompatSVA.hasTalon(p, f.id) ? 0 : owed(s, today));   // paid with the tow off the lot
                ll.appendTag(e);
            }
        }
        t.setTag("St", sl);
        t.setTag("Lt", ll);
        NBTTagList al = new NBTTagList();
        AirportDataSVA ad = AirportDataSVA.get(w);
        if (tw != null && ad != null) {
            for (ApproachMath.Approach a : ad.approaches(tw)) {
                NBTTagCompound e = new NBTTagCompound();
                e.setString("Id", a.id);
                if (paper) {
                    offer(e, priced(p, f.id, EmbassyCompatSVA.SERVICE_KEYS[EmbassyCompatSVA.S_CHART], 1));
                } else {
                    e.setInteger("P", -1);
                    e.setString("R", "sva.emb.gui.noPaper");
                }
                al.appendTag(e);
            }
        }
        t.setTag("Ap", al);
        return t;
    }

    /** One machine's row: its card, its stores and the services offered for it. */
    private NBTTagCompound machineTag(EntityPlayerMP p, EmbassyFieldSVA f, EntityVehicleSVA v, int lvl, EmbassyParkingDataSVA d,
            List<EntityVehicleSVA> others) {
        NBTTagCompound e = new NBTTagCompound();
        String vid = AirDefenseSVA.vehKey(v);
        VehicleType ty = v.getVehicleType();
        e.setInteger("Id", v.getEntityId());
        e.setInteger("T", ty.ordinal());
        e.setInteger("B", v.getBoardNumber());
        EmbassyParkingDataSVA.Stand st = d == null ? null : d.standOf(vid);
        int spot = f.spotAt(v.posX, v.posZ);
        e.setString("Sp", st != null && st.embassy == f.id ? f.spotKey(st.spot) : spot >= 0 ? f.spotKey(spot) : "");
        e.setBoolean("Lot", d != null && d.lotOf(vid) != null && d.lotOf(vid).embassy == f.id);
        boolean hangar = inHangar(f, v);
        e.setBoolean("Hg", hangar);
        e.setLong("En", v.getEnergy());
        e.setLong("EnMax", (long) v.getMaxEnergy());
        e.setBoolean("Hyd", v.onHydrogen());
        e.setInteger("H2", v.h2Mb());
        e.setInteger("H2Max", v.h2Max());
        e.setBoolean("Bat", v.onHydrogen() && v.batteryPresent());
        e.setInteger("Bh", (int) Math.round(v.batteryHealth() * 100));
        e.setInteger("Bl", (int) Math.round(v.batteryLimit() * 100));
        e.setInteger("Wear", EmbassyServiceMath.wearPct(v.getHp(), v.getMaxHp()));
        boolean crew = AirDefenseSVA.manned(v);
        e.setBoolean("Crew", crew);
        NBTTagList sl = new NBTTagList();
        int[] order = {EmbassyCompatSVA.S_CHARGE, EmbassyCompatSVA.S_HYDROGEN, EmbassyCompatSVA.S_REPAIR, EmbassyCompatSVA.S_ELECTROLYTE,
            EmbassyCompatSVA.S_EMERGENCY};
        for (int s : order) {
            long want = wanted(v, s, lvl);
            if (want <= 0) {
                continue;
            }
            NBTTagCompound o = new NBTTagCompound();
            String key = EmbassyCompatSVA.SERVICE_KEYS[s];
            o.setString("K", key);
            if (s == EmbassyCompatSVA.S_REPAIR && !hangar) {
                o.setLong("A", want);
                o.setInteger("P", -1);
                o.setString("R", "sva.emb.gui.hangarOnly");
            } else {
                offer(o, priced(p, f.id, key, want));
            }
            o.setLong("X", EmbassyServiceMath.hint(s, want));
            sl.appendTag(o);
        }
        // the tow: a machine not on a stand of his here, to a free one of its kind
        boolean onOwn = st != null && st.embassy == f.id && spot == st.spot;
        if (d != null && !onOwn && EmbassyServiceMath.spotKindFor(ty) != EmbassyServiceMath.NONE) {
            NBTTagCompound o = new NBTTagCompound();
            o.setString("K", EmbassyCompatSVA.SERVICE_KEYS[EmbassyCompatSVA.S_TOW]);
            int to = EmbassyServiceLotSVA.freeStand(f, v, vid, d, others, -1);
            if (crew) {
                o.setLong("A", 1);
                o.setInteger("P", -1);
                o.setString("R", "sva.emb.gui.towManned");
            } else if (!EmbassyServiceMath.mayPark(d.heldBy(f.id, ownerOf(v), vid))) {
                o.setLong("A", 1);
                o.setInteger("P", -1);
                o.setString("R", "sva.emb.gui.towLimit");
            } else if (to < 0) {
                o.setLong("A", 1);
                o.setInteger("P", -1);
                o.setString("R", "sva.emb.gui.towNone");
            } else {
                long[] tp = priced(p, f.id, o.getString("K"), 1);
                long today = EmbassyServiceMath.day(p.worldObj.getWorldTime());
                int owed = owed(d.earlier(f.id, vid, today), today);
                if (owed > 0) {                                     // review 2026-10-27: the stay's days owed go with the tow
                    long[] pp = priced(p, f.id, EmbassyCompatSVA.SERVICE_KEYS[EmbassyCompatSVA.S_PARK], owed);
                    if (pp[1] < 0 || pp[0] < owed) {
                        tp = new long[] {1, pp[1] < 0 ? pp[1] : -EmbassyCompatSVA.PAY_LIMIT};
                    } else if (tp[1] >= 0) {
                        tp[1] += pp[1];
                    }
                    o.setInteger("Due", owed);
                }
                offer(o, tp);
                o.setString("To", f.spotKey(to));
                o.setInteger("S", to);
            }
            o.setLong("X", EmbassyServiceMath.hint(EmbassyCompatSVA.S_TOW, 1));
            sl.appendTag(o);
        }
        e.setTag("S", sl);
        return e;
    }

    /** What service `s` would give machine `v` now (its measure; 0 - nothing to do, or not offered at this level). */
    private static long wanted(EntityVehicleSVA v, int s, int lvl) {
        if (v == null) {
            return 0;
        }
        if (s == EmbassyCompatSVA.S_CHARGE) {
            return EmbassyServiceMath.offer(s, (long) Math.floor(v.energyRoom()));
        }
        if (s == EmbassyCompatSVA.S_HYDROGEN) {
            return v.onHydrogen() && v.h2Max() > 0 ? EmbassyServiceMath.offer(s, v.gasRoom(FuelMath.H2)) : 0;
        }
        if (s == EmbassyCompatSVA.S_REPAIR) {
            return EmbassyServiceMath.offer(s, EmbassyServiceMath.wearPct(v.getHp(), v.getMaxHp()));
        }
        if (s == EmbassyCompatSVA.S_ELECTROLYTE) {
            return v.onHydrogen() && v.batteryPresent() && BatteryMath.serviceUseful(v.batteryHealth(), v.batteryLimit()) ? 1 : 0;
        }
        if (s == EmbassyCompatSVA.S_EMERGENCY) {
            if (!EmbassyServiceMath.levelSells(s, lvl)) {
                return 0;                                           // the rest buy the real thing
            }
            long eu = EmbassyServiceMath.emergencyAmount(v.getEnergy(), (long) v.getMaxEnergy());
            long mb = v.onHydrogen() ? EmbassyServiceMath.emergencyAmount(v.h2Mb(), v.h2Max()) : 0;
            return eu > 0 || mb > 0 ? 1 : 0;
        }
        return 0;
    }

    /** {amount, chips >= 0 or -PAY_*} of `want` of service `key` - shrunk while the core says PAY_LIMIT (the day's or the service's cap). */
    private static long[] priced(final EntityPlayer p, final int id, final String key, long want) {
        int q = EmbassyCompatSVA.quote(p, id, key, want, false);
        if (q == -EmbassyCompatSVA.PAY_LIMIT && want > 1) {
            long a = EmbassyServiceMath.largest(want - 1, new EmbassyServiceMath.Probe() {
                @Override
                public boolean ok(long amount) {
                    return EmbassyCompatSVA.quote(p, id, key, amount, false) != -EmbassyCompatSVA.PAY_LIMIT;
                }
            });
            if (a > 0) {
                want = a;
                q = EmbassyCompatSVA.quote(p, id, key, a, false);
            }
        }
        return new long[] {want, q};
    }

    /** An offer's "A" / "P" / "R" from priced(). */
    private static void offer(NBTTagCompound o, long[] pr) {
        o.setLong("A", pr[0]);
        if (pr[1] >= 0) {
            o.setInteger("P", (int) pr[1]);
        } else {
            o.setInteger("P", -1);
            o.setString("R", payKey((int) -pr[1]));
        }
    }

    /** The registry's card of a booked machine (it may be unloaded): "T" type, "B" board (-1: unknown). */
    private static void card(NBTTagCompound e, VehicleRegistrySVA reg, String vid) {
        VehicleRegistrySVA.Entry c = reg == null ? null : reg.entry(vid);
        e.setInteger("T", c == null ? -1 : c.type);
        e.setInteger("B", c == null ? -1 : c.board);
    }

    // ------------------------------------------------------------------ which machines, where

    /** `p`'s machines (canManage) in the window's reach, the nearest him first (at most MAX_MACHINES). */
    private List<EntityVehicleSVA> machines(final EntityPlayerMP p, EmbassyFieldSVA f, int column) {
        List<EntityVehicleSVA> out = new ArrayList<EntityVehicleSVA>();
        EmbassyParkingDataSVA d = EmbassyParkingDataSVA.get(p.worldObj);
        for (EntityVehicleSVA v : EmbassyServiceLotSVA.near(p.worldObj, f)) {
            if (v.canManage(p) && reach(f, column, v, d)) {
                out.add(v);
            }
        }
        Collections.sort(out, new Comparator<EntityVehicleSVA>() {
            @Override
            public int compare(EntityVehicleSVA a, EntityVehicleSVA b) {
                return Double.compare(a.getDistanceSqToEntity(p), b.getDistanceSqToEntity(p));
            }
        });
        return out.size() > MAX_MACHINES ? new ArrayList<EntityVehicleSVA>(out.subList(0, MAX_MACHINES)) : out;
    }

    /** The command's machine: entity `eid`, his (canManage), in the window's reach (null: not so). */
    private EntityVehicleSVA machine(EntityPlayerMP p, EmbassyFieldSVA f, int column, int eid) {
        Entity e = p.worldObj.getEntityByID(eid);
        if (!(e instanceof EntityVehicleSVA) || e.isDead) {
            return null;
        }
        EntityVehicleSVA v = (EntityVehicleSVA) e;
        return v.canManage(p) && EmbassyServiceLotSVA.at(f, v) && reach(f, column, v, EmbassyParkingDataSVA.get(p.worldObj)) ? v : null;
    }

    /**
     * The window serves machine `v`: on the field's paving height (+-8), and on one of its stands, in its hangar, on its lot (the books),
     * or within SERVE_REACH of the column (from the tab: of any column).
     */
    private static boolean reach(EmbassyFieldSVA f, int column, EntityVehicleSVA v, EmbassyParkingDataSVA d) {
        if (Math.abs(v.posY - (f.floorY + 1)) > 8) {
            return false;
        }
        if (f.spotAt(v.posX, v.posZ) >= 0 || inHangar(f, v)) {
            return true;
        }
        EmbassyParkingDataSVA.Stand l = d == null ? null : d.lotOf(AirDefenseSVA.vehKey(v));
        if (l != null && l.embassy == f.id) {
            return true;
        }
        int[][] cols = f.columns();
        double r = EmbassyServiceMath.SERVE_REACH;
        for (int i = 0; i < cols.length; i++) {
            if (column < 0 || i == column) {
                double dx = v.posX - (cols[i][0] + 0.5), dz = v.posZ - (cols[i][2] + 0.5);
                if (dx * dx + dz * dz <= r * r) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean inHangar(EmbassyFieldSVA f, EntityVehicleSVA v) {
        return EmbassyFieldSVA.in(f.hangarBox(), v.posX, v.posZ);
    }

    /** `p` is still at the embassy: in its field's zone, within COLUMN_REACH of the column, or (the tab) under its dome. */
    private static boolean atEmbassy(EntityPlayerMP p, EmbassyFieldSVA f, int column) {
        if (p.worldObj.provider.dimensionId != f.dim) {
            return false;
        }
        if (EmbassyCompatSVA.inZone(f, MathHelper.floor_double(p.posX), MathHelper.floor_double(p.posZ))) {
            return true;
        }
        int[][] cols = f.columns();
        if (column >= 0 && column < cols.length) {
            double dx = p.posX - (cols[column][0] + 0.5), dy = p.posY - cols[column][1], dz = p.posZ - (cols[column][2] + 0.5);
            return dx * dx + dy * dy + dz * dz <= COLUMN_REACH * COLUMN_REACH;
        }
        return column < 0 && f.inDome(p.posX, p.posZ);
    }

    // ================================================================== the stands, the towing, the logbook

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || ++ticks % TICK_EVERY != 0 || !EmbassyCompatSVA.on()) {
            return;
        }
        try {
            tick();
        } catch (RuntimeException x) {
            if (errors++ < 3) {                                     // review 2026-10-27: never into the server's tick
                System.out.println("[SVA-EMB] embassy services: " + x);
                x.printStackTrace(System.out);
            }
        }
    }

    /** One look of the stands, the towing and the logbook (onTick's, every TICK_EVERY). */
    private void tick() {
        World w = DimensionManager.getWorld(0);
        if (w == null || w.isRemote) {
            return;
        }
        List<EmbassyFieldSVA> fields = EmbassyCompatSVA.fields(w);
        if (fields.isEmpty()) {
            mem.clear();
            return;
        }
        EmbassyParkingDataSVA d = EmbassyParkingDataSVA.get(w);
        if (d == null) {
            return;
        }
        long now = w.getTotalWorldTime(), today = EmbassyServiceMath.day(w.getWorldTime());
        Map<String, EntityVehicleSVA> loaded = new HashMap<String, EntityVehicleSVA>();
        Map<Integer, List<EntityVehicleSVA>> atField = new HashMap<Integer, List<EntityVehicleSVA>>();
        for (Object o : w.loadedEntityList) {
            if (!(o instanceof EntityVehicleSVA) || ((EntityVehicleSVA) o).isDead) {
                continue;
            }
            EntityVehicleSVA v = (EntityVehicleSVA) o;
            loaded.put(AirDefenseSVA.vehKey(v), v);
            for (EmbassyFieldSVA f : fields) {
                if (EmbassyServiceLotSVA.at(f, v)) {
                    List<EntityVehicleSVA> l = atField.get(Integer.valueOf(f.id));
                    if (l == null) {
                        l = new ArrayList<EntityVehicleSVA>();
                        atField.put(Integer.valueOf(f.id), l);
                    }
                    l.add(v);
                    break;
                }
                if (v.airborneForAtc() && f.fromCore(v.posX, v.posZ) <= f.adRadius + AIR_WATCH) {
                    air(v, now, true);                              // on its way in (outside the field's box): the logbook's memory
                    break;
                }
            }
        }
        for (EmbassyFieldSVA f : fields) {
            List<EntityVehicleSVA> l = atField.get(Integer.valueOf(f.id));
            if (l != null) {
                for (EntityVehicleSVA v : new ArrayList<EntityVehicleSVA>(l)) {
                    look(w, f, v, d, l, now, today);
                }
            }
        }
        books(w, fields, d, loaded);
        d.pruneLeft(today);
        for (Iterator<Map.Entry<String, Mem>> it = mem.entrySet().iterator(); it.hasNext();) {
            Mem m = it.next().getValue();
            if (now - m.seen > EmbassyServiceMath.LANDED_WINDOW + 200 || now < m.seen) {
                it.remove();
            }
        }
    }

    /** A machine in the air near an embassy (`outside` its field's zone box): remembered with its pilot, its transponder, its flight in. */
    private Mem air(EntityVehicleSVA v, long now, boolean outside) {
        Mem m = mem(v, now);
        m.lastAir = now;
        m.arrived = EmbassyServiceMath.arrival(m.arrived, true, outside, now);
        m.xpdr = AirDefenseSVA.xpdrOn(v);
        EntityPlayer pl = AtcServerSVA.pilotOf(v);                  // the liner's first officer's seat too
        m.pilot = pl instanceof EntityPlayerMP && real((EntityPlayerMP) pl) ? uuid(pl) : "";
        m.area = EmbassyServiceMath.AREA_NONE;
        m.since = -1;
        m.overSince = -1;
        return m;
    }

    /** One machine at field `f`: the logbook, the stand's books and its fee, the towing. */
    private void look(World w, EmbassyFieldSVA f, EntityVehicleSVA v, EmbassyParkingDataSVA d, List<EntityVehicleSVA> others, long now, long today) {
        if (v.airborneForAtc()) {
            int bx = MathHelper.floor_double(v.posX), bz = MathHelper.floor_double(v.posZ);
            air(v, now, !EmbassyServiceMath.inGrown(f.zoneBox(), bx, bz, 0) && !f.inDome(v.posX, v.posZ));
            return;
        }
        Mem m = mem(v, now);
        String vid = AirDefenseSVA.vehKey(v), owner = ownerOf(v);
        boolean stopped = v.measuredSpeed() < EmbassyServiceMath.STOPPED, manned = AirDefenseSVA.manned(v);
        int spot = f.spotAt(v.posX, v.posZ);
        // the «Лётная книжка»: stopped on a stand / «Н» soon after a flight IN, the transponder answering
        if (stopped && spot >= 0 && (m.lastAir > 0 || m.arrived > 0)) {
            if (EmbassyServiceMath.landed(m.arrived, now, AirDefenseSVA.xpdrOn(v) || m.xpdr)) {
                EntityPlayerMP pilot = online(m.pilot);
                EntityPlayer seat = pilot == null ? AtcServerSVA.pilotOf(v) : null;
                if (seat instanceof EntityPlayerMP && real((EntityPlayerMP) seat)) {
                    pilot = (EntityPlayerMP) seat;
                }
                if (pilot != null && pilot.worldObj == w) {
                    EmbassyCompatSVA.firstLanding(pilot, f.id);     // the core gives it once an embassy a player (and says so)
                }
            }
            m.lastAir = 0;
            m.arrived = 0;
        }
        // a rider takes it off the lot (the stay's days remembered: back on a stand, the same stay)
        EmbassyParkingDataSVA.Stand lot = d.lotOf(vid);
        if (lot != null && (manned || lot.embassy != f.id)) {
            if (lot.embassy == f.id) {
                d.leave(vid, today);
            } else {
                d.release(vid);
            }
            lot = null;
        }
        // the stand's books
        EmbassyParkingDataSVA.Stand st = d.standOf(vid);
        if (st != null && (st.embassy != f.id || st.spot != spot)) {
            if (st.embassy == f.id) {
                d.leave(vid, today);                                // it left its stand here (or stands on another one now): the stay goes on
            } else {
                d.release(vid);
            }
            st = null;
        }
        // review 2026-10-27: a machine given to another player - its place is his (at his limit: the limit's case below)
        if (st != null && !st.owner.equals(owner)) {
            if (EmbassyServiceMath.mayPark(d.heldBy(f.id, owner, vid))) {
                d.setOwner(st, owner);
            } else {
                d.leave(vid, today);
                st = null;
            }
        }
        if (lot != null && !lot.owner.equals(owner)) {
            d.setOwner(lot, owner);
        }
        // a stopped machine on a free stand of its kind: its owner's (not one the lot holds - towed there, or the lot's last place «Н»);
        // review 2026-10-27: on one it does not fit, or another machine's - a stand it does not hold (AREA_SPOT: towed below)
        boolean wrongSpot = false;
        if (st == null && lot == null && spot >= 0 && stopped && (!EmbassyServiceMath.fits(v.getVehicleType(), f.spotKind(spot))
                || d.stand(f.id, spot) != null)) {
            wrongSpot = true;
        } else if (st == null && lot == null && spot >= 0 && stopped) {
            EmbassyParkingDataSVA.Stand holder = d.stand(f.id, spot);
            if (holder == null) {
                if (EmbassyServiceMath.mayPark(d.heldBy(f.id, owner, vid))) {
                    st = d.take(f.id, spot, vid, owner, now, today);
                    m.overSince = -1;
                    tellOwner(owner, EnumChatFormatting.AQUA, "sva.emb.msg.parked", name(v), new ChatComponentTranslation(f.spotKey(spot)), f.name());
                } else {
                    if (m.overSpot != spot) {
                        m.overSpot = spot;                          // once a stand it arrives at
                        tellOwner(owner, EnumChatFormatting.YELLOW, "sva.emb.msg.limit", Integer.valueOf(EmbassyServiceMath.SPOTS_PER_PLAYER));
                    }
                    if (manned) {
                        m.overSince = -1;
                    } else if (m.overSince < 0) {
                        m.overSince = now;
                    } else if (now - m.overSince >= LIMIT_GRACE) {
                        moveToLot(w, f, v, d, others, owner, now, today, "limit");
                        return;
                    }
                }
            }
        }
        if (spot < 0) {
            m.overSpot = -1;
        }
        if (lot != null && lot.paidDay < today) {
            EntityPlayerMP op = online(owner);
            if (op != null && EmbassyCompatSVA.hasTalon(op, f.id)) {
                d.paid(lot, today);                                 // the talon's days are free on the lot too
            }
        }
        if (st != null && st.embassy == f.id) {
            EntityPlayerMP op = online(owner);
            if (st.paidDay < today && op != null && EmbassyCompatSVA.hasTalon(op, f.id)) {
                d.paid(st, today);                                  // the talon's days are free
            }
            int due = EmbassyServiceMath.parkDaysDue(st.sinceDay, st.paidDay, today);
            if (EmbassyServiceMath.debtToLot(due) && !manned) {
                moveToLot(w, f, v, d, others, owner, now, today, "debt");
                return;
            }
            if (due > 0 && m.toldDay != today && op != null) {
                m.toldDay = today;
                tellOwner(owner, EnumChatFormatting.YELLOW, "sva.emb.msg.debt", name(v), new ChatComponentTranslation(f.spotKey(st.spot)),
                        unit("day", due), f.name());
            }
        }
        // the towing: nobody aboard, standing still on the runway / a pad / the taxiway, under the dome, on a stand it does not hold, on
        // the apron off the stands (FULL; not in the hangar, not on the lot)
        if (!ConfigSVA.aptEmbassyTowing || manned || !stopped || v.isAutoOn()) {
            m.area = EmbassyServiceMath.AREA_NONE;
            m.since = -1;
            return;
        }
        boolean apron = spot < 0 && st == null && lot == null && EmbassyFieldSVA.in(f.apronBox(), v.posX, v.posZ) && !inHangar(f, v);
        int area = EmbassyServiceMath.area(f.inDome(v.posX, v.posZ), EmbassyFieldSVA.in(f.stripBox(), v.posX, v.posZ),
                EmbassyFieldSVA.in(f.padBox(true), v.posX, v.posZ) || EmbassyFieldSVA.in(f.padBox(false), v.posX, v.posZ),
                EmbassyFieldSVA.in(f.taxiBox(), v.posX, v.posZ), wrongSpot, apron);
        if (area != m.area || m.since < 0) {
            m.area = area;
            m.since = area == EmbassyServiceMath.AREA_NONE ? -1 : now;
            return;
        }
        if (!EmbassyServiceMath.towDue(m.since, now, EmbassyServiceMath.towMinutes(area, ConfigSVA.aptEmbassyTowMinutes,
                ConfigSVA.aptEmbassyDomeMinutes, ConfigSVA.aptEmbassyApronMinutes))) {
            return;
        }
        if (area != EmbassyServiceMath.AREA_DOME && EmbassyServiceMath.mayPark(d.heldBy(f.id, owner, vid))) {
            int to = EmbassyServiceLotSVA.freeStand(f, v, vid, d, others, -1);
            if (to >= 0 && EmbassyServiceLotSVA.toStand(w, f, v, to)) {
                d.take(f.id, to, vid, owner, now, today);
                forgetTimers(vid);
                tellOwner(owner, EnumChatFormatting.AQUA, "sva.emb.msg.towedStand", name(v), new ChatComponentTranslation(f.spotKey(to)), f.name());
                return;
            }
        }
        moveToLot(w, f, v, d, others, owner, now, today, area == EmbassyServiceMath.AREA_DOME ? "dome" : area == EmbassyServiceMath.AREA_SPOT ? "spot"
                : area == EmbassyServiceMath.AREA_APRON ? "apron" : "strip");
    }

    /** `v` to the «Отстой» lot: booked there (its stay's days with it), its stand freed, its owner told (`why`: strip, dome, debt, limit, spot, apron). */
    private void moveToLot(World w, EmbassyFieldSVA f, EntityVehicleSVA v, EmbassyParkingDataSVA d, List<EntityVehicleSVA> others,
            String owner, long now, long today, String why) {
        if (!EmbassyServiceLotSVA.toLot(w, f, v, others)) {
            return;                                                 // nowhere it fits now: the next look tries again
        }
        String vid = AirDefenseSVA.vehKey(v);
        d.toLot(f.id, vid, owner, now, today);
        forgetTimers(vid);
        tellOwner(owner, EnumChatFormatting.YELLOW, "sva.emb.msg.towedLot", name(v), f.name(), new ChatComponentTranslation("sva.emb.msg.why." + why));
    }

    /** The books' records of machines no longer there go (never a machine). */
    private void books(World w, List<EmbassyFieldSVA> fields, EmbassyParkingDataSVA d, Map<String, EntityVehicleSVA> loaded) {
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        for (EmbassyParkingDataSVA.Stand s : d.all()) {
            EmbassyFieldSVA f = null;
            for (EmbassyFieldSVA x : fields) {
                if (x.id == s.embassy) {
                    f = x;
                }
            }
            if (f == null) {
                continue;                                           // its field is not there now (another core, a test): kept
            }
            EntityVehicleSVA v = loaded.get(s.vid);
            int[] box = s.spot >= 0 && s.spot < f.spots() ? f.spotBox(s.spot) : f.zoneBox();
            if (v != null) {
                if (!EmbassyServiceLotSVA.at(f, v)) {
                    d.release(s.vid);                               // loaded elsewhere: it left (at the field look() keeps the books)
                }
                continue;
            }
            VehicleRegistrySVA.Entry c = reg == null ? null : reg.entry(s.vid);
            if (c == null || c.placed && (c.dim != f.dim
                    || !EmbassyServiceMath.inGrown(box, MathHelper.floor_double(c.x), MathHelper.floor_double(c.z), EmbassyServiceMath.LEFT_STAND))) {
                d.release(s.vid);                                   // picked up, destroyed, or last seen far from here
            }
        }
    }

    /** The server stopped: the timers in memory go (the stands' books stay in EmbassyParkingDataSVA). */
    public void clear() {
        windows.clear();
        mem.clear();
        ticks = 0;
    }

    // ------------------------------------------------------------------ world tests (package D)

    /** World tests: one look now (the stands, the towing, the logbook), whatever the tick counter says. */
    public void debugTick() {
        ticks = TICK_EVERY - 1;
        onTick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
    }

    /** World tests: machine `v`'s towing timer (and the stands' limit's grace) started `ticks` earlier than it did. */
    public void debugAge(EntityVehicleSVA v, long ticks) {
        Mem m = v == null ? null : mem.get(AirDefenseSVA.vehKey(v));
        if (m != null) {
            if (m.since >= 0) {
                m.since -= ticks;
            }
            if (m.overSince >= 0) {
                m.overSince -= ticks;
            }
        }
    }

    /** World tests: machine `v` was in the air `ago` ticks ago, flown by `pilot` with the transponder `xpdr` (the logbook's memory). */
    public void debugAir(EntityVehicleSVA v, EntityPlayer pilot, boolean xpdr, long ago) {
        if (v != null && v.worldObj != null) {
            long now = v.worldObj.getTotalWorldTime();
            Mem m = mem(v, now);
            m.lastAir = Math.max(1, now - ago);
            m.arrived = m.lastAir;                                  // a flight in
            m.pilot = pilot == null ? "" : uuid(pilot);
            m.xpdr = xpdr;
        }
    }

    /** World tests: the logbook's memory of machine `v` - {in the air last (0 none), its transponder then (1 / 0), a pilot known (1 / 0)}; null none. */
    public long[] debugLastAir(EntityVehicleSVA v) {
        Mem m = v == null ? null : mem.get(AirDefenseSVA.vehKey(v));
        return m == null ? null : new long[] {m.lastAir, m.xpdr ? 1 : 0, m.pilot.length() > 0 ? 1 : 0};
    }

    /** World tests: the window's snapshot for `p` (as openService sends it; nothing remembered). */
    public NBTTagCompound debugSnapshot(EntityPlayerMP p, EmbassyFieldSVA f, int column) {
        return p == null || f == null ? null : snapshot(p, f, column, true);
    }

    /** World tests: the window the server keeps open for `p` ({id, column}; null none). */
    public int[] debugWindow(EntityPlayer p) {
        int[] w = p == null ? null : windows.get(uuid(p));
        return w == null ? null : w.clone();
    }

    /** World tests: open the window's books for a player without a connection (the world test's StationPlayer - no packet sent). */
    public void debugOpen(EntityPlayer p, int id, int column) {
        if (p != null) {
            windows.put(uuid(p), new int[] {id, column});
        }
    }

    // ------------------------------------------------------------------ inside

    private Mem mem(EntityVehicleSVA v, long now) {
        String k = AirDefenseSVA.vehKey(v);
        Mem m = mem.get(k);
        if (m == null) {
            m = new Mem();
            mem.put(k, m);
        }
        m.seen = now;
        return m;
    }

    private void forgetTimers(String vid) {
        Mem m = mem.get(vid);
        if (m != null) {
            m.area = EmbassyServiceMath.AREA_NONE;
            m.since = -1;
            m.overSince = -1;
        }
    }

    /** The virtual tower of field `f` (A's EmbassyAirportSVA; null: none yet). */
    static AirportDataSVA.Tower towerOf(World w, EmbassyFieldSVA f) {
        AirportDataSVA d = AirportDataSVA.get(w);
        if (d == null || f == null) {
            return null;
        }
        String k = EmbassyAirportSVA.INSTANCE.towerKey(f.id);
        AirportDataSVA.Tower t = k == null ? null : d.tower(k);
        if (t != null) {
            return t;
        }
        for (AirportDataSVA.Tower x : d.towers()) {
            if (EmbassyAirportSVA.embassyOf(x.key) == f.id) {
                return x;
            }
        }
        return null;
    }

    /** The tab's tower row: «XTAA: в сети, ВПП 36, ILS CAT III» (HELI: the helipad). */
    private static IChatComponent towerRow(World w, EmbassyFieldSVA f) {
        AirportDataSVA.Tower t = towerOf(w, f);
        AirportDataSVA d = AirportDataSVA.get(w);
        String icao = t != null && t.icao.length() > 0 ? t.icao : f.icao;
        boolean online = t != null && t.online(w.getTotalWorldTime());
        IChatComponent state = new ChatComponentTranslation(online ? "sva.emb.tab.online" : "sva.emb.tab.offline");
        if (!f.hasRunway()) {
            return new ChatComponentTranslation("sva.emb.tab.towerHeli", icao, state);
        }
        String rwy = "-";
        int cat = 0;
        if (t != null) {
            for (IlsPairing.Rwy r : t.rwys) {
                if (r.a.key.equals(t.inUse)) {
                    rwy = r.nameA;
                } else if (r.b.key.equals(t.inUse)) {
                    rwy = r.nameB;
                }
            }
            if (d != null) {
                for (ApproachMath.Approach a : d.approaches(t)) {
                    cat = Math.max(cat, a.cat);
                }
            }
        }
        return new ChatComponentTranslation("sva.emb.tab.tower", icao, state, rwy, cat > 0 ? IlsCat.catText(cat) : "-");
    }

    /** The tab's price of one unit of service `s` («1 КЧ» / the refusal's short line). */
    private static IChatComponent priceText(EntityPlayer p, EmbassyFieldSVA f, int s) {
        int q = EmbassyCompatSVA.quote(p, f.id, EmbassyCompatSVA.SERVICE_KEYS[s], EmbassyServiceMath.UNIT[s], false);
        if (q >= 0) {
            return new ChatComponentTranslation("sva.emb.tab.chips", Integer.valueOf(q));
        }
        if (q == -EmbassyCompatSVA.PAY_LEVEL) {
            int lv = EmbassyServiceMath.MIN_LEVEL[s];
            return new ChatComponentTranslation("sva.emb.tab.fromLevel", new ChatComponentTranslation(levelKey(lv)));
        }
        return new ChatComponentTranslation("sva.emb.tab.no");
    }

    /** The tab's state of a stand: the free first day, the talon, paid, or the days owed. */
    private static IChatComponent standState(EntityPlayer p, EmbassyFieldSVA f, EmbassyParkingDataSVA.Stand s, long today) {
        if (EmbassyCompatSVA.hasTalon(p, f.id)) {
            return new ChatComponentTranslation("sva.emb.tab.talon");
        }
        int due = owed(s, today);
        if (due > 0) {
            return new ChatComponentTranslation("sva.emb.tab.due", Integer.valueOf(due));
        }
        return new ChatComponentTranslation(EmbassyServiceMath.freeDay(s.sinceDay, today) ? "sva.emb.tab.free" : "sva.emb.tab.paid");
    }

    /** «Самолёт 12» of a booked machine (its registry card; unknown - «?»). */
    private static IChatComponent machineName(VehicleRegistrySVA reg, String vid) {
        VehicleRegistrySVA.Entry c = reg == null ? null : reg.entry(vid);
        if (c == null) {
            return new ChatComponentText("?");
        }
        return new ChatComponentTranslation("sva.emb.msg.machine", new ChatComponentTranslation("item.siliconagevehicles.vehicle."
                + VehicleType.byOrdinal(c.type).key + ".name"), SkinMath.boardText(c.board));
    }

    /** «Самолёт 12» of a loaded machine. */
    private static IChatComponent name(EntityVehicleSVA v) {
        return new ChatComponentTranslation("sva.emb.msg.machine", new ChatComponentTranslation("item.siliconagevehicles.vehicle."
                + v.getVehicleType().key + ".name"), SkinMath.boardText(v.getBoardNumber()));
    }

    /** An amount in a unit: sva.emb.gui.unit.<eu|mb|pct|day|one> (digits grouped by a plain space - FormatSVA.group). */
    static IChatComponent unit(String u, long n) {
        return new ChatComponentTranslation("sva.emb.gui.unit." + u, FormatSVA.group(n));
    }

    private static int service(String key) {
        for (int i = 0; key != null && i < EmbassyCompatSVA.SERVICE_KEYS.length; i++) {
            if (EmbassyCompatSVA.SERVICE_KEYS[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }

    static String payKey(int code) {
        return "sc.airfield.pay." + EmbassyCompatSVA.PAY_KEYS[code >= 0 && code < EmbassyCompatSVA.PAY_KEYS.length ? code : EmbassyCompatSVA.PAY_UNKNOWN];
    }

    static String levelKey(int level) {
        return "sc.settle.rep." + EmbassyCompatSVA.LEVEL_KEYS[level >= 0 && level < EmbassyCompatSVA.LEVEL_KEYS.length ? level : EmbassyCompatSVA.STRANGER];
    }

    private static void refuse(EntityPlayerMP p, int code) {
        IChatComponent c = new ChatComponentTranslation(payKey(code));
        c.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.YELLOW));
        p.addChatComponentMessage(c);
    }

    private static void tell(EntityPlayer p, EnumChatFormatting colour, String key, Object... args) {
        if (p == null || p instanceof FakePlayer) {
            return;
        }
        IChatComponent c = new ChatComponentTranslation(key, args);
        c.setChatStyle(new ChatStyle().setColor(colour));
        p.addChatComponentMessage(c);
    }

    /** A line to a machine's owner when he is on the server (anywhere). */
    private static void tellOwner(String owner, EnumChatFormatting colour, String key, Object... args) {
        tell(online(owner), colour, key, args);
    }

    /** The player of UUID `u` on the server now (null: offline, none). */
    private static EntityPlayerMP online(String u) {
        MinecraftServer s = MinecraftServer.getServer();
        if (u == null || u.length() == 0 || s == null || s.getConfigurationManager() == null) {
            return null;
        }
        for (Object o : s.getConfigurationManager().playerEntityList) {
            if (o instanceof EntityPlayerMP && u.equals(((EntityPlayerMP) o).getUniqueID().toString())) {
                return real((EntityPlayerMP) o) ? (EntityPlayerMP) o : null;
            }
        }
        return null;
    }

    /** The owner's UUID of a machine ("" nobody's). */
    private static String ownerOf(EntityVehicleSVA v) {
        return v.state.ownerId == null ? "" : v.state.ownerId;
    }

    private static String uuid(EntityPlayer p) {
        return p.getUniqueID().toString();
    }

    /** A real player (never a FakePlayer: it gets no packets, the core serves it nothing; NetAptSVA.send skips one without a connection). */
    private static boolean real(EntityPlayerMP p) {
        return p != null && !(p instanceof FakePlayer);
    }
}
