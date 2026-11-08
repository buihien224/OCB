package android.os;

import android.os.IUpdateEngineStableCallback;

interface IUpdateEngineStable {
    void applyPayloadFd(in ParcelFileDescriptor pfd,
                        long payload_offset,
                        long payload_size,
                        in String[] headerKeyValuePairs);
    boolean bind(IUpdateEngineStableCallback callback);
    boolean unbind(IUpdateEngineStableCallback callback);
}
