package com.bhlnk.ocb;

import android.os.IBinder;
import android.os.IUpdateEngine;
import android.os.IUpdateEngineCallback;
import android.os.IUpdateEngineStable;
import android.os.IUpdateEngineStableCallback;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Runs inside the process that Shizuku starts as the shell user (uid 2000,
 * u:r:shell:s0), which is what the exploit needs for tracefs, and which is the
 * right context to drive a local OTA via update_engine_client.
 */
public class UserService extends IUserService.Stub {

    private static final String TMP = "/data/local/tmp/";
    private static final String LOG = TMP + "timer.log";
    private static final String OTA_DIR = "/data/ota_package";
    private static final int MAX_OUTPUT = 20000;

    private Process rootProcess;
    private BufferedWriter rootInput;
    private final BlockingQueue<String> rootLines = new LinkedBlockingQueue<>();

    public UserService() {
    }

    public UserService(android.content.Context context) {
    }

    @Override
    public void destroy() {
        closeRootInternal();
        System.exit(0);
    }

    @Override
    public void exit() {
        destroy();
    }

    /** Returns the packaged GKI timer binary path. */
    private String timerBin() {
        return TMP + "timer_GKI";
    }

    @Override
    public String timerName() {
        return "timer_GKI";
    }

    @Override
    public String install(String apk) {
        try {
            Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c",
                    "rm -f " + TMP + "timer " + TMP + "timer_*"})
                    .waitFor(2, TimeUnit.SECONDS);
            try (ZipFile zf = new ZipFile(apk)) {
                String dest = TMP + "timer_GKI";
                String result = extract(zf, "assets/timer_GKI", dest);
                return result + "\n(uid=" + android.system.Os.getuid() + ')';
            }
        } catch (Throwable t) {
            return "install failed: " + t;
        }
    }

    /**
     * Extract one asset from the APK to {@code dest} atomically, mode 755.
     * ksud is 5 MB, so this streams instead of reading it into memory.
     */
    private String extract(ZipFile zf, String name, String dest) throws Exception {
        ZipEntry entry = zf.getEntry(name);
        if (entry == null) {
            return name + ": not found in apk";
        }
        String tmp = dest + ".new";
        try (InputStream in = zf.getInputStream(entry);
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        Process move = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c",
                "chmod 755 " + tmp + " && mv -f " + tmp + " " + dest});
        move.waitFor();
        return dest + " = " + new File(dest).length() + " bytes";
    }

    @Override
    public String run(String args) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "/system/bin/sh", "-c", timerBin() + " " + args + " 2>&1");
            pb.directory(new File("/data/local/tmp"));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String text = readAll(p.getInputStream());
            if (!p.waitFor(90, TimeUnit.SECONDS)) {
                return text + "\n[timeout] still running after 90s - do not kill it,"
                        + " reboot to clean up";
            }
            return text;
        } catch (Throwable t) {
            return "run failed: " + t;
        }
    }

    @Override
    public String runDetached(String args) {
        try {
            String cmd = "cd /data/local/tmp && setsid " + timerBin() + " " + args
                    + " >" + LOG + " 2>&1 </dev/null &";
            Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
            Thread.sleep(2000);
            return readLog();
        } catch (Throwable t) {
            return "start failed: " + t;
        }
    }

    @Override
    public String readLog() {
        try (FileInputStream in = new FileInputStream(LOG)) {
            return readAll(in);
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public String openRoot() {
        if (rootProcess != null && rootProcess.isAlive()) {
            return "root shell already open";
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(timerBin(), "shell");
            pb.directory(new File("/data/local/tmp"));
            pb.redirectErrorStream(true);
            rootProcess = pb.start();
            rootInput = new BufferedWriter(
                    new OutputStreamWriter(rootProcess.getOutputStream()));
            final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(rootProcess.getInputStream()));
            rootLines.clear();
            Thread pump = new Thread(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        rootLines.offer(line);
                    }
                } catch (IOException ignored) {
                }
            });
            pump.setDaemon(true);
            pump.start();
            Thread.sleep(6000);
            StringBuilder out = new StringBuilder();
            long deadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < deadline) {
                String line = rootLines.poll(100, TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                out.append(line).append('\n');
                if (line.contains("uid=0(root)")) {
                    break;
                }
            }
            return "root shell started\n" + out.toString().trim();
        } catch (Throwable t) {
            return "openRoot failed: " + t;
        }
    }

    @Override
    public String execRoot(String cmd) {
        if (rootProcess == null || !rootProcess.isAlive()) {
            return "root shell not open";
        }
        try {
            rootInput.write(cmd + " 2>&1\necho __END__\n");
            rootInput.flush();
            StringBuilder sb = new StringBuilder();
            long deadline = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < deadline) {
                String line = rootLines.poll(200, TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                if (line.equals("__END__")) {
                    break;
                }
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "execRoot failed: " + t;
        }
    }

    @Override
    public String closeRoot() {
        closeRootInternal();
        return "root shell closed";
    }

    /** SELinux / Seccomp / su_server, as shown on the home card. */
    @Override
    public String info() {
        String selinux = execOut("getenforce");
        String seccomp = execOut("grep -m1 Seccomp /proc/self/status | awk '{print $2}'");
        String suServer = new java.io.File("/data/local/tmp/su.sock").exists()
                ? "running" : "not running";
        return "selinux=" + selinux + "\nseccomp=" + seccomp + "\nsu_server=" + suServer + "\n";
    }

    @Override
    public String rebootDevice() {
        int rc = execCode("/system/bin/svc power reboot");
        if (rc != 0) rc = execCode("/system/bin/reboot");
        return rc == 0 ? "rebooting" : "reboot failed (rc=" + rc + ")";
    }

    // ---------------------------------------------------------------- OTA

    /** Run a shell command and return its output (with a timeout). */
    @Override
    public String execShell(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String text = readAll(p.getInputStream());
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroy();
                return text + "\n[timeout] command still running after 60s";
            }
            return text;
        } catch (Throwable t) {
            return "execShell failed: " + t;
        }
    }

    /** List candidate OTA files on storage, one path per line. */
    @Override
    public String listOtaFiles() {
        StringBuilder sb = new StringBuilder();
        String[] dirs = {"/sdcard/Download", "/sdcard", "/data/local/tmp"};
        for (String d : dirs) {
            sb.append(execOut("ls -1 '" + d + "'/*.bin '" + d + "'/*.zip '" + d
                    + "'/*payload*' 2>/dev/null"));
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    /** Current slot + OTA version + update_engine state. */
    @Override
    public String otaStatus() {
        String slot = execOut("getprop ro.boot.slot_suffix");
        String ota = execOut("getprop ro.build.version.ota");
        String display = execOut("getprop ro.build.display.ota");
        String ue = execOut("ps -A | grep -c '[u]pdate_engine'");
        return "slot=" + slot + "\nota=" + ota + "\ndisplay=" + display
                + "\nupdate_engine_procs=" + ue + "\n";
    }

    /**
     * Copy a payload.bin (or an A/B OTA .zip) into /data/ota_package and hand it
     * to update_engine_client. Returns the client output, including the status
     * code that tells us whether the payload signature was accepted.
     */
    @Override
    public String localUpdate(String path) {
        try {
            execOut("mkdir -p " + OTA_DIR + " && chmod 755 " + OTA_DIR);
            String dest = OTA_DIR + "/payload.bin";
            if (path != null && path.toLowerCase().endsWith(".zip")) {
                String extracted = extractZipPayload(path);
                if (!extracted.startsWith("ok")) {
                    return extracted;
                }
            } else {
                String cp = execOut("cp -f '" + path + "' " + dest
                        + " && chmod 644 " + dest + " && ls -l " + dest);
                if (cp.contains("No such file")) {
                    return "copy failed: " + cp;
                }
            }
            String headers = buildHeaders();
            String cmd = "update_engine_client --update --payload=file://"
                    + dest;
            if (!headers.isEmpty()) {
                cmd += " --headers=\"" + headers + "\"";
            }
            return execShell(cmd);
        } catch (Throwable t) {
            return "localUpdate failed: " + t;
        }
    }

    /** Safe signature/applicability check only (does not flash). */
    @Override
    public String verifyPayload(String path) {
        try {
            String dest = OTA_DIR + "/payload.bin";
            if (path != null && path.toLowerCase().endsWith(".zip")) {
                String extracted = extractZipPayload(path);
                if (!extracted.startsWith("ok")) {
                    return extracted;
                }
            } else {
                execOut("cp -f '" + path + "' " + dest + " && chmod 644 " + dest);
            }
            return execShell("update_engine_client --verify --metadata=" + dest);
        } catch (Throwable t) {
            return "verifyPayload failed: " + t;
        }
    }

    // ------------------------------------------------------------ helpers

    private String extractZipPayload(String zipPath) {
        try (ZipFile zf = new ZipFile(zipPath)) {
            String payloadEntry = null;
            java.util.Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry ze = e.nextElement();
                String n = ze.getName();
                if (n.endsWith("payload.bin") || n.equals("payload.bin")) {
                    payloadEntry = n;
                    break;
                }
            }
            if (payloadEntry == null) {
                return "no payload.bin inside " + zipPath;
            }
            try (InputStream in = zf.getInputStream(zf.getEntry(payloadEntry));
                 FileOutputStream out = new FileOutputStream(OTA_DIR + "/payload.bin")) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            ZipEntry pp = zf.getEntry("payload_properties.txt");
            if (pp != null) {
                try (InputStream in = zf.getInputStream(pp);
                     FileOutputStream out = new FileOutputStream(OTA_DIR
                             + "/payload_properties.txt")) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
            }
            return "ok extracted payload.bin from zip";
        } catch (Throwable t) {
            return "zip extract failed: " + t;
        }
    }

    private String buildHeaders() {
        File pp = new File(OTA_DIR + "/payload_properties.txt");
        if (!pp.exists()) {
            return "";
        }
        try {
            String raw = new String(java.nio.file.Files.readAllBytes(pp.toPath()));
            StringBuilder sb = new StringBuilder();
            for (String line : raw.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq).trim();
                    String val = line.substring(eq + 1).trim();
                    sb.append(key).append('=').append(val).append('\n');
                }
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private int execCode(String cmd) {
        try {
            Process p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .redirectErrorStream(true).start();
            return p.waitFor();
        } catch (Throwable t) {
            return -1;
        }
    }

    private String execOut(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String text = readAll(p.getInputStream()).trim();
            p.waitFor();
            return text;
        } catch (Throwable t) {
            return "";
        }
    }

    private void closeRootInternal() {
        try {
            if (rootInput != null) {
                rootInput.write("exit\n");
                rootInput.flush();
            }
        } catch (IOException ignored) {
        }
        if (rootProcess != null) {
            rootProcess.destroy();
            rootProcess = null;
        }
        rootInput = null;
    }

    // ---------------------------------------------------------------- UpdateEngine binder

    private IUpdateEngineStable ueEngine;
    private IUpdateEngineStableCallback ueCb;
    private static final String APPLY_LOG = "/data/local/tmp/apply.log";

    private IUpdateEngine getUpdateEngine() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        java.lang.reflect.Method getService = sm.getMethod("getService", String.class);
        IBinder b = null;
        for (String name : new String[]{
                "android.os.UpdateEngineService",
                "update_engine"}) {
            b = (IBinder) getService.invoke(null, name);
            if (b != null) break;
        }
        if (b == null) throw new Exception("update_engine service not found");
        return IUpdateEngine.Stub.asInterface(b);
    }

    private IUpdateEngineStable getEngine() throws Exception {
        if (ueEngine != null && ueEngine.asBinder().isBinderAlive()) return ueEngine;
        // ServiceManager is hidden — use reflection.
        // OPPO/Realme registers the service under the full descriptor name instead of
        // the short "update_engine_stable" name used by AOSP.
        Class<?> sm = Class.forName("android.os.ServiceManager");
        java.lang.reflect.Method getService = sm.getMethod("getService", String.class);
        IBinder b = null;
        for (String name : new String[]{
                "update_engine_stable",
                "android.os.UpdateEngineStableService",
                "android.os.UpdateEngineService"}) {
            b = (IBinder) getService.invoke(null, name);
            if (b != null) break;
        }
        if (b == null) throw new Exception("update_engine_stable service not found");
        ueEngine = IUpdateEngineStable.Stub.asInterface(b);
        return ueEngine;
    }

    @Override
    public String applyPayloadDirect(String payloadPath, String[] headers) {
        try {
            IUpdateEngineStable engine = getEngine();
            File pf = new File(payloadPath);
            if (!pf.exists()) return "payload not found: " + payloadPath;

            // Parse offset and size from headers
            long offset = 0, size = 0;
            for (String h : headers) {
                if (h.startsWith("FILE_SIZE=")) {
                    try { size = Long.parseLong(h.substring(10).trim()); } catch (Exception ignored) {}
                }
            }

            // Clear log
            new File(APPLY_LOG).delete();

            // Register callback that writes progress to apply.log
            IUpdateEngineStableCallback cb = new IUpdateEngineStableCallback.Stub() {
                @Override
                public void onStatusUpdate(int status, float fraction) {
                    writeLog("[ue] status=" + status + " progress=" + String.format("%.1f%%", fraction * 100));
                }
                @Override
                public void onPayloadApplicationComplete(int errorCode) {
                    writeLog("[ue] complete errorCode=" + errorCode
                            + (errorCode == 0 ? " UPDATED_NEED_REBOOT" : " FAILED"));
                    try (FileOutputStream f = new FileOutputStream(APPLY_LOG + ".rc", true)) {
                        f.write(("RC=" + errorCode + "\n").getBytes());
                    } catch (Exception ignored) {}
                }
                private void writeLog(String line) {
                    try (FileOutputStream f = new FileOutputStream(APPLY_LOG, true)) {
                        f.write((line + "\n").getBytes());
                    } catch (Exception ignored) {}
                }
            };

            // replace any callback from a previous run so progress isn't doubled
            if (ueCb != null) {
                try { engine.unbind(ueCb); } catch (Throwable ignored) {}
            }
            ueCb = cb;
            engine.bind(cb);

            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(pf, ParcelFileDescriptor.MODE_READ_ONLY);
            engine.applyPayloadFd(pfd, offset, size, headers);
            pfd.close();
            return "ok — applying via binder";
        } catch (Throwable t) {
            return "applyPayloadDirect failed: " + t;
        }
    }

    @Override
    public String startUpdate(String payloadPath, long payloadOffset, long payloadSize,
                               String[] headers, IUpdateCallback cb) {
        try {
            IUpdateEngineStable engine = getEngine();
            File pf = new File(payloadPath);
            if (!pf.exists()) return "payload not found: " + payloadPath;

            if (ueCb != null) {
                try { engine.unbind(ueCb); } catch (Throwable ignored) {}
            }
            ueCb = new IUpdateEngineStableCallback.Stub() {
                @Override
                public void onStatusUpdate(int status, float fraction) {
                    try { cb.onStatus(status, Math.round(fraction * 100)); } catch (Throwable ignored) {}
                }
                @Override
                public void onPayloadApplicationComplete(int errorCode) {
                    try { cb.onComplete(errorCode); } catch (Throwable ignored) {}
                    ueCb = null;
                }
            };
            engine.bind(ueCb);
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(pf, ParcelFileDescriptor.MODE_READ_ONLY);
            engine.applyPayloadFd(pfd, payloadOffset, payloadSize, headers);
            pfd.close();
            return "";
        } catch (Throwable t) {
            return "startUpdate failed: " + t;
        }
    }

    @Override
    public String rebindCallback(IUpdateCallback cb) {
        try {
            IUpdateEngineStable engine = getEngine();
            if (ueCb != null) {
                try { engine.unbind(ueCb); } catch (Throwable ignored) {}
            }
            if (cb == null) { ueCb = null; return ""; }
            ueCb = new IUpdateEngineStableCallback.Stub() {
                @Override
                public void onStatusUpdate(int status, float fraction) {
                    try { cb.onStatus(status, Math.round(fraction * 100)); } catch (Throwable ignored) {}
                }
                @Override
                public void onPayloadApplicationComplete(int errorCode) {
                    try { cb.onComplete(errorCode); } catch (Throwable ignored) {}
                    ueCb = null;
                }
            };
            engine.bind(ueCb);
            return "";
        } catch (Throwable t) {
            return "rebindCallback failed: " + t;
        }
    }

    @Override
    public String cancelUpdate() {
        try {
            getUpdateEngine().cancel();
            if (ueCb != null) {
                try { getEngine().unbind(ueCb); } catch (Throwable ignored) {}
                ueCb = null;
            }
            try { getUpdateEngine().resetStatus(); } catch (Throwable ignored) {}
            return "cancelled";
        } catch (Throwable t) {
            return "cancel failed: " + t;
        }
    }

    @Override
    public String resetStatus() {
        try {
            getUpdateEngine().resetStatus();
            return "reset";
        } catch (Throwable t) {
            return "reset failed: " + t;
        }
    }

    @Override
    public int getEngineStatus() {
        try {
            final int[] result = {-1};
            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
            IUpdateEngineCallback probeCb = new IUpdateEngineCallback.Stub() {
                @Override public void onStatusUpdate(int status, float pct) {
                    result[0] = status;
                    latch.countDown();
                }
                @Override public void onPayloadApplicationComplete(int err) {
                    latch.countDown();
                }
            };
            getUpdateEngine().bind(probeCb);
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS);
            try { getUpdateEngine().unbind(probeCb); } catch (Throwable ignored) {}
            return result[0];
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            total += n;
            if (total > MAX_OUTPUT) {
                break;
            }
        }
        return bos.toString("UTF-8");
    }
}
