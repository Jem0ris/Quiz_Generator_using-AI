package com.jemoris.quiz_generator;

import java.util.List;

public record QuizDocument(
        String id,
        String title,
        String description,
        Integer durationMinutes,
        List<QuizQuestion> questions) {
}
