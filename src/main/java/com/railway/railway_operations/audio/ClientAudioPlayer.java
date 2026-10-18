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

    private static final double REF_DIST = 2.0;

    public static void playDelayed(byte[] oggData, Vec3 pos, int[] bogieIds, int delayTicks, double radius) {
        if (oggData == null || oggData.length == 0) return;
        if (delayTicks <= 0) {
            playNow(oggData, pos, bogieIds, radius);
        } else {
            synchronized (pending) {
                pending.add(new PendingBroadcast(oggData, pos, bogieIds, delayTicks, radius));
            }
        }
    }

    private static void playNow(byte[] oggData, Vec3 pos, int[] bogieIds, double radius) {
        try {
            playOgg(oggData, pos, bogieIds, radius);
        } catch (Exception e) {
            LOGGER.error("Failed to play audio", e);
        }
    }

    private static void playOgg(byte[] oggData, Vec3 pos, int[] bogieIds, double radius) throws IOException {
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
        AL10.alSourcef(source, AL10.AL_PITCH, 1.0F);
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0.0F);
        AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 1.0F);
        AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, Float.MAX_VALUE);
        AL10.alSource3f(source, AL10.AL_POSITION, (float) pos.x, (float) pos.y, (float) pos.z);
        AL10.alSourcef(source, AL10.AL_GAIN, gainForDistance(pos, radius));
        AL10.alSourcePlay(source);
        synchronized (activeSources) {
            activeSources.add(new TrackingSource(source, buffer, pos, bogieIds, radius));
        }
    }

    private static float gainForDistance(Vec3 srcPos, double maxDist) {
        var player = Minecraft.getInstance().player;
        if (player == null) return 1.0F;
        double dist = player.position().distanceTo(srcPos);
        if (dist <= REF_DIST) return 1.0F;
        if (dist >= maxDist) return 0.0F;
        double fade = maxDist - REF_DIST;
        return (float) (1.0 - (dist - REF_DIST) / fade);
    }

    private static Vec3 getTrackedCenter(int[] bogieIds) {
        var level = Minecraft.getInstance().level;
        if (level == null || bogieIds.length == 0) return Vec3.ZERO;
        double sx = 0, sy = 0, sz = 0;
        int count = 0;
        for (int id : bogieIds) {
            Entity e = level.getEntity(id);
            if (e == null) continue;
            Vec3 c = e.getBoundingBox().getCenter();
            sx += c.x; sy += c.y; sz += c.z;
            count++;
        }
        return count > 0 ? new Vec3(sx / count, sy / count, sz / count) : Vec3.ZERO;
    }

    private static boolean wasPaused = false;

    public static void tick() {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        boolean isPaused = mc.isPaused();

        synchronized (activeSources) {
            if (isPaused && !wasPaused) {
                for (TrackingSource src : activeSources)
                    AL10.alSourcePause(src.source);
            } else if (!isPaused && wasPaused) {
                for (TrackingSource src : activeSources)
                    AL10.alSourcePlay(src.source);
            }
            wasPaused = isPaused;
            if (isPaused) return;

            Iterator<TrackingSource> it = activeSources.iterator();
            while (it.hasNext()) {
                TrackingSource src = it.next();
                if (AL10.alGetSourcei(src.source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) {
                    AL10.alDeleteSources(src.source);
                    AL10.alDeleteBuffers(src.buffer);
                    it.remove();
                } else if (src.bogieIds.length > 0) {
                    Vec3 c = getTrackedCenter(src.bogieIds);
                    AL10.alSource3f(src.source, AL10.AL_POSITION, (float) c.x, (float) c.y, (float) c.z);
                    if (player != null)
                        AL10.alSourcef(src.source, AL10.AL_GAIN, gainForDistance(c, src.radius));
                } else if (player != null) {
                    AL10.alSourcef(src.source, AL10.AL_GAIN, gainForDistance(src.pos, src.radius));
                }
            }
        }
        synchronized (pending) {
            if (isPaused) return;
            Iterator<PendingBroadcast> it = pending.iterator();
            while (it.hasNext()) {
                PendingBroadcast pb = it.next();
                if (--pb.delay <= 0) {
                    playNow(pb.data, pb.pos, pb.bogieIds, pb.radius);
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

    private record TrackingSource(int source, int buffer, Vec3 pos, int[] bogieIds, double radius) {}

    private static class PendingBroadcast {
        final byte[] data; final Vec3 pos; final int[] bogieIds; int delay; final double radius;
        PendingBroadcast(byte[] d, Vec3 p, int[] ids, int del, double r) {
            data = d; pos = p; bogieIds = ids; delay = del; radius = r;
        }
    }
}
