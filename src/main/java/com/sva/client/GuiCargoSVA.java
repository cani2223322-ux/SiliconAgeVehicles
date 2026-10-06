package com.sva.client;

import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sva.entity.EntityVehicleSVA;
import com.sva.inventory.ContainerCargoSVA;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;

/** Cargo screen: the vanilla dispenser background (3x3). */
public class GuiCargoSVA extends GuiContainer {

    private static final ResourceLocation BG = new ResourceLocation("textures/gui/container/dispenser.png");
    private final EntityVehicleSVA vehicle;
    private final InventoryPlayer inv;

    public GuiCargoSVA(InventoryPlayer inv, EntityVehicleSVA v) {
        super(new ContainerCargoSVA(inv, v));
        this.vehicle = v;
        this.inv = inv;
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mx, int my) {
        String title = Lang.tr("container.siliconagevehicles.cargo");
        fontRendererObj.drawString(title, xSize / 2 - fontRendererObj.getStringWidth(title) / 2, 6, 0x404040);
        fontRendererObj.drawString(Lang.tr(inv.getInventoryName()), 8, ySize - 96 + 2, 0x404040);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float pt, int mx, int my) {
        GL11.glColor4f(1F, 1F, 1F, 1F);
        mc.getTextureManager().bindTexture(BG);
        drawTexturedModalRect((width - xSize) / 2, (height - ySize) / 2, 0, 0, xSize, ySize);
    }
}
