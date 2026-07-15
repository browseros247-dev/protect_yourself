package protect.yourself.features.blockerPage.utils

/**
 * BlockingValidator — pure-function validation utility for the unified
 * blocking-management page.
 *
 * Extracted from [protect.yourself.features.keywordManagerPage.KeywordManagerViewModel]
 * so that validation rules can be:
 *  - Unit-tested in isolation (no Android dependencies)
 *  - Reused by the UI layer for inline validation feedback BEFORE the
 *    ViewModel is invoked
 *  - Kept in sync between the UI pre-check and the ViewModel's authoritative
 *    check (single source of truth)
 *
 * Rules (mirror the reference v1.0.53 behaviour):
 *  - Min 2 characters (after trim)
 *  - Max 100 characters (prevents abuse / performance issues)
 *  - Not blank (after trim)
 *  - Not a duplicate of an existing entry (case-insensitive, trimmed)
 *
 * For package names, an additional rule:
 *  - Must look like a Java package name (lowercase letters, digits, dots,
 *    at least one dot, no spaces, no leading/trailing dot)
 *
 * For intent/class names:
 *  - Must be a valid Java identifier sequence (letters, digits, dots, $, _,
 *    no spaces, no leading dot)
 */
object BlockingValidator {

    /** Minimum length for any blocking entry (keyword / title / intent). */
    const val MIN_LENGTH = 2

    /** Maximum length for any blocking entry. */
    const val MAX_LENGTH = 100

    /**
     * Validate a free-text keyword (blocklist, whitelist, or setting title).
     *
     * @param input the raw user input
     * @param existing list of existing keyword strings (already in the target list)
     * @return [ValidationResult] indicating success or the specific failure
     */
    fun validateKeyword(
        input: String,
        existing: List<String>
    ): ValidationResult {
        val trimmed = input.trim()
        if (trimmed.isBlank()) {
            return ValidationResult.Blank
        }
        if (trimmed.length < MIN_LENGTH) {
            return ValidationResult.TooShort(MIN_LENGTH)
        }
        if (trimmed.length > MAX_LENGTH) {
            return ValidationResult.TooLong(MAX_LENGTH)
        }
        val lower = trimmed.lowercase()
        if (existing.any { it.trim().lowercase() == lower }) {
            return ValidationResult.Duplicate
        }
        return ValidationResult.Valid(trimmed)
    }

    /**
     * Validate a package-name entry. Package names must:
     *  - Contain at least one dot
     *  - Contain only lowercase letters, digits, dots, underscores
     *  - Not start or end with a dot
     *  - Not contain consecutive dots
     *  - Not contain spaces
     *  - Be between [MIN_LENGTH] and [MAX_LENGTH] characters
     *
     * Examples of valid package names:
     *  - com.example.app
     *  - com.tiktok.android
     *  - org.mozilla.firefox
     */
    fun validatePackageName(
        input: String,
        existing: List<String>
    ): ValidationResult {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return ValidationResult.Blank
        if (trimmed.length < MIN_LENGTH) return ValidationResult.TooShort(MIN_LENGTH)
        if (trimmed.length > MAX_LENGTH) return ValidationResult.TooLong(MAX_LENGTH)

        // Must contain at least one dot
        if (!trimmed.contains('.')) {
            return ValidationResult.InvalidFormat("Package name must contain at least one dot (e.g. com.example.app)")
        }
        // Must not contain spaces
        if (trimmed.contains(' ')) {
            return ValidationResult.InvalidFormat("Package name must not contain spaces")
        }
        // Must not start or end with a dot
        if (trimmed.startsWith('.') || trimmed.endsWith('.')) {
            return ValidationResult.InvalidFormat("Package name must not start or end with a dot")
        }
        // Must not contain consecutive dots
        if (trimmed.contains("..")) {
            return ValidationResult.InvalidFormat("Package name must not contain consecutive dots")
        }
        // Must match the allowed character set: lowercase letters, digits, dots, underscores
        val validChars = Regex("^[a-z0-9._]+$")
        if (!validChars.matches(trimmed)) {
            return ValidationResult.InvalidFormat(
                "Package name must contain only lowercase letters, digits, dots, and underscores"
            )
        }

        // Duplicate check (case-insensitive — though package names should be lowercase)
        val lower = trimmed.lowercase()
        if (existing.any { it.trim().lowercase() == lower }) {
            return ValidationResult.Duplicate
        }
        return ValidationResult.Valid(trimmed)
    }

    /**
     * Validate an intent/class name entry. Intent names must:
     *  - Not contain spaces
     *  - Contain only letters, digits, dots, dollar signs, underscores
     *  - Not start with a digit
     *  - Be between [MIN_LENGTH] and [MAX_LENGTH] characters
     *
     * Examples of valid intent/class names:
     *  - MainActivity
     *  - com.example.MainActivity
     *  - LoginActivity$Companion
     */
    fun validateIntentName(
        input: String,
        existing: List<String>
    ): ValidationResult {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return ValidationResult.Blank
        if (trimmed.length < MIN_LENGTH) return ValidationResult.TooShort(MIN_LENGTH)
        if (trimmed.length > MAX_LENGTH) return ValidationResult.TooLong(MAX_LENGTH)

        // Must not contain spaces
        if (trimmed.contains(' ')) {
            return ValidationResult.InvalidFormat("Intent/class name must not contain spaces")
        }
        // Must match allowed character set
        val validChars = Regex("^[A-Za-z0-9._$]+$")
        if (!validChars.matches(trimmed)) {
            return ValidationResult.InvalidFormat(
                "Intent/class name must contain only letters, digits, dots, underscores, or dollar signs"
            )
        }
        // Must not start with a digit
        if (trimmed.first().isDigit()) {
            return ValidationResult.InvalidFormat("Intent/class name must not start with a digit")
        }

        // Duplicate check
        val lower = trimmed.lowercase()
        if (existing.any { it.trim().lowercase() == lower }) {
            return ValidationResult.Duplicate
        }
        return ValidationResult.Valid(trimmed)
    }
}

/**
 * Result of validating a blocking entry. The UI uses this to render inline
 * error messages and decide whether to enable the "Add" button.
 */
sealed class ValidationResult {
    /** Input is valid. [normalized] is the trimmed, ready-to-store string. */
    data class Valid(val normalized: String) : ValidationResult()

    /** Input is blank or whitespace-only. */
    data object Blank : ValidationResult()

    /** Input is shorter than [minLength] characters. */
    data class TooShort(val minLength: Int) : ValidationResult()

    /** Input is longer than [maxLength] characters. */
    data class TooLong(val maxLength: Int) : ValidationResult()

    /** Input is a duplicate of an existing entry (case-insensitive). */
    data object Duplicate : ValidationResult()

    /** Input has an invalid format for the target list. [reason] explains why. */
    data class InvalidFormat(val reason: String) : ValidationResult()
}

/**
 * Convert a [ValidationResult] to a user-facing message.
 * Returns `null` for [ValidationResult.Valid] (no error to show).
 */
fun ValidationResult.toUserMessage(): String? = when (this) {
    is ValidationResult.Valid -> null
    is ValidationResult.Blank -> "Cannot be empty"
    is ValidationResult.TooShort -> "Must be at least $minLength characters"
    is ValidationResult.TooLong -> "Must be at most $maxLength characters"
    is ValidationResult.Duplicate -> "Already in the list"
    is ValidationResult.InvalidFormat -> reason
}
