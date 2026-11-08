package com.bhlnk.ocb;

import com.bhlnk.ocb.IUpdateCallback;

interface IUserService {
    void destroy() = 16777114;
    void exit() = 1;
    String install(String apkPath) = 2;
    String run(String args) = 3;
    String runDetached(String args) = 4;
    String readLog() = 5;
    String openRoot() = 6;
    String execRoot(String cmd) = 7;
    String closeRoot() = 8;
    String info() = 9;
    String rebootDevice() = 10;
    String execShell(String cmd) = 11;
    String listOtaFiles() = 12;
    String localUpdate(String path) = 13;
    String verifyPayload(String path) = 14;
    String otaStatus() = 15;
    /** Apply payload via IUpdateEngineStable binder. Writes progress to /data/local/tmp/apply.log */
    String applyPayloadDirect(String payloadPath, in String[] headers) = 17;
    /** Cancel ongoing update via binder. */
    String cancelUpdate() = 18;
    /** Reset update_engine status. */
    String resetStatus() = 19;
    /**
     * Apply payload directly via IUpdateEngineStable binder with live callbacks to the caller.
     * payloadOffset/payloadSize: position of payload.bin inside the zip.
     * Returns empty string on success, error message on failure.
     */
    String startUpdate(String payloadPath, long payloadOffset, long payloadSize,
                       in String[] headers, IUpdateCallback cb) = 20;
    /** Re-bind callback to an already-running update_engine session (re-attach after app restart). */
    String rebindCallback(IUpdateCallback cb) = 21;
    /** Name of the device-specific timer binary, e.g. "timer_PLG110". */
    String timerName() = 22;
}
