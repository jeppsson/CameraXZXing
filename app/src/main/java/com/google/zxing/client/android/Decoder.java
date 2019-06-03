/*
 * Copyright (C) 2010 ZXing authors
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

package com.google.zxing.client.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import androidx.preference.PreferenceManager;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final public class Decoder {

    private static final String TAG = Decoder.class.getSimpleName();

    private static final int MIN_FRAME_WIDTH = 240;
    private static final int MIN_FRAME_HEIGHT = 240;
    private static final int MAX_FRAME_WIDTH = 675; // = 5/8 * 1080
    private static final int MAX_FRAME_HEIGHT = 1200; // = 5/8 * 1920

    private final OnResultListener onResultListener;
    private final MultiFormatReader multiFormatReader;
    private final Point screenResolution = new Point();

    private Rect framingRect;
    private Rect framingRectInPreview;

    public interface OnResultListener {
        void onResult(Result result);
    }

    public Decoder(Context context, OnResultListener onResultListener) {
        this.onResultListener = onResultListener;

        // Get screen resolution
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = manager.getDefaultDisplay();
        display.getSize(screenResolution);

        // Get decode hints
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, getDecodeHintType(context));

        multiFormatReader = new MultiFormatReader();
        multiFormatReader.setHints(hints);
    }

    /**
     * Decode the data within the viewfinder rectangle, and time how long it took. For efficiency,
     * reuse the same reader objects from one decode to the next.
     *
     * @param data   The YUV preview frame.
     * @param width  The width of the preview frame.
     * @param height The height of the preview frame.
     */
    public void decode(byte[] data, int width, int height) {
        long start = System.nanoTime();
        Result rawResult = null;
        PlanarYUVLuminanceSource source = buildLuminanceSource(data, width, height);
        if (source != null) {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            try {
                rawResult = multiFormatReader.decodeWithState(bitmap);
            } catch (ReaderException ignored) {
            } finally {
                multiFormatReader.reset();
            }
        }

        if (rawResult != null) {
            // Don't log the barcode contents for security.
            long end = System.nanoTime();
            Log.d(TAG, "Found barcode in " + TimeUnit.NANOSECONDS.toMillis(end - start) + " ms");
            onResultListener.onResult(rawResult);
        }
    }

    /**
     * Calculates the framing rect which the UI should draw to show the user where to place the
     * barcode. This target helps with alignment as well as forces the user to hold the device
     * far enough away to ensure the image will be in focus.
     *
     * @return The rectangle to draw on screen in window coordinates.
     */
    public synchronized Rect getFramingRect() {
        if (framingRect == null) {
            int width = findDesiredDimensionInRange(screenResolution.x, MIN_FRAME_WIDTH, MAX_FRAME_WIDTH);
            int height = findDesiredDimensionInRange(screenResolution.y, MIN_FRAME_HEIGHT, MAX_FRAME_HEIGHT);

            int leftOffset = (screenResolution.x - width) / 2;
            int topOffset = (screenResolution.y - height) / 2;
            framingRect = new Rect(leftOffset, topOffset, leftOffset + width, topOffset + height);
            Log.d(TAG, "Calculated framing rect: " + framingRect);
        }

        return framingRect;
    }

    private static int findDesiredDimensionInRange(int resolution, int hardMin, int hardMax) {
        int dim = 5 * resolution / 8; // Target 5/8 of each dimension
        if (dim < hardMin) {
            return hardMin;
        }
        if (dim > hardMax) {
            return hardMax;
        }
        return dim;
    }

    /**
     * Like {@link #getFramingRect} but coordinates are in terms of the preview frame,
     * not UI / screen.
     *
     * @param width  The width of the image.
     * @param height The height of the image.
     * @return {@link Rect} expressing barcode scan area in terms of the preview size
     */
    private synchronized Rect getFramingRectInPreview(int width, int height) {
        if (framingRectInPreview == null) {
            Rect framingRect = getFramingRect();
            if (framingRect == null) {
                return null;
            }
            Rect rect = new Rect(framingRect);
            Point cameraResolution = new Point(width, height);
            rect.left = rect.left * cameraResolution.x / screenResolution.x;
            rect.right = rect.right * cameraResolution.x / screenResolution.x;
            rect.top = rect.top * cameraResolution.y / screenResolution.y;
            rect.bottom = rect.bottom * cameraResolution.y / screenResolution.y;
            framingRectInPreview = rect;
        }
        return framingRectInPreview;
    }

    /**
     * A factory method to build the appropriate LuminanceSource object based on the format
     * of the preview buffers, as described by Camera.Parameters.
     *
     * @param data   A preview frame.
     * @param width  The width of the image.
     * @param height The height of the image.
     * @return A PlanarYUVLuminanceSource instance.
     */
    private PlanarYUVLuminanceSource buildLuminanceSource(byte[] data, int width, int height) {
        Rect rect = getFramingRectInPreview(width, height);
        if (rect == null) {
            return null;
        }

        return new PlanarYUVLuminanceSource(data, width, height, rect.left, rect.top,
                rect.width(), rect.height(), false);
    }

    private static Collection<BarcodeFormat> getDecodeHintType(Context context) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        Collection<BarcodeFormat> decodeFormats = EnumSet.noneOf(BarcodeFormat.class);

        if (sharedPreferences.getBoolean("AZTEC", false)) {
            decodeFormats.add(BarcodeFormat.AZTEC);
        }
        if (sharedPreferences.getBoolean("CODABAR", false)) {
            decodeFormats.add(BarcodeFormat.CODABAR);
        }
        if (sharedPreferences.getBoolean("CODE_39", false)) {
            decodeFormats.add(BarcodeFormat.CODE_39);
        }
        if (sharedPreferences.getBoolean("CODE_93", false)) {
            decodeFormats.add(BarcodeFormat.CODE_93);
        }
        if (sharedPreferences.getBoolean("CODE_128", false)) {
            decodeFormats.add(BarcodeFormat.CODE_128);
        }
        if (sharedPreferences.getBoolean("DATA_MATRIX", false)) {
            decodeFormats.add(BarcodeFormat.DATA_MATRIX);
        }
        if (sharedPreferences.getBoolean("EAN_8", false)) {
            decodeFormats.add(BarcodeFormat.EAN_8);
        }
        if (sharedPreferences.getBoolean("EAN_13", false)) {
            decodeFormats.add(BarcodeFormat.EAN_13);
        }
        if (sharedPreferences.getBoolean("ITF", false)) {
            decodeFormats.add(BarcodeFormat.ITF);
        }
        if (sharedPreferences.getBoolean("MAXICODE", false)) {
            decodeFormats.add(BarcodeFormat.MAXICODE);
        }
        if (sharedPreferences.getBoolean("PDF_417", false)) {
            decodeFormats.add(BarcodeFormat.PDF_417);
        }
        if (sharedPreferences.getBoolean("QR_CODE", false)) {
            decodeFormats.add(BarcodeFormat.QR_CODE);
        }
        if (sharedPreferences.getBoolean("RSS_14", false)) {
            decodeFormats.add(BarcodeFormat.RSS_14);
        }
        if (sharedPreferences.getBoolean("RSS_EXPANDED", false)) {
            decodeFormats.add(BarcodeFormat.RSS_EXPANDED);
        }
        if (sharedPreferences.getBoolean("UPC_A", false)) {
            decodeFormats.add(BarcodeFormat.UPC_A);
        }
        if (sharedPreferences.getBoolean("UPC_E", false)) {
            decodeFormats.add(BarcodeFormat.UPC_E);
        }

        return decodeFormats;
    }
}