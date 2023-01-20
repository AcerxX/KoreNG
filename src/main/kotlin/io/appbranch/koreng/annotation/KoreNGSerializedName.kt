package io.appbranch.koreng.annotation

@Target(
    AnnotationTarget.FUNCTION
)

@Retention(AnnotationRetention.RUNTIME)
annotation class KoreNGSerializedName(val name: String)
