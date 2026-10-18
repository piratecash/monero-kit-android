package com.m2049r.xmrwallet.model;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

final class WalletNativeCallGate {
    private int activeReads;
    private boolean closed;
    private boolean closing;

    long read(LongSupplier action) {
        synchronized (this) {
            if (closed || closing) return 0;
            activeReads++;
        }
        try {
            return action.getAsLong();
        } finally {
            synchronized (this) {
                activeReads--;
                if (activeReads == 0) notifyAll();
            }
        }
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
