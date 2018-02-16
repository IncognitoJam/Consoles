package com.github.incognitojam.consoles.v1_12_R1;

import ca.jarcode.consoles.api.nms.ConsolesNMS;

public class NMSModule {

    public static void link() {
        ConsolesNMS.mapInternals = MapInjector.IMPL;
        ConsolesNMS.packetInternals = new InternalPacketManager();
        ConsolesNMS.internals = new GeneralUtils();
        ConsolesNMS.commandInternals = new CommandBlockUtils();
    }

}
