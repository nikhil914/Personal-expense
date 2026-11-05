package app.ledgerly.core.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MerchantNormalizerTest {
    private val n = MerchantNormalizer()

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        delimiter = ';',
        value = [
            "AMZN MKTP IN*2X3AB;Amazon;Shopping/Online",
            "SWIGGY*ORDER;Swiggy;Food/Delivery",
            "zomato@hdfcbank;Zomato;Food/Delivery",
            "POS 1234 STARBUCKS MUMBAI;Starbucks;Food/Dining",
            "UPI/412312345678/SWIGGY;Swiggy;Food/Delivery",
            "RAZ*BUNDL TECHNOLOGIES;Swiggy;Food/Delivery",
            "NETFLIX.COM;Netflix;Entertainment/Streaming",
            "AMAZON WEB SERVICES;Amazon Web Services;Software/Cloud",
            "UBER INDIA SYSTEMS PVT LTD;Uber;Transport/Ride",
            "UBER EATS;Uber Eats;Food/Delivery",
            "paytm-blinkit@paytm;Blinkit;Food/Groceries",
            "netflix@icici NETFLIX;Netflix;Entertainment/Streaming",
            "IRCTC WEB UPS;IRCTC;Travel/Rail",
            "Lidl;Lidl;Food/Groceries",
            "TESCO STORES 2231;Tesco;Food/Groceries",
            "AIRTEL PREPAID RECHARGE;Airtel;Bills/Telecom",
            "SPOTIFY;Spotify;Entertainment/Streaming",
            "JIO PREPAID RECHARGE;Jio;Bills/Telecom",
            "Pret A Manger;Pret A Manger;Food/Dining",
            "BOOKMYSHOW;BookMyShow;Entertainment/Movies",
        ],
    )
    fun `normalises known merchants`(raw: String, name: String, category: String) {
        val m = n.normalize(raw)!!
        assertEquals(name, m.name)
        assertEquals(category, m.categoryHint)
        assertTrue(m.aliased)
    }

    @Test
    fun `cleans unknown merchants`() {
        assertEquals("Gupta General Store", n.normalize("POS 4521 GUPTA GENERAL STORE MUMBAI")?.name)
        assertEquals("Acme", n.normalize("NEFT Cr-CITI0000001-ACME TECHNOLOGIES PVT LTD-SALARY OCT 2026")?.name)
        assertFalse(n.normalize("SHARMA SWEETS BANGALORE IN")!!.aliased)
        assertEquals("Sharma Sweets", n.normalize("SHARMA SWEETS BANGALORE IN")?.name)
    }

    @Test
    fun `person vpa is a p2p transfer`() {
        val m = n.normalize("ravi.k@okaxis")!!
        assertEquals("Ravi", m.name)
        assertTrue(m.isPerson)
        assertEquals("Transfers/P2P", m.categoryHint)
    }

    @Test
    fun `user aliases win`() {
        n.addAlias("GUPTA GENERAL", "Gupta Kirana", "Food/Groceries")
        val m = n.normalize("POS 4521 GUPTA GENERAL STORE")!!
        assertEquals("Gupta Kirana", m.name)
        assertEquals("Food/Groceries", m.categoryHint)
    }

    @Test
    fun `alias table is large`() {
        assertTrue(MerchantNormalizer.DEFAULT_ALIASES.size >= 80, "${MerchantNormalizer.DEFAULT_ALIASES.size}")
        assertEquals(MerchantNormalizer.DEFAULT_ALIASES.size, MerchantNormalizer.DEFAULT_ALIASES.map { it.name }.toSet().size)
    }

    @Test
    fun `blank input`() {
        assertNull(n.normalize(null))
        assertNull(n.normalize("   "))
    }
}
