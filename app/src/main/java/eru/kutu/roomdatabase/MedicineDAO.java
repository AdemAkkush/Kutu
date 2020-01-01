package eru.kutu.roomdatabase;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;


@Dao
public interface MedicineDAO {
    @Query("SELECT * FROM medicines")
    List<Medicine> getMedicines();

    @Query("SELECT * FROM medicines where id LIKE :id ")
    Medicine findById(String id);

    @Insert
    void insertAll(Medicine... medicines);

    @Delete
    void delete(Medicine medicine);
}
