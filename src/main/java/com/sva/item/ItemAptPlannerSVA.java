package com.sva.item;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;
import com.sva.ReferenceSVA;
import com.sva.airport.IlsCat;
import com.sva.airport.IlsPairing;
import com.sva.airport.MarkerMath;
import com.sva.airport.PlannerMath;
import com.sva.block.AirfieldSVA;
import com.sva.block.AirportDataSVA;
import com.sva.block.BlockRunwayEndSVA;
import com.sva.block.TileEntityRunwayEndSVA;
import com.sva.init.ModContentSVA;
import com.sva.vehicle.LinerIls;
import com.sva.vehicle.RunwayMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;

/**
 * Airport wave B1 (plan-next §9, item 39; mock-up veh_airport_impl_6_planner): «Планшет строителя аэродрома».
 * <ul>
 * <li>RMB on a «Торец полосы» - picks it: the first one is the landing threshold (the approach comes over it), the second
 * the far end; then, held, the tablet shows the hologram of the ILS runway (client.AirportPlannerSVA: where the LOC array,
 * the G/S mast, their shelters, the markers, the RVR sensors, the PAPI, the weather station, the ALS and the edge lights
 * go - airport.PlannerMath - the critical / sensitive areas, red where something stands in them, and the category
 * expected);</li>
 * <li>Shift + RMB on a runway block - puts the two «Торец полосы» markers at the strip's ends (two from the inventory; in
 * creative free) facing each other, and picks them;</li>
 * <li>Shift + RMB in the air - «Проверка ILS»: every issue of the picked runway's stations with how to fix it (the chat);</li>
 * <li>RMB in the air - forget the picked ends.</li>
 * </ul>
 */
public class ItemAptPlannerSVA extends Item {

    public ItemAptPlannerSVA() {
        setMaxStackSize(1);
        setUnlocalizedName(ReferenceSVA.ASSETS + ".aptPlanner");
        setTextureName(ReferenceSVA.ASSETS + ":aptPlanner");
    }

    /** The picked ends {x, y, z} (null: not picked). */
    public static int[] end(ItemStack s, String which) {
        if (s == null || !s.hasTagCompound()) {
            return null;
        }
        int[] a = s.getTagCompound().getIntArray(which);
        return a.length == 3 ? a : null;
    }

    static void pick(ItemStack s, String which, int x, int y, int z) {
        if (!s.hasTagCompound()) {
            s.setTagCompound(new NBTTagCompound());
        }
        s.getTagCompound().setIntArray(which, new int[] {x, y, z});
    }

    @Override
    public boolean onItemUse(ItemStack s, EntityPlayer p, World w, int x, int y, int z, int side, float hx, float hy, float hz) {
        Block b = w.getBlock(x, y, z);
        if (b instanceof BlockRunwayEndSVA) {
            if (!w.isRemote) {
                int[] a = end(s, "A"), e = end(s, "B");
                boolean second = a != null && e == null && !(a[0] == x && a[1] == y && a[2] == z);
                if (!second) {
                    if (s.hasTagCompound()) {
                        s.getTagCompound().removeTag("B");
                    }
                    pick(s, "A", x, y, z);
                    p.addChatComponentMessage(new ChatComponentTranslation("sva.apt.plan.pickA"));
                } else {
                    pick(s, "B", x, y, z);
                    double len = Math.sqrt((a[0] - x) * (a[0] - x) + (a[2] - z) * (a[2] - z));
                    p.addChatComponentMessage(new ChatComponentTranslation("sva.apt.plan.pickB", Integer.valueOf((int) Math.round(len)),
                            com.sva.airport.AptScale.kmText(len, ',')));
                }
                p.inventoryContainer.detectAndSendChanges();
            }
            return true;
        }
        if (p.isSneaking() && b == ModContentSVA.runway) {
            if (!w.isRemote) {
                placeEnds(s, p, w, x, y, z);
            }
            return true;
        }
        return false;
    }

    @Override
    public ItemStack onItemRightClick(ItemStack s, World w, EntityPlayer p) {
        if (w.isRemote) {
            return s;
        }
        if (p.isSneaking()) {
            check(s, p, w);
        } else if (s.hasTagCompound()) {
            s.getTagCompound().removeTag("A");
            s.getTagCompound().removeTag("B");
            p.addChatComponentMessage(new ChatComponentTranslation("sva.apt.plan.cleared"));
        }
        return s;
    }

    /** Shift + RMB on a runway block: the two markers at the strip's ends. */
    public static void placeEnds(ItemStack s, EntityPlayer p, World w, int x, int y, int z) {
        RunwayMath.Runway r = RunwayMath.find(AirfieldSVA.view(w), x + 0.5, z + 0.5, 6);
        if (r == null) {
            p.addChatComponentMessage(new ChatComponentTranslation("sva.apt.plan.noStrip", Integer.valueOf(RunwayMath.MIN_LEN)));
            return;
        }
        int yb = (int) Math.round(r.y) - 1;
        int[][] cells = PlannerMath.thresholdBlocks(r.alongX, r.c, r.a0, r.a1, yb);
        for (int[] c : cells) {
            Block b = w.getBlock(c[0], c[1], c[2]);
            if (!(b.isReplaceable(w, c[0], c[1], c[2]) || b.getMaterial().isSolid() && !b.hasTileEntity(w.getBlockMetadata(c[0], c[1], c[2]))
                    && b.getBlockHardness(w, c[0], c[1], c[2]) >= 0)) {
                p.addChatComponentMessage(new ChatComponentTranslation("sva.apt.plan.blocked", Integer.valueOf(c[0]), Integer.valueOf(c[1]),
                        Integer.valueOf(c[2])));
                return;
            }
        }
        Item endItem = Item.getItemFromBlock(ModContentSVA.runwayEnd);
        if (!p.capabilities.isCreativeMode) {
            int have = 0;
            for (ItemStack i : p.inventory.mainInventory) {
                if (i != null && i.getItem() == endItem) {
                    have += i.stackSize;
                }
            }
            if (have < 2) {
                p.addChatComponentMessage(new ChatComponentTranslation("sva.apt.plan.needEnds"));
                return;
            }
            p.inventory.consumeInventoryItem(endItem);
            p.inventory.consumeInventoryItem(endItem);
        }
        double courseAB = r.alongX ? 90 : 180;
        for (int i = 0; i < 2; i++) {
            int[] c = cells[i];
            w.setBlock(c[0], c[1], c[2], ModContentSVA.runwayEnd, 0, 3);
            TileEntity te = w.getTileEntity(c[0], c[1], c[2]);
            if (te instanceof TileEntityRunwayEndSVA) {
                TileEntityRunwayEndSVA t = (TileEntityRunwayEndSVA) te;
                t.width = r.width;
                BlockRunwayEndSVA.setup(w, c[0], c[1], c[2], t, i == 0 ? courseAB : LinerIls.wrap360(courseAB + 180));
                t.set(t.facing, r.width, t.lights, t.als);
            }
        }
        pick(s, "A", cells[0][0], cells[0][1], cells[0][2]);
        pick(s, "B", cells[1][0], cells[1][1], cells[1][2]);
        p.inventoryContainer.detectAndSendChanges();
        p.addChatComponentMessage(new ChatComponentTranslation("sva.apt.plan.placed", Integer.valueOf((int) Math.round(r.length())),
                Integer.valueOf(r.width)));
    }

    /** «Проверка ILS»: the picked runway's stations, each issue with its hint. */
    static void check(ItemStack s, EntityPlayer p, World w) {
        int[] a = end(s, "A"), b = end(s, "B");
        if (a == null || b == null) {
            p.addChatComponentMessage(new ChatComponentTranslation("sva.apt.plan.pickFirst"));
            return;
        }
        AirportDataSVA d = AirportDataSVA.get(w);
        if (d == null) {
            return;
        }
        String ka = AirportDataSVA.key(w, a[0], a[1], a[2]), kb = AirportDataSVA.key(w, b[0], b[1], b[2]);
        IlsPairing.Rwy rwy = d.runwayOf(ka);
        if (rwy == null || !(rwy.a.key.equals(kb) || rwy.b.key.equals(kb))) {
            p.addChatComponentMessage(new ChatComponentTranslation("sva.apt.plan.notPaired", Integer.valueOf((int) IlsPairing.PAIR_TOL)));
            return;
        }
        int dir = rwy.a.key.equals(ka) ? 1 : -1;
        p.addChatComponentMessage(new ChatComponentTranslation("sva.apt.plan.head", rwy.name(dir), rwy.apt, Integer.valueOf((int) Math.round(rwy.len)),
                Integer.valueOf(rwy.width)));
        IlsPairing.Pair pair = null;
        for (IlsPairing.Pair q : d.pairs()) {
            if (q.r == rwy && q.dir == dir) {
                pair = q;
            }
        }
        if (pair == null) {
            double[] where = rwy.world(dir, rwy.len + PlannerMath.LOC_BEYOND, 0);
            line(p, IlsCat.I_UNPAIRED, String.valueOf((int) Math.floor(where[0])), String.valueOf((int) Math.floor(where[1])));
            return;
        }
        LinerIls.Station st = d.station(pair.key());
        IlsCat.Result res = d.result(pair.key());
        if (st != null) {
            p.addChatComponentMessage(new ChatComponentTranslation("sva.apt.plan.station", st.ident, LinerIls.freqText(st.freq),
                    st.off() ? new ChatComponentTranslation("sva.apt.state.off") : (Object) IlsCat.catText(st.cat)));
        }
        if (res == null || res.issues.isEmpty()) {
            p.addChatComponentMessage(new ChatComponentTranslation("sva.apt.plan.allGood"));
        } else {
            for (Integer i : res.issues) {
                line(p, i.intValue(), "", "");
            }
        }
        // the markers of this landing direction
        int mk = 0;
        if (st != null) {
            for (int t = MarkerMath.OM; t <= MarkerMath.IM; t++) {
                mk += st.markers[t] >= 0 ? 1 : 0;
            }
        }
        int[] rvr = d.rvr(pair.key());
        p.addChatComponentMessage(new ChatComponentTranslation("sva.apt.plan.extras", Integer.valueOf(mk), Integer.valueOf((rvr[0] >= 0 ? 1 : 0)
                + (rvr[1] >= 0 ? 1 : 0) + (rvr[2] >= 0 ? 1 : 0)), new ChatComponentTranslation(rwy.end(dir).als != 0 ? "sva.apt.yes" : "sva.apt.no")));
    }

    static void line(EntityPlayer p, int issue, String a1, String a2) {
        ChatComponentTranslation c = new ChatComponentTranslation("sva.apt.plan.issue", new ChatComponentTranslation("sva.apt.issue." + IlsCat.ISSUE_KEYS[issue]),
                new ChatComponentTranslation("sva.apt.hint." + IlsCat.ISSUE_KEYS[issue], a1, a2));
        c.getChatStyle().setColor(EnumChatFormatting.YELLOW);
        p.addChatComponentMessage(c);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        list.add(Lang.tr("sva.tooltip.apt.planner.summary"));
        int[] a = end(s, "A"), b = end(s, "B");
        if (a != null) {
            list.add("§7" + Lang.tr(b != null ? "sva.tooltip.apt.planner.both" : "sva.tooltip.apt.planner.one"));
        }
        TooltipSC.more(list, Lang.tr("sva.tooltip.apt.planner.details"), Lang.tr("sva.tooltip.howto.apt.planner"));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister r) {
        itemIcon = r.registerIcon(ReferenceSVA.ASSETS + ":aptPlanner");
    }
}
