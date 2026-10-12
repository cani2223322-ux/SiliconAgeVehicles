package com.sva.client;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.sva.ConfigSVA;
import com.sva.airdefense.AdShapeMath;
import com.sva.block.BlockAirDefenseSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.IItemRenderer;

/**
 * Multiblock wave: the radar / launcher / command post items drawn as their models (AdModelSVA, at rest, the power lamp
 * lit, the missiles on the rails): in a slot an isometric view from the front left and a little above, in the hand like a
 * held block, on the ground like any dropped block. Scaled to fit from the model's bounds. Config airdefense.models off:
 * the plain block icons (this renderer then declines every type).
 */
@SideOnly(Side.CLIENT)
public class RenderItemAirDefenseSVA implements IItemRenderer {

    private int shape(ItemStack s) {
        Block b = Block.getBlockFromItem(s.getItem());
        int kind = b instanceof BlockAirDefenseSVA ? ((BlockAirDefenseSVA) b).kind : 0;
        return AdShapeMath.shape(kind, s.getItemDamage());
    }

    private AdModelSVA model(ItemStack s) {
        return AdModelSVA.get(AdShapeMath.MODEL[shape(s)]);
    }

    @Override
    public boolean handleRenderType(ItemStack item, ItemRenderType type) {
        if (!ConfigSVA.adModels || item == null) {
            return false;
        }
        boolean ok = type == ItemRenderType.INVENTORY || type == ItemRenderType.ENTITY || type == ItemRenderType.EQUIPPED
                || type == ItemRenderType.EQUIPPED_FIRST_PERSON;
        return ok && model(item) != null;
    }

    @Override
    public boolean shouldUseRenderHelper(ItemRenderType type, ItemStack item, ItemRendererHelper helper) {
        switch (helper) {
            case ENTITY_ROTATION:
            case ENTITY_BOBBING:
            case BLOCK_3D:
            case EQUIPPED_BLOCK:
                return true;
            default:
                return false;                                       // INVENTORY_BLOCK: our own slot transform
        }
    }

    @Override
    public void renderItem(ItemRenderType type, ItemStack item, Object... data) {
        AdModelSVA m = model(item);
        if (m == null) {
            return;
        }
        float[] b = m.bounds;
        int shape = shape(item);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_TRANSFORM_BIT);
        GL11.glPushMatrix();
        try {
            float cx = (b[0] + b[3]) / 2, cz = (b[2] + b[5]) / 2;
            float hx = (b[3] - b[0]) / 2, hz = (b[5] - b[2]) / 2, height = b[4] - b[1];
            float radius = (float) Math.sqrt(hx * hx + hz * hz);
            if (type == ItemRenderType.INVENTORY) {
                float tilt = 28F;
                double tr = Math.toRadians(tilt);
                float s = Math.min(15.5F / (2 * radius), 15.5F / (float) (height * Math.cos(tr) + 2 * radius * Math.sin(tr)));
                GL11.glTranslatef(8F, 8.3F, 50F);
                RenderHelper.enableGUIStandardItemLighting();
                GL11.glRotatef(180F, 1F, 0F, 0F);
                GL11.glRotatef(-tilt, 1F, 0F, 0F);
                GL11.glRotatef(-135F, 0F, 1F, 0F);                 // the front (x) toward the viewer's left
                GL11.glScalef(s, s, s);
                GL11.glTranslatef(-cx, -(b[1] + height / 2), -cz);
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glDepthMask(true);
            } else if (type == ItemRenderType.ENTITY) {
                float s = 1.4F / (2 * Math.max(radius, height / 2));
                GL11.glScalef(s, s, s);
                GL11.glTranslatef(-cx, -b[1], -cz);
            } else {
                float s = 1.1F / (2 * Math.max(radius, height / 2));
                GL11.glTranslatef(0.5F, 0.5F, 0.5F);
                if (type == ItemRenderType.EQUIPPED_FIRST_PERSON) {
                    GL11.glRotatef(-45F, 0F, 1F, 0F);
                }
                GL11.glScalef(s, s, s);
                GL11.glTranslatef(-cx, -(b[1] + height / 2), -cz);
            }
            GL11.glEnable(GL11.GL_NORMALIZE);
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            GL11.glEnable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            RenderAirDefenseSVA.draw(m, shape, null, 0F, 0L, true);
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
            GL11.glColor4f(1F, 1F, 1F, 1F);
        }
    }
}
