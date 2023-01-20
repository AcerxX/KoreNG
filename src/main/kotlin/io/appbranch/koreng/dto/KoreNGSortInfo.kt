package io.appbranch.koreng.dto

import com.fasterxml.jackson.annotation.JsonCreator

data class KoreNGSortInfo @JsonCreator constructor(
    val field: String,
    val sort: String
)