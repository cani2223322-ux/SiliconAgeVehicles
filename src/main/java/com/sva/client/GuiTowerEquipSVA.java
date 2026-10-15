package com.sva.client;

import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sc.machine.UpgradeType;
import com.sva.airdefense.AdUpgradeMath;
import com.sva.block.TileEntityTowerSVA;
import com.sva.inventory.ContainerTowerSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;

/**
 * Airport wave B2: the «Диспетчерская вышка»'s equipment screen (ContainerTowerSVA) as the ILS shelter's - the power
 * (green / red), the energy bar and its buffer, EU a tick, the input voltage, the four upgrade slots (hover: the modules
 * that fit) and the inventory.
 */
@SideOnly(Side.CLIENT)
public class GuiTowerEquipSVA extends GuiContainer {

    static final int W = 176, H = 192, BG = 0xFF1B1F27, EDGE = 0xFF0B0D11, PANEL = 0xFF14171D, GOLD = 0xF2A040, TEXT = 0xD8DCE4, MUTED = 0x8A93A3,
            GREEN = 0x62E06A, RED = 0xF06060;

    private final ContainerTowerSVA c;

    public GuiTowerEquipSVA(InventoryPlayer inv, TileEntityTowerSVA tile) {
        super(new ContainerTowerSVA(inv, tile));
        c = (ContainerTowerSVA) inventorySlots;
        xSize = W;
        ySize = H;
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float pt, int mx, int my) {
        int x = guiLeft, y = guiTop;
        Gui.drawRect(x - 1, y - 1, x + W + 1, y + H + 1, EDGE);
        Gui.drawRect(x, y, x + W, y + H, BG);
        Gui.drawRect(x + 4, y + 16, x + W - 4, y + 17, 0xFFE08A30);
        Gui.drawRect(x + 4, y + 20, x + W - 4, y + 78, PANEL);
        // the energy bar
        int e = c.vals[0], max = Math.max(1, c.vals[1]);
        int bw = W - 20;
        Gui.drawRect(x + 10, y + 46, x + 10 + bw, y + 52, 0xFF050607);
        Gui.drawRect(x + 10, y + 46, x + 10 + (int) ((long) bw * Math.min(e, max) / max), y + 52, 0xFF46A8E8);
        for (Object o : inventorySlots.inventorySlots) {
            Slot s = (Slot) o;
            Gui.drawRect(x + s.xDisplayPosition - 1, y + s.yDisplayPosition - 1, x + s.xDisplayPosition + 17, y + s.yDisplayPosition + 17, 0xFF3A4150);
            Gui.drawRect(x + s.xDisplayPosition, y + s.yDisplayPosition, x + s.xDisplayPosition + 16, y + s.yDisplayPosition + 16, 0xFF0E1015);
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mx, int my) {
        fontRendererObj.drawString(Lang.tr("sva.tower.equip.title"), 6, 6, GOLD);
        boolean on = c.vals[4] != 0;
        fontRendererObj.drawString(Lang.tr(on ? "sva.apt.shelter.on" : "sva.apt.shelter.off"), 10, 24, on ? GREEN : RED);
        fontRendererObj.drawString(Lang.tr("sva.apt.shelter.energy", Integer.valueOf(c.vals[0]), Integer.valueOf(c.vals[1])), 10, 35, TEXT);
        fontRendererObj.drawString(Lang.tr("sva.apt.shelter.use", Integer.valueOf(c.vals[2])), 10, 56, TEXT);
        com.sc.energy.Tier[] tiers = com.sc.energy.Tier.values();
        fontRendererObj.drawString(Lang.tr("sva.tower.equip.tier", tiers[Math.max(0, Math.min(tiers.length - 1, c.vals[3]))].name()), 10, 67, TEXT);
        fontRendererObj.drawString(Lang.tr("sva.apt.shelter.slots"), 82, 88, MUTED);
        int rx = mx - guiLeft, ry = my - guiTop;
        if (rx >= ContainerTowerSVA.UPG_X && rx < ContainerTowerSVA.UPG_X + 18 * AdUpgradeMath.SLOTS && ry >= ContainerTowerSVA.UPG_Y
                && ry < ContainerTowerSVA.UPG_Y + 18 && !c.getSlot(0).getHasStack() && c.getSlot((rx - ContainerTowerSVA.UPG_X) / 18) != null
                && !c.getSlot((rx - ContainerTowerSVA.UPG_X) / 18).getHasStack()) {
            StringBuilder b = new StringBuilder();
            for (UpgradeType u : new UpgradeType[] {UpgradeType.UNIVERSAL_TRANSFORMER, UpgradeType.TRANSFORMER, UpgradeType.ENERGY_STORAGE,
                UpgradeType.ECONOMIZER}) {
                b.append(b.length() > 0 ? ", " : "").append(com.sc.init.ModItems.upgrade.stackOf(u).getDisplayName());
            }
            java.util.List<String> tip = new java.util.ArrayList<String>();
            tip.add(Lang.tr("sva.apt.shelter.fits"));
            tip.addAll(fontRendererObj.listFormattedStringToWidth(b.toString(), 200));
            drawHoveringText(tip, rx, ry, fontRendererObj);
        }
    }
}
