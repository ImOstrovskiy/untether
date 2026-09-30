// Status bar item with a popover that hosts the HTML UI (ui/index.html) in a WKWebView.
// JS -> Go: window.webkit.messageHandlers.pht.postMessage({action, arg}) -> goUIMessage.
// Go -> JS: phtEval(js).

#import <AppKit/AppKit.h>
#import <WebKit/WebKit.h>

extern void goUIMessage(char *action, char *arg);
extern void goUIStarted(void);

static const CGFloat kWidth = 340;

@interface PHTController : NSObject <NSPopoverDelegate, WKScriptMessageHandler>
@property(strong) NSStatusItem *item;
@property(strong) NSPopover *popover;
@property(strong) WKWebView *web;
@end

@implementation PHTController

- (void)setupWithHTML:(NSString *)html {
    WKWebViewConfiguration *config = [WKWebViewConfiguration new];
    [config.userContentController addScriptMessageHandler:self name:@"pht"];
    self.web = [[WKWebView alloc] initWithFrame:NSMakeRect(0, 0, kWidth, 420) configuration:config];
    [self.web setValue:@NO forKey:@"drawsBackground"]; // let the popover material show through
    [self.web loadHTMLString:html baseURL:nil];

    NSViewController *vc = [NSViewController new];
    vc.view = self.web;
    self.popover = [NSPopover new];
    self.popover.contentViewController = vc;
    self.popover.contentSize = NSMakeSize(kWidth, 420);
    self.popover.behavior = NSPopoverBehaviorTransient;
    self.popover.animates = YES;

    self.item = [[NSStatusBar systemStatusBar] statusItemWithLength:NSSquareStatusItemLength];
    self.item.button.target = self;
    self.item.button.action = @selector(toggle:);
    self.item.button.toolTip = @"Pixel Hotspot";
}

- (void)toggle:(id)sender {
    if (self.popover.shown) [self.popover performClose:sender];
    else [self show];
}

- (void)show {
    if (self.popover.shown) return;
    [NSApp activateIgnoringOtherApps:YES];
    [self.popover showRelativeToRect:self.item.button.bounds ofView:self.item.button preferredEdge:NSRectEdgeMinY];
    [self.web.window makeFirstResponder:self.web];
}

- (void)userContentController:(WKUserContentController *)ucc didReceiveScriptMessage:(WKScriptMessage *)message {
    NSDictionary *body = [message.body isKindOfClass:NSDictionary.class] ? message.body : @{};
    NSString *action = [body[@"action"] description] ?: @"";
    NSString *arg = [body[@"arg"] description] ?: @"";
    if ([action isEqualToString:@"height"]) {
        CGFloat h = MAX(120, MIN(900, arg.doubleValue));
        self.popover.contentSize = NSMakeSize(kWidth, h);
        return;
    }
    goUIMessage((char *)action.UTF8String, (char *)arg.UTF8String);
}
@end

static PHTController *controller;
static NSSound *findSound;

// Runs the Cocoa event loop on the main thread; never returns.
void phtRun(const char *html) {
    @autoreleasepool {
        [NSApplication sharedApplication];
        [NSApp setActivationPolicy:NSApplicationActivationPolicyAccessory];
        controller = [PHTController new];
        [controller setupWithHTML:[NSString stringWithUTF8String:html]];
        goUIStarted();
        [NSApp run];
    }
}

void phtEval(const char *js) {
    NSString *s = [NSString stringWithUTF8String:js];
    dispatch_async(dispatch_get_main_queue(), ^{
        [controller.web evaluateJavaScript:s completionHandler:nil];
    });
}

// PNG bytes of an 18 pt @2x template image.
void phtSetIcon(const void *png, int len) {
    NSData *data = [NSData dataWithBytes:png length:len];
    dispatch_async(dispatch_get_main_queue(), ^{
        NSImage *img = [[NSImage alloc] initWithData:data];
        img.size = NSMakeSize(18, 18);
        img.template = YES;
        controller.item.button.image = img;
    });
}

void phtShowPopover(void) {
    dispatch_async(dispatch_get_main_queue(), ^{ [controller show]; });
}

// Find-my-Mac: a system sound on a loop until stopped.
void phtFindSound(int on) {
    dispatch_async(dispatch_get_main_queue(), ^{
        [findSound stop];
        findSound = nil;
        if (!on) return;
        findSound = [[NSSound soundNamed:@"Sosumi"] copy];
        findSound.loops = YES;
        [findSound play];
    });
}

void phtQuit(void) {
    dispatch_async(dispatch_get_main_queue(), ^{ [NSApp terminate:nil]; });
}

// First preferred language, e.g. "uk-UA". Caller frees.
char *phtLanguage(void) {
    NSString *lang = NSLocale.preferredLanguages.firstObject ?: @"en";
    return strdup(lang.UTF8String);
}
