// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;

/** 宿主只认包名。系统升级会轮换签名，写死证书会让注入整段失效。 */
public final class HostIdentity {
    public static final String MI_PACKAGE = "com.mi.health";
    public static final String HEALTH_PACKAGE = "com.heytap.health";
    public static final String DEVICES_PACKAGE = "com.heytap.mydevices";

    private HostIdentity() {}

    public static boolean isSelf() {
        return Binder.getCallingUid() == Process.myUid();
    }

    public static void requireMiCaller(Context context) {
        requireCaller(context, MI_PACKAGE);
    }

    public static void requireCaller(Context context, String packageName) {
        if (!uidHas(context, Binder.getCallingUid(), packageName)) {
            throw new SecurityException("CALLER_NOT_AUTHORIZED");
        }
    }

    /** 这个 UID 拥有 {@code packageName}。共享 UID 下面可以挂多个包。 */
    public static boolean uidHas(Context context, int uid, String packageName) {
        if (context == null || packageName == null) return false;
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        for (String name : packages) if (packageName.equals(name)) return true;
        return false;
    }

    public static boolean installed(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException missing) {
            return false;
        }
    }
}
