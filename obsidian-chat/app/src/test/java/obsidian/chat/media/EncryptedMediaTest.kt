package obsidian.chat.media

import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedMediaTest {
    private val photo = ByteArray(5000) { (it % 251).toByte() }
    private val downloadUrl = "https://upload.obsidianexampleonionaddressforunittestsonlyaaaaaaaaaaaaa.onion:5281/file_share/abc123/f.enc"

    @Test
    fun sealsAndOpensAPhoto() {
        val sealed = EncryptedMedia.encrypt(photo)
        assertTrue("the ciphertext must not be the photo", !sealed.ciphertext.contentEquals(photo))
        assertArrayEquals(photo, EncryptedMedia.decrypt(sealed.ciphertext, sealed.key, sealed.iv))
    }

    @Test
    fun theLinkCarriesTheKeyAndNothingElseDoes() {
        val sealed = EncryptedMedia.encrypt(photo)
        val link = EncryptedMedia.link(downloadUrl, sealed)
        assertTrue(link.startsWith("aesgcm://upload."))

        val parsed = requireNotNull(EncryptedMedia.parse(link))
        assertEquals(downloadUrl, parsed.downloadUrl)
        assertEquals("upload.obsidianexampleonionaddressforunittestsonlyaaaaaaaaaaaaa.onion", parsed.host)
        assertArrayEquals(sealed.key, parsed.key)
        assertArrayEquals(sealed.iv, parsed.iv)
        assertArrayEquals(photo, EncryptedMedia.decrypt(sealed.ciphertext, parsed.key, parsed.iv))
    }

    @Test(expected = AEADBadTagException::class)
    fun refusesAFileThatWasTamperedWith() {
        val sealed = EncryptedMedia.encrypt(photo)
        sealed.ciphertext[10] = (sealed.ciphertext[10] + 1).toByte()
        EncryptedMedia.decrypt(sealed.ciphertext, sealed.key, sealed.iv)
    }

    @Test
    fun ignoresAnythingThatIsNotSuchALink() {
        assertNull(EncryptedMedia.parse("hello"))
        assertNull(EncryptedMedia.parse(downloadUrl))
        assertNull(EncryptedMedia.parse("aesgcm://upload.example.onion/f.enc")) // no key
        assertNull(EncryptedMedia.parse("aesgcm://upload.example.onion/f.enc#zz"))
    }
}
