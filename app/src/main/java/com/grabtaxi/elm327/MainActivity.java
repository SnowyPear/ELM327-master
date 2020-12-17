package com.grabtaxi.elm327;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.ActionBarActivity;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.grabtaxi.adapter.PairedListAdapter;
import com.grabtaxi.dialog.PairedDevicesDialog;
import com.grabtaxi.utility.MyLog;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import de.greenrobot.event.EventBus;

/**********************

Testing GitHub commits

 **********************/

public class MainActivity extends ActionBarActivity implements
        PairedDevicesDialog.PairedDeviceDialogListener
{
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final String TAG_DIALOG = "dialog";
    private static final String NO_BLUETOOTH = "Oops, your device doesn't support bluetooth";
    private static final String[] PIDS = {
            "01", "02", "03", "04", "05", "06", "07", "08",
            "09", "0A", "0B", "0C", "0D", "0E", "0F", "10",
            "11", "12", "13", "14", "15", "16", "17", "18",
            "19", "1A", "1B", "1C", "1D", "1E", "1F", "20"
    };

    // Last Responses
    private int lastRPM, maxRPM = 0, lastCoolantTemp, lastSpeed;
    
    // Commands
//    private static final String[] RUN_COMMANDS = {"AT Z", "AT D", "AT L0", "AT S0", "AT H0", "AT SP 0", "0105", "010C"};
    //TODO: Edit run commands
    private static final String[] RUN_COMMANDS = {"AT Z", "AT SP 0", "0105", "010C"};
    private int mCMDPointer = -1;
    private int mCMDInit = 1;

    // Intent request codes
    private static final int REQUEST_ENABLE_BT = 101;
    private static final int REQUEST_CONNECT_DEVICE_SECURE = 102;
    private static final int REQUEST_CONNECT_DEVICE_INSECURE = 103;

    // Message types accessed from the BluetoothIOGateway Handler
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;

    // Key names accesses from the BluetoothIOGateway Handler
    public static final String DEVICE_NAME = "device_name";
    public static final String TOAST = "toast_message";

    // Bluetooth
    private BluetoothIOGateway mIOGateway;
    private static BluetoothAdapter mBluetoothAdapter;
    private DeviceBroadcastReceiver mReceiver;
    private PairedDevicesDialog dialog;
    private List<BluetoothDevice> mDeviceList;

    // Files
    private FileWriter CSVwriter;

    // Widgets
    private TextView mConnectionStatus;
    private TextView mMonitor;
//    private EditText mCommandPrompt;
//    private ImageButton mBtnSend;
    private ImageButton mBtnScan;
    private ProgressBar mRPM;
    
    // Variable def
    private boolean inSimulatorMode = false;
    private static StringBuilder mSbCmdResp;
    private static StringBuilder mPartialResponse;
    private String mConnectedDeviceName;
    private final Handler mMsgHandler = new Handler()
    {
        @SuppressLint("HandlerLeak")
        @Override
        public void handleMessage(Message msg)
        {
            switch (msg.what)
            {
                case MESSAGE_STATE_CHANGE:
                    switch (msg.arg1) {
                        case BluetoothIOGateway.STATE_CONNECTING:
                            mConnectionStatus.setText(getString(R.string.BT_connecting));
                            mConnectionStatus.setBackgroundColor(Color.rgb(197, 145, 19));
                            mRPM.setIndeterminate(true);
                            break;

                        case BluetoothIOGateway.STATE_CONNECTED:
//                            mBtnSend.setEnabled(true);
                            mConnectionStatus.setText(getString(R.string.BT_status_connected_to) + " " + mConnectedDeviceName);
                            mConnectionStatus.setBackgroundColor(Color.rgb(65, 131, 19));
                            //sendInitCommands();
                            mRPM.setIndeterminate(false);
                            sendDefaultCommands();
                            break;

                        case BluetoothIOGateway.STATE_LISTEN:
                            mRPM.setIndeterminate(true);
                        case BluetoothIOGateway.STATE_NONE:
//                            mBtnSend.setEnabled(false);
                            mConnectionStatus.setText(getString(R.string.BT_status_not_connected));
                            mConnectionStatus.setBackgroundColor(Color.rgb(190, 19, 15));
                            mRPM.setIndeterminate(false);
                            break;

                        default:
                            break;
                    }
                    break;

                case MESSAGE_READ:
                    byte[] readBuf = (byte[]) msg.obj;
                    
                    // construct a string from the valid bytes in the buffer
                    String readMessage = new String(readBuf, 0, msg.arg1);
                    readMessage = readMessage.trim();
                    readMessage = readMessage.toUpperCase();
                    displayLog(mConnectedDeviceName + ": " + readMessage);
                    char lastChar = ' ';
                    if (!inSimulatorMode)
                    {
                        try {
                            lastChar = readMessage.charAt(readMessage.length() - 1);
                        }catch(Exception e){
                            //break;
                        }
                        if (lastChar == '>' & mPartialResponse.toString().length() > 0)
                        {
                            parseResponse(mPartialResponse.toString() + readMessage);
                            mPartialResponse.setLength(0);
                        }
                        else 
                        {
                            mPartialResponse.append(cleanResponse(readMessage));
                        }
                    }
                    else
                    {
                        mSbCmdResp.append("R>>");
                        mSbCmdResp.append(readMessage);
                        mSbCmdResp.append("\n");
                        mMonitor.setText(mSbCmdResp.toString());
                    }
                    break;

                case MESSAGE_WRITE:
                    byte[] writeBuf = (byte[]) msg.obj;

                    // construct a string from the buffer
                    String writeMessage = new String(writeBuf);
                    displayLog("Me: " + writeMessage);
//                    mSbCmdResp.append("W>>");
//                    mSbCmdResp.append(writeMessage);
//                    mSbCmdResp.append("\n");
//                    mMonitor.setText(mSbCmdResp.toString());
                    break;

                case MESSAGE_TOAST:
                    displayMessage(msg.getData().getString(TOAST));
                    break;

                case MESSAGE_DEVICE_NAME:
                    // save the connected device's name
                    mConnectedDeviceName = msg.getData().getString(DEVICE_NAME);
                    break;
            }
        }

    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        supportRequestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.activity_main);

        // log
        displayLog("=>\n***************\n     ELM 327 started\n***************");


        // File Handler for CSVFile
//        File root = Environment.getExternalStorageDirectory();
//        File savefile = new File(root, "save.csv");
//        CSVwriter = new FileWriter(savefile);

        // connect widgets
        mMonitor = (TextView) findViewById(R.id.tvMonitor);
        mMonitor.setMovementMethod(new ScrollingMovementMethod());
        mConnectionStatus = (TextView) findViewById(R.id.tvConnectionStatus);
//        mCommandPrompt = (EditText) findViewById(R.id.etCommandPrompt);
        mRPM = (ProgressBar) findViewById(R.id.determinateBar);
        mRPM.setMax(8000);
        mRPM.setIndeterminate(false);
        mRPM.setProgress(0);

        mBtnScan = (ImageButton) findViewById(R.id.ibSendMessage2);
        mBtnScan.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                queryPairedDevices();
                setupMonitor();
            }
        });

//        mBtnSend = (ImageButton) findViewById(R.id.ibSendMessage);
//        mBtnSend.setOnClickListener(new View.OnClickListener()
//        {
//            @Override
//            public void onClick(View v)
//            {
//                String command = mCommandPrompt.getText().toString();
//
//                // Clear command
//                mCommandPrompt.setText("");
//
//                // Send command
//                sendOBD2CMD(command);
//
//                // Close keyboard
//                InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
//                imm.hideSoftInputFromWindow(mMonitor.getWindowToken(), 0);
//            }
//        });

        // make sure user has Bluetooth hardware
        displayLog("Try to check hardware...");
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null)
        {
            // Device does not support Bluetooth
            displayMessage(NO_BLUETOOTH);
            displayLog(NO_BLUETOOTH);
            
            MainActivity.this.finish();
        }
        // log
        displayLog("Bluetooth found.");
        
        // Init variables
        mSbCmdResp = new StringBuilder();
        mPartialResponse = new StringBuilder();
        mIOGateway = new BluetoothIOGateway(this, mMsgHandler);
    }

    @Override
    protected void onStart()
    {
        super.onStart();

        if (mBluetoothAdapter == null)
        {
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        }

        // make sure Bluetooth is enabled
        displayLog("Try to check availability...");
        if (!mBluetoothAdapter.isEnabled())
        {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }
        else
        {
            displayLog("Bluetooth is available");

            queryPairedDevices();
            setupMonitor();
        }
    }

    @Override
    protected void onResume()
    {
        super.onResume();

//         Register EventBus
        EventBus.getDefault().register(this);
    }

    @Override
    protected void onPause()
    {
        super.onPause();

//         Unregister EventBus
        EventBus.getDefault().unregister(this);
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();

        // Un register receiver
        if (mReceiver != null)
        {
            unregisterReceiver(mReceiver);
        }

        // Stop scanning if is in progress
        cancelScanning();

        // Stop mIOGateway
        if (mIOGateway != null)
        {
            mIOGateway.stop();
        }
        
        // Clear StringBuilder
        if (mSbCmdResp.length() > 0)
        {
            mSbCmdResp.setLength(0);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        // Inflate the menu; this adds items to the action bar if it is present.
//        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        switch (item.getItemId())
        {
            case R.id.action_scan:
                queryPairedDevices();
                setupMonitor();
                return true;
            
            case R.id.menu_send_cmd:
                mCMDPointer = -1;
                sendDefaultCommands();
                return true;
            
            case R.id.menu_clr_scr:
                mSbCmdResp.setLength(0);
//                mMonitor.setText("");
                return true;
            
            case R.id.menu_toggle_obd_mode:
                inSimulatorMode = !inSimulatorMode;
                if(inSimulatorMode)
                {
                    displayMessage("Simulator mode enabled.");
                }
                else 
                {
                    displayMessage("Simulator mode disabled.");
                }
                return true;

            case R.id.menu_clear_code:
//                sendOBD2CMD("04");
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);


        switch (requestCode)
        {
            case REQUEST_ENABLE_BT:
                if (resultCode == RESULT_CANCELED)
                {
                    displayMessage("Bluetooth not enabled :(");
                    displayLog("Bluetooth not enabled :(");
                    return;
                }

                if (resultCode == RESULT_OK)
                {
                    displayLog("Bluetooth enabled");

                    queryPairedDevices();
                    setupMonitor();
                }

                break;

            default:
                // nothing at the moment
        }
    }
    
    private void setupMonitor()
    {
        // Start mIOGateway
        if (mIOGateway == null)
        {
            mIOGateway = new BluetoothIOGateway(this, mMsgHandler);
        }

        // Only if the state is STATE_NONE, do we know that we haven't started already
        if (mIOGateway.getState() == BluetoothIOGateway.STATE_NONE)
        {
            // Start the Bluetooth chat services
            mIOGateway.start();
            mRPM.setIndeterminate(true);
        }

        // clear string builder if contains data
        if (mSbCmdResp.length() > 0)
        {
            mSbCmdResp.setLength(0);
        }
        
    }

    private void queryPairedDevices()
    {
        displayLog("Try to query paired devices...");
        
        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        // If there are paired devices
        if (pairedDevices.size() > 0)
        {
            PairedDevicesDialog dialog = new PairedDevicesDialog();
            dialog.setAdapter(new PairedListAdapter(this, pairedDevices), false);
            showChooserDialog(dialog);
        }
        else
        {
            displayLog("No paired device found");

            scanAroundDevices();
        }
    }

    private void showChooserDialog(DialogFragment dialogFragment)
    {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        Fragment prev = getSupportFragmentManager().findFragmentByTag(TAG_DIALOG);
        if (prev != null)
        {
            ft.remove(prev);
        }
        ft.addToBackStack(null);

        dialogFragment.show(ft, "dialog");
    }

    private void scanAroundDevices()
    {
        displayLog("Try to scan around devices...");

        if (mReceiver == null)
        {
            // Register the BroadcastReceiver
            mReceiver = new DeviceBroadcastReceiver();
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            registerReceiver(mReceiver, filter);
        }

        // Start scanning
        mBluetoothAdapter.startDiscovery();
    }

    private void cancelScanning()
    {        
        if (mBluetoothAdapter.isDiscovering())
        {
            mBluetoothAdapter.cancelDiscovery();

            displayLog("Scanning canceled.");
        }
    }

    /**
     * Callback method for once a new device detected.
     *
     * @param device BluetoothDevice
     */
    public void onEvent(BluetoothDevice device)
    {
        if (mDeviceList == null)
        {
            mDeviceList = new ArrayList<>(10);
        }

        mDeviceList.add(device);

        // create dialog
        final Fragment fragment = this.getSupportFragmentManager().findFragmentByTag(TAG_DIALOG);
        if (fragment != null && fragment instanceof PairedDevicesDialog)
        {
            PairedListAdapter adapter = dialog.getAdapter();
            adapter.notifyDataSetChanged();
        }
        else
        {
            dialog = new PairedDevicesDialog();
            dialog.setAdapter(new PairedListAdapter(this, new HashSet<>(mDeviceList)), true);
            showChooserDialog(dialog);
        }
    }

    private void displayMessage(String msg)
    {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void displayLog(String msg)
    {
        Log.d(TAG, msg);
    }

    @Override
    public void onDeviceSelected(BluetoothDevice device)
    {
        cancelScanning();

        displayLog("Selected device: " + device.getName() + " (" + device.getAddress() + ")");
        
        // Attempt to connect to the device
        mIOGateway.connect(device, true);
    }

    @Override
    public void onSearchAroundDevicesRequested()
    {
        scanAroundDevices();
    }

    @Override
    public void onCancelScanningRequested()
    {
        cancelScanning();
    }

    

    private void sendOBD2CMD(String sendMsg)
    {
        if (mIOGateway.getState() != BluetoothIOGateway.STATE_CONNECTED)
        {
            displayMessage(getString(R.string.bt_not_available));
            return;
        }
        
        String strCMD = sendMsg;
        strCMD += '\r';
        
        byte[] byteCMD = strCMD.getBytes();
        mIOGateway.write(byteCMD);
    }

//    private void sendInitCommands()
//    {
//        if(inSimulatorMode)
//        {
//            displayMessage("You are in simulator mode!");
//            return;
//        }
//
//        mCMDPointer = 0;
//
//        for(mCMDPointer=0 ; mCMDPointer<INIT_COMMANDS.length ; mCMDPointer++ )
//        {
//            sendOBD2CMD(INIT_COMMANDS[mCMDPointer]);
//        }
//
//    }

    private void sendDefaultCommands()
    {
        if(inSimulatorMode)
        {
            displayMessage("You are in simulator mode!");
            return;
        }
        // reset pointer
        if (mCMDPointer < 0)
        {
            mCMDPointer = -1;
        }

        if (mCMDPointer >= RUN_COMMANDS.length-1)
        {
            mCMDPointer = 1;
        }
        mCMDPointer++;
        sendOBD2CMD(RUN_COMMANDS[mCMDPointer]);
    }
    
    private void parseResponse(String buffer)
    {
        switch (mCMDPointer)
        {
//            case 0: // CMD: AT Z, no parse needed
//                mSbCmdResp.append(buffer);
//            case "": // CMD: AT SP 0, no parse needed
//                break;
            
            case 2: // CMD: 0105, Engine coolant temperature
                int ect = showEngineCoolantTemperature(buffer);
                mSbCmdResp.append(buffer);
                mSbCmdResp.append(">>ct>");
                mSbCmdResp.append(ect);
                mSbCmdResp.append("\t\t");
//                CSVwriter.append(String.valueOf(ect));
//                CSVwriter.append(",");
                break;

            case 3: // CMD: 010C, EngineRPM
                int eRPM = showEngineRPM(buffer);
                mSbCmdResp.append(buffer);
                mSbCmdResp.append(">>rpm>");
                mSbCmdResp.append(eRPM);
                mSbCmdResp.append("\n");
//                CSVwriter.append(String.valueOf(eRPM));
//                CSVwriter.append("\n");
                break;

            case 4: // CMD: 010D, Vehicle Speed
                int vs = showVehicleSpeed(buffer);
                mSbCmdResp.append("R>>");
                mSbCmdResp.append(buffer);
                mSbCmdResp.append( " (Vehicle Speed: ");
                mSbCmdResp.append(vs);
                mSbCmdResp.append("Km/h)");
                mSbCmdResp.append("\n");
                break;
            
            case 5: // CMD: 0131
                int dt = showDistanceTraveled(buffer);
                mSbCmdResp.append("R>>");
                mSbCmdResp.append(buffer);
                mSbCmdResp.append( " (Distance traveled since codes cleared: ");
                mSbCmdResp.append(dt);
                mSbCmdResp.append("Km)");
                mSbCmdResp.append("\n");
                break;
            
            default:
                mSbCmdResp.append("R>>");
                mSbCmdResp.append(buffer);
                mSbCmdResp.append("\n");
        }

        if(mCMDPointer == 2){
            mMonitor.setText(mSbCmdResp.toString());
        }

        //tried adding a wait to improve signal
//        try {
//            wait(300);
//        }catch(Exception e)
//        {
//
//        }
        sendDefaultCommands();
    }

    private String decodeResponse(String response)
    {

        if (response.length() > 3)
        {
            return response.substring(response.length() - 4);
        }

        return "FFFF";
    }
    
    private String cleanResponse(String text)
    {
        text = text.trim();
        text = text.replaceAll("\\s" , "");
        text = text.replaceAll(">"  , "");
        
        return text;
    }
    
    private int showEngineCoolantTemperature(String buffer)
    {
        String buf = buffer;
        buf = decodeResponse(buf);

        try
        {
            String temp = buf.substring(0, 2);
            int A = Integer.valueOf(temp, 16);
            A -= 40;

            lastCoolantTemp = A;
            return A;
        }
        catch (IndexOutOfBoundsException | NumberFormatException e)
        {
            MyLog.e(TAG, e.getMessage());
        }
        
        return -1;
    }
    
    private int showEngineRPM(String buffer)
    {
        String buf = buffer;
        buf = cleanResponse(buf);
        displayLog(buf);

        if (buf.contains("410C")) {
            buf = decodeResponse(buf);

            try {
                String MSB = buf.substring(0, 2);
                displayLog("debug msb " + MSB);
                String LSB = buf.substring(2, 4);
                displayLog("debug lsb " + LSB);
                int A = Integer.valueOf(MSB, 16);
                int B = Integer.valueOf(LSB, 16);

                int tmpRPM = ((A * 256) + B) / 4;
                displayLog("debug rpm " + Integer.toString(tmpRPM));

                if (tmpRPM - lastRPM > 8000) {
                    return -1;
                }
                lastRPM = tmpRPM;

                if (lastRPM > maxRPM) {
                    maxRPM = lastRPM;
                }
                mRPM.setProgress(lastRPM);
                mRPM.setSecondaryProgress(maxRPM);

                return tmpRPM;
            } catch (IndexOutOfBoundsException | NumberFormatException e) {
                MyLog.e(TAG, e.getMessage());
            }
        }
        
        return -1;
    }
    
    private int showVehicleSpeed(String buffer)
    {
        String buf = buffer;
        buf = cleanResponse(buf);
        
        if (buf.contains("410D"))
        {
            try
            {
                buf = buf.substring(buf.indexOf("410D"));

                String temp = buf.substring(4, 6);

                int tmpSpeed = Integer.valueOf(temp, 16);
                lastSpeed = tmpSpeed;
                return tmpSpeed;
            }
            catch (IndexOutOfBoundsException | NumberFormatException e)
            {
                MyLog.e(TAG, e.getMessage());
            }
        }
        
        return -1;
    }
    
    private int showDistanceTraveled(String buffer)
    {
        String buf = buffer;
        buf = cleanResponse(buf);
        
        if (buf.contains("01314131"))
        {
            try
            {
                buf = buf.substring(buf.indexOf("4131"));

                String MSB = buf.substring(4, 6);
                String LSB = buf.substring(6, 8);
                int A = Integer.valueOf(MSB, 16);
                int B = Integer.valueOf(LSB, 16);

                return (A * 256) + B;
            }
            catch (IndexOutOfBoundsException | NumberFormatException e)
            {
                MyLog.e(TAG, e.getMessage());
            }
        }

        return -1;
    }
}
