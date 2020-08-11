import bwapi.*
import java.io.PrintStream
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.nio.file.Files
import java.nio.file.Paths

interface Type {
    fun toType(): String
    fun toValue(value: Any): String
}

class Slice(val of: Type) : Type {
    override fun toType() = "&'static [${of.toType()}]"
    override fun toValue(value: Any): String {
        val v = if (value is Iterable<*>) value else (value as Map<*, *>).entries
        return "&[${v.joinToString(", ") { of.toValue(it!!) }}]"
    }

}

class Ref(val prefix: Boolean, val type: String) : Type {
    override fun toType() = type
    override fun toValue(value: Any) =
            (if (prefix) "$type::" else "") + value
}

class Tuple2(val a: Type, val b: Type) : Type {
    override fun toType() = "(${a.toType()}, ${b.toType()})"
    override fun toValue(value: Any): String {
        val (av, bv) = if (value is Pair<*, *>) kotlin.Pair(value.first, value.second) else {
            value as Map.Entry<*, *>; kotlin.Pair(value.key, value.value)
        }
        return "(${a.toValue(av)}, ${b.toValue(bv)})"
    }

}

class TPosition(val type: String) : Type {
    override fun toType() = type
    override fun toValue(value: Any): String {
        value as Point<*>
        return "$type { x: ${value.x}, y: ${value.y} }"
    }

}

class Str : Type {
    override fun toType() = "&'static str"
    override fun toValue(value: Any) = "\"$value\""
}

fun java.lang.reflect.Type.toRSType(): Type =
        if (this is ParameterizedType) {
            when (this.rawType) {
                List::class.java -> {
                    val type = this.actualTypeArguments[0] as Class<*>
                    Slice(Ref(true, type.simpleName))
                }
                Pair::class.java -> {
                    val a = this.actualTypeArguments[0] as Class<*>
                    val b = this.actualTypeArguments[1] as Class<*>
                    Tuple2(a.toRSType(), b.toRSType())
                }
                Map::class.java -> {
                    val a = this.actualTypeArguments[0] as Class<*>
                    val b = this.actualTypeArguments[1] as Class<*>
                    Slice(Tuple2(a.toRSType(), b.toRSType()))
                }
                else -> {
                    throw IllegalStateException(this.toString())
                }
            }
        } else {
            when (this) {
                Double::class.java -> Ref(false, "f64")
                Integer::class.java, Int::class.java -> Ref(false, "i32")
                Boolean::class.java -> Ref(false, "bool")
                TilePosition::class.java -> TPosition("TilePosition")
                String::class.java -> Str()
                else -> {
                    if (this is Class<*> && this.isEnum) {
                        Ref(true, this.simpleName)
                    } else
                        throw IllegalStateException(this.toString())
                }
            }
        }

fun String.toSnakeCase() = fold(StringBuilder()) { acc, c ->
    if (acc.isNotEmpty() && acc.last().isLowerCase() && c.isUpperCase()) {
        acc.append('_')
        acc.append(c.toLowerCase())
    } else
        acc.append(c.toLowerCase())
}.toString()

inline fun printOut(out: PrintStream, c: Class<*>) {
    val values = c.getMethod("values")(c) as Array<*>
    with(out) {
        println("use crate::prelude::*;")
        val relevantGetters = c.methods
                .filter { it.parameterCount == 0 && it.returnType != Void.TYPE && it.modifiers and Modifier.STATIC == 0 }
                .filter { it.name !in arrayOf("toString", "hashCode", "getClass", "ordinal", "getDeclaringClass") }
                .toList()
        val namedGetters = relevantGetters.map {
            it.name.toSnakeCase() to it
        }
        val name = "${c.simpleName}Data"
        println("pub(crate) struct $name {")
        println(namedGetters.joinToString(",\n") { (name, getter) ->
            val type = getter.genericReturnType.toRSType()
            "pub(crate) ${name.removePrefix("get_")}: ${type.toType()}"
        })
        println("}")
        val dataName = name.toSnakeCase().toUpperCase()
        println("pub(crate) static $dataName : [$name; " + values.size + "] = [")
        println(
                values.joinToString(", \n") { item ->
                    "$name {" +
                            namedGetters.joinToString(",\n") { (name, getter) ->
                                val name = name.removePrefix("get_")
                                val type = getter.genericReturnType.toRSType()
                                val value = try {
                                    type.toValue(getter(item))
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                                "$name: $value"
                            } + "\n}"
                }
        )
        println("];")

        println("impl ${c.simpleName} {")
        println("fn d(&self) -> &$name { &$dataName[*self as usize] }")
        for ((name, getter) in namedGetters) {
            val type = getter.genericReturnType.toRSType()
            println("pub fn $name(&self) -> ${type.toType()} {")
            println("  self.d().${name.removePrefix("get_")}")
            println("}")
        }
        println("}")
    }
}

fun main(args: Array<String>) {
    arrayOf(UnitType::class.java, UpgradeType::class.java, TechType::class.java, WeaponType::class.java)
            .forEach { _class ->
                val fileName = Paths.get(args[0]).resolve(_class.simpleName.toSnakeCase() + ".rs")
                PrintStream(Files.newOutputStream(fileName))
                        .use { printOut(it, _class) }
            }

}