#include <jni.h>
#include <dlfcn.h>
#include <algorithm>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include "platform_json.hpp"
#include "apple/auth.hpp"
#include "apple/decrypt.hpp"
#include "apple/loader.hpp"
#include "apple/playback.hpp"
#include "apple/runtime.hpp"

using namespace wrapper::apple;
namespace {
Loader loader;
std::mutex init_mutex;
bool attempted = false;
bool valid_id(const std::string& id) {
    return !id.empty() && id.size() <= 24 && std::all_of(id.begin(), id.end(), [](char c) { return c >= '0' && c <= '9'; });
}
std::string utf8(JNIEnv* env, jstring value) {
    if (!value) return {};
    jclass cls = env->FindClass("java/lang/String");
    auto method = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    auto encoding = env->NewStringUTF("UTF-8");
    auto data = static_cast<jbyteArray>(env->CallObjectMethod(value, method, encoding));
    music::check(); auto result = music::text(data);
    env->DeleteLocalRef(data); env->DeleteLocalRef(encoding); env->DeleteLocalRef(cls);
    return result;
}
jbyteArray response(const music::Json& result) { return music::bytes(result.dump()); }
music::Json state() {
    auto result = music::Json::object();
    const auto snapshot = Account::instance().public_snapshot();
    result["state"] = to_string(snapshot.state);
    result["runtimeReady"] = loader.ok() && Runtime::instance().initialized();
    result["playbackReady"] = loader.ok() && Runtime::instance().playback_ready();
    result["errorCode"] = snapshot.last_error_code;
    return result;
}
void fail(JNIEnv* env, const char* message) {
    auto type = env->FindClass("java/io/IOException");
    if (type) { env->ThrowNew(type, message); env->DeleteLocalRef(type); }
}
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    music::vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass local = env->FindClass("labs/rtensor/music/apple/NativeJson");
    if (!local) return JNI_ERR;
    music::json_class = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    return JNI_VERSION_1_6;
}

#define JNI_METHOD(name) Java_labs_rtensor_music_apple_NativeAppleBridge_##name
extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(initialize)(JNIEnv* env, jclass, jstring directory, jstring session, jstring device, jobjectArray order) {
    try {
        std::lock_guard<std::mutex> lock(init_mutex);
        if (attempted) return response(state());
        attempted = true;
        const auto root = utf8(env, directory);
        const auto data = utf8(env, session);
        const auto fingerprint = utf8(env, device);
        if (root.empty() || root[0] != '/' || data.empty() || data[0] != '/' || fingerprint.size() > 512)
            throw std::runtime_error("Invalid runtime paths");
        const auto count = env->GetArrayLength(order);
        if (count < 1 || count > 32) throw std::runtime_error("Invalid runtime inventory");
        for (jsize i = 0; i < count; ++i) {
            auto name_value = static_cast<jstring>(env->GetObjectArrayElement(order, i));
            const auto name = utf8(env, name_value); env->DeleteLocalRef(name_value);
            if (name.size() > 100 || name.rfind("lib", 0) != 0 || name.find('/') != std::string::npos || name.find("..") != std::string::npos)
                throw std::runtime_error("Invalid native name");
            if (!dlopen((root + "/" + name).c_str(), RTLD_NOW | RTLD_GLOBAL))
                throw std::runtime_error("Native dependency could not load");
        }
        if (!loader.open(root)) return response(state());
        RuntimeConfig config; config.base_dir = data; config.device_info = fingerprint;
        if (!Runtime::instance().initialize(loader, config)) return response(state());
        Account::instance().try_restore_cached_session(loader, Runtime::instance());
        return response(state());
    } catch (...) { fail(env, "The imported Apple runtime could not start on this device."); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(account)(JNIEnv* env, jclass, jint operation, jbyteArray user, jbyteArray secret) {
    try {
        auto& account = Account::instance();
        if (operation == 1) {
            auto name = music::text(user); auto password = music::text(secret);
            if (name.empty() || name.size() > 320 || password.empty() || password.size() > 1024 ||
                name.find('\0') != std::string::npos || password.find('\0') != std::string::npos || !loader.ok() || !Runtime::instance().initialized())
                throw std::runtime_error("Invalid sign-in state");
            if (!account.start_login(loader, Runtime::instance(), std::move(name), std::move(password)))
                throw std::runtime_error("Sign-in already running");
        } else if (operation == 2) {
            auto code = music::text(secret);
            if (code.size() != 6 || !valid_id(code) || !account.submit_2fa(std::move(code)))
                throw std::runtime_error("Invalid verification state");
        } else if (operation == 3) account.logout();
        else if (operation != 0) throw std::runtime_error("Invalid account operation");
        return response(state());
    } catch (...) { fail(env, "Apple sign-in could not continue. Check the account or try again."); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(credentials)(JNIEnv* env, jclass) {
    try {
        auto& account = Account::instance();
        auto result = music::Json::object();
        const auto snapshot = account.public_snapshot();
        if (snapshot.state != LoginState::Authenticated) throw std::runtime_error("Not signed in");
        Runtime::instance().refresh_playback_lease();
        result["developerToken"] = snapshot.dev_token;
        result["musicUserToken"] = snapshot.music_user_token;
        result["name"] = snapshot.apple_id;
        result["storefront"] = snapshot.storefront;
        return response(result);
    } catch (...) { fail(env, "Apple session is unavailable. Sign in again."); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(playback)(JNIEnv* env, jclass, jstring song_id) {
    try {
        auto id = utf8(env, song_id);
        if (!valid_id(id) || Account::instance().state() != LoginState::Authenticated) throw std::runtime_error("Invalid playback state");
        auto& runtime = Runtime::instance();
        std::lock_guard<std::mutex> lock(runtime.playback_mutex());
        auto result = fetch_playback_json(loader, runtime, std::move(id));
        if (!result.ok) throw std::runtime_error("Playback unavailable");
        return response(result.body);
    } catch (...) { fail(env, "Apple could not provide this recording for the signed-in account."); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(decrypt)(JNIEnv* env, jclass, jstring song_id, jstring key_uri, jbyteArray input) {
    try {
        auto id = utf8(env, song_id); auto uri = utf8(env, key_uri);
        auto count = env->GetArrayLength(input);
        if (!valid_id(id) || uri.rfind("skd://itunes.apple.com/", 0) != 0 || uri.size() > 2048 ||
            std::any_of(uri.begin(), uri.end(), [](unsigned char c) { return c <= 32 || c == 127; }) || count < 16 || count > 131072 || count % 16 != 0 ||
            Account::instance().state() != LoginState::Authenticated) throw std::runtime_error("Invalid protected sample");
        std::vector<std::uint8_t> data(static_cast<std::size_t>(count));
        env->GetByteArrayRegion(input, 0, count, reinterpret_cast<jbyte*>(data.data())); music::check();
        auto& runtime = Runtime::instance();
        std::lock_guard<std::mutex> lock(runtime.playback_mutex());
        auto result = decrypt_samples(loader, runtime, std::move(id), std::move(uri), {std::move(data)});
        if (!result.ok || result.plaintexts.size() != 1 || result.plaintexts[0].size() != static_cast<std::size_t>(count))
            throw std::runtime_error("Sample unavailable");
        return music::bytes(std::string_view(reinterpret_cast<const char*>(result.plaintexts[0].data()), result.plaintexts[0].size()));
    } catch (...) { fail(env, "Apple could not open this protected audio sample."); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(jsonRoundTrip)(JNIEnv* env, jclass, jbyteArray input, jboolean worker) {
    try {
        auto data = music::text(input); std::string output; bool failed = false;
        auto work = [&]() { try { output = music::Json::parse(data).dump(); } catch (...) { failed = true; } };
        if (worker) { std::thread thread(work); thread.join(); } else work();
        if (failed) throw std::runtime_error("Invalid JSON");
        return music::bytes(output);
    } catch (...) { fail(env, "Invalid native JSON data."); return nullptr; }
}
