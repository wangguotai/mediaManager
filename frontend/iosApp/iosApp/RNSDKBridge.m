#import "RNSDKBridge.h"
#import <objc/runtime.h>
#import <objc/message.h>
#import <Foundation/Foundation.h>

// 通过 ObjC runtime 调用 RNSDK Swift 类 (避免依赖 RNSDK-Swift.h)
// RNSDK Swift 类用 @objcMembers 标记, 对 ObjC runtime 可见
// 但 Swift class 在 runtime 里带模块前缀 (如 "RNSDK.RNContainerManager"),
// objc_getClass("RNContainerManager") 返回 nil, 需尝试多个名字.

static id getRNSDKManager(void) {
    const char *classNames[] = {
        "RNSDK.RNContainerManager",
        "RNContainerManager",
        NULL
    };
    Class cls = nil;
    for (int i = 0; classNames[i] != NULL; i++) {
        cls = objc_getClass(classNames[i]);
        if (cls) break;
    }
    if (!cls) {
        // 遍历已注册 class 模糊匹配
        unsigned int count = 0;
        Class *classes = objc_copyClassList(&count);
        for (unsigned int i = 0; i < count; i++) {
            if (strstr(class_getName(classes[i]), "RNContainerManager") != NULL) {
                cls = classes[i];
                break;
            }
        }
        free(classes);
        if (!cls) return nil;
    }
    return ((id (*)(Class, SEL))objc_msgSend)(cls, sel_registerName("shared"));
}

void RNSDKBridge_initialize(BOOL useDevSupport) {
    // RNSDK 是 Swift enum, ObjC runtime 无法直接调用.
    // 改为直接通过 RNContainerManager.shared 初始化 (@objcMembers class, runtime 可见)
    id manager = getRNSDKManager();
    if (!manager) return;
    ((void (*)(id, SEL, BOOL))objc_msgSend)(manager, sel_registerName("initializeWithUseDevSupport:"), useDevSupport);
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
