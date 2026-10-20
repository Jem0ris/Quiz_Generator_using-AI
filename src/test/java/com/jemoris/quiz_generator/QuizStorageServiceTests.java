package com.jemoris.quiz_generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuizStorageServiceTests {
    @TempDir
    Path temporaryDirectory;

    private QuizStorageService storage;

    @BeforeEach
    void setUp() {
        storage = new QuizStorageService(new ObjectMapper(), temporaryDirectory.toString());
    }

    @Test
    void savesAndListsQuizzesWithSingleAndMultipleAnswers() {
        QuizSummary saved = storage.saveQuiz("""
                {
                  "title": "Science",
                  "description": "Basics",
                  "durationMinutes": 15,
                  "questions": [
                    {
                      "question": "Pick the elements",
                      "options": ["Oxygen", "Water", "Iron"],
                      "correctAnswers": [0, 2],
                      "explanation": "Oxygen and iron are elements."
                    },
                    {
                      "question": "How many planets?",
                      "options": ["Eight", "Nine"],
                      "correctAnswer": 0,
                      "explanation": "There are eight recognized planets."
                    }
                  ]
                }
                """);

        assertEquals("Science", saved.title());
        assertEquals(2, saved.questionCount());
        assertEquals(15, saved.durationMinutes());
        assertEquals(List.of(saved), storage.listQuizzes());
        assertEquals(List.of(0, 2), storage.getQuiz(saved.id()).questions().getFirst().correctAnswers());
        assertEquals(List.of(0), storage.getQuiz(saved.id()).questions().get(1).correctAnswers());
    }

    @Test
    void pastedQuizWithSavedIdReplacesExistingQuiz() {
        QuizSummary original = storage.saveQuiz("""
                {"title":"Old","questions":[{"question":"Q","options":["A","B"],
                "correctAnswer":0,"explanation":"Because."}]}
                """);

        QuizSummary updated = storage.saveQuiz("""
                {"id":"%s","title":"New","questions":[{"question":"Q2","options":["A","B"],
                "correctAnswer":1,"explanation":"Updated."}]}
                """.formatted(original.id()));

        assertEquals(original.id(), updated.id());
        assertEquals("New", storage.getQuiz(original.id()).title());
    }

    @Test
    void acceptsQuestionArrayWithAnswerObjectsAndCorrectFlags() {
        QuizSummary saved = storage.saveQuiz("""
                {
                  "title": "DevSecOps",
                  "questions": [
                    {
                      "question": "Which approach best describes DevOps?",
                      "answers": [
                        {"option": "A final security testing tool", "is_correct": false},
                        {"option": "Independent teams with sequential work", "is_correct": false},
                        {"option": "Development and Operations collaborate", "is_correct": true},
                        {"option": "Operations teams are removed", "is_correct": false},
                        {"option": "Security approves every line", "is_correct": false}
                      ],
                      "explanation": "DevOps brings Development and Operations together."
                    },
                    {
                      "question": "Select the correct parts.",
                      "answers": [
                        {"option": "One", "is_correct": true},
                        {"option": "Two", "is_correct": false},
                        {"option": "Three", "is_correct": true},
                        {"option": "Four", "is_correct": false},
                        {"option": "Five", "is_correct": false}
                      ],
                      "explanation": "One and three are correct."
                    }
                  ]
                }
                """);

        assertEquals(2, saved.questionCount());
        assertEquals(List.of("A final security testing tool",
                        "Independent teams with sequential work",
                        "Development and Operations collaborate",
                        "Operations teams are removed", "Security approves every line"),
                storage.getQuiz(saved.id()).questions().getFirst().options());
        assertEquals(List.of(2), storage.getQuiz(saved.id()).questions().getFirst().correctAnswers());
        assertEquals(List.of(0, 2), storage.getQuiz(saved.id()).questions().get(1).correctAnswers());
    }

    @Test
    void rejectsOutOfRangeCorrectAnswer() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> storage.saveQuiz("""
                        {"title":"Bad","questions":[{"question":"Q","options":["A","B"],
                        "correctAnswer":2,"explanation":"Because."}]}
                        """));

        assertEquals("Question 1: correct answer indexes must be unique and match an option.",
                exception.getMessage());
    }

    @Test
    void rejectsUnknownQuizIdWhenUpdating() {
        assertThrows(ResponseStatusException.class, () -> storage.saveQuiz("""
                {"id":"00000000-0000-0000-0000-000000000001","title":"Missing",
                "questions":[{"question":"Q","options":["A","B"],"correctAnswer":0,
                "explanation":"Because."}]}
                """));
    }

    @Test
    void quizViewHidesAnswersUntilSubmission() {
        QuizSummary saved = storage.saveQuiz("""
                {"title":"Review","durationMinutes":2,"questions":[{"question":"Pick both","options":["A","B","C"],
                "correctAnswers":[0,2],"explanation":"A and C are correct."}]}
                """);
        QuizController controller = new QuizController(storage);

        QuizController.QuizView view = controller.getQuiz(saved.id());
        assertEquals(2, view.durationMinutes());
        assertTrue(view.questions().getFirst().multipleAnswers());
        assertEquals(List.of("A", "B", "C"), view.questions().getFirst().options());

        QuizController.QuizResult result = controller.submitQuiz(saved.id(),
                new QuizController.QuizSubmission(Map.of(0, List.of(0, 2))));
        assertEquals(1, result.score());
        assertEquals(List.of(0, 2), result.results().getFirst().correctAnswers());
        assertEquals("A and C are correct.", result.results().getFirst().explanation());
    }

    @Test
    void rejectsNonPositiveQuizDuration() {
        assertThrows(IllegalArgumentException.class, () -> storage.saveQuiz("""
                {"title":"Timed","durationMinutes":0,"questions":[{"question":"Q",
                "options":["A","B"],"correctAnswer":0,"explanation":"Because."}]}
                """));
    }
}
