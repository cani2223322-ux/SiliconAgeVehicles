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
