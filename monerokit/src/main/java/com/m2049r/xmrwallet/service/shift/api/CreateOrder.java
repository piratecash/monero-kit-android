/*
 * Copyright (c) 2017-2021 m2049r et al.
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

package com.m2049r.xmrwallet.service.shift.api;

import java.util.Date;

public interface CreateOrder {
    String getTag();

    String getBtcCurrency();

    double getBtcAmount();

    String getBtcAddress();

    String getQuoteId();

    String getOrderId();

    double getXmrAmount();

    String getXmrAddress();

    Date getCreatedAt();

    Date getExpiresAt();

    String getQueryOrderId();

    ShiftType getType();
}
