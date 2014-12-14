/*
 * Copyright (C) 2012 - 2013 jonas.oreland@gmail.com
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.runnerup.tracker;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import android.location.LocationListener;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;

import org.runnerup.R;
import org.runnerup.db.DBHelper;
import org.runnerup.export.UploadManager;
import org.runnerup.tracker.component.TrackerComponent;
import org.runnerup.tracker.component.TrackerComponentCollection;
import org.runnerup.tracker.component.TrackerGPS;
import org.runnerup.tracker.component.TrackerHRM;
import org.runnerup.tracker.component.TrackerTTS;
import org.runnerup.tracker.filter.PersistentGpsLoggerListener;
import org.runnerup.hr.HRProvider;
import org.runnerup.notification.ForegroundNotificationDisplayStrategy;
import org.runnerup.notification.NotificationState;
import org.runnerup.notification.NotificationStateManager;
import org.runnerup.notification.OngoingState;
import org.runnerup.common.util.Constants;
import org.runnerup.util.Formatter;
import org.runnerup.util.HRZones;
import org.runnerup.workout.HeadsetButtonReceiver;
import org.runnerup.workout.Scope;
import org.runnerup.workout.Workout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * GpsTracker - this class tracks Location updates
 * 
 * TODO: rename this class into ActivityTracker and factor out Gps stuff into own class
 *       that should be handled much like hrm (e.g as a sensor among others)
 * 
 * @author jonas.oreland@gmail.com
 */

@TargetApi(Build.VERSION_CODES.FROYO)
public class Tracker extends android.app.Service implements
        LocationListener, Constants {
    public static final int MAX_HR_AGE = 3000; // 3s

    private final Handler handler = new Handler();

    TrackerComponentCollection components = new TrackerComponentCollection();
    TrackerGPS trackerGPS = (TrackerGPS) components.addComponent(new TrackerGPS(this));
    TrackerHRM trackerHRM = (TrackerHRM) components.addComponent(new TrackerHRM());
    TrackerTTS trackerTTS = (TrackerTTS) components.addComponent(new TrackerTTS());

    /**
     * Work-around for http://code.google.com/p/android/issues/detail?id=23937
     */
    boolean mBug23937Checked = false;
    long mBug23937Delta = 0;

    /**
	 *
	 */
    long mLapId = 0;
    long mActivityId = 0;
    double mElapsedTimeMillis = 0;
    double mElapsedDistance = 0;
    double mHeartbeats = 0;
    double mHeartbeatMillis = 0; // since we might loose HRM connectivity...
    long mMaxHR = 0;

    final boolean mWithoutGps = false;

    TrackerState state = TrackerState.INIT;
    int mLocationType = DB.LOCATION.TYPE_START;

    /**
     * Last location given by LocationManager
     */
    Location mLastLocation = null;

    /**
     * Last location given by LocationManager when in state STARTED
     */
    Location mActivityLastLocation = null;

    DBHelper mDBHelper = null;
    SQLiteDatabase mDB = null;
    PersistentGpsLoggerListener mDBWriter = null;
    PowerManager.WakeLock mWakeLock = null;
    final List<WorkoutObserver> liveLoggers = new ArrayList<WorkoutObserver>();

    private Workout workout = null;

    private NotificationStateManager notificationStateManager;

    private NotificationState activityOngoingState;

    @Override
    public void onCreate() {
        mDBHelper = new DBHelper(this);
        mDB = mDBHelper.getWritableDatabase();
        notificationStateManager = new NotificationStateManager(
                new ForegroundNotificationDisplayStrategy(this));

        if (getAllowStartStopFromHeadsetKey()) {
            registerHeadsetListener();
        }

        wakelock(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mDB != null) {
            mDB.close();
            mDB = null;
        }

        if (mDBHelper != null) {
            mDBHelper.close();
            mDBHelper = null;
        }

        unregisterHeadsetListener();
        unRegisterWorkoutBroadcastsListener();
        reset();
    }

    public void setup() {
        switch (state) {
            case INIT:
                break;
            case INITIALIZING:
            case INITIALIZED:
                return;
            case STARTED:
            case PAUSED:
            case ERROR:
                assert(false);
            case CLEANUP:
                /**
                 * if CLEANUP is in progress, setup will continue once complete
                 */
                state = TrackerState.INITIALIZING;
                break;
        }

        wakelock(true);
        state = TrackerState.INITIALIZING;

        UploadManager u = new UploadManager(this);
        u.loadLiveLoggers(liveLoggers);
        u.close();

        TrackerComponent.ResultCode result = components.onInit(onInitCallback,
                getApplicationContext());
        if (result == TrackerComponent.ResultCode.RESULT_PENDING) {
            return;
        } else {
            onInitCallback.run(components, result);
        }
    }

    private final TrackerComponent.Callback onInitCallback = new TrackerComponent.Callback() {
        @Override
        public void run(TrackerComponent component, TrackerComponent.ResultCode resultCode) {
            if (state == TrackerState.CLEANUP) {
                /**
                 * reset was called while we were initializing
                 */
                state = TrackerState.INITIALIZED;
                reset();
                return;
            }
            if (resultCode == TrackerComponent.ResultCode.RESULT_ERROR_FATAL) {
                state = TrackerState.ERROR;
            } else {
                state = TrackerState.INITIALIZED;
            }
        }
    };

    private long getBug23937Delta() {
        return mBug23937Delta;
    }

    private long createActivity(int sport) {
        assert (state == TrackerState.INIT);
        /**
         * Create an Activity instance
         */
        ContentValues tmp = new ContentValues();
        tmp.put(DB.ACTIVITY.SPORT, sport);
        mActivityId = mDB.insert(DB.ACTIVITY.TABLE, "nullColumnHack", tmp);

        tmp.clear();
        tmp.put(DB.LOCATION.ACTIVITY, mActivityId);
        tmp.put(DB.LOCATION.LAP, 0); // always start with lap 0
        mDBWriter = new PersistentGpsLoggerListener(mDB, DB.LOCATION.TABLE, tmp);
        return mActivityId;
    }

    public void start(Workout workout_) {
        assert (state == TrackerState.INITIALIZED);

        // connect workout and tracker
        this.workout = workout_;
        workout.setTracker(this);

        /**
         * create the DB activity
         */
        createActivity(workout.getSport());

        // do bindings
        doBind();

        // Let workout do initializations
        workout.onInit(workout);

        // Let components know we're starting
        components.onStart();

        mElapsedTimeMillis = 0;
        mElapsedDistance = 0;
        mHeartbeats = 0;
        mHeartbeatMillis = 0;
        mMaxHR = 0;
        // TODO: check if mLastLocation is recent enough
        mActivityLastLocation = null;

        // New location update will be tagged with START
        setNextLocationType(DB.LOCATION.TYPE_START);

        state = TrackerState.STARTED;

        activityOngoingState = new OngoingState(new Formatter(this), workout, this);


        registerWorkoutBroadcastsListener();

        /**
         * And finally let workout know that we started
         */
        workout.onStart(Scope.WORKOUT, this.workout);
    }

    private void doBind() {
        /**
         * Let components populate bindValues
         */
        HashMap<String, Object> bindValues = new HashMap<String, Object>();
        Context ctx = getApplicationContext();
        bindValues.put(TrackerComponent.KEY_CONTEXT, ctx);
        bindValues.put(Workout.KEY_FORMATTER, new Formatter(ctx));
        bindValues.put(Workout.KEY_HRZONES, new HRZones(ctx));
        bindValues.put(Workout.KEY_MUTE, new Boolean(workout.getMute()));

        components.onBind(bindValues);

        /**
         * and then give them to workout
         */
        workout.onBind(workout, bindValues);
    }

    public void newLap(ContentValues tmp) {
        tmp.put(DB.LAP.ACTIVITY, mActivityId);
        mLapId = mDB.insert(DB.LAP.TABLE, null, tmp);
        ContentValues key = mDBWriter.getKey();
        key.put(DB.LOCATION.LAP, tmp.getAsLong(DB.LAP.LAP));
        mDBWriter.setKey(key);
    }

    public void saveLap(ContentValues tmp) {
        tmp.put(DB.LAP.ACTIVITY, mActivityId);
        String key[] = {
            Long.toString(mLapId)
        };
        mDB.update(DB.LAP.TABLE, tmp, "_id = ?", key);
    }

    public void stopOrPause() {
        switch (state) {
            case INIT:
            case ERROR:
            case INITIALIZING:
            case INITIALIZED:
            case PAUSED:
                break;
            case STARTED:
                stop();
        }
    }

    private ContentValues createActivityRow() {
        ContentValues tmp = new ContentValues();
        tmp.put(Constants.DB.ACTIVITY.DISTANCE, mElapsedDistance);
        tmp.put(Constants.DB.ACTIVITY.TIME, getTime());
        if (mHeartbeatMillis > 0) {
            long avgHR = Math.round((60 * 1000 * mHeartbeats) / mHeartbeatMillis); // BPM
            tmp.put(Constants.DB.ACTIVITY.AVG_HR, avgHR);
        }
        if (mMaxHR > 0)
            tmp.put(Constants.DB.ACTIVITY.MAX_HR, mMaxHR);
        return tmp;
    }

    public void stop() {
        setNextLocationType(DB.LOCATION.TYPE_PAUSE);
        if (mActivityLastLocation != null) {
            /**
             * This saves mLastLocation as a PAUSE location
             */
            internalOnLocationChanged(mActivityLastLocation);
        }

        ContentValues tmp = createActivityRow();
        String key[] = {
            Long.toString(mActivityId)
        };
        mDB.update(DB.ACTIVITY.TABLE, tmp, "_id = ?", key);
        state = TrackerState.PAUSED;
    }

    private void internalOnLocationChanged(Location arg0) {
        long save = mBug23937Delta;
        mBug23937Delta = 0;
        onLocationChanged(arg0);
        mBug23937Delta = save;
    }

    public boolean isPaused() {
        return state == TrackerState.PAUSED;
    }

    public void resume() {
        switch (state) {
            case INIT:
            case ERROR:
            case INITIALIZING:
            case CLEANUP:
            case INITIALIZED:
                assert (false);
                return;
            case PAUSED:
                break;
            case STARTED:
                return;
        }

        assert (state == TrackerState.PAUSED);
        // TODO: check is mLastLocation is recent enough
        mActivityLastLocation = mLastLocation;
        state = TrackerState.STARTED;
        setNextLocationType(DB.LOCATION.TYPE_RESUME);
        if (mActivityLastLocation != null) {
            /**
             * save last know location as resume location
             */
            internalOnLocationChanged(mActivityLastLocation);
        }
    }

    public void reset() {
        switch (state) {
            case INIT:
                return;
            case INITIALIZING:
                // cleanup when INITIALIZE is complete
                state = TrackerState.CLEANUP;
                return;
            case INITIALIZED:
            case ERROR:
            case PAUSED:
                break;
            case STARTED:
                assert(false);
                return;
            case CLEANUP:
                return;
        }

        wakelock(false);

        if (workout != null) {
            workout.setTracker(null);
            workout = null;
        }

        state = TrackerState.CLEANUP;
        liveLoggers.clear();
        TrackerComponent.ResultCode res = components.onEnd(onEndCallback, getApplicationContext());
        if (res != TrackerComponent.ResultCode.RESULT_PENDING)
            onEndCallback.run(components, res);
    }

    private final TrackerComponent.Callback onEndCallback = new TrackerComponent.Callback() {
        @Override
        public void run(TrackerComponent component, TrackerComponent.ResultCode resultCode) {
            if (state == TrackerState.INITIALIZING) {
                /**
                 * setup was called during cleanup
                 */
                state = TrackerState.INIT;
                setup();
                return;
            }
            if (resultCode == TrackerComponent.ResultCode.RESULT_ERROR_FATAL) {
                state = TrackerState.ERROR;
            } else {
                state = TrackerState.INIT;
            }
        }
    };

    public void completeActivity(boolean save) {
        assert (state == TrackerState.PAUSED);

        setNextLocationType(DB.LOCATION.TYPE_END);
        if (mActivityLastLocation != null) {
            mDBWriter.onLocationChanged(mActivityLastLocation);
        }

        if (save) {
            ContentValues tmp = createActivityRow();
            String key[] = {
                Long.toString(mActivityId)
            };
            mDB.update(DB.ACTIVITY.TABLE, tmp, "_id = ?", key);
            liveLog(DB.LOCATION.TYPE_END);
        } else {
            ContentValues tmp = new ContentValues();
            tmp.put("deleted", 1);
            String key[] = {
                Long.toString(mActivityId)
            };
            mDB.update(DB.ACTIVITY.TABLE, tmp, "_id = ?", key);
            liveLog(DB.LOCATION.TYPE_DISCARD);
        }
        notificationStateManager.cancelNotification();
        reset();
    }

    void setNextLocationType(int newType) {
        ContentValues key = mDBWriter.getKey();
        key.put(DB.LOCATION.TYPE, newType);
        mDBWriter.setKey(key);
        mLocationType = newType;
    }

    public double getTime() {
        return mElapsedTimeMillis / 1000;
    }

    public double getDistance() {
        return mElapsedDistance;
    }

    public Location getLastKnownLocation() {
        return mLastLocation;
    }

    public long getActivityId() {
        return mActivityId;
    }

    @Override
    public void onLocationChanged(Location arg0) {
        long now = System.currentTimeMillis();
        if (mBug23937Checked == false) {
            long gpsTime = arg0.getTime();
            long utcTime = now;
            if (gpsTime > utcTime + 3 * 1000) {
                mBug23937Delta = utcTime - gpsTime;
            } else {
                mBug23937Delta = 0;
            }
            mBug23937Checked = true;
            System.err.println("Bug23937: gpsTime: " + gpsTime + " utcTime: " + utcTime
                    + " (diff: " + Math.abs(gpsTime - utcTime) + ") => delta: " + mBug23937Delta);
        }
        if (mBug23937Delta != 0) {
            arg0.setTime(arg0.getTime() + mBug23937Delta);
        }

        if (state == TrackerState.STARTED) {
            Integer hrValue = getCurrentHRValue(now, MAX_HR_AGE);
            if (mActivityLastLocation != null) {
                double timeDiff = (double) (arg0.getTime() - mActivityLastLocation
                        .getTime());
                double distDiff = arg0.distanceTo(mActivityLastLocation);
                if (timeDiff < 0) {
                    // time moved backward ??
                    System.err.println("lastTime:       " + mActivityLastLocation.getTime());
                    System.err.println("arg0.getTime(): " + arg0.getTime());
                    System.err.println(" => delta time: " + timeDiff);
                    System.err.println(" => delta dist: " + distDiff);
                    // TODO investigate if this is known...only seems to happen
                    // in emulator
                    timeDiff = 0;
                }
                mElapsedTimeMillis += timeDiff;
                mElapsedDistance += distDiff;
                if (hrValue != null) {
                    mHeartbeats += (hrValue * timeDiff) / (60 * 1000);
                    mHeartbeatMillis += timeDiff; // TODO handle loss of HRM
                                                  // connection
                    mMaxHR = Math.max(hrValue, mMaxHR);
                }
            }
            mActivityLastLocation = arg0;

            mDBWriter.onLocationChanged(arg0, hrValue);

            switch (mLocationType) {
                case DB.LOCATION.TYPE_START:
                case DB.LOCATION.TYPE_RESUME:
                    liveLog(mLocationType);
                    setNextLocationType(DB.LOCATION.TYPE_GPS);
                    break;
                case DB.LOCATION.TYPE_GPS:
                    break;
                case DB.LOCATION.TYPE_PAUSE:
                    break;
                case DB.LOCATION.TYPE_END:
                    assert (false);
                    break;
            }
            liveLog(mLocationType);

            notificationStateManager.displayNotificationState(activityOngoingState);
        }
        mLastLocation = arg0;
    }

    private void liveLog(int type) {

        for (WorkoutObserver l : liveLoggers) {
            l.workoutEvent(workout, type);
        }
    }

    @Override
    public void onProviderDisabled(String arg0) {
    }

    @Override
    public void onProviderEnabled(String arg0) {
    }

    @Override
    public void onStatusChanged(String arg0, int arg1, Bundle arg2) {
    }

    public TrackerState getState() {
        return state;
    }

    /**
     * Service interface stuff...
     */
    public class LocalBinder extends android.os.Binder {
        public Tracker getService() {
            return Tracker.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private void wakelock(boolean get) {
        if (mWakeLock != null) {
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }
            mWakeLock = null;
        }
        if (get) {
            PowerManager pm = (PowerManager) this
                    .getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "RunnerUp");
            if (mWakeLock != null) {
                mWakeLock.acquire();
            }
        }
    }

    public boolean isHRConfigured() {
        if (trackerHRM.getHrProvider() != null) {
            return true;
        }
        return false;
    }

    public boolean isHRConnected() {
        if (!isHRConfigured())
            return false;

        return trackerHRM.getHrProvider().isConnected();
    }

    public Integer getCurrentHRValue(long now, long maxAge) {
        if (!isHRConnected())
            return null;

        HRProvider hrProvider = trackerHRM.getHrProvider();
        if (now > hrProvider.getHRValueTimestamp() + maxAge)
            return null;

        return hrProvider.getHRValue();
    }

    public Integer getCurrentHRValue() {
        return getCurrentHRValue(System.currentTimeMillis(), 3000);
    }

    public Double getCurrentSpeed() {
        return getCurrentSpeed(System.currentTimeMillis(), 3000);
    }

    public Double getCurrentPace() {
        Double speed = getCurrentSpeed();
        if (speed == null)
            return null;
        if (speed == 0.0)
            return Double.MAX_VALUE;
        return 1000 / (speed * 60);
    }

    private Double getCurrentSpeed(long now, long maxAge) {
        if (mLastLocation == null)
            return null;
        if (!mLastLocation.hasSpeed())
            return null;
        if (now > mLastLocation.getTime() + maxAge)
            return null;
        return (double) mLastLocation.getSpeed();
    }

    public double getHeartbeats() {
        return mHeartbeats;
    }

    public Integer getCurrentBatteryLevel() {
        HRProvider hrProvider = trackerHRM.getHrProvider();
        if (hrProvider == null)
            return null;
        return hrProvider.getBatteryLevel();
    }

    private boolean getAllowStartStopFromHeadsetKey() {
        Context ctx = getApplicationContext();
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(ctx);
        return pref.getBoolean(getString(R.string.pref_keystartstop_active), true);
    }

    private void registerHeadsetListener() {
        ComponentName mMediaReceiverCompName = new ComponentName(
                getPackageName(), HeadsetButtonReceiver.class.getName());
        AudioManager mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mAudioManager.registerMediaButtonEventReceiver(mMediaReceiverCompName);
    }

    private void unregisterHeadsetListener() {
        ComponentName mMediaReceiverCompName = new ComponentName(
                getPackageName(), HeadsetButtonReceiver.class.getName());
        AudioManager mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mAudioManager.unregisterMediaButtonEventReceiver(mMediaReceiverCompName);
    }

    private final BroadcastReceiver mWorkoutBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (workout == null) return;
            String action = intent.getAction();
            if (action.equals(Intents.START_STOP)) {
                if (workout.isPaused())
                    workout.onResume(workout);
                else
                    workout.onPause(workout);
            } else if (action.equals(Intents.NEW_LAP)) {
                workout.onNewLap();
            }
        }
    };

    private void registerWorkoutBroadcastsListener() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intents.START_STOP);
        registerReceiver(mWorkoutBroadcastReceiver, intentFilter);
    }

    private void unRegisterWorkoutBroadcastsListener() {
        try {
            unregisterReceiver(mWorkoutBroadcastReceiver);
        } catch (IllegalArgumentException e) {
            if (e.getMessage().contains("Receiver not registered")) {
            } else {
                throw e;
            }
        }
    }

    public Workout getWorkout() {
        return workout;
    }
}
