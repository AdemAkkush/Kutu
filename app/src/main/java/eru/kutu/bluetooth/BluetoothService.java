package eru.kutu.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import android.os.Handler;
import android.os.Message;

public class BluetoothService extends Thread {
    private static final UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final char DELIMITER = '\n';
    private String RX_BUFFER = "";


    private final String address;
    private BluetoothSocket socket;

    private OutputStream outStream;
    private InputStream inStream;

    private final Handler readHandler;
    private final Handler writeHandler;


    public BluetoothService(String address, Handler handler) {

        this.address = address;

        this.readHandler = handler;
        this.writeHandler = new Handler(msg -> {
            write((String) msg.obj);
            return false;
        });
    }

    public Handler getWriteHandler() {
        return writeHandler;
    }

    private void connect() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = adapter.getRemoteDevice(address);
            socket = device.createRfcommSocketToServiceRecord(uuid);
            adapter.cancelDiscovery();
            socket.connect();
            outStream = socket.getOutputStream();
            inStream = socket.getInputStream();
            sendToReadHandler(BluetoothStates.CONNECTED);
        } catch (IOException e) {
            sendToReadHandler(BluetoothStates.CONNECTION_FAILED);
        }
    }

    private void disconnect() {
        if (inStream != null) {
            try {
                inStream.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (outStream != null) {
            try {
                outStream.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (socket != null) {
            try {
                socket.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        sendToReadHandler(BluetoothStates.DISCONNECTED);
    }

    private void write(String s) {
        try {
            s += DELIMITER;
            outStream.write(s.getBytes());

        } catch (IOException e) {
            sendToReadHandler(BluetoothStates.DATA_WRITE_ERROR);
        }
    }

    private void sendToReadHandler(String s) {
        Message msg = Message.obtain();
        msg.obj = s;
        readHandler.sendMessage(msg);
    }

    private void parseMessages() {
        int indexofDelimeter = RX_BUFFER.indexOf(DELIMITER);
        if (indexofDelimeter == -1)
            return;

        String s = RX_BUFFER.substring(0, indexofDelimeter);
        sendToReadHandler(s);
        RX_BUFFER = RX_BUFFER.substring(indexofDelimeter + 1);

        parseMessages();
    }

    private String read() {
        String s = "";

        try {
            if (inStream.available() > 0) {
                byte[] inBuffer = new byte[1024];
                int bytesRead = inStream.read(inBuffer);
                s = new String(inBuffer, StandardCharsets.US_ASCII);
                s = s.substring(0, bytesRead);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return s;
    }

    @Override
    public void run() {
        connect();
        while (!this.isInterrupted()) {
            if ((inStream == null) || (outStream == null)) {
                break;
            }
            String data = read();
            if (data.length() > 0) {
                RX_BUFFER += data;
            }
            parseMessages();
        }
        disconnect();
    }
}
