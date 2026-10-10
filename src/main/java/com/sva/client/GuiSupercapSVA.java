package com.sva.client;

import java.util.ArrayList;
import java.util.List;

import com.sc.inventory.TextFitSC;
import com.sc.manual.Lang;
import com.sva.FormatSVA;
import com.sva.block.TileEntitySupercapSVA;
import com.sva.inventory.ContainerSupercapSVA;
import com.sva.platform.PlatformMath;

import net.minecraft.client.gui.inventory.GuiContainer;

/**
 * «Суперконденсатор станции» screen, in the charging station's style: the charge bar, what comes in from the grid, what
 * goes out into vehicles, the stations beside it and how long it takes to fill / empty. 220 x 92, no slots.
 */
public class GuiSupercapSVA extends GuiContainer {

    private static final int W = 220, H = 92;
    private static final int BG = 0xFF1B1F27, EDGE = 0xFF0B0D11, PANEL = 0xFF232833, GOLD = 0xF2A040, CYAN = 0x5EC8F0, TEXT = 0xD8DCE4,
            MUTED = 0x8A93A3, GREEN = 0x62E06A, YELLOW = 0xF0D060;

    private final ContainerSupercapSVA container;

    public GuiSupercapSVA(TileEntitySupercapSVA te) {
        super(new ContainerSupercapSVA(te));
        container = (ContainerSupercapSVA) inventorySlots;
        xSize = W;
        ySize = H;
    }

    private int v(int id) {
        return container.get(id);
    }

    private void rect(int x, int y, int w, int h, int c) {
        if (w > 0 && h > 0) {
            drawRect(x, y, x + w, y + h, c);
        }
    }

    private void frame(int x, int y, int w, int h, int border, int fill) {
        rect(x, y, w, h, border);
        rect(x + 1, y + 1, w - 2, h - 2, fill);
    }

    private static String time(double sec) {
        if (Double.isInfinite(sec) || Double.isNaN(sec)) {
            return "—";
        }
        long s = Math.max(1, Math.round(sec));
        return s >= 60 ? Lang.tr("sva.st.time.ms", Long.valueOf(s / 60), Long.valueOf(s % 60)) : Lang.tr("sva.st.time.s", Long.valueOf(s));
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        TextFitSC.beginFrame();
        super.drawScreen(mx, my, pt);
        List<String> tip = TextFitSC.hoverAt(mx, my);
        if (tip != null && !tip.isEmpty()) {
            drawHoveringText(tip, mx, my, fontRendererObj);
        }
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float pt, int mx, int my) {
        int x = guiLeft, y = guiTop;
        frame(x, y, W, H, EDGE, BG);
        rect(x + 1, y + 1, W - 2, 1, 0xFF3A4150);
        rect(x + 4, y + 15, W - 8, 1, 0xFF5EC8F0);
        frame(x + 4, y + 18, W - 8, H - 22, EDGE, PANEL);
        double f = v(ContainerSupercapSVA.V_E) / (double) PlatformMath.CAP_BUFFER;
        frame(x + 8, y + 33, W - 16, 10, 0xFF3A4150, 0xFF0E1116);
        int fw = (int) Math.round((W - 18) * Math.max(0, Math.min(1, f)));
        rect(x + 9, y + 34, fw, 8, v(ContainerSupercapSVA.V_OUT) > 0 ? 0xFF8FE3FF : 0xFF5EC8F0);
        for (int i = 1; i < 10; i++) {
            rect(x + 9 + (W - 18) * i / 10, y + 34, 1, 8, 0x60000000);
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mx, int my) {
        fontRendererObj.drawString(Lang.tr("tile.siliconagevehicles.supercap.name"), 7, 5, GOLD);
        int e = v(ContainerSupercapSVA.V_E), in = v(ContainerSupercapSVA.V_IN), out = v(ContainerSupercapSVA.V_OUT);
        int pct = (int) Math.round(100.0 * e / PlatformMath.CAP_BUFFER);
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.cap.charge", FormatSVA.group(e), FormatSVA.group(PlatformMath.CAP_BUFFER), Integer.valueOf(pct)),
                8, 22, W - 16, TEXT, guiLeft, guiTop);
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.cap.in", FormatSVA.group(in), FormatSVA.group(PlatformMath.CAP_INPUT)), 8, 47, W - 16,
                in > 0 ? GREEN : MUTED, guiLeft, guiTop);
        TextFitSC.draw(fontRendererObj, out > 0 ? Lang.tr("sva.cap.out", FormatSVA.group(out)) : Lang.tr("sva.cap.outIdle", FormatSVA.group(PlatformMath.CAP_DUMP)),
                8, 57, W - 16, out > 0 ? CYAN : MUTED, guiLeft, guiTop);
        int st = v(ContainerSupercapSVA.V_STATIONS);
        TextFitSC.draw(fontRendererObj, st > 0 ? Lang.tr("sva.cap.station", Integer.valueOf(st)) : Lang.tr("sva.cap.noStation"), 8, 67, W - 16,
                st > 0 ? TEXT : YELLOW, guiLeft, guiTop);
        String eta = out > 0 ? Lang.tr("sva.cap.empty", time(e / (double) out / 20.0))
                : in > 0 && e < PlatformMath.CAP_BUFFER ? Lang.tr("sva.cap.fill", time((PlatformMath.CAP_BUFFER - (double) e) / in / 20.0)) : "";
        if (eta.length() > 0) {
            TextFitSC.draw(fontRendererObj, eta, 8, 77, W - 16, MUTED, guiLeft, guiTop);
        }
        List<String> tip = new ArrayList<String>();
        tip.add(Lang.tr("sva.cap.tip1", FormatSVA.group(PlatformMath.CAP_INPUT)));
        tip.add("§7" + Lang.tr("sva.cap.tip2", FormatSVA.group(PlatformMath.CAP_DUMP)));
        TextFitSC.hover(guiLeft + 8, guiTop + 33, W - 16, 10, tip);
    }
}
