/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.database.ContentObserver;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wimax.WimaxManagerConstants;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.cdma.EriInfo;
import com.android.internal.util.AsyncChannel;
import com.android.systemui.DemoMode;
import com.android.systemui.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class NetworkController extends BroadcastReceiver implements DemoMode {
    // debug
    static final String TAG = "StatusBar.NetworkController";
    static final boolean DEBUG = false;
    static final boolean CHATTY = false; // additional diagnostics, but not logspew

    private static final int FLIGHT_MODE_ICON = R.drawable.stat_sys_signal_flightmode;

    private static final String UPDATE_QUIET_HOURS_MODES =
            "com.android.settings.slim.service.UPDATE_QUIET_HOURS_MODES";

    // telephony
    boolean mHspaDataDistinguishable;
    final TelephonyManager mPhone;
    boolean mDataConnected;
    IccCardConstants.State mSimState = IccCardConstants.State.READY;
    int mPhoneState = TelephonyManager.CALL_STATE_IDLE;
    int mDataNetType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
    int mDataState = TelephonyManager.DATA_DISCONNECTED;
    int mDataActivity = TelephonyManager.DATA_ACTIVITY_NONE;
    ServiceState mServiceState;
    SignalStrength mSignalStrength;
    int[] mDataIconList = TelephonyIcons.DATA_G[0];
    String mNetworkName;
    String mNetworkNameDefault;
    String mNetworkNameSeparator;
    int mPhoneSignalIconId;
    int mQSPhoneSignalIconId;
    int mDataDirectionIconId; // data + data direction on phones
    int mDataSignalIconId;
    int mDataTypeIconId;
    int mQSDataTypeIconId;
    int mAirplaneIconId;
    boolean mDataActive;
    int mMobileActivityIconId; // overlay arrows for data direction
    int mLastSignalLevel;
    boolean mShowPhoneRSSIForData = false;
    boolean mShowAtLeastThreeGees = false;
    boolean mAlwaysShowCdmaRssi = false;

    String mContentDescriptionPhoneSignal;
    String mContentDescriptionWifi;
    String mContentDescriptionWimax;
    String mContentDescriptionCombinedSignal;
    String mContentDescriptionDataType;

    // wifi
    final WifiManager mWifiManager;
    AsyncChannel mWifiChannel;
    boolean mWifiEnabled, mWifiConnected;
    int mWifiRssi, mWifiLevel;
    String mWifiSsid;
    int mWifiIconId = 0;
    int mQSWifiIconId = 0;
    int mWifiActivityIconId = 0; // overlay arrows for wifi direction
    int mWifiActivity = WifiManager.DATA_ACTIVITY_NONE;
    String oldWifiSsid = "";
    private int mWifiNotifications = 0;
    boolean mConnectionAtBoot = true;
    private int mStoredSSIDs;
    LinkedList mConnectionsList = new LinkedList();

    // bluetooth
    private boolean mBluetoothTethered = false;
    private int mBluetoothTetherIconId =
        com.android.internal.R.drawable.stat_sys_tether_bluetooth;

    //wimax
    private boolean mWimaxSupported = false;
    private boolean mIsWimaxEnabled = false;
    private boolean mWimaxConnected = false;
    private boolean mWimaxIdle = false;
    private int mWimaxIconId = 0;
    private int mWimaxSignal = 0;
    private int mWimaxState = 0;
    private int mWimaxExtraState = 0;
    private int mDataServiceState = ServiceState.STATE_OUT_OF_SERVICE;
    // data connectivity (regardless of state, can we access the internet?)
    // state of inet connection - 0 not connected, 100 connected
    private boolean mConnected = false;
    private int mConnectedNetworkType = ConnectivityManager.TYPE_NONE;
    private String mConnectedNetworkTypeName;
    private int mInetCondition = 0;
    private static final int INET_CONDITION_THRESHOLD = 50;

    private boolean mAirplaneMode = false;
    private boolean mLastAirplaneMode = true;

    private Locale mLocale = null;
    private Locale mLastLocale = null;

    private boolean mHideSignal;

    // our ui
    Context mContext;
    ContentResolver mCr;
    ArrayList<ImageView> mPhoneSignalIconViews = new ArrayList<ImageView>();
    ArrayList<ImageView> mDataDirectionIconViews = new ArrayList<ImageView>();
    ArrayList<ImageView> mDataDirectionOverlayIconViews = new ArrayList<ImageView>();
    ArrayList<ImageView> mWifiIconViews = new ArrayList<ImageView>();
    ArrayList<ImageView> mWimaxIconViews = new ArrayList<ImageView>();
    ArrayList<ImageView> mCombinedSignalIconViews = new ArrayList<ImageView>();
    ArrayList<ImageView> mDataTypeIconViews = new ArrayList<ImageView>();
    ArrayList<TextView> mCombinedLabelViews = new ArrayList<TextView>();
    ArrayList<TextView> mMobileLabelViews = new ArrayList<TextView>();
    ArrayList<TextView> mWifiLabelViews = new ArrayList<TextView>();
    ArrayList<TextView> mEmergencyLabelViews = new ArrayList<TextView>();
    ArrayList<SignalCluster> mSignalClusters = new ArrayList<SignalCluster>();
    ArrayList<NetworkSignalChangedCallback> mSignalsChangedCallbacks =
            new ArrayList<NetworkSignalChangedCallback>();
    ArrayList<CarrierCluster> mCarrierCluster = new ArrayList<CarrierCluster>();
    int mLastPhoneSignalIconId = -1;
    int mLastDataDirectionIconId = -1;
    int mLastDataDirectionOverlayIconId = -1;
    int mLastWifiIconId = -1;
    int mLastWimaxIconId = -1;
    int mLastCombinedSignalIconId = -1;
    int mLastDataTypeIconId = -1;
    int mCarrierIconId = -1;
    String mLastCombinedLabel = "";

    private boolean mHasMobileDataFeature;
    private boolean mUseSixBar;
    private DirtyObserver mObserver = null;

    boolean mDataAndWifiStacked = false;

   // Whether the direction arrows are enabled by the user
   boolean mDirectionArrowsEnabled = false;

    private UpdateUIListener mUpdateUIListener = null;

    public interface SignalCluster {
        void setWifiIndicators(boolean visible, int strengthIcon, int activityIcon,
                String contentDescription);
        void setMobileDataIndicators(boolean visible, int strengthIcon, int activityIcon,
                int typeIcon, String contentDescription, String typeContentDescription);
        void setIsAirplaneMode(boolean is, int airplaneIcon);
    }

    public interface CarrierCluster {
        void setCarrierIndicators(int carrierIcon);
    }

    public interface NetworkSignalChangedCallback {
        void onWifiSignalChanged(boolean enabled, int wifiSignalIconId,
                boolean activityIn, boolean activityOut,
                String wifiSignalContentDescriptionId, String description);
        void onMobileDataSignalChanged(boolean enabled, int mobileSignalIconId,
                String mobileSignalContentDescriptionId, int dataTypeIconId,
                boolean activityIn, boolean activityOut,
                String dataTypeContentDescriptionId, String description);
        void onAirplaneModeChanged(boolean enabled);
    }

    /**
     * Construct this controller object and register for updates.
     */
    public NetworkController(Context context) {
        mContext = context;
        mCr = context.getContentResolver();
        final Resources res = context.getResources();

        ConnectivityManager cm = (ConnectivityManager)mContext.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        mHasMobileDataFeature = cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);

        mShowPhoneRSSIForData = res.getBoolean(R.bool.config_showPhoneRSSIForData);
        mShowAtLeastThreeGees = res.getBoolean(R.bool.config_showMin3G);
        mAlwaysShowCdmaRssi = res.getBoolean(
                com.android.internal.R.bool.config_alwaysUseCdmaRssi);

        // set up the default wifi icon, used when no radios have ever appeared
        updateWifiIcons();
        updateWimaxIcons();

        // telephony
        mPhone = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
        mPhone.listen(mPhoneStateListener,
                          PhoneStateListener.LISTEN_SERVICE_STATE
                        | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                        | PhoneStateListener.LISTEN_CALL_STATE
                        | PhoneStateListener.LISTEN_DATA_CONNECTION_STATE
                        | PhoneStateListener.LISTEN_DATA_ACTIVITY);
        mHspaDataDistinguishable = mContext.getResources().getBoolean(
                R.bool.config_hspa_data_distinguishable);
        updateDefaultNetworkResources();
        mNetworkName = mNetworkNameDefault;

        // wifi
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        Handler handler = new WifiHandler();
        mWifiChannel = new AsyncChannel();
        Messenger wifiMessenger = mWifiManager.getWifiServiceMessenger();
        if (wifiMessenger != null) {
            mWifiChannel.connect(mContext, handler, wifiMessenger);
        }
        mStoredSSIDs = res.getInteger(R.integer.wifi_notifications_remembered_connections);

        // broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CUSTOM_CARRIER_LABEL_CHANGED);
        filter.addAction("com.vanir.UPDATE_NETWORK_PREFERENCES");
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        filter.addAction(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(ConnectivityManager.INET_CONDITION_ACTION);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        mWimaxSupported = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_wimaxEnabled);
        if(mWimaxSupported) {
            filter.addAction(WimaxManagerConstants.WIMAX_NETWORK_STATE_CHANGED_ACTION);
            filter.addAction(WimaxManagerConstants.SIGNAL_LEVEL_CHANGED_ACTION);
            filter.addAction(WimaxManagerConstants.NET_4G_STATE_CHANGED_ACTION);
        }
        context.registerReceiver(this, filter);

        // AIRPLANE_MODE_CHANGED is sent at boot; we've probably already missed it
        updateAirplaneMode();

        // 6-bar data icons
        updateSixBar();
        mObserver = new DirtyObserver(new Handler());
        mObserver.observe();

        mLastLocale = mContext.getResources().getConfiguration().locale;

        mWifiNotifications = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.WIFI_NETWORK_NOTIFICATIONS, 0);

        SettingsObserver settingsObserver = new SettingsObserver(new Handler());
        settingsObserver.observe();
    }

    public boolean hasMobileDataFeature() {
        return mHasMobileDataFeature;
    }

    public boolean hasVoiceCallingFeature() {
        return mPhone.getPhoneType() != TelephonyManager.PHONE_TYPE_NONE;
    }

    public boolean isEmergencyOnly() {
        return (mServiceState != null && mServiceState.isEmergencyOnly());
    }

    public void addPhoneSignalIconView(ImageView v) {
        mPhoneSignalIconViews.add(v);
    }

    public void addDataDirectionIconView(ImageView v) {
        mDataDirectionIconViews.add(v);
    }

    public void addDataDirectionOverlayIconView(ImageView v) {
        mDataDirectionOverlayIconViews.add(v);
    }

    public void addWifiIconView(ImageView v) {
        mWifiIconViews.add(v);
    }
    public void addWimaxIconView(ImageView v) {
        mWimaxIconViews.add(v);
    }

    public void addCombinedSignalIconView(ImageView v) {
        mCombinedSignalIconViews.add(v);
    }

    public void addDataTypeIconView(ImageView v) {
        mDataTypeIconViews.add(v);
    }

    public void addCombinedLabelView(TextView v) {
        mCombinedLabelViews.add(v);
    }

    public void addMobileLabelView(TextView v) {
        mMobileLabelViews.add(v);
    }

    public void addWifiLabelView(TextView v) {
        mWifiLabelViews.add(v);
    }

    public void addEmergencyLabelView(TextView v) {
        mEmergencyLabelViews.add(v);
    }

    public void addCarrierCluster(CarrierCluster ccluster) {
        mCarrierCluster.add(ccluster);
        refreshCarrierCluster(ccluster);
    }

    public void addSignalCluster(SignalCluster cluster) {
        mSignalClusters.add(cluster);
        refreshSignalCluster(cluster);
    }

    public void addNetworkSignalChangedCallback(NetworkSignalChangedCallback cb) {
        mSignalsChangedCallbacks.add(cb);
        notifySignalsChangedCallbacks(cb);
    }

    public void refreshCarrierCluster(CarrierCluster ccluster) {
        if (mDemoMode) return;
        ccluster.setCarrierIndicators(mCarrierIconId);
    }

    public void refreshSignalCluster(SignalCluster cluster) {
        if (mDemoMode) return;
        cluster.setWifiIndicators(
                // only show wifi in the cluster if connected or if wifi-only
                mWifiEnabled && (mWifiConnected || !mHasMobileDataFeature),
                mWifiIconId,
                mWifiActivityIconId,
                mContentDescriptionWifi);

        if (mIsWimaxEnabled && mWimaxConnected) {
            // wimax is special
            cluster.setMobileDataIndicators(
                    true,
                    mAlwaysShowCdmaRssi ? mPhoneSignalIconId : mWimaxIconId,
                    mMobileActivityIconId,
                    mDataTypeIconId,
                    mContentDescriptionWimax,
                    mContentDescriptionDataType);
        } else {
            // normal mobile data
            cluster.setMobileDataIndicators(
                    mHasMobileDataFeature,
                    mShowPhoneRSSIForData ? mPhoneSignalIconId : mDataSignalIconId,
                    mMobileActivityIconId,
                    mDataTypeIconId,
                    mContentDescriptionPhoneSignal,
                    mContentDescriptionDataType);
        }
        cluster.setIsAirplaneMode(mAirplaneMode, mAirplaneIconId);
    }

    public void removeNetworkSignalChangedCallback(NetworkSignalChangedCallback cb) {
        mSignalsChangedCallbacks.remove(cb);
    }

    void notifySignalsChangedCallbacks(NetworkSignalChangedCallback cb) {
        // only show wifi in the cluster if connected or if wifi-only
        boolean wifiEnabled = mWifiEnabled && (mWifiConnected || !mHasMobileDataFeature);
        String wifiDesc = wifiEnabled ?
                mWifiSsid : null;
        boolean wifiIn = wifiEnabled && mWifiSsid != null
                && (mWifiActivity == WifiManager.DATA_ACTIVITY_INOUT
                || mWifiActivity == WifiManager.DATA_ACTIVITY_IN);
        boolean wifiOut = wifiEnabled && mWifiSsid != null
                && (mWifiActivity == WifiManager.DATA_ACTIVITY_INOUT
                || mWifiActivity == WifiManager.DATA_ACTIVITY_OUT);
        cb.onWifiSignalChanged(wifiEnabled, mQSWifiIconId, wifiIn, wifiOut,
                mContentDescriptionWifi, wifiDesc);

        boolean mobileIn = mDataConnected && (mDataActivity == TelephonyManager.DATA_ACTIVITY_INOUT
                || mDataActivity == TelephonyManager.DATA_ACTIVITY_IN);
        boolean mobileOut = mDataConnected && (mDataActivity == TelephonyManager.DATA_ACTIVITY_INOUT
                || mDataActivity == TelephonyManager.DATA_ACTIVITY_OUT);
        if (isEmergencyOnly()) {
            cb.onMobileDataSignalChanged(false, mQSPhoneSignalIconId,
                    mContentDescriptionPhoneSignal, mQSDataTypeIconId, mobileIn, mobileOut,
                    mContentDescriptionDataType, null);
        } else {
            if (mIsWimaxEnabled && mWimaxConnected) {
                // Wimax is special
                cb.onMobileDataSignalChanged(true, mQSPhoneSignalIconId,
                        mContentDescriptionPhoneSignal, mQSDataTypeIconId, mobileIn, mobileOut,
                        mContentDescriptionDataType, mNetworkName);
            } else {
                // Normal mobile data
                cb.onMobileDataSignalChanged(mHasMobileDataFeature, mQSPhoneSignalIconId,
                        mContentDescriptionPhoneSignal, mQSDataTypeIconId, mobileIn, mobileOut,
                        mContentDescriptionDataType, mNetworkName);
            }
        }
        cb.onAirplaneModeChanged(mAirplaneMode);
    }

    public void setStackedMode(boolean stacked) {
        mDataAndWifiStacked = true;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action.equals(WifiManager.RSSI_CHANGED_ACTION)
                || action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)
                || action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
            updateWifiState(intent);
            refreshViews();
        } else if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
            updateSimState(intent);
            updateDataIcon();
            refreshViews();
        } else if (action.equals(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION)) {
            updateDefaultNetworkResources();
            updateNetworkName(intent.getBooleanExtra(TelephonyIntents.EXTRA_SHOW_SPN, false),
                        intent.getStringExtra(TelephonyIntents.EXTRA_SPN),
                        intent.getBooleanExtra(TelephonyIntents.EXTRA_SHOW_PLMN, false),
                        intent.getStringExtra(TelephonyIntents.EXTRA_PLMN));
            refreshViews();
        } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION) ||
                 action.equals(ConnectivityManager.INET_CONDITION_ACTION)) {
            updateConnectivity(intent);
            refreshViews();
        } else if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                public void run() {
                    // Delay wifi connection notifications by at least 30 seconds after boot
                    mConnectionAtBoot = false;
            }}, 30000);
        } else if (action.equals("com.vanir.UPDATE_NETWORK_PREFERENCES")) {
            mWifiNotifications = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.WIFI_NETWORK_NOTIFICATIONS, 0);
            refreshViews();
        } else if (action.equals(Intent.ACTION_CUSTOM_CARRIER_LABEL_CHANGED)) {
            refreshViews();
        } else if (action.equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
            refreshLocale();
            refreshViews();
        } else if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
            refreshLocale();
            updateAirplaneMode();
            refreshViews();
        } else if (action.equals(WimaxManagerConstants.NET_4G_STATE_CHANGED_ACTION) ||
                action.equals(WimaxManagerConstants.SIGNAL_LEVEL_CHANGED_ACTION) ||
                action.equals(WimaxManagerConstants.WIMAX_NETWORK_STATE_CHANGED_ACTION)) {
            updateWimaxState(intent);
            refreshViews();
        }
    }


    // ===== Telephony ==============================================================

    PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            if (DEBUG) {
                Log.d(TAG, "onSignalStrengthsChanged signalStrength=" + signalStrength +
                    ((signalStrength == null) ? "" : (" level=" + signalStrength.getLevel())));
            }
            mSignalStrength = signalStrength;
            updateTelephonySignalStrength();
            refreshViews();
        }

        @Override
        public void onServiceStateChanged(ServiceState state) {
            if (DEBUG) {
                Log.d(TAG, "onServiceStateChanged voiceState=" + state.getVoiceRegState()
                        + " dataState=" + state.getDataRegState());
            }
            mServiceState = state;
            if (mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_combined_signal)) {
                /*
                 * if combined_signal is set to true only then consider data
                 * service state for signal display
                 */
                mDataServiceState = mServiceState.getDataRegState();
                if (DEBUG) {
                    Log.d(TAG, "Combining data service state " + mDataServiceState + " for signal");
                }
            }
            updateTelephonySignalStrength();
            updateDataNetType();
            updateDataIcon();
            refreshViews();
        }

        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            if (DEBUG) {
                Log.d(TAG, "onCallStateChanged state=" + state);
            }
            // In cdma, if a voice call is made, RSSI should switch to 1x.
            if (isCdma()) {
                updateTelephonySignalStrength();
                refreshViews();
            }
        }

        @Override
        public void onDataConnectionStateChanged(int state, int networkType) {
            if (DEBUG) {
                Log.d(TAG, "onDataConnectionStateChanged: state=" + state
                        + " type=" + networkType);
            }
            mDataState = state;
            mDataNetType = networkType;
            updateDataNetType();
            updateDataIcon();
            refreshViews();
        }

        @Override
        public void onDataActivity(int direction) {
            if (DEBUG) {
                Log.d(TAG, "onDataActivity: direction=" + direction);
            }
            mDataActivity = direction;
            updateDataIcon();
            refreshViews();
        }
    };

    private final void updateSimState(Intent intent) {
        String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
        if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(stateExtra)) {
            mSimState = IccCardConstants.State.ABSENT;
        }
        else if (IccCardConstants.INTENT_VALUE_ICC_READY.equals(stateExtra)) {
            mSimState = IccCardConstants.State.READY;
        }
        else if (IccCardConstants.INTENT_VALUE_ICC_LOCKED.equals(stateExtra)) {
            final String lockedReason =
                    intent.getStringExtra(IccCardConstants.INTENT_KEY_LOCKED_REASON);
            if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PIN.equals(lockedReason)) {
                mSimState = IccCardConstants.State.PIN_REQUIRED;
            }
            else if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PUK.equals(lockedReason)) {
                mSimState = IccCardConstants.State.PUK_REQUIRED;
            }
            else {
                mSimState = IccCardConstants.State.NETWORK_LOCKED;
            }
        } else {
            mSimState = IccCardConstants.State.UNKNOWN;
        }
    }

    private boolean isCdma() {
        return (mSignalStrength != null) && !mSignalStrength.isGsm();
    }

    private boolean hasService() {
        if (mServiceState != null) {
            // Consider the device to be in service if either voice or data service is available.
            // Some SIM cards are marketed as data-only and do not support voice service, and on
            // these SIM cards, we want to show signal bars for data service as well as the "no
            // service" or "emergency calls only" text that indicates that voice is not available.
            switch(mServiceState.getVoiceRegState()) {
                case ServiceState.STATE_POWER_OFF:
                    return false;
                case ServiceState.STATE_OUT_OF_SERVICE:
                case ServiceState.STATE_EMERGENCY_ONLY:
                    return mServiceState.getDataRegState() == ServiceState.STATE_IN_SERVICE;
                default:
                    return true;
            }
        } else {
            return false;
        }
    }

    private void updateAirplaneMode() {
        mAirplaneMode = (Settings.Global.getInt(mContext.getContentResolver(),
            Settings.Global.AIRPLANE_MODE_ON, 0) == 1);
    }

    private void refreshLocale() {
        mLocale = mContext.getResources().getConfiguration().locale;
    }

    private final void updateTelephonySignalStrength() {
        if (!hasService()) {
            if (CHATTY) Log.d(TAG, "updateTelephonySignalStrength: !hasService()");
            if (mUseSixBar) {
                mPhoneSignalIconId = (mHideSignal ? 0 : R.drawable.stat_sys_signal_null_6bar);
                mQSPhoneSignalIconId = R.drawable.ic_qs_signal_no_signal_6bar;
                mDataSignalIconId = (mHideSignal ? 0 : R.drawable.stat_sys_signal_null_6bar);
            } else {
                mPhoneSignalIconId = (mHideSignal ? 0 : R.drawable.stat_sys_signal_null);
                mQSPhoneSignalIconId = R.drawable.ic_qs_signal_no_signal;
                mDataSignalIconId = (mHideSignal ? 0 : R.drawable.stat_sys_signal_null);
            }
        } else {
            if (mSignalStrength == null) {
                if (CHATTY) Log.d(TAG, "updateTelephonySignalStrength: mSignalStrength == null");
                if (mUseSixBar) {
                    mPhoneSignalIconId = (mHideSignal ? 0 : R.drawable.stat_sys_signal_null_6bar);
                    mQSPhoneSignalIconId = R.drawable.ic_qs_signal_no_signal_6bar;
                    mDataSignalIconId = (mHideSignal ? 0 : R.drawable.stat_sys_signal_null_6bar);
                } else {
                    mPhoneSignalIconId = (mHideSignal ? 0 : R.drawable.stat_sys_signal_null);
                    mQSPhoneSignalIconId = R.drawable.ic_qs_signal_no_signal;
                    mDataSignalIconId = (mHideSignal ? 0 : R.drawable.stat_sys_signal_null);
                }

                mContentDescriptionPhoneSignal = mContext.getString(
                        AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0]);

            } else {
                int iconLevel;
                int[] iconList;
                if (isCdma() && mAlwaysShowCdmaRssi) {
                    mLastSignalLevel = iconLevel = (mUseSixBar) ?
                            mSignalStrength.getSixBarCdmaLevel() : mSignalStrength.getCdmaLevel();
                    if(DEBUG) Log.d(TAG, "mAlwaysShowCdmaRssi=" + mAlwaysShowCdmaRssi
                            + " set to cdmaLevel=" + mSignalStrength.getCdmaLevel()
                            + " instead of level=" + mSignalStrength.getLevel());
                } else {
                    mLastSignalLevel = iconLevel = (mUseSixBar) ?
                            mSignalStrength.getSixBarLevel() : mSignalStrength.getLevel();
                }

                if (mUseSixBar) {
                    iconList = TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH_6BAR[mInetCondition];
                    mDataSignalIconId = (mHideSignal ? 0 : TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH_6BAR[mInetCondition][iconLevel]);
                    mQSPhoneSignalIconId =
                            TelephonyIcons.QS_TELEPHONY_SIGNAL_STRENGTH_6BAR[mInetCondition][iconLevel];
                } else {
                    if (isCdma()) {
                        if (isCdmaEri()) {
                            iconList = TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH_ROAMING[mInetCondition];
                        } else {
                            iconList = TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH[mInetCondition];
                        }
                    } else {
                        // Though mPhone is a Manager, this call is not an IPC
                        if (mPhone.isNetworkRoaming()) {
                            iconList = TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH_ROAMING[mInetCondition];
                        } else {
                            iconList = TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH[mInetCondition];
                        }
                    }
                    mDataSignalIconId = (mHideSignal ? 0 : TelephonyIcons.DATA_SIGNAL_STRENGTH[mInetCondition][iconLevel]);
                    mQSPhoneSignalIconId =
                            TelephonyIcons.QS_TELEPHONY_SIGNAL_STRENGTH[mInetCondition][iconLevel];
                }
                mPhoneSignalIconId = (mHideSignal ? 0 : iconList[iconLevel]);

                mContentDescriptionPhoneSignal = mContext.getString(
                        AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[iconLevel]);
            }
        }
    }

    private final void updateDataNetType() {
        if (mIsWimaxEnabled && mWimaxConnected) {
            // wimax is a special 4g network not handled by telephony
            mDataIconList = TelephonyIcons.DATA_4G[mInetCondition];
            mDataTypeIconId = R.drawable.stat_sys_data_fully_connected_4g;
            mQSDataTypeIconId = TelephonyIcons.QS_DATA_4G[mInetCondition];
            mContentDescriptionDataType = mContext.getString(
                    R.string.accessibility_data_connection_4g);
        } else {
            switch (mDataNetType) {
                case TelephonyManager.NETWORK_TYPE_UNKNOWN:
                    if (DEBUG) {
                        Log.e(TAG, "updateDataNetType NETWORK_TYPE_UNKNOWN");
                    }
                    if (!mShowAtLeastThreeGees) {
                        mDataIconList = TelephonyIcons.DATA_G[mInetCondition];
                        mDataTypeIconId = 0;
                        mQSDataTypeIconId = 0;
                        mContentDescriptionDataType = mContext.getString(
                                R.string.accessibility_data_connection_gprs);
                        break;
                    } else {
                        // fall through
                    }
                case TelephonyManager.NETWORK_TYPE_EDGE:
                    if (!mShowAtLeastThreeGees) {
                        mDataIconList = TelephonyIcons.DATA_E[mInetCondition];
                        mDataTypeIconId = R.drawable.stat_sys_data_fully_connected_e;
                        mQSDataTypeIconId = TelephonyIcons.QS_DATA_E[mInetCondition];
                        mContentDescriptionDataType = mContext.getString(
                                R.string.accessibility_data_connection_edge);
                        break;
                    } else {
                        // fall through
                    }
                case TelephonyManager.NETWORK_TYPE_UMTS:
                case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
                    mDataIconList = TelephonyIcons.DATA_3G[mInetCondition];
                    mDataTypeIconId = R.drawable.stat_sys_data_fully_connected_3g;
                    mQSDataTypeIconId = TelephonyIcons.QS_DATA_3G[mInetCondition];
                    mContentDescriptionDataType = mContext.getString(
                            R.string.accessibility_data_connection_3g);
                    break;
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                    if (mHspaDataDistinguishable) {
                        mDataIconList = TelephonyIcons.DATA_H[mInetCondition];
                        mDataTypeIconId = R.drawable.stat_sys_data_fully_connected_h;
                        mQSDataTypeIconId = TelephonyIcons.QS_DATA_H[mInetCondition];
                        mContentDescriptionDataType = mContext.getString(
                                R.string.accessibility_data_connection_3_5g);
                    } else {
                        mDataIconList = TelephonyIcons.DATA_3G[mInetCondition];
                        mDataTypeIconId = R.drawable.stat_sys_data_fully_connected_3g;
                        mQSDataTypeIconId = TelephonyIcons.QS_DATA_3G[mInetCondition];
                        mContentDescriptionDataType = mContext.getString(
                                R.string.accessibility_data_connection_3g);
                    }
                    break;
                case TelephonyManager.NETWORK_TYPE_HSPAP:
                    mDataIconList = TelephonyIcons.DATA_HP[mInetCondition];
                    mDataTypeIconId = R.drawable.stat_sys_data_fully_connected_hp;
                    mQSDataTypeIconId = TelephonyIcons.QS_DATA_HP[mInetCondition];
                    mContentDescriptionDataType = mContext.getString(
                            R.string.accessibility_data_connection_HP);
                    break;
                case TelephonyManager.NETWORK_TYPE_DCHSPAP:
                    mDataIconList = TelephonyIcons.DATA_DC[mInetCondition];
                    mDataTypeIconId = R.drawable.stat_sys_data_fully_connected_dc;
                    mQSDataTypeIconId = TelephonyIcons.QS_DATA_DC[mInetCondition];
                    mContentDescriptionDataType = mContext.getString(
                            R.string.accessibility_data_connection_DC);
                    break;
                case TelephonyManager.NETWORK_TYPE_CDMA:
                    if (!mShowAtLeastThreeGees) {
                        // display 1xRTT for IS95A/B
                        mDataIconList = TelephonyIcons.DATA_1X[mInetCondition];
                        mDataTypeIconId = R.drawable.stat_sys_data_fully_connected_1x;
                        mQSDataTypeIconId = TelephonyIcons.QS_DATA_1X[mInetCondition];
                        mContentDescriptionDataType = mContext.getString(
                                R.string.accessibility_data_connection_cdma);
                        break;
                    } else {
                        // fall through
                    }
                case TelephonyManager.NETWORK_TYPE_1xRTT:
                    if (!mShowAtLeastThreeGees) {
                        mDataIconList = TelephonyIcons.DATA_1X[mInetCondition];
                        mDataTypeIconId = R.drawable.stat_sys_data_fully_connected_1x;
                        mQSDataTypeIconId = TelephonyIcons.QS_DATA_1X[mInetCondition];
                        mContentDescriptionDataType = mContext.getString(
                                R.string.accessibility_data_connection_cdma);
                        break;
                    } else {
                        // fall through
                    }
                case TelephonyManager.NETWORK_TYPE_EVDO_0: //fall through
                case TelephonyManager.NETWORK_TYPE_EVDO_A:
                case TelephonyManager.NETWORK_TYPE_EVDO_B:
                case TelephonyManager.NETWORK_TYPE_EHRPD:
                    mDataIconList = TelephonyIcons.DATA_3G[mInetCondition];
                    mDataTypeIconId = R.drawable.stat_sys_data_fully_connected_3g;
                    mQSDataTypeIconId = TelephonyIcons.QS_DATA_3G[mInetCondition];
                    mContentDescriptionDataType = mContext.getString(
                            R.string.accessibility_data_connection_3g);
                    break;
                case TelephonyManager.NETWORK_TYPE_LTE:
                    boolean show4GforLTE = Settings.System.getInt(mContext.getContentResolver(),
                                Settings.System.SHOW_4G_FOR_LTE, 0) == 1;
                    if (show4GforLTE) {
                        mDataIconList = TelephonyIcons.DATA_4G[mInetCondition];
                        mDataTypeIconId = R.drawable.stat_sys_data_fully_connected_4g;
                        mQSDataTypeIconId = TelephonyIcons.QS_DATA_4G[mInetCondition];
                        mContentDescriptionDataType = mContext.getString(
                                R.string.accessibility_data_connection_4g);
                    } else {
                        mDataIconList = TelephonyIcons.DATA_LTE[mInetCondition];
                        mDataTypeIconId = R.drawable.stat_sys_data_fully_connected_lte;
                        mQSDataTypeIconId = TelephonyIcons.QS_DATA_LTE[mInetCondition];
                        mContentDescriptionDataType = mContext.getString(
                                R.string.accessibility_data_connection_lte);
                    }
                    break;
                case TelephonyManager.NETWORK_TYPE_GPRS:
                    if (!mShowAtLeastThreeGees) {
                        mDataIconList = TelephonyIcons.DATA_G[mInetCondition];
                        mDataTypeIconId = R.drawable.stat_sys_data_fully_connected_g;
                        mQSDataTypeIconId = TelephonyIcons.QS_DATA_G[mInetCondition];
                        mContentDescriptionDataType = mContext.getString(
                                R.string.accessibility_data_connection_gprs);
                    } else {
                        mDataIconList = TelephonyIcons.DATA_3G[mInetCondition];
                        mDataTypeIconId = R.drawable.stat_sys_data_fully_connected_3g;
                        mQSDataTypeIconId = TelephonyIcons.QS_DATA_3G[mInetCondition];
                        mContentDescriptionDataType = mContext.getString(
                                R.string.accessibility_data_connection_3g);
                    }
                    break;
                default:
                    if (DEBUG) {
                        Log.e(TAG, "updateDataNetType unknown radio:" + mDataNetType);
                    }
                    mDataNetType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
                    mDataTypeIconId = 0;
                    break;
            }
        }

        if (isCdma()) {
            if (isCdmaEri()) {
                mDataTypeIconId = R.drawable.stat_sys_data_fully_connected_roam;
                mQSDataTypeIconId = TelephonyIcons.QS_DATA_R[mInetCondition];
            }
        } else if (mPhone.isNetworkRoaming()) {
                mDataTypeIconId = R.drawable.stat_sys_data_fully_connected_roam;
                mQSDataTypeIconId = TelephonyIcons.QS_DATA_R[mInetCondition];
        }
    }

    boolean isCdmaEri() {
        if ((mServiceState != null)
                && (hasService() || (mDataServiceState == ServiceState.STATE_IN_SERVICE))) {
            final int iconIndex = mServiceState.getCdmaEriIconIndex();
            if (iconIndex != EriInfo.ROAMING_INDICATOR_OFF) {
                final int iconMode = mServiceState.getCdmaEriIconMode();
                if (iconMode == EriInfo.ROAMING_ICON_MODE_NORMAL
                        || iconMode == EriInfo.ROAMING_ICON_MODE_FLASH) {
                    return true;
                }
            }
        }
        return false;
    }

    private final void updateDataIcon() {
        int iconId = 0;
        boolean visible = true;
        if (mDataNetType == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
            // If data network type is unknown do not display data icon
            visible = false;
        } else if (!isCdma()) {
            // GSM case, we have to check also the sim state
            if (mSimState == IccCardConstants.State.READY ||
                    mSimState == IccCardConstants.State.UNKNOWN) {
                if (mDataState == TelephonyManager.DATA_CONNECTED) {
                    switch (mDataActivity) {
                        case TelephonyManager.DATA_ACTIVITY_IN:
                            iconId = mDataIconList[1];
                            break;
                        case TelephonyManager.DATA_ACTIVITY_OUT:
                            iconId = mDataIconList[2];
                            break;
                        case TelephonyManager.DATA_ACTIVITY_INOUT:
                            iconId = mDataIconList[3];
                            break;
                        default:
                            iconId = mDataIconList[0];
                            break;
                    }
                    mDataDirectionIconId = iconId;
                } else {
                    iconId = 0;
                    visible = false;
                }
            } else {
                iconId = R.drawable.stat_sys_no_sim;
                visible = false; // no SIM? no data
            }
        } else {
            // CDMA case, mDataActivity can be also DATA_ACTIVITY_DORMANT
            if (mDataState == TelephonyManager.DATA_CONNECTED) {
                switch (mDataActivity) {
                    case TelephonyManager.DATA_ACTIVITY_IN:
                        iconId = mDataIconList[1];
                        break;
                    case TelephonyManager.DATA_ACTIVITY_OUT:
                        iconId = mDataIconList[2];
                        break;
                    case TelephonyManager.DATA_ACTIVITY_INOUT:
                        iconId = mDataIconList[3];
                        break;
                    case TelephonyManager.DATA_ACTIVITY_DORMANT:
                    default:
                        iconId = mDataIconList[0];
                        break;
                }
            } else {
                iconId = 0;
                visible = false;
            }
        }

        mDataDirectionIconId = iconId;
        mDataConnected = visible;
    }

    private void updateDefaultNetworkResources() {
        mNetworkNameSeparator = mContext.getString(R.string.status_bar_network_name_separator);
        mNetworkNameDefault = mContext.getString(
                com.android.internal.R.string.lockscreen_carrier_default);
    }

    void updateNetworkName(boolean showSpn, String spn, boolean showPlmn, String plmn) {
        if (false) {
            Log.d("CarrierLabel", "updateNetworkName showSpn=" + showSpn + " spn=" + spn
                    + " showPlmn=" + showPlmn + " plmn=" + plmn);
        }
        StringBuilder str = new StringBuilder();
        boolean something = false;
        if (showPlmn && plmn != null) {
            str.append(plmn);
            something = true;
        }
        if (showSpn && spn != null) {
            if (something) {
                str.append(mNetworkNameSeparator);
            }
            str.append(spn);
            something = true;
        }
        if (something) {
            mNetworkName = str.toString();
        } else {
            mNetworkName = mNetworkNameDefault;
        }
    }

    // ===== Wifi ===================================================================

    class WifiHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        mWifiChannel.sendMessage(Message.obtain(this,
                                AsyncChannel.CMD_CHANNEL_FULL_CONNECTION));
                    } else {
                        Log.e(TAG, "Failed to connect to wifi");
                    }
                    break;
                case WifiManager.DATA_ACTIVITY_NOTIFICATION:
                    if (msg.arg1 != mWifiActivity) {
                        mWifiActivity = msg.arg1;
                        refreshViews();
                    }
                    break;
                default:
                    //Ignore
                    break;
            }
        }
    }

    private void updateWifiState(Intent intent) {
        final String action = intent.getAction();
        if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
            mWifiEnabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                    WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED;

        } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
            final NetworkInfo networkInfo = (NetworkInfo)
                    intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
            boolean wasConnected = mWifiConnected;
            mWifiConnected = networkInfo != null && networkInfo.isConnected();
            // If we just connected, grab the inintial signal strength and ssid
            if (mWifiConnected && !wasConnected) {
                updateQuietHoursState();
                // try getting it out of the intent first
                WifiInfo info = (WifiInfo) intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO);
                if (info == null) {
                    info = mWifiManager.getConnectionInfo();
                }
                if (info != null) {
                    mWifiSsid = huntForSsid(info);
                    if (mWifiNotifications > 0) {
                        boolean restoredConnection = mConnectionsList.contains(mWifiSsid);

                        if (!mConnectionAtBoot && (mWifiSsid != oldWifiSsid)) {
                            String contentText = mContext.getString(R.string.wifi_status_changed);
                            if (restoredConnection) {
                                contentText = mContext.getString(R.string.wifi_restored);
                            }

                            switch (mWifiNotifications) {
                                case 0:
                                    break;
                                case 1:
                                    final String cheese = (contentText + ": " + mWifiSsid);
                                    Toast.makeText(mContext, cheese, Toast.LENGTH_SHORT).show();
                                    break;
                                case 2:
                                case 3:
                                    Intent i = new Intent();
                                    i.setClassName("com.android.settings", "com.android.settings.Settings$WifiSettingsActivity");
                                    PendingIntent contentIntent = PendingIntent.getActivity(mContext, 0, i,
                                    PendingIntent.FLAG_ONE_SHOT);

                                    NotificationManager nm = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
                                    Notification.Builder network = new Notification.Builder(mContext)
                                            .setSmallIcon(R.drawable.wifi_notify)
                                            .setWhen(System.currentTimeMillis())
                                            .setTicker(mContext.getString(R.string.wifi_changed_ticker) + " " + mWifiSsid)
                                            .setContentTitle(contentText)
                                            .setContentText(mContext.getString(R.string.wifi_address_changed) + " " + mWifiSsid)
                                            .setContentIntent(contentIntent)
                                            .setAutoCancel(true);

                                    if (mWifiNotifications == 3) {
                                        Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                                        network.setSound(soundUri);
                                    }

                                    nm.cancel(R.string.wifi_address_changed);
                                    nm.notify(R.string.wifi_address_changed, network.build());
                                    break;
                            }
                        }
                        oldWifiSsid = mWifiSsid;
                        // Stored previous connections to update connection description
                        if (mConnectionsList.size() == mStoredSSIDs) {
                            mConnectionsList.remove(0);
                        }
                        if (!restoredConnection) mConnectionsList.add(mWifiSsid);
                    }
                    mConnectionAtBoot = false;
                } else {
                    mWifiSsid = null;
                }
            } else if (!mWifiConnected) {
                updateQuietHoursState();
                mWifiSsid = null;
            }
        } else if (action.equals(WifiManager.RSSI_CHANGED_ACTION)) {
            mWifiRssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -200);
            mWifiLevel = WifiManager.calculateSignalLevel(
                    mWifiRssi, WifiIcons.WIFI_LEVEL_COUNT);
        }

        updateWifiIcons();
    }

    private void updateQuietHoursState() {
        final int quietHoursWiFi = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.QUIET_HOURS_REQUIRE_WIFI, 0);
        if (quietHoursWiFi == 1 && mWifiConnected) {
            // We just updated and need to inform quiet hours that we're connected
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.QUIET_HOURS_REQUIRE_WIFI, 2);
            sendUpdateIntent();
        } else if (quietHoursWiFi == 2 && !mWifiConnected) {
            // We just updated and need to inform quiet hours that we're not connected
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.QUIET_HOURS_REQUIRE_WIFI, 1);
            sendUpdateIntent();
        }
    }

    private void sendUpdateIntent() {
        Intent intent = new Intent();
        intent.setAction(UPDATE_QUIET_HOURS_MODES);
        mContext.sendBroadcast(intent);
    }

    private void updateWifiIcons() {
        if (mWifiConnected) {
            mWifiIconId = WifiIcons.WIFI_SIGNAL_STRENGTH[mInetCondition][mWifiLevel];
            mQSWifiIconId = WifiIcons.QS_WIFI_SIGNAL_STRENGTH[mInetCondition][mWifiLevel];
            mContentDescriptionWifi = mContext.getString(
                    AccessibilityContentDescriptions.WIFI_CONNECTION_STRENGTH[mWifiLevel]);
        } else {
            if (mDataAndWifiStacked) {
                mWifiIconId = 0;
                mQSWifiIconId = 0;
            } else {
                mWifiIconId = mWifiEnabled ? R.drawable.stat_sys_wifi_signal_null : 0;
                mQSWifiIconId = mWifiEnabled ? R.drawable.ic_qs_wifi_no_network : 0;
            }
            mContentDescriptionWifi = mContext.getString(R.string.accessibility_no_wifi);
        }
    }

    String huntForSsid(WifiInfo info) { // PIE
        String ssid = info.getSSID();
        if (ssid != null) {
            return ssid;
        }
        // OK, it's not in the connectionInfo; we have to go hunting for it
        List<WifiConfiguration> networks = mWifiManager.getConfiguredNetworks();
        for (WifiConfiguration net : networks) {
            if (net.networkId == info.getNetworkId()) {
                return net.SSID;
            }
        }
        return null;
    }


    // ===== Wimax ===================================================================
    private final void updateWimaxState(Intent intent) {
        final String action = intent.getAction();
        boolean wasConnected = mWimaxConnected;
        if (action.equals(WimaxManagerConstants.NET_4G_STATE_CHANGED_ACTION)) {
            int wimaxStatus = intent.getIntExtra(WimaxManagerConstants.EXTRA_4G_STATE,
                    WimaxManagerConstants.NET_4G_STATE_UNKNOWN);
            mIsWimaxEnabled = (wimaxStatus ==
                    WimaxManagerConstants.NET_4G_STATE_ENABLED);
        } else if (action.equals(WimaxManagerConstants.SIGNAL_LEVEL_CHANGED_ACTION)) {
            mWimaxSignal = intent.getIntExtra(WimaxManagerConstants.EXTRA_NEW_SIGNAL_LEVEL, 0);
        } else if (action.equals(WimaxManagerConstants.WIMAX_NETWORK_STATE_CHANGED_ACTION)) {
            mWimaxState = intent.getIntExtra(WimaxManagerConstants.EXTRA_WIMAX_STATE,
                    WimaxManagerConstants.NET_4G_STATE_UNKNOWN);
            mWimaxExtraState = intent.getIntExtra(
                    WimaxManagerConstants.EXTRA_WIMAX_STATE_DETAIL,
                    WimaxManagerConstants.NET_4G_STATE_UNKNOWN);
            mWimaxConnected = (mWimaxState ==
                    WimaxManagerConstants.WIMAX_STATE_CONNECTED);
            mWimaxIdle = (mWimaxExtraState == WimaxManagerConstants.WIMAX_IDLE);
        }
        updateDataNetType();
        updateWimaxIcons();
    }

    private void updateWimaxIcons() {
        if (mIsWimaxEnabled) {
            if (mWimaxConnected) {
                if (mWimaxIdle)
                    mWimaxIconId = WimaxIcons.WIMAX_IDLE;
                else
                    mWimaxIconId = WimaxIcons.WIMAX_SIGNAL_STRENGTH[mInetCondition][mWimaxSignal];
                mContentDescriptionWimax = mContext.getString(
                        AccessibilityContentDescriptions.WIMAX_CONNECTION_STRENGTH[mWimaxSignal]);
            } else {
                mWimaxIconId = WimaxIcons.WIMAX_DISCONNECTED;
                mContentDescriptionWimax = mContext.getString(R.string.accessibility_no_wimax);
            }
        } else {
            mWimaxIconId = 0;
        }
    }

    // ===== Full or limited Internet connectivity ==================================

    private void updateConnectivity(Intent intent) {
        if (CHATTY) {
            Log.d(TAG, "updateConnectivity: intent=" + intent);
        }

        final ConnectivityManager connManager = (ConnectivityManager) mContext
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        final NetworkInfo info = connManager.getActiveNetworkInfo();

        // Are we connected at all, by any interface?
        mConnected = info != null && info.isConnected();
        if (mConnected) {
            mConnectedNetworkType = info.getType();
            mConnectedNetworkTypeName = info.getTypeName();
        } else {
            mConnectedNetworkType = ConnectivityManager.TYPE_NONE;
            mConnectedNetworkTypeName = null;
        }

        int connectionStatus = intent.getIntExtra(ConnectivityManager.EXTRA_INET_CONDITION, 0);

        if (CHATTY) {
            Log.d(TAG, "updateConnectivity: networkInfo=" + info);
            Log.d(TAG, "updateConnectivity: connectionStatus=" + connectionStatus);
        }

        mInetCondition = (connectionStatus > INET_CONDITION_THRESHOLD ? 1 : 0);

        if (info != null && info.getType() == ConnectivityManager.TYPE_BLUETOOTH) {
            mBluetoothTethered = info.isConnected();
        } else {
            mBluetoothTethered = false;
        }

        // We want to update all the icons, all at once, for any condition change
        updateDataNetType();
        updateWimaxIcons();
        updateDataIcon();
        updateTelephonySignalStrength();
        updateWifiIcons();
    }


    // ===== Update the views =======================================================

    void refreshViews() {
        Context context = mContext;

        int combinedSignalIconId = 0;
        int combinedActivityIconId = 0;
        String combinedLabel = "";
        String wifiLabel = "";
        String mobileLabel = "";
        String carrierNumber = "";
        String carrierName = "";
        int N;
        final boolean emergencyOnly = isEmergencyOnly();

        final String customCarrierLabel = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.CUSTOM_CARRIER_LABEL, UserHandle.USER_CURRENT);

        if (!mHasMobileDataFeature) {
            mDataSignalIconId = mPhoneSignalIconId = 0;
            mQSPhoneSignalIconId = 0;
            mobileLabel = carrierName = carrierNumber = "";
        } else {
            // We want to show the carrier name if in service and either:
            //   - We are connected to mobile data, or
            //   - We are not connected to mobile data, as long as the *reason* packets are not
            //     being routed over that link is that we have better connectivity via wifi
            //     or wimax.
            // If data is disconnected for some other reason but wifi (or ethernet/bluetooth)
            // is connected, we show nothing.
            // Otherwise (nothing connected) we show "No internet connection".

            if (mDataConnected) {
                mobileLabel = mNetworkName;
            } else if (mConnected || emergencyOnly) {
                if (hasService() || mWimaxConnected || emergencyOnly) {
                    // The isEmergencyOnly test covers the case of a phone with no SIM
                    mobileLabel = carrierName = mNetworkName;
                    carrierNumber = mPhone.getNetworkOperator();
                } else {
                    // Tablets, basically
                    mobileLabel = carrierName = carrierNumber = "";
                }
            } else {
                mobileLabel
                    = context.getString(R.string.status_bar_settings_signal_meter_disconnected);
                carrierName = mNetworkName;
                carrierNumber = mPhone.getNetworkOperator();
            }

            // Now for things that should only be shown when actually using mobile data.
            if (mDataConnected) {
                combinedSignalIconId = mDataSignalIconId;
                if (mDirectionArrowsEnabled) {
                    switch (mDataActivity) {
                        case TelephonyManager.DATA_ACTIVITY_IN:
                            mMobileActivityIconId = R.drawable.stat_sys_signal_in;
                            break;
                        case TelephonyManager.DATA_ACTIVITY_OUT:
                            mMobileActivityIconId = R.drawable.stat_sys_signal_out;
                            break;
                        case TelephonyManager.DATA_ACTIVITY_INOUT:
                            mMobileActivityIconId = R.drawable.stat_sys_signal_inout;
                            break;
                        default:
                            mMobileActivityIconId = R.drawable.stat_sys_signal_noinout;
                            break;
                    }
                } else {
                    mMobileActivityIconId = R.drawable.stat_sys_signal_noinout;
                }

                combinedLabel = mobileLabel;
                combinedActivityIconId = mMobileActivityIconId;
                combinedSignalIconId = mDataSignalIconId; // set by updateDataIcon()
                mContentDescriptionCombinedSignal = mContentDescriptionDataType;
            } else {
                mMobileActivityIconId = 0;
            }
        }

        if (mWifiConnected) {
            if (mWifiSsid == null) {
                wifiLabel = context.getString(R.string.status_bar_settings_signal_meter_wifi_nossid);
                mWifiActivityIconId = 0; // no wifis, no bits
            } else {
                wifiLabel = mWifiSsid;
                if (DEBUG) {
                    wifiLabel += "xxxxXXXXxxxxXXXX";
                }
                if (mDirectionArrowsEnabled) {
                    switch (mWifiActivity) {
                        case WifiManager.DATA_ACTIVITY_IN:
                            mWifiActivityIconId = R.drawable.stat_sys_wifi_in;
                            break;
                        case WifiManager.DATA_ACTIVITY_OUT:
                            mWifiActivityIconId = R.drawable.stat_sys_wifi_out;
                            break;
                        case WifiManager.DATA_ACTIVITY_INOUT:
                            mWifiActivityIconId = R.drawable.stat_sys_wifi_inout;
                            break;
                        case WifiManager.DATA_ACTIVITY_NONE:
                            mWifiActivityIconId = R.drawable.stat_sys_wifi_noinout;
                            break;
                    }
                } else {
                    mWifiActivityIconId = R.drawable.stat_sys_wifi_noinout;
                }
            }

            combinedActivityIconId = mWifiActivityIconId;
            combinedLabel = wifiLabel;
            combinedSignalIconId = mWifiIconId; // set by updateWifiIcons()
            mContentDescriptionCombinedSignal = mContentDescriptionWifi;
        } else {
            if (mHasMobileDataFeature) {
                wifiLabel = "";
            } else {
                wifiLabel = context.getString(R.string.status_bar_settings_signal_meter_disconnected);
            }
        }

        if (mBluetoothTethered) {
            combinedLabel = mContext.getString(R.string.bluetooth_tethered);
            combinedSignalIconId = mBluetoothTetherIconId;
            mContentDescriptionCombinedSignal = mContext.getString(
                    R.string.accessibility_bluetooth_tether);
        }

        final boolean ethernetConnected = (mConnectedNetworkType == ConnectivityManager.TYPE_ETHERNET);
        if (ethernetConnected) {
            combinedLabel = context.getString(R.string.ethernet_label);
        }

        if (mAirplaneMode &&
                (mServiceState == null || (!hasService() && !mServiceState.isEmergencyOnly()))) {
            // Only display the flight-mode icon if not in "emergency calls only" mode.

            // look again; your radios are now airplanes
            mContentDescriptionPhoneSignal = mContext.getString(
                    R.string.accessibility_airplane_mode);
            mAirplaneIconId = FLIGHT_MODE_ICON;
            mPhoneSignalIconId = mDataSignalIconId = mDataTypeIconId = mQSDataTypeIconId = 0;
            mQSPhoneSignalIconId = 0;

            // combined values from connected wifi take precedence over airplane mode
            if (mWifiConnected) {
                // Suppress "No internet connection." from mobile if wifi connected.
                mobileLabel = "";
            } else {
                if (mHasMobileDataFeature) {
                    // let the mobile icon show "No internet connection."
                    wifiLabel = "";
                } else {
                    wifiLabel = context.getString(R.string.status_bar_settings_signal_meter_disconnected);
                    combinedLabel = wifiLabel;
                }
                mContentDescriptionCombinedSignal = mContentDescriptionPhoneSignal;
                combinedSignalIconId = mDataSignalIconId;
            }
        }
        else if (!mDataConnected && !mWifiConnected && !mBluetoothTethered && !mWimaxConnected && !ethernetConnected) {
            // pretty much totally disconnected

            combinedLabel = context.getString(R.string.status_bar_settings_signal_meter_disconnected);
            // On devices without mobile radios, we want to show the wifi icon
            combinedSignalIconId =
                mHasMobileDataFeature ? mDataSignalIconId : mWifiIconId;
            mContentDescriptionCombinedSignal = mHasMobileDataFeature
                ? mContentDescriptionDataType : mContentDescriptionWifi;

            mDataTypeIconId = 0;
            mQSDataTypeIconId = 0;
            if (isCdma()) {
                if (isCdmaEri()) {
                    mDataTypeIconId = R.drawable.stat_sys_data_fully_connected_roam;
                    mQSDataTypeIconId = TelephonyIcons.QS_DATA_R[mInetCondition];
                }
            } else if (mPhone.isNetworkRoaming()) {
                mDataTypeIconId = R.drawable.stat_sys_data_fully_connected_roam;
                mQSDataTypeIconId = TelephonyIcons.QS_DATA_R[mInetCondition];
            }
        }

        if (!TextUtils.isEmpty(customCarrierLabel)) {
            combinedLabel = customCarrierLabel;
            mobileLabel = customCarrierLabel;
        }

        // Cleanup the double quotes
        if (wifiLabel.length() > 0) {
            wifiLabel = wifiLabel.replaceAll("^\"|\"$", "");
        }

        if (DEBUG) {
            Log.d(TAG, "refreshViews connected={"
                    + (mWifiConnected?" wifi":"")
                    + (mDataConnected?" data":"")
                    + " } level="
                    + ((mSignalStrength == null)?"??":Integer.toString(mSignalStrength.getLevel()))
                    + " combinedSignalIconId=0x"
                    + Integer.toHexString(combinedSignalIconId)
                    + "/" + getResourceName(combinedSignalIconId)
                    + " combinedActivityIconId=0x" + Integer.toHexString(combinedActivityIconId)
                    + " mobileLabel=" + mobileLabel
                    + " wifiLabel=" + wifiLabel
                    + " emergencyOnly=" + emergencyOnly
                    + " combinedLabel=" + combinedLabel
                    + " mAirplaneMode=" + mAirplaneMode
                    + " mDataActivity=" + mDataActivity
                    + " mPhoneSignalIconId=0x" + Integer.toHexString(mPhoneSignalIconId)
                    + " mQSPhoneSignalIconId=0x" + Integer.toHexString(mQSPhoneSignalIconId)
                    + " mDataDirectionIconId=0x" + Integer.toHexString(mDataDirectionIconId)
                    + " mDataSignalIconId=0x" + Integer.toHexString(mDataSignalIconId)
                    + " mDataTypeIconId=0x" + Integer.toHexString(mDataTypeIconId)
                    + " mQSDataTypeIconId=0x" + Integer.toHexString(mQSDataTypeIconId)
                    + " mWifiIconId=0x" + Integer.toHexString(mWifiIconId)
                    + " mQSWifiIconId=0x" + Integer.toHexString(mQSWifiIconId)
                    + " mBluetoothTetherIconId=0x" + Integer.toHexString(mBluetoothTetherIconId));
        }

        // update QS
        for (NetworkSignalChangedCallback cb : mSignalsChangedCallbacks) {
            notifySignalsChangedCallbacks(cb);
        }

        if (!TextUtils.isEmpty(carrierNumber)) {
            mCarrierIconId = context.getResources().getIdentifier("l" + carrierNumber,
                                      "drawable", context.getPackageName());
        }

        if (mCarrierIconId <= 0) {
            if (!TextUtils.isEmpty(carrierName)) {
                carrierName = filterNetworkName(carrierName);
                mCarrierIconId = context.getResources().getIdentifier("l" + carrierName,
                                         "drawable", context.getPackageName());
            }
        }

        if (mCarrierIconId > 0) {
            for (CarrierCluster ccluster : mCarrierCluster) {
                 refreshCarrierCluster(ccluster);
            }
        }

        if (mLastPhoneSignalIconId          != mPhoneSignalIconId
         || mLastDataDirectionOverlayIconId != combinedActivityIconId
         || mLastWifiIconId                 != mWifiIconId
         || mLastWimaxIconId                != mWimaxIconId
         || mLastDataTypeIconId             != mDataTypeIconId
         || mLastAirplaneMode               != mAirplaneMode
         || mLastLocale                     != mLocale)
        {
            // NB: the mLast*s will be updated later
            for (SignalCluster cluster : mSignalClusters) {
                refreshSignalCluster(cluster);
            }
        }

        if (mLastAirplaneMode != mAirplaneMode) {
            mLastAirplaneMode = mAirplaneMode;
        }

        if (mLastLocale != mLocale) {
            mLastLocale = mLocale;
        }

        // the phone icon on phones
        if (mLastPhoneSignalIconId != mPhoneSignalIconId) {
            mLastPhoneSignalIconId = mPhoneSignalIconId;
            N = mPhoneSignalIconViews.size();
            for (int i=0; i<N; i++) {
                final ImageView v = mPhoneSignalIconViews.get(i);
                if (mPhoneSignalIconId == 0) {
                    v.setVisibility(View.GONE);
                } else {
                    v.setVisibility(View.VISIBLE);
                    v.setImageResource(mPhoneSignalIconId);
                    v.setContentDescription(mContentDescriptionPhoneSignal);
                }
            }
        }

        // the data icon on phones
        if (mLastDataDirectionIconId != mDataDirectionIconId) {
            mLastDataDirectionIconId = mDataDirectionIconId;
            N = mDataDirectionIconViews.size();
            for (int i=0; i<N; i++) {
                final ImageView v = mDataDirectionIconViews.get(i);
                v.setImageResource(mDataDirectionIconId);
                v.setContentDescription(mContentDescriptionDataType);
            }
        }

        // the wifi icon on phones
        if (mLastWifiIconId != mWifiIconId) {
            mLastWifiIconId = mWifiIconId;
            N = mWifiIconViews.size();
            for (int i=0; i<N; i++) {
                final ImageView v = mWifiIconViews.get(i);
                if (mWifiIconId == 0) {
                    v.setVisibility(View.GONE);
                } else {
                    v.setVisibility(View.VISIBLE);
                    v.setImageResource(mWifiIconId);
                    v.setContentDescription(mContentDescriptionWifi);
                }
            }
        }

        // the wimax icon on phones
        if (mLastWimaxIconId != mWimaxIconId) {
            mLastWimaxIconId = mWimaxIconId;
            N = mWimaxIconViews.size();
            for (int i=0; i<N; i++) {
                final ImageView v = mWimaxIconViews.get(i);
                if (mWimaxIconId == 0) {
                    v.setVisibility(View.GONE);
                } else {
                    v.setVisibility(View.VISIBLE);
                    v.setImageResource(mWimaxIconId);
                    v.setContentDescription(mContentDescriptionWimax);
                }
           }
        }
        // the combined data signal icon
        if (mLastCombinedSignalIconId != combinedSignalIconId) {
            mLastCombinedSignalIconId = combinedSignalIconId;
            N = mCombinedSignalIconViews.size();
            for (int i=0; i<N; i++) {
                final ImageView v = mCombinedSignalIconViews.get(i);
                v.setImageResource(combinedSignalIconId);
                v.setContentDescription(mContentDescriptionCombinedSignal);
            }
        }

        // the data network type overlay
        if (mLastDataTypeIconId != mDataTypeIconId) {
            mLastDataTypeIconId = mDataTypeIconId;
            N = mDataTypeIconViews.size();
            for (int i=0; i<N; i++) {
                final ImageView v = mDataTypeIconViews.get(i);
                if (mDataTypeIconId == 0) {
                    v.setVisibility(View.GONE);
                } else {
                    v.setVisibility(View.VISIBLE);
                    v.setImageResource(mDataTypeIconId);
                    v.setContentDescription(mContentDescriptionDataType);
                }
            }
        }

        // the data direction overlay
        if (mLastDataDirectionOverlayIconId != combinedActivityIconId) {
            if (DEBUG) {
                Log.d(TAG, "changing data overlay icon id to " + combinedActivityIconId);
            }
            mLastDataDirectionOverlayIconId = combinedActivityIconId;
            N = mDataDirectionOverlayIconViews.size();
            for (int i=0; i<N; i++) {
                final ImageView v = mDataDirectionOverlayIconViews.get(i);
                if (combinedActivityIconId == 0) {
                    v.setVisibility(View.GONE);
                } else {
                    v.setVisibility(View.VISIBLE);
                    v.setImageResource(combinedActivityIconId);
                    v.setContentDescription(mContentDescriptionDataType);
                }
            }
        }

        // the data direction overlay
        if (mLastDataDirectionOverlayIconId != combinedActivityIconId) {
            mLastDataDirectionOverlayIconId = combinedActivityIconId;
        }

        // the combinedLabel in the notification panel
        if (!mLastCombinedLabel.equals(combinedLabel)) {
            mLastCombinedLabel = combinedLabel;
            N = mCombinedLabelViews.size();
            for (int i=0; i<N; i++) {
                TextView v = mCombinedLabelViews.get(i);
                v.setText(combinedLabel);
            }
        }

        // wifi label
        N = mWifiLabelViews.size();
        for (int i=0; i<N; i++) {
            TextView v = mWifiLabelViews.get(i);
            v.setText(wifiLabel);
            if ("".equals(wifiLabel)) {
                v.setVisibility(View.GONE);
            } else {
                v.setVisibility(View.VISIBLE);
            }
        }

        // mobile label
        N = mMobileLabelViews.size();
        for (int i=0; i<N; i++) {
            TextView v = mMobileLabelViews.get(i);
            v.setText(mobileLabel);
            if ("".equals(mobileLabel)) {
                v.setVisibility(View.GONE);
            } else {
                v.setVisibility(View.VISIBLE);
            }
        }

        // e-call label
        N = mEmergencyLabelViews.size();
        for (int i=0; i<N; i++) {
            TextView v = mEmergencyLabelViews.get(i);
            if (!emergencyOnly) {
                v.setVisibility(View.GONE);
            } else {
                v.setText(mobileLabel); // comes from the telephony stack
                v.setVisibility(View.VISIBLE);
            }
        }

        if (mUpdateUIListener != null) {
            mUpdateUIListener.onUpdateUI();
        }
    }

    private String filterNetworkName(String string) {
        boolean bl = true;
        String string2 = string.replace((" "), (""))
                        .replace(("."), ("_")).replace(("&"), ("_"))
                        .replace(("-"), ("")).replace(("*"), (""))
                        .replace(("@"), (""));
        boolean bl2 = (string2.length() > 3) ? bl : false;
        if (string2.length() != 3) {
            bl = false;
        }
        if (!bl2 && bl) return string2.toLowerCase();
        String string3 = (string2.length() > 3)
                         ? (string2.substring(0, 4))
                         : (string2.substring(0, 3));
        string2 = string3.toLowerCase();
        return string2.toLowerCase();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NetworkController state:");
        pw.println(String.format("  %s network type %d (%s)",
                mConnected?"CONNECTED":"DISCONNECTED",
                mConnectedNetworkType, mConnectedNetworkTypeName));
        pw.println("  - telephony ------");
        pw.print("  hasVoiceCallingFeature()=");
        pw.println(hasVoiceCallingFeature());
        pw.print("  hasService()=");
        pw.println(hasService());
        pw.print("  mHspaDataDistinguishable=");
        pw.println(mHspaDataDistinguishable);
        pw.print("  mDataConnected=");
        pw.println(mDataConnected);
        pw.print("  mSimState=");
        pw.println(mSimState);
        pw.print("  mPhoneState=");
        pw.println(mPhoneState);
        pw.print("  mDataState=");
        pw.println(mDataState);
        pw.print("  mDataActivity=");
        pw.println(mDataActivity);
        pw.print("  mDataNetType=");
        pw.print(mDataNetType);
        pw.print("/");
        pw.println(TelephonyManager.getNetworkTypeName(mDataNetType));
        pw.print("  mServiceState=");
        pw.println(mServiceState);
        pw.print("  mSignalStrength=");
        pw.println(mSignalStrength);
        pw.print("  mLastSignalLevel=");
        pw.println(mLastSignalLevel);
        pw.print("  mNetworkName=");
        pw.println(mNetworkName);
        pw.print("  mNetworkNameDefault=");
        pw.println(mNetworkNameDefault);
        pw.print("  mNetworkNameSeparator=");
        pw.println(mNetworkNameSeparator.replace("\n","\\n"));
        pw.print("  mPhoneSignalIconId=0x");
        pw.print(Integer.toHexString(mPhoneSignalIconId));
        pw.print("/");
        pw.print("  mQSPhoneSignalIconId=0x");
        pw.print(Integer.toHexString(mQSPhoneSignalIconId));
        pw.print("/");
        pw.println(getResourceName(mPhoneSignalIconId));
        pw.print("  mDataDirectionIconId=");
        pw.print(Integer.toHexString(mDataDirectionIconId));
        pw.print("/");
        pw.println(getResourceName(mDataDirectionIconId));
        pw.print("  mDataSignalIconId=");
        pw.print(Integer.toHexString(mDataSignalIconId));
        pw.print("/");
        pw.println(getResourceName(mDataSignalIconId));
        pw.print("  mDataTypeIconId=");
        pw.print(Integer.toHexString(mDataTypeIconId));
        pw.print("/");
        pw.println(getResourceName(mDataTypeIconId));
        pw.print("  mQSDataTypeIconId=");
        pw.print(Integer.toHexString(mQSDataTypeIconId));
        pw.print("/");
        pw.println(getResourceName(mQSDataTypeIconId));

        pw.println("  - wifi ------");
        pw.print("  mWifiEnabled=");
        pw.println(mWifiEnabled);
        pw.print("  mWifiConnected=");
        pw.println(mWifiConnected);
        pw.print("  mWifiRssi=");
        pw.println(mWifiRssi);
        pw.print("  mWifiLevel=");
        pw.println(mWifiLevel);
        pw.print("  mWifiSsid=");
        pw.println(mWifiSsid);
        pw.println(String.format("  mWifiIconId=0x%08x/%s",
                    mWifiIconId, getResourceName(mWifiIconId)));
        pw.println(String.format("  mQSWifiIconId=0x%08x/%s",
                    mQSWifiIconId, getResourceName(mQSWifiIconId)));
        pw.print("  mWifiActivity=");
        pw.println(mWifiActivity);

        if (mWimaxSupported) {
            pw.println("  - wimax ------");
            pw.print("  mIsWimaxEnabled="); pw.println(mIsWimaxEnabled);
            pw.print("  mWimaxConnected="); pw.println(mWimaxConnected);
            pw.print("  mWimaxIdle="); pw.println(mWimaxIdle);
            pw.println(String.format("  mWimaxIconId=0x%08x/%s",
                        mWimaxIconId, getResourceName(mWimaxIconId)));
            pw.println(String.format("  mWimaxSignal=%d", mWimaxSignal));
            pw.println(String.format("  mWimaxState=%d", mWimaxState));
            pw.println(String.format("  mWimaxExtraState=%d", mWimaxExtraState));
        }

        pw.println("  - Bluetooth ----");
        pw.print("  mBtReverseTethered=");
        pw.println(mBluetoothTethered);

        pw.println("  - connectivity ------");
        pw.print("  mInetCondition=");
        pw.println(mInetCondition);

        pw.println("  - icons ------");
        pw.print("  mLastPhoneSignalIconId=0x");
        pw.print(Integer.toHexString(mLastPhoneSignalIconId));
        pw.print("/");
        pw.println(getResourceName(mLastPhoneSignalIconId));
        pw.print("  mLastDataDirectionIconId=0x");
        pw.print(Integer.toHexString(mLastDataDirectionIconId));
        pw.print("/");
        pw.println(getResourceName(mLastDataDirectionIconId));
        pw.print("  mLastDataDirectionOverlayIconId=0x");
        pw.print(Integer.toHexString(mLastDataDirectionOverlayIconId));
        pw.print("/");
        pw.println(getResourceName(mLastDataDirectionOverlayIconId));
        pw.print("  mLastWifiIconId=0x");
        pw.print(Integer.toHexString(mLastWifiIconId));
        pw.print("/");
        pw.println(getResourceName(mLastWifiIconId));
        pw.print("  mLastCombinedSignalIconId=0x");
        pw.print(Integer.toHexString(mLastCombinedSignalIconId));
        pw.print("/");
        pw.println(getResourceName(mLastCombinedSignalIconId));
        pw.print("  mLastDataTypeIconId=0x");
        pw.print(Integer.toHexString(mLastDataTypeIconId));
        pw.print("/");
        pw.println(getResourceName(mLastDataTypeIconId));
        pw.print("  mLastCombinedLabel=");
        pw.print(mLastCombinedLabel);
        pw.println("");
    }

    private String getResourceName(int resId) {
        if (resId != 0) {
            final Resources res = mContext.getResources();
            try {
                return res.getResourceName(resId);
            } catch (android.content.res.Resources.NotFoundException ex) {
                return "(unknown)";
            }
        } else {
            return "(null)";
        }
    }

    private boolean mDemoMode;
    private int mDemoInetCondition;
    private int mDemoWifiLevel;
    private int mDemoDataTypeIconId;
    private int mDemoMobileLevel;

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (!mDemoMode && command.equals(COMMAND_ENTER)) {
            mDemoMode = true;
            mDemoWifiLevel = mWifiLevel;
            mDemoInetCondition = mInetCondition;
            mDemoDataTypeIconId = mDataTypeIconId;
            mDemoMobileLevel = mLastSignalLevel;
        } else if (mDemoMode && command.equals(COMMAND_EXIT)) {
            mDemoMode = false;
            for (SignalCluster cluster : mSignalClusters) {
                refreshSignalCluster(cluster);
            }
        } else if (mDemoMode && command.equals(COMMAND_NETWORK)) {
            String airplane = args.getString("airplane");
            if (airplane != null) {
                boolean show = airplane.equals("show");
                for (SignalCluster cluster : mSignalClusters) {
                    cluster.setIsAirplaneMode(show, FLIGHT_MODE_ICON);
                }
            }
            String fully = args.getString("fully");
            if (fully != null) {
                mDemoInetCondition = Boolean.parseBoolean(fully) ? 1 : 0;
            }
            String wifi = args.getString("wifi");
            if (wifi != null) {
                boolean show = wifi.equals("show");
                String level = args.getString("level");
                if (level != null) {
                    mDemoWifiLevel = level.equals("null") ? -1
                            : Math.min(Integer.parseInt(level), WifiIcons.WIFI_LEVEL_COUNT - 1);
                }
                int iconId = mDemoWifiLevel < 0 ? R.drawable.stat_sys_wifi_signal_null
                        : WifiIcons.WIFI_SIGNAL_STRENGTH[mDemoInetCondition][mDemoWifiLevel];
                for (SignalCluster cluster : mSignalClusters) {
                    cluster.setWifiIndicators(
                            show,
                            iconId,
                            mWifiActivityIconId,
                            "Demo");
                }
            }
            String mobile = args.getString("mobile");
            if (mobile != null) {
                boolean show = mobile.equals("show");
                String datatype = args.getString("datatype");
                if (datatype != null) {
                    mDemoDataTypeIconId =
                            datatype.equals("1x") ? R.drawable.stat_sys_data_fully_connected_1x :
                            datatype.equals("3g") ? R.drawable.stat_sys_data_fully_connected_3g :
                            datatype.equals("4g") ? R.drawable.stat_sys_data_fully_connected_4g :
                            datatype.equals("e") ? R.drawable.stat_sys_data_fully_connected_e :
                            datatype.equals("g") ? R.drawable.stat_sys_data_fully_connected_g :
                            datatype.equals("h") ? R.drawable.stat_sys_data_fully_connected_h :
                            datatype.equals("lte") ? R.drawable.stat_sys_data_fully_connected_lte :
                            datatype.equals("roam")
                                    ? R.drawable.stat_sys_data_fully_connected_roam :
                            0;
                }
                int[][] icons = TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH;
                String level = args.getString("level");
                if (level != null) {
                    mDemoMobileLevel = level.equals("null") ? -1
                            : Math.min(Integer.parseInt(level), icons[0].length - 1);
                }
                int iconId = mDemoMobileLevel < 0 ? R.drawable.stat_sys_signal_null :
                        icons[mDemoInetCondition][mDemoMobileLevel];
                for (SignalCluster cluster : mSignalClusters) {
                    cluster.setMobileDataIndicators(
                            show,
                            iconId,
                            mMobileActivityIconId,
                            mDemoDataTypeIconId,
                            "Demo",
                            "Demo");
                }
            }
        }
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_NETWORK_ACTIVITY),
                    false, this, UserHandle.USER_ALL);
            mDirectionArrowsEnabled = Settings.System.getIntForUser(resolver,
                    Settings.System.STATUS_BAR_NETWORK_ACTIVITY,
                    0, UserHandle.USER_CURRENT) == 0 ? false : true;
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.STATUSBAR_HIDE_SIGNAL_BARS), false,
                    this);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    protected void updateSettings() {
        mHideSignal = (Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.STATUSBAR_HIDE_SIGNAL_BARS, 0) == 1);
        updateTelephonySignalStrength();
        updateDataNetType();
    }

    public static interface UpdateUIListener {
        void onUpdateUI();
    }

    public void setListener(UpdateUIListener listener) {
        mUpdateUIListener = listener;
    }

    private class DirtyObserver extends ContentObserver {
        public DirtyObserver(Handler handler) {
            super(handler);
        }

        public void observe() {
            mCr.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUSBAR_6BAR_SIGNAL), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSixBar();
            updateTelephonySignalStrength();
            refreshViews();
        }
    }

    private void updateSixBar() {
        boolean sixBarEnabled = (Settings.System.getInt(mCr,
            Settings.System.STATUSBAR_6BAR_SIGNAL, 0) == 1);
        mUseSixBar = sixBarEnabled;
    }

}
