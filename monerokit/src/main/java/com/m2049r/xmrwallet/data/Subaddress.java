/*
 * Copyright (c) 2018 m2049r
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

package com.m2049r.xmrwallet.data;

import java.util.regex.Pattern;

public class Subaddress implements Comparable<Subaddress> {
    final private int accountIndex;
    final private int addressIndex;
    final private String address;
    private final String label;
    private long amount;

    @Override
    public int compareTo(Subaddress another) { // newer is <
        final int compareAccountIndex = another.accountIndex - accountIndex;
        if (compareAccountIndex == 0)
            return another.addressIndex - addressIndex;
        return compareAccountIndex;
    }

    public String getSquashedAddress() {
        return address.substring(0, 8) + "…" + address.substring(address.length() - 8);
    }

    public static final Pattern DEFAULT_LABEL_FORMATTER = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{2}:[0-9]{2}:[0-9]{2}$");

    public String getDisplayLabel() {
        if (label.isEmpty() || (DEFAULT_LABEL_FORMATTER.matcher(label).matches()))
            return ("#" + addressIndex);
        else
            return label;
    }

    public Subaddress(int accountIndex, int addressIndex, String address, String label) {
        this.accountIndex = accountIndex;
        this.addressIndex = addressIndex;
        this.address = address;
        this.label = label;
    }

    @Override
    public String toString() {
        return "Subaddress(accountIndex=" + this.getAccountIndex() + ", addressIndex=" + this.getAddressIndex() + ", address=" + this.getAddress() + ", label=" + this.getLabel() + ", amount=" + this.getAmount() + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof Subaddress)) return false;
        Subaddress other = (Subaddress) o;
        if (!other.canEqual((Object) this)) return false;
        if (this.getAccountIndex() != other.getAccountIndex()) return false;
        if (this.getAddressIndex() != other.getAddressIndex()) return false;
        if (this.getAmount() != other.getAmount()) return false;
        Object this$address = this.getAddress();
        Object other$address = other.getAddress();
        if (this$address == null ? other$address != null : !this$address.equals(other$address)) return false;
        Object this$label = this.getLabel();
        Object other$label = other.getLabel();
        if (this$label == null ? other$label != null : !this$label.equals(other$label)) return false;
        return true;
    }

    protected boolean canEqual(Object other) {
        return other instanceof Subaddress;
    }

    @Override
    public int hashCode() {
        int PRIME = 59;
        int result = 1;
        result = result * PRIME + this.getAccountIndex();
        result = result * PRIME + this.getAddressIndex();
        long $amount = this.getAmount();
        result = result * PRIME + (int) ($amount >>> 32 ^ $amount);
        Object $address = this.getAddress();
        result = result * PRIME + ($address == null ? 43 : $address.hashCode());
        Object $label = this.getLabel();
        result = result * PRIME + ($label == null ? 43 : $label.hashCode());
        return result;
    }

    public int getAccountIndex() {
        return this.accountIndex;
    }

    public int getAddressIndex() {
        return this.addressIndex;
    }

    public String getAddress() {
        return this.address;
    }

    public String getLabel() {
        return this.label;
    }

    public long getAmount() {
        return this.amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }
}
