
#include <valhalla/worker.h>
#include "main.h"
#include "valhalla_actor.h"

#ifdef __ANDROID__
// The Android JNI interface uses a different function signature.
#include <jni.h>

namespace {

// Runs `action` and returns its result; a Valhalla or other error becomes the error JSON that
// Valhalla itself returns, so callers parse every outcome the same way.
template <typename Action>
std::string guarded(const char *name, Action &&action) {
    try {
        return action();
    } catch (const valhalla::valhalla_exception_t &err) {
        printf("[ValhallaActor] %s valhalla_exception: %s\n", name, err.what());
        std::string code = std::to_string(err.code);
        std::string message = err.message.c_str();
        return "{\"code\":" + code + ",\"message\":\"" + message + "\"}";
    } catch (const std::exception &err) {
        printf("[ValhallaActor] %s std::exception: %s\n", name, err.what());
        return "{\"code\":-1,\"message\":\"" + std::string(err.what()) + "\"}";
    } catch (...) {
        printf("[ValhallaActor] %s unknown exception", name);
        return "{\"code\":-1,\"message\":\"unknown exception\"}";
    }
}

// Runs an actor request against the actor behind `handle`.
template <typename Request>
jstring actorRequest(JNIEnv *env, jlong handle, jstring jRequest, const char *name, Request &&request) {
    const char *chars = env->GetStringUTFChars(jRequest, 0);
    std::string result = guarded(name, [&] {
        return request(*reinterpret_cast<ValhallaActor *>(handle), chars);
    });
    env->ReleaseStringUTFChars(jRequest, chars);
    return env->NewStringUTF(result.c_str());
}

} // namespace

// Creates the actor for a config and returns its handle. A GraphReader, and with it the package
// set built from the config, lives as long as the actor, so requests through one handle reuse it.
// The caller frees the handle with destroyActor. A config that cannot be loaded throws a
// RuntimeException.
extern "C"
JNIEXPORT jlong

JNICALL
Java_com_valhalla_valhalla_ValhallaKotlin_createActor(JNIEnv *env,
                                                      jobject thiz,
                                                      jstring jConfigPath) {
    const char *config_path = env->GetStringUTFChars(jConfigPath, 0);
    jlong handle = 0;
    try {
        handle = reinterpret_cast<jlong>(new ValhallaActor(config_path));
    } catch (const std::exception &err) {
        printf("[ValhallaActor] createActor std::exception: %s\n", err.what());
        jclass exception = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exception, err.what());
    } catch (...) {
        printf("[ValhallaActor] createActor unknown exception");
        jclass exception = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exception, "unknown exception");
    }
    env->ReleaseStringUTFChars(jConfigPath, config_path);
    return handle;
}

extern "C"
JNIEXPORT void

JNICALL
Java_com_valhalla_valhalla_ValhallaKotlin_destroyActor(JNIEnv *env,
                                                       jobject thiz,
                                                       jlong handle) {
    delete reinterpret_cast<ValhallaActor *>(handle);
}

extern "C"
JNIEXPORT jstring

JNICALL
Java_com_valhalla_valhalla_ValhallaKotlin_route(JNIEnv *env,
                                                jobject thiz,
                                                jstring jRequest,
                                                jlong handle) {
    return actorRequest(env, handle, jRequest, "route", [](ValhallaActor &actor, const char *request) {
        return actor.route(request);
    });
}

extern "C"
JNIEXPORT jstring

JNICALL
Java_com_valhalla_valhalla_ValhallaKotlin_traceRoute(JNIEnv *env,
                                                     jobject thiz,
                                                     jstring jRequest,
                                                     jlong handle) {
    return actorRequest(env, handle, jRequest, "traceRoute", [](ValhallaActor &actor, const char *request) {
        return actor.traceRoute(request);
    });
}

extern "C"
JNIEXPORT jstring

JNICALL
Java_com_valhalla_valhalla_ValhallaKotlin_traceAttributes(JNIEnv *env,
                                                          jobject thiz,
                                                          jstring jRequest,
                                                          jlong handle) {
    return actorRequest(env, handle, jRequest, "traceAttributes", [](ValhallaActor &actor, const char *request) {
        return actor.traceAttributes(request);
    });
}

extern "C"
JNIEXPORT jstring

JNICALL
Java_com_valhalla_valhalla_ValhallaKotlin_joinPackages(JNIEnv *env,
                                                       jobject thiz,
                                                       jstring jConfigPath,
                                                       jstring jOutputDir) {
    const char *config_path = env->GetStringUTFChars(jConfigPath, 0);
    const char *output_dir = env->GetStringUTFChars(jOutputDir, 0);

    std::string result = guarded("joinPackages", [&] { return joinPackages(config_path, output_dir); });

    env->ReleaseStringUTFChars(jConfigPath, config_path);
    env->ReleaseStringUTFChars(jOutputDir, output_dir);

    return env->NewStringUTF(result.c_str());
}

#elif __APPLE__
void* create_valhalla_actor(const char *config_path, ValhallaMobileHttpClient* http_client) {
    return new ValhallaActor(config_path, http_client);
}

void delete_valhalla_actor(void* actor) {
    delete ((ValhallaActor*) actor);
}

std::string route(const char *request, void* actor) {
    std::string result;
    try {
        result = ((ValhallaActor*) actor)->route(request);
    } catch (const valhalla::valhalla_exception_t &err) {
        printf("[ValhallaActor] route valhalla_exception: %s\n", err.what());
        std::string code = std::to_string(err.code);
        std::string message = err.message.c_str();

        result = "{\"code\":" + code + ",\"message\":\"" + message + "\"}";
    } catch (const std::exception &err) {
        printf("[ValhallaActor] route std::exception: %s\n", err.what());
        result = "{\"code\":-1,\"message\":\"" + std::string(err.what()) + "\"}";
    } catch (...) {
        printf("[ValhallaActor] route unknown exception");
        result = "{\"code\":-1,\"message\":\"unknown exception\"}";
    }

    return result;
}
#endif
