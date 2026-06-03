#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {
struct Slot {
    uint64_t hash;
    uint64_t offset;
};

std::string jstring_to_std_string(JNIEnv *env, jstring input) {
    const char *chars = env->GetStringUTFChars(input, nullptr);
    std::string output(chars);
    env->ReleaseStringUTFChars(input, chars);
    return output;
}

jlongArray zero_counts(JNIEnv *env) {
    jlongArray result = env->NewLongArray(3);
    jlong zeros[3] = {0, 0, 0};
    env->SetLongArrayRegion(result, 0, 3, zeros);
    return result;
}
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_de_manhhao_hoshi_RougoHoshi_probeEntryTypes(JNIEnv *env, jobject, jstring dict_path) {
    auto dir = jstring_to_std_string(env, dict_path);
    std::string hash_path = dir + "/hash.table";
    std::string blobs_path = dir + "/blobs.bin";

    FILE *hash_file = fopen(hash_path.c_str(), "rb");
    if (!hash_file) return zero_counts(env);

    uint32_t capacity = 0;
    if (fread(&capacity, sizeof(capacity), 1, hash_file) != 1) {
        fclose(hash_file);
        return zero_counts(env);
    }

    std::vector<Slot> slots(capacity);
    if (fread(slots.data(), sizeof(Slot), capacity, hash_file) != capacity) {
        fclose(hash_file);
        return zero_counts(env);
    }
    fclose(hash_file);

    FILE *blobs_file = fopen(blobs_path.c_str(), "rb");
    if (!blobs_file) return zero_counts(env);

    fseek(blobs_file, 0, SEEK_END);
    long blob_end = ftell(blobs_file);
    if (blob_end < 0) {
        fclose(blobs_file);
        return zero_counts(env);
    }

    size_t blobs_size = static_cast<size_t>(blob_end);
    fseek(blobs_file, 0, SEEK_SET);
    std::vector<uint8_t> blobs(blobs_size);
    if (fread(blobs.data(), 1, blobs_size, blobs_file) != blobs_size) {
        fclose(blobs_file);
        return zero_counts(env);
    }
    fclose(blobs_file);

    uint64_t term_count = 0;
    uint64_t freq_count = 0;
    uint64_t pitch_count = 0;

    for (uint32_t i = 0; i < capacity; i++) {
        if (slots[i].hash == 0) continue;

        uint64_t blob_offset = slots[i].offset;
        if (blob_offset + sizeof(uint32_t) > blobs_size) continue;

        uint32_t entry_count = 0;
        std::memcpy(&entry_count, blobs.data() + blob_offset, sizeof(entry_count));
        size_t idx = blob_offset + sizeof(entry_count);

        for (uint32_t j = 0; j < entry_count; j++) {
            if (idx + sizeof(uint64_t) > blobs_size) break;

            uint64_t entry_offset = 0;
            std::memcpy(&entry_offset, blobs.data() + idx, sizeof(entry_offset));
            idx += sizeof(entry_offset);

            if (entry_offset + 1 > blobs_size) continue;
            uint8_t type = blobs[entry_offset];

            if (type == 0) {
                term_count++;
            } else if (type == 1) {
                size_t pos = entry_offset + 1;
                if (pos + sizeof(uint16_t) > blobs_size) continue;

                uint16_t expr_len = 0;
                std::memcpy(&expr_len, blobs.data() + pos, sizeof(expr_len));
                pos += sizeof(expr_len) + expr_len;

                if (pos + sizeof(uint8_t) > blobs_size) continue;
                uint8_t mode_len = blobs[pos];
                pos += sizeof(mode_len);

                if (pos + static_cast<size_t>(mode_len) > blobs_size) continue;
                std::string mode(reinterpret_cast<const char *>(blobs.data() + pos), mode_len);

                if (mode == "freq") {
                    freq_count++;
                } else if (mode == "pitch" || mode == "ipa") {
                    pitch_count++;
                }
            }
        }
    }

    jlongArray result = env->NewLongArray(3);
    jlong counts[3] = {
        static_cast<jlong>(term_count),
        static_cast<jlong>(freq_count),
        static_cast<jlong>(pitch_count),
    };
    env->SetLongArrayRegion(result, 0, 3, counts);
    return result;
}
