package com.railway.railway_operations.network;

import com.railway.railway_operations.audio.ClientAudioCache;
import com.railway.railway_operations.audio.ClientAudioPlayer;

import net.minecraft.world.phys.Vec3;

/** Client-side handler: resolves audio from cache and plays at the carriage center. */
public class BroadcastPacketHandler {

    public static void handle(ClientboundPlayBroadcastPacket packet) {
        byte[] data = ClientAudioCache.get(packet.hash());
        Vec3 pos = new Vec3(packet.posX(), packet.posY(), packet.posZ());
        if (data != null) {
            ClientAudioPlayer.playDelayed(data, pos, packet.bogieIds(), packet.delayTicks(), packet.radius());
        } else if (ClientAudioCache.isKnown(packet.hash())) {
            ClientAudioCache.requestFromServer(packet.hash());
            scheduleRetry(packet);
        } else {
            scheduleRetry(packet);
        }
    }

    private static void scheduleRetry(ClientboundPlayBroadcastPacket packet) {
        PendingRetry.add(packet);
    }

    public static void onDataReceived(String hash) {
        PendingRetry.retryAll(hash);
    }

    private static class PendingRetry {
        private static final java.util.List<ClientboundPlayBroadcastPacket> list =
                new java.util.ArrayList<>();

        static void add(ClientboundPlayBroadcastPacket p) { list.add(p); }

        static void retryAll(String hash) {
            var it = list.iterator();
            while (it.hasNext()) {
                ClientboundPlayBroadcastPacket p = it.next();
                if (p.hash().equals(hash)) {
                    byte[] data = ClientAudioCache.get(hash);
                    if (data != null) {
                        Vec3 pos = new Vec3(p.posX(), p.posY(), p.posZ());
                        ClientAudioPlayer.playDelayed(data, pos, p.bogieIds(), p.delayTicks(), p.radius());
                        it.remove();
                    }
                }
            }
        }
    }
}
