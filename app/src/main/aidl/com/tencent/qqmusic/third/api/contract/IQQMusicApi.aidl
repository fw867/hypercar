package com.tencent.qqmusic.third.api.contract;
import android.os.Bundle;
import com.tencent.qqmusic.third.api.contract.IQQMusicApiCallback;
// QQMusic phone IPC contract: transaction IDs 1 and 2, matching the exported service.
interface IQQMusicApi {
    Bundle execute(String method, in Bundle params);
    oneway void executeAsync(String method, in Bundle params, IQQMusicApiCallback callback);
}
