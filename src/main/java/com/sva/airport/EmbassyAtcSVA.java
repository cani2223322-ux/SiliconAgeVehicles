package com.sva.airport;

import com.sva.block.AtcServerSVA;
import com.sva.compat.EmbassyFieldSVA;
import com.sva.entity.EntityVehicleSVA;

import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

/**
 * PACKAGE C (docs/plan-embassy-airfield-addon.md §3.4, §3.10): the embassy towers' air-traffic glue the others call - the ATIS line of the
 * Dispatcher's tab (IEmbassyAirHookSC.atisLine through EmbassyCompatSVA), the embassy's air defence on the radio (EmbassyAdSVA:
 * Cpdlc.U_EMB_AD into the aircraft's datalink log, the HUD line comes with EmbassyAdSVA's own message), the dome's warning to a pilot.
 * The rest of C lives in block.AtcServerSVA (ATIS radius, ОБСЛУЖИВАНИЕ ЗАПРЕЩЕНО, ПОСАДКА В КУПОЛЕ ЗАПРЕЩЕНА, the codes 5100..5177, who
 * occupies the runway, the taxi hold, no AD zone published for an embassy tower) and airport.AutoTower.
 * <p>
 * Signatures by the coordinator, bodies by C: both lean on block.AtcServerSVA (embassyAtis - the virtual tower's ATIS as atisChat makes it,
 * embassyRadio - the uplink into the session's log).
 */
public final class EmbassyAtcSVA {

    private EmbassyAtcSVA() {
    }

    /** The field's ATIS line now (its virtual tower's letter and text; null: none yet / off) - server thread. */
    public static IChatComponent atisLine(World w, EmbassyFieldSVA f) {
        return f == null || w == null || w.isRemote ? null : AtcServerSVA.embassyAtis(w, f.id);
    }

    /**
     * The embassy's air defence line `adLine` (EmbassyCompatSVA.AD_*) to board `callsign` on the radio of embassy `embassyId`'s tower: a
     * Cpdlc.U_EMB_AD uplink into the vehicle's datalink session log (the liner's MCDU shows it; no response wanted). Nothing when the
     * vehicle has no session (no tower in reach). Server thread.
     */
    public static void radio(World w, EntityVehicleSVA v, int embassyId, int adLine, String callsign) {
        if (w != null && !w.isRemote && v != null) {
            AtcServerSVA.INSTANCE.embassyRadio(w, v, embassyId, adLine, callsign);
        }
    }
}
