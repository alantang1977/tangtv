package com.fongmi.android.tv.player;

import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;
import com.fongmi.android.tv.setting.PlaybackProfileMergePolicy;

import org.junit.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackCompatibilityRetentionPolicyTest {

    @Test
    public void currentAssessmentRequiresCompatibilityProtection() {
        PlaybackCompatibilityRetentionPolicy.Assessment assessment =
                PlaybackCompatibilityRetentionPolicy.current();

        assertEquals(
                PlaybackCompatibilityRetentionPolicy.Status.RETAIN_REQUIRED,
                assessment.status());
        assertTrue(assessment.retainRequired());
        assertFalse(assessment.reviewEligible());
        assertEquals(14, assessment.totalRequirements());
        assertEquals(14, assessment.blockers().size());
    }

    @Test
    public void currentMatrixDistinguishesKnownEvidenceGaps() {
        PlaybackCompatibilityRetentionPolicy.Assessment assessment =
                PlaybackCompatibilityRetentionPolicy.current();

        assertEquals(14, PlaybackCompatibilityRetentionPolicy
                .currentEvidence().coverage().size());
        assertEquals(0, assessment.reliableRequirements());
        assertEquals(9, assessment.limitedRequirements());
        assertEquals(3, assessment.unverifiedRequirements());
        assertEquals(1, assessment.unobservableRequirements());
        assertEquals(1, assessment.missingRequirements());
        assertEquals(
                PlaybackCompatibilityRetentionPolicy.Coverage.UNOBSERVABLE,
                PlaybackCompatibilityRetentionPolicy.currentEvidence()
                        .coverage(PlaybackCompatibilityRetentionPolicy
                                .Requirement.VISUAL_CORRECTNESS));
    }

    @Test
    public void allReliableEvidenceOnlyPermitsFurtherReview() {
        PlaybackCompatibilityRetentionPolicy.Assessment assessment =
                PlaybackCompatibilityRetentionPolicy.evaluate(
                        PlaybackCompatibilityRetentionPolicy.Evidence
                                .allReliable());

        assertEquals(
                PlaybackCompatibilityRetentionPolicy.Status.REVIEW_ELIGIBLE,
                assessment.status());
        assertTrue(assessment.reviewEligible());
        assertFalse(assessment.retainRequired());
        assertTrue(Arrays.stream(
                        PlaybackProfileMergePolicy.selectableProfiles(true))
                .anyMatch(profile -> profile
                        == PlaybackPerformanceSetting.PROFILE_LIGHTWEIGHT));
    }

    @Test
    public void anyEngineCodecGapBlocksReview() {
        PlaybackCompatibilityRetentionPolicy.Evidence evidence =
                PlaybackCompatibilityRetentionPolicy.Evidence.allReliable()
                        .with(PlaybackCompatibilityRetentionPolicy
                                        .Requirement.MPV_CODEC_FAILURE,
                                PlaybackCompatibilityRetentionPolicy
                                        .Coverage.LIMITED);
        PlaybackCompatibilityRetentionPolicy.Assessment assessment =
                PlaybackCompatibilityRetentionPolicy.evaluate(evidence);

        assertTrue(assessment.retainRequired());
        assertEquals(1, assessment.blockers().size());
        assertEquals(
                PlaybackCompatibilityRetentionPolicy.Requirement
                        .MPV_CODEC_FAILURE,
                assessment.blockers().get(0).requirement());
    }

    @Test
    public void unobservableVisualCorrectnessIsAHardBlocker() {
        PlaybackCompatibilityRetentionPolicy.Evidence evidence =
                PlaybackCompatibilityRetentionPolicy.Evidence.allReliable()
                        .with(PlaybackCompatibilityRetentionPolicy
                                        .Requirement.VISUAL_CORRECTNESS,
                                PlaybackCompatibilityRetentionPolicy
                                        .Coverage.UNOBSERVABLE);
        PlaybackCompatibilityRetentionPolicy.Assessment assessment =
                PlaybackCompatibilityRetentionPolicy.evaluate(evidence);

        assertTrue(assessment.retainRequired());
        assertEquals(1, assessment.unobservableRequirements());
        assertEquals(
                PlaybackCompatibilityRetentionPolicy.Requirement
                        .VISUAL_CORRECTNESS,
                assessment.blockers().get(0).requirement());
    }

    @Test
    public void missingLongTermDeviceEvidenceBlocksReview() {
        PlaybackCompatibilityRetentionPolicy.Evidence evidence =
                PlaybackCompatibilityRetentionPolicy.Evidence.allReliable()
                        .with(PlaybackCompatibilityRetentionPolicy
                                        .Requirement.LONG_TERM_DEVICE_EVIDENCE,
                                PlaybackCompatibilityRetentionPolicy
                                        .Coverage.MISSING);
        PlaybackCompatibilityRetentionPolicy.Assessment assessment =
                PlaybackCompatibilityRetentionPolicy.evaluate(evidence);

        assertTrue(assessment.retainRequired());
        assertEquals(1, assessment.missingRequirements());
    }

    @Test
    public void omittedRequirementsFailClosed() {
        PlaybackCompatibilityRetentionPolicy.Assessment assessment =
                PlaybackCompatibilityRetentionPolicy.evaluate(
                        new PlaybackCompatibilityRetentionPolicy.Evidence(
                                Map.of(PlaybackCompatibilityRetentionPolicy
                                                .Requirement.EXO_CODEC_FAILURE,
                                        PlaybackCompatibilityRetentionPolicy
                                                .Coverage.RELIABLE)));

        assertTrue(assessment.retainRequired());
        assertEquals(1, assessment.reliableRequirements());
        assertEquals(13, assessment.missingRequirements());
    }

    @Test
    public void nullEvidenceFailsClosed() {
        PlaybackCompatibilityRetentionPolicy.Assessment assessment =
                PlaybackCompatibilityRetentionPolicy.evaluate(null);

        assertTrue(assessment.retainRequired());
        assertEquals(14, assessment.missingRequirements());
        assertEquals(14, assessment.blockers().size());
    }
}
