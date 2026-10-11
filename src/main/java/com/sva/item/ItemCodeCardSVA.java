package com.sva.item;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;
import com.sva.ReferenceSVA;
import com.sva.airdefense.XpdrMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IIcon;

/**
 * Air-defence wave (mock-up veh_ad_1_transponder): «Карта кодов». Blank from the crafting table (paper and blue dye);
 * a right-click on the command post with it (its owner / access list) prints the day's code on it - the post's name, the
 * game day and the code; the secret itself is never written. A transponder with «Ключ дня» on takes the code from the
 * card in its pilot's inventory by itself (AirDefenseSVA.cardCode). The card is good for its day only: tomorrow it still
 * gives today's code - a stranger's - until it is printed again. NBT: Kp, Day, Code.
 */
public class ItemCodeCardSVA extends Item {

    public static final String K_KP = "Kp", K_DAY = "Day", K_CODE = "Code";

    private IIcon blank, printed;

    public ItemCodeCardSVA() {
        setMaxStackSize(16);
        setUnlocalizedName(ReferenceSVA.ASSETS + ".codeCard");
    }

    public static boolean printed(ItemStack s) {
        return s != null && s.getItem() instanceof ItemCodeCardSVA && s.hasTagCompound() && s.getTagCompound().hasKey(K_CODE);
    }

    public static long day(ItemStack s) {
        return printed(s) ? s.getTagCompound().getLong(K_DAY) : -1;
    }

    public static int code(ItemStack s) {
        return printed(s) ? s.getTagCompound().getInteger(K_CODE) : -1;
    }

    /** Prints the day's code on the card (a stack of blanks: all of them). */
    public static void print(ItemStack s, String kp, long day, int code) {
        NBTTagCompound t = new NBTTagCompound();
        t.setString(K_KP, kp == null ? "" : kp);
        t.setLong(K_DAY, day);
        t.setInteger(K_CODE, code);
        s.setTagCompound(t);
    }

    @Override
    public String getUnlocalizedName(ItemStack s) {
        return getUnlocalizedName() + (printed(s) ? ".printed" : ".blank");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        if (printed(s)) {
            NBTTagCompound t = s.getTagCompound();
            long today = Minecraft.getMinecraft().theWorld == null ? -1 : XpdrMath.day(Minecraft.getMinecraft().theWorld.getWorldTime());
            list.add(Lang.tr("sva.tooltip.codeCard.kp", t.getString(K_KP)));
            list.add(Lang.tr("sva.tooltip.codeCard.code", XpdrMath.format(t.getInteger(K_CODE)), String.valueOf(t.getLong(K_DAY))));
            list.add(Lang.tr(today == t.getLong(K_DAY) ? "sva.tooltip.codeCard.today" : "sva.tooltip.codeCard.old"));
        } else {
            list.add(Lang.tr("sva.tooltip.codeCard.blank"));
        }
        TooltipSC.more(list, Lang.tr("sva.tooltip.codeCard.details"), Lang.tr("sva.tooltip.howto.codeCard"));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister r) {
        blank = r.registerIcon(ReferenceSVA.ASSETS + ":codeCard");
        printed = r.registerIcon(ReferenceSVA.ASSETS + ":codeCardPrinted");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconIndex(ItemStack s) {
        return printed(s) ? printed : blank;
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
