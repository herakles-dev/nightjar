package dev.herakles.nightjar

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AcousticModemScreen`'s new protocol/symbol-rate selector doesn't take a single
 * fixed `CovertCarrier<PcmAudio>` anymore — it takes a
 * `carrierFactory: (NightjarAcoustics.Protocol, NightjarAcoustics.SymbolRate) -> CovertCarrier<PcmAudio>`
 * so a selection change can rebuild the carrier (`AcousticCarrier`'s `protocol`/`symbolRate` are
 * constructor-only vals with no setter). `MainActivity.kt` wires that factory as:
 * ```
 * carrierFactory = { protocol, symbolRate -> AcousticCarrier(protocol = protocol, symbolRate = symbolRate) }
 * ```
 *
 * This test exercises that exact lambda to prove the selected values actually reach the
 * constructed `AcousticCarrier`, rather than the closure silently ignoring its arguments and
 * always building an `AcousticCarrier()` default (AUDIBLE/NORMAL) — the specific no-op failure
 * mode this guards against.
 *
 * `AcousticCarrier`'s `protocol`/`symbolRate` are private constructor vals with no getters, so
 * there is no direct way to inspect what a constructed instance was configured with. This proves
 * it behaviorally instead: two carriers built by the SAME factory with DIFFERENT
 * (protocol, symbolRate) arguments must NOT interoperate — a payload encoded by one fails to
 * decode via the other, because the tone grid / symbol timing genuinely differ. If the factory
 * ignored its arguments, every pair below would be built identically and would round-trip
 * successfully instead of failing.
 */
class AcousticCarrierFactoryWiringTest {

    /** The exact lambda MainActivity.kt passes as AcousticModemScreen's carrierFactory param. */
    private val carrierFactory: (NightjarAcoustics.Protocol, NightjarAcoustics.SymbolRate) -> CovertCarrier<PcmAudio> =
        { protocol, symbolRate -> AcousticCarrier(protocol = protocol, symbolRate = symbolRate) }

    @Test
    fun `factory threads the selected protocol into the constructed carrier`() {
        val audibleCarrier = carrierFactory(NightjarAcoustics.Protocol.AUDIBLE, NightjarAcoustics.SymbolRate.NORMAL)
        val ultrasonicCarrier = carrierFactory(NightjarAcoustics.Protocol.NEAR_ULTRASONIC, NightjarAcoustics.SymbolRate.NORMAL)

        val pcm = audibleCarrier.encode("nightjar".encodeToByteArray())

        // Decoding AUDIBLE-encoded audio with a NEAR_ULTRASONIC-configured carrier must fail: the
        // two protocols occupy different tone-grid bins entirely. If carrierFactory had silently
        // defaulted both carriers to AUDIBLE regardless of the argument passed in, they'd be
        // identical and this decode would succeed instead.
        val result = ultrasonicCarrier.decode(pcm)
        assertTrue("expected mismatched-protocol decode to fail, got $result", result is DecodeResult.Failure)
    }

    @Test
    fun `factory threads the selected symbol rate into the constructed carrier`() {
        val normalCarrier = carrierFactory(NightjarAcoustics.Protocol.AUDIBLE, NightjarAcoustics.SymbolRate.NORMAL)
        val fastCarrier = carrierFactory(NightjarAcoustics.Protocol.AUDIBLE, NightjarAcoustics.SymbolRate.FAST)

        val pcm = normalCarrier.encode("nightjar".encodeToByteArray())

        // Decoding NORMAL-encoded audio with a FAST-configured carrier must fail: FAST expects a
        // shorter per-symbol frame count, so it samples the wrong offsets within the NORMAL-paced
        // stream. If carrierFactory had silently ignored the symbolRate argument, both carriers
        // would be identical NORMAL instances and this decode would succeed instead.
        val result = fastCarrier.decode(pcm)
        assertTrue("expected mismatched-symbol-rate decode to fail, got $result", result is DecodeResult.Failure)
    }

    @Test
    fun `factory builds a distinct, self-consistent carrier per selector combination`() {
        for (protocol in NightjarAcoustics.Protocol.entries) {
            for (symbolRate in NightjarAcoustics.SymbolRate.entries) {
                val carrier = carrierFactory(protocol, symbolRate)
                val payload = "nightjar-$protocol-$symbolRate".encodeToByteArray()
                val result = carrier.decode(carrier.encode(payload))
                assertTrue(
                    "carrier built for ($protocol, $symbolRate) failed to round-trip its own encoding: $result",
                    result is DecodeResult.Success,
                )
                val success = result as DecodeResult.Success
                assertTrue(
                    "carrier built for ($protocol, $symbolRate) round-tripped the wrong bytes",
                    success.payload.contentEquals(payload),
                )
            }
        }
    }
}
