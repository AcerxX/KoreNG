package io.appbranch.koreng.controller

import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.*
import io.appbranch.koreng.annotation.KoreNGIgnore
import io.appbranch.koreng.annotation.KoreNGJsonFormat
import io.appbranch.koreng.annotation.KoreNGSerializedName
import io.appbranch.koreng.dto.KoreNGFilterInfo
import io.appbranch.koreng.dto.KoreNGSearchRequest
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Path
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaMethod

@RestController
@RequestMapping("/kore-ng")
class KoreNGController @Autowired constructor(
    val entityManager: EntityManager
) {
    companion object {
        val LOGGER = LoggerFactory.getLogger(KoreNGController::class.java)
    }

    @GetMapping("/model/{entityName}")
    fun model(@PathVariable entityName: String): Map<String, String> {
        return Class.forName(
            "io.appbranch.koreng.entity.${
                entityName.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(
                        Locale.getDefault()
                    ) else it.toString()
                }
            }"
        )
            .kotlin
            .members
            .filter { !it.returnType.toString().contains("koreng.entity") }
            .filter {
                (it is KMutableProperty && !it.javaField!!.isAnnotationPresent(KoreNGIgnore::class.java))
                        || (
                        it is KFunction
                                && !it.javaMethod!!.isAnnotationPresent(KoreNGIgnore::class.java)
                                && it.name.startsWith("get")
                        )
            }
            .associate {
                var key = it.name
                if (it is KFunction && it.javaMethod!!.isAnnotationPresent(KoreNGSerializedName::class.java)) {
                    key = it.javaMethod!!.getAnnotation(KoreNGSerializedName::class.java).name
                }

                key to when (it.returnType.toString()) {
                    "kotlin.Boolean", "kotlin.Boolean?" -> "boolean"
                    "kotlin.Int", "kotlin.Int?", "kotlin.Double", "kotlin.Double?" -> "number"
                    "java.time.LocalDate", "java.time.LocalDate?" -> "date"
                    "java.time.LocalDateTime", "java.time.LocalDateTime?" -> "dateTime"
                    else -> "string"
                }
            }
    }


    @PostMapping("/search/{entityName}")
    fun results(
        @RequestBody request: KoreNGSearchRequest,
        @PathVariable entityName: String
    ): List<Map<String, Any?>> {
        val cls = Class.forName(
            "io.appbranch.koreng.entity.${
                entityName.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(
                        Locale.getDefault()
                    ) else it.toString()
                }
            }"
        )

        val criteriaBuilder = entityManager.criteriaBuilder
        val criteriaQuery = criteriaBuilder.createQuery(cls)
        val entity = criteriaQuery.from(cls)
        val predicates = mutableListOf<Predicate>()
        val excludedPredicates = mutableListOf<Predicate>()

        try {
            val member = cls.kotlin.members.find { member -> member.name == "status" }
            if (member != null
                && (member.returnType.toString().contains("kotlin.Int")
                        || member.returnType.toString().contains("kotlin.Boolean"))
            ) {
                excludedPredicates.add(criteriaBuilder.greaterThan(entity.get("status"), 0))
            }
        } catch (_: Throwable) {
        }

        applyFilters(request.filters, entity, cls, predicates, criteriaBuilder)
        applyFilters(request.excludedColumnsFilters, entity, cls, excludedPredicates, criteriaBuilder)

        // Prepare where predicates
        if (predicates.size > 0 && excludedPredicates.size > 0) {
            criteriaQuery.where(
                criteriaBuilder.and(
                    computeFinalPredicate(request.filtersLinkOperator, criteriaBuilder, predicates),
                    computeFinalPredicate(
                        request.excludedColumnsFiltersLinkOperator,
                        criteriaBuilder,
                        excludedPredicates
                    ),
                )
            )
        } else if (predicates.size > 0) {
            criteriaQuery.where(computeFinalPredicate(request.filtersLinkOperator, criteriaBuilder, predicates))
        } else if (excludedPredicates.size > 0) {
            criteriaQuery.where(
                computeFinalPredicate(
                    request.excludedColumnsFiltersLinkOperator,
                    criteriaBuilder,
                    excludedPredicates
                )
            )
        }

        // Prepare sorting
        if (request.sort.isNotEmpty()) {
            criteriaQuery.orderBy(
                request
                    .sort
                    .map {
                        if (it.sort.lowercase() == "desc") {
                            criteriaBuilder.desc(getColumnObject(it.field, entity))
                        } else {
                            criteriaBuilder.asc(getColumnObject(it.field, entity))
                        }
                    }
            )
        }

        // Set distinct results
        criteriaQuery.distinct(true)

        val results = entityManager
            .createQuery(criteriaQuery)
            .setFirstResult(request.start)
            .setMaxResults(request.length)
            .resultList

        return convertResultsToResponse(results)
    }

    private fun computeFinalPredicate(
        linkOperator: String,
        criteriaBuilder: CriteriaBuilder,
        predicates: MutableList<Predicate>
    ): Predicate = if (linkOperator == "or") {
        criteriaBuilder.or(*predicates.toTypedArray())
    } else {
        criteriaBuilder.and(*predicates.toTypedArray())
    }

    private fun applyFilters(
        filters: List<KoreNGFilterInfo>,
        entity: Root<out Any>,
        cls: Class<*>,
        predicates: MutableList<Predicate>,
        criteriaBuilder: CriteriaBuilder
    ) {
        filters
            .filter { checkFilter(it) }
            .forEach { filter ->
                try {
                    parseFilter(filter, cls, predicates, criteriaBuilder, entity)
                } catch (ignored: Throwable) {
                }
            }
    }

    private fun checkFilter(filter: KoreNGFilterInfo): Boolean {
        if (filter.operatorValue == "isAnyOf" && filter.anyOfValues.isEmpty()) {
            LOGGER.warn("\n\n\n************************************************************\nFilter ${filter.operatorValue} of field ${filter.columnField} is not valid!\n************************************************************\n\n\n")

            return false
        }

        if (filter.operatorValue != "isAnyOf" && filter.operatorValue != "isNotEmptyArray" && filter.value == null) {
            LOGGER.warn("\n\n\n************************************************************\nFilter ${filter.operatorValue} of field ${filter.columnField} is empty!\n************************************************************\n\n\n")

            return false
        }

        return true
    }

    private fun parseFilter(
        filter: KoreNGFilterInfo,
        cls: Class<*>,
        predicates: MutableList<Predicate>,
        criteriaBuilder: CriteriaBuilder,
        entity: Root<out Any>
    ) {
        val anyColumnObj = getColumnObject(filter.columnField, entity)
        val doubleColumnObj = anyColumnObj as Path<Double>
        val stringColumnObj = anyColumnObj as Path<String>
        val localDateColumnObj = anyColumnObj as Path<LocalDate>
        val localDateTimeColumnObj = anyColumnObj as Path<LocalDateTime>

        when (filter.operatorValue) {
            "is" -> {
                when (filter.value) {
                    "false" -> {
                        val member = cls.kotlin.members.find { member -> member.name == filter.columnField }
                        if (member != null && member.returnType.toString().contains("kotlin.Boolean")) {
                            predicates.add(criteriaBuilder.isFalse(entity.get(filter.columnField)))
                        } else {
                            predicates.add(criteriaBuilder.equal(anyColumnObj, 0))
                        }
                    }

                    "true" -> {
                        val member = cls.kotlin.members.find { member -> member.name == filter.columnField }
                        if (member != null && member.returnType.toString().contains("kotlin.Boolean")) {
                            predicates.add(criteriaBuilder.isTrue(entity.get(filter.columnField)))
                        } else {
                            predicates.add(criteriaBuilder.equal(anyColumnObj, 1))
                        }
                    }

                    else -> {
                        try {
                            predicates.add(criteriaBuilder.equal(localDateColumnObj, LocalDate.parse(filter.value)))
                        } catch (e: Throwable) {
                            try {
                                predicates.add(
                                    criteriaBuilder.equal(
                                        localDateTimeColumnObj,
                                        LocalDateTime.parse(filter.value)
                                    )
                                )
                            } catch (e: Throwable) {
                                predicates.add(criteriaBuilder.equal(anyColumnObj, filter.value))
                            }
                        }
                    }
                }
            }

            "not" -> {
                try {
                    predicates.add(criteriaBuilder.notEqual(localDateColumnObj, LocalDate.parse(filter.value)))
                } catch (e: Throwable) {
                    predicates.add(
                        criteriaBuilder.notEqual(
                            localDateTimeColumnObj,
                            LocalDateTime.parse(filter.value)
                        )
                    )
                }
            }

            "after" -> {
                try {
                    predicates.add(criteriaBuilder.greaterThan(localDateColumnObj, LocalDate.parse(filter.value)))
                } catch (e: Throwable) {
                    predicates.add(
                        criteriaBuilder.greaterThan(
                            localDateTimeColumnObj,
                            LocalDateTime.parse(filter.value)
                        )
                    )
                }
            }

            "onOrAfter" -> {
                try {
                    predicates.add(
                        criteriaBuilder.greaterThanOrEqualTo(
                            localDateColumnObj,
                            LocalDate.parse(filter.value)
                        )
                    )
                } catch (e: Throwable) {
                    predicates.add(
                        criteriaBuilder.greaterThanOrEqualTo(
                            localDateTimeColumnObj,
                            LocalDateTime.parse(filter.value)
                        )
                    )
                }
            }

            "before" -> {
                try {
                    predicates.add(criteriaBuilder.lessThan(localDateColumnObj, LocalDate.parse(filter.value)))
                } catch (e: Throwable) {
                    predicates.add(
                        criteriaBuilder.lessThan(
                            localDateTimeColumnObj,
                            LocalDateTime.parse(filter.value)
                        )
                    )
                }
            }

            "onOrBefore" -> {
                try {
                    predicates.add(
                        criteriaBuilder.lessThanOrEqualTo(
                            localDateColumnObj,
                            LocalDate.parse(filter.value)
                        )
                    )
                } catch (e: Throwable) {
                    predicates.add(
                        criteriaBuilder.lessThanOrEqualTo(
                            localDateTimeColumnObj,
                            LocalDateTime.parse(filter.value)
                        )
                    )
                }
            }


            "contains" -> predicates.add(criteriaBuilder.like(stringColumnObj, "%${filter.value}%"))
            "equals" -> predicates.add(criteriaBuilder.equal(stringColumnObj, filter.value))
            "startsWith" -> predicates.add(criteriaBuilder.like(stringColumnObj, "${filter.value}%"))
            "endsWith" -> predicates.add(criteriaBuilder.like(stringColumnObj, "%${filter.value}"))
            "isEmpty" -> predicates.add(
                criteriaBuilder.or(
                    criteriaBuilder.isNull(anyColumnObj),
                    criteriaBuilder.equal(anyColumnObj, "")
                )
            )

            "isNotEmpty" -> predicates.add(
                criteriaBuilder.and(
                    criteriaBuilder.isNotNull(anyColumnObj),
                    criteriaBuilder.notEqual(anyColumnObj, "")
                )
            )

            "=" -> predicates.add(criteriaBuilder.equal(doubleColumnObj, filter.value))
            "!=" -> predicates.add(criteriaBuilder.notEqual(doubleColumnObj, filter.value))
            ">" -> predicates.add(criteriaBuilder.greaterThan(doubleColumnObj, filter.value!!.toDouble()))
            ">=" -> predicates.add(criteriaBuilder.greaterThanOrEqualTo(doubleColumnObj, filter.value!!.toDouble()))
            "<" -> predicates.add(criteriaBuilder.lessThan(doubleColumnObj, filter.value!!.toDouble()))
            "<=" -> predicates.add(criteriaBuilder.lessThanOrEqualTo(doubleColumnObj, filter.value!!.toDouble()))
            "isAnyOf" -> predicates.add(stringColumnObj.`in`(filter.anyOfValues))
            "isNotEmptyArray" -> entity.join<Any, Any>(filter.columnField)

        }
    }

    private fun getColumnObject(
        columnName: String,
        entity: Root<out Any>
    ): Path<Any> {
        var anyColumnObj: Path<Any>? = null

        columnName
            .split(".")
            .forEach { relationshipColumn ->
                anyColumnObj = if (anyColumnObj == null) {
                    entity.get(relationshipColumn)
                } else {
                    anyColumnObj!!.get(relationshipColumn)
                }
            }

        return anyColumnObj!!
    }


    private fun convertResultsToResponse(results: MutableList<out Any>) = results.map { item ->
        item
            .javaClass
            .kotlin
            .members
            .filter { !it.returnType.toString().contains("koreng.entity") }
            .filter {
                (it is KMutableProperty && !it.javaField!!.isAnnotationPresent(KoreNGIgnore::class.java))
                        || (
                        it is KFunction
                                && !it.javaMethod!!.isAnnotationPresent(KoreNGIgnore::class.java)
                                && it.name.startsWith("get")
                        )
            }
            .associate {
                if (it is KMutableProperty) {
                    it.name to when (it.returnType.toString()) {
                        "java.time.LocalDateTime", "java.time.LocalDateTime?" -> {
                            if (it.javaField!!.isAnnotationPresent(KoreNGJsonFormat::class.java)) {
                                val pattern = it.javaField!!.getAnnotation(KoreNGJsonFormat::class.java).pattern
                                (it.getter.call(item) as LocalDateTime?)?.format(DateTimeFormatter.ofPattern(pattern))
                            } else {
                                (it.getter.call(item) as LocalDateTime?)?.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))
                            }
                        }

                        "java.time.LocalDate", "java.time.LocalDate?" -> {
                            if (it.javaField!!.isAnnotationPresent(KoreNGJsonFormat::class.java)) {
                                val pattern = it.javaField!!.getAnnotation(KoreNGJsonFormat::class.java).pattern
                                (it.getter.call(item) as LocalDate?)?.format(DateTimeFormatter.ofPattern(pattern))
                            } else {
                                (it.getter.call(item) as LocalDate?)?.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                            }
                        }

                        else -> {
                            it.getter.call(item)
                        }
                    }
                } else {
                    var key = it.name
                    if ((it as KFunction).javaMethod!!.isAnnotationPresent(KoreNGSerializedName::class.java)) {
                        key = it.javaMethod!!.getAnnotation(KoreNGSerializedName::class.java).name
                    }

                    key to it.call(item)
                }
            }
    }
}