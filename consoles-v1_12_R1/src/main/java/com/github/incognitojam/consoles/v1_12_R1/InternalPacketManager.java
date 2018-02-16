package com.github.incognitojam.consoles.v1_12_R1;

import ca.jarcode.consoles.api.nms.ClientConnection;
import ca.jarcode.consoles.api.nms.PacketInternals;
import net.minecraft.server.v1_12_R1.Packet;
import net.minecraft.server.v1_12_R1.PacketPlayOutEntityMetadata;
import net.minecraft.server.v1_12_R1.PacketPlayOutMap;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class InternalPacketManager implements PacketInternals {

    private List<Packet> packets = new CopyOnWriteArrayList<>();

    @Override
    public void registerMetadataPacketTranslator(Player player) {
        PacketFunctions.registerOutListener(PacketPlayOutEntityMetadata.class, player,
                (packet) -> PacketFunctions.handleMetadataPacket(packet, player.getName()));
    }

    @Override
    public void registerMapPacketFilter(Player player) {
        PacketFunctions.registerOutListener(PacketPlayOutMap.class, player,
                (packet) -> PacketFunctions.handleMapPacket(packet, packets));
    }

    @Override
    public void registerMapPacket(Object packet) {
        if (packet instanceof PacketPlayOutMap)
            packets.add((PacketPlayOutMap) packet);
        else throw new RuntimeException("packet must be of type: " + PacketPlayOutMap.class.getName());
    }

    @Override
    public ClientConnection getConnection(Player player) {
        return new ConnectionWrapper(player);
    }

    @Override
    public Object createMapPacket(byte[] data, int id) {
        return PacketFunctions.createUpdatePacket(data, id, packets);
    }

}
