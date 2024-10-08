/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.bluetooth.avrcpcontroller;

import android.annotation.SuppressLint;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents the return value of a BIP GetImageProperties request, giving a detailed description of
 * an image and its available descriptors before download.
 *
 * <p>Format is as described by version 1.2.1 of the Basic Image Profile Specification. The
 * specification describes three types of metadata that can arrive with an image -- native, variant
 * and attachment. Native describes which native formats a particular image is available in. Variant
 * describes which other types of encodings/sizes can be created from the native image using various
 * transformations. Attachments describes other items that can be downloaded that are associated
 * with the image (text, sounds, etc.)
 *
 * <p>The specification requires that 1. The fixed version string of "1.0" is used 2. There is an
 * image handle 3. The "imaging thumbnail format" is included. This is defined for BIP in section
 * 4.4.3 (160x120 JPEG) and redefined for AVRCP in section 5.14.2.2.1 I (200x200 JPEG). It can be
 * either a native or variant format.
 *
 * <p>Example: <image-properties version="1.0" handle="123456789"> <native encoding="JPEG"
 * pixel="1280*1024" size="1048576"/> <variant encoding="JPEG" pixel="640*480" /> <variant
 * encoding="JPEG" pixel="160*120" /> <variant encoding="GIF" pixel="80*60-640*480"
 * transformation="stretch fill crop"/> <attachment content-type="text/plain" name="ABCD1234.txt"
 * size="5120"/> <attachment content-type="audio/basic" name="ABCD1234.wav" size="102400"/>
 * </image-properties>
 */
public class BipImageProperties {
    private static final String TAG = "avrcpcontroller.BipImageProperties";
    private static final String sVersion = "1.0";

    /** A Builder for a BipImageProperties object */
    public static class Builder {
        private BipImageProperties mProperties = new BipImageProperties();

        /**
         * Set the image handle field for the object you're building
         *
         * @param handle The image handle you want to add to the object
         * @return The builder object to keep building on top of
         */
        public Builder setImageHandle(String handle) {
            mProperties.mImageHandle = handle;
            return this;
        }

        /**
         * Set the FriendlyName field for the object you're building
         *
         * @param friendlyName The friendly name you want to add to the object
         * @return The builder object to keep building on top of
         */
        public Builder setFriendlyName(String friendlyName) {
            mProperties.mFriendlyName = friendlyName;
            return this;
        }

        /**
         * Add a native format for the object you're building
         *
         * @param format The format you want to add to the object
         * @return The builder object to keep building on top of
         */
        public Builder addNativeFormat(BipImageFormat format) {
            mProperties.addNativeFormat(format);
            return this;
        }

        /**
         * Add a variant format for the object you're building
         *
         * @param format The format you want to add to the object
         * @return The builder object to keep building on top of
         */
        public Builder addVariantFormat(BipImageFormat format) {
            mProperties.addVariantFormat(format);
            return this;
        }

        /**
         * Add an attachment entry for the object you're building
         *
         * @param format The format you want to add to the object
         * @return The builder object to keep building on top of
         */
        public Builder addAttachment(BipAttachmentFormat format) {
            mProperties.addAttachment(format);
            return this;
        }

        /**
         * Build the object
         *
         * @return A BipImageProperties object
         */
        public BipImageProperties build() {
            return mProperties;
        }
    }

    /** The image handle associated with this set of properties. */
    private String mImageHandle = null;

    /** The version of the properties object, used to encode and decode. */
    private String mVersion = null;

    /**
     * An optional friendly name for the associated image. The specification suggests the file name.
     */
    private String mFriendlyName = null;

    /** Whether we have the required imaging thumbnail format */
    private boolean mHasThumbnailFormat = false;

    /** The various sets of available formats. */
    private List<BipImageFormat> mNativeFormats;

    private List<BipImageFormat> mVariantFormats;
    private List<BipAttachmentFormat> mAttachments;

    private BipImageProperties() {
        mVersion = sVersion;
        mNativeFormats = new ArrayList<BipImageFormat>();
        mVariantFormats = new ArrayList<BipImageFormat>();
        mAttachments = new ArrayList<BipAttachmentFormat>();
    }

    public BipImageProperties(InputStream inputStream) {
        mNativeFormats = new ArrayList<BipImageFormat>();
        mVariantFormats = new ArrayList<BipImageFormat>();
        mAttachments = new ArrayList<BipAttachmentFormat>();
        parse(inputStream);
    }

    private void parse(InputStream inputStream) {
        try {
            XmlPullParser xpp = XmlPullParserFactory.newInstance().newPullParser();
            xpp.setInput(inputStream, "utf-8");
            int event = xpp.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                switch (event) {
                    case XmlPullParser.START_TAG:
                        String tag = xpp.getName();
                        if (tag.equals("image-properties")) {
                            mVersion = xpp.getAttributeValue(null, "version");
                            mImageHandle = xpp.getAttributeValue(null, "handle");
                            mFriendlyName = xpp.getAttributeValue(null, "friendly-name");
                        } else if (tag.equals("native")) {
                            String encoding = xpp.getAttributeValue(null, "encoding");
                            String pixel = xpp.getAttributeValue(null, "pixel");
                            String size = xpp.getAttributeValue(null, "size");
                            addNativeFormat(BipImageFormat.parseNative(encoding, pixel, size));
                        } else if (tag.equals("variant")) {
                            String encoding = xpp.getAttributeValue(null, "encoding");
                            String pixel = xpp.getAttributeValue(null, "pixel");
                            String maxSize = xpp.getAttributeValue(null, "maxsize");
                            String trans = xpp.getAttributeValue(null, "transformation");
                            addVariantFormat(
                                    BipImageFormat.parseVariant(encoding, pixel, maxSize, trans));
                        } else if (tag.equals("attachment")) {
                            String contentType = xpp.getAttributeValue(null, "content-type");
                            String name = xpp.getAttributeValue(null, "name");
                            String charset = xpp.getAttributeValue(null, "charset");
                            String size = xpp.getAttributeValue(null, "size");
                            String created = xpp.getAttributeValue(null, "created");
                            String modified = xpp.getAttributeValue(null, "modified");
                            addAttachment(
                                    new BipAttachmentFormat(
                                            contentType, charset, name, size, created, modified));
                        } else {
                            Log.w(TAG, "Unrecognized tag in x-bt/img-properties object: " + tag);
                        }
                        break;
                    case XmlPullParser.END_TAG:
                        break;
                }
                event = xpp.next();
            }
            return;
        } catch (XmlPullParserException e) {
            Log.e(TAG, "XML parser error when parsing XML", e);
        } catch (IOException e) {
            Log.e(TAG, "I/O error when parsing XML", e);
        }
        throw new ParseException("Failed to parse image-properties from stream");
    }

    public String getImageHandle() {
        return mImageHandle;
    }

    public String getVersion() {
        return mVersion;
    }

    public String getFriendlyName() {
        return mFriendlyName;
    }

    public List<BipImageFormat> getNativeFormats() {
        return mNativeFormats;
    }

    public List<BipImageFormat> getVariantFormats() {
        return mVariantFormats;
    }

    public List<BipAttachmentFormat> getAttachments() {
        return mAttachments;
    }

    private void addNativeFormat(BipImageFormat format) {
        Objects.requireNonNull(format);
        if (format.getType() != BipImageFormat.FORMAT_NATIVE) {
            throw new IllegalArgumentException(
                    "Format type '"
                            + format.getType()
                            + "' but expected '"
                            + BipImageFormat.FORMAT_NATIVE
                            + "'");
        }
        mNativeFormats.add(format);

        if (!mHasThumbnailFormat && isThumbnailFormat(format)) {
            mHasThumbnailFormat = true;
        }
    }

    private void addVariantFormat(BipImageFormat format) {
        Objects.requireNonNull(format);
        if (format.getType() != BipImageFormat.FORMAT_VARIANT) {
            throw new IllegalArgumentException(
                    "Format type '"
                            + format.getType()
                            + "' but expected '"
                            + BipImageFormat.FORMAT_VARIANT
                            + "'");
        }
        mVariantFormats.add(format);

        if (!mHasThumbnailFormat && isThumbnailFormat(format)) {
            mHasThumbnailFormat = true;
        }
    }

    private boolean isThumbnailFormat(BipImageFormat format) {
        if (format == null) return false;

        BipEncoding encoding = format.getEncoding();
        if (encoding == null || encoding.getType() != BipEncoding.JPEG) return false;

        BipPixel pixel = format.getPixel();
        if (pixel == null) return false;
        switch (pixel.getType()) {
            case BipPixel.TYPE_FIXED:
                return pixel.getMaxWidth() == 200 && pixel.getMaxHeight() == 200;
            case BipPixel.TYPE_RESIZE_MODIFIED_ASPECT_RATIO:
                return pixel.getMaxWidth() >= 200 && pixel.getMaxHeight() >= 200;
            case BipPixel.TYPE_RESIZE_FIXED_ASPECT_RATIO:
                return pixel.getMaxWidth() == pixel.getMaxHeight() && pixel.getMaxWidth() >= 200;
        }
        return false;
    }

    private void addAttachment(BipAttachmentFormat format) {
        Objects.requireNonNull(format);
        mAttachments.add(format);
    }

    @Override
    @SuppressLint("ToStringReturnsNull") // Since this is used for encoding to xml
    public String toString() {
        StringWriter writer = new StringWriter();
        XmlSerializer xmlMsgElement = Xml.newSerializer();
        try {
            xmlMsgElement.setOutput(writer);
            xmlMsgElement.startDocument("UTF-8", true);
            xmlMsgElement.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            xmlMsgElement.startTag(null, "image-properties");
            if (mVersion != null) xmlMsgElement.attribute(null, "version", mVersion);
            if (mImageHandle != null) xmlMsgElement.attribute(null, "handle", mImageHandle);
            if (mFriendlyName != null) {
                xmlMsgElement.attribute(null, "friendly-name", mFriendlyName);
            }

            for (BipImageFormat format : mNativeFormats) {
                BipEncoding encoding = format.getEncoding();
                BipPixel pixel = format.getPixel();
                int size = format.getSize();
                if (encoding == null || pixel == null) {
                    Log.e(TAG, "Native format " + format.toString() + " is invalid.");
                    continue;
                }
                xmlMsgElement.startTag(null, "native");
                xmlMsgElement.attribute(null, "encoding", encoding.toString());
                xmlMsgElement.attribute(null, "pixel", pixel.toString());
                if (size >= 0) {
                    xmlMsgElement.attribute(null, "size", Integer.toString(size));
                }
                xmlMsgElement.endTag(null, "native");
            }

            for (BipImageFormat format : mVariantFormats) {
                BipEncoding encoding = format.getEncoding();
                BipPixel pixel = format.getPixel();
                int maxSize = format.getMaxSize();
                BipTransformation trans = format.getTransformation();
                if (encoding == null || pixel == null) {
                    Log.e(TAG, "Variant format " + format.toString() + " is invalid.");
                    continue;
                }
                xmlMsgElement.startTag(null, "variant");
                xmlMsgElement.attribute(null, "encoding", encoding.toString());
                xmlMsgElement.attribute(null, "pixel", pixel.toString());
                if (maxSize >= 0) {
                    xmlMsgElement.attribute(null, "maxsize", Integer.toString(maxSize));
                }
                if (trans != null && trans.supportsAny()) {
                    xmlMsgElement.attribute(null, "transformation", trans.toString());
                }
                xmlMsgElement.endTag(null, "variant");
            }

            for (BipAttachmentFormat format : mAttachments) {
                String contentType = format.getContentType();
                String charset = format.getCharset();
                String name = format.getName();
                int size = format.getSize();
                BipDateTime created = format.getCreatedDate();
                BipDateTime modified = format.getModifiedDate();
                if (contentType == null || name == null) {
                    Log.e(TAG, "Attachment format " + format.toString() + " is invalid.");
                    continue;
                }
                xmlMsgElement.startTag(null, "attachment");
                xmlMsgElement.attribute(null, "content-type", contentType.toString());
                if (charset != null) {
                    xmlMsgElement.attribute(null, "charset", charset.toString());
                }
                xmlMsgElement.attribute(null, "name", name.toString());
                if (size >= 0) {
                    xmlMsgElement.attribute(null, "size", Integer.toString(size));
                }
                if (created != null) {
                    xmlMsgElement.attribute(null, "created", created.toString());
                }
                if (modified != null) {
                    xmlMsgElement.attribute(null, "modified", modified.toString());
                }
                xmlMsgElement.endTag(null, "attachment");
            }

            xmlMsgElement.endTag(null, "image-properties");
            xmlMsgElement.endDocument();
            return writer.toString();
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to serialize ImageProperties", e);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Failed to serialize ImageProperties", e);
        } catch (IOException e) {
            Log.e(TAG, "Failed to serialize ImageProperties", e);
        }
        return null;
    }

    /**
     * Serialize this object into a byte array
     *
     * <p>Objects that are not valid will fail to serialize and return null.
     *
     * @return Byte array representing this object, ready to send over OBEX, or null on error.
     */
    public byte[] serialize() {
        if (!isValid()) return null;
        String s = toString();
        return s != null ? s.getBytes(StandardCharsets.UTF_8) : null;
    }

    /**
     * Determine if the contents of this BipImageProperties object are valid and meet the
     * specification requirements: 1. Include the fixed 1.0 version 2. Include an image handle 3.
     * Have the thumbnail format as either the native or variant
     *
     * @return True if our contents are valid, false otherwise
     */
    public boolean isValid() {
        return sVersion.equals(mVersion) && mImageHandle != null && mHasThumbnailFormat;
    }
}
