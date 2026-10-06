package com.sva.item;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;
import com.sva.ConfigSVA;
import com.sva.ReferenceSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/** A vehicle as an item (meta = VehicleType ordinal); the NBT keeps its whole state. Right-click a block: place it. */
public class ItemVehicleSVA extends Item {

    private IIcon[] icons;

    public ItemVehicleSVA() {
        setHasSubtypes(true);
        setMaxDamage(0);
        setMaxStackSize(1);
        setUnlocalizedName(ReferenceSVA.ASSETS + ".vehicle");
    }

    public static VehicleType typeOf(ItemStack s) {
        return VehicleType.byOrdinal(s.getItemDamage());
    }

    /** The state an item holds (a fresh one for a plain item). */
    public static VehicleState stateOf(ItemStack s) {
        VehicleState st = new VehicleState(typeOf(s));
        if (s.hasTagCompound() && s.getTagCompound().hasKey(EntityVehicleSVA.NBT_ROOT)) {
            st.readNBT(s.getTagCompound().getCompoundTag(EntityVehicleSVA.NBT_ROOT));
        }
        st.type = typeOf(s);
        return st;
    }

    public static ItemStack withState(ItemStack s, VehicleState st) {
        NBTTagCompound root = s.hasTagCompound() ? s.getTagCompound() : new NBTTagCompound();
        NBTTagCompound t = new NBTTagCompound();
        st.writeNBT(t);
        root.setTag(EntityVehicleSVA.NBT_ROOT, t);
        s.setTagCompound(root);
        return s;
    }

    @Override
    public String getUnlocalizedName(ItemStack s) {
        return getUnlocalizedName() + "." + typeOf(s).key;
    }

    @Override
    public boolean onItemUse(ItemStack stack, EntityPlayer p, World w, int x, int y, int z, int side, float hx, float hy, float hz) {
        if (w.isRemote) {
            return true;
        }
        int[][] off = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        int bx = x, by = y, bz = z;
        if (!w.getBlock(x, y, z).isReplaceable(w, x, y, z)) {
            bx += off[side][0];
            by += off[side][1];
            bz += off[side][2];
        }
        if (com.sc.ShieldEventHandler.privateFor(w, p, bx, by, bz)) {
            return true;                                // someone else's private field (it says so)
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        String owner = p.getUniqueID().toString();
        if (reg != null && !VehicleMath.limitAllows(reg.count(owner), ConfigSVA.maxPerPlayer)) {
            p.addChatComponentMessage(new ChatComponentTranslation("sva.msg.limit", Integer.valueOf(ConfigSVA.maxPerPlayer)));
            return true;
        }
        float yaw = MathHelper.wrapAngleTo180_float(p.rotationYaw);
        EntityVehicleSVA v = spawn(w, stack, bx + 0.5, by, bz + 0.5, yaw, owner, p.getCommandSenderName());
        if (v == null) {
            p.addChatComponentMessage(new ChatComponentTranslation("sva.msg.noSpace"));
            return true;
        }
        if (!p.capabilities.isCreativeMode) {
            stack.stackSize--;
        }
        return true;
    }

    /**
     * Places the vehicle an item holds (server): owner = whoever places it. @return null when there is no room
     * (blocks or liquid in the way)
     */
    public static EntityVehicleSVA spawn(World w, ItemStack stack, double x, double y, double z, float yaw, String ownerId, String ownerName) {
        VehicleState st = stateOf(stack);
        st.ownerId = ownerId == null ? "" : ownerId;
        st.ownerName = ownerName == null ? "" : ownerName;
        st.vehId = java.util.UUID.randomUUID().toString();    // a fresh id per placement: copied stacks never share one
        EntityVehicleSVA v = new EntityVehicleSVA(w, st.type);
        v.state = st;
        v.setLocationAndAngles(x, y, z, yaw, 0);
        if (!w.getCollidingBoundingBoxes(v, v.boundingBox.contract(0.02, 0.02, 0.02)).isEmpty() || w.isAnyLiquid(v.boundingBox)) {
            return null;
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        if (reg != null) {
            reg.add(st.vehId, st.ownerId);
        }
        w.spawnEntityInWorld(v);
        return v;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer player, List list, boolean adv) {
        VehicleState st = stateOf(s);
        VehicleType t = st.type;
        int pct = (int) Math.floor(st.energy * 100 / st.maxEnergy());
        list.add(Lang.tr("sva.tooltip.charge", fmt((long) st.energy), fmt(t.buffer), Integer.valueOf(pct)));
        if (ConfigSVA.wear) {
            list.add(st.hp <= 0 ? Lang.tr("sva.tooltip.wrecked")
                    : Lang.tr("sva.tooltip.hp", Integer.valueOf(Math.round(st.hp * 100 / t.maxHp))));
        }
        for (com.sc.util.ArmorGasSC.Gas g : t.gases.gases()) {
            list.add(Lang.tr("sva.tooltip.gas", Lang.tr("sva.gas." + g.key()), fmt(st.gas(g)), fmt(st.gasCapacity(g))));
        }
        list.add(Lang.tr("sva.tooltip.cargo", Integer.valueOf(st.cargoUsed()), Integer.valueOf(t.cargoSlots)));
        if (st.ownerName != null && st.ownerName.length() > 0) {
            list.add(Lang.tr("sva.tooltip.owner", st.ownerName));
        }
        boolean ground = t.movement == com.sva.vehicle.Movement.GROUND;
        String details = Lang.tr("sva.tooltip.details." + t.key) + "\n"
                + Lang.tr("sva.tooltip.vehicle.stats", t.tier.name(), Integer.valueOf(VehicleMath.kmh(t.topSpeed)),
                Integer.valueOf(t.seats), Integer.valueOf(t.cargoSlots)) + "\n"
                + Lang.tr("sva.tooltip.vehicle.energy", fmt((long) (t.cruiseCost * ConfigSVA.consumption)), fmt((long) (t.idleCost * ConfigSVA.consumption)),
                fmt((long) VehicleMath.range(t, t.buffer, 0, ConfigSVA.consumption))) + "\n"
                + (t.gases.liftGas != null ? Lang.tr("sva.tooltip.vehicle.gas." + t.key, fmt(Math.round(t.gases.liftPerSecond)),
                        fmt(Math.round(t.gases.boostPerSecond))) + "\n" : "")
                + Lang.tr(ground ? "sva.tooltip.vehicle.limits" : "sva.tooltip.limits." + t.key, new Object[0]);
        TooltipSC.more(list, details, Lang.tr("sva.tooltip.howto.vehicle1") + "\n"
                + Lang.tr(ground ? "sva.tooltip.howto.vehicle2" : "sva.tooltip.howto." + t.key + "2") + "\n"
                + Lang.tr(ground ? "sva.tooltip.howto.vehicle3" : "sva.tooltip.howto." + t.key + "3", new Object[0]) + "\n"
                + Lang.tr("sva.tooltip.howto.pickUp", new Object[0]));
    }

    static String fmt(long n) {
        return String.format("%,d", Long.valueOf(n)).replace(',', ' ');
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void getSubItems(Item item, CreativeTabs tab, List list) {
        for (VehicleType t : VehicleType.values()) {
            list.add(new ItemStack(item, 1, t.ordinal()));
            VehicleState full = new VehicleState(t);
            full.energy = full.maxEnergy();
            list.add(withState(new ItemStack(item, 1, t.ordinal()), full));
            if (t.gases.any()) {
                VehicleState gassed = new VehicleState(t);
                gassed.energy = gassed.maxEnergy();
                gassed.fillAllGases();
                list.add(withState(new ItemStack(item, 1, t.ordinal()), gassed));
            }
        }
    }

    @Override
    public boolean showDurabilityBar(ItemStack s) {
        return s.hasTagCompound();
    }

    @Override
    public double getDurabilityForDisplay(ItemStack s) {
        VehicleState st = stateOf(s);
        return 1 - st.energy / st.maxEnergy();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister r) {
        icons = new IIcon[VehicleType.values().length];
        for (VehicleType t : VehicleType.values()) {
            icons[t.ordinal()] = r.registerIcon(ReferenceSVA.ASSETS + ":vehicle_" + t.key);
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconFromDamage(int meta) {
        return icons[VehicleType.byOrdinal(meta).ordinal()];
    }
}
