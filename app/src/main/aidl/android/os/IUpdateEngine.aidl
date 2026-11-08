package android.os;

/** Minimal stub for the full update_engine service (android.os.UpdateEngineService). */
interface IUpdateEngine {
    void applyPayload(String url, long payload_offset, long payload_size, in String[] headerKeyValuePairs);
    boolean bind(in android.os.IUpdateEngineCallback callback);
    boolean unbind(in android.os.IUpdateEngineCallback callback);
    void cancel();
    void resetStatus();
}
