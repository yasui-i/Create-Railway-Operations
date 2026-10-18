package com.railway.railway_operations.broadcast;

import java.util.*;

import com.google.common.collect.ImmutableList;
import com.railway.railway_operations.audio.AudioManager;
import com.railway.railway_operations.audio.AudioPack;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction;
import com.simibubi.create.foundation.gui.ModularGuiLineBuilder;

import net.createmod.catnip.data.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * A top-level schedule instruction that plays a train announcement broadcast.
 * Supports manual delay and distance-based auto-trigger (fires when the train
 * is within N blocks of the target station).
 * <p>
 * Distance mode uses a tick listener to monitor {@code Navigation.distanceToDestination},
 * which is populated when the next schedule entry ("Go to Station") starts navigation.
 */
public class BroadcastInstruction extends ScheduleInstruction {

    private static final String PACK_KEY = "AudioPack";
    private static final String BROADCAST_KEY = "Broadcast";
    private static final String PACK_ID_KEY = "AudioPackId";
    private static final String BROADCAST_ID_KEY = "BroadcastId";
    private static final String DELAY_KEY = "Delay";
    private static final String DELAY_UNIT_KEY = "DelayUnit";
    private static final String DISTANCE_KEY = "DistanceThreshold";

    // Pending distance-triggered broadcasts: train UUID → threshold + playback info
    private static final Map<UUID, PendingBroadcast> pending = new LinkedHashMap<>();
    private static boolean tickListenerRegistered = false;

    public BroadcastInstruction() {
        super();
        data.putInt(PACK_KEY, 0);
        data.putInt(BROADCAST_KEY, 0);
        data.putInt(DELAY_KEY, 0);
        data.putInt(DELAY_UNIT_KEY, 0);
        data.putInt(DISTANCE_KEY, 0);
    }

    private int getDelaySeconds() {
        int val = data.getInt(DELAY_KEY);
        var unit = com.simibubi.create.content.trains.schedule.condition.TimedWaitCondition.TimeUnit
                .values()[Math.min(data.getInt(DELAY_UNIT_KEY), 2)];
        return val * (unit.ticksPer / 20);
    }

    private int getDistanceThreshold() {
        return data.getInt(DISTANCE_KEY);
    }

    @Override
    public ResourceLocation getId() {
        return ResourceLocation.fromNamespaceAndPath("railway_operations", "broadcast_instruction");
    }

    @Override
    public boolean supportsConditions() {
        return false;
    }

    @Override
    public DiscoveredPath start(ScheduleRuntime runtime, Level level) {
        Train train = runtime.train;
        int threshold = getDistanceThreshold();

        if (threshold > 0) {
            // Distance mode: register a pending check. The tick listener will
            // fire the broadcast when navigation.distanceToDestination <= threshold.
            registerTickListener();
            pending.put(train.id, new PendingBroadcast(
                    train, level, resolvePack(), resolveBroadcastKey(resolvePack()),
                    getDelaySeconds() * 20, threshold));
        } else {
            // Manual delay mode: play immediately with configured delay
            playBroadcastOnTrain(train, level, getDelaySeconds() * 20);
        }

        runtime.state = ScheduleRuntime.State.PRE_TRANSIT;
        runtime.currentEntry++;
        return null;
    }

    private static synchronized void registerTickListener() {
        if (tickListenerRegistered) return;
        tickListenerRegistered = true;
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            if (pending.isEmpty()) return;
            Iterator<Map.Entry<UUID, PendingBroadcast>> it = pending.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                PendingBroadcast pb = entry.getValue();
                Train train = pb.train;
                if (train.navigation == null || train.navigation.destination == null) continue;
                double remaining = train.navigation.distanceToDestination;
                // distanceToDestination = remaining distance to target (decreasing).
                // Fires when the train is within threshold blocks of the station.
                if (remaining > 0 && remaining <= pb.threshold) {
                    it.remove();
                    pb.play();
                }
            }
        });
    }

    private void playBroadcastOnTrain(Train train, Level level, int delayTicks) {
        if (level.isClientSide) return;
        AudioPack pack = resolvePack();
        if (pack == null) return;
        AudioPack.BroadcastDef def = pack.broadcasts().get(resolveBroadcastKey(pack));
        if (def == null) return;

        playPackOnTrain(pack, def, train, level, delayTicks);
    }

    private static void playPackOnTrain(AudioPack pack, AudioPack.BroadcastDef def,
                                         Train train, Level level, int delayTicks) {
        for (Carriage carriage : train.carriages) {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null) continue;

            if (def.isSimple()) {
                sendPacketStatic(pack, def.fileName(), carriage, entity, delayTicks, train);
            } else {
                for (String lang : new String[]{"zh"}) {
                    String tpl = "zh".equals(lang) ? def.zhTemplate() : def.enTemplate();
                    if (tpl != null)
                        sendPacketStatic(pack, lang + "/" + tpl, carriage, entity, delayTicks, train);
                    if (def.hasStation()) {
                        String sid = resolveStationIdStatic(train);
                        if (sid != null)
                            sendPacketStatic(pack, lang + "/stations/" + sid + ".ogg", carriage, entity, delayTicks, train);
                    }
                }
            }
        }
    }

    private void sendPacket(AudioPack pack, String filePath, Carriage carriage,
                             CarriageContraptionEntity entity, int delayTicks) {
        sendPacketStatic(pack, filePath, carriage, entity, delayTicks, null);
    }

    private static void sendPacketStatic(AudioPack pack, String filePath, Carriage carriage,
                                          CarriageContraptionEntity entity, int delayTicks, Train train) {
        java.nio.file.Path file = pack.directory().resolve(filePath);
        String hash = com.railway.railway_operations.audio.AudioHashRegistry.getHash(file);
        if (hash == null) return;

        double[] sum = {0, 0, 0};
        int[] count = {0};
        carriage.forEachPresentEntity(e -> {
            Vec3 c = e.getBoundingBox().getCenter();
            sum[0] += c.x; sum[1] += c.y; sum[2] += c.z;
            count[0]++;
        });
        double cx = sum[0] / count[0];
        double cy = sum[1] / count[0];
        double cz = sum[2] / count[0];

        int[] bogieIds = new int[count[0]];
        int[] idx = {0};
        carriage.forEachPresentEntity(e -> bogieIds[idx[0]++] = e.getId());

        var packet = new com.railway.railway_operations.network.ClientboundPlayBroadcastPacket(
                hash, cx, cy, cz, bogieIds, delayTicks, 16.0);
        net.neoforged.neoforge.network.PacketDistributor
                .sendToPlayersTrackingEntity(entity, packet);
    }

    private AudioPack resolvePack() {
        String packId = data.getString(PACK_ID_KEY);
        if (!packId.isEmpty()) {
            AudioPack selected = AudioManager.getPack(packId);
            if (selected != null) return selected;
        }
        int idx = data.getInt(PACK_KEY);
        int i = 0;
        for (AudioPack pack : AudioManager.getPacks().values()) {
            if (i++ == idx) {
                data.putString(PACK_ID_KEY, pack.id());
                return pack;
            }
        }
        return null;
    }

    private String resolveBroadcastKey(AudioPack pack) {
        if (pack == null || pack.broadcasts().isEmpty()) return "";
        String broadcastId = data.getString(BROADCAST_ID_KEY);
        if (!broadcastId.isEmpty() && pack.broadcasts().containsKey(broadcastId)) return broadcastId;
        int idx = data.getInt(BROADCAST_KEY);
        if (idx < 0 || idx >= pack.broadcasts().size()) {
            data.putInt(BROADCAST_KEY, 0);
            idx = 0;
        }
        int i = 0;
        for (String key : pack.broadcasts().keySet()) {
            if (i++ == idx) {
                data.putString(BROADCAST_ID_KEY, key);
                return key;
            }
        }
        return pack.broadcasts().keySet().iterator().next();
    }
    private int indexOfPack(List<AudioPack> packList, AudioPack target) {
        int index = packList.indexOf(target);
        return index >= 0 ? index : 0;
    }

    private int indexOfBroadcast(AudioPack pack, String key) {
        if (pack != null && !key.isEmpty()) {
            int index = 0;
            for (String current : pack.broadcasts().keySet()) {
                if (current.equals(key)) return index;
                index++;
            }
        }
        return 0;
    }
    private String resolveStationId(Train train) {
        return resolveStationIdStatic(train);
    }

    private static String resolveStationIdStatic(Train train) {
        var s = train.getCurrentStation();
        return s != null ? s.name : null;
    }

    // ---- GUI ----

    private String delaySuffix() {
        int dist = getDistanceThreshold();
        if (dist > 0) return " 到站前" + dist + "m";
        int val = data.getInt(DELAY_KEY);
        if (val <= 0) return "";
        var unit = com.simibubi.create.content.trains.schedule.condition.TimedWaitCondition.TimeUnit
                .values()[Math.min(data.getInt(DELAY_UNIT_KEY), 2)];
        return " +" + val + unit.suffix;
    }

    @Override
    public Pair<ItemStack, Component> getSummary() {
        return Pair.of(new ItemStack(Items.NOTE_BLOCK),
                Component.literal(getDisplayBroadcastKey()
                        + " [" + getDisplayPackName() + "]" + delaySuffix()));
    }

    @Override
    public List<Component> getTitleAs(String displayType) {
        return ImmutableList.of(
                Component.translatable("railway_operations.schedule.instruction.broadcast_instruction")
                        .withStyle(ChatFormatting.GOLD),
                Component.literal(getDisplayPackName() + " - " + getDisplayBroadcastKey()
                        + delaySuffix())
                        .withStyle(ChatFormatting.DARK_AQUA));
    }

    @Override
    public ItemStack getSecondLineIcon() {
        return new ItemStack(Items.NOTE_BLOCK);
    }

    @Override
    public List<Component> getSecondLineTooltip(int slot) {
        return ImmutableList.of();
    }

    @Override
    public void initConfigurationWidgets(ModularGuiLineBuilder builder) {
        Map<String, AudioPack> packs = AudioManager.getPacks();

        // Pre-build pack names (stable during GUI lifetime)
        List<AudioPack> packList = new ArrayList<>(packs.values());
        List<String> packNames = new ArrayList<>();
        for (AudioPack p : packList) packNames.add(p.name());
        if (packNames.isEmpty()) packNames.add("(no packs)");

        final com.simibubi.create.foundation.gui.widget.SelectionScrollInput[] bcWidget = {null};

        // Helper: rebuild broadcast dropdown options for a given pack
        java.util.function.Consumer<Integer> refreshBroadcastOptions = idx -> {
            data.putInt(BROADCAST_KEY, 0);
            data.remove(BROADCAST_ID_KEY);
            if (idx >= 0 && idx < packList.size()) data.putString(PACK_ID_KEY, packList.get(idx).id());
            if (bcWidget[0] == null || idx < 0 || idx >= packList.size()) return;
            AudioPack pack = packList.get(idx);
            List<String> bcNames = new ArrayList<>(pack.broadcasts().keySet());
            if (bcNames.isEmpty()) bcNames.add("...");
            bcWidget[0].forOptions(bcNames.stream().map(Component::literal).toList());
            bcWidget[0].setState(0);
        };

        builder.addSelectionScrollInput(0, 32, (sel, label) -> {
            sel.forOptions(packNames.stream().map(Component::literal).toList());
            sel.setState(Math.min(indexOfPack(packList, resolvePack()), packNames.size() - 1));
            sel.format(idx -> idx >= 0 && idx < packNames.size()
                    ? Component.literal(truncate(packNames.get(idx), 4))
                    : Component.literal("..."));
            sel.titled(Component.translatable("railway_operations.gui.select_pack"));
            sel.calling(idx -> {
                data.putInt(PACK_KEY, idx);
                refreshBroadcastOptions.accept(idx);
            });
        }, PACK_KEY);

        builder.addSelectionScrollInput(34, 42, (sel, label) -> {
            bcWidget[0] = sel;
            AudioPack pack = resolvePack();
            List<String> bcNames = new ArrayList<>();
            if (pack != null) bcNames.addAll(pack.broadcasts().keySet());
            if (bcNames.isEmpty()) bcNames.add("...");
            sel.forOptions(bcNames.stream().map(Component::literal).toList());
            sel.setState(Math.min(indexOfBroadcast(resolvePack(), data.getString(BROADCAST_ID_KEY)), bcNames.size() - 1));
            sel.format(idx -> idx >= 0 && idx < bcNames.size()
                    ? Component.literal(truncate(bcNames.get(idx), 4))
                    : Component.literal("..."));
            sel.titled(Component.translatable("railway_operations.gui.select_broadcast"));
            sel.calling(idx -> {
                data.putInt(BROADCAST_KEY, idx);
                AudioPack selectedPack = resolvePack();
                if (selectedPack != null && idx >= 0 && idx < selectedPack.broadcasts().size())
                    data.putString(BROADCAST_ID_KEY, new ArrayList<>(selectedPack.broadcasts().keySet()).get(idx));
            });
        }, BROADCAST_KEY);

        builder.addScrollInput(78, 16, (scroll, label) -> {
            scroll.withRange(0, 2000)
                    .titled(Component.translatable("railway_operations.gui.distance_threshold"));
            scroll.setState(data.getInt(DISTANCE_KEY));
            scroll.format(v -> Component.literal(v == 0 ? "off" : v + "m"));
        }, DISTANCE_KEY);

        builder.addScrollInput(96, 16, (scroll, label) -> {
            scroll.withRange(0, 60).titled(Component.translatable("railway_operations.gui.delay_value"));
            scroll.setState(data.getInt(DELAY_KEY));
        }, DELAY_KEY);

        builder.addSelectionScrollInput(114, 30, (sel, label) -> {
            sel.forOptions(com.simibubi.create.content.trains.schedule.condition
                    .TimedWaitCondition.TimeUnit.translatedOptions());
            sel.setState(Math.min(data.getInt(DELAY_UNIT_KEY), 2));
        }, DELAY_UNIT_KEY);
    }

    private String getDisplayPackName() {
        AudioPack pack = resolvePack();
        return pack != null ? pack.name() : "(no pack)";
    }

    private String getDisplayBroadcastKey() {
        AudioPack pack = resolvePack();
        if (pack == null) return "...";
        String key = resolveBroadcastKey(pack);
        return key.isEmpty() ? "..." : key;
    }

    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 1) + "…";
    }

    // ---- Persistence ----

    @Override
    protected void writeAdditional(net.minecraft.core.HolderLookup.Provider provider, CompoundTag tag) {
        tag.putInt(PACK_KEY, data.getInt(PACK_KEY));
        tag.putInt(BROADCAST_KEY, data.getInt(BROADCAST_KEY));
        tag.putString(PACK_ID_KEY, data.getString(PACK_ID_KEY));
        tag.putString(BROADCAST_ID_KEY, data.getString(BROADCAST_ID_KEY));
        tag.putInt(DELAY_KEY, data.getInt(DELAY_KEY));
        tag.putInt(DELAY_UNIT_KEY, data.getInt(DELAY_UNIT_KEY));
        tag.putInt(DISTANCE_KEY, data.getInt(DISTANCE_KEY));
        super.writeAdditional(provider, tag);
    }

    @Override
    protected void readAdditional(net.minecraft.core.HolderLookup.Provider provider, CompoundTag tag) {
        if (tag.contains(PACK_KEY)) data.putInt(PACK_KEY, tag.getInt(PACK_KEY));
        if (tag.contains(BROADCAST_KEY)) data.putInt(BROADCAST_KEY, tag.getInt(BROADCAST_KEY));
        if (tag.contains(PACK_ID_KEY)) data.putString(PACK_ID_KEY, tag.getString(PACK_ID_KEY));
        if (tag.contains(BROADCAST_ID_KEY)) data.putString(BROADCAST_ID_KEY, tag.getString(BROADCAST_ID_KEY));
        if (tag.contains(DELAY_KEY)) data.putInt(DELAY_KEY, tag.getInt(DELAY_KEY));
        if (tag.contains(DELAY_UNIT_KEY)) data.putInt(DELAY_UNIT_KEY, tag.getInt(DELAY_UNIT_KEY));
        if (tag.contains(DISTANCE_KEY)) data.putInt(DISTANCE_KEY, tag.getInt(DISTANCE_KEY));
        super.readAdditional(provider, tag);
    }

    // ---- Pending broadcast record ----

    private static class PendingBroadcast {
        final Train train;
        final Level level;
        final AudioPack pack;
        final String broadcastKey;
        final int delayTicks;
        final int threshold;

        PendingBroadcast(Train train, Level level, AudioPack pack, String broadcastKey,
                         int delayTicks, int threshold) {
            this.train = train;
            this.level = level;
            this.pack = pack;
            this.broadcastKey = broadcastKey;
            this.delayTicks = delayTicks;
            this.threshold = threshold;
        }

        void play() {
            if (pack == null || broadcastKey == null || broadcastKey.isEmpty()) return;
            AudioPack.BroadcastDef def = pack.broadcasts().get(broadcastKey);
            if (def == null) return;
            playPackOnTrain(pack, def, train, level, delayTicks);
        }
    }
}
