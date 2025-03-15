package io.github.edufolly.flutterbluetoothserial;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.Arrays;
import java.util.function.Consumer;
import java.lang.reflect.Method;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

/// Universal Bluetooth serial connection class (for Java)
public class BluetoothConnectionClassic extends BluetoothConnectionBase
{
    private static final String TAG = "FlutterBluePlugin";
    protected static final UUID DEFAULT_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    protected BluetoothAdapter bluetoothAdapter;

    protected ConnectionThread connectionThread = null;

    public boolean isConnected() {
        return connectionThread != null && connectionThread.requestedClosing != true;
    }



    public BluetoothConnectionClassic(OnReadCallback onReadCallback, OnDisconnectedCallback onDisconnectedCallback, BluetoothAdapter bluetoothAdapter) {
        super(onReadCallback, onDisconnectedCallback);
        this.bluetoothAdapter = bluetoothAdapter;
    }



    // @TODO . `connect` could be done perfored on the other thread
    // @TODO . `connect` parameter: timeout
    // @TODO . `connect` other methods than `createRfcommSocketToServiceRecord`, including hidden one raw `createRfcommSocket` (on channel).
    // @TODO ? how about turning it into factoried?
    public void connect(String address, UUID uuid) throws IOException {
        if (isConnected()) {
            throw new IOException("already connected");
        }

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            throw new IOException("device not found");
        }

        BluetoothSocket socket = device.createRfcommSocketToServiceRecord(uuid); // @TODO . introduce ConnectionMethod
        if (socket == null) {
            throw new IOException("socket connection not established");
        }

        // Cancel discovery, even though we didn't start it
        bluetoothAdapter.cancelDiscovery();

        try {
            socket.connect();
        } catch (IOException e) {
            try {
                // Newer versions of android may require voodoo; see https://stackoverflow.com/a/25647197
                socket = (BluetoothSocket) device.getClass().getMethod("createRfcommSocket", new Class[] {int.class}).invoke(device,1);
                socket.connect();
            } catch (Exception e2) {
                throw new IOException("Failed to connect", e2);
            }
        }

        connectionThread = new ConnectionThread(socket);
        connectionThread.start();
    }

    public void connect(String address) throws IOException {
        connect(address, DEFAULT_UUID);
    }
    
    public void disconnect() {
        if (isConnected()) {
            connectionThread.cancel();
            connectionThread = null;
        }
    }

    public void write(byte[] data) throws IOException {
        if (!isConnected()) {
            throw new IOException("not connected");
        }

        connectionThread.write(data);
    }

    /// Thread to handle connection I/O
    private class ConnectionThread extends Thread  {
        private final BluetoothSocket socket;
        private final InputStream input;
        private final OutputStream output;
        private boolean requestedClosing = false;

        private final long KEEP_ALIVE_INTERVAL = 100; // milliseconds
        private final byte[] KEEP_ALIVE_SIGNAL = new byte[]{0x00};
        private Thread keepAliveThread;
        private volatile boolean keepAliveRunning = true;

        ConnectionThread(BluetoothSocket socket) {
            this.socket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                e.printStackTrace();
            }

            this.input = tmpIn;
            this.output = tmpOut;

            // Start keep-alive thread
            startKeepAliveThread();
        }

        private void startKeepAliveThread() {
            keepAliveThread = new Thread(() -> {
                while (keepAliveRunning && !requestedClosing) {
                    try {
                        Thread.sleep(KEEP_ALIVE_INTERVAL);
                        if (!requestedClosing) {
                            output.write(KEEP_ALIVE_SIGNAL);
                            output.flush();
                        }
                    } catch (Exception e) {
                        if (!requestedClosing) {
                            Log.e(TAG, "Keep-alive failed: " + e.getMessage());
                        }
                    }
                }
            });
            keepAliveThread.setDaemon(true);
            keepAliveThread.start();
        }

        /// Thread main code
        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;

            while (!requestedClosing) {
                try {
                    // Check if data is available first
                    if (input.available() > 0) {
                        bytes = input.read(buffer);
                        if (bytes > 0) {
                            onRead(Arrays.copyOf(buffer, bytes));
                        }
                    } else {
                        // Short sleep to prevent CPU hogging
                        try {
                            Thread.sleep(1); // Minimal sleep
                        } catch (InterruptedException ie) {
                            Log.e(TAG, "InterruptedException: " + ie.getMessage());
                        }
                    }
                } catch (IOException e) {
                    // `input.read` throws when closed by remote device
                    break;
                }
            }

            // Make sure output stream is closed
            if (output != null) {
                try {
                    output.close();
                }
                catch (Exception e) {}
            }

            // Make sure input stream is closed
            if (input != null) {
                try {
                    input.close();
                }
                catch (Exception e) {}
            }

            // Callback on disconnected, with information which side is closing
            onDisconnected(!requestedClosing);

            // Just prevent unnecessary `cancel`ing
            requestedClosing = true;
        }

        /// Writes to output stream
        public void write(byte[] bytes) {
            try {
                output.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        /// Stops the thread, disconnects
        public void cancel() {
            if (requestedClosing) {
                return;
            }

            // Stop keep-alive thread
            keepAliveRunning = false;
            if (keepAliveThread != null) {
                keepAliveThread.interrupt();
            }

            requestedClosing = true;

            // Flush output buffers before closing
            try {
                output.flush();
            }
            catch (Exception e) {}

            // Close the connection socket
            if (socket != null) {
                try {
                    // Might be useful (see https://stackoverflow.com/a/22769260/4880243)
                    Thread.sleep(111);

                    socket.close();
                }
                catch (Exception e) {}
            }
        }
    }
}
