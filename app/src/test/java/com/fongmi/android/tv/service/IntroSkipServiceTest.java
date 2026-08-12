package com.fongmi.android.tv.service;

import com.fongmi.android.tv.service.IntroSkipService.IntroSkipPlan;
import com.fongmi.android.tv.service.IntroSkipService.Segment;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class IntroSkipServiceTest {

    @Test
    public void parseIntroDb_readsIntroRecapAndOutroSegments() {
        String body = "{"
                + "\"imdb_id\":\"tt0944947\","
                + "\"season\":1,"
                + "\"episode\":1,"
                + "\"intro\":{\"start_sec\":437,\"end_sec\":531,\"confidence\":1,\"submission_count\":2},"
                + "\"recap\":{\"start_ms\":12000,\"end_ms\":82000,\"confidence\":0.8,\"submission_count\":1},"
                + "\"outro\":{\"start_sec\":3631.5,\"end_sec\":3699.5,\"confidence\":1,\"submission_count\":2}"
                + "}";

        IntroSkipPlan plan = IntroSkipService.parseIntroDb(body, 3_700_000);

        assertFalse(plan.isEmpty());
        assertEquals(2, plan.getOpenings().size());
        assertEquals(Segment.Kind.RECAP, plan.getOpenings().get(0).getKind());
        assertEquals(12_000, plan.getOpenings().get(0).getStartMs());
        assertEquals(82_000, plan.getOpenings().get(0).getEndMs());
        assertEquals(Segment.Kind.INTRO, plan.getOpenings().get(1).getKind());
        assertEquals(437_000, plan.getOpenings().get(1).getStartMs());
        assertEquals(531_000, plan.getOpenings().get(1).getEndMs());
        assertEquals(1, plan.getEndings().size());
        assertEquals(3_631_500, plan.getEndings().get(0).getStartMs());
        assertEquals(3_699_500, plan.getEndings().get(0).getEndMs());
    }

    @Test
    public void parseTheIntroDb_readsIntroRecapAndCredits() {
        String body = "{"
                + "\"tmdb_id\":12345,"
                + "\"type\":\"movie\","
                + "\"intro\":[{\"start_ms\":null,\"end_ms\":23000}],"
                + "\"recap\":[{\"start_ms\":25000,\"end_ms\":134000}],"
                + "\"credits\":[{\"start_ms\":5801777,\"end_ms\":6371111}]"
                + "}";

        IntroSkipPlan plan = IntroSkipService.parseTheIntroDb(body, 7_200_000);

        assertEquals(2, plan.getOpenings().size());
        assertEquals(Segment.Kind.INTRO, plan.getOpenings().get(0).getKind());
        assertEquals(0, plan.getOpenings().get(0).getStartMs());
        assertEquals(23_000, plan.getOpenings().get(0).getEndMs());
        assertEquals(Segment.Kind.RECAP, plan.getOpenings().get(1).getKind());
        assertEquals(25_000, plan.getOpenings().get(1).getStartMs());
        assertEquals(134_000, plan.getOpenings().get(1).getEndMs());
        assertEquals(1, plan.getEndings().size());
        assertEquals(5_801_777, plan.getEndings().get(0).getStartMs());
        assertEquals(6_371_111, plan.getEndings().get(0).getEndMs());
    }

    @Test
    public void parseTheIntroDb_usesDurationForOpenEndedCredits() {
        String body = "{\"credits\":[{\"start_ms\":6408000,\"end_ms\":null}]}";

        IntroSkipPlan plan = IntroSkipService.parseTheIntroDb(body, 7_200_000);

        assertEquals(1, plan.getEndings().size());
        assertEquals(6_408_000, plan.getEndings().get(0).getStartMs());
        assertEquals(7_200_000, plan.getEndings().get(0).getEndMs());
    }

    @Test
    public void merge_deduplicatesOverlappingProviderSegments() {
        IntroSkipPlan introDb = IntroSkipService.parseIntroDb(
                "{\"intro\":{\"start_ms\":437000,\"end_ms\":531000,\"confidence\":1,\"submission_count\":2}}",
                3_700_000);
        IntroSkipPlan theIntroDb = IntroSkipService.parseTheIntroDb(
                "{\"intro\":[{\"start_ms\":438000,\"end_ms\":530000}]}",
                3_700_000);

        IntroSkipPlan merged = IntroSkipPlan.merge(List.of(introDb, theIntroDb));

        assertEquals(1, merged.getOpenings().size());
        assertEquals("IntroDB", merged.getOpenings().get(0).getProvider());
        assertEquals(437_000, merged.getOpenings().get(0).getStartMs());
    }
}
