package com.fongmi.android.tv.player.engine;

import androidx.media3.common.C;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;

public class MpvAudioCapabilitiesTest {

    @Test
    public void mapsMedia3SurroundEncodingsToMpvSpdifNames() {
        Set<Integer> encodings = Set.of(
                C.ENCODING_AC3,
                C.ENCODING_E_AC3,
                C.ENCODING_DTS,
                C.ENCODING_DTS_HD,
                C.ENCODING_DOLBY_TRUEHD);

        assertEquals("ac3,eac3,dts,dts-hd,truehd",
                MpvAudioCapabilities.getAudioSpdifCodecs(encodings::contains));
    }

    @Test
    public void mapsCompatibleEnhancedEncodingsToTheirMpvFamilies() {
        Set<Integer> encodings = Set.of(C.ENCODING_E_AC3_JOC, C.ENCODING_DTS_HD_MA);

        assertEquals("eac3,dts,dts-hd",
                MpvAudioCapabilities.getAudioSpdifCodecs(encodings::contains));
    }

    @Test
    public void leavesSpdifDisabledWhenMedia3ReportsNoSurroundSupport() {
        assertEquals("", MpvAudioCapabilities.getAudioSpdifCodecs(encoding -> false));
    }

    @Test
    public void filtersAdvertisedCodecsByActualMpvCarrierSupport() {
        Set<String> advertised = Set.of("ac3", "eac3", "truehd");
        assertEquals("ac3,truehd",
                MpvAudioCapabilities.getAudioSpdifCodecs(
                        advertised, codec -> codec.equals("ac3") || codec.equals("truehd")));
    }

    @Test
    public void keepsCodecOrderStableWhenCarrierProbeAcceptsAll() {
        Set<String> advertised = Set.of("truehd", "dts-hd", "dts", "eac3", "ac3");
        assertEquals("ac3,eac3,dts,dts-hd,truehd",
                MpvAudioCapabilities.getAudioSpdifCodecs(advertised, codec -> true));
    }
}
