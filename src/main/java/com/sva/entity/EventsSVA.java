package com.sva.entity;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.monster.IMob;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingSetAttackTargetEvent;

/**
 * Forge bus (server side): the Singular Ship's invisibility - a hostile mob that picks a rider of a ship with its
 * invisibility on as its target drops it at once (the new AI's targets; the old AI's entityToAttack is cleared by
 * the ship itself, SpaceLogicSVA.clearTargets).
 */
public class EventsSVA {

    @SubscribeEvent
    public void onTarget(LivingSetAttackTargetEvent e) {
        if (e.target == null || e.entityLiving == null || e.entityLiving.worldObj.isRemote || !(e.entityLiving instanceof IMob)) {
            return;
        }
        if (SpaceLogicSVA.cloaked(e.target) && e.entityLiving instanceof EntityLiving) {
            ((EntityLiving) e.entityLiving).setAttackTarget(null);
        }
    }

    /** Every 0.5 s a hostile mob still after a cloaked rider (set without the event, e.g. a revenge target) forgets him. */
    @SubscribeEvent
    public void onLiving(LivingEvent.LivingUpdateEvent e) {
        if (e.entityLiving instanceof IMob && e.entityLiving instanceof EntityLiving && !e.entityLiving.worldObj.isRemote
                && e.entityLiving.ticksExisted % 10 == 0) {
            EntityLiving m = (EntityLiving) e.entityLiving;
            if (m.getAttackTarget() != null && SpaceLogicSVA.cloaked(m.getAttackTarget())) {
                m.setAttackTarget(null);
            }
        }
    }
}
