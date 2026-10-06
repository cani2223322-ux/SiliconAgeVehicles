package com.sva.item;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;
import com.sva.ReferenceSVA;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * The cargo crawler's trailer (plan §4.2 «Прицеп (ПКМ по вездеходу)»): an item that holds its own cargo
 * (TrailerCargo, NBT, append-only). Right-click the owner's crawler with it - hitched: +54 cargo slots and the trailer
 * drawn behind (truck.obj's trailer part); «Отцепить прицеп» in the cargo screen gives it back with its cargo.
 * Made on the Engineering Workbench.
 */
public class ItemTrailerSVA extends Item {

    public static final String K_CARGO = "TrailerCargo";

    public ItemTrailerSVA() {
        setMaxStackSize(1);
        setMaxDamage(0);
        setUnlocalizedName(ReferenceSVA.ASSETS + ".trailer");
        setTextureName(ReferenceSVA.ASSETS + ":trailer");
    }

    public static int slots() {
        return VehicleType.TRUCK.trailerSlots;
    }

    /** The trailer's cargo (slots() long). */
    public static ItemStack[] cargoOf(ItemStack s) {
        ItemStack[] out = new ItemStack[slots()];
        if (s == null || !s.hasTagCompound()) {
            return out;
        }
        NBTTagList list = s.getTagCompound().getTagList(K_CARGO, 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound t = list.getCompoundTagAt(i);
            int slot = t.getByte("Slot") & 0xFF;
            if (slot < out.length) {
                out[slot] = ItemStack.loadItemStackFromNBT(t);
            }
        }
        return out;
    }

    /** Writes `cargo` into the stack (an empty cargo leaves a plain item that stacks with fresh ones). */
    public static ItemStack withCargo(ItemStack s, ItemStack[] cargo) {
        NBTTagList list = new NBTTagList();
        for (int i = 0; i < cargo.length; i++) {
            if (cargo[i] != null) {
                NBTTagCompound t = new NBTTagCompound();
                t.setByte("Slot", (byte) i);
                cargo[i].writeToNBT(t);
                list.appendTag(t);
            }
        }
        if (list.tagCount() == 0) {
            s.setTagCompound(null);
            return s;
        }
        NBTTagCompound root = s.hasTagCompound() ? s.getTagCompound() : new NBTTagCompound();
        root.setTag(K_CARGO, list);
        s.setTagCompound(root);
        return s;
    }

    public static int used(ItemStack s) {
        int n = 0;
        for (ItemStack c : cargoOf(s)) {
            if (c != null) {
                n++;
            }
        }
        return n;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        list.add(Lang.tr("sva.tooltip.trailer.summary", Integer.valueOf(slots())));
        list.add(Lang.tr("sva.tooltip.trailer.cargo", Integer.valueOf(used(s)), Integer.valueOf(slots())));
        TooltipSC.more(list, Lang.tr("sva.tooltip.trailer.details"), Lang.tr("sva.tooltip.trailer.howto"));
    }
}
