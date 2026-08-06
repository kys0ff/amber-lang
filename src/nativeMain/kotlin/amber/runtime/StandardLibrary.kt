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
        cImpl: String? = null
    ) {
        val qualifiedName = if (currentModule != null) "$currentModule.$name" else name
        intrinsics[qualifiedName] = Intrinsic(name, params, returns, cName ?: name, currentModule, cImpl)
    }
}

data class Intrinsic(
    val name: String,
    val params: List<Type>,
    val returns: Type,
    val cName: String,
    val module: String? = null,
    val cImplementation: String? = null
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
                            if (val) printf("%s", (const char*)val);
                        }
                    """.trimIndent()
                )
                func(
                    "println", listOf(Type.Any), Type.Unit, "println",
                    cImpl = """
                        void __amber_rt_println(const void* val) {
                            if (val) printf("%s\n", (const char*)val);
                            else printf("\n");
                        }
                    """.trimIndent()
                )
                func(
                    "readln", emptyList(), Type.String, "readln",
                    cImpl = """
                        char* __amber_rt_readln() {
                            char* buf = (char*)GC_MALLOC(1024);
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
                    "to_string", listOf(Type.Any), Type.String, "to_string"
                )
                func(
                    "from_number", listOf(Type.Number), Type.String, "from_number",
                    cImpl = """
                        char* __amber_rt_from_number(double n) {
                            char* buf = (char*)GC_MALLOC(64);
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
                func("max", listOf(Type.Number, Type.Number), Type.Number, "math_max",
                    cImpl = "double __amber_rt_math_max(double a, double b) { return a > b ? a : b; }")
                func("min", listOf(Type.Number, Type.Number), Type.Number, "math_min",
                    cImpl = "double __amber_rt_math_min(double a, double b) { return a < b ? a : b; }")
            }

            // Runtime Module
            module("std.runtime") {
                func("exit", listOf(Type.Number), Type.Unit, "exit",
                    cImpl = "void __amber_rt_exit(double code) { exit((int)code); }")
                func("panic", listOf(Type.String), Type.Nothing, "panic",
                    cImpl = """
                        void __amber_rt_panic(const char* msg) {
                            fprintf(stderr, "panic: %s\n", msg);
                            exit(1);
                        }
                    """.trimIndent())
            }

            // List Module
            module("std.list") {
                func("len", listOf(Type.List(Type.Any)), Type.Number, "list_len",
                    cImpl = "double __amber_rt_list_len(__amber_list_t* l) { return l ? (double)l->length : 0.0; }")
                
                func("push", listOf(Type.List(Type.Any), Type.Any), Type.Unit, "list_push",
                    cImpl = """
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
                    """.trimIndent())

                func("pop", listOf(Type.List(Type.Any)), Type.Any, "list_pop",
                    cImpl = """
                        void* __amber_rt_list_pop(__amber_list_t* l) {
                            if (!l || l->length == 0) return NULL;
                            return l->data[--l->length];
                        }
                    """.trimIndent())
                
                func("get", listOf(Type.List(Type.Any), Type.Number), Type.Any, "list_get",
                    cImpl = """
                        void* __amber_rt_list_get(__amber_list_t* l, double idx) {
                            int i = (int)idx;
                            if (!l || i < 0 || i >= l->length) return NULL;
                            return l->data[i];
                        }
                    """.trimIndent())
            }

            // Core Internal Helpers
            func(
                "to_string", listOf(Type.Any), Type.String, "to_string",
                cImpl = """
                    char* __amber_rt_to_string(void* val) {
                        return (char*)val;
                    }
                """.trimIndent()
            )

            func(
                "str_concat", listOf(Type.String, Type.String), Type.String, "str_concat",
                cImpl = """
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
                """.trimIndent()
            )

            func(
                "create_list", listOf(Type.Number), Type.List(Type.Any), "create_list",
                cImpl = """
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
                """.trimIndent()
            )
        }
        intrinsics = dsl.intrinsics
    }

    fun getIntrinsic(qualifiedName: String): Intrinsic? = intrinsics[qualifiedName]
    
    fun getAllSymbols(): Map<String, Symbol> {
        return intrinsics.mapValues { (qn, intr) ->
            Symbol(
                intr.name,
                Type.Function(intr.params, intr.params.map { false }, intr.returns),
                isIntrinsic = true,
                namespace = intr.module
            )
        }
    }
}
