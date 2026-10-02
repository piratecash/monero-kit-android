package com.piratecash.monero.signer;

import androidx.annotation.NonNull;
import androidx.annotation.Keep;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Keep
final class ExternalSignerNativeBridge {
    private static final int PACKET_SIZE = 64;
    private static final long DEFAULT_RELEASE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final AtomicLong NEXT_GENERATION = new AtomicLong();

    private static Entry current;
    private static volatile long releaseTimeoutNanos = DEFAULT_RELEASE_TIMEOUT_NANOS;

    private ExternalSignerNativeBridge() {
    }

    @NonNull
    static synchronized ExternalSignerRegistration register(
            @NonNull ExternalSignerChannel channel,
            @NonNull byte[] sessionId
    ) throws ExternalSignerException {
        if (sessionId.length == 0) {
            throw failure(
                    ExternalSignerError.CHANNEL_FAILURE,
                    "Trezor session ID is required"
            );
        }
        if (current != null) {
            throw failure(
                    ExternalSignerError.CHANNEL_ALREADY_REGISTERED,
                    "An external signer channel is already registered"
            );
        }

        long generation = NEXT_GENERATION.incrementAndGet();
        current = new Entry(generation, channel, sessionId);
        return new Registration(generation);
    }

    static synchronized long currentGeneration() throws ExternalSignerException {
        Entry entry = requireCurrent();
        if (entry.closing) {
            throw failure(
                    ExternalSignerError.CANCELLED,
                    "External signer channel is closing"
            );
        }
        return entry.generation;
    }

    @NonNull
    static synchronized byte[] currentSessionId(long generation)
            throws ExternalSignerException {
        Entry entry = requireGeneration(generation);
        if (entry.closing) {
            throw failure(
                    ExternalSignerError.CANCELLED,
                    "External signer channel is closing"
            );
        }
        return entry.sessionId.clone();
    }

    static void writePacket(long generation, @NonNull byte[] packet)
            throws ExternalSignerException {
        if (packet.length != PACKET_SIZE) {
            throw failure(
                    ExternalSignerError.INVALID_PACKET,
                    "External signer packet must contain exactly 64 bytes"
            );
        }

        Entry entry = acquire(generation);
        try {
            entry.channel.writePacket(packet);
            requireOpen(entry);
        } catch (ExternalSignerException error) {
            throw error;
        } catch (Exception error) {
            throw failure(
                    ExternalSignerError.CHANNEL_FAILURE,
                    "External signer write failed",
                    error
            );
        } finally {
            releaseCall(entry);
        }
    }

    @NonNull
    static byte[] readPacket(long generation, long deadlineNanos) throws ExternalSignerException {
        Entry entry = acquire(generation);
        final byte[] packet;
        try {
            packet = entry.channel.readPacket(deadlineNanos);
            requireOpen(entry);
        } catch (ExternalSignerException error) {
            throw error;
        } catch (Exception error) {
            throw failure(
                    ExternalSignerError.CHANNEL_FAILURE,
                    "External signer read failed",
                    error
            );
        } finally {
            releaseCall(entry);
        }

        if (packet.length != PACKET_SIZE) {
            throw failure(
                    ExternalSignerError.INVALID_PACKET,
                    "External signer packet must contain exactly 64 bytes"
            );
        }
        return packet;
    }

    static void cancel(long generation) throws ExternalSignerException {
        Entry entry;
        synchronized (ExternalSignerNativeBridge.class) {
            entry = requireGeneration(generation);
            entry.beginClosing();
            startCancellation(entry);
        }
        awaitCancellation(entry, deadlineFromNow());
    }

    static synchronized void signalCancellation(long generation)
            throws ExternalSignerException {
        Entry entry = requireGeneration(generation);
        entry.beginClosing();
        startCancellation(entry);
    }

    @NonNull
    private static synchronized Entry acquire(long generation)
            throws ExternalSignerException {
        Entry entry = requireCurrent();
        if (entry.generation != generation) {
            throw failure(
                    ExternalSignerError.STALE_CHANNEL,
                    "External signer channel is stale"
            );
        }
        if (entry.closing) {
            throw failure(
                    ExternalSignerError.CANCELLED,
                    "External signer channel is closing"
            );
        }
        entry.activeCalls++;
        return entry;
    }

    private static synchronized void requireOpen(@NonNull Entry entry)
            throws ExternalSignerException {
        if (current != entry || entry.closing) {
            throw failure(
                    ExternalSignerError.CANCELLED,
                    "External signer operation was cancelled"
            );
        }
    }

    private static synchronized void releaseCall(@NonNull Entry entry) {
        entry.activeCalls--;
        ExternalSignerNativeBridge.class.notifyAll();
    }

    @NonNull
    private static Entry requireGeneration(long generation) throws ExternalSignerException {
        Entry entry = requireCurrent();
        if (entry.generation != generation) {
            throw failure(
                    ExternalSignerError.STALE_CHANNEL,
                    "External signer channel is stale"
            );
        }
        return entry;
    }

    @NonNull
    private static Entry requireCurrent() throws ExternalSignerException {
        if (current == null) {
            throw failure(
                    ExternalSignerError.NO_CHANNEL,
                    "No external signer channel is registered"
            );
        }
        return current;
    }

    private static void release(long generation) throws ExternalSignerException {
        Entry entry;
        long deadlineNanos = deadlineFromNow();
        synchronized (ExternalSignerNativeBridge.class) {
            if (current == null || current.generation != generation) {
                return;
            }
            entry = current;
            entry.beginClosing();
        }

        try {
            awaitCancellation(entry, deadlineNanos);
            synchronized (ExternalSignerNativeBridge.class) {
                while (entry.activeCalls > 0) {
                    waitForTeardown(deadlineNanos);
                }
                if (current == entry) {
                    current = null;
                }
            }
        } catch (ExternalSignerException error) {
            startReaper(entry);
            throw error;
        }
    }

    private static synchronized void startReaper(@NonNull Entry entry) {
        if (entry.reaperStarted) {
            return;
        }
        entry.reaperStarted = true;
        Thread reaper = new Thread(
                () -> reap(entry),
                "MoneroExternalSignerReaper-" + entry.generation
        );
        reaper.setDaemon(true);
        reaper.start();
    }

    private static void reap(@NonNull Entry entry) {
        synchronized (ExternalSignerNativeBridge.class) {
            while (entry.activeCalls > 0
                    || entry.cancelAttempt > entry.finishedCancelAttempt) {
                try {
                    ExternalSignerNativeBridge.class.wait();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (current == entry) {
                current = null;
            }
        }
    }

    private static void awaitCancellation(
            @NonNull Entry entry,
            long deadlineNanos
    ) throws ExternalSignerException {
        synchronized (ExternalSignerNativeBridge.class) {
            long attempt = startCancellation(entry);
            while (!entry.cancelCompleted && entry.finishedCancelAttempt < attempt) {
                waitForTeardown(deadlineNanos);
            }
            if (entry.cancelCompleted) {
                return;
            }
            if (entry.cancelFailure != null) {
                throw entry.cancelFailure;
            }
            throw failure(
                    ExternalSignerError.CHANNEL_FAILURE,
                    "External signer cancellation did not complete"
            );
        }
    }

    private static long startCancellation(@NonNull Entry entry) {
        if (entry.cancelCompleted) {
            return entry.cancelAttempt;
        }
        if (entry.cancelAttempt > entry.finishedCancelAttempt) {
            return entry.cancelAttempt;
        }

        long attempt = ++entry.cancelAttempt;
        Thread worker = new Thread(
                () -> completeCancellation(entry, attempt),
                "MoneroExternalSignerCancel-" + entry.generation
        );
        worker.setDaemon(true);
        worker.start();
        return attempt;
    }

    private static void completeCancellation(@NonNull Entry entry, long attempt) {
        ExternalSignerException cancelFailure = cancelChannel(entry);
        synchronized (ExternalSignerNativeBridge.class) {
            entry.cancelFailure = cancelFailure;
            entry.cancelCompleted = cancelFailure == null;
            entry.finishedCancelAttempt = attempt;
            ExternalSignerNativeBridge.class.notifyAll();
        }
    }

    private static ExternalSignerException cancelChannel(@NonNull Entry entry) {
        try {
            entry.channel.cancel();
            return null;
        } catch (ExternalSignerException error) {
            return error;
        } catch (Exception error) {
            return failure(
                    ExternalSignerError.CHANNEL_FAILURE,
                    "External signer cancellation failed",
                    error
            );
        }
    }

    private static void waitForTeardown(long deadlineNanos) throws ExternalSignerException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw failure(
                    ExternalSignerError.CHANNEL_FAILURE,
                    "External signer channel did not stop in time",
                    new TimeoutException("External signer teardown timed out")
            );
        }
        try {
            TimeUnit.NANOSECONDS.timedWait(
                    ExternalSignerNativeBridge.class,
                    remainingNanos
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw failure(
                    ExternalSignerError.CANCELLED,
                    "Interrupted while stopping external signer channel",
                    error
            );
        }
    }

    private static long deadlineFromNow() {
        return System.nanoTime() + releaseTimeoutNanos;
    }

    static void setReleaseTimeoutNanosForTests(long timeoutNanos) {
        if (timeoutNanos <= 0) {
            throw new IllegalArgumentException("Release timeout must be positive");
        }
        releaseTimeoutNanos = timeoutNanos;
    }

    static void resetReleaseTimeoutForTests() {
        releaseTimeoutNanos = DEFAULT_RELEASE_TIMEOUT_NANOS;
    }

    @NonNull
    private static ExternalSignerException failure(
            @NonNull ExternalSignerError error,
            @NonNull String message
    ) {
        return new ExternalSignerException(error, message, null);
    }

    @NonNull
    private static ExternalSignerException failure(
            @NonNull ExternalSignerError error,
            @NonNull String message,
            @NonNull Throwable cause
    ) {
        return new ExternalSignerException(error, message, cause);
    }

    private static final class Entry {
        private final long generation;
        @NonNull
        private final ExternalSignerChannel channel;
        @NonNull
        private final byte[] sessionId;
        private int activeCalls;
        private boolean closing;
        private boolean cancelCompleted;
        private long cancelAttempt;
        private long finishedCancelAttempt;
        private ExternalSignerException cancelFailure;
        private boolean reaperStarted;

        private Entry(
                long generation,
                @NonNull ExternalSignerChannel channel,
                @NonNull byte[] sessionId
        ) {
            this.generation = generation;
            this.channel = channel;
            this.sessionId = sessionId.clone();
        }

        private void beginClosing() {
            closing = true;
            Arrays.fill(sessionId, (byte) 0);
        }
    }

    private static final class Registration implements ExternalSignerRegistration {
        private final long generation;
        private volatile boolean released;

        private Registration(long generation) {
            this.generation = generation;
        }

        @Override
        public void signalCancellation() throws ExternalSignerException {
            if (!released) {
                ExternalSignerNativeBridge.signalCancellation(generation);
            }
        }

        @Override
        public void cancel() throws ExternalSignerException {
            if (!released) {
                ExternalSignerNativeBridge.cancel(generation);
            }
        }

        @Override
        public synchronized void release() throws ExternalSignerException {
            if (released) {
                return;
            }
            ExternalSignerNativeBridge.release(generation);
            released = true;
        }
    }
}
