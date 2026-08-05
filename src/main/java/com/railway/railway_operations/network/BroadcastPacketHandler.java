package com.railway.railway_operations.network;

import com.railway.railway_operations.audio.ClientAudioCache;
import com.railway.railway_operations.audio.ClientAudioPlayer;

/** Client-side handler: resolves audio from cache and plays. */
public class BroadcastPacketHandler {

    public static void handle(ClientboundPlayBroadcastPacket packet) {
        byte[] data = ClientAudioCache.get(packet.hash());
        if (data != null) {
            ClientAudioPlayer.playDelayed(data, packet.entityId(), packet.delayTicks());
        } else if (ClientAudioCache.isKnown(packet.hash())) {
            // Known hash, not yet cached — request from server, retry later
            ClientAudioCache.requestFromServer(packet.hash());
            scheduleRetry(packet);
        } else {
            // Unknown hash — registry hasn't been synced yet, retry
            scheduleRetry(packet);
        }
    }

    private static void scheduleRetry(ClientboundPlayBroadcastPacket packet) {
        // Will retry after server responds with data
        PendingRetry.add(packet);
    }

    /** Called when ClientboundAudioDataPacket arrives to retry pending plays. */
    public static void onDataReceived(String hash) {
        PendingRetry.retryAll(hash);
    }

    // Simple retry queue
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
                        ClientAudioPlayer.playDelayed(data, p.entityId(), p.delayTicks());
                        it.remove();
                    }
                }
            }
        }
    }
}
