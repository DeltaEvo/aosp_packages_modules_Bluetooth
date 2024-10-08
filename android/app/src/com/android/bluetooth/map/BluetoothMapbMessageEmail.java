/*
 * Copyright (C) 2013 Samsung System LSI
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
package com.android.bluetooth.map;

import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProtoEnums;
import android.util.Log;

import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.content_profiles.ContentProfileErrorReportUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// Next tag value for ContentProfileErrorReportUtils.report(): 1
public class BluetoothMapbMessageEmail extends BluetoothMapbMessage {

    private String mEmailBody = null;

    public void setEmailBody(String emailBody) {
        this.mEmailBody = emailBody;
        this.mCharset = "UTF-8";
        this.mEncoding = "8bit";
    }

    public String getEmailBody() {
        return mEmailBody;
    }

    @Override
    public void parseMsgPart(String msgPart) {
        if (mEmailBody == null) {
            mEmailBody = msgPart;
        } else {
            mEmailBody = mEmailBody + msgPart;
        }
    }

    /**
     * Set initial values before parsing - will be called is a message body is found during parsing.
     */
    @Override
    public void parseMsgInit() {
        // Not used for e-mail
    }

    @Override
    public byte[] encode() {
        List<byte[]> bodyFragments = new ArrayList<>();

        /* Store the messages in an ArrayList to be able to handle the different message types in
        a generic way.
        * We use byte[] since we need to extract the length in bytes. */
        if (mEmailBody != null) {
            String tmpBody =
                    mEmailBody.replaceAll(
                            "END:MSG",
                            "/END\\:MSG"); // Replace any occurrences of END:MSG with \END:MSG
            bodyFragments.add(tmpBody.getBytes(StandardCharsets.UTF_8));
        } else {
            Log.e(TAG, "Email has no body - this should not be possible");
            ContentProfileErrorReportUtils.report(
                    BluetoothProfile.MAP,
                    BluetoothProtoEnums.BLUETOOTH_MAP_BMESSAGE_EMAIL,
                    BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__LOG_ERROR,
                    0);
            bodyFragments.add(new byte[0]); // An empty message - this should not be possible
        }
        return encodeGeneric(bodyFragments);
    }
}
