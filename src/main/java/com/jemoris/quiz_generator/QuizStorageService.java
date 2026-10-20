package com.jemoris.quiz_generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class QuizStorageService {
    private final ObjectMapper objectMapper;
    private final Path storageDirectory;

    public QuizStorageService(
            ObjectMapper objectMapper,
            @Value("${quiz.storage.directory:data/quizzes}") String storageDirectory) {
        this.objectMapper = objectMapper;
        this.storageDirectory = Path.of(storageDirectory);
    }

    public synchronized List<QuizSummary> listQuizzes() {
        ensureStorageDirectory();
        try (Stream<Path> files = Files.list(storageDirectory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::readQuiz)
                    .map(this::toSummary)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to list saved quizzes", e);
        }
    }

    public synchronized QuizDocument getQuiz(String id) {
        Path file = quizPath(id);
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Quiz not found");
        }
        return readQuiz(file);
    }

    public synchronized QuizSummary saveQuiz(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Paste quiz JSON into the editor.");
        }
        QuizDocument uploadedQuiz;
        try {
            uploadedQuiz = parseQuiz(objectMapper.readTree(json));
        } catch (IOException e) {
            throw new IllegalArgumentException("The editor contents are not valid JSON.", e);
        }

        String id = uploadedQuiz.id();
        boolean updating = id != null && !id.isBlank();
        if (updating) {
            Path existing = quizPath(id);
            if (!Files.isRegularFile(existing)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "The quiz ID in this JSON does not match a saved quiz.");
            }
        } else {
            id = UUID.randomUUID().toString();
        }

        QuizDocument quiz = new QuizDocument(id, uploadedQuiz.title(),
                uploadedQuiz.description(), uploadedQuiz.durationMinutes(), uploadedQuiz.questions());
        writeQuiz(quiz);
        return toSummary(quiz);
    }

    public synchronized void deleteQuiz(String id) {
        Path file = quizPath(id);
        try {
            if (!Files.deleteIfExists(file)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Quiz not found");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to delete quiz", e);
        }
    }

    private QuizDocument parseQuiz(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("The JSON file must contain a quiz object.");
        }

        String id = optionalText(root, "id");
        String title = requiredText(root, "title", "Quiz title is required.");
        String description = optionalText(root, "description");
        Integer durationMinutes = parseDuration(root);
        JsonNode questionsNode = root.get("questions");
        if (questionsNode == null || !questionsNode.isArray() || questionsNode.isEmpty()) {
            throw new IllegalArgumentException("Add at least one question to the quiz.");
        }

        List<QuizQuestion> questions = new ArrayList<>();
        for (int questionIndex = 0; questionIndex < questionsNode.size(); questionIndex++) {
            JsonNode questionNode = questionsNode.get(questionIndex);
            String prefix = "Question " + (questionIndex + 1) + ": ";
            if (!questionNode.isObject()) {
                throw new IllegalArgumentException(prefix + "must be an object.");
            }

            String question = requiredText(questionNode, "question", prefix + "text is required.");
            List<String> options = new ArrayList<>();
            List<Integer> correctAnswers;
            JsonNode answersNode = questionNode.get("answers");
            if (answersNode != null) {
                if (!answersNode.isArray() || answersNode.size() < 2) {
                    throw new IllegalArgumentException(prefix + "provide at least two answers.");
                }
                List<Integer> correctAnswerIndexes = new ArrayList<>();
                for (int answerIndex = 0; answerIndex < answersNode.size(); answerIndex++) {
                    JsonNode answer = answersNode.get(answerIndex);
                    if (!answer.isObject()) {
                        throw new IllegalArgumentException(prefix + "each answer must be an object.");
                    }
                    String option = requiredText(answer, "option",
                            prefix + "each answer needs option text.");
                    JsonNode isCorrect = answer.get("is_correct");
                    if (isCorrect == null || !isCorrect.isBoolean()) {
                        throw new IllegalArgumentException(prefix
                                + "each answer needs a true or false is_correct value.");
                    }
                    options.add(option);
                    if (isCorrect.asBoolean()) {
                        correctAnswerIndexes.add(answerIndex);
                    }
                }
                correctAnswers = validateCorrectAnswers(correctAnswerIndexes, options.size(), prefix);
            } else {
                JsonNode optionsNode = questionNode.get("options");
                if (optionsNode == null || !optionsNode.isArray() || optionsNode.size() < 2) {
                    throw new IllegalArgumentException(prefix + "provide at least two options.");
                }
                for (JsonNode option : optionsNode) {
                    if (!option.isTextual() || option.asText().isBlank()) {
                        throw new IllegalArgumentException(prefix
                                + "options must be non-empty strings.");
                    }
                    options.add(option.asText().trim());
                }
                correctAnswers = parseCorrectAnswers(questionNode, options.size(), prefix);
            }

            String explanation = requiredText(questionNode, "explanation",
                    prefix + "an explanation is required.");
            questions.add(new QuizQuestion(question, List.copyOf(options),
                    correctAnswers, explanation));
        }

        return new QuizDocument(id, title, description, durationMinutes, List.copyOf(questions));
    }

    private Integer parseDuration(JsonNode root) {
        JsonNode duration = root.get("durationMinutes");
        if (duration == null || duration.isNull()) {
            return null;
        }
        if (!duration.isIntegralNumber() || !duration.canConvertToInt() || duration.asInt() < 1) {
            throw new IllegalArgumentException("Quiz duration must be a positive number of minutes.");
        }
        return duration.asInt();
    }

    private List<Integer> parseCorrectAnswers(JsonNode question, int optionCount, String prefix) {
        JsonNode answers = question.has("correctAnswers")
                ? question.get("correctAnswers")
                : question.get("correctAnswer");
        if (answers == null) {
            throw new IllegalArgumentException(prefix + "provide correctAnswers.");
        }

        List<Integer> indices = new ArrayList<>();
        if (answers.isIntegralNumber()) {
            indices.add(answers.asInt());
        } else if (answers.isArray()) {
            for (JsonNode answer : answers) {
                if (!answer.isIntegralNumber()) {
                    throw new IllegalArgumentException(prefix + "correct answer indexes must be integers.");
                }
                indices.add(answer.asInt());
            }
        } else {
            throw new IllegalArgumentException(prefix
                    + "correctAnswers must be an integer or an array of integers.");
        }

        return validateCorrectAnswers(indices, optionCount, prefix);
    }

    private List<Integer> validateCorrectAnswers(
            List<Integer> indices, int optionCount, String prefix) {
        if (indices.isEmpty()) {
            throw new IllegalArgumentException(prefix + "select at least one correct answer.");
        }
        Set<Integer> uniqueIndices = new HashSet<>();
        for (int index : indices) {
            if (index < 0 || index >= optionCount || !uniqueIndices.add(index)) {
                throw new IllegalArgumentException(prefix
                        + "correct answer indexes must be unique and match an option.");
            }
        }
        indices.sort(Integer::compareTo);
        return List.copyOf(indices);
    }

    private String requiredText(JsonNode object, String field, String message) {
        String value = optionalText(object, field);
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private String optionalText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }

    private QuizSummary toSummary(QuizDocument quiz) {
        return new QuizSummary(quiz.id(), quiz.title(), quiz.description(),
                quiz.durationMinutes(), quiz.questions().size());
    }

    private Path quizPath(String id) {
        try {
            UUID parsedId = UUID.fromString(id);
            if (!parsedId.toString().equalsIgnoreCase(id)) {
                throw new IllegalArgumentException();
            }
            return storageDirectory.resolve(parsedId + ".json");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Quiz not found");
        }
    }

    private QuizDocument readQuiz(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), QuizDocument.class);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read saved quiz: " + file.getFileName(), e);
        }
    }

    private void writeQuiz(QuizDocument quiz) {
        ensureStorageDirectory();
        Path destination = quizPath(quiz.id());
        Path temporaryFile = null;
        try {
            temporaryFile = Files.createTempFile(storageDirectory, "quiz-", ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporaryFile.toFile(), quiz);
            try {
                Files.move(temporaryFile, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryFile, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save quiz", e);
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException e) {
                    throw new IllegalStateException("Unable to clean up temporary quiz file", e);
                }
            }
        }
    }

    private void ensureStorageDirectory() {
        try {
            Files.createDirectories(storageDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create quiz storage directory", e);
        }
    }
}
