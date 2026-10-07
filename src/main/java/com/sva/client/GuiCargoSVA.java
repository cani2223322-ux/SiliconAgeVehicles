package com.sva.client;

import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sva.entity.EntityVehicleSVA;
import com.sva.inventory.ContainerCargoSVA;
import com.sva.net.NetSVA;
import com.sva.vehicle.AutoDelivery;
import com.sva.vehicle.VehicleType;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;

/**
 * Cargo screen: up to 9 slots on the vanilla dispenser background; more on the large chest one, 54 a page with
 * page buttons («Кузов» / «Прицеп» for the crawler, «Бункер 1 / 2» for the drill). «Убрать в инвентарь» above it
 * (the server checks owner / seats). Right of it, per vehicle: the crawler - auto-delivery on / off, the route (A / B
 * from a Coordinator), its state, «Сбросить маршрут», «Отцепить прицеп»; the drill - «Бур: вкл / выкл», the mode;
 * its head and module slots sit in a strip on the left.
 */
public class GuiCargoSVA extends GuiContainer {

    private static final ResourceLocation BG = new ResourceLocation("textures/gui/container/dispenser.png");
    private static final ResourceLocation CHEST = new ResourceLocation("textures/gui/container/generic_54.png");
    private static final int BTN_PICK = 0, BTN_PREV = 1, BTN_NEXT = 2, BTN_AUTO = 3, BTN_ROUTE_CLEAR = 4, BTN_DETACH = 5,
            BTN_DRILL = 6, BTN_MODE = 7;
    private static final int SIDE_W = 132;
    private final EntityVehicleSVA vehicle;
    private final InventoryPlayer inv;
    private final ContainerCargoSVA box;
    private int sentAt = -100;
    private GuiButton prev, next, auto, routeClear, detach, drill, mode;

    public GuiCargoSVA(InventoryPlayer inv, EntityVehicleSVA v) {
        super(new ContainerCargoSVA(inv, v));
        this.vehicle = v;
        this.inv = inv;
        this.box = (ContainerCargoSVA) inventorySlots;
        if (box.chest) {
            xSize = 176 + box.xOff;
            ySize = 114 + box.rows * 18;
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        int w = 140;
        buttonList.add(new GuiButton(BTN_PICK, (width - w) / 2, guiTop - 24, w, 20, Lang.tr("sva.gui.pickUp")));
        if (box.pages() > 1) {
            prev = new GuiButton(BTN_PREV, guiLeft + xSize - 40, guiTop + 3, 16, 12, "<");
            next = new GuiButton(BTN_NEXT, guiLeft + xSize - 22, guiTop + 3, 16, 12, ">");
            buttonList.add(prev);
            buttonList.add(next);
        }
        VehicleType t = vehicle.getVehicleType();
        int sx = guiLeft + xSize + 4, sy = guiTop;
        if (t.autoDelivery) {
            auto = new GuiButton(BTN_AUTO, sx, sy, SIDE_W, 20, "");
            routeClear = new GuiButton(BTN_ROUTE_CLEAR, sx, sy + 70, SIDE_W, 20, Lang.tr("sva.gui.route.clear"));
            detach = new GuiButton(BTN_DETACH, sx, sy + 96, SIDE_W, 20, Lang.tr("sva.gui.trailer.detach"));
            buttonList.add(auto);
            buttonList.add(routeClear);
            buttonList.add(detach);
        }
        if (t.hasDrill) {
            drill = new GuiButton(BTN_DRILL, sx, sy, SIDE_W, 20, "");
            mode = new GuiButton(BTN_MODE, sx, sy + 24, SIDE_W, 20, "");
            buttonList.add(drill);
            buttonList.add(mode);
        }
        refresh();
    }

    /** Button labels / states from the synced vehicle. */
    private void refresh() {
        if (prev != null) {
            prev.enabled = box.page() > 0;
            next.enabled = box.page() < box.pages() - 1;
        }
        if (auto != null) {
            auto.displayString = Lang.tr(vehicle.isAutoOn() ? "sva.gui.auto.on" : "sva.gui.auto.off");
            detach.visible = vehicle.hasTrailer();
        }
        if (drill != null) {
            drill.displayString = Lang.tr(vehicle.isDrillOn() ? "sva.gui.drill.on" : "sva.gui.drill.off");
            mode.displayString = Lang.tr("sva.gui.drill.mode", Lang.tr("sva.drill.mode." + vehicle.getDrillMode()));
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        refresh();
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id == BTN_PREV || b.id == BTN_NEXT) {
            box.setPage(box.page() + (b.id == BTN_NEXT ? 1 : -1));
            refresh();
            return;
        }
        if (mc.thePlayer.ticksExisted - sentAt <= 6) {
            return;                                                 // one press, not a flood
        }
        sentAt = mc.thePlayer.ticksExisted;
        int id = vehicle.getEntityId();
        switch (b.id) {
            case BTN_PICK:
                NetSVA.CHANNEL.sendToServer(new NetSVA.PickUp(id));
                break;
            case BTN_AUTO:
                NetSVA.CHANNEL.sendToServer(new NetSVA.Action(id, EntityVehicleSVA.A_AUTO_TOGGLE));
                break;
            case BTN_ROUTE_CLEAR:
                NetSVA.CHANNEL.sendToServer(new NetSVA.Action(id, EntityVehicleSVA.A_ROUTE_CLEAR));
                break;
            case BTN_DETACH:
                NetSVA.CHANNEL.sendToServer(new NetSVA.Action(id, EntityVehicleSVA.A_TRAILER_DETACH));
                break;
            case BTN_DRILL:
                NetSVA.CHANNEL.sendToServer(new NetSVA.Action(id, EntityVehicleSVA.A_DRILL_TOGGLE));
                break;
            case BTN_MODE:
                NetSVA.CHANNEL.sendToServer(new NetSVA.Action(id, EntityVehicleSVA.A_DRILL_MODE));
                break;
            default:
                break;
        }
    }

    /** The page's name: the crawler's body / trailer, the drill's bunker halves, else «стр. N / M». */
    private String title() {
        String base = Lang.tr("container.siliconagevehicles.cargo");
        if (box.pages() <= 1) {
            return base;
        }
        VehicleType t = vehicle.getVehicleType();
        if (t.trailerSlots > 0) {
            return Lang.tr(box.page() == 0 ? "sva.gui.page.body" : "sva.gui.page.trailer");
        }
        return Lang.tr("sva.gui.page", base, Integer.valueOf(box.page() + 1), Integer.valueOf(box.pages()));
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mx, int my) {
        String title = title();
        if (box.chest) {
            fontRendererObj.drawString(title, box.xOff + 8, 6, 0x404040);
        } else {
            fontRendererObj.drawString(title, xSize / 2 - fontRendererObj.getStringWidth(title) / 2, 6, 0x404040);
        }
        fontRendererObj.drawString(Lang.tr(inv.getInventoryName()), box.xOff + 8, ySize - 96 + 2, 0x404040);
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        super.drawScreen(mx, my, pt);
        VehicleType t = vehicle.getVehicleType();
        int sx = guiLeft + xSize + 4;
        if (t.autoDelivery) {
            String[] r = vehicle.getRoute();
            int y = guiTop + 24;
            fontRendererObj.drawStringWithShadow(Lang.tr("sva.gui.route.a", r[0].length() > 0 ? r[0] : Lang.tr("sva.gui.route.none")), sx, y, 0xE0E0E0);
            fontRendererObj.drawStringWithShadow(Lang.tr("sva.gui.route.b", r[1].length() > 0 ? r[1] : Lang.tr("sva.gui.route.none")), sx, y + 11, 0xE0E0E0);
            fontRendererObj.drawStringWithShadow(autoStatus(vehicle), sx, y + 22, vehicle.getAutoState() == AutoDelivery.STOPPED ? 0xFF6A5A : 0x8FE3FF);
            if (r[0].length() == 0 || r[1].length() == 0) {
                fontRendererObj.drawSplitString(Lang.tr("sva.gui.route.hint"), sx, guiTop + 122, SIDE_W, 0xA0A0A0);
            }
        }
        if (t.hasDrill) {
            int y = guiTop + 50;
            int left = vehicle.getHeadLeft(), max = vehicle.getHeadMax();
            String head = !vehicle.hasHead() ? Lang.tr("sva.hud.drill.noHead")
                    : left < 0 ? Lang.tr("sva.hud.drill.headForever") : Lang.tr("sva.hud.drill.head", fmt(left), fmt(max));
            fontRendererObj.drawStringWithShadow(head, sx, y, vehicle.hasHead() ? 0xE0E0E0 : 0xFF6A5A);
            fontRendererObj.drawStringWithShadow(Lang.tr("sva.hud.drill.dug", fmt(vehicle.getDug())), sx, y + 11, 0xE0E0E0);
            fontRendererObj.drawStringWithShadow(Lang.tr(vehicle.hasWidener() ? "sva.gui.drill.bore5" : "sva.gui.drill.bore3"), sx, y + 22, 0xA0A0A0);
            fontRendererObj.drawSplitString(Lang.tr("sva.gui.drill.hint"), sx, y + 36, SIDE_W, 0xA0A0A0);
        }
    }

    /** «Автодоставка: к точке B · 120 бл.» / «…: погрузка в A» / «…: стоп — путь перекрыт» / «…: выкл». */
    static String autoStatus(EntityVehicleSVA v) {
        int s = v.getAutoState();
        if (!v.isAutoOn() && s != AutoDelivery.STOPPED) {
            return Lang.tr("sva.hud.auto.off");
        }
        if (s == AutoDelivery.STOPPED) {
            return Lang.tr("sva.hud.auto.stopped." + v.getAutoReason());
        }
        return Lang.tr("sva.hud.auto." + s, Integer.valueOf(v.getAutoDist()));
    }

    static String fmt(long n) {
        return com.sva.FormatSVA.group(n);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float pt, int mx, int my) {
        GL11.glColor4f(1F, 1F, 1F, 1F);
        int x = (width - xSize) / 2, y = (height - ySize) / 2;
        if (!box.chest) {
            mc.getTextureManager().bindTexture(BG);
            drawTexturedModalRect(x, y, 0, 0, xSize, ySize);
            return;
        }
        mc.getTextureManager().bindTexture(CHEST);
        int top = box.rows * 18 + 17;
        drawTexturedModalRect(x + box.xOff, y, 0, 0, 176, top);
        drawTexturedModalRect(x + box.xOff, y + top, 0, 126, 176, 96);
        // empty cells of a short last page are greyed
        int shown = Math.max(0, Math.min(54, box.cargoSlots() - box.page() * 54));
        for (int k = shown; k < box.rows * 9; k++) {
            int cx = x + box.xOff + 8 + (k % 9) * 18, cy = y + 18 + (k / 9) * 18;
            Gui.drawRect(cx, cy, cx + 16, cy + 16, 0xFF8B8B8B);
        }
        if (box.xOff > 0) {
            // the drill's strip: head and module slots
            Gui.drawRect(x, y + 4, x + box.xOff + 3, y + 66, 0xFF555555);
            Gui.drawRect(x + 1, y + 5, x + box.xOff + 3, y + 65, 0xFFC6C6C6);
            slotFrame(x + 4, y + 17);
            slotFrame(x + 4, y + 43);
            GL11.glColor4f(1F, 1F, 1F, 1F);
        }
    }

    private static void slotFrame(int x, int y) {
        Gui.drawRect(x, y, x + 18, y + 18, 0xFF373737);
        Gui.drawRect(x + 1, y + 1, x + 18, y + 18, 0xFFFFFFFF);
        Gui.drawRect(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
    }
}
