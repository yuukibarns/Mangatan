package com.mangatan.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;

public class SetupActivity extends Activity {

    private EditText pathInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check if config already exists
        File configFile = new File(getFilesDir(), "data_path.cfg");
        if (configFile.exists()) {
            // Config exists - go straight to main activity
            launchMainActivity();
            return;
        }

        // Config doesn't exist - show setup UI
        showSetupUI();
    }

    private void showSetupUI() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);
        layout.setGravity(Gravity.CENTER);

        TextView title = new TextView(this);
        title.setText("Mangatan Setup");
        title.setTextSize(24f);
        layout.addView(title);

        TextView label = new TextView(this);
        label.setText("\nPlease enter the storage path for manga data:\n");
        layout.addView(label);

        pathInput = new EditText(this);
        pathInput.setText(Environment.getExternalStorageDirectory().getAbsolutePath() + "/MangatanData");
        layout.addView(pathInput);

        TextView spacer = new TextView(this);
        spacer.setHeight(40);
        layout.addView(spacer);

        Button btnPermission = new Button(this);
        btnPermission.setText("1. Grant Storage Permissions");
        btnPermission.setOnClickListener(v -> requestPermissions());
        layout.addView(btnPermission);

        TextView spacer2 = new TextView(this);
        spacer2.setHeight(20);
        layout.addView(spacer2);

        Button btnSave = new Button(this);
        btnSave.setText("2. Save and Start");
        btnSave.setOnClickListener(v -> saveAndFinish());
        layout.addView(btnSave);

        setContentView(layout);
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.addCategory("android.intent.category.DEFAULT");
                    intent.setData(Uri.parse(String.format("package:%s", getPackageName())));
                    startActivity(intent);
                } catch (Exception e) {
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            } else {
                Toast.makeText(this, "Permission already granted!", Toast.LENGTH_SHORT).show();
            }
        } else {
            requestPermissions(new String[]{
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }, 101);
        }
    }

    private void saveAndFinish() {
        // Check Permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this, "Please grant storage permissions first", Toast.LENGTH_LONG).show();
                return;
            }
        }

        String pathStr = pathInput.getText().toString().trim();
        File targetDir = new File(pathStr);

        // Try to create the directory
        if (!targetDir.exists()) {
            boolean created = targetDir.mkdirs();
            if (!created && !targetDir.exists()) {
                Toast.makeText(this, "Could not create directory. Check permissions.", Toast.LENGTH_LONG).show();
                return;
            }
        }

        // Write path to config file
        try {
            File configFile = new File(getFilesDir(), "data_path.cfg");
            FileOutputStream fos = new FileOutputStream(configFile);
            fos.write(pathStr.getBytes());
            fos.close();
            
            Toast.makeText(this, "Setup Complete!", Toast.LENGTH_SHORT).show();
            
            // Launch main activity
            launchMainActivity();
            
        } catch (Exception e) {
            Toast.makeText(this, "Error saving config: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void launchMainActivity() {
        Intent intent = new Intent();
        intent.setClassName(this, "com.mangatan.app.MangatanActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
