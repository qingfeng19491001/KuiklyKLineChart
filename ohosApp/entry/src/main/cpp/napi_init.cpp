#include "napi/native_api.h"
#include "libshared_api.h"
#include <cstdint>
#include <string>
#include <vector>
#include <unordered_map>

using BridgeRef = libshared_kref_com_kuikly_kuiklyklinechart_shared_OhosKLineChartBridge;

static std::unordered_map<int32_t, BridgeRef> bridges;
static int32_t nextBridgeHandle = 1;

static auto SharedApi() {
    return libshared_symbols();
}

static napi_value Undefined(napi_env env) {
    napi_value result;
    napi_get_undefined(env, &result);
    return result;
}

static std::string StringArg(napi_env env, napi_value value) {
    size_t length = 0;
    napi_get_value_string_utf8(env, value, nullptr, 0, &length);
    std::vector<char> buffer(length + 1, '\0');
    if (length > 0) {
        napi_get_value_string_utf8(env, value, buffer.data(), buffer.size(), &length);
    }
    return std::string(buffer.data(), length);
}

static napi_value JsString(napi_env env, const char *value) {
    napi_value result;
    napi_create_string_utf8(env, value == nullptr ? "" : value, NAPI_AUTO_LENGTH, &result);
    return result;
}

static bool Args(napi_env env, napi_callback_info info, size_t expected, napi_value *argv) {
    size_t argc = expected;
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    if (argc < expected) {
        napi_throw_type_error(env, nullptr, "Not enough arguments");
        return false;
    }
    return true;
}

static BridgeRef *BridgeArg(napi_env env, napi_value value) {
    int32_t handle = 0;
    if (napi_get_value_int32(env, value, &handle) != napi_ok) {
        napi_throw_type_error(env, nullptr, "Invalid KLine bridge handle");
        return nullptr;
    }
    auto found = bridges.find(handle);
    if (found == bridges.end()) {
        napi_throw_error(env, nullptr, "KLine bridge is already disposed");
        return nullptr;
    }
    return &found->second;
}

static napi_value InitKuikly(napi_env env, napi_callback_info) {
    napi_value result;
    napi_create_int32(env, SharedApi()->kotlin.root.initKuikly(), &result);
    return result;
}

static napi_value CreateKLineBridge(napi_env env, napi_callback_info) {
    const int32_t handle = nextBridgeHandle++;
    bridges.emplace(handle, SharedApi()->kotlin.root.com.kuikly.kuiklyklinechart.shared
        .OhosKLineChartBridge.OhosKLineChartBridge());
    napi_value result;
    napi_create_int32(env, handle, &result);
    return result;
}

static napi_value SetKLineProp(napi_env env, napi_callback_info info) {
    napi_value argv[3];
    if (!Args(env, info, 3, argv)) return Undefined(env);
    auto bridge = BridgeArg(env, argv[0]);
    if (bridge == nullptr) return Undefined(env);
    const auto key = StringArg(env, argv[1]);
    const auto value = StringArg(env, argv[2]);
    const bool handled = SharedApi()->kotlin.root.com.kuikly.kuiklyklinechart.shared
        .OhosKLineChartBridge.setProp(*bridge, key.c_str(), value.c_str());
    napi_value result;
    napi_get_boolean(env, handled, &result);
    return result;
}

static napi_value CallKLine(napi_env env, napi_callback_info info) {
    napi_value argv[3];
    if (!Args(env, info, 3, argv)) return Undefined(env);
    auto bridge = BridgeArg(env, argv[0]);
    if (bridge == nullptr) return Undefined(env);
    const auto method = StringArg(env, argv[1]);
    const auto params = StringArg(env, argv[2]);
    return JsString(env, SharedApi()->kotlin.root.com.kuikly.kuiklyklinechart.shared
        .OhosKLineChartBridge.call(*bridge, method.c_str(), params.c_str()));
}

static napi_value ResizeKLine(napi_env env, napi_callback_info info) {
    napi_value argv[3];
    if (!Args(env, info, 3, argv)) return Undefined(env);
    auto bridge = BridgeArg(env, argv[0]);
    if (bridge == nullptr) return Undefined(env);
    int32_t width = 0;
    int32_t height = 0;
    napi_get_value_int32(env, argv[1], &width);
    napi_get_value_int32(env, argv[2], &height);
    SharedApi()->kotlin.root.com.kuikly.kuiklyklinechart.shared
        .OhosKLineChartBridge.resize(*bridge, width, height);
    return Undefined(env);
}

static napi_value PointerKLine(napi_env env, napi_callback_info info) {
    napi_value argv[6];
    if (!Args(env, info, 6, argv)) return Undefined(env);
    auto bridge = BridgeArg(env, argv[0]);
    if (bridge == nullptr) return Undefined(env);
    const auto kind = StringArg(env, argv[1]);
    double x = 0;
    double y = 0;
    double scale = 1;
    int32_t count = 1;
    napi_get_value_double(env, argv[2], &x);
    napi_get_value_double(env, argv[3], &y);
    napi_get_value_double(env, argv[4], &scale);
    napi_get_value_int32(env, argv[5], &count);
    SharedApi()->kotlin.root.com.kuikly.kuiklyklinechart.shared
        .OhosKLineChartBridge.pointer(*bridge, kind.c_str(), x, y, scale, count);
    return Undefined(env);
}

static napi_value RenderKLineCommands(napi_env env, napi_callback_info info) {
    napi_value argv[1];
    if (!Args(env, info, 1, argv)) return Undefined(env);
    auto bridge = BridgeArg(env, argv[0]);
    if (bridge == nullptr) return Undefined(env);
    return JsString(env, SharedApi()->kotlin.root.com.kuikly.kuiklyklinechart.shared
        .OhosKLineChartBridge.renderCommands(*bridge));
}

static napi_value PollKLineEvents(napi_env env, napi_callback_info info) {
    napi_value argv[1];
    if (!Args(env, info, 1, argv)) return Undefined(env);
    auto bridge = BridgeArg(env, argv[0]);
    if (bridge == nullptr) return Undefined(env);
    return JsString(env, SharedApi()->kotlin.root.com.kuikly.kuiklyklinechart.shared
        .OhosKLineChartBridge.pollEvents(*bridge));
}

static napi_value DisposeKLineBridge(napi_env env, napi_callback_info info) {
    napi_value argv[1];
    if (!Args(env, info, 1, argv)) return Undefined(env);
    int32_t handle = 0;
    napi_get_value_int32(env, argv[0], &handle);
    const auto found = bridges.find(handle);
    if (found != bridges.end()) {
        SharedApi()->kotlin.root.com.kuikly.kuiklyklinechart.shared
            .OhosKLineChartBridge.dispose(found->second);
        bridges.erase(found);
    }
    return Undefined(env);
}

EXTERN_C_START
static napi_value Init(napi_env env, napi_value exports) {
    napi_property_descriptor desc[] = {
        {"initKuikly", nullptr, InitKuikly, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"createKLineBridge", nullptr, CreateKLineBridge, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setKLineProp", nullptr, SetKLineProp, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"callKLine", nullptr, CallKLine, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"resizeKLine", nullptr, ResizeKLine, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"pointerKLine", nullptr, PointerKLine, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"renderKLineCommands", nullptr, RenderKLineCommands, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"pollKLineEvents", nullptr, PollKLineEvents, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"disposeKLineBridge", nullptr, DisposeKLineBridge, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);
    return exports;
}
EXTERN_C_END

static napi_module demoModule = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = nullptr,
    .nm_register_func = Init,
    .nm_modname = "entry",
    .nm_priv = nullptr,
    .reserved = {0},
};

extern "C" __attribute__((constructor)) void RegisterEntryModule(void) {
    napi_module_register(&demoModule);
}
