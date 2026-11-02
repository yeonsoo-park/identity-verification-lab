package idvlab.verification.provider.mock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// 모의 기관 공통 응답 봉투: { response_code, message, result }
@JsonIgnoreProperties(ignoreUnknown = true)
public record MockIdpResponse(
        @JsonProperty("response_code") String responseCode,
        String message,
        Result result
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expire_dttm") String expireDttm,
            @JsonProperty("trx_key") String trxKey,
            @JsonProperty("client_request_id") String clientRequestId,
            @JsonProperty("composite_key") String compositeKey,
            @JsonProperty("standard_web_url") String standardWebUrl,
            String status,
            @JsonProperty("completed_dttm") String completedDttm,
            @JsonProperty("signed_data") String signedData,
            @JsonProperty("signer_cert") String signerCert,
            @JsonProperty("sign_alg") String signAlg
    ) {
    }
}
