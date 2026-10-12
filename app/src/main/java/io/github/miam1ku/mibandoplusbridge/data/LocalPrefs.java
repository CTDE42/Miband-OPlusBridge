// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

/**
 * 接管状态写在 no_backup 的 json 里。
 *
 * <p>libxposed API 102 不再把 {@link Context#getSharedPreferences} 指到
 * {@code /data/misc/apexdata}。1.6.8 及更早的模块会，旧的 OFFICIAL 副本还在那个目录。
 * 这份 json 还没有内容时，从应用数据目录的 {@code shared_prefs} 导入一次。
 * {@code RawFitnessFileStore} 仍直接打开 {@code oplusband-history}，那份步数标记已经写在用户的 SharedPreferences 里。</p>
 */
public final class LocalPrefs {
    private final File file;
    private JSONObject json;

    private LocalPrefs(File file, JSONObject json) {
        this.file = file;
        this.json = json;
    }

    public static LocalPrefs open(Context context, String name) {
        Context app = context.getApplicationContext();
        if (app != null) context = app;
        File file = new File(context.getNoBackupFilesDir(), name + ".json");
        Stored stored = readStored(file);
        JSONObject json = stored.json;
        if (!stored.present) {
            json = importShared(context, name);
            JSONObject disk = importXml(new File(context.getDataDir(), "shared_prefs/" + name + ".xml"));
            if (prefer(disk, json, name)) json = disk;
            if (json.length() > 0) writeJson(file, json);
        }
        return new LocalPrefs(file, json);
    }

    public boolean getBoolean(String key, boolean fallback) { return json.optBoolean(key, fallback); }
    public String getString(String key, String fallback) {
        return json.has(key) ? json.optString(key, fallback) : fallback;
    }
    public int getInt(String key, int fallback) { return json.optInt(key, fallback); }
    public long getLong(String key, long fallback) { return json.optLong(key, fallback); }

    public Editor edit() { return new Editor(); }

    public final class Editor {
        private final JSONObject next;
        Editor() {
            try { next = new JSONObject(json.toString()); }
            catch (Exception failed) { throw new IllegalStateException("STATE_COPY_FAILED", failed); }
        }
        public Editor putBoolean(String key, boolean value) { return put(key, value); }
        public Editor putString(String key, String value) { return put(key, value); }
        public Editor putInt(String key, int value) { return put(key, value); }
        public Editor putLong(String key, long value) { return put(key, value); }
        public Editor remove(String key) { next.remove(key); return this; }
        private Editor put(String key, Object value) {
            try { next.put(key, value); return this; }
            catch (Exception failed) { throw new IllegalStateException("STATE_WRITE_FAILED", failed); }
        }
        public boolean commit() {
            synchronized (LocalPrefs.this) {
                if (!writeJson(file, next)) return false;
                json = next;
                return true;
            }
        }
    }

    private static boolean prefer(JSONObject disk, JSONObject current, String name) {
        if (disk.length() == 0) return false;
        if ("ownership".equals(name) && "NATIVE".equals(disk.optString("mode"))
                && !"NATIVE".equals(current.optString("mode"))) return true;
        if ("band-state".equals(name) && disk.optBoolean("registered")
                && disk.optLong("revision", 0) >= current.optLong("revision", 0)) return true;
        return current.length() == 0;
    }

    /** present 表示磁盘上已经有一份非空文件。解析失败时保留这份文件，打开路径不再拿旧的 SharedPreferences 盖掉它。 */
    static final class Stored {
        final JSONObject json;
        final boolean present;
        Stored(JSONObject json, boolean present) {
            this.json = json;
            this.present = present;
        }
    }

    static Stored readStored(File file) {
        if (!file.isFile() || file.length() == 0) return new Stored(new JSONObject(), false);
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (bytes.length == 0) return new Stored(new JSONObject(), false);
            return new Stored(new JSONObject(new String(bytes, StandardCharsets.UTF_8)), true);
        } catch (Exception failed) {
            note("LOCAL_PREFS_READ_FAILED " + file.getName(), failed);
            return new Stored(new JSONObject(), true);
        }
    }

    static boolean writeJson(File file, JSONObject json) {
        File dir = file.getParentFile();
        if (dir != null) dir.mkdirs();
        File tmp = new File(file.getPath() + ".tmp");
        try {
            Files.write(tmp.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception failed) {
            tmp.delete();
            note("LOCAL_PREFS_WRITE_FAILED " + file.getName(), failed);
            return false;
        }
    }

    private static void note(String line, Exception failed) {
        try {
            android.util.Log.w("OplusBandBridge", line, failed);
        } catch (RuntimeException unavailable) {
            // 单元测试里 android.util.Log 是空实现。
        }
    }

    private static JSONObject importShared(Context context, String name) {
        JSONObject json = new JSONObject();
        try {
            SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            for (var entry : prefs.getAll().entrySet()) {
                if (entry.getValue() != null) json.put(entry.getKey(), entry.getValue());
            }
        } catch (Exception ignored) { }
        return json;
    }

    private static JSONObject importXml(File file) {
        JSONObject json = new JSONObject();
        if (!file.isFile()) return json;
        try (FileInputStream in = new FileInputStream(file)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, StandardCharsets.UTF_8.name());
            String type = null;
            String key = null;
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG) {
                    type = parser.getName();
                    key = parser.getAttributeValue(null, "name");
                    if ("boolean".equals(type) && key != null) {
                        json.put(key, Boolean.parseBoolean(parser.getAttributeValue(null, "value")));
                    } else if ("int".equals(type) && key != null) {
                        json.put(key, Integer.parseInt(parser.getAttributeValue(null, "value")));
                    } else if ("long".equals(type) && key != null) {
                        json.put(key, Long.parseLong(parser.getAttributeValue(null, "value")));
                    }
                } else if (event == XmlPullParser.TEXT && "string".equals(type) && key != null) {
                    json.put(key, parser.getText());
                } else if (event == XmlPullParser.END_TAG) {
                    type = null;
                    key = null;
                }
            }
        } catch (Exception ignored) { }
        return json;
    }
}
