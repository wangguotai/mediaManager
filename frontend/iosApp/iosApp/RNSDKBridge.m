#import "RNSDKBridge.h"
#import <objc/runtime.h>
#import <objc/message.h>
#import <Foundation/Foundation.h>

// 通过 ObjC runtime 调用 RNSDK Swift 类 (避免依赖 RNSDK-Swift.h)
// RNSDK Swift 类用 @objcMembers 标记, 对 ObjC runtime 可见

static id getRNSDKManager(void) {
    Class cls = objc_getClass("RNContainerManager");
    if (!cls) return nil;
    return ((id (*)(Class, SEL))objc_msgSend)(cls, sel_registerName("shared"));
}

void RNSDKBridge_initialize(BOOL useDevSupport) {
    Class cls = objc_getClass("RNSDK");
    if (!cls) return;
    // [RNSDK initializeWithUseDevSupport:useDevSupport]
    ((void (*)(Class, SEL, BOOL))objc_msgSend)(cls, sel_registerName("initializeWithUseDevSupport:"), useDevSupport);
}

UIView *_Nullable RNSDKBridge_createView(NSString *moduleName) {
    id manager = getRNSDKManager();
    if (!manager) return nil;
    // [manager createRNViewWithModuleName:moduleName initialProperties:nil]
    return ((UIView *(*)(id, SEL, NSString *, id))objc_msgSend)(manager,
        sel_registerName("createRNViewWithModuleName:initialProperties:"), moduleName, nil);
}

UIView *_Nullable RNSDKBridge_createViewWithBundle(NSString *moduleName, NSString *bundlePath) {
    id manager = getRNSDKManager();
    if (!manager) return nil;
    NSURL *url = [NSURL fileURLWithPath:bundlePath];
    // [manager createRNViewWithBundleURL:url moduleName:moduleName initialProperties:nil]
    return ((UIView *(*)(id, SEL, NSURL *, NSString *, id))objc_msgSend)(manager,
        sel_registerName("createRNViewWithBundleURL:moduleName:initialProperties:"), url, moduleName, nil);
}

void RNSDKBridge_setBundleURL(NSURL *url) {
    id manager = getRNSDKManager();
    if (!manager) return;
    ((void (*)(id, SEL, NSURL *))objc_msgSend)(manager, sel_registerName("setBundleURL:"), url);
}

void RNSDKBridge_setDevServerURL(NSString *urlString) {
    id manager = getRNSDKManager();
    if (!manager) return;
    ((void (*)(id, SEL, NSString *))objc_msgSend)(manager, sel_registerName("setDevServerURL:"), urlString);
}
