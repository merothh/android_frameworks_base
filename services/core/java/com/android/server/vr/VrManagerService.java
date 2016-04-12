/**
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.server.vr;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.vr.IVrListener;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.service.vr.VrListenerService;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.SystemService;
import com.android.server.utils.ManagedApplicationService.PendingEvent;
import com.android.server.vr.EnabledComponentsObserver.EnabledComponentChangeListener;
import com.android.server.utils.ManagedApplicationService;
import com.android.server.utils.ManagedApplicationService.BinderChecker;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.StringBuilder;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/**
 * Service tracking whether VR mode is active, and notifying listening services of state changes.
 * <p/>
 * Services running in system server may modify the state of VrManagerService via the interface in
 * VrManagerInternal, and may register to receive callbacks when the system VR mode changes via the
 * interface given in VrStateListener.
 * <p/>
 * Device vendors may choose to receive VR state changes by implementing the VR mode HAL, e.g.:
 *  hardware/libhardware/modules/vr
 * <p/>
 * In general applications may enable or disable VR mode by calling
 * {@link android.app.Activity#setVrModeEnabled)}.  An application may also implement a service to
 * be run while in VR mode by implementing {@link android.service.vr.VrListenerService}.
 *
 * @see {@link android.service.vr.VrListenerService}
 * @see {@link com.android.server.vr.VrManagerInternal}
 * @see {@link com.android.server.vr.VrStateListener}
 *
 * @hide
 */
public class VrManagerService extends SystemService implements EnabledComponentChangeListener{

    public static final String TAG = "VrManagerService";

    public static final String VR_MANAGER_BINDER_SERVICE = "vrmanager";

    private static native void initializeNative();
    private static native void setVrModeNative(boolean enabled);

    private final Object mLock = new Object();

    private final IBinder mOverlayToken = new Binder();

    // State protected by mLock
    private boolean mVrModeEnabled;
    private EnabledComponentsObserver mComponentObserver;
    private ManagedApplicationService mCurrentVrService;
    private Context mContext;
    private ComponentName mCurrentVrModeComponent;
    private int mCurrentVrModeUser;
    private boolean mWasDefaultGranted;
    private boolean mGuard;
    private final RemoteCallbackList<IVrStateCallbacks> mRemoteCallbacks =
            new RemoteCallbackList<>();
    private final ArraySet<String> mPreviousToggledListenerSettings = new ArraySet<>();
    private String mPreviousNotificationPolicyAccessPackage;
    private String mPreviousManageOverlayPackage;

    private static final int MSG_VR_STATE_CHANGE = 0;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MSG_VR_STATE_CHANGE : {
                    boolean state = (msg.arg1 == 1);
                    int i = mRemoteCallbacks.beginBroadcast();
                    while (i > 0) {
                        i--;
                        try {
                            mRemoteCallbacks.getBroadcastItem(i).onVrStateChanged(state);
                        } catch (RemoteException e) {
                            // Noop
                        }
                    }
                    mRemoteCallbacks.finishBroadcast();
                } break;
                default :
                    throw new IllegalStateException("Unknown message type: " + msg.what);
            }
        }
    };

    private static final BinderChecker sBinderChecker = new BinderChecker() {
        @Override
        public IInterface asInterface(IBinder binder) {
            return IVrListener.Stub.asInterface(binder);
        }

        @Override
        public boolean checkType(IInterface service) {
            return service instanceof IVrListener;
        }
    };

    /**
     * Called when a user, package, or setting changes that could affect whether or not the
     * currently bound VrListenerService is changed.
     */
    @Override
    public void onEnabledComponentChanged() {
        synchronized (mLock) {
            if (mCurrentVrService == null) {
                return; // No active services
            }

            // There is an active service, update it if needed
            updateCurrentVrServiceLocked(mVrModeEnabled, mCurrentVrService.getComponent(),
                    mCurrentVrService.getUserId(), null);
        }
    }

    private final IVrManager mVrManager = new IVrManager.Stub() {

        @Override
        public void registerListener(IVrStateCallbacks cb) {
            enforceCallerPermission(Manifest.permission.ACCESS_VR_MANAGER);
            if (cb == null) {
                throw new IllegalArgumentException("Callback binder object is null.");
            }

            VrManagerService.this.addStateCallback(cb);
        }

        @Override
        public void unregisterListener(IVrStateCallbacks cb) {
            enforceCallerPermission(Manifest.permission.ACCESS_VR_MANAGER);
            if (cb == null) {
                throw new IllegalArgumentException("Callback binder object is null.");
            }

            VrManagerService.this.removeStateCallback(cb);
        }

        @Override
        public boolean getVrModeState() {
            return VrManagerService.this.getVrMode();
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (getContext().checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("permission denied: can't dump VrManagerService from pid="
                        + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
                return;
            }
            pw.print("mVrModeEnabled=");
            pw.println(mVrModeEnabled);
            pw.print("mCurrentVrModeUser=");
            pw.println(mCurrentVrModeUser);
            pw.print("mRemoteCallbacks=");
            int i=mRemoteCallbacks.beginBroadcast(); // create the broadcast item array
            while(i-->0) {
                pw.print(mRemoteCallbacks.getBroadcastItem(i));
                if (i>0) pw.print(", ");
            }
            mRemoteCallbacks.finishBroadcast();
            pw.println();
            pw.print("mCurrentVrService=");
            pw.println(mCurrentVrService != null ? mCurrentVrService.getComponent() : "(none)");
            pw.print("mCurrentVrModeComponent=");
            pw.println(mCurrentVrModeComponent);
        }

    };

    private void enforceCallerPermission(String permission) {
        if (mContext.checkCallingOrSelfPermission(permission)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Caller does not hold the permission " + permission);
        }
    }

    /**
     * Implementation of VrManagerInternal.  Callable only from system services.
     */
    private final class LocalService extends VrManagerInternal {
        @Override
        public void setVrMode(boolean enabled, ComponentName packageName, int userId,
                ComponentName callingPackage) {
            VrManagerService.this.setVrMode(enabled, packageName, userId, callingPackage);
        }

        @Override
        public boolean isCurrentVrListener(String packageName, int userId) {
            return VrManagerService.this.isCurrentVrListener(packageName, userId);
        }

        @Override
        public int hasVrPackage(ComponentName packageName, int userId) {
            return VrManagerService.this.hasVrPackage(packageName, userId);
        }
    }

    public VrManagerService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        synchronized(mLock) {
            initializeNative();
            mContext = getContext();
        }

        publishLocalService(VrManagerInternal.class, new LocalService());
        publishBinderService(VR_MANAGER_BINDER_SERVICE, mVrManager.asBinder());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            synchronized (mLock) {
                Looper looper = Looper.getMainLooper();
                Handler handler = new Handler(looper);
                ArrayList<EnabledComponentChangeListener> listeners = new ArrayList<>();
                listeners.add(this);
                mComponentObserver = EnabledComponentsObserver.build(mContext, handler,
                        Settings.Secure.ENABLED_VR_LISTENERS, looper,
                        android.Manifest.permission.BIND_VR_LISTENER_SERVICE,
                        VrListenerService.SERVICE_INTERFACE, mLock, listeners);

                mComponentObserver.rebuildAll();
            }
        }
    }

    @Override
    public void onStartUser(int userHandle) {
        synchronized (mLock) {
            mComponentObserver.onUsersChanged();
        }
    }

    @Override
    public void onSwitchUser(int userHandle) {
        synchronized (mLock) {
            mComponentObserver.onUsersChanged();
        }

    }

    @Override
    public void onStopUser(int userHandle) {
        synchronized (mLock) {
            mComponentObserver.onUsersChanged();
        }

    }

    @Override
    public void onCleanupUser(int userHandle) {
        synchronized (mLock) {
            mComponentObserver.onUsersChanged();
        }
    }

    private void updateOverlayStateLocked(ComponentName exemptedComponent) {
        final long identity = Binder.clearCallingIdentity();
        try {
            AppOpsManager appOpsManager = getContext().getSystemService(AppOpsManager.class);
            if (appOpsManager != null) {
                String[] exemptions = (exemptedComponent == null) ? new String[0] :
                        new String[] { exemptedComponent.getPackageName() };

                appOpsManager.setUserRestriction(AppOpsManager.OP_SYSTEM_ALERT_WINDOW,
                        mVrModeEnabled, mOverlayToken, exemptions);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Send VR mode changes (if the mode state has changed), and update the bound/unbound state of
     * the currently selected VR listener service.  If the component selected for the VR listener
     * service has changed, unbind the previous listener and bind the new listener (if enabled).
     * <p/>
     * Note: Must be called while holding {@code mLock}.
     *
     * @param enabled new state for VR mode.
     * @param component new component to be bound as a VR listener.
     * @param userId user owning the component to be bound.
     * @param calling the component currently using VR mode, or null to leave unchanged.
     *
     * @return {@code true} if the component/user combination specified is valid.
     */
    private boolean updateCurrentVrServiceLocked(boolean enabled, @NonNull ComponentName component,
            int userId, ComponentName calling) {

        boolean sendUpdatedCaller = false;
        final long identity = Binder.clearCallingIdentity();
        try {

            boolean validUserComponent = (mComponentObserver.isValid(component, userId) ==
                    EnabledComponentsObserver.NO_ERROR);

            // Always send mode change events.
            changeVrModeLocked(enabled, (enabled && validUserComponent) ? component : null);

            if (!enabled || !validUserComponent) {
                // Unbind whatever is running
                if (mCurrentVrService != null) {
                    Slog.i(TAG, "Disconnecting " + mCurrentVrService.getComponent() + " for user " +
                            mCurrentVrService.getUserId());
                    mCurrentVrService.disconnect();
                    disableImpliedPermissionsLocked(mCurrentVrService.getComponent(),
                            new UserHandle(mCurrentVrService.getUserId()));
                    mCurrentVrService = null;
                }
            } else {
                if (mCurrentVrService != null) {
                    // Unbind any running service that doesn't match the component/user selection
                    if (mCurrentVrService.disconnectIfNotMatching(component, userId)) {
                        Slog.i(TAG, "Disconnecting " + mCurrentVrService.getComponent() +
                                " for user " + mCurrentVrService.getUserId());
                        disableImpliedPermissionsLocked(mCurrentVrService.getComponent(),
                                new UserHandle(mCurrentVrService.getUserId()));
                        createAndConnectService(component, userId);
                        enableImpliedPermissionsLocked(mCurrentVrService.getComponent(),
                                new UserHandle(mCurrentVrService.getUserId()));
                        sendUpdatedCaller = true;
                    }
                    // The service with the correct component/user is bound
                } else {
                    // Nothing was previously running, bind a new service
                    createAndConnectService(component, userId);
                    enableImpliedPermissionsLocked(mCurrentVrService.getComponent(),
                            new UserHandle(mCurrentVrService.getUserId()));
                    sendUpdatedCaller = true;
                }
            }

            if (calling != null && !Objects.equals(calling, mCurrentVrModeComponent))  {
                mCurrentVrModeComponent = calling;
                mCurrentVrModeUser = userId;
                sendUpdatedCaller = true;
            }

            if (mCurrentVrService != null && sendUpdatedCaller) {
                final ComponentName c = mCurrentVrModeComponent;
                mCurrentVrService.sendEvent(new PendingEvent() {
                    @Override
                    public void runEvent(IInterface service) throws RemoteException {
                        IVrListener l = (IVrListener) service;
                        l.focusedActivityChanged(c);
                    }
                });
            }

            return validUserComponent;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Enable the permission given in {@link #IMPLIED_VR_LISTENER_PERMISSIONS} for the given
     * component package and user.
     *
     * @param component the component whose package should be enabled.
     * @param userId the user that owns the given component.
     */
    private void enableImpliedPermissionsLocked(ComponentName component, UserHandle userId) {
        if (mGuard) {
            // Impossible
            throw new IllegalStateException("Enabling permissions without disabling.");
        }
        mGuard = true;

        PackageManager pm = mContext.getPackageManager();

        String pName = component.getPackageName();
        if (pm == null) {
            Slog.e(TAG, "Couldn't set implied permissions for " + pName +
                ", PackageManager isn't running");
            return;
        }

        ApplicationInfo info = null;
        try {
            info = pm.getApplicationInfo(pName, PackageManager.GET_META_DATA);
        } catch (NameNotFoundException e) {
        }

        if (info == null) {
            Slog.e(TAG, "Couldn't set implied permissions for " + pName + ", no such package.");
            return;
        }

        if (!(info.isSystemApp() || info.isUpdatedSystemApp())) {
            return; // Application is not pre-installed, avoid setting implied permissions
        }

        mWasDefaultGranted = true;

        grantOverlayAccess(pName, userId);
        grantNotificationPolicyAccess(pName);
        grantNotificationListenerAccess(pName, userId);
    }

    /**
     * Disable the permission given in {@link #IMPLIED_VR_LISTENER_PERMISSIONS} for the given
     * component package and user.
     *
     * @param component the component whose package should be disabled.
     * @param userId the user that owns the given component.
     */
    private void disableImpliedPermissionsLocked(ComponentName component, UserHandle userId) {
        if (!mGuard) {
            // Impossible
            throw new IllegalStateException("Disabling permissions without enabling.");
        }
        mGuard = false;

        PackageManager pm = mContext.getPackageManager();

        if (pm == null) {
            Slog.e(TAG, "Couldn't remove implied permissions for " + component +
                ", PackageManager isn't running");
            return;
        }

        String pName = component.getPackageName();
        if (mWasDefaultGranted) {
            revokeOverlayAccess(userId);
            revokeNotificationPolicyAccess(pName);
            revokeNotificiationListenerAccess();
            mWasDefaultGranted = false;
        }

    }

    private void grantOverlayAccess(String pkg, UserHandle userId) {
        PackageManager pm = mContext.getPackageManager();
        boolean prev = (PackageManager.PERMISSION_GRANTED ==
                pm.checkPermission(android.Manifest.permission.SYSTEM_ALERT_WINDOW, pkg));
        mPreviousManageOverlayPackage = null;
        if (!prev) {
            pm.grantRuntimePermission(pkg, android.Manifest.permission.SYSTEM_ALERT_WINDOW,
                    userId);
            mPreviousManageOverlayPackage = pkg;
        }
    }

    private void revokeOverlayAccess(UserHandle userId) {
        PackageManager pm = mContext.getPackageManager();
        if (mPreviousManageOverlayPackage != null) {
            pm.revokeRuntimePermission(mPreviousManageOverlayPackage,
                    android.Manifest.permission.SYSTEM_ALERT_WINDOW, userId);
            mPreviousManageOverlayPackage = null;
        }
    }


    private void grantNotificationPolicyAccess(String pkg) {
        NotificationManager nm = mContext.getSystemService(NotificationManager.class);
        boolean prev = nm.isNotificationPolicyAccessGrantedForPackage(pkg);
        mPreviousNotificationPolicyAccessPackage = null;
        if (!prev) {
            mPreviousNotificationPolicyAccessPackage = pkg;
            nm.setNotificationPolicyAccessGranted(pkg, true);
        }
    }

    private void revokeNotificationPolicyAccess(String pkg) {
        NotificationManager nm = mContext.getSystemService(NotificationManager.class);
        if (mPreviousNotificationPolicyAccessPackage != null) {
            if (mPreviousNotificationPolicyAccessPackage.equals(pkg)) {
                // Remove any DND zen rules possibly created by the package.
                nm.removeAutomaticZenRules(mPreviousNotificationPolicyAccessPackage);
                // Remove Notification Policy Access.
                nm.setNotificationPolicyAccessGranted(mPreviousNotificationPolicyAccessPackage, false);
                mPreviousNotificationPolicyAccessPackage = null;
            } else {
                Slog.e(TAG, "Couldn't remove Notification Policy Access for package: " + pkg);
            }
        }
    }

    private void grantNotificationListenerAccess(String pkg, UserHandle userId) {
        PackageManager pm = mContext.getPackageManager();
        ArraySet<ComponentName> possibleServices = EnabledComponentsObserver.loadComponentNames(pm,
                userId.getIdentifier(), NotificationListenerService.SERVICE_INTERFACE,
                android.Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE);
        ContentResolver resolver = mContext.getContentResolver();

        ArraySet<String> current = getCurrentNotifListeners(resolver);

        mPreviousToggledListenerSettings.clear();

        for (ComponentName c : possibleServices) {
            String flatName = c.flattenToString();
            if (Objects.equals(c.getPackageName(), pkg)
                    && !current.contains(flatName)) {
                mPreviousToggledListenerSettings.add(flatName);
                current.add(flatName);
            }
        }

        if (current.size() > 0) {
            String flatSettings = formatSettings(current);
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
                    flatSettings);
        }
    }

    private void revokeNotificiationListenerAccess() {
        if (mPreviousToggledListenerSettings.isEmpty()) {
            return;
        }

        ContentResolver resolver = mContext.getContentResolver();
        ArraySet<String> current = getCurrentNotifListeners(resolver);

        current.removeAll(mPreviousToggledListenerSettings);
        mPreviousToggledListenerSettings.clear();

        String flatSettings = formatSettings(current);
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
                flatSettings);
    }

    private ArraySet<String> getCurrentNotifListeners(ContentResolver resolver) {
        String flat = Settings.Secure.getString(resolver,
                Settings.Secure.ENABLED_NOTIFICATION_LISTENERS);

        ArraySet<String> current = new ArraySet<>();
        if (flat != null) {
            String[] allowed = flat.split(":");
            for (String s : allowed) {
                current.add(s);
            }
        }
        return current;
    }

    private static String formatSettings(Collection<String> c) {
        if (c == null || c.isEmpty()) {
            return "";
        }

        StringBuilder b = new StringBuilder();
        boolean start = true;
        for (String s : c) {
            if ("".equals(s)) {
                continue;
            }
            if (!start) {
                b.append(':');
            }
            b.append(s);
            start = false;
        }
        return b.toString();
    }



    private void createAndConnectService(@NonNull ComponentName component, int userId) {
        mCurrentVrService = VrManagerService.create(mContext, component, userId);
        mCurrentVrService.connect();
        Slog.i(TAG, "Connecting " + component + " for user " + userId);
    }

    /**
     * Send VR mode change callbacks to HAL and system services if mode has actually changed.
     * <p/>
     * Note: Must be called while holding {@code mLock}.
     *
     * @param enabled new state of the VR mode.
     * @param exemptedComponent a component to exempt from AppOps restrictions for overlays.
     */
    private void changeVrModeLocked(boolean enabled, ComponentName exemptedComponent) {
        if (mVrModeEnabled != enabled) {
            mVrModeEnabled = enabled;

            // Log mode change event.
            Slog.i(TAG, "VR mode " + ((mVrModeEnabled) ? "enabled" : "disabled"));
            setVrModeNative(mVrModeEnabled);

            updateOverlayStateLocked(exemptedComponent);
            onVrModeChangedLocked();
        }
    }

    /**
     * Notify system services of VR mode change.
     * <p/>
     * Note: Must be called while holding {@code mLock}.
     */
    private void onVrModeChangedLocked() {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_VR_STATE_CHANGE,
                (mVrModeEnabled) ? 1 : 0, 0));
    }

    /**
     * Helper function for making ManagedApplicationService instances.
     */
    private static ManagedApplicationService create(@NonNull Context context,
            @NonNull ComponentName component, int userId) {
        return ManagedApplicationService.build(context, component, userId,
                R.string.vr_listener_binding_label, Settings.ACTION_VR_LISTENER_SETTINGS,
                sBinderChecker);
    }

    /*
     * Implementation of VrManagerInternal calls.  These are callable from system services.
     */

    private boolean setVrMode(boolean enabled, @NonNull ComponentName targetPackageName,
            int userId, @NonNull ComponentName callingPackage) {
        synchronized (mLock) {
            return updateCurrentVrServiceLocked(enabled, targetPackageName, userId, callingPackage);
        }
    }

    private int hasVrPackage(@NonNull ComponentName targetPackageName, int userId) {
        synchronized (mLock) {
            return mComponentObserver.isValid(targetPackageName, userId);
        }
    }

    private boolean isCurrentVrListener(String packageName, int userId) {
        synchronized (mLock) {
            if (mCurrentVrService == null) {
                return false;
            }
            return mCurrentVrService.getComponent().getPackageName().equals(packageName) &&
                    userId == mCurrentVrService.getUserId();
        }
    }

    /*
     * Implementation of IVrManager calls.
     */

    private void addStateCallback(IVrStateCallbacks cb) {
        mRemoteCallbacks.register(cb);
    }

    private void removeStateCallback(IVrStateCallbacks cb) {
        mRemoteCallbacks.unregister(cb);
    }

    private boolean getVrMode() {
        synchronized (mLock) {
            return mVrModeEnabled;
        }
    }
}
