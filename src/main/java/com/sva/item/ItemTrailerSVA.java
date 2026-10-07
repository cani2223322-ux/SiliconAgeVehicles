package com.sva.item;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;
import com.sva.ReferenceSVA;
import com.sva.vehicle.TrailerMath;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.IIcon;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/**
 * The cargo crawler's trailer (plan §4.2 «Прицеп (ПКМ по вездеходу)»): an item that holds its own cargo
 * (TrailerCargo, NBT, append-only). Right-click the owner's crawler with it - hitched: +54 cargo slots and the trailer
 * drawn behind (truck.obj's trailer part); «Отцепить прицеп» in the cargo screen gives it back with its cargo.
 * Made on the Engineering Workbench.
 * Wave 3 (★15): the metadata is the trailer's kind (TrailerMath: 0 cargo, 1 tanker, 2 car platform, 3 mobile charger);
 * the new kinds keep their contents (the fluid, the carried vehicle, the charge) in "TrailerData" (VehicleState's
 * trailer data keys) and are drawn as their own models (models/trailer_*.obj).
 */
public class ItemTrailerSVA extends Item {

    public static final String K_CARGO = "TrailerCargo", K_DATA = "TrailerData";

    private IIcon[] icons;

    public ItemTrailerSVA() {
        setMaxStackSize(1);
        setMaxDamage(0);
        setHasSubtypes(true);
        setUnlocalizedName(ReferenceSVA.ASSETS + ".trailer");
        setTextureName(ReferenceSVA.ASSETS + ":trailer");
    }

    public static int slots() {
        return VehicleType.TRUCK.trailerSlots;
    }

    /** ★15: the trailer's kind (TrailerMath) from the metadata. */
    public static int kindOf(ItemStack s) {
        return s == null ? TrailerMath.CARGO : TrailerMath.valid(s.getItemDamage());
    }

    /** ★15: the contents of a tanker / platform / charger item (null: empty). */
    public static NBTTagCompound dataOf(ItemStack s) {
        return s != null && s.hasTagCompound() && s.getTagCompound().hasKey(K_DATA) ? s.getTagCompound().getCompoundTag(K_DATA) : null;
    }

    /** ★15: writes the contents (an empty compound leaves a plain item that stacks with fresh ones). */
    public static ItemStack withData(ItemStack s, NBTTagCompound data) {
        if (data == null || data.hasNoTags()) {
            s.setTagCompound(null);
            return s;
        }
        NBTTagCompound root = s.hasTagCompound() ? s.getTagCompound() : new NBTTagCompound();
        root.setTag(K_DATA, data);
        s.setTagCompound(root);
        return s;
    }

    /** ★15: the item holds something (cargo, fluid, a vehicle, charge) - the workbench won't take it as an ingredient. */
    public static boolean hasContents(ItemStack s) {
        NBTTagCompound d = dataOf(s);
        return s != null && s.hasTagCompound() && (used(s) > 0 || d != null && !d.hasNoTags());
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

    /** ★15: the contents read through VehicleState's trailer data (a crawler-less state of this kind). */
    public static VehicleState contents(ItemStack s) {
        VehicleState st = new VehicleState(VehicleType.TRUCK);
        st.setTrailer(true, kindOf(s));
        st.readTrailerData(dataOf(s));
        return st;
    }

    @Override
    public String getUnlocalizedName(ItemStack s) {
        int k = kindOf(s);
        return k == TrailerMath.CARGO ? getUnlocalizedName() : "item." + ReferenceSVA.ASSETS + ".trailer_" + TrailerMath.KEYS[k];
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void getSubItems(Item item, CreativeTabs tab, List list) {
        for (int k = 0; k < TrailerMath.COUNT; k++) {
            list.add(new ItemStack(item, 1, k));
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister r) {
        icons = new IIcon[TrailerMath.COUNT];
        icons[0] = r.registerIcon(ReferenceSVA.ASSETS + ":trailer");
        for (int k = 1; k < TrailerMath.COUNT; k++) {
            icons[k] = r.registerIcon(ReferenceSVA.ASSETS + ":trailer_" + TrailerMath.KEYS[k]);
        }
        itemIcon = icons[0];
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconFromDamage(int meta) {
        return icons == null ? itemIcon : icons[TrailerMath.valid(meta)];
    }

    /** ★15: a fluid's shown name ("?" for an unknown one). */
    public static String fluidName(String fluid) {
        Fluid f = fluid == null || fluid.length() == 0 ? null : FluidRegistry.getFluid(fluid);
        return f == null ? "?" : new FluidStack(f, 1).getLocalizedName();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        int k = kindOf(s);
        if (k == TrailerMath.CARGO) {
            list.add(Lang.tr("sva.tooltip.trailer.summary", Integer.valueOf(slots())));
            list.add(Lang.tr("sva.tooltip.trailer.cargo", Integer.valueOf(used(s)), Integer.valueOf(slots())));
            TooltipSC.more(list, Lang.tr("sva.tooltip.trailer.details"), Lang.tr("sva.tooltip.trailer.howto"));
            return;
        }
        String key = TrailerMath.KEYS[k];
        VehicleState st = contents(s);
        list.add(Lang.tr("sva.tooltip.trailer." + key + ".summary"));
        if (k == TrailerMath.TANK) {
            list.add(st.tankAmount > 0 ? Lang.tr("sva.tooltip.trailer.tank.in", fluidName(st.tankFluid), com.sva.FormatSVA.group(st.tankAmount),
                    com.sva.FormatSVA.group(TrailerMath.TANK_CAP)) : Lang.tr("sva.tooltip.trailer.tank.none"));
        } else if (k == TrailerMath.PLATFORM) {
            ItemStack c = st.carried == null ? null : ItemStack.loadItemStackFromNBT(st.carried);
            list.add(c != null ? Lang.tr("sva.tooltip.trailer.platform.in", c.getDisplayName(),
                    com.sva.vehicle.SkinMath.boardText(com.sva.vehicle.SkinMath.boardNumber(ItemVehicleSVA.stateOf(c).vehId)))
                    : Lang.tr("sva.tooltip.trailer.platform.none"));
        } else if (k == TrailerMath.CHARGER) {
            list.add(Lang.tr("sva.tooltip.trailer.charger.in", com.sva.FormatSVA.group((long) st.chargerEnergy),
                    com.sva.FormatSVA.group(TrailerMath.CHARGER_BUFFER)));
        }
        TooltipSC.more(list, Lang.tr("sva.tooltip.trailer." + key + ".details", new Object[0]), Lang.tr("sva.tooltip.trailer.kind.howto"));
    }
}
