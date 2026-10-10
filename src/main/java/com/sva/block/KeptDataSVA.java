package com.sva.block;

import java.util.ArrayList;
import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.ArmorGasSC.Gas;
import com.sva.FormatSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * ТБ-1: what the workbench, the garage, the charging station and the gas column keep in their item when broken, like
 * the core's machines: the EU buffer ("EnergySC", the core's key), the column's tanks, the station's settings. The
 * owner is not kept (whoever places it owns it), nor the garage's job or the workbench's assembly. The blocks drop it
 * from getDrops (the tile still there: removedByPlayer leaves the block to harvestBlock) and put it back in
 * onBlockPlacedBy.
 */
public final class KeptDataSVA {

    /** The core's item key for a buffer's charge (TileEntityMachineSC.ITEM_ENERGY_KEY). */
    public static final String K_ENERGY = "EnergySC";

    private KeptDataSVA() {
    }

    /** A tile whose block keeps something in its item. */
    public interface Keeper {
        /** Writes what the item keeps (nothing written: a plain item that stacks). */
        void writeKept(NBTTagCompound t);

        /** Puts it back when the item is placed. */
        void readKept(NBTTagCompound t);
    }

    /** The block's one drop: its item with the tile's kept data. */
    public static ArrayList<ItemStack> drops(Block b, int damage, World w, int x, int y, int z) {
        ArrayList<ItemStack> out = new ArrayList<ItemStack>();
        ItemStack s = new ItemStack(b, 1, damage);
        TileEntity te = w.getTileEntity(x, y, z);
        if (te instanceof Keeper) {
            NBTTagCompound t = new NBTTagCompound();
            ((Keeper) te).writeKept(t);
            if (!t.hasNoTags()) {
                s.setTagCompound(t);
            }
        }
        out.add(s);
        return out;
    }

    /** onBlockPlacedBy: the item's kept data back into the new tile. */
    public static void restore(World w, int x, int y, int z, ItemStack s) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (te instanceof Keeper && s != null && s.hasTagCompound()) {
            ((Keeper) te).readKept(s.getTagCompound());
            te.markDirty();
        }
    }

    /** The charge of a kept buffer (0 none). */
    public static int energy(NBTTagCompound t) {
        return t == null ? 0 : Math.max(0, t.getInteger(K_ENERGY));
    }

    /** Tooltip lines for a kept item: its charge and the gases in its tanks (nothing for a plain item). */
    @SideOnly(Side.CLIENT)
    public static void tooltip(ItemStack s, List list) {
        if (s == null || !s.hasTagCompound()) {
            return;
        }
        NBTTagCompound t = s.getTagCompound();
        int eu = energy(t);
        if (eu > 0) {
            list.add(Lang.tr("sva.tooltip.kept.energy", FormatSVA.group(eu)));
        }
        if (t.hasKey(TileEntityGasColumnSVA.K_TANKS)) {
            NBTTagCompound tanks = t.getCompoundTag(TileEntityGasColumnSVA.K_TANKS);
            for (Gas g : Gas.values()) {
                int mb = tanks.getInteger(g.key());
                if (mb > 0) {
                    list.add(Lang.tr("sva.tooltip.kept.gas", Lang.tr("sva.gas." + g.key()), FormatSVA.group(mb)));
                }
            }
        }
    }
}
