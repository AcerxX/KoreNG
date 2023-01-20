package io.appbranch.koreng.dto

import com.fasterxml.jackson.annotation.JsonCreator

data class KoreNGFilterInfo @JsonCreator constructor(
    val columnField: String,
    val operatorValue: String,
    val value: String? = null,
    val anyOfValues: List<String> = listOf()
)