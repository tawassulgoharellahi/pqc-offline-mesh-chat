#pragma once
#include <string>

namespace std {
    template <typename T>
    inline std::string format(const char* /*fmt*/, const T& val) {
        return std::to_string(val) + "%";
    }
}
