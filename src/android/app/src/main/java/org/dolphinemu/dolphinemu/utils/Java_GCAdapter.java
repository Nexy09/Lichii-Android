// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.utils;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import androidx.annotation.Keep;
import java.util.Iterator;
import java.util.Map;

public class Java_GCAdapter {
    @Keep
    static byte[] controller_payload = new byte[37];
    public static UsbManager manager;
    public static UsbDeviceConnection usb_con;
    public static UsbEndpoint usb_in;
    public static UsbInterface usb_intf;
    public static UsbEndpoint usb_out;

    @Keep
    public static int GetFD() {
        return usb_con != null ? usb_con.getFileDescriptor() : -1;
    }

    @Keep
    public static int Input() {
        if (usb_con == null || usb_in == null) return -1;
        return usb_con.bulkTransfer(usb_in, controller_payload, controller_payload.length, 16);
    }

    @Keep
    public static boolean OpenAdapter() {
        if (manager == null) return false;
        Iterator<Map.Entry<String, UsbDevice>> it = manager.getDeviceList().entrySet().iterator();
        while (it.hasNext()) {
            UsbDevice value = it.next().getValue();
            if (value.getProductId() == 823 && value.getVendorId() == 1406 && manager.hasPermission(value)) {
                usb_con = manager.openDevice(value);
                if (value.getConfigurationCount() > 0 && value.getInterfaceCount() > 0) {
                    UsbInterface usbInterface = value.getConfiguration(0).getInterface(0);
                    usb_intf = usbInterface;
                    usb_con.claimInterface(usbInterface, true);
                    if (usb_intf.getEndpointCount() == 2) {
                        for (int i = 0; i < usb_intf.getEndpointCount(); i++) {
                            if (usb_intf.getEndpoint(i).getDirection() == 128) {
                                usb_in = usb_intf.getEndpoint(i);
                            } else {
                                usb_out = usb_intf.getEndpoint(i);
                            }
                        }
                        usb_con.bulkTransfer(usb_out, new byte[]{19}, 1, 0);
                        return true;
                    }
                    usb_con.releaseInterface(usb_intf);
                }
                if (usb_con != null) {
                    usb_con.close();
                }
            }
        }
        return false;
    }

    @Keep
    public static int Output(byte[] bArr) {
        if (usb_con == null || usb_out == null) return -1;
        return usb_con.bulkTransfer(usb_out, bArr, 5, 16);
    }

    @Keep
    public static boolean QueryAdapter() {
        if (manager == null) return false;
        for (UsbDevice value : manager.getDeviceList().values()) {
            if (value.getProductId() == 823 && value.getVendorId() == 1406) {
                if (manager.hasPermission(value)) {
                    return true;
                }
            }
        }
        return false;
    }
}
