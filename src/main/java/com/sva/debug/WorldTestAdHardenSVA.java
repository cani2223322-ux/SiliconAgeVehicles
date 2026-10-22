package com.sva.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.sc.init.ModItems;
import com.sc.machine.UpgradeType;
import com.sva.ConfigSVA;
import com.sva.airdefense.AdShapeMath;
import com.sva.airdefense.AdUpgradeMath;
import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.RadarMath;
import com.sva.airdefense.ZoneMath;
import com.sva.block.BlockAirDefenseSVA;
import com.sva.block.TileEntityAirDefenseSVA;
import com.sva.block.TileEntityCommandPostSVA;
import com.sva.block.TileEntityLauncherSVA;
import com.sva.block.TileEntityRadarSVA;
import com.sva.init.ModContentSVA;
import com.sva.net.NetADSVA;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.world.BlockEvent;

/**
 * Audit 2026-10-22 world test (exploits and hardening), run by WorldTestSVA alongside its other phases from tick START in its
 * own area (x spawn + 4400 chunk-aligned, z spawn - 4400, a floor at Y 150), its own survival player «SvaAdGuard»:
 * <ul>
 * <li>bug 17: the AA gun placed the way a player places it (ItemStack.tryPlaceItemIntoWorld - ForgeHooks' snapshots and
 * PlaceEvent) over the per-player limit with the placement cancelled (a claim): no item more; cancelled after it was built
 * (a protection answering only Forge's MultiPlaceEvent), airdefense.keepModules on and off: nothing drops, the stack keeps
 * its modules; a protection refusing one part's cell only: nothing built; a placement that goes in is counted at once (a
 * second one the same tick over a limit of 1 is refused); doTileDrops off: the modules drop loose instead of vanishing;</li>
 * <li>a legacy single block whose cells are another's protected ground stays compact (tryLegacy asks the protections);</li>
 * <li>bug 1 / 7.1: the command post's commands through AirDefenseSVA.command - a zone box of +-2e9 and a zone far away are
 * refused, a fourth heavy command in a tick is refused (one a second after the burst), the zones per player limit (a new
 * zone refused, an edit taken); a viewer taken off the access list stops getting the picture.</li>
 * </ul>
 */
public final class WorldTestAdHardenSVA {

    static final int START = 2100;
    private static final int Y = 150;
    static final String NAME = "SvaAdGuard", GUEST = "SvaAdGuest";
    private static final String FAKE_NODE = "test:svaAdGuard", OTHER_KP = "test:svaAdGuardKp";

    private final WorldTestSVA t;
    private int phase, t0, x0, z0, kx, kz, gx, gz;
    private TileEntityCommandPostSVA kp;
    private TileEntityRadarSVA legacy;
    private final Guard guard = new Guard();
    private boolean registered;
    boolean done;

    WorldTestAdHardenSVA(WorldTestSVA t) {
        this.t = t;
    }

    /** The test's protection: cancels the guard player's placements - all, only Forge's MultiPlaceEvent, or one cell's own event. */
    public static final class Guard {
        static final int NONE = 0, ALL = 1, MULTI = 2, CELL = 3;
        int mode;
        int[] cell;

        @SubscribeEvent
        public void onPlace(BlockEvent.PlaceEvent e) {
            if (mode == NONE || e.player == null || !NAME.equals(e.player.getCommandSenderName())) {
                return;
            }
            boolean multi = e instanceof BlockEvent.MultiPlaceEvent;
            if (mode == ALL || mode == MULTI && multi
                    || mode == CELL && !multi && cell != null && e.x == cell[0] && e.y == cell[1] && e.z == cell[2]) {
                e.setCanceled(true);
            }
        }
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        if (phase == 0) {
            t0 = ticks;
            phase = setup(w) ? 1 : 9;
        }
        int dt = ticks - t0;
        if (phase == 1 && dt >= 5) {
            placing(w);
            legacySetup(w);
            commands(w);
            phase = 2;
        }
        if (phase == 2 && dt >= 40) {
            legacyCheck(w);
            commands2(w);
            phase = 3;
        }
        if (phase == 3 && dt >= 75) {
            commands3(w);
            phase = 9;
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private static FakePlayer player(World w, String name) {
        FakePlayer p = FakePlayerFactory.get((WorldServer) w, new GameProfile(UUID.nameUUIDFromBytes(("sva-test:" + name).getBytes()), name));
        p.capabilities.isCreativeMode = false;
        p.rotationYaw = 0F;
        return p;
    }

    private boolean setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = (sp.posX + 4400) >> 4 << 4;
        z0 = sp.posZ - 4400;
        keepLoaded(w);
        area(w, true);
        MinecraftForge.EVENT_BUS.register(guard);
        registered = true;
        kx = x0 + 6;
        kz = z0 + 16;
        BlockAirDefenseSVA.build(w, kx, Y + 1, kz, ModContentSVA.commandPost, 0, 0);
        TileEntity te = w.getTileEntity(kx, Y + 1, kz);
        check(te instanceof TileEntityCommandPostSVA, "ad harden world: the command post built");
        if (!(te instanceof TileEntityCommandPostSVA)) {
            return false;
        }
        kp = (TileEntityCommandPostSVA) te;
        kp.setOwnerName(NAME);
        clearInv(player(w, NAME));
        return true;
    }

    // ------------------------------------------------------------------ bug 17: placing through the player's path

    private static ItemStack gun() {
        return new ItemStack(ModContentSVA.launcher, 1, TileEntityLauncherSVA.GUN);
    }

    /** A gun item keeping two overclockers in its slot 1 (as a broken one keeps them, wave A). */
    private static ItemStack gunWithModules() {
        ItemStack s = gun();
        ItemStack[] slots = new ItemStack[AdUpgradeMath.SLOTS];
        slots[1] = ModItems.upgrade.stackOf(UpgradeType.OVERCLOCKER);
        slots[1].stackSize = 2;
        NBTTagCompound t = new NBTTagCompound();
        TileEntityAirDefenseSVA.writeModules(t, slots);
        s.setTagCompound(t);
        return s;
    }

    private static void clearInv(FakePlayer p) {
        for (int i = 0; i < p.inventory.mainInventory.length; i++) {
            p.inventory.mainInventory[i] = null;
        }
    }

    /** Slot 0 and slot 1 (in the hand). */
    private static void give(FakePlayer p, ItemStack a, ItemStack b) {
        clearInv(p);
        p.inventory.mainInventory[0] = a;
        p.inventory.mainInventory[1] = b;
        p.inventory.currentItem = 1;
    }

    /** RMB with the held stack on the floor's top at (x, z): the server's placing path (ForgeHooks.onPlaceItemIntoWorld). */
    private static void use(FakePlayer p, World w, int x, int z) {
        ItemStack h = p.inventory.getCurrentItem();
        if (h != null && h.stackSize > 0) {
            h.tryPlaceItemIntoWorld(p, w, x, Y, z, 1, 0.5F, 1F, 0.5F);
        }
    }

    /** Items of `it` in the player's inventory and lying round the area. */
    @SuppressWarnings("unchecked")
    private int count(FakePlayer p, World w, Item it) {
        int n = 0;
        for (ItemStack s : p.inventory.mainInventory) {
            n += s != null && s.getItem() == it && s.stackSize > 0 ? s.stackSize : 0;
        }
        List<EntityItem> l = w.getEntitiesWithinAABB(EntityItem.class, box());
        for (EntityItem e : l) {
            ItemStack s = e.getEntityItem();
            n += !e.isDead && s != null && s.getItem() == it ? s.stackSize : 0;
        }
        return n;
    }

    /** Dropped items of `it` round the area - counted, then removed (null: every item). */
    @SuppressWarnings("unchecked")
    private int drops(World w, Item it) {
        int n = 0;
        for (EntityItem e : (List<EntityItem>) w.getEntitiesWithinAABB(EntityItem.class, box())) {
            ItemStack s = e.getEntityItem();
            if (!e.isDead && s != null && (it == null || s.getItem() == it)) {
                n += s.stackSize;
                e.setDead();
            }
        }
        return n;
    }

    private AxisAlignedBB box() {
        return AxisAlignedBB.getBoundingBox(x0 - 8, Y - 3, z0 - 8, x0 + 29, Y + 9, z0 + 27);
    }

    private static int modulesIn(ItemStack s) {
        int n = 0;
        if (s != null && s.hasTagCompound()) {
            for (ItemStack u : TileEntityAirDefenseSVA.readModules(s.getTagCompound())) {
                n += u == null ? 0 : u.stackSize;
            }
        }
        return n;
    }

    /** Every cell a gun placed at (x, Y + 1, z) by the player would take is air. */
    private static boolean empty(World w, int x, int z, int facing) {
        for (int[] c : AdShapeMath.worldCells(AdShapeMath.GUN, facing)) {
            if (w.getBlock(x + c[0], Y + 1 + c[1], z + c[2]) != Blocks.air) {
                return false;
            }
        }
        return true;
    }

    private void placing(World w) {
        FakePlayer p = player(w, NAME);
        int facing = AdShapeMath.facingFromYaw(p.rotationYaw);
        Item li = Item.getItemFromBlock(ModContentSVA.launcher), up = ModItems.upgrade;
        int lx = x0 + 4, lz = z0 + 6, mx = x0 + 10, mz = z0 + 6;
        int max0 = ConfigSVA.adMaxLaunchers;
        boolean keep0 = ConfigSVA.adKeepModules;
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        String rule0 = w.getGameRules().getGameRuleStringValue("doTileDrops");
        drops(w, null);
        try {
            // 1) over the limit, the placement cancelled by a claim (the audit's case): the item came back AND Forge restored the stack
            ConfigSVA.adMaxLaunchers = 1;
            d.addNode(FAKE_NODE, AirDefenseDataSVA.N_LAUNCHER, NAME);
            give(p, gun(), gun());
            guard.mode = Guard.ALL;
            use(p, w, lx, lz);
            int cancelled = count(p, w, li);
            guard.mode = Guard.NONE;
            use(p, w, lx, lz);
            int plain = count(p, w, li);
            check(cancelled == 2 && plain == 2 && empty(w, lx, lz, facing),
                    "ad harden world (bug 17): over the limit nothing is built and no item is gained - in a claim " + cancelled + ", outside " + plain + " (2)");
            d.removeNode(FAKE_NODE);
            ConfigSVA.adMaxLaunchers = max0;
            // 2) cancelled after it was built (a protection that answers only Forge's MultiPlaceEvent): Forge puts the cells back
            for (boolean keep : new boolean[] {true, false}) {
                ConfigSVA.adKeepModules = keep;
                give(p, gun(), gunWithModules());
                guard.mode = Guard.MULTI;
                use(p, w, lx, lz);
                guard.mode = Guard.NONE;
                int inside = modulesIn(p.inventory.getStackInSlot(1));
                int loose = drops(w, up), items = count(p, w, li);
                check(items == 2 && inside == 2 && loose == 0 && empty(w, lx, lz, facing) && d.count(NAME, AirDefenseDataSVA.N_LAUNCHER) == 0,
                        "ad harden world (bug 17): a placement cancelled after building (keepModules " + keep + ") drops nothing - items " + items
                                + " (2), modules in the stack " + inside + " (2), loose " + loose + " (0)");
            }
            ConfigSVA.adKeepModules = keep0;
            // 3) a protection refusing one part's cell (the core's private field looks only at the event's own cell)
            int[] c1 = AdShapeMath.worldCells(AdShapeMath.GUN, facing)[1];
            guard.cell = new int[] {lx + c1[0], Y + 1 + c1[1], lz + c1[2]};
            guard.mode = Guard.CELL;
            give(p, gun(), gun());
            use(p, w, lx, lz);
            guard.mode = Guard.NONE;
            guard.cell = null;
            int prot = count(p, w, li);
            check(prot == 2 && empty(w, lx, lz, facing), "ad harden world (7.3): a part's cell refused by a protection - nothing built, items " + prot + " (2)");
            // 4) it goes in: one item less, the player's, its modules back, counted for the limit at once
            give(p, gun(), gunWithModules());
            use(p, w, lx, lz);
            TileEntity te = w.getTileEntity(lx, Y + 1, lz);
            ItemStack back = te instanceof TileEntityLauncherSVA ? ((TileEntityLauncherSVA) te).upgrades.getStackInSlot(1) : null;
            boolean in = te instanceof TileEntityLauncherSVA && NAME.equals(((TileEntityLauncherSVA) te).getOwnerName())
                    && ((TileEntityLauncherSVA) te).structState() == TileEntityAirDefenseSVA.MULTI && back != null && back.stackSize == 2;
            int counted = d.count(NAME, AirDefenseDataSVA.N_LAUNCHER), left = count(p, w, li);
            ConfigSVA.adMaxLaunchers = 1;
            p.inventory.currentItem = 0;
            use(p, w, mx, mz);
            ConfigSVA.adMaxLaunchers = max0;
            int left2 = count(p, w, li);
            check(in && counted == 1 && left == 1 && left2 == 1 && empty(w, mx, mz, facing),
                    "ad harden world (bug 17): placed in survival - the player's with its 2 modules, counted at once (" + counted
                            + "); a second the same tick over a limit of 1 refused (items " + left + " -> " + left2 + ")");
            // 5) doTileDrops off: no item drops, so the modules drop loose (they vanished inside the item that never came)
            w.getGameRules().setOrCreateGameRule("doTileDrops", "false");
            ConfigSVA.adKeepModules = true;
            if (te instanceof TileEntityLauncherSVA) {
                w.setBlockToAir(lx, Y + 1, lz);
            }
            w.getGameRules().setOrCreateGameRule("doTileDrops", rule0);
            ConfigSVA.adKeepModules = keep0;
            int loose = drops(w, up), item = drops(w, li);
            check(te instanceof TileEntityLauncherSVA && loose == 2 && item == 0 && empty(w, lx, lz, facing),
                    "ad harden world: broken with doTileDrops off - no item, the 2 modules loose (" + loose + ", items " + item + ")");
        } finally {
            guard.mode = Guard.NONE;
            guard.cell = null;
            ConfigSVA.adMaxLaunchers = max0;
            ConfigSVA.adKeepModules = keep0;
            w.getGameRules().setOrCreateGameRule("doTileDrops", rule0);
            d.removeNode(FAKE_NODE);
            clearInv(p);
        }
    }

    // ------------------------------------------------------------------ the legacy conversion asks the protections

    private void legacySetup(World w) {
        gx = x0 + 16;
        gz = z0 + 6;
        int dim = w.provider.dimensionId;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    if (dx != 0 || dz != 0 || dy != 0) {
                        BlockAirDefenseSVA.DEBUG_PROTECTED.add(AirDefenseDataSVA.key(dim, gx + dx, Y + 1 + dy, gz + dz));
                    }
                }
            }
        }
        w.setBlock(gx, Y + 1, gz, ModContentSVA.radar, RadarMath.TRACK, 3);
        TileEntity te = w.getTileEntity(gx, Y + 1, gz);
        legacy = te instanceof TileEntityRadarSVA ? (TileEntityRadarSVA) te : null;
        if (legacy != null) {
            legacy.setOwnerName(NAME);
        }
    }

    private void legacyCheck(World w) {
        boolean around = true;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    if ((dx != 0 || dz != 0 || dy != 0) && w.getBlock(gx + dx, Y + 1 + dy, gz + dz) != Blocks.air) {
                        around = false;
                    }
                }
            }
        }
        check(legacy != null && legacy.structState() == TileEntityAirDefenseSVA.COMPACT && around,
                "ad harden world (7.3): a legacy radar whose cells are another's protected ground stays compact, nothing built round it");
        BlockAirDefenseSVA.DEBUG_PROTECTED.clear();
        if (legacy != null) {
            w.setBlockToAir(gx, Y + 1, gz);
        }
        drops(w, null);
    }

    // ------------------------------------------------------------------ bug 1 / 7.1: the command post's commands

    private void cmd(FakePlayer p, NBTTagCompound d) {
        AirDefenseSVA.INSTANCE.command(p, new NetADSVA.Cmd(NetADSVA.KP_CMD, kx, Y + 1, kz, 0, d));
    }

    private static NBTTagCompound op(String op) {
        NBTTagCompound d = new NBTTagCompound();
        d.setString("Op", op);
        return d;
    }

    private static NBTTagCompound zoneCmd(ZoneMath.Zone z, int idx) {
        NBTTagCompound d = op("zone");
        d.setInteger("Idx", idx);
        NBTTagCompound zt = new NBTTagCompound();
        z.write(zt);
        d.setTag("Z", zt);
        return d;
    }

    private static ZoneMath.Zone rect(String name, int ax, int az, int bx, int bz) {
        ZoneMath.Zone z = new ZoneMath.Zone();
        z.name = name;
        z.shape = ZoneMath.RECT;
        z.x0 = ax;
        z.z0 = az;
        z.x1 = bx;
        z.z1 = bz;
        return z;
    }

    private FakePlayer operator(World w) {
        FakePlayer p = player(w, NAME);
        p.setPosition(kx + 2.5, Y + 1, kz + 2.5);
        return p;
    }

    private void commands(World w) {
        FakePlayer p = operator(w);
        // a modified client's box over the whole world (+-2e9 overflowed the size check), then a zone 3000 blocks away
        cmd(p, zoneCmd(rect("WORLD", -2000000000, -2000000000, 2000000000, 2000000000), -1));
        cmd(p, zoneCmd(rect("NEAR", kx - 20, kz - 20, kx + 20, kz + 20), -1));
        ZoneMath.Zone far = new ZoneMath.Zone();
        far.name = "FAR";
        far.cx = kx + 3000;
        far.cz = kz;
        far.r = 50;
        cmd(p, zoneCmd(far, -1));
        int n = kp.zones().size();
        String name = n > 0 ? kp.zones().get(0).name : "";
        check(n == 1 && "NEAR".equals(name), "ad harden world (bug 1): the world-wide box and the zone 3000 blocks away refused, the near one taken ("
                + n + " " + name + ")");
        // the fourth heavy command in one tick: refused (three at once, then one a second)
        cmd(p, op("auto"));
        check(!kp.isAuto(), "ad harden world (7.1): a fourth heavy command in one tick refused ('Auto' stays off)");
        // a guest on the access list opens the screen
        FakePlayer g = player(w, GUEST);
        g.setPosition(kx + 1.5, Y + 1, kz + 3.5);
        List<String> acc = new ArrayList<String>();
        acc.add(GUEST);
        kp.setAccess(acc);
        cmd(g, op("open"));
        check(kp.hasViewer(g), "ad harden world: a guest on the access list opens the command post's screen");
    }

    private void commands2(World w) {
        FakePlayer p = operator(w);
        FakePlayer g = player(w, GUEST);
        g.setPosition(kx + 1.5, Y + 1, kz + 3.5);
        boolean still = kp.hasViewer(g);
        kp.setAccess(new ArrayList<String>());
        cmd(p, op("auto"));
        check(still && kp.isAuto(), "ad harden world (7.1): a second later a heavy command runs again ('Auto' on); the guest still a viewer (" + still + ")");
    }

    private void commands3(World w) {
        FakePlayer p = operator(w);
        FakePlayer g = player(w, GUEST);
        check(!kp.hasViewer(g), "ad harden world (7.1): the guest taken off the access list no longer gets the picture (the day code)");
        // the zones per player: one more on another post of his - a new zone refused, an edit of his own taken
        int max0 = ConfigSVA.adMaxZonesPerPlayer;
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        try {
            ConfigSVA.adMaxZonesPerPlayer = 2;
            ZoneMath.Zone other = rect("OTHER", kx + 100, kz, kx + 120, kz + 20);
            other.owner = NAME;
            other.dim = w.provider.dimensionId;
            List<ZoneMath.Zone> l = new ArrayList<ZoneMath.Zone>();
            l.add(other);
            d.setZones(OTHER_KP, l);
            boolean room = kp.zoneRoom();
            cmd(p, zoneCmd(rect("NEW", kx - 10, kz - 10, kx + 10, kz + 10), -1));
            int n = kp.zones().size();
            cmd(p, zoneCmd(rect("EDIT", kx - 30, kz - 30, kx + 30, kz + 30), 0));
            String name = kp.zones().isEmpty() ? "" : kp.zones().get(0).name;
            check(!room && n == 1 && kp.zones().size() == 1 && "EDIT".equals(name),
                    "ad harden world (bug 1): at the zones per player limit (2, one on another post) a new zone refused, an edit taken (" + n + " " + name + ")");
        } finally {
            ConfigSVA.adMaxZonesPerPlayer = max0;
            d.removeKp(OTHER_KP);
        }
    }

    // ------------------------------------------------------------------ the area

    private void area(World w, boolean build) {
        for (int x = x0 - 4; x <= x0 + 24; x++) {
            for (int z = z0 - 4; z <= z0 + 22; z++) {
                for (int y = Y + 1; y <= Y + 6; y++) {
                    if (w.getBlock(x, y, z) != Blocks.air) {
                        w.setBlock(x, y, z, Blocks.air, 0, 2);
                    }
                }
                w.setBlock(x, Y, z, build ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
    }

    private void finish(World w) {
        if (registered) {
            MinecraftForge.EVENT_BUS.unregister(guard);
            registered = false;
        }
        guard.mode = Guard.NONE;
        BlockAirDefenseSVA.DEBUG_PROTECTED.clear();
        if (x0 != 0 || z0 != 0) {
            AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
            if (d != null) {
                d.removeNode(FAKE_NODE);
                d.removeKp(OTHER_KP);
            }
            if (kp != null && w.getTileEntity(kx, Y + 1, kz) == kp) {
                kp.noDrop = true;
                w.setBlockToAir(kx, Y + 1, kz);
            }
            area(w, false);
            drops(w, null);
            clearInv(player(w, NAME));
        }
        done = true;
    }

    void abort(World w) {
        try {
            finish(w);
        } catch (Throwable t2) {
            done = true;
        }
    }

    private void keepLoaded(World w) {
        if (!(w instanceof WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((WorldServer) w).theChunkProviderServer;
        for (int cx = (x0 - 24) >> 4; cx <= (x0 + 40) >> 4; cx++) {
            for (int cz = (z0 - 24) >> 4; cz <= (z0 + 40) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    private void check(boolean ok, String name) {
        t.check(ok, name);
    }
}
