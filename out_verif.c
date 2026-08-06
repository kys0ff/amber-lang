#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <math.h>
#include <stdint.h>
#include <gc.h>

typedef struct {
    const char* name;
    int32_t id;
} __amber_type_t;

typedef struct {
    __amber_type_t* type;
} __amber_header_t;

typedef struct {
    __amber_header_t header;
    double value;
} __amber_box_double_t;

typedef struct {
    __amber_header_t header;
    void** data;
    int32_t length;
    int32_t capacity;
} __amber_list_t;

struct AMBER_RESULT {
    void* value;
    int32_t has_error;
    const char* error_message;
};

static struct AMBER_RESULT __amber_rt_last_error = { NULL, 0, NULL };

extern __amber_type_t __amber_type_double;
extern __amber_type_t __amber_type_string;
extern __amber_type_t __amber_type_bool;

static inline void* __amber_rt_alloc(size_t size) {
    void* ptr = GC_MALLOC(size);
    if (!ptr) { fprintf(stderr, "Out of memory\n"); exit(1); }
    return ptr;
}

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
    __amber_box_double_t* p = (__amber_box_double_t*)__amber_rt_alloc(sizeof(__amber_box_double_t));
    p->header.type = &__amber_type_double;
    p->value = d;
    return p;
}

static inline double __amber_rt_unbox_double(void* p) {
    if (!p) return 0.0;
    // Basic safety check could be added here
    return ((__amber_box_double_t*)p)->value;
}

static inline int __amber_rt_is_error(struct AMBER_RESULT res) {
    return res.has_error;
}

static inline void* __amber_rt_unwrap(struct AMBER_RESULT res) {
    return res.value;
}

static inline int __amber_rt_is_type(void* obj, __amber_type_t* type) {
    if (!obj) return 0;
    __amber_header_t* header = (__amber_header_t*)obj;
    return header->type == type;
}

static inline void* __amber_rt_as_type(void* obj, __amber_type_t* type) {
    if (__amber_rt_is_type(obj, type)) return obj;
    // We'll call the intrinsic panic if available, or a simple version here
    fprintf(stderr, "ClassCastException: object is not of type %s\n", type->name);
    exit(1);
    return NULL;
}

void __amber_rt_print(const void* val) {
    if (val) printf("%s", (const char*)val);
}

void __amber_rt_println(const void* val) {
    if (val) printf("%s\n", (const char*)val);
    else printf("\n");
}

char* __amber_rt_readln() {
    char* buf = (char*)__amber_rt_alloc(1024);
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
    char* buf = (char*)__amber_rt_alloc(64);
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
        void** new_data = (void**)__amber_rt_alloc(sizeof(void*) * l->capacity);
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
    return (char*)val;
}

char* __amber_rt_str_concat(const char* s1, const char* s2) {
    if (!s1) return (char*)s2;
    if (!s2) return (char*)s1;
    size_t len1 = strlen(s1);
    size_t len2 = strlen(s2);
    char* res = (char*)__amber_rt_alloc(len1 + len2 + 1);
    memcpy(res, s1, len1);
    memcpy(res + len1, s2, len2);
    res[len1 + len2] = '\0';
    return res;
}

#include <stdarg.h>
__amber_list_t* __amber_rt_create_list(int count, ...) {
    __amber_list_t* l = (__amber_list_t*)__amber_rt_alloc(sizeof(__amber_list_t));
    l->header.type = NULL; // TODO: Set List type descriptor
    l->length = count;
    l->capacity = count < 4 ? 4 : count;
    l->data = (void**)__amber_rt_alloc(sizeof(void*) * l->capacity);
    va_list args;
    va_start(args, count);
    for (int i = 0; i < count; i++) {
        l->data[i] = va_arg(args, void*);
    }
    va_end(args);
    return l;
}

__amber_type_t __amber_type_double = { "Number", 1 };
__amber_type_t __amber_type_string = { "String", 2 };
__amber_type_t __amber_type_bool = { "Boolean", 3 };

struct AMBER_RESULT __am_unsafe() {
return __amber_rt_result_success(__amber_rt_box_double(1.0)    );
}
double __am_value_is;
int main(int argc, char** argv) {
    GC_INIT();
__am_value_is = ({ struct AMBER_RESULT __res = __am_unsafe(); double __final_res; if (__amber_rt_is_error(__res)) { __final_res = 5.0; } else { __final_res = __amber_rt_unbox_double(__amber_rt_unwrap(__res)); } __final_res; })    ;
__amber_rt_println("Hi")    ;
__amber_rt_println(__amber_rt_str_concat("Hi ", __amber_rt_from_number(__am_value_is)))    ;
    return 0;
}
