#include <jni.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cstring>
#include <fstream>
#include <cstdint>
#include <limits>
#include <memory>
#include <map>
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

    Stream(const uint8_t* key = kNctRc4Key, unsigned drop = kNctRc4Drop) {
        std::memcpy(state, kNctRc4Init, 256);
        for (unsigned k = 0; k < 256; ++k) {
            uint8_t t = state[k];
            j = (j + t + key[k & 7]) & 255;
            state[k] = state[j];
            state[j] = t;
        }
        j = 0;
        for (unsigned k = 0; k < drop; ++k) next();
    }

    uint8_t next() {
        i = (i + 1) & 255;
        j = (j + state[i]) & 255;
        std::swap(state[i], state[j]);
        return state[(state[i] + state[j]) & 255];
    }
};

bool textFileIsPlain(const std::string& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) return false;
    uint8_t sample[256];
    input.read(reinterpret_cast<char*>(sample), sizeof(sample));
    const std::streamsize count = input.gcount();
    return count > 0 && nfsnl::rr3TextIsPlain(sample, static_cast<size_t>(count));
}

jlong transformTextFile(const std::string& inputPath, const std::string& outputPath) {
    if (inputPath.empty() || outputPath.empty() || inputPath == outputPath) return -1;
    std::ifstream input(inputPath, std::ios::binary);
    if (!input) return -1;
    std::ofstream output(outputPath, std::ios::binary | std::ios::trunc);
    if (!output) return -1;

    Stream stream(kTextRc4Key, kTextRc4Drop);
    std::array<uint8_t, 256 * 1024> buffer{};
    uint64_t total = 0;
    while (input) {
        input.read(reinterpret_cast<char*>(buffer.data()),
                   static_cast<std::streamsize>(buffer.size()));
        const std::streamsize got = input.gcount();
        if (got <= 0) break;
        for (std::streamsize i = 0; i < got; ++i) buffer[static_cast<size_t>(i)] ^= stream.next();
        output.write(reinterpret_cast<const char*>(buffer.data()), got);
        if (!output) return -1;
        total += static_cast<uint64_t>(got);
    }
    if (input.bad() || !input.eof() || !output) return -1;
    output.flush();
    if (!output || total > static_cast<uint64_t>(std::numeric_limits<jlong>::max())) return -1;
    return static_cast<jlong>(total);
}

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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeRr3TextFileIsPlain(
    JNIEnv* env, jobject /* thiz */, jstring pathText) {
    const std::string path = fromJava(env, pathText);
    return android_nct::textFileIsPlain(path) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeTransformRr3TextFile(
    JNIEnv* env, jobject /* thiz */, jstring inputPathText, jstring outputPathText) {
    const std::string inputPath = fromJava(env, inputPathText);
    const std::string outputPath = fromJava(env, outputPathText);
    try {
        return android_nct::transformTextFile(inputPath, outputPath);
    } catch (...) {
        return -1;
    }
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
Java_com_sf12_rr3tool_NativeBridge_nativeInspectRr3AudioBank(
    JNIEnv* env, jobject /* thiz */, jbyteArray input) {
    gRr3AudioError.clear();
    if (!input) {
        gRr3AudioError = "No Wwise sound-bank data was supplied.";
        return toJava(env, errorJson(gRr3AudioError));
    }

    const jsize length = env->GetArrayLength(input);
    if (length < 12) {
        gRr3AudioError = "The selected file is too small to be a Wwise sound bank.";
        return toJava(env, errorJson(gRr3AudioError));
    }

    nfsnl::Bytes source(static_cast<size_t>(length));
    env->GetByteArrayRegion(
        input, 0, length, reinterpret_cast<jbyte*>(source.data()));
    if (env->ExceptionCheck()) return nullptr;

    nfsnl::Bank bank;
    bool parsed = false;
    try {
        parsed = nfsnl::readBank(source.data(), source.size(), bank);
    } catch (...) {
        parsed = false;
    }
    if (!parsed) {
        gRr3AudioError = "This file is not a readable Wwise .bnk SoundBank.";
        return toJava(env, errorJson(gRr3AudioError));
    }

    for (const auto& entry : bank.entries) {
        if (entry.offset > source.size() ||
            entry.size > source.size() - entry.offset) {
            gRr3AudioError = "The SoundBank contains an invalid sound offset or size.";
            return toJava(env, errorJson(gRr3AudioError));
        }
    }

    std::ostringstream out;
    out << "{\"ok\":true"
        << ",\"bankId\":" << bank.id
        << ",\"version\":" << bank.version
        << ",\"hircObjects\":" << bank.hircObjects
        << ",\"sections\":[";
    for (size_t i = 0; i < bank.sections.size(); ++i) {
        if (i) out << ",";
        out << jsonString(bank.sections[i]);
    }
    out << "],\"sources\":[";
    for (size_t i = 0; i < bank.sources.size(); ++i) {
        if (i) out << ",";
        out << bank.sources[i];
    }
    out << "],\"entries\":[";
    for (size_t i = 0; i < bank.entries.size(); ++i) {
        const auto& entry = bank.entries[i];
        if (i) out << ",";
        out << "{\"id\":" << entry.id
            << ",\"offset\":" << static_cast<unsigned long long>(entry.offset)
            << ",\"size\":" << static_cast<unsigned long long>(entry.size)
            << ",\"kind\":" << jsonString(entry.kind)
            << ",\"codec\":" << entry.codec
            << ",\"channels\":" << entry.channels
            << ",\"sampleRate\":" << entry.sampleRate
            << ",\"samples\":" << entry.samples
            << "}";
    }
    out << "]}";
    return toJava(env, out.str());
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
            << ",\"swatch\":" << jsonString(item.swatch)
            << ",\"logo\":" << jsonString(item.logo)
            << ",\"flag\":" << static_cast<unsigned>(item.flag)
            << ",\"arms\":" << jsonString(item.arms)
            << ",\"driver\":" << jsonString(item.driver)
            << ",\"red\":" << static_cast<unsigned>(item.rgb[0])
            << ",\"green\":" << static_cast<unsigned>(item.rgb[1])
            << ",\"blue\":" << static_cast<unsigned>(item.rgb[2])
            << ",\"overrides\":[";
        for (size_t j = 0; j < item.overrides.size(); ++j) {
            if (j) out << ',';
            out << "{\"slot\":" << item.overrides[j].first
                << ",\"path\":" << jsonString(item.overrides[j].second) << '}';
        }
        out << "],\"materials\":[";
        for (size_t j = 0; j < item.materials.size(); ++j) {
            if (j) out << ',';
            out << "{\"mesh\":" << item.materials[j].first
                << ",\"material\":" << item.materials[j].second << '}';
        }
        out << "]}";
    }
    out << "]}";
    return toJava(env, out.str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeInspectRr3LiveryStringTable(
    JNIEnv* env, jobject /* thiz */, jbyteArray input) {
    if (!input) return toJava(env, errorJson("No livery string-table data was supplied."));
    const jsize length = env->GetArrayLength(input);
    if (length < 4) return toJava(env, errorJson("The livery string table is empty or truncated."));
    jbyte* bytes = env->GetByteArrayElements(input, nullptr);
    if (!bytes) return toJava(env, errorJson("Could not read the livery string-table buffer."));
    nfsnl::Bytes plain;
    bool ok = false;
    try {
        size_t covered = 0;
        ok = nfsnl::nctTransform(
            reinterpret_cast<const uint8_t*>(bytes),
            static_cast<size_t>(length), plain, &covered) &&
            covered >= static_cast<size_t>(length);
    } catch (...) {
        ok = false;
    }
    env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
    if (!ok || plain.size() < 4)
        return toJava(env, errorJson("The livery string table could not be decoded."));

    auto readU32 = [&](size_t at, uint32_t& value) -> bool {
        if (at + 4 > plain.size()) return false;
        const uint8_t* p = plain.data() + at;
        value = static_cast<uint32_t>(p[0]) |
                (static_cast<uint32_t>(p[1]) << 8) |
                (static_cast<uint32_t>(p[2]) << 16) |
                (static_cast<uint32_t>(p[3]) << 24);
        return true;
    };

    uint32_t count = 0;
    if (!readU32(0, count) || count > 200000)
        return toJava(env, errorJson("The livery string-table count is invalid."));
    size_t cursor = 4;
    std::ostringstream out;
    out << "{\"ok\":true,\"count\":" << count << ",\"strings\":[";
    for (uint32_t i = 0; i < count; ++i) {
        uint32_t length = 0;
        if (!readU32(cursor, length) || length > plain.size() - std::min(cursor + 4, plain.size()))
            return toJava(env, errorJson("The livery string table contains a truncated string."));
        cursor += 4;
        if (length > plain.size() - cursor)
            return toJava(env, errorJson("The livery string table contains a truncated string."));
        if (i) out << ',';
        out << "{\"id\":" << i << ",\"value\":"
            << jsonString(std::string(reinterpret_cast<const char*>(plain.data() + cursor), length))
            << '}';
        cursor += length;
    }
    if (cursor != plain.size())
        return toJava(env, errorJson("The livery string table has unexpected trailing data."));
    out << "]}";
    return toJava(env, out.str());
}


namespace {
bool copyJavaByteArray(JNIEnv* env, jbyteArray input, nfsnl::Bytes& out) {
    if (!input) return false;
    const jsize length = env->GetArrayLength(input);
    if (length <= 0) return false;
    jbyte* bytes = env->GetByteArrayElements(input, nullptr);
    if (!bytes) return false;
    out.assign(reinterpret_cast<const uint8_t*>(bytes),
               reinterpret_cast<const uint8_t*>(bytes) + length);
    env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
    return true;
}

std::vector<std::string> readGameTextKeys(const nfsnl::Bytes& plain) {
    std::vector<std::string> keys;
    std::set<std::string> seen;
    static const char prefix[] = "GAMETEXT_";
    const size_t prefixLength = sizeof(prefix) - 1;

    auto addKey = [&](const char* value, size_t length) {
        if (length < prefixLength || length > 160 ||
            std::memcmp(value, prefix, prefixLength) != 0) return;
        for (size_t i = 0; i < length; ++i) {
            const uint8_t c = static_cast<uint8_t>(value[i]);
            const bool valid = (c >= 'A' && c <= 'Z') ||
                               (c >= '0' && c <= '9') || c == '_';
            if (!valid) return;
        }
        std::string key(value, length);
        if (seen.insert(key).second) keys.push_back(std::move(key));
    };

    // Most RR3 paint tables store strings as a little-endian 32-bit length
    // followed by the key. Keep supporting that representation.
    for (size_t at = 0; at + 4 + prefixLength <= plain.size(); ++at) {
        const uint8_t* p = plain.data() + at;
        const uint32_t length = static_cast<uint32_t>(p[0]) |
                                (static_cast<uint32_t>(p[1]) << 8) |
                                (static_cast<uint32_t>(p[2]) << 16) |
                                (static_cast<uint32_t>(p[3]) << 24);
        if (length >= prefixLength && length <= 160 &&
            length <= plain.size() - at - 4) {
            addKey(reinterpret_cast<const char*>(p + 4), length);
        }
    }

    // Some RR3 revisions embed the same keys as ordinary terminated ASCII
    // strings rather than length-prefixed values. Scan those too; otherwise
    // only the few entries using the first representation appear in the UI.
    for (size_t at = 0; at + prefixLength <= plain.size(); ++at) {
        if (std::memcmp(plain.data() + at, prefix, prefixLength) != 0) continue;
        size_t end = at;
        while (end < plain.size() && end - at < 160) {
            const uint8_t c = plain[end];
            const bool valid = (c >= 'A' && c <= 'Z') ||
                               (c >= '0' && c <= '9') || c == '_';
            if (!valid) break;
            ++end;
        }
        addKey(reinterpret_cast<const char*>(plain.data() + at), end - at);
    }
    // Some versions store Unity strings as UTF-16LE/BE. The old scan
    // only saw byte-oriented ASCII and silently missed those paint entries.
    for (size_t at = 0; at + prefixLength * 2 <= plain.size(); ++at) {
        for (int endian = 0; endian < 2; ++endian) {
            std::string wideKey;
            wideKey.reserve(64);
            bool validPrefix = true;
            for (size_t i = 0; i < prefixLength; ++i) {
                const uint8_t first = plain[at + i * 2];
                const uint8_t second = plain[at + i * 2 + 1];
                const uint8_t character = endian == 0 ? first : second;
                const uint8_t zero = endian == 0 ? second : first;
                if (character != static_cast<uint8_t>(prefix[i]) || zero != 0) {
                    validPrefix = false;
                    break;
                }
            }
            if (!validPrefix) continue;

            const size_t limit = std::min<size_t>(160, (plain.size() - at) / 2);
            for (size_t i = 0; i < limit; ++i) {
                const uint8_t first = plain[at + i * 2];
                const uint8_t second = plain[at + i * 2 + 1];
                const uint8_t character = endian == 0 ? first : second;
                const uint8_t zero = endian == 0 ? second : first;
                const bool valid = (character >= 'A' && character <= 'Z') ||
                                   (character >= '0' && character <= '9') ||
                                   character == '_';
                if (zero != 0 || !valid) break;
                wideKey.push_back(static_cast<char>(character));
            }
            addKey(wideKey.data(), wideKey.size());
        }
    }
    return keys;
}

std::string base64Encode(const uint8_t* data, size_t length) {
    static const char alphabet[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    out.reserve(((length + 2) / 3) * 4);
    for (size_t i = 0; i < length; i += 3) {
        const uint32_t a = data[i];
        const uint32_t b = i + 1 < length ? data[i + 1] : 0;
        const uint32_t c = i + 2 < length ? data[i + 2] : 0;
        const uint32_t n = (a << 16) | (b << 8) | c;
        out.push_back(alphabet[(n >> 18) & 63]);
        out.push_back(alphabet[(n >> 12) & 63]);
        out.push_back(i + 1 < length ? alphabet[(n >> 6) & 63] : '=');
        out.push_back(i + 2 < length ? alphabet[n & 63] : '=');
    }
    return out;
}

struct Rr3TextDocument {
    bool encrypted = false;
    std::vector<std::string> lines;
    std::vector<std::string> endings; // exact line ending after each line
};

bool rr3ReadTextDocument(const nfsnl::Bytes& raw, Rr3TextDocument& doc) {
    if (raw.empty()) return false;
    doc = Rr3TextDocument();
    doc.encrypted = !nfsnl::rr3TextIsPlain(raw.data(), raw.size());

    nfsnl::Bytes decoded;
    if (doc.encrypted) {
        if (!nfsnl::rr3TextTransform(raw.data(), raw.size(), decoded) ||
            decoded.size() != raw.size()) return false;
    } else {
        decoded = raw;
    }

    const std::string text(decoded.begin(), decoded.end());
    size_t start = 0;
    while (true) {
        size_t end = start;
        while (end < text.size() && text[end] != '\r' && text[end] != '\n') ++end;
        doc.lines.push_back(text.substr(start, end - start));
        if (end == text.size()) break;

        if (text[end] == '\r' && end + 1 < text.size() && text[end + 1] == '\n') {
            doc.endings.emplace_back("\r\n");
            start = end + 2;
        } else {
            doc.endings.emplace_back(1, text[end]);
            start = end + 1;
        }
        if (start == text.size()) {
            // Keep the final empty line when the source ends with a newline.
            doc.lines.emplace_back();
            break;
        }
    }
    return !doc.lines.empty() && doc.endings.size() + 1 == doc.lines.size();
}

std::string rr3JoinTextDocument(const Rr3TextDocument& doc) {
    std::string text;
    size_t reserve = 0;
    for (const auto& line : doc.lines) reserve += line.size();
    for (const auto& ending : doc.endings) reserve += ending.size();
    text.reserve(reserve);
    for (size_t i = 0; i < doc.lines.size(); ++i) {
        text += doc.lines[i];
        if (i < doc.endings.size()) text += doc.endings[i];
    }
    return text;
}

bool rr3EncodeTextDocument(const Rr3TextDocument& doc, nfsnl::Bytes& output) {
    const std::string plain = rr3JoinTextDocument(doc);
    nfsnl::Bytes input(plain.begin(), plain.end());
    if (!doc.encrypted) {
        output.swap(input);
        return true;
    }

    if (!nfsnl::rr3TextTransform(input.data(), input.size(), output) ||
        output.size() != input.size()) return false;

    // Never emit a rebuilt language file unless the same game cipher decodes
    // it byte-for-byte to the text we intended to write.
    nfsnl::Bytes roundTrip;
    if (!nfsnl::rr3TextTransform(output.data(), output.size(), roundTrip) ||
        roundTrip != input) {
        output.clear();
        return false;
    }
    return true;
}

std::string rr3KeyWithoutBom(const std::string& line) {
    if (line.size() >= 3 &&
        static_cast<uint8_t>(line[0]) == 0xEF &&
        static_cast<uint8_t>(line[1]) == 0xBB &&
        static_cast<uint8_t>(line[2]) == 0xBF) {
        return line.substr(3);
    }
    return line;
}

std::string rr3NormaliseGameTextKey(const std::string& key) {
    return key.compare(0, 9, "GAMETEXT_") == 0 ? key : "GAMETEXT_" + key;
}

bool rr3LoadPaintTextDocuments(const nfsnl::Bytes& gameText,
                               const nfsnl::Bytes& englishText,
                               Rr3TextDocument& keys,
                               Rr3TextDocument& english,
                               std::map<std::string, size_t>& keyLines,
                               std::string& why) {
    if (!rr3ReadTextDocument(gameText, keys)) {
        why = "Could not decode gametext.txt.";
        return false;
    }
    if (!rr3ReadTextDocument(englishText, english)) {
        why = "Could not decode text_en.txt.";
        return false;
    }

    if (keys.lines.size() != english.lines.size()) {
        why = "gametext.txt and text_en.txt have different line counts. No files were changed.";
        return false;
    }

    keyLines.clear();
    for (size_t i = 0; i < keys.lines.size(); ++i) {
        const std::string key = rr3KeyWithoutBom(keys.lines[i]);
        if (key.compare(0, 9, "GAMETEXT_") == 0) keyLines[key] = i;
    }
    if (keyLines.empty()) {
        why = "No GAMETEXT keys were found in gametext.txt.";
        return false;
    }
    return true;
}

std::string rr3PaintTextValue(const Rr3TextDocument& english,
                              const std::map<std::string, size_t>& keyLines,
                              const std::string& key) {
    const auto found = keyLines.find(rr3NormaliseGameTextKey(key));
    if (found == keyLines.end() || found->second >= english.lines.size()) return {};
    return rr3KeyWithoutBom(english.lines[found->second]);
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeInspectRr3PaintLabels(
    JNIEnv* env, jobject /* thiz */, jbyteArray paintInput, jbyteArray packInput,
    jbyteArray gameTextInput, jbyteArray englishTextInput) {
    nfsnl::Bytes paintRaw, packRaw, gameText, englishText;
    if (!copyJavaByteArray(env, paintInput, paintRaw) ||
        !copyJavaByteArray(env, packInput, packRaw) ||
        !copyJavaByteArray(env, gameTextInput, gameText) ||
        !copyJavaByteArray(env, englishTextInput, englishText)) {
        return toJava(env, errorJson("One or more paint-label source files are missing or empty."));
    }

    nfsnl::Bytes paints, packs;
    try {
        size_t paintCovered = 0, packCovered = 0;
        if (!nfsnl::nctTransform(paintRaw.data(), paintRaw.size(), paints, &paintCovered) ||
            paintCovered < paintRaw.size() ||
            !nfsnl::nctTransform(packRaw.data(), packRaw.size(), packs, &packCovered) ||
            packCovered < packRaw.size()) {
            return toJava(env, errorJson("Could not decode custom.paints.nct or custom.paintpacks.nct."));
        }
    } catch (...) {
        return toJava(env, errorJson("The paint configuration decoder failed."));
    }

    Rr3TextDocument keys, english;
    std::map<std::string, size_t> keyLines;
    std::string why;
    if (!rr3LoadPaintTextDocuments(gameText, englishText, keys, english, keyLines, why)) {
        return toJava(env, errorJson(why));
    }

    const auto packKeys = readGameTextKeys(packs);
    const auto paintKeys = readGameTextKeys(paints);
    std::vector<std::string> specialLabels;
    for (const auto& key : packKeys) {
        const std::string prefix = "GAMETEXT_CUSTOMISATION_PAINTS_";
        // _SPECIAL is a category marker in the paint-pack table, not a
        // localisation string. Other entries remain visible even if this
        // game's gametext table has no editable English line for them.
        if (key.compare(0, prefix.size(), prefix) == 0 &&
            key.compare(key.size() >= 5 ? key.size() - 5 : 0, 5, "_NAME") != 0 &&
            key != "GAMETEXT_CUSTOMISATION_PAINTS_SPECIAL") {
            specialLabels.push_back(key);
        }
    }

    struct PaintOption {
        std::string materialKey;
        std::string colourKey;
    };
    std::vector<PaintOption> options;
    std::string pendingMaterial;
    const std::string materialPrefix = "GAMETEXT_CUSTOMISATION_PAINTS_";
    const std::string colourPrefix = "GAMETEXT_CUSTOMISATION_COLOUR_";
    const std::string colorPrefix = "GAMETEXT_CUSTOMISATION_COLOR_";
    for (const auto& key : paintKeys) {
        const bool isMaterialName =
            key.compare(0, materialPrefix.size(), materialPrefix) == 0 &&
            key.size() >= 5 && key.compare(key.size() - 5, 5, "_NAME") == 0;
        const bool isPaintTableEntry =
            key.compare(0, materialPrefix.size(), materialPrefix) == 0 &&
            key != "GAMETEXT_CUSTOMISATION_PAINTS_SPECIAL";
        const bool isColourName =
            key.compare(0, colourPrefix.size(), colourPrefix) == 0 ||
            key.compare(0, colorPrefix.size(), colorPrefix) == 0 ||
            (key.compare(0, 23, "GAMETEXT_CUSTOMISATION_") == 0 &&
             (key.find("COLOUR") != std::string::npos ||
              key.find("COLOR") != std::string::npos));

        if (isMaterialName) {
            if (!pendingMaterial.empty()) options.push_back({pendingMaterial, ""});
            pendingMaterial = key;
        } else if (isColourName || isPaintTableEntry) {
            // Some revisions use the PAINTS_* namespace for the colour too,
            // rather than the separate COLOUR_/COLOR_ prefixes. Keep those
            // entries visible instead of silently dropping everything but
            // the small subset matching the older naming convention.
            options.push_back({pendingMaterial, key});
            pendingMaterial.clear();
        }
    }
    if (!pendingMaterial.empty()) options.push_back({pendingMaterial, ""});

    std::ostringstream out;
    out << "{\"ok\":true,\"packLabels\":[";
    for (size_t i = 0; i < specialLabels.size(); ++i) {
        if (i) out << ',';
        const auto& key = specialLabels[i];
        out << "{\"key\":" << jsonString(key)
            << ",\"value\":" << jsonString(rr3PaintTextValue(english, keyLines, key))
            << ",\"editable\":" << (keyLines.find(key) != keyLines.end() ? "true" : "false") << '}';
    }
    out << "],\"paintOptions\":[";
    for (size_t i = 0; i < options.size(); ++i) {
        if (i) out << ',';
        const auto& option = options[i];
        const bool materialEditable = !option.materialKey.empty() &&
            keyLines.find(option.materialKey) != keyLines.end();
        const bool colourEditable = !option.colourKey.empty() &&
            keyLines.find(option.colourKey) != keyLines.end();
        out << "{\"materialKey\":" << jsonString(option.materialKey)
            << ",\"material\":" << jsonString(option.materialKey.empty() ? "" : rr3PaintTextValue(english, keyLines, option.materialKey))
            << ",\"materialEditable\":" << (materialEditable ? "true" : "false")
            << ",\"colourKey\":" << jsonString(option.colourKey)
            << ",\"colour\":" << jsonString(option.colourKey.empty() ? "" : rr3PaintTextValue(english, keyLines, option.colourKey))
            << ",\"colourEditable\":" << (colourEditable ? "true" : "false")
            << '}';
    }
    out << "]}";
    return toJava(env, out.str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeUpdateRr3PaintLabels(
    JNIEnv* env, jobject /* thiz */, jbyteArray gameTextInput, jbyteArray englishTextInput,
    jobjectArray keyArray, jobjectArray valueArray) {
    nfsnl::Bytes gameText, englishText;
    if (!copyJavaByteArray(env, gameTextInput, gameText) ||
        !copyJavaByteArray(env, englishTextInput, englishText)) {
        return toJava(env, errorJson("gametext.txt or text_en.txt is missing or empty."));
    }
    const jsize keyCount = keyArray ? env->GetArrayLength(keyArray) : 0;
    const jsize valueCount = valueArray ? env->GetArrayLength(valueArray) : 0;
    if (keyCount <= 0 || keyCount != valueCount || keyCount > 256) {
        return toJava(env, errorJson("Select at least one paint label to update."));
    }

    Rr3TextDocument keys, english;
    std::map<std::string, size_t> keyLines;
    std::string why;
    if (!rr3LoadPaintTextDocuments(gameText, englishText, keys, english, keyLines, why)) {
        return toJava(env, errorJson(why));
    }

    bool changed = false;
    for (jsize i = 0; i < keyCount; ++i) {
        auto keyObject = static_cast<jstring>(env->GetObjectArrayElement(keyArray, i));
        auto valueObject = static_cast<jstring>(env->GetObjectArrayElement(valueArray, i));
        if (!keyObject || !valueObject) {
            if (keyObject) env->DeleteLocalRef(keyObject);
            if (valueObject) env->DeleteLocalRef(valueObject);
            continue;
        }
        const std::string key = rr3NormaliseGameTextKey(fromJava(env, keyObject));
        const std::string value = fromJava(env, valueObject);
        env->DeleteLocalRef(keyObject);
        env->DeleteLocalRef(valueObject);

        if (key.empty() || value.empty()) continue;
        if (key.size() > 160 || value.size() > 160) {
            return toJava(env, errorJson("Paint labels must be no longer than 160 characters."));
        }
        const auto found = keyLines.find(key);
        if (found == keyLines.end() || found->second >= english.lines.size()) {
            return toJava(env, errorJson("The paint label key " + key + " does not exist in the indexed text files. No files were changed."));
        }

        std::string replacement = value;
        if (found->second == 0 && english.lines[0].size() >= 3 &&
            static_cast<uint8_t>(english.lines[0][0]) == 0xEF &&
            static_cast<uint8_t>(english.lines[0][1]) == 0xBB &&
            static_cast<uint8_t>(english.lines[0][2]) == 0xBF) {
            replacement = std::string("\xEF\xBB\xBF") + value;
        }
        if (english.lines[found->second] != replacement) {
            english.lines[found->second] = replacement;
            changed = true;
        }
    }

    if (!changed) {
        return toJava(env, errorJson("No paint-label changes were made."));
    }

    nfsnl::Bytes updatedEnglish;
    if (!rr3EncodeTextDocument(english, updatedEnglish) || updatedEnglish.empty()) {
        return toJava(env, errorJson("The updated text_en.txt failed its encoding check. No files were exported."));
    }

    std::ostringstream out;
    out << "{\"ok\":true,\"files\":[{\"name\":\"text_en.txt\",\"data\":"
        << jsonString(base64Encode(updatedEnglish.data(), updatedEnglish.size()))
        << "}]}";
    return toJava(env, out.str());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeUpdateRr3Livery(
    JNIEnv* env, jobject /* thiz */, jbyteArray input, jint liveryIndex,
    jstring displayText, jstring swatchText, jstring logoText,
    jstring armsText, jstring driverText, jint flag,
    jint red, jint green, jint blue, jintArray meshArray, jintArray materialArray) {
    if (!input) return nullptr;
    const jsize length = env->GetArrayLength(input);
    if (length <= 0 || liveryIndex < 0) return nullptr;
    jbyte* bytes = env->GetByteArrayElements(input, nullptr);
    if (!bytes) return nullptr;

    nfsnl::Bytes plain;
    bool decoded = false;
    try {
        size_t covered = 0;
        decoded = nfsnl::nctTransform(
            reinterpret_cast<const uint8_t*>(bytes),
            static_cast<size_t>(length), plain, &covered) &&
            covered >= static_cast<size_t>(length);
    } catch (...) {
        decoded = false;
    }
    env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
    if (!decoded) return nullptr;

    nfsnl::Rr3Liveries liveries;
    try {
        if (!nfsnl::rr3LiveriesRead(plain.data(), plain.size(), liveries) ||
            static_cast<size_t>(liveryIndex) >= liveries.liveries.size()) return nullptr;
    } catch (...) {
        return nullptr;
    }
    if (red < 0 || red > 255 || green < 0 || green > 255 ||
        blue < 0 || blue > 255 || flag < 0 || flag > 255) return nullptr;

    const std::string display = fromJava(env, displayText);
    const std::string swatch = fromJava(env, swatchText);
    const std::string logo = fromJava(env, logoText);
    const std::string arms = fromJava(env, armsText);
    const std::string driver = fromJava(env, driverText);
    if (display.size() > 160 || swatch.size() > 1024 || logo.size() > 1024 ||
        arms.size() > 1024 || driver.size() > 1024) return nullptr;

    const jsize meshCount = meshArray ? env->GetArrayLength(meshArray) : 0;
    const jsize materialCount = materialArray ? env->GetArrayLength(materialArray) : 0;
    if (meshCount != materialCount || meshCount > 10000) return nullptr;
    std::vector<jint> meshes(static_cast<size_t>(meshCount));
    std::vector<jint> materials(static_cast<size_t>(materialCount));
    if (meshCount > 0) {
        env->GetIntArrayRegion(meshArray, 0, meshCount, meshes.data());
        env->GetIntArrayRegion(materialArray, 0, materialCount, materials.data());
        if (env->ExceptionCheck()) return nullptr;
    }
    for (jsize i = 0; i < meshCount; ++i) {
        if (meshes[static_cast<size_t>(i)] < 0 || materials[static_cast<size_t>(i)] < 0)
            return nullptr;
    }

    auto& item = liveries.liveries[static_cast<size_t>(liveryIndex)];
    item.display = display;
    item.swatch = swatch;
    item.logo = logo;
    item.arms = arms;
    item.driver = driver;
    item.flag = static_cast<uint8_t>(flag);
    item.rgb[0] = static_cast<uint8_t>(red);
    item.rgb[1] = static_cast<uint8_t>(green);
    item.rgb[2] = static_cast<uint8_t>(blue);
    item.materials.clear();
    for (jsize i = 0; i < meshCount; ++i) {
        item.materials.push_back({
            static_cast<uint32_t>(meshes[static_cast<size_t>(i)]),
            static_cast<uint32_t>(materials[static_cast<size_t>(i)])
        });
    }

    nfsnl::Bytes rebuilt = nfsnl::rr3LiveriesWrite(liveries);
    nfsnl::Bytes encoded;
    try {
        size_t covered = 0;
        if (!nfsnl::nctTransform(rebuilt.data(), rebuilt.size(), encoded, &covered) ||
            covered < rebuilt.size()) return nullptr;
    } catch (...) {
        return nullptr;
    }
    if (encoded.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(encoded.size()));
    if (!result) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(encoded.size()),
                            reinterpret_cast<const jbyte*>(encoded.data()));
    if (env->ExceptionCheck()) return nullptr;
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeReplaceRr3Texture(
    JNIEnv* env, jobject /* thiz */, jstring assetPathText,
    jbyteArray originalArray, jbyteArray imageArray) {
    const std::string assetPath = fromJava(env, assetPathText);
    if (assetPath.empty() || !originalArray || !imageArray) return nullptr;
    const jsize originalLength = env->GetArrayLength(originalArray);
    const jsize imageLength = env->GetArrayLength(imageArray);
    if (originalLength <= 0 || imageLength <= 0) return nullptr;

    jbyte* originalBytes = env->GetByteArrayElements(originalArray, nullptr);
    if (!originalBytes) return nullptr;
    nfsnl::Bytes original(
        reinterpret_cast<const uint8_t*>(originalBytes),
        reinterpret_cast<const uint8_t*>(originalBytes) + originalLength);
    env->ReleaseByteArrayElements(originalArray, originalBytes, JNI_ABORT);

    jbyte* imageBytes = env->GetByteArrayElements(imageArray, nullptr);
    if (!imageBytes) return nullptr;
    nfsnl::Image picture;
    bool imageOk = false;
    try {
        imageOk = nfsnl::decodeImageFile(
            reinterpret_cast<const uint8_t*>(imageBytes),
            static_cast<size_t>(imageLength), picture);
    } catch (...) {
        imageOk = false;
    }
    env->ReleaseByteArrayElements(imageArray, imageBytes, JNI_ABORT);
    if (!imageOk) return nullptr;

    nfsnl::Bytes inner;
    std::string innerName;
    size_t frameCount = 0;
    const bool wrapped = nfsnl::rr3IsContainer(
        assetPath, original.data(), original.size());
    if (wrapped) {
        if (!nfsnl::rr3Unwrap(assetPath, original.data(), original.size(),
                              inner, &innerName, &frameCount)) return nullptr;
    } else {
        inner = original;
        innerName = assetPath;
    }

    nfsnl::Bytes imported;
    std::string report;
    bool importedOk = false;
    try {
        importedOk = nfsnl::importPicture(innerName, inner, picture, imported, report);
    } catch (...) {
        importedOk = false;
    }
    if (!importedOk || imported.empty()) return nullptr;

    nfsnl::Bytes output = wrapped ? nfsnl::rr3WrapZ(imported) : imported;
    if (output.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(output.size()));
    if (!result) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()),
                            reinterpret_cast<const jbyte*>(output.data()));
    if (env->ExceptionCheck()) return nullptr;
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sf12_rr3tool_NativeBridge_nativeUpdateRr3AssetList(
    JNIEnv* env, jobject /* thiz */, jbyteArray assetListArray,
    jstring assetPathText, jbyteArray updatedAssetArray) {
    const std::string assetPath = fromJava(env, assetPathText);
    if (assetPath.empty() || !assetListArray || !updatedAssetArray) return nullptr;
    const jsize listLength = env->GetArrayLength(assetListArray);
    const jsize assetLength = env->GetArrayLength(updatedAssetArray);
    if (listLength <= 0 || assetLength <= 0) return nullptr;

    jbyte* listBytes = env->GetByteArrayElements(assetListArray, nullptr);
    if (!listBytes) return nullptr;
    nfsnl::Bytes rawList(
        reinterpret_cast<const uint8_t*>(listBytes),
        reinterpret_cast<const uint8_t*>(listBytes) + listLength);
    env->ReleaseByteArrayElements(assetListArray, listBytes, JNI_ABORT);

    jbyte* assetBytes = env->GetByteArrayElements(updatedAssetArray, nullptr);
    if (!assetBytes) return nullptr;
    nfsnl::Bytes updatedAsset(
        reinterpret_cast<const uint8_t*>(assetBytes),
        reinterpret_cast<const uint8_t*>(assetBytes) + assetLength);
    env->ReleaseByteArrayElements(updatedAssetArray, assetBytes, JNI_ABORT);

    nfsnl::Rr3AssetList list;
    try {
        if (!nfsnl::rr3AssetListRead(rawList, list)) return nullptr;
    } catch (...) {
        return nullptr;
    }

    uint8_t digest[16];
    nfsnl::saves::md5(updatedAsset.data(), updatedAsset.size(), digest);
    static const char* hex = "0123456789abcdef";
    std::string md5;
    md5.reserve(32);
    for (uint8_t byte : digest) {
        md5.push_back(hex[(byte >> 4) & 0x0f]);
        md5.push_back(hex[byte & 0x0f]);
    }
    // This file is being replaced, so its cached MD5 must change too.
    // keepIfListed=true preserves the old digest and leaves the depot cache stale.
    nfsnl::rr3AssetListSet(list, assetPath, md5, false);

    nfsnl::Bytes output = nfsnl::rr3AssetListWrite(list);
    if (output.empty() || output.size() > static_cast<size_t>(std::numeric_limits<jsize>::max()))
        return nullptr;

    // Read the generated cache back and confirm the exact replacement digest
    // is present before returning an exportable asset-list file.
    nfsnl::Rr3AssetList verified;
    if (!nfsnl::rr3AssetListRead(output, verified)) return nullptr;
    std::string expectedPath = "/" + assetPath;
    for (char& c : expectedPath) if (c == static_cast<char>(92)) c = '/';
    bool digestVerified = false;
    for (const auto& entry : verified.entries) {
        if (entry.first == expectedPath) {
            digestVerified = entry.second == md5;
            break;
        }
    }
    if (!digestVerified) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(output.size()));
    if (!result) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()),
                            reinterpret_cast<const jbyte*>(output.data()));
    if (env->ExceptionCheck()) return nullptr;
    return result;
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

