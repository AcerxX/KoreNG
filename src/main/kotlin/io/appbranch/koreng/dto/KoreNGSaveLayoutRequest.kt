package io.appbranch.koreng.dto

import com.fasterxml.jackson.annotation.JsonCreator

data class KoreNGSaveLayoutRequest @JsonCreator constructor(
//    val loggedUserId: Int,
    val columnOrderAndVisibility: List<String>? = null,
    val columnWidth: Map<String, Int>? = null,
    val columnPinned: Map<String, List<String>>? = null,
    val density: String? = null
)