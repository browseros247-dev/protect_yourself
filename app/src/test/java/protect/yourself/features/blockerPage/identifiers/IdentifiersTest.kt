package protect.yourself.features.blockerPage.identifiers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for AccountabilityPartnerTypeIdentifiers + AppLockTypeIdentifiers +
 * VpnConnectionTypeIdentifiers + KeywordListTypeIdentifiers.
 */
class IdentifiersTest {

    // ===== AccountabilityPartnerTypeIdentifiers =====

    @Test
    fun `AccountabilityPartnerTypeIdentifiers has 4 types`() {
        assertThat(AccountabilityPartnerTypeIdentifiers.values()).asList().hasSize(4)
    }

    @Test
    fun `NONE has value 0`() {
        assertThat(AccountabilityPartnerTypeIdentifiers.NONE.value).isEqualTo(0L)
    }

    @Test
    fun `LONG_SENTENCE has value 1`() {
        assertThat(AccountabilityPartnerTypeIdentifiers.LONG_SENTENCE.value).isEqualTo(1L)
    }

    @Test
    fun `TIME_DELAY has value 2`() {
        assertThat(AccountabilityPartnerTypeIdentifiers.TIME_DELAY.value).isEqualTo(2L)
    }

    @Test
    fun `REAL_FRIEND has value 3`() {
        assertThat(AccountabilityPartnerTypeIdentifiers.REAL_FRIEND.value).isEqualTo(3L)
    }

    @Test
    fun `AccountabilityPartnerType fromString returns NONE for null or blank`() {
        assertThat(AccountabilityPartnerTypeIdentifiers.fromString(null))
            .isEqualTo(AccountabilityPartnerTypeIdentifiers.NONE)
        assertThat(AccountabilityPartnerTypeIdentifiers.fromString(""))
            .isEqualTo(AccountabilityPartnerTypeIdentifiers.NONE)
    }

    @Test
    fun `AccountabilityPartnerType fromString returns NONE for invalid`() {
        assertThat(AccountabilityPartnerTypeIdentifiers.fromString("invalid"))
            .isEqualTo(AccountabilityPartnerTypeIdentifiers.NONE)
    }

    @Test
    fun `AccountabilityPartnerType fromString round-trip`() {
        AccountabilityPartnerTypeIdentifiers.values().forEach { type ->
            val roundTrip = AccountabilityPartnerTypeIdentifiers.fromString(type.value.toString())
            assertThat(roundTrip).isEqualTo(type)
        }
    }

    // ===== AppLockTypeIdentifiers =====

    @Test
    fun `AppLockTypeIdentifiers has 4 types`() {
        assertThat(AppLockTypeIdentifiers.values()).asList().hasSize(4)
    }

    @Test
    fun `OFF has value 0`() {
        assertThat(AppLockTypeIdentifiers.OFF.value).isEqualTo(0L)
    }

    @Test
    fun `PIN has value 1`() {
        assertThat(AppLockTypeIdentifiers.PIN.value).isEqualTo(1L)
    }

    @Test
    fun `PASSWORD has value 2`() {
        assertThat(AppLockTypeIdentifiers.PASSWORD.value).isEqualTo(2L)
    }

    @Test
    fun `PATTERN has value 3`() {
        assertThat(AppLockTypeIdentifiers.PATTERN.value).isEqualTo(3L)
    }

    @Test
    fun `AppLockType fromString returns OFF for null`() {
        assertThat(AppLockTypeIdentifiers.fromString(null)).isEqualTo(AppLockTypeIdentifiers.OFF)
    }

    @Test
    fun `AppLockType fromString round-trip`() {
        AppLockTypeIdentifiers.values().forEach { type ->
            val roundTrip = AppLockTypeIdentifiers.fromString(type.value.toString())
            assertThat(roundTrip).isEqualTo(type)
        }
    }

    // ===== VpnConnectionTypeIdentifiers =====

    @Test
    fun `VpnConnectionTypeIdentifiers has 4 types`() {
        assertThat(VpnConnectionTypeIdentifiers.values()).asList().hasSize(4)
    }

    @Test
    fun `VpnConnectionType OFF has value 0`() {
        assertThat(VpnConnectionTypeIdentifiers.OFF.value).isEqualTo(0L)
    }

    @Test
    fun `VpnConnectionType NORMAL has value 1`() {
        assertThat(VpnConnectionTypeIdentifiers.NORMAL.value).isEqualTo(1L)
    }

    @Test
    fun `VpnConnectionType POWERFUL has value 2`() {
        assertThat(VpnConnectionTypeIdentifiers.POWERFUL.value).isEqualTo(2L)
    }

    @Test
    fun `VpnConnectionType CUSTOM has value 3`() {
        assertThat(VpnConnectionTypeIdentifiers.CUSTOM.value).isEqualTo(3L)
    }

    // ===== KeywordListTypeIdentifiers =====

    @Test
    fun `KeywordListTypeIdentifiers has 3 types`() {
        assertThat(KeywordListTypeIdentifiers.values()).asList().hasSize(3)
    }

    @Test
    fun `KeywordListType ALL has value 0`() {
        assertThat(KeywordListTypeIdentifiers.ALL.value).isEqualTo(0L)
    }

    @Test
    fun `KeywordListType KEYWORDS has value 1`() {
        assertThat(KeywordListTypeIdentifiers.KEYWORDS.value).isEqualTo(1L)
    }

    @Test
    fun `KeywordListType WEBSITE has value 2`() {
        assertThat(KeywordListTypeIdentifiers.WEBSITE.value).isEqualTo(2L)
    }

    @Test
    fun `KeywordListType fromString returns ALL for null`() {
        assertThat(KeywordListTypeIdentifiers.fromString(null)).isEqualTo(KeywordListTypeIdentifiers.ALL)
    }
}
