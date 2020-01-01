package eru.kutu.roomdatabase;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "medicines")
public class Medicine {
    @PrimaryKey
    @NonNull
    private String id;

    @ColumnInfo(name = "name")
    private String name;

    @ColumnInfo(name = "hour")
    private String hour;

    @ColumnInfo(name = "minute")
    private String minute;

    @ColumnInfo(name = "section")
    private String section;

    @ColumnInfo(name= "alarmNo")
    private String alarmNo;

    @ColumnInfo(name =  "dose")
    private String dose;

    public Medicine(String name, String hour, String minute, String section,String alarmNo, String dose) {
        setName(name);
        setHour(hour);
        setMinute(minute);
        setDose(dose);
        setSection(section);
        setAlarmNo(alarmNo);//
        setId(hour+minute+section+alarmNo);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHour() {
        return hour;
    }

    public void setHour(String hour) {
        this.hour = hour;
    }

    public String getMinute() {
        return minute;
    }

    public void setMinute(String minute) {
        this.minute = minute;
    }

    public String getSection() {
        return section;
    }

    public void setSection(String section) {
        this.section = section;
    }

    public String getDose() {
        return dose;
    }

    public void setDose(String dose) {
        this.dose = dose;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public String getAlarmNo() {
        return alarmNo;
    }

    public void setAlarmNo(String alarmNo) {
        this.alarmNo = alarmNo;
    }
}
