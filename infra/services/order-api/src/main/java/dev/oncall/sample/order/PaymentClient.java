package dev.oncall.sample.order;

import dev.oncall.sample.order.Model.PaymentRequest;
import dev.oncall.sample.order.Model.PaymentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/** payment-api 호출. 호출은 toxiproxy를 거치므로 네트워크 지연을 주입할 수 있다. */
@Component
class PaymentClient {

    private final RestClient client;

    PaymentClient(RestClient.Builder builder, @Value("${app.payment-base-url}") String baseUrl) {
        // HttpURLConnection(SimpleClientHttpRequestFactory)은 연결이 끊기면 POST를 몰래 한 번 더 보낸다.
        // 결제 중복 호출을 막으려고 재시도가 없는 JDK HttpClient를 쓴다.
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    String charge(long orderId, long amount) {
        PaymentResponse response = client.post()
                .uri("/payments")
                .body(new PaymentRequest(orderId, amount))
                .retrieve()
                .body(PaymentResponse.class);
        return response == null ? "UNKNOWN" : response.status();
    }
}
