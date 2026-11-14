package com.m2049r.xmrwallet.model;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class WalletNativeCallGate {
    private int activeReads;
    private boolean closed;
    private boolean closing;

    long read(LongSupplier action) {
        if (!enter()) return 0;
        try {
            return action.getAsLong();
        } finally {
            exit();
        }
    }

    <T> T read(Supplier<T> action, T rejected) {
        if (!enter()) return rejected;
        try {
            return action.get();
        } finally {
            exit();
        }
    }

    private synchronized boolean enter() {
        if (closed || closing) return false;
        activeReads++;
        return true;
    }

    private synchronized void exit() {
        activeReads--;
        if (activeReads == 0) notifyAll();
    }

    boolean close(BooleanSupplier action) {
        boolean interrupted;
        synchronized (this) {
            if (closed) return true;
            if (closing) return false;
            closing = true;
            interrupted = awaitActiveReads();
        }

        boolean result = false;
        try {
            result = action.getAsBoolean();
            return result;
        } finally {
            completeClose(result);
            restoreInterrupt(interrupted);
        }
    }

    private boolean awaitActiveReads() {
        boolean interrupted = false;
        while (activeReads > 0) {
            try {
                wait();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private synchronized void completeClose(boolean result) {
        closed = result;
        closing = false;
    }

    private static void restoreInterrupt(boolean interrupted) {
        if (interrupted) Thread.currentThread().interrupt();
    }
}
