package io.github.hypercar.spike;

import android.content.Context;
import android.content.ContextWrapper;

/**
 * SPIKE-P0：给 DisplayManager 用的“无包名” Context。
 *
 * Android 17 的 DisplayManagerService 会校验 `packageName` 与调用 uid 的包列表是否匹配
 * （`validatePackageName`）。Shizuku UserService 进程的 uid 是 shell，但 Shizuku 传进来的
 * Context 是宿主 App 的 Application（包名 io.github.hypercar），于是建屏直接
 * `SecurityException: packageName must match the owner uid`。
 *
 * 传 null 时该校验直接放行（shell uid 本来就没有自己的包名）。Kotlin 无法从非空返回类型
 * 返回 null，所以这个小类用 Java 写。
 */
public final class NullPackageContext extends ContextWrapper {

    public NullPackageContext(Context base) {
        super(base);
    }

    @Override
    public String getPackageName() {
        return null;
    }

    @Override
    public String getOpPackageName() {
        return null;
    }
}
