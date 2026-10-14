package com.wol.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/** 添加 / 编辑设备。 */
public class DeviceEditActivity extends Activity {

    public static final String EXTRA_ID = "device_id";

    private DeviceStore store;
    private Prefs prefs;
    private Device editing;

    private EditText inputName;
    private EditText inputMac;
    private EditText inputIp;
    private EditText inputPort;
    private EditText inputNote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);

        store = new DeviceStore(this);
        prefs = new Prefs(this);

        inputName = (EditText) findViewById(R.id.inputName);
        inputMac = (EditText) findViewById(R.id.inputMac);
        inputIp = (EditText) findViewById(R.id.inputIp);
        inputPort = (EditText) findViewById(R.id.inputPort);
        inputNote = (EditText) findViewById(R.id.inputNote);

        long id = getIntent().getLongExtra(EXTRA_ID, 0L);
        if (id > 0L) {
            editing = store.find(id);
        }

        TextView title = (TextView) findViewById(R.id.editTitle);
        if (editing != null) {
            title.setText(R.string.edit_title_edit);
            inputName.setText(editing.name);
            inputMac.setText(editing.mac);
            inputIp.setText(editing.ip);
            inputPort.setText(String.valueOf(editing.port > 0 ? editing.port : prefs.defaultPort));
            inputNote.setText(editing.note);
        } else {
            title.setText(R.string.edit_title_new);
            inputPort.setText(String.valueOf(prefs.defaultPort));
        }

        TextView btnBack = (TextView) findViewById(R.id.btnBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        TextView btnSave = (TextView) findViewById(R.id.btnSave);
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
    }

    private void save() {
        String name = inputName.getText().toString().trim();
        if (name.length() == 0) {
            toast(R.string.err_name_empty, inputName);
            return;
        }

        String macText = inputMac.getText().toString().trim();
        if (MacAddress.parse(macText) == null) {
            toast(R.string.err_mac_invalid, inputMac);
            return;
        }

        String ip = inputIp.getText().toString().trim();
        if (ip.length() > 0 && NetMath.toBytes(ip) == null) {
            toast(R.string.err_ip_invalid, inputIp);
            return;
        }

        int port = prefs.defaultPort;
        String portText = inputPort.getText().toString().trim();
        if (portText.length() > 0) {
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                toast(R.string.err_port_invalid, inputPort);
                return;
            }
            if (port < 1 || port > 65535) {
                toast(R.string.err_port_invalid, inputPort);
                return;
            }
        }

        Device device = editing != null ? editing : new Device();
        if (editing == null) {
            device.id = store.nextId(store.load());
        }
        device.name = name;
        device.mac = MacAddress.normalize(macText);
        device.ip = ip;
        device.port = port;
        // 广播地址由程序按本机网段自动计算（旧数据里已填的覆盖值仍会保留，作为广播回退目标）
        device.note = inputNote.getText().toString().trim();

        store.upsert(device);
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void toast(int messageRes, EditText focus) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show();
        if (focus != null) {
            focus.requestFocus();
        }
    }
}
