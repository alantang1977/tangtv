package com.fongmi.android.tv.player.exo;

import androidx.media3.common.C;

import org.junit.Test;

import java.io.IOException;
import java.net.ProtocolException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HttpEofRecoveryPolicyTest {

    @Test
    public void recognizesOnlyUnexpectedFixedLengthEof() {
        assertTrue(HttpEofRecoveryDataSource.isRecoverableEof(new ProtocolException("unexpected end of stream")));
        assertTrue(HttpEofRecoveryDataSource.isRecoverableEof(new IOException(new ProtocolException("unexpected end of stream"))));
        assertFalse(HttpEofRecoveryDataSource.isRecoverableEof(new ProtocolException("invalid response")));
        assertFalse(HttpEofRecoveryDataSource.isRecoverableEof(new IOException("connection reset")));
    }

    @Test
    public void computesExactUnreadRangeLength() {
        assertEquals(6, HttpEofRecoveryDataSource.remainingLength(10, 4));
        assertEquals(0, HttpEofRecoveryDataSource.remainingLength(10, 12));
        assertEquals(C.LENGTH_UNSET, HttpEofRecoveryDataSource.remainingLength(C.LENGTH_UNSET, 4));
    }

    @Test
    public void capsOnlyConsecutiveFailures() {
        assertTrue(HttpEofRecoveryDataSource.canRecoverConsecutively(0));
        assertTrue(HttpEofRecoveryDataSource.canRecoverConsecutively(2));
        assertFalse(HttpEofRecoveryDataSource.canRecoverConsecutively(3));
        assertFalse(HttpEofRecoveryDataSource.canRecoverConsecutively(-1));
    }
}
