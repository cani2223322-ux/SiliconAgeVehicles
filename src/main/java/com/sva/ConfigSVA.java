package com.sva;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/** config/SiliconAgeVehicles.cfg. Every number is NaN / infinity safe (Forge's clamp lets NaN through). */
public final class ConfigSVA {

    /** Hull wear: attacks and wall hits damage vehicles; at 0 HP a vehicle is wrecked. */
    public static boolean wear = true;
    /** Vehicles one player may have placed at once; 0 = unlimited. */
    public static int maxPerPlayer = 0;
    /** Charging station rate = input voltage x this. */
    public static float chargerRate = 1F;
    /** Vehicles' EU use x this. */
    public static float consumption = 1F;
    /**
     * Client: the first-person view from a closed vehicle shows the world through the whole hull (drawn see-through)
     * instead of only through the cockpit windows. Off by default.
     */
    public static boolean seeThroughHull = false;
    /** Client: material textures on the vehicle models (pixel detail, panel seams, rivets); off = flat colours. */
    public static boolean textures = true;
    /** Client: liveries by tier (stripe, hazard band, glowing lines) and the side decal (SA emblem, board number, charge hatch). */
    public static boolean liveries = true;
    /** Client (★14): the cockpit dials / screens / warning lamps show the vehicle's real readings. On by default. */
    public static boolean liveInstruments = true;
    /** Client (★16): vehicle and trailer items drawn as small 3D models (false: the flat 16x16 icons). On by default. */
    public static boolean icons3d = true;
    /** Client (★6): headlight / landing-light beams at night and brighter brake lights. On by default. */
    public static boolean headlights = true;
    /** Client (★6): headlights light up the blocks ahead at night (client-side block light, restored when they move on). */
    public static boolean dynamicLight = true;
    /** Client (★7): navigation lights - red / green / strobe / beacon on aircraft, amber beacons and markers on work vehicles. */
    public static boolean navLights = true;
    /** Client (★10): animated parts - wheels, tracks, steering, control surfaces, landing gear, rotor tilt, hover lean. */
    public static boolean animations = true;
    /** Client (★7): the cabin trim follows the vehicle's tier - seats, floor, inserts, roof lining (SkinMath.trimTile). */
    public static boolean interiorTrim = true;
    /** Client (cockpit stage 2, ★8): at night instruments and lamps glow in the tier's colour, plafonds and the floor strip light up. */
    public static boolean nightLighting = true;
    /** Client (★8): the night glow's brightness 0..1. */
    public static float cabinLightLevel = 0.8F;
    /** Client (★11): the boarding sequence - lamp test, SA logo on the screens, needle sweep. */
    public static boolean cockpitStartup = true;
    /** Client (★11): cockpit warning sounds for the vehicle's own riders - overheating beep, stall siren. */
    public static boolean cockpitSounds = true;
    /** Client (wave 1, ★1): engine / rotor / turbine loops and the vehicles' own one-shot sounds. On by default. */
    public static boolean sounds = true;
    /** Client (★1): the vehicle loops' volume x this (0..2; the game's «Игроки» slider applies too). */
    public static float soundVolume = 1F;
    /** Client (★3): the third-person camera while riding - distance by the vehicle's size, higher, smooth follow. */
    public static boolean vehicleCamera = true;
    /** Client (★3): that camera's distance x this (0.25..4; the mouse wheel zooms 0.5x..2x on top). */
    public static float cameraDistance = 1F;
    /** ★4: cruise control for ground vehicles and the hoverbike (key «Круиз»). */
    public static boolean cruise = true;
    /** ★4: the aircraft autopilot (plane, jet, tiltrotor in airplane mode) - holds heading and height. */
    public static boolean autopilot = true;
    /** Wave 2 (★8): installed tuning upgrades change the vehicle (speed, buffer, armour...); off = upgrades do nothing. */
    public static boolean tuning = true;
    /** Client (★8): installed upgrades are drawn on the model (armour plates, light bar, battery box, extra tank, big wheels). */
    public static boolean tuningVisuals = true;
    /** Client (★7): the garage's hologram ring over a vehicle being repaired. */
    public static boolean garageHologram = true;
    /** Wave 2 (★10): access rights and lock modes (key cards); off = the old rules (owner drives, anyone rides). */
    public static boolean locks = true;
    /** ★10: server operators pass any vehicle lock. */
    public static boolean lockOpBypass = true;
    /** Cargo screen «Мусор»: items thrown out next to the vehicle ("modid:name" or "modid:name@meta"). */
    public static final String[] JUNK_DEFAULT = {"minecraft:cobblestone", "minecraft:dirt", "minecraft:gravel", "minecraft:netherrack",
            "minecraft:stone"};
    public static java.util.Set<String> junk = com.sva.inventory.CargoToolsSVA.parseJunk(JUNK_DEFAULT);

    private ConfigSVA() {
    }

    public static void load(File file) {
        Configuration c = new Configuration(file);
        try {
            c.load();
            String v = "vehicles";
            wear = c.getBoolean("wear", v, true,
                    "Hull wear: attacks and wall hits damage vehicles, at 0 HP a vehicle is wrecked / Износ корпуса: удары и столкновения повреждают технику");
            maxPerPlayer = Math.max(0, c.getInt("maxPerPlayer", v, 0, 0, 10000,
                    "Vehicles one player may have placed at once (0 = unlimited) / Лимит машин на игрока (0 - без ограничений)"));
            chargerRate = num(c, "chargerRate", v, 1F, 0.1F, 100F,
                    "Charging station speed: input voltage x this / Скорость зарядной станции: напряжение входа x это");
            consumption = num(c, "consumption", v, 1F, 0F, 100F,
                    "Vehicles' EU use x this / Расход EU техникой x это");
            seeThroughHull = c.getBoolean("seeThroughHull", v, false,
                    "Client: from the cockpit (first person) see the world through the whole hull, not only through the windows / "
                            + "Клиент: из кабины (от первого лица) видно мир сквозь весь корпус, а не только через стёкла");
            textures = c.getBoolean("textures", v, true,
                    "Client: material textures on vehicles (pixel detail, panel seams, rivets); false = flat colours / "
                            + "Клиент: текстуры материалов на технике (фактура, швы, заклёпки); false - заливка цветом");
            liveries = c.getBoolean("liveries", v, true,
                    "Client: liveries by tier and the side decal with the SA emblem and board number / "
                            + "Клиент: ливреи по уровню машины и бортовая эмблема SA с номером");
            liveInstruments = c.getBoolean("liveInstruments", v, true,
                    "Client: cockpit dials, screens and warning lamps show the vehicle's real readings / "
                            + "Клиент: приборы в кабине (стрелки, экраны, лампы) показывают реальные данные машины");
            icons3d = c.getBoolean("icons3d", v, true,
                    "Client: vehicle items are drawn as small 3D models (false: flat icons) / "
                            + "Клиент: предметы машин рисуются уменьшенными 3D-моделями (false - плоские иконки)");
            headlights = c.getBoolean("headlights", v, true,
                    "Client: headlight and landing-light beams at night, brighter brake lights / "
                            + "Клиент: лучи фар и посадочных фар ночью, яркие стоп-сигналы при торможении");
            dynamicLight = c.getBoolean("dynamicLight", v, true,
                    "Client: headlights light up the blocks ahead at night (only on your screen, nothing is saved) / "
                            + "Клиент: фары освещают блоки впереди ночью (только у вас на экране, в мир не сохраняется)");
            navLights = c.getBoolean("navLights", v, true,
                    "Client: navigation lights - red/green/strobe/beacon on aircraft, amber beacons on work vehicles / "
                            + "Клиент: бортовые огни - красный/зелёный/строб/маячок у авиации, оранжевые маячки у рабочей техники");
            animations = c.getBoolean("animations", v, true,
                    "Client: animated parts - wheels, tracks, steering, control surfaces, landing gear, rotor tilt / "
                            + "Клиент: анимации деталей - колёса, гусеницы, руль, рули самолёта, шасси, наклон винта");
            interiorTrim = c.getBoolean("interiorTrim", v, true,
                    "Client: cabin trim by the vehicle's tier - fabric / leather + wood / alcantara + carbon / velvet + dark glass / "
                            + "Клиент: отделка салона по уровню машины - ткань / кожа и дерево / алькантара и карбон / бархат и тёмное стекло");
            nightLighting = c.getBoolean("nightLighting", v, true,
                    "Client: at night (powered) the cockpit instruments and lamps glow in the tier's colour, the ceiling lights and a floor strip light up; "
                            + "false = plafonds always lit, no tint / "
                            + "Клиент: ночью (с зарядом) приборы и лампы в кабине светятся цветом уровня машины, горят плафоны и полоса у пола");
            cabinLightLevel = num(c, "cabinLightLevel", v, 0.8F, 0F, 1F,
                    "Client: brightness of the night cabin lighting 0..1 / Клиент: яркость ночной подсветки салона 0..1");
            cockpitStartup = c.getBoolean("cockpitStartup", v, true,
                    "Client: boarding as the driver starts the cockpit - lamp test, SA logo on the screens, needles sweep / "
                            + "Клиент: при посадке водителем кабина запускается - тест ламп, логотип SA на экранах, пробег стрелок");
            cockpitSounds = c.getBoolean("cockpitSounds", v, true,
                    "Client: quiet cockpit warning sounds for the riders - overheating beep once a second, stall siren / "
                            + "Клиент: тихие звуки предупреждений в кабине - писк перегрева раз в секунду, сирена сваливания");
            sounds = c.getBoolean("sounds", v, true,
                    "Client: engine, rotor and turbine sounds of vehicles and their own effects (boarding, hatch, landing, hits) / "
                            + "Клиент: звуки моторов, винтов и турбин техники и её собственные звуки (посадка, багажник, касание, удары)");
            soundVolume = num(c, "soundVolume", v, 1F, 0F, 2F,
                    "Client: vehicle engine sounds volume x this (0..2) / Клиент: громкость звуков моторов x это (0..2)");
            vehicleCamera = c.getBoolean("vehicleCamera", v, true,
                    "Client: third-person camera while riding - farther by the vehicle's size, higher, follows turns smoothly, mouse wheel zooms / "
                            + "Клиент: камера от третьего лица в технике - дальше по размеру машины, выше, плавно догоняет, колёсико - дистанция");
            cameraDistance = num(c, "cameraDistance", v, 1F, 0.25F, 4F,
                    "Client: that camera's distance x this / Клиент: дистанция этой камеры x это");
            cruise = c.getBoolean("cruise", v, true,
                    "Cruise control for ground vehicles and the hoverbike (key 'Cruise') / Круиз-контроль наземной техники и ховербайка (клавиша 'Круиз')");
            autopilot = c.getBoolean("autopilot", v, true,
                    "Autopilot for the plane, jet and tiltrotor (airplane mode): holds heading and height / "
                            + "Автопилот самолёта, реактивного и конвертоплана (режим самолёта): держит курс и высоту");
            tuning = c.getBoolean("tuning", v, true,
                    "Tuning upgrades installed in the repair garage change the vehicle (speed, buffer, armour, step, lights, drill, gas) / "
                            + "Улучшения тюнинга, поставленные в ремонтном гараже, меняют машину (скорость, заряд, броня, колёса, фары, бур, газ)");
            tuningVisuals = c.getBoolean("tuningVisuals", v, true,
                    "Client: installed upgrades are drawn on the vehicle model (armour plates, light bar, battery box, extra tank, big wheels) / "
                            + "Клиент: установленные улучшения видны на модели (бронелисты, люстра фар, доп. аккумулятор, бак, большие колёса)");
            garageHologram = c.getBoolean("garageHologram", v, true,
                    "Client: the repair garage shows a hologram ring over the vehicle while it repairs / "
                            + "Клиент: во время ремонта над машиной в гараже - кольцо-голограмма");
            locks = c.getBoolean("locks", v, true,
                    "Vehicle locks and key cards: access rights (drive / passenger / cargo / repair) and lock modes; false = old rules "
                            + "(owner drives, anyone rides as a passenger) / Замки техники и ключ-карты: права доступа и режимы замка; "
                            + "false - старые правила (водит владелец, пассажиром - любой)");
            lockOpBypass = c.getBoolean("lockOpBypass", v, true,
                    "Server operators pass any vehicle lock / Операторы сервера открывают любую машину");
            junk = com.sva.inventory.CargoToolsSVA.parseJunk(c.getStringList("junk", v, JUNK_DEFAULT,
                    "Cargo screen 'Junk' button: items dropped next to the vehicle (modid:name or modid:name@meta) / "
                            + "Кнопка 'Мусор' в окне багажника: что выбрасывается рядом с машиной"));
        } finally {
            if (c.hasChanged()) {
                c.save();
            }
        }
    }

    /** NaN / infinity -> default, else clamped into [min, max] (pure; the self-test checks it). */
    public static float sanitize(float value, float def, float min, float max) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return def;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static float num(Configuration c, String name, String cat, float def, float min, float max, String comment) {
        return sanitize(c.getFloat(name, cat, def, min, max, comment), def, min, max);
    }
}
