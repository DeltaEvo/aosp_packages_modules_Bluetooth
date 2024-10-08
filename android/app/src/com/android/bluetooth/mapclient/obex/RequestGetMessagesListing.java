/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.mapclient;

import com.android.bluetooth.ObexAppParameters;
import com.android.obex.ClientSession;
import com.android.obex.HeaderSet;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.List;

/* Get a listing of messages in directory. */
class RequestGetMessagesListing extends Request {

    private static final String TYPE = "x-bt/MAP-msg-listing";

    private MessagesListing mResponse = null;

    private boolean mNewMessage = false;

    private Date mServerTime = null;

    RequestGetMessagesListing(
            String folderName,
            int parameters,
            MessagesFilter filter,
            int subjectLength,
            int maxListCount,
            int listStartOffset) {
        if (subjectLength < 0 || subjectLength > 255) {
            throw new IllegalArgumentException("subjectLength should be [0..255]");
        }

        if (maxListCount < 0 || maxListCount > 65535) {
            throw new IllegalArgumentException("maxListCount should be [0..65535]");
        }

        if (listStartOffset < 0 || listStartOffset > 65535) {
            throw new IllegalArgumentException("listStartOffset should be [0..65535]");
        }

        mHeaderSet.setHeader(HeaderSet.TYPE, TYPE);

        if (folderName == null) {
            mHeaderSet.setHeader(HeaderSet.NAME, "");
        } else {
            mHeaderSet.setHeader(HeaderSet.NAME, folderName);
        }

        ObexAppParameters oap = new ObexAppParameters();

        if (filter != null) {
            if (filter.excludedMessageTypes != MessagesFilter.MESSAGE_TYPE_NONE) {
                oap.add(OAP_TAGID_FILTER_MESSAGE_TYPE, filter.excludedMessageTypes);
            }

            if (filter.periodBegin != null) {
                oap.add(OAP_TAGID_FILTER_PERIOD_BEGIN, filter.periodBegin);
            }

            if (filter.periodEnd != null) {
                oap.add(OAP_TAGID_FILTER_PERIOD_END, filter.periodEnd);
            }

            if (filter.readStatus != MessagesFilter.READ_STATUS_ANY) {
                oap.add(OAP_TAGID_FILTER_READ_STATUS, filter.readStatus);
            }

            if (filter.recipient != null) {
                oap.add(OAP_TAGID_FILTER_RECIPIENT, filter.recipient);
            }

            if (filter.originator != null) {
                oap.add(OAP_TAGID_FILTER_ORIGINATOR, filter.originator);
            }

            if (filter.priority != MessagesFilter.PRIORITY_ANY) {
                oap.add(OAP_TAGID_FILTER_PRIORITY, filter.priority);
            }
        }

        if (subjectLength != 0) {
            oap.add(OAP_TAGID_SUBJECT_LENGTH, (byte) subjectLength);
        }
        /* Include parameterMask only when specific values are selected,
         * to avoid IOT specific issue with no paramterMask header support.
         */
        if (parameters > 0) {
            oap.add(OAP_TAGID_PARAMETER_MASK, parameters);
        }
        // Allow GetMessageListing for maxlistcount value 0 also.
        if (maxListCount >= 0) {
            oap.add(OAP_TAGID_MAX_LIST_COUNT, (short) maxListCount);
        }

        if (listStartOffset != 0) {
            oap.add(OAP_TAGID_START_OFFSET, (short) listStartOffset);
        }

        oap.addToHeaderSet(mHeaderSet);
    }

    @Override
    protected void readResponse(InputStream stream) {
        mResponse = new MessagesListing(stream);
    }

    @Override
    protected void readResponseHeaders(HeaderSet headerset) {
        ObexAppParameters oap = ObexAppParameters.fromHeaderSet(headerset);

        mNewMessage = ((oap.getByte(OAP_TAGID_NEW_MESSAGE) & 0x01) == 1);

        if (oap.exists(OAP_TAGID_MSE_TIME)) {
            String mseTime = oap.getString(OAP_TAGID_MSE_TIME);
            if (mseTime != null) {
                mServerTime = (new ObexTime(mseTime)).getTime();
            }
        }
    }

    public List<Message> getList() {
        if (mResponse == null) {
            return null;
        }

        return mResponse.getList();
    }

    public boolean getNewMessageStatus() {
        return mNewMessage;
    }

    public Date getMseTime() {
        return mServerTime;
    }

    @Override
    public void execute(ClientSession session) throws IOException {
        executeGet(session);
    }
}
