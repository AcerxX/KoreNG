package io.appbranch.koreng.dto

import com.fasterxml.jackson.annotation.JsonCreator

data class KoreNGSearchRequest @JsonCreator constructor(
    val start: Int = 0,
    val length: Int = 20,
    val sort: List<KoreNGSortInfo> = listOf(),
    val filters: List<KoreNGFilterInfo> = listOf(),
    val filtersLinkOperator: String = "and",
    val excludedColumnsFilters: List<KoreNGFilterInfo> = listOf(),
    val excludedColumnsFiltersLinkOperator: String = "and"
)