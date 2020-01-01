package eru.kutu.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

public class BluetoothControl {
    private BluetoothAdapter adapter;
    public boolean isConnected=false;
    private static BluetoothControl instance;
    private BluetoothControl(){
        adapter=BluetoothAdapter.getDefaultAdapter();
    }

    public boolean isBluetoothSupporting(){
        return adapter != null;
    }

    public boolean isBluetoothActive(){
        return adapter.isEnabled();
    }

    public void openBluetooth(){
        if(!isBluetoothActive())
            adapter.enable();
    }

    public void closeBluetooth(){
        if(isBluetoothActive())
            adapter.disable();
    }

    public static BluetoothControl getInstance(){
        if(instance==null)
            instance=new BluetoothControl();

        return instance;
    }
}
