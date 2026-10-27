package com.sva.item;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;
import com.sva.ReferenceSVA;
import com.sva.airdefense.CodeOnlyMath;
import com.sva.airdefense.XpdrMath;
import com.sva.net.ServerConfigSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;

/**
 * Air-defence wave (mock-up veh_ad_1_transponder): «Карта кодов». Blank from the crafting table (paper and blue dye);
 * a right-click on the command post with it (its owner / access list) prints the whole stack, or the post's «Карта кодов»
 * button prints one on a sheet of paper. A transponder with «Ключ дня» on takes the code from a good card in its pilot's
 * inventory by itself (AirDefenseSVA.cardCode) and moves to the next period's code by itself.
 * <p>
 * Code-only wave (proposal-ad-strict-iff items 17, 18, 42): the card carries WHOSE key it is, the key's generation and the
 * key periods it is good for - Own, Gen, First, Periods - and the post's name (Kp) for the tooltip; never a code: the
 * server works the code out from the owner's secret, its pepper, the generation and the period
 * (AirDefenseDataSVA.keyCode), so a card on the ground or in an item frame gives nothing away. Good for [First, First +
 * Periods): 1, 3 or 7 periods (about 20 min, 1 h, 2 h 20 min); the owner's «Сменить ключ» makes every card printed
 * before worthless. An older card (Kp, Day, Code: the code itself) is still read, but gives nothing (review 2026-10-27,
 * batch 2): every post's day code is the key's now (the server's pepper, the generation), so its code matched no post, yet
 * it lit КЛЮЧ ДНЯ and its tooltip said «Код на сегодня» - the tooltip says «больше не действует, напечатайте новую». With
 * cards of several owners the one whose owner's code-only post is near sets the code, between equals the first in the
 * inventory (AirDefenseSVA.cardCode) - the Shift details say so.
 */
public class ItemCodeCardSVA extends Item {

    public static final String K_KP = "Kp", K_DAY = "Day", K_CODE = "Code";
    /** Code-only wave: whose key, its generation, the first key period and how many. */
    public static final String K_OWN = "Own", K_GEN = "Gen", K_FIRST = "First", K_PERIODS = "Periods";

    private IIcon blank, printed;

    public ItemCodeCardSVA() {
        setMaxStackSize(16);
        setUnlocalizedName(ReferenceSVA.ASSETS + ".codeCard");
    }

    /** A printed card - a key's (keyed) or an older one with its code (legacy). */
    public static boolean printed(ItemStack s) {
        return keyed(s) || legacy(s);
    }

    /** Code-only wave: a card of an owner's key (no code on it). */
    public static boolean keyed(ItemStack s) {
        return s != null && s.getItem() instanceof ItemCodeCardSVA && s.hasTagCompound() && s.getTagCompound().hasKey(K_OWN);
    }

    /** An older card that carries its code itself (gives nothing since review batch 2 - reprint). */
    public static boolean legacy(ItemStack s) {
        return s != null && s.getItem() instanceof ItemCodeCardSVA && s.hasTagCompound() && !s.getTagCompound().hasKey(K_OWN)
                && s.getTagCompound().hasKey(K_CODE);
    }

    /** An older card's game day (-1: not an older card). */
    public static long day(ItemStack s) {
        return legacy(s) ? s.getTagCompound().getLong(K_DAY) : -1;
    }

    /** An older card's code (-1: none - a key's card never carries one). */
    public static int code(ItemStack s) {
        return legacy(s) ? s.getTagCompound().getInteger(K_CODE) : -1;
    }

    /** Whose key ("" not a key's card). */
    public static String owner(ItemStack s) {
        return keyed(s) ? s.getTagCompound().getString(K_OWN) : "";
    }

    public static int gen(ItemStack s) {
        return keyed(s) ? s.getTagCompound().getInteger(K_GEN) : 0;
    }

    /** The first key period it is good for (-1: not a key's card). */
    public static long first(ItemStack s) {
        return keyed(s) ? s.getTagCompound().getLong(K_FIRST) : -1;
    }

    public static int periods(ItemStack s) {
        return keyed(s) ? Math.max(1, s.getTagCompound().getInteger(K_PERIODS)) : 0;
    }

    /** The first key period it is no longer good for (-1: not a key's card). */
    public static long until(ItemStack s) {
        return keyed(s) ? first(s) + periods(s) : -1;
    }

    /** The post's name printed on it. */
    public static String post(ItemStack s) {
        return printed(s) ? s.getTagCompound().getString(K_KP) : "";
    }

    /**
     * Code-only wave: prints an owner's key on the card (a stack: all of them) - the post's name, whose key, the generation,
     * the first period and how many; no code.
     */
    public static void print(ItemStack s, String kp, String owner, int gen, long first, int periods) {
        NBTTagCompound t = new NBTTagCompound();
        t.setString(K_KP, kp == null ? "" : kp);
        t.setString(K_OWN, owner == null ? "" : owner);
        t.setInteger(K_GEN, gen);
        t.setLong(K_FIRST, first);
        t.setInteger(K_PERIODS, Math.max(1, periods));
        s.setTagCompound(t);
    }

    /** An older card as the version before the code-only wave printed it (the tests: an old card gives nothing). */
    public static void printLegacy(ItemStack s, String kp, long day, int code) {
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

    /**
     * Item 42: «Ключ: Bob, покол. 3 / действует ещё ~45 мин / только в инвентаре пилота» - the time left on the server's key
     * clock (ServerConfigSVA.adKeyClock); the generation is not known here, so a card made worthless by «Сменить ключ» still
     * shows its time (its transponder's КЛЮЧ ДНЯ lamp stays dark).
     */
    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        World w = Minecraft.getMinecraft().theWorld;
        if (keyed(s)) {
            NBTTagCompound t = s.getTagCompound();
            if (t.getString(K_KP).length() > 0) {
                list.add(Lang.tr("sva.tooltip.codeCard.kp", t.getString(K_KP)));
            }
            list.add(Lang.tr("sva.tooltip.codeCard.key", owner(s), String.valueOf(gen(s))));
            int clock = ServerConfigSVA.adKeyClock();
            long left = w == null ? 0 : CodeOnlyMath.cardTicksLeft(until(s), clock, w.getWorldTime(), w.getTotalWorldTime());
            long now = w == null ? -1 : XpdrMath.period(clock, w.getWorldTime(), w.getTotalWorldTime());
            if (left > 0 && now >= first(s)) {
                list.add(Lang.tr("sva.tooltip.codeCard.left", String.valueOf((left + 1199) / 1200)));
            } else {
                list.add(Lang.tr("sva.tooltip.codeCard.expired"));
            }
            list.add(Lang.tr("sva.tooltip.codeCard.pilot"));
        } else if (legacy(s)) {
            // review 2026-10-27 (batch 2): no code, no «Код на сегодня» - an older card matches no post any more
            list.add(Lang.tr("sva.tooltip.codeCard.kp", s.getTagCompound().getString(K_KP)));
            list.add(Lang.tr("sva.tooltip.codeCard.legacy"));
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
