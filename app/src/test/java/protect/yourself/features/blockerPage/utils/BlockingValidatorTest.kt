package protect.yourself.features.blockerPage.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [BlockingValidator] — pure-function validation logic for the
 * unified blocking-management page.
 *
 * Covers:
 *  - Free-text keyword validation (blocklist, whitelist, setting titles)
 *  - Package name validation
 *  - Intent/class name validation
 *  - Edge cases: empty, blank, too short, too long, duplicates, invalid format
 *  - User-facing message conversion ([toUserMessage])
 */
class BlockingValidatorTest {

    // ===== Keyword validation =====

    @Test
    fun `keyword validation accepts valid input`() {
        val result = BlockingValidator.validateKeyword("porn", emptyList())
        assertThat(result).isEqualTo(ValidationResult.Valid("porn"))
    }

    @Test
    fun `keyword validation trims whitespace`() {
        val result = BlockingValidator.validateKeyword("  porn  ", emptyList())
        assertThat(result).isEqualTo(ValidationResult.Valid("porn"))
    }

    @Test
    fun `keyword validation rejects blank input`() {
        val result = BlockingValidator.validateKeyword("   ", emptyList())
        assertThat(result).isEqualTo(ValidationResult.Blank)
    }

    @Test
    fun `keyword validation rejects empty input`() {
        val result = BlockingValidator.validateKeyword("", emptyList())
        assertThat(result).isEqualTo(ValidationResult.Blank)
    }

    @Test
    fun `keyword validation rejects input shorter than 2 chars`() {
        val result = BlockingValidator.validateKeyword("a", emptyList())
        assertThat(result).isEqualTo(ValidationResult.TooShort(2))
    }

    @Test
    fun `keyword validation accepts input of exactly 2 chars`() {
        val result = BlockingValidator.validateKeyword("ab", emptyList())
        assertThat(result).isEqualTo(ValidationResult.Valid("ab"))
    }

    @Test
    fun `keyword validation rejects input longer than 100 chars`() {
        val long = "a".repeat(101)
        val result = BlockingValidator.validateKeyword(long, emptyList())
        assertThat(result).isEqualTo(ValidationResult.TooLong(100))
    }

    @Test
    fun `keyword validation accepts input of exactly 100 chars`() {
        val max = "a".repeat(100)
        val result = BlockingValidator.validateKeyword(max, emptyList())
        assertThat(result).isEqualTo(ValidationResult.Valid(max))
    }

    @Test
    fun `keyword validation rejects duplicate case-insensitive`() {
        val existing = listOf("Porn", "XXX")
        val result = BlockingValidator.validateKeyword("PORN", existing)
        assertThat(result).isEqualTo(ValidationResult.Duplicate)
    }

    @Test
    fun `keyword validation rejects duplicate with whitespace`() {
        val existing = listOf("porn")
        val result = BlockingValidator.validateKeyword("  porn  ", existing)
        assertThat(result).isEqualTo(ValidationResult.Duplicate)
    }

    @Test
    fun `keyword validation accepts non-duplicate with similar prefix`() {
        val existing = listOf("porn")
        val result = BlockingValidator.validateKeyword("pornhub", existing)
        assertThat(result).isEqualTo(ValidationResult.Valid("pornhub"))
    }

    // ===== Package name validation =====

    @Test
    fun `package validation accepts valid package name`() {
        val result = BlockingValidator.validatePackageName(
            "com.example.app",
            emptyList()
        )
        assertThat(result).isEqualTo(ValidationResult.Valid("com.example.app"))
    }

    @Test
    fun `package validation accepts package name with digits and underscores`() {
        val result = BlockingValidator.validatePackageName(
            "com.example.my_app2",
            emptyList()
        )
        assertThat(result).isEqualTo(ValidationResult.Valid("com.example.my_app2"))
    }

    @Test
    fun `package validation rejects input without dot`() {
        val result = BlockingValidator.validatePackageName("porn", emptyList())
        assertThat(result).isInstanceOf(ValidationResult.InvalidFormat::class.java)
        assertThat((result as ValidationResult.InvalidFormat).reason)
            .contains("at least one dot")
    }

    @Test
    fun `package validation rejects input with spaces`() {
        val result = BlockingValidator.validatePackageName(
            "com.example.my app",
            emptyList()
        )
        assertThat(result).isInstanceOf(ValidationResult.InvalidFormat::class.java)
        assertThat((result as ValidationResult.InvalidFormat).reason).contains("spaces")
    }

    @Test
    fun `package validation rejects input starting with dot`() {
        val result = BlockingValidator.validatePackageName(
            ".com.example.app",
            emptyList()
        )
        assertThat(result).isInstanceOf(ValidationResult.InvalidFormat::class.java)
        assertThat((result as ValidationResult.InvalidFormat).reason).contains("start or end with a dot")
    }

    @Test
    fun `package validation rejects input ending with dot`() {
        val result = BlockingValidator.validatePackageName(
            "com.example.app.",
            emptyList()
        )
        assertThat(result).isInstanceOf(ValidationResult.InvalidFormat::class.java)
        assertThat((result as ValidationResult.InvalidFormat).reason).contains("start or end with a dot")
    }

    @Test
    fun `package validation rejects consecutive dots`() {
        val result = BlockingValidator.validatePackageName(
            "com..example.app",
            emptyList()
        )
        assertThat(result).isInstanceOf(ValidationResult.InvalidFormat::class.java)
        assertThat((result as ValidationResult.InvalidFormat).reason).contains("consecutive dots")
    }

    @Test
    fun `package validation rejects uppercase letters`() {
        val result = BlockingValidator.validatePackageName(
            "Com.Example.App",
            emptyList()
        )
        assertThat(result).isInstanceOf(ValidationResult.InvalidFormat::class.java)
        assertThat((result as ValidationResult.InvalidFormat).reason).contains("lowercase")
    }

    @Test
    fun `package validation rejects special characters`() {
        val result = BlockingValidator.validatePackageName(
            "com.example.app@hack",
            emptyList()
        )
        assertThat(result).isInstanceOf(ValidationResult.InvalidFormat::class.java)
    }

    @Test
    fun `package validation rejects duplicate`() {
        val existing = listOf("com.example.app")
        val result = BlockingValidator.validatePackageName(
            "com.example.app",
            existing
        )
        assertThat(result).isEqualTo(ValidationResult.Duplicate)
    }

    @Test
    fun `package validation rejects uppercase duplicate via invalid format`() {
        // Package names must be lowercase, so an uppercase "duplicate" is
        // rejected as InvalidFormat (not Duplicate) before the duplicate
        // check runs.
        val existing = listOf("com.example.app")
        val result = BlockingValidator.validatePackageName(
            "COM.EXAMPLE.APP",
            existing
        )
        assertThat(result).isInstanceOf(ValidationResult.InvalidFormat::class.java)
    }

    @Test
    fun `package validation rejects blank input`() {
        val result = BlockingValidator.validatePackageName("   ", emptyList())
        assertThat(result).isEqualTo(ValidationResult.Blank)
    }

    @Test
    fun `package validation rejects too-short input`() {
        val result = BlockingValidator.validatePackageName("a.", emptyList())
        // a. is 2 chars but ends with a dot → invalid format, not TooShort
        // Actually let's test with "ab" (no dot) → InvalidFormat (no dot)
        val result2 = BlockingValidator.validatePackageName("ab", emptyList())
        assertThat(result).isInstanceOf(ValidationResult.InvalidFormat::class.java)
        assertThat(result2).isInstanceOf(ValidationResult.InvalidFormat::class.java)
    }

    // ===== Intent name validation =====

    @Test
    fun `intent validation accepts valid class name`() {
        val result = BlockingValidator.validateIntentName("MainActivity", emptyList())
        assertThat(result).isEqualTo(ValidationResult.Valid("MainActivity"))
    }

    @Test
    fun `intent validation accepts qualified class name`() {
        val result = BlockingValidator.validateIntentName(
            "com.example.MainActivity",
            emptyList()
        )
        assertThat(result).isEqualTo(ValidationResult.Valid("com.example.MainActivity"))
    }

    @Test
    fun `intent validation accepts name with dollar sign for inner class`() {
        val result = BlockingValidator.validateIntentName(
            "MainActivity\$Companion",
            emptyList()
        )
        assertThat(result).isEqualTo(ValidationResult.Valid("MainActivity\$Companion"))
    }

    @Test
    fun `intent validation accepts name with underscores`() {
        val result = BlockingValidator.validateIntentName(
            "My_LoginActivity",
            emptyList()
        )
        assertThat(result).isEqualTo(ValidationResult.Valid("My_LoginActivity"))
    }

    @Test
    fun `intent validation rejects input with spaces`() {
        val result = BlockingValidator.validateIntentName(
            "Main Activity",
            emptyList()
        )
        assertThat(result).isInstanceOf(ValidationResult.InvalidFormat::class.java)
        assertThat((result as ValidationResult.InvalidFormat).reason).contains("spaces")
    }

    @Test
    fun `intent validation rejects input starting with digit`() {
        val result = BlockingValidator.validateIntentName("1MainActivity", emptyList())
        assertThat(result).isInstanceOf(ValidationResult.InvalidFormat::class.java)
        assertThat((result as ValidationResult.InvalidFormat).reason).contains("digit")
    }

    @Test
    fun `intent validation rejects special characters`() {
        val result = BlockingValidator.validateIntentName(
            "MainActivity@hack",
            emptyList()
        )
        assertThat(result).isInstanceOf(ValidationResult.InvalidFormat::class.java)
    }

    @Test
    fun `intent validation rejects duplicate case-insensitive`() {
        val existing = listOf("MainActivity")
        val result = BlockingValidator.validateIntentName("mainactivity", existing)
        assertThat(result).isEqualTo(ValidationResult.Duplicate)
    }

    @Test
    fun `intent validation rejects blank input`() {
        val result = BlockingValidator.validateIntentName("   ", emptyList())
        assertThat(result).isEqualTo(ValidationResult.Blank)
    }

    @Test
    fun `intent validation rejects too-short input`() {
        val result = BlockingValidator.validateIntentName("a", emptyList())
        assertThat(result).isEqualTo(ValidationResult.TooShort(2))
    }

    @Test
    fun `intent validation rejects too-long input`() {
        val long = "M".repeat(101)
        val result = BlockingValidator.validateIntentName(long, emptyList())
        assertThat(result).isEqualTo(ValidationResult.TooLong(100))
    }

    // ===== toUserMessage =====

    @Test
    fun `toUserMessage returns null for Valid`() {
        val result = ValidationResult.Valid("porn").toUserMessage()
        assertThat(result).isNull()
    }

    @Test
    fun `toUserMessage returns Cannot be empty for Blank`() {
        val result = ValidationResult.Blank.toUserMessage()
        assertThat(result).isEqualTo("Cannot be empty")
    }

    @Test
    fun `toUserMessage returns min-length message for TooShort`() {
        val result = ValidationResult.TooShort(2).toUserMessage()
        assertThat(result).isEqualTo("Must be at least 2 characters")
    }

    @Test
    fun `toUserMessage returns max-length message for TooLong`() {
        val result = ValidationResult.TooLong(100).toUserMessage()
        assertThat(result).isEqualTo("Must be at most 100 characters")
    }

    @Test
    fun `toUserMessage returns Already in list for Duplicate`() {
        val result = ValidationResult.Duplicate.toUserMessage()
        assertThat(result).isEqualTo("Already in the list")
    }

    @Test
    fun `toUserMessage returns reason for InvalidFormat`() {
        val result = ValidationResult.InvalidFormat("Custom reason").toUserMessage()
        assertThat(result).isEqualTo("Custom reason")
    }

    // ===== Constants =====

    @Test
    fun `MIN_LENGTH is 2`() {
        assertThat(BlockingValidator.MIN_LENGTH).isEqualTo(2)
    }

    @Test
    fun `MAX_LENGTH is 100`() {
        assertThat(BlockingValidator.MAX_LENGTH).isEqualTo(100)
    }
}
