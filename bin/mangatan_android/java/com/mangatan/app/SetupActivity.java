package com.mangatan.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;

public class SetupActivity extends Activity {
    private static final String TAG = "MangatanSetup";
    private static final int REQUEST_CODE_PICK_DIRECTORY = 1001;
    private static final int REQUEST_CODE_MANAGE_STORAGE = 1002;
    private static final String PREFS_NAME = "mangatan_prefs";
    private static final String KEY_EXTERNAL_DATA_PATH = "external_data_path";
    private static final String KEY_SETUP_COMPLETE = "setup_complete";
    
    // Android 11 (API 30) - use numeric value for compatibility with SDK 26
    private static final int BUILD_VERSION_CODES_R = 30;

    private TextView statusText;
    private Button manageStorageButton;
    private Button pickButton;
    private Button continueButton;
    private String selectedPath = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Check if setup is already complete
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_SETUP_COMPLETE, false)) {
            // Setup already done, proceed to main activity
            launchMainActivity();
            return;
        }

        // Create simple UI programmatically
        setContentView(createSetupView());
        
        // Update UI based on permission status
        updatePermissionStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }

    private View createSetupView() {
        // Create a simple vertical layout
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(50, 100, 50, 50);
        layout.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        // Title
        TextView title = new TextView(this);
        title.setText("Mangatan Setup");
        title.setTextSize(28);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, 0, 0, 40);
        layout.addView(title);

        // Description
        TextView description = new TextView(this);
        description.setText("Please select a folder on external storage where Mangatan will store:\n\n" +
                "• Downloaded manga data\n" +
                "• OCR cache\n" +
                "• Yomitan dictionaries\n\n" +
                "This location must have sufficient space (recommended: 5GB+).");
        description.setTextSize(16);
        description.setPadding(20, 0, 20, 40);
        layout.addView(description);

        // Permission info (for Android 11+)
        if (Build.VERSION.SDK_INT >= BUILD_VERSION_CODES_R) {
            TextView permissionInfo = new TextView(this);
            permissionInfo.setText("Step 1: Grant storage permission");
            permissionInfo.setTextSize(14);
            permissionInfo.setTextColor(0xFF444444);
            permissionInfo.setPadding(20, 0, 20, 10);
            layout.addView(permissionInfo);

            // Manage Storage Permission button (only for Android 11+)
            manageStorageButton = new Button(this);
            manageStorageButton.setText("Grant Storage Access");
            manageStorageButton.setTextSize(16);
            manageStorageButton.setPadding(40, 20, 40, 20);
            manageStorageButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    requestManageStoragePermission();
                }
            });
            android.widget.LinearLayout.LayoutParams manageParams = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            );
            manageParams.bottomMargin = 30;
            manageStorageButton.setLayoutParams(manageParams);
            layout.addView(manageStorageButton);

            TextView step2Text = new TextView(this);
            step2Text.setText("Step 2: Select folder");
            step2Text.setTextSize(14);
            step2Text.setTextColor(0xFF444444);
            step2Text.setPadding(20, 0, 20, 10);
            layout.addView(step2Text);
        }

        // Status text
        statusText = new TextView(this);
        statusText.setText("No folder selected");
        statusText.setTextSize(14);
        statusText.setTextColor(0xFF666666);
        statusText.setPadding(20, 0, 20, 30);
        layout.addView(statusText);

        // Pick button
        pickButton = new Button(this);
        pickButton.setText("Select Folder");
        pickButton.setTextSize(18);
        pickButton.setPadding(40, 20, 40, 20);
        pickButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDirectoryPicker();
            }
        });
        layout.addView(pickButton);

        // Continue button (initially disabled)
        continueButton = new Button(this);
        continueButton.setText("Continue");
        continueButton.setTextSize(18);
        continueButton.setPadding(40, 20, 40, 20);
        continueButton.setEnabled(false);
        continueButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndContinue();
            }
        });
        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = 20;
        continueButton.setLayoutParams(params);
        layout.addView(continueButton);

        return layout;
    }

    /**
     * Request MANAGE_EXTERNAL_STORAGE permission (Android 11+)
     */
    private void requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= BUILD_VERSION_CODES_R) {
            try {
                // Use string constant instead of Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                Intent intent = new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION");
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE);
            } catch (Exception e) {
                // Fallback to general settings if specific intent fails
                Log.w(TAG, "Failed to open specific permission settings, trying general settings", e);
                try {
                    // Use string constant instead of Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    Intent intent = new Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION");
                    startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE);
                } catch (Exception e2) {
                    Log.e(TAG, "Failed to open storage permission settings", e2);
                    Toast.makeText(this, 
                        "Cannot open settings. Please manually grant 'All files access' permission in Settings > Apps > Mangatan > Permissions", 
                        Toast.LENGTH_LONG).show();
                }
            }
        } else {
            Toast.makeText(this, "This Android version doesn't require this permission", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Check if we have MANAGE_EXTERNAL_STORAGE permission using reflection
     */
    private boolean hasManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= BUILD_VERSION_CODES_R) {
            try {
                // Use reflection to call Environment.isExternalStorageManager()
                // which doesn't exist in SDK 26
                Method method = Environment.class.getMethod("isExternalStorageManager");
                return (Boolean) method.invoke(null);
            } catch (Exception e) {
                Log.e(TAG, "Failed to check storage manager permission", e);
                return false;
            }
        } else {
            // For older versions, we rely on legacy external storage and SAF
            return true;
        }
    }

    /**
     * Update UI based on current permission status
     */
    private void updatePermissionStatus() {
        if (Build.VERSION.SDK_INT >= BUILD_VERSION_CODES_R) {
            boolean hasPermission = hasManageStoragePermission();
            
            if (manageStorageButton != null) {
                if (hasPermission) {
                    manageStorageButton.setText("✓ Storage Access Granted");
                    manageStorageButton.setEnabled(false);
                } else {
                    manageStorageButton.setText("Grant Storage Access");
                    manageStorageButton.setEnabled(true);
                }
            }
            
            // Update pick button state
            if (pickButton != null) {
                pickButton.setEnabled(hasPermission);
                if (!hasPermission) {
                    statusText.setText("Please grant storage access first");
                    statusText.setTextColor(0xFFFF6600);
                }
            }
        }
    }

    private void openDirectoryPicker() {
        // Check permission for Android 11+
        if (Build.VERSION.SDK_INT >= BUILD_VERSION_CODES_R && !hasManageStoragePermission()) {
            Toast.makeText(this, "Please grant storage access first", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                       Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                       Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        
        try {
            startActivityForResult(intent, REQUEST_CODE_PICK_DIRECTORY);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open directory picker", e);
            Toast.makeText(this, "Failed to open folder picker", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_CODE_MANAGE_STORAGE) {
            // User returned from permission settings
            updatePermissionStatus();
            
            if (Build.VERSION.SDK_INT >= BUILD_VERSION_CODES_R) {
                if (hasManageStoragePermission()) {
                    Toast.makeText(this, "Storage access granted! Now select a folder.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Storage access not granted. The app may have limited functionality.", Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        
        if (requestCode == REQUEST_CODE_PICK_DIRECTORY && resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                Uri treeUri = data.getData();
                
                // Take persistable permission
                try {
                    getContentResolver().takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );
                    
                    // Convert URI to actual file path
                    String filePath = getFilePathFromUri(treeUri);
                    
                    if (filePath != null) {
                        selectedPath = filePath;
                        statusText.setText("Selected: " + filePath);
                        statusText.setTextColor(0xFF00AA00);
                        continueButton.setEnabled(true);
                        
                        Log.i(TAG, "Selected directory path: " + selectedPath);
                    } else {
                        Log.e(TAG, "Could not convert URI to file path: " + treeUri.toString());
                        Toast.makeText(this, "Failed to access folder. Please try again.", Toast.LENGTH_SHORT).show();
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "Failed to take persistable permission", e);
                    Toast.makeText(this, "Failed to access folder. Please try again.", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    /**
     * Convert content:// URI to actual filesystem path
     */
    private String getFilePathFromUri(Uri uri) {
        try {
            String uriString = uri.toString();
            Log.d(TAG, "Converting URI: " + uriString);
            
            // Handle ExternalStorageProvider (content://com.android.externalstorage.documents/...)
            if (uriString.contains("com.android.externalstorage.documents")) {
                String docId = DocumentsContract.getTreeDocumentId(uri);
                Log.d(TAG, "Document ID: " + docId);
                
                String[] split = docId.split(":");
                String type = split[0];
                String path = split.length > 1 ? split[1] : "";
                
                if ("primary".equalsIgnoreCase(type)) {
                    // Primary external storage
                    String basePath = Environment.getExternalStorageDirectory().getAbsolutePath();
                    String fullPath = path.isEmpty() ? basePath : basePath + "/" + path;
                    Log.i(TAG, "Resolved to primary storage: " + fullPath);
                    return fullPath;
                } else {
                    // SD card or other storage
                    // Try to find the mount point
                    String[] possiblePaths = {
                        "/storage/" + type + "/" + path,
                        "/mnt/media_rw/" + type + "/" + path,
                        "/storage/emulated/" + type + "/" + path
                    };
                    
                    for (String possiblePath : possiblePaths) {
                        java.io.File file = new java.io.File(possiblePath);
                        if (file.exists() && file.canWrite()) {
                            Log.i(TAG, "Resolved to secondary storage: " + possiblePath);
                            return possiblePath;
                        }
                    }
                    
                    // Fallback: use /storage/<type>/<path> even if we can't verify it yet
                    String fallbackPath = "/storage/" + type + (path.isEmpty() ? "" : "/" + path);
                    Log.w(TAG, "Using fallback path (may need verification): " + fallbackPath);
                    return fallbackPath;
                }
            }
            
            // Handle other providers if needed
            Log.w(TAG, "Unknown URI provider: " + uriString);
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Error converting URI to path", e);
            return null;
        }
    }

    /**
     * Create .nomedia file to prevent media scanning
     */
    private boolean createProtectionFile(File directory) {
        boolean success = true;
        
        try {
            // Create .nomedia file
            File nomediaFile = new File(directory, ".nomedia");
            if (!nomediaFile.exists()) {
                if (nomediaFile.createNewFile()) {
                    Log.i(TAG, "Created .nomedia file at: " + nomediaFile.getAbsolutePath());
                } else {
                    Log.w(TAG, "Failed to create .nomedia file");
                    success = false;
                }
            } else {
                Log.d(TAG, ".nomedia file already exists");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error creating .nomedia file", e);
            success = false;
        }

        return success;
    }

    /**
     * Get a user-friendly display version of the path
     */
    private String getDisplayPath(String filePath) {
        if (filePath == null) return "Unknown";
        
        String externalStorage = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (filePath.startsWith(externalStorage)) {
            return "Internal Storage" + filePath.substring(externalStorage.length());
        }
        
        if (filePath.startsWith("/storage/")) {
            return filePath.replace("/storage/", "");
        }
        
        return filePath;
    }

    private void saveAndContinue() {
        if (selectedPath == null) {
            Toast.makeText(this, "Please select a folder first", Toast.LENGTH_SHORT).show();
            return;
        }

        // Verify we can write to this path
        File testDir = new File(selectedPath);
        if (!testDir.exists()) {
            boolean created = testDir.mkdirs();
            if (!created) {
                Toast.makeText(this, "Cannot create directory. Please select another location.", Toast.LENGTH_LONG).show();
                return;
            }
        }

        if (!testDir.canWrite()) {
            Toast.makeText(this, "Cannot write to this location. Please select another folder.", Toast.LENGTH_LONG).show();
            return;
        }

        // Create .nomedia file
        if (!createProtectionFile(testDir)) {
            Log.w(TAG, "Some protection files could not be created, but continuing anyway");
        }

        // Save to SharedPreferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_EXTERNAL_DATA_PATH, selectedPath);
        editor.putBoolean(KEY_SETUP_COMPLETE, true);
        editor.apply();

        Log.i(TAG, "Setup complete. External data path: " + selectedPath);
        
        launchMainActivity();
    }

    private void launchMainActivity() {
        Intent intent = new Intent(this, MangatanActivity.class);
        startActivity(intent);
        finish();
    }
}
