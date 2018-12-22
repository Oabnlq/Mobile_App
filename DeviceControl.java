package com.example.mac.captureandsaveimage;

import android.app.Activity;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Switch;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.Toolbar;

import com.amazonaws.auth.CognitoCachingCredentialsProvider;
import com.amazonaws.mobileconnectors.iot.AWSIotKeystoreHelper;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttClientStatusCallback;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttLastWillAndTestament;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttManager;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttNewMessageCallback;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttQos;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.iot.AWSIotClient;
import com.amazonaws.services.iot.model.AttachPrincipalPolicyRequest;
import com.amazonaws.services.iot.model.CreateKeysAndCertificateRequest;
import com.amazonaws.services.iot.model.CreateKeysAndCertificateResult;
import com.nightonke.jellytogglebutton.JellyToggleButton;
import com.nightonke.jellytogglebutton.State;
import com.ramotion.fluidslider.FluidSlider;

import java.io.UnsupportedEncodingException;
import java.security.KeyStore;
import java.util.UUID;

import kotlin.Unit;
import kotlin.jvm.functions.Function0;

public class DeviceControl extends AppCompatActivity {
    static final String LOG_TAG = DeviceControl.class.getCanonicalName();
    private static final String CUSTOMER_SPECIFIC_ENDPOINT = "a3i9nrh6pm3hvm-ats.iot.us-east-1.amazonaws.com";
    private static final String COGNITO_POOL_ID = "us-east-1:50c0e21e-e0b1-45a4-92ab-4d79384de8b6";
    private static final String AWS_IOT_POLICY_NAME = "android_mqtt";
    private static final Regions MY_REGION = Regions.US_EAST_1;
    private static final String KEYSTORE_NAME = "iot_keystore";
    private static final String KEYSTORE_PASSWORD = "password";
    private static final String CERTIFICATE_ID = "default";

    //BEGIN AWS
    AWSIotClient mIotAndroidClient;
    AWSIotMqttManager mqttManager;
    String clientId;
    String keystorePath;
    String keystoreName;
    String keystorePassword;

    KeyStore clientKeyStore = null;
    String certificateId;

    CognitoCachingCredentialsProvider credentialsProvider;
    //END AWS
    TextView tvLastMessage;
    TextView tvStatus;

    Button btnSubscribe;
    ImageView imageView;
    ImageView imageTemperature;
    ImageView imageHumidity;
    SeekBar seekBar;
    TextView txtTemperature;
    TextView txtHumidity;
    JellyToggleButton swLed;

    boolean sangState = false;
    boolean baoState = false;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.device_control);
        final int max = 100;
        final int min = 0;
        final int total = max - min;
        tvLastMessage = (TextView) findViewById(R.id.tvLastMessage);
        tvStatus = (TextView) findViewById(R.id.tvStatus);

        txtTemperature = (TextView) findViewById(R.id.txtTemperature);
        txtHumidity = (TextView) findViewById(R.id.txtHumidity);

        btnSubscribe = (Button) findViewById(R.id.btnSubscribe);
        btnSubscribe.setOnClickListener(subscribeClick);



        imageView = (ImageView) this.findViewById(R.id.imageView);
        imageView.setImageResource(R.drawable.bulboff1);

        imageTemperature = (ImageView) this.findViewById(R.id.imageTemperature);
        imageTemperature.setImageResource(R.drawable.temperature);

        imageHumidity = (ImageView) this.findViewById(R.id.imageHumidity);
        imageHumidity.setImageResource(R.drawable.humidity);
        swLed = (JellyToggleButton) findViewById(R.id.switch_id);
        swLed.setOnStateChangeListener(new JellyToggleButton.OnStateChangeListener() {
            @Override
            public void onStateChange(float process, State state, JellyToggleButton jtb) {
                final String topic = "android/led";
                try {
                    if (state.equals(State.RIGHT)){
                        mqttManager.publishString("ON", topic, AWSIotMqttQos.QOS0);
                        imageView.setImageResource(R.drawable.bulbon1);}
                    else if(state.equals(State.LEFT)){
                        mqttManager.publishString("OFF", topic, AWSIotMqttQos.QOS0);
                        imageView.setImageResource(R.drawable.bulboff1);}
                } catch (Exception e) {}
            }
        });
        final FluidSlider seekBar = findViewById(R.id.seekBar);
        seekBar.setBeginTrackingListener(new Function0<Unit>() {
            @Override
            public Unit invoke() {
                Log.d("D", "setBeginTrackingListener");
                return Unit.INSTANCE;
            }
        });

        seekBar.setEndTrackingListener(new Function0<Unit>() {
            @Override
            public Unit invoke() {
                Log.d("D", "setEndTrackingListener");
                return Unit.INSTANCE;
            }
        });

        seekBar.setPositionListener(pos -> {
            final String value = String.valueOf( (int)(min + total * pos) );
            mqttManager.publishString(value, "android/fan", AWSIotMqttQos.QOS0);
            return Unit.INSTANCE;
        });
//        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
//            int value =0;
//            @Override
//            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
//                value = progress;
//            }
//
//            @Override
//            public void onStartTrackingTouch(SeekBar seekBar) {
//
//            }
//
//            @Override
//            public void onStopTrackingTouch(SeekBar seekBar) {
//                mqttManager.publishString(Integer.toString(value), "android/fan", AWSIotMqttQos.QOS0);
//            }
//        });
        //seekBar.setOnSeekBarChangeListener(seekBAR);
        // MQTT client IDs are required to be unique per AWS IoT account.
        // This UUID is "practically unique" but does not _guarantee_
        // uniqueness.
        clientId = UUID.randomUUID().toString();

        // Initialize the AWS Cognito credentials provider
        credentialsProvider = new CognitoCachingCredentialsProvider(
                getApplicationContext(), // context
                COGNITO_POOL_ID, // Identity Pool ID
                MY_REGION // Region
        );

        Region region = Region.getRegion(MY_REGION);

        // MQTT Client
        mqttManager = new AWSIotMqttManager(clientId, CUSTOMER_SPECIFIC_ENDPOINT);

        // Set keepalive to 10 seconds.  Will recognize disconnects more quickly but will also send
        // MQTT pings every 10 seconds.
        mqttManager.setKeepAlive(10);

        // Set Last Will and Testament for MQTT.  On an unclean disconnect (loss of connection)
        // AWS IoT will publish this message to alert other clients.
        AWSIotMqttLastWillAndTestament lwt = new AWSIotMqttLastWillAndTestament("my/lwt/topic",
                "Android client lost connection", AWSIotMqttQos.QOS0);
        mqttManager.setMqttLastWillAndTestament(lwt);

        // IoT Client (for creation of certificate if needed)
        mIotAndroidClient = new AWSIotClient(credentialsProvider);
        mIotAndroidClient.setRegion(region);

        keystorePath = getFilesDir().getPath();
        keystoreName = KEYSTORE_NAME;
        keystorePassword = KEYSTORE_PASSWORD;
        certificateId = CERTIFICATE_ID;

        // To load cert/key from keystore on filesystem
        try {
            if (AWSIotKeystoreHelper.isKeystorePresent(keystorePath, keystoreName)) {
                if (AWSIotKeystoreHelper.keystoreContainsAlias(certificateId, keystorePath,
                        keystoreName, keystorePassword)) {
                    Log.i(LOG_TAG, "Certificate " + certificateId
                            + " found in keystore - using for MQTT.");
                    // load keystore from file into memory to pass on connection
                    clientKeyStore = AWSIotKeystoreHelper.getIotKeystore(certificateId,
                            keystorePath, keystoreName, keystorePassword);
                } else {
                    Log.i(LOG_TAG, "Key/cert " + certificateId + " not found in keystore.");
                }
            } else {
                Log.i(LOG_TAG, "Keystore " + keystorePath + "/" + keystoreName + " not found.");
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "An error occurred retrieving cert/key from keystore.", e);
        }

        if (clientKeyStore == null) {
            Log.i(LOG_TAG, "Cert/key was not found in keystore - creating new key and certificate.");

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Create a new private key and certificate. This call
                        // creates both on the server and returns them to the
                        // device.
                        CreateKeysAndCertificateRequest createKeysAndCertificateRequest =
                                new CreateKeysAndCertificateRequest();
                        createKeysAndCertificateRequest.setSetAsActive(true);
                        final CreateKeysAndCertificateResult createKeysAndCertificateResult;
                        createKeysAndCertificateResult =
                                mIotAndroidClient.createKeysAndCertificate(createKeysAndCertificateRequest);
                        Log.i(LOG_TAG,
                                "Cert ID: " +
                                        createKeysAndCertificateResult.getCertificateId() +
                                        " created.");

                        // store in keystore for use in MQTT client
                        // saved as alias "default" so a new certificate isn't
                        // generated each run of this application
                        AWSIotKeystoreHelper.saveCertificateAndPrivateKey(certificateId,
                                createKeysAndCertificateResult.getCertificatePem(),
                                createKeysAndCertificateResult.getKeyPair().getPrivateKey(),
                                keystorePath, keystoreName, keystorePassword);

                        // load keystore from file into memory to pass on
                        // connection
                        clientKeyStore = AWSIotKeystoreHelper.getIotKeystore(certificateId,
                                keystorePath, keystoreName, keystorePassword);

                        // Attach a policy to the newly created certificate.
                        // This flow assumes the policy was already created in
                        // AWS IoT and we are now just attaching it to the
                        // certificate.
                        AttachPrincipalPolicyRequest policyAttachRequest =
                                new AttachPrincipalPolicyRequest();
                        policyAttachRequest.setPolicyName(AWS_IOT_POLICY_NAME);
                        policyAttachRequest.setPrincipal(createKeysAndCertificateResult
                                .getCertificateArn());
                        mIotAndroidClient.attachPrincipalPolicy(policyAttachRequest);

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                            }
                        });
                    } catch (Exception e) {
                        Log.e(LOG_TAG,
                                "Exception occurred when generating new private key and certificate.",
                                e);
                    }
                }
            }).start();
        }
        try {
            mqttManager.connect(clientKeyStore, new AWSIotMqttClientStatusCallback() {
                @Override
                public void onStatusChanged(final AWSIotMqttClientStatus status,
                                            final Throwable throwable) {
                    Log.d(LOG_TAG, "Status = " + String.valueOf(status));

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (status == AWSIotMqttClientStatus.Connecting) {
                                tvStatus.setText("Connecting...");

                            } else if (status == AWSIotMqttClientStatus.Connected) {
                                tvStatus.setText("Connected");

                            } else if (status == AWSIotMqttClientStatus.Reconnecting) {
                                if (throwable != null) {
                                    Log.e(LOG_TAG, "Connection error.", throwable);
                                }
                                tvStatus.setText("Reconnecting");
                            } else if (status == AWSIotMqttClientStatus.ConnectionLost) {
                                if (throwable != null) {
                                    Log.e(LOG_TAG, "Connection error.", throwable);
                                }
                                tvStatus.setText("Disconnected");
                            } else {
                                tvStatus.setText("Disconnected");

                            }
                        }
                    });
                }
            });
            subscribe();
        } catch (Exception e) {}



    }
    private void subscribe(){
        LinearLayout layoutContainPerson = (LinearLayout)findViewById(R.id.containPersonLayout);
        LinearLayout layoutShowPerson = new LinearLayout(this);
        layoutShowPerson.setOrientation(LinearLayout.HORIZONTAL);
        layoutShowPerson.setHorizontalGravity(Gravity.CENTER_HORIZONTAL);
        layoutShowPerson.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 300));

        ImageView imgSang = new ImageView(this);
        imgSang.setImageResource(R.drawable.unknown);
        imgSang.setVisibility(View.INVISIBLE);
        imgSang.setLayoutParams(new ViewGroup.LayoutParams(300, 300));
        layoutShowPerson.addView(imgSang);

        ImageView imgBao = new ImageView(this);
        imgBao.setImageResource(R.drawable.unknown);
        imgBao.setVisibility(View.INVISIBLE);
        imgBao.setLayoutParams(new ViewGroup.LayoutParams(300, 300));
        layoutShowPerson.addView(imgBao);

        layoutContainPerson.addView(layoutShowPerson);
        try {
            mqttManager.subscribeToTopic("esp/led", AWSIotMqttQos.QOS0,
                    new AWSIotMqttNewMessageCallback() {
                        @Override
                        public void onMessageArrived(final String topic, final byte[] data) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run()  {
                                    try {
                                        String message = new String(data, "UTF-8");
                                        tvLastMessage.setText(message);
                                        if(message.equals("Led ON")){
                                            swLed.setChecked(true);
                                            imageView.setImageResource(R.drawable.bulbon1);
                                        }
                                        else if(message.equals("Led OFF")){
                                            swLed.setChecked(false);
                                            imageView.setImageResource(R.drawable.bulboff1);
                                        }
                                    } catch (UnsupportedEncodingException e) {}
                                }
                            });
                        }
                    });
        } catch (Exception e) {}

        try {
            mqttManager.subscribeToTopic("esp/temperature", AWSIotMqttQos.QOS0,
                    new AWSIotMqttNewMessageCallback() {
                        @Override
                        public void onMessageArrived(final String topic, final byte[] data) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run()  {
                                    try {
                                        String message = new String(data, "UTF-8");
                                        tvLastMessage.setText(message);
                                        txtTemperature.setText(message+"°C");
                                    } catch (UnsupportedEncodingException e) {}
                                }
                            });
                        }
                    });
        } catch (Exception e) {}
        try {
            mqttManager.subscribeToTopic("esp/humidity", AWSIotMqttQos.QOS0,
                    new AWSIotMqttNewMessageCallback() {
                        @Override
                        public void onMessageArrived(final String topic, final byte[] data) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run()  {
                                    try {
                                        String message = new String(data, "UTF-8");
                                        tvLastMessage.setText(message);
                                        txtHumidity.setText(message+"%");
                                    } catch (UnsupportedEncodingException e) {}
                                }
                            });
                        }
                    });
        } catch (Exception e) {}
        try {
            mqttManager.subscribeToTopic("esp/rfid", AWSIotMqttQos.QOS0,
                    new AWSIotMqttNewMessageCallback() {
                        @Override

                        public void onMessageArrived(final String topic, final byte[] data) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run()  {
                                    try {
                                        String message = new String(data, "UTF-8");
                                        tvLastMessage.setText(message);
                                        if (message.equals("Bao")) {
                                            baoState = !baoState;
                                            if(baoState == true){
                                                imgBao.setVisibility(View.VISIBLE);
                                            }
                                            else{
                                                imgBao.setVisibility(View.INVISIBLE);
                                            }
                                        }
                                        if (message.equals("Sang")) {
                                            sangState = !sangState;
                                            if(sangState == true){
                                                imgSang.setVisibility(View.VISIBLE);
                                            }
                                            else{
//                                                layoutShowPerson.removeView(imgSang);
//                                                layoutContainPerson.removeView(layoutShowPerson);
                                                imgSang.setVisibility(View.INVISIBLE);
                                            }
                                        }
                                    } catch (UnsupportedEncodingException e) {}
                                }
                            });
                        }
                    });
        } catch (Exception e) {}

        try {
            mqttManager.subscribeToTopic("android/led", AWSIotMqttQos.QOS0,
                    new AWSIotMqttNewMessageCallback() {
                        @Override
                        public void onMessageArrived(final String topic, final byte[] data) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run()  {
                                    try {
                                        String message = new String(data, "UTF-8");
                                        tvLastMessage.setText(message);
                                    } catch (UnsupportedEncodingException e) {}
                                }
                            });
                        }
                    });
        } catch (Exception e) {}
        try {
            mqttManager.subscribeToTopic("android/fan", AWSIotMqttQos.QOS0,
                    new AWSIotMqttNewMessageCallback() {
                        @Override
                        public void onMessageArrived(final String topic, final byte[] data) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run()  {
                                    try {
                                        String message = new String(data, "UTF-8");
                                        tvLastMessage.setText(message);
                                    } catch (UnsupportedEncodingException e) {}
                                }
                            });
                        }
                    });
        } catch (Exception e) {}
    }
    View.OnClickListener subscribeClick = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            subscribe();
        }
    };

//    View.OnClickListener switchLED = new View.OnClickListener() {
//        @Override
//        public void onClick(View v) {
//
//            final String topic = "android/led";
//            try {
//                if (swLed.isChecked()){
//                    mqttManager.publishString("ON", topic, AWSIotMqttQos.QOS0);
//                    imageView.setImageResource(R.drawable.bulbon1);}
//                else{
//                    mqttManager.publishString("OFF", topic, AWSIotMqttQos.QOS0);
//                    imageView.setImageResource(R.drawable.bulboff1);}
//            } catch (Exception e) {}
//        }
//    };
}
