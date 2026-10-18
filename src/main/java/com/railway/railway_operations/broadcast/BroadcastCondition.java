package com.railway.railway_operations.broadcast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.common.collect.ImmutableList;
import com.railway.railway_operations.audio.AudioManager;
import com.railway.railway_operations.audio.AudioPack;
import com.railway.railway_operations.network.ClientboundPlayBroadcastPacket;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.foundation.gui.ModularGuiLineBuilder;

import net.createmod.catnip.data.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Schedule condition that plays a broadcast on all carriages
 * after an optional delay. Completes immediately after playing.
 */
public class BroadcastCondition extends ScheduleWaitCondition {

    private static final String PACK_KEY = "AudioPack";       // int index
    private static final String BROADCAST_KEY = "Broadcast";  // int index
    private static final String PACK_ID_KEY = "AudioPackId";
    private static final String BROADCAST_ID_KEY = "BroadcastId";
    private static final String DELAY_KEY = "Delay";           // int value
    private static final String DELAY_UNIT_KEY = "DelayUnit";  // TimeUnit ordinal
    private static final Map<UUID, TriggerState> TRIGGER_GUARD = new LinkedHashMap<>();

    public BroadcastCondition() {
        super();
        data.putInt(PACK_KEY, 0);
        data.putInt(BROADCAST_KEY, 0);
        data.putInt(DELAY_KEY, 0);
        data.putInt(DELAY_UNIT_KEY, 0); // SECONDS
    }

    private int getDelaySeconds() {
        int val = data.getInt(DELAY_KEY);
        var unit = com.simibubi.create.content.trains.schedule.condition.TimedWaitCondition.TimeUnit
                .values()[Math.min(data.getInt(DELAY_UNIT_KEY), 2)];
        return val * (unit.ticksPer / 20);
    }

    @Override
    public ResourceLocation getId() {
        return ResourceLocation.fromNamespaceAndPath("railway_operations", "broadcast");
    }

    // ---- Resolve indices at runtime ----

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

    // ---- Tick ----

    @Override
    public boolean tickCompletion(Level level, Train train, CompoundTag context) {
        if (context.getBoolean("BroadcastFired")) return true;
        context.putBoolean("BroadcastFired", true);

        String station = currentStationKey(train);
        String conditionKey = data.getString(PACK_ID_KEY) + "|" + data.getString(BROADCAST_ID_KEY)
                + "|" + data.getInt(PACK_KEY) + "|" + data.getInt(BROADCAST_KEY);
        UUID trainId = train.id;
        long gameTime = level.getGameTime();
        synchronized (TRIGGER_GUARD) {
            TriggerState previous = TRIGGER_GUARD.get(trainId);
            if (previous != null && previous.station().equals(station)
                    && previous.conditionKey().equals(conditionKey)
                    && gameTime - previous.gameTime() < 20) return true;
            TRIGGER_GUARD.put(trainId, new TriggerState(station, conditionKey, gameTime));
            if (TRIGGER_GUARD.size() > 256) TRIGGER_GUARD.clear();
        }

        playBroadcastOnTrain(train, level, getDelaySeconds() * 20);
        return true;
    }

    private String currentStationKey(Train train) {
        var station = train.getCurrentStation();
        return station == null ? "" : station.name;
    }

    private record TriggerState(String station, String conditionKey, long gameTime) {}

    private void playBroadcastOnTrain(Train train, Level level, int delayTicks) {
        if (level.isClientSide) return;
        AudioPack pack = resolvePack();
        if (pack == null) return;
        AudioPack.BroadcastDef def = pack.broadcasts().get(resolveBroadcastKey(pack));
        if (def == null) return;

        for (Carriage carriage : train.carriages) {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null) continue;

            if (def.isSimple()) {
                sendPacket(pack, def.fileName(), carriage, entity, delayTicks);
            } else {
                for (String lang : new String[]{"zh"}) {
                    String tpl = "zh".equals(lang) ? def.zhTemplate() : def.enTemplate();
                    if (tpl != null) sendPacket(pack, lang + "/" + tpl, carriage, entity, delayTicks);
                    if (def.hasStation()) {
                        String sid = resolveStationId(train);
                        if (sid != null) sendPacket(pack,
                                lang + "/stations/" + sid + ".ogg", carriage, entity, delayTicks);
                    }
                }
            }
        }
    }

    private void sendPacket(AudioPack pack, String filePath, Carriage carriage,
                             CarriageContraptionEntity entity, int delayTicks) {
        java.nio.file.Path file = pack.directory().resolve(filePath);
        String hash = com.railway.railway_operations.audio.AudioHashRegistry.getHash(file);
        if (hash == null) return;

        // True carriage center = midpoint of all bogie entity positions
        double[] sum = {0, 0, 0};
        int[] count = {0};
        carriage.forEachPresentEntity(e -> {
            Vec3 c = e.getBoundingBox().getCenter();
            sum[0] += c.x; sum[1] += c.y; sum[2] += c.z;
            count[0]++;
        });
        if (count[0] == 0) return;

        double cx = sum[0] / count[0];
        double cy = sum[1] / count[0];
        double cz = sum[2] / count[0];

        // Collect bogie entity IDs for client-side position tracking (moving trains)
        int[] bogieIds = new int[count[0]];
        int[] idx = {0};
        carriage.forEachPresentEntity(e -> bogieIds[idx[0]++] = e.getId());

        var packet = new ClientboundPlayBroadcastPacket(hash, cx, cy, cz, bogieIds, delayTicks, 16.0);
        net.neoforged.neoforge.network.PacketDistributor
                .sendToPlayersTrackingEntity(entity, packet);
    }

    private String resolveStationId(Train train) {
        var s = train.getCurrentStation();
        return s != null ? s.name : null;
    }

    /** Truncate a string to maxLen characters, appending "…" if cut. */
    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 1) + "…";
    }

    // ---- GUI ----

    @Override
    public Pair<ItemStack, Component> getSummary() {
        return Pair.of(new ItemStack(Items.NOTE_BLOCK),
                Component.literal(getDisplayBroadcastKey()
                        + " [" + getDisplayPackName() + "]"
                        + delaySuffix()));
    }

    @Override
    public List<Component> getTitleAs(String displayType) {
        return ImmutableList.of(
                Component.translatable("railway_operations.schedule.condition.broadcast")
                        .withStyle(ChatFormatting.GOLD),
                Component.literal(getDisplayPackName() + " - " + getDisplayBroadcastKey()
                        + delaySuffix())
                        .withStyle(ChatFormatting.DARK_AQUA));
    }

    private String delaySuffix() {
        int val = data.getInt(DELAY_KEY);
        if (val <= 0) return "";
        var unit = com.simibubi.create.content.trains.schedule.condition.TimedWaitCondition.TimeUnit
                .values()[Math.min(data.getInt(DELAY_UNIT_KEY), 2)];
        return " +" + val + unit.suffix;
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
    public MutableComponent getWaitingStatus(Level level, Train train, CompoundTag context) {
        return Component.literal(getDisplayBroadcastKey());
    }

    @Override
    public void initConfigurationWidgets(ModularGuiLineBuilder builder) {
        // Layout: Pack(0,38) | Broadcast(40,42) | Delay(83,20) | "s"
        Map<String, AudioPack> packs = AudioManager.getPacks();

        List<AudioPack> packList = new ArrayList<>(packs.values());
        List<String> packNames = new ArrayList<>();
        for (AudioPack p : packList) packNames.add(p.name());
        if (packNames.isEmpty()) packNames.add("(no packs)");

        final com.simibubi.create.foundation.gui.widget.SelectionScrollInput[] bcWidget = {null};

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

        builder.addSelectionScrollInput(34, 38, (sel, label) -> {
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

        builder.addScrollInput(73, 16, (scroll, label) -> {
            scroll.withRange(0, 60).titled(Component.translatable("railway_operations.gui.delay_value"));
            scroll.setState(data.getInt(DELAY_KEY));
        }, DELAY_KEY);

        builder.addSelectionScrollInput(91, 30, (sel, label) -> {
            sel.forOptions(com.simibubi.create.content.trains.schedule.condition
                    .TimedWaitCondition.TimeUnit.translatedOptions());
            sel.setState(Math.min(data.getInt(DELAY_UNIT_KEY), 2));
        }, DELAY_UNIT_KEY);
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

    // ---- Persistence ----

    @Override
    protected void writeAdditional(net.minecraft.core.HolderLookup.Provider provider, CompoundTag tag) {
        tag.putInt(PACK_KEY, data.getInt(PACK_KEY));
        tag.putInt(BROADCAST_KEY, data.getInt(BROADCAST_KEY));
        tag.putString(PACK_ID_KEY, data.getString(PACK_ID_KEY));
        tag.putString(BROADCAST_ID_KEY, data.getString(BROADCAST_ID_KEY));
        tag.putInt(DELAY_KEY, data.getInt(DELAY_KEY));
        tag.putInt(DELAY_UNIT_KEY, data.getInt(DELAY_UNIT_KEY));
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
        super.readAdditional(provider, tag);
    }
}
