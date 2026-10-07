package com.sva.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.sva.ConfigSVA;
import com.sva.ReferenceSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.SoundMath;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.MovingSound;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

/**
 * Wave 1, ★1 (mock-up veh_more_1_sound.png): the vehicles' looping sounds on this client. Every client tick each vehicle
 * within SoundMath.RANGE gets its layers (SoundMath.layers) as moving, repeating sounds whose volume / pitch follow
 * SoundMath.finalMix from what every client sees (driver aboard, charge, speed, synced throttle, rotor rate, boost, the
 * drill). A layer that has nothing to say for a second stops (no channel held by a parked vehicle) and starts again when
 * it does. The local rider of a closed vehicle in first person hears the muffled NAME_in files, quieter and lower; the
 * view or the seat changing swaps the files. Everything stops when the vehicle dies / unloads / leaves the range, the
 * world changes or vehicles.sounds is off. The volume goes through the sounds.json category "player" («Игроки»).
 * One-shots (boarding, hatch, touch-down, hits, the charger's chime, the hull alarm, ore) are played by the server at the
 * vehicle (EntityVehicleSVA.oneShot) - every client near hears them.
 */
public final class VehicleSoundsSVA {

    /** Ticks a layer stays silent before it lets its channel go. */
    private static final int SILENT_STOP = 20;

    private static final Map<Integer, Loop[]> PLAYING = new HashMap<Integer, Loop[]>();
    /** Each vehicle's speed last tick (accelerating: the motor's whine rises). */
    private static final Map<Integer, Double> LAST_SPEED = new HashMap<Integer, Double>();
    private static Object world;

    private VehicleSoundsSVA() {
    }

    /** One looping layer of one vehicle. */
    static final class Loop extends MovingSound {
        final EntityVehicleSVA v;
        final int layer;
        final boolean inside;
        int silent;
        /** The first getVolume() of SoundManager.playSound is the fall-off range (16 x volume over 1): RANGE, not the gain. */
        private boolean rangeAsked;

        Loop(EntityVehicleSVA v, int layer, boolean inside, float volume, float pitch) {
            super(new ResourceLocation(ReferenceSVA.ASSETS, SoundMath.file(layer, inside)));
            this.v = v;
            this.layer = layer;
            this.inside = inside;
            this.repeat = true;
            this.field_147665_h = 0;
            this.field_147666_i = ISound.AttenuationType.LINEAR;
            this.volume = Math.max(0.01F, volume);
            this.field_147663_c = pitch;
            place();
        }

        void place() {
            xPosF = (float) v.posX;
            yPosF = (float) (v.posY + 1.0);
            zPosF = (float) v.posZ;
        }

        @Override
        public float getVolume() {
            if (!rangeAsked) {
                rangeAsked = true;
                return SoundMath.rangeVolume();
            }
            return volume;
        }

        void stop() {
            donePlaying = true;
        }

        void set(float vol, float pitch) {
            volume = vol;
            field_147663_c = pitch;
        }

        /** The manager sets volume / pitch each tick (tick()); here only the position and the end. */
        @Override
        public void update() {
            if (v.isDead || donePlaying) {
                donePlaying = true;
                return;
            }
            place();
        }
    }

    /** Client tick (ClientProxySVA.onClientTick). */
    public static void tick(Minecraft mc) {
        if (mc.theWorld != world) {
            stopAll();
            world = mc.theWorld;
        }
        if (mc.theWorld == null || mc.thePlayer == null || !ConfigSVA.sounds || mc.isGamePaused()) {
            if (!ConfigSVA.sounds || mc.theWorld == null) {
                stopAll();
            }
            return;
        }
        Entity me = mc.renderViewEntity != null ? mc.renderViewEntity : mc.thePlayer;
        EntityVehicleSVA mine = HudVehicleSVA.vehicleOf(mc.thePlayer);
        boolean firstPerson = mc.gameSettings.thirdPersonView == 0;
        double r2 = SoundMath.RANGE * SoundMath.RANGE;
        // vehicles near
        List<EntityVehicleSVA> near = new ArrayList<EntityVehicleSVA>();
        for (Object o : mc.theWorld.loadedEntityList) {
            if (o instanceof EntityVehicleSVA) {
                EntityVehicleSVA v = (EntityVehicleSVA) o;
                if (!v.isDead && v.getDistanceSqToEntity(me) <= r2) {
                    near.add(v);
                }
            }
        }
        // the gone ones
        for (Iterator<Map.Entry<Integer, Loop[]>> it = PLAYING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Loop[]> e = it.next();
            Loop[] ls = e.getValue();
            EntityVehicleSVA v = ls.length > 0 ? firstVehicle(ls) : null;
            if (v == null || v.isDead || !near.contains(v)) {
                for (Loop l : ls) {
                    if (l != null) {
                        l.stop();
                    }
                }
                LAST_SPEED.remove(e.getKey());
                it.remove();
            }
        }
        for (EntityVehicleSVA v : near) {
            VehicleType t = v.getVehicleType();
            int[] layers = SoundMath.layers(t);
            Loop[] ls = PLAYING.get(Integer.valueOf(v.getEntityId()));
            if (ls == null || ls.length != layers.length) {
                ls = new Loop[layers.length];
                PLAYING.put(Integer.valueOf(v.getEntityId()), ls);
            }
            boolean inside = v == mine && firstPerson && SoundMath.closed(t);
            SoundMath.Input in = input(v, t);
            for (int i = 0; i < layers.length; i++) {
                float[] mix = SoundMath.finalMix(layers[i], t, in, inside, ConfigSVA.soundVolume);
                Loop l = ls[i];
                if (l != null && (l.isDonePlaying() || l.inside != inside)) {
                    l.stop();
                    l = ls[i] = null;                           // swapped files (view / seat changed)
                }
                if (mix[0] <= 0.001F) {
                    if (l != null && ++l.silent >= SILENT_STOP) {
                        l.stop();
                        ls[i] = null;
                    } else if (l != null) {
                        l.set(0.001F, mix[1]);
                    }
                    continue;
                }
                if (l == null) {
                    l = ls[i] = new Loop(v, layers[i], inside, mix[0], mix[1]);
                    mc.getSoundHandler().playSound(l);
                } else {
                    l.silent = 0;
                    l.set(mix[0], mix[1]);
                }
            }
        }
    }

    private static EntityVehicleSVA firstVehicle(Loop[] ls) {
        for (Loop l : ls) {
            if (l != null) {
                return l.v;
            }
        }
        return null;
    }

    /** What the sounds need from a vehicle, from the state every client has. */
    static SoundMath.Input input(EntityVehicleSVA v, VehicleType t) {
        SoundMath.Input in = new SoundMath.Input();
        double dx = v.posX - v.prevPosX, dz = v.posZ - v.prevPosZ;
        double sp = Math.sqrt(dx * dx + dz * dz);
        // the motor runs with a driver (or the crawler's auto-delivery), charge, no overheat; lifting types only while held
        // up (the hoverbike without gas, an aircraft gliding: no hum - the rotor still windmills)
        in.engine = (v.riddenByEntity != null || v.isAutoOn()) && v.powered() && !v.isHeatStopped()
                && (!t.movement.lifts || v.isLifting());
        in.speedFrac = sp / Math.max(0.01, t.topSpeed);
        in.kmh = VehicleMath.kmh(sp);
        in.throttle = v.getThrottle();
        in.rotor = v.rotorRate();
        in.boost = v.isBoosting();
        in.drilling = t.hasDrill && v.isDrillOn() && v.riddenByEntity != null && v.powered();
        Double last = LAST_SPEED.put(Integer.valueOf(v.getEntityId()), Double.valueOf(sp));
        in.accelerating = last != null && sp > last.doubleValue() + 0.002;
        return in;
    }

    /** Stops every loop (world change, sounds off). */
    public static void stopAll() {
        for (Loop[] ls : PLAYING.values()) {
            for (Loop l : ls) {
                if (l != null) {
                    l.stop();
                }
            }
        }
        PLAYING.clear();
        LAST_SPEED.clear();
    }
}
