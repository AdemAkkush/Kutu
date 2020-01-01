package eru.kutu;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.FragmentTransaction;

import android.app.Dialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import eru.kutu.bluetooth.BluetoothControl;
import eru.kutu.bluetooth.BluetoothService;
import eru.kutu.fragments.AddMedicineCallBack;
import eru.kutu.fragments.ConnectRequest;
import eru.kutu.fragments.MedicineDialog;
import eru.kutu.fragments.SettingsDialog;
import eru.kutu.fragments.ShowMedicineDialog;
import eru.kutu.bluetooth.BluetoothStates;
import eru.kutu.fragments.TutorialDialog;
import eru.kutu.roomdatabase.Medicine;
import eru.kutu.roomdatabase.MedicineDatabase;

public class MainActivity extends AppCompatActivity implements AddMedicineCallBack {
    private CardView addMedicine, showMedicine, tutorial, settings;
    private BluetoothService bluetoothService;
    private Handler writeHandler;
    private Handler readHandler;
    private static final String ADDRESS = "00:13:EF:00:B0:16";
    MedicineDatabase database;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        addMedicine = findViewById(R.id.main_addMedicine);
        showMedicine = findViewById(R.id.main_showMedicine);
        tutorial = findViewById(R.id.main_tutorial);
        settings = findViewById(R.id.main_settings);
        initObjects();
        initOnClicks();
        if (!BluetoothControl.getInstance().isBluetoothSupporting()) {
            showErrorDialog("Telefonunuz bluetooth desteklemiyor");
        }
    }

    private void initObjects() {
        readHandler = new Handler(msg -> {
            switch ((String) msg.obj) {
                case BluetoothStates.CONNECTED: {
                    showToast("Kutu ile bağlantı sağlandı\nAlarmlarınızı oluşturabilirsiniz");
                    BluetoothControl.getInstance().isConnected = true;
                }break;
                case BluetoothStates.CONNECTION_FAILED: {
                    BluetoothControl.getInstance().isConnected = false;
                    showErrorDialog("Bağlantı başarışız");
                }break;
                case BluetoothStates.DISCONNECTED: {
                    showToast("Bağlantı Kapatıldı");
                    BluetoothControl.getInstance().isConnected = false;
                }break;
                case BluetoothStates.DATA_WRITE_ERROR: {
                    showToast("Hata! Bağlantı varlığından emin olunuz");
                }break;
            }
            return false;
        });

        bluetoothService = new BluetoothService(ADDRESS, readHandler);
        writeHandler = bluetoothService.getWriteHandler();

        database = MedicineDatabase.getINSTANCE(MainActivity.this);
    }

    private void initOnClicks() {
        addMedicine.setOnClickListener(view -> {//ilaç ekle
            if (BluetoothControl.getInstance().isConnected) {
                FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
                MedicineDialog dialog = new MedicineDialog(MainActivity.this);
                dialog.show(fragmentTransaction, "ADD");
            } else {
                showToast("Hata! Bağlantı varlığından emin olunuz");
                showErrorDialog("Bağlantı başarışız");
            }
        });

        showMedicine.setOnClickListener(view -> {//İlaçları göster
            if (database.medicineDAO().getMedicines().isEmpty()) {
                showErrorDialog("Kayıtlı İlaç Bulunamadı");
            } else {
                ShowMedicineDialog dialog = new ShowMedicineDialog(MainActivity.this);
                dialog.show(getSupportFragmentManager(), "SHOW");
            }
        });

        tutorial.setOnClickListener(view -> {//Kılavuz
            TutorialDialog dialog = new TutorialDialog();
            dialog.show(getSupportFragmentManager(), "TUTORIAL");
        });

        settings.setOnClickListener(view -> {//Ayarlar
            SettingsDialog dialog = new SettingsDialog(new ConnectRequest() {
                @Override
                public void connect() {
                    if (!BluetoothControl.getInstance().isConnected){
                        if(bluetoothService==null) bluetoothService= new BluetoothService(ADDRESS, readHandler);
                        bluetoothService.start();
                    }

                }
                @Override
                public void disconnect() {
                    if (BluetoothControl.getInstance().isConnected)
                        bluetoothService.interrupt();
                }
            });
            dialog.show(getSupportFragmentManager(), "SETTINGS");
        });

    }

    private void showErrorDialog(String msg) {
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setTitle("İLAÇ KUTUSU")
                .setMessage(msg);
        Dialog dialog = builder.create();
        dialog.getWindow().getAttributes().windowAnimations = R.style.DialogAnimation;
        dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_back);
        dialog.show();
    }

    private void showToast(String msg) {
        Toast toast = Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG);
        toast.getView().setBackgroundResource(R.drawable.button_background);
        toast.getView().setElevation(4f);
        toast.show();
    }

    @Override
    public void sendMedicine(@NonNull Medicine medicine) {
        String data = "", hour = medicine.getHour(), minute = medicine.getMinute();

        if (Integer.parseInt(medicine.getHour()) < 10)
            hour = "0" + hour;

        if (Integer.parseInt(medicine.getMinute()) < 10)
            minute = "0" + minute;

        data = "e" + hour + minute + medicine.getSection() + medicine.getAlarmNo();
        Message message = Message.obtain();
        message.obj = data;
        Log.d("BLUETOOTH_DATA", data);
        writeHandler.sendMessage(message);
    }

    @Override
    public void deleteMedicine(@NonNull Medicine medicine) {
        String data = "";
        data = "s" + medicine.getSection() + medicine.getAlarmNo();
        Message message = Message.obtain();
        message.obj = data;
        Log.d("BLUETOOTH_DATA", data);
        writeHandler.sendMessage(message);

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bluetoothService.interrupt();
    }
}
