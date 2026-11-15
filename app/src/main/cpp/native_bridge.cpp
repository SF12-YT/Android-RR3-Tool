#include <jni.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <cstdint>
#include <limits>
#include <memory>
#include <new>
#include <sstream>
#include <string>

#include "nfsnl.h"
#include "nfsnl_save.h"

namespace {

namespace android_nct {
#include "nct_rc4.inc"

struct Stream {
    uint8_t state[256];
    unsigned i = 0;
    unsigned j = 0;

    Stream() {
        std::memcpy(state, kNctRc4Init, 256);
        for (unsigned k = 0; k < 256; ++k) {
            uint8_t t = state[k];
            j = (j + t + kNctRc4Key[k & 7]) & 255;
            state[k] = state[j];
            state[j] = t;
        }
        j = 0;
        for (unsigned k = 0; k < kNctRc4Drop; ++k) next();
    }

    uint8_t next() {
        i = (i + 1) & 255;
        j = (j + state[i]) & 255;
        std::swap(state[i], state[j]);
        return state[(state[i] + state[j]) & 255];
    }
};

bool verifyAgainstUpstream() {
    nfsnl::Bytes sample(8193);
    for (size_t i = 0; i < sample.size(); ++i)
        sample[i] = static_cast<uint8_t>((i * 37 + 11) & 0xff);

    nfsnl::Bytes expected;
    if (!nfsnl::nctTransform(sample.data(), sample.size(), expected))
        return false;

    Stream stream;
    const size_t chunks[] = { 1, 17, 1024, 4093, 257 };
    size_t offset = 0, turn = 0;
    while (offset < sample.size()) {
        const size_t count = std::min(chunks[turn++ % 5], sample.size() - offset);
        for (size_t i = 0; i < count; ++i)
            sample[offset + i] ^= stream.next();
        offset += count;
    }
    return sample == expected;
}
} // namespace android_nct

thread_local std::string gDataEditError;
thread_local std::string gRr3AudioError;
thread_local std::string gRr3TransformError;
thread_local std::string gRr3CarError;
thread_local std::string gRr3CareerError;

std::string fromJava(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring toJava(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

std::string jsonString(const std::string& value) {
    std::ostringstream out;
    out << '"';
    for (unsigned char c : value) {
        switch (c) {
            case '"': out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (c < 0x20) {
                    const char* hex = "0123456789abcdef";
                    out << "\\u00" << hex[(c >> 4) & 0xf] << hex[c & 0xf];
                } else {
                    out << static_cast<char>(c);
                }
        }
    }
    out << '"';
    return out.str();
}

std::string errorJson(const std::string& error) {
    return std::string("{\"ok\":false,\"error\":") + jsonString(error) + "}";
}

std::string okJson() {
    return "{\"ok\":true,\"error\":\"\"}";
}

std::string stampNow() {
    // Unique per write so a second edit never overwrites an earlier backup.
    const auto now = std::chrono::system_clock::now().time_since_epoch();
    return std::to_string(
        std::chrono::duration_cast<std::chrono::milliseconds>(now).count());
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeStatus(JNIEnv* env, jobject /* thiz */) {
    std::string rr3Profile = "not found";
    for (const auto& profile : nfsnl::gameProfiles()) {
        if (profile.id == "real_racing_3") {
            rr3Profile = profile.name;
            break;
        }
    }

    const auto hiddenFields = nfsnl::saves::rr3HiddenFields();
    std::string status =
        "C++17 engine loaded; profile: " + rr3Profile +
        "; hidden save fields: " + std::to_string(hiddenFields.size()) + ".";
    status += "; streaming NCT self-test: " +
        (android_nct::verifyAgainstUpstream() ? "passed" : "FAILED");
    return toJava(env, status);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeInspectRr3Save(
    JNIEnv* env, jobject /* thiz */, jstring savePath, jstring keysPath) {
    const std::string path = fromJava(env, savePath);
    const std::string keyFile = fromJava(env, keysPath);
    if (path.empty()) return toJava(env, errorJson("Save path is empty."));

    nfsnl::saves::Rr3Save save;
    std::string error;
    if (!save.load(path, error)) return toJava(env, errorJson(error));

    const auto keys = nfsnl::saves::loadKeys(keyFile);
    std::ostringstream out;
    out << "{\"ok\":true,\"error\":\"\",\"fields\":[";

    bool first = true;
    for (size_t index : save.sortedRoot()) {
        if (index >= save.root.size()) continue;
        const auto& record = save.root[index];
        if (!first) out << ',';
        first = false;
        out << "{\"name\":" << jsonString(record.name)
            << ",\"value\":" << jsonString(record.valueString())
            << ",\"editable\":" << (record.editable() ? "true" : "false")
            << ",\"type\":" << record.type() << '}';
    }

    out << "],\"hidden\":[";
    const auto& hiddenFields = nfsnl::saves::rr3HiddenFields();
    for (size_t i = 0; i < hiddenFields.size(); ++i) {
        const auto& hidden = hiddenFields[i];
        uint64_t value = 0;
        const bool calibrated = save.hiddenGet(hidden, keys, value);
        if (i) out << ',';
        out << "{\"label\":" << jsonString(hidden.label)
            << ",\"field\":" << jsonString(hidden.field)
            << ",\"keyName\":" << jsonString(hidden.keyName)
            << ",\"wide\":" << (hidden.wide ? "true" : "false")
            << ",\"calibrated\":" << (calibrated ? "true" : "false")
            << ",\"value\":";
        if (calibrated) out << jsonString(std::to_string(value));
        else out << "null";
        out << '}';
    }
    out << "]}";
    return toJava(env, out.str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeCalibrateRr3Save(
    JNIEnv* env, jobject /* thiz */, jstring savePath, jstring keysPath,
    jint hiddenIndex, jstring displayedValue) {
    const std::string path = fromJava(env, savePath);
    const std::string keyFile = fromJava(env, keysPath);
    const std::string shownText = fromJava(env, displayedValue);

    const auto& hiddenFields = nfsnl::saves::rr3HiddenFields();
    if (hiddenIndex < 0 || static_cast<size_t>(hiddenIndex) >= hiddenFields.size())
        return toJava(env, errorJson("Unknown hidden field."));

    uint64_t shown = 0;
    std::string error;
    if (!nfsnl::saves::rr3ParseNum(shownText, shown, error))
        return toJava(env, errorJson(error));

    nfsnl::saves::Rr3Save save;
    if (!save.load(path, error)) return toJava(env, errorJson(error));

    auto keys = nfsnl::saves::loadKeys(keyFile);
    if (!save.calibrate(hiddenFields[static_cast<size_t>(hiddenIndex)], keys, shown, error))
        return toJava(env, errorJson(error));
    if (!nfsnl::saves::saveKeys(keyFile, keys))
        return toJava(env, errorJson("Could not save calibration keys."));
    return toJava(env, okJson());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeSetRr3SaveField(
    JNIEnv* env, jobject /* thiz */, jstring savePath, jstring keysPath,
    jstring fieldName, jstring valueText) {
    const std::string path = fromJava(env, savePath);
    const std::string keyFile = fromJava(env, keysPath);
    const std::string name = fromJava(env, fieldName);
    const std::string value = fromJava(env, valueText);

    nfsnl::saves::Rr3Save save;
    std::string error;
    if (!save.load(path, error)) return toJava(env, errorJson(error));

    const auto& hiddenFields = nfsnl::saves::rr3HiddenFields();
    auto keys = nfsnl::saves::loadKeys(keyFile);
    bool handled = false;
    for (const auto& hidden : hiddenFields) {
        if (name != hidden.field) continue;
        uint64_t parsed = 0;
        if (!nfsnl::saves::rr3ParseNum(value, parsed, error))
            return toJava(env, errorJson(error));
        if (!save.hiddenSet(hidden, keys, parsed, error))
            return toJava(env, errorJson(error));
        handled = true;
        break;
    }

    if (!handled) {
        nfsnl::saves::Rr3Rec* record = save.find(name);
        if (!record) return toJava(env, errorJson("Field not found in this save."));
        if (!record->setFromString(value, error))
            return toJava(env, errorJson(error));
    }

    std::string backupDir;
    if (!save.save(stampNow(), backupDir, error))
        return toJava(env, errorJson(error));
    return toJava(env, std::string("{\"ok\":true,\"error\":\"\",\"backup\":") +
                         jsonString(backupDir) + "}");
}




extern "C" JNIEXPORT jlong JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeCreateRr3TransformStream(
    JNIEnv* /* env */, jobject /* thiz */) {
    if (!android_nct::verifyAgainstUpstream()) return 0;
    try {
        return static_cast<jlong>(
            reinterpret_cast<intptr_t>(new android_nct::Stream()));
    } catch (const std::bad_alloc&) {
        return 0;
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeTransformRr3Chunk(
    JNIEnv* env, jobject /* thiz */, jlong handle, jbyteArray chunk, jint length) {
    auto* stream = reinterpret_cast<android_nct::Stream*>(
        static_cast<intptr_t>(handle));
    if (!stream || !chunk || length < 0 || length > env->GetArrayLength(chunk))
        return JNI_FALSE;
    if (length == 0) return JNI_TRUE;

    jbyte* data = env->GetByteArrayElements(chunk, nullptr);
    if (!data) return JNI_FALSE;
    uint8_t* bytes = reinterpret_cast<uint8_t*>(data);
    for (jint k = 0; k < length; ++k)
        bytes[k] ^= stream->next();
    env->ReleaseByteArrayElements(chunk, data, 0);
    return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeFreeRr3TransformStream(
    JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    auto* stream = reinterpret_cast<android_nct::Stream*>(
        static_cast<intptr_t>(handle));
    delete stream;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeTransformRr3Data(
    JNIEnv* env, jobject /* thiz */, jbyteArray input) {
    gRr3TransformError.clear();
    if (!input) { gRr3TransformError = "No input data was supplied."; return nullptr; }
    const jsize length = env->GetArrayLength(input);
    if (length <= 0) { gRr3TransformError = "The selected file is empty."; return nullptr; }

    // Work directly from the Java array and reuse that same array for the
    // output. This avoids both an extra native input copy and a second Java
    // allocation for large assets such as the 14 MB career file.
    jbyte* source = env->GetByteArrayElements(input, nullptr);
    if (!source) {
        gRr3TransformError = "Android could not access the input data buffer.";
        return nullptr;
    }

    nfsnl::Bytes transformed;
    bool ok = false;
    try {
        ok = nfsnl::nctTransform(
            reinterpret_cast<const uint8_t*>(source),
            static_cast<size_t>(length), transformed);
    } catch (const std::bad_alloc&) {
        gRr3TransformError = "Not enough memory to decode this file. Close other apps and try again.";
    } catch (...) {
        gRr3TransformError = "The native RR3 transform failed unexpectedly.";
    }

    if (!ok || !gRr3TransformError.empty()) {
        env->ReleaseByteArrayElements(input, source, JNI_ABORT);
        if (gRr3TransformError.empty()) gRr3TransformError = "The RR3 data transform failed.";
        return nullptr;
    }
    if (transformed.size() != static_cast<size_t>(length)) {
        env->ReleaseByteArrayElements(input, source, JNI_ABORT);
        gRr3TransformError = "The RR3 transform returned an unexpected output length.";
        return nullptr;
    }

    std::memcpy(source, transformed.data(), transformed.size());
    // Mode 0 copies the modified bytes back if JNI gave us a temporary copy.
    env->ReleaseByteArrayElements(input, source, 0);
    return input;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeGetRr3TransformError(
    JNIEnv* env, jobject /* thiz */) {
    return toJava(env, gRr3TransformError.empty()
        ? "The RR3 data transform failed." : gRr3TransformError);
}


extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeTransformRr3Text(
    JNIEnv* env, jobject /* thiz */, jbyteArray input) {
    if (!input) return nullptr;
    const jsize length = env->GetArrayLength(input);
    if (length <= 0) return nullptr;

    nfsnl::Bytes source(static_cast<size_t>(length));
    env->GetByteArrayRegion(
        input, 0, length, reinterpret_cast<jbyte*>(source.data()));
    if (env->ExceptionCheck()) return nullptr;

    nfsnl::Bytes transformed;
    if (!nfsnl::rr3TextTransform(source.data(), source.size(), transformed))
        return nullptr;
    if (transformed.size() > static_cast<size_t>(std::numeric_limits<jsize>::max()))
        return nullptr;

    jbyteArray output = env->NewByteArray(static_cast<jsize>(transformed.size()));
    if (!output) return nullptr;
    env->SetByteArrayRegion(
        output, 0, static_cast<jsize>(transformed.size()),
        reinterpret_cast<const jbyte*>(transformed.data()));
    return output;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeRr3TextIsPlain(
    JNIEnv* env, jobject /* thiz */, jbyteArray input) {
    if (!input) return JNI_FALSE;
    const jsize length = env->GetArrayLength(input);
    if (length <= 0) return JNI_FALSE;

    nfsnl::Bytes source(static_cast<size_t>(length));
    env->GetByteArrayRegion(
        input, 0, length, reinterpret_cast<jbyte*>(source.data()));
    if (env->ExceptionCheck()) return JNI_FALSE;
    return nfsnl::rr3TextIsPlain(source.data(), source.size()) ? JNI_TRUE : JNI_FALSE;
}


extern "C" JNIEXPORT jstring JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeOpenDataAsText(
    JNIEnv* env, jobject /* thiz */, jstring fileName, jbyteArray input) {
    if (!input) return nullptr;
    const jsize length = env->GetArrayLength(input);
    if (length <= 0) return nullptr;

    const std::string path = fromJava(env, fileName);
    nfsnl::Bytes source(static_cast<size_t>(length));
    env->GetByteArrayRegion(
        input, 0, length, reinterpret_cast<jbyte*>(source.data()));
    if (env->ExceptionCheck()) return nullptr;

    std::string text;
    gDataEditError.clear();
    if (!nfsnl::dataEditableText(path, source.data(), source.size(), text, gDataEditError))
        return nullptr;
    return toJava(env, text);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeBuildEditedData(
    JNIEnv* env, jobject /* thiz */, jstring fileName, jstring editedText,
    jbyteArray original) {
    if (!original) return nullptr;
    const jsize length = env->GetArrayLength(original);
    if (length <= 0) return nullptr;

    const std::string path = fromJava(env, fileName);
    const std::string text = fromJava(env, editedText);
    nfsnl::Bytes source(static_cast<size_t>(length));
    env->GetByteArrayRegion(
        original, 0, length, reinterpret_cast<jbyte*>(source.data()));
    if (env->ExceptionCheck()) return nullptr;

    nfsnl::Bytes output;
    gDataEditError.clear();
    if (!nfsnl::dataFromEditedText(
            path, text, source.data(), source.size(), output, gDataEditError))
        return nullptr;
    if (output.size() > static_cast<size_t>(std::numeric_limits<jsize>::max()))
        return nullptr;

    jbyteArray result = env->NewByteArray(static_cast<jsize>(output.size()));
    if (!result) return nullptr;
    env->SetByteArrayRegion(
        result, 0, static_cast<jsize>(output.size()),
        reinterpret_cast<const jbyte*>(output.data()));
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeGetDataEditHelp(
    JNIEnv* env, jobject /* thiz */) {
    return toJava(
        env,
        "Supported RR3 data files are converted by the upstream data editor. "
        "The editor preserves the original file as a reference while building a new output file.");
}


extern "C" JNIEXPORT jstring JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeGetDataEditError(
    JNIEnv* env, jobject /* thiz */) {
    return toJava(env, gDataEditError.empty() ? "The data operation failed." : gDataEditError);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeUnwrapRr3Asset(
    JNIEnv* env, jobject /* thiz */, jstring fileName, jbyteArray input) {
    if (!input) return nullptr;
    const jsize length = env->GetArrayLength(input);
    if (length <= 0) return nullptr;

    const std::string name = fromJava(env, fileName);
    nfsnl::Bytes source(static_cast<size_t>(length));
    env->GetByteArrayRegion(
        input, 0, length, reinterpret_cast<jbyte*>(source.data()));
    if (env->ExceptionCheck()) return nullptr;

    nfsnl::Bytes output;
    std::string innerName;
    if (!nfsnl::rr3Unwrap(name, source.data(), source.size(), output, &innerName, nullptr))
        return nullptr;
    if (output.size() > static_cast<size_t>(std::numeric_limits<jsize>::max()))
        return nullptr;

    jbyteArray result = env->NewByteArray(static_cast<jsize>(output.size()));
    if (!result) return nullptr;
    env->SetByteArrayRegion(
        result, 0, static_cast<jsize>(output.size()),
        reinterpret_cast<const jbyte*>(output.data()));
    return result;
}


extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeConvertRr3Audio(
    JNIEnv* env, jobject /* thiz */, jstring fileName, jbyteArray input) {
    if (!input) return nullptr;
    const jsize length = env->GetArrayLength(input);
    if (length <= 0) return nullptr;

    std::string name = fromJava(env, fileName);
    for (char& c : name) {
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    }

    nfsnl::Bytes source(static_cast<size_t>(length));
    env->GetByteArrayRegion(
        input, 0, length, reinterpret_cast<jbyte*>(source.data()));
    if (env->ExceptionCheck()) return nullptr;

    nfsnl::Bytes wav;
    std::string error;
    bool converted = false;
    if (name.size() >= 4 && name.compare(name.size() - 4, 4, ".wem") == 0) {
        converted = nfsnl::wemToWav(source.data(), source.size(), wav, &error);
    } else if (name.size() >= 5 && name.compare(name.size() - 5, 5, ".gnsu") == 0) {
        converted = nfsnl::gnsuToWav(source.data(), source.size(), wav, &error);
    }
    if (!converted || wav.empty() ||
        wav.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        gRr3AudioError = error.empty()
            ? "This file is not a supported RR3 WEM/GNSU audio stream."
            : error;
        return nullptr;
    }
    gRr3AudioError.clear();

    jbyteArray result = env->NewByteArray(static_cast<jsize>(wav.size()));
    if (!result) return nullptr;
    env->SetByteArrayRegion(
        result, 0, static_cast<jsize>(wav.size()),
        reinterpret_cast<const jbyte*>(wav.data()));
    return result;
}


extern "C" JNIEXPORT jstring JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeGetRr3AudioError(
    JNIEnv* env, jobject /* thiz */) {
    return toJava(
        env,
        gRr3AudioError.empty()
            ? "The RR3 audio conversion failed."
            : gRr3AudioError);
}


extern "C" JNIEXPORT jstring JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeInspectRr3Car(
    JNIEnv* env, jobject /* thiz */, jbyteArray input) {
    gRr3CarError.clear();
    if (!input) return toJava(env, errorJson("No car data was supplied."));
    const jsize length = env->GetArrayLength(input);
    if (length <= 0) return toJava(env, errorJson("The car data file is empty."));
    jbyte* bytes = env->GetByteArrayElements(input, nullptr);
    if (!bytes) return toJava(env, errorJson("Could not read the car data buffer."));

    nfsnl::Rr3Car car;
    bool ok = false;
    try {
        ok = nfsnl::rr3CarRead(reinterpret_cast<const uint8_t*>(bytes),
                               static_cast<size_t>(length), car);
    } catch (const std::bad_alloc&) {
        gRr3CarError = "Not enough memory to inspect this car file.";
    } catch (...) {
        gRr3CarError = "The native car parser failed unexpectedly.";
    }
    env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
    if (!ok) {
        if (gRr3CarError.empty()) gRr3CarError = car.why.empty()
            ? "This file does not match the RR3 car-data format." : car.why;
        return toJava(env, errorJson(gRr3CarError));
    }

    std::ostringstream out;
    out << "{\"ok\":true,\"fields\":[";
    const auto fields = nfsnl::rr3CarFields(car);
    for (size_t i = 0; i < fields.size(); ++i) {
        if (i) out << ',';
        out << "{\"key\":" << jsonString(fields[i].key)
            << ",\"label\":" << jsonString(fields[i].label)
            << ",\"value\":" << jsonString(fields[i].value)
            << ",\"text\":" << (fields[i].text ? "true" : "false") << '}';
    }
    out << "]}";
    return toJava(env, out.str());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeSetRr3CarField(
    JNIEnv* env, jobject /* thiz */, jbyteArray input, jstring keyText, jstring valueText) {
    gRr3CarError.clear();
    if (!input) { gRr3CarError = "No car data was supplied."; return nullptr; }
    const jsize length = env->GetArrayLength(input);
    if (length <= 0) { gRr3CarError = "The car data file is empty."; return nullptr; }

    jbyte* bytes = env->GetByteArrayElements(input, nullptr);
    if (!bytes) { gRr3CarError = "Could not read the car data buffer."; return nullptr; }
    nfsnl::Rr3Car car;
    bool parsed = false;
    try {
        parsed = nfsnl::rr3CarRead(reinterpret_cast<const uint8_t*>(bytes),
                                   static_cast<size_t>(length), car);
    } catch (const std::bad_alloc&) {
        gRr3CarError = "Not enough memory to update this car file.";
    } catch (...) {
        gRr3CarError = "The native car parser failed unexpectedly.";
    }
    env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
    if (!parsed) {
        if (gRr3CarError.empty()) gRr3CarError = car.why.empty()
            ? "This file does not match the RR3 car-data format." : car.why;
        return nullptr;
    }

    std::string why;
    if (!nfsnl::rr3CarSet(car, fromJava(env, keyText), fromJava(env, valueText), why)) {
        gRr3CarError = why.empty() ? "The car field could not be changed." : why;
        return nullptr;
    }

    nfsnl::Bytes output;
    try {
        if (!nfsnl::rr3CarWrite(car, output, why)) {
            gRr3CarError = why.empty() ? "The car file could not be rebuilt." : why;
            return nullptr;
        }
    } catch (const std::bad_alloc&) {
        gRr3CarError = "Not enough memory to rebuild this car file.";
        return nullptr;
    } catch (...) {
        gRr3CarError = "The native car writer failed unexpectedly.";
        return nullptr;
    }
    if (output.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        gRr3CarError = "The rebuilt car file is too large for Android.";
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(output.size()));
    if (!result) { gRr3CarError = "Android could not allocate the rebuilt car file."; return nullptr; }
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()),
                            reinterpret_cast<const jbyte*>(output.data()));
    if (env->ExceptionCheck()) {
        gRr3CarError = "Android could not copy the rebuilt car file.";
        return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeGetRr3CarError(
    JNIEnv* env, jobject /* thiz */) {
    return toJava(env, gRr3CarError.empty() ? "The RR3 car operation failed." : gRr3CarError);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeInspectRr3Career(
    JNIEnv* env, jobject /* thiz */, jbyteArray input) {
    gRr3CareerError.clear();
    if (!input) return toJava(env, errorJson("No career data was supplied."));
    const jsize length = env->GetArrayLength(input);
    if (length <= 0) return toJava(env, errorJson("The career data file is empty."));
    jbyte* bytes = env->GetByteArrayElements(input, nullptr);
    if (!bytes) return toJava(env, errorJson("Could not read the career data buffer."));

    nfsnl::Bytes plain;
    nfsnl::Rr3Career career;
    bool parsed = false;
    std::string why;
    try {
        if (!nfsnl::nctTransform(reinterpret_cast<const uint8_t*>(bytes),
                                 static_cast<size_t>(length), plain)) {
            why = "The NCT transform failed.";
        } else {
            parsed = nfsnl::rr3CareerRead(plain.data(), plain.size(), career, why);
        }
    } catch (const std::bad_alloc&) {
        gRr3CareerError = "Not enough memory to inspect this career file. Close other apps and try again.";
    } catch (...) {
        gRr3CareerError = "The native career parser failed unexpectedly.";
    }
    env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
    if (!parsed) {
        if (gRr3CareerError.empty()) gRr3CareerError = why.empty()
            ? "This file does not match the supported RR3 career format." : why;
        return toJava(env, errorJson(gRr3CareerError));
    }

    size_t eventCount = 0;
    for (const auto& tier : career.tiers) eventCount += tier.events.size();
    std::ostringstream out;
    out << "{\"ok\":true,\"version\":" << career.version
        << ",\"tracks\":" << career.tracks.size()
        << ",\"seriesCount\":" << career.series.size()
        << ",\"tiersCount\":" << career.tiers.size()
        << ",\"superGroups\":" << career.superGroups.size()
        << ",\"eventsCount\":" << eventCount
        << ",\"series\":[";
    const size_t limit = std::min<size_t>(career.series.size(), 160);
    for (size_t i = 0; i < limit; ++i) {
        const auto& series = career.series[i];
        if (i) out << ',';
        out << "{\"id\":" << series.id()
            << ",\"type\":" << series.type()
            << ",\"titleKey\":" << jsonString(series.titleKey())
            << ",\"descriptionKey\":" << jsonString(series.descKey()) << '}';
    }
    out << "],\"tiers\":[";
    const size_t tierLimit = std::min<size_t>(career.tiers.size(), 240);
    for (size_t i = 0; i < tierLimit; ++i) {
        const auto& tier = career.tiers[i];
        if (i) out << ',';
        out << "{\"id\":" << tier.id()
            << ",\"seriesId\":" << tier.seriesId()
            << ",\"index\":" << tier.indexInSeries()
            << ",\"cars\":" << tier.cars.size()
            << ",\"events\":" << tier.events.size() << '}';
    }
    out << "]}";
    return toJava(env, out.str());
}


extern "C" JNIEXPORT jstring JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeInspectRr3CareerFile(
    JNIEnv* env, jobject /* thiz */, jstring plainPath) {
    gRr3CareerError.clear();
    const std::string path = fromJava(env, plainPath);
    nfsnl::Bytes plain;
    if (path.empty() || !nfsnl::readFile(path, plain) || plain.empty())
        return toJava(env, errorJson("Could not read the decoded career file from temporary storage."));

    nfsnl::Rr3Career career;
    std::string why;
    bool parsed = false;
    try {
        parsed = nfsnl::rr3CareerRead(plain.data(), plain.size(), career, why);
    } catch (const std::bad_alloc&) {
        gRr3CareerError = "Not enough native memory to inspect the career database.";
    } catch (...) {
        gRr3CareerError = "The career parser failed unexpectedly.";
    }
    if (!parsed) {
        if (gRr3CareerError.empty()) gRr3CareerError = why.empty()
            ? "This file does not match the supported RR3 career format." : why;
        return toJava(env, errorJson(gRr3CareerError));
    }

    size_t eventCount = 0;
    for (const auto& tier : career.tiers) eventCount += tier.events.size();
    std::ostringstream out;
    out << "{\"ok\":true,\"version\":" << career.version
        << ",\"tracks\":" << career.tracks.size()
        << ",\"seriesCount\":" << career.series.size()
        << ",\"tiersCount\":" << career.tiers.size()
        << ",\"superGroups\":" << career.superGroups.size()
        << ",\"eventsCount\":" << eventCount
        << ",\"series\":[";
    const size_t limit = std::min<size_t>(career.series.size(), 160);
    for (size_t i = 0; i < limit; ++i) {
        const auto& series = career.series[i];
        if (i) out << ',';
        out << "{\"id\":" << series.id()
            << ",\"type\":" << series.type()
            << ",\"titleKey\":" << jsonString(series.titleKey())
            << ",\"descriptionKey\":" << jsonString(series.descKey()) << '}';
    }
    out << "],\"tiers\":[";
    const size_t tierLimit = std::min<size_t>(career.tiers.size(), 240);
    for (size_t i = 0; i < tierLimit; ++i) {
        const auto& tier = career.tiers[i];
        if (i) out << ',';
        out << "{\"id\":" << tier.id()
            << ",\"seriesId\":" << tier.seriesId()
            << ",\"index\":" << tier.indexInSeries()
            << ",\"cars\":" << tier.cars.size()
            << ",\"events\":" << tier.events.size() << '}';
    }
    out << "]}";
    return toJava(env, out.str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeInspectRr3Track(
    JNIEnv* env, jobject /* thiz */, jbyteArray input) {
    if (!input) return toJava(env, errorJson("No track data was supplied."));
    const jsize length = env->GetArrayLength(input);
    if (length <= 0) return toJava(env, errorJson("The track data file is empty."));
    jbyte* bytes = env->GetByteArrayElements(input, nullptr);
    if (!bytes) return toJava(env, errorJson("Could not read the track data buffer."));

    const std::vector<std::string> assets = {
        "Tracks/data/rr3tool-track-circuit-day-1.bin.nct"
    };
    const nfsnl::Bytes source(
        reinterpret_cast<const uint8_t*>(bytes),
        reinterpret_cast<const uint8_t*>(bytes) + length);
    env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);

    auto read = [&](const std::string& path, nfsnl::Bytes& output) -> bool {
        if (path != assets[0]) return false;
        output = source;
        return true;
    };

    std::vector<nfsnl::Rr3TrackInfo> tracks;
    try {
        tracks = nfsnl::rr3ListTracks(assets, read);
    } catch (const std::bad_alloc&) {
        return toJava(env, errorJson("Not enough memory to inspect this track data file."));
    } catch (...) {
        return toJava(env, errorJson("The native track parser failed unexpectedly."));
    }
    if (tracks.empty())
        return toJava(env, errorJson("This file does not match the RR3 track-data format."));

    const auto& track = tracks.front();
    std::ostringstream out;
    out << "{\"ok\":true,\"id\":" << track.id
        << ",\"file\":" << jsonString(track.file)
        << ",\"name\":" << jsonString(track.name)
        << ",\"folder\":" << jsonString(track.folder)
        << ",\"time\":" << jsonString(track.time)
        << ",\"layout\":" << jsonString(track.layout)
        << ",\"country\":" << jsonString(track.country) << '}';
    return toJava(env, out.str());
}

