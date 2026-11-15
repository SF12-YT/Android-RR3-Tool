#include <jni.h>

#include <string>

#include "nfsnl.h"
#include "nfsnl_save.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeStatus(JNIEnv* env, jobject /* thiz */) {
    std::string rr3Profile = "not found";
    for (const auto& profile : nfsnl::gameProfiles()) {
        std::string name = profile.name;
        if (name.find("Real Racing 3") != std::string::npos) {
            rr3Profile = profile.id;
            break;
        }
    }

    const auto hiddenFields = nfsnl::rr3HiddenFields();
    std::string status =
        "C++17 engine loaded; RR3 profile: " + rr3Profile +
        "; save calibration fields: " + std::to_string(hiddenFields.size()) +
        ". Android document access is not connected yet.";

    return env->NewStringUTF(status.c_str());
}
