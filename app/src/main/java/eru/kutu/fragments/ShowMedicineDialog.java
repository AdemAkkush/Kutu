package eru.kutu.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import eru.kutu.R;
import eru.kutu.bluetooth.BluetoothControl;
import eru.kutu.roomdatabase.Medicine;
import eru.kutu.roomdatabase.MedicineDatabase;
import eru.kutu.utils.MedicineAdapter;
import eru.kutu.utils.MedicineOnClick;

public class ShowMedicineDialog extends DialogFragment {
    private RecyclerView recyclerView;
    private MedicineAdapter adapter;
    private List<Medicine> medicines;
    private MedicineDatabase localDatabase;
    private MedicineOnClick onClickListener;
    private AddMedicineCallBack callBack;

    public ShowMedicineDialog(@NonNull AddMedicineCallBack callBack) {
        this.callBack = callBack;
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
        return inflater.inflate(R.layout.show_medicines, container);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        recyclerView = view.findViewById(R.id.show_medicine_recycler);
        onClickListener = new MedicineOnClick() {
            @Override
            public void OnClickDelete(View view, int position) {
               deleteMed(medicines.get(position));
            }

            @Override
            public void OnClickSetAlarm(View view, int position) {
                if (BluetoothControl.getInstance().isConnected)
                    callBack.sendMedicine(medicines.get(position));

                else showError();
            }
        };

        init();
    }

    private void deleteMed(Medicine med){
        if(BluetoothControl.getInstance().isConnected){
            callBack.deleteMedicine(med);
            localDatabase.medicineDAO().delete(med);
        }
    }
    private void init() {
        localDatabase = MedicineDatabase.getINSTANCE(getContext());
        RecyclerView.LayoutManager layoutManager =
                new LinearLayoutManager(getContext(), RecyclerView.VERTICAL, false);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setItemAnimator(new DefaultItemAnimator());
        adapter = new MedicineAdapter(medicines, getContext());
        adapter.setClickListener(onClickListener);
        update();
        recyclerView.setAdapter(adapter);
    }

    private void update() {
        medicines=null;
        medicines = localDatabase.medicineDAO().getMedicines();
        if (medicines.isEmpty()) dismiss();
        adapter.setMedicines(medicines);
        recyclerView.setAdapter(adapter);


    }

    private void showError() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("İLAÇ KUTUSU")
                .setMessage("Bağlantı varlığından emin olunuz");

        AlertDialog dialog = builder.create();

        dialog.getWindow().getAttributes().windowAnimations = R.style.DialogAnimation;
        dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_back);
        dialog.show();
    }
}
