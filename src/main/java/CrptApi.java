import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class CrptApi {
    private static final String API_URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 30000;

    private final TimeUnit timeUnit;
    private final int requestLimit;
    private final Lock lock;
    private final Condition condition;
    private final List<Instant> requestTimes;

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this.timeUnit = timeUnit;
        this.requestLimit = requestLimit;
        this.lock = new ReentrantLock();
        this.condition = lock.newCondition();
        this.requestTimes = new ArrayList<>();
    }

    public void createDocument(ObjectNode document, String signature) throws InterruptedException {
        lock.lock();
        try {
            trimExpiredRequests();
            while (requestTimes.size() >= requestLimit) {
                boolean rsp = condition.await(getTimeUntilNextAllowedRequest(), timeUnit);
            }
            sendRequest(document, signature);
            requestTimes.add(Instant.now());
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }


    private void sendRequest(ObjectNode document, String signature) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        byte[] requestBody = objectMapper.writeValueAsBytes(document);

        URL url = new URL(API_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("X-Signature", signature);
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setDoOutput(true);
        connection.getOutputStream().write(requestBody);

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("API request failed with response code: " + responseCode);
        }
    }

    private void trimExpiredRequests() {
        Instant now = Instant.now();
        requestTimes.removeIf(t -> now.minus(getDuration()).isAfter(t));
    }

    private long getTimeUntilNextAllowedRequest() {
        Instant now = Instant.now();
        Instant nextAllowedRequest = requestTimes.getFirst().plus(getDuration());
        return Duration.between(now, nextAllowedRequest).toMillis();
    }

    private Duration getDuration() {
        return switch (timeUnit) {
            case SECONDS -> Duration.ofSeconds(1);
            case MINUTES -> Duration.ofMinutes(1);
            case HOURS -> Duration.ofHours(1);
            default -> throw new IllegalArgumentException("Unsupported TimeUnit: " + timeUnit);
        };
    }
}
