package com.railway.railway_operations.network;

import com.railway.railway_operations.audio.ClientAudioCache;
import com.railway.railway_operations.audio.ClientAudioPlayer;

/** Client-side handler: resolves audio from cache and plays. */
public class BroadcastPacketHandler {

    // Prevent duplicate plays of the same hash within a short window (avoids echo)
    private static final long DEDUP_WINDOW_MS = 2_000;
    private static final java.util.Map<String, Long> recentPlays = new java.util.HashMap<>();

    public static void handle(ClientboundPlayBroadcastPacket packet) {
        byte[] data = ClientAudioCache.get(packet.hash());
        if (data != null) {
            long now = System.currentTimeMillis();
            Long last = recentPlays.get(packet.hash());
            if (last != null && (now - last) < DEDUP_WINDOW_MS) {
                return; // skip duplicate — prevents echo from multi-carriage or double-send
            }
            recentPlays.put(packet.hash(), now);
            // Occasional cleanup (every ~64 plays on average)
            if ((recentPlays.size() & 0x3f) == 0) {
                cleanupRecentPlays();
            }
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

    /** Periodic cleanup to prevent unbounded growth. Called from onDataReceived. */
    private static void cleanupRecentPlays() {
        long now = System.currentTimeMillis();
        recentPlays.values().removeIf(t -> (now - t) > DEDUP_WINDOW_MS * 2);
    }

    private static void scheduleRetry(ClientboundPlayBroadcastPacket packet) {
        // Will retry after server responds with data
        PendingRetry.add(packet);
    }

    /** Called when ClientboundAudioDataPacket arrives to retry pending plays. */
    public static void onDataReceived(String hash) {
        cleanupRecentPlays();
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
