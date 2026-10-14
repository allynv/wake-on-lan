package com.wol.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.List;

/** 设备列表适配器。 */
public class DeviceAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<Device> items;

    public DeviceAdapter(Context context, List<Device> items) {
        this.inflater = LayoutInflater.from(context);
        this.items = items;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_device, parent, false);
            holder = new ViewHolder();
            holder.name = (TextView) convertView.findViewById(R.id.itemName);
            holder.mac = (TextView) convertView.findViewById(R.id.itemMac);
            holder.ip = (TextView) convertView.findViewById(R.id.itemIp);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Device device = items.get(position);
        holder.name.setText(device.name);
        holder.mac.setText(device.mac);

        StringBuilder detail = new StringBuilder();
        detail.append(device.hasIp() ? device.ip : "广播");
        detail.append(" :").append(device.port);
        if (device.note != null && device.note.length() > 0) {
            detail.append("  (").append(device.note).append(')');
        }
        holder.ip.setText(detail.toString());
        return convertView;
    }

    private static class ViewHolder {
        TextView name;
        TextView mac;
        TextView ip;
    }
}
