// CoreWLAN, CoreLocation, IOKit sleep, traffic counters, URL scheme and hotkey for the Go app
// (see system.go).

#import <Carbon/Carbon.h>
#import <CoreLocation/CoreLocation.h>
#import <CoreWLAN/CoreWLAN.h>
#import <IOKit/IOMessage.h>
#import <IOKit/pwr_mgt/IOPMLib.h>
#include <net/if.h>
#include <net/route.h>
#include <sys/sysctl.h>

extern void goWillSleep(void);
extern void goOpenURL(char *url);
extern void goHotKey(void);

static CLLocationManager *locationManager;

// macOS 14+ hides the current SSID unless the app has Location permission.
void phtRequestLocation(void) {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!locationManager) locationManager = [[CLLocationManager alloc] init];
        if (locationManager.authorizationStatus == kCLAuthorizationStatusNotDetermined) {
            [locationManager requestWhenInUseAuthorization];
        }
    });
}

// Returns a malloc'ed SSID, or NULL when not on Wi-Fi or without Location permission.
char *phtCurrentSSID(void) {
    @autoreleasepool {
        NSString *ssid = [CWWiFiClient sharedWiFiClient].interface.ssid;
        return ssid ? strdup(ssid.UTF8String) : NULL;
    }
}

int phtWiFiPowerOn(void) {
    return [CWWiFiClient sharedWiFiClient].interface.powerOn;
}

// The network object from the last successful join, kept on disk. The phone keeps a stable BSSID,
// so it can be joined again without a scan (a scan takes seconds and sometimes misses a fresh AP).
static CWNetwork *lastNetwork;
static NSString *lastNetworkSSID;

static NSURL *networkFile(void) {
    NSURL *dir = [[NSFileManager.defaultManager URLsForDirectory:NSApplicationSupportDirectory
                                                       inDomains:NSUserDomainMask].firstObject
        URLByAppendingPathComponent:@"Untether"];
    [NSFileManager.defaultManager createDirectoryAtURL:dir withIntermediateDirectories:YES attributes:nil error:nil];
    return [dir URLByAppendingPathComponent:@"network.data"];
}

static void loadLastNetwork(void) {
    if (lastNetwork) return;
    NSData *data = [NSData dataWithContentsOfURL:networkFile()];
    if (!data) return;
    NSDictionary *d = [NSKeyedUnarchiver unarchivedObjectOfClasses:[NSSet setWithObjects:NSDictionary.class,
        NSString.class, CWNetwork.class, nil] fromData:data error:nil];
    lastNetwork = d[@"network"];
    lastNetworkSSID = d[@"ssid"];
}

static void saveLastNetwork(CWNetwork *network, NSString *ssid) {
    lastNetwork = network;
    lastNetworkSSID = ssid;
    NSData *data = [NSKeyedArchiver archivedDataWithRootObject:@{@"network": network, @"ssid": ssid}
                                         requiringSecureCoding:YES error:nil];
    [data writeToURL:networkFile() atomically:YES];
}

// Joins the network. Returns NULL on success or a malloc'ed error message.
// *scanned is set to 1 when the fast path (cached network) did not work and a scan was needed.
char *phtJoin(const char *ssid, const char *pass, int *scanned) {
    @autoreleasepool {
        *scanned = 0;
        CWInterface *iface = [CWWiFiClient sharedWiFiClient].interface;
        if (!iface) return strdup("no Wi-Fi interface");
        NSError *err = nil;
        if (!iface.powerOn && ![iface setPower:YES error:&err]) return strdup(err.localizedDescription.UTF8String);
        NSString *name = [NSString stringWithUTF8String:ssid];
        NSString *password = pass[0] ? [NSString stringWithUTF8String:pass] : nil; // nil: open network
        loadLastNetwork();
        if (lastNetwork && [lastNetworkSSID isEqualToString:name]) {
            if ([iface associateToNetwork:lastNetwork password:password error:&err]) return NULL;
            // Stale (e.g. the AP moved to another channel): forget it so retries go straight to a scan.
            lastNetwork = nil;
            [NSFileManager.defaultManager removeItemAtURL:networkFile() error:nil];
        }
        *scanned = 1;
        NSSet<CWNetwork *> *found = [iface scanForNetworksWithName:name error:&err];
        CWNetwork *network = found.anyObject;
        if (!network) return strdup(err ? err.localizedDescription.UTF8String : "hotspot not visible yet");
        if (![iface associateToNetwork:network password:password error:&err]) {
            return strdup(err.localizedDescription.UTF8String);
        }
        saveLastNetwork(network, name);
        return NULL;
    }
}

void phtLeave(void) {
    @autoreleasepool {
        [[CWWiFiClient sharedWiFiClient].interface disassociate];
    }
}

// 64-bit byte counters of the Wi-Fi interface. Returns 0 on success.
int phtWiFiBytes(uint64_t *in, uint64_t *out) {
    NSString *name = [CWWiFiClient sharedWiFiClient].interface.interfaceName;
    unsigned int index = name ? if_nametoindex(name.UTF8String) : 0;
    if (!index) return -1;
    int mib[6] = {CTL_NET, PF_ROUTE, 0, 0, NET_RT_IFLIST2, (int)index};
    size_t len = 0;
    if (sysctl(mib, 6, NULL, &len, NULL, 0) < 0) return -1;
    char *buf = malloc(len);
    int rc = -1;
    if (sysctl(mib, 6, buf, &len, NULL, 0) == 0) {
        for (char *p = buf; p < buf + len; p += ((struct if_msghdr *)p)->ifm_msglen) {
            if (((struct if_msghdr *)p)->ifm_type == RTM_IFINFO2) {
                struct if_msghdr2 *m = (struct if_msghdr2 *)p;
                *in = m->ifm_data.ifi_ibytes;
                *out = m->ifm_data.ifi_obytes;
                rc = 0;
                break;
            }
        }
    }
    free(buf);
    return rc;
}

static io_connect_t rootPort;

static void powerCallback(void *ref, io_service_t service, natural_t type, void *arg) {
    if (type == kIOMessageSystemWillSleep) goWillSleep(); // may block briefly; sleep waits (max 30 s)
    if (type == kIOMessageSystemWillSleep || type == kIOMessageCanSystemSleep) {
        IOAllowPowerChange(rootPort, (long)arg);
    }
}

// Delivers sleep notifications on a private queue so goWillSleep can block without stalling the UI.
void phtObserveSleep(void) {
    IONotificationPortRef port;
    io_object_t notifier;
    rootPort = IORegisterForSystemPower(NULL, &port, powerCallback, &notifier);
    if (rootPort) IONotificationPortSetDispatchQueue(port, dispatch_queue_create("sleep", NULL));
}

// untether:// URLs. Not wired up yet: needs CFBundleURLTypes in Info.plist and a caller in Go.
@interface PHTURLHandler : NSObject
@end

@implementation PHTURLHandler
- (void)handle:(NSAppleEventDescriptor *)event reply:(NSAppleEventDescriptor *)reply {
    NSString *url = [event paramDescriptorForKeyword:keyDirectObject].stringValue;
    if (url) goOpenURL((char *)url.UTF8String);
}
@end

static PHTURLHandler *urlHandler;

void phtObserveURLs(void) {
    urlHandler = [PHTURLHandler new];
    [[NSAppleEventManager sharedAppleEventManager] setEventHandler:urlHandler
                                                       andSelector:@selector(handle:reply:)
                                                     forEventClass:kInternetEventClass
                                                        andEventID:kAEGetURL];
}

// Global hotkey ⌃⌥⌘H through Carbon: works without Accessibility permission.
static EventHotKeyRef hotKey;

static OSStatus hotKeyHandler(EventHandlerCallRef next, EventRef event, void *data) {
    goHotKey();
    return noErr;
}

void phtSetHotKey(int on) {
    dispatch_async(dispatch_get_main_queue(), ^{
        static int installed;
        if (!installed) {
            EventTypeSpec spec = {kEventClassKeyboard, kEventHotKeyPressed};
            InstallApplicationEventHandler(&hotKeyHandler, 1, &spec, NULL, NULL);
            installed = 1;
        }
        if (hotKey) {
            UnregisterEventHotKey(hotKey);
            hotKey = NULL;
        }
        if (on) {
            EventHotKeyID id = {'PHTK', 1};
            RegisterEventHotKey(kVK_ANSI_H, cmdKey | optionKey | controlKey, id, GetApplicationEventTarget(), 0, &hotKey);
        }
    });
}
