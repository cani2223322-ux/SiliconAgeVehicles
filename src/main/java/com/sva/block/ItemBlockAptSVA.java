package com.sva.block;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

/**
 * Airport wave B1: the item of the airport's simple blocks - the devices (one per metadata: marker beacon, RVR
 * transmissometer, weather station, windsock), the ILS shelter and the runway end marker - with TooltipSC tooltips (summary
 * + Shift / Ctrl: sva.tooltip.apt.KEY.summary / .details, sva.tooltip.howto.apt.KEY).
 */
public class ItemBlockAptSVA extends ItemBlock {

    public ItemBlockAptSVA(Block b) {
        super(b);
        setHasSubtypes(b instanceof BlockAptDeviceSVA);
        setMaxDamage(0);
    }

    private String key(ItemStack s) {
        if (field_150939_a instanceof BlockAptDeviceSVA) {
            return BlockAptDeviceSVA.KEYS[Math.max(0, Math.min(BlockAptDeviceSVA.KEYS.length - 1, s.getItemDamage()))];
        }
        return field_150939_a instanceof BlockIlsShelterSVA ? "shelter" : "rwyEnd";
    }

    @Override
    public int getMetadata(int meta) {
        return field_150939_a instanceof BlockAptDeviceSVA ? meta : 0;
    }

    @Override
    public String getUnlocalizedName(ItemStack s) {
        if (field_150939_a instanceof BlockAptDeviceSVA) {
            return super.getUnlocalizedName() + "." + key(s);
        }
        return super.getUnlocalizedName();
    }

    @SideOnly(Side.CLIENT)
    private net.minecraft.util.IIcon[] icons;

    /** The 2D icons of the TESR-drawn blocks (the runway end is a plain cube - its 3D block). */
    private boolean flat() {
        return !(field_150939_a instanceof BlockRunwayEndSVA);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(net.minecraft.client.renderer.texture.IIconRegister r) {
        if (!flat()) {
            super.registerIcons(r);
            return;
        }
        String a = com.sva.ReferenceSVA.ASSETS + ":";
        icons = field_150939_a instanceof BlockIlsShelterSVA ? new net.minecraft.util.IIcon[] {r.registerIcon(a + "ilsShelter")}
                : new net.minecraft.util.IIcon[] {r.registerIcon(a + "aptMarker"), r.registerIcon(a + "aptRvr"), r.registerIcon(a + "aptWeather"),
                    r.registerIcon(a + "aptWindsock")};
    }

    @Override
    @SideOnly(Side.CLIENT)
    public net.minecraft.util.IIcon getIconFromDamage(int meta) {
        if (icons == null) {
            return super.getIconFromDamage(meta);
        }
        return icons[Math.max(0, Math.min(icons.length - 1, meta))];
    }

    @Override
    @SideOnly(Side.CLIENT)
    public int getSpriteNumber() {
        return flat() ? 1 : 0;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        String k = key(s);
        list.add(Lang.tr("sva.tooltip.apt." + k + ".summary"));
        TooltipSC.more(list, Lang.tr("sva.tooltip.apt." + k + ".details"), Lang.tr("sva.tooltip.howto.apt." + k));
    }
}
