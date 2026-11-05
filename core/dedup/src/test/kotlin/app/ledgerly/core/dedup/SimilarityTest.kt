package app.ledgerly.core.dedup

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimilarityTest {
    @Test fun `jaro winkler reference values`() {
        assertEquals(0.961, Similarity.jaroWinkler("MARTHA", "MARHTA"), 0.001)
        assertEquals(0.840, Similarity.jaroWinkler("DWAYNE", "DUANE"), 0.001)
        assertEquals(0.813, Similarity.jaroWinkler("DIXON", "DICKSONX"), 0.001)
        assertEquals(1.0, Similarity.jaroWinkler("ABC", "ABC"))
        assertEquals(0.0, Similarity.jaroWinkler("", "ABC"))
        assertEquals(0.0, Similarity.jaroWinkler("AAA", "ZZZ"))
    }

    @Test fun `merchant normalisation strips noise`() {
        assertEquals("SWIGGY", Similarity.normaliseMerchant("SWIGGY*BANGALORE IN"))
        assertEquals("AMAZON", Similarity.normaliseMerchant("WWW.AMAZON.IN"))
        assertEquals("ZOMATO", Similarity.normaliseMerchant("Zomato Pvt Ltd"))
        assertEquals("", Similarity.normaliseMerchant(null))
    }

    @Test fun `merchant similarity across channel spellings`() {
        assertTrue(Similarity.merchant("SWIGGY BANGALORE", "Swiggy") >= 0.9)
        assertTrue(Similarity.merchant("AMAZON PAY INDIA PVT LTD", "Amazon.in") >= 0.9)
        assertTrue(Similarity.merchant("STARBUCKS COFFEE 0123", "Starbucks") >= 0.9)
        assertTrue(Similarity.merchant("SWIGGYINSTAMART", "Swiggy Instamart") >= 0.9)
        assertTrue(Similarity.merchant("UBER INDIA", "Zomato") < 0.6)
        assertEquals(0.0, Similarity.merchant(null, "X"))
    }

    @Test fun `reference normalisation`() {
        assertEquals("612345678901", Similarity.normaliseReference(" 0612-3456-78901 "))
        assertNull(Similarity.normaliseReference("12"))
        assertNull(Similarity.normaliseReference(null))
    }
}
