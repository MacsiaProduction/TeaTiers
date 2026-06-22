package com.macsia.teatiers.validation

import com.macsia.teatiers.domain.TeaType
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * The value must name a known [TeaType] (decision #141, PR-3) -- so an unknown type is REJECTED at ingest
 * rather than silently coerced to OTHER. null passes (no type observed; the importer defaults to OTHER).
 */
@MustBeDocumented
@Constraint(validatedBy = [KnownTeaTypeValidator::class])
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class KnownTeaType(
    val message: String = "must be a known tea type",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class KnownTeaTypeValidator : ConstraintValidator<KnownTeaType, String> {
    override fun isValid(value: String?, context: ConstraintValidatorContext?): Boolean =
        value == null || TeaType.entries.any { it.name == value.uppercase() }
}
