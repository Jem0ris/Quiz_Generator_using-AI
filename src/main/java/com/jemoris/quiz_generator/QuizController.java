package com.jemoris.quiz_generator;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@RestController
@RequestMapping("/api/quizzes")
public class QuizController {
    private final QuizStorageService quizStorage;

    public QuizController(QuizStorageService quizStorage) {
        this.quizStorage = quizStorage;
    }

    @GetMapping
    public List<QuizSummary> listQuizzes() {
        return quizStorage.listQuizzes();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public QuizSummary saveQuiz(@RequestBody String json) {
        return quizStorage.saveQuiz(json);
    }

    @GetMapping("/{id}")
    public QuizView getQuiz(@PathVariable String id) {
        QuizDocument quiz = quizStorage.getQuiz(id);
        List<QuestionView> questions = quiz.questions().stream()
                .map(question -> new QuestionView(question.question(), question.options(),
                        question.correctAnswers().size() > 1))
                .toList();
        return new QuizView(quiz.id(), quiz.title(), quiz.description(),
                quiz.durationMinutes(), questions);
    }

    @GetMapping("/{id}/edit")
    public QuizDocument getQuizForEditing(@PathVariable String id) {
        return quizStorage.getQuiz(id);
    }

    @PostMapping("/{id}/submit")
    public QuizResult submitQuiz(
            @PathVariable String id,
            @RequestBody QuizSubmission submission) {
        QuizDocument quiz = quizStorage.getQuiz(id);
        Map<Integer, List<Integer>> answers = submission == null || submission.answers() == null
                ? Map.of()
                : submission.answers();

        List<QuestionResult> results = new ArrayList<>();
        int score = 0;
        for (int questionIndex = 0; questionIndex < quiz.questions().size(); questionIndex++) {
            QuizQuestion question = quiz.questions().get(questionIndex);
            List<Integer> selected = answers.getOrDefault(questionIndex, List.of());
            if (selected == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Submitted answers must be arrays of option indexes.");
            }
            TreeSet<Integer> selectedSet = new TreeSet<>();
            for (Integer optionIndex : selected) {
                if (optionIndex == null || optionIndex < 0 || optionIndex >= question.options().size()
                        || !selectedSet.add(optionIndex)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Submitted answers contain an invalid option.");
                }
            }

            boolean correct = selectedSet.equals(new TreeSet<>(question.correctAnswers()));
            if (correct) {
                score++;
            }
            results.add(new QuestionResult(question.correctAnswers(),
                    List.copyOf(selectedSet), correct, question.explanation()));
        }
        for (Integer questionIndex : answers.keySet()) {
            if (questionIndex == null || questionIndex < 0
                    || questionIndex >= quiz.questions().size()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Submitted answers contain an invalid question.");
            }
        }
        return new QuizResult(score, quiz.questions().size(), results);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteQuiz(@PathVariable String id) {
        quizStorage.deleteQuiz(id);
        return ResponseEntity.noContent().build();
    }

    public record QuizView(
            String id,
            String title,
            String description,
            Integer durationMinutes,
            List<QuestionView> questions) {
    }

    public record QuestionView(String question, List<String> options, boolean multipleAnswers) {
    }

    public record QuizSubmission(Map<Integer, List<Integer>> answers) {
    }

    public record QuestionResult(
            List<Integer> correctAnswers,
            List<Integer> selectedAnswers,
            boolean correct,
            String explanation) {
    }

    public record QuizResult(int score, int total, List<QuestionResult> results) {
    }

}
