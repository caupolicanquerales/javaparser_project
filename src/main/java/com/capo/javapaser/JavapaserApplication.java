package com.capo.javapaser;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.capo.javapaser.service.JavaParserService;

@SpringBootApplication
public class JavapaserApplication implements CommandLineRunner{
	
	private final JavaParserService javaParser;

	public JavapaserApplication(JavaParserService javaParser) {
		this.javaParser= javaParser;
	}
	
	public static void main(String[] args) {
		SpringApplication.run(JavapaserApplication.class, args);
	}

	@Override
	public void run(String... args) throws Exception {
		
		if (args.length < 2) {
            System.err.println("Usage: java -jar business-extractor.jar <source-directory> <output-json-path>");
            return;
        }
		javaParser.runJavaParser(args);
	}

}
