// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.util.Log;
import androidx.annotation.Keep;
import java.net.Inet4Address;
import org.dolphinemu.dolphinemu.DolphinApplication;

public class NetworkHelper {
    private static final String TAG = "DolphinNetworkHelper";

    public static LinkAddress GetIPv4Link() {
        try {
            Context ctx = DolphinApplication.getAppContext();
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return null;
            }
            Network network = cm.getActiveNetwork();
            if (network == null) return null;
            LinkProperties lp = cm.getLinkProperties(network);
            if (lp == null) return null;
            for (LinkAddress link : lp.getLinkAddresses()) {
                if (link.getAddress() instanceof Inet4Address) {
                    return link;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "GetIPv4Link error: " + t.getMessage());
        }
        return null;
    }

    @Keep
    public static int GetNetworkGateway() {
        return 0;
    }

    @Keep
    public static int GetNetworkIpAddress() {
        LinkAddress link = GetIPv4Link();
        if (link == null) return 0;
        byte[] address = link.getAddress().getAddress();
        int ip = 0;
        for (int i = 0; i < address.length; i++) {
            ip |= (address[i] & 0xFF) << (i * 8);
        }
        return ip;
    }

    @Keep
    public static int GetNetworkPrefixLength() {
        LinkAddress link = GetIPv4Link();
        return link != null ? link.getPrefixLength() : 0;
    }
}
