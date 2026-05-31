package org.libsdl.app;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.os.Build;
import android.util.Log;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.*;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

// SDL Java glue keeps upstream static/native callback shape at this vendor boundary.
@SuppressLint({"LogNotTimber", "StaticFieldLeak"})
public class HIDDeviceManager {
    private static final String TAG = "hidapi";
    private static final String ACTION_USB_PERMISSION = "org.libsdl.app.USB_PERMISSION";

    private static HIDDeviceManager sManager;
    private static int sManagerRefCount = 0;

    static public HIDDeviceManager acquire(Context context) {
        if (sManagerRefCount == 0) {
            sManager = new HIDDeviceManager(context);
        }
        ++sManagerRefCount;
        return sManager;
    }

    static public void release(HIDDeviceManager manager) {
        if (manager == sManager) {
            --sManagerRefCount;
            if (sManagerRefCount == 0) {
                sManager.close();
                sManager = null;
            }
        }
    }

    private Context mContext;
    private HashMap<Integer, HIDDevice> mDevicesById = new HashMap<Integer, HIDDevice>();
    private int mNextDeviceId = 0;
    private SharedPreferences mSharedPreferences = null;
    private UsbManager mUsbManager;

    private final BroadcastReceiver mUsbBroadcast = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
                UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                handleUsbDeviceAttached(usbDevice);
            } else if (action.equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
                UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                handleUsbDeviceDetached(usbDevice);
            } else if (action.equals(HIDDeviceManager.ACTION_USB_PERMISSION)) {
                UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                handleUsbDevicePermission(usbDevice, intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false));
            }
        }
    };

    private HIDDeviceManager(final Context context) {
        mContext = context;

        HIDDeviceRegisterCallback();

        mSharedPreferences = mContext.getSharedPreferences("hidapi", Context.MODE_PRIVATE);

//        if (shouldClear) {
//            SharedPreferences.Editor spedit = mSharedPreferences.edit();
//            spedit.clear();
//            spedit.apply();
//        }
//        else
        {
            mNextDeviceId = mSharedPreferences.getInt("next_device_id", 0);
        }
    }

    Context getContext() {
        return mContext;
    }

    int getDeviceIDForIdentifier(String identifier) {
        SharedPreferences.Editor spedit = mSharedPreferences.edit();

        int result = mSharedPreferences.getInt(identifier, 0);
        if (result == 0) {
            result = mNextDeviceId++;
            spedit.putInt("next_device_id", mNextDeviceId);
        }

        spedit.putInt(identifier, result);
        spedit.apply();
        return result;
    }

    private void initializeUSB() {
        mUsbManager = (UsbManager)mContext.getSystemService(Context.USB_SERVICE);
        if (mUsbManager == null) {
            return;
        }

        /*
        // Logging
        for (UsbDevice device : mUsbManager.getDeviceList().values()) {
            Log.i(TAG,"Path: " + device.getDeviceName());
            Log.i(TAG,"Manufacturer: " + device.getManufacturerName());
            Log.i(TAG,"Product: " + device.getProductName());
            Log.i(TAG,"ID: " + device.getDeviceId());
            Log.i(TAG,"Class: " + device.getDeviceClass());
            Log.i(TAG,"Protocol: " + device.getDeviceProtocol());
            Log.i(TAG,"Vendor ID " + device.getVendorId());
            Log.i(TAG,"Product ID: " + device.getProductId());
            Log.i(TAG,"Interface count: " + device.getInterfaceCount());
            Log.i(TAG,"---------------------------------------");

            // Get interface details
            for (int index = 0; index < device.getInterfaceCount(); index++) {
                UsbInterface mUsbInterface = device.getInterface(index);
                Log.i(TAG,"  *****     *****");
                Log.i(TAG,"  Interface index: " + index);
                Log.i(TAG,"  Interface ID: " + mUsbInterface.getId());
                Log.i(TAG,"  Interface class: " + mUsbInterface.getInterfaceClass());
                Log.i(TAG,"  Interface subclass: " + mUsbInterface.getInterfaceSubclass());
                Log.i(TAG,"  Interface protocol: " + mUsbInterface.getInterfaceProtocol());
                Log.i(TAG,"  Endpoint count: " + mUsbInterface.getEndpointCount());

                // Get endpoint details
                for (int epi = 0; epi < mUsbInterface.getEndpointCount(); epi++)
                {
                    UsbEndpoint mEndpoint = mUsbInterface.getEndpoint(epi);
                    Log.i(TAG,"    ++++   ++++   ++++");
                    Log.i(TAG,"    Endpoint index: " + epi);
                    Log.i(TAG,"    Attributes: " + mEndpoint.getAttributes());
                    Log.i(TAG,"    Direction: " + mEndpoint.getDirection());
                    Log.i(TAG,"    Number: " + mEndpoint.getEndpointNumber());
                    Log.i(TAG,"    Interval: " + mEndpoint.getInterval());
                    Log.i(TAG,"    Packet size: " + mEndpoint.getMaxPacketSize());
                    Log.i(TAG,"    Type: " + mEndpoint.getType());
                }
            }
        }
        Log.i(TAG," No more devices connected.");
        */

        // Register for USB broadcasts and permission completions
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(HIDDeviceManager.ACTION_USB_PERMISSION);
        ContextCompat.registerReceiver(
            mContext,
            mUsbBroadcast,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        );

        for (UsbDevice usbDevice : mUsbManager.getDeviceList().values()) {
            handleUsbDeviceAttached(usbDevice);
        }
    }

    UsbManager getUSBManager() {
        return mUsbManager;
    }

    private void shutdownUSB() {
        try {
            mContext.unregisterReceiver(mUsbBroadcast);
        } catch (Exception e) {
            // We may not have registered, that's okay
        }
    }

    private boolean isHIDDeviceInterface(UsbDevice usbDevice, UsbInterface usbInterface) {
        if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_HID) {
            return true;
        }
        if (isXbox360Controller(usbDevice, usbInterface) || isXboxOneController(usbDevice, usbInterface)) {
            return true;
        }
        return false;
    }

    private boolean isXbox360Controller(UsbDevice usbDevice, UsbInterface usbInterface) {
        final int XB360_IFACE_SUBCLASS = 93;
        final int XB360_IFACE_PROTOCOL = 1; // Wired
        final int XB360W_IFACE_PROTOCOL = 129; // Wireless
        final int[] SUPPORTED_VENDORS = {
            0x0079, // GPD Win 2
            0x044f, // Thrustmaster
            0x045e, // Microsoft
            0x046d, // Logitech
            0x056e, // Elecom
            0x06a3, // Saitek
            0x0738, // Mad Catz
            0x07ff, // Mad Catz
            0x0e6f, // PDP
            0x0f0d, // Hori
            0x1038, // SteelSeries
            0x11c9, // Nacon
            0x12ab, // Unknown
            0x1430, // RedOctane
            0x146b, // BigBen
            0x1532, // Razer Sabertooth
            0x15e4, // Numark
            0x162e, // Joytech
            0x1689, // Razer Onza
            0x1949, // Lab126, Inc.
            0x1bad, // Harmonix
            0x20d6, // PowerA
            0x24c6, // PowerA
            0x2c22, // Qanba
            0x2dc8, // 8BitDo
            0x37d7, // Flydigi
            0x9886, // ASTRO Gaming
        };

        if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC &&
            usbInterface.getInterfaceSubclass() == XB360_IFACE_SUBCLASS &&
            (usbInterface.getInterfaceProtocol() == XB360_IFACE_PROTOCOL ||
             usbInterface.getInterfaceProtocol() == XB360W_IFACE_PROTOCOL)) {
            int vendor_id = usbDevice.getVendorId();
            for (int supportedVid : SUPPORTED_VENDORS) {
                if (vendor_id == supportedVid) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isXboxOneController(UsbDevice usbDevice, UsbInterface usbInterface) {
        final int XB1_IFACE_SUBCLASS = 71;
        final int XB1_IFACE_PROTOCOL = 208;
        final int[] SUPPORTED_VENDORS = {
            0x03f0, // HP
            0x044f, // Thrustmaster
            0x045e, // Microsoft
            0x0738, // Mad Catz
            0x0b05, // ASUS
            0x0e6f, // PDP
            0x0f0d, // Hori
            0x10f5, // Turtle Beach
            0x1532, // Razer Wildcat
            0x20d6, // PowerA
            0x24c6, // PowerA
            0x294b, // Snakebyte
            0x2dc8, // 8BitDo
            0x2e24, // Hyperkin
            0x2e95, // SCUF
            0x3285, // Nacon
            0x3537, // GameSir
            0x366c, // ByoWave
        };

        if (usbInterface.getId() == 0 &&
            usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC &&
            usbInterface.getInterfaceSubclass() == XB1_IFACE_SUBCLASS &&
            usbInterface.getInterfaceProtocol() == XB1_IFACE_PROTOCOL) {
            int vendor_id = usbDevice.getVendorId();
            for (int supportedVid : SUPPORTED_VENDORS) {
                if (vendor_id == supportedVid) {
                    return true;
                }
            }
        }
        return false;
    }

    private void handleUsbDeviceAttached(UsbDevice usbDevice) {
        connectHIDDeviceUSB(usbDevice);
    }

    private void handleUsbDeviceDetached(UsbDevice usbDevice) {
        List<Integer> devices = new ArrayList<Integer>();
        for (HIDDevice device : mDevicesById.values()) {
            if (usbDevice.equals(device.getDevice())) {
                devices.add(device.getId());
            }
        }
        for (int id : devices) {
            HIDDevice device = mDevicesById.get(id);
            mDevicesById.remove(id);
            device.shutdown();
            HIDDeviceDisconnected(id);
        }
    }

    private void handleUsbDevicePermission(UsbDevice usbDevice, boolean permission_granted) {
        for (HIDDevice device : mDevicesById.values()) {
            if (usbDevice.equals(device.getDevice())) {
                boolean opened = false;
                if (permission_granted) {
                    opened = device.open();
                }
                HIDDeviceOpenResult(device.getId(), opened);
            }
        }
    }

    private void connectHIDDeviceUSB(UsbDevice usbDevice) {
        synchronized (this) {
            int interface_mask = 0;
            for (int interface_index = 0; interface_index < usbDevice.getInterfaceCount(); interface_index++) {
                UsbInterface usbInterface = usbDevice.getInterface(interface_index);
                if (isHIDDeviceInterface(usbDevice, usbInterface)) {
                    // Check to see if we've already added this interface
                    // This happens with the Xbox Series X controller which has a duplicate interface 0, which is inactive
                    int interface_id = usbInterface.getId();
                    if ((interface_mask & (1 << interface_id)) != 0) {
                        continue;
                    }
                    interface_mask |= (1 << interface_id);

                    HIDDeviceUSB device = new HIDDeviceUSB(this, usbDevice, interface_index);
                    int id = device.getId();
                    mDevicesById.put(id, device);
                    HIDDeviceConnected(id, device.getIdentifier(), device.getVendorId(), device.getProductId(), device.getSerialNumber(), device.getVersion(), device.getManufacturerName(), device.getProductName(), usbInterface.getId(), usbInterface.getInterfaceClass(), usbInterface.getInterfaceSubclass(), usbInterface.getInterfaceProtocol(), false);
                }
            }
        }
    }

    private void close() {
        shutdownUSB();
        synchronized (this) {
            for (HIDDevice device : mDevicesById.values()) {
                device.shutdown();
            }
            mDevicesById.clear();
            HIDDeviceReleaseCallback();
        }
    }

    public void setFrozen(boolean frozen) {
        synchronized (this) {
            for (HIDDevice device : mDevicesById.values()) {
                device.setFrozen(frozen);
            }
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////////////

    private HIDDevice getDevice(int id) {
        synchronized (this) {
            HIDDevice result = mDevicesById.get(id);
            if (result == null) {
                Log.v(TAG, "No device for id: " + id);
                Log.v(TAG, "Available devices: " + mDevicesById.keySet());
            }
            return result;
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////// JNI interface functions
    //////////////////////////////////////////////////////////////////////////////////////////////////////

    boolean initialize(boolean usb, boolean bluetooth) {
        Log.v(TAG, "initialize(" + usb + ", " + bluetooth + ")");

        if (usb) {
            initializeUSB();
        }
        if (bluetooth) {
            Log.v(TAG, "Bluetooth HID is disabled in this build");
        }
        return true;
    }

    boolean openDevice(int deviceID) {
        Log.v(TAG, "openDevice deviceID=" + deviceID);
        HIDDevice device = getDevice(deviceID);
        if (device == null) {
            HIDDeviceDisconnected(deviceID);
            return false;
        }

        // Look to see if this is a USB device and we have permission to access it
        UsbDevice usbDevice = device.getDevice();
        if (usbDevice != null && !mUsbManager.hasPermission(usbDevice)) {
            HIDDeviceOpenPending(deviceID);
            try {
                final int FLAG_MUTABLE = 0x02000000; // PendingIntent.FLAG_MUTABLE, but don't require SDK 31
                int flags;
                if (Build.VERSION.SDK_INT >= 31 /* Android 12.0 (S) */) {
                    flags = FLAG_MUTABLE;
                } else {
                    flags = 0;
                }

                Intent intent = new Intent(HIDDeviceManager.ACTION_USB_PERMISSION);
                intent.setPackage(mContext.getPackageName());
                mUsbManager.requestPermission(usbDevice, PendingIntent.getBroadcast(mContext, 0, intent, flags));
            } catch (Exception e) {
                Log.v(TAG, "Couldn't request permission for USB device " + usbDevice);
                HIDDeviceOpenResult(deviceID, false);
            }
            return false;
        }

        try {
            return device.open();
        } catch (Exception e) {
            Log.e(TAG, "Got exception: " + Log.getStackTraceString(e));
        }
        return false;
    }

    int writeReport(int deviceID, byte[] report, boolean feature) {
        try {
            //Log.v(TAG, "writeReport deviceID=" + deviceID + " length=" + report.length);
            HIDDevice device;
            device = getDevice(deviceID);
            if (device == null) {
                HIDDeviceDisconnected(deviceID);
                return -1;
            }

            return device.writeReport(report, feature);
        } catch (Exception e) {
            Log.e(TAG, "Got exception: " + Log.getStackTraceString(e));
        }
        return -1;
    }

    boolean readReport(int deviceID, byte[] report, boolean feature) {
        try {
            //Log.v(TAG, "readReport deviceID=" + deviceID);
            HIDDevice device;
            device = getDevice(deviceID);
            if (device == null) {
                HIDDeviceDisconnected(deviceID);
                return false;
            }

            return device.readReport(report, feature);
        } catch (Exception e) {
            Log.e(TAG, "Got exception: " + Log.getStackTraceString(e));
        }
        return false;
    }

    void closeDevice(int deviceID) {
        try {
            Log.v(TAG, "closeDevice deviceID=" + deviceID);
            HIDDevice device;
            device = getDevice(deviceID);
            if (device == null) {
                HIDDeviceDisconnected(deviceID);
                return;
            }

            device.close();
        } catch (Exception e) {
            Log.e(TAG, "Got exception: " + Log.getStackTraceString(e));
        }
    }


    //////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////////// Native methods
    //////////////////////////////////////////////////////////////////////////////////////////////////////

    private native void HIDDeviceRegisterCallback();
    private native void HIDDeviceReleaseCallback();

    native void HIDDeviceConnected(int deviceID, String identifier, int vendorId, int productId, String serial_number, int release_number, String manufacturer_string, String product_string, int interface_number, int interface_class, int interface_subclass, int interface_protocol, boolean bBluetooth);
    native void HIDDeviceOpenPending(int deviceID);
    native void HIDDeviceOpenResult(int deviceID, boolean opened);
    native void HIDDeviceDisconnected(int deviceID);

    native void HIDDeviceInputReport(int deviceID, byte[] report);
    native void HIDDeviceReportResponse(int deviceID, byte[] report);
}
