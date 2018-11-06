package net.synapticweb.callrecorder.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.support.annotation.RequiresApi;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
//import android.support.v4.media.app.NotificationCompat.MediaStyle;device
import java.io.IOException;
import net.synapticweb.callrecorder.AppLibrary;
import net.synapticweb.callrecorder.CallRecorderApplication;
import net.synapticweb.callrecorder.R;
import net.synapticweb.callrecorder.contactslist.ContactsListActivityMain;
import net.synapticweb.callrecorder.data.Contact;
import net.synapticweb.callrecorder.data.ContactsContract.*;
import net.synapticweb.callrecorder.data.RecordingsContract.*;
import net.synapticweb.callrecorder.data.CallRecorderDbHelper;

import static net.synapticweb.callrecorder.AppLibrary.*;


public class RecorderService extends Service {
    private static final String TAG = "CallRecorder";
    private static String receivedNumPhone;
    private static Boolean privateCall = null;
    private static Boolean match = null;
    private Boolean incoming = null;
    public static boolean shouldStartAtHookup = false;
    private Long idIfMatch = null;
    private static String contactNameIfMatch = null;

    public static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "call_recorder_channel";
    public static final String CALL_IDENTIFIER = "call_identifier";
    public static final String PHONE_NUMBER = "phone_number";

    public static final int RECORD_AUTOMMATICALLY = 1;
    public static final int RECORD_ON_HOOKUP = 2;
    public static final int RECORD_ON_REQUEST = 3;

    @Override
    public IBinder onBind(Intent i){
        return null;
    }

    public void onCreate(){
        super.onCreate();
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private static void createChannel() {
        NotificationManager mNotificationManager =
                (NotificationManager) CallRecorderApplication.getInstance().getSystemService(Context.NOTIFICATION_SERVICE);

        // The user-visible name of the channel.
        CharSequence name = "Call recorder";
        // The user-visible description of the channel.
        String description = "Call recorder controls";
        int importance = NotificationManager.IMPORTANCE_LOW;
        NotificationChannel mChannel = new NotificationChannel(CHANNEL_ID, name, importance);
        // Configure the notification channel.
        mChannel.setDescription(description);
        mChannel.setShowBadge(false);
        mChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        mNotificationManager.createNotificationChannel(mChannel);
    }

    public static Notification buildNotification(int typeOfNotification, String callNameOrNumber) {
        Intent notificationIntent = new Intent(CallRecorderApplication.getInstance(), ContactsListActivityMain.class);
        PendingIntent tapNotificationPi = PendingIntent.getBroadcast(CallRecorderApplication.getInstance(), 0, notificationIntent, 0);
        Bitmap bitmap = BitmapFactory.decodeResource(CallRecorderApplication.getInstance().getResources(), R.drawable.record);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            createChannel();
        NotificationCompat.Builder builder = null;

        switch(typeOfNotification) {
            case RECORD_AUTOMMATICALLY:
                builder = new NotificationCompat.Builder(CallRecorderApplication.getInstance(), CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_album_white_24dp)
                        .setContentTitle(callNameOrNumber)
                        .setContentIntent(tapNotificationPi)
                        .setLargeIcon(bitmap)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText("Recording..."));
                break;
            case RECORD_ON_HOOKUP:
                builder = new NotificationCompat.Builder(CallRecorderApplication.getInstance(), CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_album_white_24dp)
                        .setContentTitle(callNameOrNumber)
                        .setContentIntent(tapNotificationPi)
                        .setLargeIcon(bitmap)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText("Recording will begin when you answer the call."));
                break;
            case RECORD_ON_REQUEST:
                notificationIntent = new Intent(CallRecorderApplication.getInstance(), ControlRecordingReceiver.class);
                notificationIntent.setAction(RecorderBox.ACTION_START_RECORDING);
                String callIdentifier;
                if(privateCall)
                    callIdentifier = CallRecorderApplication.getInstance().getResources().getString(R.string.private_number_name);
                else
                    callIdentifier = match ? contactNameIfMatch : receivedNumPhone;

                notificationIntent.putExtra(CALL_IDENTIFIER, callIdentifier);
                notificationIntent.putExtra(PHONE_NUMBER, receivedNumPhone != null ? receivedNumPhone : "private_phone");
                PendingIntent startRecordingPi = PendingIntent.getBroadcast(CallRecorderApplication.getInstance(), 0, notificationIntent, 0);

                builder = new NotificationCompat.Builder(CallRecorderApplication.getInstance(), CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_album_white_24dp)
                        .setContentTitle(callNameOrNumber)
                        .setContentIntent(tapNotificationPi)
                        .setLargeIcon(bitmap)
                        .addAction(new NotificationCompat.Action.Builder(R.drawable.ic_play_grey600_24dp,
                                "Start recording", startRecordingPi).build() )
                        .setStyle(new NotificationCompat.BigTextStyle().bigText("Press \"Start recording\" to begin recording."));
        }
        if(builder != null)
            return builder.build();

        return  null;
    }

    public static void onIncomingOfhook() {
        if(shouldStartAtHookup) {
            NotificationManager nm = (NotificationManager) CallRecorderApplication.getInstance().
                    getSystemService(Context.NOTIFICATION_SERVICE);
            String callIdentifier;
            if(privateCall)
                callIdentifier = CallRecorderApplication.getInstance().getResources().getString(R.string.private_number_name);
            else
                callIdentifier = match ? contactNameIfMatch : receivedNumPhone;
            if(nm != null)
                nm.notify(NOTIFICATION_ID, buildNotification(RECORD_AUTOMMATICALLY, callIdentifier));
            RecorderBox.doRecording(CallRecorderApplication.getInstance(), receivedNumPhone != null ? receivedNumPhone : "private_phone");
        }
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        boolean shouldRecord = true;

        receivedNumPhone = intent.getStringExtra(CallReceiver.ARG_NUM_PHONE);
        incoming = intent.getBooleanExtra(CallReceiver.ARG_INCOMING, false);

        //în cazul în care nr primit e null înseamnă că se sună de pe nr privat
        privateCall = (receivedNumPhone == null);

        if(!privateCall) {//și nu trebuie să mai verificăm dacă nr este în baza de date sau, dacă nu
            // este în baza de date, dacă este în contacte.
            Contact contact;
            match = ((contact = Contact.getContactIfNumberInDb(receivedNumPhone, CallRecorderApplication.getInstance())) != null);
            if(match) {
                idIfMatch = contact.getId();
                contactNameIfMatch = contact.getContactName(); //posibil subiect pentru un test.
                shouldRecord = contact.shouldRecord();
            }
        }

        if(incoming) {
            if(privateCall) {
                final SharedPreferences settings = getSharedPreferences(AppLibrary.PREFERENCES, MODE_PRIVATE);
                boolean recordAutommatically = settings.getBoolean(AppLibrary.Settings.AUTOMMATICALLY_RECORD_PRIVATE_CALLS, false);
                if(recordAutommatically){
                    startForeground(NOTIFICATION_ID, buildNotification(RECORD_ON_HOOKUP,
                            getResources().getString(R.string.private_number_name)));
                    shouldStartAtHookup = true;
                }
                else
                    startForeground(NOTIFICATION_ID, buildNotification(RECORD_ON_REQUEST,
                            getResources().getString(R.string.private_number_name)));
            }
            else { //normal call, number present.
                if(match) {
                    if(shouldRecord) {
                        startForeground(NOTIFICATION_ID, buildNotification(RECORD_ON_HOOKUP, contactNameIfMatch));
                        shouldStartAtHookup = true;
                    }
                    else
                        startForeground(NOTIFICATION_ID, buildNotification(RECORD_ON_REQUEST, contactNameIfMatch));
                }
                else
                    startForeground(NOTIFICATION_ID, buildNotification(RECORD_ON_REQUEST, receivedNumPhone));
            }
        }
        else { //outgoing call
            if(match) {
                if(shouldRecord) {
                    startForeground(NOTIFICATION_ID, buildNotification(RECORD_AUTOMMATICALLY, contactNameIfMatch));
                    RecorderBox.doRecording(CallRecorderApplication.getInstance(), receivedNumPhone);
                }
                else
                    startForeground(NOTIFICATION_ID, buildNotification(RECORD_ON_REQUEST, contactNameIfMatch));
            }
            else
                startForeground(NOTIFICATION_ID, buildNotification(RECORD_ON_REQUEST, receivedNumPhone));
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        RecorderBox.disposeRecorder();

        if(!RecorderBox.getRecordingDone()) //dacă nu s-a pornit înregistrarea nu avem nimic de făcut
            return ;

        CallRecorderDbHelper mDbHelper = new CallRecorderDbHelper(getApplicationContext());
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        long idToInsert;

        if(privateCall) {
            Cursor cursor = db.query(Contacts.TABLE_NAME, new String[]{Contacts._ID},
                    Contacts.COLUMN_NAME_PRIVATE_NUMBER + "=" + SQLITE_TRUE, null, null, null, null);

            if(cursor.getCount() == 0) { //încă nu a fost înregistrat un apel de pe număr ascuns
                Contact contact =  new Contact();
                contact.setPrivateNumber(true);
                try {
                    contact.insertInDatabase(this);
                }
                catch (SQLException  exc) {
                    Log.wtf(TAG, exc.getMessage());
                }
                idToInsert = contact.getId();
            }
            else { //Avem cel puțin un apel de pe nr ascuns. Pentru teste: aici e de așteptat ca întotdeauna cursorul să conțină numai 1 element
                cursor.moveToFirst();
                idToInsert = cursor.getInt(cursor.getColumnIndex(Contacts._ID));
            }
            cursor.close();
        }

        else if(match)
            idToInsert = idIfMatch;

        else { //dacă nu e nici match nici private atunci trebuie mai întîi verificat dacă nu cumva nr există totuși în contactele telefonului.
            Contact contact;
            if((contact = Contact.searchNumberInPhoneContacts(receivedNumPhone, getApplicationContext())) != null) {
                try {
                    contact.copyPhotoIfExternal(this);
                    contact.insertInDatabase(this);
                }
                catch (SQLException | IOException exception) {
                    Log.wtf(TAG, exception.getMessage());
                }
                idToInsert = contact.getId();
            }
            else { //numărul nu există nici contactele telefonului. Deci este unknown.
                contact =  new Contact(null, receivedNumPhone, getResources().getString(R.string.unkown_contact), null, AppLibrary.UNKNOWN_TYPE_PHONE_CODE);
                try {
                    contact.insertInDatabase(this); //introducerea în db setează id-ul în obiect
                }
                catch (SQLException exc) {
                    Log.wtf(TAG, exc.getMessage());
                }
                idToInsert = contact.getId();
            }
        }

        ContentValues values = new ContentValues();
        values.put(Recordings.COLUMN_NAME_PHONE_NUM_ID, idToInsert);
        values.put(Recordings.COLUMN_NAME_INCOMING, incoming ? SQLITE_TRUE : SQLITE_FALSE);
        values.put(Recordings.COLUMN_NAME_PATH, RecorderBox.getAudioFilePath());
        values.put(Recordings.COLUMN_NAME_START_TIMESTAMP, RecorderBox.getStartTimestamp());
        values.put(Recordings.COLUMN_NAME_END_TIMESTAMP, System.currentTimeMillis());

        try {
            db.insert(Recordings.TABLE_NAME, null, values);
        }
        catch(SQLException exc) {
            Log.wtf(TAG, exc.getMessage());
        }

    }
}