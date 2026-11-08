package com.bhlnk.ocb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Client for the PC-side "adb bridge": connects to a small server running on
 * the PC (which itself drives `adb`), sends commands, receives output. This is
 * the "combine with adb on the PC" transport — an alternative to Shizuku.
 *
 * Framed protocol: 4-byte big-endian length prefix + UTF-8 payload, both ways.
 * Command strings: "shell <cmd>", "push_timer", "push_ksud", "devices".
 */
public class AdbBridge {

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private boolean connected;

    public boolean isConnected() {
        return connected && socket != null && socket.isConnected();
    }

    /** Synchronized so a reconnect can never swap the socket mid-frame of a send(). */
    public synchronized String connect(String host, int port) {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 8000);
            socket.setTcpNoDelay(true);
            // Long enough for the timer exploit (~20s) and payload extraction.
            socket.setSoTimeout(300000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
            connected = true;
            return "ok";
        } catch (IOException e) {
            return "connect failed: " + e.getMessage();
        }
    }

    /** Send a command and receive the response (blocking, serialized). */
    public synchronized String send(String command) {
        if (!isConnected()) {
            return "not connected";
        }
        try {
            byte[] payload = command.getBytes("UTF-8");
            writeFrame(payload);
            byte[] resp = readFrame();
            return new String(resp, "UTF-8");
        } catch (Throwable t) {
            connected = false;
            return "bridge error: " + t;
        }
    }

    /** Run a shell command on the device via the PC's adb. */
    public String exec(String shellCmd) {
        return send("shell " + shellCmd);
    }

    /**
     * Ask the PC to push both privileged helpers (timer + ksud).
     * ksud is what provides `su` after the KernelSU module is late-loaded,
     * so a separate manager APK is not needed.
     */
    public String install() {
        String model = exec("getprop ro.product.name").trim();
        String timer = send(model.isEmpty() ? "push_timer" : "push_timer " + model);
        String ksud = send("push_ksud");
        return timer + "\n" + ksud;
    }


    /** Ask the PC for its adb device list. */
    public String devices() {
        return send("devices");
    }

    /**
     * Stream a shell command line by line.
     * Sends "shell_stream <cmd>"; bridge sends one frame per output line,
     * then a final "__STREAM_DONE__ <rc>" frame.
     * onLine is called for each line. Returns the exit code, or -1 on error.
     * Synchronized for the full duration — concurrent send() calls block until streaming ends.
     */
    public synchronized int streamShell(String cmd, java.util.function.Consumer<String> onLine) {
        if (!isConnected()) return -1;
        try {
            byte[] req = ("shell_stream " + cmd).getBytes("UTF-8");
            writeFrame(req);
            while (true) {
                byte[] frame = readFrame();
                String line = new String(frame, "UTF-8");
                if (line.startsWith("__STREAM_DONE__ ")) {
                    try { return Integer.parseInt(line.substring(16).trim()); }
                    catch (NumberFormatException ignored) { return 0; }
                }
                onLine.accept(line);
            }
        } catch (Throwable t) {
            connected = false;
            return -1;
        }
    }

    public synchronized void disconnect() {
        connected = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Throwable ignored) {
        }
        socket = null;
        in = null;
        out = null;
    }

    // ------------------------------------------------------------ framing

    private void writeFrame(byte[] payload) throws IOException {
        out.write(new byte[]{
                (byte) (payload.length >>> 24),
                (byte) (payload.length >>> 16),
                (byte) (payload.length >>> 8),
                (byte) payload.length});
        out.write(payload);
        out.flush();
    }

    private byte[] readFrame() throws IOException {
        byte[] hdr = new byte[4];
        readFully(hdr);
        int len = ((hdr[0] & 0xff) << 24) | ((hdr[1] & 0xff) << 16)
                | ((hdr[2] & 0xff) << 8) | (hdr[3] & 0xff);
        if (len < 0 || len > 64 * 1024 * 1024) {
            throw new IOException("bad frame length " + len);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int remaining = len;
        while (remaining > 0) {
            int n = in.read(buf, 0, Math.min(buf.length, remaining));
            if (n < 0) {
                throw new IOException("eof in frame");
            }
            bos.write(buf, 0, n);
            remaining -= n;
        }
        return bos.toByteArray();
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new IOException("eof");
            }
            off += n;
        }
    }
}
