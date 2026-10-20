package com.jemoris.quiz_generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class QuizGeneratorApplication {

	public static void main(String[] args) {
		SpringApplication.run(QuizGeneratorApplication.class, args);
	}

	@Bean
	ObjectMapper objectMapper() {
		return new ObjectMapper();
	}
}
