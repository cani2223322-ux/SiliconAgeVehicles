package com.sva.client;

import org.lwjgl.opengl.GL11;

import com.sva.block.TileEntityWorkbenchSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.workbench.WorkbenchRecipeSVA;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;

/** While a vehicle is assembled: its model as a slowly turning translucent cyan hologram over the workbench. */
public class RenderWorkbenchSVA extends TileEntitySpecialRenderer {

    public static final float SCALE = 0.25F;

    @Override
    public void renderTileEntityAt(TileEntity te, double x, double y, double z, float pt) {
        if (!(te instanceof TileEntityWorkbenchSVA)) {
            return;
        }
        TileEntityWorkbenchSVA wb = (TileEntityWorkbenchSVA) te;
        WorkbenchRecipeSVA r = wb.getAssembling();
        if (r == null) {
            return;
        }
        ItemStack out = r.display();
        if (!(out.getItem() instanceof ItemVehicleSVA)) {
            return;
        }
        ObjModelSVA m = ObjModelSVA.get(ItemVehicleSVA.typeOf(out).model);
        if (m == null) {
            return;
        }
        long t = te.getWorldObj() == null ? 0 : te.getWorldObj().getTotalWorldTime();
        float angle = ((t % 360L) + pt) * 1.5F;
        float bob = (float) Math.sin((t + pt) * 0.08) * 0.03F;
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glTranslated(x + 0.5, y + 1.08 + bob, z + 0.5);
        GL11.glRotatef(angle, 0F, 1F, 0F);
        GL11.glScalef(SCALE, SCALE, SCALE);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);         // additive: a glow
        GL11.glDepthMask(false);
        float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
        float pulse = 0.35F + 0.08F * (float) Math.sin((t + pt) * 0.15);
        m.renderTinted(0.25F, 0.85F, 1F, pulse);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
        GL11.glDepthMask(true);
        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }
}
