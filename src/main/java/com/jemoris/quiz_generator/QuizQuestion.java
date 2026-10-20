package com.jemoris.quiz_generator;

import java.util.List;

public record QuizQuestion(
        String question,
        List<String> options,
        List<Integer> correctAnswers,
        String explanation) {
}
