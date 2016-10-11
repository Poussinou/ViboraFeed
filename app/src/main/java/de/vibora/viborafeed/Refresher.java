package de.vibora.viborafeed;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Der Typ Refresher ist ein Singelton.
 * Diese Klasse hat alle nötigen Methoden, um über den AlarmManager bzw
 * den Alarm {@link Alarm} mit einer Feed-Quelle zu kommunizieren. Der Refresher
 * erstellt auch die Notifications.
 *
 * @see Alarm
 */
public class Refresher {
    private Context _ctx;
    private SharedPreferences _pref;
    private int _notifyColor;
    private int _notifyType;

    /**
     * Dieses Array nimmt neue Feeds auf, um beim Erzeugen von Notifikations nicht
     * den umweg über die Datenbank gehen zu müssen.
     */
    public ArrayList<ContentValues> _newFeeds;

    private static Refresher _me = null;

    /**
     * Refresher ist als Singelton ausgelegt.
     * Der Context wird übergeben, da {@link ViboraApp#getContextOfApplication()}
     * nicht tat. Scheinbar hat Alarm bzw der frühere Service nicht auf ViboraApp
     * zugreifen konnte.
     *
     * @param ctx Der Kontext der Application.
     * @return the refresher
     */
    public static Refresher ME(Context ctx) {
        if (_me == null) _me = new Refresher(ctx);
        return _me;
    }

    private Refresher(Context ctx) {
        _ctx = ctx;
        _newFeeds = new ArrayList<>();
        _pref = PreferenceManager.getDefaultSharedPreferences(ViboraApp.getContextOfApplication());
        _notifyColor = Color.parseColor(
                _pref.getString("notify_color", ViboraApp.Config.DEFAULT_notifyColor)
        );
        _notifyType = Integer.parseInt(
                _pref.getString("notify_type", ViboraApp.Config.DEFAULT_notifyType)
        );
    }

    /**
     * Entscheidet, ob ein Feed neu ist.
     * In ersten Schritt werden Feeds ignoriert, die DEFAULT_expunge überschreiten.
     * Das ist wichtig, da sonst bereits aus der Datenbank entfernte Feeds als
     * neu erkannt werden. Im zweiten Schritt wird lediglich der Titel in der
     * Datenbank gesucht. Existiert dieser nicht, so wird true zurückgegeben.
     *
     * @see ViboraApp.Config
     *
     * @param date  the date
     * @param title the title
     * @param expunge anzahl an tagen, wie alt ein neuer feed max sein darf
     * @return the boolean
     */
    public boolean isReallyFresh(Date date, String title, int expunge) {
        boolean back = true;
        Date now = new Date();
        try {
            long diff = now.getTime() - date.getTime();
            long days = TimeUnit.DAYS.convert(diff, TimeUnit.MILLISECONDS);
            if (days > expunge) {
                back = false;
            } else {
                /// @todo same title could be possible ?!
                Cursor c = _ctx.getContentResolver().query(
                        FeedContentProvider.CONTENT_URI,
                        FeedContract.projection,
                        FeedContract.Feeds.COLUMN_Title+"=?",
                        new String[]{title},
                        null
                );
                if (c != null) {
                    if (c.getCount() != 0) back = false;
                    c.close();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return back;
    }

    /**
     * Prüft, ob das Gerät online ist.
     * Sollte z.B. wegen dem Flugmodus keine Verbindung bestehen, wird false zurückgegeben
     *
     * @return ist true, wenn ein zugang zu einem Netz besteht
     */
    public boolean isOnline() {
        boolean result;
        ConnectivityManager connectivityManager;
        connectivityManager = (ConnectivityManager) _ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = null;
        if (connectivityManager != null) {
            networkInfo = connectivityManager.getActiveNetworkInfo();
            if(networkInfo != null) {
                switch (networkInfo.getType()) {
                    case ConnectivityManager.TYPE_WIFI:
                        Log.d(ViboraApp.TAG, "using Wifi connection");
                        break;
                    case ConnectivityManager.TYPE_MOBILE:
                        Log.d(ViboraApp.TAG, "using mobile connection");
                        break;
                    default:
                        Log.d(ViboraApp.TAG, "Unknown connection type");
                        break;
                }
            }
        }
        result = (networkInfo != null && networkInfo.isConnected());
        Log.d(ViboraApp.TAG, "Connection state: " + String.valueOf(result));

        return result;
    }

    /**
     * Holt aus den Preferences das Date des letzten Refresh.
     * Beim Datum wird +0200 nach GMT entfernt und die Stundenzahl angepasst. Sollte
     * in den Preferences kein Datum sein,
     * wird DEFAULT_expunge zur Erzeugung
     * eines Datums in der Vergangenheit genutzt.
     *
     * @param rssurl quelle als http://..... angabe
     * @param expunge anzahl an tagen, wie alt ein neuer feed max sein darf
     * @return a string with a 'good' HTTP Mod Time Request format
     */
    public String ifModifiedSinceDate(String rssurl, int expunge) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("GMT"));

        Date defaultDate = new Date();
        c.setTime(defaultDate);
        // Setting the default modified date to a date some days in the past (0 => 1970)
        c.add(Calendar.DAY_OF_MONTH, -1 * expunge);
        defaultDate = c.getTime();
        Date lastUpdate = new Date(_pref.getLong("last_update_"+rssurl, defaultDate.getTime()));
        c.setTime(lastUpdate);

        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.ENGLISH);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));

        return sdf.format(c.getTime()) + " GMT";
    }

    /**
     * Methode sendet If-Modified-Since und wertet den Response Code aus.
     * Könnte false negativ sein, wenn 301 (dauerhaft umgezogen) kommt.
     *
     * @param url the url
     * @param expunge anzahl an tagen, wie alt ein neuer feed max sein darf
     * @return false, wenn HTTP_NOT_MODIFIED oder der Code nicht 200 ist
     * @throws Exception ausgelöst, wenn z.B. die url nicht stimmt
     */
    public boolean newStuff(URL url, int expunge) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        String now = ifModifiedSinceDate(url.toString(), expunge);
        Log.d(ViboraApp.TAG, "If-Modified-Since: " + now);
        conn.setRequestProperty("If-Modified-Since", now);
        int responseCode = conn.getResponseCode();
        /**
        this close() removes the strictMode Error:
        Explicit termination method 'end' not called
        at com.android.okhttp.okio.GzipSource.<init>(GzipSource.java:62)
        -> getResponseCode() does not close the inputStream automatically !
         */
        conn.getInputStream().close();

        Log.d(ViboraApp.TAG, "Response Code: " + Integer.toString(responseCode));
        if (BuildConfig.DEBUG) {
            error(Integer.toString(responseCode), "if modified since " + now);
        }
        if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
            return false;
        }
        if (responseCode != HttpURLConnection.HTTP_OK) {
            error(url.toString(), _ctx.getString(R.string.responseStrange));
            Log.e(ViboraApp.TAG, _ctx.getString(R.string.responseStrange));
            return false;
        }
        return true;
    }

    /**
     * Holt Seite von rssurl und legt diese in XML-Doc ab.
     * Die Methode setzt in den Preferences das Datum der letzten Änderung.
     *
     * @param rssurl quelle als http://..... angabe
     * @param expunge anzahl an tagen, wie alt ein neuer feed max sein darf
     * @return the doc
     */
    public Document getDoc(String rssurl, int expunge) {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        try {
            URL url = new URL(rssurl);
            DocumentBuilder db = dbf.newDocumentBuilder();
            if (! newStuff(url, expunge)) return null;
            InputStream is = url.openStream();
            Document doc = db.parse(is);
            doc.getDocumentElement().normalize();
            SharedPreferences.Editor editor = _pref.edit();
            editor.putLong("last_update_"+rssurl, new Date().getTime());
            editor.apply();
            return doc;
        } catch (ParserConfigurationException e) {
            e.printStackTrace();
        } catch (MalformedURLException e) {
            e.printStackTrace();
            error(rssurl, _ctx.getString(R.string.rssUrlWrong));
            Log.e(ViboraApp.TAG, _ctx.getString(R.string.rssUrlWrong));
        } catch (Exception e) {
            error(rssurl, _ctx.getString(R.string.noConnection));
            Log.e(ViboraApp.TAG, _ctx.getString(R.string.noConnection));
        }
        return null;
    }

    /**
     * Das Doc wird ausgelesen und in die DB geschrieben.
     * Die neuen Feeds werden auch in _newFeeds abgelegt. Das _newFeeds Array wird
     * so sortiert, dass die neusten Feeds bei id 0 zu finden sind.
     *
     * @param expunge anzahl an tagen, wie alt ein neuer feed max sein darf
     * @param sourceId aktuell ist 1 für vibora und 2 für das, was user eingestellt hat
     * @param doc the doc
     */
    public void insertToDb(Document doc, int expunge, int sourceId) {
        if (doc == null) {
            Log.d(ViboraApp.TAG, "doc is null - no insertToDb()");
            return;
        }

        String[] blacklist = getBlacklist();
        NodeList nodeList = doc.getElementsByTagName("item");
        // put to database if not the same  -------------------------------------------------
        try {
            Node n;
            // run through item Tags
            feediter:
            for (int i = 0; i < nodeList.getLength(); i++) {
                n = nodeList.item(i);
                String title = FeedContract.extract(n, "title");
                String body = FeedContract.extract(n, "description");
                String dateStr = FeedContract.extract(n, "pubDate");
                Date date = FeedContract.rawToDate(dateStr);
                for (String bl: blacklist) {
                    Log.d(ViboraApp.TAG, "Check Blacklist: " + bl);
                    if (body.contains(bl)) {
                        Log.d(ViboraApp.TAG, "in body");
                        continue feediter;
                    }
                    if (title.contains(bl)) {
                        Log.d(ViboraApp.TAG, "in title");
                        continue feediter;
                    }
                }
                Log.d(ViboraApp.TAG, "is fresh?");
                if (isReallyFresh(date, title, expunge)) {
                    Log.d(ViboraApp.TAG, "  yes");
                    ContentValues values = new ContentValues();
                    values.put(FeedContract.Feeds.COLUMN_Title, title);
                    values.put(FeedContract.Feeds.COLUMN_Date, FeedContract.dbFriendlyDate(date));
                    values.put(FeedContract.Feeds.COLUMN_Link, FeedContract.extract(n, "link"));
                    values.put(FeedContract.Feeds.COLUMN_Body, body);
                    values.put(FeedContract.Feeds.COLUMN_Image, FeedContract.getBytes(
                            FeedContract.getImage(n)
                    ));
                    values.put(FeedContract.Feeds.COLUMN_Source, sourceId);
                    values.put(FeedContract.Feeds.COLUMN_Deleted, FeedContract.Flag.VISIBLE);
                    values.put(FeedContract.Feeds.COLUMN_Flag, FeedContract.Flag.NEW);

                    Uri uri = _ctx.getContentResolver().insert(FeedContentProvider.CONTENT_URI, values);

                    if (uri != null) {
                        long id = Long.parseLong(uri.getLastPathSegment());
                        values.put(FeedContract.Feeds._ID, id);
                        _newFeeds.add(values);
                    }
                } else {
                    Log.d(ViboraApp.TAG, "  no");
                }
            }

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public String[] getBlacklist() {
        String nos = _pref.getString("blacklist", "");
        if (nos.equals("")) return new String[]{};
        return nos.split(",");
    }

    public void sortFeeds() {
        // elements: the last one is the oldest but we want the first one as the newest
        Collections.sort(_newFeeds, new Comparator<ContentValues>() {
            @Override
            public int compare(ContentValues t1, ContentValues t2) {
                return t1.getAsString(
                        FeedContract.Feeds.COLUMN_Date
                ).compareTo(t2.getAsString(
                        FeedContract.Feeds.COLUMN_Date)
                );
            }
        });
    }

    /**
     * Macht einzelne Notifikation.
     * Diese wird dargestellt wie eine HeadUp Nachricht.
     *
     * @param pi Der PendingIntent, wenn man auf die Notification klickt
     */
    public void makeNotify(PendingIntent pi) {
        Uri sound = Uri.parse("android.resource://" + ViboraApp.getContextOfApplication().getPackageName() + "/" + R.raw.notifysnd);
        notify(_newFeeds.get(_newFeeds.size()-1), pi, sound, true);
    }

    /**
     * Macht viele Notifikations. Nur eine davon bekommt einen Sound.
     * Als zusätzliche Action wird das öffnen des Feed-Links im Browser eingefügt.
     *
     * @param pi Der PendingIntent, wenn man auf die Notification klickt
     */
    public void makeNotifies(PendingIntent pi) {
        Uri sound = Uri.parse("android.resource://" + ViboraApp.getContextOfApplication().getPackageName() + "/" + R.raw.notifysnd);

        for (ContentValues cv : _newFeeds) {
            notify(cv, pi, sound, false);
            // make sound only 1x times
            if (sound != null) sound = null;
        }
    }

    public void error(String title, String msg) {
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(_ctx);
        Bitmap largeIcon = BitmapFactory.decodeResource(_ctx.getResources(), R.mipmap.errorhint);
        mBuilder.setContentTitle(title)
                .setContentText(msg)
                .setTicker(msg)
                .setSmallIcon(R.drawable.logo_sw)
                .setLargeIcon(largeIcon)
                .setVibrate(new long[]{2000})
                .setPriority(Notification.PRIORITY_HIGH);
        Notification noti = mBuilder.build();
        noti.flags |= Notification.FLAG_AUTO_CANCEL;
        NotificationManager mNotifyMgr =
                (NotificationManager) _ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        mNotifyMgr.notify(42, noti);
    }

    private void notify(ContentValues cv, PendingIntent pi, Uri sound, boolean isHeadUp) {
        String body = FeedContract.removeHtml(cv.getAsString(FeedContract.Feeds.COLUMN_Body));
        String title= FeedContract.removeHtml(cv.getAsString(FeedContract.Feeds.COLUMN_Title));
        String link = cv.getAsString(FeedContract.Feeds.COLUMN_Link);
        Bitmap largeIcon = FeedContract.getImage(cv.getAsByteArray(FeedContract.Feeds.COLUMN_Image));

        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(_ctx);

        NotificationCompat.BigTextStyle bigStyle = new NotificationCompat.BigTextStyle();
        bigStyle.bigText(body);

        if (largeIcon == null)
            largeIcon = BitmapFactory.decodeResource(_ctx.getResources(), R.mipmap.ic_launcher);

        mBuilder.setContentTitle(title)
                .setContentText(body)
                .setTicker(body)
                .setContentIntent(pi)
                .setStyle(bigStyle)
                .setSmallIcon(R.drawable.logo_sw)
                .setLargeIcon(largeIcon);

        if (! isHeadUp) {
            Intent linkIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
            PendingIntent linkpi = PendingIntent.getActivity(
                    ViboraApp.getContextOfApplication(), 0, linkIntent, 0
            );
            mBuilder.addAction(
                    android.R.drawable.ic_menu_view,
                    ViboraApp.getContextOfApplication().getString(R.string.open),
                    linkpi
            );
        } else {
            mBuilder.setPriority(Notification.PRIORITY_HIGH);
        }

        if (sound != null) mBuilder.setSound(sound);

        switch (_notifyType) {
            case 1:
                mBuilder.setLights(_notifyColor, 1000, 0);
                break;
            case 2:
                mBuilder.setLights(_notifyColor, 4000, 1000);
                break;
            case 3:
                mBuilder.setLights(_notifyColor, 500, 200);
                break;
            default:
        }

        Notification noti = mBuilder.build();
        noti.flags |= Notification.FLAG_AUTO_CANCEL;
        NotificationManager mNotifyMgr =
                (NotificationManager) _ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        mNotifyMgr.notify(cv.getAsInteger(FeedContract.Feeds._ID), noti);
    }

}
