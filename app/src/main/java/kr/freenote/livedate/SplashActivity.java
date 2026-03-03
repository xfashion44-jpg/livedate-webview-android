package kr.freenote.livedate;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SplashActivity extends AppCompatActivity {
    private static final long SPLASH_DELAY_MS = 1200L;
    private static final String UPDATE_MANIFEST_URL = "https://freenote.kr/downloads/manifest.json";
    private static final String BASE_URL = "https://freenote.kr";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean navigated;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        checkForUpdateThenContinue();
    }

    private void checkForUpdateThenContinue() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            UpdateInfo updateInfo = fetchUpdateInfo();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                int currentVersionCode = getCurrentVersionCode();
                if (updateInfo != null && updateInfo.versionCode > currentVersionCode) {
                    showUpdateDialog(updateInfo);
                } else {
                    proceedToMainWithDelay();
                }
            });
        });
        executor.shutdown();
    }

    private void showUpdateDialog(UpdateInfo info) {
        String message = "새 버전 " + info.versionName + " 이(가) 있습니다.\n지금 업데이트할까요?";
        new AlertDialog.Builder(this)
                .setTitle("업데이트 안내")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("업데이트", (dialog, which) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl)));
                    } catch (Exception ignored) {
                    }
                    proceedToMainWithDelay();
                })
                .setNegativeButton("나중에", (dialog, which) -> proceedToMainWithDelay())
                .show();
    }

    private void proceedToMainWithDelay() {
        handler.postDelayed(this::goMain, SPLASH_DELAY_MS);
    }

    private void goMain() {
        if (navigated) {
            return;
        }
        navigated = true;
        startActivity(new Intent(SplashActivity.this, MainActivity.class));
        finish();
    }

    private int getCurrentVersionCode() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) packageInfo.getLongVersionCode();
            }
            return packageInfo.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private UpdateInfo fetchUpdateInfo() {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(UPDATE_MANIFEST_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            connection.setRequestMethod("GET");
            connection.setUseCaches(false);

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }

            JSONObject root = new JSONObject(sb.toString());
            int versionCode = root.optInt("version_code", 0);
            String versionName = root.optString("version_name", "");
            String path = null;
            JSONObject artifacts = root.optJSONObject("artifacts");
            if (artifacts != null) {
                JSONObject releaseApk = artifacts.optJSONObject("release_apk");
                if (releaseApk != null) {
                    path = releaseApk.optString("path", null);
                }
            }
            if (versionCode <= 0 || path == null || path.trim().isEmpty()) {
                return null;
            }

            String apkUrl = path.startsWith("http://") || path.startsWith("https://")
                    ? path
                    : BASE_URL + path;
            return new UpdateInfo(versionCode, versionName, apkUrl);
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static final class UpdateInfo {
        final int versionCode;
        final String versionName;
        final String apkUrl;

        UpdateInfo(int versionCode, String versionName, String apkUrl) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkUrl = apkUrl;
        }
    }
}
