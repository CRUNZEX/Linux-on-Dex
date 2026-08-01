package com.termux.terminal;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * A Termux terminal session backed by an external byte transport.
 *
 * <p>The upstream class couples {@link TerminalEmulator} to a subprocess and
 * PTY. This API-compatible variant keeps {@link com.termux.view.TerminalView}
 * unchanged while allowing the emulator to consume a QEMU serial stream.</p>
 */
public final class TerminalSession extends TerminalOutput {

    public final String mHandle = UUID.randomUUID().toString();

    private final ExternalTerminalTransport mTransport;
    private final Integer mTranscriptRows;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ByteArrayOutputStream mPendingOutput = new ByteArrayOutputStream();

    private TerminalSessionClient mClient;
    private TerminalEmulator mEmulator;

    public String mSessionName;

    public TerminalSession(
        ExternalTerminalTransport transport,
        Integer transcriptRows,
        TerminalSessionClient client
    ) {
        mTransport = transport;
        mTranscriptRows = transcriptRows;
        mClient = client;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null) mEmulator.updateTerminalSessionClient(client);
    }

    /** Initializes or resizes the emulator without opening a local PTY. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            mEmulator = new TerminalEmulator(
                this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient
            );
            byte[] pending = mPendingOutput.toByteArray();
            mPendingOutput.reset();
            if (pending.length > 0) {
                mEmulator.append(pending, pending.length);
                mClient.onTextChanged(this);
            }
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
        mTransport.onSizeChanged(columns, rows);
    }

    /** Feeds process output into Termux on its required main/UI thread. */
    public void appendOutput(byte[] data, int offset, int count) {
        if (count <= 0) return;
        byte[] copy = Arrays.copyOfRange(data, offset, offset + count);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            appendOnMain(copy);
        } else {
            mMainHandler.post(() -> appendOnMain(copy));
        }
    }

    private void appendOnMain(byte[] data) {
        if (mEmulator == null) {
            mPendingOutput.write(data, 0, data.length);
            return;
        }
        mEmulator.append(data, data.length);
        mClient.onTextChanged(this);
    }

    @Override
    public void write(byte[] data, int offset, int count) {
        mTransport.write(data, offset, count);
    }

    /** Writes one Unicode code point using the same encoding as upstream. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (!Character.isValidCodePoint(codePoint) ||
            (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) {
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }
        String value = new String(Character.toChars(codePoint));
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (prependEscape) write(new byte[]{0x1b}, 0, 1);
        write(encoded, 0, encoded.length);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    public String getTitle() {
        return mEmulator == null ? null : mEmulator.getTitle();
    }

    /** Clears terminal state, visible rows, and transcript for a new VM boot. */
    public void reset() {
        mPendingOutput.reset();
        if (mEmulator == null) return;
        byte[] leaveAlternateScreen = "\033[?1049l".getBytes(StandardCharsets.US_ASCII);
        mEmulator.append(leaveAlternateScreen, leaveAlternateScreen.length);
        mEmulator.reset();
        byte[] clearScreen = "\033[2J\033[H".getBytes(StandardCharsets.US_ASCII);
        mEmulator.append(clearScreen, clearScreen.length);
        mEmulator.getScreen().clearTranscript();
        mClient.onTextChanged(this);
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }
}
