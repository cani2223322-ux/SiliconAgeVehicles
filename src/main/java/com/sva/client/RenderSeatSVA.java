package com.sva.client;

import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

/** Seats are invisible. */
public class RenderSeatSVA extends Render {

    @Override
    public void doRender(Entity e, double x, double y, double z, float yaw, float pt) {
    }

    @Override
    protected ResourceLocation getEntityTexture(Entity e) {
        return null;
    }
}
