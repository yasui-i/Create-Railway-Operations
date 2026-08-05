package com.railway.railway_operations.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.sound.sampled.AudioFormat;

import org.lwjgl.openal.AL10;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.FloatSampleSource;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public class ClientAudioPlayer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<TrackingSource> activeSources = new ArrayList<>();
    private static final List<PendingBroadcast> pending = new ArrayList<>();

    /** Play OGG data at the entity's position. */
    public static void playDelayed(byte[] oggData, int entityId, int delayTicks) {
        if (oggData == null || oggData.length == 0) return;
        if (delayTicks <= 0) {
            playNow(oggData, entityId);
        } else {
            synchronized (pending) {
                pending.add(new PendingBroadcast(oggData, entityId, delayTicks));
            }
        }
    }

    private static void playNow(byte[] oggData, int entityId) {
        try {
            playOgg(oggData, entityId);
        } catch (Exception e) {
            LOGGER.error("Failed to play audio", e);
        }
    }

    private static void playOgg(byte[] oggData, int entityId) throws IOException {
        FloatSampleSource stream = new JOrbisAudioStream(
                new java.io.ByteArrayInputStream(oggData));
        AudioFormat fmt = stream.getFormat();

        int channels = fmt.getChannels();
        int sampleRate = (int) fmt.getSampleRate();
        int openAlFormat = (channels == 1)
                ? (fmt.getSampleSizeInBits() == 8 ? AL10.AL_FORMAT_MONO8 : AL10.AL_FORMAT_MONO16)
                : (fmt.getSampleSizeInBits() == 8 ? AL10.AL_FORMAT_STEREO8 : AL10.AL_FORMAT_STEREO16);

        ByteBuffer pcm = stream.readAll();
        stream.close();

        int buffer = AL10.alGenBuffers();
        AL10.alBufferData(buffer, openAlFormat, pcm, sampleRate);

        int source = AL10.alGenSources();
        AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
        AL10.alSourcef(source, AL10.AL_GAIN, 1.0F);
        AL10.alSourcef(source, AL10.AL_PITCH, 1.0F);
        Vec3 pos = getEntityPos(entityId);
        AL10.alSource3f(source, AL10.AL_POSITION,
                (float) pos.x, (float) pos.y, (float) pos.z);
        AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 1.0F);
        AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, 32.0F);
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0.5F);

        AL10.alSourcePlay(source);
        synchronized (activeSources) {
            activeSources.add(new TrackingSource(source, buffer, entityId));
        }
    }

    private static Vec3 getEntityPos(int entityId) {
        if (Minecraft.getInstance().level == null) return Vec3.ZERO;
        Entity e = Minecraft.getInstance().level.getEntity(entityId);
        return e != null ? e.position() : Vec3.ZERO;
    }

    public static void tick() {
        var level = Minecraft.getInstance().level;
        synchronized (activeSources) {
            Iterator<TrackingSource> it = activeSources.iterator();
            while (it.hasNext()) {
                TrackingSource src = it.next();
                if (AL10.alGetSourcei(src.source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) {
                    AL10.alDeleteSources(src.source);
                    AL10.alDeleteBuffers(src.buffer);
                    it.remove();
                } else if (src.entityId >= 0 && level != null) {
                    Entity e = level.getEntity(src.entityId);
                    if (e != null) {
                        AL10.alSource3f(src.source, AL10.AL_POSITION,
                                (float) e.getX(), (float) e.getY(), (float) e.getZ());
                    }
                }
            }
        }
        synchronized (pending) {
            Iterator<PendingBroadcast> it = pending.iterator();
            while (it.hasNext()) {
                PendingBroadcast pb = it.next();
                if (--pb.delay <= 0) {
                    playNow(pb.data, pb.entityId);
                    it.remove();
                }
            }
        }
    }

    public static void stopAll() {
        synchronized (activeSources) {
            for (TrackingSource src : activeSources) {
                AL10.alSourceStop(src.source);
                AL10.alDeleteSources(src.source);
                AL10.alDeleteBuffers(src.buffer);
            }
            activeSources.clear();
        }
        synchronized (pending) { pending.clear(); }
    }

    private record TrackingSource(int source, int buffer, int entityId) {}

    private static class PendingBroadcast {
        final byte[] data; final int entityId; int delay;
        PendingBroadcast(byte[] d, int e, int del) { data = d; entityId = e; delay = del; }
    }
}
