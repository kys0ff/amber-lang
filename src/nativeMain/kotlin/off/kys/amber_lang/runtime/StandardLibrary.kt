package off.kys.amber_lang.runtime

import off.kys.amber_lang.transpiler.type.Symbol
import off.kys.amber_lang.transpiler.type.Type

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
        returns: Type = Type.UnitType,
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
                    "print", listOf(Type.AnyType), Type.UnitType, "print",
                    cImpl = """
                        void __amber_rt_print(void* val) {
                            if (val) printf("%s", (char*)val);
                        }
                    """.trimIndent()
                )
                func(
                    "println", listOf(Type.AnyType), Type.UnitType, "println",
                    cImpl = """
                        void __amber_rt_println(void* val) {
                            if (val) printf("%s\n", (char*)val);
                            else printf("\n");
                        }
                    """.trimIndent()
                )
                func(
                    "readln", emptyList(), Type.StringType, "readln",
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
                    "len", listOf(Type.StringType), Type.NumberType, "str_len",
                    cImpl = """
                        double __amber_rt_str_len(const char* s) {
                            return (double)(s ? strlen(s) : 0);
                        }
                    """.trimIndent()
                )
                func(
                    "to_string", listOf(Type.AnyType), Type.StringType, "to_string"
                )
                func(
                    "from_number", listOf(Type.NumberType), Type.StringType, "from_number",
                    cImpl = """
                        char* __amber_rt_from_number(double n) {
                            char* buf = (char*)GC_MALLOC(64);
                            snprintf(buf, 64, "%g", n);
                            return buf;
                        }
                    """.trimIndent()
                )
                func("contains", listOf(Type.StringType, Type.StringType), Type.BooleanType, "str_contains",
                    cImpl = "int __amber_rt_str_contains(const char* s, const char* sub) { return (s && sub && strstr(s, sub)) ? 1 : 0; }")
                func("starts_with", listOf(Type.StringType, Type.StringType), Type.BooleanType, "str_starts_with",
                    cImpl = "int __amber_rt_str_starts_with(const char* s, const char* sub) { return (s && sub && strncmp(s, sub, strlen(sub)) == 0) ? 1 : 0; }")
                func("ends_with", listOf(Type.StringType, Type.StringType), Type.BooleanType, "str_ends_with",
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
                func("abs", listOf(Type.NumberType), Type.NumberType, "math_abs",
                    cImpl = "double __amber_rt_math_abs(double n) { return fabs(n); }")
                func("sqrt", listOf(Type.NumberType), Type.NumberType, "math_sqrt",
                    cImpl = "double __amber_rt_math_sqrt(double n) { return sqrt(n); }")
                func("pow", listOf(Type.NumberType, Type.NumberType), Type.NumberType, "math_pow",
                    cImpl = "double __amber_rt_math_pow(double b, double e) { return pow(b, e); }")
                func("sin", listOf(Type.NumberType), Type.NumberType, "math_sin",
                    cImpl = "double __amber_rt_math_sin(double n) { return sin(n); }")
                func("cos", listOf(Type.NumberType), Type.NumberType, "math_cos",
                    cImpl = "double __amber_rt_math_cos(double n) { return cos(n); }")
                func("floor", listOf(Type.NumberType), Type.NumberType, "math_floor",
                    cImpl = "double __amber_rt_math_floor(double n) { return floor(n); }")
                func("ceil", listOf(Type.NumberType), Type.NumberType, "math_ceil",
                    cImpl = "double __amber_rt_math_ceil(double n) { return ceil(n); }")
                func("max", listOf(Type.NumberType, Type.NumberType), Type.NumberType, "math_max",
                    cImpl = "double __amber_rt_math_max(double a, double b) { return a > b ? a : b; }")
                func("min", listOf(Type.NumberType, Type.NumberType), Type.NumberType, "math_min",
                    cImpl = "double __amber_rt_math_min(double a, double b) { return a < b ? a : b; }")
            }

            // Runtime Module
            module("std.runtime") {
                func("exit", listOf(Type.NumberType), Type.UnitType, "exit",
                    cImpl = "void __amber_rt_exit(double code) { exit((int)code); }")
                func("panic", listOf(Type.StringType), Type.NothingType, "panic",
                    cImpl = """
                        void __amber_rt_panic(const char* msg) {
                            fprintf(stderr, "panic: %s\n", msg);
                            exit(1);
                        }
                    """.trimIndent())
            }

            // List Module
            module("std.list") {
                func("len", listOf(Type.ListType(Type.AnyType)), Type.NumberType, "list_len",
                    cImpl = "double __amber_rt_list_len(__amber_list_t* l) { return l ? (double)l->length : 0.0; }")
                
                func("push", listOf(Type.ListType(Type.AnyType), Type.AnyType), Type.UnitType, "list_push",
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

                func("pop", listOf(Type.ListType(Type.AnyType)), Type.AnyType, "list_pop",
                    cImpl = """
                        void* __amber_rt_list_pop(__amber_list_t* l) {
                            if (!l || l->length == 0) return NULL;
                            return l->data[--l->length];
                        }
                    """.trimIndent())
                
                func("get", listOf(Type.ListType(Type.AnyType), Type.NumberType), Type.AnyType, "list_get",
                    cImpl = """
                        void* __amber_rt_list_get(__amber_list_t* l, double idx) {
                            int i = (int)idx;
                            if (!l || i < 0 || i >= l->length) return NULL;
                            return l->data[i];
                        }
                    """.trimIndent())
            }

            // Core Internal Helpers (not necessarily namespaced for user but used by compiler)
            func(
                "to_string", listOf(Type.AnyType), Type.StringType, "to_string",
                cImpl = """
                    char* __amber_rt_to_string(void* val) {
                        // Currently assumes string, but can be improved with RTTI
                        return (char*)val;
                    }
                """.trimIndent()
            )

            func(
                "str_concat", listOf(Type.StringType, Type.StringType), Type.StringType, "str_concat",
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
                "create_list", listOf(Type.NumberType), Type.ListType(Type.AnyType), "create_list",
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
                Type.FunctionType(intr.params, intr.params.map { false }, intr.returns),
                isIntrinsic = true,
                namespace = intr.module
            )
        }
    }
}
