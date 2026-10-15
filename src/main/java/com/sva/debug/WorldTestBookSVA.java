package com.sva.debug;

import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.sva.airdefense.AdShapeMath;
import com.sva.block.BlockAirDefenseSVA;
import com.sva.block.BlockIlsAntennaSVA;
import com.sva.block.BlockTowerSVA;
import com.sva.book.BookGiveSVA;
import com.sva.book.BookSchematics;
import com.sva.book.TutorialMath;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerGuide;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * Wave C world test (plan-next §9, items 41-46: the handbook), run by WorldTestSVA from tick START in its own area (x spawn -
 * 7000, z spawn + 7000, a stone floor at Y 120):
 * <ul>
 * <li>«give»: a fake player's first join gets the handbook once (BookGiveSVA), a second join nothing; the flag rides in the
 * player's saved NBT (ForgeData / PlayerPersisted);</li>
 * <li>«hologram»: every air-defence shape in 4 facings, the tower and the two ILS antennas in 4 facings are built with the
 * PLACEMENT code (BlockAirDefenseSVA.build, BlockTowerSVA.place, BlockIlsAntennaSVA.place) and the world compared cell by cell
 * with the hologram's data (BookSchematics.get) - the right block in every cell and no part outside them; then taken away;</li>
 * <li>«tutorial»: a real liner on the server, started by the API (the cockpit's clicks / LinerSystems.set) - TutorialMath fed
 * each tick from it moves through the start-up steps in order (BAT .. after start) by the real systems' state.</li>
 * </ul>
 */
final class WorldTestBookSVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000b00c";
    static final int START = 300;
    private static final int Y = 120, MAX_TICKS = 2200;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    boolean done;
    private EntityVehicleSVA liner;
    private final TutorialMath.State tut = new TutorialMath.State();
    private final int[] reachedAt = new int[TutorialMath.DONE + 1];
    private int availAt = -1, eng2At = -1, startupAt = -1;
    private double failChance = -1;

    WorldTestBookSVA(WorldTestSVA t) {
        this.t = t;
    }

    private void check(boolean ok, String name) {
        t.check(ok, name);
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        if (phase == 0) {
            ChunkCoordinates sp = w.getSpawnPoint();
            x0 = sp.posX - 7000;
            z0 = sp.posZ + 7000;
            keepLoaded(w);
            floor(w, true);
            failChance = com.sva.ConfigSVA.linerStartFailChance;
            com.sva.ConfigSVA.linerStartFailChance = 0;
            give(w);
            hologram(w);
            liner = spawn(w);
            liner.debugEngineManual = true;                          // the real systems, not the tests' instant engines
            TutorialMath.restart(tut);
            java.util.Arrays.fill(reachedAt, -1);
            t0 = ticks;
            phase = 1;
            return;
        }
        int n = ticks - t0;
        if (phase == 1) {
            if (liner == null || liner.isDead) {
                check(false, "book world: the tutorial's liner was lost");
                phase = 9;
            } else {
                tutorialTick(n);
                if (startupAt >= 0 || n > MAX_TICKS) {
                    report();
                    phase = 9;
                }
            }
        }
        if (phase == 9) {
            finish(w);
        }
    }

    void abort(World w) {
        finish(w);
    }

    // ---------------------------------------------------------------- the book on the first join

    private void give(World w) {
        if (!(w instanceof WorldServer)) {
            check(false, "book world: no server world for the fake player");
            return;
        }
        FakePlayer p = FakePlayerFactory.get((WorldServer) w, new GameProfile(UUID.fromString("00000000-5bad-4e57-0000-00000000b00d"), "TestBookReader"));
        p.getEntityData().removeTag(EntityPlayer.PERSISTED_NBT_TAG);
        p.inventory.clearInventory(null, -1);
        boolean first = BookGiveSVA.give(p);
        int n1 = count(p);
        boolean second = BookGiveSVA.give(p);
        int n2 = count(p);
        NBTTagCompound saved = new NBTTagCompound();
        p.writeToNBT(saved);
        boolean flag = saved.getCompoundTag("ForgeData").getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG).getBoolean(BookGiveSVA.FLAG);
        // a «new session»: the saved NBT read back into the player keeps the flag - no second book
        p.inventory.clearInventory(null, -1);
        p.readFromNBT(saved);
        p.inventory.clearInventory(null, -1);
        boolean third = BookGiveSVA.give(p);
        check(first && n1 == 1 && !second && n2 == 1 && flag && !third && BookGiveSVA.given(p),
                "book world: a fake player's first join - the handbook once (" + n1 + "), the second join nothing; the flag saved in its NBT and read back");
        p.inventory.clearInventory(null, -1);
    }

    private static int count(EntityPlayer p) {
        int n = 0;
        for (ItemStack s : p.inventory.mainInventory) {
            n += s != null && s.getItem() == ModContentSVA.handbook ? s.stackSize : 0;
        }
        return n;
    }

    // ---------------------------------------------------------------- the hologram's data = what the placement builds

    private void hologram(World w) {
        int ok = 0, tried = 0;
        StringBuilder bad = new StringBuilder();
        int slot = 0;
        for (int shape = 0; shape < AdShapeMath.SHAPES; shape++) {
            String id = "ad_" + new String[] {"survey", "track", "height", "gun", "missile", "kp"}[shape];
            for (int f = 0; f < 4; f++) {
                int x = x0 + 6 + (slot % 8) * 8, y = Y + 1, z = z0 + 6 + (slot / 8) * 8;
                slot++;
                tried++;
                BlockAirDefenseSVA block = shape <= AdShapeMath.HEIGHT ? ModContentSVA.radar : shape <= AdShapeMath.MISSILE ? ModContentSVA.launcher
                        : ModContentSVA.commandPost;
                if (BlockAirDefenseSVA.room(w, x, y, z, shape, f) != null || !BlockAirDefenseSVA.build(w, x, y, z, block, BookSchematics.adMeta(shape, f), f)) {
                    bad.append(' ').append(id).append(f).append(":place");
                    continue;
                }
                String why = compare(w, BookSchematics.get(id, f), x, y, z, ModContentSVA.adPart);
                if (why == null) {
                    ok++;
                } else {
                    bad.append(' ').append(id).append(f).append(':').append(why);
                }
                clear(w, BookSchematics.get(id, f), x, y, z);
            }
        }
        for (int f = 0; f < 4; f++) {
            for (int kind = 0; kind < 2; kind++) {
                String id = kind == 0 ? "ils_loc" : "ils_gs";
                int x = x0 + 6 + (slot % 8) * 8, y = Y + 1, z = z0 + 6 + (slot / 8) * 8;
                slot++;
                tried++;
                if (BlockIlsAntennaSVA.place(w, x, y, z, kind, f, "TBK", 10930, 3) == null) {
                    bad.append(' ').append(id).append(f).append(":place");
                    continue;
                }
                String why = compare(w, BookSchematics.get(id, f), x, y, z, ModContentSVA.aptPart);
                if (why == null) {
                    ok++;
                } else {
                    bad.append(' ').append(id).append(f).append(':').append(why);
                }
                clear(w, BookSchematics.get(id, f), x, y, z);
            }
        }
        int x = x0 + 6 + (slot % 8) * 8, y = Y + 1, z = z0 + 6 + (slot / 8) * 8;
        tried++;
        if (BlockTowerSVA.place(w, x, y, z, 0, "TestBook") == null) {
            bad.append(" tower:place");
        } else {
            String why = compare(w, BookSchematics.get("tower", 0), x, y, z, ModContentSVA.aptPart);
            if (why == null) {
                ok++;
            } else {
                bad.append(" tower:").append(why);
            }
            clear(w, BookSchematics.get("tower", 0), x, y, z);
        }
        check(ok == tried && tried == 6 * 4 + 8 + 1, "book world: the hologram's cells = what the placement code builds - " + ok + " / " + tried
                + " (6 air-defence shapes x 4 facings, LOC / G/S x 4, the tower)" + bad);
    }

    /** null when every cell holds its block and no part of `partBlock` stands round it outside the cells; else why. */
    private static String compare(World w, BookSchematics.Schem s, int x, int y, int z, Block partBlock) {
        java.util.Set<String> cells = new java.util.HashSet<String>();
        for (BookSchematics.Cell c : s.cells) {
            Block want = com.sva.book.BookRecipesSVA.block(c.block);
            Block have = w.getBlock(x + c.x, y + c.y, z + c.z);
            if (want == null || have != want) {
                return "cell " + c.x + "," + c.y + "," + c.z + " " + have.getUnlocalizedName();
            }
            cells.add((x + c.x) + "," + (y + c.y) + "," + (z + c.z));
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = 0; dy <= 6; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (w.getBlock(x + dx, y + dy, z + dz) == partBlock && !cells.contains((x + dx) + "," + (y + dy) + "," + (z + dz))) {
                        return "extra part " + dx + "," + dy + "," + dz;
                    }
                }
            }
        }
        return null;
    }

    private static void clear(World w, BookSchematics.Schem s, int x, int y, int z) {
        for (int i = s.cells.size() - 1; i >= 0; i--) {
            BookSchematics.Cell c = s.cells.get(i);
            w.setBlock(x + c.x, y + c.y, z + c.z, Blocks.air, 0, 2);
        }
    }

    // ---------------------------------------------------------------- the tutorial on a real liner

    private EntityVehicleSVA spawn(World w) {
        VehicleState st = new VehicleState(VehicleType.LINER);
        st.fillAllGases();
        st.energy = st.maxEnergy();
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.LINER.ordinal()), st);
        return ItemVehicleSVA.spawn(w, s, x0 + 30.5, Y + 1, z0 + 60.5, -90F, OWNER, "WorldTestBookLiner");
    }

    private static void click(EntityVehicleSVA v, LinerControls.Ctl c) {
        v.state.liner().control(c.index, LinerControls.A_PRIMARY);
    }

    private void tutorialTick(int n) {
        LinerSystems S = liner.state.liner();
        TutorialMath.Obs o = new TutorialMath.Obs();
        o.L = S;
        o.ground = liner.onGround;
        o.kmh = VehicleMath.kmh(Math.sqrt((liner.posX - liner.prevPosX) * (liner.posX - liner.prevPosX)
                + (liner.posZ - liner.prevPosZ) * (liner.posZ - liner.prevPosZ)));
        o.y = liner.posY;
        o.hdg = LinerGuide.compass(liner.rotationYaw);
        int before = tut.step;
        TutorialMath.update(tut, o);
        if (tut.step != before && tut.step <= TutorialMath.DONE) {
            reachedAt[tut.step] = n;
        }
        // the pilot's hands, step by step, as the tutorial asks (the cockpit's clicks; the after-start set by the API)
        switch (tut.step) {
            case TutorialMath.BAT:
                if (n == 3) {
                    click(liner, LinerControls.BAT1);
                    click(liner, LinerControls.BAT2);
                }
                break;
            case TutorialMath.ADIRS:
                if (S.at(LinerControls.ADIRS1) == 0) {
                    click(liner, LinerControls.ADIRS1);
                    click(liner, LinerControls.ADIRS2);
                    click(liner, LinerControls.ADIRS3);
                }
                break;
            case TutorialMath.APU:
                if (!S.on(LinerControls.APU_MASTER)) {
                    click(liner, LinerControls.APU_MASTER);
                } else if (!S.apuStartLit && S.apu != LinerSystems.A_START && S.apu != LinerSystems.A_AVAIL && n % 10 == 0) {
                    click(liner, LinerControls.APU_START);              // the quick start's own rule (the flap may still be opening)
                }
                break;
            case TutorialMath.APU_BLEED:
                if (availAt < 0) {
                    availAt = n;
                    click(liner, LinerControls.APU_BLEED);
                    click(liner, LinerControls.PACK1);
                    click(liner, LinerControls.PACK2);
                }
                break;
            case TutorialMath.BEACON:
                if (S.at(LinerControls.BEACON) != 0) {
                    click(liner, LinerControls.BEACON);
                }
                break;
            case TutorialMath.ENG_MODE:
                if (S.at(LinerControls.ENG_MODE) != 2) {
                    click(liner, LinerControls.ENG_MODE);
                }
                break;
            case TutorialMath.ENG2:
                if (!S.on(LinerControls.ENG_MASTER2)) {
                    click(liner, LinerControls.ENG_MASTER2);
                }
                break;
            case TutorialMath.ENG1:
                if (eng2At < 0) {
                    eng2At = n;
                }
                if (!S.on(LinerControls.ENG_MASTER1)) {
                    click(liner, LinerControls.ENG_MASTER1);
                }
                break;
            case TutorialMath.AFTER_START:
                S.set(LinerControls.ENG_MODE, 1);
                S.set(LinerControls.APU_BLEED, 0);
                S.set(LinerControls.PACK1, 1);
                S.set(LinerControls.PACK2, 1);
                break;
            default:
                if (tut.step > TutorialMath.AFTER_START && startupAt < 0) {
                    startupAt = n;
                }
                break;
        }
    }

    private void report() {
        boolean ordered = true;
        int last = -1;
        StringBuilder at = new StringBuilder();
        for (int s = TutorialMath.ADIRS; s <= TutorialMath.STARTUP_END + 1; s++) {
            at.append(' ').append(TutorialMath.STEPS[Math.min(s, TutorialMath.STEPS.length - 1)]).append('@').append(reachedAt[s]);
            if (reachedAt[s] < 0 || reachedAt[s] < last) {
                ordered = false;
            }
            last = reachedAt[s];
        }
        LinerSystems S = liner == null ? null : liner.state.liner();
        check(startupAt > 0 && ordered && S != null && S.eng[0].running() && S.eng[1].running() && TutorialMath.doneCount(tut) == TutorialMath.STARTUP_END + 1
                && reachedAt[TutorialMath.ENG1] - reachedAt[TutorialMath.ENG2] >= 0,
                "book world: the tutorial on a real liner - the start-up steps move on in order by the real systems (" + (startupAt / 20) + " s):" + at);
    }

    // ---------------------------------------------------------------- the area

    private void floor(World w, boolean build) {
        for (int x = x0; x < x0 + 80; x++) {
            for (int z = z0; z < z0 + 80; z++) {
                w.setBlock(x, Y, z, build ? Blocks.stone : Blocks.air, 0, 2);
                if (build) {
                    for (int y = Y + 1; y < Y + 10; y++) {
                        if (!w.isAirBlock(x, y, z)) {
                            w.setBlock(x, y, z, Blocks.air, 0, 2);
                        }
                    }
                }
            }
        }
    }

    private void keepLoaded(World w) {
        if (!(w instanceof WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((WorldServer) w).theChunkProviderServer;
        for (int cx = (x0 - 16) >> 4; cx <= (x0 + 96) >> 4; cx++) {
            for (int cz = (z0 - 16) >> 4; cz <= (z0 + 96) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    private void finish(World w) {
        if (liner != null && !liner.isDead) {
            liner.dismountAll();
            liner.setDead();
        }
        liner = null;
        if (x0 != 0 || z0 != 0) {
            floor(w, false);
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        if (reg != null) {
            reg.removeOwner(OWNER);
        }
        if (failChance >= 0) {
            com.sva.ConfigSVA.linerStartFailChance = failChance;
            failChance = -1;
        }
        done = true;
    }
}
