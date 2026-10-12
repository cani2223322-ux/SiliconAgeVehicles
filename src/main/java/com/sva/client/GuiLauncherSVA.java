package com.sva.client;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL11;

import com.sc.inventory.TextFitSC;
import com.sc.manual.Lang;
import com.sva.FormatSVA;
import com.sva.airdefense.RoeMath;
import com.sva.block.TileEntityLauncherSVA;
import com.sva.inventory.ContainerLauncherSVA;
import com.sva.net.NetADSVA;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Air-defence wave (mock-up veh_ad_3_launchers, «Окно ПУ»): a launcher's screen - the rule of engagement (four), «перед
 * огнём»: the message to the pilot, the warning shot, the wait (s; never under the server's minimum), the fire radius;
 * the state (charge, ammunition, the command post it is linked to, a tracking radar for the missiles, the targets with
 * their stage, the server's mode) and the 4 ammunition slots. Own codes, the day key and the Mode S white list are the
 * command post's (its screen). Settings go at once (NetADSVA LAUNCHER_SET); the state comes every 10 ticks.
 * Upgrades wave: a row with the burst interval / the reload and the input tier with EU/t (the upgrades in), and the four
 * upgrade slots beside the ammunition (hover: the modules that fit, GuiAdEquipSVA.fitsTip).
 */
public class GuiLauncherSVA extends GuiContainer {

    private static final int W = 260, H = 240, BG = 0xFF1B1F27, EDGE = 0xFF0B0D11, LINE = 0xFF3A4150, GOLD = 0xF2A040, TEXT = 0xD8DCE4,
            MUTED = 0x8A93A3, AMBER = 0xF0B040, RED = 0xF06060, GREEN = 0x62E06A, CYAN = 0x5EC8F0;
    private static final int B_ROE = 10, B_MSG = 20, B_WARN = 21, B_WAIT_DN = 22, B_WAIT_UP = 23, B_R_DN = 24, B_R_UP = 25;

    private static NBTTagCompound last;

    private final TileEntityLauncherSVA tile;
    private int roe, wait, radius;
    private boolean msg, warn, loaded;

    public GuiLauncherSVA(InventoryPlayer inv, TileEntityLauncherSVA tile) {
        super(new ContainerLauncherSVA(inv, tile));
        this.tile = tile;
        xSize = W;
        ySize = H;
        last = null;
    }

    /** The server's state for the open screen. */
    public static void receive(NBTTagCompound t) {
        last = t;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        for (int i = 0; i < RoeMath.ROES; i++) {
            buttonList.add(new GuiCargoSVA.Dark(B_ROE + i, guiLeft + 8, guiTop + 20 + i * 14, 122, 12, Lang.tr("sva.ad.roe." + RoeMath.ROE_KEYS[i]),
                    Lang.tr("sva.ad.roe." + RoeMath.ROE_KEYS[i] + ".tip")));
        }
        buttonList.add(new GuiCargoSVA.Dark(B_MSG, guiLeft + 8, guiTop + 80, 80, 12, Lang.tr("sva.ad.l.msg"), Lang.tr("sva.ad.l.msg.tip")));
        buttonList.add(new GuiCargoSVA.Dark(B_WARN, guiLeft + 8, guiTop + 94, 80, 12, Lang.tr("sva.ad.l.warn"), Lang.tr("sva.ad.l.warn.tip")));
        buttonList.add(new GuiCargoSVA.Dark(B_WAIT_DN, guiLeft + 176, guiTop + 94, 12, 12, "-", null));
        buttonList.add(new GuiCargoSVA.Dark(B_WAIT_UP, guiLeft + 240, guiTop + 94, 12, 12, "+", null));
        buttonList.add(new GuiCargoSVA.Dark(B_R_DN, guiLeft + 176, guiTop + 80, 12, 12, "-", null));
        buttonList.add(new GuiCargoSVA.Dark(B_R_UP, guiLeft + 240, guiTop + 80, 12, 12, "+", null));
    }

    private void send() {
        NBTTagCompound t = new NBTTagCompound();
        t.setInteger("Roe", roe);
        t.setBoolean("Msg", msg);
        t.setBoolean("Warn", warn);
        t.setInteger("Wait", wait);
        t.setInteger("Radius", radius);
        NetADSVA.cmd(NetADSVA.LAUNCHER_SET, tile.xCoord, tile.yCoord, tile.zCoord, 0, t);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!loaded && last != null) {
            loaded = true;
            roe = last.getByte("Roe");
            msg = last.getBoolean("Msg");
            warn = last.getBoolean("Warn");
            wait = last.getInteger("Wait");
            radius = last.getInteger("Radius");
        }
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (!loaded) {
            return;
        }
        int maxR = last == null ? 110 : last.getInteger("MaxR");
        int step = isShiftKeyDown() ? 50 : 10;
        if (b.id >= B_ROE && b.id < B_ROE + RoeMath.ROES) {
            roe = b.id - B_ROE;
        } else if (b.id == B_MSG) {
            msg = !msg;
        } else if (b.id == B_WARN) {
            warn = !warn;
        } else if (b.id == B_WAIT_DN) {
            wait = Math.max(RoeMath.WAIT_MIN, wait - (isShiftKeyDown() ? 10 : 1));
        } else if (b.id == B_WAIT_UP) {
            wait = Math.min(RoeMath.WAIT_MAX, wait + (isShiftKeyDown() ? 10 : 1));
        } else if (b.id == B_R_DN) {
            int r = radius <= 0 ? maxR : radius;
            radius = Math.max(16, r - step);
        } else if (b.id == B_R_UP) {
            int r = radius <= 0 ? maxR : radius;
            radius = r + step >= maxR ? 0 : r + step;
        }
        send();
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float pt, int mx, int my) {
        TextFitSC.beginFrame();
        int x = guiLeft, y = guiTop;
        drawRect(x - 1, y - 1, x + W + 1, y + H + 1, EDGE);
        drawRect(x, y, x + W, y + H, BG);
        drawRect(x + 1, y + 1, x + W - 1, y + 2, LINE);
        drawRect(x + 3, y + 15, x + W - 3, y + 16, 0xFFE08A30);
        for (Object o : inventorySlots.inventorySlots) {
            Slot s = (Slot) o;
            drawRect(x + s.xDisplayPosition - 1, y + s.yDisplayPosition - 1, x + s.xDisplayPosition + 17, y + s.yDisplayPosition + 17,
                    s instanceof com.sva.inventory.ContainerAdSVA.UpgradeSlot ? 0xFF2A5068 : 0xFF0E1014);
            drawRect(x + s.xDisplayPosition, y + s.yDisplayPosition, x + s.xDisplayPosition + 16, y + s.yDisplayPosition + 16, 0xFF2A2F3A);
        }
        for (Object o : buttonList) {
            GuiCargoSVA.Dark b = (GuiCargoSVA.Dark) o;
            b.enabled = loaded;
            b.active = b.id == B_ROE + roe || b.id == B_MSG && msg || b.id == B_WARN && warn;
            b.activeColor = b.id == B_ROE + RoeMath.HOLD && roe == RoeMath.HOLD ? GREEN : 0;
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mx, int my) {
        NBTTagCompound t = last;
        boolean missile = tile.kind() == TileEntityLauncherSVA.MISSILE;
        String no = t != null && t.getInteger("No") > 0 ? " " + Lang.tr("sva.ad.l.no", Integer.valueOf(t.getInteger("No"))) : "";
        TextFitSC.draw(fontRendererObj, Lang.tr(tile.nameKey()) + no, 6, 4, W - 60, GOLD, false, guiLeft, guiTop);
        String tier = t != null && t.hasKey("In") ? GuiAdEquipSVA.input(t) : missile ? "EV" : "MV";
        fontRendererObj.drawString(tier, W - 6 - fontRendererObj.getStringWidth(tier), 4, t != null && t.getBoolean("Any") ? GREEN : MUTED);
        // upgrades wave: the labels over the ammunition and the upgrade slots
        int ly = ContainerLauncherSVA.SLOT_Y - 11;
        fontRendererObj.drawString(Lang.tr("sva.ad.l.ammo"), ContainerLauncherSVA.SLOT_X, ly, MUTED);
        boolean upgOn = t == null || !t.hasKey("Upg") || t.getBoolean("Upg");
        fontRendererObj.drawString(Lang.tr("sva.ad.eq.modules"), ContainerLauncherSVA.UPG_X, ly, upgOn ? CYAN : RED);
        List<String> fits = GuiAdEquipSVA.fitsTip(tile, upgOn);
        TextFitSC.hover(guiLeft + ContainerLauncherSVA.UPG_X, guiTop + ly - 1, 76, 10, fits);
        for (int i = 0; i < com.sva.airdefense.AdUpgradeMath.SLOTS; i++) {
            if (tile.upgrades.getStackInSlot(i) == null) {
                TextFitSC.hover(guiLeft + ContainerLauncherSVA.UPG_X + 18 * i - 1, guiTop + ContainerLauncherSVA.SLOT_Y - 1, 18, 18, fits);
            }
        }
        if (t != null && t.hasKey("Interval")) {
            int iv = t.getInteger("Interval"), biv = t.getInteger("BaseInterval");
            String key = missile ? "sva.ad.l.reload" : "sva.ad.l.burst";
            String rate = iv == biv ? Lang.tr(key, GuiAdEquipSVA.sec(iv)) : Lang.tr(key + "Was", GuiAdEquipSVA.sec(iv), GuiAdEquipSVA.sec(biv));
            TextFitSC.draw(fontRendererObj, rate, 8, 110, 122, iv < biv ? GREEN : TEXT, false, guiLeft, guiTop);
            TextFitSC.hover(guiLeft + 8, guiTop + 109, 122, 10, Lang.tr("sva.ad.l.shotEu", FormatSVA.group(t.getInteger("ShotEu"))));
            int use = t.getInteger("Use"), base = t.getInteger("BaseUse");
            String in = Lang.tr("sva.ad.l.inUse", GuiAdEquipSVA.input(t), FormatSVA.group(use));
            TextFitSC.draw(fontRendererObj, in, 136, 110, W - 142, use > base ? AMBER : use < base ? GREEN : TEXT, false, guiLeft, guiTop);
            if (t.getBoolean("Puller")) {
                TextFitSC.hover(guiLeft + ContainerLauncherSVA.SLOT_X, guiTop + ly - 1, 76, 10, Lang.tr("sva.ad.l.puller"));
            }
        }
        int sx = 136, sy = 20;
        if (t == null) {
            fontRendererObj.drawString(Lang.tr("sva.ad.l.wait"), sx, sy, MUTED);
            return;
        }
        double e = t.getInteger("Energy") / (double) Math.max(1, t.getInteger("EMax"));
        line(Lang.tr("sva.ad.l.charge", Integer.valueOf((int) Math.round(e * 100))), sx, sy, t.getBoolean("Powered") ? TEXT : RED);
        drawRect(sx + 80, sy + 2, sx + 116, sy + 6, 0xFF0E1014);
        drawRect(sx + 80, sy + 2, sx + 80 + (int) Math.round(36 * Math.max(0, Math.min(1, e))), sy + 6, 0xFFE05050);
        line(Lang.tr(missile ? "sva.ad.l.missiles" : "sva.ad.l.shells", Integer.valueOf(t.getInteger("Ammo")), Integer.valueOf(t.getInteger("AmmoMax"))), sx, sy + 11,
                t.getInteger("Ammo") > 0 ? TEXT : RED);
        String kp = t.getString("Kp");
        line(kp.length() > 0 ? Lang.tr("sva.ad.l.kp", kp) : Lang.tr("sva.ad.l.noKp"), sx, sy + 22, kp.length() > 0 ? GREEN : AMBER);
        if (missile) {
            line(Lang.tr(t.getBoolean("Track") ? "sva.ad.l.track" : "sva.ad.l.noTrack"), sx, sy + 33, t.getBoolean("Track") ? GREEN : AMBER);
        }
        String mode = t.getString("Mode");
        line(Lang.tr("sva.ad.l.mode." + mode), sx, sy + 44, "fire".equals(mode) ? RED : AMBER);
        NBTTagList tl = t.getTagList("Targets", 10);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < tl.tagCount(); i++) {
            NBTTagCompound c = tl.getCompoundTagAt(i);
            b.append(b.length() > 0 ? ", " : "").append(c.getInteger("No") > 0 ? String.format("%02d", Integer.valueOf(c.getInteger("No"))) : "-").append(' ')
                    .append(Lang.tr("sva.ad.stage." + RoeMath.STAGE_KEYS[Math.max(0, Math.min(4, c.getByte("St")))]));
        }
        line(tl.tagCount() == 0 ? Lang.tr("sva.ad.l.noTargets") : Lang.tr("sva.ad.l.targets", b.toString()), sx, sy + 55, tl.tagCount() > 0 ? RED : MUTED);
        // the radius and the wait
        int maxR = t.getInteger("MaxR");
        fontRendererObj.drawString(Lang.tr("sva.ad.l.radius"), 136 - fontRendererObj.getStringWidth(Lang.tr("sva.ad.l.radius")) + 36, 82, MUTED);
        String rs = (radius <= 0 ? maxR : radius) + "";
        fontRendererObj.drawString(rs, 214 - fontRendererObj.getStringWidth(rs) / 2, 82, TEXT);
        fontRendererObj.drawString(Lang.tr("sva.ad.l.waitS"), 136 - fontRendererObj.getStringWidth(Lang.tr("sva.ad.l.waitS")) + 36, 96, MUTED);
        int minW = t.getInteger("MinWait");
        String ws = Math.max(wait, minW) + " " + Lang.tr("sva.ad.l.sec");
        fontRendererObj.drawString(ws, 214 - fontRendererObj.getStringWidth(ws) / 2, 96, wait < minW ? AMBER : TEXT);
        TextFitSC.hover(guiLeft + 6, guiTop + 2, W - 60, 12, Lang.tr("sva.ad.l.always"));
        List<String> tip = TextFitSC.hoverAt(mx, my);
        for (Object o : buttonList) {
            GuiCargoSVA.Dark bt = (GuiCargoSVA.Dark) o;
            if (bt.tip != null && mx >= bt.xPosition && my >= bt.yPosition && mx < bt.xPosition + bt.width && my < bt.yPosition + bt.height) {
                tip = new ArrayList<String>();
                tip.add(bt.tip);
            }
        }
        if (tip != null && !tip.isEmpty()) {
            List<String> wrapped = new ArrayList<String>();
            for (String l : tip) {
                wrapped.addAll(fontRendererObj.listFormattedStringToWidth(l, 200));
            }
            drawHoveringText(wrapped, mx - guiLeft, my - guiTop, fontRendererObj);
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    private void line(String s, int x, int y, int c) {
        TextFitSC.draw(fontRendererObj, s, x, y, W - x - 6, c, false, guiLeft, guiTop);
    }
}
