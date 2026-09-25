package obsidian.chat.update

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The update information is the one thing a phone reads before deciding to replace its own
 * operating system, and it arrives from a web server that may have been taken over. These check
 * that the signature decides what is installed, not whoever is answering.
 */
class UpdateMetadataTest {
    private val json = """
        {
          "device": "shiba",
          "model": "Pixel 8",
          "version": "2026.10.01",
          "full": {"file": "updates/full.zip", "size": 2000000000, "sha256": "AABB"},
          "incremental": {
            "2026.09.25": {"file": "updates/small.zip", "size": 40000000, "sha256": "CCDD"}
          }
        }
    """.trimIndent().toByteArray()

    @Test
    fun `accepts information signed by the release key`() {
        val metadata = UpdateMetadata.verifyAndParse(json, sign(json, TEST_KEY), TEST_CERT)

        assertEquals("shiba", metadata.device)
        assertEquals("2026.10.01", metadata.version)
        assertEquals(2_000_000_000L, metadata.full.size)
        assertEquals("checksums are compared in one case", "aabb", metadata.full.sha256)
    }

    @Test
    fun `refuses information signed by some other key`() {
        // Signed with a key that is not the one built into the phone.
        val theirs = sign(json, OTHER_KEY)

        expectRefusal("not signed by OBSIDIAN") { UpdateMetadata.verifyAndParse(json, theirs, TEST_CERT) }
    }

    @Test
    fun `refuses information that was changed after it was signed`() {
        val signature = sign(json, TEST_KEY)
        // A server pointing phones at a file of its own choosing, keeping the real signature.
        val tampered = String(json).replace("updates/full.zip", "updates/theirs.zip").toByteArray()

        expectRefusal("not signed by OBSIDIAN") { UpdateMetadata.verifyAndParse(tampered, signature, TEST_CERT) }
    }

    @Test
    fun `refuses to judge anything when the build carries no certificate`() {
        expectRefusal("no update certificate") { UpdateMetadata.verifyAndParse(json, sign(json, TEST_KEY), "") }
    }

    @Test
    fun `takes the small update only when it starts from the installed version`() {
        val metadata = UpdateMetadata.verifyAndParse(json, sign(json, TEST_KEY), TEST_CERT)

        assertEquals("updates/small.zip", metadata.packageFor("2026.09.25").file)
        assertEquals("updates/full.zip", metadata.packageFor("2026.09.20").file)
        assertEquals("updates/full.zip", metadata.packageFor("unknown").file)
    }

    private fun expectRefusal(contains: String, block: () -> Unit) {
        try {
            block()
            fail("expected this to be refused")
        } catch (refused: IllegalArgumentException) {
            assertTrue(
                "wrong reason: ${refused.message}",
                refused.message!!.contains(contains),
            )
        }
    }

    private fun sign(bytes: ByteArray, privateKeyPem: String): ByteArray {
        val der = Base64.getMimeDecoder().decode(
            privateKeyPem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
        )
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        return Signature.getInstance("SHA256withRSA").apply {
            initSign(key)
            update(bytes)
        }.sign()
    }

    private companion object {
        // Throwaway keys, made for these tests alone and never used to sign anything real. Fixed
        // rather than generated so the tests do not spend a second making RSA keys each run.
        const val TEST_CERT = """-----BEGIN CERTIFICATE-----
MIIDEzCCAfugAwIBAgIURx91o9mJDws+o3pbN2rrjUL/QPYwDQYJKoZIhvcNAQEL
BQAwGDEWMBQGA1UEAwwNT0JTSURJQU4gVGVzdDAgFw0yNjA5MjUwODEwMTVaGA8y
MTI2MDkwMTA4MTAxNVowGDEWMBQGA1UEAwwNT0JTSURJQU4gVGVzdDCCASIwDQYJ
KoZIhvcNAQEBBQADggEPADCCAQoCggEBAMEqjqiYy61sOce6jcz51/62SB8mFREw
FaLn98sddEzAv2paFHDbLAeUrDIvN4IclTLTNbvIbnbtTbJq8dogjvXdEMhXqXPg
rPRh5kgg6zg6D72Pe33b9fgmMCdCrpM+/KTxHwXR/Vb0pzemhppF3DRcA01JKRY2
udjE+0+VEsxEfchclvtEyhEBi9dtaDrfwiPYJ/M3YRlWFijcyYaTmfgIIQ6fRok7
xhuxnmfZd/LMSbDoD4T81+vUQFhMWRjBbvjDd6xlv7GuKX52ITSQJapq3I0mKaSC
SGZ3pYeFgUgoB4j/yDPNKkWsPtbXIi4+fQN70wK2ufLP33CVFwLVVbkCAwEAAaNT
MFEwHQYDVR0OBBYEFIizc7dt+GyC7NQCRpdWzGqeAnBcMB8GA1UdIwQYMBaAFIiz
c7dt+GyC7NQCRpdWzGqeAnBcMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQEL
BQADggEBAAciolBQtvnytjxi5faQ/Y9LOFNLd+JVwoPAF7aiFw/Z7VAw6uKQ7lMD
v/oZz0flDny8Uqi0qghTJp03w9+OaXP74SZBf8R/58ZYUss94saSPvwGCSP40Tpw
XJ158lulcWbdRr7bnaIpYNoE/LxBGLPcz7/8xwlQFbmuAj+4ziyzASssUhEM5jzq
Wab4Km/8O3v1VKcMU2VbUABTT+Skd2ohU0TI7GY3KGIkazNQzAtrhKCCQ8n36EKw
H++RCMR9cIb6VMsrj+NCyLWTzcbSsNPi2iK/5yLjv8IYbEXXwqfell5zhmMPgTdF
alLK9QmR9ztmBuf/nHhW0L76ARY9bQI=
-----END CERTIFICATE-----"""

        const val TEST_KEY = """-----BEGIN PRIVATE KEY-----
MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDBKo6omMutbDnH
uo3M+df+tkgfJhURMBWi5/fLHXRMwL9qWhRw2ywHlKwyLzeCHJUy0zW7yG527U2y
avHaII713RDIV6lz4Kz0YeZIIOs4Og+9j3t92/X4JjAnQq6TPvyk8R8F0f1W9Kc3
poaaRdw0XANNSSkWNrnYxPtPlRLMRH3IXJb7RMoRAYvXbWg638Ij2CfzN2EZVhYo
3MmGk5n4CCEOn0aJO8YbsZ5n2XfyzEmw6A+E/Nfr1EBYTFkYwW74w3esZb+xril+
diE0kCWqatyNJimkgkhmd6WHhYFIKAeI/8gzzSpFrD7W1yIuPn0De9MCtrnyz99w
lRcC1VW5AgMBAAECggEABIuh/Wv8XabZJI7fwliIvCOBm/YIneBsnz+Sev2aRyoW
oa6KokM4ty7zmbS3ENaSWOFTzN5BDIcLV9egvE9y0xdHdfzAwzYgwRltUqTaVCGN
ou91lPca7ngblE5DObohi3y/DE3Y28sjBNy5eHeMtdC1JS7uYBRGmEgTr9eOsIQg
8v4qag1E7MV8foaPS7cYNq9ecVmHiAxt1MU8ei9V/ueyZ+Tq/Kh8tCrVT/pOWiba
5Di+2O3wg09PB5ou0bZs9k/kS3M6bief+q//CdKqdt22aFxM3trUX8vd+u4YqI8d
MXR9u2dznrd4zbx4cA3k7XvhbkJB5cWY6MUdJLfzdwKBgQDf3ecpPPX8wAF0SFil
pAO0w8fRnxlDqVL0cYaOi+GJOhSOuAY9LWsPPBXGxjiUTPh6+xQ+Q0Njri6/1Y78
ppV6tWOubGvOuVX9V5zDilmhUPFtRa1ztx+an0vwWtr77BjsoUgBzFKvnTG0kBRR
RN7BoSPKG5QEmaU0tKUi+cLe5wKBgQDc5Ivv6CMXrYFAXYVkq6fNOZ7+QcBmYbcR
eGlcvulCFklz6t2A8v2OrQAN8d6yfbQ0dKZLUiN0a7anggNCn2TO0X6d5Q9Ky65j
KBkgt64u4bsuQBPh61Ujs0QXbMjpdakqsP194SddRps2DVWyh4tyx39dA6D0CUQe
LoBVHjiyXwKBgQDGJuuKo+v0OZvP6o2UqDWjljvTPukk68aCmc4JdKVASwmRPjA/
jGkcBgocdK32Dp9Oc9l6Nx16KN42bPhwh57+C3pdFJjkVUZYxWj76ATn9faMy0T5
jNjLiz6zVChLHrL9GNDczpdN3Gz3ryRcvwIRD+nW5kxBOiJyIYeeCsdDdwKBgQDB
o4xU3A/fUtdKgHFOZfgsvQV26FbsqV5db0wV5LoAmB7+Afb5con6SAgTMWPC3tVZ
YqTgxHmWNlKhlySag7ZQY2/2pHV6YQFXpTAZ8Us9h96Z1cxYMP+q0xyu2Etr9Rw9
83fUoMrsRtgLUzc70Pzauq4Y8PRo2bKgLq2LcHnNSwKBgB7bHJOlKMOEi3Tf5lXC
u8z10TikmEsuCj3rrUenVwQ4m91dBwIgg79i9IzLdX8wZr/5S0QG2+SUm2s0hrFe
Ck5yr4wPMsz5XGbK/FNkyx/Mb5oVKfWTRiDyy3feeU2NY8rkugXZ94WXv8FWQcaB
oK+Majt7o+VqcLbiphc6cZje
-----END PRIVATE KEY-----"""

        // A different key entirely, standing in for anyone who is not us.
        const val OTHER_KEY = """-----BEGIN PRIVATE KEY-----
MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQClXQD3OrPGQgHw
i3LH+dSC8CUnZZkOp0dMYlLRlGQMxL14QfjIMpB98whnrAgicergEsPSn5SMq14r
ANpM5wX/e3UqO2hQMpCYX7yunL34Bs7wx9y/la4ORt82UnJa9UU8BDUMZ/Ny9GJe
JD9YmnVXnagBAk1WisyXc7QBGUH25TjLuJUhEOV/cZ6IjhO43zLpi6rH1xw4ztxd
5YNkEdcHmer02mFid/MMWNCXrNZQsKg1rPbWnKc0EqSa6jtE6fgzs/FkXpWKglMB
c6X6iBOE+Hnv+3drmjftXFhpYFmtMP0070Cs2m+GnQ6s9PeXx2IDUQJ+5hAQ5D8D
qMPF07UHAgMBAAECggEAAhoRhhLv8AgswCL1nYcWZWeqATswW9hSdq0VQmaxctwZ
UGr+P4UtAvzQ0YiFPfLWumxios22dKdE9iT3oVyaXenyzkudAs/xuI4W9t01KO7l
OHhB3QmJAdNyyNz5aSCO9blT90lm9cIXcfzh9T1ZWni9hu3Bj+UJvq8QzabFiN7R
usgh153YspodBqhTHzeOKeDnGPQ0QcxjHiXZLfUP4/fDITzr2UL2zJN/M1xPsmUr
v2IaR1SuBUUDHG5+McicjGDwR1oskR20I0ulTUwdq+vM9bvUUNqjlqAoVPUcycR/
aU2Jse2FDF63GC1jDNte0iaLQKX2Md0lE4/rtHu60QKBgQDnXJt07iKtnKCelcuE
lrTp58DodJub5Somjr7+cSqYEnMp7XWV8qqek9PGBAEASKzinAScLu83FGSE4aQ7
0pvec3oJSNGyPv+Dju3D2D2eP0VJtim2Amgwjo/iBxorwBJgtwOxcjlvHcEUOvvE
OIFNxorgRASkKNj2JpIajKP2GQKBgQC2+STspVmvS/qaS3fyiiO25TDMNBmhhE9M
2P7GKTmnvtyowBQ/2x2FyKPXmuPATTP6Shtlld2UZ0a/FoOTzGkLAJ1G+yRsRZa1
Uz4X7tbqCynaAt+R34dUHqVyC8ej+aHIgBZHLB5ljYDeNFxlZA/IKyT4/VmiDILa
ApUyKk0oHwKBgDtdV8Y5FEzX057dcMskoGckQCtlQEhQFPCvz/IZt4Qtt78aXwGK
spzd8YjtnhQkkbfTsJsir97CLMir3Sg8ciIyHy4isSu7XnSzR+7onWbtdSnkw+8f
zvwmmGS2zdBMCGsoipoNZQ9N4yz9tXwzw3nhZ+EDku1MTd9bJkIJtEiBAoGAaP1a
76MNbCW3vXNSd0xGo/qs3m+CyYgnDJxyzHf5SkSdTwMwW1e6f/qZ8OxChSSHj7WY
NNkilLYSBTHA/DzNhW+rz3/p7WqcyJzkQ01b6l3PfPYrHQPbHiybmG4j9vYVm/vL
TLmHE5R3foki/1bEK4J+K5UMKoztRHU48NkO/tcCgYB7vvM6baI58Et+dT0pCq73
RyluccM6gWbVj8d0Xm7EfukziI4pHTAxMgRjOoYKPfisypy/Hjfh7dStEgQgxKlt
J1nGrQb3RJbF4vpwa69eTBYtjpNXoxsTHfV35YNQaCiUIdriFIOqmAULqS4Sil/l
jcjbzAhJW6W/bmYx9dus2Q==
-----END PRIVATE KEY-----"""
    }
}
