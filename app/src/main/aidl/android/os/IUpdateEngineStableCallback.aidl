package android.os;

oneway interface IUpdateEngineStableCallback {
    void onStatusUpdate(int status_code, float fraction);
    void onPayloadApplicationComplete(int error_code);
}
