package com.sva.inventory;

import com.sva.entity.EntityVehicleSVA;

import cpw.mods.fml.common.network.IGuiHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

/** GUI ids: CARGO (x = the vehicle's entity id), WORKBENCH (x, y, z = the block). */
public class GuiHandlerSVA implements IGuiHandler {

    public static final int CARGO = 0, WORKBENCH = 1;

    @Override
    public Object getServerGuiElement(int id, EntityPlayer p, World w, int x, int y, int z) {
        if (id == WORKBENCH) {
            net.minecraft.tileentity.TileEntity te = w.getTileEntity(x, y, z);
            return te instanceof com.sva.block.TileEntityWorkbenchSVA ? new ContainerWorkbenchSVA(p.inventory, (com.sva.block.TileEntityWorkbenchSVA) te) : null;
        }
        EntityVehicleSVA v = vehicle(w, x);
        return id == CARGO && v != null ? new ContainerCargoSVA(p.inventory, v) : null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer p, World w, int x, int y, int z) {
        if (id == WORKBENCH) {
            net.minecraft.tileentity.TileEntity te = w.getTileEntity(x, y, z);
            return te instanceof com.sva.block.TileEntityWorkbenchSVA ? new com.sva.client.GuiWorkbenchSVA(p.inventory, (com.sva.block.TileEntityWorkbenchSVA) te) : null;
        }
        EntityVehicleSVA v = vehicle(w, x);
        return id == CARGO && v != null ? new com.sva.client.GuiCargoSVA(p.inventory, v) : null;
    }

    private static EntityVehicleSVA vehicle(World w, int id) {
        Entity e = w.getEntityByID(id);
        return e instanceof EntityVehicleSVA ? (EntityVehicleSVA) e : null;
    }
}
