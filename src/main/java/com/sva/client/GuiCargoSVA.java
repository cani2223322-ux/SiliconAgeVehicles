package com.sva.client;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.sc.energy.Tier;
import com.sc.inventory.TextFitSC;
import com.sc.item.ItemDrillHeadSC;
import com.sc.manual.Lang;
import com.sc.util.ArmorGasSC.Gas;
import com.sva.ConfigSVA;
import com.sva.FormatSVA;
import com.sva.entity.DrillLogicSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.inventory.CargoToolsSVA;
import com.sva.inventory.ContainerCargoSVA;
import com.sva.item.ComponentType;
import com.sva.net.NetSVA;
import com.sva.vehicle.AutoDelivery;
import com.sva.vehicle.CrawlerMath;
import com.sva.vehicle.DrillMath;
import com.sva.vehicle.Movement;
import com.sva.vehicle.TrailerMath;
import com.sva.vehicle.VehicleType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

/**
 * The cargo screen v2 (mock-ups docs/mockups/veh_12_cargo_v2.png and veh_12_cargo_cargo / drill / delivery / vehicle):
 * a dark 214 x 236 window (fits a 427 x 240 screen) with the vehicle's name, its tier and a small «Убрать» in the
 * title bar and icon tabs on the left:
 * <ul>
 * <li>«Груз» (every vehicle): the cargo grid 9 x 6 a page with page buttons (the crawler: «Кузов» / «Прицеп», the
 * drill: «Бункер»), a search field that dims the slots that don't match, «Сорт.» and «Мусор» (server), «Заполнено N / M»;</li>
 * <li>«Бур» (drill): the labelled head slot with its name, speed and wear, the module slot (a ghost Bore Widener when
 * empty), «Бур: ВКЛ / ВЫКЛ (B)», the three mode buttons, the zone, EU a block, blocks dug + torches, the argon field;</li>
 * <li>«Автодоставка» (crawler): the route A / B from a Coordinator, on / off, «Сбросить маршрут», the state, the trailer
 * with «Отцепить»;</li>
 * <li>«Машина» (every vehicle): icon, name, owner, seats, bars for EU (with the range), heat, hull and each gas tank,
 * «Убрать в инвентарь» and the vehicle's keys (their current bindings).</li>
 * </ul>
 * Nothing is drawn outside the window. Every button has a tooltip.
 */
public class GuiCargoSVA extends GuiContainer {

    private static final int W = ContainerCargoSVA.W, H = ContainerCargoSVA.H, X0 = 30, CW = 162;
    private static final int BTN_PICK_SMALL = 0, BTN_PREV = 1, BTN_NEXT = 2, BTN_AUTO = 3, BTN_ROUTE_CLEAR = 4, BTN_DETACH = 5,
            BTN_DRILL = 6, BTN_MODE = 7, BTN_SORT = 10, BTN_JUNK = 11, BTN_PICK = 12, BTN_ACCESS = 13, BTN_TAB = 20,
            /** ★15 the «Прицеп» tab. */
            BTN_REFUEL = 30, BTN_DUMP = 31, BTN_LOAD = 32, BTN_UNLOAD = 33, BTN_TDETACH = 34;
    private static final int BG = 0xFF1B1F27, EDGE = 0xFF0B0D11, PANEL = 0xFF232833, PANEL_HI = 0xFF2E3542, SLOT = 0xFF2B313C,
            SLOT_DARK = 0xFF0D1015, LINE = 0xFF3A4150, CYAN = 0x5EC8F0, TEXT = 0xD8DCE4, MUTED = 0x8A93A3, GREEN = 0x62E06A,
            RED = 0xF06060, YELLOW = 0xF0D060;
    /** The tab shown last (kept while the game runs). */
    private static int lastTab = ContainerCargoSVA.TAB_CARGO;
    private static String lastSearch = "";

    private final EntityVehicleSVA vehicle;
    private final ContainerCargoSVA box;
    private final VehicleType type;
    private int sentAt = -100;
    private GuiTextField search;
    private final List<Dark> tabButtons = new ArrayList<Dark>();
    private final List<int[]> tabsShown = new ArrayList<int[]>();
    private Dark pickSmall, prev, next, sort, junk, auto, routeClear, detach, drill, pick, access;
    /** ★15 the «Прицеп» tab's buttons. */
    private Dark refuel, dump, load, unload, tDetach;
    private final Dark[] modes = new Dark[DrillMath.MODES];

    public GuiCargoSVA(InventoryPlayer inv, EntityVehicleSVA v) {
        super(new ContainerCargoSVA(inv, v));
        this.vehicle = v;
        this.box = (ContainerCargoSVA) inventorySlots;
        this.type = v.getVehicleType();
        xSize = W;
        ySize = H;
    }

    private boolean hasSpecial() {
        return type.hasDrill || type.autoDelivery;
    }

    /** ★15: a tanker / platform / charger is hitched - the «Прицеп» tab. */
    private boolean hasTrailerTab() {
        return type.trailerSlots > 0 && vehicle.hasTrailer() && vehicle.trailerKind() != TrailerMath.CARGO;
    }

    // ------------------------------------------------------------------ setup

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        tabButtons.clear();
        int x = guiLeft, y = guiTop;
        List<Integer> tl = new ArrayList<Integer>();
        tl.add(Integer.valueOf(ContainerCargoSVA.TAB_CARGO));
        if (hasSpecial()) {
            tl.add(Integer.valueOf(ContainerCargoSVA.TAB_SPECIAL));
        }
        if (hasTrailerTab()) {
            tl.add(Integer.valueOf(ContainerCargoSVA.TAB_TRAILER));      // ★15
        }
        tl.add(Integer.valueOf(ContainerCargoSVA.TAB_VEHICLE));
        int[] tabs = new int[tl.size()];
        for (int i = 0; i < tabs.length; i++) {
            tabs[i] = tl.get(i).intValue();
        }
        for (int i = 0; i < tabs.length; i++) {
            Dark tb = new Dark(BTN_TAB + tabs[i], x + 3, y + 19 + i * 24, 22, 22, "", tip("tab." + tabName(tabs[i])));
            tb.icon = tabIcon(tabs[i]);
            tb.tab = true;
            tabButtons.add(tb);
            buttonList.add(tb);
        }
        pickSmall = add(new Dark(BTN_PICK_SMALL, x + W - 44, y + 2, 40, 12, Lang.tr("sva.gui.pickShort"), tip("pick")));
        // cargo tab
        prev = add(new Dark(BTN_PREV, x + 167, y + 18, 12, 11, "<", tip("prev")));
        next = add(new Dark(BTN_NEXT, x + 181, y + 18, 12, 11, ">", tip("next")));
        sort = add(new Dark(BTN_SORT, x + 122, y + 31, 34, 12, Lang.tr("sva.gui.sort"), tip("sort")));
        junk = add(new Dark(BTN_JUNK, x + 158, y + 31, 35, 12, Lang.tr("sva.gui.junk"),
                tip(type.hasDrill ? "junkDrill" : "junk", Integer.valueOf(EntityVehicleSVA.JUNK_KEEP_COBBLE))));
        String old = search == null ? lastSearch : search.getText();
        search = new GuiTextField(fontRendererObj, x + X0 + 3, y + 33, 84, 10);
        search.setEnableBackgroundDrawing(false);
        search.setMaxStringLength(32);
        search.setText(old);
        // drill tab
        if (type.hasDrill) {
            drill = add(new Dark(BTN_DRILL, x + X0, y + 78, CW, 13, "", tip("drill", key(com.sva.vehicle.VehicleFn.DRILL))));
            for (int m = 0; m < DrillMath.MODES; m++) {
                modes[m] = add(new Dark(BTN_MODE + m, x + X0 + m * 55, y + 93, 52, 13, Lang.tr("sva.drill.mode." + m), tip("mode." + m)));
            }
        }
        // auto-delivery tab
        if (type.autoDelivery) {
            auto = add(new Dark(BTN_AUTO, x + X0, y + 62, 80, 13, "", tip("auto")));
            routeClear = add(new Dark(BTN_ROUTE_CLEAR, x + X0 + 82, y + 62, 80, 13, Lang.tr("sva.gui.route.clear"), tip("routeClear")));
            detach = add(new Dark(BTN_DETACH, x + X0 + 110, y + 126, 52, 13, Lang.tr("sva.gui.trailer.detachShort"), tip("detach")));
        }
        // ★15 the trailer tab
        refuel = add(new Dark(BTN_REFUEL, x + X0, y + 114, 126, 13, Lang.tr("sva.gui.tank.refuel"),
                tip("tankRefuel", Integer.valueOf((int) TrailerMath.REFUEL_RADIUS))));
        dump = add(new Dark(BTN_DUMP, x + X0 + 128, y + 114, 34, 13, Lang.tr("sva.gui.tank.dump"), tip("tankDump")));
        load = add(new Dark(BTN_LOAD, x + X0, y + 86, 80, 13, Lang.tr("sva.gui.platform.load"),
                tip("platformLoad", Integer.valueOf((int) TrailerMath.LOAD_RADIUS))));
        unload = add(new Dark(BTN_UNLOAD, x + X0 + 82, y + 86, 80, 13, Lang.tr("sva.gui.platform.unload"), tip("platformUnload")));
        tDetach = add(new Dark(BTN_TDETACH, x + X0 + 110, y + 132, 52, 13, Lang.tr("sva.gui.trailer.detachShort"), tip("detach")));
        // vehicle tab
        pick = add(new Dark(BTN_PICK, x + X0, y + 127, 104, 13, Lang.tr("sva.gui.pickUp"), tip("pick")));
        access = add(new Dark(BTN_ACCESS, x + X0 + 106, y + 127, 56, 13, Lang.tr("sva.gui.access"), tip("access")));
        int t = lastTab == ContainerCargoSVA.TAB_SPECIAL && !hasSpecial() || lastTab == ContainerCargoSVA.TAB_TRAILER && !hasTrailerTab()
                ? ContainerCargoSVA.TAB_CARGO : lastTab;
        box.setView(t, box.page());
        refresh();
    }

    private Dark add(Dark b) {
        buttonList.add(b);
        return b;
    }

    private static String tabName(int tab) {
        return tab == ContainerCargoSVA.TAB_CARGO ? "cargo" : tab == ContainerCargoSVA.TAB_VEHICLE ? "vehicle"
                : tab == ContainerCargoSVA.TAB_TRAILER ? "trailer" : "special";
    }

    private String tip(String k, Object... args) {
        if ("tab.special".equals(k)) {
            k = type.hasDrill ? "tab.drill" : "tab.delivery";
        }
        return Lang.tr("sva.gui.tip." + k, args);
    }

    private ItemStack tabIcon(int tab) {
        if (tab == ContainerCargoSVA.TAB_CARGO) {
            return new ItemStack(Blocks.chest);
        }
        if (tab == ContainerCargoSVA.TAB_VEHICLE) {
            return new ItemStack(ModContentSVA.vehicle, 1, type.ordinal());
        }
        if (tab == ContainerCargoSVA.TAB_TRAILER) {
            return new ItemStack(ModContentSVA.trailer, 1, vehicle.trailerKind());
        }
        if (type.hasDrill) {
            return com.sc.init.ModItems.DRILL_HEADS.isEmpty() ? new ItemStack(net.minecraft.init.Items.diamond_pickaxe)
                    : new ItemStack(com.sc.init.ModItems.DRILL_HEADS.get(Math.min(2, com.sc.init.ModItems.DRILL_HEADS.size() - 1)));
        }
        return com.sc.init.ModItems.coordinator != null ? new ItemStack(com.sc.init.ModItems.coordinator) : new ItemStack(net.minecraft.init.Items.compass);
    }

    private static String key(com.sva.vehicle.VehicleFn f) {
        return VehicleKeyBindsSVA.name(f);
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
        lastTab = box.tab();
        if (search != null) {
            lastSearch = search.getText();
        }
    }

    /** Which buttons show on the current tab, labels and states from the synced vehicle. */
    private void refresh() {
        int tab = box.tab();
        boolean cargo = tab == ContainerCargoSVA.TAB_CARGO, special = tab == ContainerCargoSVA.TAB_SPECIAL, veh = tab == ContainerCargoSVA.TAB_VEHICLE;
        for (Dark tb : tabButtons) {
            tb.active = tb.id - BTN_TAB == tab;
        }
        prev.visible = next.visible = cargo && box.pages() > 1;
        prev.enabled = box.page() > 0;
        next.enabled = box.page() < box.pages() - 1;
        sort.visible = junk.visible = cargo;
        pick.visible = veh;
        String o = vehicle.getOwnerName();
        access.visible = veh && vehicle.locksOn() && (o.length() == 0 || o.equalsIgnoreCase(mc.thePlayer.getCommandSenderName())
                || mc.thePlayer.capabilities.isCreativeMode);
        boolean tr = tab == ContainerCargoSVA.TAB_TRAILER && hasTrailerTab();
        int kind = vehicle.trailerKind();
        refuel.visible = dump.visible = tr && kind == TrailerMath.TANK;
        refuel.enabled = dump.enabled = vehicle.tankAmount() > 0;
        load.visible = unload.visible = tr && kind == TrailerMath.PLATFORM;
        boolean carrying = vehicle.carried()[0] >= 0;
        load.enabled = !carrying;
        unload.enabled = carrying;
        tDetach.visible = tr;
        if (drill != null) {
            drill.visible = special;
            boolean on = vehicle.isDrillOn();
            drill.displayString = Lang.tr(on ? "sva.gui.drill.onKey" : "sva.gui.drill.offKey", key(com.sva.vehicle.VehicleFn.DRILL));
            drill.activeColor = on ? GREEN : 0;
            drill.active = on;
            for (int m = 0; m < modes.length; m++) {
                modes[m].visible = special;
                modes[m].active = vehicle.getDrillMode() == m;
            }
        }
        if (auto != null) {
            auto.visible = routeClear.visible = special;
            boolean on = vehicle.isAutoOn();
            auto.displayString = Lang.tr(on ? "sva.gui.auto.on" : "sva.gui.auto.off");
            auto.active = on;
            auto.activeColor = on ? GREEN : 0;
            detach.visible = special && vehicle.hasTrailer();
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (search != null) {
            search.updateCursorCounter();
        }
        refresh();
    }

    // ------------------------------------------------------------------ input

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id >= BTN_TAB) {
            box.setView(b.id - BTN_TAB, box.page());
            lastTab = box.tab();
            refresh();
            return;
        }
        if (b.id == BTN_PREV || b.id == BTN_NEXT) {
            box.setView(box.tab(), box.page() + (b.id == BTN_NEXT ? 1 : -1));
            refresh();
            return;
        }
        if (mc.thePlayer.ticksExisted - sentAt <= 6) {
            return;                                                 // one press, not a flood
        }
        sentAt = mc.thePlayer.ticksExisted;
        int id = vehicle.getEntityId();
        int action = -1;
        switch (b.id) {
            case BTN_PICK:
            case BTN_PICK_SMALL:
                NetSVA.CHANNEL.sendToServer(new NetSVA.PickUp(id));
                return;
            case BTN_ACCESS:
                mc.thePlayer.closeScreen();                         // the server's cargo container closes cleanly first
                NetSVA.CHANNEL.sendToServer(new NetSVA.Action(id, EntityVehicleSVA.A_ACCESS));
                return;
            case BTN_AUTO:
                action = EntityVehicleSVA.A_AUTO_TOGGLE;
                break;
            case BTN_ROUTE_CLEAR:
                action = EntityVehicleSVA.A_ROUTE_CLEAR;
                break;
            case BTN_DETACH:
            case BTN_TDETACH:
                action = EntityVehicleSVA.A_TRAILER_DETACH;
                break;
            case BTN_REFUEL:
                action = EntityVehicleSVA.A_TANK_REFUEL;
                break;
            case BTN_DUMP:
                if (!isShiftKeyDown()) {
                    return;                                         // «Вылить» only with Shift (its tooltip says so)
                }
                action = EntityVehicleSVA.A_TANK_DUMP;
                break;
            case BTN_LOAD:
                action = EntityVehicleSVA.A_PLATFORM_LOAD;
                break;
            case BTN_UNLOAD:
                action = EntityVehicleSVA.A_PLATFORM_UNLOAD;
                break;
            case BTN_DRILL:
                action = EntityVehicleSVA.A_DRILL_TOGGLE;
                break;
            case BTN_SORT:
                action = EntityVehicleSVA.A_SORT;
                break;
            case BTN_JUNK:
                action = EntityVehicleSVA.A_JUNK;
                break;
            default:
                if (b.id >= BTN_MODE && b.id < BTN_MODE + DrillMath.MODES) {
                    action = EntityVehicleSVA.A_MODE_SET + b.id - BTN_MODE;
                }
                break;
        }
        if (action >= 0) {
            NetSVA.CHANNEL.sendToServer(new NetSVA.Action(id, action));
        }
    }

    @Override
    protected void keyTyped(char c, int k) {
        if (search != null && search.isFocused() && box.tab() == ContainerCargoSVA.TAB_CARGO && k != Keyboard.KEY_ESCAPE) {
            search.textboxKeyTyped(c, k);
            return;
        }
        super.keyTyped(c, k);
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        super.mouseClicked(mx, my, button);
        if (search != null && box.tab() == ContainerCargoSVA.TAB_CARGO) {
            search.mouseClicked(mx, my, button);
            if (button == 1 && inside(mx, my, guiLeft + X0, guiTop + 31, 88, 12)) {
                search.setText("");
            }
        } else if (search != null) {
            search.setFocused(false);
        }
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------ data

    private String query() {
        return search == null ? "" : search.getText().trim();
    }

    /** Does the stack match the search (display name, registry name, ore names)? */
    static boolean matches(String q, ItemStack s) {
        if (s == null) {
            return false;
        }
        List<String> texts = new ArrayList<String>();
        try {
            texts.add(s.getDisplayName());
        } catch (RuntimeException e) {
            // a broken item: matched by its registry name only
        }
        texts.add(CargoToolsSVA.registryName(s));
        if (s.getItem() != null) {
            for (int id : OreDictionary.getOreIDs(s)) {
                texts.add(OreDictionary.getOreName(id));
            }
        }
        return CargoToolsSVA.matches(q, texts.toArray(new String[texts.size()]));
    }

    /** The page's name: the crawler's body / trailer, the drill's bunker, else «Груз». */
    private String pageTitle() {
        String base = Lang.tr(type.hasDrill ? "sva.gui.page.bunker" : type.trailerSlots > 0
                ? (box.page() == 0 ? "sva.gui.page.body2" : "sva.gui.page.trailer2") : "sva.gui.page.cargo");
        if (box.pages() <= 1 || type.trailerSlots > 0) {
            return base;
        }
        return Lang.tr("sva.gui.page", base, Integer.valueOf(box.page() + 1), Integer.valueOf(box.pages()));
    }

    private static int tierColour(Tier t) {
        switch (t) {
            case LV: return 0xFFB0B8C0;
            case MV: return 0xFFF0B838;
            case HV: return 0xFFF08A30;
            case EV: return 0xFFE04848;
            case IV: return 0xFF8090A0;
            case QV: return 0xFF4AB8F0;
            case XV: return 0xFF50E0B0;
            default: return 0xFFB070F0;
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void drawScreen(int mx, int my, float pt) {
        TextFitSC.beginFrame();
        super.drawScreen(mx, my, pt);
        for (Object o : buttonList) {
            Dark b = o instanceof Dark ? (Dark) o : null;
            if (b != null && b.visible && b.tip != null && b.tip.length() > 0) {
                TextFitSC.hover(b.xPosition, b.yPosition, b.width, b.height, split(b.tip));
            }
        }
        if (box.tab() == ContainerCargoSVA.TAB_SPECIAL && type.hasDrill) {
            if (vehicle.getStackInSlot(vehicle.headSlot()) == null) {
                TextFitSC.hover(guiLeft + X0, guiTop + 18, 26, 26, split(Lang.tr("sva.gui.tip.headSlot")));
            }
            if (vehicle.getStackInSlot(vehicle.headSlot() + 1) == null) {
                TextFitSC.hover(guiLeft + ContainerCargoSVA.MODULE_X - 1, guiTop + ContainerCargoSVA.MODULE_Y - 1, 18, 18,
                        split(Lang.tr("sva.gui.tip.moduleSlot")));
            }
        }
        List<String> tip = TextFitSC.hoverAt(mx, my);
        if (tip != null && !tip.isEmpty() && !overFilledSlot(mx, my)) {
            drawHoveringText(tip, mx, my, fontRendererObj);
        }
    }

    /** A slot with an item under the mouse: its own tooltip wins. */
    private boolean overFilledSlot(int mx, int my) {
        for (Object o : inventorySlots.inventorySlots) {
            Slot s = (Slot) o;
            if (s.getHasStack() && inside(mx, my, guiLeft + s.xDisplayPosition, guiTop + s.yDisplayPosition, 16, 16)) {
                return true;
            }
        }
        return false;
    }

    /** "line 1|line 2" or a long line -> tooltip lines (180 px wide). */
    private List<String> split(String s) {
        List<String> out = new ArrayList<String>();
        for (String part : s.split("\\|")) {
            for (Object l : fontRendererObj.listFormattedStringToWidth(part, 180)) {
                out.add((String) l);
            }
        }
        return out;
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

    /** A bar: dark frame, filled part in colour. */
    private void bar(int x, int y, int w, int h, double frac, int colour) {
        frame(x, y, w, h, EDGE, 0xFF0E1116);
        double f = Double.isNaN(frac) ? 0 : Math.max(0, Math.min(1, frac));
        rect(x + 1, y + 1, (int) Math.round((w - 2) * f), h - 2, 0xFF000000 | colour);
    }

    private void item(ItemStack s, int x, int y) {
        if (s == null) {
            return;
        }
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), s, x, y);
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /** An item drawn faded (an empty slot's hint). */
    private void ghost(ItemStack s, int x, int y) {
        item(s, x, y);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        rect(x, y, 16, 16, 0xA02B313C);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float pt, int mx, int my) {
        int x = guiLeft, y = guiTop;
        GL11.glColor4f(1F, 1F, 1F, 1F);
        frame(x, y, W, H, EDGE, BG);
        rect(x + 1, y + 1, W - 2, 1, LINE);
        rect(x + 3, y + 15, W - 6, 1, tierColour(type.tier));
        // the tier badge left of «Убрать»
        rect(x + W - 70, y + 3, 24, 10, tierColour(type.tier));
        // the player's inventory
        for (Object o : inventorySlots.inventorySlots) {
            Slot s = (Slot) o;
            if (s.xDisplayPosition > -1000) {
                frame(x + s.xDisplayPosition - 1, y + s.yDisplayPosition - 1, 18, 18, SLOT_DARK, SLOT);
            }
        }
        int tab = box.tab();
        if (tab == ContainerCargoSVA.TAB_CARGO) {
            cargoBackground(x, y);
        } else if (tab == ContainerCargoSVA.TAB_SPECIAL && type.hasDrill) {
            drillBackground(x, y);
        } else if (tab == ContainerCargoSVA.TAB_SPECIAL) {
            frame(x + X0, y + 29, 22, 22, CYAN | 0xFF000000, PANEL);
            item(tabIcon(ContainerCargoSVA.TAB_SPECIAL), x + X0 + 3, y + 32);
        } else if (tab == ContainerCargoSVA.TAB_TRAILER) {
            trailerBackground(x, y);
        } else {
            vehicleBackground(x, y);
        }
    }

    // ------------------------------------------------------------------ ★15 the «Прицеп» tab

    private void trailerBackground(int x, int y) {
        if (!hasTrailerTab()) {
            return;
        }
        int kind = vehicle.trailerKind();
        if (kind == TrailerMath.TANK) {
            int amount = vehicle.tankAmount();
            frame(x + X0, y + 30, 26, 78, LINE, 0xFF0E1116);
            int h = (int) Math.round(76 * TrailerMath.tankFill(amount));
            rect(x + X0 + 1, y + 30 + 77 - h, 24, h, 0xFF000000 | RenderVehicleSVA.fluidColour(vehicle.tankFluid(), amount));
            rect(x + X0 + 1, y + 30 + 39, 24, 1, 0x80FFFFFF);       // half full: the speed mark
            frame(x + ContainerCargoSVA.TANK_IN_X - 1, y + ContainerCargoSVA.TANK_Y - 1, 18, 18, SLOT_DARK, SLOT);
            frame(x + ContainerCargoSVA.TANK_OUT_X - 1, y + ContainerCargoSVA.TANK_Y - 1, 18, 18, SLOT_DARK, SLOT);
            if (vehicle.getStackInSlot(vehicle.headSlot()) == null) {
                ghost(new ItemStack(net.minecraft.init.Items.water_bucket), x + ContainerCargoSVA.TANK_IN_X, y + ContainerCargoSVA.TANK_Y);
            }
            if (vehicle.getStackInSlot(vehicle.headSlot() + 1) == null) {
                ghost(new ItemStack(net.minecraft.init.Items.bucket), x + ContainerCargoSVA.TANK_OUT_X, y + ContainerCargoSVA.TANK_Y);
            }
        } else if (kind == TrailerMath.PLATFORM) {
            int[] c = vehicle.carried();
            frame(x + X0, y + 30, CW, 50, c[0] >= 0 ? 0xFF5EC8F0 : LINE, PANEL);
            if (c[0] >= 0) {
                item(new ItemStack(ModContentSVA.vehicle, 1, c[0]), x + X0 + 4, y + 35);
                bar(x + X0 + 26, y + 47, CW - 30, 6, c[2] / 100.0, c[2] < 20 ? RED : GREEN);
            }
        } else if (kind == TrailerMath.CHARGER) {
            bar(x + X0, y + 31, CW, 7, vehicle.chargerEnergy() / TrailerMath.CHARGER_BUFFER, 0x5EC8F0);
            List<int[]> served = vehicle.chargerServed();
            for (int i = 0; i < served.size() && i < 4; i++) {
                item(new ItemStack(ModContentSVA.vehicle, 1, served.get(i)[0]), x + X0, y + 82 + i * 11);
            }
        }
    }

    private void trailerText(int ox, int oy) {
        if (!hasTrailerTab()) {
            return;
        }
        int kind = vehicle.trailerKind();
        String name = new ItemStack(ModContentSVA.trailer, 1, kind).getDisplayName();
        TextFitSC.draw(fontRendererObj, name, X0, 19, CW, CYAN, ox, oy);
        if (kind == TrailerMath.TANK) {
            int amount = vehicle.tankAmount();
            String fluid = amount > 0 ? com.sva.item.ItemTrailerSVA.fluidName(vehicle.tankFluid()) : Lang.tr("sva.gui.tank.empty");
            TextFitSC.draw(fontRendererObj, fluid, 62, 31, 130, amount > 0 ? TEXT : MUTED, ox, oy);
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.tank.amount", fmt(amount), fmt(TrailerMath.TANK_CAP)), 62, 41, 130, MUTED, ox, oy);
            boolean slow = TrailerMath.speedMul(kind, amount, false) < 1;
            TextFitSC.draw(fontRendererObj, Lang.tr(slow ? "sva.gui.trailer.slow" : "sva.gui.trailer.fast",
                    Integer.valueOf((int) Math.round((1 - TrailerMath.TANK_SPEED) * 100))), 62, 51, 130, slow ? YELLOW : MUTED, ox, oy);
            TextFitSC.drawCentered(fontRendererObj, Lang.tr("sva.gui.tank.in"), ContainerCargoSVA.TANK_IN_X - 14, 64, 44, MUTED, false, ox, oy);
            TextFitSC.drawCentered(fontRendererObj, Lang.tr("sva.gui.tank.out"), ContainerCargoSVA.TANK_OUT_X - 14, 64, 44, MUTED, false, ox, oy);
            boolean gas = Gas.of(net.minecraftforge.fluids.FluidRegistry.getFluid(vehicle.tankFluid())) != null;
            TextFitSC.draw(fontRendererObj, Lang.tr(gas || amount <= 0 ? "sva.gui.tank.hintGas" : "sva.gui.tank.hintFluid",
                    Integer.valueOf((int) TrailerMath.REFUEL_RADIUS)), 62, 96, 130, MUTED, ox, oy);
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.tank.hintSlots"), X0, 135, 106, MUTED, ox, oy);
        } else if (kind == TrailerMath.PLATFORM) {
            int[] c = vehicle.carried();
            if (c[0] >= 0) {
                VehicleType ct = VehicleType.byOrdinal(c[0]);
                String vn = new ItemStack(ModContentSVA.vehicle, 1, c[0]).getDisplayName();
                TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.platform.carried", vn, com.sva.vehicle.SkinMath.boardText(c[1])), 56, 35, 134,
                        TEXT, ox, oy);
                TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.platform.charge", Integer.valueOf(c[2])), 56, 57, 134, MUTED, ox, oy);
                TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.platform.tier", ct.tier.name()), 56, 67, 134, MUTED, ox, oy);
            } else {
                int yy = 35;
                for (Object l : fontRendererObj.listFormattedStringToWidth(Lang.tr("sva.gui.platform.empty"), CW - 8)) {
                    if (yy > 70) {
                        break;
                    }
                    fontRendererObj.drawString((String) l, X0 + 4, yy, MUTED);
                    yy += 9;
                }
            }
            boolean slow = c[0] >= 0;
            TextFitSC.draw(fontRendererObj, Lang.tr(slow ? "sva.gui.trailer.slow" : "sva.gui.trailer.fast",
                    Integer.valueOf((int) Math.round((1 - TrailerMath.PLATFORM_SPEED) * 100))), X0, 104, CW, slow ? YELLOW : MUTED, ox, oy);
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.platform.fits"), X0, 114, CW, MUTED, ox, oy);
        } else if (kind == TrailerMath.CHARGER) {
            double e = vehicle.chargerEnergy();
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.charger.buffer", FormatSVA.eu((long) e), FormatSVA.eu(TrailerMath.CHARGER_BUFFER)),
                    X0, 41, CW, TEXT, ox, oy);
            boolean sun = vehicle.chargerSolar();
            TextFitSC.draw(fontRendererObj, sun ? Lang.tr("sva.gui.charger.sun", Integer.valueOf(TrailerMath.SOLAR_EU)) : Lang.tr("sva.gui.charger.noSun"),
                    X0, 52, CW, sun ? GREEN : MUTED, ox, oy);
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.charger.feed", Integer.valueOf(TrailerMath.FEED_EU),
                    Integer.valueOf((int) Math.round(TrailerMath.FEED_FLOOR * 100))), X0, 62, CW, MUTED, ox, oy);
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.charger.serving", Integer.valueOf((int) TrailerMath.CHARGE_RADIUS)), X0, 72, CW,
                    CYAN, ox, oy);
            List<int[]> served = vehicle.chargerServed();
            if (served.isEmpty()) {
                TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.charger.nobody"), X0, 84, CW, MUTED, ox, oy);
            }
            for (int i = 0; i < served.size() && i < 4; i++) {
                int[] v = served.get(i);
                String vn = new ItemStack(ModContentSVA.vehicle, 1, v[0]).getDisplayName();
                TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.platform.carried", vn, com.sva.vehicle.SkinMath.boardText(v[1])), X0 + 18,
                        86 + i * 11, CW - 18, TEXT, ox, oy);
            }
        }
    }

    private void cargoBackground(int x, int y) {
        frame(x + X0, y + 31, 90, 12, search.isFocused() ? 0xFF5EC8F0 : LINE, 0xFF0E1116);
        search.drawTextBox();
        if (search.getText().length() == 0 && !search.isFocused()) {
            fontRendererObj.drawString(Lang.tr("sva.gui.search"), x + X0 + 3, y + 33, 0x5A6272);
        }
        // cells past the end of a short page: no slot there
        int rows = box.rows();
        int shown = Math.max(0, Math.min(CrawlerMath.PAGE, box.cargoSlots() - box.page() * CrawlerMath.PAGE));
        for (int k = shown; k < rows * 9; k++) {
            int cx = x + ContainerCargoSVA.GRID_X + (k % 9) * 18, cy = y + ContainerCargoSVA.GRID_Y + (k / 9) * 18;
            frame(cx - 1, cy - 1, 18, 18, SLOT_DARK, 0xFF15181E);
        }
    }

    private void drillBackground(int x, int y) {
        ItemStack head = vehicle.getStackInSlot(vehicle.headSlot());
        frame(x + X0, y + 18, 26, 26, head != null ? 0xFF5EC8F0 : LINE, PANEL);
        frame(x + ContainerCargoSVA.HEAD_X - 1, y + ContainerCargoSVA.HEAD_Y - 1, 18, 18, SLOT_DARK, SLOT);
        if (head == null && !com.sc.init.ModItems.DRILL_HEADS.isEmpty()) {
            ghost(new ItemStack(com.sc.init.ModItems.DRILL_HEADS.get(0)), x + ContainerCargoSVA.HEAD_X, y + ContainerCargoSVA.HEAD_Y);
        }
        ItemStack module = vehicle.getStackInSlot(vehicle.headSlot() + 1);
        frame(x + ContainerCargoSVA.MODULE_X - 1, y + ContainerCargoSVA.MODULE_Y - 1, 18, 18, module != null ? 0xFF5EC8F0 : SLOT_DARK, SLOT);
        if (module == null) {
            ghost(ModContentSVA.component.stackOf(ComponentType.DRILL_WIDENER, 1), x + ContainerCargoSVA.MODULE_X, y + ContainerCargoSVA.MODULE_Y);
        }
        // wear bar
        int left = vehicle.getHeadLeft(), max = vehicle.getHeadMax();
        if (head != null) {
            double f = left < 0 ? 1 : max <= 0 ? 0 : left / (double) max;
            bar(x + 60, y + 39, 132, 5, f, left < 0 ? CYAN : f > 0.5 ? GREEN : f > 0.2 ? YELLOW : RED);
        } else {
            bar(x + 60, y + 39, 132, 5, 0, LINE);
        }
        // argon field
        Gas fg = type.gases.fieldGas;
        if (fg != null) {
            int cap = vehicle.getGasCapacity(fg);
            bar(x + 48, y + 141, 60, 6, cap <= 0 ? 0 : vehicle.getGas(fg) / (double) cap, fg.color);
        }
    }

    /** Rows of the «Машина» tab: EU, heat, hull, every gas tank. */
    private List<Object[]> vehicleRows() {
        List<Object[]> rows = new ArrayList<Object[]>();
        long e = vehicle.getEnergy();
        if (vehicle.onHydrogen()) {
            // hydrogen wave: the board battery (its slot is top right) - charge and health
            if (!vehicle.batteryPresent()) {
                rows.add(new Object[] {Lang.tr("sva.hud.bat"), Double.valueOf(0), Integer.valueOf(0xF06060), Lang.tr("sva.gui.veh.noBat")});
            } else {
                double h = vehicle.batteryHealth();
                rows.add(new Object[] {Lang.tr("sva.hud.bat"), Double.valueOf(vehicle.batteryFrac()), Integer.valueOf(0x62E06A),
                        Lang.tr("sva.gui.veh.bat", FormatSVA.eu(e), FormatSVA.eu((long) vehicle.getMaxEnergy()), Integer.valueOf((int) Math.round(h * 100)))});
            }
        } else {
            float cruise = vehicle.getCruiseCost();
            String range = cruise > 0 ? FormatSVA.group((long) (e / cruise * type.topSpeed)) : "--";
            rows.add(new Object[] {Lang.tr("sva.hud.eu"), Double.valueOf(e / Math.max(1.0, vehicle.getMaxEnergy())), Integer.valueOf(0xE04848),
                    Lang.tr("sva.gui.veh.eu", FormatSVA.eu(e), FormatSVA.eu((long) vehicle.getMaxEnergy()), range)});
        }
        double heat = vehicle.getHeat();
        rows.add(new Object[] {Lang.tr("sva.hud.heat"), Double.valueOf(heat / 100.0), Integer.valueOf(vehicle.isHeatStopped() ? 0xFF4040 : 0xF0A040),
                (int) heat + "%"});
        float hp = vehicle.getHp();
        rows.add(new Object[] {Lang.tr("sva.hud.hull"), Double.valueOf(hp / Math.max(1F, vehicle.getMaxHp())), Integer.valueOf(vehicle.isWrecked() ? 0xFF4040 : 0x62E06A),
                FormatSVA.group(Math.round(hp)) + " / " + FormatSVA.group(Math.round(vehicle.getMaxHp()))});
        for (Gas g : vehicle.tankList()) {
            int cap = vehicle.getGasCapacity(g), mb = vehicle.getGas(g);
            rows.add(new Object[] {Lang.tr("sva.gas." + g.key()), Double.valueOf(cap <= 0 ? 0 : mb / (double) cap), Integer.valueOf(g.color),
                    Lang.tr("sva.gui.veh.mb", FormatSVA.group(mb))});
        }
        return rows;
    }

    private int rowStep(int n) {
        return n > 7 ? 9 : 10;
    }

    private void vehicleBackground(int x, int y) {
        frame(x + X0, y + 18, 22, 22, LINE, PANEL);
        item(new ItemStack(ModContentSVA.vehicle, 1, type.ordinal()), x + X0 + 3, y + 21);
        if (vehicle.onHydrogen() && box.batteryShown() == null) {
            // hydrogen wave: the empty battery slot shows what goes there, framed red (no battery - no start, no electrics)
            frame(x + ContainerCargoSVA.BAT_X - 2, y + ContainerCargoSVA.BAT_Y - 2, 20, 20, 0xFFF06060, SLOT);
            ghost(new ItemStack(ModContentSVA.battery, 1, type.ordinal()), x + ContainerCargoSVA.BAT_X, y + ContainerCargoSVA.BAT_Y);
        }
        List<Object[]> rows = vehicleRows();
        int step = rowStep(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Object[] r = rows.get(i);
            bar(x + 72, y + 44 + i * step, 58, 7, ((Double) r[1]).doubleValue(), ((Integer) r[2]).intValue());
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mx, int my) {
        int ox = guiLeft, oy = guiTop;
        // title bar
        String name = new ItemStack(ModContentSVA.vehicle, 1, type.ordinal()).getDisplayName();
        TextFitSC.draw(fontRendererObj, name, 6, 4, W - 80, tierColour(type.tier) & 0xFFFFFF, true, ox, oy);
        TextFitSC.drawCentered(fontRendererObj, type.tier.name(), W - 70, 4, 24, 0x101010, false, ox, oy);
        int tab = box.tab();
        if (tab == ContainerCargoSVA.TAB_CARGO) {
            cargoText(ox, oy);
        } else if (tab == ContainerCargoSVA.TAB_SPECIAL && type.hasDrill) {
            drillText(ox, oy);
        } else if (tab == ContainerCargoSVA.TAB_SPECIAL) {
            deliveryText(ox, oy);
        } else if (tab == ContainerCargoSVA.TAB_TRAILER) {
            trailerText(ox, oy);
        } else {
            vehicleText(ox, oy);
        }
    }

    private void cargoText(int ox, int oy) {
        TextFitSC.draw(fontRendererObj, pageTitle(), X0, 20, 70, TEXT, ox, oy);
        String q = query();
        String right;
        int found = 0;
        if (q.length() > 0) {
            for (int i = 0; i < box.cargoSlots(); i++) {
                if (matches(q, vehicle.getStackInSlot(i))) {
                    found++;
                }
            }
            right = Lang.tr("sva.gui.found", Integer.valueOf(found));
        } else {
            right = Lang.tr("sva.gui.filled", Integer.valueOf(vehicle.getCargoUsed()), Integer.valueOf(vehicle.cargoCapacity()));
        }
        int room = (box.pages() > 1 ? 165 : 193) - (X0 + 72);
        int w = Math.min(room, fontRendererObj.getStringWidth(right));
        TextFitSC.draw(fontRendererObj, right, (box.pages() > 1 ? 165 : 193) - w, 20, room, q.length() > 0 ? CYAN : MUTED, ox, oy);
        if (q.length() == 0) {
            return;
        }
        // dim what doesn't match, outline what does
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        for (int i = 0; i < box.cargoSlots(); i++) {
            Slot s = (Slot) inventorySlots.inventorySlots.get(i);
            if (s.xDisplayPosition < -1000 || !s.getHasStack()) {
                continue;
            }
            int sx = s.xDisplayPosition, sy = s.yDisplayPosition;
            if (matches(q, s.getStack())) {
                rect(sx - 1, sy - 1, 18, 1, 0xFF5EC8F0);
                rect(sx - 1, sy + 16, 18, 1, 0xFF5EC8F0);
                rect(sx - 1, sy, 1, 16, 0xFF5EC8F0);
                rect(sx + 16, sy, 1, 16, 0xFF5EC8F0);
            } else {
                rect(sx, sy, 16, 16, 0xC8101318);
            }
        }
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    private void drillText(int ox, int oy) {
        ItemStack head = vehicle.getStackInSlot(vehicle.headSlot());
        ItemDrillHeadSC.Kind k = DrillLogicSVA.headKind(head);
        fontRendererObj.drawString(Lang.tr("sva.gui.drill.head"), 60, 19, 0xFFFFFF);
        int left = vehicle.getHeadLeft(), max = vehicle.getHeadMax();
        if (k == null) {
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.drill.noHead"), 60, 29, 132, RED, ox, oy);
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.drill.noHead2"), 60, 46, 132, MUTED, ox, oy);
        } else {
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.drill.headLine", head.getDisplayName(),
                    Integer.valueOf(k.blocksPerSecond * DrillMath.DIG_MUL)), 60, 29, 132, MUTED, ox, oy);
            String wear = left < 0 ? Lang.tr("sva.gui.drill.wearForever")
                    : Lang.tr("sva.gui.drill.wear", FormatSVA.group(Math.max(0, max - left)), FormatSVA.group(max));
            TextFitSC.draw(fontRendererObj, wear, 60, 46, 132, left < 0 ? CYAN : TEXT, ox, oy);
        }
        boolean wide = vehicle.hasWidener();
        ItemStack module = vehicle.getStackInSlot(vehicle.headSlot() + 1);
        TextFitSC.draw(fontRendererObj, module == null ? Lang.tr("sva.gui.drill.moduleEmpty") : Lang.tr("sva.gui.drill.module", module.getDisplayName()),
                60, 57, 132, TEXT, ox, oy);
        TextFitSC.draw(fontRendererObj, Lang.tr(wide ? "sva.gui.drill.moduleOn" : "sva.gui.drill.moduleHint"), 60, 67, 132, wide ? GREEN : MUTED, ox, oy);
        int mode = vehicle.getDrillMode();
        int wBlocks = wide ? DrillMath.ROWS_WIDE : DrillMath.ROWS, h = DrillMath.rowTo(mode, wide) - DrillMath.rowFrom(mode);
        if (mode == DrillMath.MODE_ORE) {
            wBlocks += 2;
        }
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.drill.zone", Integer.valueOf(wBlocks), Integer.valueOf(h),
                String.valueOf(DrillMath.FAR).replace('.', Lang.tr("sva.num.point").charAt(0))), X0, 110, CW, TEXT, ox, oy);
        boolean exo = k == ItemDrillHeadSC.Kind.EXO;
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.drill.euBlock", Integer.valueOf(DrillMath.euPerBlock(1.5F, exo, com.sva.net.ServerConfigSVA.consumption())),
                Integer.valueOf(DrillMath.euPerBlock(3F, exo, com.sva.net.ServerConfigSVA.consumption()))), X0, 120, CW, TEXT, ox, oy);
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.drill.dug", FormatSVA.group(vehicle.getDug()), FormatSVA.group(vehicle.getTorches())),
                X0, 130, CW, TEXT, ox, oy);
        Gas fg = type.gases.fieldGas;
        if (fg != null) {
            fontRendererObj.drawString(Lang.tr("sva.gas." + fg.key()), X0, 141, 0xFFFFFF);
            String fl = Lang.tr(vehicle.isFieldOn() ? "sva.gui.drill.fieldOn" : "sva.gui.drill.fieldOff",
                    Integer.valueOf((int) Math.round(type.gases.fieldReduction * 100)), FormatSVA.group(vehicle.getGas(fg)));
            TextFitSC.draw(fontRendererObj, fl, 112, 141, 80, vehicle.isFieldOn() ? GREEN : MUTED, ox, oy);
        }
    }

    private void deliveryText(int ox, int oy) {
        fontRendererObj.drawString(Lang.tr("sva.gui.tab.delivery"), X0, 19, CYAN);
        String[] r = vehicle.getRoute();
        String none = Lang.tr("sva.gui.route.none");
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.route.a", r[0].length() > 0 ? r[0] : none), 56, 30, 136, r[0].length() > 0 ? TEXT : MUTED, ox, oy);
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.route.b", r[1].length() > 0 ? r[1] : none), 56, 40, 136, r[1].length() > 0 ? TEXT : MUTED, ox, oy);
        int len = routeLength(r);
        if (len >= 0) {
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.route.len", FormatSVA.group(len)), 56, 50, 136, MUTED, ox, oy);
        }
        boolean stop = vehicle.getAutoState() == AutoDelivery.STOPPED;
        TextFitSC.draw(fontRendererObj, autoStatus(vehicle), X0, 79, CW, stop ? RED : vehicle.isAutoPaused() ? YELLOW : vehicle.isAutoOn() ? GREEN : MUTED, true, ox, oy);
        if (r[0].length() == 0 || r[1].length() == 0) {
            int yy = 90;
            for (Object l : fontRendererObj.listFormattedStringToWidth(Lang.tr("sva.gui.route.hint"), CW)) {
                if (yy > 108) {
                    break;
                }
                fontRendererObj.drawString((String) l, X0, yy, MUTED);
                yy += 9;
            }
        } else {
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.route.rules"), X0, 91, CW, MUTED, ox, oy);
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.route.stops"), X0, 101, CW, MUTED, ox, oy);
        }
        fontRendererObj.drawString(Lang.tr("sva.gui.trailer"), X0, 117, CYAN);
        boolean tr = vehicle.hasTrailer();
        int kind = vehicle.trailerKind();
        String line = !tr ? Lang.tr("sva.gui.trailer.off") : kind == TrailerMath.CARGO ? Lang.tr("sva.gui.trailer.on", Integer.valueOf(type.trailerSlots))
                : Lang.tr("sva.gui.trailer.onKind", new ItemStack(ModContentSVA.trailer, 1, kind).getDisplayName());     // ★15
        TextFitSC.draw(fontRendererObj, line, X0, 129, tr ? 106 : CW, tr ? TEXT : MUTED, ox, oy);
    }

    /** Blocks from A to B (straight, x/z), -1 when a point is missing. */
    static int routeLength(String[] r) {
        try {
            String[] a = r[0].trim().split("\\s+"), b = r[1].trim().split("\\s+");
            if (a.length < 3 || b.length < 3) {
                return -1;
            }
            double dx = Integer.parseInt(a[0]) - Integer.parseInt(b[0]), dz = Integer.parseInt(a[2]) - Integer.parseInt(b[2]);
            return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void vehicleText(int ox, int oy) {
        String name = new ItemStack(ModContentSVA.vehicle, 1, type.ordinal()).getDisplayName();
        TextFitSC.draw(fontRendererObj, name, 56, 19, vehicle.onHydrogen() ? 134 : 136, 0xFFFFFF, ox, oy);
        if (vehicle.onHydrogen() && box.batteryShown() == null) {
            List<String> tip = new ArrayList<String>();
            tip.add(Lang.tr("sva.gui.veh.batSlot"));
            tip.add("§7" + Lang.tr("sva.gui.veh.batSlot.tip"));
            TextFitSC.hover(ox + ContainerCargoSVA.BAT_X, oy + ContainerCargoSVA.BAT_Y, 16, 16, tip);
        }
        String owner = vehicle.getOwnerName();
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.veh.owner", owner.length() > 0 ? owner : Lang.tr("sva.gui.veh.noOwner"),
                Integer.valueOf(vehicle.ridersNow()), Integer.valueOf(type.seats)), 56, 29, 136, MUTED, ox, oy);
        List<Object[]> rows = vehicleRows();
        int step = rowStep(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Object[] r = rows.get(i);
            int yy = 44 + i * step;
            TextFitSC.draw(fontRendererObj, (String) r[0], X0, yy, 40, TEXT, ox, oy);
            TextFitSC.draw(fontRendererObj, (String) r[3], 134, yy, W - 4 - 134, MUTED, ox, oy);
        }
        String keys = keysLine();
        TextFitSC.draw(fontRendererObj, keys, X0, 144, CW + 18, MUTED, ox, oy);
    }

    /** «Клавиши: L - багажник, B - бур, N - режим» for this vehicle, with the player's bindings. */
    private String keysLine() {
        StringBuilder b = new StringBuilder();
        add(b, com.sva.vehicle.VehicleFn.CARGO, "cargo");
        if (type.hasDrill) {
            add(b, com.sva.vehicle.VehicleFn.DRILL, "drill");
            add(b, com.sva.vehicle.VehicleFn.MODE, "mode");
        }
        if (type.movement == Movement.TILT) {
            add(b, com.sva.vehicle.VehicleFn.MODE, "tilt");
        }
        if (type.longRange) {
            add(b, com.sva.vehicle.VehicleFn.MODE, "long");
        }
        if (type.movement == Movement.HELI || type.movement == Movement.PLANE || type.movement == Movement.TILT) {
            add(b, com.sva.vehicle.VehicleFn.DOWN, "down");
        }
        if (type.movement == Movement.HOVER || type.gases.boostGas != null) {
            add(b, com.sva.vehicle.VehicleFn.BOOST, "boost");
        }
        if (type.singular) {
            add(b, com.sva.vehicle.VehicleFn.JUMP, "jump");
            add(b, com.sva.vehicle.VehicleFn.TARGET, "target");
            add(b, com.sva.vehicle.VehicleFn.ANCHOR, "anchor");
            add(b, com.sva.vehicle.VehicleFn.CLOAK, "cloak");
        }
        return Lang.tr("sva.gui.keys", b.toString());
    }

    private static void add(StringBuilder b, com.sva.vehicle.VehicleFn k, String what) {
        if (b.length() > 0) {
            b.append(", ");
        }
        b.append(key(k)).append(" - ").append(Lang.tr("sva.gui.key." + what));
    }

    /** «Автодоставка: к точке B · 120 бл.» / «…: погрузка в A» / «…: стоп — путь перекрыт» / «…: выкл». */
    static String autoStatus(EntityVehicleSVA v) {
        int s = v.getAutoState();
        if (!v.isAutoOn() && s != AutoDelivery.STOPPED) {
            return Lang.tr("sva.hud.auto.off");
        }
        if (v.isAutoPaused()) {
            return Lang.tr("sva.hud.auto.paused");
        }
        if (s == AutoDelivery.STOPPED) {
            return Lang.tr("sva.hud.auto.stopped." + v.getAutoReason());
        }
        return Lang.tr("sva.hud.auto." + s, Integer.valueOf(v.getAutoDist()));
    }

    static String fmt(long n) {
        return FormatSVA.group(n);
    }

    /**
     * A button in the mod's dark style: a frame (cyan under the mouse), the caption fitted (TextFitSC); `active`
     * marks the chosen mode / tab / an «on» toggle (activeColor: its text colour, else cyan). A tab shows an icon.
     */
    static class Dark extends GuiButton {
        final String tip;
        boolean active, tab;
        int activeColor;
        ItemStack icon;

        Dark(int id, int x, int y, int w, int h, String text, String tip) {
            super(id, x, y, w, h, text);
            this.tip = tip;
        }

        @Override
        public void drawButton(Minecraft mc, int mx, int my) {
            if (!visible) {
                return;
            }
            field_146123_n = mx >= xPosition && my >= yPosition && mx < xPosition + width && my < yPosition + height;
            boolean hov = field_146123_n && enabled;
            int border = active ? 0xFF5EC8F0 : hov ? 0xFF7A8496 : 0xFF3A4150;
            int fill = tab ? (active ? 0xFF2E3542 : 0xFF1E232C) : !enabled ? 0xFF1E232C : hov ? 0xFF3A4252 : 0xFF2E3542;
            drawRect(xPosition, yPosition, xPosition + width, yPosition + height, border);
            drawRect(xPosition + 1, yPosition + 1, xPosition + width - 1, yPosition + height - 1, fill);
            if (!tab && enabled) {
                drawRect(xPosition + 1, yPosition + 1, xPosition + width - 1, yPosition + 2, 0x30FFFFFF);
            }
            if (icon != null) {
                RenderHelper.enableGUIStandardItemLighting();
                GL11.glEnable(GL12.GL_RESCALE_NORMAL);
                itemRender.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), icon, xPosition + (width - 16) / 2,
                        yPosition + (height - 16) / 2);
                RenderHelper.disableStandardItemLighting();
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glColor4f(1F, 1F, 1F, 1F);
            }
            if (displayString.length() > 0) {
                int c = !enabled ? 0x6A7282 : active ? (activeColor != 0 ? activeColor : 0x5EC8F0) : hov ? 0xFFFFA0 : 0xD8DCE4;
                TextFitSC.drawCentered(mc.fontRenderer, displayString, xPosition + 2, yPosition + (height - 8) / 2, width - 4, c, false, 0, 0);
            }
        }

        private static final net.minecraft.client.renderer.entity.RenderItem itemRender = new net.minecraft.client.renderer.entity.RenderItem();
    }
}
