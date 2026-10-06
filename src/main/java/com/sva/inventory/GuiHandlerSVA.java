package com.sva.inventory;

import com.sva.entity.EntityVehicleSVA;

import cpw.mods.fml.common.network.IGuiHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

/** GUI ids: CARGO (x = the vehicle's entity id). */
public class GuiHandlerSVA implements IGuiHandler {

    public static final int CARGO = 0;

    @Override
    public Object getServerGuiElement(int id, EntityPlayer p, World w, int x, int y, int z) {
        EntityVehicleSVA v = vehicle(w, x);
        return id == CARGO && v != null ? new ContainerCargoSVA(p.inventory, v) : null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer p, World w, int x, int y, int z) {
        EntityVehicleSVA v = vehicle(w, x);
        return id == CARGO && v != null ? new com.sva.client.GuiCargoSVA(p.inventory, v) : null;
    }

    private static EntityVehicleSVA vehicle(World w, int id) {
        Entity e = w.getEntityByID(id);
        return e instanceof EntityVehicleSVA ? (EntityVehicleSVA) e : null;
    }
}
