package com.theleoborges.researchagent.models;

public class Models {


    // Base search request with query and number of results to retrieve
    public record SearchRequest(String query, int resultCount) {
    }

    public record AggregateRequest(String query) {
    }

    // Search result from Brave API
    public record SearchResults(
            String query,
            String searchContext
    ) {
    }

    public record AggregatedSearchResults(
            java.util.Set<String> queries,
            String searchContext
    ) {
    }

    // Analysis of a single search result
    public record Analysis(
            String query, String analysis
    ) {
    }

    public record ResearchResult(
            String query,
            String research
    ) {}

    public record WriteContentResult(
            String query, String content
    ) {}

    // Collection of analyses
    public record AnalysisReport(
            String query,
            String analysis
    ) {
    }

    // Final report ready for publishing
    public record FinalReport(
            String query,
            String detailedReport
    ) {}

    public record EmailResult(String recipient, String result){}

}