package com.bhlnk.ocb;

/** Callback interface for direct update_engine progress — fires in the caller's process. */
oneway interface IUpdateCallback {
    void onStatus(int status, int pct);
    void onComplete(int errorCode);
}
