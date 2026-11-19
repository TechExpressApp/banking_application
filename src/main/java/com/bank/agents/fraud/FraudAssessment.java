package com.bank.agents.fraud;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** Structured output the fraud monitor asks Claude to return. */
public record FraudAssessment(
        @JsonPropertyDescription("One of LOW, MEDIUM, HIGH") String riskLevel,
        @JsonPropertyDescription("0 (clearly fine) to 100 (almost certainly fraud)") int riskScore,
        @JsonPropertyDescription("Short, specific reasons based on the data provided") List<String> reasons,
        @JsonPropertyDescription("One of NONE, MONITOR, CONTACT_CUSTOMER, FREEZE_AND_INVESTIGATE") String recommendedAction) {
}
