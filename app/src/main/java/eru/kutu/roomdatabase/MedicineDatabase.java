package eru.kutu.roomdatabase;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {Medicine.class}, version = 1,exportSchema = false)
public abstract class MedicineDatabase extends RoomDatabase {
    private static MedicineDatabase INSTANCE;
    public abstract MedicineDAO medicineDAO();

    public static MedicineDatabase getINSTANCE(Context context){
        if (INSTANCE==null){
            INSTANCE= Room.databaseBuilder(context.getApplicationContext(), MedicineDatabase.class, "medicines")
                    .allowMainThreadQueries()
                    .build();
        }
        return INSTANCE;
    }
    public static void disposeINSTANCE(){
        INSTANCE=null;
    }

}
