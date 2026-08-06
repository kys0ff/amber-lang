#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <math.h>
#include <gc.h>

typedef struct {
    void** data;
    int length;
    int capacity;
} __amber_list_t;

struct AMBER_RESULT {
    void* value;
    int has_error;
    const char* error_message;
};

static struct AMBER_RESULT __amber_rt_last_error = { NULL, 0, NULL };

static inline struct AMBER_RESULT __amber_rt_result_success(void* val) {
    struct AMBER_RESULT res = { val, 0, NULL };
    return res;
}

static inline struct AMBER_RESULT __amber_rt_result_error(const char* msg) {
    struct AMBER_RESULT res = { NULL, 1, msg };
    __amber_rt_last_error = res;
    return res;
}

static inline void* __amber_rt_box_double(double d) {
    double* p = (double*)GC_MALLOC(sizeof(double));
    if (p) *p = d;
    return p;
}

static inline double __amber_rt_unbox_double(void* p) {
    return p ? *(double*)p : 0.0;
}

static inline int __amber_rt_is_error(struct AMBER_RESULT res) {
    return res.has_error;
}

static inline void* __amber_rt_unwrap(struct AMBER_RESULT res) {
    return res.value;
}

void __amber_rt_print(void* val) {
    if (val) printf("%s", (char*)val);
}

void __amber_rt_println(void* val) {
    if (val) printf("%s\n", (char*)val);
    else printf("\n");
}

char* __amber_rt_readln() {
    char* buf = (char*)GC_MALLOC(1024);
    if (fgets(buf, 1024, stdin)) {
        size_t len = strlen(buf);
        if (len > 0 && buf[len-1] == '\n') buf[len-1] = '\0';
        return buf;
    }
    return "";
}

double __amber_rt_str_len(const char* s) {
    return (double)(s ? strlen(s) : 0);
}

char* __amber_rt_from_number(double n) {
    char* buf = (char*)GC_MALLOC(64);
    snprintf(buf, 64, "%g", n);
    return buf;
}

int __amber_rt_str_contains(const char* s, const char* sub) { return (s && sub && strstr(s, sub)) ? 1 : 0; }

int __amber_rt_str_starts_with(const char* s, const char* sub) { return (s && sub && strncmp(s, sub, strlen(sub)) == 0) ? 1 : 0; }

int __amber_rt_str_ends_with(const char* s, const char* sub) {
    if (!s || !sub) return 0;
    size_t len = strlen(s);
    size_t sublen = strlen(sub);
    if (sublen > len) return 0;
    return strcmp(s + len - sublen, sub) == 0 ? 1 : 0;
}

double __amber_rt_math_abs(double n) { return fabs(n); }

double __amber_rt_math_sqrt(double n) { return sqrt(n); }

double __amber_rt_math_pow(double b, double e) { return pow(b, e); }

double __amber_rt_math_sin(double n) { return sin(n); }

double __amber_rt_math_cos(double n) { return cos(n); }

double __amber_rt_math_floor(double n) { return floor(n); }

double __amber_rt_math_ceil(double n) { return ceil(n); }

double __amber_rt_math_max(double a, double b) { return a > b ? a : b; }

double __amber_rt_math_min(double a, double b) { return a < b ? a : b; }

void __amber_rt_exit(double code) { exit((int)code); }

void __amber_rt_panic(const char* msg) {
    fprintf(stderr, "panic: %s\n", msg);
    exit(1);
}

double __amber_rt_list_len(__amber_list_t* l) { return l ? (double)l->length : 0.0; }

void __amber_rt_list_push(__amber_list_t* l, void* val) {
    if (!l) return;
    if (l->length >= l->capacity) {
        l->capacity = l->capacity == 0 ? 4 : l->capacity * 2;
        void** new_data = (void**)GC_MALLOC(sizeof(void*) * l->capacity);
        if (l->data) memcpy(new_data, l->data, sizeof(void*) * l->length);
        l->data = new_data;
    }
    l->data[l->length++] = val;
}

void* __amber_rt_list_pop(__amber_list_t* l) {
    if (!l || l->length == 0) return NULL;
    return l->data[--l->length];
}

void* __amber_rt_list_get(__amber_list_t* l, double idx) {
    int i = (int)idx;
    if (!l || i < 0 || i >= l->length) return NULL;
    return l->data[i];
}

char* __amber_rt_to_string(void* val) {
    // Currently assumes string, but can be improved with RTTI
    return (char*)val;
}

char* __amber_rt_str_concat(const char* s1, const char* s2) {
    if (!s1) return (char*)s2;
    if (!s2) return (char*)s1;
    size_t len1 = strlen(s1);
    size_t len2 = strlen(s2);
    char* res = (char*)GC_MALLOC(len1 + len2 + 1);
    memcpy(res, s1, len1);
    memcpy(res + len1, s2, len2);
    res[len1 + len2] = '\0';
    return res;
}

#include <stdarg.h>
__amber_list_t* __amber_rt_create_list(int count, ...) {
    __amber_list_t* l = (__amber_list_t*)GC_MALLOC(sizeof(__amber_list_t));
    l->length = count;
    l->capacity = count < 4 ? 4 : count;
    l->data = (void**)GC_MALLOC(sizeof(void*) * l->capacity);
    va_list args;
    va_start(args, count);
    for (int i = 0; i < count; i++) {
        l->data[i] = va_arg(args, void*);
    }
    va_end(args);
    return l;
}

struct AMBER_RESULT __am_safe_div(double __am_a, double __am_b) {
if ((__am_b == 0.0)    ) {
        {
return __amber_rt_result_error("division by zero")            ;
        }
    }
return __amber_rt_result_success(__amber_rt_box_double((__am_a / __am_b))    );
}
struct AMBER_RESULT __am_nested_unsafe(double __am_a, double __am_b) {
double __am_res = ({ struct AMBER_RESULT __res = __am_safe_div(__am_a, __am_b); double __final_res; if (__amber_rt_is_error(__res)) { return __amber_rt_result_error("failed in nested"); } else { __final_res = __amber_rt_unbox_double(__amber_rt_unwrap(__res)); } __final_res; })    ;
return __amber_rt_result_success(__amber_rt_box_double((__am_res + 1.0))    );
}
void __am_test4() {
double __am_r4 = ({ struct AMBER_RESULT __res = __am_safe_div(5.0, 0.0); double __final_res; if (__amber_rt_is_error(__res)) { const char* __am_e = __res.error_message; double __am_fallback = 42.0; __amber_rt_println(__amber_rt_str_concat("Fallback to ", __amber_rt_from_number(__am_fallback))); __final_res = __am_fallback; } else { __final_res = __amber_rt_unbox_double(__amber_rt_unwrap(__res)); } __final_res; })    ;
__amber_rt_println(__amber_rt_str_concat("r4 = ", __amber_rt_from_number(__am_r4)))    ;
}
double __am_r1;
double __am_r2;
double __am_r3;
int main(int argc, char** argv) {
    GC_INIT();
__am_r1 = ({ struct AMBER_RESULT __res = __am_safe_div(10.0, 2.0); double __final_res; if (__amber_rt_is_error(__res)) { __final_res = -1.0; } else { __final_res = __amber_rt_unbox_double(__amber_rt_unwrap(__res)); } __final_res; })    ;
__amber_rt_println(__amber_rt_str_concat("10 / 2 = ", __amber_rt_from_number(__am_r1)))    ;
__am_r2 = ({ struct AMBER_RESULT __res = __am_safe_div(10.0, 0.0); double __final_res; if (__amber_rt_is_error(__res)) { const char* __am_e = __res.error_message; __final_res = -2.0; } else { __final_res = __amber_rt_unbox_double(__amber_rt_unwrap(__res)); } __final_res; })    ;
__amber_rt_println(__amber_rt_str_concat("10 / 0 = ", __amber_rt_from_number(__am_r2)))    ;
__am_r3 = ({ struct AMBER_RESULT __res = __am_nested_unsafe(10.0, 0.0); double __final_res; if (__amber_rt_is_error(__res)) { const char* __am_e = __res.error_message; __final_res = -3.0; } else { __final_res = __amber_rt_unbox_double(__amber_rt_unwrap(__res)); } __final_res; })    ;
__amber_rt_println(__amber_rt_str_concat("nested(10, 0) = ", __amber_rt_from_number(__am_r3)))    ;
__am_test4()    ;
    return 0;
}
