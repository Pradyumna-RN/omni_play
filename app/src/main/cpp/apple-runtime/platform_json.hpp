#pragma once
#include <jni.h>
#include <utility>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <string_view>
#include <type_traits>

namespace music {
inline JavaVM* vm = nullptr;
inline jclass json_class = nullptr;
inline JNIEnv* env() {
    struct Attachment { bool own = false; ~Attachment() { if (own && vm) vm->DetachCurrentThread(); } };
    static thread_local Attachment attachment;
    JNIEnv* result = nullptr;
    if (!vm) throw std::runtime_error("JSON bridge unavailable");
    if (vm->GetEnv(reinterpret_cast<void**>(&result), JNI_VERSION_1_6) == JNI_EDETACHED) {
#ifdef __ANDROID__
        if (vm->AttachCurrentThread(&result, nullptr) != JNI_OK) throw std::runtime_error("Thread attachment failed");
#else
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&result), nullptr) != JNI_OK) throw std::runtime_error("Thread attachment failed");
#endif
        attachment.own = true;
    }
    return result;
}
inline void check() {
    if (env()->ExceptionCheck()) { env()->ExceptionClear(); throw std::runtime_error("Invalid JSON operation"); }
}
inline jmethodID method(const char* name, const char* signature) {
    auto id = env()->GetStaticMethodID(json_class, name, signature); check();
    if (!id) throw std::runtime_error("JSON method unavailable");
    return id;
}
inline jbyteArray bytes(std::string_view value) {
    if (value.size() > 2 * 1024 * 1024) throw std::runtime_error("JSON size limit");
    auto array = env()->NewByteArray(static_cast<jsize>(value.size())); check();
    env()->SetByteArrayRegion(array, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte*>(value.data())); check();
    return array;
}
inline std::string text(jbyteArray array) {
    if (!array) return {};
    auto size = env()->GetArrayLength(array);
    if (size < 0 || size > 2 * 1024 * 1024) throw std::runtime_error("JSON size limit");
    std::string result(static_cast<std::size_t>(size), '\0');
    env()->GetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte*>(result.data())); check();
    return result;
}
class Json {
    jobject value_ = nullptr;
    static Json adopt(jobject local) {
        Json out;
        if (local) { out.value_ = env()->NewGlobalRef(local); env()->DeleteLocalRef(local); check(); }
        return out;
    }
public:
    Json() = default;
    Json(std::nullptr_t) {}
    Json(const Json& other) { if (other.value_) value_ = env()->NewGlobalRef(other.value_); check(); }
    Json(Json&& other) noexcept : value_(other.value_) { other.value_ = nullptr; }
    Json& operator=(Json other) noexcept { auto old = value_; value_ = other.value_; other.value_ = old; return *this; }
    ~Json() { if (value_) env()->DeleteGlobalRef(value_); }
    Json(const std::string& value) {
        auto input = bytes(value);
        auto local = env()->CallStaticObjectMethod(json_class, method("string", "([B)Ljava/lang/Object;"), input);
        env()->DeleteLocalRef(input); check(); *this = adopt(local);
    }
    Json(const char* value) : Json(std::string(value ? value : "")) {}
    Json(bool value) { *this = adopt(env()->CallStaticObjectMethod(json_class, method("boolean", "(Z)Ljava/lang/Object;"), static_cast<jboolean>(value))); check(); }
    Json(double value) { *this = adopt(env()->CallStaticObjectMethod(json_class, method("number", "(D)Ljava/lang/Object;"), value)); check(); }
    template<class T, std::enable_if_t<std::is_integral_v<T> && !std::is_same_v<T, bool>, int> = 0>
    Json(T value) { *this = adopt(env()->CallStaticObjectMethod(json_class, method("integer", "(J)Ljava/lang/Object;"), static_cast<jlong>(value))); check(); }
    static Json object() { auto out = env()->CallStaticObjectMethod(json_class, method("objectValue", "()Ljava/lang/Object;")); check(); return adopt(out); }
    static Json array() { auto out = env()->CallStaticObjectMethod(json_class, method("arrayValue", "()Ljava/lang/Object;")); check(); return adopt(out); }
    static Json parse(std::string_view source) {
        auto input = bytes(source);
        auto out = env()->CallStaticObjectMethod(json_class, method("parse", "([B)Ljava/lang/Object;"), input);
        env()->DeleteLocalRef(input); check(); return adopt(out);
    }
    std::string dump() const {
        auto out = static_cast<jbyteArray>(env()->CallStaticObjectMethod(json_class, method("dump", "(Ljava/lang/Object;)[B"), value_));
        check(); auto result = text(out); env()->DeleteLocalRef(out); return result;
    }
    int kind() const { auto value = env()->CallStaticIntMethod(json_class, method("kind", "(Ljava/lang/Object;)I"), value_); check(); return value; }
    bool is_string() const { return kind() == 3; }
    bool is_number_integer() const { return kind() == 5; }
    bool is_number_unsigned() const { return is_number_integer() && get<long long>() >= 0; }
    bool empty() const { auto out = env()->CallStaticBooleanMethod(json_class, method("empty", "(Ljava/lang/Object;)Z"), value_); check(); return out; }
    bool contains(const std::string& key) const {
        auto input = bytes(key);
        auto out = env()->CallStaticBooleanMethod(json_class, method("contains", "(Ljava/lang/Object;[B)Z"), value_, input);
        env()->DeleteLocalRef(input); check(); return out;
    }
    Json at(const std::string& key) const {
        auto input = bytes(key);
        auto out = env()->CallStaticObjectMethod(json_class, method("get", "(Ljava/lang/Object;[B)Ljava/lang/Object;"), value_, input);
        env()->DeleteLocalRef(input); check(); return adopt(out);
    }
    void put(const std::string& key, const Json& value) {
        auto input = bytes(key);
        env()->CallStaticVoidMethod(json_class, method("put", "(Ljava/lang/Object;[BLjava/lang/Object;)V"), value_, input, value.value_);
        env()->DeleteLocalRef(input); check();
    }
    void push_back(const Json& value) {
        env()->CallStaticVoidMethod(json_class, method("append", "(Ljava/lang/Object;Ljava/lang/Object;)V"), value_, value.value_); check();
    }
    template<class T> T get() const {
        if constexpr (std::is_same_v<T, std::string>) {
            auto out = static_cast<jbyteArray>(env()->CallStaticObjectMethod(json_class, method("stringBytes", "(Ljava/lang/Object;)[B"), value_));
            check(); auto result = text(out); env()->DeleteLocalRef(out); return result;
        } else {
            auto out = env()->CallStaticLongMethod(json_class, method("longValue", "(Ljava/lang/Object;)J"), value_); check(); return static_cast<T>(out);
        }
    }
    class Member {
        Json& parent_; std::string key_;
    public:
        Member(Json& parent, std::string key) : parent_(parent), key_(std::move(key)) {}
        Member& operator=(const Json& value) { parent_.put(key_, value); return *this; }
        operator Json() const { return parent_.at(key_); }
        bool is_string() const { return parent_.at(key_).is_string(); }
        bool is_number_integer() const { return parent_.at(key_).is_number_integer(); }
        bool is_number_unsigned() const { return parent_.at(key_).is_number_unsigned(); }
        template<class T> T get() const { return parent_.at(key_).get<T>(); }
    };
    Member operator[](const std::string& key) { return Member(*this, key); }
    Json operator[](const std::string& key) const { return at(key); }
};
}
