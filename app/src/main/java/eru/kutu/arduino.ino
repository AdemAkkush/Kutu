#include <Arduino.h>
#include <Wire.h>
#include <SoftwareSerial.h>


#include "RTC.h"
#include "LiquidCrystalNEW.h"

 /* 3D Robotic Laboratory: www.3drobolab.com */
String data;//okunan verileri tutacak
String hour;
String minute;
char section;
char alarmNo;
int ALARM_NOT_SET=-1;
char command;//silme ya da ekleme için okunacak komut
double angle_rad = PI/180.0;
double angle_deg = 180.0/PI;
double a1s1;
double a1s2;
double a1s3;
double a1d1;
double a1d2;
double a1d3;
double a2s1;
double a2s2;
double a2s3;
double a2d1;
double a2d2;
double a2d3;
double a3s1;
double a3s2;
double a3s3;
double a3d1;
double a3d2;
double a3d3;
double M;
double z;
double a1s;
double a1d;
double a2s;
double a2d;
double a3s;
double a3d;
double aa;
double aab;
double __var__49_49_49_49_49_49;
double __var__50_50_50_50_50_50;
double ababa;
double aax;
double b;
double v;
double m;
double n;
double __var__49_98;
double __var__49_97_97;
double __var__49_109;
double __var__49_110;
double __var__50_98;
double __var__50_120;
double __var__50_109;
double __var__50_110;

LiquidCrystal lcd(12, 11, 5, 4, 3, 2);
RTC rtc(DST_ON);
int dateTime_0, dateTime_1, dateTime_2, dateTime_3, dateTime_4, dateTime_5;



void setup(){
Serial.begin(9600);
     lcd.begin(16, 2);
     lcd.display();
     lcd.clear();
     data="";
     hour="";
     minute="";
     command='';
     section='';
     alarmNo='';
    a1s1 = ALARM_NOT_SET;
    a1s2 = ALARM_NOT_SET;
    a1s3 = ALARM_NOT_SET;
    a1d1 = ALARM_NOT_SET;
    a1d2 = ALARM_NOT_SET;
    a1d3 = ALARM_NOT_SET;
    a2s1 = ALARM_NOT_SET;
    a2s2 = ALARM_NOT_SET;
    a2s3 = ALARM_NOT_SET;
    a2d1 = ALARM_NOT_SET;
    a2d2 = ALARM_NOT_SET;
    a2d3 = ALARM_NOT_SET;
    a3s1 = ALARM_NOT_SET;
    a3s2 = ALARM_NOT_SET;
    a3s3 = ALARM_NOT_SET;
    a3d1 = ALARM_NOT_SET;
    a3d2 = ALARM_NOT_SET;
    a3d3 = ALARM_NOT_SET;
    M = 0;

    pinMode(6,OUTPUT);
    pinMode(10,INPUT);
    pinMode(9,OUTPUT);
}

void loop(){

    if((z) > (3)){
        z = 1;
    }
    z += 1;
    if(((z)==(2))){
        a1s = a1s1;
        a1d = a1d1;
        a2s = a2s1;
        a2d = a2d1;
        a3s = a3s1;
        a3d = a3d1;
    }
    if(((z)==(3))){
        a1s = a1s2;
        a1d = a1d2;
        a2s = a2s2;
        a2d = a2d2;
        a3s = a3s2;
        a3d = a3d2;
    }
    if(((z)==(4))){
        a1s = a1s3;
        a1d = a1d3;
        a2s = a2s3;
        a2d = a2d3;
        a3s = a3s3;
        a3d = a3d3;
    }
    _delay(0.3);
     lcd.clear();
    if((((((aa)==(1))) && (((aab)==(1)))) || ((((__var__49_49_49_49_49_49)==(1))) && (((__var__50_50_50_50_50_50)==(1))))) || ((((ababa)==(1))) && (((aax)==(1))))){
        if((((__var__49_49_49_49_49_49)==(1))) && (((__var__50_50_50_50_50_50)==(1)))){
             lcd.setCursor(1-1, 2-1);
             lcd.print("1.ILACINI IC");
            tone(6,262,500); // write to buzzer
            delay(500);
        }
        if((((aa)==(1))) && (((aab)==(1)))){
             lcd.setCursor(1-1, 2-1);
             lcd.print("2.ILACINI IC");
            tone(6,262,500); // write to buzzer
            delay(500);
        }
        if((((ababa)==(1))) && (((aax)==(1)))){
             lcd.setCursor(1-1, 2-1);
             lcd.print("3.ILACINI IC");
            tone(6,262,500); // write to buzzer
            delay(500);
        }
    }else{
         lcd.setCursor(1-1, 2-1);
         lcd.clear();
        if(((digitalRead(10))==(1))){
            digitalWrite(9,0);
            _delay(59);
        }
    }
     lcd.setCursor(1-1, 1-1);
     lcd.print(dateTime_3);
     lcd.setCursor(3-1, 1-1);
     lcd.print(":");
     lcd.setCursor(4-1, 1-1);
     lcd.print(dateTime_4);
     lcd.setCursor(7-1, 1-1);
     lcd.print(dateTime_2);
     lcd.print("/");
     lcd.print(dateTime_1);
     lcd.print("/");
     lcd.print(dateTime_0);
    if(((dateTime_3)==(a1s))){
        b += 1;
    }
    if(((b)==(1))){
        v = 1;
    }
    if(((dateTime_3)==((a1s) + (1)))){
        b = 0;
    }
    if(((digitalRead(10))==(1))){
        v = 0;
    }
    if(((dateTime_4)==(a1d))){
        m += 1;
    }
    if(((m)==(1))){
        n = 1;
    }
    if(((dateTime_4)==((a1d) + (1)))){
        m = 0;
    }
    if(((digitalRead(10))==(1))){
        n = 0;
    }
    if(((v)==(1))){
        __var__49_49_49_49_49_49 = 1;
    }
    if(((n)==(1))){
        __var__50_50_50_50_50_50 = 1;
    }
    if(((v)==(0))){
        __var__49_49_49_49_49_49 = 0;
    }
    if(((n)==(0))){
        __var__50_50_50_50_50_50 = 0;
    }
    if(((dateTime_3)==(a2s))){
        __var__49_97_97 += 1;
    }
    if(((__var__49_98)==(1))){
        __var__49_97_97 = 1;
    }
    if(((dateTime_3)==((a2s) + (1)))){
        __var__49_98 = 0;
    }
    if(((digitalRead(10))==(1))){
        __var__49_97_97 = 0;
    }
    if(((dateTime_4)==(a2d))){
        __var__49_109 += 1;
    }
    if(((__var__49_109)==(1))){
        __var__49_110 = 1;
    }
    if(((dateTime_4)==((a2d) + (1)))){
        __var__49_109 = 0;
    }
    if(((digitalRead(10))==(1))){
        __var__49_110 = 0;
    }
    if(((__var__49_110)==(1))){
        aab = 1;
    }
    if(((__var__49_97_97)==(1))){
        aa = 1;
    }
    if(((__var__49_110)==(0))){
        aab = 0;
    }
    if(((__var__49_97_97)==(0))){
        aa = 0;
    }
    if(((dateTime_3)==(a3s))){
        __var__50_120 += 1;
    }
    if(((__var__50_98)==(1))){
        __var__50_120 = 1;
    }
    if(((dateTime_3)==((a3s) + (1)))){
        __var__50_98 = 0;
    }
    if(((digitalRead(10))==(1))){
        __var__50_120 = 0;
    }
    if(((dateTime_4)==(a3d))){
        __var__50_109 += 1;
    }
    if(((__var__50_109)==(1))){
        __var__50_110 = 1;
    }
    if(((dateTime_4)==((a3d) + (1)))){
        __var__50_109 = 0;
    }
    if(((digitalRead(10))==(1))){
        __var__50_110 = 0;
    }
    if(((__var__50_110)==(1))){
        aax = 1;
    }
    if(((__var__50_120)==(1))){
        ababa = 1;
    }
    if(((__var__50_110)==(0))){
        aax = 0;
    }
    if(((__var__50_120)==(0))){
        ababa = 0;
    }

    _loop();
}

void _delay(float seconds){
    long endTime = millis() + seconds * 1000;
    while(millis() < endTime)_loop();
}

void _loop(){
    Data d = rtc.getData();
    dateTime_0=d.year;
    dateTime_1=d.month;
    dateTime_2=d.day;
    dateTime_3=d.hour24h;
    dateTime_4=d.minutes;
    dateTime_5=d.seconds;
}
void readBluetooth(){ //bt aktif olduğun da verileri okuyacak fonksiyon

    while (Serial.available())
    {
       delay(10);
       char c = Serial.read();
       data= data+c;
    }
    command=data.charAt(0);//verideki ilk karakter alınır


    if(command=='e'){
    //İLK KARAKTERİN 'e' OLMASI YENİ ALARM EKLENECEK DEMEK
         hour= data.substring(1,3);
         minute= data.substring(3,5);
         section= data.charAt(5);
         alarmNo=data.charAt(6);

         ////////BİRİNCİ BÖLME////////
         if(section=='1'){
            if(alarmNo=='1'){       //1. ALARM
                a1s1=hour.toInt();
                a1d1=minute.toInt();
            }else if(alarmNo=='2'){ //2. ALARM
                a1s2=hour.toInt();
                a1d2=minute.toInt();
            }else{                  //3. ALARM
                a1s3=hour.toInt();
                a1d3=minute.toInt();
            }
         }
         ////////İKİNCİ BÖLME////////
         else if(section=='2'){
            if(alarmNo=='1'){       //1. ALARM
                a2s1=hour.toInt();
                a2d1=minute.toInt();
            }else if(alarmNo=='2'){ //2. ALARM
                a2s2=hour.toInt();
                a2d2=minute.toInt();
            }else {                 //3. ALARM
                a2s3=hour.toInt();
                a2d3=minute.toInt();
            }
         }
         ////////ÜÇÜNCÜ BÖLME////////
         else{
            if(alarmNo=='1'){       //1. ALARM
                a3s1=hour.toInt();
                a3d1=minute.toInt();
            }else if(alarmNo=='2'){ //2. ALARM
                a3s2=hour.toInt();
                a3d2=minute.toInt();
            }else {                 //3. ALARM
                a3s3=hour.toInt();
                a3d3=minute.toInt();
            }
         }
    }else if(command=='s'){
    //İLK KARAKTER 's' OLDUĞUNDA KURULU ALARM KUTUDAN KALDIRILIR
        section=data.charAt(1);
        alarmNo=data.charAt(2);

          ////////BİRİNCİ BÖLME////////
        if(section=='1'){
            if(alarmNo=='1'){       //1. ALARM
               a1s1=ALARM_NOT_SET;
               a1d1=ALARM_NOT_SET;
            }else if(alarmNo=='2'){ //2. ALARM
               a1s2=ALARM_NOT_SET;
               a1d2=ALARM_NOT_SET;
            }else{                  //3. ALARM
               a1s3=ALARM_NOT_SET;
               a1d3=ALARM_NOT_SET;
            }
        }
        ////////İKİNCİ BÖLME////////
        else if(section=='2'){
            if(alarmNo=='1'){       //1. ALARM
               a2s1=ALARM_NOT_SET;
               a2d1=ALARM_NOT_SET;
            }else if(alarmNo=='2'){ //2. ALARM
               a2s2=ALARM_NOT_SET;
               a2d2=ALARM_NOT_SET;
            }else {                 //3. ALARM
               a2s3=ALARM_NOT_SET;
               a2d3=ALARM_NOT_SET;
             }
            }
        ////////ÜÇÜNCÜ BÖLME////////
        else{
            if(alarmNo=='1'){       //1. ALARM
              a3s1=ALARM_NOT_SET;
              a3d1=ALARM_NOT_SET;
            }else if(alarmNo=='2'){ //2. ALARM
              a3s2=ALARM_NOT_SET;
              a3d2=ALARM_NOT_SET;
            }else {                 //3. ALARM
              a3s3=ALARM_NOT_SET;
              a3d3=ALARM_NOT_SET;
              }
            }
    }

    //DEĞİŞKENLERİ İŞLERİNİ YAPTIKTAN SONRA SIFIRLIYORUZ
    data="";
    hour="";
    minute="";
    command='';
    section='';
    alarmNo='';
  }
