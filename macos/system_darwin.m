// CoreWLAN, CoreLocation and IOKit sleep notifications for the Go app (see system.go).

#import <CoreLocation/CoreLocation.h>
#import <CoreWLAN/CoreWLAN.h>
#import <IOKit/IOMessage.h>
#import <IOKit/pwr_mgt/IOPMLib.h>

extern void goWillSleep(void);

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

// Joins the network. Returns NULL on success or a malloc'ed error message.
char *phtJoin(const char *ssid, const char *pass) {
    @autoreleasepool {
        CWInterface *iface = [CWWiFiClient sharedWiFiClient].interface;
        if (!iface) return strdup("no Wi-Fi interface");
        NSError *err = nil;
        if (!iface.powerOn && ![iface setPower:YES error:&err]) return strdup(err.localizedDescription.UTF8String);
        NSSet<CWNetwork *> *found = [iface scanForNetworksWithName:[NSString stringWithUTF8String:ssid] error:&err];
        CWNetwork *network = found.anyObject;
        if (!network) return strdup(err ? err.localizedDescription.UTF8String : "hotspot not visible yet");
        if (![iface associateToNetwork:network password:[NSString stringWithUTF8String:pass] error:&err]) {
            return strdup(err.localizedDescription.UTF8String);
        }
        return NULL;
    }
}

void phtLeave(void) {
    @autoreleasepool {
        [[CWWiFiClient sharedWiFiClient].interface disassociate];
    }
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
