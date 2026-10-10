package com.sva.client;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.sc.energy.Tier;
import com.sc.inventory.GuiEnergyGaugeSC;
import com.sc.inventory.TextFitSC;
import com.sc.manual.Lang;
import com.sc.util.ArmorGasSC.Gas;
import com.sva.FormatSVA;
import com.sva.block.BlockChargePlateSVA;
import com.sva.block.PlateStatesSVA;
import com.sva.block.TileEntityChargerSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.inventory.ContainerChargerSVA;
import com.sva.net.NetSVA;
import com.sva.platform.PlatformMath;
import com.sva.vehicle.PlatformSize;
import com.sva.vehicle.VehicleType;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;

/**
 * The charging station screen (mock-up docs/mockups/veh_9_platform_gui.png): the platform map (plates round the
 * station, occupied cells lit), plate count + speed bonus + «помещается», the vehicle card (EU / gases / hull with
 * rates, time to full), upgrades, modules, settings, connected devices, the status line and the player's inventory.
 * 360 x 236 - fits a 427 x 240 screen.
 */
public class GuiChargerSVA extends GuiContainer {

    private static final int W = 360, H = 236;
    private static final int BG = 0xFF1B1F27, EDGE = 0xFF0B0D11, PANEL = 0xFF232833, SLOT = 0xFF2B313C, SLOT_DARK = 0xFF0D1015,
            GOLD = 0xF2A040, CYAN = 0x5EC8F0, TEXT = 0xD8DCE4, MUTED = 0x8A93A3, GREEN = 0x62E06A, RED = 0xF06060, YELLOW = 0xF0D060;
    private static final int MAP_X = 8, MAP_Y = 31, MAP_W = 100, MAP_H = 62;
    private static final int SET_X = 258, SET_Y = 82, SET_STEP = 9;
    private static final int[] SET_BITS = {0, TileEntityChargerSVA.SET_REDSTONE, TileEntityChargerSVA.SET_SOUND, TileEntityChargerSVA.SET_GAS};

    private final TileEntityChargerSVA tile;
    private final ContainerChargerSVA container;

    public GuiChargerSVA(InventoryPlayer inv, TileEntityChargerSVA te) {
        super(new ContainerChargerSVA(inv, te));
        this.tile = te;
        this.container = (ContainerChargerSVA) inventorySlots;
        xSize = W;
        ySize = H;
    }

    // ------------------------------------------------------------------ data

    private int v(int id) {
        return container.get(id);
    }

    private EntityVehicleSVA vehicle() {
        int id = v(ContainerChargerSVA.V_VEH) - 1;
        if (id < 0 || mc.theWorld == null) {
            return null;
        }
        Entity e = mc.theWorld.getEntityByID(id);
        return e instanceof EntityVehicleSVA && !e.isDead ? (EntityVehicleSVA) e : null;
    }

    private boolean st(int bit) {
        return (v(ContainerChargerSVA.V_STATUS) & bit) != 0;
    }

    private boolean set(int bit) {
        return (v(ContainerChargerSVA.V_SET) & bit) != 0;
    }

    private static String num(long n) {
        return com.sva.FormatSVA.group(n);
    }

    private static String pct(double f) {
        return Math.round(Math.max(0, Math.min(1, f)) * 100) + "%";
    }

    private static String time(double sec) {
        if (Double.isInfinite(sec) || Double.isNaN(sec)) {
            return "—";
        }
        long s = Math.max(1, Math.round(sec));
        return s >= 60 ? Lang.tr("sva.st.time.ms", Long.valueOf(s / 60), Long.valueOf(s % 60)) : Lang.tr("sva.st.time.s", Long.valueOf(s));
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------ input

    /** Wave 3, ★19: «Техника» in the inventory panel's header opens «Моя техника» round this station. */
    private static final int B_MAP = 0;

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        if (com.sva.ConfigSVA.vehicleMap) {
            buttonList.add(new GuiCargoSVA.Dark(B_MAP, guiLeft + 300, guiTop + 140, 52, 11, Lang.tr("sva.st.map"), Lang.tr("sva.st.tip.map")));
        }
    }

    @Override
    protected void actionPerformed(net.minecraft.client.gui.GuiButton b) {
        if (b.id == B_MAP) {
            GuiMapSVA.openAtStation(mc, tile.xCoord, tile.zCoord);
        }
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        super.mouseClicked(mx, my, button);
        for (int i = 1; i < SET_BITS.length; i++) {
            if (inside(mx, my, guiLeft + SET_X, guiTop + SET_Y + i * SET_STEP, 96, SET_STEP)) {
                NetSVA.CHANNEL.sendToServer(new NetSVA.Station(tile.xCoord, tile.yCoord, tile.zCoord, SET_BITS[i]));
                mc.getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.func_147674_a(
                        new net.minecraft.util.ResourceLocation("gui.button.press"), 1.0F));
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    /** ТИ-2: the window shrinks as a whole on a screen too small for it (GuiFitSVA); 1 = as designed. */
    private float fit = 1F;

    @Override
    public void setWorldAndResolution(net.minecraft.client.Minecraft mc, int w, int h) {
        fit = GuiFitSVA.fit(w, h, W, H);
        super.setWorldAndResolution(mc, GuiFitSVA.virtual(w, fit), GuiFitSVA.virtual(h, fit));
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        GuiFitSVA.begin(fit);
        drawFitted(GuiFitSVA.mouse(mx, fit), GuiFitSVA.mouse(my, fit), pt);
        GuiFitSVA.end(fit);
    }

    /** drawScreen in the window's own (virtual) coordinates. */
    private void drawFitted(int mx, int my, float pt) {
        TextFitSC.beginFrame();
        super.drawScreen(mx, my, pt);
        for (Object o : buttonList) {
            if (o instanceof GuiCargoSVA.Dark && ((GuiCargoSVA.Dark) o).visible) {
                GuiCargoSVA.Dark d = (GuiCargoSVA.Dark) o;
                TextFitSC.hover(d.xPosition, d.yPosition, d.width, d.height, d.tip);
            }
        }
        List<String> tip = TextFitSC.hoverAt(mx, my);
        if (tip != null && !tip.isEmpty()) {
            drawHoveringText(tip, mx, my, fontRendererObj);
        }
    }

    private void rect(int x, int y, int w, int h, int c) {
        if (w > 0 && h > 0) {
            drawRect(x, y, x + w, y + h, c);
        }
    }

    private void frame(int x, int y, int w, int h, int border, int fillC) {
        rect(x, y, w, h, border);
        rect(x + 1, y + 1, w - 2, h - 2, fillC);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float pt, int mx, int my) {
        int x = guiLeft, y = guiTop;
        GL11.glColor4f(1F, 1F, 1F, 1F);
        frame(x, y, W, H, EDGE, BG);
        rect(x + 1, y + 1, W - 2, 1, 0xFF3A4150);
        rect(x + 4, y + 15, W - 8, 1, 0xFFE08A30);
        frame(x + 4, y + 18, 108, 102, EDGE, PANEL);           // platform
        frame(x + 115, y + 18, 137, 102, EDGE, PANEL);         // vehicle card
        frame(x + 255, y + 18, 101, 102, EDGE, PANEL);         // upgrades / modules / settings
        int stc = st(TileEntityChargerSVA.S_NO_FIT) ? 0xFFF06060 : st(TileEntityChargerSVA.S_VEHICLE) ? 0xFF62E06A : 0xFF3A4150;
        frame(x + 4, y + 123, 352, 13, stc, 0xFF0E1116);       // status line
        frame(x + 4, y + 139, 182, 94, EDGE, PANEL);           // connected
        frame(x + 189, y + 139, 167, 94, EDGE, PANEL);         // inventory
        for (Object o : inventorySlots.inventorySlots) {
            Slot s = (Slot) o;
            frame(x + s.xDisplayPosition - 1, y + s.yDisplayPosition - 1, 18, 18, SLOT_DARK, SLOT);
        }
        int max = v(ContainerChargerSVA.V_MAX);
        GuiEnergyGaugeSC.draw(x + 337, y + 21, 14, 56, max <= 0 ? 0F : Math.min(1F, v(ContainerChargerSVA.V_E) / (float) max));
        drawMap(x + MAP_X, y + MAP_Y);
        drawBars(x, y);
        for (int i = 0; i < SET_BITS.length; i++) {
            boolean on = i == 0 ? set(ContainerChargerSVA.M_ACCESS) : set(SET_BITS[i]);
            int cy = y + SET_Y + i * SET_STEP;
            frame(x + SET_X, cy, 8, 8, i == 0 ? 0xFF3A4150 : 0xFF5A6272, 0xFF0E1116);
            if (on) {
                rect(x + SET_X + 2, cy + 2, 4, 4, i == 0 ? 0xFF4AA060 : 0xFF62E06A);
            }
        }
        for (int i = 0; i < 3; i++) {
            frame(x + 7, y + 152 + i * 27, 176, 25, 0xFF3A4150, 0xFF1E232C);
        }
        drawDeviceIcons(x, y);
    }

    /** The plates round the station, cells under a vehicle lit, the station orange. */
    private void drawMap(int mx0, int my0) {
        rect(mx0, my0, MAP_W, MAP_H, 0xFF151920);
        int[] plates = tile.getClientPlates();
        int x0 = 0, z0 = 0, x1 = 0, z1 = 0;
        for (int p : plates) {
            int dx = (short) (p >> 16), dz = (short) p;
            x0 = Math.min(x0, dx);
            z0 = Math.min(z0, dz);
            x1 = Math.max(x1, dx);
            z1 = Math.max(z1, dz);
        }
        int sx = x1 - x0 + 1, sz = z1 - z0 + 1;
        int cell = Math.max(2, Math.min(12, Math.min(MAP_W / sx, MAP_H / sz)));
        int ox = mx0 + (MAP_W - cell * sx) / 2, oz = my0 + (MAP_H - cell * sz) / 2;
        int vis = tile.getClientVisual();
        int border = vis == PlateStatesSVA.CHARGING ? 0xFF5EC8F0 : vis == PlateStatesSVA.FULL ? 0xFF62E06A : 0xFF4A5468;
        List<AxisAlignedBB> boxes = new ArrayList<AxisAlignedBB>();
        if (mc.theWorld != null) {
            AxisAlignedBB zone = AxisAlignedBB.getBoundingBox(tile.xCoord + x0 - 1, tile.yCoord, tile.zCoord + z0 - 1, tile.xCoord + x1 + 2,
                    tile.yCoord + 6, tile.zCoord + z1 + 2);
            for (Object o : mc.theWorld.getEntitiesWithinAABB(EntityVehicleSVA.class, zone)) {
                boxes.add(((EntityVehicleSVA) o).boundingBox);
            }
        }
        for (int p : plates) {
            int dx = (short) (p >> 16), dz = (short) p;
            int cx = ox + (dx - x0) * cell, cz = oz + (dz - z0) * cell;
            int wx = tile.xCoord + dx, wz = tile.zCoord + dz;
            boolean occ = false;
            for (AxisAlignedBB b : boxes) {
                occ |= b.maxX > wx && b.minX < wx + 1 && b.maxZ > wz && b.minZ < wz + 1;
            }
            int meta = mc.theWorld == null ? 0 : BlockChargePlateSVA.variant(mc.theWorld.getBlockMetadata(wx, tile.yCoord, wz));
            int fill = occ ? 0xFF2E5A80 : meta == BlockChargePlateSVA.AVIA ? 0xFF4A4020 : 0xFF262D3A;
            if (cell >= 4) {
                frame(cx, cz, cell - 1, cell - 1, border, fill);
            } else {
                rect(cx, cz, cell - 1 < 1 ? 1 : cell - 1, cell - 1 < 1 ? 1 : cell - 1, occ ? 0xFF5EC8F0 : border);
            }
            if (meta == BlockChargePlateSVA.EDGE && cell >= 4) {
                rect(cx + cell / 2 - 1, cz + 1, 2, 2, 0xFFFFD24D);
            }
        }
        int cx = ox + (0 - x0) * cell, cz = oz + (0 - z0) * cell;
        rect(cx, cz, Math.max(1, cell - 1), Math.max(1, cell - 1), 0xFFF08A30);
    }

    private void bar(int x, int y, double frac, int colour) {
        rect(x, y, 98, 6, 0xFF0E1014);
        int f = (int) Math.round(98 * Math.max(0, Math.min(1, frac)));
        rect(x, y, f, 6, colour);
    }

    /** Bars of the vehicle card (texts are drawn in the foreground). */
    private void drawBars(int x, int y) {
        EntityVehicleSVA veh = vehicle();
        if (veh == null) {
            return;
        }
        VehicleType t = veh.getVehicleType();
        int ry = y + 43;
        bar(x + 150, ry, veh.getEnergy() / veh.getMaxEnergy(), 0xFFE05454);
        ry += 16;
        int g = 0;
        for (Gas gas : t.gases.gases()) {
            if (g++ >= 2) {
                break;
            }
            bar(x + 150, ry, veh.getGas(gas) / (double) Math.max(1, veh.getGasCapacity(gas)), 0xFF000000 | gas.color);
            ry += 16;
        }
        if (veh.wearOn() && ry <= y + 91) {
            double hp = veh.getHp() / veh.getMaxHp();
            bar(x + 150, ry, hp, hp < 0.25 ? 0xFFFF4040 : 0xFF3CCB6E);
        }
    }

    private void item(ItemStack s, int x, int y) {
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), s, x, y);
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    private void drawDeviceIcons(int x, int y) {
        item(new ItemStack(ModContentSVA.gasColumn), x + 10, y + 156);
        item(new ItemStack(Blocks.chest), x + 10, y + 183);
        item(new ItemStack(ModContentSVA.chargePlate, 1, BlockChargePlateSVA.EDGE), x + 10, y + 210);
        EntityVehicleSVA veh = vehicle();
        if (veh != null) {
            item(new ItemStack(ModContentSVA.vehicle, 1, veh.getVehicleType().ordinal()), x + 118, y + 21);
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mx, int my) {
        int ox = guiLeft, oy = guiTop;
        fontRendererObj.drawString(Lang.tr("tile.siliconagevehicles.charger.name"), 7, 5, GOLD);
        int plates = v(ContainerChargerSVA.V_PLATES), sx = v(ContainerChargerSVA.V_SX), sz = v(ContainerChargerSVA.V_SZ);
        Tier in = Tier.byOrdinal(v(ContainerChargerSVA.V_TIER));
        String head = plates > 0 ? Lang.tr("sva.st.head", in.name(), num(v(ContainerChargerSVA.V_RATE)), sx + "x" + sz, Integer.valueOf(plates))
                : Lang.tr("sva.st.head.lone", in.name(), num(v(ContainerChargerSVA.V_RATE)));
        int hw = Math.min(200, fontRendererObj.getStringWidth(head));
        TextFitSC.draw(fontRendererObj, head, W - 6 - hw, 5, 200, MUTED, ox, oy);
        drawPlatformText();
        drawCard();
        drawRight(mx, my);
        drawStatus();
        drawDevices();
        fontRendererObj.drawString(Lang.tr("sva.st.inventory"), 193, 142, CYAN);
    }

    private void drawPlatformText() {
        fontRendererObj.drawString(Lang.tr("sva.st.platform"), 8, 21, CYAN);
        int plates = v(ContainerChargerSVA.V_PLATES), sx = v(ContainerChargerSVA.V_SX), sz = v(ContainerChargerSVA.V_SZ);
        EntityVehicleSVA veh = vehicle();
        PlatformSize size = veh == null ? PlatformSize.P3x3 : veh.getVehicleType().platformSize;
        if (plates == 0) {
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.st.noPlates1"), 8, 97, 100, MUTED, guiLeft, guiTop);
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.st.noPlates2"), 8, 107, 100, MUTED, guiLeft, guiTop);
            return;
        }
        int bonus = (int) Math.round(PlatformMath.plateBonus(plates, size) * 100);
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.st.plates", Integer.valueOf(plates), Integer.valueOf(bonus)), 8, 97, 100, TEXT, guiLeft, guiTop);
        String fit;
        int c;
        if (veh != null) {
            boolean ok = PlatformMath.fits(size, plates, sx, sz);
            fit = Lang.tr(ok ? "sva.st.fits.yes" : "sva.st.fits.no", size.label());
            c = ok ? GREEN : RED;
        } else if (st(TileEntityChargerSVA.S_NO_FIT)) {
            fit = Lang.tr("sva.st.fits.no", "?");
            c = RED;
        } else {
            fit = Lang.tr("sva.st.fits.none");
            c = MUTED;
        }
        TextFitSC.draw(fontRendererObj, fit, 8, 107, 100, c, guiLeft, guiTop);
        List<String> tip = new ArrayList<String>();
        tip.add(Lang.tr("sva.st.map.tip1"));
        tip.add("§7" + Lang.tr("sva.st.map.tip2"));
        TextFitSC.hover(guiLeft + MAP_X, guiTop + MAP_Y, MAP_W, MAP_H, tip);
    }

    private void drawCard() {
        EntityVehicleSVA veh = vehicle();
        if (veh == null) {
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.st.card.none1"), 119, 24, 130, MUTED, guiLeft, guiTop);
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.st.card.none2"), 119, 34, 130, MUTED, guiLeft, guiTop);
            return;
        }
        VehicleType t = veh.getVehicleType();
        String name = new ItemStack(ModContentSVA.vehicle, 1, t.ordinal()).getDisplayName();
        TextFitSC.draw(fontRendererObj, name, 137, 22, 112, 0xFFFFFF, guiLeft, guiTop);
        String owner = container.vehicleOwner == null || container.vehicleOwner.length() == 0 ? "—" : container.vehicleOwner;
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.st.card.owner", owner, t.platformSize.label()), 137, 31, 112, MUTED, guiLeft, guiTop);
        int ry = 42;
        int euT = v(ContainerChargerSVA.V_EU_T);
        fontRendererObj.drawString(Lang.tr("sva.hud.eu"), 119, ry, TEXT);
        String eu = Lang.tr("sva.st.card.eu", FormatSVA.eu(veh.getEnergy()), FormatSVA.eu((long) veh.getMaxEnergy())) + (euT > 0 ? " · +" + num(euT) + " EU/t" : "");
        TextFitSC.draw(fontRendererObj, eu, 150, ry + 8, 99, MUTED, guiLeft, guiTop);
        ry += 16;
        int gasRate = v(ContainerChargerSVA.V_GAS);
        int g = 0;
        for (Gas gas : t.gases.gases()) {
            if (g++ >= 2) {
                break;
            }
            int mb = veh.getGas(gas), cap = veh.getGasCapacity(gas);
            fontRendererObj.drawString(Lang.tr("sva.gas." + gas.key()), 119, ry, TEXT);
            String line = Lang.tr("sva.st.card.gas", num(mb), num(cap)) + (gasRate > 0 && mb < cap ? " · +" + num(gasRate) + " " + Lang.tr("sva.st.mbs") : "");
            TextFitSC.draw(fontRendererObj, line, 150, ry + 8, 99, MUTED, guiLeft, guiTop);
            ry += 16;
        }
        if (t.gases.any()) {
            gasTip(veh, t, gasRate);
        }
        if (veh.wearOn() && ry <= 90) {
            fontRendererObj.drawString(Lang.tr("sva.hud.hull"), 119, ry, TEXT);
            String line = pct(veh.getHp() / veh.getMaxHp()) + (set(ContainerChargerSVA.M_REPAIR) ? " · " + Lang.tr("sva.st.card.repair") : "");
            TextFitSC.draw(fontRendererObj, line, 150, ry + 8, 99, MUTED, guiLeft, guiTop);
            ry += 16;
        }
        if (ry <= 106) {
            double room = veh.getMaxEnergy() - veh.getEnergy();
            String eta = room < 1 ? Lang.tr("sva.st.card.full") : Lang.tr("sva.st.card.eta", time(PlatformMath.etaSeconds(room, euT)));
            TextFitSC.draw(fontRendererObj, eta, 119, 109, 130, room < 1 ? GREEN : CYAN, guiLeft, guiTop);
        }
    }

    /** ТИ-3: every tank of the vehicle (the card has room for two) and the refuelling rate, on hover over the card. */
    private void gasTip(EntityVehicleSVA veh, VehicleType t, int gasRate) {
        List<String> tip = new ArrayList<String>();
        tip.add(Lang.tr("sva.st.card.gasTip"));
        for (Gas gas : t.gases.gases()) {
            tip.add("§7" + Lang.tr("sva.st.card.gasLine", Lang.tr("sva.gas." + gas.key()), num(veh.getGas(gas)), num(veh.getGasCapacity(gas))));
        }
        tip.add(gasRate > 0 ? "§a" + Lang.tr("sva.st.card.gasRate", num(gasRate), Integer.valueOf(PlatformMath.GAS_RATE))
                : "§8" + Lang.tr("sva.st.card.gasIdle", Integer.valueOf(PlatformMath.GAS_RATE)));
        TextFitSC.hover(guiLeft + 117, guiTop + 40, 134, 66, tip);
    }

    private void drawRight(int mx, int my) {
        fontRendererObj.drawString(Lang.tr("sva.st.upgrades"), 258, 21, CYAN);
        fontRendererObj.drawString(Lang.tr("sva.st.modules"), 258, 51, CYAN);
        String[] upg = {"sva.st.upg.oc", "sva.st.upg.transformer", "sva.st.upg.storage", "sva.st.upg.any"};
        for (int i = 0; i < TileEntityChargerSVA.UPGRADE_SLOTS; i++) {
            int sx = ContainerChargerSVA.UPG_X + i * ContainerChargerSVA.UPG_STEP, sy = ContainerChargerSVA.UPG_Y;
            if (tile.getStackInSlot(TileEntityChargerSVA.FIRST_UPGRADE + i) == null) {
                List<String> t = new ArrayList<String>();
                t.add(Lang.tr(upg[i]));
                t.add("§7" + Lang.tr("sva.st.upg.hint"));
                TextFitSC.hover(guiLeft + sx - 1, guiTop + sy - 1, 18, 18, t);
            }
        }
        String[] mods = {"sva.st.mod.gas", "sva.st.mod.repair", "sva.st.mod.access"};
        for (int i = 0; i < TileEntityChargerSVA.MODULE_SLOTS; i++) {
            int sx = ContainerChargerSVA.MOD_X + i * ContainerChargerSVA.MOD_STEP, sy = ContainerChargerSVA.MOD_Y;
            if (tile.getStackInSlot(TileEntityChargerSVA.FIRST_MODULE + i) == null) {
                List<String> t = new ArrayList<String>();
                t.add(Lang.tr("sva.st.mod.slot"));
                t.add("§7" + Lang.tr(mods[i]));
                TextFitSC.hover(guiLeft + sx - 1, guiTop + sy - 1, 18, 18, t);
                fontRendererObj.drawString("M", sx + 5, sy + 4, 0x4A5262);
            }
        }
        String[] names = {"sva.st.set.mine", "sva.st.set.redstone", "sva.st.set.sound", "sva.st.set.gas"};
        for (int i = 0; i < names.length; i++) {
            int cy = SET_Y + i * SET_STEP;
            TextFitSC.draw(fontRendererObj, Lang.tr(names[i]), SET_X + 11, cy, 86, i == 0 ? MUTED : TEXT, guiLeft, guiTop);
            List<String> t = new ArrayList<String>();
            t.add(Lang.tr(names[i]));
            t.add("§7" + Lang.tr(names[i] + ".tip"));
            TextFitSC.hover(guiLeft + SET_X, guiTop + cy, 96, SET_STEP, t);
        }
        int e = v(ContainerChargerSVA.V_E), max = v(ContainerChargerSVA.V_MAX);
        List<String> g = new ArrayList<String>();
        g.add(Lang.tr("sva.st.gauge", FormatSVA.eu(e), FormatSVA.eu(max)));
        g.add("§7" + Lang.tr("sva.st.gauge.in", Tier.byOrdinal(v(ContainerChargerSVA.V_TIER)).name()));
        TextFitSC.hover(guiLeft + 337, guiTop + 21, 14, 56, g);
    }

    private void drawStatus() {
        EntityVehicleSVA veh = vehicle();
        List<String> parts = new ArrayList<String>();
        int c = GREEN;
        if (veh == null) {
            if (st(TileEntityChargerSVA.S_NO_FIT)) {
                parts.add(Lang.tr("sva.st.s.noFit"));
                c = RED;
            } else if (st(TileEntityChargerSVA.S_NO_ACCESS)) {
                parts.add(Lang.tr("sva.st.s.noAccess"));
                c = YELLOW;
            } else {
                parts.add(Lang.tr(v(ContainerChargerSVA.V_PLATES) > 0 ? "sva.st.s.idle" : "sva.st.s.idleLone"));
                c = MUTED;
            }
        } else {
            parts.add(Lang.tr("sva.st.s.on", new ItemStack(ModContentSVA.vehicle, 1, veh.getVehicleType().ordinal()).getDisplayName()));
            if (st(TileEntityChargerSVA.S_OFF)) {
                parts.add(Lang.tr("sva.st.s.off"));
                c = YELLOW;
            } else if (st(TileEntityChargerSVA.S_CHARGING)) {
                parts.add(Lang.tr("sva.st.s.charging"));
            } else if (st(TileEntityChargerSVA.S_FULL)) {
                parts.add(Lang.tr("sva.st.s.full"));
            } else if (st(TileEntityChargerSVA.S_NO_ENERGY)) {
                parts.add(Lang.tr("sva.st.s.noEnergy"));
                c = YELLOW;
            }
            if (st(TileEntityChargerSVA.S_REFUEL)) {
                parts.add(Lang.tr("sva.st.s.refuel"));
            }
            if (st(TileEntityChargerSVA.S_REPAIR) || set(ContainerChargerSVA.M_REPAIR) && veh.wearOn() && veh.getHp() < veh.getMaxHp()
                    && v(ContainerChargerSVA.V_STEEL) > 0) {
                parts.add(Lang.tr("sva.st.s.repair"));
            } else if (set(ContainerChargerSVA.M_REPAIR) && veh.wearOn() && veh.getHp() < veh.getMaxHp()) {
                parts.add(Lang.tr("sva.st.s.noSteel"));
                c = YELLOW;
            }
            if (st(TileEntityChargerSVA.S_NO_FIT)) {
                parts.add(Lang.tr("sva.st.s.otherNoFit"));
                c = YELLOW;
            }
        }
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (b.length() > 0) {
                b.append(" · ");
            }
            b.append(p);
        }
        TextFitSC.draw(fontRendererObj, b.toString(), 8, 126, 344, c, guiLeft, guiTop);
    }

    private void drawDevices() {
        fontRendererObj.drawString(Lang.tr("sva.st.connected"), 8, 142, CYAN);
        // the gas column
        boolean col = v(ContainerChargerSVA.V_COLUMN) > 0;
        TextFitSC.draw(fontRendererObj, Lang.tr("tile.siliconagevehicles.gasColumn.name"), 29, 155, 150, col ? TEXT : MUTED, guiLeft, guiTop);
        String d;
        if (!col) {
            d = Lang.tr("sva.st.dev.noColumn");
        } else {
            StringBuilder b = new StringBuilder();
            for (Gas g : Gas.values()) {
                int mb = g.ordinal() < 8 ? v(ContainerChargerSVA.V_COL_GAS + g.ordinal()) : 0;
                if (mb > 0) {
                    if (b.length() > 0) {
                        b.append(" · ");
                    }
                    b.append(Lang.tr("sva.gas." + g.key())).append(' ').append(num(mb)).append(' ').append(Lang.tr("sva.st.mb"));
                }
            }
            d = b.length() == 0 ? Lang.tr("sva.st.dev.columnEmpty") : b.toString();
            if (!set(ContainerChargerSVA.M_GAS)) {
                d = Lang.tr("sva.st.dev.needGasModule") + " · " + d;
            }
        }
        TextFitSC.draw(fontRendererObj, d, 29, 165, 152, MUTED, guiLeft, guiTop);
        // the repair chest
        boolean chest = v(ContainerChargerSVA.V_CHESTS) > 0;
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.st.dev.chest"), 29, 182, 150, chest ? TEXT : MUTED, guiLeft, guiTop);
        String cd = !chest ? Lang.tr("sva.st.dev.noChest") : Lang.tr("sva.st.dev.steel", Integer.valueOf(v(ContainerChargerSVA.V_STEEL)));
        if (chest && !set(ContainerChargerSVA.M_REPAIR)) {
            cd = Lang.tr("sva.st.dev.needRepairModule") + " · " + cd;
        }
        TextFitSC.draw(fontRendererObj, cd, 29, 192, 152, MUTED, guiLeft, guiTop);
        // the lights
        int edge = v(ContainerChargerSVA.V_EDGE), avia = v(ContainerChargerSVA.V_AVIA);
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.st.dev.lights"), 29, 209, 150, edge > 0 ? TEXT : MUTED, guiLeft, guiTop);
        String ld = edge > 0 ? Lang.tr("sva.st.dev.lightsOn", Integer.valueOf(edge)) : Lang.tr("sva.st.dev.noLights");
        if (avia > 0) {
            ld += " · " + Lang.tr("sva.st.dev.avia", Integer.valueOf(avia));
        }
        TextFitSC.draw(fontRendererObj, ld, 29, 219, 152, MUTED, guiLeft, guiTop);
    }
}
