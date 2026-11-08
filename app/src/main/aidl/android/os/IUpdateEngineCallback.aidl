package android.os;

oneway interface IUpdateEngineCallback {
    void onStatusUpdate(int status_code, float percentage);
    void onPayloadApplicationComplete(int error_code);
}
