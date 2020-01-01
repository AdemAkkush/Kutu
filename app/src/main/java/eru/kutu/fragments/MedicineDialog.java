package eru.kutu.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import java.util.Calendar;

import eru.kutu.R;
import eru.kutu.roomdatabase.Medicine;
import eru.kutu.roomdatabase.MedicineDatabase;

public class MedicineDialog extends DialogFragment {
    private EditText medicineName, medicineDose;
    private RadioButton section1, section2;
    private RadioButton alarm1, alarm2;
    private Button createBtn, closeDialog;
    private TextView medTimeTV;
    private AddMedicineCallBack callBack;
    private Medicine med;
    private int hour = 0, minute = 0;
    private MedicineDatabase database;

    public MedicineDialog(AddMedicineCallBack callBack) {
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
        return inflater.inflate(R.layout.medicine_dialog, container);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        medicineName = view.findViewById(R.id.medicine_name);
        medicineDose = view.findViewById(R.id.medicine_dose);
        createBtn = view.findViewById(R.id.medicine_create);
        section1 = view.findViewById(R.id.section_1);
        section2 = view.findViewById(R.id.section_2);
        alarm1 = view.findViewById(R.id.alarm1);
        alarm2 = view.findViewById(R.id.alarm2);
        medTimeTV = view.findViewById(R.id.med_timeText);
        closeDialog = view.findViewById(R.id.kapatBtn);
        database = MedicineDatabase.getINSTANCE(getContext());
        setStyle(STYLE_NO_TITLE, R.style.AppTheme);
        init();
    }

    private TimePickerDialog.OnTimeSetListener timeSetListener = (view, hourOfDay, minute) -> {
        this.hour = hourOfDay;
        this.minute = minute;
        this.medTimeTV.setText(getTimeString(hourOfDay, minute));
    };

    private String getTimeString(int hour, int minute) {
        String s;

        if (hour < 10) s = "0" + hour;
        else s = "" + hour;
        if (minute < 10) s = s + ":0" + minute;
        else s = s + ":" + minute;

        return s;
    }

    private void init() {
        Calendar calendar = Calendar.getInstance();
        this.hour = calendar.get(Calendar.HOUR_OF_DAY);
        this.minute = calendar.get(Calendar.MINUTE);

        medTimeTV.setText(getTimeString(this.hour, this.minute));
        medTimeTV.setOnClickListener(view -> {
            TimePickerDialog dialog = new TimePickerDialog(
                    this.getContext(),
                    this.timeSetListener,
                    this.hour,
                    this.minute,
                    true);
            dialog.show();
        });

        closeDialog.setOnClickListener(view -> dismiss());
        createBtn.setOnClickListener(view -> {
            String name = medicineName.getText().toString();
            String dose = medicineDose.getText().toString();
            int section = section1.isChecked() ? 1 : section2.isChecked() ? 2 : 3;
            int alarmNo = alarm1.isChecked() ? 1 : alarm2.isChecked() ? 2 : 3;
            med = new Medicine(
                    name,
                    String.valueOf(hour),
                    String.valueOf(minute),
                    String.valueOf(section),
                    String.valueOf(alarmNo),
                    dose);
            if (checkValid(med)) {
                database.medicineDAO().insertAll(med);
                callBack.sendMedicine(med);
                dismiss();
            } else {
                showError();
            }
        });


    }

    private boolean checkValid(Medicine medicine) {
        return !medicine.getDose().isEmpty() && !medicine.getName().isEmpty();
    }

    private void showError() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("İLAÇ KUTUSU")
                .setMessage("Tüm alanları doldurduğunuzdan emin olunuz");

        AlertDialog dialog = builder.create();

        dialog.getWindow().getAttributes().windowAnimations = R.style.DialogAnimation;
        dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_back);
        dialog.show();
    }
}
