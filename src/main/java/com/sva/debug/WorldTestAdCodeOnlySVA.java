package com.sva.debug;

import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.AirportCodeMath;
import com.sva.airdefense.CodeOnlyMath;
import com.sva.airdefense.XpdrMath;
import com.sva.block.BlockAirDefenseSVA;
import com.sva.block.TileEntityAirDefenseSVA;
import com.sva.block.TileEntityCommandPostSVA;
import com.sva.entity.EntitySeatSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemCodeCardSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerView;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * Code-only wave world test (docs/proposal-ad-strict-iff.md) - the core: the key, the cards, the transponder. Run by
 * WorldTestSVA alongside its other phases from tick START in its own area (x spawn + 11800, z spawn - 11800, a stone floor
 * at Y 150), owner «TestADQ»:
 * <ul>
 * <li>item 35: the post's «set» drops 7000 / 7700 from its own codes, its trust data never takes them;</li>
 * <li>item 36: the day code is the secret + the server's pepper + the key generation on the key's clock; the pepper is not
 * in the post's picture (what its screens get);</li>
 * <li>item 19: /time set does not change the key period (the total world time);</li>
 * <li>item 17: a stack of cards right-clicked on the post - all printed with the key (no code), the log «... напечатал карт
 * кодов: 16»;</li>
 * <li>items 18, 20, 30: the pilot's card sets the day code (cardCode, and the server's own tick), the КЛЮЧ ДНЯ lamp; taken
 * away - back to 7000; «Сменить ключ» - the old card gives nothing, a new one the new code; the liner's F/O flying alone -
 * his card works, a passenger's never; an older card (its own code) sets nothing, even on its day (review 2026-10-27,
 * batch 2: no КЛЮЧ ДНЯ lamp); a running card's code is not replaced by a tower (mayReplace on the key's clock).</li>
 * </ul>
 */
final class WorldTestAdCodeOnlySVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000adf0", OWNER_NAME = "TestADQ";
    private static final String SECRET = "coKey";
    static final int START = 3100;
    private static final int Y = 150, MAX_TICKS = 300;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    boolean done;
    private TileEntityCommandPostSVA kp;
    private EntityVehicleSVA plane, liner;
    private FakePlayer owner, pilot, fo, pax;
    private int tickCode;

    WorldTestAdCodeOnlySVA(WorldTestSVA t) {
        this.t = t;
    }

    private void check(boolean ok, String name) {
        t.check(ok, name);
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = kp == null || plane == null || liner == null ? 9 : 1;
        }
        int k = ticks - t0;
        if (kp != null && phase < 9) {
            kp.fillForTest();
        }
        if (plane != null && !plane.isDead) {
            plane.setPosition(x0 + 20.5, Y + 30, z0 + 0.5);
            plane.motionX = plane.motionY = plane.motionZ = 0;
        }
        if (phase == 1 && k >= 5) {
            ownCodes(w);
            pepperAndClock(w);
            printing(w);
            cards(w);
            phase = 2;
            tickCode = k;
        } else if (phase == 2 && k >= tickCode + 45) {
            serverTick(w);
            liner(w);
            legacy(w);
            phase = 9;
        } else if (phase < 9 && k > MAX_TICKS) {
            check(false, "ad code-only world: every stage finished in time (stopped in stage " + phase + ")");
            phase = 9;
        }
        if (phase == 9) {
            finish(w);
        }
    }

    // ---------------------------------------------------------------- items 35, 36, 19

    private void ownCodes(World w) {
        NBTTagCompound d = new NBTTagCompound();
        d.setString("Op", "set");
        d.setString("Name", "CO");
        d.setBoolean("DayKey", true);
        d.setIntArray("Codes", new int[] {07000, 01234, 07700, 0});
        kp.command(owner, d);
        AirDefenseDataSVA dd = AirDefenseDataSVA.get(w);
        AirDefenseDataSVA.KpInfo i = dd == null ? null : dd.info(kp.key(), false);
        check(kp.ownCode(01234) && !kp.ownCode(07000) && !kp.ownCode(07700) && !kp.ownCode(0) && i != null && i.ownCode(01234) && !i.ownCode(07000),
                "ad code-only world (item 35): the post's «set» keeps 1234 and drops 7000 / 7700 / 0000 from its own codes (its trust data too)");
    }

    private void pepperAndClock(World w) {
        AirDefenseDataSVA dd = AirDefenseDataSVA.get(w);
        if (dd == null) {
            check(false, "ad code-only world: the air defence's saved data");
            return;
        }
        String pep = dd.pepper();
        long period = AirDefenseDataSVA.keyPeriod(w);
        int dc = kp.dayCode();
        check(pep.length() == 32 && dc >= 0 && dc == XpdrMath.dayCode(SECRET, pep, dd.gen(OWNER_NAME), period) && dc == AirDefenseDataSVA.keyCodeNow(w, OWNER_NAME)
                && period == w.getTotalWorldTime() / XpdrMath.PERIOD_TICKS,
                "ad code-only world (items 19, 36): the day code - the secret, the server's pepper, the key generation, the period of the total world time");
        NBTTagCompound snap = kp.snapshot();
        check(!snap.toString().contains(pep) && snap.getLong("Day") == period && snap.getInteger("KeyLeft") == (int) AirDefenseDataSVA.keyLeft(w)
                && snap.getInteger("KeyGen") == dd.gen(OWNER_NAME) && snap.getInteger("CardPeriods") == CodeOnlyMath.DEFAULT_CARD_PERIODS,
                "ad code-only world (item 36): the pepper never goes to a screen; the picture has the key period, the time to the next one, the generation");
        long wt = w.getWorldTime();
        w.setWorldTime(wt + 5 * 24000L + 7000);                    // /time set and back within the tick (the other tests share the clock)
        boolean same = AirDefenseDataSVA.keyPeriod(w) == period && kp.dayCode() == dc;
        w.setWorldTime(wt);
        check(same, "ad code-only world (item 19): /time set does not change the key period nor the day code");
    }

    // ---------------------------------------------------------------- item 17: printing a stack

    private void printing(World w) {
        ItemStack stack = new ItemStack(ModContentSVA.codeCard, 16);
        boolean ok = kp.printCard(owner, stack);
        AirDefenseDataSVA dd = AirDefenseDataSVA.get(w);
        boolean logged = false;
        for (String[] l : kp.logLines()) {
            logged |= l.length > 3 && "sva.ad.log.printedN".equals(l[1]) && OWNER_NAME.equals(l[2]) && "16".equals(l[3]);
        }
        check(ok && stack.stackSize == 16 && ItemCodeCardSVA.keyed(stack) && !stack.getTagCompound().hasKey(ItemCodeCardSVA.K_CODE)
                && OWNER_NAME.equals(ItemCodeCardSVA.owner(stack)) && dd != null && ItemCodeCardSVA.gen(stack) == dd.gen(OWNER_NAME)
                && ItemCodeCardSVA.first(stack) == AirDefenseDataSVA.keyPeriod(w) && ItemCodeCardSVA.periods(stack) == 3 && logged,
                "ad code-only world (item 17): a stack of 16 printed at once - whose key, generation, this period, 3 periods, no code; the log says who and how many");
    }

    // ---------------------------------------------------------------- items 18, 20, 30: the card in the cockpit

    private static void give(FakePlayer p, ItemStack s) {
        for (int i = 0; i < p.inventory.mainInventory.length; i++) {
            p.inventory.mainInventory[i] = null;
        }
        p.inventory.mainInventory[3] = s;
    }

    private ItemStack card() {
        ItemStack c = new ItemStack(ModContentSVA.codeCard);
        kp.printCardForTest(c);
        return c;
    }

    private void cards(World w) {
        XpdrMath.Xpdr x = plane.state.xpdr;
        long period = AirDefenseDataSVA.keyPeriod(w);
        give(pilot, card());
        ride(pilot, plane);
        AirDefenseSVA.INSTANCE.debugCardCode(plane);
        int dc = kp.dayCode();
        check(x.code == dc && x.fromCard() && x.keyLamp(period) && OWNER_NAME.equals(x.cardOwner) && x.cardUntil == period + 3
                && !AirportCodeMath.mayReplace(x.code, x.keyLamp(AirDefenseDataSVA.keyPeriod(w))),
                "ad code-only world (items 18, 20): the pilot's card sets the day code, the КЛЮЧ ДНЯ lamp lit, a tower may not replace it");
        give(pilot, null);
        AirDefenseSVA.INSTANCE.debugCardCode(plane);
        check(x.code == XpdrMath.VFR && !x.fromCard() && "".equals(x.cardOwner) && !x.keyLamp(period),
                "ad code-only world (item 30): the card taken away (a hijacker without it) - the code back to 7000 within a second");
        ItemStack old = card();
        AirDefenseDataSVA dd = AirDefenseDataSVA.get(w);
        // review 2026-10-27 (batch 3): from the generation before (the owner's key stays in the save - a rerun starts at 2)
        int g0 = dd == null ? -9 : dd.gen(OWNER_NAME);
        int gen = dd == null ? 0 : dd.rekey(OWNER_NAME);
        give(pilot, old);
        AirDefenseSVA.INSTANCE.debugCardCode(plane);
        boolean voided = x.code == XpdrMath.VFR && !x.fromCard();
        int dc2 = kp.dayCode();
        ItemStack fresh = card();
        give(pilot, fresh);
        AirDefenseSVA.INSTANCE.debugCardCode(plane);
        check(gen == g0 + 1 && voided && ItemCodeCardSVA.gen(fresh) == gen && x.code == dc2 && x.cardGen == gen && dc2 >= 0,
                "ad code-only world (item 24): «Сменить ключ» - the card printed before gives nothing, a new one the new generation's code");
        // the server's own tick (every second) sets it too: the code dropped by hand to 7000 with the key on
        x.setCode(XpdrMath.VFR, w.getTotalWorldTime());
        x.cardDay = -1;
    }

    private void serverTick(World w) {
        XpdrMath.Xpdr x = plane.state.xpdr;
        check(x.code == kp.dayCode() && x.fromCard(), "ad code-only world: the server's tick (once a second) set the card's code by itself");
        ride(pilot, null);
        // review 2026-10-27 (batch 3): a thief boards the keyed aircraft - the 10-tick copy between two card rounds runs the
        // card round first (the holders changed): the code he is sent is 7000, not the key's
        give(pax, null);
        ride(pax, plane);
        AirDefenseSVA.INSTANCE.debugRiderRound(plane, true, false);
        String sent = AirDefenseSVA.INSTANCE.debugXpdrSent(plane);
        boolean reset = x.code == XpdrMath.VFR && !x.fromCard() && sent != null && sent.startsWith(x.mode + ":" + XpdrMath.VFR + ":");
        ride(pax, null);
        check(reset, "ad code-only world (review batch 3, item 30): a thief boarding - the card round runs before the panel's copy goes, 7000 sent ("
                + XpdrMath.format(x.code) + ", " + sent + ")");
    }

    // ---------------------------------------------------------------- item 20: the liner's F/O, a passenger

    private void liner(World w) {
        EntitySeatSVA foSeat = liner.seatAt(LinerView.FO_SEAT), paxSeat = liner.seatAt(5);
        if (foSeat == null || paxSeat == null) {
            check(false, "ad code-only world (item 20): the liner's seats are there");
            return;
        }
        XpdrMath.Xpdr x = liner.state.xpdr;
        x.mode = XpdrMath.C;
        x.dayKey = true;
        x.setCode(XpdrMath.VFR, w.getTotalWorldTime());
        give(pax, card());
        ride(pax, paxSeat);
        AirDefenseSVA.INSTANCE.debugCardCode(liner);
        boolean paxNo = x.code == XpdrMath.VFR && !x.fromCard() && AirDefenseSVA.cardHolders(liner).isEmpty();
        give(fo, card());
        ride(fo, foSeat);
        AirDefenseSVA.INSTANCE.debugCardCode(liner);
        boolean foYes = x.code == kp.dayCode() && x.fromCard() && AirDefenseSVA.cardHolders(liner).size() == 1;
        check(paxNo && foYes, "ad code-only world (item 20): the liner's F/O flying alone - his card works (" + foYes + "); a passenger's never (" + paxNo + ")");
        ride(fo, null);
        ride(pax, null);
    }

    // ---------------------------------------------------------------- an older card

    private void legacy(World w) {
        XpdrMath.Xpdr x = plane.state.xpdr;
        ItemStack old = new ItemStack(ModContentSVA.codeCard);
        ItemCodeCardSVA.printLegacy(old, "OLD", XpdrMath.day(w.getWorldTime()), 01234);
        ItemStack stale = new ItemStack(ModContentSVA.codeCard);
        ItemCodeCardSVA.printLegacy(stale, "OLD", XpdrMath.day(w.getWorldTime()) + 3, 02345);
        give(pilot, stale);
        ride(pilot, plane);
        x.setCode(XpdrMath.VFR, w.getTotalWorldTime());
        AirDefenseSVA.INSTANCE.debugCardCode(plane);
        boolean staleNo = x.code == XpdrMath.VFR;
        give(pilot, old);
        AirDefenseSVA.INSTANCE.debugCardCode(plane);
        // review 2026-10-27 (batch 2): an older card gives nothing even on its day - its code matches no post since the pepper,
        // and the КЛЮЧ ДНЯ lamp it lit kept a tower from giving the aircraft an airport code
        boolean lamp = x.keyLamp(AirDefenseDataSVA.keyPeriod(w));
        check(staleNo && x.code == XpdrMath.VFR && !x.fromCard() && !lamp && "".equals(x.cardOwner)
                && AirDefenseSVA.cardCodeNow(w, old) == -1,
                "ad code-only world: an older card (its own code) sets nothing even on its day - no code, no КЛЮЧ ДНЯ lamp (" + XpdrMath.format(x.code) + " "
                        + lamp + ")");
        give(pilot, null);
        ride(pilot, null);
    }

    // ---------------------------------------------------------------- the area

    private static FakePlayer player(World w, String name) {
        return FakePlayerFactory.get((WorldServer) w, new GameProfile(UUID.nameUUIDFromBytes(("sva-test:" + name).getBytes()), name));
    }

    /** Mounts / unmounts a fake player by the fields (EntityPlayerMP.mountEntity sends a packet it has no connection for). */
    private static void ride(FakePlayer p, Entity m) {
        if (p.ridingEntity != null) {
            p.ridingEntity.riddenByEntity = null;
        }
        p.ridingEntity = m;
        if (m != null) {
            m.riddenByEntity = p;
        }
    }

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = (sp.posX + 11800) >> 4 << 4;
        z0 = (sp.posZ - 11800) >> 4 << 4;
        keepLoaded(w);
        area(w, true);
        owner = player(w, OWNER_NAME);
        pilot = player(w, "TestADQpilot");
        fo = player(w, "TestADQfo");
        pax = player(w, "TestADQpax");
        BlockAirDefenseSVA.build(w, x0, Y + 1, z0, (BlockAirDefenseSVA) ModContentSVA.commandPost, 2, 2);
        TileEntity te = w.getTileEntity(x0, Y + 1, z0);
        if (te instanceof TileEntityAirDefenseSVA) {
            ((TileEntityAirDefenseSVA) te).setOwnerName(OWNER_NAME);
        }
        kp = te instanceof TileEntityCommandPostSVA ? (TileEntityCommandPostSVA) te : null;
        if (kp != null) {
            kp.debugSetup(SECRET, null, 0, null);
        }
        VehicleState st = new VehicleState(VehicleType.PLANE);
        st.energy = st.maxEnergy();
        plane = ItemVehicleSVA.spawn(w, ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.PLANE.ordinal()), st),
                x0 + 20.5, Y + 30, z0 + 0.5, 0F, OWNER, OWNER_NAME);
        if (plane != null) {
            plane.state.xpdr.mode = XpdrMath.C;
            plane.state.xpdr.dayKey = true;
        }
        VehicleState ls = new VehicleState(VehicleType.LINER);
        ls.fillAllGases();
        ls.energy = ls.maxEnergy();
        liner = ItemVehicleSVA.spawn(w, ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.LINER.ordinal()), ls),
                x0 + 0.5, Y + 1, z0 + 30.5, -90F, OWNER, OWNER_NAME);
        if (liner != null) {
            liner.debugEngineManual = true;
            liner.state.liner().set(LinerControls.BAT1, 1);
            liner.state.liner().set(LinerControls.BAT2, 1);
        }
        check(kp != null && plane != null && liner != null, "ad code-only world: a command post, a plane and a liner placed");
    }

    private void area(World w, boolean build) {
        for (int x = x0 - 24; x <= x0 + 40; x++) {
            for (int z = z0 - 24; z <= z0 + 48; z++) {
                for (int y = Y + 1; y <= Y + 14; y++) {
                    if (w.getBlock(x, y, z) != Blocks.air) {
                        w.setBlock(x, y, z, Blocks.air, 0, 2);
                    }
                }
                w.setBlock(x, Y, z, build ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
    }

    private void keepLoaded(World w) {
        if (!(w instanceof WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        ChunkProviderServer cp = ((WorldServer) w).theChunkProviderServer;
        for (int cx = (x0 - 40) >> 4; cx <= (x0 + 56) >> 4; cx++) {
            for (int cz = (z0 - 40) >> 4; cz <= (z0 + 64) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    private void finish(World w) {
        for (FakePlayer p : new FakePlayer[] {pilot, fo, pax}) {
            if (p != null) {
                give(p, null);
                ride(p, null);
            }
        }
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {plane, liner}) {
            if (v != null && !v.isDead) {
                v.dismountAll();
                v.setDead();
            }
        }
        if (kp != null) {
            kp.noDrop = true;
        }
        if (x0 != 0 || z0 != 0) {
            keepLoaded(w);
            area(w, false);
        }
        AirDefenseDataSVA dd = AirDefenseDataSVA.get(w);
        if (dd != null && kp != null) {
            dd.removeKp(kp.key());
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        if (reg != null) {
            reg.removeOwner(OWNER);
        }
        phase = 10;
        done = true;
    }

    void abort(World w) {
        try {
            finish(w);
        } catch (Throwable t2) {
            done = true;
        }
    }
}
