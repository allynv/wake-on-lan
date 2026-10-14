package com.wol.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 设备列表持久化（SharedPreferences + JSON）。 */
public class DeviceStore {

    private static final String PREF_NAME = "wol_devices";
    private static final String KEY_ITEMS = "items";

    private final SharedPreferences sp;

    public DeviceStore(Context context) {
        this.sp = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 读取并排序后的设备列表。 */
    public List<Device> load() {
        List<Device> list = new ArrayList<Device>();
        String raw = sp.getString(KEY_ITEMS, "");
        if (raw != null && raw.length() > 0) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        list.add(fromJson(o));
                    }
                }
            } catch (Exception ignored) {
                // 数据损坏时返回空列表，避免崩溃
            }
        }
        sort(list);
        return list;
    }

    public void save(List<Device> list) {
        JSONArray arr = new JSONArray();
        for (Device d : list) {
            try {
                arr.put(toJson(d));
            } catch (Exception ignored) {
                // 单条序列化失败不影响其它设备
            }
        }
        sp.edit().putString(KEY_ITEMS, arr.toString()).apply();
    }

    public long nextId(List<Device> list) {
        long max = 0L;
        for (Device d : list) {
            if (d.id > max) {
                max = d.id;
            }
        }
        return max + 1L;
    }

    public void upsert(Device device) {
        List<Device> list = load();
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == device.id) {
                list.set(i, device);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            list.add(device);
        }
        save(list);
    }

    public void delete(long id) {
        List<Device> list = load();
        List<Device> kept = new ArrayList<Device>();
        for (Device d : list) {
            if (d.id != id) {
                kept.add(d);
            }
        }
        save(kept);
    }

    public Device find(long id) {
        for (Device d : load()) {
            if (d.id == id) {
                return d;
            }
        }
        return null;
    }

    /** 按名称做中文排序，同名再按 MAC 排序。 */
    public static void sort(List<Device> list) {
        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(list, new Comparator<Device>() {
            @Override
            public int compare(Device a, Device b) {
                String an = a.name == null ? "" : a.name;
                String bn = b.name == null ? "" : b.name;
                int r = collator.compare(an, bn);
                if (r != 0) {
                    return r;
                }
                return (a.mac == null ? "" : a.mac).compareTo(b.mac == null ? "" : b.mac);
            }
        });
    }

    // ------------------------------------------------------------------ JSON（放在存储层，让 Device 保持纯数据）

    private static JSONObject toJson(Device d) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", d.id);
        o.put("name", d.name);
        o.put("mac", d.mac);
        o.put("ip", d.ip);
        o.put("port", d.port);
        o.put("broadcast", d.broadcast);
        o.put("note", d.note);
        return o;
    }

    private static Device fromJson(JSONObject o) {
        Device d = new Device();
        d.id = o.optLong("id", 0L);
        d.name = o.optString("name", "");
        d.mac = o.optString("mac", "");
        d.ip = o.optString("ip", "");
        d.port = o.optInt("port", 9);
        d.broadcast = o.optString("broadcast", "");
        d.note = o.optString("note", "");
        return d;
    }
}
