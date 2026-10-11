package com.sva.client;

import org.lwjgl.opengl.GL11;

import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.AnimMath;
import com.sva.vehicle.AnimParts;
import com.sva.vehicle.Movement;
import com.sva.vehicle.TrailerMath;

import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

/**
 * Draws a vehicle's OBJ model (display lists, see ObjModelSVA), rotated by the interpolated yaw.
 * ★10 (config vehicles.animations): the moving parts from vehicle/AnimParts - wheels spin by the distance rolled and the
 * front ones steer, the crawlers' tread texture runs, control surfaces deflect, landing gear retracts, the heli's rotor
 * disc tilts a little more than the body, the hover bike bobs and leans, ground vehicles bounce on bumps; all from the
 * client's AnimMath.State (EntityVehicleSVA.anim). ★6 / ★7: the lights (VehicleLightsSVA) in the same model frame.
 * Aircraft stage A: the high-lift devices (AnimParts DEVICE) - flaps travel with the synced setting, slats and spoilers /
 * the jet's dorsal brake with their state, the tiltrotor's flaperons droop in the vertical mode and work as ailerons.
 */
public class RenderVehicleSVA extends Render {

    private static final ResourceLocation NONE = new ResourceLocation("textures/blocks/iron_block.png");
    /** Aircraft pitch / bank about this model height (model units). */
    static final float ATTITUDE_Y = 0.7F;
    /** ★10: the heli's rotor disc tilts this share of the body's attitude more than the body, at most ROTOR_TILT_MAX deg. */
    private static final float ROTOR_TILT = 0.5F, ROTOR_TILT_MAX = 6F;
    /** ★10: the hover bike's idle bob (blocks) and its rate (rad/tick). */
    static final float HOVER_BOB = 0.035F, HOVER_BOB_RATE = 0.15F;
    /** ★6: the red tail lamps while not braking (1 while braking). */
    private static final float TAIL_DIM = 0.55F;

    public RenderVehicleSVA() {
        shadowSize = 1.2F;
    }

    @Override
    public void doRender(Entity e, double x, double y, double z, float yaw, float pt) {
        EntityVehicleSVA v = (EntityVehicleSVA) e;
        com.sva.vehicle.VehicleType vt = v.getVehicleType();
        ObjModelSVA m = ObjModelSVA.get(vt.model);
        if (m == null) {
            return;
        }
        float ry = v.prevRotationYaw + MathHelper.wrapAngleTo180_float(v.rotationYaw - v.prevRotationYaw) * pt;
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LIGHTING_BIT);
        GL11.glTranslated(x, y, z);
        GL11.glRotatef(-ry - 90F, 0F, 1F, 0F);         // model +x -> the entity's facing
        float s = vt.modelScale;
        GL11.glScalef(s, s, s);
        GL11.glTranslatef(-vt.modelOffsetX, 0F, 0F);   // the crawler sits forward of its trailer in truck.obj
        GL11.glEnable(GL11.GL_NORMALIZE);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        // the local rider in first person: the hull opaque with the cockpit windows cut out (VehicleType.cabWindows, GL
        // clip planes) and the glass over them lightly tinted - the world shows only through the windscreen / canopy.
        // Config vehicles.seeThroughHull: the older look (no glass, the whole shell see-through). Everyone else: opaque
        // hull, glass see-through.
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        boolean ownView = mc.gameSettings.thirdPersonView == 0 && HudVehicleSVA.vehicleOf(mc.thePlayer) == v;
        float pitch = v.prevRenderPitch + (v.renderPitch - v.prevRenderPitch) * pt;
        float roll = v.prevRenderRoll + (v.renderRoll - v.prevRenderRoll) * pt;
        if (ownView) {
            eyeClip(true);
        }
        if (vt.movement.flies && !ownView) {
            // aircraft attitude (pitch nose up +, bank right +) about the body's middle; the own cockpit view stays level
            GL11.glTranslatef(0F, ATTITUDE_Y, 0F);
            GL11.glRotatef(pitch, 0F, 0F, 1F);
            GL11.glRotatef(roll, 1F, 0F, 0F);
            GL11.glTranslatef(0F, -ATTITUDE_Y, 0F);
        }
        AnimMath.State an = v.anim;
        boolean animate = ConfigSVA.animations;
        // ★10: body bob (ground vehicles: the bump spring; the wheels stay on the ground) - not in the own cockpit view
        float bob = animate && !ownView ? AnimMath.lerp(an.prevBob, an.bob, pt) / s : 0F;
        if (animate && vt.movement == Movement.HOVER && !ownView) {
            // the hover bike floats on its cushion (powered) and leans into turns
            if (v.powered()) {
                bob += HOVER_BOB * (float) Math.sin((v.ticksExisted + pt) * HOVER_BOB_RATE) / s;
            }
            float lean = AnimMath.lerp(an.prevLean, an.lean, pt);
            GL11.glTranslatef(0F, ATTITUDE_Y * 0.5F, 0F);
            GL11.glRotatef(lean, 1F, 0F, 0F);
            GL11.glTranslatef(0F, -ATTITUDE_Y * 0.5F, 0F);
        }
        GL11.glTranslatef(0F, bob, 0F);
        float shade = v.isWrecked() ? 0.45F : 1F;
        double rolled = animate ? an.prevRoll + (an.roll - an.prevRoll) * pt : 0;
        // skins: the tier's livery and this vehicle's board number (config vehicles.textures / vehicles.liveries in ObjModelSVA)
        m.style(com.sva.vehicle.SkinMath.livery(vt.tier), v.getBoardNumber());
        // ★13: the weathering follows the synced hull (getHp / getMaxHp - the garage's repair cleans it)
        m.wear(com.sva.vehicle.WeatherMath.bucket(v.getHp(), v.getMaxHp(), v.wearOn()));
        // ★10 the tread runs with the distance rolled; ★6 the red tail lamps light up fully while braking
        float tail = ConfigSVA.headlights && v.powered() ? (an.braking() ? 1F : TAIL_DIM) : 1F;
        m.anim((float) (rolled % 1.0), tail);
        // ★8: the plafonds glow only at night with charge, in the tier's colour (vehicles.nightLighting off: always, as before)
        if (ConfigSVA.nightLighting) {
            float night = com.sva.vehicle.CockpitLamps.night(VehicleLightsSVA.darkness(v), v.powered(), ConfigSVA.cabinLightLevel);
            m.cabin(night, com.sva.vehicle.CockpitLamps.tierGlow(com.sva.vehicle.SkinMath.livery(vt.tier)));
        } else {
            m.cabin(-1F, 0);
        }
        if (!ownView) {
            m.render(shade, ObjModelSVA.GLASS_ALPHA, 1F, null);
        } else if (ConfigSVA.seeThroughHull) {
            m.render(shade, 0F, vt.cabShellAlpha, null);
        } else {
            m.render(shade, ObjModelSVA.COCKPIT_GLASS_ALPHA, 1F, vt.ownViewCuts());
        }
        // wave 2, ★8: installed upgrades as extra parts (not in the own cockpit view)
        if (!ownView) {
            TuningPartsSVA.render(v, vt, m, shade, VehicleLightsSVA.headlightsOn(v));
        }
        boolean bigWheels = ConfigSVA.tuningVisuals && com.sva.vehicle.TuningMath.has(v.getTuning(), com.sva.vehicle.TuningMath.BIG_WHEELS);
        // *14: live cockpit instruments over the model's dials / screens (own vehicle only)
        CockpitInstrumentsSVA.render(v, m, pt);
        float gear = animate ? AnimMath.lerp(an.prevGear, an.gear, pt) : 0F;
        java.util.List<ObjModelSVA.Part> parts = m.parts();
        int trailerKind = v.hasTrailer() ? v.trailerKind() : -1;
        boolean cargoTrailer = trailerKind == TrailerMath.CARGO;
        if (trailerKind > TrailerMath.CARGO) {
            // ★15: the tanker / platform / charger - its own model in truck.obj's space (hitched at the same point)
            renderTrailerKind(v, vt, trailerKind, shade, rolled, bob, tail);
        }
        if (!parts.isEmpty()) {
            // rotor / propeller: turned by the synced spin; a faint blur disc while it spins fast
            float a = v.rotorAngle(pt), rate = Math.abs(v.rotorRate());
            float blur = rate > 20F ? 0.05F + 0.07F * Math.min(1F, (rate - 20F) / 20F) : 0F;
            float steer = animate ? AnimMath.lerp(an.prevSteer, an.steer, pt) : 0F;
            float ail = animate ? AnimMath.lerp(an.prevAileron, an.aileron, pt) : 0F;
            float elev = animate ? AnimMath.lerp(an.prevElevator, an.elevator, pt) : 0F;
            float rud = animate ? AnimMath.lerp(an.prevRudder, an.rudder, pt) : 0F;
            for (ObjModelSVA.Part p : parts) {
                if (p.kind == ObjModelSVA.Part.WHEEL) {
                    AnimParts.Def d = p.def;
                    if (d.trailer && !cargoTrailer) {
                        continue;                                              // the cargo trailer's wheels
                    }
                    GL11.glPushMatrix();
                    GL11.glTranslatef(0F, -bob, 0F);                           // wheels stay on the ground
                    gearTransform(d, gear);
                    if (d.steer && steer != 0F) {
                        // + steer = right: a negative turn about +y swings the wheel's front to +z (right)
                        GL11.glTranslatef(d.pivot[0], d.pivot[1], d.pivot[2]);
                        GL11.glRotatef(-steer, 0F, 1F, 0F);
                        GL11.glTranslatef(-d.pivot[0], -d.pivot[1], -d.pivot[2]);
                    }
                    if (bigWheels && !d.trailer) {
                        // ★8 «Большие колёса»: bigger, grown up from where the tyre meets the ground
                        float gy = d.pivot[1] - d.radius;
                        GL11.glTranslatef(d.pivot[0], gy, d.pivot[2]);
                        GL11.glScalef(TuningPartsSVA.BIG_WHEEL_SCALE, TuningPartsSVA.BIG_WHEEL_SCALE, TuningPartsSVA.BIG_WHEEL_SCALE);
                        GL11.glTranslatef(-d.pivot[0], -gy, -d.pivot[2]);
                    }
                    m.renderPart(p, AnimMath.wheelAngle(rolled, d.radius), shade);
                    GL11.glPopMatrix();
                } else if (p.kind == ObjModelSVA.Part.GEAR) {
                    GL11.glPushMatrix();
                    gearTransform(p.def, gear);
                    m.renderPart(p, 0F, shade);
                    GL11.glPopMatrix();
                } else if (p.kind == ObjModelSVA.Part.SURFACE) {
                    AnimParts.Def d = p.def;
                    float cmd = d.control == AnimParts.AILERON ? ail : d.control == AnimParts.ELEVATOR ? elev : rud;
                    m.renderPart(p, cmd * d.gain, shade);
                } else if (p.kind == ObjModelSVA.Part.DEVICE) {
                    // aircraft stage A: turned by ang1 x level about axis1 through the pivot, moved by move x level
                    AnimParts.Def d = p.def;
                    float level = animate ? deviceLevel(v, vt, d, an, pt) : 0F;
                    float extra = animate && d.ailMix != 0F ? d.gain * d.ailMix * ail / AnimMath.MAX_SURFACE : 0F;
                    GL11.glPushMatrix();
                    if (level != 0F || extra != 0F) {
                        GL11.glTranslatef(d.move[0] * level, d.move[1] * level, d.move[2] * level);
                        GL11.glTranslatef(d.pivot[0], d.pivot[1], d.pivot[2]);
                        GL11.glRotatef(d.ang1 * level + extra, d.axis1[0], d.axis1[1], d.axis1[2]);
                        GL11.glTranslatef(-d.pivot[0], -d.pivot[1], -d.pivot[2]);
                    }
                    m.renderPart(p, 0F, shade);
                    GL11.glPopMatrix();
                } else if (p.kind == ObjModelSVA.Part.CONTROL) {
                    // cockpit stage 1: steering wheel / cyclic / collective / yoke / throttle / pedals follow their input
                    AnimParts.Def d = p.def;
                    GL11.glPushMatrix();
                    if (animate) {
                        if (d.inMove >= 0) {
                            float k = an.control(d.inMove, pt);
                            GL11.glTranslatef(d.move[0] * k, d.move[1] * k, d.move[2] * k);
                        }
                        GL11.glTranslatef(d.pivot[0], d.pivot[1], d.pivot[2]);
                        if (d.in1 >= 0) {
                            GL11.glRotatef(d.ang1 * an.control(d.in1, pt), d.axis1[0], d.axis1[1], d.axis1[2]);
                        }
                        if (d.in2 >= 0) {
                            GL11.glRotatef(d.ang2 * an.control(d.in2, pt), d.axis2[0], d.axis2[1], d.axis2[2]);
                        }
                        GL11.glTranslatef(-d.pivot[0], -d.pivot[1], -d.pivot[2]);
                    }
                    m.renderPart(p, 0F, shade);
                    GL11.glPopMatrix();
                } else if (p.kind == ObjModelSVA.Part.TRAILER) {
                    if (cargoTrailer) {
                        m.renderPart(p, 0F, shade);
                    }
                } else if (p.kind == ObjModelSVA.Part.BORE) {
                    m.renderPart(p, v.drillAngle(pt) * p.speed, shade);
                } else if (p.kind == ObjModelSVA.Part.CORE) {
                    // the singularity pulses (harder while the jump portal opens)
                    float t = v.ticksExisted + pt;
                    float amp = v.isPortalOpening() ? 0.22F : 0.07F;
                    m.renderPulse(p, 1F + amp * (float) Math.sin(t * (v.isPortalOpening() ? 0.9F : 0.22F)), shade);
                } else if (p.kind == ObjModelSVA.Part.RINGS) {
                    m.renderPart(p, v.coreAngle(pt), shade);
                } else if (p.kind == ObjModelSVA.Part.NACELLE) {
                    // ★10: the nacelles follow the client's smoothed position (the synced one moves in 1/40 steps)
                    double tp = animate && an.tilt >= 0 ? an.prevTilt + (an.tilt - an.prevTilt) * pt : v.getTiltPos();
                    m.renderNacelle(p, com.sva.vehicle.TiltMath.nacelleAngle(tp), a * p.speed, shade, blur);
                } else if (animate && vt.movement == Movement.HELI && "rotor".equals(p.name) && "heli".equals(vt.model)) {
                    // ★10: the main rotor disc leans with the attitude, a bit more than the body (forward in forward flight)
                    float tp = AnimMath.clamp(pitch * ROTOR_TILT, -ROTOR_TILT_MAX, ROTOR_TILT_MAX);
                    float tr = AnimMath.clamp(roll * ROTOR_TILT, -ROTOR_TILT_MAX, ROTOR_TILT_MAX);
                    GL11.glPushMatrix();
                    GL11.glTranslatef(p.pivot[0], p.pivot[1], p.pivot[2]);
                    GL11.glRotatef(tp, 0F, 0F, 1F);
                    GL11.glRotatef(tr, 1F, 0F, 0F);
                    GL11.glTranslatef(-p.pivot[0], -p.pivot[1], -p.pivot[2]);
                    m.renderPart(p, a * p.speed, shade);
                    ObjModelSVA.renderDisc(p, blur);
                    GL11.glPopMatrix();
                } else {
                    m.renderPart(p, a * p.speed, shade);
                    ObjModelSVA.renderDisc(p, blur);
                }
            }
        }
        m.anim(0F, 1F);
        m.cabin(-1F, 0);
        if (v.isBoosting() && vt.gases.boostGas != null && vt.movement == Movement.PLANE && shade >= 1F) {
            // the jet's afterburner: a flickering flame cone out of the nozzle (EntityVehicleSVA adds flame particles)
            float flicker = 0.85F + 0.15F * (float) Math.sin((v.ticksExisted + pt) * 2.3F);
            ObjModelSVA.renderFlame(EntityVehicleSVA.NOZZLE_X, EntityVehicleSVA.NOZZLE_Y, EntityVehicleSVA.NOZZLE_R, 0.9F, flicker);
        } else if (vt == com.sva.vehicle.VehicleType.JET && shade >= 1F && v.isLifting()) {
            // round 2, D2: the nozzle glows at idle - a short hot cone; right after the light-off (the first half second of
            // the warm-up) a longer flash
            boolean lightOff = v.engineState() == com.sva.vehicle.SystemsMath.WARM && v.engineTicks() < 10;
            float flicker = 0.85F + 0.15F * (float) Math.sin((v.ticksExisted + pt) * 2.3F);
            ObjModelSVA.renderFlame(EntityVehicleSVA.NOZZLE_X, EntityVehicleSVA.NOZZLE_Y, EntityVehicleSVA.NOZZLE_R * 0.8F,
                    lightOff ? 0.45F : 0.1F, flicker);
        }
        // ★6 / ★7: headlight beams, nav lights, beacons, brake glow (in this model frame)
        VehicleLightsSVA.render(v, vt, pt, gear, ownView);
        if (ownView) {
            eyeClip(false);
        }
        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    /** The own view's eye clip: GL_CLIP_PLANE5 (the window cut uses 0..MAX_WINDOWS-1). */
    private static final int EYE_CLIP_PLANE = GL11.GL_CLIP_PLANE5;
    private static final java.nio.DoubleBuffer EYE_EQ = org.lwjgl.BufferUtils.createDoubleBuffer(4);

    /**
     * Fix 2026-10 (the shuttle pilot's view was one dark grey wall): vanilla's first-person camera sits
     * CockpitView.CAMERA_BACK (0.1) blocks behind the player's eye, so anything of the vehicle right behind the head - a
     * headrest, the wall behind a back-row seat - lies in front of the camera and can fill the screen. In the own
     * first-person view the vehicle is clipped at the eye's depth (a plane given in eye space - the modelview loaded with
     * identity - keeping depth >= CockpitView.EYE_CLIP): nothing behind the eye plane is drawn, everything in front as
     * before. Turned on before the model and off after the lights (also undone by the attrib pop: GL_ENABLE_BIT).
     */
    private static void eyeClip(boolean on) {
        if (!on) {
            GL11.glDisable(EYE_CLIP_PLANE);
            return;
        }
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        EYE_EQ.clear();
        EYE_EQ.put(0).put(0).put(-1).put(-com.sva.vehicle.CockpitView.EYE_CLIP).flip();
        GL11.glClipPlane(EYE_CLIP_PLANE, EYE_EQ);
        GL11.glPopMatrix();
        GL11.glEnable(EYE_CLIP_PLANE);
    }

    /**
     * ★15: a trailer kind's model (models/trailer_*.obj, truck.obj's model space) in the crawler's livery, its wheels
     * rolling with the crawler's (they stay on the ground like the crawler's), the tanker's band in its cargo's colour,
     * the platform's vehicle on the deck.
     */
    private static void renderTrailerKind(EntityVehicleSVA v, com.sva.vehicle.VehicleType vt, int kind, float shade, double rolled,
            float bob, float tail) {
        ObjModelSVA tm = ObjModelSVA.get(TrailerMath.MODELS[kind]);
        if (tm == null) {
            return;
        }
        tm.style(com.sva.vehicle.SkinMath.livery(vt.tier), v.getBoardNumber());
        tm.anim(0F, tail);
        tm.band(kind == TrailerMath.TANK ? fluidColour(v.tankFluid(), v.tankAmount()) : -1);
        tm.render(shade, ObjModelSVA.GLASS_ALPHA, 1F, null);
        for (ObjModelSVA.Part p : tm.parts()) {
            GL11.glPushMatrix();
            if (p.kind == ObjModelSVA.Part.WHEEL) {
                GL11.glTranslatef(0F, -bob, 0F);
                tm.renderPart(p, AnimMath.wheelAngle(rolled, p.def.radius), shade);
            } else {
                tm.renderPart(p, 0F, shade);
            }
            GL11.glPopMatrix();
        }
        tm.band(-1);
        tm.anim(0F, 1F);
        if (kind == TrailerMath.PLATFORM) {
            int[] c = v.carried();
            if (c[0] >= 0) {
                renderCarried(com.sva.vehicle.VehicleType.byOrdinal(c[0]), c[1], vt, shade);
            }
        }
    }

    /**
     * ★15: the carried vehicle on the deck, its own OBJ in its livery with its board number, at its true size if that
     * fits (its modelScale over the crawler's), else shrunk to the deck's length / width (the buggy ~x0.7).
     */
    static void renderCarried(com.sva.vehicle.VehicleType ct, int board, com.sva.vehicle.VehicleType truck, float shade) {
        ObjModelSVA cm = ObjModelSVA.get(ct.model);
        if (cm == null) {
            return;
        }
        float len = cm.bbMax[0] - cm.bbMin[0], wid = cm.bbMax[2] - cm.bbMin[2];
        float s = TrailerMath.carriedScale(ct.modelScale / truck.modelScale, len, wid);
        float cx = (cm.bbMin[0] + cm.bbMax[0]) / 2F, cz = (cm.bbMin[2] + cm.bbMax[2]) / 2F;
        GL11.glPushMatrix();
        GL11.glTranslatef((TrailerMath.DECK_X0 + TrailerMath.DECK_X1) / 2F, TrailerMath.DECK_Y, 0F);
        GL11.glScalef(s, s, s);
        GL11.glTranslatef(-cx, -cm.bbMin[1], -cz);
        cm.style(com.sva.vehicle.SkinMath.livery(ct.tier), board);
        cm.anim(0F, 1F);
        cm.render(shade, ObjModelSVA.GLASS_ALPHA, 1F, null);
        for (ObjModelSVA.Part p : cm.parts()) {
            if (p.kind == ObjModelSVA.Part.NACELLE) {
                cm.renderNacelle(p, 0F, 0F, shade, 0F);
            } else if (p.kind != ObjModelSVA.Part.TRAILER) {
                cm.renderPart(p, 0F, shade);
            }
        }
        GL11.glPopMatrix();
    }

    /** ★15: the tanker's band colour - the gas's (ArmorGasSC.Gas), water / lava their own, any other fluid its tint; grey empty. */
    static int fluidColour(String fluid, int amount) {
        if (amount <= 0 || fluid == null || fluid.length() == 0) {
            return 0x6A7080;
        }
        net.minecraftforge.fluids.Fluid f = net.minecraftforge.fluids.FluidRegistry.getFluid(fluid);
        com.sc.util.ArmorGasSC.Gas g = com.sc.util.ArmorGasSC.Gas.of(f);
        if (g != null) {
            return g.color & 0xFFFFFF;
        }
        if ("water".equals(fluid)) {
            return 0x3F76E4;
        }
        if ("lava".equals(fluid)) {
            return 0xE0601A;
        }
        int c = f == null ? 0xFFFFFF : f.getColor() & 0xFFFFFF;
        return c == 0xFFFFFF ? 0xC8C8C8 : c;
    }

    /**
     * Aircraft stage A: a DEVICE part's level (its deflection over the Def's reference one): flaps - the angle of the
     * travelling flap position (the tiltrotor's flaperons drooped by the nacelle position), slats / spoilers 0..1, the
     * jet's brake 0..1 of 45 deg (25 deg fast).
     */
    static float deviceLevel(EntityVehicleSVA v, com.sva.vehicle.VehicleType vt, AnimParts.Def d, AnimMath.State an, float pt) {
        if (d.device == AnimParts.LAMP) {
            return AnimMath.lerp(an.prevLamp, an.lamp, pt);
        }
        com.sva.vehicle.HighLiftMath.Spec s = com.sva.vehicle.HighLiftMath.spec(vt);
        if (s == null) {
            return 0F;
        }
        switch (d.device) {
            case AnimParts.FLAP: {
                float a = s.angleAt(AnimMath.lerp(an.prevFlapPos, an.flapPos, pt));
                if (s.flaperons) {
                    double tp = an.tilt >= 0 ? an.prevTilt + (an.tilt - an.prevTilt) * pt : v.getTiltPos();
                    a = com.sva.vehicle.HighLiftMath.flaperonAngle(s, a, tp);
                }
                return a / d.refDeg;
            }
            case AnimParts.SLAT:
                return AnimMath.lerp(an.prevSlat, an.slat, pt);
            case AnimParts.SPOILER:
                return AnimMath.lerp(an.prevSpoiler, an.spoiler, pt);
            case AnimParts.BRAKE:
                return AnimMath.lerp(an.prevSpoiler, an.spoiler, pt) * (v.brakeLimited() ? s.brakeFastDeg / s.brakeDeg : 1F);
            case AnimParts.LAMP:
                return AnimMath.lerp(an.prevLamp, an.lamp, pt);               // stage D1: the landing light's door
            default:
                return 0F;
        }
    }

    /** ★10: a retracting part at gear position g (0 down .. 1 up): turned about its gear pivot, then lifted. */
    private static void gearTransform(AnimParts.Def d, float g) {
        if (d.gearPivot == null || g <= 0F) {
            return;
        }
        GL11.glTranslatef(0F, d.gearLift * g, 0F);
        if (d.gearAngle != 0F) {
            float[] q = d.gearPivot;
            GL11.glTranslatef(q[0], q[1], q[2]);
            GL11.glRotatef(d.gearAngle * g, d.gearAxis == 0 ? 1F : 0F, d.gearAxis == 1 ? 1F : 0F, d.gearAxis == 2 ? 1F : 0F);
            GL11.glTranslatef(-q[0], -q[1], -q[2]);
        }
    }

    @Override
    protected ResourceLocation getEntityTexture(Entity e) {
        return NONE;
    }
}
