package com.sva.entity;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

/**
 * An extra seat (1.7.10 has one rider per entity): an invisible entity the body positions every tick.
 * Never saved - the body spawns its seats again after a load.
 */
public class EntitySeatSVA extends Entity {

    private static final int DW_PARENT = 20, DW_INDEX = 21;

    private EntityVehicleSVA parent;
    private int orphanTicks;
    private Entity lastRider;

    public EntitySeatSVA(World world) {
        super(world);
        setSize(0.5F, 0.5F);
        noClip = true;
    }

    public EntitySeatSVA(World world, EntityVehicleSVA parent, int index) {
        this(world);
        this.parent = parent;
        dataWatcher.updateObject(DW_PARENT, Integer.valueOf(parent.getEntityId()));
        dataWatcher.updateObject(DW_INDEX, Byte.valueOf((byte) index));
    }

    @Override
    protected void entityInit() {
        dataWatcher.addObject(DW_PARENT, Integer.valueOf(-1));
        dataWatcher.addObject(DW_INDEX, Byte.valueOf((byte) 1));
    }

    public int getIndex() {
        return dataWatcher.getWatchableObjectByte(DW_INDEX);
    }

    public EntityVehicleSVA getParent() {
        if (worldObj.isRemote && (parent == null || parent.isDead)) {
            parent = null;                  // the body left tracking: a new object comes for it when it's back
            Entity e = worldObj.getEntityByID(dataWatcher.getWatchableObjectInt(DW_PARENT));
            if (e instanceof EntityVehicleSVA && !e.isDead) {
                parent = (EntityVehicleSVA) e;
                if (!parent.clientSeats.contains(this)) {
                    parent.clientSeats.add(this);
                }
            }
        }
        return parent;
    }

    @Override
    public void onUpdate() {
        prevPosX = posX;
        prevPosY = posY;
        prevPosZ = posZ;
        EntityVehicleSVA p = getParent();
        if (p == null || p.isDead || !worldObj.isRemote && worldObj.getEntityByID(p.getEntityId()) != p) {
            if (!worldObj.isRemote && ++orphanTicks > 40) {
                if (riddenByEntity != null) {
                    riddenByEntity.mountEntity(null);
                }
                setDead();
            }
            return;
        }
        orphanTicks = 0;
        if (!worldObj.isRemote) {
            if (lastRider != null && riddenByEntity != lastRider && lastRider.ridingEntity == null && !lastRider.isDead) {
                p.placeBeside(lastRider, 1, getIndex());       // beside its own seat, not the driver's
            }
            lastRider = riddenByEntity;
        }
    }

    @Override
    public double getMountedYOffset() {
        EntityVehicleSVA p = getParent();
        return p != null ? p.getMountedYOffset() : 0.45;
    }

    @Override
    public void updateRiderPosition() {
        if (riddenByEntity != null) {
            riddenByEntity.setPosition(posX, posY + getMountedYOffset() + riddenByEntity.getYOffset(), posZ);
        }
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean canBePushed() {
        return false;
    }

    @Override
    public AxisAlignedBB getBoundingBox() {
        return null;
    }

    @Override
    public boolean attackEntityFrom(net.minecraft.util.DamageSource src, float amount) {
        return false;
    }

    @Override
    public void travelToDimension(int dim) {
    }

    @Override
    public boolean writeToNBTOptional(NBTTagCompound tag) {
        return false;                       // never saved
    }

    @Override
    @cpw.mods.fml.relauncher.SideOnly(cpw.mods.fml.relauncher.Side.CLIENT)
    public void setPositionAndRotation2(double x, double y, double z, float yaw, float pitch, int inc) {
        if (getParent() == null) {
            setPosition(x, y, z);           // the body places it once known
        }
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound tag) {
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound tag) {
    }
}
