/*
 * Copyright (c) 2023 m2049r
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.m2049r.xmrwallet.model;

import androidx.annotation.Keep;

// this is not the CoinsInfo from the API as that is owned by the Coins object
// this is a POJO
@Keep
public final class CoinsInfo {
    private final int accountIndex;
    private final int addressIndex;
    private final long amount;
    private final long blockheight;
    private final String txHash;
    private final boolean spent;
    private final boolean frozen;
    private final long unlockTime;
    private final boolean unlocked;

    public boolean isSpendable() {
        return !spent && unlocked;
    }

    public CoinsInfo(int accountIndex, int addressIndex, long amount, long blockheight, String txHash, boolean spent, boolean frozen, long unlockTime, boolean unlocked) {
        this.accountIndex = accountIndex;
        this.addressIndex = addressIndex;
        this.amount = amount;
        this.blockheight = blockheight;
        this.txHash = txHash;
        this.spent = spent;
        this.frozen = frozen;
        this.unlockTime = unlockTime;
        this.unlocked = unlocked;
    }

    public int getAccountIndex() {
        return this.accountIndex;
    }

    public int getAddressIndex() {
        return this.addressIndex;
    }

    public long getAmount() {
        return this.amount;
    }

    public long getBlockheight() {
        return this.blockheight;
    }

    public String getTxHash() {
        return this.txHash;
    }

    public boolean isSpent() {
        return this.spent;
    }

    public boolean isFrozen() {
        return this.frozen;
    }

    public long getUnlockTime() {
        return this.unlockTime;
    }

    public boolean isUnlocked() {
        return this.unlocked;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof CoinsInfo)) return false;
        CoinsInfo other = (CoinsInfo) o;
        if (this.getAccountIndex() != other.getAccountIndex()) return false;
        if (this.getAddressIndex() != other.getAddressIndex()) return false;
        if (this.getAmount() != other.getAmount()) return false;
        if (this.getBlockheight() != other.getBlockheight()) return false;
        if (this.isSpent() != other.isSpent()) return false;
        if (this.isFrozen() != other.isFrozen()) return false;
        if (this.getUnlockTime() != other.getUnlockTime()) return false;
        if (this.isUnlocked() != other.isUnlocked()) return false;
        Object this$txHash = this.getTxHash();
        Object other$txHash = other.getTxHash();
        if (this$txHash == null ? other$txHash != null : !this$txHash.equals(other$txHash)) return false;
        return true;
    }

    @Override
    public int hashCode() {
        int PRIME = 59;
        int result = 1;
        result = result * PRIME + this.getAccountIndex();
        result = result * PRIME + this.getAddressIndex();
        long $amount = this.getAmount();
        result = result * PRIME + (int) ($amount >>> 32 ^ $amount);
        long $blockheight = this.getBlockheight();
        result = result * PRIME + (int) ($blockheight >>> 32 ^ $blockheight);
        result = result * PRIME + (this.isSpent() ? 79 : 97);
        result = result * PRIME + (this.isFrozen() ? 79 : 97);
        long $unlockTime = this.getUnlockTime();
        result = result * PRIME + (int) ($unlockTime >>> 32 ^ $unlockTime);
        result = result * PRIME + (this.isUnlocked() ? 79 : 97);
        Object $txHash = this.getTxHash();
        result = result * PRIME + ($txHash == null ? 43 : $txHash.hashCode());
        return result;
    }

    @Override
    public String toString() {
        return "CoinsInfo(accountIndex=" + this.getAccountIndex() + ", addressIndex=" + this.getAddressIndex() + ", amount=" + this.getAmount() + ", blockheight=" + this.getBlockheight() + ", txHash=" + this.getTxHash() + ", spent=" + this.isSpent() + ", frozen=" + this.isFrozen() + ", unlockTime=" + this.getUnlockTime() + ", unlocked=" + this.isUnlocked() + ")";
    }
}
