package kr.freenote.livedate;

import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpSender;
import org.webrtc.RtpReceiver;
import org.webrtc.RTCStats;
import org.webrtc.RTCStatsCollectorCallback;
import org.webrtc.RTCStatsReport;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.AudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public class CallActivity extends AppCompatActivity {
    private static final String TAG = "LiveDateCall";
    private static final double REMOTE_AUDIO_TRACK_VOLUME = 8.0;
    private static final int SIGNAL_CONNECT_TIMEOUT_MS = 10000;
    private static final int SIGNAL_READ_TIMEOUT_MS = 10000;
    private static final int POLL_CONNECT_TIMEOUT_MS = 5000;
    private static final int POLL_READ_TIMEOUT_MS = 30000;
    private static final long OFFER_START_DELAY_MS = 50L;
    private static final long MAIN_RETURN_FINISH_DELAY_MS = 700L;
    private static final String BASE_URL = "https://freenote.kr";
    private static final String PUSH_ENDPOINT = BASE_URL + "/toast/call_signal_push.php";
    private static final String PULL_ENDPOINT = BASE_URL + "/toast/call_signal_pull.php";
    private static final String RESET_ENDPOINT = BASE_URL + "/toast/call_signal_reset.php";
    private static final String ICE_SERVERS_ENDPOINT = BASE_URL + "/toast/ice_servers.php";
    private static final String CALL_RETURN_PREFS = "livedate_call_return";
    private static final String KEY_FORCE_RETURN_URL = "force_return_url";
    private static final String RETURN_URL_PAGE4 = "https://freenote.kr/page_4.php";

    private SurfaceViewRenderer remoteRenderer;
    private SurfaceViewRenderer localRenderer;
    private TextView statusText;
    private Button micBtn;
    private Button camBtn;
    private Button endBtn;

    private EglBase eglBase;
    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;
    private MediaStream localMediaStream;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private VideoCapturer videoCapturer;
    private SurfaceTextureHelper surfaceTextureHelper;
    private AudioTrack remoteAudioTrack;
    private AudioDeviceModule audioDeviceModule;

    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private int previousAudioMode = AudioManager.MODE_NORMAL;
    private boolean previousSpeakerphoneOn = false;
    private int previousVoiceCallVolume = -1;
    private int previousMusicVolume = -1;
    private boolean audioStateCaptured = false;
    private boolean audioFocusGranted = false;
    private boolean audioFocusLost = false;
    private boolean audioDeviceCallbackRegistered = false;
    private volatile PeerConnection.IceConnectionState currentIceState = PeerConnection.IceConnectionState.NEW;
    private volatile int lastAudioFocusChange = Integer.MIN_VALUE;
    private final AudioManager.OnAudioFocusChangeListener callAudioFocusChangeListener = focusChange -> {
        lastAudioFocusChange = focusChange;
        Log.i(TAG, ts() + " AUDIO_FOCUS_CHANGE=" + focusChange);
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            audioFocusLost = true;
            logAudioManagerState("focusLoss");
            return;
        }
        if (focusChange == AudioManager.AUDIOFOCUS_GAIN && audioFocusLost) {
            audioFocusLost = false;
            enforceCallAudioRoute("focusRegained");
            logAudioManagerState("focusRegained");
        }
    };
    private final AudioDeviceCallback callAudioDeviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            handleAudioDeviceChange("audioDeviceAdded");
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            handleAudioDeviceChange("audioDeviceRemoved");
        }
    };

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService pollExecutor = Executors.newSingleThreadScheduledExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Set<Long> ownSignalIds = Collections.synchronizedSet(new HashSet<>());
    private final List<IceCandidate> pendingRemoteCandidates = Collections.synchronizedList(new ArrayList<>());
    private long sinceId = 0L;
    private boolean isCaller = false;
    private boolean micOn = true;
    private boolean camOn = true;
    private String room = "";
    private String cookieHeader = "";
    private volatile boolean finishing = false;
    private volatile boolean remoteDescriptionSet = false;
    private volatile boolean audioSenderRefreshed = false;
    private volatile boolean makingOffer = false;
    private volatile boolean polite = true;
    private volatile long lastIceRestartAt = 0L;
    private volatile PeerConnection.SignalingState currentSignalingState = PeerConnection.SignalingState.STABLE;
    private volatile boolean forceRelayPolicy = false;
    private final AtomicBoolean returningToInbox = new AtomicBoolean(false);
    private int localHostCandidates = 0;
    private int localSrflxCandidates = 0;
    private int localRelayCandidates = 0;
    private int remoteHostCandidates = 0;
    private int remoteSrflxCandidates = 0;
    private int remoteRelayCandidates = 0;
    private volatile boolean statsLoopRunning = false;
    private int statsLoopCount = 0;
    private long prevInBytes = -1L;
    private long prevOutBytes = -1L;
    private int unchangedInCount = 0;
    private int unchangedOutCount = 0;
    private boolean oneWaySuspectLogged = false;
    private final Runnable statsLoopRunnable = new Runnable() {
        @Override
        public void run() {
            if (finishing || peerConnection == null || !statsLoopRunning) return;
            collectAndLogSelectedPairStats();
            statsLoopCount++;
            if (statsLoopCount < 10) {
                mainHandler.postDelayed(this, 2000L);
            } else {
                statsLoopRunning = false;
            }
        }
    };

    private void attachRemoteAudioTrack(AudioTrack track) {
        if (track == null) return;
        remoteAudioTrack = track;
        remoteAudioTrack.setEnabled(true);
        remoteAudioTrack.setVolume(REMOTE_AUDIO_TRACK_VOLUME);
        enforceCallAudioRoute("remoteAudioAttach");
        scheduleDelayedSpeakerReapply("remoteAudioAttach", 300L);
        logRemoteAudioState("attachRemoteAudioTrack");
        logAudioManagerState("attachRemoteAudioTrack");
        setStatus("상대 오디오 수신 중...");
    }

    private void attachRemoteVideoTrack(VideoTrack track) {
        if (track == null) return;
        mainHandler.post(() -> track.addSink(remoteRenderer));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call_native);

        remoteRenderer = findViewById(R.id.remote_renderer);
        localRenderer = findViewById(R.id.local_renderer);
        statusText = findViewById(R.id.call_status_text);
        micBtn = findViewById(R.id.btn_mic);
        camBtn = findViewById(R.id.btn_cam);
        endBtn = findViewById(R.id.btn_end);

        String rawRoom = getIntent().getStringExtra("room");
        room = rawRoom == null ? "" : rawRoom.trim();
        String rawRole = getIntent().getStringExtra("role");
        String role = rawRole == null ? "" : rawRole.trim();
        isCaller = "caller".equalsIgnoreCase(role);
        polite = !isCaller;

        if (room == null || room.trim().isEmpty()) {
            finish();
            return;
        }

        cookieHeader = CookieManager.getInstance().getCookie(BASE_URL);
        initCallAudioRouting();
        enforceCallAudioRoute("onCreate");
        logAudioManagerState("onCreate");
        setStatus("통화 초기화 중...");
        setupButtons();
        networkExecutor.execute(() -> {
            List<PeerConnection.IceServer> fetched = fetchIceServersFromServer();
            List<PeerConnection.IceServer> initialIce = (fetched == null || fetched.isEmpty())
                    ? Collections.singletonList(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
                    : fetched;
            if (isCaller) {
                resetSignalsAsCaller();
            }
            mainHandler.post(() -> {
                if (finishing) return;
                initWebRtc(initialIce);
                if (isCaller) {
                    startPolling();
                    mainHandler.postDelayed(this::createAndSendOffer, OFFER_START_DELAY_MS);
                } else {
                    startPolling();
                    setStatus("상대 Offer 대기 중...");
                }
            });
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        enforceCallAudioRoute("onStart");
        logAudioManagerState("onStart");
    }

    @Override
    protected void onResume() {
        super.onResume();
        logAudioManagerState("onResume");
    }

    @Override
    protected void onPause() {
        logAudioManagerState("onPause");
        super.onPause();
    }

    @Override
    protected void onStop() {
        logAudioManagerState("onStop");
        super.onStop();
    }

    private void setupButtons() {
        micBtn.setOnClickListener(v -> {
            micOn = !micOn;
            if (localAudioTrack != null) localAudioTrack.setEnabled(micOn);
            micBtn.setText(micOn ? "마이크 끄기" : "마이크 켜기");
            Log.i(TAG, ts() + " MIC_TOGGLE only-enabled-change=true no-track-stop=true micOn=" + micOn);
            logLocalAudioState("micToggle");
        });
        camBtn.setOnClickListener(v -> {
            camOn = !camOn;
            if (localVideoTrack != null) localVideoTrack.setEnabled(camOn);
            camBtn.setText(camOn ? "카메라 끄기" : "카메라 켜기");
        });
        endBtn.setOnClickListener(v -> {
            sendSignalAsync("bye", new JSONObject());
            returnToInboxAndFinish();
        });
    }

    private void initWebRtc(List<PeerConnection.IceServer> iceServers) {
        eglBase = EglBase.create();
        remoteRenderer.init(eglBase.getEglBaseContext(), null);
        remoteRenderer.setMirror(false);
        localRenderer.init(eglBase.getEglBaseContext(), null);
        localRenderer.setMirror(true);

        PeerConnectionFactory.InitializationOptions initOptions =
                PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions();
        PeerConnectionFactory.initialize(initOptions);

        DefaultVideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(
                eglBase.getEglBaseContext(), true, true);
        DefaultVideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

        audioDeviceModule = JavaAudioDeviceModule.builder(this)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule();

        peerConnectionFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();

        videoCapturer = createVideoCapturer();
        if (videoCapturer == null) {
            setStatus("카메라 초기화 실패");
            return;
        }

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
        videoSource = peerConnectionFactory.createVideoSource(false);
        videoCapturer.initialize(surfaceTextureHelper, this, videoSource.getCapturerObserver());
        try {
            videoCapturer.startCapture(640, 480, 24);
        } catch (Exception ignored) {
        }
        localVideoTrack = peerConnectionFactory.createVideoTrack("LOCAL_VIDEO", videoSource);
        localVideoTrack.setEnabled(true);
        localVideoTrack.addSink(localRenderer);

        MediaConstraints audioConstraints = new MediaConstraints();
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
        audioSource = peerConnectionFactory.createAudioSource(audioConstraints);
        localAudioTrack = peerConnectionFactory.createAudioTrack("LOCAL_AUDIO", audioSource);
        localAudioTrack.setEnabled(true);

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(iceServers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        if (forceRelayPolicy) {
            config.iceTransportsType = PeerConnection.IceTransportsType.RELAY;
        }
        logRtcConfig(config, iceServers);
        peerConnection = peerConnectionFactory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                currentSignalingState = signalingState;
                Log.i(TAG, ts() + " signalingState=" + signalingState + " localAudioTracks=" + localAudioTrackCount() + " remoteAudioTracks=" + remoteAudioTrackCount());
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                currentIceState = iceConnectionState;
                setStatus("ICE: " + iceConnectionState.name());
                Log.i(TAG, ts() + " iceConnectionState=" + iceConnectionState + " localAudioTracks=" + localAudioTrackCount() + " remoteAudioTracks=" + remoteAudioTrackCount());
                if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED
                        || iceConnectionState == PeerConnection.IceConnectionState.COMPLETED) {
                    enforceCallAudioRoute("iceConnected");
                    scheduleDelayedSpeakerReapply("iceConnected", 300L);
                }
                logAudioManagerState("onIceConnectionChange");
                logLocalAudioState("onIceConnectionChange");
                logRemoteAudioState("onIceConnectionChange");
                if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED
                        || iceConnectionState == PeerConnection.IceConnectionState.FAILED) {
                    maybeRestartIce();
                }
                if ((iceConnectionState == PeerConnection.IceConnectionState.CONNECTED
                        || iceConnectionState == PeerConnection.IceConnectionState.COMPLETED)
                        && !audioSenderRefreshed) {
                    audioSenderRefreshed = true;
                    refreshLocalAudioSenderAsync();
                }
                if ((iceConnectionState == PeerConnection.IceConnectionState.CONNECTED
                        || iceConnectionState == PeerConnection.IceConnectionState.COMPLETED)
                        && !statsLoopRunning) {
                    statsLoopRunning = true;
                    statsLoopCount = 0;
                    prevInBytes = -1L;
                    prevOutBytes = -1L;
                    unchangedInCount = 0;
                    unchangedOutCount = 0;
                    oneWaySuspectLogged = false;
                    mainHandler.postDelayed(statsLoopRunnable, 1000L);
                }
            }

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
                setStatus("연결: " + newState.name());
                Log.i(TAG, ts() + " connectionState=" + newState + " localAudioTracks=" + localAudioTrackCount() + " remoteAudioTracks=" + remoteAudioTrackCount());
                if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                    enforceCallAudioRoute("peerConnected");
                    scheduleDelayedSpeakerReapply("peerConnectedUi", 300L);
                }
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) { }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                Log.i(TAG, ts() + " iceGatheringState=" + iceGatheringState + " localAudioTracks=" + localAudioTrackCount() + " remoteAudioTracks=" + remoteAudioTrackCount());
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                try {
                    String sdp = iceCandidate == null ? "" : iceCandidate.sdp;
                    String cType = candidateTypeOf(sdp);
                    countCandidateType(sdp, true);
                    Log.i(TAG, ts() + " onIceCandidate type=" + cType + " sample=" + safeCandidateSample(sdp));
                    JSONObject payload = new JSONObject();
                    payload.put("candidate", sdp);
                    payload.put("sdpMid", iceCandidate.sdpMid);
                    payload.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
                    sendSignalAsync("candidate", payload);
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) { }
            @Override
            public void onAddStream(MediaStream mediaStream) {
                if (mediaStream == null) return;
                int aCount = (mediaStream.audioTracks == null) ? 0 : mediaStream.audioTracks.size();
                int vCount = (mediaStream.videoTracks == null) ? 0 : mediaStream.videoTracks.size();
                Log.i(TAG, ts() + " onAddStream id=" + mediaStreamId(mediaStream) + " audioTracks=" + aCount + " videoTracks=" + vCount);
                if (mediaStream.videoTracks != null && !mediaStream.videoTracks.isEmpty()) {
                    attachRemoteVideoTrack(mediaStream.videoTracks.get(0));
                }
                if (mediaStream.audioTracks != null && !mediaStream.audioTracks.isEmpty()) {
                    attachRemoteAudioTrack(mediaStream.audioTracks.get(0));
                }
            }

            @Override
            public void onRemoveStream(MediaStream mediaStream) { }

            @Override
            public void onDataChannel(DataChannel dataChannel) { }

            @Override
            public void onRenegotiationNeeded() {
                Log.i(TAG, ts() + " onRenegotiationNeeded");
            }

            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                if (rtpReceiver.track() instanceof VideoTrack) {
                    Log.i(TAG, ts() + " onAddTrack kind=video streamCount=" + (mediaStreams == null ? 0 : mediaStreams.length));
                    attachRemoteVideoTrack((VideoTrack) rtpReceiver.track());
                    return;
                }
                if (rtpReceiver.track() instanceof AudioTrack) {
                    Log.i(TAG, ts() + " onAddTrack kind=audio streamCount=" + (mediaStreams == null ? 0 : mediaStreams.length));
                    attachRemoteAudioTrack((AudioTrack) rtpReceiver.track());
                    return;
                }
                if (rtpReceiver.track() != null) {
                    setStatus("원격 트랙: " + rtpReceiver.track().kind());
                    Log.i(TAG, ts() + " remoteTrack kind=" + rtpReceiver.track().kind());
                }
            }
        });

        if (peerConnection != null) {
            localMediaStream = peerConnectionFactory.createLocalMediaStream("LOCAL_STREAM");
            localMediaStream.addTrack(localVideoTrack);
            localMediaStream.addTrack(localAudioTrack);
            peerConnection.addTrack(localVideoTrack, Collections.singletonList(localMediaStream.getId()));
            peerConnection.addTrack(localAudioTrack, Collections.singletonList(localMediaStream.getId()));
            logLocalAudioState("afterLocalTrackAdd");
        }
    }

    private void initCallAudioRouting() {
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null) return;
        if (!audioStateCaptured) {
            previousAudioMode = audioManager.getMode();
            previousSpeakerphoneOn = audioManager.isSpeakerphoneOn();
            try {
                previousVoiceCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
                previousMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            } catch (Exception ignored) {
            }
            audioStateCaptured = true;
        }
        if (!audioDeviceCallbackRegistered) {
            try {
                audioManager.registerAudioDeviceCallback(callAudioDeviceCallback, mainHandler);
                audioDeviceCallbackRegistered = true;
            } catch (Exception ignored) {
            }
        }
    }

    private void enforceCallAudioRoute(String reason) {
        initCallAudioRouting();
        if (audioManager == null) return;
        try {
            if (!audioFocusGranted) {
                int focusResult;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    AudioAttributes attrs = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build();
                    audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(attrs)
                            .setAcceptsDelayedFocusGain(false)
                            .setOnAudioFocusChangeListener(callAudioFocusChangeListener)
                            .build();
                    focusResult = audioManager.requestAudioFocus(audioFocusRequest);
                } else {
                    focusResult = audioManager.requestAudioFocus(
                            callAudioFocusChangeListener,
                            AudioManager.STREAM_VOICE_CALL,
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                    );
                }
                audioFocusGranted = (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                Log.i(TAG, ts() + " AUDIO_FOCUS reason=" + reason + " result=" + focusResult);
            }

            // 1) MODE_IN_COMMUNICATION first
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            logAudioManagerState("enforce_afterSetMode_" + reason);

            boolean externalConnected = hasWiredOrBluetoothOutput();
            if (externalConnected) {
                Log.i(TAG, ts() + " AUDIO_ROUTE_SKIP reason=" + reason + " externalConnected=true");
                audioManager.setSpeakerphoneOn(false);
                logAudioManagerState("enforce_afterSetSpeaker_false_" + reason);
            } else {
                // 2) Speakerphone fallback force
                audioManager.setSpeakerphoneOn(true);
                logAudioManagerState("enforce_afterSetSpeaker_true_" + reason);

                // 3) Android 12+ communication device force to BUILTIN_SPEAKER
                boolean commDeviceSet = trySetCommunicationDeviceToSpeaker();
                Log.i(TAG, ts() + " AUDIO_COMM_DEVICE reason=" + reason + " speakerSetResult=" + commDeviceSet);
                logAudioManagerState("enforce_afterSetCommDevice_" + reason);
            }

            try {
                int maxVoice = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                int maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoice, 0);
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0);
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
        logAudioManagerState("enforceCallAudioRoute_" + reason);
    }

    private void handleAudioDeviceChange(String reason) {
        if (finishing) return;
        enforceCallAudioRoute(reason);
    }

    private void scheduleDelayedSpeakerReapply(String reason, long delayMs) {
        mainHandler.postDelayed(() -> {
            if (finishing) return;
            enforceCallAudioRoute(reason + "_delay");
            logAudioManagerState(reason + "_delayApplied");
        }, delayMs);
    }

    private boolean hasWiredOrBluetoothOutput() {
        if (audioManager == null) return false;
        try {
            AudioDeviceInfo[] outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            if (outputs == null) return false;
            for (AudioDeviceInfo dev : outputs) {
                if (dev == null) continue;
                int type = dev.getType();
                if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean trySetCommunicationDeviceToSpeaker() {
        if (audioManager == null) return false;
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return false;
        try {
            AudioDeviceInfo target = null;
            for (AudioDeviceInfo dev : audioManager.getAvailableCommunicationDevices()) {
                if (dev == null) continue;
                int type = dev.getType();
                if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    target = dev;
                    break;
                }
            }
            if (target != null) {
                return audioManager.setCommunicationDevice(target);
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void restoreAudioMode() {
        if (audioManager == null) return;
        try {
            if (audioDeviceCallbackRegistered) {
                try {
                    audioManager.unregisterAudioDeviceCallback(callAudioDeviceCallback);
                } catch (Exception ignored) {
                }
                audioDeviceCallbackRegistered = false;
            }
            audioManager.setSpeakerphoneOn(previousSpeakerphoneOn);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                try { audioManager.clearCommunicationDevice(); } catch (Exception ignored) {}
            }
            if (previousVoiceCallVolume >= 0) {
                try { audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, previousVoiceCallVolume, 0); } catch (Exception ignored) {}
            }
            if (previousMusicVolume >= 0) {
                try { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousMusicVolume, 0); } catch (Exception ignored) {}
            }
            audioManager.setMode(previousAudioMode);
            if (audioFocusGranted) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    if (audioFocusRequest != null) {
                        audioManager.abandonAudioFocusRequest(audioFocusRequest);
                    }
                } else {
                    audioManager.abandonAudioFocus(null);
                }
                audioFocusGranted = false;
            }
        } catch (Exception ignored) {
        }
        logAudioManagerState("restoreAudioMode");
    }

    private VideoCapturer createVideoCapturer() {
        CameraEnumerator enumerator;
        if (Camera2Enumerator.isSupported(this)) {
            enumerator = new Camera2Enumerator(this);
        } else {
            enumerator = new Camera1Enumerator(true);
        }
        String[] names = enumerator.getDeviceNames();
        for (String name : names) {
            if (enumerator.isFrontFacing(name)) {
                VideoCapturer capturer = enumerator.createCapturer(name, null);
                if (capturer != null) return capturer;
            }
        }
        for (String name : names) {
            if (!enumerator.isFrontFacing(name)) {
                VideoCapturer capturer = enumerator.createCapturer(name, null);
                if (capturer != null) return capturer;
            }
        }
        return null;
    }

    private void createAndSendOffer() {
        if (peerConnection == null) return;
        MediaConstraints mc = new MediaConstraints();
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        makingOffer = true;
        peerConnection.createOffer(new SdpAdapter("createOffer") {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                if (peerConnection == null) return;
                peerConnection.setLocalDescription(new SdpAdapter("setLocalOffer") {
                    @Override
                    public void onSetSuccess() {
                        makingOffer = false;
                        sendSdpSignal("offer", sdp);
                    }
                    @Override
                    public void onSetFailure(String s) {
                        makingOffer = false;
                    }
                }, sdp);
            }
            @Override
            public void onCreateFailure(String s) {
                makingOffer = false;
            }
        }, mc);
    }

    private void createAndSendAnswer() {
        if (peerConnection == null) return;
        MediaConstraints mc = new MediaConstraints();
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        peerConnection.createAnswer(new SdpAdapter("createAnswer") {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                if (peerConnection == null) return;
                peerConnection.setLocalDescription(new SdpAdapter("setLocalAnswer") {
                    @Override
                    public void onSetSuccess() {
                        sendSdpSignal("answer", sdp);
                    }
                }, sdp);
            }
        }, mc);
    }

    private void sendSdpSignal(String type, SessionDescription sdp) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("type", sdp.type.canonicalForm());
            payload.put("sdp", sdp.description);
            sendSignalAsync(type, payload);
        } catch (Exception ignored) {
        }
    }

    private void sendSignalAsync(String type, JSONObject payload) {
        networkExecutor.execute(() -> {
            try {
                String body = "room=" + URLEncoder.encode(room, "UTF-8")
                        + "&type=" + URLEncoder.encode(type, "UTF-8")
                        + "&msg_uuid=" + URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8")
                        + "&payload=" + URLEncoder.encode(payload.toString(), "UTF-8");
                HttpURLConnection conn = (HttpURLConnection) new URL(PUSH_ENDPOINT).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                if (cookieHeader != null && !cookieHeader.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookieHeader);
                }
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                String res = readResponse(conn);
                JSONObject obj = new JSONObject(res);
                long id = obj.optLong("id", 0L);
                if (id > 0) ownSignalIds.add(id);
                conn.disconnect();
            } catch (Exception ignored) {
            }
        });
    }

    private void resetSignalsAsCaller() {
        long startedAt = System.currentTimeMillis();
        try {
            String body = "room=" + URLEncoder.encode(room, "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(RESET_ENDPOINT).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(SIGNAL_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(SIGNAL_READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            if (cookieHeader != null && !cookieHeader.isEmpty()) {
                conn.setRequestProperty("Cookie", cookieHeader);
            }
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            readResponse(conn);
            conn.disconnect();
            Log.i(TAG, ts() + " RESET_SIGNALS done elapsedMs=" + (System.currentTimeMillis() - startedAt));
        } catch (Exception ignored) {
            Log.i(TAG, ts() + " RESET_SIGNALS failed elapsedMs=" + (System.currentTimeMillis() - startedAt));
        }
    }

    private List<PeerConnection.IceServer> fetchIceServersFromServer() {
        List<PeerConnection.IceServer> out = new ArrayList<>();
        HttpURLConnection conn = null;
        long startedAt = System.currentTimeMillis();
        try {
            conn = (HttpURLConnection) new URL(ICE_SERVERS_ENDPOINT).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(SIGNAL_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(SIGNAL_READ_TIMEOUT_MS);
            if (cookieHeader != null && !cookieHeader.isEmpty()) {
                conn.setRequestProperty("Cookie", cookieHeader);
            }
            String res = readResponse(conn);
            JSONObject root = new JSONObject(res);
            if (!root.optBoolean("ok", false)) return out;
            forceRelayPolicy = root.optBoolean("force_relay", false);
            JSONArray arr = root.optJSONArray("ice_servers");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject row = arr.optJSONObject(i);
                if (row == null) continue;
                JSONArray urlsArr = row.optJSONArray("urls");
                List<String> urls = new ArrayList<>();
                if (urlsArr != null) {
                    for (int j = 0; j < urlsArr.length(); j++) {
                        String u = urlsArr.optString(j, "").trim();
                        if (!u.isEmpty()) urls.add(u);
                    }
                } else {
                    String one = row.optString("urls", "").trim();
                    if (!one.isEmpty()) urls.add(one);
                }
                if (urls.isEmpty()) continue;
                String user = row.optString("username", "").trim();
                String cred = row.optString("credential", "").trim();
                for (String u : urls) {
                    PeerConnection.IceServer.Builder b = PeerConnection.IceServer.builder(u);
                    if (!user.isEmpty()) b.setUsername(user);
                    if (!cred.isEmpty()) b.setPassword(cred);
                    out.add(b.createIceServer());
                }
            }
            Log.i(TAG, ts() + " ICE_SERVERS_FETCHED count=" + out.size() + " forceRelay=" + forceRelayPolicy + " elapsedMs=" + (System.currentTimeMillis() - startedAt));
        } catch (Exception ignored) {
            Log.i(TAG, ts() + " ICE_SERVERS_FETCH_FAILED elapsedMs=" + (System.currentTimeMillis() - startedAt));
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
        return out;
    }

    private void startPolling() {
        pollExecutor.execute(() -> {
            while (!finishing && !Thread.currentThread().isInterrupted()) {
                try {
                    String q = PULL_ENDPOINT
                            + "?room=" + URLEncoder.encode(room, "UTF-8")
                            + "&since_id=" + sinceId
                            + "&timeout_ms=25000";
                    HttpURLConnection conn = (HttpURLConnection) new URL(q).openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(POLL_CONNECT_TIMEOUT_MS);
                    conn.setReadTimeout(POLL_READ_TIMEOUT_MS);
                    if (cookieHeader != null && !cookieHeader.isEmpty()) {
                        conn.setRequestProperty("Cookie", cookieHeader);
                    }
                    String res = readResponse(conn);
                    conn.disconnect();

                    JSONObject root = new JSONObject(res);
                    if (!root.optBoolean("ok", false)) continue;
                    long maxId = root.optLong("max_id", sinceId);
                    JSONArray items = root.optJSONArray("items");
                    if (items != null) {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject it = items.optJSONObject(i);
                            if (it == null) continue;
                            long id = it.optLong("id", 0L);
                            if (ownSignalIds.contains(id)) continue;
                            handleSignalItem(it);
                        }
                    }
                    sinceId = Math.max(sinceId, maxId);
                } catch (Exception ignored) {
                    try { Thread.sleep(500L); } catch (Exception ignored2) {}
                }
            }
        });
    }

    private void handleSignalItem(@NonNull JSONObject item) {
        if (peerConnection == null) return;
        String type = item.optString("type", "");
        JSONObject payload = item.optJSONObject("payload");
        if (payload == null) payload = new JSONObject();

        try {
            switch (type) {
                case "offer": {
                    String sdp = payload.optString("sdp", "");
                    SessionDescription remote = new SessionDescription(SessionDescription.Type.OFFER, sdp);
                    boolean offerCollision = makingOffer || (currentSignalingState != PeerConnection.SignalingState.STABLE);
                    if (offerCollision && !polite) {
                        Log.i(TAG, ts() + " offerCollision ignored (impolite=true)");
                        break;
                    }
                    peerConnection.setRemoteDescription(new SdpAdapter("setRemoteOffer") {
                        @Override
                        public void onSetSuccess() {
                            remoteDescriptionSet = true;
                            flushPendingCandidates();
                            mainHandler.post(CallActivity.this::createAndSendAnswer);
                        }
                    }, remote);
                    break;
                }
                case "answer": {
                    String sdp = payload.optString("sdp", "");
                    SessionDescription remote = new SessionDescription(SessionDescription.Type.ANSWER, sdp);
                    peerConnection.setRemoteDescription(new SdpAdapter("setRemoteAnswer") {
                        @Override
                        public void onSetSuccess() {
                            remoteDescriptionSet = true;
                            flushPendingCandidates();
                        }
                    }, remote);
                    break;
                }
                case "candidate": {
                    String cand = payload.optString("candidate", "");
                    String mid = payload.optString("sdpMid", "");
                    int line = payload.optInt("sdpMLineIndex", 0);
                    IceCandidate candidate = new IceCandidate(mid, line, cand);
                    countCandidateType(cand, false);
                    Log.i(TAG, ts() + " remoteCandidate type=" + candidateTypeOf(cand) + " sample=" + safeCandidateSample(cand));
                    if (!remoteDescriptionSet || peerConnection.getRemoteDescription() == null) {
                        pendingRemoteCandidates.add(candidate);
                    } else {
                        peerConnection.addIceCandidate(candidate);
                    }
                    break;
                }
                case "bye": {
                    mainHandler.post(this::returnToInboxAndFinish);
                    break;
                }
                default:
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    private String readResponse(HttpURLConnection conn) throws Exception {
        InputStream is = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) return "{}";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private void setStatus(String text) {
        mainHandler.post(() -> statusText.setText(text));
    }

    private void countCandidateType(String sdp, boolean local) {
        String t = candidateTypeOf(sdp);
        if ("relay".equals(t)) {
            if (local) localRelayCandidates++; else remoteRelayCandidates++;
        } else if ("srflx".equals(t)) {
            if (local) localSrflxCandidates++; else remoteSrflxCandidates++;
        } else if ("host".equals(t)) {
            if (local) localHostCandidates++; else remoteHostCandidates++;
        }
        Log.d(TAG, "candidate(" + (local ? "local" : "remote") + "," + t + ") "
                + "L[h/s/r]=" + localHostCandidates + "/" + localSrflxCandidates + "/" + localRelayCandidates
                + " R[h/s/r]=" + remoteHostCandidates + "/" + remoteSrflxCandidates + "/" + remoteRelayCandidates);
    }

    private String candidateTypeOf(String sdp) {
        if (sdp == null) return "unknown";
        String src = sdp.toLowerCase();
        if (src.contains(" typ relay")) return "relay";
        if (src.contains(" typ srflx")) return "srflx";
        if (src.contains(" typ host")) return "host";
        return "unknown";
    }

    private void flushPendingCandidates() {
        if (peerConnection == null) return;
        synchronized (pendingRemoteCandidates) {
            for (IceCandidate c : pendingRemoteCandidates) {
                try {
                    peerConnection.addIceCandidate(c);
                } catch (Exception ignored) {
                }
            }
            pendingRemoteCandidates.clear();
        }
    }

    private void returnToInboxAndFinish() {
        if (!returningToInbox.compareAndSet(false, true)) {
            Log.i(TAG, ts() + " returnToInbox skipped duplicate call");
            return;
        }
        finishing = true;
        logAudioManagerState("hangupRequested_beforeFinish");
        try {
            SharedPreferences prefs = getSharedPreferences(CALL_RETURN_PREFS, MODE_PRIVATE);
            prefs.edit().putString(KEY_FORCE_RETURN_URL, RETURN_URL_PAGE4).apply();
        } catch (Exception e) {
            Log.i(TAG, ts() + " returnToInbox markPending failed=" + e.getClass().getSimpleName());
        }
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("app_url", RETURN_URL_PAGE4);
            intent.putExtra("force_load", true);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
            Log.i(TAG, ts() + " returnToInbox relaunchMain ok taskId=" + getTaskId());
        } catch (Exception e) {
            Log.i(TAG, ts() + " returnToInbox relaunchMain failed=" + e.getClass().getSimpleName());
        }
        Log.i(TAG, ts() + " returnToInbox finishOnly=false");
        if (!isFinishing()) finish();
    }

    private String ts() {
        return "t=" + System.currentTimeMillis();
    }

    private int localAudioTrackCount() {
        if (localMediaStream == null || localMediaStream.audioTracks == null) return 0;
        return localMediaStream.audioTracks.size();
    }

    private int remoteAudioTrackCount() {
        return remoteAudioTrack == null ? 0 : 1;
    }

    private String safeCandidateSample(String sdp) {
        if (sdp == null) return "";
        String flat = sdp.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() > 120 ? flat.substring(0, 120) + "..." : flat;
    }

    private void logRtcConfig(PeerConnection.RTCConfiguration config, List<PeerConnection.IceServer> iceServers) {
        List<String> urls = new ArrayList<>();
        if (iceServers != null) {
            for (PeerConnection.IceServer s : iceServers) {
                if (s == null || s.urls == null) continue;
                urls.addAll(s.urls);
            }
        }
        Log.i(TAG, ts() + " ICE_SERVERS_FINAL=" + joinStrings(urls));
        Log.i(TAG, ts() + " ICE_POLICY=" + config.iceTransportsType
                + " BUNDLE=" + config.bundlePolicy
                + " RTCPMUX=" + config.rtcpMuxPolicy
                + " CONTINUAL_GATHER=" + config.continualGatheringPolicy);
        Log.i(TAG, ts() + " initWebRtc forceRelay=" + forceRelayPolicy + " polite=" + polite + " iceServerCount=" + urls.size());
    }

    private void logLocalAudioState(String reason) {
        try {
            String senderState = "none";
            if (peerConnection != null) {
                for (RtpSender s : peerConnection.getSenders()) {
                    if (s != null && s.track() instanceof AudioTrack) {
                        senderState = "senderTrack=true senderEnabled=" + s.track().enabled();
                        break;
                    }
                }
            }
            if (localAudioTrack == null) {
                Log.i(TAG, ts() + " LOCAL_AUDIO reason=" + reason + " track=false " + senderState);
                return;
            }
            Log.i(TAG, ts() + " LOCAL_AUDIO reason=" + reason
                    + " track=true id=" + localAudioTrack.id()
                    + " enabled=" + localAudioTrack.enabled()
                    + " state=" + localAudioTrack.state()
                    + " " + senderState);
        } catch (Exception e) {
            Log.i(TAG, ts() + " LOCAL_AUDIO reason=" + reason + " logError=" + e.getMessage());
        }
    }

    private void logRemoteAudioState(String reason) {
        try {
            if (remoteAudioTrack == null) {
                Log.i(TAG, ts() + " REMOTE_AUDIO reason=" + reason + " track=false");
                return;
            }
            Log.i(TAG, ts() + " REMOTE_AUDIO reason=" + reason
                    + " track=true id=" + remoteAudioTrack.id()
                    + " enabled=" + remoteAudioTrack.enabled()
                    + " state=" + remoteAudioTrack.state()
                    + " appliedVolume=" + REMOTE_AUDIO_TRACK_VOLUME);
        } catch (Exception e) {
            Log.i(TAG, ts() + " REMOTE_AUDIO reason=" + reason + " logError=" + e.getMessage());
        }
    }

    private void logAudioManagerState(String reason) {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) {
                Log.i(TAG, ts() + " AUDIO reason=" + reason + " audioManager=null");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(ts()).append(" AUDIO reason=").append(reason)
                    .append(" mode=").append(am.getMode())
                    .append(" speaker=").append(am.isSpeakerphoneOn())
                    .append(" micMute=").append(am.isMicrophoneMute())
                    .append(" focusGranted=").append(audioFocusGranted)
                    .append(" focusLast=").append(audioFocusChangeName(lastAudioFocusChange))
                    .append(" ice=").append(currentIceState);
            try {
                int voice = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
                int voiceMax = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                int music = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                int musicMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                sb.append(" voice=").append(voice).append("/").append(voiceMax)
                        .append(" music=").append(music).append("/").append(musicMax);
            } catch (Exception ignored) {
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                try {
                    android.media.AudioDeviceInfo d = am.getCommunicationDevice();
                    sb.append(" commDev=").append(d == null ? "null" : d.getType());
                } catch (Exception ignored) {
                }
            }
            sb.append(" outputs=").append(describeOutputDevices(am));
            Log.i(TAG, sb.toString());
        } catch (Exception e) {
            Log.i(TAG, ts() + " AUDIO reason=" + reason + " logError=" + e.getMessage());
        }
    }

    private String audioFocusChangeName(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_GAIN) return "GAIN";
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) return "LOSS";
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) return "LOSS_TRANSIENT";
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) return "LOSS_CAN_DUCK";
        if (focusChange == Integer.MIN_VALUE) return "NONE";
        return String.valueOf(focusChange);
    }

    private String describeOutputDevices(AudioManager am) {
        try {
            AudioDeviceInfo[] outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            if (outputs == null || outputs.length == 0) return "[]";
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < outputs.length; i++) {
                AudioDeviceInfo d = outputs[i];
                if (d == null) continue;
                if (sb.length() > 1) sb.append(",");
                sb.append(d.getType());
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private void collectAndLogSelectedPairStats() {
        try {
            if (peerConnection == null) return;
            peerConnection.getStats(new RTCStatsCollectorCallback() {
                @Override
                public void onStatsDelivered(RTCStatsReport report) {
                    try {
                        Map<String, RTCStats> statsMap = report.getStatsMap();
                        Map<String, RTCStats> localCandidates = new java.util.HashMap<>();
                        Map<String, RTCStats> remoteCandidates = new java.util.HashMap<>();
                        RTCStats selectedPair = null;
                        long inPackets = 0L, inBytes = 0L, outPackets = 0L, outBytes = 0L, inLost = 0L;
                        String inJitter = "";

                        for (RTCStats s : statsMap.values()) {
                            String type = s.getType();
                            if ("local-candidate".equals(type)) {
                                localCandidates.put(s.getId(), s);
                            } else if ("remote-candidate".equals(type)) {
                                remoteCandidates.put(s.getId(), s);
                            } else if ("candidate-pair".equals(type)) {
                                Object state = s.getMembers().get("state");
                                Object selected = s.getMembers().get("selected");
                                Object nominated = s.getMembers().get("nominated");
                                boolean okState = state != null && "succeeded".equals(String.valueOf(state));
                                boolean isSelected = (selected instanceof Boolean && (Boolean) selected)
                                        || (nominated instanceof Boolean && (Boolean) nominated);
                                if (okState && isSelected) {
                                    selectedPair = s;
                                }
                            } else if ("inbound-rtp".equals(type)) {
                                String mediaType = valueAsString(s.getMembers().get("mediaType"));
                                if (mediaType.isEmpty()) mediaType = valueAsString(s.getMembers().get("kind"));
                                if ("audio".equals(mediaType)) {
                                    inPackets += valueAsLong(s.getMembers().get("packetsReceived"));
                                    inBytes += valueAsLong(s.getMembers().get("bytesReceived"));
                                    inLost += valueAsLong(s.getMembers().get("packetsLost"));
                                    Object jitterObj = s.getMembers().get("jitter");
                                    if (jitterObj != null) inJitter = String.valueOf(jitterObj);
                                }
                            } else if ("outbound-rtp".equals(type)) {
                                String mediaType = valueAsString(s.getMembers().get("mediaType"));
                                if (mediaType.isEmpty()) mediaType = valueAsString(s.getMembers().get("kind"));
                                if ("audio".equals(mediaType)) {
                                    outPackets += valueAsLong(s.getMembers().get("packetsSent"));
                                    outBytes += valueAsLong(s.getMembers().get("bytesSent"));
                                }
                            }
                        }

                        if (selectedPair != null) {
                            String localId = valueAsString(selectedPair.getMembers().get("localCandidateId"));
                            String remoteId = valueAsString(selectedPair.getMembers().get("remoteCandidateId"));
                            RTCStats lc = localCandidates.get(localId);
                            RTCStats rc = remoteCandidates.get(remoteId);
                            String localType = (lc == null) ? "unknown" : valueAsString(lc.getMembers().get("candidateType"));
                            String remoteType = (rc == null) ? "unknown" : valueAsString(rc.getMembers().get("candidateType"));
                            String proto = (lc == null) ? "unknown" : valueAsString(lc.getMembers().get("protocol"));
                            String lAddr = (lc == null) ? "" : (valueAsString(lc.getMembers().get("address")) + ":" + valueAsString(lc.getMembers().get("port")));
                            String rAddr = (rc == null) ? "" : (valueAsString(rc.getMembers().get("address")) + ":" + valueAsString(rc.getMembers().get("port")));
                            Log.i(TAG, ts() + " STATS_SELECTED_PAIR localType=" + localType + " remoteType=" + remoteType + " proto=" + proto + " local=" + lAddr + " remote=" + rAddr);
                        } else {
                            Log.i(TAG, ts() + " STATS_SELECTED_PAIR none");
                        }
                        Log.i(TAG, ts() + " STATS_AUDIO_IN packetsReceived=" + inPackets + " bytesReceived=" + inBytes + " jitter=" + inJitter + " packetsLost=" + inLost);
                        Log.i(TAG, ts() + " STATS_AUDIO_OUT packetsSent=" + outPackets + " bytesSent=" + outBytes);
                        detectAndLogOneWaySuspect(inBytes, outBytes);
                    } catch (Exception e) {
                        Log.i(TAG, ts() + " STATS_ERROR " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            Log.i(TAG, ts() + " STATS_REQUEST_ERROR " + e.getMessage());
        }
    }

    private long valueAsLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String valueAsString(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private void detectAndLogOneWaySuspect(long inBytes, long outBytes) {
        if (prevInBytes >= 0L) {
            unchangedInCount = (inBytes <= prevInBytes) ? (unchangedInCount + 1) : 0;
        }
        if (prevOutBytes >= 0L) {
            unchangedOutCount = (outBytes <= prevOutBytes) ? (unchangedOutCount + 1) : 0;
        }
        prevInBytes = inBytes;
        prevOutBytes = outBytes;

        // Start judging after ~6s (3 samples at 2s interval)
        if (oneWaySuspectLogged || statsLoopCount < 3) return;

        if (outBytes <= 0L || unchangedOutCount >= 2) {
            oneWaySuspectLogged = true;
            Log.w(TAG, ts() + " ONEWAY_SUSPECT: OUT=0 outBytes=" + outBytes + " unchangedOutCount=" + unchangedOutCount);
            return;
        }
        if (inBytes <= 0L || unchangedInCount >= 2) {
            oneWaySuspectLogged = true;
            Log.w(TAG, ts() + " ONEWAY_SUSPECT: IN=0 inBytes=" + inBytes + " unchangedInCount=" + unchangedInCount);
            return;
        }
        boolean remoteTrackMissing = (remoteAudioTrack == null) || !remoteAudioTrack.enabled();
        if (inBytes > 0L && remoteTrackMissing) {
            oneWaySuspectLogged = true;
            Log.w(TAG, ts() + " ONEWAY_SUSPECT: ATTACH/ROUTING inBytes=" + inBytes + " remoteTrack=" + (remoteAudioTrack != null));
            logRemoteAudioState("oneway_suspect_attach");
            logAudioManagerState("oneway_suspect_attach");
        }
    }

    private String joinStrings(List<String> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private String mediaStreamId(MediaStream stream) {
        if (stream == null) return "";
        try {
            java.lang.reflect.Field f = MediaStream.class.getField("id");
            Object v = f.get(stream);
            if (v != null) return String.valueOf(v);
        } catch (Exception ignored) {
        }
        try {
            java.lang.reflect.Method m = MediaStream.class.getMethod("getId");
            Object v = m.invoke(stream);
            if (v != null) return String.valueOf(v);
        } catch (Exception ignored) {
        }
        return "";
    }

    private void refreshLocalAudioSenderAsync() {
        networkExecutor.execute(() -> {
            try {
                if (peerConnectionFactory == null || peerConnection == null) return;
                try { Thread.sleep(350L); } catch (Exception ignored) {}
                MediaConstraints c = new MediaConstraints();
                c.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
                c.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
                c.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
                c.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));

                AudioSource newSource = peerConnectionFactory.createAudioSource(c);
                AudioTrack newTrack = peerConnectionFactory.createAudioTrack("LOCAL_AUDIO_REFRESH_" + System.currentTimeMillis(), newSource);
                newTrack.setEnabled(micOn);

                boolean replaced = false;
                for (RtpSender s : peerConnection.getSenders()) {
                    if (s != null && s.track() instanceof AudioTrack) {
                        s.setTrack(newTrack, false);
                        replaced = true;
                        break;
                    }
                }
                if (replaced) {
                    AudioTrack oldTrack = localAudioTrack;
                    AudioSource oldSource = audioSource;
                    localAudioTrack = newTrack;
                    audioSource = newSource;
                    if (oldTrack != null) {
                        try { oldTrack.dispose(); } catch (Exception ignored) {}
                    }
                    if (oldSource != null) {
                        try { oldSource.dispose(); } catch (Exception ignored) {}
                    }
                } else {
                    try { newTrack.dispose(); } catch (Exception ignored) {}
                    try { newSource.dispose(); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void maybeRestartIce() {
        long now = System.currentTimeMillis();
        if (now - lastIceRestartAt < 8000L) return;
        lastIceRestartAt = now;
        networkExecutor.execute(() -> {
            try {
                if (peerConnection == null || finishing) return;
                MediaConstraints mc = new MediaConstraints();
                mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
                mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
                mc.mandatory.add(new MediaConstraints.KeyValuePair("IceRestart", "true"));
                makingOffer = true;
                peerConnection.createOffer(new SdpAdapter("createOfferRestart") {
                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {
                        if (peerConnection == null) return;
                        peerConnection.setLocalDescription(new SdpAdapter("setLocalOfferRestart") {
                            @Override
                            public void onSetSuccess() {
                                makingOffer = false;
                                sendSdpSignal("offer", sdp);
                            }
                            @Override
                            public void onSetFailure(String s) {
                                makingOffer = false;
                            }
                        }, sdp);
                    }
                    @Override
                    public void onCreateFailure(String s) {
                        makingOffer = false;
                    }
                }, mc);
            } catch (Exception ignored) {
                makingOffer = false;
            }
        });
    }

    @Override
    public void onBackPressed() {
        sendSignalAsync("bye", new JSONObject());
        returnToInboxAndFinish();
    }

    @Override
    protected void onDestroy() {
        finishing = true;
        statsLoopRunning = false;
        mainHandler.removeCallbacks(statsLoopRunnable);
        pollExecutor.shutdownNow();
        networkExecutor.shutdownNow();
        logAudioManagerState("hangup_before_restore");

        try {
            if (videoCapturer != null) {
                videoCapturer.stopCapture();
                videoCapturer.dispose();
            }
        } catch (Exception ignored) {
        }
        if (surfaceTextureHelper != null) surfaceTextureHelper.dispose();
        if (localVideoTrack != null) localVideoTrack.dispose();
        if (localAudioTrack != null) localAudioTrack.dispose();
        if (localMediaStream != null) localMediaStream.dispose();
        if (remoteAudioTrack != null) remoteAudioTrack.dispose();
        if (videoSource != null) videoSource.dispose();
        if (audioSource != null) audioSource.dispose();
        if (peerConnection != null) peerConnection.close();
        if (peerConnectionFactory != null) peerConnectionFactory.dispose();
        if (audioDeviceModule != null) audioDeviceModule.release();
        if (remoteRenderer != null) remoteRenderer.release();
        if (localRenderer != null) localRenderer.release();
        if (eglBase != null) eglBase.release();
        restoreAudioMode();
        logAudioManagerState("hangup_after_restore");

        super.onDestroy();
    }
}
