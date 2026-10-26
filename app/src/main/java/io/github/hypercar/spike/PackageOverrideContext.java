package io.github.hypercar.spike;

import android.content.Context;
import android.content.ContextWrapper;

/**
 * SPIKE-P0：把 DisplayManager 看到的包名覆盖成指定值。
 *
 * Android 17（HyperOS 4）的 DisplayManagerService 在 createVirtualDisplay 时会校验
 * `packageName` 与调用 uid 的包列表匹配（否则 `SecurityException: packageName must
 * match the owner uid`，即使传 null 也照样拒）。Shizuku UserService 的 uid 是 shell(2000)，
 * 系统给这个 uid 登记的包名是 `com.android.shell`，所以这里把包名改成它。
 */
public final class PackageOverrideContext extends ContextWrapper {

    private final String packageName;

    public PackageOverrideContext(Context base, String packageName) {
        super(base);
        this.packageName = packageName;
    }

    @Override
    public String getPackageName() {
        return packageName;
    }

    @Override
    public String getOpPackageName() {
        return packageName;
    }
}
