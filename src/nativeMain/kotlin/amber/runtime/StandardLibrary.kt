package amber.runtime

import amber.compiler.symbol.Symbol
import amber.compiler.type.Type

class IntrinsicDSL {
    val intrinsics = mutableMapOf<String, Intrinsic>()
    var currentModule: String? = null

    fun module(name: String, block: IntrinsicDSL.() -> Unit) {
        val oldModule = currentModule
        currentModule = name
        this.block()
        currentModule = oldModule
    }

    fun func(
        name: String,
        params: List<Type> = emptyList(),
        returns: Type = Type.Unit,
        cName: String? = null,
        cImpl: String? = null,
        isMutated: List<Boolean>? = null
    ) {
        val qualifiedName = if (currentModule != null) "$currentModule.$name" else name
        intrinsics[qualifiedName] = Intrinsic(name, params, returns, cName ?: name, currentModule, cImpl, isMutated ?: params.map { false })
    }
}

data class Intrinsic(
    val name: String,
    val params: List<Type>,
    val returns: Type,
    val cName: String,
    val module: String? = null,
    val cImplementation: String? = null,
    val isParameterMutated: List<Boolean> = params.map { false }
)

object StandardLibrary {
    val intrinsics: Map<String, Intrinsic>

    init {
        val dsl = IntrinsicDSL()
        dsl.apply {
            // IO Module
            module("std.io") {
                func(
                    "print", listOf(Type.Any), Type.Unit, "print",
                    cImpl = """
                        void __amber_rt_print(const void* val) {
                            if (val) printf("%s", __amber_rt_to_string((void*)val));
                        }
                    """.trimIndent()
                )
                func(
                    "println", listOf(Type.Any), Type.Unit, "println",
                    cImpl = """
                        void __amber_rt_println(const void* val) {
                            if (val) printf("%s\n", __amber_rt_to_string((void*)val));
                            else printf("\n");
                        }
                    """.trimIndent()
                )
                func(
                    "readln", emptyList(), Type.String, "readln",
                    cImpl = """
                        char* __amber_rt_readln() {
                            char* buf = (char*)__amber_rt_alloc(1024);
                            if (fgets(buf, 1024, stdin)) {
                                size_t len = strlen(buf);
                                if (len > 0 && buf[len-1] == '\n') buf[len-1] = '\0';
                                return buf;
                            }
                            return "";
                        }
                    """.trimIndent()
                )
            }

            // String Module
            module("std.str") {
                func(
                    "len", listOf(Type.String), Type.Number, "str_len",
                    cImpl = """
                        double __amber_rt_str_len(const char* s) {
                            return (double)(s ? strlen(s) : 0);
                        }
                    """.trimIndent()
                )
                func(
                    "get", listOf(Type.String, Type.Number), Type.Char, "str_get",
                    cImpl = """
                        char __amber_rt_str_get(const char* s, double idx) {
                            int i = (int)idx;
                            if (!s || i < 0 || i >= (int)strlen(s)) {
                                __amber_rt_panic("string index out of bounds");
                            }
                            return s[i];
                        }
                    """.trimIndent()
                )
                func(
                    "get_or_err", listOf(Type.String, Type.Number), Type.Unsafe(Type.Char), "str_get_or_err",
                    cImpl = """
                        struct AMBER_RESULT __amber_rt_str_get_or_err(const char* s, double idx) {
                            int i = (int)idx;
                            if (!s || i < 0 || i >= (int)strlen(s)) {
                                return __amber_rt_result_error("string index out of bounds");
                            }
                            return __amber_rt_result_success(__amber_rt_box_char(s[i]));
                        }
                    """.trimIndent()
                )
                func(
                    "from_num", listOf(Type.Number), Type.String, "from_num",
                    cImpl = """
                        char* __amber_rt_from_num(double n) {
                            char* buf = (char*)__amber_rt_alloc(64);
                            snprintf(buf, 64, "%g", n);
                            return buf;
                        }
                    """.trimIndent()
                )
                func("contains", listOf(Type.String, Type.String), Type.Boolean, "str_contains",
                    cImpl = "int __amber_rt_str_contains(const char* s, const char* sub) { return (s && sub && strstr(s, sub)) ? 1 : 0; }")
                func("starts_with", listOf(Type.String, Type.String), Type.Boolean, "str_starts_with",
                    cImpl = "int __amber_rt_str_starts_with(const char* s, const char* sub) { return (s && sub && strncmp(s, sub, strlen(sub)) == 0) ? 1 : 0; }")
                func("ends_with", listOf(Type.String, Type.String), Type.Boolean, "str_ends_with",
                    cImpl = """
                        int __amber_rt_str_ends_with(const char* s, const char* sub) {
                            if (!s || !sub) return 0;
                            size_t len = strlen(s);
                            size_t sublen = strlen(sub);
                            if (sublen > len) return 0;
                            return strcmp(s + len - sublen, sub) == 0 ? 1 : 0;
                        }
                    """.trimIndent())
            }

            // Math Module
            module("std.math") {
                func("abs", listOf(Type.Number), Type.Number, "math_abs",
                    cImpl = "double __amber_rt_math_abs(double n) { return fabs(n); }")
                func("sqrt", listOf(Type.Number), Type.Number, "math_sqrt",
                    cImpl = "double __amber_rt_math_sqrt(double n) { return sqrt(n); }")
                func("pow", listOf(Type.Number, Type.Number), Type.Number, "math_pow",
                    cImpl = "double __amber_rt_math_pow(double b, double e) { return pow(b, e); }")
                func("sin", listOf(Type.Number), Type.Number, "math_sin",
                    cImpl = "double __amber_rt_math_sin(double n) { return sin(n); }")
                func("cos", listOf(Type.Number), Type.Number, "math_cos",
                    cImpl = "double __amber_rt_math_cos(double n) { return cos(n); }")
                func("floor", listOf(Type.Number), Type.Number, "math_floor",
                    cImpl = "double __amber_rt_math_floor(double n) { return floor(n); }")
                func("ceil", listOf(Type.Number), Type.Number, "math_ceil",
                    cImpl = "double __amber_rt_math_ceil(double n) { return ceil(n); }")
            }

            // Char Module
            module("std.char") {
                func("is_digit", listOf(Type.Char), Type.Boolean, "char_is_digit",
                    cImpl = "int __amber_rt_char_is_digit(char c) { return (c >= '0' && c <= '9') ? 1 : 0; }")
                func("is_letter", listOf(Type.Char), Type.Boolean, "char_is_letter",
                    cImpl = "int __amber_rt_char_is_letter(char c) { return ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) ? 1 : 0; }")
            }

            // Runtime Module
            module("std.runtime") {
                func("exit", listOf(Type.Number), Type.Unit, "exit",
                    cImpl = "void __amber_rt_exit(double code) { exit((int)code); }")
                func("fatal", listOf(Type.String), Type.Unit, "panic",
                    cImpl = """
                        void __amber_rt_panic(const char* msg) {
                            fprintf(stderr, "panic: %s\n", msg);
                            exit(1);
                        }
                    """.trimIndent())
                
                func(
                    "to_string", listOf(Type.Any), Type.String, "to_string",
                    cImpl = """
                        char* __amber_rt_double_to_string(void* val) {
                            return __amber_rt_from_num(__amber_rt_unbox_double(val));
                        }
                        char* __amber_rt_bool_to_string(void* val) {
                            return __amber_rt_unbox_bool(val) ? "true" : "false";
                        }
                        char* __amber_rt_char_to_string_direct(char c) {
                            char* buf = (char*)__amber_rt_alloc(2);
                            buf[0] = c;
                            buf[1] = '\0';
                            return buf;
                        }
                        char* __amber_rt_char_to_string(void* val) {
                            return __amber_rt_char_to_string_direct(__amber_rt_unbox_char(val));
                        }
                        char* __amber_rt_string_to_string(void* val) {
                            return __amber_rt_unbox_string(val);
                        }
                        char* __amber_rt_list_to_string(void* val) {
                            __amber_list_t* l = (__amber_list_t*)val;
                            if (!l) return "null";
                            if (l->length == 0) return "[]";
                            
                            size_t total_len = 2; // "[" and "]"
                            char** strings = (char**)__amber_rt_alloc(sizeof(char*) * l->length);
                            for (int i = 0; i < l->length; i++) {
                                strings[i] = __amber_rt_to_string(l->data[i]);
                                total_len += strlen(strings[i]);
                                if (i < l->length - 1) total_len += 2; // ", "
                            }
                            
                            char* res = (char*)__amber_rt_alloc(total_len + 1);
                            char* p = res;
                            *p++ = '[';
                            for (int i = 0; i < l->length; i++) {
                                size_t slen = strlen(strings[i]);
                                memcpy(p, strings[i], slen);
                                p += slen;
                                if (i < l->length - 1) {
                                    *p++ = ',';
                                    *p++ = ' ';
                                }
                            }
                            *p++ = ']';
                            *p = '\0';
                            return res;
                        }
    
                        char* __amber_rt_to_string(void* val) {
                            if (!val) return "null";
                            __amber_header_t* h = (__amber_header_t*)val;
                            if (h->type == &__amber_type_double) return __amber_rt_double_to_string(val);
                            if (h->type == &__amber_type_string) return __amber_rt_string_to_string(val);
                            if (h->type == &__amber_type_bool) return __amber_rt_bool_to_string(val);
                            if (h->type == &__amber_type_char) return __amber_rt_char_to_string(val);
                            if (h->type == &__amber_type_list) return __amber_rt_list_to_string(val);
                            if (h->type && h->type->to_string) {
                                return h->type->to_string(val);
                            }
                            return "???";
                        }
                    """.trimIndent()
                )
            }

            // List Module
            module("std.list") {
                func("len", listOf(Type.List(Type.Any)), Type.Number, "list_len",
                    cImpl = "double __amber_rt_list_len(__amber_list_t* l) { return l ? (double)l->length : 0.0; }")

                func("get", listOf(Type.List(Type.Any), Type.Number), Type.Any, "list_get",
                    cImpl = """
                        void* __amber_rt_list_get(__amber_list_t* l, double idx) {
                            int i = (int)idx;
                            if (!l || i < 0 || i >= l->length) {
                                __amber_rt_panic("index out of bounds");
                            }
                            return l->data[i];
                        }
                    """.trimIndent())

                func("get_or_err", listOf(Type.List(Type.Any), Type.Number), Type.Unsafe(Type.Any), "list_get_or_err",
                    cImpl = """
                        struct AMBER_RESULT __amber_rt_list_get_or_err(__amber_list_t* l, double idx) {
                            int i = (int)idx;
                            if (!l || i < 0 || i >= l->length) {
                                return __amber_rt_result_error("index out of bounds");
                            }
                            return __amber_rt_result_success(l->data[i]);
                        }
                    """.trimIndent())
                
                func("push", listOf(Type.List(Type.Any), Type.Any), Type.Unit, "list_push",
                    isMutated = listOf(true, false),
                    cImpl = """
                        void __amber_rt_list_push(__amber_list_t* l, void* val) {
                            if (!l) return;
                            if (l->length >= l->capacity) {
                                l->capacity = l->capacity == 0 ? 4 : l->capacity * 2;
                                l->data = (void**)__amber_rt_realloc(l->data, sizeof(void*) * l->capacity);
                            }
                            l->data[l->length++] = val;
                        }
                    """.trimIndent())

                func("pop", listOf(Type.List(Type.Any)), Type.Any, "list_pop",
                    isMutated = listOf(true),
                    cImpl = """
                        void* __amber_rt_list_pop(__amber_list_t* l) {
                            if (!l || l->length == 0) return NULL;
                            return l->data[--l->length];
                        }
                    """.trimIndent())
                
                func("set", listOf(Type.List(Type.Any), Type.Number, Type.Any), Type.Unit, "list_set",
                    isMutated = listOf(true, false, false),
                    cImpl = """
                        void __amber_rt_list_set(__amber_list_t* l, double idx, void* val) {
                            int i = (int)idx;
                            if (!l || i < 0 || i >= l->length) return;
                            l->data[i] = val;
                        }
                    """.trimIndent())
            }

            func(
                "str_concat", listOf(Type.String, Type.String), Type.String, "str_concat",
                cImpl = """
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

                    char* __amber_rt_str_concat_multi(int count, ...) {
                        va_list args;
                        va_start(args, count);
                        size_t total_len = 0;
                        char** strings = (char**)__amber_rt_alloc(sizeof(char*) * count);
                        for (int i = 0; i < count; i++) {
                            strings[i] = va_arg(args, char*);
                            if (strings[i]) total_len += strlen(strings[i]);
                        }
                        va_end(args);

                        char* res = (char*)__amber_rt_alloc(total_len + 1);
                        char* p = res;
                        for (int i = 0; i < count; i++) {
                            if (strings[i]) {
                                size_t len = strlen(strings[i]);
                                memcpy(p, strings[i], len);
                                p += len;
                            }
                        }
                        *p = '\0';
                        return res;
                    }
                """.trimIndent()
            )

            func(
                "create_list", listOf(Type.Number), Type.List(Type.Any), "create_list",
                cImpl = """
                    #include <stdarg.h>
                    __amber_list_t* __amber_rt_create_list(int count, ...) {
                        __amber_list_t* l = (__amber_list_t*)__amber_rt_alloc(sizeof(__amber_list_t));
                        l->header.type = &__amber_type_list;
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
                """.trimIndent()
            )
        }
        intrinsics = dsl.intrinsics
    }

    fun getIntrinsic(qualifiedName: String): Intrinsic? = intrinsics[qualifiedName]

    fun getBuiltInSymbols(): Map<String, Symbol> = getAllSymbols().filter { it.value.namespace == null }

    fun getAllSymbols(): Map<String, Symbol> = intrinsics.mapValues { (_, intr) ->
        Symbol(
            intr.name,
            Type.Function(intr.params, intr.params.map { false }, intr.returns, intr.isParameterMutated),
            isIntrinsic = true,
            namespace = intr.module
        )
    }
}
