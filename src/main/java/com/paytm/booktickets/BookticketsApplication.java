package com.paytm.booktickets;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BookticketsApplication {

	public static void main(String[] args) {
		SpringApplication.run(BookticketsApplication.class, args);
	}

}
