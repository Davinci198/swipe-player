package com.swipeplayer;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);

        // Initialize DragonBones native
        boolean ok = DragonBonesBridge.init();
        String version = DragonBonesBridge.getVersion();

        String msg = "DragonBones Native v" + version + "\nStatus: " + (ok ? "✓ LOADED" : "✗ FAILED");
        tvStatus.setText(msg);

        Toast.makeText(this, "DragonBones " + version, Toast.LENGTH_LONG).show();
    }
}