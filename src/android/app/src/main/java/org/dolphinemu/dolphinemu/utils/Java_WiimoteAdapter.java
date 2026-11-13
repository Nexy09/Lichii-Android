// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.utils;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import androidx.annotation.Keep;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

public class Java_WiimoteAdapter {
    public static UsbManager manager;
    public static UsbDeviceConnection usb_con;
    public static final UsbInterface[] usb_intf = new UsbInterface[4];
    public static final UsbEndpoint[] usb_in = new UsbEndpoint[4];

    @Keep
    public static byte[][] wiimote_payload = (byte[][]) Array.newInstance(Byte.TYPE, 4, 23);

    @Keep
    public static int Input(int i) {
        if (usb_con == null || usb_in[i] == null) return -1;
        return usb_con.bulkTransfer(usb_in[i], wiimote_payload[i], 23, 200);
    }

    @Keep
    public static boolean OpenAdapter() {
        if (manager == null) return false;
        UsbDeviceConnection connection = usb_con;
        if (connection != null && connection.getFileDescriptor() != -1) {
            return true;
        }
        Iterator<Map.Entry<String, UsbDevice>> it = manager.getDeviceList().entrySet().iterator();
        while (it.hasNext()) {
            UsbDevice value = it.next().getValue();
            if (value.getProductId() == 774 && value.getVendorId() == 1406 && manager.hasPermission(value)) {
                usb_con = manager.openDevice(value);
                if (value.getInterfaceCount() > 0) {
                    for (int i = 0; i < 4; i++) {
                        UsbInterface usbInterface = value.getInterface(i);
                        usb_intf[i] = usbInterface;
                        usb_con.claimInterface(usbInterface, true);
                        usb_in[i] = usbInterface.getEndpoint(0);
                    }
                    return true;
                }
                if (usb_con != null) {
                    usb_con.close();
                }
            }
        }
        return false;
    }

    @Keep
    public static int Output(int i, byte[] bArr, int i2) {
        if (usb_con == null) return -1;
        int controlTransfer = usb_con.controlTransfer(33, 9, bArr[0] | 512, i, Arrays.copyOfRange(bArr, 1, bArr.length), i2 - 1, 1000);
        return (controlTransfer < 0) ? 0 : controlTransfer + 1;
    }

    @Keep
    public static boolean QueryAdapter() {
        if (manager == null) return false;
        for (UsbDevice value : manager.getDeviceList().values()) {
            if (value.getProductId() == 774 && value.getVendorId() == 1406) {
                if (manager.hasPermission(value)) {
                    return true;
                }
            }
        }
        return false;
    }
}
