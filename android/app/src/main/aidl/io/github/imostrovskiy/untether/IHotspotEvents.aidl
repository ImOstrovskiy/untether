package io.github.imostrovskiy.untether;

/** From HotspotShell back to the app. */
oneway interface IHotspotEvents {
    void onLog(String line);
    void onStartFailed(int error);
    void onClients(in String[] macs, in String[] ips, in String[] names);
    void onTetheredSsid(String ssid);
    void onBlocked(int count);
}
