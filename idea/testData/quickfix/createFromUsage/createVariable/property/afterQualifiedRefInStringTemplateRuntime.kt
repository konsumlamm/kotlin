// "Create property 'foo'" "true"
// ERROR: Property must be initialized or be abstract

class A {
    val foo: String
}

fun test() {
    println("a = ${A().foo}")
}