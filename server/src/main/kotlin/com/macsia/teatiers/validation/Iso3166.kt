package com.macsia.teatiers.validation

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import java.util.Locale
import kotlin.reflect.KClass

/**
 * The value must be an ISO 3166-1 alpha-2 country code (decision #138 / #141, PR-3) -- canonical uppercase,
 * e.g. "CN", "JP". null passes (the field is optional). Stops free-text countries from reaching claims.
 */
@MustBeDocumented
@Constraint(validatedBy = [Iso3166Validator::class])
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Iso3166(
    val message: String = "must be an ISO 3166-1 alpha-2 country code (uppercase)",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class Iso3166Validator : ConstraintValidator<Iso3166, String> {
    override fun isValid(value: String?, context: ConstraintValidatorContext?): Boolean =
        value == null || value in ISO_COUNTRIES

    private companion object {
        /** The JDK's ISO 3166-1 alpha-2 set (uppercase); canonical form is required, so "cn" is invalid. */
        val ISO_COUNTRIES: Set<String> = Locale.getISOCountries().toSet()
    }
}
