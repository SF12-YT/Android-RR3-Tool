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
#include <set>
#include <cerrno>
#include <sys/stat.h>

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


std::string careerTextEscape(const std::string& value) {
    static const char* hex = "0123456789ABCDEF";
    std::string out;
    out.reserve(value.size());
    for (unsigned char c : value) {
        switch (c) {
            case '\\': out += "\\\\"; break;
            case '\t': out += "\\t"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            default:
                if (c < 0x20 || c == 0x7f) {
                    out += "\\x";
                    out += hex[(c >> 4) & 0xf];
                    out += hex[c & 0xf];
                } else out += static_cast<char>(c);
        }
    }
    return out;
}

int careerHexDigit(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

bool careerTextUnescape(const std::string& value, std::string& out) {
    out.clear();
    for (size_t i = 0; i < value.size(); ++i) {
        if (value[i] != '\\') { out.push_back(value[i]); continue; }
        if (++i >= value.size()) return false;
        switch (value[i]) {
            case '\\': out.push_back('\\'); break;
            case 't': out.push_back('\t'); break;
            case 'n': out.push_back('\n'); break;
            case 'r': out.push_back('\r'); break;
            case 'x': {
                if (i + 2 >= value.size()) return false;
                int hi = careerHexDigit(value[i + 1]), lo = careerHexDigit(value[i + 2]);
                if (hi < 0 || lo < 0) return false;
                out.push_back(static_cast<char>((hi << 4) | lo));
                i += 2;
                break;
            }
            default: return false;
        }
    }
    return true;
}

std::vector<std::string> careerSplitTabs(const std::string& line) {
    std::vector<std::string> fields;
    size_t start = 0;
    while (true) {
        size_t end = line.find('\t', start);
        if (end == std::string::npos) { fields.push_back(line.substr(start)); break; }
        fields.push_back(line.substr(start, end - start));
        start = end + 1;
    }
    return fields;
}

bool careerParseI32(const std::string& value, int32_t& out) {
    try {
        size_t used = 0;
        long long parsed = std::stoll(value, &used, 10);
        if (used != value.size() || parsed < std::numeric_limits<int32_t>::min() ||
            parsed > std::numeric_limits<int32_t>::max()) return false;
        out = static_cast<int32_t>(parsed);
        return true;
    } catch (...) { return false; }
}

bool careerParseIndex(const std::string& value, size_t& out) {
    int32_t parsed = 0;
    if (!careerParseI32(value, parsed) || parsed < 0) return false;
    out = static_cast<size_t>(parsed);
    return true;
}

bool careerToEditableText(const nfsnl::Rr3Career& career,
                          std::string& text, std::string& why) {
    std::ostringstream out;
    out << "# RR3_CAREER_TEXT_V1\n"
        << "# Editable working copy for data/events.dat.\n"
        << "# Keep every SERIES, TIER and EVENT row. Do not change index or ID columns.\n"
        << "# SERIES: index, id, type, title_key, description_key, completion_reward\n"
        << "# TIER: index, id, series_id, index_in_series, name_key, display_key\n"
        << "# EVENT: tier_index, event_index, id, type, track, laps, fame, reward\n"
        << "# Strings support \\\\, \\t, \\n, \\r and \\xHH escapes.\n\n";
    for (size_t i = 0; i < career.series.size(); ++i) {
        const auto& s = career.series[i];
        if (s.head.size() < 4 || s.tail.size() < 5) {
            why = "A series record is missing fields required by the editor."; return false;
        }
        out << "SERIES\t" << i << '\t' << static_cast<int32_t>(s.head[0].u) << '\t'
            << static_cast<int32_t>(s.head[2].u) << '\t'
            << careerTextEscape(s.head[1].s) << '\t'
            << careerTextEscape(s.head[3].s) << '\t'
            << careerTextEscape(s.tail[3].s) << '\n';
    }
    out << '\n';
    size_t eventCount = 0;
    for (size_t i = 0; i < career.tiers.size(); ++i) {
        const auto& t = career.tiers[i];
        if (t.head.size() < 8) {
            why = "A tier record is missing fields required by the editor."; return false;
        }
        out << "TIER\t" << i << '\t' << static_cast<int32_t>(t.head[3].u) << '\t'
            << static_cast<int32_t>(t.head[4].u) << '\t'
            << static_cast<int32_t>(t.head[2].u) << '\t'
            << careerTextEscape(t.head[0].s) << '\t'
            << careerTextEscape(t.head[1].s) << '\n';
        eventCount += t.events.size();
    }
    out << '\n';
    for (size_t ti = 0; ti < career.tiers.size(); ++ti) {
        const auto& t = career.tiers[ti];
        for (size_t ei = 0; ei < t.events.size(); ++ei) {
            const auto& e = t.events[ei];
            if (e.a.size() < 9) {
                why = "An event record is missing fields required by the editor."; return false;
            }
            out << "EVENT\t" << ti << '\t' << ei << '\t'
                << static_cast<int32_t>(e.a[1].u) << '\t'
                << static_cast<int32_t>(e.a[0].u) << '\t'
                << careerTextEscape(e.a[2].s) << '\t'
                << static_cast<int32_t>(e.a[3].u) << '\t'
                << static_cast<int32_t>(e.a[7].u) << '\t'
                << static_cast<int32_t>(e.a[8].u) << '\n';
        }
    }
    text = out.str();
    return true;
}

bool careerApplyEditableText(nfsnl::Rr3Career& career,
                             const std::string& text, std::string& why) {
    std::vector<bool> seenSeries(career.series.size(), false);
    std::vector<bool> seenTiers(career.tiers.size(), false);
    std::vector<std::vector<bool>> seenEvents;
    for (const auto& tier : career.tiers) seenEvents.emplace_back(tier.events.size(), false);

    std::istringstream input(text);
    std::string line;
    size_t lineNumber = 0, seriesCount = 0, tierCount = 0, eventCount = 0;
    while (std::getline(input, line)) {
        ++lineNumber;
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.empty() || line[0] == '#') continue;
        auto f = careerSplitTabs(line);
        auto bad = [&](const std::string& message) {
            why = "Line " + std::to_string(lineNumber) + ": " + message; return false;
        };
        if (f[0] == "SERIES") {
            if (f.size() != 7) return bad("SERIES rows need 6 tab-separated values.");
            size_t i = 0; int32_t id = 0, type = 0;
            std::string title, desc, reward;
            if (!careerParseIndex(f[1], i) || i >= career.series.size()) return bad("invalid series index.");
            if (seenSeries[i]) return bad("duplicate series row.");
            auto& s = career.series[i];
            if (!careerParseI32(f[2], id) || !careerParseI32(f[3], type) ||
                id != s.id() || type != s.type()) return bad("series ID and type columns are fixed.");
            if (!careerTextUnescape(f[4], title) || !careerTextUnescape(f[5], desc) ||
                !careerTextUnescape(f[6], reward)) return bad("invalid string escape.");
            if (s.head.size() < 4 || s.tail.size() < 5) return bad("source series record is incomplete.");
            s.head[1].s = std::move(title); s.head[3].s = std::move(desc);
            s.tail[3].s = std::move(reward);
            seenSeries[i] = true; ++seriesCount;
        } else if (f[0] == "TIER") {
            if (f.size() != 7) return bad("TIER rows need 6 tab-separated values.");
            size_t i = 0; int32_t id = 0, seriesId = 0, pos = 0;
            std::string name, display;
            if (!careerParseIndex(f[1], i) || i >= career.tiers.size()) return bad("invalid tier index.");
            if (seenTiers[i]) return bad("duplicate tier row.");
            auto& t = career.tiers[i];
            if (!careerParseI32(f[2], id) || id != t.id() ||
                !careerParseI32(f[4], pos) || pos != t.indexInSeries())
                return bad("tier ID and index columns are fixed.");
            if (!careerParseI32(f[3], seriesId)) return bad("invalid series ID.");
            if (!careerTextUnescape(f[5], name) || !careerTextUnescape(f[6], display))
                return bad("invalid string escape.");
            if (t.head.size() < 8) return bad("source tier record is incomplete.");
            t.head[4].u = static_cast<uint32_t>(seriesId);
            t.head[0].s = std::move(name); t.head[1].s = std::move(display);
            seenTiers[i] = true; ++tierCount;
        } else if (f[0] == "EVENT") {
            if (f.size() != 9) return bad("EVENT rows need 8 tab-separated values.");
            size_t ti = 0, ei = 0;
            int32_t id = 0, type = 0, laps = 0, fame = 0, reward = 0;
            std::string track;
            if (!careerParseIndex(f[1], ti) || ti >= career.tiers.size()) return bad("invalid event tier index.");
            if (!careerParseIndex(f[2], ei) || ei >= career.tiers[ti].events.size())
                return bad("invalid event index.");
            if (seenEvents[ti][ei]) return bad("duplicate event row.");
            auto& e = career.tiers[ti].events[ei];
            if (!careerParseI32(f[3], id) || id != e.id()) return bad("event ID column is fixed.");
            if (!careerParseI32(f[4], type) || !careerTextUnescape(f[5], track) ||
                !careerParseI32(f[6], laps) || !careerParseI32(f[7], fame) ||
                !careerParseI32(f[8], reward)) return bad("invalid event value or string escape.");
            if (e.a.size() < 9) return bad("source event record is incomplete.");
            e.a[0].u = static_cast<uint32_t>(type); e.a[2].s = std::move(track);
            e.a[3].u = static_cast<uint32_t>(laps); e.a[7].u = static_cast<uint32_t>(fame);
            e.a[8].u = static_cast<uint32_t>(reward);
            seenEvents[ti][ei] = true; ++eventCount;
        } else return bad("unknown record type. Only SERIES, TIER and EVENT rows are supported.");
    }

    if (seriesCount != career.series.size()) {
        why = "The text copy is missing one or more SERIES rows; keep every row in the file."; return false;
    }
    if (tierCount != career.tiers.size()) {
        why = "The text copy is missing one or more TIER rows; keep every row in the file."; return false;
    }
    size_t expectedEvents = 0;
    for (size_t i = 0; i < career.tiers.size(); ++i) {
        expectedEvents += career.tiers[i].events.size();
        if (std::find(seenEvents[i].begin(), seenEvents[i].end(), false) != seenEvents[i].end()) {
            why = "The text copy is missing one or more EVENT rows; keep every row in the file.";
            return false;
        }
    }
    if (eventCount != expectedEvents) {
        why = "The text copy does not contain the expected number of EVENT rows."; return false;
    }
    return true;
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
    status += std::string("; streaming NCT self-test: ") +
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



namespace {
std::vector<std::string> stringsFromJava(JNIEnv* env, jobjectArray array) {
    std::vector<std::string> out;
    if (!array) return out;
    const jsize count = env->GetArrayLength(array);
    out.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        out.push_back(fromJava(env, value));
        if (value) env->DeleteLocalRef(value);
    }
    return out;
}

bool makeParentDirectories(const std::string& filePath) {
    const size_t last = filePath.find_last_of('/');
    if (last == std::string::npos) return true;
    const std::string parent = filePath.substr(0, last);
    size_t start = !parent.empty() && parent[0] == '/' ? 1 : 0;
    for (size_t i = start; i <= parent.size(); ++i) {
        if (i != parent.size() && parent[i] != '/') continue;
        const std::string part = parent.substr(0, i);
        if (part.empty()) continue;
        if (mkdir(part.c_str(), 0700) != 0 && errno != EEXIST) return false;
    }
    return true;
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeInspectRr3Liveries(
    JNIEnv* env, jobject /* thiz */, jbyteArray input) {
    if (!input) return toJava(env, errorJson("No livery-list data was supplied."));
    const jsize length = env->GetArrayLength(input);
    if (length <= 0) return toJava(env, errorJson("The livery-list file is empty."));

    jbyte* bytes = env->GetByteArrayElements(input, nullptr);
    if (!bytes) return toJava(env, errorJson("Could not read the livery-list buffer."));
    nfsnl::Bytes plain;
    bool transformed = false;
    try {
        size_t covered = 0;
        transformed = nfsnl::nctTransform(
            reinterpret_cast<const uint8_t*>(bytes),
            static_cast<size_t>(length), plain, &covered) && covered >= static_cast<size_t>(length);
    } catch (...) {
        transformed = false;
    }
    env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
    if (!transformed)
        return toJava(env, errorJson("The .liveries.bin.nct file could not be decoded."));

    nfsnl::Rr3Liveries liveries;
    bool parsed = false;
    try {
        parsed = nfsnl::rr3LiveriesRead(plain.data(), plain.size(), liveries);
    } catch (const std::bad_alloc&) {
        return toJava(env, errorJson("Not enough memory to inspect this livery list."));
    } catch (...) {
        return toJava(env, errorJson("The native livery-list parser failed."));
    }
    if (!parsed)
        return toJava(env, errorJson("This file does not match the RR3 livery-list format."));

    std::ostringstream out;
    out << "{\"ok\":true,\"car\":" << jsonString(liveries.car)
        << ",\"count\":" << liveries.liveries.size() << ",\"liveries\":[";
    for (size_t i = 0; i < liveries.liveries.size(); ++i) {
        const auto& item = liveries.liveries[i];
        if (i) out << ',';
        std::string texture;
        for (const auto& overrideEntry : item.overrides) {
            if (overrideEntry.second.compare(0, 7, "livery/") == 0) {
                texture = overrideEntry.second;
                break;
            }
        }
        out << "{\"file\":" << jsonString(item.file)
            << ",\"display\":" << jsonString(item.display)
            << ",\"texture\":" << jsonString(texture)
            << ",\"red\":" << static_cast<unsigned>(item.rgb[0])
            << ",\"green\":" << static_cast<unsigned>(item.rgb[1])
            << ",\"blue\":" << static_cast<unsigned>(item.rgb[2]) << '}';
    }
    out << "]}";
    return toJava(env, out.str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeAddRr3Livery(
    JNIEnv* env, jobject /* thiz */,
    jstring carFolderText, jobjectArray assetPathArray, jobjectArray sourcePathArray,
    jstring outputDirectoryText, jstring liveryNameText, jint templateIndex,
    jint red, jint green, jint blue) {
    const std::string carFolder = fromJava(env, carFolderText);
    const std::string outputDirectory = fromJava(env, outputDirectoryText);
    const std::string liveryName = fromJava(env, liveryNameText);
    if (carFolder.empty() || outputDirectory.empty() || liveryName.empty())
        return toJava(env, errorJson("The car folder, output folder and livery name are required."));
    if (carFolder.find('/') != std::string::npos || carFolder.find('\\') != std::string::npos ||
        carFolder.find("..") != std::string::npos)
        return toJava(env, errorJson("Invalid car folder name."));
    if (red < 0 || red > 255 || green < 0 || green > 255 || blue < 0 || blue > 255)
        return toJava(env, errorJson("RGB values must be between 0 and 255."));
    if (liveryName.size() > 120)
        return toJava(env, errorJson("The livery display name is too long (maximum 120 UTF-8 bytes)."));

    const std::vector<std::string> assetsInput = stringsFromJava(env, assetPathArray);
    const std::vector<std::string> sourceFiles = stringsFromJava(env, sourcePathArray);
    if (assetsInput.empty() || assetsInput.size() != sourceFiles.size())
        return toJava(env, errorJson("The livery assets and their cached source files do not match."));

    std::vector<std::string> assets;
    std::map<std::string, std::string> pathByAsset;
    assets.reserve(assetsInput.size());
    for (size_t i = 0; i < assetsInput.size(); ++i) {
        const auto& asset = assetsInput[i];
        if (asset.empty() || asset[0] == '/' || asset.find("..") != std::string::npos ||
            asset.find('\\') != std::string::npos)
            return toJava(env, errorJson("An unsafe asset path was supplied."));
        if (std::find(assets.begin(), assets.end(), asset) == assets.end()) assets.push_back(asset);
        if (!sourceFiles[i].empty()) pathByAsset.emplace(asset, sourceFiles[i]);
    }

    const uint8_t sourceRgb[3] = {
        static_cast<uint8_t>(red), static_cast<uint8_t>(green), static_cast<uint8_t>(blue)
    };
    nfsnl::Rr3NewLivery spec;
    spec.name = liveryName;
    std::copy(sourceRgb, sourceRgb + 3, spec.rgb);
    spec.from = static_cast<int>(templateIndex);
    spec.repaint = true;

    auto readAsset = [&](const std::string& path, nfsnl::Bytes& output) -> bool {
        const auto found = pathByAsset.find(path);
        if (found == pathByAsset.end()) return false;
        return nfsnl::readFile(found->second, output);
    };

    std::vector<nfsnl::Rr3CarOutput> outputs;
    std::string report;
    bool ok = false;
    try {
        ok = nfsnl::rr3AddLiveries(
            carFolder, std::vector<nfsnl::Rr3NewLivery>{spec},
            assets, readAsset, outputs, report);
    } catch (const std::bad_alloc&) {
        return toJava(env, errorJson("Not enough native memory to add this livery. The original assets are unchanged."));
    } catch (...) {
        return toJava(env, errorJson("The RR3 livery writer failed unexpectedly. The original assets are unchanged."));
    }
    if (!ok || outputs.empty())
        return toJava(env, errorJson(report.empty() ? "The new livery could not be built." : report));

    std::ostringstream filesJson;
    filesJson << '[';
    for (size_t i = 0; i < outputs.size(); ++i) {
        const auto& item = outputs[i];
        if (item.path.empty() || item.path[0] == '/' || item.path.find("..") != std::string::npos ||
            item.path.find('\\') != std::string::npos)
            return toJava(env, errorJson("The livery writer returned an unsafe output path."));
        const std::string destination = outputDirectory + "/" + item.path;
        if (!makeParentDirectories(destination) || !nfsnl::writeFile(destination, item.data))
            return toJava(env, errorJson("Could not write livery output: " + item.path));
        if (i) filesJson << ',';
        filesJson << "{\"path\":" << jsonString(item.path)
                  << ",\"bytes\":" << item.data.size()
                  << ",\"note\":" << jsonString(item.note) << '}';
    }
    filesJson << ']';
    return toJava(env, "{\"ok\":true,\"report\":" + jsonString(report) +
        ",\"files\":" + filesJson.str() + "}");
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
    nfsnl::Rr3Career career;
    std::string why;
    bool parsed = false;
    try {
        if (path.empty() || !nfsnl::readFile(path, plain) || plain.empty())
            return toJava(env, errorJson("Could not read the decoded career file from temporary storage."));
        parsed = nfsnl::rr3CareerRead(plain.data(), plain.size(), career, why);
    } catch (const std::bad_alloc&) {
        gRr3CareerError = "Not enough native memory to load or inspect the career database.";
    } catch (...) {
        gRr3CareerError = "The career file could not be loaded or parsed safely.";
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
Java_com_sf12_rr3tool_NativeBridge_nativeExportRr3CareerText(
    JNIEnv* env, jobject /* thiz */, jstring plainPath, jstring textPath) {
    gRr3CareerError.clear();
    const std::string sourcePath = fromJava(env, plainPath);
    const std::string outputPath = fromJava(env, textPath);
    if (sourcePath.empty() || outputPath.empty())
        return toJava(env, errorJson("The career cache paths are missing."));
    try {
        nfsnl::Bytes plain;
        if (!nfsnl::readFile(sourcePath, plain) || plain.empty())
            return toJava(env, errorJson("Could not read the decoded events.dat cache file."));
        nfsnl::Rr3Career career;
        std::string why;
        if (!nfsnl::rr3CareerRead(plain.data(), plain.size(), career, why))
            return toJava(env, errorJson(why.empty() ? "This is not a supported RR3 career database." : why));
        std::string editable;
        if (!careerToEditableText(career, editable, why))
            return toJava(env, errorJson(why));
        if (!nfsnl::writeFile(outputPath,
                reinterpret_cast<const uint8_t*>(editable.data()), editable.size()))
            return toJava(env, errorJson("Could not write the editable career .txt file to cache."));
        size_t events = 0;
        for (const auto& tier : career.tiers) events += tier.events.size();
        std::ostringstream result;
        result << "{\"ok\":true,\"series\":" << career.series.size()
               << ",\"tiers\":" << career.tiers.size()
               << ",\"events\":" << events << ",\"textBytes\":" << editable.size() << '}';
        return toJava(env, result.str());
    } catch (const std::bad_alloc&) {
        return toJava(env, errorJson("Not enough native memory to create the editable career text. The source file is unchanged."));
    } catch (...) {
        return toJava(env, errorJson("The career text export failed unexpectedly. The source file is unchanged."));
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeBuildRr3CareerText(
    JNIEnv* env, jobject /* thiz */, jstring plainPath, jstring textPath, jstring outputPath) {
    gRr3CareerError.clear();
    const std::string sourcePath = fromJava(env, plainPath);
    const std::string editablePath = fromJava(env, textPath);
    const std::string rebuiltPath = fromJava(env, outputPath);
    if (sourcePath.empty() || editablePath.empty() || rebuiltPath.empty())
        return toJava(env, errorJson("One or more career editor cache paths are missing."));
    try {
        nfsnl::Bytes plain, textBytes;
        if (!nfsnl::readFile(sourcePath, plain) || plain.empty())
            return toJava(env, errorJson("The original decoded events.dat cache file is missing."));
        if (!nfsnl::readFile(editablePath, textBytes) || textBytes.empty())
            return toJava(env, errorJson("The editable career .txt file is empty or missing."));
        nfsnl::Rr3Career career;
        std::string why;
        if (!nfsnl::rr3CareerRead(plain.data(), plain.size(), career, why))
            return toJava(env, errorJson(why.empty() ? "The original events.dat could not be parsed." : why));
        const std::string editable(textBytes.begin(), textBytes.end());
        if (!careerApplyEditableText(career, editable, why))
            return toJava(env, errorJson(why.empty() ? "The editable career text is invalid." : why));
        nfsnl::Bytes rebuilt = nfsnl::rr3CareerWrite(career);
        if (rebuilt.empty()) return toJava(env, errorJson("The RR3 career writer produced an empty file."));
        nfsnl::Rr3Career verify;
        std::string verifyWhy;
        if (!nfsnl::rr3CareerRead(rebuilt.data(), rebuilt.size(), verify, verifyWhy) ||
            verify.series.size() != career.series.size() ||
            verify.tiers.size() != career.tiers.size())
            return toJava(env, errorJson("The rebuilt career database failed validation: " + verifyWhy));
        if (!nfsnl::writeFile(rebuiltPath, rebuilt))
            return toJava(env, errorJson("Could not write the rebuilt events.dat file to cache."));
        std::ostringstream result;
        result << "{\"ok\":true,\"bytes\":" << rebuilt.size() << '}';
        return toJava(env, result.str());
    } catch (const std::bad_alloc&) {
        return toJava(env, errorJson("Not enough native memory to rebuild events.dat. The original and editable text are unchanged."));
    } catch (...) {
        return toJava(env, errorJson("The RR3 career writer failed unexpectedly. The original and editable text are unchanged."));
    }
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

