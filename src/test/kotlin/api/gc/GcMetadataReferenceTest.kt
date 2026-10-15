package api.gc

import gc.citadel.CitadelMatchHistory.CMsgClientToGCGetMatchMetaDataResponse
import gc.citadel.CitadelMatchHistory.CMsgClientToGCGetMatchMetaDataResponse.EResult
import kotlin.test.*

class GcMetadataReferenceTest {
    private fun response(result: EResult, salt: Int = 0, group: Int = 0) = CMsgClientToGCGetMatchMetaDataResponse.newBuilder()
        .setResult(result).apply { if (salt != 0) metadataSalt = salt; if (group != 0) replayGroupId = group }.build()

    @Test fun `GC results distinguish denial, invalid matches, processing and busy servers`() {
        mapOf(
            EResult.k_eResult_InvalidPermission to GcMetadataFailure.DENIED,
            EResult.k_eResult_InvalidMatch to GcMetadataFailure.INVALID_MATCH,
            EResult.k_eResult_MatchInFlight to GcMetadataFailure.IN_FLIGHT,
            EResult.k_eResult_TooBusy to GcMetadataFailure.BUSY,
            EResult.k_eResult_Timeout to GcMetadataFailure.BUSY,
            EResult.k_eResult_Success to GcMetadataFailure.NOT_READY,
        ).forEach { (result, failure) ->
            assertEquals(failure, assertFailsWith<GcMetadataException> { metadataReference(99, response(result)) }.failure, "$result")
        }
    }

    @Test fun `unsigned salts and replay groups survive conversion`() {
        assertEquals(GcMetadataDownload(99, 4294967294L, 4294967295L),
            metadataReference(99, response(EResult.k_eResult_Success, salt = -1, group = -2)))
    }
}
