package com.sva.item;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;
import com.sva.ReferenceSVA;
import com.sva.vehicle.AccessMath;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IIcon;

/**
 * Wave 2, ★10: «Ключ-карта». Blank from the Engineering Workbench; a right-click on your own vehicle binds it (the
 * owner's card: every right). The vehicle's access screen (Shift+RMB with the bound card) writes copies for other
 * players onto blank cards. Whoever carries a card (anywhere in the inventory) gets the rights written on it for that
 * vehicle while the card's lock version is the vehicle's - «Сменить замок» makes every older card useless.
 * NBT (on the stack): VehId, Board, Type, Owner, Holder, Lock, Rights. A blank card has none and stacks (16).
 */
public class ItemKeyCardSVA extends Item {

    public static final String K_VEH = "VehId", K_BOARD = "Board", K_TYPE = "Type", K_OWNER = "Owner", K_HOLDER = "Holder",
            K_LOCK = "Lock", K_RIGHTS = "Rights";

    private IIcon blank, bound;

    public ItemKeyCardSVA() {
        setMaxStackSize(16);
        setUnlocalizedName(ReferenceSVA.ASSETS + ".keyCard");
    }

    public static boolean isCard(ItemStack s) {
        return s != null && s.getItem() instanceof ItemKeyCardSVA;
    }

    public static boolean isBound(ItemStack s) {
        return isCard(s) && s.hasTagCompound() && s.getTagCompound().getString(K_VEH).length() > 0;
    }

    public static String vehicleOf(ItemStack s) {
        return isBound(s) ? s.getTagCompound().getString(K_VEH) : null;
    }

    /** The rights this stack gives for the vehicle `vehId` at lock version `lock` (-1: none - not a card, other vehicle, old lock). */
    public static int rightsFor(ItemStack s, String vehId, int lock) {
        if (!isBound(s)) {
            return -1;
        }
        NBTTagCompound t = s.getTagCompound();
        return AccessMath.cardRights(t.getString(K_VEH), t.getInteger(K_LOCK), t.getInteger(K_RIGHTS), vehId, lock);
    }

    /** Writes a bound card (one item) for a vehicle. */
    public static ItemStack write(ItemStack card, String vehId, int board, VehicleType type, String owner, String holder, int lock, int rights) {
        ItemStack s = new ItemStack(card.getItem(), 1, 0);
        NBTTagCompound t = new NBTTagCompound();
        t.setString(K_VEH, vehId == null ? "" : vehId);
        t.setInteger(K_BOARD, board);
        t.setInteger(K_TYPE, type == null ? 0 : type.ordinal());
        t.setString(K_OWNER, owner == null ? "" : owner);
        t.setString(K_HOLDER, holder == null ? "" : holder);
        t.setInteger(K_LOCK, lock);
        t.setInteger(K_RIGHTS, AccessMath.norm(rights));
        s.setTagCompound(t);
        return s;
    }

    /** «Сменить замок» from the owner's own card: that card follows the new lock. */
    public static void relock(ItemStack s, int lock) {
        if (isBound(s)) {
            s.getTagCompound().setInteger(K_LOCK, lock);
        }
    }

    /** Lang keys of the rights, comma separated through Lang (client). */
    @SideOnly(Side.CLIENT)
    public static String rightsText(int r) {
        StringBuilder b = new StringBuilder();
        String[] k = {"drive", "passenger", "cargo", "repair"};
        for (int i = 0; i < k.length; i++) {
            if ((r & 1 << i) != 0) {
                if (b.length() > 0) {
                    b.append(", ");
                }
                b.append(Lang.tr("sva.access.right." + k[i]));
            }
        }
        return b.length() == 0 ? Lang.tr("sva.access.right.none") : b.toString();
    }

    @Override
    public String getUnlocalizedName(ItemStack s) {
        return getUnlocalizedName() + (isBound(s) ? ".bound" : ".blank");
    }

    @Override
    public boolean hasEffect(ItemStack s, int pass) {
        return false;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        if (isBound(s)) {
            NBTTagCompound t = s.getTagCompound();
            VehicleType vt = VehicleType.byOrdinal(t.getInteger(K_TYPE));
            list.add(Lang.tr("sva.tooltip.card.vehicle", Lang.tr("item.siliconagevehicles.vehicle." + vt.key + ".name"),
                    Integer.valueOf(t.getInteger(K_BOARD))));
            list.add(Lang.tr("sva.tooltip.card.owner", t.getString(K_OWNER)));
            if (t.getString(K_HOLDER).length() > 0 && !t.getString(K_HOLDER).equalsIgnoreCase(t.getString(K_OWNER))) {
                list.add(Lang.tr("sva.tooltip.card.holder", t.getString(K_HOLDER)));
            }
            list.add(Lang.tr("sva.tooltip.card.rights", rightsText(t.getInteger(K_RIGHTS))));
        } else {
            list.add(Lang.tr("sva.tooltip.card.blank"));
        }
        TooltipSC.more(list, Lang.tr("sva.tooltip.card.details"), Lang.tr("sva.tooltip.howto.card"));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister r) {
        blank = r.registerIcon(ReferenceSVA.ASSETS + ":keyCard");
        bound = r.registerIcon(ReferenceSVA.ASSETS + ":keyCardBound");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconIndex(ItemStack s) {
        return isBound(s) ? bound : blank;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(ItemStack s, int pass) {
        return getIconIndex(s);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconFromDamage(int meta) {
        return blank;
    }
}
