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

package com.example.android.sunshine.wearable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {

    private static final String TAG = MyWatchFace.class.getSimpleName();

    private SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, MMM dd yyyy");

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a minute since seconds are
     * not displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(60);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    private static final String GET_WEATHER_DATA = "/get_weather_data";
    private static final long CONNECTION_TIME_OUT_MS = 100;
    private String nodeId;
    private static final String MESSAGE = "get_weather_data";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTimePaint;
        boolean mAmbient;
        Calendar mCalendar;
        Paint mDatePaint;
        Paint mWeatherPaint;
        Paint mHighTempPaint;
        Paint mLowTempPaint;
        Paint mLinePaint;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;

        // To call the Data Layer API
        GoogleApiClient mGoogleApiClient;

        int mIconID = -1;
        String mHighTemp;
        String mLowTemp;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

            mGoogleApiClient.connect();

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(getColor(R.color.background));

            mTimePaint = new Paint();
            mTimePaint = createTextPaint(getColor(R.color.digital_text));

            mCalendar = Calendar.getInstance();

            mDatePaint = createTextPaint(getColor(R.color.grey));
            mWeatherPaint = new Paint();
            mHighTempPaint = createTextPaint(getColor(R.color.digital_text));
            mLowTempPaint = createTextPaint(getColor(R.color.grey));
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float normalTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float smallTextSize = resources.getDimension(isRound
                    ? R.dimen.small_text_size_round : R.dimen.small_text_size);
            float mediumTextSize = resources.getDimension(isRound
                    ? R.dimen.medium_text_size_round : R.dimen.medium_text_size);

            mTimePaint.setTextSize(normalTextSize);
            mDatePaint.setTextSize(smallTextSize);
            mHighTempPaint.setTextSize(mediumTextSize);
            mLowTempPaint.setTextSize(mediumTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        public float centerHorizontally(Rect bounds, Paint paint, String text) {
            float startingXPoint = bounds.centerX() - paint.measureText(text) / 2;
            return startingXPoint;
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String timeText = String.format("%d:%02d", mCalendar.get(Calendar.HOUR), mCalendar.get(Calendar.MINUTE));
            canvas.drawText(timeText, centerHorizontally(bounds, mTimePaint, timeText), mYOffset, mTimePaint);

            String dateText = dateFormat.format(mCalendar.getTime());
            canvas.drawText(dateText, centerHorizontally(bounds, mDatePaint, dateText), mYOffset + 45, mDatePaint);

            mLinePaint = new Paint();
            mLinePaint.setColor(getColor(R.color.grey));
            canvas.drawLine(bounds.centerX() - 30, bounds.centerY(), bounds.centerX() + 30, bounds.centerY(), mLinePaint);

            int drawableRes = R.drawable.ic_clear;
            // Draw the weather icon
            if (mIconID != -1) {
                if (mIconID >= 200 && mIconID <= 232) {
                    drawableRes = R.drawable.ic_storm;
                } else if (mIconID >= 300 && mIconID <= 321) {
                    drawableRes = R.drawable.ic_light_rain;
                } else if (mIconID >= 500 && mIconID <= 504) {
                    drawableRes = R.drawable.ic_rain;
                } else if (mIconID == 511) {
                    drawableRes = R.drawable.ic_snow;
                } else if (mIconID >= 520 && mIconID <= 531) {
                    drawableRes = R.drawable.ic_rain;
                } else if (mIconID >= 600 && mIconID <= 622) {
                    drawableRes = R.drawable.ic_snow;
                } else if (mIconID >= 701 && mIconID <= 761) {
                    drawableRes = R.drawable.ic_fog;
                } else if (mIconID == 761 || mIconID == 781) {
                    drawableRes = R.drawable.ic_storm;
                } else if (mIconID == 800) {
                    drawableRes = R.drawable.ic_clear;
                } else if (mIconID == 801) {
                    drawableRes = R.drawable.ic_light_clouds;
                } else if (mIconID >= 802 && mIconID <= 804) {
                    drawableRes = R.drawable.ic_cloudy;
                }

            }

            // Draw the high and low temp
            if (mHighTemp != null && mLowTemp != null) {

                String highTempDisplay = mHighTemp + "°";
                canvas.drawText(highTempDisplay, centerHorizontally(bounds, mHighTempPaint, highTempDisplay) + 15, bounds.centerY() + 75, mHighTempPaint);

                String lowTempDisplay = mLowTemp + "°";
                canvas.drawText(lowTempDisplay, bounds.centerX() + mHighTempPaint.measureText(highTempDisplay) / 2 + 30, bounds.centerY() + 75, mLowTempPaint);
                Log.d(TAG, "Weather High: " + mHighTemp + " Weather Low: " + mLowTemp);

                Bitmap bitmap = BitmapFactory.decodeResource(getResources(), drawableRes);
                canvas.drawBitmap(bitmap, centerHorizontally(bounds, mHighTempPaint, highTempDisplay) - 75, bounds.centerY() + 30, mWeatherPaint);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle connectionHint) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
//            syncWeather();
            Log.d(TAG, "onConnected: " + connectionHint);
            // Now you can use the Data Layer API
        }

//        private void syncWeather() {
//            retrieveDeviceNode();
//            if (nodeId != null) {
//                new Thread(new Runnable() {
//                    @Override
//                    public void run() {
//                        mGoogleApiClient.blockingConnect(CONNECTION_TIME_OUT_MS, TimeUnit.MILLISECONDS);
//                        Wearable.MessageApi.sendMessage(mGoogleApiClient, nodeId, MESSAGE, null);
//                        mGoogleApiClient.disconnect();
//                    }
//                }).start();
//            }
//        }

//        private void retrieveDeviceNode() {
//            new Thread(new Runnable() {
//                @Override
//                public void run() {
//                    mGoogleApiClient.blockingConnect(CONNECTION_TIME_OUT_MS, TimeUnit.MILLISECONDS);
//                    NodeApi.GetConnectedNodesResult result =
//                            Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).await();
//                    List<Node> nodes = result.getNodes();
//                    if (nodes.size() > 0) {
//                        nodeId = nodes.get(0).getId();
//                    }
//                    mGoogleApiClient.disconnect();
//                }
//            }).start();
//        }

        @Override
        public void onConnectionSuspended(int cause) {
            Log.d(TAG, "onConnectionSuspended: " + cause);
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult result) {
            Log.d(TAG, "onConnectionFailed: " + result);
            if (result.getErrorCode() == ConnectionResult.API_UNAVAILABLE) {
                Toast.makeText(getApplicationContext(), "Connection Failed", Toast.LENGTH_SHORT).show();
                // The Wearable API is unavailable
            }
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            // Get the weather data sent over from the mobile device
            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataMap dataMap = DataMapItem.fromDataItem(dataEvent.getDataItem()).getDataMap();
                    String path = dataEvent.getDataItem().getUri().getPath();
                    if(path.equals("/weather-data")){
                        mIconID = dataMap.getInt("icon-id");
                        mHighTemp = dataMap.getString("high-temp");
                        mLowTemp = dataMap.getString("low-temp");
                        Log.d(TAG, "onDataChanged: " + mIconID + " " + mHighTemp + " " + mLowTemp);
                        // Refresh the screen
                        invalidate();
                    }
                }

            }

        }
    }
}
