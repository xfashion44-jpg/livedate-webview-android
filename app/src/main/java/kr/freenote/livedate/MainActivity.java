package kr.freenote.livedate;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.onesignal.OneSignal;
import com.onesignal.OSDeviceState;
import com.onesignal.OSNotificationOpenedResult;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "LiveDateCall";
    private static final String HOME_URL = "https://freenote.kr/";
    private static final String ONESIGNAL_APP_ID = "bb7af6d9-e8c8-41d4-a25c-c4272f661e7c";
    private static final String EXTRA_FORCE_LOAD = "force_load";
    private static final String EXTRA_CALL_RETURN_URL = "return_url";
    private static final String CALL_RETURN_PREFS = "livedate_call_return";
    private static final String KEY_FORCE_RETURN_URL = "force_return_url";

    private WebView webView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ValueCallback<Uri[]> filePathCallback;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private int previousAudioMode = AudioManager.MODE_NORMAL;
    private boolean previousSpeakerphoneOn = false;

    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ActivityResultLauncher<Intent> nativeCallLauncher;
    private ActivityResultLauncher<String[]> permissionLauncher;
    private PermissionRequest pendingWebPermissionRequest;
    private String pendingNativeCallRoom = "";
    private String pendingNativeCallRole = "";
    private boolean nativeCallLaunching = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "MAIN onCreate taskId=" + getTaskId());

        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (filePathCallback == null) {
                        return;
                    }
                    Uri[] results = null;
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        if (data.getClipData() != null) {
                            int count = data.getClipData().getItemCount();
                            results = new Uri[count];
                            for (int i = 0; i < count; i++) {
                                results[i] = data.getClipData().getItemAt(i).getUri();
                            }
                        } else if (data.getData() != null) {
                            results = new Uri[]{data.getData()};
                        }
                    }
                    filePathCallback.onReceiveValue(results);
                    filePathCallback = null;
                }
        );

        nativeCallLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    nativeCallLaunching = false;
                    Intent data = result.getData();
                    String returnUrl = data == null ? "" : data.getStringExtra(EXTRA_CALL_RETURN_URL);
                    if (returnUrl == null || returnUrl.trim().isEmpty()) {
                        returnUrl = "https://freenote.kr/page_4.php";
                    }
                    if (webView != null) {
                        webView.clearHistory();
                        webView.loadUrl(returnUrl);
                    }
                }
        );

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    handlePendingWebPermissionRequest();
                    launchPendingNativeCallIfReady();
                }
        );

        initOneSignal();
        configureAudioForCall();

        setContentView(R.layout.activity_main);

        swipeRefreshLayout = findViewById(R.id.swipe_refresh);
        webView = findViewById(R.id.web_view);

        swipeRefreshLayout.setOnRefreshListener(webView::reload);
        swipeRefreshLayout.setOnChildScrollUpCallback((parent, child) -> webView.getScrollY() > 0);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setSupportMultipleWindows(true);
        webSettings.setBuiltInZoomControls(false);
        webSettings.setDisplayZoomControls(false);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setTextZoom(100);
        webSettings.setGeolocationEnabled(true);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
        webView.addJavascriptInterface(new LiveDateBridge(), "LiveDateNative");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleExternalScheme(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleExternalScheme(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                swipeRefreshLayout.setRefreshing(false);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
            ) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                Intent intent;
                if (fileChooserParams != null && fileChooserParams.isCaptureEnabled()) {
                    intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
                    intent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1);
                } else {
                    // Prefer gallery app for "파일로 올리기" to avoid device-dependent chooser behavior.
                    intent = new Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
                    intent.setType("video/*");
                    if (intent.resolveActivity(getPackageManager()) == null) {
                        try {
                            intent = fileChooserParams.createIntent();
                        } catch (Exception e) {
                            MainActivity.this.filePathCallback = null;
                            Toast.makeText(MainActivity.this, "파일 선택기를 열 수 없습니다.", Toast.LENGTH_SHORT).show();
                            return false;
                        }
                    }
                }

                try {
                    fileChooserLauncher.launch(intent);
                } catch (ActivityNotFoundException e) {
                    MainActivity.this.filePathCallback = null;
                    Toast.makeText(MainActivity.this, "파일 선택 앱을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                boolean granted = hasAnyLocationPermission();
                callback.invoke(origin, granted, false);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    List<String> missing = new ArrayList<>();
                    String[] resources = request.getResources();
                    if (resources != null) {
                        for (String resource : resources) {
                            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) && !hasCameraPermission()) {
                                addIfMissing(missing, Manifest.permission.CAMERA);
                            }
                            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource) && !hasAudioPermission()) {
                                addIfMissing(missing, Manifest.permission.RECORD_AUDIO);
                            }
                        }
                    }
                    if (!missing.isEmpty()) {
                        pendingWebPermissionRequest = request;
                        permissionLauncher.launch(missing.toArray(new String[0]));
                        return;
                    }
                    if (resources != null) {
                        request.grant(resources);
                    } else {
                        request.deny();
                    }
                });
            }
        });

        requestRequiredPermissions();
        applyLaunchIntent(getIntent());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    webView.loadUrl("https://freenote.kr/page_4.php");
                }
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Log.i(TAG, "MAIN onNewIntent taskId=" + getTaskId());
        applyLaunchIntent(intent);
    }

    private void applyLaunchIntent(Intent intent) {
        if (webView == null) return;
        String targetUrl = resolveLaunchUrl(intent);
        boolean forceLoad = intent != null && intent.getBooleanExtra(EXTRA_FORCE_LOAD, false);
        Log.i(TAG, "MAIN applyLaunchIntent forceLoad=" + forceLoad + " target=" + targetUrl);
        if (forceLoad) {
            webView.clearHistory();
            webView.loadUrl(targetUrl);
            return;
        }
        if (!targetUrl.equals(webView.getUrl())) {
            webView.loadUrl(targetUrl);
        }
    }

    private void initOneSignal() {
        OneSignal.setNotificationOpenedHandler(this::handleNotificationOpened);
        OneSignal.setLogLevel(OneSignal.LOG_LEVEL.VERBOSE, OneSignal.LOG_LEVEL.NONE);
        OneSignal.initWithContext(this);
        OneSignal.setAppId(ONESIGNAL_APP_ID);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            OneSignal.promptForPushNotifications();
        }
    }

    private void configureAudioForCall() {
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null) return;

        previousAudioMode = audioManager.getMode();
        previousSpeakerphoneOn = audioManager.isSpeakerphoneOn();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(focusChange -> {
                        // no-op
                    })
                    .build();
            audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            audioManager.requestAudioFocus(
                    focusChange -> {
                        // no-op
                    },
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            );
        }

        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(true);
    }

    private void restoreAudioMode() {
        if (audioManager == null) return;
        try {
            audioManager.setSpeakerphoneOn(previousSpeakerphoneOn);
            audioManager.setMode(previousAudioMode);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (audioFocusRequest != null) {
                    audioManager.abandonAudioFocusRequest(audioFocusRequest);
                }
            } else {
                audioManager.abandonAudioFocus(null);
            }
        } catch (Exception ignored) {
        }
    }

    private void handleNotificationOpened(OSNotificationOpenedResult result) {
        String targetUrl = "https://freenote.kr/page_4.php";
        try {
            if (result != null && result.getNotification() != null) {
                JSONObject data = result.getNotification().getAdditionalData();
                if (data != null) {
                    String url = data.optString("target_url", "").trim();
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        targetUrl = url;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("app_url", targetUrl);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private final class LiveDateBridge {
        @JavascriptInterface
        public void setExternalUserId(String userId) {
            String id = (userId == null) ? "" : userId.trim();
            if (id.isEmpty()) {
                return;
            }
            runOnUiThread(() -> OneSignal.setExternalUserId(id));
        }

        @JavascriptInterface
        public String getOneSignalDebug() {
            try {
                OSDeviceState state = OneSignal.getDeviceState();
                JSONObject obj = new JSONObject();
                if (state == null) {
                    obj.put("has_state", false);
                    return obj.toString();
                }
                obj.put("has_state", true);
                obj.put("subscribed", state.isSubscribed());
                obj.put("user_id", String.valueOf(state.getUserId()));
                obj.put("push_token", String.valueOf(state.getPushToken()));
                obj.put("external_user_id", "");
                obj.put("notification_permission", state.areNotificationsEnabled());
                return obj.toString();
            } catch (Exception e) {
                return "{\"has_state\":false,\"error\":\"" + e.getClass().getSimpleName() + "\"}";
            }
        }

        @JavascriptInterface
        public void openNativeCall(String room, String role) {
            final String safeRoom = room == null ? "" : room.trim();
            if (safeRoom.isEmpty()) return;
            final String safeRole = role == null ? "" : role.trim();
            runOnUiThread(() -> {
                if (nativeCallLaunching) return;
                if (!hasCameraPermission() || !hasAudioPermission()) {
                    pendingNativeCallRoom = safeRoom;
                    pendingNativeCallRole = safeRole;
                    List<String> missing = new ArrayList<>();
                    if (!hasCameraPermission()) missing.add(Manifest.permission.CAMERA);
                    if (!hasAudioPermission()) missing.add(Manifest.permission.RECORD_AUDIO);
                    if (!missing.isEmpty()) {
                        permissionLauncher.launch(missing.toArray(new String[0]));
                    }
                    return;
                }
                nativeCallLaunching = true;
                Intent intent = new Intent(MainActivity.this, CallActivity.class);
                intent.putExtra("room", safeRoom);
                intent.putExtra("role", safeRole);
                nativeCallLauncher.launch(intent);
            });
        }
    }

    private void launchPendingNativeCallIfReady() {
        if (pendingNativeCallRoom == null || pendingNativeCallRoom.trim().isEmpty()) return;
        if (!hasCameraPermission() || !hasAudioPermission()) {
            pendingNativeCallRoom = "";
            pendingNativeCallRole = "";
            Toast.makeText(this, "카메라/마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show();
            openAppPermissionSettings();
            return;
        }
        if (nativeCallLaunching) return;
        nativeCallLaunching = true;
        Intent intent = new Intent(MainActivity.this, CallActivity.class);
        intent.putExtra("room", pendingNativeCallRoom);
        intent.putExtra("role", pendingNativeCallRole == null ? "" : pendingNativeCallRole);
        pendingNativeCallRoom = "";
        pendingNativeCallRole = "";
        nativeCallLauncher.launch(intent);
    }

    private void requestRequiredPermissions() {
        List<String> needed = new ArrayList<>();

        addIfMissing(needed, Manifest.permission.CAMERA);
        addIfMissing(needed, Manifest.permission.RECORD_AUDIO);
        addIfMissing(needed, Manifest.permission.ACCESS_FINE_LOCATION);
        addIfMissing(needed, Manifest.permission.ACCESS_COARSE_LOCATION);
        addIfMissing(needed, Manifest.permission.READ_CONTACTS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(needed, Manifest.permission.POST_NOTIFICATIONS);
            addIfMissing(needed, Manifest.permission.READ_MEDIA_IMAGES);
            addIfMissing(needed, Manifest.permission.READ_MEDIA_VIDEO);
        } else {
            addIfMissing(needed, Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        if (!needed.isEmpty()) {
            permissionLauncher.launch(needed.toArray(new String[0]));
        }
    }

    private void addIfMissing(List<String> needed, String permission) {
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            needed.add(permission);
        }
    }

    private boolean hasAnyLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void handlePendingWebPermissionRequest() {
        PermissionRequest request = pendingWebPermissionRequest;
        pendingWebPermissionRequest = null;
        if (request == null) return;

        String[] resources = request.getResources();
        boolean canGrant = true;
        if (resources != null) {
            for (String resource : resources) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) && !hasCameraPermission()) {
                    canGrant = false;
                    break;
                }
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource) && !hasAudioPermission()) {
                    canGrant = false;
                    break;
                }
            }
        } else {
            canGrant = false;
        }

        if (canGrant) {
            request.grant(resources);
        } else {
            request.deny();
            Toast.makeText(this, "카메라/마이크 권한을 허용해주세요.", Toast.LENGTH_SHORT).show();
            openAppPermissionSettings();
        }
    }

    private void openAppPermissionSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", getPackageName(), null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private boolean handleExternalScheme(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }

        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            return false;
        }

        if ("intent".equalsIgnoreCase(scheme)) {
            try {
                Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                if (intent != null) {
                    if (intent.resolveActivity(getPackageManager()) != null) {
                        startActivity(intent);
                        return true;
                    }
                    String fallbackUrl = intent.getStringExtra("browser_fallback_url");
                    if (fallbackUrl != null) {
                        webView.loadUrl(fallbackUrl);
                        return true;
                    }
                    String packageName = intent.getPackage();
                    if (packageName != null) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName)));
                        return true;
                    }
                }
            } catch (Exception e) {
                Toast.makeText(this, "외부 앱을 열 수 없습니다.", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
            return true;
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "지원되지 않는 링크입니다.", Toast.LENGTH_SHORT).show();
            return true;
        }
    }

    private String resolveLaunchUrl(Intent intent) {
        if (intent == null) return HOME_URL;
        Uri data = intent.getData();
        if (data != null) {
            String url = data.toString();
            if (url.startsWith("http://") || url.startsWith("https://")) {
                return url;
            }
            if (url.startsWith("livedate://")) {
                String path = data.getPath();
                if (path != null && !path.isEmpty()) {
                    return "https://freenote.kr" + path;
                }
                return HOME_URL;
            }
        }

        String fromExtras = extractUrlFromIntentExtras(intent);
        if (fromExtras != null && !fromExtras.isEmpty()) {
            return fromExtras;
        }

        String[] keys = new String[] {"url", "app_url", "launchURL", "launch_url"};
        for (String key : keys) {
            String value = intent.getStringExtra(key);
            if (value != null) {
                String v = value.trim();
                if (v.startsWith("http://") || v.startsWith("https://")) {
                    return v;
                }
            }
        }

        // OneSignal push click without explicit URL: open message page by default.
        if (intent.getExtras() != null && !intent.getExtras().isEmpty()) {
            return "https://freenote.kr/page_4.php";
        }
        return HOME_URL;
    }

    private String extractUrlFromIntentExtras(Intent intent) {
        try {
            if (intent.getExtras() == null) return "";
            String[] keys = new String[] {"onesignalData", "custom", "os_data"};
            for (String key : keys) {
                String raw = intent.getStringExtra(key);
                if (raw == null || raw.trim().isEmpty()) continue;
                String url = extractUrlFromJsonString(raw);
                if (!url.isEmpty()) return url;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private String extractUrlFromJsonString(String raw) {
        try {
            JSONObject obj = new JSONObject(raw);
            String[] directKeys = new String[] {"u", "url", "app_url", "launchURL", "launch_url", "target_url"};
            for (String key : directKeys) {
                String v = obj.optString(key, "").trim();
                if (v.startsWith("http://") || v.startsWith("https://")) {
                    return v;
                }
            }

            // OneSignal custom payload pattern: {"a": {...}}
            JSONObject a = obj.optJSONObject("a");
            if (a != null) {
                for (String key : directKeys) {
                    String v = a.optString(key, "").trim();
                    if (v.startsWith("http://") || v.startsWith("https://")) {
                        return v;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "MAIN onResume taskId=" + getTaskId() + " url=" + (webView == null ? "null" : webView.getUrl()));
        applyPendingForcedReturnUrl();
        nativeCallLaunching = false;
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.i(TAG, "MAIN onStart taskId=" + getTaskId());
    }

    @Override
    protected void onPause() {
        Log.i(TAG, "MAIN onPause taskId=" + getTaskId());
        super.onPause();
    }

    @Override
    protected void onStop() {
        Log.i(TAG, "MAIN onStop taskId=" + getTaskId());
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "MAIN onDestroy taskId=" + getTaskId());
        restoreAudioMode();
        nativeCallLaunching = false;
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    private void applyPendingForcedReturnUrl() {
        if (webView == null) return;
        try {
            SharedPreferences prefs = getSharedPreferences(CALL_RETURN_PREFS, MODE_PRIVATE);
            String forceUrl = prefs.getString(KEY_FORCE_RETURN_URL, "").trim();
            if (forceUrl.isEmpty()) return;
            prefs.edit().remove(KEY_FORCE_RETURN_URL).apply();
            Log.i(TAG, "MAIN applyPendingForcedReturnUrl=" + forceUrl);
            webView.clearHistory();
            webView.loadUrl(forceUrl);
        } catch (Exception e) {
            Log.i(TAG, "MAIN applyPendingForcedReturnUrl failed=" + e.getClass().getSimpleName());
        }
    }
}
