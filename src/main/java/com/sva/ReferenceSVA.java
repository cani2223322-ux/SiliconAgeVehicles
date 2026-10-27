package com.sva;

public final class ReferenceSVA {

    public static final String MODID = "SiliconAgeVehicles";
    /** Resource domain: 1.7.10 skips asset folders with capital letters. */
    public static final String ASSETS = "siliconagevehicles";
    public static final String NAME = "Silicon Age: Vehicles";
    public static final String VERSION = "0.1.1";
    /**
     * The core version this addon was built against (lowest that works). Embassy airfield: kept at 0.2.0 on purpose - the embassy part
     * needs the core's com.sc.embassy.api (API_VERSION 1, core 0.2.3 + df459a0 onwards) but finds it by name (compat.EmbassyCompatSVA):
     * an older core runs the add-on with the embassies' airfields simply off. Raise it with the core's release that ships the API.
     */
    public static final String CORE_DEP = "required-after:SiliconAge@[0.2.0,)";

    public static final String CLIENT_PROXY = "com.sva.proxy.ClientProxySVA";
    public static final String COMMON_PROXY = "com.sva.proxy.CommonProxySVA";

    private ReferenceSVA() {
    }
}
