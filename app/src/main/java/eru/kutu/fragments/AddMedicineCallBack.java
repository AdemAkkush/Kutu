package eru.kutu.fragments;

import androidx.annotation.NonNull;

import eru.kutu.roomdatabase.Medicine;

public interface AddMedicineCallBack {
    void sendMedicine(@NonNull Medicine medicine);
    void deleteMedicine(@NonNull Medicine medicine);
}
