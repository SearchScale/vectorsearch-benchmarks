package com.searchscale.lucene.cuvs.benchmarks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;

public class QueryResult {

  @JsonProperty("codec")
  final String codec;
  @JsonProperty("query-id")
  final public int queryId;
  @JsonProperty("docs")
  final List<Integer> docs;
  @JsonProperty("ground-truth")
  final int[] groundTruth;
  @JsonProperty("scores")
  final List<Float> scores;
  @JsonProperty("latency")
  final double latencyMs;
  @JsonProperty("precision")
  double precision;
  @JsonProperty("recall")
  double recall;

  public QueryResult(String codec, int id, List<Integer> docs, int[] groundTruth, List<Float> scores,
      double latencyMs) {
    this.codec = codec;
    this.queryId = id;
    this.docs = docs;
    this.groundTruth = groundTruth;
    this.scores = scores;
    this.latencyMs = latencyMs;
    calculatePrecisionAndRecall();
  }

  private void calculatePrecisionAndRecall() {

    ArrayList<Integer> topKGroundtruthValues = new ArrayList<Integer>();
    for (int i = 0; i < docs.size(); i++) { // docs.size() is the topK value
      topKGroundtruthValues.add(groundTruth[i]);
    }

    int precisionMatches = 0;
    for (int g : groundTruth) {
      if (docs.contains(g)) {
        precisionMatches += 1;
      }
    }
    this.precision = ((float) precisionMatches / (float) docs.size()) * 100.0;

    Set<Integer> matchingRecallValues = docs.stream().distinct().filter(topKGroundtruthValues::contains)
        .collect(Collectors.toSet());

    this.recall = ((float) matchingRecallValues.size() / (float) topKGroundtruthValues.size()) * 100.0;
  }

  @Override
  public String toString() {
    try {
      return Util.newObjectMapper().writeValueAsString(this);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Problem with converting the result to a string", e);
    }
  }
}
