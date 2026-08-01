package com.termux.terminal;

/**
 * The byte transport behind a Termux terminal whose process is owned outside
 * the emulator library (for Linux on DeX, a QEMU serial console).
 */
public interface ExternalTerminalTransport {

    /** Writes terminal input or an emulator response to the remote process. */
    void write(byte[] data, int offset, int count);

    /** Reports the grid measured by TerminalView. */
    void onSizeChanged(int columns, int rows);
}
