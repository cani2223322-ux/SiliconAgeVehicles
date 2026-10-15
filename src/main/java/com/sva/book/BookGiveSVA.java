package com.sva.book;

import com.sva.ConfigSVA;
import com.sva.init.ModContentSVA;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Wave C, item 41: the handbook given once - on a player's first join (config book.giveOnJoin), remembered in the player's
 * persisted NBT (EntityPlayer.PERSISTED_NBT_TAG survives death and the End's return), so a player who threw the book away
 * crafts a new one. Server side (FML bus).
 */
public final class BookGiveSVA {

    /** The flag in the player's persisted NBT. */
    public static final String FLAG = "svaBookGiven";

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.player != null && !e.player.worldObj.isRemote) {
            give(e.player);
        }
    }

    /** The persisted tag of a player (made when missing). */
    static NBTTagCompound persisted(EntityPlayer p) {
        NBTTagCompound d = p.getEntityData();
        if (!d.hasKey(EntityPlayer.PERSISTED_NBT_TAG)) {
            d.setTag(EntityPlayer.PERSISTED_NBT_TAG, new NBTTagCompound());
        }
        return d.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
    }

    /** Was the book given to this player already? */
    public static boolean given(EntityPlayer p) {
        return persisted(p).getBoolean(FLAG);
    }

    /**
     * Gives the book if it was never given (and the config allows): into the inventory, else dropped at the feet.
     * @return it was given now
     */
    public static boolean give(EntityPlayer p) {
        if (!ConfigSVA.bookGiveOnJoin || ModContentSVA.handbook == null) {
            return false;
        }
        NBTTagCompound t = persisted(p);
        if (shouldGive(ConfigSVA.bookGiveOnJoin, t.getBoolean(FLAG))) {
            t.setBoolean(FLAG, true);
            ItemStack s = new ItemStack(ModContentSVA.handbook);
            if (!p.inventory.addItemStackToInventory(s)) {
                p.dropPlayerItemWithRandomChoice(s, false);
            }
            p.inventoryContainer.detectAndSendChanges();
            return true;
        }
        return false;
    }

    /** The rule (pure - the self-test): on, and never given before. */
    public static boolean shouldGive(boolean configOn, boolean alreadyGiven) {
        return configOn && !alreadyGiven;
    }
}
