package eru.kutu.utils;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import eru.kutu.R;
import eru.kutu.roomdatabase.Medicine;

public class MedicineAdapter extends RecyclerView.Adapter<MedicineAdapter.MedicineHolder> {
    List<Medicine> medicines;
    private Context context;
    private MedicineHolder holder;
    private MedicineOnClick clickListener;

    public MedicineAdapter(List<Medicine> medicines, Context context) {
        this.medicines = medicines;
        this.context = context;
    }

    public void setClickListener(MedicineOnClick clickListener) {
        this.clickListener = clickListener;
    }

    public void setMedicines(List<Medicine> medicines) {
        this.medicines = medicines;
    }

    @NonNull
    @Override
    public MedicineHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.single_medicine, parent, false);
        holder = new MedicineHolder(view);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull MedicineHolder holder, int position) {
        String medName, medHour, medMinute, medDose, medSection,medAlarmNo;
        Medicine medicine = medicines.get(position);

        medName = medicine.getName().toUpperCase();
        medHour = medicine.getHour().length() == 2 ? "SAAT " + medicine.getHour() : "SAAT " + "0" + medicine.getHour();
        medMinute = medicine.getMinute().length() == 2 ? medicine.getMinute() : "0" + medicine.getMinute();
        medDose = medicine.getDose() + " ADET";
        medSection = medicine.getSection() + ". BÖLME";
        medAlarmNo="ALARM NO: "+medicine.getAlarmNo();
        holder.name.setText(medName);
        holder.hour.setText(medHour);
        holder.minute.setText(medMinute);
        holder.section.setText(medSection);
        holder.alarmNo.setText(medAlarmNo);
        holder.dose.setText(medDose);
        holder.deleteBtn.setOnClickListener(v -> clickListener.OnClickDelete(v, position));
        holder.setAlarmBtn.setOnClickListener(v->clickListener.OnClickSetAlarm(v, position));

    }

    @Override
    public int getItemCount() {
        return medicines.size();
    }

     class MedicineHolder extends RecyclerView.ViewHolder {
        private TextView name, hour, minute, dose, section,alarmNo;
        private Button deleteBtn,setAlarmBtn;

        private MedicineHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.single_medicine_name);
            hour = itemView.findViewById(R.id.single_medicine_hour);
            minute = itemView.findViewById(R.id.single_medicine_minute);
            dose = itemView.findViewById(R.id.single_medicine_dose);
            section = itemView.findViewById(R.id.single_medicine_section);
            deleteBtn = itemView.findViewById(R.id.single_medicine_delete);
            setAlarmBtn=itemView.findViewById(R.id.single_medicine_send);
            alarmNo=itemView.findViewById(R.id.single_medicine_alarmNo);
        }
    }
}
