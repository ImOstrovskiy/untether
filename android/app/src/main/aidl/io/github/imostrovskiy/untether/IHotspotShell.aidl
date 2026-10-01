package io.github.imostrovskiy.untether;

import io.github.imostrovskiy.untether.IHotspotEvents;

/**
 * The privileged half of hotspot control, served by HotspotShell. Methods that return a String
 * return null on success or the error.
 */
interface IHotspotShell {
    /** Shizuku's reserved transaction, called right before it stops the process. */
    void destroy() = 16777114;

    String open(IHotspotEvents events) = 1;
    int apState() = 2;
    /** ssid and pass null: keep the network set in Android's own hotspot settings. compatible: any
     *  band and WPA2 instead of 5 GHz channel 36 and WPA3, for chips that cannot start the latter. */
    String syncConfig(String ssid, String pass, int autoOffMinutes, boolean compatible) = 3;
    String startTethering(String ssid, String pass, int autoOffMinutes, boolean withConfig, boolean compatible) = 4;
    String stopTethering() = 5;
    /** Adds mac to the hotspot blocklist; null clears it. */
    String editBlocklist(String mac) = 6;
    String setDataSim(int subId) = 7;
    /** Runs a command as the shell user and returns its exit code. */
    int exec(in String[] cmd) = 8;
    /** A SIM's network types allowed by the user (TelephonyManager.NETWORK_TYPE_BITMASK_*), -1 unknown.
     *  The app passes the subscription: in this process SubscriptionManager has no telephony behind it. */
    long allowedTypes(int subId) = 9;
    String setAllowedTypes(int subId, long types) = 10;
    /** Reports the system hotspot config's network through IHotspotEvents.onNetwork, changing nothing. */
    String readNetwork() = 11;
}
