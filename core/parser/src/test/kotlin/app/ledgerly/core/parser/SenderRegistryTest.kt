package app.ledgerly.core.parser

import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.SourceEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SenderRegistryTest {
    private val reg = SenderRegistry()

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource("AX-HDFCBK,HDFCBK", "VM-ICICIB,ICICIB", "JD-SBIUPI-S,SBIUPI", "AD-AXISBK-T,AXISBK", "HDFCBK,HDFCBK", "+91 98450 12345,919845012345")
    fun `normalises DLT sender ids`(raw: String, expected: String) {
        assertEquals(expected, SenderRegistry.normalizeSmsSender(raw))
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "AX-HDFCBK-S,HDFC Bank", "VM-ICICIB,ICICI Bank", "JD-SBIUPI,State Bank of India", "AD-KOTAKB,Kotak Mahindra Bank",
        "VM-HDFCXY,HDFC Bank", "BP-AMEXIN,American Express",
    )
    fun `maps sms senders to institutions`(raw: String, inst: String) {
        assertEquals(inst, reg.lookupSms(raw))
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "com.google.android.apps.nbu.paisa.user,Google Pay", "com.phonepe.app,PhonePe", "net.one97.paytm,Paytm",
        "com.revolut.revolut,Revolut", "com.transferwise.android,Wise", "co.uk.getmondo,Monzo", "de.number26.android,N26",
        "com.csam.icici.bank.imobile,ICICI Bank", "com.sbi.lotusintouch,State Bank of India", "com.dreamplug.androidapp,CRED",
    )
    fun `maps packages`(pkg: String, inst: String) {
        assertEquals(inst, reg.lookupPackage(pkg))
    }

    @Test
    fun `unknown senders and packages are not allowed`() {
        assertFalse(reg.isAllowedSmsSender("AX-RANDOM"))
        assertFalse(reg.isAllowedSmsSender("+919845012345"))
        assertFalse(reg.isAllowedPackage("com.whatsapp"))
    }

    @Test
    fun `user added senders and packages`() {
        reg.addSender("AX-MYCOOP-S", "My Co-op Bank")
        reg.addPackage("com.example.bank", "Example Bank")
        assertEquals("My Co-op Bank", reg.lookupSms("VM-MYCOOP"))
        assertTrue(reg.resolve(SourceEvent("1", Channel.NOTIFICATION, null, "com.example.bank", null, "x", RECEIVED)).userAdded)
        reg.removeSender("MYCOOP")
        assertNull(reg.lookupSms("VM-MYCOOP"))
    }

    @Test
    fun `gmail notifications resolve institution from text`() {
        val m = reg.resolve(notif(Pkg.GMAIL, "HDFC Bank InstaAlerts", "Rs.899.00 has been debited"))
        assertTrue(m.allowed)
        assertTrue(m.aggregator)
        assertEquals("HDFC Bank", m.institution)
    }

    @Test
    fun `email senders map by domain`() {
        assertEquals("HDFC Bank", reg.lookupEmail("HDFC Bank <alerts@hdfcbank.net>"))
        assertEquals("ICICI Bank", reg.lookupEmail("credit_cards@icicibank.com"))
        assertEquals("American Express", reg.lookupEmail("AmericanExpress@welcome.aexp.com"))
        assertNull(reg.lookupEmail("someone@gmail.com"))
    }
}
