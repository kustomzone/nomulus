package(
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0


java_library(
    name = "request",
    srcs = glob(
        ["*.java"],
        exclude = ["Modules.java"],
    ),
    visibility = ["//visibility:public"],
    deps = [
        "//java/com/google/common/annotations",
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//java/com/google/common/html",
        "//java/com/google/common/io",
        "//java/com/google/common/net",
        "//third_party/java/appengine:appengine-api",
        "//third_party/java/auto:auto_value",
        "//third_party/java/dagger",
        "//third_party/java/joda_time",
        "//third_party/java/json_simple",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/jsr330_inject",
        "//third_party/java/servlet/servlet_api",
        "//java/google/registry/security",
        "//java/google/registry/util",
    ],
)

java_library(
    name = "modules",
    srcs = ["Modules.java"],
    visibility = ["//visibility:public"],
    deps = [
        "//java/com/google/api/client/extensions/appengine/http",
        "//java/com/google/api/client/googleapis/auth/oauth2",
        "//java/com/google/api/client/googleapis/extensions/appengine/auth/oauth2",
        "//java/com/google/api/client/http",
        "//java/com/google/api/client/json",
        "//java/com/google/api/client/json/jackson2",
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//third_party/java/appengine:appengine-api",
        "//third_party/java/dagger",
        "//java/google/registry/config",
        "//java/google/registry/keyring/api",
    ],
)
