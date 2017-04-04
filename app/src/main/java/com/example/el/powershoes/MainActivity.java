package com.example.el.powershoes;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements SeekBar.OnSeekBarChangeListener {

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothDevice bDevice;
    private boolean deviceFound, connectionActive, willTighten, forceEnabled = true, initialized;
    private InputStream iStream;
    private OutputStream oStream;

    private SeekBar seeker;
    private TextView stepTv, percentageTv;
    private FloatingActionButton fab;
    private Switch fSwitch;
    private Button resetButton;
    private CoordinatorLayout coordinatorLayout;

    private final String DEVICE_ADDRESS = "98:D3:36:00:9A:AB";
    private int steps = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        seeker = (SeekBar) findViewById(R.id.seekBar);
        stepTv = (TextView) findViewById(R.id.tv_steps);
        percentageTv = (TextView) findViewById(R.id.tv_percent);
        fab = (FloatingActionButton) findViewById(R.id.fab);
        resetButton = (Button) findViewById(R.id.reset_button);
        fSwitch = (Switch) findViewById(R.id.switch1);
        coordinatorLayout = (CoordinatorLayout) findViewById(R.id.coordinator);

        seeker.setOnSeekBarChangeListener(this);


        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(connectionActive) {
                    closestFullTighten();
                } else {
                    Snackbar.make(coordinatorLayout, "Not connected", Snackbar.LENGTH_LONG).show();
                }
            }
        });

        resetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                steps = 0;
            }
        });

        fSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(connectionActive) {
                    changeMode();
                } else {
                    Snackbar.make(coordinatorLayout, "Not connected", Snackbar.LENGTH_LONG).show();
                }
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        checkBluetooth();

        if(deviceFound) {
            startCommunication();
        } else {
            Log.d("Comm", "Device not found");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    // Returns True if bluetooth is connected successfully
    public void checkBluetooth() {
        // Check if device is bluetooth enabled
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(getApplicationContext(),"Device doesn't Support Bluetooth", Toast.LENGTH_SHORT).show();
            return;
        }

        // Start Activity to enable bluetooth
        if(!bluetoothAdapter.isEnabled())
        {
            Intent enableAdapter = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableAdapter, 0);
            return;
        }

        checkBonded();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode == 0) {
            if(resultCode == RESULT_OK) {
                checkBonded();
                if(deviceFound) {
                    startCommunication();
                } else {
                    Log.d("Bluetooth", "Device not found");
                }
            }
        }
    }

    // Check if the Bluetooth module of Arduino is paired with this Android device
    public void checkBonded() {
        Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();

        if(bondedDevices.isEmpty()) {
            Toast.makeText(getApplicationContext(),"Please Pair the Device first",Toast.LENGTH_SHORT).show();
        } else {
            for (BluetoothDevice iterator : bondedDevices) {
                if(iterator.getAddress().equals(DEVICE_ADDRESS)) {  //Replace with iterator.getName() if comparing Device names.
                    bDevice = iterator; //device is an object of type BluetoothDevice
                    deviceFound = true;
                    break;
                }
            }
        }
    }

    // Open io streams and start a thread to receive incoming messages from Arduino
    public void startCommunication() {
        if(!deviceFound)
            return;
        try {
            UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
            BluetoothSocket socket = bDevice.createRfcommSocketToServiceRecord(uuid);
            socket.connect();
            iStream = socket.getInputStream();
            oStream = socket.getOutputStream();
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        Snackbar.make(coordinatorLayout, "Connection Established", Snackbar.LENGTH_LONG).show();
        final Handler handler = new Handler();
        connectionActive = true;
        fSwitch.setEnabled(true);

        // Read any incoming data and Update UI
        new Thread(new Runnable(){
            @Override
            public void run () {
                // Initialize GUI by requesting present status of tightness
                askStatus();

                // Wait for data
                while(connectionActive) {
                    Log.d("Bluetooth", "Background thread started");
                    byte[] rawBytes = new byte[20];
                    try {
                        int b  = iStream.read(rawBytes);
                        if(b < 1) {
                            Log.d("Bluetooth", "b<1, connection stopping");
                            connectionActive = false;
                            return;
                        }
                        Log.d("Bluetooth", "Message Received " + b + " bytes");
                        // Operate on the string
                        String string = new String(rawBytes, 0, b);
                        final String[] splits = string.split(" ");
                        Log.d("Bluetooth", string);
                        handler.post(new Runnable() {
                            public void run()
                            {
                                if(splits[0].equals("s")) {
                                    steps++;
                                    Log.d("Steps", "Pedometer reported " + steps + "steps.");
                                    stepTv.setText("" + steps + " steps");
                                } else if(splits[0].equals("t")) {
                                    Log.d("Bluetooth", "t here");
                                    if(!initialized) {
                                        Log.d("b", "initializign");
                                        initialize(splits);
                                    } else {
                                        Log.d("Force", "Updating ui before func");
                                        updateUIonForceTighten();
                                    }
                                }
                            }
                        });
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
    }

    public void initialize(String[] splits) {
        if (splits.length > 1) {
            int tightness = Integer.parseInt(splits[1]);
            forceEnabled = (Integer.parseInt(splits[2]) == 1);
            fSwitch.setEnabled(true);
            if(forceEnabled) {
                fSwitch.setChecked(true);
                seeker.setVisibility(View.GONE);
                seeker.setProgress(tightness);
                if(tightness == 180) {
                    willTighten = false;
                    fab.setImageResource(R.drawable.ic_action_name);
                } else {
                    willTighten = true;
                    fab.setImageResource(R.drawable.ic_lock);
                }
            } else {
                fSwitch.setChecked(false);
                seeker.setVisibility(View.VISIBLE);

                // Initialize Button image
                if(tightness >= 90) {
                    willTighten = true;
                    fab.setImageResource(R.drawable.ic_lock);
                } else {
                    willTighten = false;
                    fab.setImageResource(R.drawable.ic_action_name);
                }
            }

            float percentage = ((float) tightness) / 180 * 100;
            String per = new DecimalFormat("#").format(percentage);
            Log.d("Tightness", "Tightness is currently " + per + "%.");
            percentageTv.setText("" + percentage + "%");
        }
        initialized = true;
    }

    public void updateUIonForceTighten() {
        Log.d("Force", "Tightening ui");
        willTighten = false;
        fab.setImageResource(R.drawable.ic_action_name);
        percentageTv.setText("100%");
    }

    // Degree from 0 - 180, 180 is the tightest
    public void tighten(int degree) {
        String s = "d " + degree + "\r\n";
        try {
            oStream.write(s.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }

        seeker.setProgress(degree);
        float percentage = ((float) degree) / 180 * 100;
        String per = new DecimalFormat("#").format(percentage);
        percentageTv.setText("" + per + "%");
    }

    public void askStatus() {
        String request = "t\r\n";
        try {
            oStream.write(request.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void changeMode() {
        forceEnabled = fSwitch.isChecked();
        int check = forceEnabled ? 1 : 0;
        String request = "m " + check + "\r\n";
        try {
            oStream.write(request.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }

        if(forceEnabled) {
            seeker.setVisibility(View.GONE);
        } else {
            seeker.setVisibility(View.VISIBLE);
        }

        if(forceEnabled)
            closestFullTighten();
    }

    public void closestFullTighten() {
        if(willTighten) {
            tighten(180);
            fab.setImageResource(R.drawable.ic_action_name);
        } else {
            tighten(0);
            fab.setImageResource(R.drawable.ic_lock);
        }
        willTighten = !willTighten;
    }


    @Override
    public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {}

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        tighten(seekBar.getProgress());
        if(seekBar.getProgress() >= 90 && !willTighten) {
            fab.setImageResource(R.drawable.ic_lock);
            willTighten = true;
        } else if(seekBar.getProgress() < 90 && willTighten) {
            fab.setImageResource(R.drawable.ic_action_name);
            willTighten = false;
        }
        // Change button
    }
}
