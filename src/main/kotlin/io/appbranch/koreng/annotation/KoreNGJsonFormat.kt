package io.appbranch.koreng.annotation

@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.FUNCTION
)

@Retention(AnnotationRetention.RUNTIME)
annotation class KoreNGJsonFormat(val pattern: String)
