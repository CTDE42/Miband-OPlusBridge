// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.content.AttributionSource;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs as root through {@code app_process}. Turns on ColorOS「自启动」and「关联启动」for the
 * given packages through the provider the system settings page uses
 * ({@code com.oplus.startup.provider}, in {@code com.oplus.battery}).
 *
 * <p>Without「关联启动」, {@code OplusAppStartupManager} refuses to start this app when OPPO 健康
 * opens one of its content providers ("prevent start ... by contentprovider"). After every
 * package update the bridge process is gone, so OPPO 健康 only sees {@code Unknown authority}
 * until the bridge is opened by hand. Without「自启动」, {@code MY_PACKAGE_REPLACED} and
 * {@code BOOT_COMPLETED} do not start the bridge either.</p>
 *
 * <p>标准输出就是结果通道：这个类经 {@code app_process} 以 root 运行，调用方从进程输出读结果。
 * 每个包一行 {@code <pkg> auto=<bool> associate=<bool>}，调用方最多读
 * {@link OwnershipController#MAX_COMMAND_OUTPUT} 字节。一行只放这一条状态，结果才能完整回到桥接进程。
 * 退出码 0 表示每个包的自启动和关联启动都已允许。</p>
 */
public final class RootStartupGrant {
    static final String AUTHORITY = "com.oplus.startup.provider";

    private RootStartupGrant() {}

    public static void main(String[] args) {
        int code;
        try {
            code = run(args) ? 0 : 1;
        } catch (Throwable failure) {
            System.out.println("ERROR " + failure.getClass().getSimpleName() + " " + failure.getMessage());
            code = 2;
        }
        System.out.flush();
        System.exit(code);
    }

    private static boolean run(String[] args) throws Exception {
        ArrayList<String> packages = new ArrayList<>();
        for (String arg : args) if (arg != null && arg.matches("[A-Za-z0-9._]+")) packages.add(arg);
        if (packages.isEmpty()) throw new IllegalArgumentException("NO_PACKAGES");
        Object am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        IBinder token = new Binder();
        Object provider = acquire(am, token);
        try {
            List<String> auto = list(provider, "getAutoStartList", "autoStartAllowList");
            List<String> associate = list(provider, "getAssociateStartList", "associateStartAllowList");
            ArrayList<String> needAuto = missing(packages, auto);
            ArrayList<String> needAssociate = missing(packages, associate);
            if (!needAuto.isEmpty()) call(provider, "setStartupState", allow(needAuto));
            if (!needAssociate.isEmpty()) call(provider, "setAssociateStartState", allow(needAssociate));
            if (!needAuto.isEmpty()) auto = list(provider, "getAutoStartList", "autoStartAllowList");
            if (!needAssociate.isEmpty()) associate = list(provider, "getAssociateStartList", "associateStartAllowList");
            boolean all = true;
            for (String pkg : packages) {
                boolean a = auto.contains(pkg);
                boolean b = associate.contains(pkg);
                all &= a && b;
                System.out.println(pkg + " auto=" + a + " associate=" + b
                        + (needAuto.contains(pkg) || needAssociate.contains(pkg) ? " changed" : ""));
            }
            return all;
        } finally {
            release(am, token);
        }
    }

    static ArrayList<String> missing(List<String> wanted, List<String> allowed) {
        ArrayList<String> result = new ArrayList<>();
        for (String pkg : wanted) if (!allowed.contains(pkg)) result.add(pkg);
        return result;
    }

    private static Bundle allow(ArrayList<String> packages) {
        Bundle extras = new Bundle();
        extras.putStringArrayList("packageList", packages);
        for (String pkg : packages) extras.putBoolean(pkg, true);
        return extras;
    }

    private static List<String> list(Object provider, String method, String key) throws Exception {
        Bundle result = call(provider, method, new Bundle());
        List<String> values = result == null ? null : result.getStringArrayList(key);
        if (values == null) throw new IllegalStateException("STARTUP_LIST_UNAVAILABLE " + method);
        return values;
    }

    private static Object acquire(Object am, IBinder token) throws Exception {
        Object holder = null;
        for (Method method : am.getClass().getMethods()) {
            if (!method.getName().equals("getContentProviderExternal")) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 4) holder = method.invoke(am, AUTHORITY, 0, token, "oplusband");
            else if (types.length == 3) holder = method.invoke(am, AUTHORITY, 0, token);
            if (holder != null) break;
        }
        if (holder == null) throw new IllegalStateException("STARTUP_PROVIDER_MISSING");
        Object provider = holder.getClass().getField("provider").get(holder);
        if (provider == null) throw new IllegalStateException("STARTUP_PROVIDER_MISSING");
        return provider;
    }

    private static void release(Object am, IBinder token) {
        for (Method method : am.getClass().getMethods()) {
            try {
                if (method.getName().equals("removeContentProviderExternalAsUser")
                        && method.getParameterCount() == 3) {
                    method.invoke(am, AUTHORITY, token, 0);
                    return;
                }
            } catch (ReflectiveOperationException ignored) {
                // The binder token dies with this process anyway.
            }
        }
    }

    private static Bundle call(Object provider, String name, Bundle extras) throws Exception {
        AttributionSource source = new AttributionSource.Builder(Process.myUid()).setPackageName("root").build();
        for (Method method : provider.getClass().getMethods()) {
            if (!method.getName().equals("call")) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 5 && types[0] == AttributionSource.class) {
                return (Bundle) method.invoke(provider, source, AUTHORITY, name, null, extras);
            }
        }
        throw new NoSuchMethodException("IContentProvider.call");
    }
}
