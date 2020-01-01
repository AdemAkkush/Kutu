package eru.kutu.fragments;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import eru.kutu.R;
import eru.kutu.bluetooth.BluetoothControl;

public class SettingsDialog extends DialogFragment {
    private Switch btSwitch, connectSwitch;
    private ConnectRequest connectRequest;

    public SettingsDialog(ConnectRequest connectRequest) {
        this.connectRequest = connectRequest;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        getDialog().getWindow().getAttributes().windowAnimations = R.style.DialogAnimation;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.DialogStyle);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.settings_layout, container);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        btSwitch = view.findViewById(R.id.settings_bt_switch);
        if (BluetoothControl.getInstance().isBluetoothActive())
            btSwitch.setChecked(true);
        else
            btSwitch.setChecked(false);


        btSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                BluetoothControl.getInstance().openBluetooth();
            } else {
                BluetoothControl.getInstance().closeBluetooth();
                // connectRequest.disconnect(); //ihtiyaç olabilir
            }
        });

        connectSwitch = view.findViewById(R.id.settings_connect_box);
        if (BluetoothControl.getInstance().isConnected)
            connectSwitch.setChecked(true);
        else
            connectSwitch.setChecked(false);

        connectSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked)
                connectRequest.connect();
            else connectRequest.disconnect();
        });
    }
}
