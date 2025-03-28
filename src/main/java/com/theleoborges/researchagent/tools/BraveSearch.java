package com.theleoborges.researchagent.tools;

import com.theleoborges.researchagent.config.AppConfig;
import com.theleoborges.researchagent.models.Models;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;


public class BraveSearch {



    static class Bucket4jRateLimiter {
        private final Bucket bucket;

        public Bucket4jRateLimiter() {
            // Create a bandwidth that allows one token per second
            Bandwidth bandwidth = Bandwidth.simple(1, Duration.ofSeconds(1));

            // Create a bucket with the specified bandwidth
            this.bucket = Bucket.builder()
                    .addLimit(bandwidth)
                    .build();
        }

        public boolean isRequestAllowed() {
            // Try to consume one token from the bucket
            return bucket.tryConsume(1);
        }

    }



    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Bucket4jRateLimiter rateLimiter;

    private static BraveSearch instance;

    public static BraveSearch getInstance(Duration timeout) {
        synchronized (BraveSearch.class) {
            if (instance == null) {
                instance = new BraveSearch(timeout);
            }
            return instance;
        }
    }

    private BraveSearch(Duration timeout, int maxThreads) {
        super();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(60))
                .readTimeout(timeout)
                .build();
        this.objectMapper = new ObjectMapper();
        this.rateLimiter = new Bucket4jRateLimiter();

    }

    private BraveSearch(Duration timeout) {
        this(timeout, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Searches using Brave Search API and fetches content from each result URL in parallel
     *
     * @param query The search query
     * @param count Number of search analysisReport to fetch
     * @return SearchResults with combined content from all URLs
     * @throws IOException If an I/O error occurs
     */
    public Models.SearchResults search(String query, int count) throws IOException {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://api.search.brave.com/res/v1/web/search?q=" + encodedQuery
                + "&count=" + count
                + "&summary=true";

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("X-Subscription-Token", AppConfig.getInstance().getBraveApiKey())
                .build();

        while (!rateLimiter.isRequestAllowed()) {
            System.err.println("Rate limit reached. Waiting...");
            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
//            ConsumptionProbe probe = rateLimiter.bucket.tryConsumeAndReturnRemaining(1);
//            long nanosToWaitForRefill = probe.getNanosToWaitForRefill();
//            System.err.println("Rate limit exceeded. Waiting " + nanosToWaitForRefill + " nanoseconds for refill.");
//            try {
//                TimeUnit.SECONDS.sleep(2);
//                boolean allowed = rateLimiter.isRequestAllowed(); // consume token after sleep
//                System.err.println("Rate limit exceeded. Allowed after wait: " + allowed);
//            } catch (InterruptedException e) {
//                throw new RuntimeException(e);
//            }
        }

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response code: " + response);
            }

            String responseBody = response.body().string();
            JsonNode rootNode = objectMapper.readTree(responseBody);
            JsonNode webNode = rootNode.path("web");
            JsonNode resultsNode = webNode.path("results");

            List<CompletableFuture<SingleResult>> futures = new ArrayList<>();

            // Create a future for each URL to fetch its content
            for (JsonNode resultNode : resultsNode) {
                String title = resultNode.path("title").asText();
                String resultUrl = resultNode.path("url").asText();
                String description = resultNode.path("description").asText();

                // Create a future that will fetch the content of this URL
                CompletableFuture<SingleResult> future = CompletableFuture.supplyAsync(
                        () -> fetchContent(title, description, resultUrl)
                );

                futures.add(future);

            }

            // Wait for all fetches to complete
            CompletableFuture
                    .allOf(futures.toArray(new CompletableFuture[0]))
                    .join();


            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0])
            );

            // Wait for all futures to complete and combine the content
            allFutures.join();

            String searchContext = futures.stream().map(future -> {
                try {
                    SingleResult result = future.get();
                    return """
                            # TITLE: %s
                            # URL: %s
                            # CONTENT:
                            %s
                            
                            ---
                            
                            
                            """.formatted(result.title(), result.url(), result.content());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).collect(Collectors.joining());

            return new Models.SearchResults(query, searchContext);
        }
    }

    record SingleResult(String title, String url, String content) {}

    private SingleResult fetchContent(String title, String description, String url) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return new SingleResult(title, url, "Failed to fetch content: " + response.code());
                }

                // filter out PDF files for now . Use the Brave short description instead.
                if (response.body().contentType().toString().contains("pdf")) { return new SingleResult(title, url, description); }
                ;
                Document document = Jsoup.parse(response.body().string());
                return new SingleResult(title, url, document.text());
            }
        } catch (Exception e) {
            return new SingleResult(title, url, "Error fetching content: " + e.getMessage());
        }
    }
}
