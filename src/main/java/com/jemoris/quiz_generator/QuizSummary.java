package com.jemoris.quiz_generator;

public record QuizSummary(
        String id,
        String title,
        String description,
        Integer durationMinutes,
        int questionCount) {
}
