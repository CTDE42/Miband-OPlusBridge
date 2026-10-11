// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider;
import io.github.miam1ku.mibandoplusbridge.protocol.SportReportParser;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Writes one Xiaomi sport summary into OHealth table 1004 (DBOneTimeSport). */
final class OHealthSportWriter {
    static final int TABLE = 1004;
    private static final String[] COLUMNS = {"recordId", "revision", "record"};
    private final OHealthHealthImportHook.HostContract host;
    private final Constructor<?> created;
    private final Method setAccount, setDevice, setType, setStart, setEnd, setMode, setZone;
    private final Method setDisplay, setSync, setMeta, setClient;
    private final Method getStart, getMode, getDevice;

    OHealthSportWriter(OHealthHealthImportHook.HostContract host) throws ReflectiveOperationException {
        this.host = host;
        Class<?> type = Class.forName("com.heytap.databaseengine.model.OneTimeSport", false, host.loader);
        created = type.getConstructor();
        setAccount = type.getMethod("setSsoid", String.class);
        setDevice = type.getMethod("setDeviceUniqueId", String.class);
        setType = type.getMethod("setDeviceType", String.class);
        setStart = type.getMethod("setStartTimestamp", long.class);
        setEnd = type.getMethod("setEndTimestamp", long.class);
        setMode = type.getMethod("setSportMode", int.class);
        setZone = type.getMethod("setTimezone", String.class);
        setDisplay = type.getMethod("setDisplay", int.class);
        setSync = type.getMethod("setSyncStatus", int.class);
        setMeta = type.getMethod("setMetaData", String.class);
        setClient = type.getMethod("setClientDataId", String.class);
        getStart = type.getMethod("getStartTimestamp");
        getMode = type.getMethod("getSportMode");
        getDevice = type.getMethod("getDeviceUniqueId");
    }

    void write(Context context) throws Exception {
        String account = host.account();
        if (account == null || account.isBlank()) return;
        Object api = host.api();
        if (api == null) throw new IllegalStateException("SPORT_IMPORT_NOT_READY");
        Bundle band = OHealthDeviceHook.registeredSnapshot();
        if (band == null || band.getString("deviceId", "").isBlank()) return;
        String queued = band.getString("deviceId");
        String device = OHealthDeviceHook.healthId(queued);
        SharedPreferences written = context.getSharedPreferences("oplusband-sport-import", Context.MODE_PRIVATE);
        for (HealthRecord record : history(context, account, queued)) {
            String key = device + ":" + record.recordId;
            if (written.getInt(key, 0) == record.revision) continue;
            try {
                if (store(api, account, device, record)) {
                    if (!written.edit().putInt(key, record.revision).commit()) {
                        throw new IllegalStateException("SPORT_IMPORT_MEMORY_FAILED");
                    }
                }
            } catch (Exception failure) {
                Log.i("OplusBandBridge", "OHEALTH_SPORT_FAIL " + failure.getClass().getSimpleName());
            }
        }
    }

    private boolean store(Object api, String account, String device, HealthRecord record) throws Exception {
        int mode = record.value.intValue();
        Object row = created.newInstance();
        setAccount.invoke(row, account);
        setDevice.invoke(row, device);
        setType.invoke(row, OHealthStepWriter.DEVICE_BAND);
        setStart.invoke(row, record.startMs);
        setEnd.invoke(row, record.endMs);
        setMode.invoke(row, mode);
        setDisplay.invoke(row, 1);
        setSync.invoke(row, 0);
        setClient.invoke(row, record.recordId);
        if (record.timezone != null) setZone.invoke(row, record.timezone);
        setMeta.invoke(row, metadata(record, mode));
        host.insertRows(api, TABLE, List.of(row));
        List<?> found = host.readRows(api, account, TABLE, device, record.startMs, record.endMs, 0, false);
        for (Object saved : found) {
            if (!device.equals(getDevice.invoke(saved))) continue;
            if (((Number) getStart.invoke(saved)).longValue() != record.startMs) continue;
            if (((Number) getMode.invoke(saved)).intValue() == mode) return true;
        }
        return false;
    }

    private static String metadata(HealthRecord record, int mode) throws Exception {
        JSONObject json = new JSONObject();
        json.put("sportMode", mode);
        json.put("sportName", SportReportParser.name(mode));
        json.put("totalTime", Math.max(1, (record.endMs - record.startMs) / 1000));
        if (record.calories != null) json.put("totalCalories", record.calories);
        if (record.distance != null) json.put("totalDistance", record.distance);
        if (record.reps != null) json.put("totalSteps", record.reps);
        if (record.heart != null) json.put("avgHeartRate", record.heart);
        return json.toString();
    }

    private List<HealthRecord> history(Context context, String account, String device) throws Exception {
        List<HealthRecord> records = new ArrayList<>();
        String after = null;
        for (;;) {
            Uri uri = after == null ? HealthQueueProvider.RECORDS_URI
                    : HealthQueueProvider.RECORDS_URI.buildUpon().appendQueryParameter("after", after).build();
            int count = 0;
            try (Cursor rows = context.getContentResolver().query(uri, COLUMNS,
                    "account=? AND deviceId=? AND kind=? AND startMs<? AND endMs>?",
                    new String[] {account, device, "sport_session", Long.toString(Long.MAX_VALUE), "0"}, null)) {
                if (rows == null) throw new IllegalStateException("SPORT_HISTORY_UNAVAILABLE");
                while (rows.moveToNext()) {
                    records.add(HealthRecord.fromJson(new JSONObject(rows.getString(2))));
                    after = rows.getString(0);
                    count++;
                }
            }
            if (count < 200) break;
        }
        return records;
    }
}
