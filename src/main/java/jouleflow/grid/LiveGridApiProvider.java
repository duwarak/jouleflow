package jouleflow.grid;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real integration point for the <a href="https://www.electricitymaps.com/">Electricity Maps</a>
 * API. This is where JouleFlow gets <em>live</em> grid carbon intensity when given a real API key.
 *
 * <p>Endpoints used:
 * <ul>
 *   <li>{@code GET /v3/carbon-intensity/latest?zone=REGION} — current intensity.</li>
 *   <li>{@code GET /v3/carbon-intensity/forecast?zone=REGION} — hourly forecast.</li>
 * </ul>
 * Authentication is the {@code auth-token} request header. The single {@code carbonIntensity}
 * field is pulled out with a small regex rather than pulling in a heavy JSON dependency for one
 * number. With a valid key and normal internet access, this should return real data.
 */
public final class LiveGridApiProvider implements CarbonIntensityProvider {

    private static final String BASE = "https://api.electricitymap.org/v3";
    private static final Pattern INTENSITY =
            Pattern.compile("\"carbonIntensity\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern DATETIME =
            Pattern.compile("\"datetime\"\\s*:\\s*\"([^\"]+)\"");

    private final String apiKey;
    private final HttpClient http;

    public LiveGridApiProvider(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Electricity Maps API key is required");
        }
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String dataSourceLabel() {
        return "Electricity Maps API (live)";
    }

    @Override
    public boolean isLiveData() {
        return true;
    }

    @Override
    public CarbonIntensitySample current(String region) {
        String body = get("/carbon-intensity/latest?zone=" + region);
        Matcher m = INTENSITY.matcher(body);
        if (!m.find()) {
            throw new IllegalStateException("No carbonIntensity in response for " + region + ": " + body);
        }
        double intensity = Double.parseDouble(m.group(1));
        Matcher dt = DATETIME.matcher(body);
        Instant when = dt.find() ? Instant.parse(dt.group(1)) : Instant.now();
        return new CarbonIntensitySample(region, when, intensity);
    }

    @Override
    public List<CarbonIntensitySample> forecast(String region, int hours) {
        String body = get("/carbon-intensity/forecast?zone=" + region);
        Matcher mi = INTENSITY.matcher(body);
        Matcher md = DATETIME.matcher(body);
        List<CarbonIntensitySample> out = new ArrayList<>();
        while (out.size() < hours && mi.find()) {
            double intensity = Double.parseDouble(mi.group(1));
            Instant when = md.find() ? Instant.parse(md.group(1)) : Instant.now();
            out.add(new CarbonIntensitySample(region, when, intensity));
        }
        return out;
    }

    private String get(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .header("auth-token", apiKey)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "Electricity Maps API returned HTTP " + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Electricity Maps API call failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Electricity Maps API call interrupted", e);
        }
    }
}
