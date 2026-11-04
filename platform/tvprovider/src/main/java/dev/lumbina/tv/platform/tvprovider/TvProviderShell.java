package dev.lumbina.tv.platform.tvprovider;

import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only bridge for the original companion provider, launched as the local adb shell. */
public final class TvProviderShell {
    private static final String AUTHORITY = "android.media.tv";

    private TvProviderShell() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args.length % 2 != 0) {
            throw new IllegalArgumentException("Expected table and columns pairs");
        }
        for (int i = 0; i < args.length; i += 2) {
            String table = args[i];
            if (!(table.equals("channel") || table.equals("preview_program") || table.equals("watch_next_program"))
                    || !args[i + 1].matches("[a-z0-9_,]+")) {
                throw new IllegalArgumentException("Invalid provider query");
            }
        }
        Class<?> managerClass = Class.forName("android.app.IActivityManager");
        Object manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        IBinder token = new Binder();
        Object holder = managerClass.getMethod("getContentProviderExternal", String.class, int.class,
                IBinder.class, String.class).invoke(manager, AUTHORITY, 0, token, "*lumbinaplus*");
        if (holder == null) throw new IllegalStateException("TV provider unavailable");
        try {
            Object provider = holder.getClass().getField("provider").get(holder);
            Class<?> providerClass = Class.forName("android.content.IContentProvider");
            for (int i = 0; i < args.length; i += 2) {
                JSONArray rows = readRows(providerClass, provider, args[i], args[i + 1]);
                System.out.println("TVPROVIDER_JSON=" + args[i] + "\t" + rows);
            }
        } finally {
            managerClass.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class,
                    int.class).invoke(manager, AUTHORITY, token, 0);
        }
    }

    private static JSONArray readRows(Class<?> providerClass, Object provider, String table, String columns)
            throws Exception {
        Cursor cursor = (Cursor) providerClass.getMethod("query", String.class, String.class,
                Uri.class, String[].class, Bundle.class, Class.forName("android.os.ICancellationSignal"))
                .invoke(provider, "com.android.shell", null, Uri.parse("content://" + AUTHORITY + "/" + table),
                        columns.split(","), null, null);
        if (cursor == null) throw new IllegalStateException("TV provider returned no cursor");
        try (Cursor rows = cursor) {
            JSONArray result = new JSONArray();
            while (rows.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < rows.getColumnCount(); i++) {
                    Object value;
                    switch (rows.getType(i)) {
                        case Cursor.FIELD_TYPE_INTEGER: value = rows.getLong(i); break;
                        case Cursor.FIELD_TYPE_FLOAT: value = rows.getDouble(i); break;
                        case Cursor.FIELD_TYPE_STRING: value = rows.getString(i); break;
                        default: value = JSONObject.NULL;
                    }
                    row.put(rows.getColumnName(i), value);
                }
                result.put(row);
            }
            return result;
        }
    }
}
