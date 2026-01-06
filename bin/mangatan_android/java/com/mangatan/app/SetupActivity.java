package com.mangatan.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.view.Gravity;
import android.content.Context;

public class SetupActivity extends Activity {
    private static final int PICK_DIRECTORY_REQUEST = 1;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Simple programmatic UI to avoid XML resources for this example
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(50, 50, 50, 50);

        TextView title = new TextView(this);
        title.setText("Mangatan Setup");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        statusText = new TextView(this);
        statusText.setText("Please select a storage directory.");
        statusText.setPadding(0, 50, 0, 50);
        statusText.setGravity(Gravity.CENTER);
        layout.addView(statusText);

        Button permButton = new Button(this);
        permButton.setText("1. Grant File Permissions");
        permButton.setOnClickListener(v -> requestAllFilesAccess());
        layout.addView(permButton);

        Button pickButton = new Button(this);
        pickButton.setText("2. Select Storage Folder");
        pickButton.setOnClickListener(v -> openDirectoryPicker());
        layout.addView(pickButton);

        setContentView(layout);
    }

    private void requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                statusText.setText("Permission already granted.");
            }
        }
    }

    private void openDirectoryPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, PICK_DIRECTORY_REQUEST);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_DIRECTORY_REQUEST && resultCode == Activity.RESULT_OK) {
            if (data != null) {
                Uri uri = data.getData();
                getContentResolver().takePersistableUriPermission(
                        uri, 
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                );
                
                String path = resolvePath(uri);
                if (path != null) {
                    savePath(path);
                    statusText.setText("Saved: " + path);
                    // Finish and return to Rust
                    finish();
                } else {
                    statusText.setText("Could not resolve filesystem path. Please try a different folder (Internal Storage preferred).");
                }
            }
        }
    }

    private void savePath(String path) {
        SharedPreferences prefs = getSharedPreferences("MangatanPrefs", Context.MODE_PRIVATE);
        prefs.edit().putString("custom_storage_path", path).apply();
    }

    // Attempt to convert Tree URI to absolute path for std::fs usage
    private String resolvePath(Uri uri) {
        String path = uri.getPath();
        // Typically looks like: /tree/primary:Documents/Mangatan
        if (path.contains("primary:")) {
            String relativeUrl = path.split("primary:")[1];
            return Environment.getExternalStorageDirectory() + "/" + relativeUrl;
        }
        // Fallback or SD card logic would go here, simplified for Primary Storage
        return null;
    }
}
