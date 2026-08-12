package androidx.media3.mpvplayer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class MpvHlsCacheCoordinatorTest {

    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    private File directory;

    @Before
    public void setUp() throws IOException {
        directory = Files.createTempDirectory("mpv-hls-cache").toFile();
    }

    @After
    public void tearDown() {
        if (directory == null) return;
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(directory.toPath())) {
            paths
                    .sorted(Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(File::delete);
        } catch (IOException ignored) {
        }
    }

    @Test
    public void sharedRegistryReturnsOneCoordinatorForDirectory() {
        MpvHlsCacheCoordinator first = MpvHlsCacheCoordinator.shared(directory);
        MpvHlsCacheCoordinator second = MpvHlsCacheCoordinator.shared(new File(directory, "."));

        assertSame(first, second);
    }

    @Test
    public void concurrentReservationsConsumeOneSharedBudgetAndReleaseOnAbort() {
        FakeClock clock = new FakeClock(1_000_000);
        MpvHlsCacheCoordinator coordinator = coordinator(new MpvHlsCacheCoordinator.StorageFacts(true, 8 * GIB, 10 * GIB), clock);

        MpvHlsCacheCoordinator.ReservationDecision first = coordinator.tryReserve(
                "a.bin", new File(directory, "a.bin"), 700_000, MIB, MpvHlsCacheCoordinator.WriterType.PREFETCH);
        assertTrue(first.granted());

        MpvHlsCacheCoordinator.ReservationDecision blocked = coordinator.tryReserve(
                "b.bin", new File(directory, "b.bin"), 400_000, MIB, MpvHlsCacheCoordinator.WriterType.PREFETCH);
        assertFalse(blocked.granted());
        assertEquals(MpvHlsCacheCoordinator.DenyReason.NO_GROWTH, blocked.reason());

        first.reservation().abort();
        MpvHlsCacheCoordinator.ReservationDecision afterRelease = coordinator.tryReserve(
                "b.bin", new File(directory, "b.bin"), 400_000, MIB, MpvHlsCacheCoordinator.WriterType.PREFETCH);
        assertTrue(afterRelease.granted());
        afterRelease.reservation().abort();
    }

    @Test
    public void preloadSnapshotIncludesConcurrentReservationAndRelease() {
        MpvHlsCacheCoordinator coordinator = coordinator(
                new MpvHlsCacheCoordinator.StorageFacts(true, 8 * GIB, 10 * GIB),
                new FakeClock(1_000_000));
        MpvHlsCacheCoordinator.ReservationDecision reservation =
                coordinator.tryReserve(
                        "full.bin",
                        new File(directory, "full.bin"),
                        MIB - 256,
                        MIB,
                        MpvHlsCacheCoordinator.WriterType.PREFETCH);

        assertTrue(reservation.granted());
        MpvHlsCacheCoordinator.PreloadCapacitySnapshot blocked =
                coordinator.preloadSnapshot(MIB);
        assertFalse(blocked.allowed());
        assertEquals(MIB, blocked.capacity().reservedBytes());

        reservation.reservation().abort();
        assertTrue(coordinator.preloadSnapshot(MIB).allowed());
    }

    @Test
    public void temporaryFilesAreCountedAgainstCapacity() throws IOException {
        File temp = new File(directory, "active.tmp");
        writeBytes(temp, 900_000);
        temp.setLastModified(1_000_000);
        MpvHlsCacheCoordinator coordinator = coordinator(new MpvHlsCacheCoordinator.StorageFacts(true, 8 * GIB, 10 * GIB), new FakeClock(1_000_000));

        MpvHlsCacheCoordinator.ReservationDecision decision = coordinator.tryReserve(
                "new.bin", new File(directory, "new.bin"), 200_000, MIB, MpvHlsCacheCoordinator.WriterType.PREFETCH);

        assertFalse(decision.granted());
        assertEquals(MpvHlsCacheCoordinator.DenyReason.NO_GROWTH, decision.reason());
    }

    @Test
    public void writtenProgressMovesAccountingFromReservationToTempWithoutDoubleCounting() throws IOException {
        MpvHlsCacheCoordinator coordinator = coordinator(
                new MpvHlsCacheCoordinator.StorageFacts(true, 8 * GIB, 10 * GIB), new FakeClock(1_000_000));
        MpvHlsCacheCoordinator.ReservationDecision first = coordinator.tryReserve(
                "a.bin", new File(directory, "a.bin"), 600_000, MIB, MpvHlsCacheCoordinator.WriterType.PREFETCH);
        assertTrue(first.granted());
        try (FileOutputStream output = new FileOutputStream(first.reservation().tempFile())) {
            output.write(new byte[300_000]);
        }
        assertTrue(first.reservation().recordWritten(300_000));

        MpvHlsCacheCoordinator.ReservationDecision second = coordinator.tryReserve(
                "b.bin", new File(directory, "b.bin"), 300_000, MIB, MpvHlsCacheCoordinator.WriterType.PREFETCH);

        assertTrue(second.granted());
        second.reservation().abort();
        first.reservation().abort();
    }

    @Test
    public void unavailableStorageBlocksWritesButAllowsExistingReads() throws Exception {
        File cached = new File(directory, "cached.bin");
        byte[] bytes = new byte[]{1, 2, 3, 4};
        Files.write(cached.toPath(), bytes);
        MpvHlsCacheCoordinator coordinator = coordinator(new MpvHlsCacheCoordinator.StorageFacts(false, 0, 0), new FakeClock(1_000_000));

        MpvHlsCacheCoordinator.ReservationDecision decision = coordinator.tryReserve(
                "new.bin", new File(directory, "new.bin"), 100, MIB, MpvHlsCacheCoordinator.WriterType.FOREGROUND);
        assertFalse(decision.granted());
        assertEquals(MpvHlsCacheCoordinator.DenyReason.STORAGE_UNAVAILABLE, decision.reason());

        MpvHlsCacheCoordinator.ReadLease lease = coordinator.openRead(cached);
        assertTrue(lease != null);
        byte[] actual = lease.readAllBytes();
        lease.close();
        assertArrayEquals(bytes, actual);
    }

    @Test
    public void activeReaderIsExcludedFromLruDeletion() throws Exception {
        File active = new File(directory, "active.bin");
        File evictable = new File(directory, "evictable.bin");
        writeBytes(active, 500_000);
        writeBytes(evictable, 500_000);
        active.setLastModified(10);
        evictable.setLastModified(20);
        MpvHlsCacheCoordinator coordinator = coordinator(new MpvHlsCacheCoordinator.StorageFacts(true, 8 * GIB, 10 * GIB), new FakeClock(1_000_000));

        MpvHlsCacheCoordinator.ReadLease reader = coordinator.openRead(active);
        assertTrue(reader != null);
        MpvHlsCacheCoordinator.ReservationDecision decision = coordinator.tryReserve(
                "new.bin", new File(directory, "new.bin"), 400_000, MIB, MpvHlsCacheCoordinator.WriterType.PREFETCH);

        assertTrue(decision.granted());
        assertTrue(active.isFile());
        assertFalse(evictable.isFile());
        decision.reservation().abort();
        reader.close();
    }

    @Test
    public void duplicateKeyCannotHaveTwoWritersAndWinnerIsNotDeleted() throws Exception {
        FakeClock clock = new FakeClock(1_000_000);
        MpvHlsCacheCoordinator coordinator = coordinator(new MpvHlsCacheCoordinator.StorageFacts(true, 8 * GIB, 10 * GIB), clock);
        File file = new File(directory, "same.bin");
        MpvHlsCacheCoordinator.ReservationDecision first = coordinator.tryReserve(
                "same.bin", file, 4, MIB, MpvHlsCacheCoordinator.WriterType.PREFETCH);
        MpvHlsCacheCoordinator.ReservationDecision second = coordinator.tryReserve(
                "same.bin", file, 4, MIB, MpvHlsCacheCoordinator.WriterType.FOREGROUND);
        assertTrue(first.granted());
        assertFalse(second.granted());
        assertEquals(MpvHlsCacheCoordinator.DenyReason.DUPLICATE_KEY, second.reason());

        Files.write(file.toPath(), new byte[]{9, 8, 7, 6});
        try (FileOutputStream output = new FileOutputStream(first.reservation().tempFile())) {
            output.write(new byte[]{1, 2, 3, 4});
        }
        assertFalse(first.reservation().commit("video/mp2t"));
        assertArrayEquals(new byte[]{9, 8, 7, 6}, Files.readAllBytes(file.toPath()));
    }

    @Test
    public void successfulCommitPublishesCompleteFileAndReleasesReservation() throws Exception {
        MpvHlsCacheCoordinator coordinator = coordinator(
                new MpvHlsCacheCoordinator.StorageFacts(true, 8 * GIB, 10 * GIB), new FakeClock(1_000_000));
        File file = new File(directory, "commit.bin");
        byte[] bytes = new byte[]{1, 3, 5, 7};
        MpvHlsCacheCoordinator.ReservationDecision decision = coordinator.tryReserve(
                "commit.bin", file, bytes.length, MIB, MpvHlsCacheCoordinator.WriterType.PREFETCH);
        assertTrue(decision.granted());
        try (FileOutputStream output = new FileOutputStream(decision.reservation().tempFile())) {
            output.write(bytes);
        }
        assertTrue(decision.reservation().recordWritten(bytes.length));

        assertTrue(decision.reservation().commit("video/mp2t"));
        assertArrayEquals(bytes, Files.readAllBytes(file.toPath()));
        assertTrue(new File(directory, "commit.bin.meta").isFile());
        assertEquals(0, coordinator.snapshot(MIB).reservedBytes());
        MpvHlsCacheCoordinator.ReservationDecision duplicate = coordinator.tryReserve(
                "commit.bin", file, bytes.length, MIB, MpvHlsCacheCoordinator.WriterType.FOREGROUND);
        assertFalse(duplicate.granted());
        assertEquals(MpvHlsCacheCoordinator.DenyReason.ALREADY_CACHED, duplicate.reason());
    }

    @Test
    public void activeKernelClientsUseTheMostConservativePositiveLimit() {
        MpvHlsCacheCoordinator coordinator = coordinator(
                new MpvHlsCacheCoordinator.StorageFacts(true, 8 * GIB, 10 * GIB), new FakeClock(1_000_000));
        MpvHlsCacheCoordinator.ClientLease large = coordinator.registerClient(2 * MIB, null);
        MpvHlsCacheCoordinator.ClientLease small = coordinator.registerClient(MIB, null);

        MpvHlsCacheCoordinator.ReservationDecision blocked = coordinator.tryReserve(
                "large.bin", new File(directory, "large.bin"), 1_500_000, 2 * MIB, MpvHlsCacheCoordinator.WriterType.PREFETCH);
        assertFalse(blocked.granted());
        assertEquals(MpvHlsCacheCoordinator.DenyReason.NO_GROWTH, blocked.reason());

        small.close();
        MpvHlsCacheCoordinator.ReservationDecision allowed = coordinator.tryReserve(
                "large.bin", new File(directory, "large.bin"), 1_500_000, 2 * MIB, MpvHlsCacheCoordinator.WriterType.PREFETCH);
        assertTrue(allowed.granted());
        allowed.reservation().abort();
        large.close();
    }

    @Test
    public void enospcOpensSharedCircuitAndRecoversAfterStorageRetryDelay() {
        FakeClock clock = new FakeClock(1_000_000);
        MpvHlsCacheCoordinator coordinator = coordinator(new MpvHlsCacheCoordinator.StorageFacts(true, 8 * GIB, 10 * GIB), clock);
        AtomicInteger callbacks = new AtomicInteger();
        MpvHlsCacheCoordinator.ClientLease mpv = coordinator.registerClient(MIB, callbacks::incrementAndGet);
        MpvHlsCacheCoordinator.ClientLease ijk = coordinator.registerClient(MIB, callbacks::incrementAndGet);
        MpvHlsCacheCoordinator.ReservationDecision first = coordinator.tryReserve(
                "a.bin", new File(directory, "a.bin"), 100, MIB, MpvHlsCacheCoordinator.WriterType.PREFETCH);
        assertTrue(first.granted());
        first.reservation().fail(new IOException("write failed: ENOSPC"));

        assertTrue(coordinator.isCircuitOpen());
        assertEquals(2, callbacks.get());
        MpvHlsCacheCoordinator.ReservationDecision blocked = coordinator.tryReserve(
                "b.bin", new File(directory, "b.bin"), 100, MIB, MpvHlsCacheCoordinator.WriterType.PREFETCH);
        assertFalse(blocked.granted());
        assertEquals(MpvHlsCacheCoordinator.DenyReason.CIRCUIT_OPEN, blocked.reason());

        clock.advance(30_001);
        MpvHlsCacheCoordinator.ReservationDecision recovered = coordinator.tryReserve(
                "b.bin", new File(directory, "b.bin"), 100, MIB, MpvHlsCacheCoordinator.WriterType.PREFETCH);
        assertTrue(recovered.granted());
        assertFalse(coordinator.isCircuitOpen());
        recovered.reservation().abort();
        mpv.close();
        ijk.close();
    }

    @Test
    public void ordinaryWriteFailureDoesNotOpenCircuitButNestedNoSpaceDoes() {
        MpvHlsCacheCoordinator coordinator = coordinator(
                new MpvHlsCacheCoordinator.StorageFacts(true, 8 * GIB, 10 * GIB), new FakeClock(1_000_000));
        MpvHlsCacheCoordinator.ReservationDecision ordinary = coordinator.tryReserve(
                "ordinary.bin", new File(directory, "ordinary.bin"), 100, MIB, MpvHlsCacheCoordinator.WriterType.PREFETCH);
        assertTrue(ordinary.granted());
        ordinary.reservation().fail(new IOException("HTTP 503"));
        assertFalse(coordinator.isCircuitOpen());

        MpvHlsCacheCoordinator.ReservationDecision nested = coordinator.tryReserve(
                "nested.bin", new File(directory, "nested.bin"), 100, MIB, MpvHlsCacheCoordinator.WriterType.PREFETCH);
        assertTrue(nested.granted());
        nested.reservation().fail(new IllegalStateException("wrapper", new IOException("errno 28")));
        assertTrue(coordinator.isCircuitOpen());
    }

    @Test
    public void staleTempAndOrphanMetaAreRecoveredAtStartup() throws IOException {
        File staleTemp = new File(directory, "old.bin.tmp");
        File recentTemp = new File(directory, "recent.bin.tmp");
        File orphanMeta = new File(directory, "orphan.bin.meta");
        writeBytes(staleTemp, 4);
        writeBytes(recentTemp, 4);
        writeBytes(orphanMeta, 4);
        staleTemp.setLastModified(100);
        orphanMeta.setLastModified(100);
        recentTemp.setLastModified(950);

        new MpvHlsCacheCoordinator(directory, ignored -> new MpvHlsCacheCoordinator.StorageFacts(true, 8 * GIB, 10 * GIB), new FakeClock(1_000), 30_000, 100);

        assertFalse(staleTemp.exists());
        assertFalse(orphanMeta.exists());
        assertTrue(recentTemp.exists());
    }

    private MpvHlsCacheCoordinator coordinator(MpvHlsCacheCoordinator.StorageFacts storage, FakeClock clock) {
        return new MpvHlsCacheCoordinator(directory, ignored -> storage, clock, 30_000, Long.MAX_VALUE);
    }

    private static void writeBytes(File file, int count) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[Math.min(8192, count)];
            int remaining = count;
            while (remaining > 0) {
                int length = Math.min(remaining, buffer.length);
                output.write(buffer, 0, length);
                remaining -= length;
            }
        }
    }

    private static final class FakeClock implements MpvHlsCacheCoordinator.Clock {

        private long value;

        private FakeClock(long value) {
            this.value = value;
        }

        private void advance(long delta) {
            value += delta;
        }

        @Override
        public long now() {
            return value;
        }
    }
}
